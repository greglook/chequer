(ns chequer.web.layout
  "The page shell shared by every full-page response, and the viewer's time zone."
  (:require
    [chequer.time :as time]
    [chequer.web.common :as web]
    [clojure.string :as str]
    [jolt.datastar.html :as h]))


(defn viewer-tz
  "The viewer's time zone id: the tz cookie if it names a known zone, else the configured one.
  Expects the request to have passed through ring's wrap-cookies."
  [req]
  (let [tz (get-in req [:cookies "tz" :value])]
    (if (time/valid-zone? tz)
      tz
      (get-in req [:chequer/cfg :server :time-zone]))))


(def ^:private tz-script
  "Set the tz cookie from the browser's zone, only when it differs from the current one."
  "var tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
if (tz && document.cookie.indexOf('tz=' + encodeURIComponent(tz)) === -1) {
  document.cookie = 'tz=' + encodeURIComponent(tz) + '; path=/; max-age=31536000; SameSite=Lax';
}")


(def ^:private theme-script
  "Apply the theme saved in this browser before the page paints, and define the toggle
  that flips between light and dark and saves the choice. Without a saved theme the page
  follows the system's preference."
  "(function () {
  var t;
  try { t = localStorage.getItem('theme'); } catch (e) {}
  if (t === 'light' || t === 'dark') document.documentElement.dataset.theme = t;
})();
function toggleTheme() {
  var root = document.documentElement;
  var dark = root.dataset.theme
    ? root.dataset.theme === 'dark'
    : window.matchMedia('(prefers-color-scheme: dark)').matches;
  var t = dark ? 'light' : 'dark';
  root.dataset.theme = t;
  try { localStorage.setItem('theme', t); } catch (e) {}
}")


(def ^:private theme-choices
  "Moon and sun icons, each with a label, for the theme toggle; the stylesheet shows the
  one for the theme a click switches to."
  (list [:span.moon
         (h/raw (str "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
                     "<path d=\"M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z\"/></svg>"))
         "Dark mode"]
        [:span.sun
         (h/raw (str "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
                     "<circle cx=\"12\" cy=\"12\" r=\"4\"/>"
                     "<path d=\"M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4\"/>"
                     "</svg>"))
         "Light mode"]))


(defn- account-menu
  "The signed-in user's name and badge, opening a menu with the theme toggle and, for a
  session sign-in, signing out."
  [req user]
  [:details.menu.account {:name "more-menu"}
   [:summary {:title "Account menu" :aria-label "Account menu"}
    [:span.name (:title user)]
    (web/user-badge user)]
   [:div.menu-items
    [:button.theme-toggle {:type "button" :onclick "toggleTheme()"} theme-choices]
    (when (:chequer/session req)
      [:form.sign-out {:method "post" :action "/auth/logout"}
       [:button {:type "submit"} "Sign out"]])]])


(def ^:private search-icon
  "Magnifying glass icon for the search link."
  (h/raw (str "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
              "<circle cx=\"11\" cy=\"11\" r=\"7\"/><path d=\"M20 20l-4-4\"/></svg>")))


(def ^:private sections
  "Navigation links as [href label uri-prefixes icon?]: a link is current when the request's
  uri starts with one of its prefixes. A link with an icon shows it in place of the label."
  [["/" "Lists" ["/lists" "/tasks"]]
   ["/plans" "Plans" ["/plans"]]
   ["/search" "Search" ["/search"] search-icon]])


(defn- nav-link
  [uri [href label prefixes icon]]
  (let [current? (or (= uri href) (some #(str/starts-with? uri %) prefixes))]
    [:a (cond-> {:href href}
          icon (assoc :class "icon" :title label :aria-label label)
          current? (assoc :aria-current "page"))
     (or icon label)]))


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
           [:script (h/raw theme-script)]
           [:link {:rel "stylesheet" :href "/css/app.css"}]
           [:script {:type "module" :src "/js/datastar.js"}]
           [:script {:src "/js/drag.js" :defer true}]
           [:script (h/raw tz-script)]]
          [:body
           [:header.site
            [:nav
             [:a.brand {:href "/"} "Chequer"]
             [:div.links (map #(nav-link (:uri req) %) sections)]
             (if-let [user (:chequer/user req)]
               (account-menu req user)
               [:button.theme-toggle.solo {:type "button" :onclick "toggleTheme()"
                                           :title "Toggle dark mode" :aria-label "Toggle dark mode"}
                theme-choices])]]
           [:main body]]])))
