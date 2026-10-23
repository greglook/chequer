(ns chequer.load-test
  "Many open pages against a real server. Tagged ^:load, so bin/test skips it; run it with
  bin/test -i :load."
  (:require
    [chequer.config :as config]
    [chequer.core :as core]
    [chequer.data.lists :as lists]
    [chequer.db :as db]
    [chequer.live :as live]
    [chequer.test-helpers :as helpers]
    [clojure.core.async :as async]
    [clojure.data.json :as json]
    [clojure.test :refer [deftest is]])
  (:import
    (java.net
      Socket)))


(def ^:private stream-total 50)
(def ^:private mutation-total 100)
(def ^:private task-total 10)
(def ^:private delivery-ms 2000)
(def ^:private latency-ms 200)
(def ^:private base-url "http://load.test")


;; ## Client

(defn- send-request!
  "Write a request for path to socket s, with body as JSON when given."
  [^Socket s method path body]
  (let [^bytes payload (some-> body (json/write-str) (.getBytes "UTF-8"))
        head (str method " " path " HTTP/1.1\r\n"
                  "Host: 127.0.0.1\r\n"
                  "Origin: " base-url "\r\n"
                  "Connection: close\r\n"
                  (when payload
                    (str "Content-Type: application/json\r\n"
                         "Content-Length: " (alength payload) "\r\n"))
                  "\r\n")
        out (.getOutputStream s)]
    (.write out (.getBytes head "UTF-8"))
    (when payload (.write out payload))
    (.flush out)))


(defn- read-all
  "Everything socket s sends until it closes, as a string."
  [^Socket s]
  (let [in (.getInputStream s)
        buf (byte-array 8192)
        sb (StringBuilder.)]
    (loop []
      (let [n (.read in buf)]
        (when (pos? n)
          (.append sb (String. buf 0 n "UTF-8"))
          (recur))))
    (str sb)))


(defn- request!
  "Make a request on a new connection and read the whole response. Returns {:status n :ms t}."
  [port method path body]
  (let [start (System/nanoTime)]
    (with-open [s (Socket. "127.0.0.1" (int port))]
      (send-request! s method path body)
      (let [resp (read-all s)]
        {:status (some-> (re-find #"^HTTP/1\.1 (\d+)" resp) second parse-long)
         :ms (/ (- (System/nanoTime) start) 1e6)}))))


(defn- marker
  "The title the ith mutation gives a task."
  [i]
  (str "load-" i "-end"))


(defn- open-stream!
  "Open an SSE stream of path, read on a thread of its own. Returns {:socket s :frames n :seen
  i}, where frames counts the patch events received and seen is the highest marker number
  received."
  [port path]
  (let [s (Socket. "127.0.0.1" (int port))
        frames (atom 0)
        seen (atom 0)]
    (send-request! s "GET" path nil)
    (async/thread
      (let [in (.getInputStream s)
            buf (byte-array 65536)]
        (loop [tail ""]
          (let [n (try (.read in buf) (catch Exception _ -1))]
            (when (pos? n)
              ;; tail carries the end of the last read, so a marker split across reads is found
              (let [text (str tail (String. buf 0 n "UTF-8"))]
                (swap! frames + (count (re-seq #"event: datastar-patch-elements" (subs text (count tail)))))
                (doseq [[_ i] (re-seq #"load-(\d+)-end" text)]
                  (swap! seen max (parse-long i)))
                (recur (subs text (max 0 (- (count text) 32))))))))))
    {:socket s :frames frames :seen seen}))


;; ## Measuring

(defn- await-until
  "Poll (pred) every 5ms until it's true or ms have passed. Returns whether it became true."
  [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond
        (pred) true
        (< deadline (System/currentTimeMillis)) false
        :else (do (async/<!! (async/timeout 5))
                  (recur))))))


(defn- percentile
  [xs p]
  (let [sorted (vec (sort xs))]
    (sorted (min (dec (count sorted)) (int (* p (count sorted)))))))


(defn- watcher-count
  "The number of stream renders watching any of l's topic cells."
  [l]
  (reduce + (map #(count @(:watches %)) (vals @(:cells l)))))


;; ## Test

(deftest ^:load many-streams-receive-every-update
  (let [path (helpers/temp-db-path)
        sys (core/start! (config/normalize {"database" {"path" path}
                                            "server" {"listen" "127.0.0.1:0" "base-url" base-url}}))
        port (:port (:server sys))
        u (helpers/create-user! (:db sys) "load")
        {lid :result} (db/with-tx [tx (:db sys)] (lists/create-list! tx u {:title "Load"}))
        task-ids (vec (for [i (range task-total)]
                        (:result (db/with-tx [tx (:db sys)] (lists/create-task! tx u {:list-id lid :title (str "Task " i)})))))
        streams (vec (repeatedly stream-total #(open-stream! port (str "/lists/" lid "?datastar-sse=true&datastar-selector=%23live"))))]
    (try
      (is (await-until 10000 #(every? (comp pos? deref :frames) streams)) "every stream gets its first frame")
      (let [results (vec (for [i (range 1 (inc mutation-total))]
                           (let [start (System/nanoTime)
                                 resp (request! port "PUT" (str "/tasks/" (task-ids (mod i task-total))) {:title (marker i)})
                                 delivered? (await-until delivery-ms #(every? (fn [{:keys [seen]}] (<= i @seen)) streams))]
                             (assoc resp
                                    :i i
                                    :delivered? delivered?
                                    :delivery-ms (/ (- (System/nanoTime) start) 1e6)))))
            latencies (map :ms results)
            deliveries (map :delivery-ms results)]
        (println (format "load: %d streams, %d mutations; request p50 %.1fms p95 %.1fms max %.1fms; delivery p50 %.1fms p95 %.1fms max %.1fms"
                         stream-total mutation-total
                         (percentile latencies 0.5) (percentile latencies 0.95) (apply max latencies)
                         (percentile deliveries 0.5) (percentile deliveries 0.95) (apply max deliveries)))
        (is (every? #(= 204 (:status %)) results) "every mutation succeeds")
        (is (= [] (mapv :i (remove :delivered? results))) "every stream receives every update in time")
        (is (< (percentile latencies 0.95) latency-ms) "requests stay fast at p95"))
      (is (= stream-total (live/stream-count (:live sys))))
      (run! #(.close ^Socket (:socket %)) streams)
      (is (await-until 10000 #(zero? (live/stream-count (:live sys)))) "closed streams are released")
      (is (zero? (watcher-count (:live sys))) "nothing watches a topic once its streams are gone")
      (finally
        (run! #(.close ^Socket (:socket %)) streams)
        (core/stop! sys)
        (helpers/delete-db-files path)))))
