(ns chequer.users
  "User provisioning and fuzzy last-seen tracking."
  (:require
    [chequer.db :as db]
    [chequer.time :as time]
    [jdbc.core :as jdbc]))


(def ^:private last-seen-granularity-ms
  "A user's last_seen is only rewritten when it is older than this."
  (* 5 60 1000))


(defn- name-taken?
  "True if a user other than self-id has the given name."
  [tx user-name self-id]
  (some? (jdbc/fetch-one tx ["SELECT 1 FROM user WHERE name = ? AND id IS NOT ?" user-name self-id])))


(defn- free-name
  "The first of base, base2, base3... not used by another user."
  [tx base self-id]
  (first (remove #(name-taken? tx % self-id)
                 (cons base (map #(str base %) (iterate inc 2))))))


(defn- by-id
  [tx id]
  (jdbc/fetch-one tx ["SELECT * FROM user WHERE id = ?" id]))


(defn ensure-user!
  "Find the user with subject, creating them if needed and refreshing name and title
  otherwise. A name used by another user gets the first free numeric suffix. Returns the user row."
  [db {user-name :name :keys [subject title]}]
  (:result
    (db/with-tx [tx db]
      (if-let [existing (jdbc/fetch-one tx ["SELECT * FROM user WHERE subject = ?" subject])]
        (let [new-name (free-name tx user-name (:id existing))]
          (jdbc/execute! tx ["UPDATE user SET name = ?, title = ? WHERE id = ?" new-name title (:id existing)])
          (by-id tx (:id existing)))
        (let [now (time/now-ms)
              id (db/insert! tx :user {:subject subject
                                       :name (free-name tx user-name nil)
                                       :title title
                                       :created_at now
                                       :last_seen now})]
          (by-id tx id))))))


(defn find-user
  "The user row with id, or nil."
  [db id]
  (db/q1 db ["SELECT * FROM user WHERE id = ?" id]))


(defn list-users
  "Every user as {:id :name :title}, ordered by title."
  [db]
  (db/q db ["SELECT id, name, title FROM user ORDER BY title, id"]))


(defn touch-last-seen!
  "Record that user (a user row) was just seen, writing only if their last_seen is more than
  five minutes old. Returns the user row, updated when it was written."
  [db {:keys [id last_seen] :as user}]
  (let [now (time/now-ms)]
    (if (> (- now last_seen) last-seen-granularity-ms)
      (do (db/with-tx [tx db]
            (jdbc/execute! tx ["UPDATE user SET last_seen = ? WHERE id = ?" now id]))
          (assoc user :last_seen now))
      user)))
