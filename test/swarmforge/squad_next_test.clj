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
        (is (str/includes? (:out in-process) "COMMAND: done_with_current.sh "))
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
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root}
           (script "squad_approval.sh")
           "request"
           "theme__wumpus"
           "theme"
           "wumpus"
           "theme"
           "Approve theme"
           "theme is ready")
      (write-file (fs/path root ".swarmforge/daemon/squad-web-url")
                  "http://127.0.0.1:8765/\n")
      (let [approval (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out approval) "NEXT_ACTION: request_user_approval"))
        (is (str/includes? (:out approval) "APPROVAL: theme__wumpus"))
        (is (str/includes? (:out approval) "DASHBOARD_URL: http://127.0.0.1:8765/"))
        (is (str/includes? (:out approval) "WEB_APPROVAL_PATH: http://127.0.0.1:8765/api/approvals/theme__wumpus/approve"))
        (is (str/includes? (:out approval) "COMMAND_ON_APPROVAL: squad_approval.sh approve theme__wumpus approved-by-user")))
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

(deftest squad-next-does-not-wait-on-failed-transients
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
        (is (str/includes? (:out wait) "REASON: no handoffs, pending approvals, active transient agents, or stale locks"))
        (is (not (str/includes? (:out wait) "ACTIVE: gherkin-writer-001"))))
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
      (let [merge-ready (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out merge-ready) "NEXT_ACTION: check_merge_readiness"))
        (is (str/includes? (:out merge-ready) "COMMAND: squad_assign.sh merge-ready wumpus-analysis")))
      (write-file (fs/path root ".squad/assignments/wumpus-analysis/status")
                  "assignment_id: wumpus-analysis\nstate: merge_ready\n")
      (let [accept-merge (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out accept-merge) "NEXT_ACTION: accept_merge"))
        (is (str/includes? (:out accept-merge) "COMMAND: squad_assign.sh accept-merge wumpus-analysis")))
      (write-file (fs/path root ".squad/assignments/wumpus-analysis/status")
                  "assignment_id: wumpus-analysis\nstate: merged\n")
      (let [finish (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out finish) "NEXT_ACTION: finish_in_process_handoff"))
        (is (str/includes? (:out finish) "COMMAND: done_with_current.sh ")))
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
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "approve" "wumpus" "theme" "approved")
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

