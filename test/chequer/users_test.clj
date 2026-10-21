(ns chequer.users-test
  (:require
    [chequer.db :as db]
    [chequer.test-helpers :as helpers]
    [chequer.time :as time]
    [chequer.users :as users]
    [clojure.test :refer [deftest is]]))


(defn- with-db
  [f]
  (let [path (helpers/temp-db-path)
        d (db/open path)]
    (try
      (db/migrate! d)
      (f d)
      (finally
        (db/close d)
        (helpers/delete-db-files path)))))


(defn- user-row
  [d id]
  (db/q1 d ["SELECT * FROM user WHERE id = ?" id]))


(def ^:private minute 60000)


(deftest first-call-inserts
  (with-db
    (fn [d]
      (binding [time/*now-ms* (constantly 5000)]
        (let [u (users/ensure-user! d {:subject "owner" :name "greg" :title "Greg"})]
          (is (= {:subject "owner" :name "greg" :title "Greg" :created_at 5000 :last_seen 5000}
                 (dissoc u :id)))
          (is (integer? (:id u)))
          (is (= u (user-row d (:id u)))))))))


(deftest second-call-refreshes-title-and-name
  (with-db
    (fn [d]
      (let [u1 (users/ensure-user! d {:subject "s1" :name "greg" :title "Greg"})
            u2 (users/ensure-user! d {:subject "s1" :name "gregor" :title "Gregor"})]
        (is (= (:id u1) (:id u2)))
        (is (= "Gregor" (:title u2)))
        (is (= "gregor" (:name u2)))
        (is (= u2 (user-row d (:id u1))))
        (is (= 1 (:n (db/q1 d ["SELECT count(*) AS n FROM user"]))))))))


(deftest name-collision-gets-first-free-suffix
  (with-db
    (fn [d]
      (let [names (fn [& subjects]
                    (mapv #(:name (users/ensure-user! d {:subject % :name "greg" :title "G"})) subjects))]
        (is (= ["greg" "greg2" "greg3"] (names "a" "b" "c")))
        (is (= "greg2" (:name (users/ensure-user! d {:subject "b" :name "greg" :title "G"})))
            "a returning user keeps their suffixed name")
        (is (= "greg4" (:name (users/ensure-user! d {:subject "d" :name "greg" :title "G"}))))))))


(deftest suffix-skips-taken-suffixes
  (with-db
    (fn [d]
      (users/ensure-user! d {:subject "a" :name "greg2" :title "G"})
      (users/ensure-user! d {:subject "b" :name "greg" :title "G"})
      (is (= "greg3" (:name (users/ensure-user! d {:subject "c" :name "greg" :title "G"})))))))


(deftest refresh-resolves-collision-with-another-user
  (with-db
    (fn [d]
      (users/ensure-user! d {:subject "a" :name "greg" :title "G"})
      (users/ensure-user! d {:subject "b" :name "bob" :title "B"})
      (is (= "greg2" (:name (users/ensure-user! d {:subject "b" :name "greg" :title "B"})))))))


(deftest touch-last-seen-throttles-writes
  (with-db
    (fn [d]
      (let [u (binding [time/*now-ms* (constantly 0)]
                (users/ensure-user! d {:subject "s" :name "n" :title "t"}))
            last-seen #(:last_seen (user-row d (:id u)))
            touch (fn [ms] (binding [time/*now-ms* (constantly ms)] (users/touch-last-seen! d u)))]
        (touch (* 5 minute))
        (is (= 0 (last-seen)) "exactly 5 minutes: not written")
        (touch (dec (* 5 minute)))
        (is (= 0 (last-seen)) "just under 5 minutes: not written")
        (touch (inc (* 5 minute)))
        (is (= (inc (* 5 minute)) (last-seen)) "just over 5 minutes: written")))))
