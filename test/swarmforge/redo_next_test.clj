(ns swarmforge.redo-next-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [swarmforge.test-support :refer :all]))

(defn- write-roles! [root]
  (write-file (fs/path root ".swarmforge/roles.tsv")
              (str "squad-leader\tmaster\t" root
                   "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n")))

(deftest worker-handoff-tells-sl-to-merge
  ;; Given an implementer handed a SHA to SL
  ;; When squad_next runs
  ;; Then residual is SL merge of that SHA — not merger, not daemon accept-merge
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root
                       "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "impl-001\timpl-001\t" root "/.worktrees/impl-001"
                       "\tswarmforge-impl-001\tImplementer 001\tcodex\ttask\n"))
      (write-agent-status! root "impl-001" "handoff_sent")
      (write-file (fs/path root ".squad/assignments/cave-impl/metadata")
                  (str "assignment_id: cave-impl\n"
                       "story_id: cave-graph\n"
                       "template: implementer\n"
                       "assignment_file: " root "/instructions.md\n"))
      (write-file (fs/path root ".squad/assignments/cave-impl/status")
                  "assignment_id: cave-impl\nstate: merge_ready\n")
      (write-file (fs/path root ".swarmforge/handoffs/inbox/in_process/50_20260803T000000Z_000001_from_impl-001_to_squad-leader.handoff")
                  (str "type: git_handoff\n"
                       "to: squad-leader\n"
                       "from: impl-001\n"
                       "priority: 50\n"
                       "task: cave-impl\n"
                       "commit: abcdef1234\n"
                       "assignment: cave-impl\n"
                       "agent: impl-001\n"
                       "template: implementer\n"
                       "artifacts: none\n\n"
                       "merge_and_process impl-001 abcdef1234\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))
            residual (:out (run {:dir root} (script "squad_next.sh") "--residual-only"))]
        (is (not (str/includes? out "TEMPLATE: merger")))
        (is (not (str/includes? out "create-merger")))
        (is (not (str/includes? residual "wait_for_daemon_main_git")))
        (is (not (str/includes? residual "check_merge_readiness")))
        (is (str/includes? residual "accept-merge"))
        (is (or (str/includes? out "accept_merge")
                (str/includes? out "accept-merge"))))
      (finally
        (fs/delete-tree root)))))

(deftest merge-blocked-is-gone
  ;; Given leftover merge_blocked status from the old machine
  ;; When squad_next runs
  ;; Then it does not create a merger and does not treat merge_blocked as a live state
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/assignments/cave-impl/metadata")
                  (str "assignment_id: cave-impl\nstory_id: cave-graph\n"
                       "template: implementer\n"))
      (write-file (fs/path root ".squad/assignments/cave-impl/status")
                  "assignment_id: cave-impl\nstate: merge_blocked\n")
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (not (str/includes? out "TEMPLATE: merger")))
        (is (not (str/includes? out "create-merger")))
        (is (not (str/includes? out "wait_for_merge_recovery")))
        (is (str/includes? out "NEXT_ACTION: wait")))
      (finally
        (fs/delete-tree root)))))

(deftest empty-swarm-waits
  ;; Given a new repo with only SL registered
  ;; When residual runs
  ;; Then wait — not write_theme_module_map
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "NEXT_ACTION: wait"))
        (is (not (str/includes? out "write_theme_module_map")))
        (is (not (str/includes? out "create_approval_request"))))
      (finally
        (fs/delete-tree root)))))

(deftest implementer-is-one-story-without-order-file
  ;; Given two implementer-ready stories and no implementation-order.md
  ;; When residual runs
  ;; Then each story may get its own implementer; no batch of two
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "swarmforge/squad.conf") implementer-gate-conf)
      (doseq [story ["alpha" "beta"]]
        (write-file (fs/path root ".squad/stories" story "packet")
                    (str "story_id: " story "\n"
                         "theme_id: swarm\n"
                         "implementation_plan_path: .squad/stories/" story "/plan.md\n"
                         "implementation_plan_approval: approved\n"
                         "gherkin_path: features/" story ".feature\n"
                         "gherkin_approval: approved\n"
                         "qa_procedure_path: qa/" story ".md\n"
                         "qa_procedure_approval: approved\n"))
        (write-file (fs/path root "stories" (str story ".md")) (str "Story " story ".\n")))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "TEMPLATE: implementer"))
        (is (not (str/includes? out "--batch-stories")))
        (is (not (str/includes? out "record_implementation_order"))))
      (finally
        (fs/delete-tree root)))))

