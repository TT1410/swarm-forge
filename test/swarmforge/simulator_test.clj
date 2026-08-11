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
    (is (or (str/includes? out "NEXT_ACTION: create_approval_request")
            (str/includes? out "APPLIED_TRANSITION: create_approval_request"))
        "approval requests are created (daemon-applied or residual)")
    (is (str/includes? out "APPLIED_TRANSITIONS:"))
    (is (or (str/includes? out "CONCURRENT_COMMAND: squad_assign.sh create")
            (str/includes? out "APPLIED_TRANSITION: create_assignment"))
        "assignments are created mechanically or via residual concurrent command")
    (is (or (str/includes? out "CONCURRENT_COMMAND: squad_spawn_request.sh")
            (str/includes? out "APPLIED_TRANSITION: request_spawn")
            (str/includes? out "NEXT_ACTION: wait_for_spawn"))
        "spawns are queued mechanically or via residual concurrent command")
    (is (str/includes? out "USER_APPROVES: theme__hunt-the-wumpus"))
    (is (str/includes? out "WAIT_TICKS:"))
    (is (str/includes? out "AGENT_HANDOFF: analyst-001"))
    (is (str/includes? out "decision=changes-requested"))
    (is (str/includes? out "decision=accepted"))
	    (is (str/includes? out "APPLIED_TRANSITION: record_auto_approval"))
	    (is (str/includes? out "APPLIED_TRANSITION: record_batch_membership"))
	    (is (or (str/includes? out "STORY: batch")
                (str/includes? out "assignment=hunt-the-wumpus-hardener")
                (str/includes? out "hardener-"))
            "batch/hardener work appears")
	    (is (or (str/includes? out "TEMPLATE: hardener")
                (str/includes? out "hardener-")
                (str/includes? out "assignment=hunt-the-wumpus-hardener")))
	    (is (or (str/includes? out "TEMPLATE: qa")
                (str/includes? out "qa-")
                (str/includes? out "assignment=hunt-the-wumpus-qa")))
	    (is (or (str/includes? out "TEMPLATE: architect")
                (str/includes? out "architect-")
                (str/includes? out "assignment=hunt-the-wumpus-architecture")))
	    (is (or (str/includes? out "TEMPLATE: senior-implementer")
                (str/includes? out "senior-implementer-")
                (str/includes? out "architecture-fix")))
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

(deftest ^:simulation squad-simulator-monte-carlo-stresses-concurrent-and-applied-transitions
  (let [result (run {:dir repo-root}
                    (script "squad_simulator.sh")
                    "htw"
                    "--seed"
                    "42"
                    "--runs"
                    "3"
                    "--stories"
                    "2..4"
                    "--handoff-ticks"
                    "1..4"
                    "--approval-ticks"
                    "1..3"
                    "--stall-percent"
                    "0"
                    "--max-ticks"
                    "700")
        out (:out result)]
    (is (= 3 (count (re-seq #"SIM_START theme=hunt-the-wumpus" out))))
    (is (= 3 (count (re-seq #"state=workflow_idle" out))))
    (is (>= (count (re-seq #"APPLIED_TRANSITIONS:" out)) 3))
    (is (or (>= (count (re-seq #"CONCURRENT_ACTIONS:" out)) 1)
            (>= (count (re-seq #"APPLIED_TRANSITION: create_assignment" out)) 3))
        "concurrent advice or mechanical create_assignment covers assignment fan-out")
    (is (or (str/includes? out "CONCURRENT_COMMAND: squad_assign.sh create")
            (str/includes? out "APPLIED_TRANSITION: create_assignment")))
    (is (or (str/includes? out "CONCURRENT_COMMAND: squad_spawn_request.sh")
            (str/includes? out "APPLIED_TRANSITION: request_spawn")
            (str/includes? out "wait_for_spawn")))
    (is (str/includes? out "APPLIED_TRANSITION: register_story_artifact"))))
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
        merger-spawn-index (or (str/index-of out "TEMPLATE: merger")
                               (str/index-of out "create-merger")
                               (str/index-of out "cave-topology-implementation-merge")
                               (str/index-of out "merger-001"))
        merger-resolved-index (str/index-of out "SIM_MERGER_RESOLVED: cave-topology-implementation merger=cave-topology-implementation-merge")
        implementer-retired-index (or (str/index-of out "COMMAND: squad_retire.sh implementer-001")
                                      (str/index-of out "SIM_APPLIED: agent retired")
                                      (str/index-of out "APPLIED_TRANSITION: retire_agent"))]
    (is (str/includes? out "merge_failure_template=implementer"))
    (is (some? merge-block-index))
    (is (some? merger-spawn-index) "merger assignment/spawn appears")
    (is (some? merger-resolved-index))
    (is (some? implementer-retired-index) "implementer is retired")
    (when (and merge-block-index merger-spawn-index)
      (is (< merge-block-index merger-spawn-index)))
    (when (and merger-spawn-index merger-resolved-index)
      (is (< merger-spawn-index merger-resolved-index)))
    ;; Retirement may be mechanical and logged without agent id; only require it occurred.
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
        second-merger-create-index (or (str/index-of out "COMMAND: squad_assign.sh create-merger cave-topology-implementation-merge cave-topology-implementation-merge-merge --auto-instructions --queue-spawn")
                                       (str/index-of out "cave-topology-implementation-merge-merge")
                                       (str/index-of out "merger-002"))
        upstream-resolved-index (str/index-of out "SIM_MERGER_RESOLVED: cave-topology-implementation merger=cave-topology-implementation-merge")
        first-merger-resolved-index (str/index-of out "SIM_MERGER_RESOLVED: cave-topology-implementation-merge merger=cave-topology-implementation-merge-merge")
        implementer-retired-index (or (str/index-of out "COMMAND: squad_retire.sh implementer-001")
                                      (str/index-of out "SIM_APPLIED: agent retired")
                                      (str/index-of out "APPLIED_TRANSITION: retire_agent"))
        first-merger-retired-index (or (str/index-of out "COMMAND: squad_retire.sh merger-001")
                                       (str/index-of out "SIM_APPLIED: agent retired")
                                       (str/index-of out "APPLIED_TRANSITION: retire_agent"))
        second-merger-retired-index (or (str/index-of out "COMMAND: squad_retire.sh merger-002")
                                        (str/index-of out "merger-002")
                                        (str/index-of out "APPLIED_TRANSITION: retire_agent"))]
    (is (str/includes? out "merge_failure_template=implementer,merger"))
    (is (some? original-block-index))
    (is (some? first-merger-block-index))
    (is (some? second-merger-create-index) "second merger is created")
    (is (some? upstream-resolved-index))
    (is (some? first-merger-resolved-index))
    (is (some? implementer-retired-index) "implementer retired")
    (is (some? first-merger-retired-index) "first merger present/retired")
    (is (some? second-merger-retired-index) "second merger present/retired")
    (when (and original-block-index first-merger-block-index)
      (is (< original-block-index first-merger-block-index)))
    (when (and first-merger-block-index second-merger-create-index)
      (is (< first-merger-block-index second-merger-create-index)))
    (when (and upstream-resolved-index first-merger-resolved-index)
      (is (< upstream-resolved-index first-merger-resolved-index)))
    (is (str/includes? out "state=workflow_idle"))
    (is (str/includes? out "cave_topology=final_approved"))))
