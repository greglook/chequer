(ns chequer.web.plans
  "Plan pages, the plan SSE stream, and the plan, group and task mutation handlers."
  (:require
    [chequer.data.plans :as plans]
    [chequer.db :as db]
    [chequer.live :as live]
    [chequer.markdown :as md]
    [chequer.web.common :as web :refer [action confirmed edit-button edit-form edit-toggle errors-el field
                                        html html-response move-buttons mutate raw text-response toggle-signal]]
    [chequer.web.layout :as layout]
    [jolt.datastar.core :as ds]))


;; ## Rendering

(defn- task-form
  "The fields of a task form."
  [t]
  (list (field "Title" "title" (:plan.task/title t) {:required true})
        (field "Icon" "icon" (:plan.task/icon t) {:size 4})
        (field "Estimate (minutes)" "estimate" (some-> (:plan.task/estimate t) str) {:inputmode "numeric" :size 6})
        [:label "Description" [:textarea {:name "description" :rows 3} (or (:plan.task/description t) "")]]))


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
       (edit-button signal)
       (move-buttons path)
       [:button {:type "button" :data-on:click (confirmed "Delete this task?" (str "@delete('" path "')"))} "Delete"]]]
     (when-let [d (:plan.task/description t)]
       [:details [:summary "Description"] [:div.markdown (raw (md/render d))]])
     (edit-form signal path
                (task-form t)
                (errors-el errors)
                [:button "Save"])]))


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
       (edit-button signal)
       (move-buttons path)
       [:button {:type "button" :data-on:click (confirmed "Delete this group and its tasks?" (str "@delete('" path "')"))}
        "Delete"]]]
     (edit-form signal path
                (field "Title" "title" (:plan.group/title group) {:required true})
                (field "Icon" "icon" (:plan.group/icon group) {:size 4})
                (errors-el (str "plan-group-" id "-errors"))
                [:button "Save"])
     [:ul.plan-tasks (map #(vector render-plan-task %) tasks)]
     [new-task-form plan-id id]]))


(defn- render-loaded-plan
  [{:keys [plan ungrouped groups]}]
  (let [id (:plan/id plan)
        path (str "/plans/" id)
        signal (toggle-signal "Plan" id)]
    [:div.plan (merge {:id (str "plan-" id)} (edit-toggle signal))
     [:h1 [:span.icon (:plan/icon plan)] [:span.title (:plan/title plan)]]
     (when-let [d (:plan/description plan)]
       [:div.markdown (raw (md/render d))])
     [:div.controls
      [:a.button {:href (str "/lists/new?plan=" id)} "Start a list from this plan"]
      (edit-button signal)
      [:button {:type "button" :data-on:click (confirmed "Delete this plan?" (str "@delete('" path "')"))} "Delete"]]
     (edit-form signal path
                (field "Title" "title" (:plan/title plan) {:required true})
                (field "Icon" "icon" (:plan/icon plan) {:size 4})
                [:label "Description" [:textarea {:name "description" :rows 3} (or (:plan/description plan) "")]]
                (errors-el (str "plan-" id "-errors"))
                [:button "Save"])
     [:ul.plan-tasks (map #(vector render-plan-task %) ungrouped)]
     [new-task-form id nil]
     (map #(vector render-group id %) groups)
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
      (:jolt.datastar/sse-request req) (html-response 200 (if fragment (html fragment) (web/redirect-script "/plans")))
      fragment (html-response 200
                              (layout/page req (:title (db/q1 db ["SELECT title FROM plan WHERE id = ?" id]))
                                           [:div#live (ds/init-opts {:selector "#live"}) fragment]))
      :else (text-response 404 "Not found"))))


;; ## Mutations

(defn- entity-attrs
  [params ks]
  (select-keys params ks))


(defn create-plan
  "POST /plans: 303 to the new plan, or, from Datastar, an SSE redirect."
  [req]
  (mutate req "new-plan-errors"
          (fn [tx user params]
            (plans/create-plan! tx user (merge {:title nil} (entity-attrs params [:title :description :icon]))))
          #(web/created-response req (str "/plans/" %))))


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


(defn create-task
  "POST /plans/:id/tasks; the optional group field puts the task in that group."
  [{:keys [params] :as req}]
  (let [id (:id params)]
    (mutate req nil
            (fn [tx user body]
              (let [group (web/parse-id (:group body))]
                (web/with-errors-id (str "new-plan-task-" id (when group (str "-" group)) "-errors")
                  #(plans/create-plan-task! tx user (assoc (merge {:title nil}
                                                                  (select-keys body [:title :description :icon :estimate]))
                                                           :plan-id id :group-id group))))))))


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


(defn move-group
  "POST /plan-groups/:id/move?dir=up|down."
  [req]
  (web/move req plans/move-group!))


(defn move-task
  "POST /plan-tasks/:id/move?dir=up|down."
  [req]
  (web/move req plans/move-plan-task!))


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
