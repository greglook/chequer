(ns chequer.broker-test
  (:require
    [chequer.broker :as broker]
    [clojure.core.async :as async]
    [clojure.test :refer [deftest is]]))


(defn- drain
  [ch]
  (loop [out []]
    (if-let [v (async/poll! ch)]
      (recur (conj out v))
      out)))


(deftest subscribers-receive-rendered-events-for-their-topics
  (let [b (broker/broker)
        a (async/chan 8)
        c (async/chan 8)
        seen (atom [])]
    (broker/subscribe! b [:plan 7] a {:tz "UTC"})
    (broker/subscribe! b [:plan 8] c {:tz "UTC"})
    (broker/publish! b #{[:plan 7] [:plans]}
                     (fn [topic m]
                       (swap! seen conj [topic m])
                       ["e1" "e2"]))
    (is (= ["e1" "e2"] (drain a)))
    (is (= [] (drain c)) "unrelated topics get nothing")
    (is (= [[[:plan 7] {:tz "UTC"}]] @seen))))


(deftest closed-channels-are-unsubscribed
  (let [b (broker/broker)
        a (async/chan 8)
        c (async/chan 8)]
    (broker/subscribe! b [:plan 7] a {})
    (broker/subscribe! b [:plan 7] c {})
    (async/close! a)
    (broker/publish! b #{[:plan 7]} (constantly ["e"]))
    (is (= ["e"] (drain c)))
    (is (= 1 (count (get @b [:plan 7]))))
    (broker/publish! b #{[:plan 7]} (constantly ["f"]))
    (is (= ["f"] (drain c)))))


(deftest full-channels-are-dropped-and-closed-instead-of-blocking
  (let [b (broker/broker)
        a (async/chan 1)]
    (broker/subscribe! b [:plan 7] a {})
    (broker/publish! b #{[:plan 7]} (constantly ["e1" "e2"]))
    (is (empty? (get @b [:plan 7])))
    (is (= ["e1"] (drain a)))
    (is (nil? (async/<!! a)) "the dropped channel is closed, ending its stream")))


(deftest unsubscribe-removes-a-channel-from-every-topic
  (let [b (broker/broker)
        a (async/chan 8)]
    (broker/subscribe! b [:plan 7] a {})
    (broker/subscribe! b [:plans] a {})
    (broker/unsubscribe! b a)
    (is (= {} @b))))


(deftest wrap-publish-publishes-the-topics-in-response-metadata
  (let [calls (atom [])
        handler (broker/wrap-publish
                  (fn [req]
                    (case (:uri req)
                      "/a" (with-meta {:status 204} {:topics #{[:plan 1]}})
                      "/b" {:status 200}
                      "/c" (with-meta {:status 204} {:topics #{}})))
                  (fn [topics] (swap! calls conj topics)))]
    (is (= {:status 204} (handler {:uri "/c"})))
    (is (= {:status 204} (handler {:uri "/a"})))
    (is (= {:status 200} (handler {:uri "/b"})))
    (is (= [#{[:plan 1]}] @calls))))
