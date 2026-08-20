(ns swarmforge.squad-retire-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [squad-retire :as retire]))

(deftest session-target-is-exact
  (is (= "=swarmforge-implementer-001"
         (retire/session-target "swarmforge-implementer-001"))))

(deftest capture-pane-uses-exact-pane-target
  ;; Given a tmux session name
  ;; When retire captures the pane
  ;; Then -t is =session: (a pane). =session is a session target and tmux
  ;; reports "can't find pane".
  (let [seen (atom nil)]
    (with-redefs [retire/run-continue
                  (fn [& args]
                    (reset! seen (vec args))
                    (if (= "=swarmforge-analyst-001:" (nth args 6 nil))
                      {:exit 0 :out "analyst wrote the plan\n" :err ""}
                      {:exit 1 :out "" :err "can't find pane: =swarmforge-analyst-001"}))]
      (is (= "analyst wrote the plan\n"
             (retire/capture-pane-text "sock" "swarmforge-analyst-001")))
      (is (= ["tmux" "-S" "sock" "capture-pane" "-p" "-t"
              "=swarmforge-analyst-001:" "-S" "-2000"]
             @seen)))))

(deftest stop-running-session-force-kills-when-lingering
  ;; First kill may leave the session; force path kills again
  (let [kills (atom [])
        waits (atom 0)]
    (with-redefs [retire/kill-session-attempts!
                  (fn [_socket session]
                    (swap! kills conj session))
                  retire/wait-session-stopped
                  (fn [_socket _session]
                    (swap! waits inc)
                    (if (= 1 @waits)
                      (retire/lingering-session-result)
                      (retire/stopped-session-result)))]
      (let [result (retire/stop-running-session! "/tmp/sock" "swarmforge-x-001")]
        (is (true? (:stopped? result)))
        (is (= 2 (count @kills)) "kill then force kill")
        (is (= 2 @waits))))))

(deftest stop-session-absent-is-not-a-successful-stop
  (with-redefs [retire/session-exists? (constantly false)
                retire/kill-session-attempts! (fn [& _] nil)]
    (let [r (retire/stop-session! "/tmp/sock" "swarmforge-gone-001")]
      (is (false? (:stopped? r))
          "SESSION_STOPPED stays false when nothing was running")
      (is (str/includes? (:detail r) "not running")))))
