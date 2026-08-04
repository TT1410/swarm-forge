(ns swarmforge.simulator-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [swarmforge.test-support :refer :all]))

(deftest ^:simulation squad-simulator-runs-htw-through-tool-driven-workflow
  (let [result (run {:dir repo-root}
                    (script "squad_simulator.sh")
                    "htw"
                    "--seed"
                    "1"
                    "--stories"
                    "2"
                    "--handoff-ticks"
                    "3"
                    "--approval-ticks"
                    "5"
                    "--stall-percent"
                    "0")
        out (:out result)]
    (is (str/includes? out "SIM_SEED: 1"))
    (is (str/includes? out "SIM_START theme=hunt-the-wumpus"))
    (is (str/includes? out "handoff_ticks=3..3"))
    (is (str/includes? out "approval_ticks=5..5"))
    (is (str/includes? out "NEXT_ACTION: create_approval_request"))
    (is (str/includes? out "USER_APPROVES: theme__hunt-the-wumpus"))
    (is (str/includes? out "WAIT_TICKS:"))
    (is (str/includes? out "AGENT_HANDOFF: analyst-001"))
    (is (str/includes? out "decision=changes-requested"))
    (is (str/includes? out "decision=accepted"))
	    (is (str/includes? out "NEXT_ACTION: record_auto_approval"))
	    (is (str/includes? out "NEXT_ACTION: record_batch_membership"))
	    (is (str/includes? out "STORY: batch"))
	    (is (str/includes? out "TEMPLATE: hardener"))
	    (is (str/includes? out "TEMPLATE: qa"))
	    (is (str/includes? out "TEMPLATE: architect"))
	    (is (str/includes? out "TEMPLATE: senior-implementor"))
	    (is (str/includes? out "REVIEW_DECISION: batch architect decision=changes-requested"))
	    (is (str/includes? out "REVIEW_DECISION: batch architect decision=accepted"))
	    (is (str/includes? out "SIM_END"))
	    (is (str/includes? out "state=workflow_idle"))
	    (is (str/includes? out "cave_topology=final_approved"))
	    (is (str/includes? out "player_actions=final_approved"))))

(deftest ^:simulation squad-simulator-reports-stalled-agents
  (let [result (run {:dir repo-root :ok? false}
                    (script "squad_simulator.sh")
                    "htw"
                    "--seed"
                    "7"
                    "--stories"
                    "2"
                    "--handoff-ticks"
                    "1..2"
                    "--approval-ticks"
                    "1"
                    "--stall-percent"
                    "100"
                    "--max-ticks"
                    "30")
        out (:out result)]
	    (is (= 2 (:exit result)))
	    (is (str/includes? out "SIM_STALL: analyst-001 dark"))
	    (is (str/includes? out "NEXT_ACTION: recover_agent"))
	    (is (str/includes? out "RECOVERY_STATE: failed_no_work"))
	    (is (str/includes? out "state=max_ticks_exceeded"))
	    (is (str/includes? (:err result) "SIM_FAILED: exceeded max ticks"))))

(deftest ^:simulation squad-simulator-keeps-live-stalls-from-recovery-and-recovers-dark-stalls
  (let [live-result (run {:dir repo-root}
                         (script "squad_simulator.sh")
                         "htw"
                         "--seed"
                         "2"
                         "--stories"
                         "1"
                         "--handoff-ticks"
                         "1"
                         "--approval-ticks"
                         "1"
                         "--stall-percent"
                         "100"
                         "--stall-mode"
                         "active-then-handoff"
                         "--stall-active-ticks"
                         "4"
                         "--max-ticks"
                         "250")
        live-out (:out live-result)
        dark-result (run {:dir repo-root :ok? false}
                         (script "squad_simulator.sh")
                         "htw"
                         "--seed"
                         "3"
                         "--stories"
                         "1"
                         "--handoff-ticks"
                         "1"
                         "--approval-ticks"
                         "1"
                         "--stall-percent"
                         "100"
                         "--stall-mode"
                         "active-then-dark"
                         "--stall-active-ticks"
                         "3"
                         "--max-ticks"
                         "30")
        dark-out (:out dark-result)]
    (is (str/includes? live-out "SIM_STALL: analyst-001 active-then-handoff"))
    (is (str/includes? live-out "AGENT_HANDOFF: analyst-001"))
    (is (not (str/includes? live-out "NEXT_ACTION: recover_agent")))
    (is (str/includes? live-out "state=workflow_idle"))
    (is (= 2 (:exit dark-result)))
    (is (str/includes? dark-out "SIM_STALL: analyst-001 active-then-dark"))
    (is (str/includes? dark-out "NEXT_ACTION: recover_agent"))
    (is (str/includes? dark-out "QUIET_FOR_SECONDS: 5"))
    (is (str/includes? dark-out "RECOVERY_STATE: failed_no_work"))))
