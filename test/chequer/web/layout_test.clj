(ns chequer.web.layout-test
  (:require
    [chequer.config :as config]
    [chequer.web.layout :as layout]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]))


(def ^:private cfg
  (config/normalize {"server" {"time-zone" "Europe/Paris"}}))


(defn- req
  "A request; cookies is a map of name to value, as ring's wrap-cookies leaves in :cookies."
  [& {:keys [cookies user] :or {user {:id 1 :name "greg" :title "Greg"}}}]
  (cond-> {:request-method :get
           :uri "/"
           :headers {}
           :chequer/cfg cfg
           :chequer/user user}
    cookies (assoc :cookies (update-vals cookies (fn [v] {:value v})))))


(deftest viewer-tz-reads-the-cookie
  (is (= "America/New_York" (layout/viewer-tz (req :cookies {"tz" "America/New_York"}))))
  (is (= "Asia/Tokyo" (layout/viewer-tz (req :cookies {"a" "b" "tz" "Asia/Tokyo" "c" "d"})))))


(deftest viewer-tz-falls-back-to-config
  (is (= "Europe/Paris" (layout/viewer-tz (req))) "no cookie")
  (is (= "Europe/Paris" (layout/viewer-tz (req :cookies {"other" "1"}))) "no tz cookie")
  (is (= "Europe/Paris" (layout/viewer-tz (req :cookies {"tz" "Nope/Zone"}))) "unknown zone")
  (is (= "Europe/Paris" (layout/viewer-tz (req :cookies {"tz" "../../etc"}))) "malformed zone")
  (is (= "Europe/Paris" (layout/viewer-tz (req :cookies {"tz" ""}))) "empty zone"))


(deftest page-renders-the-shell
  (let [html (layout/page (req) "Lists" [:h1 "Hello"])]
    (is (str/starts-with? html "<!DOCTYPE html>"))
    (is (str/includes? html "<title>Lists - Chequer</title>"))
    (is (str/includes? html "/js/datastar.js"))
    (is (str/includes? html "/css/app.css"))
    (is (str/includes? html "<main><h1>Hello</h1></main>"))
    (doseq [[href label] [["/" "Lists"] ["/plans" "Plans"] ["/search" "Search"]]]
      (is (str/includes? html (str "<a href=\"" href "\">" label "</a>")) label))
    (is (str/includes? html "Greg"))
    (is (str/includes? html "Intl.DateTimeFormat"))))


(deftest page-escapes-user-text
  (let [html (layout/page (req :user {:id 2 :name "x" :title "<script>alert(1)</script>"}) "T<b>" [:p "ok"])]
    (is (not (str/includes? html "<script>alert(1)")))
    (is (str/includes? html "&lt;script&gt;alert(1)"))
    (is (str/includes? html "T&lt;b&gt;"))))
