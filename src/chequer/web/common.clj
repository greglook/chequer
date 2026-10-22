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


;; ## Responses

(defn redirect-script
  "A script element that sends the browser to path (a server-built path, not user input)."
  [path]
  (html [:script {:data-effect "el.remove()"} (raw (str "window.location.href = \"" path "\""))]))


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

(defn read-params
  "The request's Datastar signals (JSON) or form fields (parsed by ring's wrap-params) as a
  map of keyword to string, or nil when the body is malformed or holds non-string values.
  The tab id glimmer-datastar's page signals carry under :jolt is dropped."
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
      (when (and (map? params) (every? #(or (nil? %) (string? %)) (vals params)))
        params))
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
