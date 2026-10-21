(ns chequer.test-helpers
  (:require
    [clojure.java.io :as io]))


(defn temp-db-path
  []
  (str (System/getProperty "java.io.tmpdir") "/chequer-test-" (System/nanoTime) ".db"))


(defn delete-db-files
  "Delete the SQLite file at path along with its WAL, shm and backup siblings."
  [path]
  (let [f (io/file path)
        prefix (.getName f)]
    (doseq [sibling (.listFiles (io/file (or (.getParent f) ".")))
            :when (.startsWith (.getName sibling) prefix)]
      (.delete sibling))))