(deftest squad-next-registers-merged-analyst-story-artifacts
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/alpha.md") "Story: alpha.\n")
      (write-file (fs/path root "stories/beta.md") "Story: beta.\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
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
        (is (str/includes? (:out register) "NEXT_ACTION: register_story_artifact"))
        (is (str/includes? (:out register) "STORY: alpha"))
        (is (str/includes? (:out register) "COMMAND: squad_theme.sh story wumpus alpha stories/alpha.md && squad_packet.sh create wumpus alpha wumpus-analysis master"))
        (is (str/includes? (:out register) "CONCURRENT_ACTIONS: 2"))
        (is (str/includes? (:out register) "CONCURRENT_STORY: alpha"))
        (is (str/includes? (:out register) "CONCURRENT_STORY: beta")))
      (let [applied (run {:dir root} (script "squad_next.sh") "--apply-mechanical")]
        (is (str/includes? (:out applied) "APPLIED_TRANSITIONS: 2"))
        (is (str/includes? (:out applied) "APPLIED_TRANSITION: register_story_artifact story=alpha assignment=wumpus-analysis batch=none exit=0"))
        (is (str/includes? (:out applied) "APPLIED_TRANSITION: register_story_artifact story=beta assignment=wumpus-analysis batch=none exit=0"))
        (is (str/includes? (:out applied) "NEXT_ACTION: create_approval_request")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-registers-direct-theme-story-before-gherkin-work
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/alpha.md")
                  "Story: alpha supplied directly to the squad leader.\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "approve" "wumpus" "theme" "approved")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "alpha" "stories/alpha.md")
      (run {:dir root} "git" "add" "stories" ".squad")
      (run {:dir root} "git" "commit" "-q" "-m" "Register direct story reference")
      (let [register (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out register) "NEXT_ACTION: register_story_packet"))
        (is (str/includes? (:out register) "STORY: alpha"))
        (is (str/includes? (:out register) "COMMAND: squad_packet.sh create wumpus alpha squad-leader master $(git rev-parse --short=10 HEAD) && squad_packet.sh approve alpha story approved-by-user"))
        (is (not (str/includes? (:out register) "TEMPLATE: gherkin-writer"))))
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root} (script "squad_packet.sh") "create" "wumpus" "alpha" "squad-leader" "master" sha)
        (run {:dir root} (script "squad_packet.sh") "approve" "alpha" "story" "approved-by-user"))
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "NEXT_ACTION: create_assignment"))
        (is (str/includes? (:out next) "TEMPLATE: gherkin-writer"))
        (is (str/includes? (:out next) "CONCURRENT_ACTIONS: 2"))
        (is (str/includes? (:out next) "CONCURRENT_TEMPLATE: gherkin-writer"))
        (is (str/includes? (:out next) "CONCURRENT_TEMPLATE: qa-procedure-writer"))
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
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "alpha" "stories/alpha.md")
      (run {:dir root} "git" "add" "stories" "qa")
      (run {:dir root} "git" "commit" "-q" "-m" "Add QA procedure")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root} (script "squad_packet.sh") "create" "wumpus" "alpha" "wumpus-analysis" "master" sha)
        (run {:dir root} (script "squad_packet.sh") "approve" "alpha" "story" "approved")
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
        (is (str/includes? packet "gherkin_sha: abcdef2222"))
        (is (str/includes? packet "gherkin_review: accepted")
            "one-review-cycle acceptance should follow same-path r2 attach"))
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
        (is (str/includes? (:out next) "APPLIED_TRANSITIONS: 1"))
        (is (str/includes? (:out next) "APPLIED_TRANSITION: record_merged_result story=alpha assignment=alpha-implementation batch=none exit=0"))
        (is (str/includes? packet "implementation_sha: abcdef1234")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-records-merged-review-result-from-durable-review
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\n"
                       "theme_id: wumpus\n"
                       "gherkin_path: features/alpha.feature\n"
                       "gherkin_assignment: alpha-gherkin\n"
                       "gherkin_sha: 1111111111\n"))
      (write-file (fs/path root ".squad/assignments/alpha-gherkin-review/metadata")
                  (str "assignment_id: alpha-gherkin-review\n"
                       "theme_id: wumpus\n"
                       "story_id: alpha\n"
                       "template: gherkin-reviewer\n"
                       "assignment_file: " root "/instructions.md\n"))
      (write-file (fs/path root ".squad/assignments/alpha-gherkin-review/status")
                  "assignment_id: alpha-gherkin-review\nstate: merged\n")
      (write-file (fs/path root ".squad/assignments/alpha-gherkin-review/accepted-merge")
                  (str "assignment_id: alpha-gherkin-review\n"
                       "state: merged\n"
                       "commit: 2222222222\n"
                       "merge_commit: abcdef1234\n"))
      (write-file (fs/path root ".squad/assignments/alpha-gherkin-review/review.md")
                  "## Recommendation\n\nAccept.\n")
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "NEXT_ACTION: record_review_result"))
        (is (str/includes? (:out next) "COMMAND: squad_packet.sh review alpha gherkin accepted alpha-gherkin-review master abcdef1234")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-auto-accepts-revised-gherkin-after-one-review-cycle
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\n"
                       "theme_id: wumpus\n"
                       "gherkin_path: features/alpha.feature\n"
                       "gherkin_assignment: alpha-gherkin-r2\n"
                       "gherkin_sha: 2222222222\n"
                       "gherkin_review: changes-requested\n"
                       "gherkin_review_assignment: alpha-gherkin-review\n"
                       "gherkin_review_sha: 1111111111\n"
                       "gherkin_review_target_sha: 1111111111\n"))
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "NEXT_ACTION: record_post_revision_review_acceptance"))
        (is (str/includes? (:out next) "COMMAND: squad_packet.sh review alpha gherkin accepted alpha-gherkin-r2 master 2222222222"))
        (is (not (str/includes? (:out next) "\nTEMPLATE: gherkin-reviewer"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-does-not-replay-stale-review-after-post-revision-acceptance
  ;; Given an r2 artifact with stale changes-requested and the original merged review
  ;; When mechanical repair runs
  ;; Then one-cycle acceptance wins and the old changes-requested is not re-applied
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\n"
                       "theme_id: wumpus\n"
                       "gherkin_path: features/alpha.feature\n"
                       "gherkin_assignment: alpha-gherkin-r2\n"
                       "gherkin_sha: 2222222222\n"
                       "gherkin_review: changes-requested\n"
                       "gherkin_review_assignment: alpha-gherkin-review\n"
                       "gherkin_review_sha: 1111111111\n"
                       "gherkin_review_target_sha: 1111111111\n"))
      (write-file (fs/path root ".squad/assignments/alpha-gherkin-review/metadata")
                  (str "assignment_id: alpha-gherkin-review\n"
                       "theme_id: wumpus\n"
                       "story_id: alpha\n"
                       "template: gherkin-reviewer\n"
                       "assignment_file: " root "/review.md\n"))
      (write-file (fs/path root ".squad/assignments/alpha-gherkin-review/status")
                  "assignment_id: alpha-gherkin-review\nstate: merged\n")
      (write-file (fs/path root ".squad/assignments/alpha-gherkin-review/accepted-merge")
                  (str "assignment_id: alpha-gherkin-review\n"
                       "state: merged\n"
                       "commit: 1111111111\n"
                       "merge_commit: abcdef1111\n"))
      (write-file (fs/path root ".squad/assignments/alpha-gherkin-review/review.md")
                  "changes-requested\n")
      (let [out (:out (run {:dir root} (script "squad_next.sh") "--apply-mechanical"))
            packet (slurp (str (fs/path root ".squad/stories/alpha/packet")))]
        (is (str/includes? out "record_post_revision_review_acceptance"))
        (is (not (str/includes? out "APPLIED_TRANSITION: record_review_result story=alpha assignment=alpha-gherkin-review"))
            "stale original review must not be re-recorded against the revised sha")
        (is (str/includes? packet "gherkin_review: accepted"))
        (is (str/includes? packet "gherkin_review_target_sha: 2222222222"))
        (is (not (str/includes? packet "gherkin_review: changes-requested"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-auto-accepts-after-revised-artifact-is-attached
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md") "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/alpha.md") "Story: alpha.\n")
      (write-file (fs/path root "features/alpha.feature") "Feature: alpha\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "alpha" "stories/alpha.md")
      (run {:dir root} "git" "add" "stories" "features")
      (run {:dir root} "git" "commit" "-q" "-m" "Prepare alpha")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root} (script "squad_packet.sh") "create" "wumpus" "alpha" "analysis-alpha" "master" sha)
        (run {:dir root} (script "squad_packet.sh") "attach" "alpha" "gherkin" "alpha-gherkin" "master" sha "features/alpha.feature")
        (run {:dir root} (script "squad_packet.sh") "review" "alpha" "gherkin" "changes-requested" "alpha-gherkin-review" "master" sha))
      (write-file (fs/path root "features/alpha.feature") "Feature: alpha revised\n")
      (run {:dir root} "git" "add" "features/alpha.feature")
      (run {:dir root} "git" "commit" "-q" "-m" "Revise alpha gherkin")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root} (script "squad_packet.sh") "attach" "alpha" "gherkin" "alpha-gherkin-r2" "master" sha "features/alpha.feature")
        (let [next (run {:dir root} (script "squad_next.sh"))]
          (is (str/includes? (:out next) "NEXT_ACTION: record_post_revision_review_acceptance"))
          (is (str/includes? (:out next) "COMMAND: squad_packet.sh review alpha gherkin accepted alpha-gherkin-r2 master"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-does-not-create-second-reviewer-when-review-history-exists
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\n"
                       "theme_id: wumpus\n"
                       "gherkin_path: features/alpha.feature\n"
                       "gherkin_assignment: alpha-gherkin\n"
                       "gherkin_sha: 1111111111\n"))
      (write-file (fs/path root ".squad/assignments/alpha-gherkin-review/metadata")
                  (str "assignment_id: alpha-gherkin-review\n"
                       "theme_id: wumpus\n"
                       "story_id: alpha\n"
                       "template: gherkin-reviewer\n"
                       "assignment_file: " root "/instructions.md\n"))
      (write-file (fs/path root ".squad/assignments/alpha-gherkin-review/status")
                  "assignment_id: alpha-gherkin-review\nstate: merged\n")
      (write-file (fs/path root ".squad/assignments/alpha-gherkin-review/accepted-merge")
                  "assignment_id: alpha-gherkin-review\nstate: merged\ncommit: 2222222222\nmerge_commit: abcdef1234\n")
      (write-file (fs/path root ".squad/assignments/alpha-gherkin-review/review.md")
                  "accepted\n")
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "NEXT_ACTION: record_review_result"))
        (is (not (str/includes? (:out next) "alpha-gherkin-review-r2"))))
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
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "beta" "stories/beta.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "alpha" "stories/alpha.md")
      (run {:dir root} "git" "add" "stories")
      (run {:dir root} "git" "commit" "-q" "-m" "Prepare alpha and beta stories")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root} (script "squad_packet.sh") "create" "wumpus" "beta" "analysis-beta" "master" sha)
        (run {:dir root} (script "squad_packet.sh") "create" "wumpus" "alpha" "analysis-alpha" "master" sha))
      (let [first-approval (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out first-approval) "NEXT_ACTION: create_approval_request"))
        (is (str/includes? (:out first-approval) "STORY: alpha"))
        (is (str/includes? (:out first-approval) "GATE: story")))
      (run {:dir root}
           (script "squad_approval.sh")
           "request"
           "story__beta"
           "story"
           "beta"
           "story"
           "Approve_story"
           "story-ready-for-approval")
      (run {:dir root} (script "squad_packet.sh") "approve" "alpha" "story" "approved")
      (let [create-assignment (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out create-assignment) "NEXT_ACTION: create_assignment"))
        (is (str/includes? (:out create-assignment) "STORY: alpha"))
        (is (str/includes? (:out create-assignment) "TEMPLATE: gherkin-writer"))
        (is (str/includes? (:out create-assignment) "COMMAND: squad_assign.sh create wumpus alpha gherkin-writer alpha-gherkin --auto-instructions --queue-spawn")))
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

