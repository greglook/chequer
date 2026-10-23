(ns chequer.data.add-plan
  "Adding plans to a list: plan groups merge into list groups by title, and plan tasks are
  copied into them unless their destination already holds a task with the same title."
  (:require
    [chequer.data.lists :as lists]
    [chequer.data.plans :as plans]
    [chequer.db :as db]
    [chequer.event :as event]
    [clojure.string :as str]
    [jdbc.core :as jdbc]))


(defn normalize-title
  "The title s as compared when merging: trimmed and lower-cased."
  [s]
  (str/lower-case (str/trim (str s))))


(defn- list-groups
  "Map of normalized title to the id of the list's first group with that title."
  [tx list-id]
  (reduce (fn [m {:keys [id title]}]
            (update m (normalize-title title) #(or % id)))
          {}
          (jdbc/fetch tx ["SELECT id, title FROM list_group WHERE list_id = ? ORDER BY \"order\", id" list-id])))


(defn- list-task-titles
  "Map of destination group id (nil for ungrouped) to the set of normalized task titles in it."
  [tx list-id]
  (reduce (fn [m {:keys [group_id title]}]
            (update m group_id (fnil conj #{}) (normalize-title title)))
          {}
          (jdbc/fetch tx ["SELECT group_id, title FROM task WHERE list_id = ?" list-id])))


(defn- plan-tasks
  "The plan's tasks in display order: ungrouped first, then each group's in group order."
  [tx plan-id groups]
  (let [by-group (group-by :group_id (jdbc/fetch tx ["SELECT * FROM plan_task WHERE plan_id = ? ORDER BY \"order\", id"
                                                     plan-id]))]
    (concat (get by-group nil) (mapcat #(get by-group (:id %)) groups))))


(defn add-plan!
  "Copy plan plan-id into list list-id following the design's merge rules, recording a
  :create event for each new group and task and one :add-plan event on the list. Returns
  {:plan-id :plan-title :groups-created :tasks-created :tasks-skipped}."
  [tx user list-id plan-id]
  (plans/fetch-existing tx "list" list-id)
  (let [plan (plans/fetch-existing tx "plan" plan-id)
        groups (jdbc/fetch tx ["SELECT * FROM plan_group WHERE plan_id = ? ORDER BY \"order\", id" plan-id])
        [dest _ groups-created]
        (reduce (fn [[dest matched n] g]
                  (if-let [id (matched (normalize-title (:title g)))]
                    [(assoc dest (:id g) id) matched n]
                    (let [id (lists/create-group! tx user {:list-id list-id :source-id (:id g)
                                                           :title (:title g) :icon (:icon g)})]
                      [(assoc dest (:id g) id) (assoc matched (normalize-title (:title g)) id) (inc n)])))
                [{} (list-groups tx list-id) 0]
                groups)
        [_ created skipped]
        (reduce (fn [[titles created skipped] t]
                  (let [group-id (when-let [g (:group_id t)] (dest g))
                        title (normalize-title (:title t))]
                    (if (contains? (get titles group-id) title)
                      [titles created (inc skipped)]
                      (do (lists/create-task! tx user {:list-id list-id :group-id group-id :source-id (:id t)
                                                       :title (:title t) :description (:description t)
                                                       :icon (:icon t) :estimate (:estimate t)})
                          [(update titles group-id (fnil conj #{}) title) (inc created) skipped]))))
                [(list-task-titles tx list-id) 0 0]
                (plan-tasks tx plan-id groups))
        result {:plan-id plan-id
                :plan-title (:title plan)
                :groups-created groups-created
                :tasks-created created
                :tasks-skipped skipped}]
    (event/record! tx {:user user :entity-type "list" :entity-id list-id :list list-id
                       :action :add-plan :data result})
    (db/touch! [:list list-id])
    (db/touch! [:lists])
    result))


(defn add-plans!
  "Add each plan of plan-ids to list list-id, in order, within the caller's transaction.
  Returns the vector of add-plan! results."
  [tx user list-id plan-ids]
  (mapv #(add-plan! tx user list-id %) plan-ids))
