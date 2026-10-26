(ns chequer.main
  "Command line entry point: subcommands (server, validate, migrate, backup), config loading and process lifecycle."
  (:require
    [chequer.auth :as auth]
    [chequer.config :as config]
    [chequer.core :as core]
    [chequer.db :as db]
    [chequer.oidc :as oidc]
    [chequer.time :as time]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.cli :as cli]
    [clojure.tools.logging :as log]
    [jolt.host :as host]))


(def ^:private cli-options
  "tools.cli option specs."
  [["-c" "--config PATH" "Path to the ini configuration file"]
   ["-h" "--help" "Show this help"]])


(def ^:private commands
  "Subcommands: argument usage and description."
  {"server" {:args "" :doc "Run the Chequer server"}
   "validate" {:args "" :doc "Check that the server is ready to run, reporting each check"}
   "migrate" {:args "" :doc "Apply pending database migrations, reporting which were applied"}
   "backup" {:args " DEST" :doc "Write a backup of the database to the file DEST"}})


(defn- usage
  "The help text, with the tools.cli option summary."
  [summary]
  (str "Usage: chequer [-c PATH] COMMAND\n\nCommands:\n"
       (str/join "\n" (for [[command {:keys [args doc]}] (sort commands)]
                        (str "  " command args "\n      " doc)))
       "\n\nOptions:\n" summary))


(defn parse-args
  "Parse command line args into {:command :server|:validate|:migrate|:backup :config path :dest path},
  {:help usage} or {:errors [...]}."
  [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)
        [command & more] arguments
        expected (if (= "backup" command) 1 0)]
    (cond
      (:help options) {:help (usage summary)}
      (seq errors) {:errors errors}
      (nil? command) {:errors [(str "Missing command (one of: " (str/join ", " (sort (keys commands))) ")")]}
      (not (contains? commands command)) {:errors [(str "Unknown command: " command)]}
      (< (count more) expected) {:errors [(str "Usage: chequer " command (:args (commands command)))]}
      (> (count more) expected) {:errors (mapv #(str "Unexpected argument: " %) (drop expected more))}
      :else (cond-> (assoc (select-keys options [:config]) :command (keyword command))
              (= "backup" command) (assoc :dest (first more))))))


(defn- config-path
  "Resolve the config path: --config, then CHEQUER_CONFIG, then config.ini."
  [{:keys [config]}]
  (or config (System/getenv "CHEQUER_CONFIG") "config.ini"))


(defn- fail
  "Print lines to stderr and exit with status 1."
  [& lines]
  (binding [*out* *err*]
    (println (str/join "\n" lines)))
  (System/exit 1))


(defn- load-cfg
  "Load the configuration named by the parsed args, or exit with an error."
  [parsed]
  (let [path (config-path parsed)]
    (try
      (config/load-config path)
      (catch Exception e
        (fail (str "Cannot load configuration " path ": " (ex-message e)))))))


(defn- run-server
  "Start the server and run until interrupted. The process exits on SIGINT or SIGTERM
  without returning from the park, so the system is stopped by a shutdown hook."
  [cfg]
  (let [{:keys [host port]} (get-in cfg [:server :listen])
        sys (try
              (core/start! cfg)
              (catch Exception e
                (fail (str "Cannot start server: " (ex-message e)))))]
    (host/add-shutdown-hook
      (fn stop-system
        []
        (log/info "Chequer shutting down")
        (core/stop! sys)))
    (log/info (str "Chequer listening on " host ":" port))
    (host/block-sigint)
    (host/park-until-interrupt)))


(defn- check-result
  "A check result {:name :ok? :message} for the check named check-name."
  [check-name ok? message]
  {:name check-name :ok? ok? :message message})


(defn check-config
  "Load and validate the config file at path. Returns a check result, with the
  loaded :config when it succeeds."
  [path]
  (try
    (assoc (check-result "config" true (str "Loaded " path))
           :config (config/load-config path))
    (catch Exception e
      (check-result "config" false (str "Cannot load " path ": " (ex-message e))))))


(defn check-database
  "Check the database file at path: it must be a writable regular file, or
  absent so it can be created, in a writable directory where SQLite keeps its
  WAL files and migration backups."
  [path]
  (let [file (.getAbsoluteFile (io/file path))
        dir (.getParentFile file)
        result (partial check-result "database")]
    (cond
      (and (.exists file) (not (.isFile file)))
      (result false (str "Database " path " is not a regular file"))

      (and (.isFile file) (not (.canWrite file)))
      (result false (str "Database " path " is not writable"))

      (not (.isDirectory dir))
      (result false (str "Database directory " dir " does not exist"))

      (not (.canWrite dir))
      (result false (str "Database directory " dir " is not writable, but SQLite"
                         " needs it for WAL files and migration backups"))

      (.isFile file)
      (result true (str "Database " path " exists and is writable"))

      :else
      (result true (str "Database " path " will be created in " dir)))))


