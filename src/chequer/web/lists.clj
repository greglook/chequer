(ns chequer.web.lists
  "List pages, their SSE streams, and the list, group and task mutation handlers."
  (:require
    [chequer.data.add-plan :as add-plan]
    [chequer.data.lists :as lists]
    [chequer.data.plans :as plans]
    [chequer.db :as db]
    [chequer.live :as live]
    [chequer.markdown :as md]
    [chequer.time :as time]
    [chequer.users :as users]
    [chequer.web.common :as web :refer [action delete-icon-button delete-menu-item edit-button
                                        edit-form edit-icon-button edit-toggle errors-el field html
                                        html-response icon-field more-menu move-buttons mutate raw
                                        text-response toggle-signal]]
    [chequer.web.layout :as layout]
    [clojure.string :as str]
    [jolt.datastar.core :as ds]))


;; ## Viewing context

(def ^:private minute-ms 60000)


(defn- view
  "What a render depends on besides the data: the time (to the minute, so cached components
  stay valid for a minute), the viewer's zone and id, the ?mine=1 filter and the users that
  tasks can be assigned to."
  [{:chequer/keys [db user] :as req}]
  {:now (* minute-ms (quot (time/now-ms) minute-ms))
   :tz (layout/viewer-tz req)
   :me (:id user)
   :mine? (= "1" (web/query-param req "mine"))
   :users (users/list-users db)})


