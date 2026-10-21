(ns chequer.router
  "A small router matching request method and path segments."
  (:require
    [clojure.string :as str]))


(defn- split-path
  "Split path into its non-blank segments."
  [path]
  (into [] (remove str/blank?) (str/split path #"/")))


(defn- parse-id
  "Parse s as a non-negative integer id, or nil if it is not one or does not fit."
  [s]
  (when (re-matches #"\d+" s)
    (try (parse-long s) (catch Exception _ nil))))


(defn- match-segments
  "Match pattern segments against path segments. Keyword segments capture numeric ids."
  [pattern segments]
  (when (= (count pattern) (count segments))
    (reduce (fn [params [p s]]
              (cond
                (keyword? p) (if-let [id (parse-id s)]
                               (assoc params p id)
                               (reduced nil))
                (= p s) params
                :else (reduced nil)))
            {}
            (map vector pattern segments))))


(defn match
  "Find the route for method and path. Routes are [method pattern handler] vectors, e.g.
  [:get [\"plans\" :id] f]. Returns {:handler f :params {:id 7}} or nil."
  [routes method path]
  (let [segments (split-path path)]
    (some (fn [[m pattern handler]]
            (when (= m method)
              (when-let [params (match-segments pattern segments)]
                {:handler handler :params params})))
          routes)))


(defn allowed-methods
  "The set of methods that have a route matching path."
  [routes path]
  (let [segments (split-path path)]
    (into #{}
          (keep (fn [[m pattern _]] (when (match-segments pattern segments) m)))
          routes)))


(defn handler
  "Ring handler dispatching on request method and uri. Matched params are merged
  into the request as :params."
  [routes]
  (fn [{:keys [request-method uri] :as req}]
    (if-let [{route-handler :handler :keys [params]} (match routes request-method uri)]
      (route-handler (assoc req :params params))
      (let [allowed (allowed-methods routes uri)]
        (if (seq allowed)
          {:status 405
           :headers {"Content-Type" "text/plain; charset=utf-8"
                     "Allow" (str/join ", " (map (comp str/upper-case name) (sort allowed)))}
           :body "Method not allowed"}
          {:status 404
           :headers {"Content-Type" "text/plain; charset=utf-8"}
           :body "Not found"})))))
