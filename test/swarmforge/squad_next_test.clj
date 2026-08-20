(ns swarmforge.squad-next-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [swarmforge.test-support :refer :all]))

(deftest squad-next-reports-highest-priority-workflow-action
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "analyst-001\tanalyst-001\t" root "/.worktrees/analyst-001\tswarmforge-analyst-001\tAnalyst 001\tcodex\ttask\n"))
      (write-agent-status! root "analyst-001" "running")
      (write-file (fs/path root ".swarmforge/handoffs/inbox/new/50_20260803T000000Z_000001_from_analyst-001_to_squad-leader.handoff")
                  (str "type: git_handoff\n"
                       "to: squad-leader\n"
                       "from: analyst-001\n"
                       "priority: 50\n"
                       "task: story review\n"
                       "commit: abcdef1234\n\n"
                       "stories ready\n"))
      (let [new-handoff (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out new-handoff) "NEXT_ACTION: process_handoff"))
        (is (str/includes? (:out new-handoff) "FROM: analyst-001"))
        (is (str/includes? (:out new-handoff) "COMMAND: ready_for_next.sh")))
      (fs/create-dirs (fs/path root ".swarmforge/handoffs/inbox/in_process"))
      (fs/move (fs/path root ".swarmforge/handoffs/inbox/new/50_20260803T000000Z_000001_from_analyst-001_to_squad-leader.handoff")
               (fs/path root ".swarmforge/handoffs/inbox/in_process/50_20260803T000000Z_000001_from_analyst-001_to_squad-leader.handoff"))
      (let [in-process (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out in-process) "NEXT_ACTION: finish_in_process_handoff"))
        (is (str/includes? (:out in-process) "HANDOFF:"))
        (is (str/includes? (:out in-process) "done_with_current.sh"))
        (is (str/includes? (:out in-process) "SWARMFORGE_ROLE=squad-leader"))
        (is (str/includes? (:out in-process) "in_process/50_20260803T000000Z_000001_from_analyst-001_to_squad-leader.handoff")))
      (fs/create-dirs (fs/path root ".swarmforge/handoffs/inbox/completed"))
      (fs/move (fs/path root ".swarmforge/handoffs/inbox/in_process/50_20260803T000000Z_000001_from_analyst-001_to_squad-leader.handoff")
               (fs/path root ".swarmforge/handoffs/inbox/completed/50_20260803T000000Z_000001_from_analyst-001_to_squad-leader.handoff"))
      (let [retire (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out retire) "NEXT_ACTION: retire_agent"))
        (is (str/includes? (:out retire) "COMMAND: squad_retire.sh analyst-001")))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (let [needs-map (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out needs-map) "NEXT_ACTION: wait"))
        (is (not (str/includes? (:out needs-map) "write_theme_module_map"))))
      (write-file (fs/path root "module-map.md") minimal-module-map)
      (fs/delete-tree (fs/path root ".squad/approvals"))
      (write-file (fs/path root ".swarmforge/squad/spawn.lock/owner")
                  "pid: 999999999\n")
      (let [lock (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out lock) "NEXT_ACTION: clear_stale_lock"))
        (is (str/includes? (:out lock) "OWNER_PID: 999999999")))
      (fs/delete-tree (fs/path root ".swarmforge/squad/spawn.lock"))
      (write-file (fs/path root ".squad/spawn-requests/new/wumpus-impl.request")
                  "template: implementer\n")
      (let [spawn (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out spawn) "NEXT_ACTION: wait_for_spawn"))
        (is (str/includes? (:out spawn) "CHECK_AFTER_SECONDS: 10")))
      (fs/delete-tree (fs/path root ".squad/spawn-requests"))
      (fs/delete-tree (fs/path root ".squad/themes"))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "gherkin-writer-001\tgherkin-writer-001\t" root "/.worktrees/gherkin-writer-001\tswarmforge-gherkin-writer-001\tGherkin Writer 001\tcodex\ttask\n"))
      (write-agent-status! root "gherkin-writer-001" "running")
      (let [wait (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out wait) "NEXT_ACTION: wait"))
        (is (str/includes? (:out wait) "ACTIVE: gherkin-writer-001 gherkin-writer-001 running")))
	    (finally
	      (fs/delete-tree root)))))

(deftest squad-next-recovers-only-after-agent-goes-quiet
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root "swarmforge/squad.conf")
                  "recovery_quiet_seconds 5\nrecovery_retry_seconds 5\n")
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "analyst-001\tanalyst-001\t" root "/.worktrees/analyst-001\tswarmforge-analyst-001\tAnalyst 001\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/agents/analyst-001/metadata")
                  (str "agent: analyst-001\n"
                       "template: analyst\n"
                       "task_id: hunt-the-wumpus-analysis\n"
                       "session: swarmforge-analyst-001\n"))
      (write-agent-status! root "analyst-001" "running" "2026-08-03T00:00:00Z")
      (write-file (fs/path root ".squad/agents/analyst-001/liveness")
                  (str "state: running_pane_active\n"
                       "observed_at: 2026-08-03T00:00:08Z\n"
                       "pane_changed: true\n"
                       "pane_hash: fresh\n"
                       "last_10_lines:\nstill working\n"))
      (let [wait (run {:dir root
                       :env {"SWARMFORGE_NOW" "2026-08-03T00:00:10Z"}}
                      (script "squad_next.sh"))]
        (is (str/includes? (:out wait) "NEXT_ACTION: wait"))
        (is (str/includes? (:out wait) "quiet_for=2"))
        (is (str/includes? (:out wait) "activity_source=pane")))
      (write-file (fs/path root ".squad/agents/analyst-001/liveness")
                  (str "state: running_pane_active\n"
                       "observed_at: 2026-08-03T00:00:08Z\n"
                       "pane_changed: true\n"
                       "pane_hash: stale\n"
                       "last_10_lines:\nlast activity\n"))
      (let [recover (run {:dir root
                          :env {"SWARMFORGE_NOW" "2026-08-03T00:00:14Z"}}
                         (script "squad_next.sh"))]
        (is (str/includes? (:out recover) "NEXT_ACTION: recover_agent"))
        (is (str/includes? (:out recover) "AGENT: analyst-001"))
        (is (str/includes? (:out recover) "QUIET_FOR_SECONDS: 6"))
        (is (str/includes? (:out recover) "COMMAND: squad_recover.sh analyst-001")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-counts-failed-transients-against-capacity-and-wait
  ;; Failed is a temporary lifecycle state; the agent still occupies a slot.
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "gherkin-writer-001\tgherkin-writer-001\t" root "/.worktrees/gherkin-writer-001\tswarmforge-gherkin-writer-001\tGherkin Writer 001\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/agents/gherkin-writer-001/metadata")
                  "template: gherkin-writer\ntask_id: alpha-gherkin\n")
      (write-agent-status! root "gherkin-writer-001" "failed")
      (let [wait (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out wait) "NEXT_ACTION: wait"))
        (is (str/includes? (:out wait) "ACTIVE: gherkin-writer-001"))
        (is (str/includes? (:out wait) "REASON: active agents are still working or awaiting handoff delivery")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-processes-claimed-git-handoff-before-completion
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "analyst-001\tanalyst-001\t" root "/.worktrees/analyst-001\tswarmforge-analyst-001\tAnalyst 001\tcodex\ttask\n"))
      (write-agent-status! root "analyst-001" "handoff_sent")
      (write-file (fs/path root ".swarmforge/handoffs/inbox/in_process/50_20260803T000000Z_000001_from_analyst-001_to_squad-leader.handoff")
                  (str "type: git_handoff\n"
                       "to: squad-leader\n"
                       "from: analyst-001\n"
                       "priority: 50\n"
                       "task: wumpus-analysis\n"
                       "commit: abcdef1234\n"
                       "assignment: wumpus-analysis\n"
                       "agent: analyst-001\n"
                       "template: analyst\n"
                       "artifacts: stories/cave.md\n\n"
                       "merge_and_process analyst-001 abcdef1234\n"))
      (write-file (fs/path root ".squad/assignments/wumpus-analysis/metadata")
                  (str "assignment_id: wumpus-analysis\n"
                       "theme_id: wumpus\n"
                       "story_id: theme\n"
                       "template: analyst\n"
                       "assignment_file: " root "/instructions.md\n"))
      (write-file (fs/path root ".squad/assignments/wumpus-analysis/status")
                  "assignment_id: wumpus-analysis\nstate: in_progress\n")
      (let [record-result (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out record-result) "NEXT_ACTION: record_assignment_result"))
        (is (str/includes? (:out record-result) "COMMAND: squad_assign.sh result wumpus-analysis ")))
      (write-file (fs/path root ".squad/assignments/wumpus-analysis/status")
                  "assignment_id: wumpus-analysis\nstate: result_received\n")
      (let [accept-merge (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out accept-merge) "NEXT_ACTION: accept_merge"))
        (is (str/includes? (:out accept-merge) "COMMAND: squad_assign.sh accept-merge wumpus-analysis")))
      (write-file (fs/path root ".squad/assignments/wumpus-analysis/status")
                  "assignment_id: wumpus-analysis\nstate: merged\n")
      (let [finish (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out finish) "NEXT_ACTION: finish_in_process_handoff"))
        (is (str/includes? (:out finish) "done_with_current.sh"))
        (is (str/includes? (:out finish) "SWARMFORGE_ROLE=squad-leader")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-does-not-retire-completed-handoff-before-assignment-resolution
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "analyst-001\tanalyst-001\t" root "/.worktrees/analyst-001\tswarmforge-analyst-001\tAnalyst 001\tcodex\ttask\n"))
      (write-agent-status! root "analyst-001" "handoff_sent")
      (write-file (fs/path root ".swarmforge/handoffs/inbox/completed/50_20260803T000000Z_000001_from_analyst-001_to_squad-leader.handoff")
                  (str "type: git_handoff\n"
                       "to: squad-leader\n"
                       "from: analyst-001\n"
                       "priority: 50\n"
                       "task: wumpus-analysis\n"
                       "commit: abcdef1234\n\n"))
      (write-file (fs/path root ".squad/assignments/wumpus-analysis/metadata")
                  "assignment_id: wumpus-analysis\ntheme_id: wumpus\nstory_id: theme\ntemplate: analyst\n")
      (write-file (fs/path root ".squad/assignments/wumpus-analysis/status")
                  "assignment_id: wumpus-analysis\nstate: result_received\n")
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (not (str/includes? (:out next) "NEXT_ACTION: retire_agent"))))
      (write-file (fs/path root ".squad/assignments/wumpus-analysis/status")
                  "assignment_id: wumpus-analysis\nstate: merged\n")
      (let [retire (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out retire) "NEXT_ACTION: retire_agent"))
        (is (str/includes? (:out retire) "COMMAND: squad_retire.sh analyst-001")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-treats-merged-replacement-analysis-as-theme-analysis-complete
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root ".squad/assignments/wumpus-analysis/metadata")
                  (str "assignment_id: wumpus-analysis\n"
                       "theme_id: wumpus\n"
                       "story_id: theme\n"
                       "template: analyst\n"
                       "assignment_file: " root "/analysis.md\n"))
      (write-file (fs/path root ".squad/assignments/wumpus-analysis/status")
                  "assignment_id: wumpus-analysis\nstate: superseded\n")
      (write-file (fs/path root ".squad/assignments/wumpus-analysis-r2/metadata")
                  (str "assignment_id: wumpus-analysis-r2\n"
                       "theme_id: wumpus\n"
                       "story_id: theme\n"
                       "template: analyst\n"
                       "replaces: wumpus-analysis\n"
                       "assignment_file: " root "/analysis-r2.md\n"))
      (write-file (fs/path root ".squad/assignments/wumpus-analysis-r2/status")
                  "assignment_id: wumpus-analysis-r2\nstate: merged\n")
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (not (str/includes? (:out next) "TEMPLATE: analyst")))
        (is (str/includes? (:out next) "NEXT_ACTION: wait")))
      (finally
        (fs/delete-tree root)))))