(deftest squad-next-spawns-existing-rereview-before-requesting-another-revision
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md") "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/alpha.md") "Story: alpha.\n")
      (write-file (fs/path root "features/alpha.feature") "Feature: alpha\n")
      (write-file (fs/path root "qa/alpha.md") "# QA: alpha\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "alpha" "stories/alpha.md")
      (run {:dir root} "git" "add" "stories" "features" "qa")
      (run {:dir root} "git" "commit" "-q" "-m" "Prepare alpha artifacts")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root} (script "squad_packet.sh") "create" "wumpus" "alpha" "analysis-alpha" "master" sha)
        (run {:dir root} (script "squad_packet.sh") "approve" "alpha" "story" "approved")
        (run {:dir root} (script "squad_packet.sh") "attach" "alpha" "gherkin" "alpha-gherkin-r2" "master" sha "features/alpha.feature")
        (run {:dir root} (script "squad_packet.sh") "attach" "alpha" "qa-procedure" "alpha-qa-procedure" "master" sha "qa/alpha.md")
        (run {:dir root} (script "squad_packet.sh") "review" "alpha" "gherkin" "changes-requested" "alpha-gherkin-review" "master" sha))
      (write-file (fs/path root ".squad/assignments/alpha-gherkin-review-r2/metadata")
                  (str "assignment_id: alpha-gherkin-review-r2\n"
                       "theme_id: wumpus\n"
                       "story_id: alpha\n"
                       "template: gherkin-reviewer\n"
                       "assignment_file: " root "/instructions.md\n"
                       "created_at: 2026-08-03T00:00:00Z\n"))
      (write-file (fs/path root ".squad/assignments/alpha-gherkin-review-r2/status")
                  (str "assignment_id: alpha-gherkin-review-r2\n"
                       "state: created\n"
                       "detail: gherkin-reviewer for alpha\n"
                       "updated_at: 2026-08-03T00:00:00Z\n"))
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "NEXT_ACTION: request_spawn"))
        (is (str/includes? (:out next) "TEMPLATE: gherkin-reviewer"))
        (is (str/includes? (:out next) "ASSIGNMENT: alpha-gherkin-review-r2"))
        (is (not (str/includes? (:out next) "TEMPLATE: gherkin-writer"))))
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
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "alpha" "stories/alpha.md")
      (run {:dir root} "git" "add" "stories")
      (run {:dir root} "git" "commit" "-q" "-m" "Prepare alpha story")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root} (script "squad_packet.sh") "create" "wumpus" "alpha" "analysis-alpha" "master" sha))
      (run {:dir root} (script "squad_packet.sh") "approve" "alpha" "story" "approved")
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

