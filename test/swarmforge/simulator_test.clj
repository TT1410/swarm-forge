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

(deftest ^:simulation squad-simulator-routes-merge-failure-through-merger-before-retiring-source-agent
  (let [result (run {:dir repo-root}
                    (script "squad_simulator.sh")
                    "htw"
                    "--seed"
                    "5"
                    "--stories"
                    "1"
                    "--handoff-ticks"
                    "1"
                    "--approval-ticks"
                    "1"
                    "--stall-percent"
                    "0"
                    "--merge-failure-template"
                    "implementer"
                    "--max-ticks"
                    "250")
        out (:out result)
        merge-block-index (str/index-of out "SIM_MERGE_BLOCKED: cave-topology-implementation agent=implementer-001")
        merger-spawn-index (str/index-of out "TEMPLATE: merger")
        merger-resolved-index (str/index-of out "SIM_MERGER_RESOLVED: cave-topology-implementation merger=cave-topology-implementation-merge")
        implementer-retired-index (str/index-of out "COMMAND: squad_retire.sh implementer-001")]
    (is (str/includes? out "merge_failure_template=implementer"))
    (is (some? merge-block-index))
    (is (some? merger-spawn-index))
    (is (some? merger-resolved-index))
    (is (some? implementer-retired-index))
    (is (< merge-block-index merger-spawn-index))
    (is (< merger-spawn-index merger-resolved-index))
    (is (< merger-resolved-index implementer-retired-index))
    (is (str/includes? out "state=workflow_idle"))
    (is (str/includes? out "cave_topology=final_approved"))))

(deftest ^:simulation squad-simulator-routes-merger-handoff-merge-failure-through-another-merger
  (let [result (run {:dir repo-root}
                    (script "squad_simulator.sh")
                    "htw"
                    "--seed"
                    "6"
                    "--stories"
                    "1"
                    "--handoff-ticks"
                    "1"
                    "--approval-ticks"
                    "1"
                    "--stall-percent"
                    "0"
                    "--merge-failure-template"
                    "implementer,merger"
                    "--max-ticks"
                    "300")
        out (:out result)
        original-block-index (str/index-of out "SIM_MERGE_BLOCKED: cave-topology-implementation agent=implementer-001")
        first-merger-block-index (str/index-of out "SIM_MERGE_BLOCKED: cave-topology-implementation-merge agent=merger-001")
        second-merger-create-index (str/index-of out "COMMAND: squad_assign.sh create-merger cave-topology-implementation-merge cave-topology-implementation-merge-merge <instructions-file>")
        upstream-resolved-index (str/index-of out "SIM_MERGER_RESOLVED: cave-topology-implementation merger=cave-topology-implementation-merge")
        first-merger-resolved-index (str/index-of out "SIM_MERGER_RESOLVED: cave-topology-implementation-merge merger=cave-topology-implementation-merge-merge")
        implementer-retired-index (str/index-of out "COMMAND: squad_retire.sh implementer-001")
        first-merger-retired-index (str/index-of out "COMMAND: squad_retire.sh merger-001")
        second-merger-retired-index (str/index-of out "COMMAND: squad_retire.sh merger-002")]
    (is (str/includes? out "merge_failure_template=implementer,merger"))
    (is (some? original-block-index))
    (is (some? first-merger-block-index))
    (is (some? second-merger-create-index))
    (is (some? upstream-resolved-index))
    (is (some? first-merger-resolved-index))
    (is (some? implementer-retired-index))
    (is (some? first-merger-retired-index))
    (is (some? second-merger-retired-index))
    (is (< original-block-index first-merger-block-index))
    (is (< first-merger-block-index second-merger-create-index))
    (is (< first-merger-block-index implementer-retired-index))
    (is (< implementer-retired-index upstream-resolved-index))
    (is (< second-merger-create-index upstream-resolved-index))
    (is (< upstream-resolved-index first-merger-resolved-index))
    (is (< first-merger-resolved-index first-merger-retired-index))
    (is (< first-merger-resolved-index second-merger-retired-index))
    (is (str/includes? out "state=workflow_idle"))
    (is (str/includes? out "cave_topology=final_approved"))))