(deftest leftover-theme-analyst-does-not-register-stories
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/alpha.md") "Story: alpha.\n")
      (write-file (fs/path root "stories/beta.md") "Story: beta.\n")
      (run {:dir root} "git" "add" "stories")
      (run {:dir root} "git" "commit" "-q" "-m" "Add analyst stories")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (write-file (fs/path root ".squad/assignments/wumpus-analysis/metadata")
                    (str "assignment_id: wumpus-analysis\n"
                         "theme_id: wumpus\n"
                         "story_id: theme\n"
                         "template: analyst\n"
                         "assignment_file: " root "/analysis.md\n"))
        (write-file (fs/path root ".squad/assignments/wumpus-analysis/status")
                    "assignment_id: wumpus-analysis\nstate: merged\n")
        (write-file (fs/path root ".squad/assignments/wumpus-analysis/result-manifest")
                    (str "assignment_id: wumpus-analysis\n"
                         "agent: analyst-001\n"
                         "template: analyst\n"
                         "commit: " sha "\n"
                         "artifacts: stories/beta.md,stories/alpha.md\n"))
        (write-file (fs/path root ".squad/assignments/wumpus-analysis/accepted-merge")
                    (str "assignment_id: wumpus-analysis\n"
                         "state: merged\n"
                         "commit: " sha "\n"
                         "merge_commit: " sha "\n")))
      (let [register (run {:dir root} (script "squad_next.sh"))]
        (is (not (str/includes? (:out register) "register_story_artifact")))
        (is (not (str/includes? (:out register) "squad_theme.sh story")))
        (is (not (str/includes? (:out register) "squad_packet.sh create wumpus"))))
      (let [applied (run {:dir root} (script "squad_next.sh") "--apply-mechanical")]
        (is (not (str/includes? (:out applied) "register_story_artifact")))
        (is (not (str/includes? (:out applied) "TEMPLATE: analyst"))))
      (finally
        (fs/delete-tree root)))))

(deftest leftover-theme-story-ref-does-not-register-a-packet
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/alpha.md")
                  "Story: alpha supplied directly to the squad leader.\n")
      (run {:dir root} "git" "add" "stories")
      (run {:dir root} "git" "commit" "-q" "-m" "Register direct story reference")
      (let [register (run {:dir root} (script "squad_next.sh"))]
        (is (not (str/includes? (:out register) "register_story_packet")))
        (is (not (str/includes? (:out register) "squad_packet.sh create wumpus")))
        (is (not (str/includes? (:out register) "TEMPLATE: gherkin-writer"))))
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root} (script "squad_packet.sh") "create" "alpha" "squad-leader" "master" sha))
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "NEXT_ACTION: create_assignment"))
        (is (str/includes? (:out next) "TEMPLATE: analyst"))
        (is (str/includes? (:out next) "STORY: alpha"))
        (is (not (str/includes? (:out next) "GATE: story"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-attaches-merged-qa-procedure-artifact-before-duplicate-assignment
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/alpha.md") "Story: alpha.\n")
      (write-file (fs/path root "qa/alpha.md") "# QA alpha\n")
      (run {:dir root} "git" "add" "stories" "qa")
      (run {:dir root} "git" "commit" "-q" "-m" "Add QA procedure")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root} (script "squad_packet.sh") "create" "alpha" "wumpus-analysis" "master" sha)
        (write-file (fs/path root ".squad/assignments/alpha-qa-procedure/metadata")
                    (str "assignment_id: alpha-qa-procedure\n"
                         "theme_id: wumpus\n"
                         "story_id: alpha\n"
                         "template: qa-procedure-writer\n"
                         "assignment_file: " root "/qa-instructions.md\n"))
        (write-file (fs/path root ".squad/assignments/alpha-qa-procedure/status")
                    "assignment_id: alpha-qa-procedure\nstate: merged\n")
        (write-file (fs/path root ".squad/assignments/alpha-qa-procedure/result-manifest")
                    (str "assignment_id: alpha-qa-procedure\n"
                         "agent: qa-procedure-writer-001\n"
                         "template: qa-procedure-writer\n"
                         "commit: " sha "\n"
                         "artifacts: qa/alpha.md\n"))
        (write-file (fs/path root ".squad/assignments/alpha-qa-procedure/accepted-merge")
                    (str "assignment_id: alpha-qa-procedure\n"
                         "state: merged\n"
                         "commit: " sha "\n"
                         "merge_commit: " sha "\n")))
      (let [attach (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out attach) "NEXT_ACTION: attach_story_artifact"))
        (is (str/includes? (:out attach) "TEMPLATE: qa-procedure-writer"))
        (is (str/includes? (:out attach) "COMMAND: squad_packet.sh attach alpha qa-procedure alpha-qa-procedure master")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-attaches-revised-artifact-when-path-unchanged
  ;; Given a packet already pointing at features/alpha.feature from the first writer
  ;; And a merged r2 writer with the same path but a new assignment id and sha
  ;; When squad_next runs
  ;; Then it emits attach_story_artifact for the r2 assignment
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "features/alpha.feature") "Feature: alpha revised\n")
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\n"
                       "theme_id: wumpus\n"
                       "gherkin_path: features/alpha.feature\n"
                       "gherkin_assignment: alpha-gherkin\n"
                       "gherkin_sha: 1111111111\n"
                       "gherkin_review: changes-requested\n"
                       "gherkin_review_assignment: alpha-gherkin-review\n"
                       "gherkin_review_sha: 1111111111\n"
                       "gherkin_review_target_sha: 1111111111\n"))
      (write-file (fs/path root ".squad/assignments/alpha-gherkin-r2/metadata")
                  (str "assignment_id: alpha-gherkin-r2\n"
                       "theme_id: wumpus\n"
                       "story_id: alpha\n"
                       "template: gherkin-writer\n"
                       "assignment_file: " root "/instructions.md\n"))
      (write-file (fs/path root ".squad/assignments/alpha-gherkin-r2/status")
                  "assignment_id: alpha-gherkin-r2\nstate: merged\n")
      (write-file (fs/path root ".squad/assignments/alpha-gherkin-r2/result-manifest")
                  (str "assignment_id: alpha-gherkin-r2\n"
                       "agent: gherkin-writer-002\n"
                       "template: gherkin-writer\n"
                       "commit: 2222222222\n"
                       "artifacts: features/alpha.feature\n"))
      (write-file (fs/path root ".squad/assignments/alpha-gherkin-r2/accepted-merge")
                  (str "assignment_id: alpha-gherkin-r2\n"
                       "state: merged\n"
                       "commit: 2222222222\n"
                       "merge_commit: abcdef2222\n"))
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "NEXT_ACTION: attach_story_artifact"))
        (is (str/includes? (:out next) "ASSIGNMENT: alpha-gherkin-r2"))
        (is (str/includes? (:out next)
                           "COMMAND: squad_packet.sh attach alpha gherkin alpha-gherkin-r2 master abcdef2222 features/alpha.feature")))
      (let [applied (:out (run {:dir root} (script "squad_next.sh") "--apply-mechanical"))
            packet (slurp (str (fs/path root ".squad/stories/alpha/packet")))]
        (is (str/includes? applied "attach_story_artifact"))
        (is (str/includes? packet "gherkin_assignment: alpha-gherkin-r2"))
        (is (str/includes? packet "gherkin_sha: abcdef2222")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-creates-implementer-when-implementation-ready
  ;; Given specs approved and currently accepted, implementation approved, no impl sha
  ;; When squad_next runs
  ;; Then it creates an implementer assignment with queued spawn
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "swarmforge/squad.conf") implementer-gate-conf)
      (write-file (fs/path root ".squad/themes/wumpus/implementation-order.md")
                  "")
      (write-nontrivial-checker! root)
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\n"
                       "theme_id: wumpus\n"
                       "implementation_plan_path: .squad/stories/alpha/plan.md\n"
                       "implementation_plan_approval: approved\n"
                       "gherkin_path: features/alpha.feature\n"
                       "gherkin_assignment: alpha-gherkin\n"
                       "gherkin_sha: abcdef1111\n"
                       "gherkin_approval: approved\n"
                       "qa_procedure_path: qa/alpha.md\n"
                       "qa_procedure_approval: approved\n"))

      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "NEXT_ACTION: create_assignment"))
        (is (str/includes? (:out next) "TEMPLATE: implementer"))
        (is (str/includes? (:out next) "ASSIGNMENT: alpha-implementation"))
        (is (str/includes? (:out next)
                           "COMMAND: squad_assign.sh create alpha implementer alpha-implementation --auto-instructions --queue-spawn"))
        (is (not (str/includes? (:out next) "NEXT_ACTION: wait"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-prefers-newer-revision-attach-over-older-writer
  ;; Given original and r2 writers both merged with the same path
  ;; When mechanical repair runs
  ;; Then the packet ends on the r2 assignment/sha without thrashing forever
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "features/alpha.feature") "Feature: alpha revised\n")
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\n"
                       "theme_id: wumpus\n"
                       "gherkin_path: features/alpha.feature\n"
                       "gherkin_assignment: alpha-gherkin\n"
                       "gherkin_sha: abcdef1111\n"
                       "gherkin_review: changes-requested\n"
                       "gherkin_review_assignment: alpha-gherkin-review\n"
                       "gherkin_review_target_sha: abcdef1111\n"))
      (doseq [[id sha] [["alpha-gherkin" "abcdef1111"]
                        ["alpha-gherkin-r2" "abcdef2222"]]]
        (write-file (fs/path root ".squad/assignments" id "metadata")
                    (str "assignment_id: " id "\ntheme_id: wumpus\nstory_id: alpha\ntemplate: gherkin-writer\n"))
        (write-file (fs/path root ".squad/assignments" id "status") "state: merged\n")
        (write-file (fs/path root ".squad/assignments" id "result-manifest")
                    (str "artifacts: features/alpha.feature\ncommit: " sha "\n"))
        (write-file (fs/path root ".squad/assignments" id "accepted-merge")
                    (str "merge_commit: " sha "\ncommit: " sha "\n")))
      (let [out (:out (run {:dir root} (script "squad_next.sh") "--apply-mechanical"))
            packet (slurp (str (fs/path root ".squad/stories/alpha/packet")))
            attach-count (count (re-seq #"APPLIED_TRANSITION: attach_story_artifact" out))]
        (is (<= attach-count 2) "must not thrash attaches between original and r2")
        (is (str/includes? packet "gherkin_assignment: alpha-gherkin-r2"))
        (is (str/includes? packet "gherkin_sha: abcdef2222")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-records-merged-direct-result-before-downstream-work
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\n"
                       "theme_id: wumpus\n"
                       "story_approval: approved\n"
                       "gherkin_review: accepted\n"
                       "qa_procedure_review: accepted\n"
                       "implementation_approval: approved\n"))
      (write-file (fs/path root ".squad/assignments/alpha-implementation/metadata")
                  (str "assignment_id: alpha-implementation\n"
                       "theme_id: wumpus\n"
                       "story_id: alpha\n"
                       "template: implementer\n"
                       "assignment_file: " root "/instructions.md\n"))
      (write-file (fs/path root ".squad/assignments/alpha-implementation/status")
                  "assignment_id: alpha-implementation\nstate: merged\n")
      (write-file (fs/path root ".squad/assignments/alpha-implementation/accepted-merge")
                  (str "assignment_id: alpha-implementation\n"
                       "state: merged\n"
                       "commit: 1111111111\n"
                       "merge_commit: abcdef1234\n"))
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "NEXT_ACTION: record_merged_result"))
        (is (str/includes? (:out next) "COMMAND: squad_packet.sh record alpha implementation alpha-implementation master abcdef1234")))
      (finally
        (fs/delete-tree root)))))
