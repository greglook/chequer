(ns chequer.event
  "The audit log: one event row per user action, written in the action's transaction."
  (:require
    [chequer.db :as db]
    [chequer.time :as time]))


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
