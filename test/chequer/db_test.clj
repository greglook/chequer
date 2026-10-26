(ns chequer.db-test
  (:require
    [chequer.db :as db]
    [chequer.test-helpers :as helpers]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [jdbc.core :as jdbc]
    [jolt.fs :as fs]
    [tick.core :as t]))


(defn- with-db
  [f]
  (let [path (helpers/temp-db-path)
        d (db/open path)]
    (try
      (jdbc/execute! (:writer d) "CREATE TABLE thing (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)")
      (f d)
      (finally
        (db/close! d)
        (helpers/delete-db-files path)))))


(deftest open-applies-pragmas-to-every-connection
  (with-db
    (fn [d]
      (doseq [conn (cons (:writer d) @(:readers d))]
        (is (= "wal" (:journal_mode (jdbc/fetch-one conn "PRAGMA journal_mode"))))
        (is (= 1 (:foreign_keys (jdbc/fetch-one conn "PRAGMA foreign_keys"))))
        (is (= 5000 (:timeout (jdbc/fetch-one conn "PRAGMA busy_timeout"))))))))


(deftest with-tx-returns-result-and-topics
  (with-db
    (fn [d]
      (let [r (db/with-tx [tx d]
                (db/touch! [:plan 7])
                (db/touch! [:lists])
                (db/touch! [:plan 7])
                (db/insert! tx :thing {:name "a"}))]
        (is (= {:result 1 :topics #{[:plan 7] [:lists]}} r))))))


(deftest with-tx-without-touches-has-empty-topics
  (with-db
    (fn [d]
      (is (= {:result :ok :topics #{}} (db/with-tx [_ d] :ok))))))


(deftest insert-returns-successive-ids
  (with-db
    (fn [d]
      (is (= [1 2] (:result (db/with-tx [tx d]
                              [(db/insert! tx :thing {:name "a"})
                               (db/insert! tx :thing {:name "b"})])))))))


(deftest exception-rolls-back-and-propagates
  (with-db
    (fn [d]
      (is (thrown-with-msg? Exception #"boom"
            (db/with-tx [tx d]
              (db/insert! tx :thing {:name "a"})
              (throw (ex-info "boom" {})))))
      (is (nil? (db/q1 d ["SELECT id FROM thing"])))
      (is (= {:result 1 :topics #{}} (db/with-tx [tx d] (db/insert! tx :thing {:name "b"})))
          "the writer is usable after a rollback"))))


(deftest topics-do-not-leak_between_transactions
  (with-db
    (fn [d]
      (db/with-tx [_ d] (db/touch! [:plan 1]))
      (is (= #{} (:topics (db/with-tx [_ d] nil)))))))


(deftest readers-see-committed-rows
  (with-db
    (fn [d]
      (db/with-tx [tx d]
        (db/insert! tx :thing {:name "a"})
        (db/insert! tx :thing {:name "b"}))
      (is (= ["a" "b"] (mapv :name (db/q d ["SELECT name FROM thing ORDER BY id"]))))
      (is (= {:n 2} (db/q1 d ["SELECT count(*) AS n FROM thing"])))
      (is (= {:name "b"} (db/q1 d ["SELECT name FROM thing WHERE id = ?" 2]))))))


(deftest readers-do-not-see-uncommitted-rows
  (with-db
    (fn [d]
      (db/with-tx [tx d]
        (db/insert! tx :thing {:name "a"})
        (is (nil? (db/q1 d ["SELECT id FROM thing"])))))))


(deftest reader-pool-is-restored-after-use
  (with-db
    (fn [d]
      (let [n (count @(:readers d))]
        (db/q d ["SELECT 1"])
        (is (= n (count @(:readers d))))
        (is (thrown? Exception (db/q d ["SELECT * FROM missing"])))
        (is (= n (count @(:readers d))) "a failed read still returns its connection")))))


;; ## Schema Migration

(defn- with-empty-db
  [f]
  (let [path (helpers/temp-db-path)
        d (db/open path)]
    (try
      (f d path)
      (finally
        (db/close! d)
        (helpers/delete-db-files path)))))


(defn- user-version
  [d]
  (:user_version (jdbc/fetch-one (:writer d) "PRAGMA user_version")))


(defn- table-names
  "The tables in d, leaving out SQLite's own and the search index's shadow tables."
  [d]
  (->> (db/q d ["SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"])
       (map :name)
       (remove #(str/starts-with? % "search_"))
       (set)))


(def ^:private latest
  (:version (#'db/parse-migration-name (last db/migration-files))))


(deftest migration-files-are-sorted-by-name
  (is (= "20261021-001-create-users-and-events.sql" (first db/migration-files)))
  (is (= (sort db/migration-files) db/migration-files)))


(deftest migration-versions-are-consecutive-from-one
  (is (= (range 1 (inc latest))
         (map (comp :version #'db/parse-migration-name) db/migration-files))))


(deftest parse-migration-name-reads-date-version-and-slug
  (is (= {:name "add-things" :date (t/date "2026-11-01") :version 12}
         (#'db/parse-migration-name "20261101-012-add-things.sql"))))


(deftest parse-migration-name-rejects-a-malformed-name
  (doseq [bad ["2026-10-21-001-x.sql" "20261021-1-x.sql" "20261021-001.sql" "20261021-001-X.sql" "20261021-001-x.txt"]]
    (is (thrown-with-msg? Exception #"Malformed migration file name" (#'db/parse-migration-name bad))
        bad)))


(deftest parse-migration-text-splits-statements-and-drops-comments
  (is (= ["CREATE TABLE a (\n  x TEXT DEFAULT ';')" "CREATE INDEX a_x ON a(x)"]
         (#'db/parse-migration-text
          "-- leading comment\nCREATE TABLE a (\n  x TEXT DEFAULT ';');\n\nCREATE INDEX a_x ON a(x);\n"))))


(deftest parse-migration-text-keeps-a-trigger-body-whole-when-inner-semicolons-are-mid-line
  (let [trigger "CREATE TRIGGER t BEFORE INSERT ON a\n  BEGIN SELECT RAISE(ABORT, 'no'); END"]
    (is (= [trigger] (#'db/parse-migration-text (str trigger ";\n"))))))


(deftest fresh-database-reaches-latest-version
  (with-empty-db
    (fn [d path]
      (db/migrate! d)
      (is (= latest (user-version d)))
      (is (= #{"user" "event" "plan" "plan_group" "plan_task" "list" "list_group" "task" "search"
               "session"}
             (table-names d)))
      (is (not (fs/exists? (str path ".pre-v" latest ".bak")))
          "an empty database needs no backup"))))


(deftest rerunning-migrations-is-a-no-op
  (with-empty-db
    (fn [d path]
      (db/migrate! d)
      (db/migrate! d)
      (is (= latest (user-version d)))
      (is (not (fs/exists? (str path ".pre-v" latest ".bak")))))))


(deftest migrate-returns-the-applied-migrations
  (with-empty-db
    (fn [d _]
      (is (= (vec db/migration-files) (mapv :file (db/migrate! d))))
      (is (= [] (db/migrate! d)) "nothing is applied the second time"))))


(deftest migrate-returns-only-the-pending-migrations
  (with-empty-db
    (fn [d _]
      (let [ms [{:file "a" :version 1 :statements (delay ["CREATE TABLE a (id INTEGER PRIMARY KEY)"])}
                {:file "b" :version 2 :statements (delay ["CREATE TABLE b (id INTEGER PRIMARY KEY)"])}]]
        (db/migrate! d (subvec ms 0 1))
        (is (= ["b"] (mapv :file (db/migrate! d ms))))))))


(deftest backup-copies-the-database-to-the-destination
  (with-empty-db
    (fn [d path]
      (db/migrate! d)
      (jdbc/execute! (:writer d) "INSERT INTO user (subject, name, title, created_at, last_seen) VALUES ('s', 'n', 't', 0, 0)")
      (let [dest (str path ".copy")]
        (db/backup! d dest)
        (let [b (db/open dest)]
          (try
            (is (= latest (user-version b)))
            (is (= {:n 1} (db/q1 b ["SELECT count(*) AS n FROM user"])))
            (finally (db/close! b))))))))


(deftest backup-never-overwrites-an-existing-file
  (with-empty-db
    (fn [d path]
      (let [dest (str path ".copy")]
        (spit dest "precious")
        (is (thrown-with-msg? Exception #"already exists" (db/backup! d dest)))
        (is (= "precious" (slurp dest)))))))


(deftest upgrading-a-non-empty-database-writes-a-backup
  (with-empty-db
    (fn [d path]
      (jdbc/execute! (:writer d) "CREATE TABLE legacy (id INTEGER PRIMARY KEY)")
      (jdbc/execute! (:writer d) "INSERT INTO legacy DEFAULT VALUES")
      (db/migrate! d)
      (let [backup (str path ".pre-v" latest ".bak")
            b (db/open backup)]
        (try
          (is (fs/exists? backup))
          (is (= 0 (:user_version (jdbc/fetch-one (:writer b) "PRAGMA user_version")))
              "the backup is the pre-migration state")
          (is (= {:n 1} (db/q1 b ["SELECT count(*) AS n FROM legacy"])))
          (finally (db/close! b)))))))


(deftest existing-backup-is-never-overwritten
  (with-empty-db
    (fn [d path]
      (jdbc/execute! (:writer d) "CREATE TABLE legacy (id INTEGER PRIMARY KEY)")
      (spit (str path ".pre-v" latest ".bak") "precious")
      (is (thrown-with-msg? Exception #"already exists" (db/migrate! d)))
      (is (= "precious" (slurp (str path ".pre-v" latest ".bak"))))
      (is (= 0 (user-version d))))))


(deftest failing-migration-leaves-version-unchanged
  (with-empty-db
    (fn [d _]
      (let [bad [{:version 1 :statements (delay ["CREATE TABLE ok (id INTEGER PRIMARY KEY)"])}
                 {:version 2 :statements (delay ["CREATE TABLE ok2 (id INTEGER PRIMARY KEY)" "NOT VALID SQL"])}]]
        (db/migrate! d (subvec bad 0 1))
        (is (= 1 (user-version d)))
        (is (thrown? Exception (db/migrate! d bad)))
        (is (= 1 (user-version d)))
        (is (= #{"ok"} (table-names d)) "the failed migration's earlier statements were rolled back")))))


(deftest event-requires-an-existing-user
  (with-empty-db
    (fn [d _]
      (db/migrate! d)
      (is (thrown? Exception
            (db/with-tx [tx d]
              (db/insert! tx :event {:instant 1 :user_id 99 :entity_type "plan" :entity_id 1 :action "created"}))))
      (let [uid (:result (db/with-tx [tx d]
                           (db/insert! tx :user {:subject "owner" :name "owner" :title "Owner" :created_at 1 :last_seen 1})))]
        (is (pos? (:result (db/with-tx [tx d]
                             (db/insert! tx :event {:instant 1 :user_id uid :entity_type "plan" :entity_id 1 :action "created"})))))))))


(deftest plan-schema-enforces-same-plan-groups-and-cascades
  (helpers/with-test-db
    (fn [d]
      (let [uid (:id (helpers/create-user! d "u"))
            plan #(db/insert! %1 :plan {:title %2 :created_at 1 :created_by uid :updated_at 1 :updated_by uid})
            task (fn [tx pid gid]
                   (db/insert! tx :plan_task {:plan_id pid :group_id gid :rank 1.0 :title "t"
                                              :created_at 1 :created_by uid :updated_at 1 :updated_by uid}))
            {[p1 p2 g1 t1] :result} (db/with-tx [tx d]
                                      (let [p1 (plan tx "a")
                                            p2 (plan tx "b")
                                            g1 (db/insert! tx :plan_group {:plan_id p1 :rank 1.0 :title "g"})]
                                        [p1 p2 g1 (task tx p1 g1)]))]
        (is (thrown-with-msg? Exception #"group belongs to another plan"
              (db/with-tx [tx d] (task tx p2 g1))))
        (is (thrown-with-msg? Exception #"group belongs to another plan"
              (db/with-tx [tx d]
                (jdbc/execute! tx ["UPDATE plan_task SET plan_id = ? WHERE id = ?" p2 t1]))))
        (is (thrown-with-msg? Exception #"group belongs to another plan"
              (db/with-tx [tx d]
                (let [g2 (db/insert! tx :plan_group {:plan_id p2 :rank 1.0 :title "g"})]
                  (jdbc/execute! tx ["UPDATE plan_task SET group_id = ? WHERE id = ?" g2 t1])))))
        (db/with-tx [tx d] (jdbc/execute! tx ["DELETE FROM plan WHERE id = ?" p1]))
        (is (nil? (db/q1 d ["SELECT 1 FROM plan_task WHERE id = ?" t1])))
        (is (nil? (db/q1 d ["SELECT 1 FROM plan_group WHERE plan_id = ?" p1])))))))


(deftest list-schema-enforces-same-list-groups-state-check-and-cascades
  (helpers/with-test-db
    (fn [d]
      (let [uid (:id (helpers/create-user! d "u"))
            lst #(db/insert! %1 :list {:title %2 :created_at 1 :created_by uid :updated_at 1 :updated_by uid})
            task (fn [tx lid gid & [state]]
                   (db/insert! tx :task (cond-> {:list_id lid :group_id gid :rank 1.0 :title "t"
                                                 :created_at 1 :created_by uid :updated_at 1 :updated_by uid}
                                          state (assoc :state state))))
            {[l1 l2 g1 t1] :result} (db/with-tx [tx d]
                                      (let [l1 (lst tx "a")
                                            l2 (lst tx "b")
                                            g1 (db/insert! tx :list_group {:list_id l1 :rank 1.0 :title "g"})]
                                        [l1 l2 g1 (task tx l1 g1)]))]
        (is (= "pending" (:state (db/q1 d ["SELECT state FROM task WHERE id = ?" t1]))) "state defaults to pending")
        (is (thrown-with-msg? Exception #"group belongs to another list"
              (db/with-tx [tx d] (task tx l2 g1))))
        (is (thrown-with-msg? Exception #"group belongs to another list"
              (db/with-tx [tx d]
                (jdbc/execute! tx ["UPDATE task SET list_id = ? WHERE id = ?" l2 t1]))))
        (is (thrown-with-msg? Exception #"group belongs to another list"
              (db/with-tx [tx d]
                (let [g2 (db/insert! tx :list_group {:list_id l2 :rank 1.0 :title "g"})]
                  (jdbc/execute! tx ["UPDATE task SET group_id = ? WHERE id = ?" g2 t1])))))
        (is (thrown? Exception (db/with-tx [tx d] (task tx l1 nil "bogus"))))
        (db/with-tx [tx d] (jdbc/execute! tx ["DELETE FROM list WHERE id = ?" l1]))
        (is (nil? (db/q1 d ["SELECT 1 FROM task WHERE id = ?" t1])))
        (is (nil? (db/q1 d ["SELECT 1 FROM list_group WHERE list_id = ?" l1])))))))
