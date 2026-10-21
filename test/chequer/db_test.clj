(ns chequer.db-test
  (:require
    [chequer.db :as db]
    [chequer.test-helpers :as helpers]
    [clojure.test :refer [deftest is]]
    [jdbc.core :as jdbc]))


(defn- with-db
  [f]
  (let [path (helpers/temp-db-path)
        d (db/open path)]
    (try
      (jdbc/execute! (:writer d) "CREATE TABLE thing (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)")
      (f d)
      (finally
        (db/close d)
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
