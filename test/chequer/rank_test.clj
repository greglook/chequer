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


(defn- place!
  [d id scope before]
  (db/with-tx [tx d]
    (rank/place tx {:table "thing" :scope-sql ["scope = ?" scope] :id id :before before})
    (jdbc/execute! tx ["UPDATE thing SET scope = ? WHERE id = ?" scope id])))


(deftest place-puts-a-row-before-another-or-last
  (with-things
    (fn [d]
      (place! d 3 1 1)
      (is (= ["c" "a" "b"] (names d 1)))
      (place! d 3 1 nil)
      (is (= ["a" "b" "c"] (names d 1)))
      (place! d 1 1 3)
      (is (= ["b" "a" "c"] (names d 1))))))


(deftest place-moves-a-row-into-another-scope
  (with-things
    (fn [d]
      (place! d 2 2 4)
      (is (= ["a" "c"] (names d 1)))
      (is (= ["b" "x"] (names d 2)))
      (place! d 1 3 nil)
      (is (= ["a"] (names d 3)) "an empty scope takes the row"))))


(deftest place-before-a-row-outside-the-scope-is-a-bad-request
  (with-things
    (fn [d]
      (is (= :chequer/bad-request
             (:type (ex-data (try (place! d 1 1 4) (catch Exception e e))))))
      (is (= :chequer/bad-request
             (:type (ex-data (try (place! d 1 1 1) (catch Exception e e)))))
          "a row can't be placed before itself"))))


(deftest repeated-places-renumber-the-siblings
  (with-things
    (fn [d]
      (dotimes [i 120]
        (place! d (if (even? i) 3 2) 1 (if (even? i) 2 3))
        (is (= (if (even? i) ["a" "c" "b"] ["a" "b" "c"]) (names d 1))))
      (let [ranks (map :rank (db/q d ["SELECT rank FROM thing WHERE scope = 1"]))]
        (is (apply distinct? ranks))))))
