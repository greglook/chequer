(ns chequer.oidc-test
  (:require
    [chequer.config :as config]
    [chequer.core :as core]
    [chequer.crypto :as crypto]
    [chequer.db :as db]
    [chequer.live :as live]
    [chequer.oidc :as oidc]
    [chequer.test-helpers :as helpers]
    [chequer.time :as time]
    [clojure.core.async :as async]
    [clojure.data.json :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]])
  (:import
    (java.net
      URLDecoder)))


(def ^:private base-url "https://chequer.test")
(def ^:private issuer "https://idp.test")
(def ^:private client-id "chequer")


(def ^:private cfg
  (config/normalize {"server" {"base-url" base-url}
                     "auth" {"mode" "oidc"}
                     "auth.oidc" {"issuer" issuer
                                  "client-id" client-id
                                  "client-secret" "s3cret"
                                  "session-key" (apply str (repeat 32 "a5"))
                                  "session-ttl" "1h"}}))


(def ^:private discovery
  {"issuer" issuer
   "authorization_endpoint" (str issuer "/authorize")
   "token_endpoint" (str issuer "/token")})


(defn- jwt
  "An unsigned JWT carrying claims."
  [claims]
  (str (crypto/base64url "{\"alg\":\"none\"}") "."
       (crypto/base64url (json/write-str claims)) "."))


(defn- valid-claims
  [nonce]
  {"iss" issuer
   "aud" client-id
   "sub" "u-123"
   "exp" (+ (quot (time/now-ms) 1000) 300)
   "nonce" nonce
   "preferred_username" "alice"
   "name" "Alice Liddell"})


(defn- with-app
  "Call (f handler ctx) with the full app in OIDC mode over a migrated temp database. The
  fake token endpoint answers with the ID token (tweak claims) gives, where claims are valid
  claims for the nonce of the login in progress. When userinfo is given, the provider has a
  userinfo endpoint answering with the {:status :body} it returns, and its body is encoded
  as JSON. ctx has :sys, :posts, an atom of the requests made to the token endpoint, and
  :userinfo-requests, an atom of those made to the userinfo endpoint."
  ([f] (with-app identity f))
  ([tweak f] (with-app tweak nil f))
  ([tweak userinfo f]
   (helpers/with-test-db
     (fn [d]
       (let [posts (atom [])
             userinfo-requests (atom [])
             nonce (atom nil)
             userinfo-url (str issuer "/userinfo")
             provider (oidc/provider cfg
                                     {:http-get (fn [url & [req]]
                                                  (cond
                                                    (= url (str issuer "/.well-known/openid-configuration"))
                                                    {:status 200 :body (json/write-str (cond-> discovery
                                                                                         userinfo (assoc "userinfo_endpoint" userinfo-url)))}

                                                    (and userinfo (= url userinfo-url))
                                                    (do (swap! userinfo-requests conj req)
                                                        (update (userinfo) :body json/write-str))

                                                    :else
                                                    {:status 404 :body ""}))
                                      :http-post (fn [url req]
                                                   (swap! posts conj (assoc req :url url))
                                                   {:status 200
                                                    :body (json/write-str {"access_token" "at"
                                                                           "id_token" (jwt (tweak (valid-claims @nonce)))})})})
             sys {:db d :cfg cfg :live (live/live) :oidc provider}]
         (try
           (f (core/handler sys) {:sys sys :posts posts :userinfo-requests userinfo-requests :nonce nonce})
           (finally
             (live/stop! (:live sys)))))))))


