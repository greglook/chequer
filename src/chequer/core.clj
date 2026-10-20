(ns chequer.core
  (:require
    [clojure.core.async :as async]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [hiccup2.core :as h]
    [ring-chez.adapter :as adapter]
    [ring-chez.sse :as sse]))


(defn- page
  []
  (str "<!DOCTYPE html>"
       (h/html
         [:html {:lang "en"}
          [:head
           [:meta {:charset "utf-8"}]
           [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
           [:title "Chequer"]
           [:script {:type "module" :src "/js/datastar.js"}]]
          [:body
           [:h1 "Chequer"]
           [:button {:data-on:click "@get('/hello')"} "Say hello"]
           [:p#greeting]]])))


(defn- hello
  []
  (let [ch (async/chan 1)]
    (async/>!! ch (sse/format-event
                    {:event "datastar-patch-elements"
                     :data (str "elements " (h/html [:p#greeting "Hello from the server!"]))}))
    (async/close! ch)
    (sse/event-response ch)))


(defn handler
  [{:keys [request-method uri]}]
  (case [request-method uri]
    [:get "/"]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (page)}

    [:get "/hello"]
    (hello)

    [:get "/js/datastar.js"]
    {:status 200
     :headers {"Content-Type" "text/javascript; charset=utf-8"}
     :body (slurp (io/resource "public/js/datastar.js"))}

    ;; else
    {:status 404
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body "Not found"}))


(defn wrap-request-log
  [handler]
  (fn [{:keys [request-method uri query-string] :as req}]
    (let [start (System/nanoTime)
          log (fn [outcome]
                (binding [*out* *err*]
                  (println (str/upper-case (name request-method))
                           (cond-> uri query-string (str "?" query-string))
                           outcome)))
          elapsed #(str (quot (- (System/nanoTime) start) 1000000) "ms")]
      (try
        (let [resp (handler req)]
          (log (str (:status resp) " " (elapsed)))
          resp)
        (catch Exception e
          (log (str "ERROR " (elapsed) " " (ex-message e)))
          (throw e))))))


(defn -main
  [& _]
  (let [port (or (some-> (System/getenv "PORT") parse-long) 3000)
        server (adapter/run-server (wrap-request-log handler) {:port port})]
    (println (str "Chequer listening on http://127.0.0.1:" port))
    (jolt.host/block-sigint)
    (jolt.host/park-until-interrupt)
    (adapter/stop-server server)))
