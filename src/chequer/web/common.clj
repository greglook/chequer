(ns chequer.web.common
  "Helpers shared by the page namespaces: responses, request bodies, form rendering and the
  mutation wrapper."
  (:require
    [chequer.db :as db]
    [clojure.data.json :as json]
    [clojure.string :as str]
    [jolt.datastar.core :as ds]
    [jolt.datastar.html :as jh]))


;; ## Rendering

(defn html
  "Render hiccup nodes to an HTML string. Return it unchanged as an SSE render's body, so
  glimmer-datastar can send only the components that changed."
  [& nodes]
  (apply jh/html nodes))


(def raw
  "Mark a string as HTML to be written without escaping."
  jh/raw)


(defn sources
  "What live components read from the request: the database and the live state. They take
  it as an argument, which is the same for every request, so their cache entries are shared
  by every stream."
  [req]
  (select-keys req [:chequer/db :chequer/live]))


(def live-attrs
  "Attributes for a page's #live root, which opens its SSE stream. glimmer-datastar's
  init-opts leaves Datastar's retry at auto, which gives up for good when the stream ends
  cleanly or a reconnect gets an error status, as when the server restarts; always retries
  those too, with the same backoff."
  (let [opts "{openWhenHidden: false, retryMaxCount: Infinity}"
        retry (fn [expr]
                (when-not (str/includes? expr opts)
                  (throw (ex-info "init-opts no longer has the expected @get options" {:expr expr})))
                (str/replace expr opts "{openWhenHidden: false, retry: 'always', retryMaxCount: Infinity}"))]
    (-> (ds/init-opts {:selector "#live"})
        (update :data-init retry)
        (update "data-on:online__window" retry))))


;; ## User badges

(defn- name-hue
  "A hue in [0, 360) from a 32-bit FNV-1a hash of s, so a name always gets the same color."
  [s]
  (mod (reduce (fn [h c] (mod (* (bit-xor h (int c)) 16777619) 4294967296))
               2166136261
               s)
       360))


(defn user-badge
  "A solid circle, colored by the user's name, holding the first letter of their title, with
  the title as its hover text."
  [{:keys [title], user-name :name}]
  (let [label (if (str/blank? title) user-name title)]
    [:span.user-badge {:title label
                       :aria-label label
                       :style (str "background-color: hsl(" (name-hue (or user-name label "")) " 55% 42%)")}
     (if (str/blank? label) "?" (str/upper-case (subs (str/trim label) 0 1)))]))


;; ## Responses

(defn redirect-el
  "A script element that sends the browser to path (a server-built path, not user input)."
  [path]
  [:script {:data-effect "el.remove()"} (raw (str "window.location.href = \"" path "\""))])


(defn redirect-script
  "redirect-el as a string."
  [path]
  (html (redirect-el path)))


(defn redirect-event
  [path]
  (ds/patch-elements-event (redirect-script path) "body" "append"))


(defn sse-response
  [events]
  {:status 200
   :headers {"Content-Type" "text/event-stream" "Cache-Control" "no-cache"}
   :body (apply str events)})


(defn html-response
  [status body]
  {:status status
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body body})


(defn text-response
  [status body]
  {:status status
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body body})


(defn created-response
  "The response to creating an entity at path: from Datastar, an SSE redirect; otherwise a 303."
  [req path]
  (if (get-in req [:headers "datastar-request"])
    (sse-response [(redirect-event path)])
    {:status 303 :headers {"Location" path}}))


;; ## Request bodies

(defn- list-key?
  "True for a field named like plan_ids[], which carries a list of values."
  [k]
  (str/ends-with? (name k) "[]"))


(defn- normalize-param
  "[k v] with a list field's value as a vector of strings (one value arrives as a plain
  string), or nil when v isn't a string, or for a list field, strings."
  [[k v]]
  (if (list-key? k)
    (let [vs (if (string? v) [v] v)]
      (when (and (sequential? vs) (every? string? vs))
        [k (vec vs)]))
    (when (or (nil? v) (string? v))
      [k v])))


