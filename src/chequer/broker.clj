(ns chequer.broker
  "Live-update subscriptions: SSE channels subscribed to topics such as [:plan 7]."
  (:require
    [clojure.core.async :as async]))


(defn broker
  "A new broker: an atom of {topic #{{:ch ch :meta m}}}."
  []
  (atom {}))


(defn subscribe!
  "Subscribe channel ch to topic. m (e.g. {:user .. :tz ..}) is passed to the renderer."
  [b topic ch m]
  (swap! b update topic (fnil conj #{}) {:ch ch :meta m}))


(defn unsubscribe!
  "Remove channel ch from every topic."
  [b ch]
  (swap! b (fn [subscriptions]
             (into {}
                   (keep (fn [[topic entries]]
                           (let [kept (into #{} (remove #(= ch (:ch %))) entries)]
                             (when (seq kept) [topic kept]))))
                   subscriptions))))


(defn publish!
  "Push (render-fn topic meta), a sequence of SSE event strings, to each subscriber of each
  of topics. A subscriber whose channel is closed or full is unsubscribed and closed, so
  a slow client can't block the publisher; its page reconnects and re-renders."
  [b topics render-fn]
  (doseq [topic topics
          {ch :ch m :meta} (get @b topic)]
    (when-not (every? #(async/offer! ch %) (render-fn topic m))
      (unsubscribe! b ch)
      (async/close! ch))))


(defn wrap-publish
  "Middleware calling (publish-fn topics) with the :topics in the response's metadata,
  when there are any."
  [handler publish-fn]
  (fn [req]
    (let [resp (handler req)]
      (when-let [topics (seq (:topics (meta resp)))]
        (publish-fn (set topics)))
      resp)))
