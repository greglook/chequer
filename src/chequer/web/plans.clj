(ns chequer.web.plans
  "Plan pages, the plan SSE stream, and the plan, group and task mutation handlers."
  (:require
    [chequer.data.plans :as plans]
    [chequer.db :as db]
    [chequer.live :as live]
    [chequer.markdown :as md]
    [chequer.web.layout :as layout]
    [clojure.data.json :as json]
    [clojure.string :as str]
    [hiccup2.core :as h]
    [jolt.datastar.core :as ds]))


;; ## SSE events

(defn- html
  [hiccup]
  (str (h/html hiccup)))


(defn- redirect-script
  "A script element that sends the browser to path (a server-built path, not user input)."
  [path]
  (html [:script {:data-effect "el.remove()"} (h/raw (str "window.location.href = \"" path "\""))]))


(defn- redirect-event
  [path]
  (ds/patch-elements-event (redirect-script path) "body" "append"))


(defn- sse-response
  [events]
  {:status 200
   :headers {"Content-Type" "text/event-stream" "Cache-Control" "no-cache"}
   :body (apply str events)})


(defn- html-response
  [status body]
  {:status status
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body body})


(defn- text-response
  [status body]
  {:status status
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body body})


;; ## Request bodies

(defn- read-params
  "The request's Datastar signals (JSON) or form fields (parsed by ring's wrap-params) as a
  map of keyword to string, or nil when the body is malformed or holds non-string values."
  [{:keys [body headers form-params]}]
  (try
    (let [content-type (get headers "content-type" "")
          ;; wrap-params has already consumed a urlencoded form body
          form? (str/includes? content-type "application/x-www-form-urlencoded")
          s (if (and body (not form?)) (slurp body) "")
          params (cond
                   form? (update-keys form-params keyword)
                   (str/blank? s) {}
                   (str/includes? content-type "application/json") (json/read-str s :key-fn keyword))]
      (when (and (map? params) (every? #(or (nil? %) (string? %)) (vals params)))
        params))
    (catch Exception _ nil)))


;; ## Rendering

(defn- action
  "A Datastar expression sending the enclosing form's fields with method to url."
  [method url]
  (str "@" method "('" url "', {contentType: 'form'})"))


(defn- confirmed
  [question expr]
  (str "confirm('" question "') && " expr))


(defn- errors-el
  [id]
  [:ul.errors {:id id}])


(defn- errors-fragment
  "SSE response replacing the error list id with messages from {field message}."
  [id errors]
  (sse-response [(ds/patch-elements-event (html [:ul.errors {:id id}
                                                 (for [[k msg] (sort-by key errors)]
                                                   [:li (str (str/capitalize (name k)) " " msg)])])
                                          nil "outer")]))


(defn- field
  ([label input-name value] (field label input-name value {}))
  ([label input-name value attrs]
   [:label label [:input (merge {:name input-name :value (or value "")} attrs)]]))


(defn- toggle-signal
  "Local (unsent) signal name toggling the edit form of an element."
  [kind id]
  (str "_edit" kind id))


(defn- edit-toggle
  "The data-signals attribute declaring signal false unless an earlier render already did."
  [signal]
  {:data-signals__ifmissing (str "{" signal ": false}")})


(defn- task-form
  "The fields of a task form."
  [t]
  (list (field "Title" "title" (:plan.task/title t) {:required true})
        (field "Icon" "icon" (:plan.task/icon t) {:size 4})
        (field "Estimate (minutes)" "estimate" (some-> (:plan.task/estimate t) str) {:inputmode "numeric" :size 6})
        [:label "Description" [:textarea {:name "description" :rows 3} (or (:plan.task/description t) "")]]))


(defn- move-buttons
  [path]
  (list [:button {:type "button" :title "Move up" :data-on:click (str "@post('" path "/move?dir=up')")} "↑"]
        [:button {:type "button" :title "Move down" :data-on:click (str "@post('" path "/move?dir=down')")} "↓"]))


(defn render-plan-task
  "#plan-task-<id>: the title row, the description, the controls and an inline edit form."
  [t]
  (let [id (:plan.task/id t)
        path (str "/plan-tasks/" id)
        signal (toggle-signal "Task" id)
        errors (str "plan-task-" id "-errors")]
    [:li.plan-task (merge {:id (str "plan-task-" id)} (edit-toggle signal))
     [:div.row
      [:span.icon (:plan.task/icon t)]
      [:span.title (:plan.task/title t)]
      (when-let [e (:plan.task/estimate t)] [:span.estimate (str e " min")])
      [:span.controls
       [:button {:type "button" :data-on:click (str "$" signal " = !$" signal)} "Edit"]
       (move-buttons path)
       [:button {:type "button" :data-on:click (confirmed "Delete this task?" (str "@delete('" path "')"))} "Delete"]]]
     (when-let [d (:plan.task/description t)]
       [:details [:summary "Description"] [:div.markdown (h/raw (md/render d))]])
     [:form.edit {:data-show (str "$" signal) :style "display: none"
                  :data-on:submit (str (action "put" path) "; $" signal " = false")}
      (task-form t)
      (errors-el errors)
      [:button "Save"]]]))


(defn- new-task-form
  [plan-id group-id]
  (let [suffix (str plan-id (when group-id (str "-" group-id)))]
    [:form.new-task {:data-on:submit (str (action "post" (str "/plans/" plan-id "/tasks")) "; el.reset()")}
     (when group-id [:input {:type "hidden" :name "group" :value (str group-id)}])
     (field "New task" "title" nil {:required true})
     (errors-el (str "new-plan-task-" suffix "-errors"))
     [:button "Add"]]))


(defn- render-group
  [plan-id {:keys [group tasks]}]
  (let [id (:plan.group/id group)
        path (str "/plan-groups/" id)
        signal (toggle-signal "Group" id)]
    [:section.plan-group (merge {:id (str "plan-group-" id)} (edit-toggle signal))
     [:h2
      [:span.icon (:plan.group/icon group)]
      [:span.title (:plan.group/title group)]
      [:span.controls
       [:button {:type "button" :data-on:click (str "$" signal " = !$" signal)} "Edit"]
       (move-buttons path)
       [:button {:type "button" :data-on:click (confirmed "Delete this group and its tasks?" (str "@delete('" path "')"))}
        "Delete"]]]
     [:form.edit {:data-show (str "$" signal) :style "display: none"
                  :data-on:submit (str (action "put" path) "; $" signal " = false")}
      (field "Title" "title" (:plan.group/title group) {:required true})
      (field "Icon" "icon" (:plan.group/icon group) {:size 4})
      (errors-el (str "plan-group-" id "-errors"))
      [:button "Save"]]
     [:ul.plan-tasks (map render-plan-task tasks)]
     (new-task-form plan-id id)]))


(defn- render-loaded-plan
  [{:keys [plan ungrouped groups]}]
  (let [id (:plan/id plan)
        path (str "/plans/" id)
        signal (toggle-signal "Plan" id)]
    [:div.plan (merge {:id (str "plan-" id)} (edit-toggle signal))
     [:h1 [:span.icon (:plan/icon plan)] [:span.title (:plan/title plan)]]
     (when-let [d (:plan/description plan)]
       [:div.markdown (h/raw (md/render d))])
     [:div.controls
      [:button {:type "button" :data-on:click (str "$" signal " = !$" signal)} "Edit"]
      [:button {:type "button" :data-on:click (confirmed "Delete this plan?" (str "@delete('" path "')"))} "Delete"]]
     [:form.edit {:data-show (str "$" signal) :style "display: none"
                  :data-on:submit (str (action "put" path) "; $" signal " = false")}
      (field "Title" "title" (:plan/title plan) {:required true})
      (field "Icon" "icon" (:plan/icon plan) {:size 4})
      [:label "Description" [:textarea {:name "description" :rows 3} (or (:plan/description plan) "")]]
      (errors-el (str "plan-" id "-errors"))
      [:button "Save"]]
     [:ul.plan-tasks (map render-plan-task ungrouped)]
     (new-task-form id nil)
     (map #(render-group id %) groups)
     [:form.new-group {:data-on:submit (str (action "post" (str path "/groups")) "; el.reset()")}
      (field "New group" "title" nil {:required true})
      (errors-el (str "new-plan-group-" id "-errors"))
      [:button "Add group"]]]))


(defn render-plan
  "#plan-<id> as hiccup, or nil if the plan doesn't exist. viewer is unused until views
  depend on the user or time zone."
  [db plan-id _viewer]
  (some-> (plans/load-plan db plan-id) render-loaded-plan))


;; ## Pages

(defn index
  "GET /plans."
  [{:chequer/keys [db] :as req}]
  (html-response 200
                 (layout/page req "Plans"
                              [:h1 "Plans"]
                              [:p [:a {:href "/plans/new"} "New plan"]]
                              (if-let [ps (seq (plans/list-plans db))]
                                [:ul.plans
                                 (for [p ps]
                                   [:li [:a {:href (str "/plans/" (:plan/id p))}
                                         [:span.icon (:plan/icon p)] " " (:plan/title p)]])]
                                [:p.empty "No plans yet."]))))


(defn new-page
  "GET /plans/new. The form works as a plain POST and, with Datastar, shows errors inline."
  [req]
  (html-response 200
                 (layout/page req "New plan"
                              [:h1 "New plan"]
                              [:form {:method "post" :action "/plans" :data-on:submit (action "post" "/plans")}
                               (field "Title" "title" nil {:required true :autofocus true})
                               (field "Icon" "icon" nil {:size 4})
                               [:label "Description" [:textarea {:name "description" :rows 4}]]
                               (errors-el "new-plan-errors")
                               [:button "Create"]])))


(defn show
  "GET /plans/:id. The page opens an SSE request to the same URL, which glimmer-datastar
  re-renders into #live whenever the plan's topic is touched; a deleted plan sends the
  viewer to the index."
  [{:chequer/keys [db live user] :keys [params] :as req}]
  (let [id (:id params)
        _ @(live/cell live [:plan id])
        fragment (render-plan db id user)]
    (cond
      (:jolt.datastar/sse-request req) (html-response 200 (if fragment (html fragment) (redirect-script "/plans")))
      fragment (html-response 200
                              (layout/page req (:title (db/q1 db ["SELECT title FROM plan WHERE id = ?" id]))
                                           [:div#live (ds/init-opts {:selector "#live"}) fragment]))
      :else (text-response 404 "Not found"))))


;; ## Mutations

(def ^:private no-content
  {:status 204})


(defn- mutate
  "Run (f tx user params) in a transaction. Returns 204 with the touched topics as
  metadata, 400 for a malformed body or a broken constraint, 404 for a missing entity, or
  an errors fragment in errors-id for invalid attributes. ok-response, given the result,
  replaces the 204."
  ([req errors-id f] (mutate req errors-id f (constantly no-content)))
  ([{:chequer/keys [db user] :as req} errors-id f ok-response]
   (if-let [params (read-params req)]
     (try
       (let [{:keys [result topics]} (db/with-tx [tx db] (f tx user params))]
         (with-meta (ok-response result) {:topics topics}))
       (catch Exception e
         (let [data (ex-data e)]
           (cond
             (= :chequer/invalid (:type data)) (errors-fragment (or (:errors-id data) errors-id) (:errors data))
             (= :chequer/not-found (:type data)) (text-response 404 "Not found")
             (= :chequer/bad-request (:type data)) (text-response 400 "Bad request")
             (str/includes? (str (ex-message e)) "group belongs to another plan") (text-response 400 "Bad request")
             :else (throw e)))))
     (text-response 400 "Bad request"))))


(defn- entity-attrs
  [params ks]
  (select-keys params ks))


(defn create-plan
  "POST /plans: 303 to the new plan, or, from Datastar, an SSE redirect."
  [req]
  (mutate req "new-plan-errors"
          (fn [tx user params]
            (plans/create-plan! tx user (merge {:title nil} (entity-attrs params [:title :description :icon]))))
          (fn [id]
            (let [path (str "/plans/" id)]
              (if (get-in req [:headers "datastar-request"])
                (sse-response [(redirect-event path)])
                {:status 303 :headers {"Location" path}})))))


(defn update-plan
  "PUT /plans/:id."
  [{:keys [params] :as req}]
  (let [id (:id params)]
    (mutate req (str "plan-" id "-errors")
            (fn [tx user body]
              (plans/update-plan! tx user (assoc (entity-attrs body [:title :description :icon]) :id id))))))


(defn delete-plan
  "DELETE /plans/:id."
  [{:keys [params] :as req}]
  (mutate req nil (fn [tx user _] (plans/delete-plan! tx user {:id (:id params)}))))


(defn create-group
  "POST /plans/:id/groups."
  [{:keys [params] :as req}]
  (let [id (:id params)]
    (mutate req (str "new-plan-group-" id "-errors")
            (fn [tx user body]
              (plans/create-group! tx user (assoc (merge {:title nil} (entity-attrs body [:title :icon])) :plan-id id))))))


(defn update-group
  "PUT /plan-groups/:id."
  [{:keys [params] :as req}]
  (let [id (:id params)]
    (mutate req (str "plan-group-" id "-errors")
            (fn [tx user body]
              (plans/update-group! tx user (assoc (entity-attrs body [:title :icon]) :id id))))))


(defn delete-group
  "DELETE /plan-groups/:id."
  [{:keys [params] :as req}]
  (mutate req nil (fn [tx user _] (plans/delete-group! tx user {:id (:id params)}))))


(defn- parse-group
  [s]
  (when-not (str/blank? s)
    (or (when (re-matches #"\d{1,18}" s) (parse-long s))
        (throw (ex-info "Bad group" {:type :chequer/bad-request})))))


(defn create-task
  "POST /plans/:id/tasks; the optional group field puts the task in that group."
  [{:keys [params] :as req}]
  (let [id (:id params)]
    (mutate req nil
            (fn [tx user body]
              (let [group (parse-group (:group body))]
                (try
                  (plans/create-plan-task! tx user (assoc (merge {:title nil}
                                                                 (select-keys body [:title :description :icon :estimate]))
                                                          :plan-id id :group-id group))
                  (catch Exception e
                    (if (= :chequer/invalid (:type (ex-data e)))
                      (throw (ex-info (ex-message e)
                                      (assoc (ex-data e) :errors-id (str "new-plan-task-" id (when group (str "-" group)) "-errors"))))
                      (throw e)))))))))


(defn update-task
  "PUT /plan-tasks/:id."
  [{:keys [params] :as req}]
  (let [id (:id params)]
    (mutate req (str "plan-task-" id "-errors")
            (fn [tx user body]
              (plans/update-plan-task! tx user (assoc (entity-attrs body [:title :description :icon :estimate]) :id id))))))


(defn delete-task
  "DELETE /plan-tasks/:id."
  [{:keys [params] :as req}]
  (mutate req nil (fn [tx user _] (plans/delete-plan-task! tx user {:id (:id params)}))))


(defn- move
  [req move-fn]
  (if-let [direction ({"up" :up "down" :down} (get-in req [:query-params "dir"]))]
    (mutate req nil (fn [tx user _] (move-fn tx user {:id (get-in req [:params :id]) :direction direction})))
    (text-response 400 "Bad request")))


(defn move-group
  "POST /plan-groups/:id/move?dir=up|down."
  [req]
  (move req plans/move-group!))


(defn move-task
  "POST /plan-tasks/:id/move?dir=up|down."
  [req]
  (move req plans/move-plan-task!))


(def routes
  "Plan routes as [method pattern handler]."
  [[:get ["plans"] index]
   [:post ["plans"] create-plan]
   [:get ["plans" "new"] new-page]
   [:get ["plans" :id] show]
   [:put ["plans" :id] update-plan]
   [:delete ["plans" :id] delete-plan]
   [:post ["plans" :id "groups"] create-group]
   [:post ["plans" :id "tasks"] create-task]
   [:put ["plan-groups" :id] update-group]
   [:delete ["plan-groups" :id] delete-group]
   [:post ["plan-groups" :id "move"] move-group]
   [:put ["plan-tasks" :id] update-task]
   [:delete ["plan-tasks" :id] delete-task]
   [:post ["plan-tasks" :id "move"] move-task]])