(deftest backlog-add-does-not-start-analyst
  ;; Given an open backlog item
  ;; When residual runs
  ;; Then wait — no analyst
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (require 'squadd.web)
      ((resolve 'squadd.web/create-backlog!) root {:title "Cave graph" :body "Rooms and tunnels."})
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "NEXT_ACTION: wait"))
        (is (not (str/includes? out "TEMPLATE: analyst"))))
      (finally
        (fs/delete-tree root)))))

(deftest start-backlog-creates-analyst-for-that-story
  ;; Given a backlog item
  ;; When the operator starts it
  ;; Then a story packet exists and residual is create_assignment analyst for that story
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (require 'squadd.web)
      (let [web (find-ns 'squadd.web)
            created ((ns-resolve web 'create-backlog!) root {:title "Cave graph" :body "Rooms and tunnels."})
            id (get-in created [:item "id"])
            _ (write-frame-ready! root)
            started ((ns-resolve web 'approve-backlog!) root id)
            story-id (or (get-in started [:item "story_id"]) "cave-graph")
            out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (fs/regular-file? (fs/path root "stories" (str story-id ".md"))))
        (is (fs/regular-file? (fs/path root ".squad/stories" story-id "packet")))
        (is (not (str/includes? (slurp (str (fs/path root ".squad/stories" story-id "packet")))
                                "theme_id:")))
        (is (str/includes? out "TEMPLATE: analyst"))
        (is (str/includes? out story-id))
        (is (not (str/includes? out "NEW THEME")))
        (is (not (str/includes? (get-in started [:request "body"] "") "classify"))))
      (finally
        (fs/delete-tree root)))))

(deftest analyst-plan-requests-implementation-plan-approval
  ;; Given a started story whose analyst assignment is merged with a plan file
  ;; When residual runs
  ;; Then create_approval_request gate implementation-plan
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "stories/cave-graph.md") "Rooms and tunnels.\n")
      (write-file (fs/path root ".squad/stories/cave-graph/plan.md")
                  "# Implementation plan\n\n1. Graph.\n")
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str "story_id: cave-graph\n"
                       "theme_id: swarm\n"
                       "implementation_plan_path: .squad/stories/cave-graph/plan.md\n"
                       "implementation_plan_sha: abcdef1234\n"))
      (write-file (fs/path root ".squad/assignments/cave-graph-analysis/metadata")
                  (str "assignment_id: cave-graph-analysis\n"
                       "theme_id: swarm\n"
                       "story_id: cave-graph\n"
                       "template: analyst\n"))
      (write-file (fs/path root ".squad/assignments/cave-graph-analysis/status")
                  "state: merged\n")
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "NEXT_ACTION: create_approval_request"))
        (is (str/includes? out "implementation-plan")))
      (finally
        (fs/delete-tree root)))))

