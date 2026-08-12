(ns swarmforge.squad-actions-test
  (:require [clojure.test :refer [deftest is testing]]
            [squad-actions :as actions]))

(deftest action-builds-typed-map-with-authority
  (let [a (actions/action :record_merged_result
                          :assignment-id "cave-impl"
                          :command "squad_packet.sh record cave implementation cave-impl master abc")]
    (is (= "record_merged_result" (:op a)))
    (is (= :record_merged_result (:op-kw a)))
    (is (= :daemon (:authority a)))
    (is (= "record_merged_result" (:next-action a)))
    (is (= "cave-impl" (:assignment-id a)))))

(deftest ensure-typed-upgrades-legacy-next-action
  (let [a (actions/ensure-typed {:next-action "retire_agent"
                                 :agent "implementer-001"
                                 :command "squad_retire.sh implementer-001"})]
    (is (= "retire_agent" (:op a)))
    (is (= :daemon (:authority a)))
    (is (= "retire_agent" (actions/op-of a)))))

(deftest answer-dashboard-request-authority-is-sl-residual
  ;; Residual only surfaces product requests owned by the Squad Leader after
  ;; Troubleshooter route-to-sl. Repair chat is answered by Troubleshooter via
  ;; dashboard wake, not this residual op.
  (is (= :sl-residual (actions/authority-for "answer_dashboard_request"))))

(deftest shell-command-is-outer-boundary-only
  (is (= "echo hi" (actions/shell-command {:command "echo hi" :op "wait"}))))
