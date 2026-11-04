(ns chequer.web.search
  "The search page, which searches as you type."
  (:require
    [chequer.search :as search]
    [chequer.web.common :as web :refer [html html-response raw]]
    [chequer.web.layout :as layout]
    [clojure.string :as str]
    [jolt.datastar.core :as ds]))


(def ^:private kind-labels
  {"plan" "Plan"
   "plan.task" "Plan task"
   "list" "List"
   "task" "Task"})


(defn- result-href
  "The page a result links to: its plan or list, scrolled to the task for a task."
  [{:search/keys [kind entity-id owner-kind owner-id owner-title]}]
  (str (case owner-kind
         "plan" (web/plan-path owner-id owner-title)
         "list" (web/list-path owner-id owner-title))
       (case kind
         "plan.task" (str "#plan-task-" entity-id)
         "task" (str "#task-" entity-id)
         nil)))


(defn- render-result
  [{:search/keys [kind owner-title title-html snippet-html archived?] :as r}]
  [:li.result
   [:a {:href (result-href r)} (raw title-html)]
   " "
   [:span.kind (kind-labels kind)]
   (when (#{"plan.task" "task"} kind)
     (list " " [:span.owner "in " owner-title]))
   (when archived?
     (list " " [:span.badge.archived "archived"]))
   (when-not (str/blank? snippet-html)
     [:p.snippet (raw snippet-html)])])


(defn- render-results
  "#search-results: the results for q, or a prompt to type when q is blank."
  [db q]
  [:div#search-results
   (cond
     (str/blank? q) [:p.empty "Search plans, lists and tasks."]
     :else (let [results (search/search db q)]
             (if (seq results)
               [:ol.results (map render-result results)]
               [:p.empty "No results for " [:q (str/trim q)] "."])))])


(defn- query
  [req]
  (or (web/query-param req "q") ""))


(def ^:private search-as-you-type
  "Fetch the results for the input's value, and keep the page's URL in step so a reload or
  a link shows the same search."
  (str "@get('/search/results?q=' + encodeURIComponent(el.value)); "
       "history.replaceState(null, '', '/search?q=' + encodeURIComponent(el.value))"))


(defn page
  "GET /search?q=: the search box and the results for q. Without JavaScript the box submits
  as a plain GET."
  [{:chequer/keys [db] :as req}]
  (let [q (query req)]
    (html-response 200
                   (layout/page req (if (str/blank? q) "Search" (str "Search: " (str/trim q)))
                                [:h1 "Search"]
                                [:form.search {:method "get" :action "/search" :role "search"}
                                 [:label [:span.visually-hidden "Search"]
                                  [:input {:type "search" :name "q" :value q :autofocus true
                                           :placeholder "Search plans, lists and tasks"
                                           :data-on:input__debounce.250ms search-as-you-type}]]
                                 [:button "Search"]]
                                (render-results db q)))))


(defn results
  "GET /search/results?q=: the #search-results fragment for q, as an SSE patch."
  [{:chequer/keys [db] :as req}]
  (web/sse-response [(ds/patch-elements-event (html (render-results db (query req))) nil "outer")]))


(def routes
  "Search routes as [method pattern handler]."
  [[:get ["search"] page]
   [:get ["search" "results"] results]])