(deftest squad-next-apply-mechanical-records-safe-repairs-before-next-action
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\n"
                       "theme_id: wumpus\n"))
      (write-file (fs/path root ".squad/assignments/alpha-implementation/metadata")
                  (str "assignment_id: alpha-implementation\n"
                       "theme_id: wumpus\n"
                       "story_id: alpha\n"
                       "template: implementer\n"
                       "assignment_file: " root "/instructions.md\n"))
      (write-file (fs/path root ".squad/assignments/alpha-implementation/status")
                  "assignment_id: alpha-implementation\nstate: merged\n")
      (write-file (fs/path root ".squad/assignments/alpha-implementation/accepted-merge")
                  (str "assignment_id: alpha-implementation\n"
                       "state: merged\n"
                       "commit: 1111111111\n"
                       "merge_commit: abcdef1234\n"))
      (let [next (run {:dir root} (script "squad_next.sh") "--apply-mechanical")
            packet (slurp (str (fs/path root ".squad/stories/alpha/packet")))]
        (is (str/includes? (:out next) "APPLIED_TRANSITION: record_merged_result story=alpha assignment=alpha-implementation batch=none exit=0"))
        (is (str/includes? packet "implementation_sha: abcdef1234")))
      (finally
        (fs/delete-tree root)))))
