(ns chequer.core
  "System wiring: the ring handler, middleware, and server start and stop."
  (:require
    [chequer.auth :as auth]
    [chequer.db :as db]
    [chequer.live :as live]
    [chequer.router :as router]
    [chequer.web.history :as history]
    [chequer.web.lists :as lists]
    [chequer.web.plans :as plans]
    [chequer.web.search :as search]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
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
               history/routes
               lists/routes
               plans/routes
               search/routes)))


(def ^:private static-content-types
  "Allow-list of static file extensions and their Content-Type."
  {"js" "text/javascript; charset=utf-8"
   "css" "text/css; charset=utf-8"})


(defn wrap-static
  "Serve files under /js/ and /css/ from resources/public with a content-hash ETag, for
  wrap-not-modified to revalidate. Only a flat file name with
  an allow-listed extension is served; anything else falls through to handler. Unlike
  ring.middleware.resource, this works for resources embedded in a built binary, which
  can't be opened as a URL connection."
  [handler]
  (fn [{:keys [request-method uri] :as req}]
    (let [[_ dir file ext] (re-matches #"/(js|css)/([A-Za-z0-9_-][A-Za-z0-9._-]*\.(js|css))" uri)
          resource (when (and (= :get request-method) file)
                     (io/resource (str "public/" dir "/" file)))]
      (if (and resource (= dir ext))
        (let [body (slurp resource)]
          {:status 200
           :headers {"Content-Type" (static-content-types ext)
                     "Cache-Control" "public, no-cache"
                     "ETag" (str "\"" (Integer/toHexString (hash body)) "\"")}
           :body body})
        (handler req)))))


(defn wrap-system
  "Put the database, config and live state on each request as :chequer/db, :chequer/cfg
  and :chequer/live."
  [handler {:keys [db cfg live]}]
  (fn [req]
    (handler (assoc req :chequer/db db :chequer/cfg cfg :chequer/live live))))


(defn handler
  "The application's ring handler for the running system sys ({:db .. :cfg .. :live ..})."
  [{:keys [cfg live] :as sys}]
  (-> (router/handler routes)
      (live/wrap-touch live)
      (auth/wrap-auth (:auth cfg))
      (auth/wrap-origin-check)
      (wrap-cookies)
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


(defn start!
  "Open and migrate the database, then start the HTTP server. Returns
  {:server .. :db .. :cfg .. :live ..}."
  [cfg]
  (let [db (db/open (get-in cfg [:database :path]))]
    (try
      (db/migrate! db)
      (let [{:keys [host port]} (get-in cfg [:server :listen])
            sys {:db db :cfg cfg :live (live/live)}
            server (adapter/run-server (wrap-request-log (handler sys))
                                       {:host host :port port :strategy :fibers})]
        (assoc sys :server server))
      (catch Exception e
        (db/close! db)
        (throw e)))))


(defn stop!
  "Stop the HTTP server and close the database of a system returned by start!."
  [{:keys [server db live]}]
  (adapter/stop-server server)
  (live/stop! live)
  (db/close! db))
