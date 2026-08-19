(ns swarmforge.dashboard-polish-test
  "P3 dashboard polish:  therm hash,  glow,  next action, backlog button."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [squadd.web :as web]))

(deftest pane-sample-drops-trailing-timer-line
  (is (= (web/pane-sample-for-hash "work output\nstill working\nelapsed 0:01")
         (web/pane-sample-for-hash "work output\nstill working\nelapsed 0:02"))
      "Timer-only last line does not change the hash sample")
  (is (not= (web/pane-sample-for-hash "work output\nstill working\nelapsed 0:01")
            (web/pane-sample-for-hash "work output\nchanged above\nelapsed 0:01"))
      "A real content change above the last line is visible")
  (is (= "" (web/pane-sample-for-hash "only-one-line"))
      "Empty-after-drop is idle path"))

(deftest status-bar-says-next-action
  (is (str/includes? web/dashboard-html "next action:"))
  (is (not (str/includes? web/dashboard-html "residual:"))))

(deftest card-glow-pulses-three-times
  (let [html web/dashboard-html]
    (is (re-find #"card-glow[^{]*\{[^}]*3" html)
        "Glow animation runs three times")
    (is (re-find #"(?i)card-glow[^;]{0,40}(\.?[6-9]|0\.[6-9]|1(\.0)?)s" html)
        "Each pulse is about 0.6–1.0s")))

(deftest backlog-is-top-button-not-board-lane
  (let [html web/dashboard-html]
    (is (str/includes? html "id=\"backlog-deck\""))
    (is (str/includes? html "Add Story"))
    (is (not (str/includes? html "cols=['backlog'"))
        "Backlog is not a board column")
    (is (not (str/includes? html "backlog-col"))
        "No dedicated backlog lane")))
