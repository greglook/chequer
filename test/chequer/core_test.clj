(ns chequer.core-test
  (:require
    [chequer.core :as core]
    [clojure.core.async :as async]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]))


(defn- request
  [uri]
  (core/handler {:request-method :get :uri uri :headers {}}))


(deftest index-page-loads-datastar
  (let [r (request "/")]
    (is (= 200 (:status r)))
    (is (str/starts-with? (get-in r [:headers "Content-Type"]) "text/html"))
    (is (str/includes? (:body r) "/js/datastar.js"))
    (is (str/includes? (:body r) "data-on:click=\"@get(&apos;/hello&apos;)\""))))


(deftest serves-datastar-bundle
  (let [r (request "/js/datastar.js")]
    (is (= 200 (:status r)))
    (is (str/starts-with? (get-in r [:headers "Content-Type"]) "text/javascript"))
    (is (str/starts-with? (:body r) "// Datastar v1.0.4"))))


(deftest hello-streams-patch-elements
  (let [r (request "/hello")
        e (async/<!! (:body r))]
    (is (= "text/event-stream" (get-in r [:headers "Content-Type"])))
    (is (str/includes? e "event: datastar-patch-elements"))
    (is (str/includes? e "data: elements <p id=\"greeting\">"))
    (is (nil? (async/<!! (:body r))) "stream closes after the patch")))


(deftest unknown-path-is-404
  (is (= 404 (:status (request "/nope")))))


(defn- logged
  "Call handler wrapped in the request log; return [response-or-exception log-output]."
  [handler req]
  (let [err (java.io.StringWriter.)
        result (binding [*err* err]
                 (try ((core/wrap-request-log handler) req)
                      (catch Exception e e)))]
    [result (str err)]))


(deftest request-log-records-each-request
  (let [resp {:status 201 :body "ok"}
        [r out] (logged (constantly resp)
                        {:request-method :post :uri "/lists" :query-string "a=1" :headers {}})]
    (is (= resp r) "response passes through unchanged")
    (is (re-matches #"POST /lists\?a=1 201 \d+ms\n" out))))


(deftest request-log-omits-empty-query
  (let [[_ out] (logged (constantly {:status 200})
                        {:request-method :get :uri "/" :headers {}})]
    (is (re-matches #"GET / 200 \d+ms\n" out))))


(deftest request-log-records-handler-exceptions
  (let [[r out] (logged (fn [_] (throw (ex-info "boom" {})))
                        {:request-method :get :uri "/x" :headers {}})]
    (is (= "boom" (ex-message r)) "exception is rethrown")
    (is (re-matches #"GET /x ERROR \d+ms boom\n" out))))
