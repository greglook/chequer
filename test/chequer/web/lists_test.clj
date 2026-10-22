(ns chequer.web.lists-test
  (:require
    [chequer.config :as config]
    [chequer.core :as core]
    [chequer.data.lists :as lists]
    [chequer.db :as db]
    [chequer.live :as live]
    [chequer.test-helpers :as helpers]
    [chequer.time :as time]
    [clojure.core.async :as async]
    [clojure.data.json :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]])
  (:import
    (java.io
      ByteArrayInputStream)))


(def ^:private base-url "http://chequer.test")


(defn- with-app
  "Call (f handler sys) with the full app handler over a migrated temp database."
  [f]
  (helpers/with-test-db
    (fn [d]
      (let [sys {:db d
                 :cfg (config/normalize {"database" {"path" "unused"}
                                         "server" {"base-url" base-url}})
                 :live (live/live)}]
        (try
          (f (core/handler sys) sys)
          (finally
            (live/stop! (:live sys))))))))


(defn- request
  "Call h with a request; opts may carry :signals (sent as JSON) and :tz (the tz cookie)."
  ([h method uri] (request h method uri {}))
  ([h method uri {:keys [signals tz]}]
   (let [[path query] (str/split uri #"\?" 2)
         headers (cond-> {"origin" base-url}
                   tz (assoc "cookie" (str "tz=" tz))
                   signals (assoc "content-type" "application/json"))]
     (h (cond-> {:request-method method :uri path :query-string query :headers headers}
          signals (assoc :body (ByteArrayInputStream.
                                 (.getBytes ^String (json/write-str signals :escape-unicode false) "UTF-8"))))))))


(defn- owner
  "The single-mode owner, provisioned by the first request."
  [h {:keys [db]}]
  (request h :get "/lists/new")
  (db/q1 db ["SELECT * FROM user WHERE subject = 'owner'"]))


(defn- create-list!
  [h title]
  (let [r (request h :post "/lists" {:signals {:title title}})]
    (parse-long (second (re-find #"^/lists/(\d+)$" (get-in r [:headers "Location"]))))))


(defn- task-ids
  [{:keys [db]}]
  (mapv :id (db/q db ["SELECT id FROM task ORDER BY id"])))


(defn- take-frame
  [ch]
  (first (async/alts!! [ch (async/timeout 2000)])))


(defn- ok?
  [r]
  (= 204 (:status r)))


(deftest lists-can-be-created-from-the-new-page
  (with-app
    (fn [h sys]
      (let [r (request h :get "/lists/new")]
        (is (= 200 (:status r)))
        (is (str/includes? (:body r) "action=\"/lists\""))
        (is (str/includes? (:body r) "type=\"datetime-local\"")))
      (let [r (request h :post "/lists" {:signals {:title "  Groceries " :icon "🛒" :due ""}})
            row (db/q1 (:db sys) ["SELECT * FROM list"])]
        (is (= 303 (:status r)))
        (is (= (str "/lists/" (:id row)) (get-in r [:headers "Location"])))
        (is (= ["Groceries" "🛒" nil] [(:title row) (:icon row) (:due_at row)])))
      (let [r (request h :post "/lists" {:signals {:title " "}})]
        (is (str/includes? (:body r) "id=\"new-list-errors\""))
        (is (str/includes? (:body r) "can&#39;t be blank"))))))


(deftest list-page-renders-in-a-live-div
  (with-app
    (fn [h sys]
      (let [lid (create-list! h "Trip")
            u (owner h sys)
            {g :result} (db/with-tx [tx (:db sys)] (lists/create-group! tx u {:list-id lid :title "Clothes"}))]
        (db/with-tx [tx (:db sys)]
          (lists/create-task! tx u {:list-id lid :group-id g :title "Socks"})
          (lists/create-task! tx u {:list-id lid :group-id g :title "Hat"})
          (lists/create-task! tx u {:list-id lid :title "Passport"}))
        (let [{:keys [status body]} (request h :get (str "/lists/" lid))]
          (is (= 200 status))
          (is (str/includes? body "datastar-sse=true"))
          (is (str/includes? body "id=\"live\""))
          (is (str/includes? body (str "id=\"list-" lid "\"")))
          (is (str/includes? body (str "id=\"list-" lid "-header\"")))
          (is (str/includes? body (str "id=\"list-group-" g "\"")))
          (is (str/includes? body "data-task-count=\"2\"") "deleting the group confirms with its task count")
          (is (str/includes? body "0 of 3 done"))
          (is (< (str/index-of body "Passport") (str/index-of body "Clothes")) "ungrouped tasks come first"))
        (is (= 404 (:status (request h :get "/lists/999"))))))))


(deftest checking-a-task-pushes-the-task-and-header-to-other-streams
  (with-app
    (fn [h sys]
      (let [lid (create-list! h "Chores")
            _ (request h :post (str "/lists/" lid "/tasks") {:signals {:title "Dishes"}})
            _ (request h :post (str "/lists/" lid "/tasks") {:signals {:title "Laundry"}})
            [a b] (task-ids sys)
            ch (:body (request h :get (str "/lists/" lid "?datastar-sse=true&datastar-selector=%23live")))]
        (try
          (let [first-frame (take-frame ch)]
            (is (str/includes? first-frame "data: selector #live"))
            (is (str/includes? first-frame (str "id=\"task-" a "\"")))
            (is (not (str/includes? first-frame "<html"))))
          (testing "checking a task"
            (is (ok? (request h :post (str "/tasks/" a "/state?to=completed"))))
            (let [frame (take-frame ch)]
              (is (str/includes? frame "data: mode outer") "only the changed parts are patched")
              (is (str/includes? frame (str "id=\"task-" a "\"")))
              (is (str/includes? frame (str "id=\"list-" lid "-header\"")))
              (is (str/includes? frame "1 of 2 done"))
              (is (str/includes? frame "checked"))
              (is (not (str/includes? frame (str "id=\"task-" b "\""))) "an unchanged task isn't sent")))
          (is (= "completed" (:state (db/q1 (:db sys) ["SELECT state FROM task WHERE id = ?" a]))))
          (testing "undoing the check"
            (is (ok? (request h :post (str "/tasks/" a "/state?to=pending"))))
            (let [frame (take-frame ch)]
              (is (str/includes? frame (str "id=\"task-" a "\"")))
              (is (str/includes? frame "0 of 2 done"))))
          (testing "a delete redirects subscribers to the index"
            (is (ok? (request h :delete (str "/lists/" lid))))
            (let [frame (take-frame ch)]
              (is (str/includes? frame "window.location.href = \"/\""))))
          (finally
            (async/close! ch)))))))


(deftest task-states-and-moves
  (with-app
    (fn [h sys]
      (let [lid (create-list! h "L")
            _ (request h :post (str "/lists/" lid "/tasks") {:signals {:title "a"}})
            _ (request h :post (str "/lists/" lid "/tasks") {:signals {:title "b"}})
            [a b] (task-ids sys)
            state #(:state (db/q1 (:db sys) ["SELECT state FROM task WHERE id = ?" %]))]
        (is (ok? (request h :post (str "/tasks/" a "/state?to=working"))))
        (is (= "working" (state a)))
        (is (ok? (request h :post (str "/tasks/" a "/state?to=abandoned"))))
        (is (= "abandoned" (state a)))
        (is (= 400 (:status (request h :post (str "/tasks/" a "/state?to=done")))))
        (is (= 404 (:status (request h :post "/tasks/999/state?to=completed"))))
        (is (ok? (request h :post (str "/tasks/" b "/move?dir=up"))))
        (is (= [b a] (map :id (db/q (:db sys) ["SELECT id FROM task ORDER BY \"order\""]))))))))


(deftest due-dates-are-read-in-the-viewers-zone
  (with-app
    (fn [h sys]
      (let [lid (create-list! h "L")
            ny "America/New_York"]
        (is (ok? (request h :post (str "/lists/" lid "/tasks") {:signals {:title "x" :due "2026-03-01T09:30"} :tz ny})))
        (let [[t] (task-ids sys)]
          (is (= 1772375400000 (:due_at (db/q1 (:db sys) ["SELECT due_at FROM task WHERE id = ?" t])))
              "09:30 in New York is 14:30 UTC")
          (is (str/includes? (:body (request h :get (str "/lists/" lid) {:tz ny})) "value=\"2026-03-01T09:30\""))
          (is (str/includes? (:body (request h :get (str "/lists/" lid) {:tz "UTC"})) "value=\"2026-03-01T14:30\""))
          (let [r (request h :put (str "/tasks/" t) {:signals {:due "next tuesday"} :tz ny})]
            (is (str/includes? (:body r) (str "id=\"task-" t "-errors\"")))
            (is (str/includes? (:body r) "Due must be a date and time")))
          (is (ok? (request h :put (str "/tasks/" t) {:signals {:due ""}})))
          (is (nil? (:due_at (db/q1 (:db sys) ["SELECT due_at FROM task WHERE id = ?" t])))))
        (is (ok? (request h :put (str "/lists/" lid) {:signals {:due "2026-03-01T09:30"} :tz ny})))
        (is (= 1772375400000 (:due_at (db/q1 (:db sys) ["SELECT due_at FROM list WHERE id = ?" lid]))))))))


(deftest overdue-tasks-are-badged
  (with-app
    (fn [h sys]
      (let [lid (create-list! h "L")]
        (binding [time/*now-ms* (constantly 1772375400000)]
          (request h :post (str "/lists/" lid "/tasks") {:signals {:title "late" :due "2026-02-28T09:30"} :tz "UTC"})
          (let [body (:body (request h :get (str "/lists/" lid)))]
            (is (str/includes? body "overdue"))
            (is (str/includes? body "due 1 day ago")))
          (request h :post (str "/tasks/" (first (task-ids sys)) "/state?to=completed"))
          (is (not (str/includes? (:body (request h :get (str "/lists/" lid))) "overdue"))))))))


(deftest assigned-to-me-hides-other-users-tasks
  (with-app
    (fn [h sys]
      (let [lid (create-list! h "L")
            me (owner h sys)
            other (helpers/create-user! (:db sys) "sam")]
        (is (ok? (request h :post (str "/lists/" lid "/tasks") {:signals {:title "Mine" :assignee (str (:id me))}})))
        (db/with-tx [tx (:db sys)]
          (lists/create-task! tx other {:list-id lid :title "Theirs" :assignee_id (:id other)})
          (lists/create-task! tx other {:list-id lid :title "Nobody's"}))
        (let [all (:body (request h :get (str "/lists/" lid)))
              mine (:body (request h :get (str "/lists/" lid "?mine=1")))]
          (is (every? #(str/includes? all %) ["Mine" "Theirs" "Nobody&#39;s"]))
          (is (str/includes? mine "Mine"))
          (is (not (str/includes? mine "Theirs")))
          (is (not (str/includes? mine "Nobody")))
          (is (str/includes? all "sam") "the assignee is shown"))
        (testing "the stream keeps the filter"
          (let [ch (:body (request h :get (str "/lists/" lid "?mine=1&datastar-sse=true&datastar-selector=%23live")))]
            (try
              (let [frame (take-frame ch)]
                (is (str/includes? frame "Mine"))
                (is (not (str/includes? frame "Theirs"))))
              (db/with-tx [tx (:db sys)]
                (lists/create-task! tx other {:list-id lid :title "Also theirs" :assignee_id (:id other)})
                (lists/create-task! tx other {:list-id lid :title "Mine too" :assignee_id (:id me)}))
              (live/touch! (:live sys) [[:list lid]])
              (let [frame (take-frame ch)]
                (is (str/includes? frame "Mine too"))
                (is (not (str/includes? frame "Also theirs"))))
              (finally
                (async/close! ch)))))
        (let [r (request h :post (str "/lists/" lid "/tasks") {:signals {:title "x" :assignee "999"}})]
          (is (str/includes? (:body r) "Assignee is not a user")))))))


(deftest index-sorts-active-lists-and-archiving-moves-them
  (with-app
    (fn [h sys]
      (let [u (owner h sys)
            day (* 24 60 60 1000)
            now (* 100 day)
            mk (fn [title due updated]
                 (binding [time/*now-ms* (constantly updated)]
                   (:result (db/with-tx [tx (:db sys)] (lists/create-list! tx u {:title title :due_at due})))))
            _later (mk "Later" (+ now (* 5 day)) 1)
            _soon (mk "Soon" (+ now day) 1)
            _old (mk "Undated old" nil 1)
            _new (mk "Undated new" nil 2)
            late (mk "Overdue" (- now day) 1)]
        (binding [time/*now-ms* (constantly now)]
          (let [body (:body (request h :get "/"))
                order (map #(str/index-of body %) ["Overdue" "Soon" "Later" "Undated new" "Undated old"])]
            (is (every? some? order))
            (is (apply < order) "overdue, then by due date, then most recently updated")
            (is (str/includes? body "href=\"/lists?archived=1\""))
            (is (str/includes? body "id=\"live\"")))
          (is (ok? (request h :post (str "/lists/" late "/archive"))))
          (is (some? (:archived_at (db/q1 (:db sys) ["SELECT archived_at FROM list WHERE id = ?" late]))))
          (is (not (str/includes? (:body (request h :get "/")) "Overdue")))
          (let [archived (:body (request h :get "/lists?archived=1"))]
            (is (str/includes? archived "Overdue"))
            (is (not (str/includes? archived "Soon"))))
          (is (str/includes? (:body (request h :get (str "/lists/" late))) "Unarchive"))
          (is (ok? (request h :post (str "/lists/" late "/unarchive"))))
          (is (nil? (:archived_at (db/q1 (:db sys) ["SELECT archived_at FROM list WHERE id = ?" late]))))
          (is (str/includes? (:body (request h :get "/")) "Overdue")))))))


(deftest index-stream-updates-when-a-list-changes
  (with-app
    (fn [h _]
      (let [lid (create-list! h "Chores")
            ch (:body (request h :get "/?datastar-sse=true&datastar-selector=%23live"))]
        (try
          (is (str/includes? (take-frame ch) "0 of 0 done"))
          (request h :post (str "/lists/" lid "/tasks") {:signals {:title "Dishes"}})
          (is (str/includes? (take-frame ch) "0 of 1 done"))
          (create-list! h "Errands")
          (is (str/includes? (take-frame ch) "Errands"))
          (finally
            (async/close! ch)))))))


(deftest groups-can-be-edited-moved-and-deleted
  (with-app
    (fn [h sys]
      (let [d (:db sys)
            lid (create-list! h "L")]
        (is (ok? (request h :post (str "/lists/" lid "/groups") {:signals {:title "G1"}})))
        (is (ok? (request h :post (str "/lists/" lid "/groups") {:signals {:title "G2"}})))
        (let [[g1 g2] (map :id (db/q d ["SELECT id FROM list_group ORDER BY \"order\""]))]
          (is (ok? (request h :post (str "/lists/" lid "/tasks") {:signals {:title "a" :group (str g1)}})))
          (is (= g1 (:group_id (db/q1 d ["SELECT group_id FROM task"]))))
          (is (ok? (request h :post (str "/list-groups/" g2 "/move?dir=up"))))
          (is (= [g2 g1] (map :id (db/q d ["SELECT id FROM list_group ORDER BY \"order\""]))))
          (is (ok? (request h :put (str "/list-groups/" g1) {:signals {:title "Clothes" :icon ""}})))
          (is (= "Clothes" (:title (db/q1 d ["SELECT title FROM list_group WHERE id = ?" g1]))))
          (let [r (request h :post (str "/lists/" lid "/tasks") {:signals {:title " " :group (str g1)}})]
            (is (str/includes? (:body r) (str "id=\"new-task-" lid "-" g1 "-errors\""))))
          (is (ok? (request h :delete (str "/list-groups/" g1))))
          (is (empty? (db/q d ["SELECT * FROM task"])))
          (let [other (create-list! h "Other")]
            (is (= 400 (:status (request h :post (str "/lists/" other "/tasks") {:signals {:title "x" :group (str g2)}}))))))))))
