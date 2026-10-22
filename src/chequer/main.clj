(ns chequer.main
  "Command line entry point: subcommands (server, migrate, backup), config loading and process lifecycle."
  (:require
    [chequer.config :as config]
    [chequer.core :as core]
    [chequer.db :as db]
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
  "Parse command line args into {:command :server|:migrate|:backup :config path :dest path},
  {:help usage} or {:errors [...]}."
  [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)
        [command & more] arguments
        expected (if (= "backup" command) 1 0)]
    (cond
      (:help options) {:help (usage summary)}
      (seq errors) {:errors errors}
      (nil? command) {:errors ["Missing command (one of: server, migrate, backup)"]}
      (not (contains? commands command)) {:errors [(str "Unknown command: " command)]}
      (< (count more) expected) {:errors [(str "Usage: chequer " command (:args (commands command)))]}
      (> (count more) expected) {:errors (mapv #(str "Unexpected argument: " %) (drop expected more))}
      :else (cond-> (assoc (select-keys options [:config]) :command (keyword command))
              (= "backup" command) (assoc :dest (first more))))))


(defn- config-path
  "Resolve the config path: --config, then CHEQUER_CONFIG, then chequer.ini."
  [{:keys [config]}]
  (or config (System/getenv "CHEQUER_CONFIG") "chequer.ini"))


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
  "Start the server and run until interrupted."
  [cfg]
  (let [{:keys [host port]} (get-in cfg [:server :listen])
        sys (core/start! cfg)]
    (log/info (str "Chequer listening on " host ":" port))
    (host/block-sigint)
    (host/park-until-interrupt)
    (core/stop! sys)))


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
      :else
      (let [cfg (load-cfg parsed)]
        (case command
          :server (run-server cfg)
          :migrate (run-migrate cfg)
          :backup (run-backup cfg dest))))))
