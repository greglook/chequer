(ns chequer.time
  "Clocks, time zones and formatting of instants (epoch milliseconds)."
  (:require
    [tick.core :as t])
  (:import
    (java.time
      ZoneId)))


(def ^:dynamic *now-ms*
  "Function returning the current time in epoch milliseconds; rebind in tests to pin the clock."
  #(System/currentTimeMillis))


(defn now-ms
  "The current time in epoch milliseconds."
  []
  (*now-ms*))


(defn valid-zone?
  "True if zone-id names a known time zone. tick.core/zone accepts any syntactically valid
  region, so the id is checked against the zones the runtime actually knows."
  [zone-id]
  (boolean (and (string? zone-id)
                (.contains (ZoneId/getAvailableZoneIds) zone-id))))


(def ^:private instant-formatter
  (t/formatter "EEE d MMM yyyy, HH:mm"))


(defn format-instant
  "Format epoch milliseconds ms in time zone tz (an id such as \"UTC\"), e.g. \"Mon 3 Mar 2025, 14:05\"."
  [ms tz]
  (t/format instant-formatter (t/in (t/instant ms) tz)))


(def ^:private minute-ms 60000)
(def ^:private hour-ms (* 60 minute-ms))
(def ^:private day-ms (* 24 hour-ms))


(defn relative
  "Describe the instant ms relative to now, e.g. \"in 2 days\" or \"3 hours ago\"."
  [ms now]
  (let [diff (- ms now)
        span (abs diff)
        [n unit] (cond
                   (>= span day-ms) [(quot span day-ms) "day"]
                   (>= span hour-ms) [(quot span hour-ms) "hour"]
                   (>= span minute-ms) [(quot span minute-ms) "minute"])]
    (cond
      (nil? n) "just now"
      (neg? diff) (str n " " unit (when (> n 1) "s") " ago")
      :else (str "in " n " " unit (when (> n 1) "s")))))


(def ^:private input-formatter
  (t/formatter "yyyy-MM-dd'T'HH:mm"))


(defn parse-local-input
  "Epoch milliseconds for the value s of a datetime-local input (\"2026-03-01T09:30\", seconds
  optional) read in time zone tz, or nil if s isn't one."
  [s tz]
  (try
    (inst-ms (t/instant (t/in (t/date-time s) tz)))
    (catch Exception _ nil)))


(defn format-local-input
  "The datetime-local input value for epoch milliseconds ms in time zone tz."
  [ms tz]
  (t/format input-formatter (t/in (t/instant ms) tz)))
