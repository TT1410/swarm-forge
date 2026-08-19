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
                         "gherkin_approval: approved\n"))
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

(deftest implementer-after-plan-and-gherkin-not-waiting-for-qa-procedure
  ;; Given plan and Gherkin user-approved, no QA procedure
  ;; When residual runs
  ;; Then create_assignment implementer
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
        (is (str/includes? out "TEMPLATE: implementer"))
        (is (not (str/includes? out "qa-procedure-reviewer"))))
      (finally
        (fs/delete-tree root)))))
