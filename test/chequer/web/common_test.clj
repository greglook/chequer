(ns chequer.web.common-test
  (:require
    [chequer.web.common :as web]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]))


(defn- badge
  [user]
  (web/html (web/user-badge user)))


(defn- hue
  [html]
  (some-> (re-find #"hsl\((\d+) " html) second parse-long))


(deftest user-badge-shows-the-capitalized-initial
  (let [html (badge {:name "greg" :title "greg Look"})]
    (is (str/includes? html ">G</span>"))
    (is (str/includes? html "title=\"greg Look\""))))


(deftest user-badge-color-is-stable-per-name
  (let [h (hue (badge {:name "greg" :title "Greg"}))]
    (is (<= 0 h 359))
    (is (= h (hue (badge {:name "greg" :title "Gregory"}))) "the title doesn't change it")
    (is (not= h (hue (badge {:name "sam" :title "Sam"}))))))


(deftest user-badge-handles-missing-titles
  (is (str/includes? (badge {:name "sam" :title ""}) ">S</span>"))
  (is (str/includes? (badge {:name nil :title nil}) ">?</span>")))


(deftest slugs-are-lowercase-words-joined-by-dashes
  (is (= "things-to-fix" (web/slug "Things to Fix")))
  (is (= "c-3po-s-list" (web/slug "  C-3PO's   list! ")))
  (is (= "" (web/slug "🛒")))
  (is (= "/lists/5-things-to-fix" (web/list-path 5 "Things to Fix")))
  (is (= "/plans/3" (web/plan-path 3 "🧳")) "a title without a slug leaves the bare id"))


(deftest canonical-redirect-keeps-the-query
  (is (nil? (web/canonical-redirect {:uri "/lists/5-a"} "/lists/5-a")))
  (is (= {:status 302 :headers {"Location" "/lists/5-a"}}
         (web/canonical-redirect {:uri "/lists/5"} "/lists/5-a")))
  (is (= "/lists/5-a?mine=1"
         (get-in (web/canonical-redirect {:uri "/lists/5-b" :query-string "mine=1"} "/lists/5-a")
                 [:headers "Location"]))))
