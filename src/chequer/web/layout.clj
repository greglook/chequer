(ns chequer.web.layout
  "The page shell shared by every full-page response, and the viewer's time zone."
  (:require
    [chequer.time :as time]
    [clojure.string :as str]
    [hiccup2.core :as h])
  (:import
    java.net.URLDecoder))


(defn- cookie-value
  "The value of cookie `cookie-name` in the request's Cookie header, URL-decoded, or nil."
  [req cookie-name]
  (some->> (str/split (get-in req [:headers "cookie"] "") #";")
           (map str/trim)
           (some (fn [pair]
                   (let [[k v] (str/split pair #"=" 2)]
                     (when (= k cookie-name) v))))
           (#(URLDecoder/decode ^String % "UTF-8"))))


(defn viewer-tz
  "The viewer's time zone id: the tz cookie if it names a known zone, else the configured one."
  [req]
  (let [tz (cookie-value req "tz")]
    (if (time/valid-zone? tz)
      tz
      (get-in req [:chequer/cfg :server :time-zone]))))


(def ^:private tz-script
  "Set the tz cookie from the browser's zone, only when it differs from the current one."
  "var tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
if (tz && document.cookie.indexOf('tz=' + encodeURIComponent(tz)) === -1) {
  document.cookie = 'tz=' + encodeURIComponent(tz) + '; path=/; max-age=31536000; SameSite=Lax';
}")


(defn page
  "Render a full HTML page as a string: the head, the navigation bar for the request's user
  and body inside [:main]."
  [req title & body]
  (str "<!DOCTYPE html>"
       (h/html
         [:html {:lang "en"}
          [:head
           [:meta {:charset "utf-8"}]
           [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
           [:title (str title " - Chequer")]
           [:link {:rel "stylesheet" :href "/css/app.css"}]
           [:script {:type "module" :src "/js/datastar.js"}]
           [:script (h/raw tz-script)]]
          [:body
           [:nav
            [:a {:href "/"} "Lists"]
            [:a {:href "/plans"} "Plans"]
            [:a {:href "/search"} "Search"]
            [:span.user (get-in req [:chequer/user :title])]]
           (into [:main] body)]])))
