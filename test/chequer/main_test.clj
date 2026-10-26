(ns chequer.main-test
  (:require
    [chequer.config :as config]
    [chequer.db :as db]
    [chequer.main :as main]
    [chequer.test-helpers :as th]
    [clojure.test :refer [deftest is]]
    [jdbc.core :as jdbc]
    [jolt.fs :as fs]))


(deftest parse-args-subcommands
  (is (= {:command :server} (main/parse-args ["server"])))
  (is (= {:command :validate} (main/parse-args ["validate"])))
  (is (= {:command :migrate} (main/parse-args ["migrate"])))
  (is (= {:command :backup :dest "out.db"} (main/parse-args ["backup" "out.db"]))))


(deftest parse-args-config-option
  (is (= {:command :server :config "a.ini"} (main/parse-args ["-c" "a.ini" "server"])))
  (is (= {:command :server :config "b.ini"} (main/parse-args ["server" "--config=b.ini"])))
  (is (= {:command :backup :config "a.ini" :dest "out.db"}
         (main/parse-args ["backup" "out.db" "-c" "a.ini"]))))


(deftest parse-args-help
  (is (string? (:help (main/parse-args ["-h"]))))
  (is (string? (:help (main/parse-args ["server" "-h"]))))
  (is (re-find #"backup" (:help (main/parse-args ["-h"])))))


(deftest parse-args-errors
  (is (seq (:errors (main/parse-args []))) "a subcommand is required")
  (is (seq (:errors (main/parse-args ["-c" "a.ini"]))))
  (is (seq (:errors (main/parse-args ["bogus"]))))
  (is (seq (:errors (main/parse-args ["--bogus" "server"]))))
  (is (seq (:errors (main/parse-args ["server" "stray"]))))
  (is (seq (:errors (main/parse-args ["migrate" "stray"]))))
  (is (seq (:errors (main/parse-args ["validate" "stray"]))))
  (is (seq (:errors (main/parse-args ["backup"]))) "backup needs a destination")
  (is (seq (:errors (main/parse-args ["backup" "a" "b"])))))


(deftest check-config-results
  (let [path (str (fs/temp-dir) "/chequer-main-test-" (System/nanoTime) ".ini")]
    (try
      (spit path "[server]\nlisten = :8080\n")
      (let [{:keys [ok? config]} (main/check-config path)]
        (is ok?)
        (is (= 8080 (get-in config [:server :listen :port]))))
      (spit path "[auth]\nmode = proxy\n")
      (is (not (:ok? (main/check-config path))) "proxy mode needs a header")
      (finally (fs/delete-if-exists path))))
  (is (not (:ok? (main/check-config "/nonexistent/config.ini")))))


(deftest check-database-results
  (let [path (th/temp-db-path)]
    (is (:ok? (main/check-database path)) "absent from a writable directory")
    (try
      (spit path "")
      (is (:ok? (main/check-database path)) "a writable regular file")
      (finally (fs/delete-if-exists path))))
  (is (not (:ok? (main/check-database (fs/temp-dir)))) "a directory")
  (is (not (:ok? (main/check-database "/nonexistent/chequer.db"))) "a missing directory"))


(deftest check-database-needs-a-writable-directory
  (let [dir (fs/create-temp-dir {:prefix "chequer-main-test-"})
        path (str dir "/chequer.db")]
    (try
      (spit path "")
      (fs/set-posix-file-permissions dir "r-xr-xr-x")
      (when-not (fs/writable? dir) ; root can write anyway
        (is (re-find #"not writable" (:message (main/check-database path)))))
      (finally
        (fs/set-posix-file-permissions dir "rwx------")
        (fs/delete-tree dir)))))


(defn- set-version!
  "Set the schema version of the database file at path."
  [path version]
  (let [conn (jdbc/connection (str "sqlite:" path))]
    (try
      (jdbc/execute! conn (str "PRAGMA user_version = " version))
      (finally (.close conn)))))


(deftest check-schema-results
  (th/with-test-db
    (fn [{:keys [path]}]
      (is (re-find #"up to date" (:message (main/check-schema path))))
      (set-version! path 1)
      (is (re-find #"pending" (:message (main/check-schema path))))
      (is (:ok? (main/check-schema path)) "pending migrations run on startup")
      (spit (db/migration-backup-path path db/latest-version) "")
      (is (not (:ok? (main/check-schema path))) "a leftover backup would stop the migration")
      (set-version! path (inc db/latest-version))
      (is (not (:ok? (main/check-schema path))) "newer than the binary")))
  (let [path (th/temp-db-path)]
    (try
      (spit path "not a database, but long enough that SQLite reads a header from it")
      (is (not (:ok? (main/check-schema path))))
      (finally (th/delete-db-files path)))))


(deftest check-settings-results
  (is (:ok? (main/check-time-zone "Europe/London")))
  (is (not (:ok? (main/check-time-zone "Mars/Olympus_Mons"))))
  (is (:ok? (main/check-trusted-proxies ["127.0.0.1" "10.0.0.0/8"])))
  (is (re-find #"bogus" (:message (main/check-trusted-proxies ["127.0.0.1" "bogus"]))))
  (is (:ok? (main/check-session-key {:session-key (apply str (repeat 32 "a5"))})))
  (is (not (:ok? (main/check-session-key {:session-key "abc"}))))
  (is (not (:ok? (main/check-issuer "http://127.0.0.1:1"))) "nothing listens on port 1"))


(deftest config-checks-depend-on-the-mode
  (let [check-names (fn [ini]
                      (let [db-path (th/temp-db-path)]
                        (mapv :name (main/config-checks
                                      (config/normalize (assoc ini "database" {"path" db-path}))))))]
    (is (= ["database" "time-zone"] (check-names {})))
    (is (= ["database" "time-zone" "trusted-proxies"]
           (check-names {"auth" {"mode" "proxy"} "auth.proxy" {"header" "X-User"}})))
    (is (= ["database" "time-zone" "session-key" "issuer"]
           (check-names {"auth" {"mode" "oidc"} "auth.oidc" {"issuer" "http://127.0.0.1:1"}}))))
  (th/with-test-db
    (fn [{:keys [path]}]
      (is (= ["database" "schema" "time-zone"]
             (mapv :name (main/config-checks (config/normalize {"database" {"path" path}}))))
          "an existing database has its schema checked"))))
