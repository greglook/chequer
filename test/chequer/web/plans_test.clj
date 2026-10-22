(ns chequer.web.plans-test
  (:require
    [chequer.config :as config]
    [chequer.core :as core]
    [chequer.data.plans :as plans]
    [chequer.db :as db]
    [chequer.live :as live]
    [chequer.test-helpers :as helpers]
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
  ([h method uri] (request h method uri nil))
  ([h method uri signals]
   (let [[path query] (str/split uri #"\?" 2)]
     (h (cond-> {:request-method method :uri path :query-string query :headers {"origin" base-url}}
          ;; Unescaped like a browser's JSON.stringify; Jolt's data.json mis-escapes emoji.
          signals (assoc :body (ByteArrayInputStream. (.getBytes ^String (json/write-str signals :escape-unicode false) "UTF-8"))
                         :headers {"origin" base-url "content-type" "application/json"}))))))


(defn- user-id
  [{:keys [db]}]
  (:id (db/q1 db ["SELECT id FROM user"])))


(defn- create-plan!
  [h title]
  (let [r (request h :post "/plans" {:title title})]
    (parse-long (second (re-find #"^/plans/(\d+)$" (get-in r [:headers "Location"]))))))


(defn- take-frame
  [ch]
  (first (async/alts!! [ch (async/timeout 2000)])))


(deftest index-and-new-pages-render
  (with-app
    (fn [h _]
      (create-plan! h "Packing")
      (let [r (request h :get "/plans")]
        (is (= 200 (:status r)))
        (is (str/includes? (:body r) "Packing"))
        (is (str/includes? (:body r) "href=\"/plans/new\"")))
      (let [r (request h :get "/plans/new")]
        (is (= 200 (:status r)))
        (is (str/includes? (:body r) "action=\"/plans\""))))))


(deftest post-plans-creates-and-redirects
  (with-app
    (fn [h sys]
      (let [r (request h :post "/plans" {:title "  Packing  " :icon "🧳"})
            row (db/q1 (:db sys) ["SELECT * FROM plan"])]
        (is (= 303 (:status r)))
        (is (= (str "/plans/" (:id row)) (get-in r [:headers "Location"])))
        (is (= ["Packing" "🧳"] [(:title row) (:icon row)]))))))


(deftest post-plans-accepts-a-plain-form-post
  (with-app
    (fn [h sys]
      (let [r (h {:request-method :post :uri "/plans"
                  :headers {"origin" base-url "content-type" "application/x-www-form-urlencoded"}
                  :body (ByteArrayInputStream. (.getBytes "title=Pack+%26+go&description=" "UTF-8"))})]
        (is (= 303 (:status r)))
        (is (= "Pack & go" (:title (db/q1 (:db sys) ["SELECT title FROM plan"]))))))))


(deftest invalid-title-returns-an-error-fragment
  (with-app
    (fn [h sys]
      (let [r (request h :post "/plans" {:title " "})]
        (is (= 200 (:status r)))
        (is (= "text/event-stream" (get-in r [:headers "Content-Type"])))
        (is (str/includes? (:body r) "event: datastar-patch-elements"))
        (is (str/includes? (:body r) "id=\"new-plan-errors\""))
        (is (str/includes? (:body r) "can&apos;t be blank")))
      (is (nil? (db/q1 (:db sys) ["SELECT * FROM plan"])))
      (let [pid (create-plan! h "P")
            r (request h :post (str "/plans/" pid "/tasks") {:title "x" :estimate "soon"})]
        (is (str/includes? (:body r) (str "id=\"new-plan-task-" pid "-errors\"")))
        (is (str/includes? (:body r) "must be a whole number of minutes"))))))


(deftest plan-page-shows-plan-and-subscribes-to-its-stream
  (with-app
    (fn [h sys]
      (let [pid (create-plan! h "Packing")
            u {:id (user-id sys)}
            {g :result} (db/with-tx [tx (:db sys)] (plans/create-group! tx u {:plan-id pid :title "Clothes"}))]
        (db/with-tx [tx (:db sys)]
          (plans/create-plan-task! tx u {:plan-id pid :group-id g :title "Socks" :estimate 5})
          (plans/create-plan-task! tx u {:plan-id pid :title "Passport"}))
        (let [{:keys [status body]} (request h :get (str "/plans/" pid))]
          (is (= 200 status))
          (is (str/includes? body "datastar-sse=true"))
          (is (str/includes? body "id=\"live\""))
          (is (str/includes? body (str "id=\"plan-" pid "\"")))
          (is (str/includes? body (str "id=\"plan-group-" g "\"")))
          (is (str/includes? body "Socks"))
          (is (< (str/index-of body "Passport") (str/index-of body "Clothes")) "ungrouped tasks come first"))
        (is (= 404 (:status (request h :get "/plans/999"))))))))


(deftest description-html-is-escaped
  (with-app
    (fn [h sys]
      (let [pid (create-plan! h "P")
            _ (request h :post (str "/plans/" pid "/tasks") {:title "<b>bold</b>" :description "<script>alert(1)</script> *ok*"})
            body (:body (request h :get (str "/plans/" pid)))]
        (is (not (str/includes? body "<script>alert")))
        (is (str/includes? body "&lt;script&gt;alert(1)&lt;/script&gt;"))
        (is (str/includes? body "<em>ok</em>"))
        (is (str/includes? body "&lt;b&gt;bold&lt;/b&gt;"))
        (is (= 1 (count (db/q (:db sys) ["SELECT * FROM plan_task"]))))))))


(deftest stream-subscribers-receive-the-re-rendered-plan
  (with-app
    (fn [h _]
      (let [pid (create-plan! h "Packing")
            stream (request h :get (str "/plans/" pid "?datastar-sse=true&datastar-selector=%23live"))
            ch (:body stream)]
        (is (= "text/event-stream; charset=utf-8" (get-in stream [:headers "Content-Type"])))
        (let [first-frame (take-frame ch)]
          (is (str/includes? first-frame "data: selector #live"))
          (is (str/includes? first-frame (str "id=\"plan-" pid "\"")))
          (is (not (str/includes? first-frame "<html")) "the stream renders the fragment, not the page"))
        (let [r (request h :put (str "/plans/" pid) {:title "Packing list"})
              frame (take-frame ch)]
          (is (= 204 (:status r)))
          (is (str/includes? frame "event: datastar-patch-elements"))
          (is (str/includes? frame "Packing list")))
        (testing "a delete redirects subscribers to the index"
          (is (= 204 (:status (request h :delete (str "/plans/" pid)))))
          (let [frame (take-frame ch)]
            (is (str/includes? frame "window.location"))
            (is (str/includes? frame "/plans"))))
        (async/close! ch)))))


(deftest groups-and-tasks-can-be-edited-moved-and-deleted
  (with-app
    (fn [h sys]
      (let [d (:db sys)
            pid (create-plan! h "P")
            ok? #(= 204 (:status %))]
        (is (ok? (request h :post (str "/plans/" pid "/groups") {:title "G1"})))
        (is (ok? (request h :post (str "/plans/" pid "/groups") {:title "G2"})))
        (let [[g1 g2] (map :id (db/q d ["SELECT id FROM plan_group ORDER BY \"order\""]))]
          (is (ok? (request h :post (str "/plans/" pid "/tasks") {:title "a" :group (str g1)})))
          (is (ok? (request h :post (str "/plans/" pid "/tasks") {:title "b" :group (str g1)})))
          (let [[a b] (map :id (db/q d ["SELECT id FROM plan_task ORDER BY \"order\""]))]
            (is (ok? (request h :post (str "/plan-groups/" g2 "/move?dir=up"))))
            (is (= [g2 g1] (map :id (db/q d ["SELECT id FROM plan_group ORDER BY \"order\""]))))
            (is (ok? (request h :post (str "/plan-tasks/" b "/move?dir=up"))))
            (is (= [b a] (map :id (db/q d ["SELECT id FROM plan_task ORDER BY \"order\""]))))
            (is (= 400 (:status (request h :post (str "/plan-tasks/" b "/move?dir=sideways")))))
            (is (ok? (request h :put (str "/plan-groups/" g1) {:title "Clothes" :icon ""})))
            (is (ok? (request h :put (str "/plan-tasks/" a) {:title "a2" :description "d" :icon "" :estimate "3"})))
            (is (= {:title "a2" :description "d" :estimate 3}
                   (db/q1 d ["SELECT title, description, estimate FROM plan_task WHERE id = ?" a])))
            (is (ok? (request h :delete (str "/plan-tasks/" b))))
            (is (ok? (request h :delete (str "/plan-groups/" g1))))
            (is (empty? (db/q d ["SELECT * FROM plan_task"])))
            (is (= 404 (:status (request h :put (str "/plan-tasks/" a) {:title "x"}))))))))))


(deftest a-task-cannot-be-added-to-another-plans-group
  (with-app
    (fn [h sys]
      (let [p1 (create-plan! h "P1")
            p2 (create-plan! h "P2")
            _ (request h :post (str "/plans/" p1 "/groups") {:title "G"})
            g (:id (db/q1 (:db sys) ["SELECT id FROM plan_group"]))]
        (is (= 400 (:status (request h :post (str "/plans/" p2 "/tasks") {:title "x" :group (str g)}))))
        (is (empty? (db/q (:db sys) ["SELECT * FROM plan_task"])))))))


(deftest malformed-signal-bodies-are-rejected
  (with-app
    (fn [h _]
      (let [pid (create-plan! h "P")]
        (is (= 400 (:status (h {:request-method :put :uri (str "/plans/" pid)
                                :headers {"origin" base-url "content-type" "application/json"}
                                :body (ByteArrayInputStream. (.getBytes "{not json" "UTF-8"))}))))
        (is (= 400 (:status (request h :put (str "/plans/" pid) {:title 42}))))
        (is (= 400 (:status (h {:request-method :put :uri (str "/plans/" pid)
                                :headers {"origin" base-url "content-type" "application/json"}
                                :body (ByteArrayInputStream. (.getBytes "{\"icon\":\"\\ud83e\\uddf3\"}" "UTF-8"))})))
            "Jolt's data.json can't read surrogate-pair escapes; that must be a 400, not a 500")
        (is (= 400 (:status (h {:request-method :put :uri (str "/plans/" pid)
                                :headers {"origin" base-url "content-type" "application/json" "datastar-request" "true"}
                                :body (ByteArrayInputStream. (.getBytes "{not json" "UTF-8"))})))
            "glimmer-datastar parses Datastar bodies first; malformed JSON must still be a 400")))))
