(ns chequer.migrations-test
  (:require
    [chequer.db :as db]
    [chequer.migrations :as migrations]
    [chequer.test-helpers :as helpers]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [jdbc.core :as jdbc]))


(defn- with-db
  [f]
  (let [path (helpers/temp-db-path)
        d (db/open path)]
    (try
      (f d path)
      (finally
        (db/close d)
        (helpers/delete-db-files path)))))


(defn- user-version
  [d]
  (:user_version (jdbc/fetch-one (:writer d) "PRAGMA user_version")))


(defn- table-names
  [d]
  (set (map :name (db/q d ["SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"]))))


(def ^:private latest (:version (last migrations/migrations)))


(deftest versions-are-consecutive-from-one
  (is (= (range 1 (inc latest)) (map :version migrations/migrations))))


(deftest fresh-database-reaches-latest-version
  (with-db
    (fn [d path]
      (db/migrate! d)
      (is (= latest (user-version d)))
      (is (= #{"user" "event"} (table-names d)))
      (is (not (.exists (io/file (str path ".pre-v" latest ".bak"))))
          "an empty database needs no backup"))))


(deftest rerunning-is-a-no-op
  (with-db
    (fn [d path]
      (db/migrate! d)
      (db/migrate! d)
      (is (= latest (user-version d)))
      (is (not (.exists (io/file (str path ".pre-v" latest ".bak"))))))))


(deftest upgrading-a-non-empty-database-writes-a-backup
  (with-db
    (fn [d path]
      (jdbc/execute! (:writer d) "CREATE TABLE legacy (id INTEGER PRIMARY KEY)")
      (jdbc/execute! (:writer d) "INSERT INTO legacy DEFAULT VALUES")
      (db/migrate! d)
      (let [backup (str path ".pre-v" latest ".bak")
            b (db/open backup)]
        (try
          (is (.exists (io/file backup)))
          (is (= 0 (:user_version (jdbc/fetch-one (:writer b) "PRAGMA user_version")))
              "the backup is the pre-migration state")
          (is (= {:n 1} (db/q1 b ["SELECT count(*) AS n FROM legacy"])))
          (finally (db/close b)))))))


(deftest existing-backup-is-never-overwritten
  (with-db
    (fn [d path]
      (jdbc/execute! (:writer d) "CREATE TABLE legacy (id INTEGER PRIMARY KEY)")
      (spit (str path ".pre-v" latest ".bak") "precious")
      (is (thrown-with-msg? Exception #"already exists" (db/migrate! d)))
      (is (= "precious" (slurp (str path ".pre-v" latest ".bak"))))
      (is (= 0 (user-version d))))))


(deftest failing-migration-leaves-version-unchanged
  (with-db
    (fn [d _]
      (let [bad [{:version 1 :sql ["CREATE TABLE ok (id INTEGER PRIMARY KEY)"]}
                 {:version 2 :sql ["CREATE TABLE ok2 (id INTEGER PRIMARY KEY)" "NOT VALID SQL"]}]]
        (db/migrate! d (subvec bad 0 1))
        (is (= 1 (user-version d)))
        (is (thrown? Exception (db/migrate! d bad)))
        (is (= 1 (user-version d)))
        (is (= #{"ok"} (table-names d)) "the failed migration's earlier statements were rolled back")))))


(deftest event-requires-an-existing-user
  (with-db
    (fn [d _]
      (db/migrate! d)
      (is (thrown? Exception
            (db/with-tx [tx d]
              (db/insert! tx :event {:instant 1 :user_id 99 :entity_type "plan" :entity_id 1 :action "created"}))))
      (let [uid (:result (db/with-tx [tx d]
                           (db/insert! tx :user {:subject "owner" :name "owner" :title "Owner" :created_at 1 :last_seen 1})))]
        (is (pos? (:result (db/with-tx [tx d]
                             (db/insert! tx :event {:instant 1 :user_id uid :entity_type "plan" :entity_id 1 :action "created"})))))))))
