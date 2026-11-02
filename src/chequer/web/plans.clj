(ns chequer.web.plans
  "Plan pages, the plan SSE stream, and the plan, section and task mutation handlers."
  (:require
    [chequer.data.plans :as plans]
    [chequer.db :as db]
    [chequer.live :as live]
    [chequer.markdown :as md]
    [chequer.web.common :as web
     :refer [action confirmed delete-icon-button delete-menu-item
             edit-button edit-form edit-icon-button edit-toggle errors-el
             field html html-response icon-field more-menu move-buttons mutate raw
             text-response toggle-signal]]
    [chequer.web.layout :as layout]))


;; ## Rendering

(defn- task-form
  "The fields of a task form."
  [t]
  (list (field "Title" "title" (:plan.task/title t) {:required true})
        (icon-field (:plan.task/icon t))
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
    [:li.plan-task (merge {:id el-id} (web/expandable el-id signal) (web/draggable id path))
     [:div.row (web/expand-row-attrs el-id)
      web/drag-grip
      [:span.icon (:plan.task/icon t)]
      (web/expand-title el-id (:plan.task/title t) description)
      [:span.controls
       (edit-icon-button signal)
       (more-menu (delete-menu-item "Delete this task?" path))]]
     (web/expanded-card el-id signal [] (some-> description md/render))
     (edit-form signal path
                (task-form t)
                (errors-el errors)
                [:button "Save"])]))


(defn- new-task-form
  [plan-id section-id]
  (let [suffix (str plan-id (when section-id (str "-" section-id)))]
    (web/new-task-form (str "/plans/" plan-id "/tasks")
                       (when section-id [:input {:type "hidden" :name "section" :value (str section-id)}])
                       (errors-el (str "new-plan-task-" suffix "-errors")))))


(defn- render-section-header
  "#plan-section-<id>-header: the section's title, which collapses it, its controls and an
  inline edit form."
  [section task-count]
  (let [id (:plan.section/id section)
        path (str "/plan-sections/" id)
        signal (toggle-signal "Section" id)]
    [:header (merge {:id (str "plan-section-" id "-header")} (edit-toggle signal))
     [:h2
      (web/collapse-toggle (str "plan-section-" id) (:plan.section/icon section) (:plan.section/title section) task-count)
      [:span.controls
       (edit-icon-button signal)
       (move-buttons path)
       (delete-icon-button (confirmed "Delete this section and its tasks?" (str "@delete('" path "')")))]]
     (edit-form signal path
                (field "Title" "title" (:plan.section/title section) {:required true})
                (icon-field (:plan.section/icon section))
                (errors-el (str "plan-section-" id "-errors"))
                [:button "Save"])]))


(defn- plan-task-view
  "#plan-task-<id>, re-rendered when the task's topic is touched; nothing once it's gone."
  [{:chequer/keys [db live]} plan-id task-id]
  @(live/cell live [:plan plan-id :task task-id])
  (some-> (plans/load-plan-task db task-id) render-plan-task))


(defn- task-list
  [src plan-id section-id task-ids]
  [:ul.plan-tasks (web/task-list-attrs section-id) (map #(vector plan-task-view src plan-id %) task-ids)])


(defn- render-section
  "A section's section, rendered as a component. Its root has no id, so glimmer-datastar keeps the
  parts inside it, the header and each task, patchable on their own."
  [src plan-id {:keys [section task-ids]}]
  (let [id (:plan.section/id section)]
    [:section.plan-section (web/collapsible (str "plan-section-" id))
     [render-section-header section (count task-ids)]
     (task-list src plan-id id task-ids)
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
                (icon-field (:plan/icon p))
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
  (if-let [{:keys [unsectioned sections]} (plans/load-plan-structure db plan-id)]
    (list [header-view src plan-id]
          (task-list src plan-id nil unsectioned)
          [new-task-form plan-id nil]
          (map #(vector render-section src plan-id %) sections)
          [:form.new-section {:data-on:submit (str (action "post" (str "/plans/" plan-id "/sections")) "; el.reset()")}
           (field [:span.visually-hidden "New section"] "title" nil {:required true :placeholder "Add a section"})
           (errors-el (str "new-plan-section-" plan-id "-errors"))
           [:button "Add section"]])
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
                               (icon-field nil)
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
        (html-response 200 (layout/page req title [:div#live web/live-attrs fragment]))
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


(defn create-section
  "POST /plans/:id/sections."
  [{:keys [params] :as req}]
  (let [id (:id params)]
    (mutate req (str "new-plan-section-" id "-errors")
            (fn [tx user body]
              (plans/create-section! tx user (assoc (merge {:title nil} (entity-attrs body [:title :icon])) :plan-id id))))))


(defn update-section
  "PUT /plan-sections/:id."
  [{:keys [params] :as req}]
  (let [id (:id params)]
    (mutate req (str "plan-section-" id "-errors")
            (fn [tx user body]
              (plans/update-section! tx user (assoc (entity-attrs body [:title :icon]) :id id))))))


(defn delete-section
  "DELETE /plan-sections/:id."
  [{:keys [params] :as req}]
  (mutate req nil (fn [tx user _] (plans/delete-section! tx user {:id (:id params)}))))


(defn create-task
  "POST /plans/:id/tasks; the optional section field puts the task in that section."
  [{:keys [params] :as req}]
  (let [id (:id params)]
    (mutate req nil
            (fn [tx user body]
              (let [section (web/parse-id (:section body))]
                (web/with-errors-id (str "new-plan-task-" id (when section (str "-" section)) "-errors")
                  #(plans/create-plan-task! tx user (assoc (merge {:title nil}
                                                                  (select-keys body [:title :description :icon]))
                                                           :plan-id id :section-id section)))))
            (web/create-task-response req))))


(defn update-task
  "PUT /plan-tasks/:id."
  [{:keys [params] :as req}]
  (let [id (:id params)]
    (mutate req (str "plan-task-" id "-errors")
            (fn [tx user body]
              (plans/update-plan-task! tx user (assoc (entity-attrs body [:title :description :icon]) :id id))))))


(defn delete-task
  "DELETE /plan-tasks/:id."
  [{:keys [params] :as req}]
  (mutate req nil (fn [tx user _] (plans/delete-plan-task! tx user {:id (:id params)}))))


(defn move-section
  "POST /plan-sections/:id/move?dir=up|down."
  [req]
  (web/move req plans/move-section!))


(defn move-task
  "POST /plan-tasks/:id/move?section=<id>&before=<id>."
  [req]
  (web/place req plans/place-plan-task!))


(def routes
  "Plan routes as [method pattern handler]."
  [[:get ["plans"] index]
   [:post ["plans"] create-plan]
   [:get ["plans" "new"] new-page]
   [:get ["plans" :id] show]
   [:put ["plans" :id] update-plan]
   [:delete ["plans" :id] delete-plan]
   [:post ["plans" :id "sections"] create-section]
   [:post ["plans" :id "tasks"] create-task]
   [:put ["plan-sections" :id] update-section]
   [:delete ["plan-sections" :id] delete-section]
   [:post ["plan-sections" :id "move"] move-section]
   [:put ["plan-tasks" :id] update-task]
   [:delete ["plan-tasks" :id] delete-task]
   [:post ["plan-tasks" :id "move"] move-task]])
