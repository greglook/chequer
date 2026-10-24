(ns chequer.emoji-test
  (:require
    [chequer.data.plans :as plans]
    [chequer.emoji :as emoji]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]))


(def ^:private sample
  "# Version: 18.0
# group: Smileys & Emotion
# subgroup: face-smiling
😀\tgrinning face
😆\tgrinning squinting face
# group: Food & Drink
# subgroup: food-fruit
🍇\tgrapes
🍊\ttangerine
# subgroup: food-prepared
🍞\tbread
# group: Flags
# subgroup: country-flag
🇨🇮\tflag: Côte d’Ivoire
")


(defn- names
  [entries q]
  (mapv :name (emoji/search entries q 20)))


(deftest parse-reads-emoji-with-their-group-and-subgroup
  (let [[grinning :as entries] (emoji/parse sample)]
    (is (= 6 (count entries)))
    (is (= {:emoji "😀" :name "grinning face" :words ["grinning" "face"]
            :tags ["smileys" "emotion" "face" "smiling"]}
           grinning))
    (is (= ["flag" "côte" "d" "ivoire"] (:words (peek entries))))))


(deftest search-matches-word-prefixes
  (let [entries (emoji/parse sample)]
    (testing "a blank query lists the first ones in order"
      (is (= ["grinning face" "grinning squinting face"] (take 2 (names entries "  ")))))
    (testing "every term must start a word"
      (is (= ["grinning squinting face"] (names entries "squint gri")))
      (is (= [] (names entries "rinning"))))
    (testing "case and surrounding space don't matter"
      (is (= ["grapes"] (names entries " GRAPE "))))
    (testing "the group and subgroup match after the name"
      (is (= ["grapes" "tangerine" "bread"] (names entries "food")))
      (is (= ["grapes" "tangerine"] (names entries "fruit"))))
    (testing "a whole-name match ranks first, then one at the start of the name"
      (is (= ["grinning face" "grinning squinting face"] (names entries "grinning face")))
      (is (= ["grinning squinting face"] (names entries "grinning squinting face")))
      (is (= ["grinning squinting face" "grinning face"] (names entries "grinning s")))
      (is (= ["grinning face" "grinning squinting face"] (names entries "face grinning"))))
    (testing "the result is limited"
      (is (= 2 (count (emoji/search entries "f" 2)))))))


(deftest the-bundled-set-is-searchable
  (is (= {:emoji "🍇" :name "grapes"} (first (emoji/search "grapes"))))
  (is (= 20 (count (emoji/search ""))))
  (is (<= (count (emoji/search "face")) 20))
  (is (= [] (emoji/search "zzzqqq"))))


(deftest every-bundled-emoji-is-a-valid-icon
  (let [entries (emoji/parse (slurp (io/resource "chequer/emoji.txt")))]
    (is (< 1500 (count entries)))
    (is (= [] (remove #(= {:value (:emoji %)} (plans/validate-icon (:emoji %))) entries)))))
