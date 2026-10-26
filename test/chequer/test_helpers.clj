(ns chequer.test-helpers
  (:require
    [chequer.db :as db]
    [chequer.users :as users]
    [clojure.string :as str]
    [jolt.fs :as fs]))


(defn temp-db-path
  []
  (str (fs/temp-dir) "/chequer-test-" (System/nanoTime) ".db"))


(defn delete-db-files
  "Delete the SQLite file at path along with its WAL, shm and backup siblings."
  [path]
  (let [prefix (fs/file-name path)]
    (doseq [sibling (fs/list-dir (or (fs/parent path) "."))
            :when (str/starts-with? (fs/file-name sibling) prefix)]
      (fs/delete-if-exists sibling))))


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
