(ns chequer.web.lists
  "List pages, their SSE streams, and the list, section and task mutation handlers."
  (:require
    [chequer.data.add-plan :as add-plan]
    [chequer.data.lists :as lists]
    [chequer.data.plans :as plans]
    [chequer.db :as db]
    [chequer.live :as live]
    [chequer.markdown :as md]
    [chequer.time :as time]
    [chequer.users :as users]
    [chequer.web.common :as web :refer [action delete-icon-button delete-menu-item edit-form
                                        edit-icon-button edit-toggle errors-el escape-expr field
                                        history-icon-link html html-response icon-button
                                        icon-field more-menu move-buttons mutate raw
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
        (icon-field (:task/icon t))
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
    [:li.task (merge {:id el-id :class (name state)} (web/expandable el-id signal) (web/draggable id path))
     [:div.row (web/expand-row-attrs el-id)
      web/drag-grip
      [:input {:type "checkbox" :title "Done" :checked (= :completed state)
               :data-on:change (str "@post('" path "/state?to=' + (el.checked ? 'completed' : 'pending'))")}]
      [:span.icon (:task/icon t)]
      (web/expand-title el-id (:task/title t) description)
      (when (= :skipped state)
        [:span.badge {:class (name state)} (name state)])
      (due-el (:task/due-at t) (lists/overdue? t (:now v)) v)
      (some->> (:task/assignee t) (find-user v) web/user-badge)
      [:span.controls
       (edit-icon-button signal)
       (more-menu
         (for [s [:skipped :pending]
               :when (not= s state)]
           [:button {:type "button" :data-on:click (str "@post('" (state-url id s) "')")} (state-actions s)])
         [:a {:href (str path "/history")} "History"]
         (delete-menu-item "Delete this task?" path))]]
     (web/expanded-card el-id signal
                        [["State" (name state)]
                         ["Due" (some-> (:task/due-at t) (time/format-date (:tz v)))]
                         ["Assignee" (some->> (:task/assignee t) (find-user v) :title)]]
                        (some-> description md/render))
     (edit-form signal path
                (task-form t v)
                (errors-el (str "task-" id "-errors"))
                [:button "Save"])]))


(defn- new-task-form
  [list-id section-id]
  (let [suffix (str list-id (when section-id (str "-" section-id)))]
    (web/new-task-form (str "/lists/" list-id "/tasks")
                       (when section-id [:input {:type "hidden" :name "section" :value (str section-id)}])
                       (errors-el (str "new-task-" suffix "-errors")))))


(defn- render-section-header
  "#list-section-<id>-header: the section's title and controls. Collapsed, it counts the
  section's closed tasks of its counts out of task-count. Deleting confirms with the number of
  tasks, from data-task-count, that go with it."
  [list-id section counts task-count]
  (let [id (:list.section/id section)
        path (str "/list-sections/" id)
        signal (toggle-signal "Section" id)]
    [:header (merge {:id (str "list-section-" id "-header")} (edit-toggle signal))
     [:h2
      (web/collapse-toggle (str "list-" list-id) (str "list-section-" id) (:list.section/icon section) (:list.section/title section)
                           (str (closed-count counts) "/" task-count))
      [:span.controls
       (edit-icon-button signal)
       (move-buttons path)
       (delete-icon-button (str "confirm('Delete this section and its ' + el.dataset.taskCount + ' task(s)?') && "
                                "@delete('" path "')")
                           {:data-task-count (str task-count)})]]
     (edit-form signal path
                (field "Title" "title" (:list.section/title section) {:required true})
                (icon-field (:list.section/icon section))
                (errors-el (str "list-section-" id "-errors"))
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
  [src list-id section-id task-ids v]
  [:ul.tasks (web/task-list-attrs section-id) (map #(vector task-view src list-id % v) task-ids)])


(defn- section-header-view
  "#list-section-<id>-header, re-rendered when the list's header topic, which task state
  changes touch, is touched."
  [{:chequer/keys [db live]} list-id section task-count]
  @(live/cell live [:list list-id :header])
  (render-section-header list-id section (lists/load-section-counts db (:list.section/id section)) task-count))


(defn- render-section
  "A section's section, rendered as a component. Its root has no id, so glimmer-datastar keeps the
  parts inside it, the header and each task, patchable on their own."
  [src list-id {:keys [section task-ids]} v]
  (let [id (:list.section/id section)]
    [:section.list-section (web/collapsible (str "list-" list-id) (str "list-section-" id))
     [section-header-view src list-id section (count task-ids)]
     (task-list src list-id id task-ids v)
     [new-task-form list-id id]]))


(defn- add-plans-signal
  "Local signal name showing the add-plans panel of list list-id."
  [list-id]
  (str "_addPlans" list-id))


(defn- add-plans-button
  "The button toggling list list-id's add-plans panel, disabled while there are no plans."
  [list-id plans?]
  (let [signal (add-plans-signal list-id)]
    (if plans?
      [:button.toggle {:type "button"
                       :data-signals__ifmissing (str "{" signal ": false}")
                       :data-class:on (str "$" signal)
                       :data-attr:aria-expanded (str "String($" signal ")")
                       :data-on:click (str "$" signal " = !$" signal)}
       "Add plans"]
      [:button.toggle {:type "button" :disabled true :title "There are no plans to add yet"}
       "Add plans"])))


(defn- render-header
  "#list-<id>-header: the title, progress, due date, controls and the inline edit form.
  plans? says whether any plans exist to add."
  [l counts plans? v]
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
      (add-plans-button id plans?)
      (if (:mine? v)
        [:a.toggle.on {:href path} "Assigned to me"]
        [:a.toggle {:href (str path "?mine=1")} "Assigned to me"])
      (web/collapse-all-buttons (str "list-" id))
      (edit-icon-button signal)
      (history-icon-link path)
      (if archived?
        (icon-button web/unarchive-icon "Unarchive" (str "@post('" path "/unarchive')"))
        (icon-button web/archive-icon "Archive" (str "@post('" path "/archive')")))
      (delete-icon-button (web/confirmed "Delete this list?" (str "@delete('" path "')")))]
     (edit-form signal path
                (field "Title" "title" (:list/title l) {:required true})
                (icon-field (:list/icon l))
                (due-field (:list/due-at l) v)
                [:label "Description" [:textarea {:name "description" :rows 3} (or (:list/description l) "")]]
                (errors-el (str "list-" id "-errors"))
                [:button "Save"])]))


(defn- header-view
  "#list-<id>-header for the view v, re-rendered when the list's header topic is touched."
  [{:chequer/keys [db live]} list-id v]
  @(live/cell live [:list list-id :header])
  (when-let [{l :list :keys [counts]} (lists/load-list-header db list-id)]
    (render-header l counts (some? (db/q1 db ["SELECT 1 FROM plan LIMIT 1"])) v)))


;; ## Adding plans

(def ^:private plan-ids-key
  (keyword "plan_ids[]"))


(defn- pluralize
  [n noun]
  (str n " " noun (when (not= 1 n) "s")))


(defn- plan-sizes
  "How many sections and tasks plan p has, e.g. \"2 sections, 5 tasks\"."
  [p]
  (let [sections (or (:plan/section-count p) 0)]
    (str (when (pos? sections) (str (pluralize sections "section") ", "))
         (pluralize (or (:plan/task-count p) 0) "task"))))


(defn- plan-picker
  "Checkboxes choosing plans, submitted as plan_ids[] in the order shown, each with its size.
  With reorder?, up and down buttons reorder them in the page. The plans with ids in selected
  come first, checked, in that order."
  [all-plans selected reorder?]
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
           [:span.size (plan-sizes p)]
           (when reorder?
             (list [:button {:type "button" :title "Move up"
                             :data-on:click "el.closest('li').previousElementSibling?.before(el.closest('li'))"} "↑"]
                   [:button {:type "button" :title "Move down"
                             :data-on:click "el.closest('li').nextElementSibling?.after(el.closest('li'))"} "↓"]))])]])))


