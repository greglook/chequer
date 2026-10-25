(ns chequer.event
  "The audit log: one event row per user action, written in the action's transaction, and
  the history views read from it."
  (:require
    [chequer.db :as db]
    [chequer.time :as time]
    [clojure.edn :as edn]
    [clojure.string :as str]))


(defn record!
  "Write an event for user's action on an entity. data, an EDN map, is stored with pr-str."
  [tx {:keys [user entity-type entity-id plan action data message] list-id :list}]
  (db/insert! tx :event {:instant (time/now-ms)
                         :user_id (:id user)
                         :entity_type entity-type
                         :entity_id entity-id
                         :plan_id plan
                         :list_id list-id
                         :action (name action)
                         :data (some-> data pr-str)
                         :message message}))


(defn diff
  "Map of each key in ks whose value differs between before and after to [old-value new-value]."
  [before after ks]
  (into {}
        (keep (fn [k]
                (when (not= (get before k) (get after k))
                  [k [(get before k) (get after k)]])))
        ks))


;; ## History

(def ^:private entity-tables
  "The table holding each entity type's rows."
  {"plan" "plan"
   "plan.group" "plan_group"
   "plan.task" "plan_task"
   "list" "list"
   "list.group" "list_group"
   "task" "task"})


(def ^:private history-select
  "The columns of a history row: the event, its user's title, the entity's current title
  (null once deleted) and the data of the entity's :delete event, if any."
  (str "SELECT e.*, u.title AS user_title, CASE e.entity_type "
       (apply str (for [[entity-type table] (sort entity-tables)]
                    (str "WHEN '" entity-type "' THEN (SELECT title FROM " table " WHERE id = e.entity_id) ")))
       "END AS entity_title, "
       "(SELECT d.data FROM event d WHERE d.entity_type = e.entity_type AND d.entity_id = e.entity_id"
       " AND d.action = 'delete' ORDER BY d.id DESC LIMIT 1) AS deleted_data "
       "FROM event e JOIN user u ON u.id = e.user_id "))


(defn- read-data
  [s]
  (some-> s edn/read-string))


(defn- row->event
  [r]
  (let [data (read-data (:data r))]
    {:event/id (:id r)
     :event/instant (:instant r)
     :event/user (:user_id r)
     :event/user-title (:user_title r)
     :event/entity-type (:entity_type r)
     :event/entity-id (:entity_id r)
     :event/entity-title (or (:entity_title r) (:title (read-data (:deleted_data r))) (:title data))
     :event/plan (:plan_id r)
     :event/list (:list_id r)
     :event/action (keyword (:action r))
     :event/data data
     :event/message (:message r)}))


(defn- history
  "Events matching where (an sqlvec fragment on e), newest first, with ids below :before (if
  given), at most :limit (default 50) of them."
  [db [where & params] {:keys [before limit] :or {limit 50}}]
  (mapv row->event
        (db/q db (into [(str history-select "WHERE " where " AND e.id < ? ORDER BY e.id DESC LIMIT ?")]
                       (concat params [(or before Long/MAX_VALUE) limit])))))


(defn plan-history
  "The events of plan plan-id and its groups and tasks, newest first. opts are {:before id
  :limit n}, paging by event id."
  [db plan-id opts]
  (history db ["e.plan_id = ?" plan-id] opts))


(defn list-history
  "The events of list list-id and its groups and tasks, newest first. opts are as for
  plan-history."
  [db list-id opts]
  (history db ["e.list_id = ?" list-id] opts))


(defn entity-history
  "The events of the entity of entity-type (e.g. \"task\") with id, newest first. opts are as
  for plan-history."
  [db entity-type id opts]
  (history db ["e.entity_type = ? AND e.entity_id = ?" entity-type id] opts))


;; ## Descriptions

(def ^:private kind-names
  {"plan" "plan"
   "plan.group" "group"
   "plan.task" "task"
   "list" "list"
   "list.group" "group"
   "task" "task"})


(def ^:private field-names
  {:title "title"
   :description "description"
   :icon "icon"
   :estimate "estimate"
   :due_at "due date"
   :assignee_id "assignee"})


(defn- describe-update
  "The predicate of an :update event's sentence, from its changes, or nil when it has none."
  [kind title changes]
  (let [[old-title new-title] (:title changes)
        others (keep field-names (keys (dissoc changes :title)))]
    (cond
      (= [:state] (keys changes))
      (str "reset " title " to pending")

      (and (:title changes) (empty? others))
      (str "renamed " kind " " old-title " → " new-title)

      (seq others)
      (str "changed the " (str/join ", " (sort others)) " of " kind " " title
           (when (:title changes) (str " and renamed it to " new-title))))))


(defn- describe-add-plan
  [{:keys [plan-title tasks-created tasks-skipped]}]
  (str "added plan " plan-title ": " tasks-created " task" (when (not= 1 tasks-created) "s")
       (when (pos? (or tasks-skipped 0)) (str ", " tasks-skipped " skipped"))))


(defn describe-event
  "A sentence describing event e, e.g. \"greg completed Pack charger\" or \"greg renamed group
  Kitchen → Cooking\". Deleted entities are named by the title stored in their events. Falls
  back to the event's message when no case matches."
  [{:event/keys [user-title entity-type entity-title action data message]}]
  (let [kind (kind-names entity-type entity-type)
        title (or entity-title "(untitled)")
        predicate (case action
                    :create (str "created " kind " " title)
                    :delete (str "deleted " kind " " title)
                    :update (describe-update kind title data)
                    :complete (str "completed " title)
                    :skip (str "skipped " title)
                    :move (some->> (:direction data) name (str "moved " kind " " title " "))
                    :archive (str "archived " kind " " title)
                    :unarchive (str "unarchived " kind " " title)
                    :add-plan (describe-add-plan data)
                    nil)]
    (cond
      predicate (str user-title " " predicate)
      message message
      :else (str user-title " " (name action) " " kind " " title))))
