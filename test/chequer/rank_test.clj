(ns chequer.rank-test
  (:require
    [chequer.db :as db]
    [chequer.rank :as rank]
    [chequer.test-helpers :as helpers]
    [clojure.test :refer [deftest is]]
    [jdbc.core :as jdbc]))


(deftest append-rank-follows-the-maximum
  (is (= 1 (rank/append-rank [])))
  (is (= 4.5 (rank/append-rank [1 3.5 2]))))


(deftest between-is-the-midpoint-or-one-past-a-missing-neighbour
  (is (= 1.5 (rank/between 1 2)))
  (is (= 0 (rank/between nil 1)))
  (is (= 4 (rank/between 3 nil))))


(deftest repeated-midpoints-eventually-need-renumbering
  (let [steps (->> (iterate #(rank/between 1 %) 2.0)
                   (take-while #(not (rank/needs-renumber? 1 %)))
                   (count))]
    (is (< 25 steps 60))))


(defn- with-things
  "Call (f d) with a thing table holding rows a, b, c (ranks 1..3) in scope 1 and x in scope 2."
  [f]
  (helpers/with-test-db
    (fn [d]
      (db/with-tx [tx d]
        (jdbc/execute! tx "CREATE TABLE thing (id INTEGER PRIMARY KEY, scope INTEGER, name TEXT, rank REAL)")
        (doseq [[s n o] [[1 "a" 1] [1 "b" 2] [1 "c" 3] [2 "x" 1.5]]]
          (db/insert! tx :thing {:scope s :name n :rank o})))
      (f d))))


(defn- names
  [d scope]
  (mapv :name (db/q d ["SELECT name FROM thing WHERE scope = ? ORDER BY rank" scope])))


(defn- move!
  [d id direction]
  (:result (db/with-tx [tx d]
             (rank/move tx {:table "thing" :scope-sql ["scope = ?" 1] :id id :direction direction}))))


(deftest move-swaps-with-a-neighbour-within-the-scope
  (with-things
    (fn [d]
      (is (true? (move! d 3 :up)))
      (is (= ["a" "c" "b"] (names d 1)))
      (is (true? (move! d 3 :up)))
      (is (= ["c" "a" "b"] (names d 1)))
      (is (true? (move! d 1 :down)))
      (is (= ["c" "b" "a"] (names d 1)))
      (is (= ["x"] (names d 2)) "other scopes are untouched"))))


(deftest moving-past-either-end-is-a-no-op
  (with-things
    (fn [d]
      (is (false? (move! d 1 :up)))
      (is (false? (move! d 3 :down)))
      (is (= ["a" "b" "c"] (names d 1))))))


(deftest repeated-moves-renumber-the-siblings
  (with-things
    (fn [d]
      (dotimes [i 120]
        (move! d (if (even? i) 3 2) :up)
        (is (= (if (even? i) ["a" "c" "b"] ["a" "b" "c"]) (names d 1))))
      (let [ranks (map :rank (db/q d ["SELECT rank FROM thing WHERE scope = 1"]))]
        (is (apply distinct? ranks) "without renumbering, the halved gaps would collapse")))))
