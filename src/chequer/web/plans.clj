(ns chequer.web.plans
  "Plan pages, the plan SSE stream, and the plan, group and task mutation handlers."
  (:require
    [chequer.data.plans :as plans]
    [chequer.db :as db]
    [chequer.live :as live]
    [chequer.markdown :as md]
    [chequer.web.common :as web :refer [action confirmed delete-icon-button delete-menu-item
                                        edit-button edit-form edit-icon-button edit-toggle errors-el
                                        field html html-response icon-field more-menu move-buttons mutate raw
                                        text-response toggle-signal]]
    [chequer.web.layout :as layout]
    [jolt.datastar.core :as ds]))


;; ## Rendering

(defn- task-form
  "The fields of a task form."
  [t]
  (list (field "Title" "title" (:plan.task/title t) {:required true})
        (icon-field "Task" (:plan.task/id t) (:plan.task/icon t))
        (field "Estimate (minutes)" "estimate" (some-> (:plan.task/estimate t) str) {:inputmode "numeric" :size 6})
        [:label "Description" [:textarea {:name "description" :rows 3} (or (:plan.task/description t) "")]]))


(defn render-plan-task
  "#plan-task-<id>: the title row, the controls, a card of its full info that a click on the
  row expands, and an inline edit form."
  [t]
  (let [id (:plan.task/id t)
        el-id (str "plan-task-" id)
        path (str "/plan-tasks/" id)
        signal (toggle-signal "Task" id)
        errors (str "plan-task-" id "-errors")
        description (:plan.task/description t)]
    [:li.plan-task (merge {:id el-id} (web/expandable el-id signal))
     [:div.row (web/expand-row-attrs el-id)
      [:span.icon (:plan.task/icon t)]
      (web/expand-title el-id (:plan.task/title t) description)
      (when-let [e (:plan.task/estimate t)] [:span.estimate (str e " min")])
      [:span.controls
       (edit-icon-button signal)
       (move-buttons path)
       (more-menu (delete-menu-item "Delete this task?" path))]]
     (web/expanded-card el-id signal
                        [["Estimate" (some-> (:plan.task/estimate t) (str " min"))]]
                        (some-> description md/render))
     (edit-form signal path
                (task-form t)
                (errors-el errors)
                [:button "Save"])]))


(defn- new-task-form
  [plan-id group-id]
  (let [suffix (str plan-id (when group-id (str "-" group-id)))]
    [:form.new-task {:data-on:submit (str (action "post" (str "/plans/" plan-id "/tasks")) "; el.reset()")}
     (when group-id [:input {:type "hidden" :name "group" :value (str group-id)}])
     (field [:span.visually-hidden "New task"] "title" nil {:required true :placeholder "Add a task"})
     (errors-el (str "new-plan-task-" suffix "-errors"))
     [:button "Add"]]))


(defn- render-group-header
  "#plan-group-<id>-header: the group's title, its controls and an inline edit form."
  [group]
  (let [id (:plan.group/id group)
        path (str "/plan-groups/" id)
        signal (toggle-signal "Group" id)]
    [:header (merge {:id (str "plan-group-" id "-header")} (edit-toggle signal))
     [:h2
      [:span.icon (:plan.group/icon group)]
      [:span.title (:plan.group/title group)]
      [:span.controls
       (edit-icon-button signal)
       (move-buttons path)
       (delete-icon-button (confirmed "Delete this group and its tasks?" (str "@delete('" path "')")))]]
     (edit-form signal path
                (field "Title" "title" (:plan.group/title group) {:required true})
                (icon-field "Group" id (:plan.group/icon group))
                (errors-el (str "plan-group-" id "-errors"))
                [:button "Save"])]))


(defn- plan-task-view
  "#plan-task-<id>, re-rendered when the task's topic is touched; nothing once it's gone."
  [{:chequer/keys [db live]} plan-id task-id]
  @(live/cell live [:plan plan-id :task task-id])
  (some-> (plans/load-plan-task db task-id) render-plan-task))