(deftest analyst-merge-attaches-implementation-plan
  ;; Given a merged analyst whose artifact is the story plan
  ;; When residual runs
  ;; Then attach_story_artifact records the plan on the packet
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "stories/cave-graph.md") "Rooms and tunnels.\n")
      (write-file (fs/path root ".squad/stories/cave-graph/plan.md")
                  "# Implementation plan\n\n1. Graph.\n")
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  "story_id: cave-graph\n")
      (write-file (fs/path root ".squad/assignments/cave-graph-analysis/metadata")
                  (str "assignment_id: cave-graph-analysis\n"
                       "story_id: cave-graph\n"
                       "template: analyst\n"
                       "assignment_file: " root "/plan-instructions.md\n"))
      (write-file (fs/path root ".squad/assignments/cave-graph-analysis/status")
                  "state: merged\n")
      (write-file (fs/path root ".squad/assignments/cave-graph-analysis/result-manifest")
                  (str "assignment_id: cave-graph-analysis\n"
                       "agent: analyst-001\n"
                       "template: analyst\n"
                       "commit: abcdef1234\n"
                       "artifacts: .squad/stories/cave-graph/plan.md\n"))
      (write-file (fs/path root ".squad/assignments/cave-graph-analysis/accepted-merge")
                  (str "assignment_id: cave-graph-analysis\n"
                       "state: merged\n"
                       "commit: abcdef1234\n"
                       "merge_commit: abcdef1234\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "NEXT_ACTION: attach_story_artifact"))
        (is (str/includes? out "implementation-plan")))
      (finally
        (fs/delete-tree root)))))

(deftest start-backlog-apply-creates-analyst-assignment
  ;; Given a started story
  ;; When mechanical apply runs
  ;; Then the analyst assignment for that story is created
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "swarmforge/role-templates/analyst.prompt")
                  (slurp (str (fs/path repo-root "swarmforge/role-templates/analyst.prompt"))))
      (write-file (fs/path root "swarmforge/role-templates/analyst.contract.edn")
                  (slurp (str (fs/path repo-root "swarmforge/role-templates/analyst.contract.edn"))))
      (require 'squadd.web)
      (let [web (find-ns 'squadd.web)
            created ((ns-resolve web 'create-backlog!) root {:title "Cave graph" :body "Rooms."})
            _ (write-frame-ready! root)
            started ((ns-resolve web 'approve-backlog!) root (get-in created [:item "id"]))
            story-id (get-in started [:item "story_id"])
            applied (:out (run {:dir root} (script "squad_next.sh") "--apply-mechanical"))]
        (is (str/includes? applied "APPLIED_TRANSITION: create_assignment"))
        (is (str/includes? applied "exit=0"))
        (is (fs/directory? (fs/path root ".squad/assignments" (str story-id "-analysis")))))
      (finally
        (fs/delete-tree root)))))

(deftest rejected-implementation-plan-reopens-backlog
  ;; Given a started story with a pending implementation-plan approval
  ;; When the operator rejects the plan
  ;; Then the original backlog item is open again
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (require 'squadd.web)
      (let [web (find-ns 'squadd.web)
            created ((ns-resolve web 'create-backlog!) root {:title "Cave graph" :body "Rooms."})
            id (get-in created [:item "id"])
            _ (write-frame-ready! root)
            started ((ns-resolve web 'approve-backlog!) root id)
            story-id (get-in started [:item "story_id"])]
        (run {:dir root} (script "squad_approval.sh") "request"
             (str "implementation-plan__" story-id)
             "story" story-id "implementation-plan"
             "Approve_implementation_plan" "plan-ready")
        (run {:dir root} (script "squad_approval.sh") "reject"
             (str "implementation-plan__" story-id) "wrong shape")
        (let [item ((ns-resolve web 'get-backlog) root id)]
          (is (= "open" (get item "status")))
          (is (= story-id (get item "story_id")))))
      (finally
        (fs/delete-tree root)))))

(defn- plan-approved-packet [story]
  (str "story_id: " story "\n"
       "implementation_plan_path: .squad/stories/" story "/plan.md\n"
       "implementation_plan_approval: approved\n"))

(deftest gherkin-writer-after-plan-approval
  ;; Given implementation-plan approved
  ;; When residual runs
  ;; Then create_assignment gherkin-writer — not gherkin-reviewer
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (plan-approved-packet "cave-graph"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "TEMPLATE: gherkin-writer"))
        (is (not (str/includes? out "gherkin-reviewer"))))
      (finally
        (fs/delete-tree root)))))

(deftest gherkin-merge-requests-user-approval-not-reviewer
  ;; Given gherkin_path recorded
  ;; When residual runs
  ;; Then create_approval_request gherkin — not gherkin-reviewer
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str (plan-approved-packet "cave-graph")
                       "gherkin_path: features/cave-graph.feature\n"
                       "gherkin_sha: abcdef1234\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "NEXT_ACTION: create_approval_request"))
        (is (str/includes? out "gherkin"))
        (is (not (str/includes? out "TEMPLATE: gherkin-reviewer"))))
      (finally
        (fs/delete-tree root)))))

(deftest qa-writer-then-user-approval-not-reviewer
  ;; Given a QA procedure on disk after the plan and Gherkin
  ;; When residual runs
  ;; Then create_approval_request qa-procedure — not qa-procedure-reviewer
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str (plan-approved-packet "cave-graph")
                       "gherkin_path: features/cave-graph.feature\n"
                       "gherkin_approval: approved\n"
                       "qa_procedure_path: qa/cave-graph.md\n"
                       "qa_procedure_sha: abcdef1234\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "NEXT_ACTION: create_approval_request"))
        (is (or (str/includes? out "qa-procedure")
                (str/includes? out "qa_procedure")))
        (is (not (str/includes? out "qa-procedure-reviewer"))))
      (finally
        (fs/delete-tree root)))))

