(ns chequer.core-test
  (:require
    [chequer.core :as core]
    [clojure.core.async :as async]
    [clojure.string :as str]
    [clojure.test :refer [deftest is run-tests]]))


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


(defn -main
  [& _]
  (let [{:keys [fail error] :as result} (run-tests 'chequer.core-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "tests failed" result)))))
