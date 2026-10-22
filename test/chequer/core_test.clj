(ns chequer.core-test
  (:require
    [chequer.config :as config]
    [chequer.core :as core]
    [chequer.db :as db]
    [chequer.test-helpers :as helpers]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]))


(defn- with-handler
  "Call f with a ring handler over a migrated temp database."
  [f]
  (let [path (helpers/temp-db-path)
        d (db/open path)
        cfg (config/normalize {"database" {"path" path}})]
    (try
      (db/migrate! d)
      (f (core/handler {:db d :cfg cfg}))
      (finally
        (db/close! d)
        (helpers/delete-db-files path)))))


(defn- get-uri
  [handler uri]
  (handler {:request-method :get :uri uri :headers {}}))


(deftest index-page-loads-datastar-and-css
  (with-handler
    (fn [h]
      (let [r (get-uri h "/")]
        (is (= 200 (:status r)))
        (is (str/starts-with? (get-in r [:headers "Content-Type"]) "text/html"))
        (is (str/includes? (:body r) "/js/datastar.js"))
        (is (str/includes? (:body r) "/css/app.css"))))))


(deftest healthz-reports-ok
  (with-handler
    (fn [h]
      (let [r (get-uri h "/healthz")]
        (is (= 200 (:status r)))
        (is (= "ok" (:body r)))))))


(deftest healthz-rejects-other-methods
  (with-handler
    (fn [h]
      (is (= 405 (:status (h {:request-method :post :uri "/healthz"
                              :headers {"origin" "http://127.0.0.1:3000"}})))))))


(deftest serves-datastar-bundle
  (with-handler
    (fn [h]
      (let [r (get-uri h "/js/datastar.js")]
        (is (= 200 (:status r)))
        (is (str/starts-with? (get-in r [:headers "Content-Type"]) "text/javascript"))
        (is (str/starts-with? (:body r) "// Datastar v1.0.4"))))))


(deftest serves-stylesheet
  (with-handler
    (fn [h]
      (let [r (get-uri h "/css/app.css")]
        (is (= 200 (:status r)))
        (is (str/starts-with? (get-in r [:headers "Content-Type"]) "text/css"))))))


(deftest static-files-reject-unknown-types-and-traversal
  (with-handler
    (fn [h]
      (is (= 404 (:status (get-uri h "/js/missing.js"))))
      (is (= 404 (:status (get-uri h "/js/../css/app.css"))))
      (is (= 404 (:status (get-uri h "/css/../../deps.edn"))))
      (is (= 404 (:status (get-uri h "/js/")))))))


(deftest unknown-path-is-404
  (with-handler
    (fn [h]
      (is (= 404 (:status (get-uri h "/nope")))))))


(deftest wrap-system-puts-db-and-cfg-on-the-request
  (let [sys {:db :the-db :cfg :the-cfg}
        seen (atom nil)
        h (core/wrap-system (fn [req] (reset! seen req) {:status 200}) sys)]
    (h {:request-method :get :uri "/"})
    (is (= :the-db (:chequer/db @seen)))
    (is (= :the-cfg (:chequer/cfg @seen)))))


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


(deftest start-and-stop-run-the-system
  (let [path (helpers/temp-db-path)
        cfg (config/normalize {"database" {"path" path}
                               "server" {"listen" "127.0.0.1:0"}})
        sys (core/start! cfg)]
    (try
      (is (= cfg (:cfg sys)))
      (is (= (count db/migration-files) (:user_version (db/q1 (:db sys) ["PRAGMA user_version"])))
          "migrated on start")
      (finally
        (core/stop! sys)
        (helpers/delete-db-files path)))))


(deftest index-page-shows-the-current-user-and-empty-state
  (with-handler
    (fn [h]
      (let [r (get-uri h "/")]
        (is (str/includes? (:body r) "Owner"))
        (is (str/includes? (:body r) "No lists yet"))))))


(deftest static-files-need-no-user
  (with-handler
    (fn [h]
      (is (= 200 (:status (get-uri h "/css/app.css")))))))


(deftest mutating-requests-need-a-matching-origin
  (with-handler
    (fn [h]
      (is (= 403 (:status (h {:request-method :post :uri "/x" :headers {}}))))
      (is (= 404 (:status (h {:request-method :post :uri "/x"
                              :headers {"origin" "http://127.0.0.1:3000"}})))
          "a same-origin POST reaches the router"))))
