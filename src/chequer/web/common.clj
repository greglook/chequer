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
                                          nil "outer")]))


(defn field
  ([label input-name value] (field label input-name value {}))
  ([label input-name value attrs]
   [:label label [:input (merge {:name input-name :value (or value "")} attrs)]]))


(defn icon-field
  "An icon input that opens an emoji picker when clicked. The picker's search box fetches
  up to 20 matches from /emoji as you type; picking one fills the input and closes it.
  kind and id (nil in a form for a new entity) make the picker's signal and ids unique on
  the page."
  [kind id value]
  (let [k (str kind (or id "New"))
        signal (str "$_icon" k)
        results (str "icon-results-" k)
        picker "el.closest('.icon-field')"
        search (str picker ".querySelector('input[type=search]')")
        icon (str picker ".querySelector('input[name=icon]')")
        fetch (str "@get('/emoji?target=" results "&q=' + encodeURIComponent(" search ".value))")]
    [:div.icon-field {:data-signals__ifmissing (str "{_icon" k ": false}")
                      :data-on:click__outside (str signal " = false")
                      :data-on:keydown (str "evt.key === 'Escape' && (" signal " = false)")}
     (field "Icon" "icon" value {:size 4 :autocomplete "off"
                                 :data-on:click (str signal " = true; " fetch "; "
                                                     "setTimeout(() => " search ".focus())")})
     [:div.icon-picker {:data-show signal :style "display: none"
                        :data-on:click (str "evt.target.dataset.emoji && "
                                            "(" icon ".value = evt.target.dataset.emoji, " signal " = false)")}
      [:input {:type "search" :placeholder "Search emoji" :aria-label "Search emoji" :autocomplete "off"
               :data-on:input__debounce.150ms fetch
               :data-on:keydown (str "evt.key === 'Enter' && (evt.preventDefault(), "
                                     picker ".querySelector('[data-emoji]')?.click())")}]
      [:div.emoji-results {:id results}]
      [:button {:type "button" :data-on:click (str icon ".value = ''; " signal " = false")} "Clear icon"]]]))


(defn toggle-signal
  "Local (unsent) signal name toggling the edit form of an element."
  [kind id]
  (str "_edit" kind id))


(defn edit-toggle
  "The data-signals attribute declaring signal false unless an earlier render already did."
  [signal]
  {:data-signals__ifmissing (str "{" signal ": false}")})


(defn edit-button
  [signal]
  [:button {:type "button" :data-on:click (str "$" signal " = !$" signal)} "Edit"])


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


(defn edit-icon-button
  "An edit-button showing a pencil icon."
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
  "A dropdown menu of items behind a vertical triple-dot button."
  [& items]
  [:details.menu.more
   [:summary {:title "More actions" :aria-label "More actions"} dots-icon]
   (into [:div.menu-items] items)])


(defn delete-menu-item
  "A red menu item running a DELETE of path once question is confirmed."
  [question path]
  [:button.danger {:type "button" :data-on:click (confirmed question (str "@delete('" path "')"))} "Delete"])


(defn edit-form
  "An inline edit form, shown while signal is true, that PUTs its fields to path."
  [signal path & body]
  (into [:form.edit {:data-show (str "$" signal) :style "display: none"
                     :data-on:submit (str (action "put" path) "; $" signal " = false")}]
        body))


(defn move-buttons
  [path]
  (list [:button {:type "button" :title "Move up" :data-on:click (str "@post('" path "/move?dir=up')")} "↑"]
        [:button {:type "button" :title "Move down" :data-on:click (str "@post('" path "/move?dir=down')")} "↓"]))


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
             (re-find #"group belongs to another (plan|list)" (str (ex-message e))) (text-response 400 "Bad request")
             :else (throw e)))))
     (text-response 400 "Bad request"))))


(defn move
  "POST .../move?dir=up|down, moving with (move-fn tx user {:id :direction})."
  [req move-fn]
  (if-let [direction ({"up" :up "down" :down} (query-param req "dir"))]
    (mutate req nil (fn [tx user _] (move-fn tx user {:id (get-in req [:params :id]) :direction direction})))
    (text-response 400 "Bad request")))