(defn- find-user
  [{:keys [users]} id]
  (some #(when (= id (:id %)) %) users))


(defn- due-el
  "The due date relative to now, with the date in the viewer's zone as its title."
  [due-at overdue? {:keys [now tz]}]
  (when due-at
    [:span.due {:title (time/format-date due-at tz)}
     (str "due " (time/relative due-at now))
     (when overdue? [:span.badge.overdue "overdue"])]))


(defn- due-field
  [due-at {:keys [tz]}]
  (field "Due" "due" (some-> due-at (time/format-date-input tz)) {:type "date"}))


(defn- closed-count
  [counts]
  (+ (:completed counts 0) (:skipped counts 0)))


(defn- list-due
  "When the list or its first open task is due, ignoring the list's own due date once it's done."
  [l done? next-due]
  (let [dues (remove nil? [(when-not done? (:list/due-at l)) next-due])]
    (when (seq dues) (apply min dues))))


;; ## Rendering

(def ^:private state-actions
  {:pending "Reset to pending"
   :skipped "Skip"})


(defn- state-url
  [task-id state]
  (str "/tasks/" task-id "/state?to=" (name state)))


(defn- task-form
  "The fields of a task form."
  [t v]
  (list (field "Title" "title" (:task/title t) {:required true})
        (icon-field "Task" (:task/id t) (:task/icon t))
        (field "Estimate (minutes)" "estimate" (some-> (:task/estimate t) str) {:inputmode "numeric" :size 6})
        (due-field (:task/due-at t) v)
        [:label "Assignee"
         [:select {:name "assignee"}
          [:option {:value ""} "Unassigned"]
          (for [u (:users v)]
            [:option {:value (str (:id u)) :selected (= (:id u) (:task/assignee t))} (:title u)])]]
        [:label "Description" [:textarea {:name "description" :rows 3} (or (:task/description t) "")]]))


(defn render-task
  "#task-<id>: a checkbox toggling it between pending and completed, its due date, its
  assignee's badge beside the controls, a menu of the other states, its history and
  deletion, a card of its full info that a click on the row expands, and an inline edit
  form."
  [t v]
  (let [id (:task/id t)
        el-id (str "task-" id)
        path (str "/tasks/" id)
        state (:task/state t)
        signal (toggle-signal "Task" id)
        description (:task/description t)]
    [:li.task (merge {:id el-id :class (name state)} (web/expandable el-id signal))
     [:div.row (web/expand-row-attrs el-id)
      [:input {:type "checkbox" :title "Done" :checked (= :completed state)
               :data-on:change (str "@post('" path "/state?to=' + (el.checked ? 'completed' : 'pending'))")}]
      [:span.icon (:task/icon t)]
      (web/expand-title el-id (:task/title t) description)
      (when (= :skipped state)
        [:span.badge {:class (name state)} (name state)])
      (when-let [e (:task/estimate t)] [:span.estimate (str e " min")])
      (due-el (:task/due-at t) (lists/overdue? t (:now v)) v)
      (some->> (:task/assignee t) (find-user v) web/user-badge)
      [:span.controls
       (edit-icon-button signal)
       (move-buttons path)
       (more-menu
         (for [s [:skipped :pending]
               :when (not= s state)]
           [:button {:type "button" :data-on:click (str "@post('" (state-url id s) "')")} (state-actions s)])
         [:a {:href (str path "/history")} "History"]
         (delete-menu-item "Delete this task?" path))]]
     (web/expanded-card el-id signal
                        [["State" (name state)]
                         ["Estimate" (some-> (:task/estimate t) (str " min"))]
                         ["Due" (some-> (:task/due-at t) (time/format-date (:tz v)))]
                         ["Assignee" (some->> (:task/assignee t) (find-user v) :title)]]
                        (some-> description md/render))
     (edit-form signal path
                (task-form t v)
                (errors-el (str "task-" id "-errors"))
                [:button "Save"])]))


(defn- new-task-form
  [list-id group-id]
  (let [suffix (str list-id (when group-id (str "-" group-id)))]
    [:form.new-task {:data-on:submit (str (action "post" (str "/lists/" list-id "/tasks")) "; el.reset()")}
     (when group-id [:input {:type "hidden" :name "group" :value (str group-id)}])
     (field [:span.visually-hidden "New task"] "title" nil {:required true :placeholder "Add a task"})
     (errors-el (str "new-task-" suffix "-errors"))
     [:button "Add"]]))


(defn- render-group-header
  "#list-group-<id>-header: the group's title and controls. Deleting confirms with the
  number of tasks, from data-task-count, that go with it."
  [group task-count]
  (let [id (:list.group/id group)
        path (str "/list-groups/" id)
        signal (toggle-signal "Group" id)]
    [:header (merge {:id (str "list-group-" id "-header")} (edit-toggle signal))
     [:h2
      (web/collapse-toggle (str "list-group-" id) (:list.group/icon group) (:list.group/title group) task-count)
      [:span.controls
       (edit-icon-button signal)
       (move-buttons path)
       (delete-icon-button (str "confirm('Delete this group and its ' + el.dataset.taskCount + ' task(s)?') && "
                                "@delete('" path "')")
                           {:data-task-count (str task-count)})]]
     (edit-form signal path
                (field "Title" "title" (:list.group/title group) {:required true})
                (icon-field "Group" id (:list.group/icon group))
                (errors-el (str "list-group-" id "-errors"))
                [:button "Save"])]))


(defn- shown?
  "True when the view shows task t: with ?mine=1, only when it's assigned to the viewer."
  [t {:keys [mine? me]}]
  (or (not mine?) (= me (:task/assignee t))))


(defn- task-view
  "#task-<id> for the view v, re-rendered when the task's topic is touched. Renders nothing
  when the task is gone or the view filters it out."
  [{:chequer/keys [db live]} list-id task-id v]
  @(live/cell live [:list list-id :task task-id])
  (when-let [t (lists/load-task db task-id)]
    (when (shown? t v)
      (render-task t v))))


(defn- task-list
  [src list-id task-ids v]
  [:ul.tasks (map #(vector task-view src list-id % v) task-ids)])


(defn- render-group
  "A group's section, rendered as a component. Its root has no id, so glimmer-datastar keeps the
  parts inside it, the header and each task, patchable on their own."
  [src list-id {:keys [group task-ids]} v]
  (let [id (:list.group/id group)]
    [:section.list-group (web/collapsible (str "list-group-" id))
     [render-group-header group (count task-ids)]
     (task-list src list-id task-ids v)
     [new-task-form list-id id]]))


(defn- render-header
  "#list-<id>-header: the title, progress, due date, controls and the inline edit form."
  [l counts v]
  (let [id (:list/id l)
        path (str "/lists/" id)
        signal (toggle-signal "List" id)
        total (reduce + (vals counts))
        done? (and (pos? total) (= total (closed-count counts)))
        archived? (some? (:list/archived-at l))]
    [:header.list-header (merge {:id (str "list-" id "-header")} (edit-toggle signal))
     [:h1
      [:span.icon (:list/icon l)]
      [:span.title (:list/title l)]
      (when done? [:span.badge.done "done"])
      (when archived? [:span.badge.archived "archived"])]
     [:p.summary
      (str (closed-count counts) " of " total " done")
      (when-not done?
        (due-el (:list/due-at l) (some-> (:list/due-at l) (< (:now v))) v))]
     [:progress {:max (str (max total 1)) :value (str (closed-count counts))}]
     (when-let [d (:list/description l)]
       [:div.markdown (raw (md/render d))])
     [:div.controls
      (if (:mine? v)
        [:a.toggle.on {:href path} "Assigned to me"]
        [:a.toggle {:href (str path "?mine=1")} "Assigned to me"])
      [:a {:href (str path "/history")} "History"]
      (edit-button signal)
      (if archived?
        [:button {:type "button" :data-on:click (str "@post('" path "/unarchive')")} "Unarchive"]
        [:button {:type "button" :data-on:click (str "@post('" path "/archive')")} "Archive"])
      [:button.danger {:type "button" :data-on:click (web/confirmed "Delete this list?" (str "@delete('" path "')"))} "Delete"]]
     (edit-form signal path
                (field "Title" "title" (:list/title l) {:required true})
                (icon-field "List" id (:list/icon l))
                (due-field (:list/due-at l) v)
                [:label "Description" [:textarea {:name "description" :rows 3} (or (:list/description l) "")]]
                (errors-el (str "list-" id "-errors"))
                [:button "Save"])]))


(defn- header-view
  "#list-<id>-header for the view v, re-rendered when the list's header topic is touched."
  [{:chequer/keys [db live]} list-id v]
  @(live/cell live [:list list-id :header])
  (when-let [{l :list :keys [counts]} (lists/load-list-header db list-id)]
    (render-header l counts v)))


(defn- list-body
  "The contents of #list-<id> for the view v, re-rendered when the list's structure topic is
  touched. The header and each task are components reading topics of their own, so a change
  to one task renders and sends only that task and the header. A deleted list sends the
  viewer to the index."
  [{:chequer/keys [db live] :as src} list-id v]
  @(live/cell live [:list list-id :structure])
  (if-let [{:keys [ungrouped groups]} (lists/load-list-structure db list-id)]
    (list [header-view src list-id v]
          (task-list src list-id ungrouped v)
          [new-task-form list-id nil]
          (map #(vector render-group src list-id % v) groups)
          [:form.new-group {:data-on:submit (str (action "post" (str "/lists/" list-id "/groups")) "; el.reset()")}
           (field [:span.visually-hidden "New group"] "title" nil {:required true :placeholder "Add a group"})
           (errors-el (str "new-list-group-" list-id "-errors"))
           [:button "Add group"]])
    (web/redirect-el "/")))


(defn render-list
  "#list-<id> as hiccup for the view v, reading the list through src (see web/sources)."
  [src list-id v]
  [:div.list {:id (str "list-" list-id)}
   [list-body src list-id v]])


(defn- render-index-row
  [{l :list :keys [counts done? next-due]} v]
  (let [id (:list/id l)
        due (list-due l done? next-due)
        total (reduce + (vals counts))]
    [:li {:id (str "list-row-" id)}
     [:a {:href (str "/lists/" id)} [:span.icon (:list/icon l)] " " (:list/title l)]
     " "
     [:span.summary (str (closed-count counts) " of " total " done")]
     (when done? [:span.badge.done "done"])
     (due-el due (some-> due (< (:now v))) v)
     [:progress {:max (str (max total 1)) :value (str (closed-count counts))}]]))


(defn- index-order
  "Overdue lists first, then by due date (undated last), then the most recently updated."
  [rows now]
  (sort-by (fn [{l :list :keys [done? next-due]}]
             (let [due (list-due l done? next-due)]
               [(if (and due (< due now)) 0 1) (if due 0 1) (or due 0) (- (:list/updated-at l))]))
           rows))


(defn- render-index
  "The active (or archived) lists, subscribing the render to each of them."
  [{:chequer/keys [db live]} archived? v]
  @(live/cell live [:lists])
  (let [rows (filter #(= archived? (some? (:list/archived-at (:list %)))) (lists/list-summary db))]
    (doseq [r rows]
      @(live/cell live [:list (:list/id (:list r))]))
    [:div.lists
     (if (seq rows)
       [:ul.lists (map #(vector render-index-row % v) (index-order rows (:now v)))]
       [:p.empty (if archived? "No archived lists." "No lists yet.")])]))


;; ## Adding plans

(def ^:private plan-ids-key
  (keyword "plan_ids[]"))


(defn- plan-picker
  "Checkboxes choosing plans, submitted as plan_ids[] in the order shown, with up and down
  buttons that reorder them in the page. The plans with ids in selected come first, checked,
  in that order."
  [all-plans selected]
  (let [by-id (into {} (map (juxt :plan/id identity)) all-plans)
        chosen (keep by-id (distinct selected))
        chosen? (set (map :plan/id chosen))]
    (when (seq all-plans)
      [:fieldset.plan-picker
       [:legend "Plans"]
       [:ol
        (for [p (concat chosen (remove #(chosen? (:plan/id %)) all-plans))]
          [:li
           [:label
            [:input {:type "checkbox" :name "plan_ids[]" :value (str (:plan/id p)) :checked (contains? chosen? (:plan/id p))}]
            [:span.icon (:plan/icon p)] " " (:plan/title p)]
           [:button {:type "button" :title "Move up"
                     :data-on:click "el.closest('li').previousElementSibling?.before(el.closest('li'))"} "↑"]
           [:button {:type "button" :title "Move down"
                     :data-on:click "el.closest('li').nextElementSibling?.after(el.closest('li'))"} "↓"]])]])))


(defn- plan-ids
  "The ids of the body's plan_ids[], in order and without repeats."
  [body]
  (vec (distinct (keep web/parse-id (get body plan-ids-key)))))


(defn- pluralize
  [n noun]
  (str n " " noun (when (not= 1 n) "s")))


(defn flash-message
  "A sentence describing the result of add-plan!, e.g. \"Added Packing: 12 tasks, 3 skipped\"."
  [{:keys [plan-title tasks-created tasks-skipped]}]
  (str "Added " plan-title ": " (pluralize tasks-created "task")
       (when (pos? tasks-skipped) (str ", " tasks-skipped " skipped"))))


(defn- flash-id
  [list-id]
  (str "list-" list-id "-flash"))


(defn- add-plans-form
  "The form adding plans to list list-id, outside #live so re-renders keep its choices, and
  the flash element its results go to."
  [list-id all-plans]
  (when (seq all-plans)
    (list [:details.add-plans
           [:summary "Add plans"]
           [:form {:data-on:submit (str (action "post" (str "/lists/" list-id "/add-plans")) "; el.reset()")}
            (plan-picker all-plans nil)
            (errors-el (str "add-plans-" list-id "-errors"))
            [:button "Add"]]]
          [:div.flash {:id (flash-id list-id)}])))


;; ## Pages

(defn index
  "GET / and GET /lists: the active lists, or with ?archived=1 the archived ones."
  [req]
  (let [archived? (= "1" (web/query-param req "archived"))
        fragment (render-index req archived? (view req))]
    (if (:jolt.datastar/sse-request req)
      (html-response 200 (html fragment))
      (html-response 200
                     (layout/page req (if archived? "Archived lists" "Lists")
                                  [:h1 (if archived? "Archived lists" "Lists")]
                                  [:p.actions
                                   [:a.button.primary {:href "/lists/new"} "New list"]
                                   (if archived?
                                     [:a {:href "/"} "Show active"]
                                     [:a {:href "/lists?archived=1"} "Show archived"])]
                                  [:div#live (ds/init-opts {:selector "#live"}) fragment])))))


(defn new-page
  "GET /lists/new. The form works as a plain POST and, with Datastar, shows errors inline.
  ?plan=<id> pre-selects that plan."
  [{:chequer/keys [db] :as req}]
  (html-response 200
                 (layout/page req "New list"
                              [:h1 "New list"]
                              [:form {:method "post" :action "/lists" :data-on:submit (action "post" "/lists")}
                               (field "Title" "title" nil {:required true :autofocus true})
                               (icon-field "List" nil nil)
                               (field "Due" "due" nil {:type "date"})
                               [:label "Description" [:textarea {:name "description" :rows 4}]]
                               (plan-picker (plans/list-plans db)
                                            (some-> (web/query-param req "plan") parse-long vector))
                               (errors-el "new-list-errors")
                               [:button "Create"]])))


(defn show
  "GET /lists/:id. The page opens an SSE request to the same URL (with its ?mine=1, if any),
  which glimmer-datastar re-renders into #live whenever the list's topic is touched; a
  deleted list sends the viewer to the index."
  [{:chequer/keys [db] :keys [params] :as req}]
  (let [id (:id params)
        fragment (render-list (web/sources req) id (view req))]
    (if (:jolt.datastar/sse-request req)
      (html-response 200 (html fragment))
      (if-let [{:keys [title]} (db/q1 db ["SELECT title FROM list WHERE id = ?" id])]
        (html-response 200
                       (layout/page req title
                                    [:div#live (ds/init-opts {:selector "#live"}) fragment]
                                    (add-plans-form id (plans/list-plans db))))
        (text-response 404 "Not found")))))


;; ## Mutations

(defn- due-attrs
  "{:due_at ms} from the body's date :due, the end of that day in the viewer's zone; {} without one."
  [body req]
  (if (contains? body :due)
    (let [s (str/trim (or (:due body) ""))]
      (if (str/blank? s)
        {:due_at nil}
        {:due_at (or (time/parse-date-input s (layout/viewer-tz req))
                     (web/invalid! {:due "must be a date"}))}))
    {}))


(defn- assignee-attrs
  "{:assignee_id id} from the body's :assignee user id, blank for none; {} without one."
  [tx body]
  (if (contains? body :assignee)
    (let [id (web/parse-id (:assignee body))]
      (when (and id (nil? (plans/fetch-row tx "user" id)))
        (web/invalid! {:assignee "is not a user"}))
      {:assignee_id id})
    {}))


(defn- list-attrs
  [body req]
  (merge (select-keys body [:title :description :icon]) (due-attrs body req)))


(defn- task-attrs
  [tx body req]
  (merge (select-keys body [:title :description :icon :estimate])
         (due-attrs body req)
         (assignee-attrs tx body)))


(defn- by-id
  [req]
  (get-in req [:params :id]))


(defn create-list
  "POST /lists: creates the list and adds the plan_ids[] plans to it, in order, then 303s to
  it, or, from Datastar, sends an SSE redirect."
  [req]
  (mutate req "new-list-errors"
          (fn [tx user body]
            (let [id (lists/create-list! tx user (merge {:title nil} (list-attrs body req)))]
              (add-plan/add-plans! tx user id (plan-ids body))
              id))
          #(web/created-response req (str "/lists/" %))))


(defn update-list
  "PUT /lists/:id."
  [req]
  (let [id (by-id req)]
    (mutate req (str "list-" id "-errors")
            (fn [tx user body]
              (lists/update-list! tx user (assoc (list-attrs body req) :id id))))))


(defn delete-list
  "DELETE /lists/:id."
  [req]
  (mutate req nil (fn [tx user _] (lists/delete-list! tx user {:id (by-id req)}))))


(defn archive-list
  "POST /lists/:id/archive."
  [req]
  (mutate req nil (fn [tx user _] (lists/archive-list! tx user {:id (by-id req)}))))


(defn unarchive-list
  "POST /lists/:id/unarchive."
  [req]
  (mutate req nil (fn [tx user _] (lists/unarchive-list! tx user {:id (by-id req)}))))


(defn create-group
  "POST /lists/:id/groups."
  [req]
  (let [id (by-id req)]
    (mutate req (str "new-list-group-" id "-errors")
            (fn [tx user body]
              (lists/create-group! tx user (assoc (merge {:title nil} (select-keys body [:title :icon])) :list-id id))))))


(defn update-group
  "PUT /list-groups/:id."
  [req]
  (let [id (by-id req)]
    (mutate req (str "list-group-" id "-errors")
            (fn [tx user body]
              (lists/update-group! tx user (assoc (select-keys body [:title :icon]) :id id))))))


(defn delete-group
  "DELETE /list-groups/:id."
  [req]
  (mutate req nil (fn [tx user _] (lists/delete-group! tx user {:id (by-id req)}))))


(defn move-group
  "POST /list-groups/:id/move?dir=up|down."
  [req]
  (web/move req lists/move-group!))


(defn create-task
  "POST /lists/:id/tasks; the optional group field puts the task in that group."
  [req]
  (let [id (by-id req)]
    (mutate req nil
            (fn [tx user body]
              (let [group (web/parse-id (:group body))]
                (web/with-errors-id (str "new-task-" id (when group (str "-" group)) "-errors")
                  #(lists/create-task! tx user (assoc (merge {:title nil} (task-attrs tx body req))
                                                      :list-id id :group-id group))))))))


(defn update-task
  "PUT /tasks/:id."
  [req]
  (let [id (by-id req)]
    (mutate req (str "task-" id "-errors")
            (fn [tx user body]
              (lists/update-task! tx user (assoc (task-attrs tx body req) :id id))))))


(defn delete-task
  "DELETE /tasks/:id."
  [req]
  (mutate req nil (fn [tx user _] (lists/delete-task! tx user {:id (by-id req)}))))


(defn move-task
  "POST /tasks/:id/move?dir=up|down."
  [req]
  (web/move req lists/move-task!))


(def ^:private states-by-name
  (zipmap (map name lists/states) lists/states))


(defn set-task-state
  "POST /tasks/:id/state?to=pending|completed|skipped."
  [req]
  (if-let [state (states-by-name (web/query-param req "to"))]
    (mutate req nil (fn [tx user _] (lists/set-state! tx user {:id (by-id req) :state state})))
    (text-response 400 "Bad request")))


(defn add-plans
  "POST /lists/:id/add-plans: adds the plan_ids[] plans, in order, and answers with a flash
  describing what each added."
  [req]
  (let [id (by-id req)]
    (mutate req (str "add-plans-" id "-errors")
            (fn [tx user body]
              (let [pids (plan-ids body)]
                (when (empty? pids)
                  (web/invalid! {:plans "must include at least one plan"}))
                (add-plan/add-plans! tx user id pids)))
            (fn [results]
              (web/sse-response
                [(ds/patch-elements-event (html [:div.flash {:id (flash-id id)}
                                                 (for [r results] [:p (flash-message r)])])
                                          nil "outer")
                 (ds/patch-elements-event (html (errors-el (str "add-plans-" id "-errors"))) nil "outer")])))))


(def routes
  "List routes as [method pattern handler]."
  [[:get [] index]
   [:get ["lists"] index]
   [:post ["lists"] create-list]
   [:get ["lists" "new"] new-page]
   [:get ["lists" :id] show]
   [:put ["lists" :id] update-list]
   [:delete ["lists" :id] delete-list]
   [:post ["lists" :id "archive"] archive-list]
   [:post ["lists" :id "unarchive"] unarchive-list]
   [:post ["lists" :id "add-plans"] add-plans]
   [:post ["lists" :id "groups"] create-group]
   [:post ["lists" :id "tasks"] create-task]
   [:put ["list-groups" :id] update-group]
   [:delete ["list-groups" :id] delete-group]
   [:post ["list-groups" :id "move"] move-group]
   [:put ["tasks" :id] update-task]
   [:delete ["tasks" :id] delete-task]
   [:post ["tasks" :id "move"] move-task]
   [:post ["tasks" :id "state"] set-task-state]])
