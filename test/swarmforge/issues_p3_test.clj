(ns swarmforge.issues-p3-test
  "P3 dashboard polish: B95 therm hash, B93 glow, B92 next action, B102 backlog button."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [squadd.web :as web]))

(deftest b95-pane-sample-drops-trailing-timer-line
  (is (= (web/pane-sample-for-hash "work output\nstill working\nelapsed 0:01")
         (web/pane-sample-for-hash "work output\nstill working\nelapsed 0:02"))
      "B95: timer-only last line does not change the hash sample")
  (is (not= (web/pane-sample-for-hash "work output\nstill working\nelapsed 0:01")
            (web/pane-sample-for-hash "work output\nchanged above\nelapsed 0:01"))
      "B95: a real content change above the last line is visible")
  (is (= "" (web/pane-sample-for-hash "only-one-line"))
      "B95: empty-after-drop is idle path"))

(deftest b92-status-bar-says-next-action
  (is (str/includes? web/dashboard-html "next action:"))
  (is (not (str/includes? web/dashboard-html "residual:"))))

(deftest b93-card-glow-pulses-three-times
  (let [html web/dashboard-html]
    (is (re-find #"card-glow[^{]*\{[^}]*3" html)
        "B93: glow animation runs three times")
    (is (re-find #"(?i)card-glow[^;]{0,40}(\.?[6-9]|0\.[6-9]|1(\.0)?)s" html)
        "B93: each pulse is about 0.6–1.0s")))

(deftest b102-backlog-is-top-button-not-board-lane
  (let [html web/dashboard-html]
    (is (str/includes? html "id=\"backlog-deck\""))
    (is (str/includes? html "Add Story"))
    (is (not (str/includes? html "cols=['backlog'"))
        "B102: backlog is not a board column")
    (is (not (str/includes? html "backlog-col"))
        "B102: no dedicated backlog lane")))
