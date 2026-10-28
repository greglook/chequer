(ns chequer.event-test
  (:require
    [chequer.db :as db]
    [chequer.event :as event]
    [chequer.test-helpers :as helpers]
    [chequer.time :as time]
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is]]))


(deftest record-writes-an-event-row
  (helpers/with-test-db
    (fn [d]
      (let [user (helpers/create-user! d "u")
            data {:title ["Old" "New"] :icon [nil "x"]}]
        (binding [time/*now-ms* (constantly 1234)]
          (db/with-tx [tx d]
            (event/record! tx {:user user :entity-type "plan.task" :entity-id 42 :plan 7 :list nil
                               :action :update :data data :message "Renamed"})))
        (let [row (db/q1 d ["SELECT * FROM event"])]
          (is (= {:instant 1234 :user_id (:id user) :entity_type "plan.task" :entity_id 42
                  :plan_id 7 :list_id nil :action "update" :message "Renamed"}
                 (dissoc row :id :data)))
          (is (= data (edn/read-string (:data row)))))))))


(deftest record-leaves-absent-data-null
  (helpers/with-test-db
    (fn [d]
      (let [user (helpers/create-user! d "u")]
        (db/with-tx [tx d]
          (event/record! tx {:user user :entity-type "plan" :entity-id 1 :plan 1 :action :create}))
        (is (= {:data nil :message nil :list_id nil}
               (db/q1 d ["SELECT data, message, list_id FROM event"])))))))


(deftest diff-reports-only-changed-keys
  (is (= {:title ["a" "b"] :icon [nil "x"]}
         (event/diff {:title "a" :icon nil :description "d"}
                     {:title "b" :icon "x" :description "d"}
                     [:title :icon :description])))
  (is (= {} (event/diff {:title "a"} {:title "a" :other 1} [:title]))))