(deftest implementer-after-plan-and-gherkin-waits-for-qa-procedure-approval
  ;; Given plan and Gherkin user-approved, no QA procedure
  ;; When residual runs
  ;; Then QA-procedure writer or approval, not implementer
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "swarmforge/squad.conf") implementer-gate-conf)
      (write-file (fs/path root "stories/cave-graph.md") "Rooms and tunnels.\n")
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str (plan-approved-packet "cave-graph")
                       "gherkin_path: features/cave-graph.feature\n"
                       "gherkin_approval: approved\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (not (str/includes? out "TEMPLATE: implementer")))
        (is (str/includes? out "qa-procedure"))
        (is (not (str/includes? out "qa-procedure-reviewer"))))
      (finally
        (fs/delete-tree root)))))

(deftest written-gherkin-is-ready-for-user-approval-without-reviewer
  ;; Given a Gherkin file on the packet and no reviewer verdict
  ;; When packet state is derived
  ;; Then gherkin approval is pending for the operator, not blocked on review
  (require 'squad-state)
  (let [state (find-ns 'squad-state)
        packet {"story_id" "cave-graph"
                "gherkin_path" "features/cave-graph.feature"
                "qa_procedure_path" "qa/cave-graph.md"}
        derived ((ns-resolve state 'derived-stage-fields) packet "specification_in_progress")]
    (is (= "pending" (get derived "gherkin_approval_state")))
    (is (= "pending" (get derived "qa_procedure_approval_state")))))

(deftest implementer-assignment-state-does-not-need-implementation-approval-row
  ;; Given plan, Gherkin, and QA procedure user-approved
  ;; When packet state is derived
  ;; Then implementer is ready without a separate implementation_approval field
  (require 'squad-state)
  (let [state (find-ns 'squad-state)
        packet {"story_id" "cave-graph"
                "implementation_plan_approval" "approved"
                "gherkin_approval" "approved"
                "qa_procedure_approval" "approved"
                "gherkin_path" "features/cave-graph.feature"}]
    (is (= "implementation_approval_ready"
           ((ns-resolve state 'recompute-state) packet)))
    (is (= "ready"
           (get ((ns-resolve state 'derived-stage-fields)
                 packet "implementation_approval_ready")
                "implementation_assignment_state")))))

(deftest theme-analyst-does-not-register-stories
  ;; Given a leftover theme-scoped analyst assignment that is merged
  ;; When residual runs
  ;; Then it does not register theme stories or create themed packets
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "stories/alpha.md") "Story alpha.\n")
      (write-file (fs/path root ".squad/assignments/wumpus-analysis/metadata")
                  (str "assignment_id: wumpus-analysis\n"
                       "theme_id: wumpus\n"
                       "story_id: theme\n"
                       "template: analyst\n"))
      (write-file (fs/path root ".squad/assignments/wumpus-analysis/status")
                  "state: merged\n")
      (write-file (fs/path root ".squad/assignments/wumpus-analysis/result-manifest")
                  "commit: abcdef1234\nartifacts: stories/alpha.md\n")
      (write-file (fs/path root ".squad/assignments/wumpus-analysis/accepted-merge")
                  "state: merged\ncommit: abcdef1234\nmerge_commit: abcdef1234\n")
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (not (str/includes? out "register_story_artifact")))
        (is (not (str/includes? out "squad_theme.sh story")))
        (is (not (str/includes? out "squad_packet.sh create wumpus"))))
      (finally
        (fs/delete-tree root)))))

(deftest theme-refs-do-not-register-story-packets
  ;; Given a leftover theme story .ref and no packet
  ;; When residual runs
  ;; Then it does not create a themed packet or auto-approve the story gate
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/themes/wumpus/stories/alpha.ref")
                  "path: stories/alpha.md\n")
      (write-file (fs/path root "stories/alpha.md") "Story alpha.\n")
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (not (str/includes? out "register_story_packet")))
        (is (not (str/includes? out "squad_packet.sh create wumpus")))
        (is (not (str/includes? out "approve alpha story"))))
      (finally
        (fs/delete-tree root)))))

(deftest theme-slice-does-not-finalize
  ;; Given leftover theme records whose stories look complete
  ;; When residual runs
  ;; Then it does not request theme finalize
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/themes/wumpus/status") "state: approved\n")
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\ntheme_id: wumpus\n"
                       "qa_sha: abcdef1234\n"
                       "architecture_review: accepted\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (not (str/includes? out "finalize")))
        (is (not (str/includes? out "squad_theme.sh finalize"))))
      (finally
        (fs/delete-tree root)))))

