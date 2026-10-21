(ns chequer.auth
  "Authentication modes and the middleware that ties each request to a user."
  (:require
    [chequer.users :as users]
    [clojure.string :as str])
  (:import
    (java.net
      InetAddress)))


(def ^:private ip-literal-re
  "Dotted-quad IPv4 or a colon-separated hex IPv6 literal; anything else could trigger a DNS lookup."
  #"\d{1,3}(?:\.\d{1,3}){3}|[0-9a-fA-F:.]*:[0-9a-fA-F:.]*")


(defn- ip-bytes
  "The address bytes of the IP literal s, or nil if s is not an IP literal."
  [s]
  (when (and (string? s) (re-matches ip-literal-re s))
    (try
      (vec (.getAddress (InetAddress/getByName s)))
      (catch Exception _ nil))))


(defn- prefix-match?
  "True if the first `bits` bits of byte vectors a and b are equal."
  [bits a b]
  (let [whole (quot bits 8)
        rest-bits (rem bits 8)
        mask (bit-and 0xff (bit-shift-left 0xff (- 8 rest-bits)))]
    (and (= (subvec a 0 whole) (subvec b 0 whole))
         (or (zero? rest-bits)
             (= (bit-and (nth a whole) mask) (bit-and (nth b whole) mask))))))


(defn- cidr-match?
  "True if the address bytes addr fall inside cidr (an IP or IP/prefix string)."
  [addr cidr]
  (let [[ip prefix] (str/split cidr #"/" 2)
        net (ip-bytes ip)]
    (and net
         (= (count net) (count addr))
         (prefix-match? (if prefix (parse-long prefix) (* 8 (count net))) addr net))))


(defn trusted?
  "True if remote-addr, an IPv4 or IPv6 literal, is inside any of cidrs (IPs or IP/prefix strings)."
  [remote-addr cidrs]
  (boolean
    (when-let [addr (ip-bytes remote-addr)]
      (some #(cidr-match? addr %) cidrs))))


(defn- forbidden
  []
  {:status 403
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body "Forbidden"})


(defn- bypass?
  "True for paths served without authentication: the health check and static files."
  [uri]
  (or (= uri "/healthz")
      (str/starts-with? uri "/js/")
      (str/starts-with? uri "/css/")))


(defn- single-mode
  "Middleware resolving every request to the configured owner. The owner is looked up once
  and then cached."
  [handler]
  (let [owner (atom nil)]
    (fn wrapper
      [{:chequer/keys [db cfg] :as req}]
      (let [single-auth (get-in cfg [:auth :single])
            owner-name (:name single-auth)
            title (:title single-auth)
            user (or @owner (reset! owner (users/ensure-user! db {:subject "owner", :name owner-name, :title title})))
            user (reset! owner (users/touch-last-seen! db user))]
        (handler (assoc req :chequer/user user))))))


(defn- proxy-mode
  "Middleware that trusts the configured header, but only from trusted proxy addresses."
  [handler]
  (fn wrapper
    [{:chequer/keys [db cfg] :keys [remote-addr headers] :as req}]
    (let [{:keys [header trusted-proxies]} (get-in cfg [:auth :proxy])
          value (some-> (get headers (str/lower-case header)) str/trim)]
      (if (and (trusted? remote-addr trusted-proxies) (not (str/blank? value)))
        (let [user (users/ensure-user! db {:subject value :name value :title value})]
          (handler (assoc req :chequer/user (users/touch-last-seen! db user))))
        (forbidden)))))


(defn wrap-auth
  "Middleware putting the current user on the request as :chequer/user, according to the
  configured auth mode. Expects :chequer/db and :chequer/cfg on the request."
  [handler auth-cfg]
  (let [auth-mode (:mode auth-cfg)
        wrapped (case auth-mode
                  :single
                  (single-mode handler)

                  :proxy
                  (proxy-mode handler)

                  ;; else
                  (throw (ex-info (str "Unknown authentication mode: " (pr-str auth-mode))
                                  {:mode auth-mode})))]
    (fn wrapper
      [{:keys [uri] :as req}]
      (if (bypass? uri)
        (handler req)
        (wrapped req)))))


(defn- origin-of
  "Normalize a URL or Origin header value to scheme://host[:port], lower-cased and without a
  default port, or nil if it isn't an http(s) origin."
  [s]
  (when-let [[_ scheme host port] (some->> s (re-matches #"(?i)(https?)://([^/:?#]+|\[[^\]]+\])(?::(\d+))?(?:[/?#].*)?"))]
    (let [scheme (str/lower-case scheme)
          default-port (if (= scheme "https") "443" "80")]
      (str scheme "://" (str/lower-case host)
           (when (and port (not= port default-port)) (str ":" port))))))


(defn- same-origin-request?
  "True if the Origin header matches base-url's origin, or, when there is no Origin header,
  Sec-Fetch-Site says same-origin."
  [base-url headers]
  (if-let [origin (get headers "origin")]
    (let [expected (origin-of base-url)]
      (and expected (= expected (origin-of origin))))
    (= "same-origin" (get headers "sec-fetch-site"))))


(defn wrap-origin-check
  "CSRF defence: requests other than GET and HEAD must come from the configured base-url's
  origin, else get a 403."
  [handler]
  (fn wrapper
    [{:chequer/keys [cfg] :keys [request-method headers] :as req}]
    (if (or (#{:get :head} request-method)
            (same-origin-request? (get-in cfg [:server :base-url]) headers))
      (handler req)
      (forbidden))))
