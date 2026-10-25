(ns chequer.web.lists-test
  (:require
    [chequer.config :as config]
    [chequer.core :as core]
    [chequer.data.lists :as lists]
    [chequer.data.plans :as plans]
    [chequer.db :as db]
    [chequer.live :as live]
    [chequer.test-helpers :as helpers]
    [chequer.time :as time]
    [clojure.core.async :as async]
    [clojure.data.json :as json]
    [clojure.set :as set]
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
  "Call h with a request; opts may carry :signals (sent as JSON), :form (a urlencoded body
  string) and :tz (the tz cookie)."
  ([h method uri] (request h method uri {}))
  ([h method uri {:keys [signals form tz]}]
   (let [[path query] (str/split uri #"\?" 2)
         headers (cond-> {"origin" base-url}
                   tz (assoc "cookie" (str "tz=" tz))
                   signals (assoc "content-type" "application/json")
                   form (assoc "content-type" "application/x-www-form-urlencoded"))
         body (or (some-> signals (json/write-str :escape-unicode false)) form)]
     (h (cond-> {:request-method method :uri path :query-string query :headers headers}
          body (assoc :body (ByteArrayInputStream. (.getBytes ^String body "UTF-8"))))))))


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
        (is (str/includes? (:body r) "type=\"date\"")))
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
          (is (str/includes? body (str "id=\"list-group-" g "-header\"")))
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


