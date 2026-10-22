(ns chequer.repl
  (:require
    [clojure.java.io :as io]
    [clojure.repl :refer :all]
    [clojure.string :as str]
    [malli.core :as m]
    [malli.error :as me]
    [next.jdbc :as jdbc]
    [chequer.config :as config]
    [chequer.core :as core]
    [tick.core :as t])
  (:import
    (java.time
      Instant
      LocalDate)))


(def system
  nil)


(defn start
  "Start a new system if one is not already running."
  []
  (when-not system
    (let [config-path (or (System/getenv "CHEQUER_CONFIG") "dev/config.ini")
          config (config/load-config config-path)
          sys (core/start! config)]
      (alter-var-root #'system (constantly sys))
      :started)))


(defn stop
  "Stop the running system."
  []
  (when system
    (core/stop! system)
    (alter-var-root #'system (constantly nil))
    :stopped))


(defn reset
  "Stop the running system, reload changed code, and restart it."
  []
  (when system
    (println "Stopping...")
    (stop))
  (println "Reloading...")
  ;; TODO: do real transitive loading here...
  (require 'chequer.core :reload)
  (println "Starting...")
  (#'start))
