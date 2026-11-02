(ns chequer.web.emoji
  "The emoji search behind the icon picker."
  (:require
    [chequer.emoji :as emoji]
    [chequer.web.common :as web :refer [html]]
    [jolt.datastar.core :as ds]))


(defn render-results
  "#icon-results: a button for each emoji matching q."
  [q]
  [:div#icon-results.emoji-results
   (if-let [matches (seq (emoji/search q))]
     (for [{e :emoji n :name} matches]
       [:button {:type "button" :title n :aria-label n :data-emoji e} e])
     [:p.none "No matching emoji."])])


(defn results
  "GET /emoji?q=: the picker's results for q, as an SSE patch of #icon-results."
  [req]
  (web/sse-response [(ds/patch-elements-event (html (render-results (web/query-param req "q"))) nil "outer")]))


(def routes
  "Emoji routes as [method pattern handler]."
  [[:get ["emoji"] results]])
