(ns chequer.data.add-plan-test
  (:require
    [chequer.data.add-plan :as add-plan]
    [chequer.data.lists :as lists]
    [chequer.data.plans :as plans]
    [chequer.db :as db]
    [chequer.test-helpers :as helpers]
    [chequer.time :as time]
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is testing]]))


(defn- tx!
  [d f]
  (db/with-tx [tx d] (f tx)))


(defn- with-user
  [f]
  (helpers/with-test-db
    (fn [d]
      (binding [time/*now-ms* (constantly 1000)]
        (f d (helpers/create-user! d "u"))))))


(defn- make-plan!
  "Create a plan titled title from tree [unsectioned-tasks [section-title tasks]...], where each
  task is a title or a map of task attributes. Returns {:id plan-id :tasks {title id}
  :sections {title id}}."
  [d u title [unsectioned & sections]]
  (:result
    (tx! d (fn [tx]
             (let [pid (plans/create-plan! tx u {:title title})
                   add-task (fn [section-id t]
                              (let [attrs (if (map? t) t {:title t})]
                                [(:title attrs)
                                 (plans/create-plan-task! tx u (assoc attrs :plan-id pid :section-id section-id))]))
                   unsectioned (mapv #(add-task nil %) unsectioned)
                   sectioned (mapv (fn [[gt tasks]]
                                     (let [gid (plans/create-section! tx u {:plan-id pid :title gt})]
                                       [[gt gid] (mapv #(add-task gid %) tasks)]))
                                   sections)]
               {:id pid
                :sections (into {} (map first) sectioned)
                :tasks (into {} (concat unsectioned (mapcat second sectioned)))})))))


(defn- make-list!
  [d u]
  (:result (tx! d #(lists/create-list! % u {:title "L"}))))


(defn- shape
  "The list's display order as [unsectioned-titles [section-title task-titles]...]."
  [d lid]
  (let [{:keys [unsectioned sections]} (lists/load-list d lid)]
    [(mapv :task/title unsectioned)
     (mapv (fn [{:keys [section tasks]}] [(:list.section/title section) (mapv :task/title tasks)]) sections)]))


(defn- add!
  [d u lid & pids]
  (tx! d #(add-plan/add-plans! % u lid pids)))


(def ^:private packing
  [["Passport" {:title "Charger" :description "USB-C *and* lightning" :icon "🔌"}]
   ["Clothes" ["Socks" "Shirts"]]
   ["Toiletries" ["Toothbrush"]]])


(deftest normalize-title-trims-and-lower-cases
  (is (= "pack socks" (add-plan/normalize-title "  Pack SOCKS "))))


(deftest adding-a-plan-to-an-empty-list-copies-it-exactly
  (with-user
    (fn [d u]
      (let [{pid :id :keys [tasks sections]} (make-plan! d u "Packing" packing)
            lid (make-list! d u)
            {:keys [result topics]} (add! d u lid pid)]
        (is (= [{:plan-id pid :plan-title "Packing" :sections-created 2 :tasks-created 5 :tasks-skipped 0}] result))
        (is (= #{[:list lid] [:list lid :header] [:list lid :structure] [:lists]} topics))
        (is (= [["Passport" "Charger"] [["Clothes" ["Socks" "Shirts"]] ["Toiletries" ["Toothbrush"]]]]
               (shape d lid)))
        (let [loaded (lists/load-list d lid)
              charger (second (:unsectioned loaded))]
          (is (= {:task/description "USB-C *and* lightning" :task/icon "🔌"
                  :task/state :pending :task/source (tasks "Charger")}
                 (select-keys charger [:task/description :task/icon :task/state :task/source])))
          (is (= (sections "Clothes") (:list.section/source (:section (first (:sections loaded))))))
          (is (= (tasks "Socks") (:task/source (first (:tasks (first (:sections loaded))))))))
        (testing "events: a create for each row, then one add-plan on the list"
          (let [evs (db/q d ["SELECT * FROM event WHERE list_id = ? ORDER BY id" lid])
                creates (butlast (rest evs))
                add-ev (last evs)]
            (is (= 7 (count creates)))
            (is (every? #(= "create" (:action %)) creates))
            (is (= {:entity_type "list" :entity_id lid :action "add-plan"}
                   (select-keys add-ev [:entity_type :entity_id :action])))
            (is (= {:plan-id pid :plan-title "Packing" :sections-created 2 :tasks-created 5 :tasks-skipped 0}
                   (edn/read-string (:data add-ev))))))))))


(deftest adding-the-same-plan-twice-does-nothing-the-second-time
  (with-user
    (fn [d u]
      (let [{pid :id} (make-plan! d u "Packing" packing)
            lid (make-list! d u)]
        (add! d u lid pid)
        (let [before (shape d lid)]
          (is (= [{:plan-id pid :plan-title "Packing" :sections-created 0 :tasks-created 0 :tasks-skipped 5}]
                 (:result (add! d u lid pid))))
          (is (= before (shape d lid))))))))


(deftest plans-sharing-a-section-title-merge-into-one-section
  (with-user
    (fn [d u]
      (let [{a :id} (make-plan! d u "Weekend" [[] ["Clothes" ["Socks" "Hat"]]])
            {b :id} (make-plan! d u "Beach" [["Towel"] [" clothes " ["Swimsuit" " SOCKS"]] ["Fun" ["Kite"]]])
            lid (make-list! d u)]
        (is (= [{:plan-id a :plan-title "Weekend" :sections-created 1 :tasks-created 2 :tasks-skipped 0}
                {:plan-id b :plan-title "Beach" :sections-created 1 :tasks-created 3 :tasks-skipped 1}]
               (:result (add! d u lid a b))))
        (is (= [["Towel"] [["Clothes" ["Socks" "Hat" "Swimsuit"]] ["Fun" ["Kite"]]]] (shape d lid)))))))


(deftest a-duplicate-title-in-a-different-section-is-not-skipped
  (with-user
    (fn [d u]
      (let [{pid :id} (make-plan! d u "P" [["Charger"] ["Car" ["Charger"]] ["Office" ["charger"]]])
            lid (make-list! d u)]
        (is (= 3 (:tasks-created (first (:result (add! d u lid pid))))))
        (is (= [["Charger"] [["Car" ["Charger"]] ["Office" ["charger"]]]] (shape d lid)))))))


(deftest copying-is-a-snapshot
  (with-user
    (fn [d u]
      (let [{pid :id :keys [tasks sections]} (make-plan! d u "Packing" packing)
            lid (make-list! d u)]
        (add! d u lid pid)
        (let [before (shape d lid)]
          (tx! d (fn [tx]
                   (plans/update-plan-task! tx u {:id (tasks "Socks") :title "Wool socks"})
                   (plans/update-section! tx u {:id (sections "Clothes") :title "Garments"})
                   (plans/create-plan-task! tx u {:plan-id pid :title "Snacks"})))
          (is (= before (shape d lid))))))))


(deftest deleting-the-source-clears-source-ids
  (with-user
    (fn [d u]
      (let [{pid :id :keys [tasks]} (make-plan! d u "Packing" packing)
            lid (make-list! d u)]
        (add! d u lid pid)
        (tx! d #(plans/delete-plan-task! % u {:id (tasks "Passport")}))
        (is (= [nil] (map :source_id (db/q d ["SELECT source_id FROM task WHERE title = 'Passport'"]))))
        (is (= (tasks "Charger") (:source_id (db/q1 d ["SELECT source_id FROM task WHERE title = 'Charger'"]))))
        (tx! d #(plans/delete-plan! % u {:id pid}))
        (is (every? nil? (map :source_id (db/q d ["SELECT source_id FROM task UNION ALL SELECT source_id FROM list_section"]))))
        (is (= 5 (count (db/q d ["SELECT * FROM task WHERE list_id = ?" lid]))) "the list keeps its tasks")))))


(deftest an-exception-partway-rolls-everything-back
  (with-user
    (fn [d u]
      (let [{pid :id} (make-plan! d u "Packing" packing)
            lid (make-list! d u)
            events-before (count (db/q d ["SELECT * FROM event"]))]
        (is (= :chequer/not-found
               (:type (ex-data (try (add! d u lid pid 999) (catch Exception e e))))))
        (is (empty? (db/q d ["SELECT * FROM task"])))
        (is (empty? (db/q d ["SELECT * FROM list_section"])))
        (is (= events-before (count (db/q d ["SELECT * FROM event"]))))))))


(deftest adding-to-a-missing-list-is-not-found
  (with-user
    (fn [d u]
      (let [{pid :id} (make-plan! d u "Packing" packing)]
        (is (= :chequer/not-found
               (:type (ex-data (try (add! d u 999 pid) (catch Exception e e))))))))))
