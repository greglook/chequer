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