(defn read-params
  "The request's Datastar signals (JSON) or form fields (parsed by ring's wrap-params) as a
  map of keyword to string, or nil when the body is malformed or holds non-string values.
  A field whose name ends in [] is a vector of strings instead. The tab id
  glimmer-datastar's page signals carry under :jolt is dropped."
  [{:keys [body headers form-params]}]
  (try
    (let [content-type (get headers "content-type" "")
          ;; wrap-params has already consumed a urlencoded form body
          form? (str/includes? content-type "application/x-www-form-urlencoded")
          s (if (and body (not form?)) (slurp body) "")
          params (cond
                   form? (update-keys form-params keyword)
                   (str/blank? s) {}
                   (str/includes? content-type "application/json") (dissoc (json/read-str s :key-fn keyword) :jolt))]
      (when (map? params)
        (let [normalized (keep normalize-param params)]
          (when (= (count normalized) (count params))
            (into {} normalized)))))
    (catch Exception _ nil)))


(defn query-param
  [req k]
  (get-in req [:query-params k]))


(defn parse-id
  "s as an id, nil when blank. Anything else is a bad request."
  [s]
  (when-not (str/blank? s)
    (or (when (re-matches #"\d{1,18}" s) (parse-long s))
        (throw (ex-info "Bad id" {:type :chequer/bad-request})))))


;; ## Forms

(defn action
  "A Datastar expression sending the enclosing form's fields with method to url."
  [method url]
  (str "@" method "('" url "', {contentType: 'form'})"))


(defn confirmed
  [question expr]
  (str "confirm('" question "') && " expr))


(defn errors-el
  [id]
  [:ul.errors {:id id}])


(defn errors-fragment
  "SSE response replacing the error list id with messages from {field message}."
  [id errors]
  (sse-response [(ds/patch-elements-event (html [:ul.errors {:id id}
                                                 (for [[k msg] (sort-by key errors)]
                                                   [:li (str (str/capitalize (name k)) " " msg)])])
                                          nil "replace")]))


(defn field
  ([label input-name value] (field label input-name value {}))
  ([label input-name value attrs]
   [:label label [:input (merge {:name input-name :value (or value "")} attrs)]]))


(defn icon-field
  "An icon input, which opens the page's emoji picker (see icon-picker) when clicked."
  [value]
  [:div.icon-field (field "Icon" "icon" value {:size 4 :autocomplete "off"})])


(def icon-picker
  "The emoji picker every icon field on the page shares, which the layout renders once,
  outside the live content, so that live renders don't send it. /js/icon-picker.js opens it
  under the icon input clicked and fills that input with the emoji picked. Its search box
  fetches up to 20 matches from /emoji into #icon-results as you type, and when the picker
  opens."
  (let [fetch "@get('/emoji?q=' + encodeURIComponent(el.value))"]
    [:div#icon-picker.icon-picker {:hidden true}
     [:input {:type "search" :placeholder "Search emoji" :aria-label "Search emoji" :autocomplete "off"
              :data-on:input__debounce.150ms fetch
              :data-on:icon-picker-open fetch}]
     [:div#icon-results.emoji-results]
     [:button.clear {:type "button"} "Clear icon"]]))


(defn toggle-signal
  "Local (unsent) signal name toggling the edit form of an element."
  [kind id]
  (str "_edit" kind id))


(defn edit-toggle
  "The data-signals attribute declaring signal false unless an earlier render already did."
  [signal]
  {:data-signals__ifmissing (str "{" signal ": false}")})


(defn new-task-form
  "A form adding a task, with its title, to url. Enter adds the task collapsed; the Add
  button adds it with its edit form open, through create-task-response."
  [url & body]
  [:form.new-task {:data-on:submit (str (action "post" url) "; el.reset()")}
   (field [:span.visually-hidden "New task"] "title" nil {:required true :placeholder "Add a task"})
   body
   ;; Not a submit button, so that Enter's implicit submission doesn't report it as the submitter.
   [:button.primary {:type "button" :data-on:click (str (action "post" (str url "?edit=1")) "; el.form.reset()")} "Add"]])


(def ^:private pencil-icon
  (raw (str "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
            "<path d=\"M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z\"/></svg>")))


(def ^:private trash-icon
  (raw (str "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
            "<path d=\"M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6M10 11v6M14 11v6\"/></svg>")))


(def ^:private dots-icon
  (raw (str "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
            "<circle cx=\"12\" cy=\"5\" r=\"1.75\"/><circle cx=\"12\" cy=\"12\" r=\"1.75\"/>"
            "<circle cx=\"12\" cy=\"19\" r=\"1.75\"/></svg>")))


(def ^:private history-icon
  (raw (str "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
            "<path d=\"M3 12a9 9 0 1 0 2.6-6.4L3 8M3 3v5h5M12 7v5l3 2\"/></svg>")))


(def archive-icon
  (raw (str "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
            "<path d=\"M3 4h18v4H3zM5 8v12h14V8M10 12h4\"/></svg>")))


(def unarchive-icon
  (raw (str "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
            "<path d=\"M3 4h18v4H3zM5 8v12h14V8M12 18v-6M9 15l3-3 3 3\"/></svg>")))


(defn icon-button
  "A button showing icon, with label as its hover text, running the Datastar expression click."
  [icon label click]
  [:button.icon-button {:type "button" :title label :aria-label label :data-on:click click}
   icon])


(defn history-icon-link
  "A link to path's history page, showing a clock icon."
  [path]
  [:a.icon-button {:href (str path "/history") :title "History" :aria-label "History"}
   history-icon])


(defn edit-icon-button
  "A button toggling signal, showing a pencil icon."
  [signal]
  [:button.icon-button {:type "button" :title "Edit" :aria-label "Edit"
                        :data-on:click (str "$" signal " = !$" signal)}
   pencil-icon])


(defn delete-icon-button
  "A red trash can button running the Datastar expression click, with extra attrs."
  ([click] (delete-icon-button click {}))
  ([click attrs]
   [:button.icon-button.danger (merge {:type "button" :title "Delete" :aria-label "Delete"
                                       :data-on:click click}
                                      attrs)
    trash-icon]))


(defn more-menu
  "A dropdown menu of items behind a vertical triple-dot button. Menus share a name so
  that the browser keeps only one open at a time."
  [& items]
  [:details.menu.more {:name "more-menu"}
   [:summary {:title "More actions" :aria-label "More actions"} dots-icon]
   (into [:div.menu-items] items)])


(defn delete-menu-item
  "A red menu item running a DELETE of path once question is confirmed."
  [question path]
  [:button.danger {:type "button" :data-on:click (confirmed question (str "@delete('" path "')"))} "Delete"])


(defn escape-expr
  "A keydown expression that runs handler on an Escape that nothing else, like the icon
  picker or a drag, has handled (by preventing its default), while condition holds."
  [condition handler]
  (str "evt.key === 'Escape' && !evt.defaultPrevented && " condition " && (" handler ")"))


(defn edit-form
  "An inline edit form, shown while signal is true, that PUTs its fields to path, or closes
  unsaved on Escape. Setting _focusEdit to signal, as create-task-response does, focuses its
  title field once."
  [signal path & body]
  (into [:form.edit {:data-signals__ifmissing "{_focusEdit: ''}"
                     :data-show (str "$" signal) :style "display: none"
                     :data-on:submit (str (action "put" path) "; $" signal " = false")
                     ;; Prevented, so the expanded task beneath it stays open (see expandable).
                     :data-on:keydown__window (escape-expr (str "$" signal)
                                                           (str "$" signal " = false; evt.preventDefault()"))
                     ;; After a frame, so that data-show has shown the form.
                     :data-effect (str "$_focusEdit === '" signal "' && ($_focusEdit = '', "
                                       "requestAnimationFrame(() => el.elements.title?.focus()))")}]
        body))


;; ## Collapsible sections

(def ^:private chevron-icon
  (raw (str "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
            "<path d=\"M6 9l6 6 6-6\"/></svg>")))


(defn- collapse-signal
  [element-id]
  (str "_collapsed_" (str/replace element-id #"-" "_")))


(defn collapsible
  "The attributes of the root element of a section, element-id, of owner, the list or plan
  like \"list-5\": declares its collapse signal, initially what this browser stored for it
  (see collapse.js), and adds the collapsed class while it's set. Collapsing is per-browser
  state that the server never sees."
  [owner element-id]
  (let [signal (collapse-signal element-id)]
    {:data-signals__ifmissing (str "{" signal ": chequerCollapse.isCollapsed('" owner "', '" element-id "')}")
     :data-class:collapsed (str "$" signal)}))


(defn collapse-toggle
  "A button in the header of the section element-id of owner, holding the section's icon and
  title and, while it's collapsed, its task count, which may be a string like \"3/8\", that
  collapses or expands it and remembers that in this browser."
  [owner element-id icon title task-count]
  (let [signal (str "$" (collapse-signal element-id))]
    [:button.section-toggle {:type "button"
                             :data-attr:aria-expanded (str "String(!" signal ")")
                             :data-attr:title (str signal " ? 'Expand section' : 'Collapse section'")
                             :data-on:click (str signal " = !" signal "; "
                                                 "chequerCollapse.set('" owner "', '" element-id "', " signal ")")}
     [:span.chevron chevron-icon]
     [:span.icon icon]
     [:span.title title]
     [:span.count {:data-show signal :style "display: none"} (str "(" task-count ")")]]))


(def prune-collapsed
  "A script, for the index pages, forgetting the collapsed sections of lists and plans this
  browser hasn't opened in 30 days."
  [:script "chequerCollapse.prune()"])


;; ## Expandable tasks

(def ^:private notes-icon
  (raw (str "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
            "<path d=\"M6 3h9l4 4v14H6zM14 3v5h5M9 12h7M9 16h7\"/></svg>")))


(defn- expanded-expr
  "The expression that's true while the task element-id is the expanded one and isn't being
  edited with the edit form shown by edit-signal."
  [element-id edit-signal]
  (str "$_expandedTask === '" element-id "' && !$" edit-signal))


(defn expandable
  "The attributes of a task's root element, element-id: declares its edit-signal and the
  shared _expandedTask signal, holding the id of the one expanded task, adds the expanded
  class while it's that task, and collapses it on Escape."
  [element-id edit-signal]
  {:data-signals__ifmissing (str "{_expandedTask: '', " edit-signal ": false}")
   :data-class:expanded (expanded-expr element-id edit-signal)
   :data-on:keydown__window (escape-expr (expanded-expr element-id edit-signal)
                                         "$_expandedTask = ''")})


(defn expand-row-attrs
  "Attributes of a task's row that expand the task element-id on a click outside its
  controls and checkbox, or collapse it if it's already expanded."
  [element-id]
  {:data-on:click (str "evt.target.closest('.controls, .grip, input, a') || "
                       "($_expandedTask = $_expandedTask === '" element-id "' ? '' : '" element-id "')")})


(defn expand-title
  "A task's title as a button that toggles the expanded card (the click reaches the row),
  followed by a notes icon when the task has a description."
  [element-id title description?]
  (list [:button.title {:type "button"
                        :data-attr:aria-expanded (str "String($_expandedTask === '" element-id "')")}
         title]
        (when description?
          [:span.has-description {:title "Has a description"} notes-icon])))


(defn expanded-card
  "The full task info shown under the row while element-id is expanded: a grid of the
  labeled facts (pairs of label and content, nil content skipped) and the rendered
  description, if any."
  [element-id edit-signal facts description-html]
  [:div.task-card {:data-show (expanded-expr element-id edit-signal) :style "display: none"}
   (for [[label content] facts
         :when content]
     [:div.fact [:span.label label] [:span.value content]])
   (when description-html
     [:div.fact.description [:span.label "Description"] [:div.markdown (raw description-html)]])])


(defn move-buttons
  [path]
  (list [:button {:type "button" :title "Move up" :data-on:click (str "@post('" path "/move?dir=up')")} "↑"]
        [:button {:type "button" :title "Move down" :data-on:click (str "@post('" path "/move?dir=down')")} "↓"]))


;; ## Dragging tasks

(def ^:private grip-icon
  (raw (str "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
            "<circle cx=\"9\" cy=\"6\" r=\"1.5\"/><circle cx=\"15\" cy=\"6\" r=\"1.5\"/>"
            "<circle cx=\"9\" cy=\"12\" r=\"1.5\"/><circle cx=\"15\" cy=\"12\" r=\"1.5\"/>"
            "<circle cx=\"9\" cy=\"18\" r=\"1.5\"/><circle cx=\"15\" cy=\"18\" r=\"1.5\"/></svg>")))


(defn task-list-attrs
  "The attributes of a list of tasks that public/js/drag.js lets tasks be dragged within and
  between: section-id is the section the list holds, nil for the unsectioned tasks."
  [section-id]
  {:data-task-list "" :data-section (str section-id)})


(defn draggable
  "The attributes of the root element of task id, at path, that send where drag.js dropped it
  to path's move route."
  [id path]
  {:data-task-id (str id)
   :data-on:task-drop (str "@post('" path "/move?section=' + evt.detail.section + '&before=' + evt.detail.before)")})


(def drag-grip
  "The handle a task is dragged by, or moved with the arrow keys once focused."
  [:button.grip {:type "button" :title "Drag to move" :aria-label "Move task (drag, or press the arrow keys)"}
   grip-icon])


;; ## Mutations

(def no-content
  {:status 204})


(defn invalid!
  "Throw the error for invalid attributes, {field message}."
  [errors]
  (throw (ex-info "Invalid attributes" {:type :chequer/invalid :errors errors})))


(defn with-errors-id
  "Call (f), sending its attribute errors to the error element errors-id."
  [errors-id f]
  (try
    (f)
    (catch Exception e
      (if (= :chequer/invalid (:type (ex-data e)))
        (throw (ex-info (ex-message e) (assoc (ex-data e) :errors-id errors-id)))
        (throw e)))))


(defn mutate
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
             (re-find #"section belongs to another (plan|list)" (str (ex-message e))) (text-response 400 "Bad request")
             :else (throw e)))))
     (text-response 400 "Bad request"))))


(defn create-task-response
  "The ok-response of a task creation, given the new task's id: opens its edit form, with its
  title focused, when the request asked with ?edit=1."
  [req]
  (fn [id]
    (if (= "1" (query-param req "edit"))
      (let [signal (toggle-signal "Task" id)]
        (ds/patch-signals {signal true "_focusEdit" signal}))
      no-content)))


(defn move
  "POST .../move?dir=up|down, moving with (move-fn tx user {:id :direction})."
  [req move-fn]
  (if-let [direction ({"up" :up "down" :down} (query-param req "dir"))]
    (mutate req nil (fn [tx user _] (move-fn tx user {:id (get-in req [:params :id]) :direction direction})))
    (text-response 400 "Bad request")))


(defn place
  "POST .../move?section=<id>&before=<id>, placing the task with (place-fn tx user {:id
  :section-id :before}); a blank section means unsectioned and a blank before means last."
  [req place-fn]
  (mutate req nil (fn [tx user _]
                    (place-fn tx user {:id (get-in req [:params :id])
                                       :section-id (parse-id (query-param req "section"))
                                       :before (parse-id (query-param req "before"))}))))
