(ns chequer.search-test
  (:require
    [chequer.config :as config]
    [chequer.core :as core]
    [chequer.data.lists :as lists]
    [chequer.data.plans :as plans]
    [chequer.db :as db]
    [chequer.live :as live]
    [chequer.search :as search]
    [chequer.test-helpers :as helpers]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [jdbc.core :as jdbc]))


(defmacro ^:private tx
  [d & body]
  `(:result (db/with-tx [~'tx ~d] ~@body)))


(defn- index-rows
  "The search index's rows as [kind entity-id owner-kind owner-id title description]."
  [d]
  (set (map (juxt :kind :entity_id :owner_kind :owner_id :title :description)
            (db/q d ["SELECT * FROM search"]))))


(defn- found
  "[kind entity-id] of each result for s, best first."
  [d s]
  (mapv (juxt :search/kind :search/entity-id) (search/search d s)))


;; ## Index

(deftest inserted-rows-are-indexed
  (helpers/with-test-db
    (fn [d]
      (let [u (helpers/create-user! d "greg")
            pid (tx d (plans/create-plan! tx u {:title "Packing" :description "For trips"}))
            ptid (tx d (plans/create-plan-task! tx u {:plan-id pid :title "Charger"}))
            lid (tx d (lists/create-list! tx u {:title "Weekend away"}))
            tid (tx d (lists/create-task! tx u {:list-id lid :title "Charger" :description "The *long* cable"}))]
        (is (= #{["plan" pid "plan" pid "Packing" "For trips"]
                 ["plan.task" ptid "plan" pid "Charger" nil]
                 ["list" lid "list" lid "Weekend away" nil]
                 ["task" tid "list" lid "Charger" "The *long* cable"]}
               (index-rows d)))
        (is (= #{["plan.task" ptid] ["task" tid]} (set (found d "charger"))))
        (is (= [["task" tid]] (found d "cable")) "descriptions are searched")
        (is (= [["plan" pid]] (found d "trips")))))))


(deftest updating-a-title-replaces-it-in-the-index
  (helpers/with-test-db
    (fn [d]
      (let [u (helpers/create-user! d "greg")
            pid (tx d (plans/create-plan! tx u {:title "Packing"}))
            ptid (tx d (plans/create-plan-task! tx u {:plan-id pid :title "Charger"}))
            lid (tx d (lists/create-list! tx u {:title "Trip"}))
            tid (tx d (lists/create-task! tx u {:list-id lid :title "Socks"}))]
        (tx d (plans/update-plan! tx u {:id pid :title "Luggage"}))
        (tx d (plans/update-plan-task! tx u {:id ptid :title "Cable"}))
        (tx d (lists/update-list! tx u {:id lid :title "Holiday" :description "Beach"}))
        (tx d (lists/update-task! tx u {:id tid :title "Shoes"}))
        (is (= 4 (count (index-rows d))) "each entity still has exactly one row")
        (is (empty? (found d "packing")))
        (is (empty? (found d "charger")))
        (is (empty? (found d "trip")))
        (is (empty? (found d "socks")))
        (is (= [["plan" pid]] (found d "luggage")))
        (is (= [["plan.task" ptid]] (found d "cable")))
        (is (= [["list" lid]] (found d "beach")))
        (is (= [["task" tid]] (found d "shoes")))
        (testing "state changes leave the index alone"
          (tx d (lists/set-state! tx u {:id tid :state :completed}))
          (is (= [["task" tid]] (found d "shoes"))))))))


(deftest deleting-removes-from-the-index
  (helpers/with-test-db
    (fn [d]
      (let [u (helpers/create-user! d "greg")
            pid (tx d (plans/create-plan! tx u {:title "Packing"}))
            gid (tx d (plans/create-group! tx u {:plan-id pid :title "Bags"}))
            _ (tx d (plans/create-plan-task! tx u {:plan-id pid :group-id gid :title "Charger"}))
            keep-id (tx d (plans/create-plan-task! tx u {:plan-id pid :title "Charger cable"}))
            lid (tx d (lists/create-list! tx u {:title "Trip"}))
            tid (tx d (lists/create-task! tx u {:list-id lid :title "Socks"}))]
        (tx d (lists/delete-task! tx u {:id tid}))
        (is (empty? (found d "socks")))
        (testing "deleting a group removes its tasks through the cascade"
          (tx d (plans/delete-group! tx u {:id gid}))
          (is (= [["plan.task" keep-id]] (found d "charger"))))
        (tx d (lists/create-task! tx u {:list-id lid :title "Shoes"}))
        (tx d (lists/delete-list! tx u {:id lid}))
        (tx d (plans/delete-plan! tx u {:id pid}))
        (is (empty? (index-rows d)) "cascaded deletes clear the plan's and list's tasks")))))


(deftest backfill-indexes-existing-data
  (let [path (helpers/temp-db-path)
        d (db/open path)
        [before [search-migration]] (split-at 3 (mapv #'db/load-migration db/migration-files))]
    (try
      (is (= "create-search-index" (:name search-migration)))
      (db/migrate! d (vec before))
      (let [u (helpers/create-user! d "greg")
            pid (tx d (plans/create-plan! tx u {:title "Packing"}))
            ptid (tx d (plans/create-plan-task! tx u {:plan-id pid :title "Charger"}))
            lid (tx d (lists/create-list! tx u {:title "Trip"}))
            tid (tx d (lists/create-task! tx u {:list-id lid :title "Charger"}))]
        (db/migrate! d (conj (vec before) search-migration))
        (is (= #{["plan" pid "plan" pid "Packing" nil]
                 ["plan.task" ptid "plan" pid "Charger" nil]
                 ["list" lid "list" lid "Trip" nil]
                 ["task" tid "list" lid "Charger" nil]}
               (index-rows d)))
        (testing "triggers find backfilled rows"
          (tx d (lists/update-task! tx u {:id tid :title "Cable"}))
          (is (= [["plan.task" ptid]] (found d "charger")))))
      (finally
        (db/close! d)
        (helpers/delete-db-files path)))))


(deftest rebuild-recovers-a-broken-index
  (helpers/with-test-db
    (fn [d]
      (let [u (helpers/create-user! d "greg")
            pid (tx d (plans/create-plan! tx u {:title "Packing"}))
            _ (tx d (plans/create-plan-task! tx u {:plan-id pid :title "Charger"}))
            lid (tx d (lists/create-list! tx u {:title "Trip"}))
            _ (tx d (lists/create-task! tx u {:list-id lid :title "Socks"}))
            expected (index-rows d)]
        (db/with-tx [conn d]
          (jdbc/execute! conn "DELETE FROM search WHERE kind = 'task'")
          (jdbc/execute! conn "INSERT INTO search (title, kind, entity_id) VALUES ('Stale', 'task', 999)"))
        (search/rebuild-search! d)
        (is (= expected (index-rows d)))
        (tx d (lists/update-list! tx u {:id lid :title "Holiday"}))
        (is (= [["list" lid]] (found d "holiday")) "triggers still find the rebuilt rows")))))


;; ## Queries

(deftest fts-query-quotes-words
  (is (nil? (search/fts-query nil)))
  (is (nil? (search/fts-query "   ")))
  (is (nil? (search/fts-query "\" - ( *")) "words without letters or digits are dropped")
  (is (= "\"pack\"*" (search/fts-query "pack")))
  (is (= "\"pack\" \"charger\"*" (search/fts-query "  pack   charger ")))
  (is (= "\"NEAR(a\" \"b)\"*" (search/fts-query "NEAR(a b)")))
  (is (= "\"say\"\"hi\"*" (search/fts-query "say\"hi"))))


(deftest prefix-matching
  (helpers/with-test-db
    (fn [d]
      (let [u (helpers/create-user! d "greg")
            lid (tx d (lists/create-list! tx u {:title "Trip"}))
            tid (tx d (lists/create-task! tx u {:list-id lid :title "Pack the charger"}))]
        (is (= [["task" tid]] (found d "char")))
        (is (= [["task" tid]] (found d "pack char")))
        (is (= [["task" tid]] (found d "PACK")) "matching ignores case")
        (is (empty? (found d "pac charger")) "only the last word is a prefix")
        (is (empty? (found d "pack zzz")) "every word must match")))))


(deftest diacritics-are-ignored
  (helpers/with-test-db
    (fn [d]
      (let [u (helpers/create-user! d "greg")
            lid (tx d (lists/create-list! tx u {:title "Café visit"}))]
        (is (= [["list" lid]] (found d "cafe")))
        (is (= [["list" lid]] (found d "café")))))))


(deftest operator-input-is-only-text
  (helpers/with-test-db
    (fn [d]
      (let [u (helpers/create-user! d "greg")
            lid (tx d (lists/create-list! tx u {:title "NEAR the sea"}))]
        (doseq [s ["\"" "NEAR(" "NEAR(a b)" "a OR b" "a AND" "NOT" "*" "title:x" "(" "^a" "a\"b" "-" "{a b}:c"]]
          (is (vector? (search/search d s)) (str "searching " (pr-str s) " doesn't throw")))
        (is (= [["list" lid]] (found d "NEAR(")))
        (is (= [] (found d "\"")))))))


(deftest titles-rank-above-descriptions
  (helpers/with-test-db
    (fn [d]
      (let [u (helpers/create-user! d "greg")
            lid (tx d (lists/create-list! tx u {:title "Trip"}))
            in-desc (tx d (lists/create-task! tx u {:list-id lid :title "Bag" :description "Put the charger in it"}))
            in-title (tx d (lists/create-task! tx u {:list-id lid :title "Charger"}))]
        (is (= [["task" in-title] ["task" in-desc]] (found d "charger")))))))


(deftest archived-results-rank-after-active-ones
  (helpers/with-test-db
    (fn [d]
      (let [u (helpers/create-user! d "greg")
            archived (tx d (lists/create-list! tx u {:title "Charger"}))
            archived-task (tx d (lists/create-task! tx u {:list-id archived :title "Charger"}))
            active (tx d (lists/create-list! tx u {:title "Trip"}))
            ;; A weaker match: the word is only in the description.
            active-task (tx d (lists/create-task! tx u {:list-id active :title "Bag"
                                                        :description "Put the charger and some other things in it"}))]
        (tx d (lists/archive-list! tx u {:id archived}))
        (let [results (search/search d "charger")]
          (is (= ["task" active-task] ((juxt :search/kind :search/entity-id) (first results))))
          (is (= #{["list" archived] ["task" archived-task]}
                 (set (map (juxt :search/kind :search/entity-id) (rest results)))))
          (is (= [false true true] (map :search/archived? results)))
          (is (= ["Trip" "Charger" "Charger"] (map :search/owner-title results))))
        (tx d (lists/unarchive-list! tx u {:id archived}))
        (is (= [false false false] (map :search/archived? (search/search d "charger"))))))))


(deftest results-are-limited
  (helpers/with-test-db
    (fn [d]
      (let [u (helpers/create-user! d "greg")
            lid (tx d (lists/create-list! tx u {:title "Trip"}))]
        (doseq [i (range 5)]
          (tx d (lists/create-task! tx u {:list-id lid :title (str "Sock " i)})))
        (is (= 5 (count (search/search d "sock"))))
        (is (= 2 (count (search/search d "sock" {:limit 2}))))))))


(deftest snippets-cannot-inject-html
  (helpers/with-test-db
    (fn [d]
      (let [u (helpers/create-user! d "greg")
            lid (tx d (lists/create-list! tx u {:title "Trip"}))]
        (tx d (lists/create-task! tx u {:list-id lid :title "<b>Charger</b> & \"cable\""
                                        :description "Plug <script>alert(1)</script> charger in <img src=x>"}))
        (let [[r] (search/search d "charger")]
          (is (= "&lt;b&gt;<mark>Charger</mark>&lt;/b&gt; &amp; &quot;cable&quot;" (:search/title-html r)))
          (is (str/includes? (:search/snippet-html r) "&lt;script&gt;alert(1)&lt;/script&gt; <mark>charger</mark>"))
          (is (not (str/includes? (:search/snippet-html r) "<img")))
          (is (not (str/includes? (:search/snippet-html r) "<script"))))))))


;; ## Page

(def ^:private base-url "http://chequer.test")


(defn- with-app
  "Call (f handler db user) with the full app handler over a migrated temp database, and the
  single-mode owner user."
  [f]
  (helpers/with-test-db
    (fn [d]
      (let [sys {:db d
                 :cfg (config/normalize {"database" {"path" "unused"}
                                         "server" {"base-url" base-url}})
                 :live (live/live)}
            h (core/handler sys)]
        (try
          (h {:request-method :get :uri "/search" :headers {}})
          (f h d (db/q1 d ["SELECT * FROM user"]))
          (finally
            (live/stop! (:live sys))))))))


(defn- request
  ([h uri] (request h uri {}))
  ([h uri headers]
   (let [[path query] (str/split uri #"\?" 2)]
     (h {:request-method :get :uri path :query-string query :headers headers}))))


(deftest search-page-links-results-to-their-pages
  (with-app
    (fn [h d u]
      (let [pid (tx d (plans/create-plan! tx u {:title "Packing"}))
            ptid (tx d (plans/create-plan-task! tx u {:plan-id pid :title "Charger"}))
            lid (tx d (lists/create-list! tx u {:title "Old trip"}))
            tid (tx d (lists/create-task! tx u {:list-id lid :title "Charger"}))]
        (tx d (lists/archive-list! tx u {:id lid}))
        (let [{:keys [status body]} (request h "/search?q=charg")]
          (is (= 200 status))
          (is (str/includes? body "value=\"charg\""))
          (is (str/includes? body "data-on:input__debounce.250ms"))
          (is (str/includes? body (str "href=\"/plans/" pid "#plan-task-" ptid "\"")))
          (is (str/includes? body (str "href=\"/lists/" lid "#task-" tid "\"")))
          (is (str/includes? body "<mark>Charger</mark>"))
          (is (str/includes? body "in Packing"))
          (is (str/includes? body "in Old trip"))
          (is (= 1 (count (re-seq #"badge archived" body))))
          (is (< (str/index-of body (str "#plan-task-" ptid)) (str/index-of body (str "#task-" tid)))
              "the archived result comes last"))))))


(deftest search-page-empty-states
  (with-app
    (fn [h _ _]
      (is (str/includes? (:body (request h "/search")) "Search plans, lists and tasks."))
      (is (str/includes? (:body (request h "/search?q=%3Cnope%3E")) "No results for <q>&lt;nope&gt;</q>.")))))


(deftest search-results-patch-the-results
  (with-app
    (fn [h d u]
      (let [lid (tx d (lists/create-list! tx u {:title "Trip"}))]
        (let [r (request h "/search/results?q=tri" {"datastar-request" "true"})]
          (is (= 200 (:status r)))
          (is (= "text/event-stream" (get-in r [:headers "Content-Type"])))
          (is (str/includes? (:body r) "event: datastar-patch-elements"))
          (is (str/includes? (:body r) "<div id=\"search-results\">"))
          (is (str/includes? (:body r) (str "href=\"/lists/" lid "\""))))
        (is (= 200 (:status (request h "/search/results?q=NEAR(%22" {"datastar-request" "true"}))))))))