(deftest leftover-merge-blocked-handoff-is-not-live-recovery
  ;; Given an in-process handoff whose assignment is leftover merge_blocked
  ;; When residual runs
  ;; Then it does not spawn a merger or treat merge_blocked as a recovery machine
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root
                       "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "impl-001\timpl-001\t" root "/.worktrees/impl-001"
                       "\tswarmforge-impl-001\tImplementer 001\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/assignments/cave-impl/metadata")
                  "assignment_id: cave-impl\nstory_id: cave-graph\ntemplate: implementer\n")
      (write-file (fs/path root ".squad/assignments/cave-impl/status")
                  "state: merge_blocked\n")
      (write-file (fs/path root ".squad/assignments/cave-impl/merge")
                  "state: merge_blocked\n")
      (write-file (fs/path root ".swarmforge/handoffs/inbox/in_process/50_from_impl-001.handoff")
                  (str "type: git_handoff\nto: squad-leader\nfrom: impl-001\n"
                       "task: cave-impl\ncommit: abcdef1234\nassignment: cave-impl\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (not (str/includes? out "TEMPLATE: merger")))
        (is (not (str/includes? out "create-merger")))
        (is (not (str/includes? out "accept-merge")))
        (is (not (str/includes? out "accept_merge"))))
      (finally
        (fs/delete-tree root)))))

(defn- spec-approved-packet [story]
  (str "story_id: " story "\n"
       "theme_id: swarm\n"
       "implementation_plan_path: .squad/stories/" story "/plan.md\n"
       "implementation_plan_approval: approved\n"
       "gherkin_path: features/" story ".feature\n"
       "gherkin_approval: approved\n"
       "qa_procedure_path: qa/" story ".md\n"
       "qa_procedure_approval: approved\n"))

(deftest cleaner-after-implementer
  ;; Given implementation_sha, no cleaner
  ;; When residual runs
  ;; Then cleaner — not code-reviewer
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str (spec-approved-packet "cave-graph")
                       "implementation_sha: abcdef1111\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "TEMPLATE: cleaner"))
        (is (not (str/includes? out "TEMPLATE: code-reviewer"))))
      (finally
        (fs/delete-tree root)))))

