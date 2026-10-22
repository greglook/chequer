(ns chequer.live
  "Live pages via glimmer-datastar. Each topic, such as [:plan 7], has a revision ratom.
  A page's SSE render derefs the revisions of the topics it shows, and a mutation bumps
  the revisions of the topics it touched, so every open page showing them re-renders."
  (:require
    [glimmer.ratom :as ratom]
    [jolt.datastar.core :as ds])
  (:import
    (java.io
      StringReader)))


(defn live
  "A new live state: the topic revisions and the render scheduler."
  []
  {:cells (atom {})
   :scheduler (ds/scheduler)})


(defn stop!
  "Stop the render loop of live state l."
  [l]
  (ds/stop! (:scheduler l)))


(defn cell
  "The revision ratom of topic, created on first use."
  [l topic]
  (or (get @(:cells l) topic)
      (get (swap! (:cells l) update topic #(or % (ratom/atom 0))) topic)))


(defn touch!
  "Bump the revisions of topics. A topic no page has read has no cell and needs no bump."
  [l topics]
  (doseq [topic topics
          :let [c (get @(:cells l) topic)]
          :when c]
    (swap! c inc)))


(defn wrap-touch
  "Middleware touching the :topics in the response's metadata."
  [handler l]
  (fn [req]
    (let [resp (handler req)]
      (when-let [topics (seq (:topics (meta resp)))]
        (touch! l topics))
      resp)))


(defn wrap-live
  "The glimmer-datastar middleware. It reads the body of Datastar requests for signals,
  so the body is buffered and handed to handler unread. A body it can't parse is a 400."
  [handler l]
  (let [inner (ds/wrap-datastar (fn [req]
                                  (vreset! (::reached req) true)
                                  (handler (cond-> req
                                             (::body req) (assoc :body (StringReader. (::body req))))))
                                {:scheduler (:scheduler l)})]
    (fn [{:keys [body] :as req}]
      (let [reached (volatile! false)
            req (assoc req ::reached reached)]
        (try
          (if body
            (let [s (slurp body)]
              (inner (assoc req :body (StringReader. s) ::body s)))
            (inner req))
          (catch Exception e
            (if @reached
              (throw e)
              {:status 400
               :headers {"Content-Type" "text/plain; charset=utf-8"}
               :body "Bad request"})))))))
