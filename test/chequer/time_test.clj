(ns chequer.time-test
  (:require
    [chequer.time :as time]
    [clojure.test :refer [deftest is]]))


;; 2025-03-03T14:05:00Z, a Monday.
(def ^:private instant-ms 1741010700000)


(deftest now-ms-can-be-pinned
  (is (integer? (time/now-ms)))
  (binding [time/*now-ms* (constantly 1000)]
    (is (= 1000 (time/now-ms)))))


(deftest format-instant-in-utc
  (is (= "Mon 3 Mar 2025, 14:05" (time/format-instant instant-ms "UTC"))))


(deftest format-instant-in-viewer-zone
  (is (= "Mon 3 Mar 2025, 09:05" (time/format-instant instant-ms "America/New_York")))
  (is (= "Mon 3 Mar 2025, 23:05" (time/format-instant instant-ms "Asia/Tokyo"))))


(deftest valid-zone
  (is (time/valid-zone? "UTC"))
  (is (time/valid-zone? "America/New_York"))
  (is (not (time/valid-zone? "Nope/Zone")) "syntactically valid but unknown region")
  (is (not (time/valid-zone? "../../etc")))
  (is (not (time/valid-zone? "")))
  (is (not (time/valid-zone? nil))))


(def ^:private minute 60000)
(def ^:private hour (* 60 minute))
(def ^:private day (* 24 hour))


(deftest relative-future
  (is (= "in 2 days" (time/relative (+ 1000 (* 2 day)) 1000)))
  (is (= "in 1 day" (time/relative (+ 1000 day) 1000)))
  (is (= "in 5 hours" (time/relative (+ 1000 (* 5 hour)) 1000)))
  (is (= "in 10 minutes" (time/relative (+ 1000 (* 10 minute)) 1000))))


(deftest relative-past
  (is (= "3 hours ago" (time/relative 1000 (+ 1000 (* 3 hour)))))
  (is (= "1 hour ago" (time/relative 1000 (+ 1000 hour))))
  (is (= "4 days ago" (time/relative 1000 (+ 1000 (* 4 day)))))
  (is (= "1 minute ago" (time/relative 1000 (+ 1000 minute)))))


(deftest relative-unit-boundaries
  (is (= "just now" (time/relative 1000 1000)))
  (is (= "just now" (time/relative (+ 1000 (dec minute)) 1000)) "just under a minute")
  (is (= "in 1 minute" (time/relative (+ 1000 minute) 1000)) "exactly a minute")
  (is (= "in 59 minutes" (time/relative (+ 1000 (dec hour)) 1000)) "just under an hour")
  (is (= "in 1 hour" (time/relative (+ 1000 hour) 1000)) "exactly an hour")
  (is (= "in 23 hours" (time/relative (+ 1000 (dec day)) 1000)) "just under a day")
  (is (= "in 1 day" (time/relative (+ 1000 day) 1000)) "exactly a day"))


(deftest local-inputs-round-trip-in-the-zone
  ;; 2026-03-01 is before the US switch to daylight time, so New York is UTC-5.
  (is (= 1772375400000 (time/parse-local-input "2026-03-01T09:30" "America/New_York")))
  (is (= 1772375400000 (time/parse-local-input "2026-03-01T14:30:00" "UTC")))
  (is (= "2026-03-01T09:30" (time/format-local-input 1772375400000 "America/New_York")))
  (is (nil? (time/parse-local-input "tomorrow" "UTC")))
  (is (nil? (time/parse-local-input "" "UTC"))))
