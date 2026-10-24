(ns chequer.web.emoji
  "The emoji search behind the icon picker."
  (:require
    [chequer.emoji :as emoji]
    [chequer.web.common :as web :refer [html]]
    [jolt.datastar.core :as ds]))


(defn render-results
  "The results element target: a button for each emoji matching q."
  [target q]
  [:div.emoji-results {:id target}
   (if-let [matches (seq (emoji/search q))]
     (for [{e :emoji n :name} matches]
       [:button {:type "button" :title n :aria-label n :data-emoji e} e])
     [:p.none "No matching emoji."])])


(defn results
  "GET /emoji?target=&q=: the picker's results element for q, as an SSE patch. target is
  the id of the element to replace."
  [req]
  (let [target (web/query-param req "target")]
    (if (and target (re-matches #"icon-results-[A-Za-z0-9]{1,40}" target))
      (web/sse-response [(ds/patch-elements-event
                           (html (render-results target (web/query-param req "q")))
                           nil "outer")])
      (web/text-response 400 "Bad request"))))


(def routes
  "Emoji routes as [method pattern handler]."
  [[:get ["emoji"] results]])