(deftest code-reviewer-after-cleaner
  ;; Given implementation_sha and cleaner_sha
  ;; When residual runs
  ;; Then code-reviewer
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str (spec-approved-packet "cave-graph")
                       "implementation_sha: abcdef1111\n"
                       "cleaner_sha: abcdef2222\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "TEMPLATE: code-reviewer")))
      (finally
        (fs/delete-tree root)))))

(deftest hardener-after-code-review
  ;; Given CR recorded
  ;; When residual runs
  ;; Then hardener (may be a batch of ready stories)
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str (spec-approved-packet "cave-graph")
                       "implementation_sha: abcdef1111\n"
                       "cleaner_sha: abcdef2222\n"
                       "code_review: accepted\n"
                       "code_review_sha: abcdef3333\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (or (str/includes? out "TEMPLATE: hardener")
                (str/includes? out "hardener"))))
      (finally
        (fs/delete-tree root)))))

(deftest code-review-recs-go-to-hardener-not-implementer
  ;; Given CR recorded changes-requested
  ;; When residual runs
  ;; Then hardener applies the recs — not a new implementer
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str (spec-approved-packet "cave-graph")
                       "implementation_sha: abcdef1111\n"
                       "cleaner_sha: abcdef2222\n"
                       "code_review: changes-requested\n"
                       "code_review_sha: abcdef3333\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (or (str/includes? out "TEMPLATE: hardener")
                (str/includes? out "hardener")))
        (is (not (str/includes? out "TEMPLATE: implementer")))
        (is (not (str/includes? out "code review requested implementation changes"))))
      (finally
        (fs/delete-tree root)))))

(deftest ready-stories-may-share-a-hardener-batch
  ;; Given two stories both CR-complete
  ;; When residual runs
  ;; Then one hardener may cover both (batch stays)
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (doseq [story ["alpha" "beta"]]
        (write-file (fs/path root ".squad/stories" story "packet")
                    (str (spec-approved-packet story)
                         "implementation_sha: a\ncleaner_sha: b\n"
                         "code_review: accepted\ncode_review_sha: c\n")))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "hardener")))
      (finally
        (fs/delete-tree root)))))

(deftest architect-does-not-wait-for-unready-sibling
  ;; Given one QA-complete story and a sibling still in coding
  ;; When residual runs
  ;; Then architect may take the ready story; it does not wait for the sibling
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str (spec-approved-packet "alpha")
                       "implementation_sha: a\ncleaner_sha: b\n"
                       "code_review: accepted\ncode_review_sha: c\n"
                       "hardener_sha: d\nqa_sha: e\nqa_approval: approved\n"))
      (write-file (fs/path root ".squad/stories/beta/packet")
                  (str (spec-approved-packet "beta")
                       "implementation_sha: a\ncleaner_sha: b\n"
                       "code_review: accepted\ncode_review_sha: c\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (or (str/includes? out "TEMPLATE: architect")
                (str/includes? out "architecture")))
        (is (not (str/includes? out "TEMPLATE: senior-implementer"))))
      (finally
        (fs/delete-tree root)))))

(deftest no-final-bless
  ;; Given architect accepted with no recs (or SI merged)
  ;; When residual runs
  ;; Then story is done — not create_approval_request final
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str (spec-approved-packet "cave-graph")
                       "implementation_sha: a\ncleaner_sha: b\n"
                       "code_review: accepted\nhardener_sha: c\nqa_sha: d\n"
                       "architecture_review: accepted\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (not (str/includes? out "Approve_final")))
        (is (not (str/includes? out "gate final")))
        (is (not (str/includes? out "story-ready-for-final-acceptance"))))
      (finally
        (fs/delete-tree root)))))

