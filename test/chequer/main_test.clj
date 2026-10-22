(ns chequer.main-test
  (:require
    [chequer.main :as main]
    [clojure.test :refer [deftest is]]))


(deftest parse-args-subcommands
  (is (= {:command :server} (main/parse-args ["server"])))
  (is (= {:command :migrate} (main/parse-args ["migrate"])))
  (is (= {:command :backup :dest "out.db"} (main/parse-args ["backup" "out.db"]))))


(deftest parse-args-config-option
  (is (= {:command :server :config "a.ini"} (main/parse-args ["-c" "a.ini" "server"])))
  (is (= {:command :server :config "b.ini"} (main/parse-args ["server" "--config=b.ini"])))
  (is (= {:command :backup :config "a.ini" :dest "out.db"}
         (main/parse-args ["backup" "out.db" "-c" "a.ini"]))))


(deftest parse-args-help
  (is (string? (:help (main/parse-args ["-h"]))))
  (is (string? (:help (main/parse-args ["server" "-h"]))))
  (is (re-find #"backup" (:help (main/parse-args ["-h"])))))


(deftest parse-args-errors
  (is (seq (:errors (main/parse-args []))) "a subcommand is required")
  (is (seq (:errors (main/parse-args ["-c" "a.ini"]))))
  (is (seq (:errors (main/parse-args ["bogus"]))))
  (is (seq (:errors (main/parse-args ["--bogus" "server"]))))
  (is (seq (:errors (main/parse-args ["server" "stray"]))))
  (is (seq (:errors (main/parse-args ["migrate" "stray"]))))
  (is (seq (:errors (main/parse-args ["backup"]))) "backup needs a destination")
  (is (seq (:errors (main/parse-args ["backup" "a" "b"])))))