(deftest squad-next-selects-deterministic-story-candidates
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/alpha.md")
                  "Story: alpha.\n")
      (write-file (fs/path root "stories/beta.md")
                  "Story: beta.\n")
      (run {:dir root} "git" "add" "stories")
      (run {:dir root} "git" "commit" "-q" "-m" "Prepare alpha and beta stories")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root} (script "squad_packet.sh") "create" "beta" "analysis-beta" "master" sha)
        (run {:dir root} (script "squad_packet.sh") "create" "alpha" "analysis-alpha" "master" sha))
      (let [first (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out first) "NEXT_ACTION: create_assignment"))
        (is (str/includes? (:out first) "STORY: alpha"))
        (is (str/includes? (:out first) "TEMPLATE: analyst")))
      (doseq [story ["alpha" "beta"]]
        (mark-implementation-plan-approved! root story))
      (let [create-assignment (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out create-assignment) "NEXT_ACTION: create_assignment"))
        (is (str/includes? (:out create-assignment) "STORY: alpha"))
        (is (str/includes? (:out create-assignment) "TEMPLATE: gherkin-writer"))
        (is (re-find #"COMMAND: squad_assign.sh create alpha gherkin-writer alpha-gherkin --auto-instructions --queue-spawn"
                     (:out create-assignment))))
      (write-file (fs/path root "instructions.md")
                  "Write Gherkin.\n")
      (write-file (fs/path root ".squad/assignments/alpha-gherkin/metadata")
                  (str "assignment_id: alpha-gherkin\n"
                       "theme_id: wumpus\n"
                       "story_id: alpha\n"
                       "template: gherkin-writer\n"
                       "assignment_file: " root "/instructions.md\n"
                       "created_at: 2026-08-03T00:00:00Z\n"))
      (write-file (fs/path root ".squad/assignments/alpha-gherkin/status")
                  (str "assignment_id: alpha-gherkin\n"
                       "state: created\n"
                       "detail: gherkin-writer for alpha\n"
                       "updated_at: 2026-08-03T00:00:00Z\n"))
      (let [spawn (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out spawn) "NEXT_ACTION: request_spawn"))
        (is (str/includes? (:out spawn) "STORY: alpha"))
        (is (str/includes? (:out spawn) "ASSIGNMENT: alpha-gherkin"))
        (is (str/includes? (:out spawn) "COMMAND: squad_spawn_request.sh gherkin-writer alpha-gherkin")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-does-not-implement-before-accepted-gherkin-and-qa
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root "swarmforge/squad.conf")
                  "max_transient_agents 2\napproval_required implementation false\n")
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "gherkin-writer-001\tgherkin-writer-001\t" root "/.worktrees/gherkin-writer-001\tswarmforge-gherkin-writer-001\tGherkin Writer 001\tcodex\ttask\n"
                       "qa-procedure-writer-001\tqa-procedure-writer-001\t" root "/.worktrees/qa-procedure-writer-001\tswarmforge-qa-procedure-writer-001\tQA Procedure Writer 001\tcodex\ttask\n"))
      (write-agent-status! root "gherkin-writer-001" "running")
      (write-agent-status! root "qa-procedure-writer-001" "running")
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/alpha.md")
                  "Story: alpha.\n")
      (run {:dir root} "git" "add" "stories")
      (run {:dir root} "git" "commit" "-q" "-m" "Prepare alpha story")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root} (script "squad_packet.sh") "create" "alpha" "analysis-alpha" "master" sha))
      (mark-implementation-plan-approved! root "alpha")
      (doseq [[assignment-id template agent-id] [["alpha-gherkin" "gherkin-writer" "gherkin-writer-001"]
                                                 ["alpha-qa-procedure" "qa-procedure-writer" "qa-procedure-writer-001"]]]
        (write-file (fs/path root ".squad/assignments" assignment-id "metadata")
                    (str "assignment_id: " assignment-id "\n"
                         "theme_id: wumpus\n"
                         "story_id: alpha\n"
                         "template: " template "\n"
                         "assignment_file: " root "/instructions.md\n"
                         "created_at: 2026-08-03T00:00:00Z\n"))
        (write-file (fs/path root ".squad/assignments" assignment-id "status")
                    (str "assignment_id: " assignment-id "\n"
                         "state: created\n"
                         "detail: " template " for alpha\n"
                         "updated_at: 2026-08-03T00:00:00Z\n"))
        (write-file (fs/path root ".squad/agents" agent-id "metadata")
                    (str "agent_id: " agent-id "\n"
                         "task_id: " assignment-id "\n"
                         "template: " template "\n"
                         "session: swarmforge-" agent-id "\n"))
        (write-file (fs/path root ".squad/agents" agent-id "status")
                    (str "agent_id: " agent-id "\n"
                         "state: running\n"
                         "detail: active\n"
                         "updated_at: 2026-08-03T00:00:00Z\n")))
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "NEXT_ACTION: wait"))
        (is (not (str/includes? (:out next) "TEMPLATE: implementer"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-spawns-ready-assignment-before-repeating-pending-approval
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "instructions.md") "Revise the artifact.\n")
      (write-file (fs/path root ".squad/assignments/alpha-revision/metadata")
                  (str "assignment_id: alpha-revision\n"
                       "theme_id: wumpus\n"
                       "story_id: alpha\n"
                       "template: gherkin-writer\n"
                       "assignment_file: " root "/instructions.md\n"
                       "created_at: 2026-08-03T00:00:00Z\n"))
      (write-file (fs/path root ".squad/assignments/alpha-revision/status")
                  (str "assignment_id: alpha-revision\n"
                       "state: created\n"
                       "detail: revision ready\n"
                       "updated_at: 2026-08-03T00:00:00Z\n"))
      (write-file (fs/path root ".squad/approvals/pending/story__beta.approval")
                  (str "approval_id: story__beta\n"
                       "target_kind: story\n"
                       "target_id: beta\n"
                       "gate: story\n"
                       "state: pending\n"
                       "title: Approve story\n"
                       "reason: beta ready\n"))
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "NEXT_ACTION: request_spawn"))
        (is (str/includes? (:out next) "ASSIGNMENT: alpha-revision"))
        (is (not (str/includes? (:out next) "NEXT_ACTION: request_user_approval"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-routes-code-review-recs-to-hardener
  ;; Given CR recorded changes-requested
  ;; When residual runs
  ;; Then hardener applies the recs; no implementer rework
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md") "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md") "Story: cave topology and setup.\n")
      (write-file (fs/path root "swarmforge/squad.conf") implementer-gate-conf)
      (let [sha (prepare-implementation-packet! root "wumpus" "cave-topology")]
        (run {:dir root} (script "squad_packet.sh") "approve" "cave-topology" "implementation" "approved")
        (run {:dir root} (script "squad_packet.sh") "record" "cave-topology" "implementation" "impl-1" "master" sha)
        (run {:dir root} (script "squad_packet.sh") "record" "cave-topology" "cleaner" "clean-1" "master" sha)
        (run {:dir root} (script "squad_packet.sh") "review" "cave-topology" "code" "changes-requested" "review-1" "master" sha)
        (let [next (run {:dir root} (script "squad_next.sh"))]
          (is (or (str/includes? (:out next) "TEMPLATE: hardener")
                  (str/includes? (:out next) "hardener")))
          (is (not (str/includes? (:out next) "TEMPLATE: implementer")))
          (is (not (str/includes? (:out next) "code review requested implementation changes")))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-does-not-spawn-repeat-code-reviewer-for-same-cleaner
  ;; Given cleaner_sha and a merged code-reviewer whose decision was never recorded
  ;; When squad_next runs
  ;; Then it must not create code-review-r2; at most one reviewer per cleaner version
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\n"
                       "theme_id: wumpus\n"
                       "implementation_sha: abcdef1111\n"
                       "cleaner_sha: abcdef1111\n"
                       "cleaner_assignment: alpha-cleaner\n"))
      (write-file (fs/path root ".squad/assignments/alpha-cleaner/metadata")
                  "assignment_id: alpha-cleaner\ntheme_id: wumpus\nstory_id: alpha\ntemplate: cleaner\n")
      (write-file (fs/path root ".squad/assignments/alpha-cleaner/status") "state: merged\n")
      (write-file (fs/path root ".squad/assignments/alpha-code-review/metadata")
                  "assignment_id: alpha-code-review\ntheme_id: wumpus\nstory_id: alpha\ntemplate: code-reviewer\n")
      (write-file (fs/path root ".squad/assignments/alpha-code-review/status") "state: merged\n")
      (write-file (fs/path root ".squad/assignments/alpha-code-review/accepted-merge")
                  "merge_commit: abcdef9999\ncommit: abcdef9999\n")
      (write-file (fs/path root ".squad/assignments/alpha-code-review/review.md")
                  "Recommendation: revise\n")
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (not (str/includes? (:out next) "alpha-code-review-r2")))
        (is (not (str/includes? (:out next) "TEMPLATE: code-reviewer"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-does-not-create-code-reviewer-after-new-cleaner-version
  ;; One CR per story. A second cleaner after the first CR does not spawn CR-r2.
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\n"
                       "theme_id: wumpus\n"
                       "implementation_sha: abcdef2222\n"
                       "cleaner_sha: abcdef2222\n"
                       "cleaner_assignment: alpha-cleaner-r2\n"
                       "code_review_iterations: alpha-code-review=changes-requested\n"))
      (doseq [id ["alpha-cleaner" "alpha-cleaner-r2"]]
        (write-file (fs/path root ".squad/assignments" id "metadata")
                    (str "assignment_id: " id "\ntheme_id: wumpus\nstory_id: alpha\ntemplate: cleaner\n"))
        (write-file (fs/path root ".squad/assignments" id "status") "state: merged\n"))
      (write-file (fs/path root ".squad/assignments/alpha-code-review/metadata")
                  "assignment_id: alpha-code-review\ntheme_id: wumpus\nstory_id: alpha\ntemplate: code-reviewer\n")
      (write-file (fs/path root ".squad/assignments/alpha-code-review/status") "state: merged\n")
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (not (str/includes? (:out next) "alpha-code-review-r2")))
        (is (not (str/includes? (:out next) "TEMPLATE: code-reviewer"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-creates-first-code-reviewer-for-cleaned-story
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\n"
                       "theme_id: wumpus\n"
                       "implementation_sha: abcdef1111\n"
                       "cleaner_sha: abcdef1111\n"))
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "TEMPLATE: code-reviewer"))
        (is (str/includes? (:out next) "ASSIGNMENT: alpha-code-review"))
        (is (str/includes? (:out next) "--queue-spawn")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-does-not-retire-agent-while-its-handoff-is-merge-blocked
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "implementer-001\timplementer-001\t" root "/.worktrees/implementer-001\tswarmforge-implementer-001\tImplementer 001\tcodex\ttask\n"))
      (write-agent-status! root "implementer-001" "running")
      (write-file (fs/path root ".squad/agents/implementer-001/metadata")
                  "template: implementer\ntask_id: cave-impl\n")
      (write-file (fs/path root ".swarmforge/handoffs/inbox/completed/50_20260803T000000Z_000001_from_implementer-001_to_squad-leader.handoff")
                  (str "type: git_handoff\n"
                       "to: squad-leader\n"
                       "from: implementer-001\n"
                       "priority: 50\n"
                       "task: cave-impl\n"
                       "commit: abcdef1234\n\n"
                       "implementation ready\n"))
      (write-file (fs/path root ".squad/assignments/cave-impl/metadata")
                  (str "assignment_id: cave-impl\n"
                       "theme_id: wumpus\n"
                       "story_id: cave-topology\n"
                       "template: implementer\n"
                       "assignment_file: " root "/instructions.md\n"
                       "created_at: 2026-08-03T00:00:00Z\n"))
      (write-file (fs/path root ".squad/assignments/cave-impl/status")
                  (str "assignment_id: cave-impl\n"
                       "state: merge_blocked\n"
                       "detail: accepted merge failed\n"
                       "updated_at: 2026-08-03T00:00:00Z\n"))
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (not (str/includes? (:out next) "NEXT_ACTION: retire_agent")))
        (is (not (str/includes? (:out next) "TEMPLATE: merger")))
        (is (not (str/includes? (:out next) "create-merger"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-retires-source-agent-after-assignment-merged-to-main
  ;; Given source assignment resolved to merged (work landed on main)
  ;; When squad_next runs
  ;; Then retire the held source agent
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "implementer-001\timplementer-001\t" root "/.worktrees/implementer-001\tswarmforge-implementer-001\tImplementer 001\tcodex\ttask\n"))
      (write-agent-status! root "implementer-001" "handoff_sent")
      (write-file (fs/path root ".squad/agents/implementer-001/metadata")
                  "template: implementer\ntask_id: cave-impl\n")
      (write-file (fs/path root ".swarmforge/handoffs/inbox/completed/50_20260803T000000Z_000001_from_implementer-001_to_squad-leader.handoff")
                  (str "type: git_handoff\n"
                       "to: squad-leader\n"
                       "from: implementer-001\n"
                       "priority: 50\n"
                       "task: cave-impl\n"
                       "commit: abcdef1234\n\n"
                       "implementation ready\n"))
      (write-file (fs/path root ".squad/assignments/cave-impl/metadata")
                  (str "assignment_id: cave-impl\n"
                       "theme_id: wumpus\n"
                       "story_id: cave-topology\n"
                       "template: implementer\n"
                       "assignment_file: " root "/instructions.md\n"
                       "created_at: 2026-08-03T00:00:00Z\n"))
      (write-file (fs/path root ".squad/assignments/cave-impl/status")
                  (str "assignment_id: cave-impl\n"
                       "state: merged\n"
                       "detail: resolved by merger assignment cave-impl-merge\n"
                       "updated_at: 2026-08-03T00:00:00Z\n"))
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "NEXT_ACTION: retire_agent"))
        (is (str/includes? (:out next) "AGENT: implementer-001"))
        (is (str/includes? (:out next) "COMMAND: squad_retire.sh implementer-001")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-concurrent-actions-retire-before-spawn-when-capacity-full
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root "swarmforge/squad.conf")
                  "max_transient_agents 1\n")
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "implementer-001\timplementer-001\t" root "/.worktrees/implementer-001\tswarmforge-implementer-001\tImplementer 001\tcodex\ttask\n"))
      (write-agent-status! root "implementer-001" "handoff_sent")
      (write-file (fs/path root ".squad/agents/implementer-001/metadata")
                  "template: implementer\ntask_id: alpha-implementation\n")
      (write-file (fs/path root ".swarmforge/handoffs/inbox/completed/50_20260803T000000Z_000001_from_implementer-001_to_squad-leader.handoff")
                  (str "type: git_handoff\n"
                       "to: squad-leader\n"
                       "from: implementer-001\n"
                       "priority: 50\n"
                       "task: alpha-implementation\n"
                       "commit: abcdef1234\n\n"))
      (write-file (fs/path root ".squad/assignments/alpha-implementation/metadata")
                  (str "assignment_id: alpha-implementation\n"
                       "theme_id: wumpus\n"
                       "story_id: alpha\n"
                       "template: implementer\n"
                       "assignment_file: " root "/impl.md\n"))
      (write-file (fs/path root ".squad/assignments/alpha-implementation/status")
                  "assignment_id: alpha-implementation\nstate: merged\n")
      (write-file (fs/path root "clean.md") "Clean alpha.\n")
      (write-file (fs/path root ".squad/assignments/alpha-cleaner/metadata")
                  (str "assignment_id: alpha-cleaner\n"
                       "theme_id: wumpus\n"
                       "story_id: alpha\n"
                       "template: cleaner\n"
                       "assignment_file: " root "/clean.md\n"
                       "created_at: 2026-08-03T00:00:00Z\n"))
      (write-file (fs/path root ".squad/assignments/alpha-cleaner/status")
                  "assignment_id: alpha-cleaner\nstate: created\n")
      (let [first-next (run {:dir root} (script "squad_next.sh"))
            second-next (run {:dir root} (script "squad_next.sh"))]
        (doseq [next [first-next second-next]]
          (is (str/includes? (:out next) "NEXT_ACTION: retire_agent"))
          (is (str/includes? (:out next) "CONCURRENT_ACTIONS: 2"))
          (is (str/includes? (:out next) "CONCURRENT_ACTION_NAME: retire_agent"))
          (is (str/includes? (:out next) "COMMAND: squad_retire.sh implementer-001"))
          (is (str/includes? (:out next) "CONCURRENT_ACTION_NAME: request_spawn"))
          (is (str/includes? (:out next) "CONCURRENT_COMMAND: squad_spawn_request.sh cleaner alpha-cleaner"))
          (is (str/includes? (:out next) "registry lock"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-serializes-multiple-retirements
  ;; Multiple completed agents must not all be concurrent retire commands.
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "implementer-001\timplementer-001\t" root "/.worktrees/implementer-001\tswarmforge-implementer-001\tI1\tcodex\ttask\n"
                       "cleaner-001\tcleaner-001\t" root "/.worktrees/cleaner-001\tswarmforge-cleaner-001\tC1\tcodex\ttask\n"))
      (doseq [[agent task] [["implementer-001" "alpha-implementation"]
                            ["cleaner-001" "alpha-cleaner"]]]
        (write-agent-status! root agent "handoff_sent")
        (write-file (fs/path root ".squad/agents" agent "metadata")
                    (str "template: " (first (str/split agent #"-")) "\ntask_id: " task "\n"))
        (write-file (fs/path root ".swarmforge/handoffs/inbox/completed"
                             (str "50_20260803T000000Z_00000" (if (= agent "implementer-001") "1" "2")
                                  "_from_" agent "_to_squad-leader.handoff"))
                    (str "type: git_handoff\nto: squad-leader\nfrom: " agent "\npriority: 50\n"
                         "task: " task "\ncommit: abcdef1234\n\n"))
        (write-file (fs/path root ".squad/assignments" task "metadata")
                    (str "assignment_id: " task "\ntheme_id: wumpus\nstory_id: alpha\n"
                         "template: " (first (str/split agent #"-")) "\n"))
        (write-file (fs/path root ".squad/assignments" task "status")
                    (str "assignment_id: " task "\nstate: merged\n")))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))
            retire-cmds (re-seq #"CONCURRENT_COMMAND: squad_retire\.sh \S+" out)]
        (is (str/includes? out "NEXT_ACTION: retire_agent"))
        (is (= 1 (count retire-cmds))
            "only one retire_agent may be concurrent; registry lock is exclusive")
        (is (str/includes? out "registry lock")))
      (let [applied (:out (run {:dir root} (script "squad_next.sh") "--apply-mechanical"))]
        (is (str/includes? applied "APPLIED_TRANSITION: retire_agent"))
        (is (str/includes? (slurp (str (fs/path root ".squad/agents/implementer-001/status")))
                           "state: retired")
            "mechanical apply retires sequentially without lock races")
        (is (str/includes? (slurp (str (fs/path root ".squad/agents/cleaner-001/status")))
                           "state: retired")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-concurrent-actions-respect-remaining-agent-capacity
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root "swarmforge/squad.conf")
                  "max_transient_agents 2\n")
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "implementer-001\timplementer-001\t" root "/.worktrees/implementer-001\tswarmforge-implementer-001\tImplementer 001\tcodex\ttask\n"))
      (write-agent-status! root "implementer-001" "running")
      (write-file (fs/path root ".squad/agents/implementer-001/metadata")
                  "template: implementer\ntask_id: busy-implementation\n")
      (doseq [assignment ["alpha-cleaner" "beta-cleaner"]]
        (write-file (fs/path root (str assignment ".md")) "Clean.\n")
        (write-file (fs/path root ".squad/assignments" assignment "metadata")
                    (str "assignment_id: " assignment "\n"
                         "theme_id: wumpus\n"
                         "story_id: " (first (str/split assignment #"-")) "\n"
                         "template: cleaner\n"
                         "assignment_file: " root "/" assignment ".md\n"
                         "created_at: 2026-08-03T00:00:00Z\n"))
        (write-file (fs/path root ".squad/assignments" assignment "status")
                    (str "assignment_id: " assignment "\nstate: created\n")))
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "NEXT_ACTION: request_spawn"))
        (is (str/includes? (:out next) "CONCURRENT_ACTIONS: 1"))
        (is (str/includes? (:out next) "COMMAND: squad_spawn_request.sh cleaner alpha-cleaner"))
        (is (not (str/includes? (:out next) "CONCURRENT_COMMAND: squad_spawn_request.sh cleaner beta-cleaner"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-emits-create-batch-for-batch-assignments
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "swarmforge/squad.conf")
                  "approval_required code_review false\n")
      (write-file (fs/path root "theme.md") "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md") "Story: cave topology and setup.\n")
      (let [sha (prepare-implementation-packet! root "wumpus" "cave-topology")]
        (run {:dir root} (script "squad_packet.sh") "approve" "cave-topology" "implementation" "approved")
        (run {:dir root} (script "squad_packet.sh") "record" "cave-topology" "implementation" "impl-1" "master" sha)
        (run {:dir root} (script "squad_packet.sh") "record" "cave-topology" "cleaner" "clean-1" "master" sha)
        (run {:dir root} (script "squad_packet.sh") "review" "cave-topology" "code" "accepted" "review-1" "master" sha)
        (run {:dir root} (script "squad_packet.sh") "approve" "cave-topology" "code-review" "approved")
        (run {:dir root} (script "squad_packet.sh") "batch" "cave-topology" "hardener" "wumpus-hardener" "code_reviewed" "review-1" "master" sha)
        (let [next (run {:dir root} (script "squad_next.sh"))]
          (is (str/includes? (:out next) "NEXT_ACTION: create_assignment"))
          (is (str/includes? (:out next) "STORY: batch"))
          (is (str/includes? (:out next) "TEMPLATE: hardener"))
          (is (str/includes? (:out next) "COMMAND: squad_assign.sh create-batch hardener"))
          (is (not (str/includes? (:out next) "squad_assign.sh create batch")))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-fills-open-batch-before-creating-batch-assignment
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root "swarmforge/squad.conf")
                  "approval_required code_review false\n")
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md") "Implement a faithful Hunt the Wumpus.\n")
      (doseq [story ["alpha" "beta"]]
        (write-file (fs/path root "stories" (str story ".md")) (str "Story: " story ".\n")))
      (doseq [story ["alpha" "beta"]]
        (let [sha (prepare-implementation-packet! root "wumpus" story)]
          (run {:dir root} (script "squad_packet.sh") "approve" story "implementation" "approved")
          (run {:dir root} (script "squad_packet.sh") "record" story "implementation" (str story "-impl") "master" sha)
          (run {:dir root} (script "squad_packet.sh") "record" story "cleaner" (str story "-clean") "master" sha)
          (run {:dir root} (script "squad_packet.sh") "review" story "code" "accepted" (str story "-review") "master" sha)
          (run {:dir root} (script "squad_packet.sh") "approve" story "code-review" "approved")))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "TEMPLATE: hardener"))
        (is (str/includes? out "create-batch hardener"))
        (is (str/includes? out "STORY: alpha"))
        (is (str/includes? out "STORY: beta")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-assign-create-batch-refuses-missing-batch-manifest
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (fs/create-dirs (fs/path root "swarmforge"))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "instructions.md") "Harden the batch.\n")
      (let [result (run {:dir root :ok? false}
                        (script "squad_assign.sh")
                        "create-batch"
                        "hardener"
                        "wumpus-hardener"
                        "instructions.md")]
        (is (= 2 (:exit result)))
        (is (str/includes? (:err result) "Batch record is missing: wumpus-hardener")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-batch-closes-on-assignment-create-and-completes-after-result
  ;; Lifecycle: open -> add members -> create-batch closes admission ->
  ;; result_received -> packet projection -> complete. New stories need a new batch.
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "swarmforge/role-templates/hardener.prompt") "Harden.\n")
      (write-file (fs/path root "swarmforge/role-templates/hardener.contract.edn")
                  "{:handoff-targets [\"squad-leader\"]}\n")
      (write-file (fs/path root "theme.md") "Theme\n")
      (write-file (fs/path root "stories/alpha.md") "Story alpha\n")
      (run {:dir root} (script "squad_batch.sh") "create" "hardener" "wumpus-hardener")
      (run {:dir root} (script "squad_batch.sh") "add" "wumpus-hardener" "alpha"
           "code_reviewed" "alpha-review" "master" "abcdef1111")
      (write-file (fs/path root "instructions.md") "Harden the batch.\n")
      (run {:dir root} (script "squad_assign.sh") "create-batch" "hardener"
           "wumpus-hardener" "instructions.md")
      (let [status (slurp (str (fs/path root ".squad/batches/wumpus-hardener/status")))]
        (is (str/includes? status "state: closed")
            "batch assignment create must close admission"))
      (let [blocked (run {:dir root :ok? false}
                         (script "squad_batch.sh") "add" "wumpus-hardener" "beta"
                         "code_reviewed" "beta-review" "master" "abcdef1111")]
        (is (= 2 (:exit blocked)))
        (is (str/includes? (:err blocked) "not open for new members")))
      (run {:dir root} (script "squad_batch.sh") "result" "wumpus-hardener"
           "wumpus-hardener" "master" "aa11bb22cc")
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\n"
                       "theme_id: wumpus\n"
                       "hardener_batch: wumpus-hardener\n"
                       "code_review: accepted\n"
                       "cleaner_sha: abcdef1111\n"))
      (write-file (fs/path root ".squad/assignments/wumpus-hardener/status")
                  "assignment_id: wumpus-hardener\nstate: merged\n")
      (write-file (fs/path root ".squad/assignments/wumpus-hardener/accepted-merge")
                  "merge_commit: aa11bb22cc\ncommit: aa11bb22cc\n")
      (let [out (:out (run {:dir root} (script "squad_next.sh") "--apply-mechanical"))
            batch-status (slurp (str (fs/path root ".squad/batches/wumpus-hardener/status")))
            packet (slurp (str (fs/path root ".squad/stories/alpha/packet")))]
        (is (str/includes? packet "hardener_sha: aa11bb22cc"))
        (is (str/includes? out "complete_batch"))
        (is (str/includes? batch-status "state: complete")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-projects-batch-result-sha-onto-all-member-packets
  ;; Given a hardener batch with two stories and a durable batch result SHA
  ;; (assignment merged without accepted-merge/result-manifest on the assignment)
  ;; When mechanical repair runs
  ;; Then both story packets receive hardener_sha and the batch becomes complete
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "swarmforge/squad.conf")
                  (str "approval_required code_review false\n"
                       "approval_required hardening false\n"))
      (doseq [story ["alpha" "beta"]]
        (write-file (fs/path root ".squad/stories" story "packet")
                    (str "story_id: " story "\n"
                         "theme_id: wumpus\n"
                         "cleaner_sha: abcdef1234\n"
                         "code_review: accepted\n"
                         "code_review_sha: abcdef1234\n"
                         "hardener_batch: wumpus-hardener\n"
                         "hardener_batch_stage: code_reviewed\n")))
      (write-file (fs/path root ".squad/batches/wumpus-hardener/metadata")
                  "batch_id: wumpus-hardener\nkind: hardener\ncreated_at: 2026-08-10T00:00:00Z\n")
      (write-file (fs/path root ".squad/batches/wumpus-hardener/status")
                  "batch_id: wumpus-hardener\nkind: hardener\nstate: result_received\nupdated_at: 2026-08-10T00:00:00Z\n")
      (write-file (fs/path root ".squad/batches/wumpus-hardener/result")
                  (str "batch_id: wumpus-hardener\n"
                       "kind: hardener\n"
                       "assignment_id: wumpus-hardener\n"
                       "branch: master\n"
                       "sha: aa11bb22cc\n"
                       "received_at: 2026-08-10T00:00:00Z\n"))
      (write-file (fs/path root ".squad/batches/wumpus-hardener/manifest.tsv")
                  (str "story_id\tstage\tassignment_id\tbranch\tsha\tadded_at\n"
                       "alpha\tcode_reviewed\talpha-review\tmaster\tabcdef1234\t2026-08-10T00:00:00Z\n"
                       "beta\tcode_reviewed\tbeta-review\tmaster\tabcdef1234\t2026-08-10T00:00:00Z\n"))
      (write-file (fs/path root ".squad/assignments/wumpus-hardener/metadata")
                  (str "assignment_id: wumpus-hardener\n"
                       "theme_id: wumpus\n"
                       "story_id: batch\n"
                       "template: hardener\n"
                       "assignment_file: " root "/hardener.md\n"))
      (write-file (fs/path root ".squad/assignments/wumpus-hardener/status")
                  "assignment_id: wumpus-hardener\nstate: merged\n")
      (let [out (:out (run {:dir root} (script "squad_next.sh") "--apply-mechanical"))
            alpha (slurp (str (fs/path root ".squad/stories/alpha/packet")))
            beta (slurp (str (fs/path root ".squad/stories/beta/packet")))
            batch-status (slurp (str (fs/path root ".squad/batches/wumpus-hardener/status")))]
        (is (str/includes? out "record_merged_batch_result"))
        (is (str/includes? alpha "hardener_sha: aa11bb22cc"))
        (is (str/includes? beta "hardener_sha: aa11bb22cc"))
        (is (or (str/includes? alpha "state: hardener_returned")
                (str/includes? alpha "state: hardening_approved"))
            "stage advances from durable hardener result on the packet")
        (is (or (str/includes? beta "state: hardener_returned")
                (str/includes? beta "state: hardening_approved")))
        (is (str/includes? batch-status "state: complete")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-projects-batch-result-from-replacement-assignment-batch-id
  ;; Given batch members under original batch id, assignment replaced then merged
  ;; When mechanical repair runs
  ;; Then packets receive hardener_sha from the replacement assignment
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "swarmforge/squad.conf")
                  "approval_required hardening false\n")
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\ntheme_id: wumpus\n"
                       "cleaner_sha: abcdef1234\ncode_review: accepted\ncode_review_sha: abcdef1234\n"
                       "hardener_batch: wumpus-hardener\nhardener_batch_stage: code_reviewed\n"))
      (write-file (fs/path root ".squad/batches/wumpus-hardener/metadata")
                  "batch_id: wumpus-hardener\nkind: hardener\n")
      (write-file (fs/path root ".squad/batches/wumpus-hardener/status")
                  "batch_id: wumpus-hardener\nkind: hardener\nstate: closed\n")
      (write-file (fs/path root ".squad/batches/wumpus-hardener/manifest.tsv")
                  (str "story_id\tstage\tassignment_id\tbranch\tsha\tadded_at\n"
                       "alpha\tcode_reviewed\talpha-review\tmaster\tabcdef1234\t2026-08-10T00:00:00Z\n"))
      (write-file (fs/path root ".squad/assignments/wumpus-hardener/metadata")
                  "assignment_id: wumpus-hardener\ntheme_id: wumpus\nstory_id: batch\ntemplate: hardener\n")
      (write-file (fs/path root ".squad/assignments/wumpus-hardener/status")
                  "state: superseded\ndetail: wumpus-hardener-r2\n")
      (write-file (fs/path root ".squad/assignments/wumpus-hardener-r2/metadata")
                  (str "assignment_id: wumpus-hardener-r2\ntheme_id: wumpus\nstory_id: batch\n"
                       "template: hardener\nreplaces: wumpus-hardener\nbatch_id: wumpus-hardener\n"
                       "assignment_file: " root "/h.md\n"))
      (write-file (fs/path root ".squad/assignments/wumpus-hardener-r2/status")
                  "assignment_id: wumpus-hardener-r2\nstate: merged\n")
      (write-file (fs/path root ".squad/assignments/wumpus-hardener-r2/accepted-merge")
                  "merge_commit: bb22cc33dd\ncommit: bb22cc33dd\n")
      (let [out (:out (run {:dir root} (script "squad_next.sh") "--apply-mechanical"))
            packet (slurp (str (fs/path root ".squad/stories/alpha/packet")))]
        (is (str/includes? out "record_merged_batch_result"))
        (is (str/includes? packet "hardener_sha: bb22cc33dd")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-projects-batch-result-even-when-assignment-not-yet-merged
  ;; Durable batch result alone is enough to project onto member packets
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\ntheme_id: wumpus\n"
                       "hardener_batch: wumpus-qa\n"
                       "hardener_sha: abcdef1234\n"
                       "hardening_approval: approved\n"
                       "qa_batch: wumpus-qa\n"))
      (write-file (fs/path root ".squad/batches/wumpus-qa/metadata")
                  "batch_id: wumpus-qa\nkind: qa\n")
      (write-file (fs/path root ".squad/batches/wumpus-qa/status")
                  "batch_id: wumpus-qa\nkind: qa\nstate: result_received\n")
      (write-file (fs/path root ".squad/batches/wumpus-qa/result")
                  "batch_id: wumpus-qa\nkind: qa\nassignment_id: wumpus-qa\nbranch: master\nsha: dd11ee22ff\n")
      (write-file (fs/path root ".squad/batches/wumpus-qa/manifest.tsv")
                  (str "story_id\tstage\tassignment_id\tbranch\tsha\tadded_at\n"
                       "alpha\thardening_approved\twumpus-hardener\tmaster\tabcdef1234\tt\n"))
      (write-file (fs/path root ".squad/assignments/wumpus-qa/metadata")
                  "assignment_id: wumpus-qa\ntheme_id: wumpus\nstory_id: batch\ntemplate: qa\n")
      (write-file (fs/path root ".squad/assignments/wumpus-qa/status")
                  "assignment_id: wumpus-qa\nstate: handoff_sent\n")
      (let [out (:out (run {:dir root} (script "squad_next.sh") "--apply-mechanical"))
            packet (slurp (str (fs/path root ".squad/stories/alpha/packet")))]
        (is (str/includes? out "record_merged_batch_result"))
        (is (str/includes? packet "qa_sha: dd11ee22ff"))
        (is (or (str/includes? packet "state: qa_returned")
                (str/includes? packet "state: qa_approved"))
            "stage advances once the batch QA result is on the packet"))
      (finally
        (fs/delete-tree root)))))

