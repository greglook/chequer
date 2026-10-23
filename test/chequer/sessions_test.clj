(ns chequer.sessions-test
  (:require
    [chequer.db :as db]
    [chequer.sessions :as sessions]
    [chequer.test-helpers :as helpers]
    [chequer.time :as time]
    [clojure.core.async :as async]
    [clojure.test :refer [deftest is]]))


(defn- session-count
  [d]
  (:n (db/q1 d ["SELECT count(*) AS n FROM session"])))


(deftest create-and-lookup
  (helpers/with-test-db
    (fn [d]
      (let [user (helpers/create-user! d "greg")
            s (binding [time/*now-ms* (constantly 1000)]
                (sessions/create-session! d (:id user) 500))]
        (is (re-matches #"[A-Za-z0-9_-]{43}" (:session/id s)) "32 random bytes in base64url")
        (is (= {:session/user (:id user) :session/created-at 1000 :session/expires-at 1500}
               (dissoc s :session/id)))
        (is (= s (sessions/lookup d (:session/id s) 1200)))
        (is (nil? (sessions/lookup d "nope" 1200)))
        (is (nil? (sessions/lookup d nil 1200)))
        (is (not= (:session/id s) (:session/id (sessions/create-session! d (:id user) 500))) "ids are random")))))


(deftest expiry-is-enforced
  (helpers/with-test-db
    (fn [d]
      (let [user (helpers/create-user! d "greg")
            s (binding [time/*now-ms* (constantly 1000)]
                (sessions/create-session! d (:id user) 500))]
        (is (some? (sessions/lookup d (:session/id s) 1499)))
        (is (nil? (sessions/lookup d (:session/id s) 1500)))
        (is (nil? (sessions/lookup d (:session/id s) 9999)))))))


(deftest delete-removes-one-session
  (helpers/with-test-db
    (fn [d]
      (let [user (helpers/create-user! d "greg")
            s1 (sessions/create-session! d (:id user) 60000)
            s2 (sessions/create-session! d (:id user) 60000)]
        (sessions/delete! d (:session/id s1))
        (is (nil? (sessions/lookup d (:session/id s1) (time/now-ms))))
        (is (some? (sessions/lookup d (:session/id s2) (time/now-ms))))))))


(deftest sweep-removes-expired-rows
  (helpers/with-test-db
    (fn [d]
      (let [user (helpers/create-user! d "greg")]
        (binding [time/*now-ms* (constantly 1000)]
          (sessions/create-session! d (:id user) 100)
          (sessions/create-session! d (:id user) 200)
          (sessions/create-session! d (:id user) 5000))
        (is (= 2 (sessions/sweep! d 1300)))
        (is (= 1 (session-count d)))
        (is (= 0 (sessions/sweep! d 1300)))))))


(deftest sweeper-runs-periodically
  (helpers/with-test-db
    (fn [d]
      (let [user (helpers/create-user! d "greg")]
        (binding [time/*now-ms* (constantly 1000)]
          (sessions/create-session! d (:id user) 1))
        (let [sweeper (sessions/start-sweeper! d 20)]
          (try
            (loop [tries 100]
              (when (and (pos? (session-count d)) (pos? tries))
                (async/<!! (async/timeout 10))
                (recur (dec tries))))
            (is (= 0 (session-count d)))
            (finally
              (sessions/stop-sweeper! sweeper))))))))
