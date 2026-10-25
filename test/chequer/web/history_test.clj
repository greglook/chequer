(ns chequer.web.history-test
  (:require
    [chequer.config :as config]
    [chequer.core :as core]
    [chequer.data.add-plan :as add-plan]
    [chequer.data.lists :as lists]
    [chequer.data.plans :as plans]
    [chequer.db :as db]
    [chequer.event :as event]
    [chequer.live :as live]
    [chequer.test-helpers :as helpers]
    [chequer.time :as time]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]))


(def ^:private base-url "http://chequer.test")


(defn- with-app
  "Call (f handler db user) with the full app handler over a migrated temp database, and the
  single-mode owner user."
  [f]
  (helpers/with-test-db
    (fn [d]
      (let [sys {:db d
                 :cfg (config/normalize {"database" {"path" "unused"}
                                         "server" {"base-url" base-url}
                                         "auth.single" {"name" "greg" "title" "Greg"}})
                 :live (live/live)}
            h (core/handler sys)]
        (try
          ;; The first request provisions the owner.
          (h {:request-method :get :uri "/plans" :headers {}})
          (f h d (db/q1 d ["SELECT * FROM user"]))
          (finally
            (live/stop! (:live sys))))))))


(defn- request
  ([h uri] (request h uri {}))
  ([h uri headers]
   (let [[path query] (str/split uri #"\?" 2)]
     (h {:request-method :get :uri path :query-string query :headers headers}))))


(defmacro ^:private tx
  [d & body]
  `(:result (db/with-tx [~'tx ~d] ~@body)))


;; ## Descriptions

(defn- describe
  [action entity-type data]
  (event/describe-event {:event/user-title "greg" :event/entity-type entity-type :event/entity-title "Pack charger"
                         :event/action action :event/data data}))


(deftest describe-event-sentences
  (is (= "greg completed Pack charger" (describe :complete "task" {:state ["pending" "completed"]})))
  (is (= "greg skipped Pack charger" (describe :skip "task" {:state ["pending" "skipped"]})))
  (is (= "greg reset Pack charger to pending" (describe :update "task" {:state ["completed" "pending"]})))
  (is (= "greg created task Pack charger" (describe :create "plan.task" {:title "Pack charger"})))
  (is (= "greg deleted task Pack charger" (describe :delete "task" {:title "Pack charger"})))
  (is (= "greg renamed group Kitchen → Cooking" (describe :update "list.group" {:title ["Kitchen" "Cooking"]})))
  (is (= "greg changed the description, estimate of task Pack charger"
         (describe :update "plan.task" {:estimate [nil 5] :description [nil "x"]})))
  (is (= "greg changed the due date of task Pack charger and renamed it to Pack cable"
         (describe :update "task" {:due_at [nil 1] :title ["Pack charger" "Pack cable"]})))
  (is (= "greg moved task Pack charger up" (describe :move "task" {:direction :up})))
  (is (= "greg archived list Pack charger" (describe :archive "list" nil)))
  (is (= "greg added plan Packing: 12 tasks, 3 skipped"
         (describe :add-plan "list" {:plan-title "Packing" :tasks-created 12 :tasks-skipped 3})))
  (testing "falls back to the message, then the action"
    (is (= "Something odd" (event/describe-event {:event/user-title "greg" :event/entity-type "task"
                                                  :event/action :frob :event/message "Something odd"})))
    (is (= "greg frob task Pack charger" (describe :frob "task" nil)))))


;; ## Queries

(deftest history-is-scoped-to-its-plan
  (helpers/with-test-db
    (fn [d]
      (let [u (helpers/create-user! d "greg")
            p1 (tx d (plans/create-plan! tx u {:title "Packing"}))
            p2 (tx d (plans/create-plan! tx u {:title "Cleaning"}))]
        (tx d (plans/create-plan-task! tx u {:plan-id p1 :title "Socks"}))
        (tx d (plans/create-plan-task! tx u {:plan-id p2 :title "Vacuum"}))
        (is (= ["Socks" "Packing"] (map :event/entity-title (event/plan-history d p1 {}))))
        (is (= ["Vacuum" "Cleaning"] (map :event/entity-title (event/plan-history d p2 {}))))
        (is (every? #(= "greg" (:event/user-title %)) (event/plan-history d p1 {})))))))


(deftest history-pages-by-event-id
  (helpers/with-test-db
    (fn [d]
      (let [u (helpers/create-user! d "greg")
            lid (tx d (lists/create-list! tx u {:title "Trip"}))]
        (doseq [i (range 5)]
          (tx d (lists/create-task! tx u {:list-id lid :title (str "T" i)})))
        (let [page1 (event/list-history d lid {:limit 2})
              page2 (event/list-history d lid {:before (:event/id (last page1)) :limit 2})
              page3 (event/list-history d lid {:before (:event/id (last page2)) :limit 2})]
          (is (= ["T4" "T3"] (map :event/entity-title page1)))
          (is (= ["T2" "T1"] (map :event/entity-title page2)))
          (is (= ["T0" "Trip"] (map :event/entity-title page3)))
          (is (empty? (event/list-history d lid {:before (:event/id (last page3))}))))))))


(deftest deleted-entities-keep-their-titles
  (helpers/with-test-db
    (fn [d]
      (let [u (helpers/create-user! d "greg")
            lid (tx d (lists/create-list! tx u {:title "Trip"}))
            tid (tx d (lists/create-task! tx u {:list-id lid :title "Pack charger"}))]
        (tx d (lists/set-state! tx u {:id tid :state :completed}))
        (tx d (lists/delete-task! tx u {:id tid}))
        (is (= ["greg deleted task Pack charger"
                "greg completed Pack charger"
                "greg created task Pack charger"]
               (map event/describe-event (event/entity-history d "task" tid {}))))))))


;; ## Pages

(deftest plan-and-list-pages-link-to-their-history
  (with-app
    (fn [h d u]
      (let [pid (tx d (plans/create-plan! tx u {:title "Packing"}))
            lid (tx d (lists/create-list! tx u {:title "Trip"}))
            tid (tx d (lists/create-task! tx u {:list-id lid :title "Socks"}))]
        (is (str/includes? (:body (request h (str "/plans/" pid))) (str "href=\"/plans/" pid "/history\"")))
        (let [body (:body (request h (str "/lists/" lid)))]
          (is (str/includes? body (str "href=\"/lists/" lid "/history\"")))
          (is (str/includes? body (str "href=\"/tasks/" tid "/history\""))))))))


(deftest plan-history-page-renders-events-in-the-viewers-zone
  (with-app
    (fn [h d u]
      (let [pid (binding [time/*now-ms* (constantly 0)]
                  (tx d (plans/create-plan! tx u {:title "Packing"})))
            other (tx d (plans/create-plan! tx u {:title "Cleaning"}))]
        (tx d (plans/create-group! tx u {:plan-id pid :title "Kitchen"}))
        (tx d (plans/create-plan-task! tx u {:plan-id other :title "Vacuum"}))
        (let [g (:id (db/q1 d ["SELECT id FROM plan_group"]))]
          (tx d (plans/update-group! tx u {:id g :title "Cooking"})))
        (let [{:keys [status body]} (request h (str "/plans/" pid "/history")
                                             {"cookie" "tz=America/New_York"})]
          (is (= 200 status))
          (is (str/includes? body "Packing history"))
          (is (str/includes? body "Greg renamed group Kitchen → Cooking"))
          (is (str/includes? body "Greg created plan Packing"))
          (is (str/includes? body "Wed 31 Dec 1969, 19:00"))
          (is (not (str/includes? body "Vacuum")))
          (is (not (str/includes? body "Load more"))))))))


(deftest deleted-task-history-still-renders-its-title
  (with-app
    (fn [h d u]
      (let [lid (tx d (lists/create-list! tx u {:title "Trip"}))
            tid (tx d (lists/create-task! tx u {:list-id lid :title "Pack charger"}))]
        (tx d (lists/set-state! tx u {:id tid :state :completed}))
        (tx d (lists/delete-task! tx u {:id tid}))
        (let [{:keys [status body]} (request h (str "/tasks/" tid "/history"))]
          (is (= 200 status))
          (is (str/includes? body "<h1>Pack charger history</h1>"))
          (is (str/includes? body "Greg completed Pack charger"))
          (is (str/includes? body "Greg deleted task Pack charger"))
          (is (str/includes? body (str "href=\"/lists/" lid "\""))))
        (is (str/includes? (:body (request h (str "/lists/" lid "/history"))) "Greg deleted task Pack charger"))))))


(deftest history-pages-load-more
  (with-app
    (fn [h d u]
      (let [pid (tx d (plans/create-plan! tx u {:title "Packing"}))
            lid (tx d (lists/create-list! tx u {:title "Trip"}))]
        (doseq [i (range 60)]
          (tx d (plans/create-plan-task! tx u {:plan-id pid :title (str "Item " i)})))
        ;; The list's create, 60 task copies and one :add-plan event: two pages.
        (tx d (add-plan/add-plan! tx u lid pid))
        (let [body (:body (request h (str "/lists/" lid "/history")))
              ids (map parse-long (re-seq #"(?<=id=\"event-)\d+" body))
              before (second (re-find #"\?before=(\d+)" body))]
          (is (= 50 (count ids)))
          (is (str/includes? body "Greg added plan Packing: 60 tasks"))
          (is (str/includes? body "Load more"))
          (is (= (str (last ids)) before))
          (let [r (request h (str "/lists/" lid "/history?before=" before) {"datastar-request" "true"})
                more-ids (map parse-long (re-seq #"(?<=id=\"event-)\d+" (:body r)))]
            (is (= "text/event-stream" (get-in r [:headers "Content-Type"])))
            (is (str/includes? (:body r) "data: selector #history"))
            (is (str/includes? (:body r) "data: mode append"))
            (is (= 12 (count more-ids)))
            (is (every? #(< % (last ids)) more-ids))
            (is (str/includes? (:body r) "Greg created list Trip"))
            (is (str/includes? (:body r) "<p id=\"history-more\"></p>"))))))))


(deftest history-pages-reject-bad-requests
  (with-app
    (fn [h d u]
      (let [lid (tx d (lists/create-list! tx u {:title "Trip"}))]
        (is (= 404 (:status (request h "/plans/999/history"))))
        (is (= 404 (:status (request h "/lists/999/history"))))
        (is (= 404 (:status (request h "/tasks/999/history"))))
        (is (= 400 (:status (request h (str "/lists/" lid "/history?before=abc")))))))))
