(ns chequer.web.history
  "History pages for plans, lists and tasks, read from the event log a page at a time."
  (:require
    [chequer.event :as event]
    [chequer.time :as time]
    [chequer.web.common :as web :refer [html html-response text-response]]
    [chequer.web.layout :as layout]
    [jolt.datastar.core :as ds]))


(def ^:private page-size
  50)


;; ## Rendering

(defn render-event
  "#event-<id>: when the event happened, in time zone tz, and what it did."
  [e tz]
  [:li {:id (str "event-" (:event/id e))}
   [:time (time/format-instant (:event/instant e) tz)]
   " "
   [:span.description (event/describe-event e)]
   (when (and (= "task" (:event/entity-type e)) (not= :delete (:event/action e)))
     (list " " [:a.task-history {:href (str "/tasks/" (:event/entity-id e) "/history")} "task history"]))])


(defn- more-el
  "#history-more: a button appending the page of events before the last one shown, when
  there are more."
  [path last-event more?]
  [:p {:id "history-more"}
   (when more?
     [:button {:type "button"
               :data-on:click (str "@get('" path "?before=" (:event/id last-event) "')")}
      "Load more"])])


;; ## Pages

(defn- before-param
  "The ?before= event id, nil when absent. Anything but an id is a bad request."
  [req]
  (web/parse-id (web/query-param req "before")))


(defn- history-response
  "The history page of the events (fetch-events opts) under heading, or, for a Datastar
  request, the next page appended to the list shown."
  [req {:keys [heading back fetch-events]}]
  (let [path (:uri req)
        tz (layout/viewer-tz req)
        fetched (fetch-events {:before (before-param req) :limit (inc page-size)})
        events (take page-size fetched)
        more? (< page-size (count fetched))
        items (map #(render-event % tz) events)
        more (more-el path (last events) more?)]
    (if (get-in req [:headers "datastar-request"])
      (web/sse-response [(ds/patch-elements-event (apply html items) "#history" "append")
                         (ds/patch-elements-event (html more) nil "outer")])
      (html-response 200
                     (layout/page req (str heading " history")
                                  [:h1 heading " history"]
                                  [:p [:a {:href (first back)} (second back)]]
                                  (if (seq events)
                                    [:ol#history.history items]
                                    [:p.empty "No history yet."])
                                  more)))))


(defn- entity-title
  "The current, or for a deleted entity its last, title of the entity, or nil if it has no
  events."
  [db entity-type id]
  (:event/entity-title (first (event/entity-history db entity-type id {:limit 1}))))


(defn- handle
  "Call (f) for a history response, answering a bad ?before= with a 400."
  [f]
  (try
    (f)
    (catch Exception e
      (if (= :chequer/bad-request (:type (ex-data e)))
        (text-response 400 "Bad request")
        (throw e)))))


(defn plan-history
  "GET /plans/:id/history: the plan's events, including its sections' and tasks'."
  [{:chequer/keys [db] :keys [params] :as req}]
  (let [id (:id params)]
    (if-let [title (entity-title db "plan" id)]
      (handle #(history-response req {:heading title
                                      :back [(web/plan-path id title) "Back to plan"]
                                      :fetch-events (fn [opts] (event/plan-history db id opts))}))
      (text-response 404 "Not found"))))


(defn list-history
  "GET /lists/:id/history: the list's events, including its sections' and tasks'."
  [{:chequer/keys [db] :keys [params] :as req}]
  (let [id (:id params)]
    (if-let [title (entity-title db "list" id)]
      (handle #(history-response req {:heading title
                                      :back [(web/list-path id title) "Back to list"]
                                      :fetch-events (fn [opts] (event/list-history db id opts))}))
      (text-response 404 "Not found"))))


(defn task-history
  "GET /tasks/:id/history: the task's own events."
  [{:chequer/keys [db] :keys [params] :as req}]
  (let [id (:id params)
        [newest] (event/entity-history db "task" id {:limit 1})
        list-id (:event/list newest)]
    (if newest
      (handle #(history-response req {:heading (:event/entity-title newest)
                                      :back [(web/list-path list-id (entity-title db "list" list-id)) "Back to list"]
                                      :fetch-events (fn [opts] (event/entity-history db "task" id opts))}))
      (text-response 404 "Not found"))))


(def routes
  "History routes as [method pattern handler]."
  [[:get ["plans" :id "history"] plan-history]
   [:get ["lists" :id "history"] list-history]
   [:get ["tasks" :id "history"] task-history]])
