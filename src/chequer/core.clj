(ns chequer.core
  "System wiring: the ring handler, middleware, and server start and stop."
  (:require
    [chequer.auth :as auth]
    [chequer.db :as db]
    [chequer.router :as router]
    [chequer.web.layout :as layout]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [ring-chez.adapter :as adapter]))


(defn- index
  "GET / handler: the lists page."
  [req]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (layout/page req "Lists"
                      [:h1 "Lists"]
                      [:p.empty "No lists yet."])})


(defn- healthz
  "GET /healthz handler: 200 ok once the database answers SELECT 1."
  [{:chequer/keys [db]}]
  (db/q1 db ["SELECT 1"])
  {:status 200
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body "ok"})


(def ^:private routes
  "Application routes as [method path-pattern handler]."
  [[:get [] index]
   [:get ["healthz"] healthz]])


(def ^:private static-content-types
  "Allow-list of static file extensions and their Content-Type."
  {"js" "text/javascript; charset=utf-8"
   "css" "text/css; charset=utf-8"})


(defn wrap-static
  "Serve files under /js/ and /css/ from resources/public. Only a flat file name with
  an allow-listed extension is served; anything else falls through to handler."
  [handler]
  (fn [{:keys [request-method uri] :as req}]
    (let [[_ dir file ext] (re-matches #"/(js|css)/([A-Za-z0-9_-][A-Za-z0-9._-]*\.(js|css))" uri)
          resource (when (and (= :get request-method) file)
                     (io/resource (str "public/" dir "/" file)))]
      (if (and resource (= dir ext))
        {:status 200
         :headers {"Content-Type" (static-content-types ext)}
         :body (slurp resource)}
        (handler req)))))


(defn wrap-system
  "Put the database and config on each request as :chequer/db and :chequer/cfg."
  [handler {:keys [db cfg]}]
  (fn [req]
    (handler (assoc req :chequer/db db :chequer/cfg cfg))))


(defn handler
  "The application's ring handler for the running system sys ({:db .. :cfg ..})."
  [sys]
  (let [cfg (:cfg sys)]
    (-> (router/handler routes)
        (auth/wrap-auth (:auth cfg))
        (auth/wrap-origin-check)
        (wrap-static)
        (wrap-system sys))))


(defn wrap-request-log
  "Middleware logging method, uri, status and elapsed time of each request to stderr."
  [next-handler]
  (fn [{:keys [request-method uri query-string] :as req}]
    (let [start (System/nanoTime)
          log (fn [outcome]
                (binding [*out* *err*]
                  (println (str/upper-case (name request-method))
                           (cond-> uri query-string (str "?" query-string))
                           outcome)))
          elapsed #(str (quot (- (System/nanoTime) start) 1000000) "ms")]
      (try
        (let [resp (next-handler req)]
          (log (str (:status resp) " " (elapsed)))
          resp)
        (catch Exception e
          (log (str "ERROR " (elapsed) " " (ex-message e)))
          (throw e))))))


(defn start!
  "Open and migrate the database, then start the HTTP server. Returns {:server .. :db .. :cfg ..}."
  [cfg]
  (let [d (db/open (get-in cfg [:database :path]))]
    (try
      (db/migrate! d)
      (let [{:keys [host port]} (get-in cfg [:server :listen])
            sys {:db d :cfg cfg}
            server (adapter/run-server (wrap-request-log (handler sys))
                                       {:host host :port port})]
        (assoc sys :server server))
      (catch Exception e
        (db/close d)
        (throw e)))))


(defn stop!
  "Stop the HTTP server and close the database of a system returned by start!."
  [{:keys [server db]}]
  (adapter/stop-server server)
  (db/close db))
