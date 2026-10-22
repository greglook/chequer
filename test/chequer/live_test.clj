(ns chequer.live-test
  (:require
    [chequer.live :as live]
    [clojure.core.async :as async]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]])
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


(deftest action-requests-keep-their-body
  (let [l (live/live)
        handler (live/wrap-live (fn [req] {:status 200 :body (slurp (:body req)) :sse (:jolt.datastar/sse-request req)}) l)
        r (handler {:request-method :post :uri "/plans"
                    :headers {"datastar-request" "true" "content-type" "application/x-www-form-urlencoded"}
                    :body (ByteArrayInputStream. (.getBytes "title=x" "UTF-8"))})]
    (is (= "title=x" (:body r)))
    (is (nil? (:sse r)))))
