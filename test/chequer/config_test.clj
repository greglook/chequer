(ns chequer.config-test
  (:require
    [chequer.config :as config]
    [clojure.test :refer [deftest is testing]]))


(def ^:private sample-ini
  "; leading comment
# another comment

[server]
listen = 0.0.0.0:8080
base-url = https://chequer.example.com
time-zone = Europe/Berlin

[auth.proxy]
header=X-Remote-User
")


(deftest parse-ini-handles-sections-comments-and-whitespace
  (is (= {"server" {"listen" "0.0.0.0:8080"
                    "base-url" "https://chequer.example.com"
                    "time-zone" "Europe/Berlin"}
          "auth.proxy" {"header" "X-Remote-User"}}
         (config/parse-ini sample-ini)))
  (is (= {"a" {"k" "v = w"}}
         (config/parse-ini "[a]\n  k  =  v = w  \n"))
      "only the first = separates key and value"))


(deftest parse-ini-rejects-malformed-lines
  (is (thrown-with-msg? Exception #"line 2" (config/parse-ini "[a]\nnot a pair\n"))))


(deftest normalize-fills-defaults
  (is (= {:server {:listen {:host "127.0.0.1" :port 3000}
                   :base-url "http://127.0.0.1:3000"
                   :time-zone "UTC"}
          :database {:path "chequer.db"}
          :auth {:mode :single
                 :single {:name "owner" :title "Owner"}
                 :proxy {:header nil :trusted-proxies ["127.0.0.1" "::1"]}
                 :oidc {:issuer nil
                        :client-id nil
                        :client-secret nil
                        :scopes "openid profile email"
                        :session-ttl-ms 1209600000}}}
         (config/normalize {}))))


(deftest normalize-reads-values
  (let [cfg (config/normalize (config/parse-ini sample-ini))]
    (is (= {:host "0.0.0.0" :port 8080} (get-in cfg [:server :listen])))
    (is (= "https://chequer.example.com" (get-in cfg [:server :base-url])))
    (is (= "Europe/Berlin" (get-in cfg [:server :time-zone])))
    (is (= "X-Remote-User" (get-in cfg [:auth :proxy :header])))))


(deftest listen-forms
  (let [listen #(get-in (config/normalize {"server" {"listen" %}}) [:server :listen])]
    (is (= {:host "127.0.0.1" :port 3000} (listen ":3000")))
    (is (= {:host "0.0.0.0" :port 8080} (listen "0.0.0.0:8080")))
    (is (= {:host "::1" :port 9000} (listen "[::1]:9000")))
    (is (thrown? Exception (listen "nope")))
    (is (thrown? Exception (listen "localhost:abc")))))


(deftest default-base-url-follows-listen
  (is (= "http://0.0.0.0:8080"
         (get-in (config/normalize {"server" {"listen" "0.0.0.0:8080"}}) [:server :base-url]))))


(deftest session-ttl-units
  (let [ttl #(get-in (config/normalize {"auth.oidc" {"session-ttl" %}}) [:auth :oidc :session-ttl-ms])]
    (is (= (* 14 24 60 60 1000) (ttl "14d")))
    (is (= (* 12 60 60 1000) (ttl "12h")))
    (is (= (* 30 60 1000) (ttl "30m")))
    (is (thrown? Exception (ttl "14")))
    (is (thrown? Exception (ttl "soon")))))


(deftest trusted-proxies-split-on-commas
  (is (= ["10.0.0.0/8" "192.168.1.5"]
         (get-in (config/normalize {"auth.proxy" {"trusted-proxies" "10.0.0.0/8, 192.168.1.5"}})
                 [:auth :proxy :trusted-proxies]))))


(defn- errors
  [ini]
  (try (config/validate-config (config/normalize ini))
       nil
       (catch Exception e (:errors (ex-data e)))))


(deftest validate-accepts-defaults
  (let [cfg (config/normalize {})]
    (is (= cfg (config/validate-config cfg)))))


(deftest validate-rejects-unknown-mode
  (is (some? (errors {"auth" {"mode" "bogus"}}))))


(deftest validate-requires-proxy-header
  (is (some? (errors {"auth" {"mode" "proxy"}})))
  (is (nil? (errors {"auth" {"mode" "proxy"} "auth.proxy" {"header" "X-User"}}))))


(deftest validate-requires-oidc-keys
  (testing "all missing"
    (is (some? (errors {"auth" {"mode" "oidc"}}))))
  (testing "complete"
    (is (nil? (errors {"auth" {"mode" "oidc"}
                       "auth.oidc" {"issuer" "https://id.example.com"
                                    "client-id" "abc"
                                    "client-secret" "s3cret"}})))))


(deftest validate-rejects-bad-port
  (is (some? (errors {"server" {"listen" ":70000"}}))))


(deftest load-config-reads-a-file
  (let [path (str "/tmp/chequer-config-test-" (System/nanoTime) ".ini")]
    (spit path sample-ini)
    (try
      (is (= 8080 (get-in (config/load-config path) [:server :listen :port])))
      (finally (.delete (java.io.File. path))))))