(deftest apply-mechanical-creates-assignments-and-queues-spawns
  ;; Given an approved story ready for gherkin and qa-procedure writers
  ;; When squad_next --apply-mechanical runs
  ;; Then create_assignment actions are applied (not left for the SL)
  ;; And spawn requests are queued
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (doseq [template ["gherkin-writer" "qa-procedure-writer"]]
        (write-file (fs/path root "swarmforge/role-templates" (str template ".prompt"))
                    (str template " prompt\n")))
      (write-file (fs/path root "theme.md") "Theme.\n")
      (write-file (fs/path root "stories/alpha.md") "Story: alpha.\n")
      (run {:dir root} "git" "add" "stories")
      (run {:dir root} "git" "commit" "-q" "-m" "story")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root} (script "squad_packet.sh") "create" "alpha" "squad-leader" "master" sha))
      (mark-implementation-plan-approved! root "alpha")
      (let [applied (:out (run {:dir root} (script "squad_next.sh") "--apply-mechanical"))]
        (is (str/includes? applied "APPLIED_TRANSITION: create_assignment")
            "create_assignment is daemon-applied")
        (is (str/includes? applied "exit=0")
            "create_assignment succeeds when role templates exist")
        (is (not (str/includes? applied "NEXT_ACTION: create_assignment"))
            "SL is not asked to create assignments after mechanical apply")
        (is (fs/exists? (fs/path root ".squad/assignments/alpha-gherkin"))
            "gherkin assignment created")
        (is (fs/exists? (fs/path root ".squad/assignments/alpha-qa-procedure"))
            "qa-procedure assignment created")
        (is (or (str/includes? applied "wait_for_spawn")
                (fs/exists? (fs/path root ".squad/spawn-requests/new"))
                (and (fs/directory? (fs/path root ".squad/spawn-requests"))
                     (seq (filter #(str/ends-with? (fs/file-name %) ".request")
                                  (mapcat #(when (fs/directory? %) (fs/list-dir %))
                                          (fs/list-dir (fs/path root ".squad/spawn-requests")))))))
            "spawn requests queued via --queue-spawn"))
      (finally
        (fs/delete-tree root)))))
