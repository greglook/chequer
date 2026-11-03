(ns chequer.data.plans-test
  (:require
    [chequer.data.plans :as plans]
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
          (-> (select-keys e [:entity_type :entity_id :plan_id :action :data])
              (update :data #(some-> % edn/read-string))))
        (db/q d ["SELECT * FROM event ORDER BY id"])))


(defn- with-user
  [f]
  (helpers/with-test-db
    (fn [d]
      (binding [time/*now-ms* (constantly 1000)]
        (f d (helpers/create-user! d "u"))))))


(deftest validators-clean-or-explain
  (is (= {:value "Packing"} (plans/validate-title "  Packing ")))
  (is (= {:error "can't be blank"} (plans/validate-title "   ")))
  (is (= {:error "can't be blank"} (plans/validate-title nil)))
  (is (= {:error "must be a single line"} (plans/validate-title "a\nb")))
  (is (= {:value (apply str (repeat 200 "x"))} (plans/validate-title (apply str (repeat 200 "x")))))
  (is (= {:error "must be at most 200 characters"} (plans/validate-title (apply str (repeat 201 "x")))))
  (is (= {:value nil} (plans/validate-icon " ")))
  (is (= {:value "🧳"} (plans/validate-icon "🧳")))
  (is (= {:value "👍🏽"} (plans/validate-icon "👍🏽")) "a modified emoji is several code points")
  (is (= {:value "👨‍👩‍👧‍👦"} (plans/validate-icon "👨‍👩‍👧‍👦")) "a family ZWJ sequence is 7 code points")
  (is (contains? (plans/validate-icon "a") :error))
  (is (contains? (plans/validate-icon "🧳1") :error))
  (is (= {:value "7️⃣"} (plans/validate-icon "7️⃣")) "a digit is allowed in a keycap")
  (is (contains? (plans/validate-icon "7") :error))
  (is (contains? (plans/validate-icon "🧳🧳🧳🧳🧳🧳🧳🧳🧳") :error) "9 code points is too many"))


(deftest create-update-delete-plan-write-events
  (with-user
    (fn [d u]
      (let [{pid :result topics :topics} (tx! d #(plans/create-plan! % u {:title " Packing " :description "" :icon "🧳"}))]
        (is (= #{[:plan pid] [:plan pid :header] [:plan pid :structure] [:plans]} topics))
        (is (= {:plan/id pid :plan/title "Packing" :plan/description nil :plan/icon "🧳"
                :plan/created-at 1000 :plan/created-by (:id u) :plan/updated-at 1000 :plan/updated-by (:id u)}
               (:plan (plans/load-plan d pid))))
        (binding [time/*now-ms* (constantly 2000)]
          (tx! d #(plans/update-plan! % u {:id pid :title "Packing list" :description "Bring *all*" :icon "🧳"})))
        (is (= {:plan/title "Packing list" :plan/description "Bring *all*" :plan/updated-at 2000}
               (select-keys (:plan (plans/load-plan d pid)) [:plan/title :plan/description :plan/updated-at])))
        (testing "an update that changes nothing writes no event"
          (tx! d #(plans/update-plan! % u {:id pid :title "Packing list"})))
        (tx! d #(plans/delete-plan! % u {:id pid}))
        (is (nil? (plans/load-plan d pid)))
        (is (= [{:entity_type "plan" :entity_id pid :plan_id pid :action "create" :data {:title "Packing"}}
                {:entity_type "plan" :entity_id pid :plan_id pid :action "update"
                 :data {:title ["Packing" "Packing list"] :description [nil "Bring *all*"]}}
                {:entity_type "plan" :entity_id pid :plan_id pid :action "delete" :data {:title "Packing list"}}]
               (events d)))))))


(deftest invalid-attributes-are-rejected-with-field-errors
  (with-user
    (fn [d u]
      (let [e (try (tx! d #(plans/create-plan! % u {:title " " :icon "abc"}))
                   (catch Exception e e))]
        (is (= {:type :chequer/invalid :errors {:title "can't be blank" :icon "must be a single emoji"}}
               (ex-data e))))
      (is (empty? (db/q d ["SELECT * FROM plan"]))))))


(defn- seed
  "A plan with section g1 holding tasks a, b; unsectioned task c; section g2 holding d."
  [d u]
  (:result
    (tx! d (fn [tx]
             (let [pid (plans/create-plan! tx u {:title "P"})
                   g1 (plans/create-section! tx u {:plan-id pid :title "G1"})
                   g2 (plans/create-section! tx u {:plan-id pid :title "G2" :icon "🧺"})
                   a (plans/create-plan-task! tx u {:plan-id pid :section-id g1 :title "a" :icon "🧦"})
                   b (plans/create-plan-task! tx u {:plan-id pid :section-id g1 :title "b"})
                   c (plans/create-plan-task! tx u {:plan-id pid :title "c" :description "desc"})
                   dd (plans/create-plan-task! tx u {:plan-id pid :section-id g2 :title "d"})]
               {:pid pid :g1 g1 :g2 g2 :a a :b b :c c :dd dd})))))


(defn- shape
  "The display order of a loaded plan as [unsectioned-titles [section-title task-titles]...]."
  [{:keys [unsectioned sections]}]
  [(mapv :plan.task/title unsectioned)
   (mapv (fn [{:keys [section tasks]}] [(:plan.section/title section) (mapv :plan.task/title tasks)]) sections)])


(deftest load-plan-puts-unsectioned-tasks-first-then-sections-in-order
  (with-user
    (fn [d u]
      (let [{:keys [pid a]} (seed d u)
            loaded (plans/load-plan d pid)]
        (is (= [["c"] [["G1" ["a" "b"]] ["G2" ["d"]]]] (shape loaded)))
        (is (= {:plan.task/id a :plan.task/plan pid :plan.task/icon "🧦"}
               (select-keys (first (:tasks (first (:sections loaded))))
                            [:plan.task/id :plan.task/plan :plan.task/icon])))))))


(deftest moving-sections-and-tasks-changes-display-order
  (with-user
    (fn [d u]
      (let [{:keys [pid g1 g2 a b c dd]} (seed d u)]
        (is (= #{[:plan pid] [:plan pid :structure]} (:topics (tx! d #(plans/move-section! % u {:id g2 :direction :up})))))
        (is (= #{[:plan pid] [:plan pid :structure]} (:topics (tx! d #(plans/place-plan-task! % u {:id b :section-id g1 :before a})))))
        (tx! d #(plans/place-plan-task! % u {:id dd :section-id g2 :before nil}))
        (is (= [["c"] [["G2" ["d"]] ["G1" ["b" "a"]]]] (shape (plans/load-plan d pid))))
        (tx! d #(plans/place-plan-task! % u {:id a :section-id g2 :before dd}))
        (tx! d #(plans/place-plan-task! % u {:id c :section-id g1 :before nil}))
        (tx! d #(plans/place-plan-task! % u {:id b :section-id nil :before nil}))
        (is (= [["b"] [["G2" ["a" "d"]] ["G1" ["c"]]]] (shape (plans/load-plan d pid)))
            "placing a task in another section, or none, moves it there")
        (is (= (repeat 7 "create") (mapv :action (events d)))
            "reordering records no events")))))


(deftest updating-sections-and-tasks-records-diffs
  (with-user
    (fn [d u]
      (let [{:keys [pid g1 a]} (seed d u)
            before (count (events d))]
        (is (= #{[:plan pid] [:plan pid :structure]} (:topics (tx! d #(plans/update-section! % u {:id g1 :title "Clothes" :icon "👕"})))))
        (tx! d #(plans/update-plan-task! % u {:id a :title "a2" :icon "" :description "x"}))
        (is (= [{:entity_type "plan.section" :entity_id g1 :plan_id pid :action "update"
                 :data {:title ["G1" "Clothes"] :icon [nil "👕"]}}
                {:entity_type "plan.task" :entity_id a :plan_id pid :action "update"
                 :data {:title ["a" "a2"] :description [nil "x"] :icon ["🧦" nil]}}]
               (subvec (events d) before)))))))


(deftest deleting-a-section-deletes-and-logs-its-tasks
  (with-user
    (fn [d u]
      (let [{:keys [pid g1 a b]} (seed d u)
            before (count (events d))]
        (is (= #{[:plan pid] [:plan pid :structure]} (:topics (tx! d #(plans/delete-section! % u {:id g1})))))
        (is (= [["c"] [["G2" ["d"]]]] (shape (plans/load-plan d pid))))
        (is (= [{:entity_type "plan.task" :entity_id a :plan_id pid :action "delete" :data {:title "a"}}
                {:entity_type "plan.task" :entity_id b :plan_id pid :action "delete" :data {:title "b"}}
                {:entity_type "plan.section" :entity_id g1 :plan_id pid :action "delete" :data {:title "G1"}}]
               (subvec (events d) before)))))))


(deftest deleting-a-plan-cascades
  (with-user
    (fn [d u]
      (let [{:keys [pid c]} (seed d u)]
        (tx! d #(plans/delete-plan! % u {:id pid}))
        (is (empty? (db/q d ["SELECT * FROM plan_task"])))
        (is (empty? (db/q d ["SELECT * FROM plan_section"])))
        (let [before (events d)]
          (is (= #{} (:topics (tx! d #(plans/delete-plan-task! % u {:id c})))))
          (is (= before (events d)) "deleting a missing task is a no-op, so a second tab's delete is harmless"))))))


(deftest a-task-cannot-join-another-plans-section
  (with-user
    (fn [d u]
      (let [{:keys [g1]} (seed d u)
            other (:result (tx! d #(plans/create-plan! % u {:title "Other"})))]
        (is (thrown-with-msg? Exception #"section belongs to another plan"
              (tx! d #(plans/create-plan-task! % u {:plan-id other :section-id g1 :title "x"}))))
        (let [x (:result (tx! d #(plans/create-plan-task! % u {:plan-id other :title "x"})))]
          (is (thrown-with-msg? Exception #"section belongs to another plan"
                (tx! d #(plans/place-plan-task! % u {:id x :section-id g1 :before nil})))))))))


(deftest missing-entities-are-not-found
  (with-user
    (fn [d u]
      (doseq [f [#(plans/update-plan! % u {:id 99 :title "x"})
                 #(plans/update-section! % u {:id 99 :title "x"})
                 #(plans/update-plan-task! % u {:id 99 :title "x"})
                 #(plans/move-section! % u {:id 99 :direction :up})
                 #(plans/place-plan-task! % u {:id 99 :section-id nil :before nil})
                 #(plans/create-section! % u {:plan-id 99 :title "x"})]]
        (is (= :chequer/not-found
               (:type (ex-data (try (tx! d f) (catch Exception e e))))))))))


(deftest plan-index-lists-plans-by-title
  (with-user
    (fn [d u]
      (tx! d #(plans/create-plan! % u {:title "b"}))
      (tx! d #(plans/create-plan! % u {:title "A"}))
      (is (= ["A" "b"] (mapv :plan/title (plans/list-plans d)))))))


(deftest list-plans-counts-sections-and-tasks
  (with-user
    (fn [d u]
      (tx! d (fn [tx]
               (let [pid (plans/create-plan! tx u {:title "P"})
                     g (plans/create-section! tx u {:plan-id pid :title "G"})]
                 (plans/create-plan-task! tx u {:plan-id pid :title "a"})
                 (plans/create-plan-task! tx u {:plan-id pid :section-id g :title "b"}))))
      (tx! d #(plans/create-plan! % u {:title "Q"}))
      (is (= [[1 2] [0 0]]
             (mapv (juxt :plan/section-count :plan/task-count) (plans/list-plans d)))))))
