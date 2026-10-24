(ns chequer.config
  "Ini configuration: parsing, defaults, normalization and malli validation."
  (:require
    [clojure.string :as str]
    [malli.core :as m]
    [malli.error :as me]))


(defn parse-ini
  "Parse ini text into {section {key value}}. Blank lines and lines starting
  with ; or # are ignored; keys and values are trimmed."
  [s]
  (loop [[line & more] (str/split-lines s)
         n 1
         section nil
         result {}]
    (if (nil? line)
      result
      (let [line (str/trim line)]
        (cond
          (or (str/blank? line) (#{\; \#} (first line)))
          (recur more (inc n) section result)

          (and (str/starts-with? line "[") (str/ends-with? line "]"))
          (let [section (str/trim (subs line 1 (dec (count line))))]
            (recur more (inc n) section (update result section #(or % {}))))

          :else
          (let [i (str/index-of line "=")]
            (when (or (nil? i) (nil? section) (zero? i))
              (throw (ex-info (str "Invalid ini syntax on line " n ": " line)
                              {:line n})))
            (recur more
                   (inc n)
                   section
                   (assoc-in result
                             [section (str/trim (subs line 0 i))]
                             (str/trim (subs line (inc i)))))))))))


(defn- parse-listen
  "Parse a listen address (\":3000\", \"host:port\" or \"[ipv6]:port\") into {:host :port}."
  [s]
  (let [[_ bracketed host port] (re-matches #"(?:\[([^\]]+)\]|([^:\[\]]*)):(\d+)" s)]
    (when-not port
      (throw (ex-info (str "Invalid listen address (expected host:port): " s) {:listen s})))
    {:host (or bracketed (when-not (str/blank? host) host) "127.0.0.1")
     :port (parse-long port)}))


(def ^:private duration-units
  "Milliseconds per duration suffix."
  {"d" (* 24 60 60 1000)
   "h" (* 60 60 1000)
   "m" (* 60 1000)})


(defn- parse-duration-ms
  "Parse a duration such as 14d, 12h or 30m into milliseconds."
  [s]
  (let [[_ n unit] (re-matches #"(\d+)([dhm])" s)]
    (when-not n
      (throw (ex-info (str "Invalid duration (expected e.g. 14d, 12h, 30m): " s) {:duration s})))
    (* (parse-long n) (duration-units unit))))


(defn- url-host
  "Format host for use in a URL, bracketing IPv6 literals."
  [host]
  (if (str/includes? host ":") (str "[" host "]") host))


(defn normalize
  "Merge parsed ini data over the defaults and return the normalized config map."
  [ini]
  (let [get-ini (fn [section k default] (get-in ini [section k] default))
        {:keys [host port] :as listen} (parse-listen (get-ini "server" "listen" "127.0.0.1:3000"))]
    {:server {:listen listen
              :base-url (get-ini "server" "base-url" (str "http://" (url-host host) ":" port))
              :time-zone (get-ini "server" "time-zone" "UTC")}
     :database {:path (get-ini "database" "path" "chequer.db")}
     :auth {:mode (keyword (get-ini "auth" "mode" "single"))
            :single {:name (get-ini "auth.single" "name" "owner")
                     :title (get-ini "auth.single" "title" "Owner")}
            :proxy {:header (get-ini "auth.proxy" "header" nil)
                    :trusted-proxies (if-let [v (get-ini "auth.proxy" "trusted-proxies" nil)]
                                       (into [] (comp (map str/trim) (remove str/blank?)) (str/split v #","))
                                       ["127.0.0.1" "::1"])}
            :oidc {:issuer (get-ini "auth.oidc" "issuer" nil)
                   :client-id (get-ini "auth.oidc" "client-id" nil)
                   :client-secret (get-ini "auth.oidc" "client-secret" nil)
                   :session-key (get-ini "auth.oidc" "session-key" nil)
                   :scopes (get-ini "auth.oidc" "scopes" "openid profile email")
                   :session-ttl-ms (parse-duration-ms (get-ini "auth.oidc" "session-ttl" "14d"))}}}))


(def ^:private NonBlank
  "Malli schema for a non-empty string."
  [:string {:min 1}])


(def ^:private Server
  "Malli schema for the :server section."
  [:map
   [:listen [:map
             [:host NonBlank]
             [:port [:int {:min 1 :max 65535}]]]]
   [:base-url NonBlank]
   [:time-zone NonBlank]])


(defn- auth-schema
  "Schema for the :auth map, requiring the keys that `mode` needs."
  [mode]
  (let [required-string (fn [k] [k NonBlank])
        optional-string (fn [k] [k [:maybe :string]])]
    [:map
     [:mode [:= mode]]
     [:single [:map
               [:name NonBlank]
               [:title NonBlank]]]
     [:proxy [:map
              (if (= mode :proxy) (required-string :header) (optional-string :header))
              [:trusted-proxies [:vector NonBlank]]]]
     ;; session-key is checked when the OIDC provider starts, which explains how to make one.
     [:oidc (into [:map
                   [:session-key [:maybe :string]]
                   [:scopes NonBlank]
                   [:session-ttl-ms [:int {:min 1}]]]
                  (map (if (= mode :oidc) required-string optional-string))
                  [:issuer :client-id :client-secret])]]))


(def Config
  "Malli schema for the normalized config map."
  [:map
   [:server Server]
   [:database [:map [:path NonBlank]]]
   [:auth [:multi {:dispatch :mode}
           [:single (auth-schema :single)]
           [:proxy (auth-schema :proxy)]
           [:oidc (auth-schema :oidc)]]]])


(defn validate-config
  "Return cfg if it satisfies Config, otherwise throw ex-info with humanized :errors."
  [cfg]
  (if-let [explanation (m/explain Config cfg)]
    (let [errors (me/humanize explanation)]
      (throw (ex-info (str "Invalid configuration: " (pr-str errors)) {:errors errors})))
    cfg))


(defn load-config
  "Read the ini file at path and return the validated, normalized config."
  [path]
  (-> (slurp path) parse-ini normalize validate-config))
