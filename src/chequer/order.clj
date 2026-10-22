(ns chequer.order
  "Fractional ordering of sibling rows by a REAL \"order\" column."
  (:require
    [jdbc.core :as jdbc]))


(defn append-order
  "The order for a row appended after siblings with the given orders."
  [orders]
  (inc (reduce max 0 orders)))


(defn between
  "The order midway between a and b; a nil neighbour counts as one beyond the other."
  [a b]
  (cond
    (nil? a) (dec b)
    (nil? b) (inc a)
    :else (/ (+ a b) 2.0)))


(defn needs-renumber?
  "True when a and b are too close together to split again."
  [a b]
  (< (- b a) 1e-9))


(defn- siblings
  [tx table [where & params]]
  (jdbc/fetch tx (into [(str "SELECT id, \"order\" FROM " table " WHERE " where " ORDER BY \"order\", id")]
                       params)))


(defn- set-order!
  [tx table id order]
  (jdbc/execute! tx [(str "UPDATE " table " SET \"order\" = ? WHERE id = ?") order id]))


(defn- neighbours
  "The orders of the two rows the moving row will sit between, or nil if it can't move."
  [rows id direction]
  (let [orders (mapv :order rows)
        i (first (keep-indexed #(when (= id (:id %2)) %1) rows))
        [lo hi] (case direction
                  :up [(- i 2) (- i 1)]
                  :down [(+ i 1) (+ i 2)])]
    (when (< -1 (if (= :up direction) hi lo) (count orders))
      [(get orders lo) (get orders hi)])))


(defn move
  "Move row id of table one place :up or :down among the rows matching scope-sql
  ([where-clause & params]), renumbering the siblings 1..n first when the gap is too
  small to split. Returns true if the row moved, false if it was already at that end."
  [tx {:keys [table scope-sql id direction]}]
  (let [rows (siblings tx table scope-sql)
        [a b] (neighbours rows id direction)
        rows (if (and a b (needs-renumber? a b))
               (do (doseq [[n row] (map-indexed vector rows)]
                     (set-order! tx table (:id row) (inc n)))
                   (siblings tx table scope-sql))
               rows)]
    (if-let [[a b] (neighbours rows id direction)]
      (do (set-order! tx table id (between a b))
          true)
      false)))
