(ns chequer.data.lists-test
  (:require
    [chequer.data.lists :as lists]
    [chequer.db :as db]
    [chequer.test-helpers :as helpers]
    [chequer.time :as time]
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is testing]]))


(defn- tx!
  "Run (f tx) in a transaction and return {:result r :topics #{...}}."
  [d f]
  (db/with-tx [tx d] (f tx)))


(defn- events
  [d]
  (mapv (fn [e]
          (-> (select-keys e [:entity_type :entity_id :list_id :action :data])
              (update :data #(some-> % edn/read-string))))
        (db/q d ["SELECT * FROM event ORDER BY id"])))


(defn- with-user
  [f]
  (helpers/with-test-db
    (fn [d]
      (binding [time/*now-ms* (constantly 1000)]
        (f d (helpers/create-user! d "u"))))))


(defn- seed
  "A list with section g1 holding tasks a, b; unsectioned task c."
  [d u]
  (:result
    (tx! d (fn [tx]
             (let [lid (lists/create-list! tx u {:title "L"})
                   g1 (lists/create-section! tx u {:list-id lid :title "G1"})
                   a (lists/create-task! tx u {:list-id lid :section-id g1 :title "a"})
                   b (lists/create-task! tx u {:list-id lid :section-id g1 :title "b"})
                   c (lists/create-task! tx u {:list-id lid :title "c"})]
               {:lid lid :g1 g1 :a a :b b :c c})))))


(defn- state-of
  [d id]
  (:state (db/q1 d ["SELECT state FROM task WHERE id = ?" id])))


(deftest state-transition-applies-the-design-timestamp-rules
  (let [user {:id 7}
        t (fn [state ended by] {:state state :ended_at ended :ended_by by})
        go #(lists/state-transition % user 100)]
    (is (= (t "pending" nil nil) (go :pending)) "to pending clears the end")
    (is (= (t "completed" 100 7) (go :completed)) "to completed stamps the end")
    (is (= (t "skipped" 100 7) (go :skipped)) "to skipped stamps the end")))


(deftest set-state-writes-columns-topics-and-events
  (with-user
    (fn [d u]
      (let [{:keys [lid a]} (seed d u)
            before (count (events d))]
        (is (= #{[:list lid] [:list lid :header] [:list lid :task a]} (:topics (tx! d #(lists/set-state! % u {:id a :state :completed})))))
        (is (= {:state "completed" :ended_at 1000 :ended_by (:id u)}
               (select-keys (db/q1 d ["SELECT * FROM task WHERE id = ?" a]) [:state :ended_at :ended_by])))
        (testing "undo back to pending"
          (tx! d #(lists/set-state! % u {:id a :state :pending}))
          (is (= {:state "pending" :ended_at nil :ended_by nil}
                 (select-keys (db/q1 d ["SELECT * FROM task WHERE id = ?" a]) [:state :ended_at :ended_by]))))
        (tx! d #(lists/set-state! % u {:id a :state :skipped}))
        (testing "setting the state it already has writes nothing"
          (is (= #{} (:topics (tx! d #(lists/set-state! % u {:id a :state :skipped}))))))
        (is (= [["complete" {:state ["pending" "completed"]}]
                ["update" {:state ["completed" "pending"]}]
                ["skip" {:state ["pending" "skipped"]}]]
               (mapv (juxt :action :data) (subvec (events d) before))))
        (is (every? #(= lid (:list_id %)) (subvec (events d) before)))))))


(deftest set-state-rejects-an-unknown-state
  (with-user
    (fn [d u]
      (let [{:keys [a]} (seed d u)]
        (is (thrown? Exception (tx! d #(lists/set-state! % u {:id a :state :bogus}))))
        (is (= "pending" (state-of d a)))))))


(deftest derived-values
  (let [task (fn [state due] {:task/state state :task/due-at due})]
    (testing "a list is done when it has tasks and none are open"
      (is (false? (lists/list-done? [])))
      (is (false? (lists/list-done? [(task :completed nil) (task :pending nil)])))
      (is (true? (lists/list-done? [(task :completed nil) (task :skipped nil)]))))
    (testing "overdue means past due and still open"
      (is (true? (lists/overdue? (task :pending 99) 100)))
      (is (false? (lists/overdue? (task :skipped 99) 100)))
      (is (false? (lists/overdue? (task :pending 100) 100)) "due exactly now is not yet overdue")
      (is (false? (lists/overdue? (task :pending nil) 100)))
      (is (false? (lists/overdue? (task :completed 99) 100))))))


(deftest create-update-delete-list-write-events
  (with-user
    (fn [d u]
      (let [{lid :result topics :topics} (tx! d #(lists/create-list! % u {:title " Trip " :description "" :icon "🧳" :due_at 5000}))]
        (is (= #{[:list lid] [:list lid :header] [:list lid :structure] [:lists]} topics))
        (is (= {:list/id lid :list/title "Trip" :list/description nil :list/icon "🧳" :list/due-at 5000
                :list/archived-at nil :list/created-at 1000 :list/created-by (:id u)
                :list/updated-at 1000 :list/updated-by (:id u)}
               (:list (lists/load-list d lid))))
        (binding [time/*now-ms* (constantly 2000)]
          (is (= #{[:list lid] [:list lid :header] [:lists]} (:topics (tx! d #(lists/update-list! % u {:id lid :title "Trip!" :due_at nil}))))))
        (is (= {:list/title "Trip!" :list/due-at nil :list/updated-at 2000}
               (select-keys (:list (lists/load-list d lid)) [:list/title :list/due-at :list/updated-at])))
        (testing "an update that changes nothing writes no event and no topics"
          (is (= #{} (:topics (tx! d #(lists/update-list! % u {:id lid :title "Trip!"}))))))
        (tx! d #(lists/delete-list! % u {:id lid}))
        (is (nil? (lists/load-list d lid)))
        (is (= [{:entity_type "list" :entity_id lid :list_id lid :action "create" :data {:title "Trip"}}
                {:entity_type "list" :entity_id lid :list_id lid :action "update"
                 :data {:title ["Trip" "Trip!"] :due_at [5000 nil]}}
                {:entity_type "list" :entity_id lid :list_id lid :action "delete" :data {:title "Trip!"}}]
               (events d)))))))


(deftest invalid-attributes-are-rejected-with-field-errors
  (with-user
    (fn [d u]
      (let [e (try (tx! d #(lists/create-list! % u {:title " " :icon "abc"}))
                   (catch Exception e e))]
        (is (= {:type :chequer/invalid :errors {:title "can't be blank" :icon "must be a single emoji"}}
               (ex-data e))))
      (is (empty? (db/q d ["SELECT * FROM list"]))))))


(deftest archive-and-unarchive-set-and-clear-archived-at
  (with-user
    (fn [d u]
      (let [{:keys [lid]} (seed d u)
            archived-at #(:list/archived-at (:list (lists/load-list d lid)))]
        (is (= #{[:list lid] [:list lid :header] [:lists]} (:topics (tx! d #(lists/archive-list! % u {:id lid})))))
        (is (= 1000 (archived-at)))
        (testing "archiving twice keeps the first instant and writes nothing"
          (binding [time/*now-ms* (constantly 2000)]
            (is (= #{} (:topics (tx! d #(lists/archive-list! % u {:id lid}))))))
          (is (= 1000 (archived-at))))
        (tx! d #(lists/unarchive-list! % u {:id lid}))
        (is (nil? (archived-at)))
        (is (= #{} (:topics (tx! d #(lists/unarchive-list! % u {:id lid})))))
        (is (= ["archive" "unarchive"] (mapv :action (take-last 2 (events d)))))))))


(defn- shape
  "The display order of a loaded list as [unsectioned-titles [section-title task-titles]...]."
  [{:keys [unsectioned sections]}]
  [(mapv :task/title unsectioned)
   (mapv (fn [{:keys [section tasks]}] [(:list.section/title section) (mapv :task/title tasks)]) sections)])


(deftest load-list-puts-unsectioned-tasks-first-then-sections-in-order
  (with-user
    (fn [d u]
      (let [{:keys [lid a]} (seed d u)
            loaded (lists/load-list d lid)]
        (is (= [["c"] [["G1" ["a" "b"]]]] (shape loaded)))
        (is (= {:task/id a :task/list lid :task/state :pending}
               (select-keys (first (:tasks (first (:sections loaded)))) [:task/id :task/list :task/state])))))))


(deftest moving-sections-and-tasks-changes-display-order
  (with-user
    (fn [d u]
      (let [{:keys [lid g1 a b c]} (seed d u)
            g2 (:result (tx! d #(lists/create-section! % u {:list-id lid :title "G2"})))
            d2 (:result (tx! d #(lists/create-task! % u {:list-id lid :section-id g2 :title "d"})))
            before (count (events d))]
        (is (= #{[:list lid] [:list lid :structure]} (:topics (tx! d #(lists/move-section! % u {:id g2 :direction :up})))))
        (is (= #{[:list lid] [:list lid :structure]} (:topics (tx! d #(lists/place-task! % u {:id b :section-id g1 :before a})))))
        (tx! d #(lists/place-task! % u {:id d2 :section-id g2 :before nil}))
        (is (= [["c"] [["G2" ["d"]] ["G1" ["b" "a"]]]] (shape (lists/load-list d lid))))
        (is (= g1 (:section_id (db/q1 d ["SELECT section_id FROM task WHERE id = ?" b]))))
        (tx! d #(lists/place-task! % u {:id a :section-id g2 :before d2}))
        (tx! d #(lists/place-task! % u {:id c :section-id g1 :before nil}))
        (tx! d #(lists/place-task! % u {:id b :section-id nil :before nil}))
        (is (= [["b"] [["G2" ["a" "d"]] ["G1" ["c"]]]] (shape (lists/load-list d lid)))
            "placing a task in another section, or none, moves it there")
        (is (= before (count (events d))) "reordering records no events")))))


(deftest updating-sections-and-tasks-records-diffs
  (with-user
    (fn [d u]
      (let [{:keys [lid g1 a]} (seed d u)
            before (count (events d))]
        (is (= #{[:list lid] [:list lid :structure]} (:topics (tx! d #(lists/update-section! % u {:id g1 :title "Clothes" :icon "👕"})))))
        (tx! d #(lists/update-task! % u {:id a :title "a2" :due_at 9000 :assignee_id (:id u)}))
        (is (= [{:entity_type "list.section" :entity_id g1 :list_id lid :action "update"
                 :data {:title ["G1" "Clothes"] :icon [nil "👕"]}}
                {:entity_type "task" :entity_id a :list_id lid :action "update"
                 :data {:title ["a" "a2"] :due_at [nil 9000] :assignee_id [nil (:id u)]
                        :assignee [nil (:title u)]}}]
               (subvec (events d) before)))))))


(deftest deleting-a-section-deletes-and-logs-its-tasks
  (with-user
    (fn [d u]
      (let [{:keys [lid g1 a b]} (seed d u)
            before (count (events d))]
        (is (= #{[:list lid] [:list lid :header] [:list lid :structure]} (:topics (tx! d #(lists/delete-section! % u {:id g1})))))
        (is (= [["c"] []] (shape (lists/load-list d lid))))
        (is (= [{:entity_type "task" :entity_id a :list_id lid :action "delete" :data {:title "a"}}
                {:entity_type "task" :entity_id b :list_id lid :action "delete" :data {:title "b"}}
                {:entity_type "list.section" :entity_id g1 :list_id lid :action "delete" :data {:title "G1"}}]
               (subvec (events d) before)))))))


(deftest deleting-a-list-cascades-and-a-missing-task-delete-is-a-no-op
  (with-user
    (fn [d u]
      (let [{:keys [lid c]} (seed d u)]
        (tx! d #(lists/delete-list! % u {:id lid}))
        (is (empty? (db/q d ["SELECT * FROM task"])))
        (is (empty? (db/q d ["SELECT * FROM list_section"])))
        (let [before (events d)]
          (is (= #{} (:topics (tx! d #(lists/delete-task! % u {:id c})))))
          (is (= before (events d))))))))


(deftest a-task-cannot-join-another-lists-section
  (with-user
    (fn [d u]
      (let [{:keys [g1]} (seed d u)
            other (:result (tx! d #(lists/create-list! % u {:title "Other"})))]
        (is (thrown-with-msg? Exception #"section belongs to another list"
              (tx! d #(lists/create-task! % u {:list-id other :section-id g1 :title "x"}))))))))


(deftest missing-entities-are-not-found
  (with-user
    (fn [d u]
      (doseq [f [#(lists/update-list! % u {:id 99 :title "x"})
                 #(lists/archive-list! % u {:id 99})
                 #(lists/update-section! % u {:id 99 :title "x"})
                 #(lists/update-task! % u {:id 99 :title "x"})
                 #(lists/set-state! % u {:id 99 :state :completed})
                 #(lists/place-task! % u {:id 99 :section-id nil :before nil})
                 #(lists/create-section! % u {:list-id 99 :title "x"})
                 #(lists/create-task! % u {:list-id 99 :title "x"})]]
        (is (= :chequer/not-found
               (:type (ex-data (try (tx! d f) (catch Exception e e))))))))))


(deftest list-summary-counts-states-done-and-next-due
  (with-user
    (fn [d u]
      (let [{:keys [lid a b c]} (seed d u)
            empty-id (:result (tx! d #(lists/create-list! % u {:title "Empty"})))
            done-id (:result (tx! d (fn [tx]
                                      (let [l (lists/create-list! tx u {:title "Done"})
                                            t (lists/create-task! tx u {:list-id l :title "t"})]
                                        (lists/set-state! tx u {:id t :state :completed})
                                        l))))]
        (tx! d #(lists/update-task! % u {:id a :due_at 7000}))
        (tx! d #(lists/update-task! % u {:id b :due_at 3000}))
        (tx! d #(lists/set-state! % u {:id b :state :completed}))
        (tx! d #(lists/set-state! % u {:id c :state :skipped}))
        (tx! d #(lists/archive-list! % u {:id done-id}))
        (let [by-id (into {} (map (juxt (comp :list/id :list) identity)) (lists/list-summary d))]
          (is (= {:counts {:pending 1 :completed 1 :skipped 1} :done? false :next-due 7000}
                 (select-keys (by-id lid) [:counts :done? :next-due]))
              "a completed task's due date is not the next due")
          (is (= {:counts {:pending 0 :completed 0 :skipped 0} :done? false :next-due nil}
                 (select-keys (by-id empty-id) [:counts :done? :next-due])))
          (is (= {:done? true :next-due nil} (select-keys (by-id done-id) [:done? :next-due])))
          (is (= 1000 (:list/archived-at (:list (by-id done-id))))
              "archived lists are included, flagged by archived-at"))))))
