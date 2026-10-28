(ns chequer.data.lists
  "Lists, list groups and tasks: validation, mutations (each logged as events), task states and
  derived values."
  (:require
    [chequer.data.plans :as plans]
    [chequer.db :as db]
    [chequer.event :as event]
    [chequer.rank :as rank]
    [chequer.time :as time]
    [jdbc.core :as jdbc]))


;; ## Validation

(def Instant
  [:maybe :int])


(def UserRef
  [:maybe :int])


(def ^:private list-schemas
  {:title plans/Title :description plans/Description :icon plans/Icon :due_at Instant})


(def ^:private group-schemas
  {:title plans/Title :icon plans/Icon})


(def ^:private task-schemas
  {:title plans/Title :description plans/Description :icon plans/Icon
   :due_at Instant :assignee_id UserRef})


(def states
  #{:pending :completed :skipped})


;; ## Rows

(defn- row->list
  [r]
  {:list/id (:id r)
   :list/title (:title r)
   :list/description (:description r)
   :list/icon (:icon r)
   :list/due-at (:due_at r)
   :list/archived-at (:archived_at r)
   :list/created-at (:created_at r)
   :list/created-by (:created_by r)
   :list/updated-at (:updated_at r)
   :list/updated-by (:updated_by r)})


(defn- row->group
  [r]
  {:list.group/id (:id r)
   :list.group/list (:list_id r)
   :list.group/source (:source_id r)
   :list.group/rank (:rank r)
   :list.group/title (:title r)
   :list.group/icon (:icon r)})


(defn- row->task
  [r]
  {:task/id (:id r)
   :task/list (:list_id r)
   :task/group (:group_id r)
   :task/source (:source_id r)
   :task/rank (:rank r)
   :task/title (:title r)
   :task/description (:description r)
   :task/icon (:icon r)
   :task/due-at (:due_at r)
   :task/assignee (:assignee_id r)
   :task/state (keyword (:state r))
   :task/created-at (:created_at r)
   :task/created-by (:created_by r)
   :task/updated-at (:updated_at r)
   :task/updated-by (:updated_by r)
   :task/ended-at (:ended_at r)
   :task/ended-by (:ended_by r)})


(defn- touch-list!
  "Touch the topic of list lid, which every change to it touches, and of the parts given:
  :header (the list's row and task counts), :structure (its groups, and which tasks are in
  it and where) or a task id (that task's row)."
  [lid & parts]
  (db/touch! [:list lid])
  (doseq [part parts]
    (db/touch! (if (keyword? part) [:list lid part] [:list lid :task part]))))


(defn- record!
  [tx user entity-type id list-id action data]
  (event/record! tx {:user user :entity-type entity-type :entity-id id :list list-id
                     :action action :data data}))


(defn- update-entity!
  "Apply the validated attrs to row id of table, recording an :update event with the diff,
  merged with (annotate tx changes) when given. Returns the changes. Writes nothing when no
  value changes."
  [tx user {:keys [table schemas entity-type list-id-of part-of stamped? annotate]} {:keys [id] :as attrs}]
  (let [old (plans/fetch-existing tx table id)
        v (plans/clean schemas attrs)
        changes (event/diff old v (keys v))
        lid (list-id-of old)]
    (when (seq changes)
      (plans/update-row! tx table id (cond-> (update-vals changes second)
                                       stamped? (assoc :updated_at (time/now-ms) :updated_by (:id user))))
      (record! tx user entity-type id lid :update (cond-> changes annotate (merge (annotate tx changes))))
      (touch-list! lid (part-of old)))
    changes))


(defn- move-entity!
  "Move row id of table one place in direction. Reordering records no event."
  [tx {:keys [table scope-sql]} {:keys [id direction]} old]
  (when (rank/move tx {:table table :scope-sql (scope-sql old) :id id :direction direction})
    (touch-list! (:list_id old) :structure))
  nil)


;; ## Lists

(defn create-list!
  "Create a list from {:title :description :icon :due_at} and return its id."
  [tx user attrs]
  (let [v (plans/clean list-schemas (merge {:title nil} attrs))
        now (time/now-ms)
        id (db/insert! tx :list (assoc v
                                       :created_at now :created_by (:id user)
                                       :updated_at now :updated_by (:id user)))]
    (record! tx user "list" id id :create {:title (:title v)})
    (touch-list! id :header :structure)
    (db/touch! [:lists])
    id))


(defn update-list!
  "Update the given keys of {:id :title :description :icon :due_at}."
  [tx user attrs]
  (when (seq (update-entity! tx user {:table "list" :schemas list-schemas :entity-type "list"
                                      :list-id-of :id :part-of (constantly :header) :stamped? true}
                             attrs))
    (db/touch! [:lists]))
  nil)


(defn delete-list!
  "Delete list {:id}, with its groups and tasks. Deleting a missing list does nothing."
  [tx user {:keys [id]}]
  (when-let [old (plans/fetch-row tx "list" id)]
    (record! tx user "list" id id :delete {:title (:title old)})
    (jdbc/execute! tx ["DELETE FROM list WHERE id = ?" id])
    (touch-list! id :header :structure)
    (db/touch! [:lists]))
  nil)


(defn- set-archived!
  [tx user id archived-at action]
  (let [old (plans/fetch-existing tx "list" id)]
    (when (not= (nil? archived-at) (nil? (:archived_at old)))
      (plans/update-row! tx "list" id {:archived_at archived-at
                                       :updated_at (time/now-ms) :updated_by (:id user)})
      (record! tx user "list" id id action nil)
      (touch-list! id :header)
      (db/touch! [:lists]))
    nil))


(defn archive-list!
  "Archive list {:id}. Archiving an archived list keeps its original archived-at."
  [tx user {:keys [id]}]
  (set-archived! tx user id (time/now-ms) :archive))


(defn unarchive-list!
  "Unarchive list {:id}."
  [tx user {:keys [id]}]
  (set-archived! tx user id nil :unarchive))


;; ## Groups

(defn create-group!
  "Create a group in list :list-id from {:title :icon}, appended after the others. :source-id,
  if given, is the plan group it was copied from. Returns its id."
  [tx user {:keys [list-id source-id] :as attrs}]
  (plans/fetch-existing tx "list" list-id)
  (let [v (plans/clean group-schemas (merge {:title nil} attrs))
        id (db/insert! tx :list_group (assoc v
                                             :list_id list-id
                                             :source_id source-id
                                             :rank (plans/next-rank tx "list_group" ["list_id = ?" list-id])))]
    (record! tx user "list.group" id list-id :create {:title (:title v)})
    (touch-list! list-id :structure)
    id))


(defn update-group!
  "Update the given keys of {:id :title :icon}."
  [tx user attrs]
  (update-entity! tx user {:table "list_group" :schemas group-schemas :entity-type "list.group"
                           :list-id-of :list_id :part-of (constantly :structure)}
                  attrs)
  nil)


(defn delete-group!
  "Delete group {:id} and its tasks, logging a :delete event for each. A missing group does nothing."
  [tx user {:keys [id]}]
  (when-let [{lid :list_id :as old} (plans/fetch-row tx "list_group" id)]
    (doseq [t (jdbc/fetch tx ["SELECT id, title FROM task WHERE group_id = ? ORDER BY rank, id" id])]
      (record! tx user "task" (:id t) lid :delete {:title (:title t)}))
    (record! tx user "list.group" id lid :delete {:title (:title old)})
    (jdbc/execute! tx ["DELETE FROM list_group WHERE id = ?" id])
    (touch-list! lid :header :structure))
  nil)


(defn move-group!
  "Move group {:id} one place in {:direction :up|:down}."
  [tx _user {:keys [id] :as attrs}]
  (move-entity! tx {:table "list_group"
                    :scope-sql (fn [g] ["list_id = ?" (:list_id g)])}
                attrs (plans/fetch-existing tx "list_group" id)))


;; ## Tasks

(defn create-task!
  "Create a pending task in list :list-id (and group :group-id, if given) from the task
  attributes, appended after its siblings. :source-id, if given, is the plan task it was
  copied from. Returns its id."
  [tx user {:keys [list-id group-id source-id] :as attrs}]
  (plans/fetch-existing tx "list" list-id)
  (let [v (plans/clean task-schemas (merge {:title nil} attrs))
        now (time/now-ms)
        id (db/insert! tx :task
                       (assoc v
                              :list_id list-id
                              :group_id group-id
                              :source_id source-id
                              :rank (plans/next-rank tx "task" ["list_id = ? AND group_id IS ?" list-id group-id])
                              :created_at now :created_by (:id user)
                              :updated_at now :updated_by (:id user)))]
    (record! tx user "task" id list-id :create {:title (:title v)})
    (touch-list! list-id :header :structure)
    id))


(defn- assignee-titles
  "{:assignee [old-title new-title]} naming the users of an :assignee_id change, nil for
  unassigned; {} when the assignee didn't change."
  [tx {ids :assignee_id}]
  (if ids
    {:assignee (mapv #(some->> % (plans/fetch-row tx "user") :title) ids)}
    {}))


(defn update-task!
  "Update the given keys of {:id :title :description :icon :due_at :assignee_id}."
  [tx user attrs]
  (update-entity! tx user {:table "task" :schemas task-schemas :entity-type "task"
                           :list-id-of :list_id :part-of :id :stamped? true
                           :annotate assignee-titles}
                  attrs)
  nil)


(defn delete-task!
  "Delete task {:id}. A missing task does nothing."
  [tx user {:keys [id]}]
  (when-let [{lid :list_id :as old} (plans/fetch-row tx "task" id)]
    (record! tx user "task" id lid :delete {:title (:title old)})
    (jdbc/execute! tx ["DELETE FROM task WHERE id = ?" id])
    (touch-list! lid :header :structure))
  nil)


(defn place-task!
  "Move task {:id} into group :group-id (nil for none) of its list, just before task
  :before of that group (or last, when nil)."
  [tx _user attrs]
  (let [{lid :list_id} (plans/place-in-group! tx {:table "task" :group-table "list_group" :parent-col :list_id} attrs)]
    (touch-list! lid :structure))
  nil)


;; ## States

(defn state-transition
  "The state columns of a task after user moves it to new-state at now."
  [new-state user now]
  (case new-state
    :pending {:state "pending" :ended_at nil :ended_by nil}
    (:completed :skipped) {:state (name new-state) :ended_at now :ended_by (:id user)}))


(defn set-state!
  "Move task {:id} to {:state}. Setting the state it already has writes nothing."
  [tx user {:keys [id state]}]
  (when-not (states state)
    (throw (ex-info "Invalid attributes" {:type :chequer/invalid :errors {:state "is not a task state"}})))
  (let [{old-state :state lid :list_id} (plans/fetch-existing tx "task" id)]
    (when (not= old-state (name state))
      (plans/update-row! tx "task" id (assoc (state-transition state user (time/now-ms))
                                             :updated_at (time/now-ms) :updated_by (:id user)))
      (record! tx user "task" id lid (case state :completed :complete :skipped :skip :update)
               {:state [old-state (name state)]})
      (touch-list! lid :header id))
    nil))


;; ## Derived values

(defn- open?
  [task]
  (= :pending (:task/state task)))


(defn list-done?
  "True when tasks is non-empty and none are pending."
  [tasks]
  (boolean (and (seq tasks) (not-any? open? tasks))))


(defn overdue?
  "True when task is past its due-at and still pending."
  [task now]
  (boolean (and (open? task) (some-> (:task/due-at task) (< now)))))


;; ## Reads

(defn load-task
  "Task id, or nil."
  [db id]
  (some-> (db/q1 db ["SELECT * FROM task WHERE id = ?" id]) row->task))


(defn load-list-header
  "{:list l :counts {state n}} for list id, or nil."
  [db id]
  (when-let [l (db/q1 db ["SELECT * FROM list WHERE id = ?" id])]
    {:list (row->list l)
     :counts (into (zipmap states (repeat 0))
                   (map (juxt (comp keyword :state) :n))
                   (db/q db ["SELECT state, count(*) AS n FROM task WHERE list_id = ? GROUP BY state" id]))}))


(defn load-list-structure
  "{:ungrouped [task-id] :groups [{:group g :task-ids [...]}]} for list id in display order,
  or nil."
  [db id]
  (when (db/q1 db ["SELECT id FROM list WHERE id = ?" id])
    (let [groups (map row->group (db/q db ["SELECT * FROM list_group WHERE list_id = ? ORDER BY rank, id" id]))
          task-ids (update-vals (group-by :group_id (db/q db ["SELECT id, group_id FROM task WHERE list_id = ? ORDER BY rank, id" id]))
                                #(mapv :id %))]
      {:ungrouped (get task-ids nil [])
       :groups (mapv (fn [g] {:group g :task-ids (get task-ids (:list.group/id g) [])}) groups)})))


(defn load-list
  "{:list l :ungrouped [tasks] :groups [{:group g :tasks [...]}]} in display order, or nil."
  [db id]
  (when-let [l (db/q1 db ["SELECT * FROM list WHERE id = ?" id])]
    (let [groups (map row->group (db/q db ["SELECT * FROM list_group WHERE list_id = ? ORDER BY rank, id" id]))
          tasks (group-by :task/group
                          (map row->task (db/q db ["SELECT * FROM task WHERE list_id = ? ORDER BY rank, id" id])))]
      {:list (row->list l)
       :ungrouped (vec (get tasks nil))
       :groups (mapv (fn [g] {:group g :tasks (vec (get tasks (:list.group/id g)))}) groups)})))


(defn list-summary
  "Every list, active and archived, as {:list l :counts {state n} :done? bool :next-due instant},
  where next-due is the earliest due-at among its open tasks."
  [db]
  (let [tasks (group-by :list_id (db/q db ["SELECT list_id, state, due_at FROM task"]))]
    (mapv (fn [l]
            (let [ts (map (fn [t] {:task/state (keyword (:state t)) :task/due-at (:due_at t)})
                          (get tasks (:id l)))]
              {:list (row->list l)
               :counts (merge (zipmap states (repeat 0)) (frequencies (map :task/state ts)))
               :done? (list-done? ts)
               :next-due (some->> (seq (keep :task/due-at (filter open? ts))) (apply min))}))
          (db/q db ["SELECT * FROM list ORDER BY id"]))))
