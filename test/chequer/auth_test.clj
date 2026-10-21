(ns chequer.auth-test
  (:require
    [chequer.auth :as auth]
    [chequer.config :as config]
    [chequer.db :as db]
    [chequer.test-helpers :as helpers]
    [clojure.test :refer [deftest is]]))


(defn- with-db
  [f]
  (let [path (helpers/temp-db-path)
        d (db/open path)]
    (try
      (db/migrate! d)
      (f d)
      (finally
        (db/close d)
        (helpers/delete-db-files path)))))


(defn- echo-user
  [req]
  {:status 200 :body (:chequer/user req)})


(defn- request
  [d cfg & {:as overrides}]
  (merge {:request-method :get
          :uri "/"
          :remote-addr "127.0.0.1"
          :headers {}
          :chequer/db d
          :chequer/cfg cfg}
         overrides))


(def ^:private proxy-cfg
  (config/normalize {"auth" {"mode" "proxy"}
                     "auth.proxy" {"header" "X-Forwarded-User"}}))


(deftest cidr-matching
  (is (auth/trusted? "10.2.3.4" ["10.0.0.0/8"]))
  (is (not (auth/trusted? "11.0.0.1" ["10.0.0.0/8"])))
  (is (auth/trusted? "10.255.255.255" ["10.0.0.0/8"]) "top of the range")
  (is (not (auth/trusted? "9.255.255.255" ["10.0.0.0/8"])) "just below the range")
  (is (auth/trusted? "192.168.1.77" ["192.168.1.0/24"]))
  (is (not (auth/trusted? "192.168.2.1" ["192.168.1.0/24"])))
  (is (auth/trusted? "172.16.5.1" ["172.16.0.0/12"]) "prefix not on a byte boundary")
  (is (not (auth/trusted? "172.32.0.1" ["172.16.0.0/12"])))
  (is (auth/trusted? "127.0.0.1" ["127.0.0.1"]) "bare address means /32")
  (is (not (auth/trusted? "127.0.0.2" ["127.0.0.1"])))
  (is (auth/trusted? "1.2.3.4" ["0.0.0.0/0"]))
  (is (auth/trusted? "::1" ["127.0.0.1" "::1"]))
  (is (auth/trusted? "fd00::5" ["fd00::/8"]))
  (is (not (auth/trusted? "fe80::1" ["fd00::/8"])))
  (is (not (auth/trusted? "::1" ["127.0.0.1"])) "families don't mix")
  (is (not (auth/trusted? "10.0.0.1" [])))
  (is (not (auth/trusted? nil ["127.0.0.1"])))
  (is (not (auth/trusted? "not-an-ip" ["127.0.0.1"])) "hostnames are never resolved"))


(deftest single-mode-resolves-the-owner
  (with-db
    (fn [d]
      (let [cfg (config/normalize {"auth.single" {"name" "greg" "title" "Greg"}})
            h (auth/wrap-auth echo-user)
            u1 (:body (h (request d cfg)))
            u2 (:body (h (request d cfg :remote-addr "8.8.8.8")))]
        (is (= {:subject "owner" :name "greg" :title "Greg"} (select-keys u1 [:subject :name :title])))
        (is (= (:id u1) (:id u2)))
        (is (= 1 (:n (db/q1 d ["SELECT count(*) AS n FROM user"]))))))))


(deftest proxy-mode-trusts-loopback
  (with-db
    (fn [d]
      (let [h (auth/wrap-auth echo-user)
            r (h (request d proxy-cfg :headers {"x-forwarded-user" "alice"}))]
        (is (= 200 (:status r)))
        (is (= {:subject "alice" :name "alice" :title "alice"}
               (select-keys (:body r) [:subject :name :title])))))))


(deftest proxy-mode-header-lookup-ignores-case
  (with-db
    (fn [d]
      (let [h (auth/wrap-auth echo-user)
            r (h (request d (assoc-in proxy-cfg [:auth :proxy :header] "X-Auth-User")
                          :headers {"x-auth-user" "bob"}))]
        (is (= "bob" (:name (:body r))))))))


(deftest proxy-mode-returning-user-keeps-identity
  (with-db
    (fn [d]
      (let [h (auth/wrap-auth echo-user)
            req (request d proxy-cfg :headers {"x-forwarded-user" "alice"})
            id1 (:id (:body (h req)))
            id2 (:id (:body (h req)))]
        (is (= id1 id2))
        (is (= 1 (:n (db/q1 d ["SELECT count(*) AS n FROM user"]))))))))