(defn- plan-ids
  "The ids of the body's plan_ids[], in order and without repeats."
  [body]
  (vec (distinct (keep web/parse-id (get body plan-ids-key)))))


(defn flash-message
  "A sentence describing the result of add-plan!, e.g. \"Added Packing: 12 tasks, 3 skipped\"."
  [{:keys [plan-title tasks-created tasks-skipped]}]
  (str "Added " plan-title ": " (pluralize tasks-created "task")
       (when (pos? tasks-skipped) (str ", " tasks-skipped " skipped"))))


(defn- flash-id
  [list-id]
  (str "list-" list-id "-flash"))


(defn- add-plans-panel
  "The panel adding plans to list list-id, shown by the header's add-plans button and closed
  by Escape or a successful add, and the flash element its results go to. It's ignored by
  morphs, so re-renders of #live keep its choices; patches inside it must replace elements."
  [list-id all-plans]
  (when (seq all-plans)
    (let [signal (add-plans-signal list-id)]
      [:div.add-plans {:id (str "list-" list-id "-add-plans")
                       :data-ignore-morph true
                       :data-signals__ifmissing (str "{" signal ": false}")}
       [:section {:data-show (str "$" signal) :style "display: none"
                  :data-on:keydown__window (escape-expr (str "$" signal) (str "$" signal " = false"))}
        [:form {:data-on:submit (str (action "post" (str "/lists/" list-id "/add-plans")) "; el.reset()")}
         (plan-picker all-plans nil false)
         (errors-el (str "add-plans-" list-id "-errors"))
         [:button "Add"]]]
       [:div.flash {:id (flash-id list-id)}]])))


