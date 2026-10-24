(ns chequer.emoji
  "Searching the standard emoji set by name, for the icon picker. The set is read from
  resources/chequer/emoji.txt, which bin/update-emoji generates from Unicode's emoji-test.txt."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]))


(defn- words
  "The lower-case words of s, split on spaces and punctuation."
  [s]
  (into [] (remove str/blank?) (str/split (str/lower-case s) #"[\s:,.!()&’'“”-]+")))


(defn parse
  "The emoji in text (the format of emoji.txt) in order, as maps of :emoji, :name, :words
  (from the name) and :tags (from the group and subgroup)."
  [text]
  (loop [lines (str/split-lines text)
         group nil
         subgroup nil
         acc []]
    (if-let [[line & more] (seq lines)]
      (if-let [[_ g] (re-matches #"# group: (.*)" line)]
        (recur more g subgroup acc)
        (if-let [[_ sg] (re-matches #"# subgroup: (.*)" line)]
          (recur more group sg acc)
          (if-let [[_ e n] (re-matches #"([^\t#]+)\t(.+)" line)]
            (recur more group subgroup (conj acc {:emoji e
                                                  :name n
                                                  :words (words n)
                                                  :tags (into (words (or group "")) (words (or subgroup "")))}))
            (recur more group subgroup acc))))
      acc)))


(def ^:private all-emoji
  (delay (parse (slurp (io/resource "chequer/emoji.txt")))))


(defn- prefixes-all?
  "True when every term is a prefix of one of ws."
  [terms ws]
  (every? (fn [t] (some #(str/starts-with? % t) ws)) terms))


(defn- rank
  "How well entry matches query q (lower-cased and trimmed) split into terms, lower being
  better, or nil when it doesn't match: the whole name, the start of the name, every term
  starting a word of the name, or of the name and its group and subgroup."
  [q terms {n :name ws :words tags :tags}]
  (let [n (str/lower-case n)]
    (cond
      (= n q) 0
      (str/starts-with? n q) 1
      (prefixes-all? terms ws) 2
      (prefixes-all? terms (into ws tags)) 3)))


(defn search
  "Up to limit emoji, as {:emoji :name}, matching query, best first and otherwise in the
  standard order. A blank query matches the first ones."
  ([query] (search query 20))
  ([query limit] (search @all-emoji query limit))
  ([entries query limit]
   (let [q (str/trim (str/lower-case (or query "")))
         terms (words q)
         matches (if (empty? terms)
                   entries
                   (->> entries
                        (keep-indexed (fn [i e] (when-let [r (rank q terms e)] [r i e])))
                        (sort-by (juxt first second))
                        (map peek)))]
     (mapv #(select-keys % [:emoji :name]) (take limit matches)))))