(defn- part-ids
  "The ids of the parts a patch frame replaces."
  [frame]
  (set (map second (re-seq #"id=\"(task-\d+|list-\d+-header|list-group-\d+-header)\"" frame))))


(deftest a-state-change-sends-exactly-the-task-and-the-header
  (with-app
    (fn [h sys]
      (let [lid (create-list! h "Trip")
            _ (request h :post (str "/lists/" lid "/tasks") {:signals {:title "Passport"}})
            _ (request h :post (str "/lists/" lid "/groups") {:signals {:title "Clothes"}})
            gid (:id (db/q1 (:db sys) ["SELECT id FROM list_group"]))
            _ (request h :post (str "/lists/" lid "/tasks") {:signals {:title "Socks" :group (str gid)}})
            _ (request h :post (str "/lists/" lid "/tasks") {:signals {:title "Shirts" :group (str gid)}})
            [_ socks shirts] (task-ids sys)
            streams (repeatedly 2 #(:body (request h :get (str "/lists/" lid "?datastar-sse=true&datastar-selector=%23live"))))]
        (try
          (doseq [ch streams]
            (is (set/subset? #{(str "task-" socks) (str "task-" shirts) (str "list-" lid "-header") (str "list-group-" gid "-header")}
                             (part-ids (take-frame ch)))
                "the first frame is the whole list"))
          (is (ok? (request h :post (str "/tasks/" socks "/state?to=completed"))))
          (doseq [ch streams]
            (let [frame (take-frame ch)]
              (is (str/includes? frame "data: mode outer"))
              (is (not (str/includes? frame "data: selector")) "parts are replaced by id")
              (is (= #{(str "task-" socks) (str "list-" lid "-header")} (part-ids frame)))))
          (testing "renaming a task sends only the task"
            (is (ok? (request h :put (str "/tasks/" shirts) {:signals {:title "Shirts x3"}})))
            (doseq [ch streams]
              (is (= #{(str "task-" shirts)} (part-ids (take-frame ch))))))
          (finally
            (run! async/close! streams)))))))


(deftest task-states-and-moves
  (with-app
    (fn [h sys]
      (let [lid (create-list! h "L")
            _ (request h :post (str "/lists/" lid "/tasks") {:signals {:title "a"}})
            _ (request h :post (str "/lists/" lid "/tasks") {:signals {:title "b"}})
            [a b] (task-ids sys)
            state #(:state (db/q1 (:db sys) ["SELECT state FROM task WHERE id = ?" %]))]
        (is (ok? (request h :post (str "/tasks/" a "/state?to=skipped"))))
        (is (= "skipped" (state a)))
        (is (= 400 (:status (request h :post (str "/tasks/" a "/state?to=working")))))
        (is (= 400 (:status (request h :post (str "/tasks/" a "/state?to=done")))))
        (is (= 404 (:status (request h :post "/tasks/999/state?to=completed"))))
        (is (ok? (request h :post (str "/tasks/" b "/move?dir=up"))))
        (is (= [b a] (map :id (db/q (:db sys) ["SELECT id FROM task ORDER BY \"order\""]))))))))


(deftest due-dates-are-read-in-the-viewers-zone
  (with-app
    (fn [h sys]
      (let [lid (create-list! h "L")
            ny "America/New_York"]
        (is (ok? (request h :post (str "/lists/" lid "/tasks") {:signals {:title "x" :due "2026-03-01"} :tz ny})))
        (let [[t] (task-ids sys)]
          (is (= 1772427599999 (:due_at (db/q1 (:db sys) ["SELECT due_at FROM task WHERE id = ?" t])))
              "the end of the day in New York is 05:00 UTC the next day")
          (is (str/includes? (:body (request h :get (str "/lists/" lid) {:tz ny})) "value=\"2026-03-01\""))
          (is (str/includes? (:body (request h :get (str "/lists/" lid) {:tz "UTC"})) "value=\"2026-03-02\""))
          (let [r (request h :put (str "/tasks/" t) {:signals {:due "next tuesday"} :tz ny})]
            (is (str/includes? (:body r) (str "id=\"task-" t "-errors\"")))
            (is (str/includes? (:body r) "Due must be a date")))
          (is (ok? (request h :put (str "/tasks/" t) {:signals {:due ""}})))
          (is (nil? (:due_at (db/q1 (:db sys) ["SELECT due_at FROM task WHERE id = ?" t])))))
        (is (ok? (request h :put (str "/lists/" lid) {:signals {:due "2026-03-01"} :tz ny})))
        (is (= 1772427599999 (:due_at (db/q1 (:db sys) ["SELECT due_at FROM list WHERE id = ?" lid]))))))))


(deftest overdue-tasks-are-badged
  (with-app
    (fn [h sys]
      (let [lid (create-list! h "L")]
        (binding [time/*now-ms* (constantly 1772375400000)]
          (request h :post (str "/lists/" lid "/tasks") {:signals {:title "late" :due "2026-02-28"} :tz "UTC"})
          (let [body (:body (request h :get (str "/lists/" lid)))]
            (is (str/includes? body "overdue"))
            (is (str/includes? body "due 14 hours ago")))
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
              (live/touch! (:live sys)
                           (:topics (db/with-tx [tx (:db sys)]
                                      (lists/create-task! tx other {:list-id lid :title "Also theirs" :assignee_id (:id other)})
                                      (lists/create-task! tx other {:list-id lid :title "Mine too" :assignee_id (:id me)}))))
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


(defn- make-plan!
  "A plan titled title with groups {group-title [task-title...]} (nil for ungrouped). Returns its id."
  [{:keys [db]} u title groups]
  (:result
    (db/with-tx [tx db]
      (let [pid (plans/create-plan! tx u {:title title})]
        (doseq [[gt tasks] groups
                :let [gid (when gt (plans/create-group! tx u {:plan-id pid :title gt}))]
                t tasks]
          (plans/create-plan-task! tx u {:plan-id pid :group-id gid :title t}))
        pid))))


(defn- shape
  "The list's display order as [ungrouped-titles [group-title task-titles]...]."
  [{:keys [db]} lid]
  (let [{:keys [ungrouped groups]} (lists/load-list db lid)]
    [(mapv :task/title ungrouped)
     (mapv (fn [{:keys [group tasks]}] [(:list.group/title group) (mapv :task/title tasks)]) groups)]))


(def ^:private plan-ids-key
  (keyword "plan_ids[]"))


(deftest new-list-form-picks-plans-in-order
  (with-app
    (fn [h sys]
      (let [u (owner h sys)
            weekend (make-plan! sys u "Weekend" [[nil ["Passport"]] ["Clothes" ["Socks" "Hat"]]])
            beach (make-plan! sys u "Beach" [["clothes" ["Swimsuit" "socks"]] ["Fun" ["Kite"]]])]
        (testing "the form lists the plans, with ?plan= pre-selected and first"
          (let [body (:body (request h :get (str "/lists/new?plan=" beach)))]
            (is (str/includes? body "name=\"plan_ids[]\""))
            (is (not (re-find #"checked=\"\d" body)) "checked is a boolean attribute")
            (is (< (str/index-of body "Beach") (str/index-of body "Weekend")))
            (is (re-find (re-pattern (str "checked[^>]*value=\"" beach "\"|value=\"" beach "\"[^>]*checked")) body))
            (is (not (re-find (re-pattern (str "checked[^>]*value=\"" weekend "\"|value=\"" weekend "\"[^>]*checked")) body)))))
        (testing "a plain form post creates the list from the plans in the submitted order"
          (let [r (request h :post "/lists" {:form (str "title=Trip&plan_ids%5B%5D=" weekend "&plan_ids%5B%5D=" beach)})
                lid (parse-long (second (re-find #"^/lists/(\d+)$" (get-in r [:headers "Location"]))))]
            (is (= 303 (:status r)))
            (is (= [["Passport"] [["Clothes" ["Socks" "Hat" "Swimsuit"]] ["Fun" ["Kite"]]]] (shape sys lid))
                "groups merge by title, and the duplicate socks is skipped")
            (is (= ["add-plan" "add-plan"]
                   (map :action (db/q (:db sys) ["SELECT action FROM event WHERE list_id = ? AND action = 'add-plan'" lid]))))))
        (testing "one plan arrives as a single field"
          (let [r (request h :post "/lists" {:form (str "title=Solo&plan_ids%5B%5D=" beach)})
                lid (parse-long (second (re-find #"^/lists/(\d+)$" (get-in r [:headers "Location"]))))]
            (is (= [[] [["clothes" ["Swimsuit" "socks"]] ["Fun" ["Kite"]]]] (shape sys lid)))))
        (testing "an invalid title creates nothing, even with plans"
          (let [before (count (db/q (:db sys) ["SELECT * FROM task"]))
                r (request h :post "/lists" {:signals {:title " " plan-ids-key [(str weekend)]}})]
            (is (str/includes? (:body r) "id=\"new-list-errors\""))
            (is (= before (count (db/q (:db sys) ["SELECT * FROM task"]))))))
        (is (= 404 (:status (request h :post "/lists" {:signals {:title "X" plan-ids-key ["999"]}}))))
        (is (= 400 (:status (request h :post "/lists" {:signals {:title "X" plan-ids-key ["nope"]}}))))))))


(deftest adding-plans-to-a-list-flashes-counts-and-updates-viewers
  (with-app
    (fn [h sys]
      (let [u (owner h sys)
            packing (make-plan! sys u "Packing" [[nil ["Passport" "Charger"]] ["Clothes" ["Socks"]]])
            lid (create-list! h "Trip")
            add (fn [pids] (request h :post (str "/lists/" lid "/add-plans") {:signals {plan-ids-key (mapv str pids)}}))]
        (testing "the list page offers the plans"
          (let [body (:body (request h :get (str "/lists/" lid)))]
            (is (str/includes? body (str "/lists/" lid "/add-plans")))
            (is (str/includes? body (str "id=\"list-" lid "-flash\"")))))
        (let [ch (:body (request h :get (str "/lists/" lid "?datastar-sse=true&datastar-selector=%23live")))]
          (try
            (take-frame ch)
            (let [r (add [packing])]
              (is (= 200 (:status r)))
              (is (str/includes? (:body r) (str "id=\"list-" lid "-flash\"")))
              (is (str/includes? (:body r) "Added Packing: 3 tasks")))
            (testing "other viewers get a live update"
              (let [frame (take-frame ch)]
                (is (str/includes? frame "Passport"))
                (is (str/includes? frame "Clothes"))))
            (finally
              (async/close! ch))))
        (testing "adding it again skips everything"
          (is (str/includes? (:body (add [packing])) "Added Packing: 0 tasks, 3 skipped")))
        (testing "after a task is removed, re-adding restores only that one"
          (db/with-tx [tx (:db sys)]
            (lists/delete-task! tx u {:id (:id (db/q1 (:db sys) ["SELECT id FROM task WHERE title = 'Charger'"]))}))
          (is (str/includes? (:body (add [packing])) "Added Packing: 1 task, 2 skipped")))
        (testing "choosing no plans is an error"
          (let [r (request h :post (str "/lists/" lid "/add-plans") {:signals {}})]
            (is (str/includes? (:body r) (str "id=\"add-plans-" lid "-errors\"")))
            (is (str/includes? (:body r) "Plans must include at least one plan"))))
        (is (= 404 (:status (add [999]))))
        (is (= 404 (:status (request h :post "/lists/999/add-plans" {:signals {plan-ids-key [(str packing)]}}))))))))


(deftest plan-page-starts-a-list-from-the-plan
  (with-app
    (fn [h sys]
      (let [pid (make-plan! sys (owner h sys) "Packing" [])]
        (is (str/includes? (:body (request h :get (str "/plans/" pid)))
                           (str "href=\"/lists/new?plan=" pid "\"")))))))


(deftest a-group-added-while-a-render-runs-still-reaches-the-stream
  (with-app
    (fn [h _sys]
      (let [lid (create-list! h "Trip")
            _ (request h :post (str "/lists/" lid "/tasks") {:signals {:title "Passport"}})
            ch (:body (request h :get (str "/lists/" lid "?datastar-sse=true&datastar-selector=%23live")))
            load-structure lists/load-list-structure
            inside (promise)
            release (promise)
            first-call (atom true)]
        (try
          (take-frame ch)
          (with-redefs [lists/load-list-structure
                        (fn [db id]
                          (let [structure (load-structure db id)]
                            (when (compare-and-set! first-call true false)
                              (deliver inside true)
                              @release)
                            structure))]
            (request h :post (str "/lists/" lid "/groups") {:signals {:title "First"}})
            (is (deref inside 3000 nil) "the render is held after reading the first group")
            (request h :post (str "/lists/" lid "/groups") {:signals {:title "Second"}})
            (deliver release true)
            (let [frames (str (take-frame ch) (take-frame ch))]
              (is (str/includes? frames "First"))
              (is (str/includes? frames "Second") "the render that missed the group isn't cached")))
          (finally
            (async/close! ch)))))))
