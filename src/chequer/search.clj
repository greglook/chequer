(ns chequer.search
  "Full-text search over plans, plan tasks, lists and tasks, using the FTS5 `search` table
  that migration 004's triggers keep in sync."
  (:require
    [chequer.db :as db]
    [clojure.string :as str]
    [jdbc.core :as jdbc]))


;; ## Index

(def ^:private indexed
  "Each indexed kind as [kind rowid-code table owner-kind owner-column]: a row's rowid in
  the index is id * 4 + its code. Mirrors migration 004."
  [["plan" 0 "plan" "plan" "id"]
   ["plan.task" 1 "plan_task" "plan" "plan_id"]
   ["list" 2 "list" "list" "id"]
   ["task" 3 "task" "list" "list_id"]])


(defn rebuild-search!
  "Recover from a broken index: empty it and index every plan, plan task, list and task
  again, in one transaction."
  [d]
  (db/with-tx [tx d]
    (jdbc/execute! tx "DELETE FROM search")
    (doseq [[kind code table owner-kind owner-col] indexed]
      (jdbc/execute! tx [(str "INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id) "
                              "SELECT id * 4 + " code ", title, description, ?, id, ?, " owner-col " FROM " table)
                         kind owner-kind]))))


;; ## Queries

(defn fts-query
  "An FTS5 query matching every word of the user's input s, the last one as a prefix, or nil
  when s has no words. Each word is quoted, so FTS operators and punctuation in the input
  are only ever text; words without a letter or digit, which match nothing, are dropped."
  [s]
  (let [words (filter #(re-find #"[\p{L}\p{N}]" %) (str/split (str/trim (or s "")) #"\s+"))]
    (when (seq words)
      (str (str/join " " (map #(str "\"" (str/replace % "\"" "\"\"") "\"") words)) "*"))))


(def ^:private archived-penalty
  "Added to the rank of results in archived lists, to put them after every active result.
  bm25 ranks are small negative numbers, so better matches sort first."
  1000)


(def ^:private mark-open
  "Private-use characters FTS5 wraps highlighted text in, replaced by <mark> tags only after
  the text has been escaped."
  "")


(def ^:private mark-close
  "")


(def ^:private snippet-tokens
  "Tokens in a description snippet."
  16)


(defn- escape-html
  [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))


(defn highlight-html
  "FTS5 output s, with matches between the mark characters, as HTML: the text escaped,
  then the marks turned into <mark> tags. Mark characters typed by users can only add
  <mark> tags, never other markup."
  [s]
  (when s
    (-> (escape-html s)
        (str/replace mark-open "<mark>")
        (str/replace mark-close "</mark>"))))


(defn- row->result
  [r]
  {:search/kind (:kind r)
   :search/entity-id (:entity_id r)
   :search/owner-kind (:owner_kind r)
   :search/owner-id (:owner_id r)
   :search/owner-title (:owner_title r)
   :search/title-html (highlight-html (:title r))
   :search/snippet-html (highlight-html (:snippet r))
   :search/archived? (= 1 (:archived r))})


(defn search
  "Up to :limit (default 50) results for the user's input s, best first: titles weigh more
  than descriptions, and results in archived lists come after the rest. Each has its kind,
  entity id, owning plan or list, and its title and description snippet as HTML with the
  matches in <mark> tags."
  ([d s] (search d s {}))
  ([d s {:keys [limit] :or {limit 50}}]
   (if-let [query (fts-query s)]
     (mapv row->result
           (db/q d [(str "SELECT search.kind, search.entity_id, search.owner_kind, search.owner_id, "
                         "highlight(search, 0, ?, ?) AS title, "
                         "snippet(search, 1, ?, ?, '…', " snippet-tokens ") AS snippet, "
                         "COALESCE(p.title, l.title) AS owner_title, "
                         "l.archived_at IS NOT NULL AS archived "
                         "FROM search "
                         "LEFT JOIN plan p ON search.owner_kind = 'plan' AND p.id = search.owner_id "
                         "LEFT JOIN list l ON search.owner_kind = 'list' AND l.id = search.owner_id "
                         "WHERE search MATCH ? "
                         "ORDER BY bm25(search, 10.0, 1.0)"
                         " + CASE WHEN l.archived_at IS NOT NULL THEN " archived-penalty " ELSE 0 END, "
                         "search.rowid "
                         "LIMIT ?")
                    mark-open mark-close mark-open mark-close query limit]))
     [])))
