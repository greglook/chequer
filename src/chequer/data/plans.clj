(ns chequer.data.plans
  "Plans, plan sections and plan tasks: validation, mutations (each logged as events) and loading."
  (:require
    [chequer.db :as db]
    [chequer.event :as event]
    [chequer.rank :as rank]
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


(def ^:private keycap
  "A digit keycap emoji, such as 1️⃣."
  (re-pattern "[0-9]️?⃣"))


(def Icon
  "One emoji, approximated as at most 8 code points (enough for ZWJ sequences) with no ASCII
  letters, whitespace or digits outside a keycap."
  [:maybe {:decode/string #(blank->nil (str/trim (str %)))}
   [:and
    :string
    [:fn {:error/message "must be a single emoji"} #(<= (count %) 8)]
    [:fn {:error/message "must be a single emoji"} #(not (re-find #"[A-Za-z0-9\s]" (str/replace % keycap "")))]]])


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


(defn clean
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


(def ^:private section-schemas
  {:title Title :icon Icon})


(def ^:private task-schemas
  {:title Title :description Description :icon Icon})


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


(defn- row->section
  [r]
  {:plan.section/id (:id r)
   :plan.section/plan (:plan_id r)
   :plan.section/rank (:rank r)
   :plan.section/title (:title r)
   :plan.section/icon (:icon r)})


(defn- row->task
  [r]
  {:plan.task/id (:id r)
   :plan.task/plan (:plan_id r)
   :plan.task/section (:section_id r)
   :plan.task/rank (:rank r)
   :plan.task/title (:title r)
   :plan.task/description (:description r)
   :plan.task/icon (:icon r)
   :plan.task/created-at (:created_at r)
   :plan.task/created-by (:created_by r)
   :plan.task/updated-at (:updated_at r)
   :plan.task/updated-by (:updated_by r)})


(defn fetch-row
  [tx table id]
  (jdbc/fetch-one tx [(str "SELECT * FROM " table " WHERE id = ?") id]))


(defn fetch-existing
  "The row of table with id, or throws ex-info {:type :chequer/not-found}."
  [tx table id]
  (or (fetch-row tx table id)
      (throw (ex-info (str "No " table " " id) {:type :chequer/not-found :table table :id id}))))


(defn update-row!
  "Set the columns in changes (keys from a fixed schema map) on row id of table."
  [tx table id changes]
  (let [cols (keys changes)]
    (jdbc/execute! tx (into [(str "UPDATE " table " SET "
                                  (str/join ", " (map #(str "\"" (name %) "\" = ?") cols))
                                  " WHERE id = ?")]
                            (concat (map changes cols) [id])))))


(defn next-rank
  [tx table [where & params]]
  (rank/append-rank
    (map :rank (jdbc/fetch tx (into [(str "SELECT rank FROM " table " WHERE " where)] params)))))


;; ## Plans

(defn- touch-plan!
  "Touch the topic of plan pid, which every change to it touches, and of the parts given:
  :header (the plan's row), :structure (its sections, and which tasks are in it and where) or
  a task id (that task's row)."
  [pid & parts]
  (db/touch! [:plan pid])
  (doseq [part parts]
    (db/touch! (if (keyword? part) [:plan pid part] [:plan pid :task part]))))


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
    (touch-plan! id :header :structure)
    (db/touch! [:plans])
    id))


(defn- update-entity!
  "Apply the validated attrs to row id of table, recording an :update event with the diff.
  Returns the changes. Writes nothing when no value changes."
  [tx user {:keys [table schemas entity-type plan-id-of part-of stamped?]} {:keys [id] :as attrs}]
  (let [old (fetch-existing tx table id)
        v (clean schemas attrs)
        changes (event/diff old v (keys v))
        pid (plan-id-of old)]
    (when (seq changes)
      (update-row! tx table id (cond-> (update-vals changes second)
                                 stamped? (assoc :updated_at (time/now-ms) :updated_by (:id user))))
      (event/record! tx {:user user :entity-type entity-type :entity-id id :plan pid
                         :action :update :data changes})
      (touch-plan! pid (part-of old)))
    changes))


(defn update-plan!
  "Update the given keys of {:id :title :description :icon}."
  [tx user attrs]
  (when (seq (update-entity! tx user {:table "plan" :schemas plan-schemas :entity-type "plan"
                                      :plan-id-of :id :part-of (constantly :header) :stamped? true}
                             attrs))
    (db/touch! [:plans]))
  nil)


(defn delete-plan!
  "Delete plan {:id}, with its sections and tasks. Deleting a missing plan does nothing."
  [tx user {:keys [id]}]
  (when-let [old (fetch-row tx "plan" id)]
    (event/record! tx {:user user :entity-type "plan" :entity-id id :plan id
                       :action :delete :data {:title (:title old)}})
    (jdbc/execute! tx ["DELETE FROM plan WHERE id = ?" id])
    (touch-plan! id :header :structure)
    (db/touch! [:plans]))
  nil)


;; ## Sections

(defn create-section!
  "Create a section in plan :plan-id from {:title :icon}, appended after the others. Returns its id."
  [tx user {:keys [plan-id] :as attrs}]
  (fetch-existing tx "plan" plan-id)
  (let [v (clean section-schemas (merge {:title nil} attrs))
        id (db/insert! tx :plan_section (assoc v
                                               :plan_id plan-id
                                               :rank (next-rank tx "plan_section" ["plan_id = ?" plan-id])))]
    (event/record! tx {:user user :entity-type "plan.section" :entity-id id :plan plan-id
                       :action :create :data {:title (:title v)}})
    (touch-plan! plan-id :structure)
    id))


(defn update-section!
  "Update the given keys of {:id :title :icon}."
  [tx user attrs]
  (update-entity! tx user {:table "plan_section" :schemas section-schemas :entity-type "plan.section"
                           :plan-id-of :plan_id :part-of (constantly :structure)}
                  attrs)
  nil)


(defn delete-section!
  "Delete section {:id} and its tasks, logging a :delete event for each. A missing section does nothing."
  [tx user {:keys [id]}]
  (when-let [{pid :plan_id :as old} (fetch-row tx "plan_section" id)]
    (doseq [t (jdbc/fetch tx ["SELECT id, title FROM plan_task WHERE section_id = ? ORDER BY rank, id" id])]
      (event/record! tx {:user user :entity-type "plan.task" :entity-id (:id t) :plan pid
                         :action :delete :data {:title (:title t)}}))
    (event/record! tx {:user user :entity-type "plan.section" :entity-id id :plan pid
                       :action :delete :data {:title (:title old)}})
    (jdbc/execute! tx ["DELETE FROM plan_section WHERE id = ?" id])
    (touch-plan! pid :structure))
  nil)


(defn- move-entity!
  "Move row id of table one place in direction. Reordering records no event."
  [tx {:keys [table scope-sql]} {:keys [id direction]} old]
  (when (rank/move tx {:table table :scope-sql (scope-sql old) :id id :direction direction})
    (touch-plan! (:plan_id old) :structure))
  nil)


(defn move-section!
  "Move section {:id} one place in {:direction :up|:down}."
  [tx _user {:keys [id] :as attrs}]
  (move-entity! tx {:table "plan_section"
                    :scope-sql (fn [g] ["plan_id = ?" (:plan_id g)])}
                attrs (fetch-existing tx "plan_section" id)))


;; ## Plan tasks

(defn create-plan-task!
  "Create a task in plan :plan-id (and section :section-id, if given) from {:title :description
  :icon}, appended after its siblings. Returns its id."
  [tx user {:keys [plan-id section-id] :as attrs}]
  (fetch-existing tx "plan" plan-id)
  (let [v (clean task-schemas (merge {:title nil} attrs))
        now (time/now-ms)
        id (db/insert! tx :plan_task
                       (assoc v
                              :plan_id plan-id
                              :section_id section-id
                              :rank (next-rank tx "plan_task" ["plan_id = ? AND section_id IS ?" plan-id section-id])
                              :created_at now :created_by (:id user)
                              :updated_at now :updated_by (:id user)))]
    (event/record! tx {:user user :entity-type "plan.task" :entity-id id :plan plan-id
                       :action :create :data {:title (:title v)}})
    (touch-plan! plan-id :structure)
    id))


(defn update-plan-task!
  "Update the given keys of {:id :title :description :icon}."
  [tx user attrs]
  (update-entity! tx user {:table "plan_task" :schemas task-schemas :entity-type "plan.task"
                           :plan-id-of :plan_id :part-of :id :stamped? true}
                  attrs)
  nil)


(defn delete-plan-task!
  "Delete plan task {:id}. A missing task does nothing."
  [tx user {:keys [id]}]
  (when-let [{pid :plan_id :as old} (fetch-row tx "plan_task" id)]
    (event/record! tx {:user user :entity-type "plan.task" :entity-id id :plan pid
                       :action :delete :data {:title (:title old)}})
    (jdbc/execute! tx ["DELETE FROM plan_task WHERE id = ?" id])
    (touch-plan! pid :structure))
  nil)


(defn place-in-section!
  "Move task {:id} of table into section :section-id (nil for none) of the same plan or list,
  named by its parent-col, just before task :before of that section (or last, when nil).
  Returns the task's row as it was. Reordering records no event."
  [tx {:keys [table section-table parent-col]} {:keys [id section-id before]}]
  (let [old (fetch-existing tx table id)]
    (when section-id
      (fetch-existing tx section-table section-id))
    (rank/place tx {:table table
                    :scope-sql [(str (name parent-col) " = ? AND section_id IS ?") (get old parent-col) section-id]
                    :id id :before before})
    (when (not= section-id (:section_id old))
      (update-row! tx table id {:section_id section-id}))
    old))


(defn place-plan-task!
  "Move plan task {:id} into section :section-id (nil for none) of its plan, just before task
  :before of that section (or last, when nil)."
  [tx _user attrs]
  (let [{pid :plan_id} (place-in-section! tx {:table "plan_task" :section-table "plan_section" :parent-col :plan_id} attrs)]
    (touch-plan! pid :structure))
  nil)


;; ## Reads

(defn list-plans
  "All plans, ordered by title, with their :plan/section-count and :plan/task-count."
  [db]
  (mapv #(assoc (row->plan %) :plan/section-count (:section_count %) :plan/task-count (:task_count %))
        (db/q db ["SELECT p.*,
                          (SELECT COUNT(*) FROM plan_section s WHERE s.plan_id = p.id) AS section_count,
                          (SELECT COUNT(*) FROM plan_task t WHERE t.plan_id = p.id) AS task_count
                   FROM plan p ORDER BY p.title COLLATE NOCASE, p.id"])))


(defn load-plan-task
  "Plan task id, or nil."
  [db id]
  (some-> (db/q1 db ["SELECT * FROM plan_task WHERE id = ?" id]) row->task))


(defn load-plan-header
  "Plan id without its sections and tasks, or nil."
  [db id]
  (some-> (db/q1 db ["SELECT * FROM plan WHERE id = ?" id]) row->plan))


(defn load-plan-structure
  "{:unsectioned [task-id] :sections [{:section g :task-ids [...]}]} for plan id in display order,
  or nil."
  [db id]
  (when (db/q1 db ["SELECT id FROM plan WHERE id = ?" id])
    (let [sections (map row->section (db/q db ["SELECT * FROM plan_section WHERE plan_id = ? ORDER BY rank, id" id]))
          task-ids (update-vals (group-by :section_id (db/q db ["SELECT id, section_id FROM plan_task WHERE plan_id = ? ORDER BY rank, id" id]))
                                #(mapv :id %))]
      {:unsectioned (get task-ids nil [])
       :sections (mapv (fn [g] {:section g :task-ids (get task-ids (:plan.section/id g) [])}) sections)})))


(defn load-plan
  "{:plan p :unsectioned [tasks] :sections [{:section g :tasks [...]}]} in display order, or nil."
  [db id]
  (when-let [p (db/q1 db ["SELECT * FROM plan WHERE id = ?" id])]
    (let [sections (map row->section (db/q db ["SELECT * FROM plan_section WHERE plan_id = ? ORDER BY rank, id" id]))
          tasks (group-by :plan.task/section
                          (map row->task (db/q db ["SELECT * FROM plan_task WHERE plan_id = ? ORDER BY rank, id" id])))]
      {:plan (row->plan p)
       :unsectioned (vec (get tasks nil))
       :sections (mapv (fn [g] {:section g :tasks (vec (get tasks (:plan.section/id g)))}) sections)})))