(defn- request
  [h method uri & {:keys [cookies headers]}]
  (let [[path query] (str/split uri #"\?" 2)]
    (h {:request-method method
        :uri path
        :query-string query
        :remote-addr "127.0.0.1"
        :headers (cond-> (merge {"origin" base-url} headers)
                   (seq cookies) (assoc "cookie" (str/join "; " (map (fn [[k v]] (str k "=" v)) cookies))))})))


(defn- set-cookies
  "The cookies a response sets, as {name {:value v :attrs \"...\"}}."
  [resp]
  (let [hs (get-in resp [:headers "Set-Cookie"])]
    (into {}
          (map (fn [s]
                 (let [[nv attrs] (str/split s #";" 2)
                       [k v] (str/split nv #"=" 2)]
                   [k {:value v :attrs (or attrs "")}])))
          (if (string? hs) [hs] hs))))


(defn- location-params
  [resp]
  (let [[_ q] (str/split (get-in resp [:headers "Location"]) #"\?" 2)]
    (into {}
          (map (fn [kv]
                 (let [[k v] (str/split kv #"=" 2)]
                   [(URLDecoder/decode k "UTF-8") (URLDecoder/decode (or v "") "UTF-8")])))
          (str/split q #"&"))))


(defn- start-login
  "Begin a login at /auth/login (with next), noting its nonce for the fake token endpoint.
  Returns {:resp .. :params .. :login-cookie ..}."
  [h {:keys [nonce]} next-path]
  (let [resp (request h :get (cond-> "/auth/login" next-path (str "?next=" next-path)))
        params (location-params resp)]
    (reset! nonce (get params "nonce"))
    {:resp resp
     :params params
     :login-cookie (get-in (set-cookies resp) ["chequer-login" :value])}))


(defn- finish-login
  [h {:keys [params login-cookie]} & {:keys [state]}]
  (request h :get (str "/auth/callback?code=the-code&state=" (or state (get params "state")))
           :cookies {"chequer-login" login-cookie}))


(defn- session-cookie
  [resp]
  (get-in (set-cookies resp) ["chequer-session" :value]))


(defn- count-rows
  [d table]
  (:n (db/q1 d [(str "SELECT count(*) AS n FROM " table)])))


(deftest happy-path
  (with-app
    (fn [h {:keys [sys posts] :as ctx}]
      (let [login (start-login h ctx "/plans")
            {:keys [resp params]} login]
        (testing "login redirects to the provider with PKCE"
          (is (= 302 (:status resp)))
          (is (str/starts-with? (get-in resp [:headers "Location"]) (str issuer "/authorize?")))
          (is (= {"response_type" "code"
                  "client_id" client-id
                  "redirect_uri" "https://chequer.test/auth/callback"
                  "scope" "openid profile email"
                  "code_challenge_method" "S256"}
                 (select-keys params ["response_type" "client_id" "redirect_uri" "scope" "code_challenge_method"])))
          (is (every? #(re-matches #"[A-Za-z0-9_-]{43}" (get params %)) ["state" "nonce" "code_challenge"]))
          (is (vector? (get-in resp [:headers "Set-Cookie"])) "the adapter writes a line per vector element")
          (let [attrs (get-in (set-cookies resp) ["chequer-login" :attrs])]
            (is (str/includes? attrs "HttpOnly"))
            (is (str/includes? attrs "Secure"))
            (is (str/includes? attrs "SameSite=Lax"))))
        (let [cb (finish-login h login)
              sid (session-cookie cb)]
          (testing "the callback exchanges the code with the verifier and client secret"
            (let [[{:keys [url headers form-params]}] @posts]
              (is (= (str issuer "/token") url))
              (is (= "Basic Y2hlcXVlcjpzM2NyZXQ=" (get headers "Authorization")))
              (is (= {"grant_type" "authorization_code"
                      "code" "the-code"
                      "redirect_uri" "https://chequer.test/auth/callback"}
                     (dissoc form-params "code_verifier")))
              (is (= (get params "code_challenge")
                     (crypto/base64url (crypto/sha256 (get form-params "code_verifier"))))
                  "the verifier matches the challenge")))
          (testing "the callback starts a session and returns to the original page"
            (is (= 303 (:status cb)))
            (is (= "/plans" (get-in cb [:headers "Location"])))
            (is (re-matches #"[A-Za-z0-9_-]{43}" sid))
            (let [attrs (get-in (set-cookies cb) ["chequer-session" :attrs])]
              (is (every? #(str/includes? attrs %) ["HttpOnly" "Secure" "SameSite=Lax" "Path=/"])))
            (is (= "" (get-in (set-cookies cb) ["chequer-login" :value])) "the login cookie is cleared"))
          (testing "the user is provisioned from the claims"
            (is (= {:subject "https://idp.test|u-123" :name "alice" :title "Alice Liddell"}
                   (select-keys (db/q1 (:db sys) ["SELECT * FROM user"]) [:subject :name :title]))))
          (testing "the session cookie signs the user in"
            (let [r (request h :get "/plans" :cookies {"chequer-session" sid})]
              (is (= 200 (:status r)))
              (is (str/includes? (:body r) "Alice Liddell"))
              (is (str/includes? (:body r) "action=\"/auth/logout\""))))
          (testing "a second login by the same subject keeps the user"
            (finish-login h (start-login h ctx nil))
            (is (= 1 (count-rows (:db sys) "user")))
            (is (= 2 (count-rows (:db sys) "session")))))))))


(deftest unauthenticated-requests
  (with-app
    (fn [h _]
      (let [r (request h :get "/plans?x=1")]
        (is (= 302 (:status r)) "a page load goes to log in")
        (is (= "/auth/login?next=%2Fplans%3Fx%3D1" (get-in r [:headers "Location"]))))
      (is (= 401 (:status (request h :post "/plans"))) "an action gets a 401")
      (is (= 401 (:status (request h :get "/search/results?q=x" :headers {"datastar-request" "true"}))))
      (is (= 302 (:status (request h :get "/plans" :cookies {"chequer-session" "forged"}))) "an unknown session")
      (is (= 200 (:status (request h :get "/healthz"))) "the health check needs no session"))))


(deftest bad-state-is-rejected
  (with-app
    (fn [h {:keys [sys posts] :as ctx}]
      (let [login (start-login h ctx "/")]
        (is (= 400 (:status (finish-login h login :state "wrong"))))
        (is (= 400 (:status (finish-login h (assoc login :login-cookie "tampered.cookie")))) "a forged login cookie")
        (is (= 400 (:status (request h :get (str "/auth/callback?code=c&state=" (get-in login [:params "state"])))))
            "no login cookie")
        (is (empty? @posts) "the code is never exchanged")
        (is (= 0 (count-rows (:db sys) "session")))))))


(deftest an-expired-login-is-rejected
  (with-app
    (fn [h ctx]
      (let [login (binding [time/*now-ms* (constantly (- (time/now-ms) (* 11 60 1000)))]
                    (start-login h ctx "/"))]
        (is (= 400 (:status (finish-login h login))))))))


(defn- rejected?
  "True if a login whose ID token has claims changed by tweak is refused without a session."
  [tweak]
  (with-app tweak
    (fn [h {:keys [sys] :as ctx}]
      (let [cb (finish-login h (start-login h ctx "/"))]
        (and (= 403 (:status cb))
             (nil? (session-cookie cb))
             (= 0 (count-rows (:db sys) "session"))
             (= 0 (count-rows (:db sys) "user")))))))


(deftest bad-id-tokens-are-rejected
  (is (rejected? #(assoc % "aud" "someone-else")) "wrong aud")
  (is (rejected? #(assoc % "aud" ["someone-else" "another"])) "wrong aud list")
  (is (rejected? #(assoc % "exp" (- (quot (time/now-ms) 1000) 3600))) "expired")
  (is (rejected? #(assoc % "nonce" "not-the-nonce")) "nonce mismatch")
  (is (rejected? #(dissoc % "nonce")) "no nonce")
  (is (rejected? #(assoc % "iss" "https://evil.example")) "wrong issuer")
  (is (not (rejected? #(assoc % "aud" ["other" client-id] "azp" client-id))) "an aud list including us"))


(deftest profile-claims-come-from-userinfo
  (with-app #(dissoc % "preferred_username" "name")
    (fn [] {:status 200 :body {"sub" "u-123" "preferred_username" "alice" "name" "Alice Liddell"}})
    (fn [h {:keys [sys userinfo-requests] :as ctx}]
      (is (= 303 (:status (finish-login h (start-login h ctx "/")))))
      (is (= [{:headers {"Authorization" "Bearer at"}}] @userinfo-requests) "userinfo is fetched with the access token")
      (is (= {:subject "https://idp.test|u-123" :name "alice" :title "Alice Liddell"}
             (select-keys (db/q1 (:db sys) ["SELECT * FROM user"]) [:subject :name :title]))))))


(defn- userinfo-fails?
  "The status of a login whose userinfo endpoint answers with resp, if it starts no session."
  [resp]
  (with-app
    identity (constantly resp)
    (fn [h {:keys [sys] :as ctx}]
      (let [cb (finish-login h (start-login h ctx "/"))]
        (when (and (nil? (session-cookie cb))
                   (= 0 (count-rows (:db sys) "user")))
          (:status cb))))))


(deftest bad-userinfo-is-rejected
  (is (= 403 (userinfo-fails? {:status 200 :body {"sub" "someone-else" "preferred_username" "mallory"}}))
      "another subject")
  (is (= 502 (userinfo-fails? {:status 401 :body {"error" "invalid_token"}})) "a refused access token"))


(deftest claims-error-checks-each-claim
  (let [now (* 1000 1000000)
        claims {"iss" issuer "aud" client-id "sub" "s" "exp" 1000100 "nonce" "n"}
        opts {:issuer issuer :client-id client-id :nonce "n" :now-ms now}]
    (is (nil? (oidc/claims-error claims opts)))
    (is (= "expired" (oidc/claims-error (assoc claims "exp" 999900) opts)))
    (is (nil? (oidc/claims-error (assoc claims "exp" 999990) opts)) "within the allowed clock skew")
    (is (= "wrong authorized party" (oidc/claims-error (assoc claims "azp" "x") opts)))
    (is (= "no subject" (oidc/claims-error (dissoc claims "sub") opts)))))


(deftest open-redirects-are-blocked
  (doseq [bad ["//evil.example" "/\\evil.example" "https://evil.example" "evil" "" nil "/ok\nLocation: x"]]
    (is (= "/" (oidc/safe-path bad)) (pr-str bad)))
  (doseq [good ["/" "/plans" "/lists/9?mine=1#task-3"]]
    (is (= good (oidc/safe-path good))))
  (with-app
    (fn [h ctx]
      (let [cb (finish-login h (start-login h ctx "%2F%2Fevil.example%2Fx"))]
        (is (= 303 (:status cb)))
        (is (= "/" (get-in cb [:headers "Location"])))))))


(deftest login-cookie-signing
  (let [k (crypto/unbase64url (crypto/random-token))
        v (oidc/sign k {:state "s" :expires-at 2000})]
    (is (= {:state "s" :expires-at 2000} (oidc/unsign k v 1000)))
    (is (nil? (oidc/unsign k v 2000)) "expired")
    (is (nil? (oidc/unsign (crypto/unbase64url (crypto/random-token)) v 1000)) "another key")
    (let [[payload sig] (str/split v #"\." 2)
          forged (crypto/base64url (pr-str {:state "x" :expires-at 2000}))]
      (is (nil? (oidc/unsign k (str forged "." sig) 1000)) "a changed payload")
      (is (some? (oidc/unsign k (str payload "." sig) 1000))))))


(deftest session-key-comes-from-the-config
  (is (= (repeat 32 (unchecked-byte 0xa5))
         (seq (oidc/session-key {:session-key (str " " (apply str (repeat 32 "A5")) " ")})))
      "hex in either case, trimmed")
  (is (= 48 (count (oidc/session-key {:session-key (apply str (repeat 48 "00"))}))) "longer keys are fine")
  (is (nil? (oidc/session-key-problem {:session-key (apply str (repeat 32 "a5"))})))
  (doseq [bad [nil "" "  " (apply str (repeat 31 "a5")) (apply str (repeat 32 "zz")) "abc"]]
    (is (string? (oidc/session-key-problem {:session-key bad})) (pr-str bad))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"session-key"
          (oidc/session-key {:session-key bad}))
        (pr-str bad))))


(defn- take-frame
  "The next frame on ch, :closed if it closed, or :timeout."
  [ch]
  (let [[v port] (async/alts!! [ch (async/timeout 2000)])]
    (cond
      (not= port ch) :timeout
      (nil? v) :closed
      :else v)))


(deftest logout-ends-the-session-and-its-streams
  (with-app
    (fn [h {:keys [sys] :as ctx}]
      (let [sid (session-cookie (finish-login h (start-login h ctx "/")))
            other (session-cookie (finish-login h (start-login h ctx "/")))
            stream (:body (request h :get "/plans?datastar-sse=true" :cookies {"chequer-session" sid}))
            other-stream (:body (request h :get "/plans?datastar-sse=true" :cookies {"chequer-session" other}))]
        (try
          (is (string? (take-frame stream)) "the stream renders while signed in")
          (is (string? (take-frame other-stream)))
          (let [r (request h :post "/auth/logout" :cookies {"chequer-session" sid})]
            (is (= 303 (:status r)))
            (is (= "/auth/signed-out" (get-in r [:headers "Location"])))
            (is (= "" (session-cookie r)) "the cookie is cleared"))
          (is (= :closed (take-frame stream)) "the session's stream ends")
          (is (nil? (db/q1 (:db sys) ["SELECT 1 FROM session WHERE id = ?" sid])))
          (is (= 302 (:status (request h :get "/plans" :cookies {"chequer-session" sid}))))
          (is (= 200 (:status (request h :get "/plans" :cookies {"chequer-session" other})))
              "another session is unaffected")
          (is (= :timeout (take-frame other-stream)) "and its stream stays open")
          (finally
            (async/close! stream)
            (async/close! other-stream)))))))


(deftest auth-routes-are-404-outside-oidc-mode
  (helpers/with-test-db
    (fn [d]
      (let [sys {:db d :cfg (config/normalize {"server" {"base-url" base-url}}) :live (live/live)}
            h (core/handler sys)]
        (try
          (is (= 404 (:status (request h :get "/auth/login"))))
          (finally
            (live/stop! (:live sys))))))))
