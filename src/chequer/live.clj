(ns chequer.live
  "Live pages via glimmer-datastar. Each topic, such as [:plan 7], has a revision ratom.
  A page's SSE render derefs the revisions of the topics it shows, and a mutation bumps
  the revisions of the topics it touched, so every open page showing them re-renders.

  Every SSE stream goes out through a relay, which sends a heartbeat comment when the
  stream has been quiet for a while, so proxies keep idle streams open, and which counts
  the open streams. Streams also tell nginx not to buffer them, which would otherwise hold
  back the tail of a large frame until enough later frames arrive to push it out."
  (:require
    [clojure.core.async :as async]
    [glimmer.ratom :as ratom]
    [jolt.datastar.cache :as cache]
    [jolt.datastar.core :as ds])
  (:import
    (java.io
      StringReader)
    (java.util.concurrent
      ConcurrentHashMap)))


;; ## Cache guard
;;
;; glimmer-datastar's component cache records the values of the cells a component read
;; after the component has rendered, not when it read them. If a topic is touched while a
;; component is rendering, after it has loaded its data but before it is admitted, the entry
;; holds HTML from before the change as current for the revision after it, and nothing
;; re-renders it. The guard refuses to admit an entry if any topic was touched while it
;; rendered; the render is still served, and the next one recomputes it.

(def ^:private epoch
  "Counts touches, so a render can tell whether one happened while it ran."
  (atom 0))


(def ^:private starts
  "Per render thread, the stack of epochs at the lookups whose outcome isn't known yet."
  (ConcurrentHashMap.))


(def ^:private pending
  "Per render thread, the epoch at the lookup of the component about to be admitted."
  (ConcurrentHashMap.))


(defn- push-start!
  [t e]
  (.put starts t (conj (or (.get starts t) []) e)))


(defn- pop-start!
  "The epoch last pushed on thread t, or nil."
  [t]
  (when-let [stack (.get starts t)]
    (if (<= (count stack) 1)
      (.remove starts t)
      (.put starts t (pop stack)))
    (peek stack)))


(def ^:private guard-installed
  (atom false))


(defn- install-cache-guard!
  "Wrap the component cache's lookup, record! and admit! with the guard, once."
  []
  (when (compare-and-set! guard-installed false true)
    (let [lookup cache/lookup
          record! cache/record!
          admit! cache/admit!]
      ;; a lookup precedes the render of a component, and record! follows it, before admit!
      (alter-var-root #'cache/lookup
                      (fn [_]
                        (fn [c k]
                          (push-start! (Thread/currentThread) @epoch)
                          (lookup c k))))
      (alter-var-root #'cache/record!
                      (fn [_]
                        (fn [c what]
                          (let [t (Thread/currentThread)
                                start (pop-start! t)]
                            (when (and start (not= :hits what))
                              (.put pending t start)))
                          (record! c what))))
      (alter-var-root #'cache/admit!
                      (fn [_]
                        (fn [c k e]
                          (let [start (.remove pending (Thread/currentThread))]
                            (and (or (nil? start) (= start @epoch))
                                 (admit! c k e)))))))))


(def ^:private default-heartbeat-ms
  "How long a stream may be quiet before it gets a heartbeat."
  25000)


(def heartbeat
  "An SSE comment, which the Datastar client ignores."
  ": heartbeat\n\n")


(defn live
  "A new live state: the topic revisions, the render scheduler and the open stream count.
  Options: :heartbeat-ms, how long a stream may be quiet before it gets a heartbeat."
  ([] (live {}))
  ([{:keys [heartbeat-ms] :or {heartbeat-ms default-heartbeat-ms}}]
   (install-cache-guard!)
   {:cells (atom {})
    :scheduler (ds/scheduler)
    :streams (atom 0)
    :heartbeat-ms heartbeat-ms}))


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
    (swap! c inc))
  (swap! epoch inc))


(defn stream-count
  "The number of open SSE streams."
  [l]
  @(:streams l))


(defn stats
  "The open stream count and the component cache's counters, as {:streams n :cache {...}}."
  [l]
  {:streams (stream-count l)
   :cache (ds/cache-stats (:scheduler l))})


(defn wrap-touch
  "Middleware touching the :topics in the response's metadata."
  [handler l]
  (fn [req]
    (let [resp (handler req)]
      (when-let [topics (seq (:topics (meta resp)))]
        (touch! l topics))
      resp)))


(defn- on-close
  "A transducer passing values through, which calls f when its channel closes."
  [f]
  (fn [rf]
    (fn
      ([] (rf))
      ([acc] (f) (rf acc))
      ([acc x] (rf acc x)))))


(defn- relay
  "A channel carrying the frames of glimmer-datastar's stream channel in, plus a heartbeat
  whenever in has been quiet for the heartbeat interval. The heartbeat can't go on in
  itself, whose sliding buffer would drop a frame the client hasn't taken for it. Closing
  either channel closes the other: the server closes the relay when the client goes away,
  which releases the stream's render, and a render ending the stream closes in."
  [{:keys [streams heartbeat-ms]} in]
  (swap! streams inc)
  (let [out (async/chan 1 (on-close (fn released
                                      []
                                      (swap! streams dec)
                                      (async/close! in))))]
    (async/go-loop []
      (let [[frame port] (async/alts! [in (async/timeout heartbeat-ms)])]
        (cond
          (not= port in) (when (async/>! out heartbeat) (recur))
          (some? frame) (when (async/>! out frame) (recur))
          :else (async/close! out))))
    out))


(defn- stream?
  "True for glimmer-datastar's SSE response, whose body is a channel."
  [{:keys [status headers body]}]
  (and (= 200 status)
       (= "text/event-stream; charset=utf-8" (get headers "Content-Type"))
       (not (string? body))))


(defn wrap-live
  "The glimmer-datastar middleware. It reads the body of Datastar requests for signals,
  so the body is buffered and handed to handler unread. A body it can't parse is a 400.
  SSE streams go out through a relay that adds heartbeats, marked as unbuffered for nginx."
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
          (let [resp (if body
                       (let [s (slurp body)]
                         (inner (assoc req :body (StringReader. s) ::body s)))
                       (inner req))]
            (cond-> resp
              (stream? resp) (-> (update :body #(relay l %))
                                 (assoc-in [:headers "X-Accel-Buffering"] "no"))))
          (catch Exception e
            (if @reached
              (throw e)
              {:status 400
               :headers {"Content-Type" "text/plain; charset=utf-8"}
               :body "Bad request"})))))))
