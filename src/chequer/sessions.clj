(ns chequer.sessions
  "Server-side login sessions, used only in OIDC mode. The session cookie carries only the
  session id, a random token."
  (:require
    [chequer.crypto :as crypto]
    [chequer.db :as db]
    [chequer.time :as time]
    [clojure.core.async :as async]
    [clojure.tools.logging :as log]
    [jdbc.core :as jdbc]))


(def cookie-name
  "The name of the session cookie."
  "chequer-session")


(defn- row->session
  [r]
  {:session/id (:id r)
   :session/user (:user_id r)
   :session/created-at (:created_at r)
   :session/expires-at (:expires_at r)})


(defn create-session!
  "Create a session for the user with user-id that lasts ttl milliseconds, and return it."
  [db user-id ttl]
  (let [now (time/now-ms)
        row {:id (crypto/random-token)
             :user_id user-id
             :created_at now
             :expires_at (+ now ttl)}]
    (db/with-tx [tx db]
      (jdbc/execute! tx ["INSERT INTO session (id, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)"
                         (:id row) (:user_id row) (:created_at row) (:expires_at row)]))
    (row->session row)))


(defn lookup
  "The session with id, or nil if there is none or it had expired at now."
  [db id now]
  (when (string? id)
    (some-> (db/q1 db ["SELECT * FROM session WHERE id = ? AND expires_at > ?" id now])
            (row->session))))


(defn delete!
  "Remove the session with id, if there is one."
  [db id]
  (db/with-tx [tx db]
    (jdbc/execute! tx ["DELETE FROM session WHERE id = ?" id]))
  nil)


(defn sweep!
  "Remove every session that had expired at now, and return how many were removed."
  [db now]
  (:result
    (db/with-tx [tx db]
      (let [n (:n (jdbc/fetch-one tx ["SELECT count(*) AS n FROM session WHERE expires_at <= ?" now]))]
        (jdbc/execute! tx ["DELETE FROM session WHERE expires_at <= ?" now])
        n))))


(def ^:private default-sweep-interval-ms
  "How often the sweeper removes expired sessions."
  (* 60 60 1000))


(defn start-sweeper!
  "Sweep expired sessions from db every interval-ms (default an hour) until stop-sweeper! is
  called on the returned value."
  ([db] (start-sweeper! db default-sweep-interval-ms))
  ([db interval-ms]
   (let [stop (async/chan)]
     (async/thread
       (loop []
         (let [[_ port] (async/alts!! [stop (async/timeout interval-ms)])]
           (when-not (= port stop)
             (try
               (let [n (sweep! db (time/now-ms))]
                 (when (pos? n)
                   (log/info (str "Swept " n " expired session(s)"))))
               (catch Exception e
                 (log/error (str "Session sweep failed: " (ex-message e)))))
             (recur)))))
     stop)))


(defn stop-sweeper!
  "Stop a sweeper started by start-sweeper!."
  [sweeper]
  (async/close! sweeper))