(defn- themeless-cr-ready [story]
  (str "story_id: " story "\n"
       "story_path: stories/" story ".md\n"
       "implementation_plan_path: .squad/stories/" story "/plan.md\n"
       "implementation_plan_approval: approved\n"
       "gherkin_path: features/" story ".feature\n"
       "gherkin_approval: approved\n"
       "qa_procedure_path: qa/" story ".md\n"
       "qa_procedure_approval: approved\n"
       "implementation_sha: abcdef1111\n"
       "cleaner_sha: abcdef2222\n"
       "code_review: accepted\n"
       "code_review_sha: abcdef3333\n"))

(deftest themeless-story-gets-hardener-after-cr
  ;; Given a Start packet (no theme_id) with CR recorded
  ;; When residual runs
  ;; Then hardener — not wait
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "stories/cave-graph.md") "Rooms.\n")
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (themeless-cr-ready "cave-graph"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "TEMPLATE: hardener"))
        (is (not (str/includes? out "NEXT_ACTION: wait"))))
      (finally
        (fs/delete-tree root)))))

(deftest themeless-ready-stories-share-one-hardener
  ;; Given two themeless CR-ready stories
  ;; When residual runs
  ;; Then one hardener batch covers both
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (doseq [story ["alpha" "beta"]]
        (write-file (fs/path root "stories" (str story ".md")) (str story "\n"))
        (write-file (fs/path root ".squad/stories" story "packet")
                    (themeless-cr-ready story)))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "TEMPLATE: hardener"))
        (is (str/includes? out "alpha"))
        (is (str/includes? out "beta")))
      (finally
        (fs/delete-tree root)))))

(deftest themeless-architect-after-qa
  ;; Given a themeless packet with qa_sha
  ;; When residual runs
  ;; Then architect
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "stories/cave-graph.md") "Rooms.\n")
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str (themeless-cr-ready "cave-graph")
                       "hardener_sha: abcdef4444\n"
                       "qa_sha: abcdef5555\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "TEMPLATE: architect")))
      (finally
        (fs/delete-tree root)))))

(deftest themeless-si-after-architect-recs
  ;; Given themeless architecture_review changes-requested
  ;; When residual runs
  ;; Then senior-implementer
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "stories/cave-graph.md") "Rooms.\n")
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str (themeless-cr-ready "cave-graph")
                       "hardener_sha: abcdef4444\n"
                       "qa_sha: abcdef5555\n"
                       "architecture_review: changes-requested\n"
                       "architecture_sha: abcdef6666\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "TEMPLATE: senior-implementer")))
      (finally
        (fs/delete-tree root)))))

(deftest architect-accepted-no-recs-is-done
  ;; Given architecture_review accepted, no recs
  ;; When residual runs
  ;; Then wait — not Approve_architecture, not SI
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "stories/cave-graph.md") "Rooms.\n")
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str (themeless-cr-ready "cave-graph")
                       "hardener_sha: abcdef4444\n"
                       "qa_sha: abcdef5555\n"
                       "architecture_review: accepted\n"
                       "architecture_sha: abcdef6666\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "NEXT_ACTION: wait"))
        (is (not (str/includes? out "Approve_architecture")))
        (is (not (str/includes? out "TEMPLATE: senior-implementer"))))
      (finally
        (fs/delete-tree root)))))

(deftest extra-user-gates-are-gone
  ;; Given CR accepted on a themeless packet
  ;; When residual runs
  ;; Then no leftover user gates for CR, hardening, QA result, or architecture
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "stories/cave-graph.md") "Rooms.\n")
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (themeless-cr-ready "cave-graph"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (not (str/includes? out "approve cave-graph code-review")))
        (is (not (str/includes? out "approve cave-graph hardening")))
        (is (not (str/includes? out "approve cave-graph qa auto-approved")))
        (is (not (str/includes? out "approve cave-graph architecture"))))
      (finally
        (fs/delete-tree root)))))

