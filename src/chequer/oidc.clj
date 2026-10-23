(ns chequer.oidc
  "OpenID Connect login: discovery, the authorization code flow with PKCE, and the login,
  callback, logout and signed-out pages.

  The state, nonce, PKCE verifier and return path of a login in progress travel in a
  short-lived cookie signed with the configured cookie-key. The ID token comes
  straight from the token endpoint over TLS, so its claims are checked but its signature
  isn't."
  (:require
    [chequer.crypto :as crypto]
    [chequer.sessions :as sessions]
    [chequer.time :as time]
    [chequer.users :as users]
    [chequer.web.common :as web]
    [chequer.web.layout :as layout]
    [clojure.data.json :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [jolt.http-client :as http])
  (:import
    (java.net
      URLEncoder)
    (java.util
      Base64)))


;; ## Provider

(def ^:private http-timeout-ms
  "Connect and read timeout for requests to the provider."
  10000)


(defn- http-get
  "GET url from the provider, returning {:status :body}."
  [url]
  (http/get url {:accept :json
                 :throw-exceptions false
                 :conn-timeout http-timeout-ms
                 :socket-timeout http-timeout-ms}))


(defn- http-post
  "POST form-params to url at the provider, returning {:status :body}."
  [url {:keys [headers form-params]}]
  (http/post url {:accept :json
                  :headers headers
                  :form-params form-params
                  :throw-exceptions false
                  :conn-timeout http-timeout-ms
                  :socket-timeout http-timeout-ms}))


(def ^:private min-cookie-key-bytes
  "The shortest cookie-key accepted: a 256-bit key, as HMAC-SHA256 wants."
  32)


(defn cookie-key
  "The key that signs login cookies, decoded from the hex cookie-key of the OIDC config.
  When it is missing or unusable, log how to generate one and throw."
  [oidc-cfg]
  (let [hex (some-> (:cookie-key oidc-cfg) str/trim)
        k (crypto/unhex hex)]
    (if (and k (<= min-cookie-key-bytes (count k)))
      k
      (let [problem (if (str/blank? hex)
                      "Missing [auth.oidc] cookie-key"
                      (str "Invalid [auth.oidc] cookie-key: expected at least " min-cookie-key-bytes
                           " bytes as hex"))]
        (log/error (str problem ". It signs login cookies and is required in oidc mode."
                        " Generate one with:\n\n    openssl rand -hex 32\n\n"
                        "and add it to the configuration file:\n\n"
                        "    [auth.oidc]\n    cookie-key = <the output>\n"))
        (throw (ex-info problem {:key :cookie-key}))))))


(defn- strip-trailing-slash
  [s]
  (str/replace s #"/+$" ""))


(defn provider
  "The OIDC provider for the config cfg. Throws if the config's cookie-key is missing or
  unusable. opts may replace :http-get (fn [url]) and :http-post
  (fn [url {:headers :form-params}]), which return {:status :body}."
  ([cfg] (provider cfg {}))
  ([cfg opts]
   (let [oidc-cfg (get-in cfg [:auth :oidc])]
     (merge {:issuer (:issuer oidc-cfg)
             :client-id (:client-id oidc-cfg)
             :client-secret (:client-secret oidc-cfg)
             :scopes (:scopes oidc-cfg)
             :session-ttl-ms (:session-ttl-ms oidc-cfg)
             :redirect-uri (str (strip-trailing-slash (get-in cfg [:server :base-url])) "/auth/callback")
             :key (cookie-key oidc-cfg)
             :http-get http-get
             :http-post http-post
             :discovery (atom nil)}
            (select-keys opts [:http-get :http-post])))))


(defn- read-json
  "Parse a JSON object from s, or nil if it isn't one."
  [s]
  (try
    (let [v (json/read-str s)]
      (when (map? v) v))
    (catch Exception _ nil)))


(defn discover
  "The provider's discovery document, fetched from the issuer's
  /.well-known/openid-configuration on first use and then cached. Throws if it can't be
  fetched or doesn't name the configured issuer."
  [{:keys [issuer discovery] get-fn :http-get}]
  (or @discovery
      (let [url (str (strip-trailing-slash issuer) "/.well-known/openid-configuration")
            {:keys [status body]} (get-fn url)
            doc (when (= 200 status) (read-json body))]
        (when-not (and doc (get doc "authorization_endpoint") (get doc "token_endpoint"))
          (throw (ex-info (str "OIDC discovery failed at " url " (status " status ")") {:url url :status status})))
        (when-not (= (strip-trailing-slash issuer) (some-> (get doc "issuer") strip-trailing-slash))
          (throw (ex-info (str "OIDC discovery at " url " names another issuer: " (get doc "issuer"))
                          {:url url :issuer (get doc "issuer")})))
        (reset! discovery doc))))


;; ## Login Cookie

(def ^:private login-cookie
  "The name of the cookie holding a login in progress."
  "chequer-login")


(def ^:private login-ttl-ms
  "How long a login may take, from leaving for the provider to coming back."
  (* 10 60 1000))


(defn sign
  "Encode the map m as a cookie value signed with the key k."
  [k m]
  (let [payload (crypto/base64url (pr-str m))]
    (str payload "." (crypto/base64url (crypto/hmac-sha256 k payload)))))


(defn unsign
  "The map signed into the cookie value s with the key k, or nil if s isn't one, its
  signature doesn't match, or its :expires-at is past at now."
  [k s now]
  (when-let [[_ payload sig] (and (string? s) (re-matches #"([A-Za-z0-9_-]+)\.([A-Za-z0-9_-]+)" s))]
    (when (crypto/constant-time= sig (crypto/base64url (crypto/hmac-sha256 k payload)))
      (let [m (try (edn/read-string (String. (crypto/unbase64url payload) "UTF-8"))
                   (catch Exception _ nil))]
        (when (and (map? m) (int? (:expires-at m)) (< now (:expires-at m)))
          m)))))


(defn- cookie
  "A cookie readable only over HTTPS by the server, sent on top-level navigations from
  other sites, as a return from the provider is."
  [value path max-age]
  {:value value
   :path path
   :max-age max-age
   :http-only true
   :secure true
   :same-site :lax})


;; ## Helpers

(defn safe-path
  "path if it is a path on this site, else \"/\". A redirect to anything else, such as
  //evil.example, would be an open redirect."
  [path]
  (if (and (string? path)
           (re-matches #"/[^/\\][^\\\s]*|/" path))
    path
    "/"))


(defn- form-encode
  [s]
  (URLEncoder/encode (str s) "UTF-8"))


(defn- query-string
  [params]
  (str/join "&" (map (fn [[k v]] (str (form-encode k) "=" (form-encode v))) params)))


(defn- with-query
  "url with params appended to its query."
  [url params]
  (str url (if (str/includes? url "?") "&" "?") (query-string params)))


(defn- page-response
  "A plain page, for a login that can't go on, with a link to try again."
  [req status title message]
  (web/html-response status
                     (layout/page req title
                                  [:h1 title]
                                  [:p message]
                                  [:p [:a {:href "/auth/login"} "Sign in"]])))


;; ## Login

(defn login
  "GET /auth/login?next=/path: send the browser to the provider to sign in, remembering the
  state, nonce, PKCE verifier and where to return in a signed cookie."
  [{:chequer/keys [oidc] :as req}]
  (let [doc (try
              (discover oidc)
              (catch Exception e
                (log/error (ex-message e))
                nil))]
    (if-not doc
      (page-response req 502 "Sign-in unavailable" "The sign-in provider can't be reached right now.")
      (let [state (crypto/random-token)
            nonce (crypto/random-token)
            verifier (crypto/random-token)
            pending {:state state
                     :nonce nonce
                     :verifier verifier
                     :next (safe-path (web/query-param req "next"))
                     :expires-at (+ (time/now-ms) login-ttl-ms)}]
        {:status 302
         :headers {"Location" (with-query (get doc "authorization_endpoint")
                                [["response_type" "code"]
                                 ["client_id" (:client-id oidc)]
                                 ["redirect_uri" (:redirect-uri oidc)]
                                 ["scope" (:scopes oidc)]
                                 ["state" state]
                                 ["nonce" nonce]
                                 ["code_challenge" (crypto/base64url (crypto/sha256 verifier))]
                                 ["code_challenge_method" "S256"]])
                   "Cache-Control" "no-store"}
         :cookies {login-cookie (cookie (sign (:key oidc) pending) "/auth" (quot login-ttl-ms 1000))}}))))


;; ## Callback

(defn- basic-auth
  "The Authorization header for client credentials, form-encoded first as RFC 6749 asks."
  [client-id client-secret]
  (str "Basic " (.encodeToString (Base64/getEncoder)
                                 (.getBytes (str (form-encode client-id) ":" (form-encode client-secret))
                                            "UTF-8"))))


(defn- exchange-code
  "Exchange the authorization code for tokens at the token endpoint and return the ID token.
  Throws if the endpoint refuses or answers without one."
  [{:keys [client-id client-secret redirect-uri] post-fn :http-post} doc code verifier]
  (let [auth-methods (get doc "token_endpoint_auth_methods_supported" ["client_secret_basic"])
        basic? (some #{"client_secret_basic"} auth-methods)
        form (cond-> {"grant_type" "authorization_code"
                      "code" code
                      "redirect_uri" redirect-uri
                      "code_verifier" verifier}
               (not basic?) (assoc "client_id" client-id "client_secret" client-secret))
        {:keys [status body]} (post-fn (get doc "token_endpoint")
                                       {:headers (when basic? {"Authorization" (basic-auth client-id client-secret)})
                                        :form-params form})
        id-token (when (= 200 status) (get (read-json body) "id_token"))]
    (when-not (string? id-token)
      (throw (ex-info (str "Token endpoint refused the code (status " status ")") {:status status :body body})))
    id-token))


(defn decode-claims
  "The claims of a JWT, decoded without checking its signature, or nil if it isn't a JWT."
  [jwt]
  (when-let [[_ payload] (and (string? jwt) (re-matches #"[A-Za-z0-9_-]+\.([A-Za-z0-9_-]+)\.[A-Za-z0-9_-]*" jwt))]
    (some-> (crypto/unbase64url payload) (String. "UTF-8") read-json)))


(def ^:private clock-skew-s
  "How far past its expiry an ID token is still accepted, for clocks that disagree."
  60)


(defn claims-error
  "Why the ID token claims can't be accepted, or nil if they can: they must come from issuer,
  be meant for client-id, not have expired at now-ms, and carry nonce."
  [claims {:keys [issuer client-id nonce now-ms]}]
  (let [aud (get claims "aud")
        auds (if (string? aud) [aud] aud)
        azp (get claims "azp")
        exp (get claims "exp")]
    (cond
      (not= (strip-trailing-slash issuer) (some-> (get claims "iss") str strip-trailing-slash)) "wrong issuer"
      (not (and (sequential? auds) (some #{client-id} auds))) "wrong audience"
      (and (some? azp) (not= client-id azp)) "wrong authorized party"
      (not (number? exp)) "no expiry"
      (<= (+ exp clock-skew-s) (quot now-ms 1000)) "expired"
      (not (crypto/constant-time= nonce (get claims "nonce"))) "nonce mismatch"
      (str/blank? (str (get claims "sub"))) "no subject")))


(defn- claims->user
  "The user attributes for ID token claims."
  [claims]
  (let [username (or (not-empty (str/trim (str (get claims "preferred_username" ""))))
                     (str (get claims "sub")))]
    {:subject (str (get claims "iss") "|" (get claims "sub"))
     :name username
     :title (or (not-empty (str/trim (str (get claims "name" "")))) username)}))


(defn callback
  "GET /auth/callback: finish a login. Check the state against the login cookie, exchange
  the code, check the ID token's claims, provision the user, start a session and return to
  where the login began."
  [{:chequer/keys [db oidc] :as req}]
  (let [now (time/now-ms)
        pending (unsign (:key oidc) (get-in req [:cookies login-cookie :value]) now)
        error (web/query-param req "error")
        code (web/query-param req "code")
        failed (fn [status message]
                 (assoc (page-response req status "Sign-in failed" message)
                        :cookies {login-cookie (cookie "" "/auth" 0)}))]
    (cond
      (nil? pending)
      (failed 400 "This sign-in expired or was started in another browser. Please try again.")

      (not (crypto/constant-time= (:state pending) (web/query-param req "state")))
      (failed 400 "This sign-in doesn't match the one this browser started. Please try again.")

      error
      (failed 403 (str "The sign-in provider refused: " error))

      (str/blank? code)
      (failed 400 "The sign-in provider didn't return a code.")

      :else
      (let [claims (try
                     (decode-claims (exchange-code oidc (discover oidc) code (:verifier pending)))
                     (catch Exception e
                       (log/error (str "OIDC code exchange failed: " (ex-message e)))
                       ::unavailable))]
        (if (= ::unavailable claims)
          (failed 502 "The sign-in provider couldn't complete the sign-in.")
          (if-let [problem (if claims
                             (claims-error claims {:issuer (:issuer oidc)
                                                   :client-id (:client-id oidc)
                                                   :nonce (:nonce pending)
                                                   :now-ms now})
                             "malformed ID token")]
            (do (log/warn (str "Rejected OIDC ID token: " problem))
                (failed 403 "The sign-in couldn't be verified."))
            (let [user (users/ensure-user! db (claims->user claims))
                  session (sessions/create-session! db (:id user) (:session-ttl-ms oidc))]
              {:status 303
               :headers {"Location" (safe-path (:next pending))
                         "Cache-Control" "no-store"}
               :cookies {login-cookie (cookie "" "/auth" 0)
                         sessions/cookie-name (cookie (:session/id session) "/" (quot (:session-ttl-ms oidc) 1000))}})))))))


;; ## Logout

(defn logout
  "POST /auth/logout: end the session and clear its cookie. Touching the session's topic
  re-renders its open streams, which then fail auth and end."
  [{:chequer/keys [db] :as req}]
  (let [sid (get-in req [:cookies sessions/cookie-name :value])]
    (when sid
      (sessions/delete! db sid))
    (with-meta {:status 303
                :headers {"Location" "/auth/signed-out"}
                :cookies {sessions/cookie-name (cookie "" "/" 0)}}
      {:topics (when sid #{[:session sid]})})))


(defn signed-out
  "GET /auth/signed-out: the page shown after signing out."
  [req]
  (web/html-response 200
                     (layout/page req "Signed out"
                                  [:h1 "Signed out"]
                                  [:p "You have signed out of Chequer."]
                                  [:p [:a {:href "/auth/login"} "Sign in again"]])))


(defn- when-oidc
  "Wrap handler so it answers only when OIDC is configured, and is a 404 otherwise."
  [handler]
  (fn [req]
    (if (:chequer/oidc req)
      (handler req)
      (web/text-response 404 "Not found"))))


(def routes
  "OIDC routes as [method pattern handler]."
  [[:get ["auth" "login"] (when-oidc login)]
   [:get ["auth" "callback"] (when-oidc callback)]
   [:post ["auth" "logout"] (when-oidc logout)]
   [:get ["auth" "signed-out"] (when-oidc signed-out)]])