(deftest apply-mechanical-records-in-process-git-handoff-result
  ;; Given a claimed git handoff for an in_progress assignment
  ;; When apply-mechanical runs
  ;; Then record_assignment_result is applied before finish
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "analyst-001\tanalyst-001\t" root "/.worktrees/a\tswarmforge-a\tA\tcodex\ttask\n"))
      (write-agent-status! root "analyst-001" "handoff_sent")
      (write-file (fs/path root ".squad/agents/analyst-001/metadata")
                  "template: analyst\ntask_id: wumpus-analysis\n")
      (let [handoff (fs/path root ".swarmforge/handoffs/inbox/in_process/50_from_analyst-001.handoff")]
        (write-file handoff
                    (str "type: git_handoff\nto: squad-leader\nfrom: analyst-001\npriority: 50\n"
                         "task: wumpus-analysis\ncommit: abcdef1234\nassignment: wumpus-analysis\n"
                         "agent: analyst-001\ntemplate: analyst\nartifacts: stories/cave.md\n\n"
                         "merge_and_process analyst-001 abcdef1234\n"))
        (write-file (fs/path root ".squad/assignments/wumpus-analysis/metadata")
                    (str "assignment_id: wumpus-analysis\ntheme_id: wumpus\nstory_id: theme\n"
                         "template: analyst\nassignment_file: " root "/i.md\n"))
        (write-file (fs/path root ".squad/assignments/wumpus-analysis/status")
                    "assignment_id: wumpus-analysis\nstate: in_progress\n")
        (let [applied (:out (run {:dir root} (script "squad_next.sh") "--apply-mechanical"))]
          (is (str/includes? applied "APPLIED_TRANSITION: record_assignment_result")
              "daemon records assignment result")
          (is (fs/exists? (fs/path root ".squad/assignments/wumpus-analysis/result"))
              "result file written")
          (is (not (str/includes? applied "NEXT_ACTION: record_assignment_result")))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-hard-gates-implementer-on-implementation-order
  ;; Given two implementer-ready stories and an order file that used to serialize them
  ;; When residual runs
  ;; Then each story may get its own implementer; order does not block
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "swarmforge/squad.conf") implementer-gate-conf)
      (write-file (fs/path root ".squad/themes/wumpus/implementation-order.md")
                  "story-b: story-a\n")
      (write-nontrivial-checker! root)
      (doseq [story ["story-a" "story-b"]]
        (write-file (fs/path root ".squad/stories" story "packet")
                    (str "story_id: " story "\n"
                         "theme_id: wumpus\n"
                         "implementation_plan_path: .squad/stories/" story "/plan.md\n"
                         "implementation_plan_approval: approved\n"
                         "gherkin_path: features/" story ".feature\n"
                         "gherkin_approval: approved\n"
                         "qa_procedure_approval: approved\n"
                         "implementation_approval: approved\n")))
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "implementer"))
        (is (str/includes? (:out next) "story-a-implementation"))
        (is (str/includes? (:out next) "story-b-implementation"))
        (is (not (str/includes? (:out next) "--batch-stories"))))
      (finally
        (fs/delete-tree root)))))