(defn check-schema
  "Check the schema version of the existing database file at path against the
  migrations: older is fine, as pending migrations are applied on startup,
  unless their backup file is already in the way; newer is not."
  [path]
  (let [result (partial check-result "schema")
        latest db/latest-version]
    (try
      (let [version (db/schema-version path)
            pending (count (filter #(< version %) (db/migration-versions)))
            backup (db/migration-backup-path path latest)]
        (cond
          (< latest version)
          (result false (str "Database is at schema v" version
                             ", newer than this binary's v" latest))

          (zero? pending)
          (result true (str "Database is up to date at schema v" version))

          (and (< 0 version) (.exists (io/file backup)))
          (result false (str "Database is at schema v" version " with " pending
                             " pending migration(s), but backup " backup
                             " already exists and would stop them"))

          :else
          (result true (str "Database is at schema v" version "; " pending
                            " pending migration(s) to v" latest " will be applied on startup"))))
      (catch Exception e
        (result false (str "Cannot read schema version of " path ": " (ex-message e)))))))


(defn check-time-zone
  "Check that zone-id names a time zone the runtime knows."
  [zone-id]
  (if (time/valid-zone? zone-id)
    (check-result "time-zone" true (str "Time zone " zone-id " is known"))
    (check-result "time-zone" false (str "Unknown [server] time-zone: " zone-id))))


(defn check-trusted-proxies
  "Check that every trusted proxy is an IP or CIDR."
  [cidrs]
  (if-let [invalid (seq (remove auth/valid-cidr? cidrs))]
    (check-result "trusted-proxies" false (str "Invalid [auth.proxy] trusted-proxies (expected IPs or CIDRs): "
                                               (str/join ", " invalid)))
    (check-result "trusted-proxies" true (str "Trusting " (str/join ", " cidrs)))))


(defn check-session-key
  "Check that the OIDC config has a usable session-key."
  [oidc-cfg]
  (if-let [problem (oidc/session-key-problem oidc-cfg)]
    (check-result "session-key" false (str problem "; generate one with: openssl rand -hex 32"))
    (check-result "session-key" true "Session key is usable")))


(defn check-issuer
  "Check that the discovery document of the OIDC issuer can be fetched."
  [issuer]
  (try
    (oidc/discover-issuer issuer)
    (check-result "issuer" true (str "Discovered OIDC issuer " issuer))
    (catch Exception e
      (check-result "issuer" false (str "Cannot discover OIDC issuer " issuer ": " (ex-message e))))))


(defn config-checks
  "Run the readiness checks of the loaded config cfg, returning their results."
  [cfg]
  (let [db-path (get-in cfg [:database :path])
        db-check (check-database db-path)
        {:keys [mode] proxy-cfg :proxy oidc-cfg :oidc} (:auth cfg)]
    (cond-> [db-check]
      (and (:ok? db-check) (.isFile (io/file db-path))) (conj (check-schema db-path))
      true (conj (check-time-zone (get-in cfg [:server :time-zone])))
      (= :proxy mode) (conj (check-trusted-proxies (:trusted-proxies proxy-cfg)))
      (= :oidc mode) (conj (check-session-key oidc-cfg)
                           (check-issuer (:issuer oidc-cfg))))))


(defn- print-check
  "Print the outcome of a check result."
  [{check-name :name :keys [ok? message]}]
  (println (str (if ok? "[ OK ] " "[FAIL] ") check-name ": " message)))


(defn- run-validate
  "Run each readiness check, printing its outcome, and exit with status 1 if any fail.
  The config is checked first, as every other check needs it."
  [parsed]
  (let [{cfg :config :as config-check} (check-config (config-path parsed))
        checks (cond-> [config-check]
                 cfg (into (config-checks cfg)))]
    (run! print-check checks)
    (if (every? :ok? checks)
      (println "Ready to run")
      (fail "Not ready to run"))))


(defn- run-migrate
  "Apply pending migrations to the configured database and report them."
  [cfg]
  (let [path (get-in cfg [:database :path])
        d (db/open path)]
    (try
      (let [applied (db/migrate! d)]
        (if (seq applied)
          (do (println (str "Applied " (count applied) " migration(s) to " path ":"))
              (doseq [{:keys [file]} applied]
                (println (str "  " file))))
          (println (str "Database " path " is up to date"))))
      (catch Exception e
        (fail (str "Migration failed: " (ex-message e))))
      (finally (db/close! d)))))


(defn- run-backup
  "Write a backup of the configured database to dest."
  [cfg dest]
  (let [path (get-in cfg [:database :path])]
    (when-not (.exists (io/file path))
      (fail (str "Database not found: " path)))
    (let [d (db/open path)]
      (try
        (db/backup! d dest)
        (println (str "Backed up " path " to " dest))
        (catch Exception e
          (fail (str "Backup failed: " (ex-message e))))
        (finally (db/close! d))))))


(defn -main
  "Run the subcommand named by the command line args."
  [& args]
  (let [{:keys [help errors command dest] :as parsed} (parse-args args)]
    (cond
      help (println help)
      errors (apply fail errors)
      (= :validate command) (run-validate parsed)
      :else
      (let [cfg (load-cfg parsed)]
        (case command
          :server (run-server cfg)
          :migrate (run-migrate cfg)
          :backup (run-backup cfg dest))))))
