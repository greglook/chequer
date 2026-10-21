(ns chequer.main
  "Command line entry point: argument parsing, config loading and process lifecycle."
  (:require
    [chequer.config :as config]
    [chequer.core :as core]
    [clojure.string :as str]
    [clojure.tools.cli :as cli]
    [jolt.host :as host]))


(def ^:private cli-options
  "tools.cli option specs."
  [["-c" "--config PATH" "Path to the ini configuration file"]
   ["-h" "--help" "Show this help"]])


(defn parse-args
  "Parse command line args into {:config path}, {:help usage} or {:errors [...]}."
  [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)
        errors (into (vec errors) (map #(str "Unexpected argument: " %)) arguments)]
    (cond
      (:help options) {:help (str "Usage: chequer [-c PATH]\n\n" summary)}
      (seq errors) {:errors errors}
      :else (select-keys options [:config]))))


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


(defn -main
  "Start Chequer from the command line args and run until interrupted."
  [& args]
  (let [{:keys [help errors] :as parsed} (parse-args args)]
    (cond
      help (println help)
      errors (apply fail errors)
      :else
      (let [path (config-path parsed)
            cfg (try
                  (config/load-config path)
                  (catch Exception e
                    (fail (str "Cannot load configuration " path ": " (ex-message e)))))
            {:keys [host port]} (get-in cfg [:server :listen])
            sys (core/start! cfg)]
        (println (str "Chequer listening on " host ":" port))
        (host/block-sigint)
        (host/park-until-interrupt)
        (core/stop! sys)))))