(deftest residual-only-defers-accept-merge-to-daemon
  ;; Given merge_ready in-process handoff
  ;; When SL uses --residual-only
  ;; Then residual is wait_for_daemon_main_git (not accept-merge COMMAND)
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "analyst-001\tanalyst-001\t" root "/.worktrees/analyst-001\tswarmforge-analyst-001\tAnalyst 001\tcodex\ttask\n"))
      (write-agent-status! root "analyst-001" "handoff_sent")
      (write-file (fs/path root ".swarmforge/handoffs/inbox/in_process/50_20260803T000000Z_000001_from_analyst-001_to_squad-leader.handoff")
                  (str "type: git_handoff\n"
                       "to: squad-leader\n"
                       "from: analyst-001\n"
                       "priority: 50\n"
                       "task: wumpus-analysis\n"
                       "commit: abcdef1234\n"
                       "assignment: wumpus-analysis\n"
                       "agent: analyst-001\n"
                       "template: analyst\n"
                       "artifacts: stories/cave.md\n\n"
                       "merge_and_process analyst-001 abcdef1234\n"))
      (write-file (fs/path root ".squad/assignments/wumpus-analysis/metadata")
                  (str "assignment_id: wumpus-analysis\n"
                       "theme_id: wumpus\n"
                       "story_id: theme\n"
                       "template: analyst\n"
                       "assignment_file: " root "/instructions.md\n"))
      (write-file (fs/path root ".squad/assignments/wumpus-analysis/status")
                  "assignment_id: wumpus-analysis\nstate: merge_ready\n")
      (let [inspection (run {:dir root} (script "squad_next.sh"))
            residual (run {:dir root} (script "squad_next.sh") "--residual-only")]
        (is (str/includes? (:out inspection) "NEXT_ACTION: accept_merge")
            "plain inspection still shows the real merge command")
        (is (str/includes? (:out residual) "NEXT_ACTION: accept_merge"))
        (is (str/includes? (:out residual) "COMMAND: squad_assign.sh accept-merge"))
        (is (not (str/includes? (:out residual) "wait_for_daemon_main_git"))
            "SL residual is the merge; daemon does not own it"))
      (finally
        (fs/delete-tree root)))))

(deftest residual-only-defers-merge-ready-to-daemon
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "analyst-001\tanalyst-001\t" root "/.worktrees/analyst-001\tswarmforge-analyst-001\tAnalyst 001\tcodex\ttask\n"))
      (write-agent-status! root "analyst-001" "handoff_sent")
      (write-file (fs/path root ".swarmforge/handoffs/inbox/in_process/50_20260803T000000Z_000001_from_analyst-001_to_squad-leader.handoff")
                  (str "type: git_handoff\nto: squad-leader\nfrom: analyst-001\npriority: 50\n"
                       "task: wumpus-analysis\ncommit: abcdef1234\nassignment: wumpus-analysis\n"
                       "agent: analyst-001\ntemplate: analyst\nartifacts: stories/cave.md\n\n"
                       "merge_and_process analyst-001 abcdef1234\n"))
      (write-file (fs/path root ".squad/assignments/wumpus-analysis/metadata")
                  (str "assignment_id: wumpus-analysis\ntheme_id: wumpus\nstory_id: theme\n"
                       "template: analyst\nassignment_file: " root "/i.md\n"))
      (write-file (fs/path root ".squad/assignments/wumpus-analysis/status")
                  "assignment_id: wumpus-analysis\nstate: result_received\n")
      (let [residual (run {:dir root} (script "squad_next.sh") "--residual-only")]
        (is (str/includes? (:out residual) "NEXT_ACTION: accept_merge"))
        (is (str/includes? (:out residual) "COMMAND: squad_assign.sh accept-merge"))
        (is (not (str/includes? (:out residual) "wait_for_daemon_main_git")))
        (is (not (str/includes? (:out residual) "check_merge_readiness"))))
      (finally
        (fs/delete-tree root)))))