(deftest proxy-mode-untrusted-source-gets-403-even-with-header
  (with-db
    (fn [d]
      (let [h (auth/wrap-auth echo-user)
            r (h (request d proxy-cfg :remote-addr "203.0.113.9" :headers {"x-forwarded-user" "mallory"}))]
        (is (= 403 (:status r)))
        (is (= 0 (:n (db/q1 d ["SELECT count(*) AS n FROM user"]))) "nobody was provisioned")))))


(deftest proxy-mode-missing-or-blank-header-gets-403
  (with-db
    (fn [d]
      (let [h (auth/wrap-auth echo-user)]
        (is (= 403 (:status (h (request d proxy-cfg)))))
        (is (= 403 (:status (h (request d proxy-cfg :headers {"x-forwarded-user" "  "})))))))))


(deftest health-and-static-paths-bypass-auth
  (with-db
    (fn [d]
      (let [h (auth/wrap-auth (fn [req] {:status 200 :body (:chequer/user req)}))]
        (doseq [uri ["/healthz" "/js/datastar.js" "/css/app.css"]]
          (let [r (h (request d proxy-cfg :uri uri :remote-addr "203.0.113.9"))]
            (is (= 200 (:status r)) uri)
            (is (nil? (:body r)) uri)))
        (is (= 403 (:status (h (request d proxy-cfg :uri "/jsx" :remote-addr "203.0.113.9"))))
            "lookalike prefix is not a static path")))))


(def ^:private origin-cfg
  (config/normalize {"server" {"base-url" "https://chequer.example.com"}}))


(defn- origin-request
  [method headers & {:keys [cfg] :or {cfg origin-cfg}}]
  {:request-method method :uri "/x" :headers headers :chequer/cfg cfg})


(defn- status-of
  [req]
  (:status ((auth/wrap-origin-check (fn [_] {:status 204})) req)))


(deftest origin-check-allows-matching-origin
  (is (= 204 (status-of (origin-request :post {"origin" "https://chequer.example.com"})))))


(deftest origin-check-blocks-foreign-origin
  (is (= 403 (status-of (origin-request :post {"origin" "https://evil.example"}))))
  (is (= 403 (status-of (origin-request :post {"origin" "http://chequer.example.com"}))) "wrong scheme")
  (is (= 403 (status-of (origin-request :post {"origin" "https://chequer.example.com:8443"}))) "wrong port")
  (is (= 403 (status-of (origin-request :post {"origin" "https://chequer.example.com.evil.example"}))))
  (is (= 403 (status-of (origin-request :post {"origin" "null"})))))


(deftest origin-check-ignores-path-and-default-port-in-base-url
  (let [cfg (config/normalize {"server" {"base-url" "https://chequer.example.com:443/app/"}})]
    (is (= 204 (status-of (origin-request :post {"origin" "https://chequer.example.com"} :cfg cfg))))
    (is (= 204 (status-of (origin-request :post {"origin" "https://chequer.example.com:443"} :cfg cfg))))))


(deftest origin-check-keeps-explicit-port
  (let [cfg (config/normalize {"server" {"base-url" "http://127.0.0.1:3000"}})]
    (is (= 204 (status-of (origin-request :put {"origin" "http://127.0.0.1:3000"} :cfg cfg))))
    (is (= 403 (status-of (origin-request :put {"origin" "http://127.0.0.1:3001"} :cfg cfg))))))


(deftest origin-check-falls-back-to-sec-fetch-site
  (is (= 204 (status-of (origin-request :post {"sec-fetch-site" "same-origin"}))))
  (is (= 403 (status-of (origin-request :post {"sec-fetch-site" "cross-site"}))))
  (is (= 403 (status-of (origin-request :post {"sec-fetch-site" "same-site"}))))
  (is (= 403 (status-of (origin-request :post {}))) "neither header"))


(deftest origin-check-prefers-origin-over-sec-fetch-site
  (is (= 403 (status-of (origin-request :post {"origin" "https://evil.example"
                                               "sec-fetch-site" "same-origin"})))))


(deftest origin-check-skips-safe-methods
  (is (= 204 (status-of (origin-request :get {}))))
  (is (= 204 (status-of (origin-request :head {}))))
  (is (= 403 (status-of (origin-request :delete {}))))
  (is (= 403 (status-of (origin-request :patch {})))))
