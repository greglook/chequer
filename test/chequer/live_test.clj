(ns chequer.live-test
  (:require
    [chequer.live :as live]
    [clojure.core.async :as async]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]])
  (:import
    (java.io
      ByteArrayInputStream)))


(defn- take-frame
  [ch]
  (first (async/alts!! [ch (async/timeout 2000)])))


(deftest touch-bumps-only-the-touched-topics
  (let [l (live/live)
        a (live/cell l [:plan 7])
        b (live/cell l [:plan 8])]
    (is (identical? a (live/cell l [:plan 7])))
    (live/touch! l #{[:plan 7] [:plans]})
    (is (= [1 0] [@a @b]))
    (is (nil? (get @(:cells l) [:plans])) "touching an unwatched topic creates no cell")))


(deftest wrap-touch-touches-the-topics-in-response-metadata
  (let [l (live/live)
        c (live/cell l [:plan 1])
        handler (live/wrap-touch
                  (fn [req]
                    (case (:uri req)
                      "/a" (with-meta {:status 204} {:topics #{[:plan 1]}})
                      "/b" {:status 200}))
                  l)]
    (is (= {:status 200} (handler {:uri "/b"})))
    (is (= 0 @c))
    (is (= {:status 204} (handler {:uri "/a"})))
    (is (= 1 @c))))


(deftest sse-requests-re-render-when-a-cell-they-read-changes
  (let [l (live/live)
        handler (live/wrap-live
                  (fn [req]
                    {:status 200 :body (str "<p>rev " @(live/cell l [:plan 1]) " " (boolean (:jolt.datastar/sse-request req)) "</p>")})
                  l)]
    (try
      (let [r (handler {:request-method :get :uri "/plans/1" :query-string "datastar-sse=true" :headers {}})
            ch (:body r)]
        (is (= "text/event-stream; charset=utf-8" (get-in r [:headers "Content-Type"])))
        (is (str/includes? (take-frame ch) "<p>rev 0 true</p>"))
        (live/touch! l #{[:plan 1]})
        (let [frame (take-frame ch)]
          (is (str/includes? frame "event: datastar-patch-elements"))
          (is (str/includes? frame "<p>rev 1 true</p>")))
        (async/close! ch))
      (finally
        (live/stop! l)))))


(defn- sse-request
  []
  {:request-method :get :uri "/plans/1" :query-string "datastar-sse=true" :headers {}})


(defn- await-streams
  "Wait up to a second for l's open stream count to be n, and return it."
  [l n]
  (loop [tries 100]
    (let [c (live/stream-count l)]
      (if (or (= n c) (zero? tries))
        c
        (do (async/<!! (async/timeout 10))
            (recur (dec tries)))))))


(deftest idle-streams-get-heartbeats
  (let [l (live/live {:heartbeat-ms 50})
        handler (live/wrap-live (fn [_] {:status 200 :body (str "<p>rev " @(live/cell l [:plan 1]) "</p>")}) l)]
    (try
      (let [ch (:body (handler (sse-request)))]
        (is (= 1 (live/stream-count l)))
        (is (str/includes? (take-frame ch) "<p>rev 0</p>"))
        (is (= live/heartbeat (take-frame ch)) "a quiet stream gets a heartbeat")
        (is (= live/heartbeat (take-frame ch)) "and another")
        (live/touch! l #{[:plan 1]})
        (is (str/includes? (take-frame ch) "<p>rev 1</p>") "frames still arrive between heartbeats")
        (async/close! ch))
      (finally
        (live/stop! l)))))


(deftest closed-streams-are-released
  (let [l (live/live {:heartbeat-ms 50})
        renders (atom 0)
        handler (live/wrap-live (fn [_]
                                  (swap! renders inc)
                                  {:status 200 :body (str "<p>rev " @(live/cell l [:plan 1]) "</p>")})
                                l)]
    (try
      (testing "a stream the client left"
        (let [ch (:body (handler (sse-request)))]
          (take-frame ch)
          (is (= 1 @renders))
          (async/close! ch)
          (is (zero? (await-streams l 0)) "is no longer counted")
          (live/touch! l #{[:plan 1]})
          (async/<!! (async/timeout 100))
          (is (= 1 @renders) "renders no more")))
      (testing "a stream whose render ends it"
        (let [ended (live/wrap-live (fn [_] @(live/cell l [:plan 1]) {:status 401 :body "gone"}) l)
              ch (:body (ended (sse-request)))]
          (is (nil? (take-frame ch)) "is closed")
          (is (zero? (await-streams l 0)) "is no longer counted")))
      (finally
        (live/stop! l)))))


(deftest action-requests-keep-their-body
  (let [l (live/live)
        handler (live/wrap-live (fn [req] {:status 200 :body (slurp (:body req)) :sse (:jolt.datastar/sse-request req)}) l)
        r (handler {:request-method :post :uri "/plans"
                    :headers {"datastar-request" "true" "content-type" "application/x-www-form-urlencoded"}
                    :body (ByteArrayInputStream. (.getBytes "title=x" "UTF-8"))})]
    (is (= "title=x" (:body r)))
    (is (nil? (:sse r)))))
