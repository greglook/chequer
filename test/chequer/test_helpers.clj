(ns chequer.test-helpers
  (:require
    [chequer.db :as db]
    [chequer.users :as users]
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


(defn with-test-db
  "Call (f db) with a fully migrated temp database, deleted afterwards."
  [f]
  (let [path (temp-db-path)
        d (db/open path)]
    (try
      (db/migrate! d)
      (f d)
      (finally
        (db/close! d)
        (delete-db-files path)))))


(defn create-user!
  "Create a user named user-name and return its row."
  [d user-name]
  (users/ensure-user! d {:subject user-name :name user-name :title user-name}))