(defn- task-list
  [src plan-id task-ids]
  [:ul.plan-tasks (map #(vector plan-task-view src plan-id %) task-ids)])


(defn- render-group
  "A group's section, rendered as a component. Its root has no id, so glimmer-datastar keeps the
  parts inside it, the header and each task, patchable on their own."
  [src plan-id {:keys [group task-ids]}]
  (let [id (:plan.group/id group)]
    [:section.plan-group
     [render-group-header group]
     (task-list src plan-id task-ids)
     [new-task-form plan-id id]]))


(defn- render-header
  "#plan-<id>-header: the title, description, controls and an inline edit form."
  [p]
  (let [id (:plan/id p)
        path (str "/plans/" id)
        signal (toggle-signal "Plan" id)]
    [:header.plan-header (merge {:id (str "plan-" id "-header")} (edit-toggle signal))
     [:h1 [:span.icon (:plan/icon p)] [:span.title (:plan/title p)]]
     (when-let [d (:plan/description p)]
       [:div.markdown (raw (md/render d))])
     [:div.controls
      [:a.button.primary {:href (str "/lists/new?plan=" id)} "Start a list from this plan"]
      [:a {:href (str path "/history")} "History"]
      (edit-button signal)
      [:button.danger {:type "button" :data-on:click (confirmed "Delete this plan?" (str "@delete('" path "')"))} "Delete"]]
     (edit-form signal path
                (field "Title" "title" (:plan/title p) {:required true})
                (icon-field "Plan" id (:plan/icon p))
                [:label "Description" [:textarea {:name "description" :rows 3} (or (:plan/description p) "")]]
                (errors-el (str "plan-" id "-errors"))
                [:button "Save"])]))


(defn- header-view
  "#plan-<id>-header, re-rendered when the plan's header topic is touched."
  [{:chequer/keys [db live]} plan-id]
  @(live/cell live [:plan plan-id :header])
  (some-> (plans/load-plan-header db plan-id) render-header))


(defn- plan-body
  "The contents of #plan-<id>, re-rendered when the plan's structure topic is touched. The
  header and each task are components reading topics of their own, so a change to one task
  renders and sends only that task. A deleted plan sends the viewer to the index."
  [{:chequer/keys [db live] :as src} plan-id]
  @(live/cell live [:plan plan-id :structure])
  (if-let [{:keys [ungrouped groups]} (plans/load-plan-structure db plan-id)]
    (list [header-view src plan-id]
          (task-list src plan-id ungrouped)
          [new-task-form plan-id nil]
          (map #(vector render-group src plan-id %) groups)
          [:form.new-group {:data-on:submit (str (action "post" (str "/plans/" plan-id "/groups")) "; el.reset()")}
           (field [:span.visually-hidden "New group"] "title" nil {:required true :placeholder "Add a group"})
           (errors-el (str "new-plan-group-" plan-id "-errors"))
           [:button "Add group"]])
    (web/redirect-el "/plans")))


(defn render-plan
  "#plan-<id> as hiccup, reading the plan through src (see web/sources)."
  [src plan-id]
  [:div.plan {:id (str "plan-" plan-id)}
   [plan-body src plan-id]])


;; ## Pages

(defn index
  "GET /plans."
  [{:chequer/keys [db] :as req}]
  (html-response 200
                 (layout/page req "Plans"
                              [:h1 "Plans"]
                              [:p.actions [:a.button.primary {:href "/plans/new"} "New plan"]]
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
                               (icon-field "Plan" nil nil)
                               [:label "Description" [:textarea {:name "description" :rows 4}]]
                               (errors-el "new-plan-errors")
                               [:button "Create"]])))


(defn show
  "GET /plans/:id. The page opens an SSE request to the same URL, which glimmer-datastar
  re-renders into #live whenever the plan's topic is touched; a deleted plan sends the
  viewer to the index."
  [{:chequer/keys [db] :keys [params] :as req}]
  (let [id (:id params)
        fragment (render-plan (web/sources req) id)]
    (if (:jolt.datastar/sse-request req)
      (html-response 200 (html fragment))
      (if-let [{:keys [title]} (db/q1 db ["SELECT title FROM plan WHERE id = ?" id])]
        (html-response 200 (layout/page req title [:div#live (ds/init-opts {:selector "#live"}) fragment]))
        (text-response 404 "Not found")))))


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