(deftest p0-implementer-one-rework-per-code-review-changes-requested
  ;; Given current code_review changes-requested and original implementation recorded
  ;; When a rework implementer already exists
  ;; Then no second rework implementer is created (thrash stop)
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "swarmforge/squad.conf") implementer-gate-conf)
      (write-file (fs/path root ".squad/themes/wumpus/implementation-order.md")
                  "alpha:\n")
      (write-nontrivial-checker! root)
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\n"
                       "theme_id: wumpus\n"
                       "story_approval: approved\n"
                       "gherkin_approval: approved\n"
                       "qa_procedure_approval: approved\n"
                       "gherkin_review: accepted\n"
                       "qa_procedure_review: accepted\n"
                       "implementation_approval: approved\n"
                       "implementation_assignment: alpha-implementation\n"
                       "implementation_sha: aaaaaaaaaa\n"
                       "cleaner_sha: bbbbbbbbbb\n"
                       "code_review: changes-requested\n"
                       "code_review_assignment: alpha-code-review\n"
                       "code_review_sha: cccccccccc\n"
                       "code_review_target_sha: bbbbbbbbbb\n"))
      (write-file (fs/path root ".squad/assignments/alpha-implementation/metadata")
                  (str "assignment_id: alpha-implementation\ntheme_id: wumpus\nstory_id: alpha\n"
                       "template: implementer\nassignment_file: " root "/i.md\n"))
      (write-file (fs/path root ".squad/assignments/alpha-implementation/status")
                  "assignment_id: alpha-implementation\nstate: merged\n")
      (write-file (fs/path root ".squad/assignments/alpha-implementation-r2/metadata")
                  (str "assignment_id: alpha-implementation-r2\ntheme_id: wumpus\nstory_id: alpha\n"
                       "template: implementer\nassignment_file: " root "/i2.md\n"))
      (write-file (fs/path root ".squad/assignments/alpha-implementation-r2/status")
                  "assignment_id: alpha-implementation-r2\nstate: merged\n")
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (not (str/includes? out "alpha-implementation-r3"))
            "must not create endless implementer reworks while CR is open")
        (is (not (re-find #"create_assignment.*implementer.*alpha-implementation-r[3-9]" out))))
      (finally
        (fs/delete-tree root)))))

(deftest p0-rework-implementer-re-records-implementation-result
  ;; Given merged rework implementer after first implementation_sha is set
  ;; When squad_next runs
  ;; Then record_merged_result is offered for the rework assignment
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/themes/wumpus/implementation-order.md") "")
      (write-file (fs/path root "swarmforge/squad.conf") implementer-gate-conf)
      (write-nontrivial-checker! root)
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\n"
                       "theme_id: wumpus\n"
                       "story_approval: approved\n"
                       "gherkin_approval: approved\n"
                       "qa_procedure_approval: approved\n"
                       "gherkin_review: accepted\n"
                       "qa_procedure_review: accepted\n"
                       "implementation_approval: approved\n"
                       "implementation_assignment: alpha-implementation\n"
                       "implementation_sha: aaaaaaaaaa\n"
                       "cleaner_sha: bbbbbbbbbb\n"
                       "code_review: changes-requested\n"
                       "code_review_target_sha: bbbbbbbbbb\n"))
      (write-file (fs/path root ".squad/assignments/alpha-implementation-r2/metadata")
                  (str "assignment_id: alpha-implementation-r2\ntheme_id: wumpus\nstory_id: alpha\n"
                       "template: implementer\nassignment_file: " root "/i2.md\n"))
      (write-file (fs/path root ".squad/assignments/alpha-implementation-r2/status")
                  "assignment_id: alpha-implementation-r2\nstate: merged\n")
      (write-file (fs/path root ".squad/assignments/alpha-implementation-r2/accepted-merge")
                  (str "assignment_id: alpha-implementation-r2\n"
                       "state: merged\n"
                       "commit: dddddddddd\n"
                       "merge_commit: eeeeeeeeee\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "record_merged_result")
            "rework implementer must re-record even when implementation_sha exists")
        (is (str/includes? out "alpha-implementation-r2")))
      (finally
        (fs/delete-tree root)))))

(deftest does-not-replay-superseded-cleaner-or-code-review-after-impl-rework
  ;; Given: code_review changes-requested, one implementer rework already recorded,
  ;;        clear-downstream left cleaner/CR empty, but old cleaner + CR still merged
  ;;        and present in iterations history.
  ;; When: mechanical residual runs
  ;; Then: do not re-record old cleaner or re-apply old changes-requested;
  ;;       offer create_assignment for a new cleaner instead.
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "swarmforge/squad.conf") implementer-gate-conf)
      (write-nontrivial-checker! root)
      (write-file (fs/path root ".squad/themes/wumpus/implementation-order.md")
                  "alpha:\n")
      ;; Post clear-downstream state after implementation-r2 record.
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\n"
                       "theme_id: wumpus\n"
                       "story_approval: approved\n"
                       "gherkin_approval: approved\n"
                       "qa_procedure_approval: approved\n"
                       "gherkin_review: accepted\n"
                       "qa_procedure_review: accepted\n"
                       "implementation_approval: approved\n"
                       "implementation_assignment: alpha-implementation-r2\n"
                       "implementation_branch: master\n"
                       "implementation_sha: dddddddddd\n"
                       "implementation_iterations: alpha-implementation=recorded,alpha-implementation-r2=recorded\n"
                       "cleaner_iterations: alpha-cleaner=recorded\n"
                       "code_review_iterations: alpha-code-review=changes-requested\n"))
      (write-file (fs/path root ".squad/assignments/alpha-cleaner/metadata")
                  (str "assignment_id: alpha-cleaner\ntheme_id: wumpus\nstory_id: alpha\n"
                       "template: cleaner\nassignment_file: " root "/c.md\n"))
      (write-file (fs/path root ".squad/assignments/alpha-cleaner/status")
                  "assignment_id: alpha-cleaner\nstate: merged\n")
      (write-file (fs/path root ".squad/assignments/alpha-cleaner/accepted-merge")
                  (str "assignment_id: alpha-cleaner\n"
                       "state: merged\n"
                       "commit: bbbbbbbbbb\n"
                       "merge_commit: bbbbbbbbbb\n"))
      (write-file (fs/path root ".squad/assignments/alpha-code-review/metadata")
                  (str "assignment_id: alpha-code-review\ntheme_id: wumpus\nstory_id: alpha\n"
                       "template: code-reviewer\nassignment_file: " root "/r.md\n"))
      (write-file (fs/path root ".squad/assignments/alpha-code-review/status")
                  "assignment_id: alpha-code-review\nstate: merged\n")
      (write-file (fs/path root ".squad/assignments/alpha-code-review/accepted-merge")
                  (str "assignment_id: alpha-code-review\n"
                       "state: merged\n"
                       "commit: cccccccccc\n"
                       "merge_commit: cccccccccc\n"))
      (write-file (fs/path root ".squad/assignments/alpha-code-review/review.md")
                  "changes-requested\n")
      (write-file (fs/path root "reviews/alpha-code-review.md")
                  "changes-requested\n")
      (let [out (:out (run {:dir root} (script "squad_next.sh") "--apply-mechanical"))
            packet (slurp (str (fs/path root ".squad/stories/alpha/packet")))]
        (is (not (str/includes? out "record_merged_result story=alpha assignment=alpha-cleaner"))
            "Must not re-record superseded cleaner after impl rework clear")
        (is (not (str/includes? out "record_review_result story=alpha assignment=alpha-code-review"))
            "Must not re-apply superseded code_review changes-requested")
        (is (not (str/includes? packet "cleaner_sha: bbbbbbbbbb"))
            "packet must not regain old cleaner_sha")
        (is (not (str/includes? packet "\ncode_review: changes-requested\n"))
            "packet must not regain old changes-requested")
        (is (or (str/includes? out "create_assignment")
                (str/includes? out "TEMPLATE: cleaner")
                (str/includes? out "alpha-cleaner-r2")
                (str/includes? out "implemented story needs cleaning"))
            "should progress toward a fresh cleaner cycle"))
      (finally
        (fs/delete-tree root)))))

(deftest p0-missing-durable-implementation-order-blocks-all-implementers
  ;; Given implementer-ready stories and no durable order
  ;; When residual runs
  ;; Then implementers are created; order is not a gate
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "swarmforge/squad.conf") implementer-gate-conf)
      (write-nontrivial-checker! root)
      (write-file (fs/path root "implementation-order.md")
                  "story-b: story-a\n")
      (doseq [story ["story-a" "story-b"]]
        (write-file (fs/path root ".squad/stories" story "packet")
                    (str "story_id: " story "\n"
                         "theme_id: wumpus\n"
                         "implementation_plan_path: .squad/stories/" story "/plan.md\n"
                         "implementation_plan_approval: approved\n"
                         "gherkin_path: features/" story ".feature\n"
                         "gherkin_approval: approved\n"
                         "qa_procedure_approval: approved\n"
                         "implementation_approval: approved\n")))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (not (str/includes? out "record_implementation_order")))
        (is (str/includes? out "story-a-implementation"))
        (is (str/includes? out "story-b-implementation")))
      (finally
        (fs/delete-tree root)))))


(deftest troubleshooter-is-not-active-transient-for-wait
  ;; Given only persistent roles in roles.tsv and no transient workers
  ;; When residual would wait
  ;; Then Troubleshooter is not listed as ACTIVE (not a product fleet agent)
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "troubleshooter\tmaster\t" root "\tswarmforge-troubleshooter\tTroubleshooter\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/agents/troubleshooter/recovery")
                  "state: dirty_worktree\nchecked_at: now\n")
      (let [out (:out (run {:dir root} (script "squad_next.sh") "--residual-only"))]
        (is (not (str/includes? out "ACTIVE: troubleshooter"))
            "persistent Troubleshooter must not freeze residual wait")
        (is (str/includes? out "NEXT_ACTION: wait")))
      (finally
        (fs/delete-tree root)))))


(deftest leftover-theme-slice-does-not-finalize
  ;; Given leftover theme records whose stories look complete
  ;; When squad_next runs
  ;; Then it does not request theme finalize. Theme CLI finalize/reopen stays a fixture.
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "swarmforge/squad.conf")
                  "approval_required finalize true\n")
      (write-file (fs/path root "theme.md") "Hello theme.\n")
      (write-file (fs/path root "stories/alpha.md") "Story alpha.\n")
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\n"
                       "theme_id: hello\n"
                       "state: final_approved\n"
                       "final_state: final_approved\n"
                       "final_approval: approved\n"
                       "story_approval: approved\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (not (str/includes? out "project-slice-ready-to-finalize"))
            out)
        (is (not (str/includes? out "squad_theme.sh finalize"))
            out))
      (let [after (:out (run {:dir root} (script "squad_next.sh") "--residual-only"))]
        (is (not (str/includes? after "FINALIZED_THEME: hello"))
            after)
        (is (not (str/includes? after "squad_theme.sh finalize"))
            after))
      (finally
        (fs/delete-tree root)))))




