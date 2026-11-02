(ns chequer.core
  "System wiring: the ring handler, middleware, and the clip system of components which\n  hold the application's state, from the database to the HTTP server."
  (:require
    [chequer.auth :as auth]
    [chequer.db :as db]
    [chequer.live :as live]
    [chequer.oidc :as oidc]
    [chequer.router :as router]
    [chequer.sessions :as sessions]
    [chequer.web.emoji :as emoji]
    [chequer.web.history :as history]
    [chequer.web.lists :as lists]
    [chequer.web.plans :as plans]
    [chequer.web.search :as search]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [juxt.clip.core :as clip]
    [ring-chez.adapter :as adapter]
    [ring.middleware.cookies :refer [wrap-cookies]]
    [ring.middleware.not-modified :refer [wrap-not-modified]]
    [ring.middleware.params :refer [wrap-params]]))


(defn- healthz
  "GET /healthz handler: 200 ok once the database answers SELECT 1, followed by the open
  stream count and the component cache's counters."
  [{:chequer/keys [db live]}]
  (db/q1 db ["SELECT 1"])
  (let [{:keys [streams cache]} (live/stats live)]
    {:status 200
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body (str "ok\n"
                "streams=" streams "\n"
                (str/join " " (cons "cache" (map (fn [[k v]] (str (name k) "=" v)) (sort cache)))) "\n")}))


(def ^:private routes
  "Application routes as [method path-pattern handler]."
  (vec (concat [[:get ["healthz"] healthz]]
               emoji/routes
               history/routes
               lists/routes
               oidc/routes
               plans/routes
               search/routes)))


(def ^:private static-dirs
  "Allow-list of static directories, each with the one file extension it serves and that
  extension's Content-Type."
  {"js" ["js" "text/javascript; charset=utf-8"]
   "css" ["css" "text/css; charset=utf-8"]
   "img" ["svg" "image/svg+xml; charset=utf-8"]})


(defn wrap-static
  "Serve files under /js/, /css/ and /img/ from resources/public with a content-hash ETag,
  for wrap-not-modified to revalidate. Only a flat file name with the directory's
  allow-listed extension is served; anything else falls through to handler. Unlike
  ring.middleware.resource, this works for resources embedded in a built binary, which
  can't be opened as a URL connection."
  [handler]
  (fn [{:keys [request-method uri] :as req}]
    (let [[_ dir file ext] (re-matches #"/(js|css|img)/([A-Za-z0-9_-][A-Za-z0-9._-]*\.(js|css|svg))" uri)
          [dir-ext content-type] (static-dirs dir)
          resource (when (and (= :get request-method) file)
                     (io/resource (str "public/" dir "/" file)))]
      (if (and resource (= dir-ext ext))
        (let [body (slurp resource)]
          {:status 200
           :headers {"Content-Type" content-type
                     "Cache-Control" "public, no-cache"
                     "ETag" (str "\"" (Integer/toHexString (hash body)) "\"")}
           :body body})
        (handler req)))))


(defn wrap-header-vectors
  "Turn sequential response header values, such as the Set-Cookie list wrap-cookies makes,
  into vectors: the adapter writes one header line per element only for a vector."
  [handler]
  (fn [req]
    (let [resp (handler req)]
      (cond-> resp
        (some (comp seq? val) (:headers resp))
        (update :headers update-vals #(if (seq? %) (vec %) %))))))


(defn wrap-system
  "Put the database, config, live state and OIDC provider (in OIDC mode) on each request as
  :chequer/db, :chequer/cfg, :chequer/live and :chequer/oidc."
  [handler {:keys [db cfg live oidc]}]
  (fn [req]
    (handler (assoc req :chequer/db db :chequer/cfg cfg :chequer/live live :chequer/oidc oidc))))


(defn handler
  "The application's ring handler for the running system sys ({:db .. :cfg .. :live ..},
  plus :oidc in OIDC mode)."
  [{:keys [cfg live] :as sys}]
  (-> (router/handler routes)
      (live/wrap-touch live)
      (auth/wrap-auth (:auth cfg))
      (auth/wrap-origin-check)
      (wrap-cookies)
      (wrap-header-vectors)
      (wrap-params)
      (wrap-static)
      (wrap-not-modified)
      (wrap-system sys)
      (live/wrap-live live)))


(defn wrap-request-log
  "Middleware logging method, uri, status and elapsed time of each request."
  [next-handler]
  (fn [{:keys [request-method uri query-string] :as req}]
    (let [start (System/nanoTime)
          describe (fn [outcome]
                     (str (str/upper-case (name request-method)) " "
                          (cond-> uri query-string (str "?" query-string)) " "
                          outcome))
          elapsed #(str (quot (- (System/nanoTime) start) 1000000) "ms")]
      (try
        (let [resp (next-handler req)]
          (log/info (describe (str (:status resp) " " (elapsed))))
          resp)
        (catch Exception e
          (log/error (describe (str "ERROR " (elapsed) " " (ex-message e))))
          (throw e))))))


(defn- start-server
  "Start the HTTP server for the components of sys on the configured listen address."
  [{:keys [cfg] :as sys}]
  (let [{:keys [host port]} (get-in cfg [:server :listen])]
    (adapter/run-server (wrap-request-log (handler sys))
                        {:host host :port port :strategy :fibers})))


(defn- stop-sweeper!
  "Stop the expired-session sweeper, if one was started."
  [sweeper]
  (when sweeper
    (sessions/stop-sweeper! sweeper)))


(defn system-config
  "Construct a clip component system from the config."
  [cfg]
  {:components
   (->
     {:cfg {:start (constantly cfg)}
      :db {:start #(db/open (get-in cfg [:database :path]))
           :post-start db/migrate!
           :stop db/close!}
      :live {:start live/live
             :stop live/stop!}
      ;; TODO: rename this to a more general :auth component?
      :oidc {:start (constantly nil)}
      :server {:start (list start-server
                            {:cfg cfg
                             :db (clip/ref :db)
                             :live (clip/ref :live)
                             :oidc (clip/ref :oidc)})
               :stop adapter/stop-server}}
     (cond->
       (= :oidc (get-in cfg [:auth :mode]))
       (assoc :oidc {:start #(oidc/provider cfg)}
              :sweeper {:start (list sessions/start-sweeper! (clip/ref :db))
                        :stop stop-sweeper!})))})


(defn start!
  "Start the system for the config cfg: open and migrate the database, then start the HTTP
  server. Returns the running system {:cfg .. :db .. :live .. :oidc .. :server .. :sweeper ..}.
  If a component fails to start, the ones already started are stopped and the failure is
  rethrown."
  [cfg]
  (let [sys-cfg (system-config cfg)]
    (try
      (vary-meta (clip/start sys-cfg) assoc ::system-config sys-cfg)
      (catch Exception e
        (if-let [partial-sys (::clip/system (ex-data e))]
          (do (clip/stop sys-cfg partial-sys)
              (throw (or (ex-cause e) e)))
          (throw e))))))


(defn stop!
  "Stop the components of a system returned by start!, in reverse dependency order."
  [sys]
  (clip/stop (::system-config (meta sys)) sys)
  nil)
