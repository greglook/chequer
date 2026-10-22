(ns chequer.data.plans
  "Plans, plan groups and plan tasks: validation, mutations (each logged as events) and loading."
  (:require
    [chequer.db :as db]
    [chequer.event :as event]
    [chequer.order :as order]
    [chequer.time :as time]
    [clojure.string :as str]
    [jdbc.core :as jdbc]
    [malli.core :as m]
    [malli.error :as me]
    [malli.transform :as mt]))


;; ## Validation

(defn- blank->nil
  [s]
  (when-not (str/blank? s) s))


(def Title
  [:and {:decode/string #(str/trim (str %))}
   :string
   [:fn {:error/message "can't be blank"} (complement str/blank?)]
   [:fn {:error/message "must be a single line"} #(not (re-find #"[\r\n]" %))]
   [:fn {:error/message "must be at most 200 characters"} #(<= (count %) 200)]])


(def Description
  [:maybe {:decode/string #(blank->nil (str %))}
   :string])


(def Icon
  "One emoji, approximated as at most 8 code points (enough for ZWJ sequences) with no ASCII
  letters, digits or whitespace."
  [:maybe {:decode/string #(blank->nil (str/trim (str %)))}
   [:and
    :string
    [:fn {:error/message "must be a single emoji"} #(<= (count %) 8)]
    [:fn {:error/message "must be a single emoji"} #(not (re-find #"[A-Za-z0-9\s]" %))]]])


(def Estimate
  [:maybe {:decode/string #(let [s (blank->nil (str/trim (str %)))]
                             (if (and s (re-matches #"\d{1,9}" s)) (parse-long s) s))}
   [:int {:min 0 :error/message "must be a whole number of minutes"}]])


(defn- validate
  [schema v]
  (let [decoded (m/decode schema v mt/string-transformer)]
    (if-let [explanation (m/explain schema decoded)]
      {:error (first (me/humanize explanation))}
      {:value decoded})))


(defn validate-title
  "{:value cleaned-title} or {:error message}."
  [s]
  (validate Title s))


(defn validate-icon
  "{:value cleaned-icon-or-nil} or {:error message}."
  [s]
  (validate Icon s))


(defn validate-estimate
  "{:value minutes-or-nil} or {:error message}."
  [s]
  (validate Estimate s))


(defn- clean
  "Validate the keys of attrs that have a schema in schemas. Returns the cleaned map of those
  keys, or throws ex-info {:type :chequer/invalid :errors {k message}}."
  [schemas attrs]
  (let [results (into {}
                      (keep (fn [[k schema]]
                              (when (contains? attrs k)
                                [k (validate schema (get attrs k))])))
                      schemas)
        errors (into {} (keep (fn [[k r]] (when-let [e (:error r)] [k e]))) results)]
    (if (seq errors)
      (throw (ex-info "Invalid attributes" {:type :chequer/invalid :errors errors}))
      (update-vals results :value))))


(def ^:private plan-schemas
  {:title Title :description Description :icon Icon})


(def ^:private group-schemas
  {:title Title :icon Icon})


(def ^:private task-schemas
  {:title Title :description Description :icon Icon :estimate Estimate})


;; ## Rows

(defn- row->plan
  [r]
  {:plan/id (:id r)
   :plan/title (:title r)
   :plan/description (:description r)
   :plan/icon (:icon r)
   :plan/created-at (:created_at r)
   :plan/created-by (:created_by r)
   :plan/updated-at (:updated_at r)
   :plan/updated-by (:updated_by r)})


(defn- row->group
  [r]
  {:plan.group/id (:id r)
   :plan.group/plan (:plan_id r)
   :plan.group/order (:order r)
   :plan.group/title (:title r)
   :plan.group/icon (:icon r)})


(defn- row->task
  [r]
  {:plan.task/id (:id r)
   :plan.task/plan (:plan_id r)
   :plan.task/group (:group_id r)
   :plan.task/order (:order r)
   :plan.task/title (:title r)
   :plan.task/description (:description r)
   :plan.task/icon (:icon r)
   :plan.task/estimate (:estimate r)
   :plan.task/created-at (:created_at r)
   :plan.task/created-by (:created_by r)
   :plan.task/updated-at (:updated_at r)
   :plan.task/updated-by (:updated_by r)})


(defn- fetch-row
  [tx table id]
  (jdbc/fetch-one tx [(str "SELECT * FROM " table " WHERE id = ?") id]))


(defn- fetch-existing
  "The row of table with id, or throws ex-info {:type :chequer/not-found}."
  [tx table id]
  (or (fetch-row tx table id)
      (throw (ex-info (str "No " table " " id) {:type :chequer/not-found :table table :id id}))))


(defn- update-row!
  "Set the columns in changes (keys from a fixed schema map) on row id of table."
  [tx table id changes]
  (let [cols (keys changes)]
    (jdbc/execute! tx (into [(str "UPDATE " table " SET "
                                  (str/join ", " (map #(str "\"" (name %) "\" = ?") cols))
                                  " WHERE id = ?")]
                            (concat (map changes cols) [id])))))


(defn- next-order
  [tx table [where & params]]
  (order/append-order
    (map :order (jdbc/fetch tx (into [(str "SELECT \"order\" FROM " table " WHERE " where)] params)))))


;; ## Plans

(defn create-plan!
  "Create a plan from {:title :description :icon} and return its id."
  [tx user attrs]
  (let [v (clean plan-schemas (merge {:title nil} attrs))
        now (time/now-ms)
        id (db/insert! tx :plan (assoc v
                                       :created_at now :created_by (:id user)
                                       :updated_at now :updated_by (:id user)))]
    (event/record! tx {:user user :entity-type "plan" :entity-id id :plan id
                       :action :create :data {:title (:title v)}})
    (db/touch! [:plan id])
    (db/touch! [:plans])
    id))


(defn- update-entity!
  "Apply the validated attrs to row id of table, recording an :update event with the diff.
  Returns the changes. Writes nothing when no value changes."
  [tx user {:keys [table schemas entity-type plan-id-of stamped?]} {:keys [id] :as attrs}]
  (let [old (fetch-existing tx table id)
        v (clean schemas attrs)
        changes (event/diff old v (keys v))
        pid (plan-id-of old)]
    (when (seq changes)
      (update-row! tx table id (cond-> (update-vals changes second)
                                 stamped? (assoc :updated_at (time/now-ms) :updated_by (:id user))))
      (event/record! tx {:user user :entity-type entity-type :entity-id id :plan pid
                         :action :update :data changes})
      (db/touch! [:plan pid]))
    changes))


(defn update-plan!
  "Update the given keys of {:id :title :description :icon}."
  [tx user attrs]
  (when (seq (update-entity! tx user {:table "plan" :schemas plan-schemas :entity-type "plan"
                                      :plan-id-of :id :stamped? true}
                             attrs))
    (db/touch! [:plans]))
  nil)


(defn delete-plan!
  "Delete plan {:id}, with its groups and tasks. Deleting a missing plan does nothing."
  [tx user {:keys [id]}]
  (when-let [old (fetch-row tx "plan" id)]
    (event/record! tx {:user user :entity-type "plan" :entity-id id :plan id
                       :action :delete :data {:title (:title old)}})
    (jdbc/execute! tx ["DELETE FROM plan WHERE id = ?" id])
    (db/touch! [:plan id])
    (db/touch! [:plans]))
  nil)


;; ## Groups

(defn create-group!
  "Create a group in plan :plan-id from {:title :icon}, appended after the others. Returns its id."
  [tx user {:keys [plan-id] :as attrs}]
  (fetch-existing tx "plan" plan-id)
  (let [v (clean group-schemas (merge {:title nil} attrs))
        id (db/insert! tx :plan_group (assoc v
                                             :plan_id plan-id
                                             :order (next-order tx "plan_group" ["plan_id = ?" plan-id])))]
    (event/record! tx {:user user :entity-type "plan.group" :entity-id id :plan plan-id
                       :action :create :data {:title (:title v)}})
    (db/touch! [:plan plan-id])
    id))


(defn update-group!
  "Update the given keys of {:id :title :icon}."
  [tx user attrs]
  (update-entity! tx user {:table "plan_group" :schemas group-schemas :entity-type "plan.group"
                           :plan-id-of :plan_id}
                  attrs)
  nil)


(defn delete-group!
  "Delete group {:id} and its tasks, logging a :delete event for each. A missing group does nothing."
  [tx user {:keys [id]}]
  (when-let [{pid :plan_id :as old} (fetch-row tx "plan_group" id)]
    (doseq [t (jdbc/fetch tx ["SELECT id, title FROM plan_task WHERE group_id = ? ORDER BY \"order\", id" id])]
      (event/record! tx {:user user :entity-type "plan.task" :entity-id (:id t) :plan pid
                         :action :delete :data {:title (:title t)}}))
    (event/record! tx {:user user :entity-type "plan.group" :entity-id id :plan pid
                       :action :delete :data {:title (:title old)}})
    (jdbc/execute! tx ["DELETE FROM plan_group WHERE id = ?" id])
    (db/touch! [:plan pid]))
  nil)


(defn- move-entity!
  [tx user {:keys [table entity-type scope-sql]} {:keys [id direction]} old]
  (let [pid (:plan_id old)]
    (when (order/move tx {:table table :scope-sql (scope-sql old) :id id :direction direction})
      (event/record! tx {:user user :entity-type entity-type :entity-id id :plan pid
                         :action :move :data {:direction direction}})
      (db/touch! [:plan pid]))
    nil))


(defn move-group!
  "Move group {:id} one place in {:direction :up|:down}."
  [tx user {:keys [id] :as attrs}]
  (move-entity! tx user {:table "plan_group" :entity-type "plan.group"
                         :scope-sql (fn [g] ["plan_id = ?" (:plan_id g)])}
                attrs (fetch-existing tx "plan_group" id)))


;; ## Plan tasks

(defn create-plan-task!
  "Create a task in plan :plan-id (and group :group-id, if given) from {:title :description
  :icon :estimate}, appended after its siblings. Returns its id."
  [tx user {:keys [plan-id group-id] :as attrs}]
  (fetch-existing tx "plan" plan-id)
  (let [v (clean task-schemas (merge {:title nil} attrs))
        now (time/now-ms)
        id (db/insert! tx :plan_task
                       (assoc v
                              :plan_id plan-id
                              :group_id group-id
                              :order (next-order tx "plan_task" ["plan_id = ? AND group_id IS ?" plan-id group-id])
                              :created_at now :created_by (:id user)
                              :updated_at now :updated_by (:id user)))]
    (event/record! tx {:user user :entity-type "plan.task" :entity-id id :plan plan-id
                       :action :create :data {:title (:title v)}})
    (db/touch! [:plan plan-id])
    id))


(defn update-plan-task!
  "Update the given keys of {:id :title :description :icon :estimate}."
  [tx user attrs]
  (update-entity! tx user {:table "plan_task" :schemas task-schemas :entity-type "plan.task"
                           :plan-id-of :plan_id :stamped? true}
                  attrs)
  nil)


(defn delete-plan-task!
  "Delete plan task {:id}. A missing task does nothing."
  [tx user {:keys [id]}]
  (when-let [{pid :plan_id :as old} (fetch-row tx "plan_task" id)]
    (event/record! tx {:user user :entity-type "plan.task" :entity-id id :plan pid
                       :action :delete :data {:title (:title old)}})
    (jdbc/execute! tx ["DELETE FROM plan_task WHERE id = ?" id])
    (db/touch! [:plan pid]))
  nil)


(defn move-plan-task!
  "Move plan task {:id} one place in {:direction :up|:down} among the tasks of its group."
  [tx user {:keys [id] :as attrs}]
  (move-entity! tx user {:table "plan_task" :entity-type "plan.task"
                         :scope-sql (fn [t] ["plan_id = ? AND group_id IS ?" (:plan_id t) (:group_id t)])}
                attrs (fetch-existing tx "plan_task" id)))


;; ## Reads

(defn list-plans
  "All plans, ordered by title."
  [db]
  (mapv row->plan (db/q db ["SELECT * FROM plan ORDER BY title COLLATE NOCASE, id"])))


(defn load-plan
  "{:plan p :ungrouped [tasks] :groups [{:group g :tasks [...]}]} in display order, or nil."
  [db id]
  (when-let [p (db/q1 db ["SELECT * FROM plan WHERE id = ?" id])]
    (let [groups (map row->group (db/q db ["SELECT * FROM plan_group WHERE plan_id = ? ORDER BY \"order\", id" id]))
          tasks (group-by :plan.task/group
                          (map row->task (db/q db ["SELECT * FROM plan_task WHERE plan_id = ? ORDER BY \"order\", id" id])))]
      {:plan (row->plan p)
       :ungrouped (vec (get tasks nil))
       :groups (mapv (fn [g] {:group g :tasks (vec (get tasks (:plan.group/id g)))}) groups)})))
