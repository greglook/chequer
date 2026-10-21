(ns chequer.router-test
  (:require
    [chequer.router :as router]
    [clojure.test :refer [deftest is]]))


(defn- h-index [_] {:status 200 :body "index"})
(defn- h-show [_] {:status 200 :body "show"})
(defn- h-update [_] {:status 200 :body "update"})
(defn- h-task [_] {:status 200 :body "task"})


(def ^:private routes
  [[:get [] h-index]
   [:get ["plans" :id] h-show]
   [:put ["plans" :id] h-update]
   [:post ["plans" :id "tasks"] h-task]
   [:delete ["plans" :plan-id "tasks" :task-id] h-task]])


(deftest matches-method-and-segments
  (is (= {:handler h-index :params {}} (router/match routes :get "/")))
  (is (= {:handler h-show :params {:id 7}} (router/match routes :get "/plans/7")))
  (is (= {:handler h-update :params {:id 7}} (router/match routes :put "/plans/7")))
  (is (= {:handler h-task :params {:id 12}} (router/match routes :post "/plans/12/tasks")))
  (is (= {:handler h-task :params {:plan-id 1 :task-id 2}}
         (router/match routes :delete "/plans/1/tasks/2"))))


(deftest trailing-slash-is-ignored
  (is (= {:handler h-show :params {:id 7}} (router/match routes :get "/plans/7/"))))


(deftest no-match-returns-nil
  (is (nil? (router/match routes :get "/nope")))
  (is (nil? (router/match routes :get "/plans")))
  (is (nil? (router/match routes :get "/plans/7/extra")))
  (is (nil? (router/match routes :post "/plans/7"))))


(deftest ids-must-be-numeric
  (is (nil? (router/match routes :get "/plans/abc")))
  (is (nil? (router/match routes :get "/plans/-1")))
  (is (nil? (router/match routes :get "/plans/7x")))
  (is (nil? (router/match routes :get "/plans/99999999999999999999")))
  (is (= {:id 0} (:params (router/match routes :get "/plans/0")))))


(deftest allowed-methods-lists-methods-for-a-path
  (is (= #{:get :put} (router/allowed-methods routes "/plans/7")))
  (is (= #{} (router/allowed-methods routes "/nope"))))


(deftest handler-dispatches-with-params-on-request
  (let [seen (atom nil)
        h (router/handler [[:get ["plans" :id] (fn [req] (reset! seen (:params req)) {:status 200})]])]
    (is (= 200 (:status (h {:request-method :get :uri "/plans/5"}))))
    (is (= {:id 5} @seen))))


(deftest handler-404-for-unknown-path-and-bad-id
  (let [h (router/handler routes)]
    (is (= 404 (:status (h {:request-method :get :uri "/nope"}))))
    (is (= 404 (:status (h {:request-method :get :uri "/plans/abc"}))))))


(deftest handler-405-with-allow-header
  (let [r ((router/handler routes) {:request-method :post :uri "/plans/7"})]
    (is (= 405 (:status r)))
    (is (= "GET, PUT" (get-in r [:headers "Allow"])))))