(deftest squad-next-routes-code-review-rejection-back-to-implementer
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md") "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md") "Story: cave topology and setup.\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "cave-topology" "stories/cave-topology.md")
      (let [sha (prepare-implementation-packet! root "wumpus" "cave-topology")]
        (run {:dir root} (script "squad_packet.sh") "approve" "cave-topology" "implementation" "approved")
        (run {:dir root} (script "squad_packet.sh") "record" "cave-topology" "implementation" "impl-1" "master" sha)
        (run {:dir root} (script "squad_packet.sh") "record" "cave-topology" "cleaner" "clean-1" "master" sha)
        (run {:dir root} (script "squad_packet.sh") "review" "cave-topology" "code" "changes-requested" "review-1" "master" sha)
        (let [next (run {:dir root} (script "squad_next.sh"))]
          (is (str/includes? (:out next) "NEXT_ACTION: create_assignment"))
          (is (str/includes? (:out next) "TEMPLATE: implementer"))
          (is (str/includes? (:out next) "code review requested implementation changes"))
          (is (not (str/includes? (:out next) "TEMPLATE: hardener")))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-routes-merge-blocked-assignment-to-merger
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
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
                       "detail: dry-run merge failed\n"
                       "updated_at: 2026-08-03T00:00:00Z\n"))
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "NEXT_ACTION: create_assignment"))
        (is (str/includes? (:out next) "TEMPLATE: merger"))
        (is (str/includes? (:out next) "ASSIGNMENT: cave-impl-merge"))
        (is (str/includes? (:out next) "COMMAND: squad_assign.sh create-merger cave-impl cave-impl-merge --auto-instructions --queue-spawn"))
        (is (str/includes? (:out next) "merge-blocked assignment needs merger")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-routes-merge-blocked-assignment-to-merger-when-capacity-full
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root "swarmforge/squad.conf")
                  "max_transient_agents 1\n")
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "implementer-001\timplementer-001\t" root "/.worktrees/implementer-001\tswarmforge-implementer-001\tImplementer 001\tcodex\ttask\n"))
      (write-agent-status! root "implementer-001" "running")
      (write-file (fs/path root ".squad/agents/implementer-001/metadata")
                  "template: implementer\n")
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
                       "detail: dry-run merge failed\n"
                       "updated_at: 2026-08-03T00:00:00Z\n"))
      (write-file (fs/path root ".squad/assignments/cave-impl-merge/metadata")
                  (str "assignment_id: cave-impl-merge\n"
                       "theme_id: wumpus\n"
                       "story_id: cave-topology\n"
                       "template: merger\n"
                       "assignment_file: " root "/merger-assignment.md\n"
                       "created_at: 2026-08-03T00:00:00Z\n"))
      (write-file (fs/path root ".squad/assignments/cave-impl-merge/status")
                  (str "assignment_id: cave-impl-merge\n"
                       "state: created\n"
                       "detail: merger for cave-topology\n"
                       "updated_at: 2026-08-03T00:00:00Z\n"))
      (write-file (fs/path root "merger-assignment.md")
                  "Resolve the merge.\n")
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "NEXT_ACTION: request_spawn"))
        (is (str/includes? (:out next) "TEMPLATE: merger"))
        (is (str/includes? (:out next) "COMMAND: squad_spawn_request.sh merger cave-impl-merge")))
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
        (is (str/includes? (:out next) "NEXT_ACTION: create_assignment"))
        (is (str/includes? (:out next) "TEMPLATE: merger"))
        (is (str/includes? (:out next) "COMMAND: squad_assign.sh create-merger cave-impl cave-impl-merge --auto-instructions --queue-spawn")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-retires-merge-blocked-source-agent-after-downstream-merger-result
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
      (write-file (fs/path root ".squad/assignments/cave-impl-merge/metadata")
                  (str "assignment_id: cave-impl-merge\n"
                       "theme_id: wumpus\n"
                       "story_id: cave-topology\n"
                       "template: merger\n"
                       "merge_for: cave-impl\n"
                       "assignment_file: " root "/merger.md\n"
                       "created_at: 2026-08-03T00:00:00Z\n"))
      (write-file (fs/path root ".squad/assignments/cave-impl-merge/status")
                  (str "assignment_id: cave-impl-merge\n"
                       "state: merge_blocked\n"
                       "detail: merger merge failed\n"
                       "updated_at: 2026-08-03T00:00:00Z\n"))
      (write-file (fs/path root ".squad/assignments/cave-impl-merge/result")
                  (str "assignment_id: cave-impl-merge\n"
                       "state: result_received\n"
                       "from: merger-001\n"
                       "commit: abcdef1234\n"))
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
          (is (str/includes? (:out next) "CONCURRENT_COMMAND: squad_spawn_request.sh cleaner alpha-cleaner"))))
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
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "cave-topology" "stories/cave-topology.md")
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
          (is (str/includes? (:out next) "COMMAND: squad_assign.sh create-batch wumpus hardener wumpus-hardener --auto-instructions --queue-spawn"))
          (is (not (str/includes? (:out next) "squad_assign.sh create wumpus batch")))))
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
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (doseq [story ["alpha" "beta"]]
        (run {:dir root} (script "squad_theme.sh") "story" "wumpus" story (str "stories/" story ".md"))
        (let [sha (prepare-implementation-packet! root "wumpus" story)]
          (run {:dir root} (script "squad_packet.sh") "approve" story "implementation" "approved")
          (run {:dir root} (script "squad_packet.sh") "record" story "implementation" (str story "-impl") "master" sha)
          (run {:dir root} (script "squad_packet.sh") "record" story "cleaner" (str story "-clean") "master" sha)
          (run {:dir root} (script "squad_packet.sh") "review" story "code" "accepted" (str story "-review") "master" sha)
          (run {:dir root} (script "squad_packet.sh") "approve" story "code-review" "approved")))
      (let [first-next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out first-next) "NEXT_ACTION: record_batch_membership"))
        (is (str/includes? (:out first-next) "STORY: alpha")))
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root} (script "squad_batch_story.sh") "add" "alpha" "hardener" "wumpus-hardener" "code_reviewed" "alpha-review" "master" sha))
      (let [second-next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out second-next) "NEXT_ACTION: record_batch_membership"))
        (is (str/includes? (:out second-next) "STORY: beta"))
        (is (not (str/includes? (:out second-next) "\nTEMPLATE: hardener"))))
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root} (script "squad_batch_story.sh") "add" "beta" "hardener" "wumpus-hardener" "code_reviewed" "beta-review" "master" sha))
      (let [third-next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out third-next) "NEXT_ACTION: create_assignment"))
        (is (str/includes? (:out third-next) "STORY: batch"))
        (is (str/includes? (:out third-next) "COMMAND: squad_assign.sh create-batch wumpus hardener wumpus-hardener --auto-instructions --queue-spawn")))
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
                        "wumpus"
                        "hardener"
                        "wumpus-hardener"
                        "instructions.md")]
        (is (= 2 (:exit result)))
        (is (str/includes? (:err result) "Batch record is missing: wumpus-hardener")))
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