(defn- list-body
  "The contents of #list-<id> for the view v, re-rendered when the list's structure topic is
  touched. The header and each task are components reading topics of their own, so a change
  to one task renders and sends only that task and the header. A deleted list sends the
  viewer to the index."
  [{:chequer/keys [db live] :as src} list-id v]
  @(live/cell live [:list list-id :structure])
  (if-let [{:keys [unsectioned sections]} (lists/load-list-structure db list-id)]
    (list [header-view src list-id v]
          (add-plans-panel list-id (plans/list-plans db))
          (task-list src list-id nil unsectioned v)
          [new-task-form list-id nil]
          (map #(vector render-section src list-id % v) sections)
          [:form.new-section {:data-on:submit (str (action "post" (str "/lists/" list-id "/sections")) "; el.reset()")}
           (field [:span.visually-hidden "New section"] "title" nil {:required true :placeholder "Add a section"})
           (errors-el (str "new-list-section-" list-id "-errors"))
           [:button "Add section"]])
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
     [:a {:href (str "/lists/" id)}
      [:span.title [:span.icon (:list/icon l)] " " (:list/title l)]
      " "
      [:span.summary (str (closed-count counts) " of " total " done")]
      (when done? [:span.badge.done "done"])
      (due-el due (some-> due (< (:now v))) v)
      [:progress {:max (str (max total 1)) :value (str (closed-count counts))}]]]))


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
                                  [:div#live web/live-attrs fragment]
                                  web/prune-collapsed)))))


(defn new-page
  "GET /lists/new. The form works as a plain POST and, with Datastar, shows errors inline.
  ?plan=<id> pre-selects that plan."
  [{:chequer/keys [db] :as req}]
  (html-response 200
                 (layout/page req "New list"
                              [:h1 "New list"]
                              [:form {:method "post" :action "/lists" :data-on:submit (action "post" "/lists")}
                               (field "Title" "title" nil {:required true :autofocus true})
                               (icon-field nil)
                               (field "Due" "due" nil {:type "date"})
                               [:label "Description" [:textarea {:name "description" :rows 4}]]
                               (plan-picker (plans/list-plans db)
                                            (some-> (web/query-param req "plan") parse-long vector)
                                            true)
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
                                    [:div#live web/live-attrs fragment]))
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
  (merge (select-keys body [:title :description :icon])
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


(defn create-section
  "POST /lists/:id/sections."
  [req]
  (let [id (by-id req)]
    (mutate req (str "new-list-section-" id "-errors")
            (fn [tx user body]
              (lists/create-section! tx user (assoc (merge {:title nil} (select-keys body [:title :icon])) :list-id id))))))


(defn update-section
  "PUT /list-sections/:id."
  [req]
  (let [id (by-id req)]
    (mutate req (str "list-section-" id "-errors")
            (fn [tx user body]
              (lists/update-section! tx user (assoc (select-keys body [:title :icon]) :id id))))))


(defn delete-section
  "DELETE /list-sections/:id."
  [req]
  (mutate req nil (fn [tx user _] (lists/delete-section! tx user {:id (by-id req)}))))


(defn move-section
  "POST /list-sections/:id/move?dir=up|down."
  [req]
  (web/move req lists/move-section!))


(defn create-task
  "POST /lists/:id/tasks; the optional section field puts the task in that section."
  [req]
  (let [id (by-id req)]
    (mutate req nil
            (fn [tx user body]
              (let [section (web/parse-id (:section body))]
                (web/with-errors-id (str "new-task-" id (when section (str "-" section)) "-errors")
                  #(lists/create-task! tx user (assoc (merge {:title nil} (task-attrs tx body req))
                                                      :list-id id :section-id section)))))
            (web/create-task-response req))))


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
  "POST /tasks/:id/move?section=<id>&before=<id>."
  [req]
  (web/place req lists/place-task!))


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
                                          nil "replace")
                 (ds/patch-elements-event (html (errors-el (str "add-plans-" id "-errors"))) nil "replace")
                 (str "event: datastar-patch-signals\n"
                      "data: signals " (ds/signals-json {(add-plans-signal id) false}) "\n\n")])))))


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
   [:post ["lists" :id "sections"] create-section]
   [:post ["lists" :id "tasks"] create-task]
   [:put ["list-sections" :id] update-section]
   [:delete ["list-sections" :id] delete-section]
   [:post ["list-sections" :id "move"] move-section]
   [:put ["tasks" :id] update-task]
   [:delete ["tasks" :id] delete-task]
   [:post ["tasks" :id "move"] move-task]
   [:post ["tasks" :id "state"] set-task-state]])
