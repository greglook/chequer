(ns chequer.web.emoji
  "The emoji search behind the icon picker."
  (:require
    [chequer.emoji :as emoji]
    [chequer.web.common :as web :refer [html raw]]
    [jolt.datastar.core :as ds]))


(def page-size
  "How many emoji a page of the picker's results shows: four rows of five."
  20)


(def max-pages
  "How many pages of results the picker shows at most."
  5)


(def ^:private prev-icon
  (raw (str "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
            "<path d=\"M15 6l-6 6 6 6\"/></svg>")))


(def ^:private next-icon
  (raw (str "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
            "<path d=\"M9 6l6 6-6 6\"/></svg>")))


(defn- render-pager
  "The picker's buttons for paging through n pages of results, on the first."
  [n]
  [:div.emoji-pager
   [:button.icon-button.prev {:type "button" :title "Previous page" :aria-label "Previous page" :disabled true}
    prev-icon]
   [:span.dots {:aria-hidden "true"}
    (for [i (range n)]
      [:span (when (zero? i) {:class "current"})])]
   [:button.icon-button.next {:type "button" :title "Next page" :aria-label "Next page"}
    next-icon]])


(defn render-results
  "#icon-results: a button for each emoji matching q, in pages which scroll sideways, and
  the buttons to page through them when there's more than one."
  [q]
  [:div#icon-results.emoji-results
   (if-let [matches (seq (emoji/search q (* page-size max-pages)))]
     (let [pages (partition-all page-size matches)]
       (list
         [:div.emoji-pages
          (for [page pages]
            [:div.emoji-page
             (for [{e :emoji n :name} page]
               [:button {:type "button" :title n :aria-label n :data-emoji e} e])])]
         (when (next pages)
           (render-pager (count pages)))))
     [:p.none "No matching emoji."])])


(defn results
  "GET /emoji?q=: the picker's results for q, as an SSE patch of #icon-results."
  [req]
  (web/sse-response [(ds/patch-elements-event (html (render-results (web/query-param req "q"))) nil "outer")]))


(def routes
  "Emoji routes as [method pattern handler]."
  [[:get ["emoji"] results]])