(deftest packet-create-is-story-id-only
  ;; Given a story file and no theme records
  ;; When packet create is invoked with story-id only
  ;; Then a packet exists and usage does not take a theme-id
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "stories/cave-graph.md") "Rooms.\n")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
            usage (:err (run {:dir root :ok? false} (script "squad_packet.sh")))
            created (run {:dir root :ok? false} (script "squad_packet.sh")
                         "create" "cave-graph" "cave-graph-start" "master" sha)
            packet-file (fs/path root ".squad/stories/cave-graph/packet")]
        (is (str/includes? usage "create <story-id>"))
        (is (not (str/includes? usage "create <theme-id>")))
        (is (zero? (:exit created)))
        (is (str/includes? (:out created) "SQUAD_PACKET: cave-graph"))
        (is (fs/regular-file? packet-file))
        (when (fs/regular-file? packet-file)
          (let [packet (slurp (str packet-file))]
            (is (str/includes? packet "story_id: cave-graph"))
            (is (not (str/includes? packet "theme_id:"))))))
      (finally
        (fs/delete-tree root)))))

(deftest already-merged-handoff-finishes-without-merge-ready
  ;; Given an in-process git handoff whose assignment is already merged
  ;; When residual runs
  ;; Then it finishes the handoff without a merge-ready resync
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root
                       "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "impl-001\timpl-001\t" root "/.worktrees/impl-001"
                       "\tswarmforge-impl-001\tImplementer 001\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/assignments/cave-impl/metadata")
                  "assignment_id: cave-impl\nstory_id: cave-graph\ntemplate: implementer\n")
      (write-file (fs/path root ".squad/assignments/cave-impl/status")
                  "state: merged\n")
      (write-file (fs/path root ".squad/assignments/cave-impl/accepted-merge")
                  "state: merged\ncommit: abcdef1234\nmerge_commit: abcdef1234\n")
      (write-file (fs/path root ".swarmforge/handoffs/inbox/in_process/50_from_impl-001.handoff")
                  (str "type: git_handoff\nto: squad-leader\nfrom: impl-001\n"
                       "task: cave-impl\ncommit: abcdef1234\nassignment: cave-impl\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh") "--residual-only"))]
        (is (str/includes? out "finish_in_process_handoff"))
        (is (not (str/includes? out "merge-ready")))
        (is (str/includes? out "done_with_current.sh")))
      (finally
        (fs/delete-tree root)))))

(deftest theme-cli-is-gone
  ;; Given the shipped scripts
  ;; Then there is no theme CLI
  (is (not (fs/exists? (fs/path repo-root "swarmforge/scripts/squad_theme.sh"))))
  (is (not (fs/exists? (fs/path repo-root "swarmforge/scripts/squad_theme.clj")))))

(deftest packet-review-is-code-and-architecture-only
  ;; Given a story packet
  ;; When review is attempted for leftover gherkin or qa-procedure kinds
  ;; Then those kinds are rejected
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "stories/cave-graph.md") "Rooms.\n")
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  "story_id: cave-graph\nstory_path: stories/cave-graph.md\n")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
            gherkin (run {:dir root :ok? false} (script "squad_packet.sh")
                         "review" "cave-graph" "gherkin" "accepted" "g-1" "master" sha)
            qa (run {:dir root :ok? false} (script "squad_packet.sh")
                    "review" "cave-graph" "qa-procedure" "accepted" "q-1" "master" sha)]
        (is (= 2 (:exit gherkin)))
        (is (= 2 (:exit qa)))
        (is (not (str/includes? (:err gherkin) "gherkin"))))
      (finally
        (fs/delete-tree root)))))

(deftest packet-approve-drops-story-and-final
  ;; Given a story packet
  ;; When leftover story or final approval is attempted
  ;; Then those gates are rejected
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "stories/cave-graph.md") "Rooms.\n")
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  "story_id: cave-graph\nstory_path: stories/cave-graph.md\n")
      (let [story (run {:dir root :ok? false} (script "squad_packet.sh")
                       "approve" "cave-graph" "story" "approved")
            final (run {:dir root :ok? false} (script "squad_packet.sh")
                       "approve" "cave-graph" "final" "ship-it")]
        (is (= 2 (:exit story)))
        (is (= 2 (:exit final))))
      (finally
        (fs/delete-tree root)))))
