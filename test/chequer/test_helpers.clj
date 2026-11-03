(ns chequer.test-helpers
  (:require
    [chequer.data.lists :as lists]
    [chequer.data.plans :as plans]
    [chequer.db :as db]
    [chequer.users :as users]
    [clojure.string :as str]
    [clojure.tools.logging.impl :as log-impl]
    [jolt.fs :as fs]))


;; Silence the default stderr logger so test output isn't buried in log noise. Tests
;; that check logging bind their own clojure.tools.logging/*logger-factory*.
(alter-var-root #'log-impl/*level* (constantly :fatal))


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


(defn- load-nested
  "Join header (the map under k) and structure into {k h :unsectioned [tasks] :sections
  [{:section g :tasks [...]}]}, loading each task with load-task, or nil."
  [db k header structure load-task]
  (when header
    (let [{:keys [unsectioned sections]} structure
          load-tasks #(mapv (partial load-task db) %)]
      {k header
       :unsectioned (load-tasks unsectioned)
       :sections (mapv (fn [{:keys [section task-ids]}] {:section section :tasks (load-tasks task-ids)})
                       sections)})))


(defn load-list
  "{:list l :unsectioned [tasks] :sections [{:section g :tasks [...]}]} in display order, or nil."
  [db id]
  (load-nested db :list (:list (lists/load-list-header db id)) (lists/load-list-structure db id) lists/load-task))


(defn load-plan
  "{:plan p :unsectioned [tasks] :sections [{:section g :tasks [...]}]} in display order, or nil."
  [db id]
  (load-nested db :plan (plans/load-plan-header db id) (plans/load-plan-structure db id) plans/load-plan-task))
