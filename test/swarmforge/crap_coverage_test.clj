(ns swarmforge.crap-coverage-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [squad-config :as config]
            [squad-next :as next]
            [squad-recover :as recover]
            [squad-report :as report]
            [squad-retire :as retire]
            [squad-simulator :as simulator]
            [squad-spawn :as spawn]
            [squad-spawn-request :as spawn-request]
            [squad-state :as state]
            [squad-status :as status]
            [squad-tool :as tool]
            [squadd :as squadd]
            [swarm-handoff :as handoff]
            [swarmforge :as forge]))

(def temp-dirs (atom []))

(defn tmp-dir []
  (let [dir (fs/create-temp-dir {:prefix "swarmforge-crap-coverage."})]
    (swap! temp-dirs conj dir)
    dir))

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn packet
  [& kvs]
  (apply hash-map kvs))

(deftest squad-state-recomputes-story-lifecycle-states
  (let [cases [{"story_id" "s1"}
               {"story_id" "s1" "story_approval" "approved"}
               {"story_id" "s1" "story_approval" "approved" "gherkin_assignment" "a1"}
               {"story_id" "s1" "story_approval" "approved"
                "gherkin_approval" "approved" "qa_procedure_approval" "approved"
                "gherkin_review" "accepted" "gherkin_review_target_sha" "g1" "gherkin_sha" "g1"
                "qa_procedure_review" "accepted" "qa_procedure_review_target_sha" "q1" "qa_procedure_sha" "q1"}
               {"implementation_approval" "approved"}
               {"implementation_sha" "i1"}
               {"cleaner_sha" "c1"}
               {"cleaner_sha" "c1" "code_review" "accepted" "code_review_target_sha" "c1"}
               {"code_review_approval" "approved"}
               {"hardener_sha" "h1"}
               {"hardening_approval" "approved"}
               {"qa_sha" "qa1"}
               {"qa_approval" "approved"}
               {"architecture_sha" "a1"}
               {"senior_implementor_sha" "sri1"}
               {"architecture_sha" "a1" "architecture_review" "accepted" "architecture_review_target_sha" "a1"}
               {"architecture_approval" "approved"}
               {"final_approval" "approved"}]
        expected ["story_recorded"
                  "story_approved"
                  "specification_in_progress"
                  "implementation_approval_ready"
                  "implementation_approved"
                  "implemented"
                  "cleaned"
                  "code_reviewed"
                  "code_review_approved"
                  "hardener_returned"
                  "hardening_approved"
                  "qa_returned"
                  "qa_approved"
                  "architecture_returned"
                  "architecture_revision_returned"
                  "architecture_reviewed"
                  "architecture_approved"
                  "final_approved"]]
    (is (= expected (map state/recompute-state cases)))))

(deftest squad-state-derives-stage-fields-for-pending-blocked-and-complete-branches
  (let [pending (state/derived-stage-fields {"story_id" "s1"} "story_recorded")
        assigned (state/derived-stage-fields {"story_approval" "approved"
                                              "gherkin_assignment" "g1"
                                              "qa_procedure_assignment" "q1"}
                                             "specification_in_progress")
        reviewed (state/derived-stage-fields {"gherkin_path" "features/s1.feature"
                                              "gherkin_review" "accepted"
                                              "gherkin_review_target_sha" "gsha"
                                              "gherkin_sha" "gsha"
                                              "qa_procedure_path" "qa/s1.md"
                                              "qa_procedure_review" "accepted"
                                              "qa_procedure_review_target_sha" "qsha"
                                              "qa_procedure_sha" "qsha"}
                                             "implementation_approval_ready")
        downstream (state/derived-stage-fields {"implementation_approval" "approved"
                                                "implementation_assignment" "i1"
                                                "implementation_sha" "isha"
                                                "cleaner_sha" "csha"
                                                "code_review" "changes-requested"
                                                "code_review_target_sha" "csha"
                                                "hardener_batch" "hb1"
                                                "hardener_sha" "hsha"
                                                "hardening_approval" "approved"
                                                "qa_batch" "qb1"
                                                "qa_sha" "qasha"
                                                "qa_approval" "approved"
                                                "architecture_batch" "ab1"
                                                "architecture_sha" "asha"
                                                "architecture_review" "accepted"
                                                "architecture_review_target_sha" "asha"
                                                "architecture_approval" "approved"}
                                               "architecture_approved")]
    (is (= "pending" (get pending "story_approval_state")))
    (is (= "blocked" (get pending "implementation_assignment_state")))
    (is (= "assigned" (get assigned "gherkin_assignment_state")))
    (is (= "assigned" (get assigned "qa_procedure_assignment_state")))
    (is (= "complete" (get reviewed "gherkin_assignment_state")))
    (is (= "accepted" (get reviewed "gherkin_review_state")))
    (is (= "pending" (get reviewed "gherkin_approval_state")))
    (is (= "complete" (get downstream "implementation_assignment_state")))
    (is (= "changes-requested" (get downstream "cleaner_review_state")))
    (is (= "approved" (get downstream "hardener_review_state")))
    (is (= "approved" (get downstream "qa_result_state")))
    (is (= "approved" (get downstream "architecture_result_state")))))

(deftest squad-state-detects-stale-reviews-and-batch-indexes
  (let [root (tmp-dir)]
    (write-file (fs/path root ".squad/stories/s1/active-batches/hardener") "hb1\n")
    (write-file (fs/path root ".squad/batches/hb1/status") "state: closed\n")
    (let [issues (state/consistency-issues
                  root
                  {"story_id" "s1"
                   "gherkin_review" "accepted"
                   "gherkin_review_target_sha" "old"
                   "gherkin_sha" "new"
                   "hardener_batch" "hb0"})]
      (is (= #{"stale-review" "stale-active-batch-index"}
             (set (map :code issues)))))))

(deftest swarm-handoff-parses-and-validates-drafts
  (let [root (tmp-dir)
        draft (fs/path root "draft.handoff")]
    (write-file draft
                (str "type: git_handoff\n"
                     "to: reviewer\n"
                     "priority: 05\n"
                     "task: story-one\n"
                     "commit: 0123456789\n"))
    (with-redefs [handoff/role-known? #{"reviewer"}
                  handoff/canonical-commit (fn [commit] [commit nil])]
      (let [{:keys [headers ordered errors]} (handoff/parse-draft draft)
            validated (handoff/validate headers ordered)]
        (is (= [] errors))
        (is (= ["type" "to" "priority" "task" "commit"] ordered))
        (is (= ["reviewer"] (:recipients validated)))
        (is (= "0123456789" (:canonical-commit validated)))
        (is (= [] (:errors validated)))))))

(deftest swarm-handoff-reports-invalid-drafts
  (let [root (tmp-dir)
        draft (fs/path root "bad.handoff")]
    (write-file draft
                (str "type: note\n"
                     "to: reviewer,,reviewer,bad_role,missing\n"
                     "priority: high\n"
                     "commit: 0123456789\n"
                     "message: " (apply str (repeat 81 "x")) "\n"
                     "message: duplicate\n"
                     "id: reserved\n"
                     "unknown: nope\n"
                     "\n"
                     "payload\n"))
    (with-redefs [handoff/role-known? #{"reviewer"}]
      (let [{:keys [headers ordered errors]} (handoff/parse-draft draft)
            validated (handoff/validate headers ordered)
            all-errors (concat errors (:errors validated))]
        (is (some #(str/includes? % "duplicate header 'message'") all-errors))
        (is (some #(str/includes? % "reserved") all-errors))
        (is (some #(str/includes? % "unknown header") all-errors))
        (is (some #(str/includes? % "payloads are generated") all-errors))
        (is (some #(str/includes? % "priority") all-errors))
        (is (some #(str/includes? % "empty recipient") all-errors))
        (is (some #(str/includes? % "may not contain underscores") all-errors))
        (is (some #(str/includes? % "Duplicate recipient") all-errors))
        (is (some #(str/includes? % "Unknown recipient") all-errors))
        (is (some #(str/includes? % "only allowed for git_handoff") all-errors))
        (is (some #(str/includes? % "message' must be no longer") all-errors))))))

(deftest squad-next-selects-approval-and-assignment-candidates
  (let [root (tmp-dir)
        story-dir (fs/path root ".squad/stories/s1")]
    (write-file (fs/path root ".swarmforge/roles.tsv") "squad-leader\tmaster\t/tmp\ts\tw\tcodex\ttask\n")
    (write-file (fs/path root "swarmforge/squad.conf")
                (str "approval_required story true\n"
                     "approval_required gherkin true\n"
                     "approval_required qa_procedure true\n"
                     "approval_required implementation true\n"))
    (write-file (fs/path story-dir "packet")
                (str "theme_id: theme-a\n"
                     "story_id: s1\n"))
    (let [candidate (first (next/story-candidates root []))]
      (is (= "create_approval_request" (:next-action candidate)))
      (is (= "story" (:gate candidate))))
    (write-file (fs/path story-dir "packet")
                (str "theme_id: theme-a\n"
                     "story_id: s1\n"
                     "story_approval: approved\n"))
    (let [candidates (next/story-candidates root [])]
      (is (= #{"gherkin-writer" "qa-procedure-writer"}
             (set (map :template candidates)))))
    (write-file (fs/path story-dir "packet")
                (str "theme_id: theme-a\n"
                     "story_id: s1\n"
                     "story_approval: approved\n"
                     "gherkin_path: features/s1.feature\n"
                     "gherkin_sha: gsha\n"
                     "qa_procedure_path: qa/s1.md\n"
                     "qa_procedure_sha: qsha\n"))
    (let [candidates (next/story-candidates root [])]
      (is (= #{"gherkin-reviewer" "qa-procedure-reviewer"}
             (set (map :template candidates)))))))

(deftest squad-next-selects-downstream-and-batch-candidates
  (let [root (tmp-dir)
        story-dir (fs/path root ".squad/stories/s1")]
    (write-file (fs/path root "swarmforge/squad.conf")
                (str "approval_required story false\n"
                     "approval_required gherkin false\n"
                     "approval_required qa_procedure false\n"
                     "approval_required implementation false\n"
                     "approval_required code_review false\n"
                     "approval_required hardening false\n"
                     "approval_required qa false\n"
                     "approval_required architecture false\n"
                     "approval_required final false\n"))
    (write-file (fs/path story-dir "packet")
                (str "theme_id: theme-a\n"
                     "story_id: s1\n"
                     "story_approval: approved\n"
                     "gherkin_approval: approved\n"
                     "qa_procedure_approval: approved\n"
                     "implementation_approval: approved\n"
                     "gherkin_path: features/s1.feature\n"
                     "gherkin_sha: gsha\n"
                     "gherkin_review: accepted\n"
                     "gherkin_review_target_sha: gsha\n"
                     "qa_procedure_path: qa/s1.md\n"
                     "qa_procedure_sha: qsha\n"
                     "qa_procedure_review: accepted\n"
                     "qa_procedure_review_target_sha: qsha\n"))
    (is (= "implementer" (:template (first (next/story-candidates root [])))))
    (write-file (fs/path story-dir "packet")
                (str "theme_id: theme-a\n"
                     "story_id: s1\n"
                     "story_approval: approved\n"
                     "gherkin_approval: approved\n"
                     "qa_procedure_approval: approved\n"
                     "implementation_approval: approved\n"
                     "gherkin_path: features/s1.feature\n"
                     "gherkin_sha: gsha\n"
                     "gherkin_review: accepted\n"
                     "gherkin_review_target_sha: gsha\n"
                     "qa_procedure_path: qa/s1.md\n"
                     "qa_procedure_sha: qsha\n"
                     "qa_procedure_review: accepted\n"
                     "qa_procedure_review_target_sha: qsha\n"
                     "implementation_sha: isha\n"))
    (is (= "cleaner" (:template (first (next/story-candidates root [])))))
    (write-file (fs/path story-dir "packet")
                (str "theme_id: theme-a\n"
                     "story_id: s1\n"
                     "story_approval: approved\n"
                     "gherkin_approval: approved\n"
                     "qa_procedure_approval: approved\n"
                     "implementation_approval: approved\n"
                     "gherkin_path: features/s1.feature\n"
                     "gherkin_sha: gsha\n"
                     "gherkin_review: accepted\n"
                     "gherkin_review_target_sha: gsha\n"
                     "qa_procedure_path: qa/s1.md\n"
                     "qa_procedure_sha: qsha\n"
                     "qa_procedure_review: accepted\n"
                     "qa_procedure_review_target_sha: qsha\n"
                     "implementation_sha: isha\n"
                     "cleaner_sha: csha\n"))
    (is (= "code-reviewer" (:template (first (next/story-candidates root [])))))
    (write-file (fs/path story-dir "packet")
                (str "theme_id: theme-a\n"
                     "story_id: s1\n"
                     "story_approval: approved\n"
                     "gherkin_approval: approved\n"
                     "qa_procedure_approval: approved\n"
                     "implementation_approval: approved\n"
                     "code_review_approval: approved\n"
                     "gherkin_path: features/s1.feature\n"
                     "gherkin_sha: gsha\n"
                     "gherkin_review: accepted\n"
                     "gherkin_review_target_sha: gsha\n"
                     "qa_procedure_path: qa/s1.md\n"
                     "qa_procedure_sha: qsha\n"
                     "qa_procedure_review: accepted\n"
                     "qa_procedure_review_target_sha: qsha\n"
                     "implementation_sha: isha\n"
                     "cleaner_sha: csha\n"
                     "code_review: accepted\n"
                     "code_review_target_sha: csha\n"
                     "code_review_sha: crsha\n"))
    (is (some #(and (= "record_batch_membership" (:next-action %))
                    (= "hardener" (:batch-kind %)))
              (next/story-candidates root [])))
    (write-file (fs/path story-dir "packet")
                (str "theme_id: theme-a\n"
                     "story_id: s1\n"
                     "code_review_approval: approved\n"
                     "hardener_batch: theme-a-hardener\n"))
    (is (= "hardener" (:template (first (next/batch-candidates root [])))))
    (write-file (fs/path story-dir "packet")
                (str "theme_id: theme-a\n"
                     "story_id: s1\n"
                     "story_approval: approved\n"
                     "gherkin_approval: approved\n"
                     "qa_procedure_approval: approved\n"
                     "implementation_approval: approved\n"
                     "code_review_approval: approved\n"
                     "hardening_approval: approved\n"
                     "gherkin_path: features/s1.feature\n"
                     "gherkin_sha: gsha\n"
                     "gherkin_review: accepted\n"
                     "gherkin_review_target_sha: gsha\n"
                     "qa_procedure_path: qa/s1.md\n"
                     "qa_procedure_sha: qsha\n"
                     "qa_procedure_review: accepted\n"
                     "qa_procedure_review_target_sha: qsha\n"
                     "hardening_approval: approved\n"
                     "hardener_sha: hsha\n"))
    (is (some #(and (= "record_batch_membership" (:next-action %))
                    (= "qa" (:batch-kind %)))
              (next/story-candidates root [])))
    (write-file (fs/path story-dir "packet")
                (str "theme_id: theme-a\n"
                     "story_id: s1\n"
                     "qa_batch: theme-a-qa\n"))
    (is (= "qa" (:template (first (next/batch-candidates root [])))))))

(deftest squad-next-uses-agent-liveness-for-capacity
  (let [root (tmp-dir)
        rows [["swarmforge-implementer-001" "s1-implementation" "ignored"]]]
    (write-file (fs/path root "swarmforge/squad.conf") "max_transient_agents 1\n")
    (write-file (fs/path root ".squad/agents/swarmforge-implementer-001/metadata")
                "template: implementer\ntask_id: s1-implementation\n")
    (write-file (fs/path root ".squad/agents/swarmforge-implementer-001/status")
                "state: running\nupdated_at: 2026-08-04T00:00:00Z\n")
    (write-file (fs/path root ".squad/agents/swarmforge-implementer-001/heartbeat")
                "updated_at: 2026-08-04T00:01:00Z\n")
    (write-file (fs/path root ".squad/agents/swarmforge-implementer-001/liveness")
                "pane_changed: true\nobserved_at: 2026-08-04T00:02:00Z\n")
    (let [agents (next/agent-records root rows)]
      (is (= "pane" (:activity-source (first agents))))
      (is (not (next/spawn-capacity? root agents "implementer")))
      (is (next/active-template? agents "implementer")))))

(deftest config-and-forge-helper-branches
  (let [root (tmp-dir)]
    (write-file (fs/path root "swarmforge/squad.conf")
                (str "approval_required theme false\n"
                     "approval_required qa-procedure required\n"
                     "feature_enabled yes\n"))
    (is (false? (config/squad-approval-required? root "theme")))
    (is (true? (config/squad-approval-required? root "qa_procedure")))
    (is (false? (config/squad-approval-required? root "final")))
    (is (true? (config/squad-config-bool root "feature_enabled" false)))
    (is (false? (config/parse-config-bool "off" true)))
    (is (= "iterm2" (forge/normalize-terminal-backend "iTerm.app")))
    (is (= "custom" (forge/normalize-terminal-backend "custom")))
    (with-redefs [forge/command-exists? #{"osascript"}]
      (is (#{"iterm2" "terminal-app"} (forge/detect-terminal-backend))))
    (with-redefs [forge/command-exists? #{"wt.exe"}]
      (is (= "windows-terminal" (forge/detect-terminal-backend))))))

(deftest status-and-report-helper-branches
  (let [root (tmp-dir)
        metadata (fs/path root "metadata")
        state-file (fs/path root "status")
        heartbeat (fs/path root "heartbeat")
        liveness (fs/path root "liveness")]
    (write-file metadata "task_id: t1\ntemplate: implementer\n")
    (write-file state-file "state: running\ndetail: busy\nupdated_at: 2026-08-04T00:00:00Z\n")
    (write-file heartbeat "updated_at: 2026-08-04T00:00:01Z\n")
    (write-file liveness "state: running_pane_idle\nobserved_at: now\npane_changed: false\nlast_10_lines:\none\ntwo\n")
    (is (= "one" (first (str/split-lines (status/read-liveness-tail liveness)))))
    (is (= :unknown (status/pane-status nil "s")))
    (is (= :not-live (status/pane-status "sock" "")))
    (with-redefs [status/tmux-session-exists? (constantly false)]
      (is (= :not-live (status/pane-status "sock" "session"))))
    (with-redefs [status/tmux-session-exists? (constantly true)
                  status/pane-dead? (constantly true)]
      (is (= :dead (status/pane-status "sock" "session"))))
    (with-redefs [status/tmux-session-exists? (constantly true)
                  status/pane-dead? (constantly false)]
      (is (= :live (status/pane-status "sock" "session"))))
    (is (= [["AGENT" "agent-001"]
            ["TASK_ID" "t1"]
            ["TEMPLATE" "implementer"]
            ["SESSION" "session"]
            ["STATE" "running"]
            ["DETAIL" "busy"]
            ["UPDATED_AT" "2026-08-04T00:00:00Z"]
            ["HEARTBEAT_AT" "2026-08-04T00:00:01Z"]]
           (status/agent-metadata-fields "agent-001" metadata state-file heartbeat "session")))
    (is (str/includes? (report/assignment-summary {:id "a1" :template "cleaner" :story "s1" :state "blocked"})
                       "result=no"))))

(deftest squad-next-helper-branches
  (let [root (tmp-dir)
        rows [["agent-001" "task-1"]]]
    (write-file (fs/path root ".squad/agents/agent-001/metadata") "template: implementer\n")
    (write-file (fs/path root ".squad/agents/agent-001/status") "state: running\nupdated_at: 2026-08-04T00:00:00Z\n")
    (write-file (fs/path root ".squad/agents/agent-001/heartbeat") "updated_at: 2026-08-04T00:01:00Z\n")
    (is (= "heartbeat" (:activity-source (first (next/agent-records root rows)))))
    (write-file (fs/path root ".squad/agents/agent-001/liveness") "pane_changed: true\nobserved_at: 2026-08-04T00:02:00Z\n")
    (is (= "pane" (:activity-source (first (next/agent-records root rows)))))
    (write-file (fs/path root ".squad/batches/theme-hardener/status") "state: open\n")
    (write-file (fs/path root ".squad/batches/theme-hardener/metadata") "kind: hardener\n")
    (is (= "theme-hardener" (next/next-batch-id root [] "theme" "hardener" "hardener")))
    (is (= "theme-qa" (next/next-batch-id root [] "theme" "qa" "qa")))
    (is (true? (next/requirement-satisfied? root {"story_approval" "approved"} [] "approval:story")))
    (is (false? (next/requirement-satisfied? root nil [] "bogus:story")))))

(deftest squadd-helper-branches
  (let [root (tmp-dir)
        logs (atom [])]
    (with-redefs [squadd/log! (fn [_ & parts] (swap! logs conj parts))
                  squadd/git-continue (fn [& _] {:exit 0})
                  fs/exists? (fn [path] (not (str/includes? (str path) "missing")))]
      (squadd/cleanup-worktree! root "agent-001" (fs/path root ".worktrees/agent-001"))
      (is (seq @logs)))
    (is (= :active (squadd/sl-watchdog-log-state {:changed? true :prompt? true :idle-for 100} 60 true)))
    (is (= :not-idle (squadd/sl-watchdog-log-state {:changed? false :prompt? false :idle-for 100} 60 true)))
    (is (= :below-threshold (squadd/sl-watchdog-log-state {:changed? false :prompt? true :idle-for 10} 60 true)))
    (is (= :throttled (squadd/sl-watchdog-log-state {:changed? false :prompt? true :idle-for 100} 60 false)))
    (is (= :notify (squadd/sl-watchdog-log-state {:changed? false :prompt? true :idle-for 100} 60 true)))
    (with-redefs [squadd/tmux-session-exists? (constantly false)]
      (is (nil? (squadd/maybe-tmux-alert root "sock" true "agent" "session")))
      (is (nil? (squadd/maybe-tmux-alert root "sock" false "agent" "session"))))
    (with-redefs [squadd/tmux-session-exists? (constantly true)
                  squadd/pane-dead? (constantly true)]
      (is (str/includes? (squadd/maybe-tmux-alert root "sock" false "agent" "session") "dead")))))

(deftest recover-and-tool-helper-branches
  (let [root (tmp-dir)
        paths (tool/cache-paths (fs/path root "tools"))]
    (is (= "live" (recover/recovery-state {:live? true})))
    (is (= "delivered_handoff" (recover/recovery-state {:handoffs ["h"]})))
    (is (= "dirty_worktree" (recover/recovery-state {:dirty ["M x"]})))
    (is (= "committed_no_handoff" (recover/recovery-state {:committed 1})))
    (with-redefs [recover/recent-status? (constantly true)]
      (is (= "recently_active_no_work" (recover/recovery-state {:root root :agent "a" :committed 0}))))
    (with-redefs [recover/recent-status? (constantly false)]
      (is (= "failed_no_work" (recover/recovery-state {:root root :agent "a" :committed 0}))))
    (is (= :missing (:state (tool/tool-state paths "crap" "src" "1"))))
    (write-file (tool/manifest-file paths "crap") "source: old\nversion: 1\n")
    (is (= "missing executable" (:reason (tool/tool-state paths "crap" "src" "1"))))
    (write-file (tool/executable-target paths "crap") "#!/bin/sh\n")
    (is (= "source" (:field (tool/tool-state paths "crap" "src" "1"))))
    (write-file (tool/manifest-file paths "crap") "source: src\nversion: old\n")
    (is (= "version" (:field (tool/tool-state paths "crap" "src" "1"))))
    (write-file (tool/manifest-file paths "crap") "source: src\nversion: 1\n")
    (is (= :available (:state (tool/tool-state paths "crap" "src" "1"))))))

(deftest simulator-and-spawn-helper-branches
  (is (= [3 3] (simulator/parse-int-range! "Ticks" "3")))
  (is (= [2 5] (simulator/parse-int-range! "Ticks" "2..5")))
  (is (= 7 (simulator/range-end "Ticks" 2 "7")))
  (let [rows [["squad-leader" "master" "/tmp" "session" "Squad Leader" "claude" "task"]]]
    (with-redefs [config/squad-transient-agent-config (constantly "leader")]
      (is (= "claude" (spawn/configured-transient-agent (tmp-dir) rows))))
    (with-redefs [config/squad-transient-agent-config (constantly "codex")]
      (is (= "codex" (spawn/configured-transient-agent (tmp-dir) rows))))
    (with-redefs [config/squad-transient-agent-config (constantly "")]
      (is (nil? (spawn/configured-transient-agent (tmp-dir) rows)))))
  (is (= :gone (squadd/wait-session-gone-step "sock" "session" 20)))
  (with-redefs [squadd/tmux-session-exists? (constantly true)]
    (is (= :timed-out (squadd/wait-session-gone-step "sock" "session" 0)))
    (is (= :retry (squadd/wait-session-gone-step "sock" "session" 1))))
  (is (= :stopped (retire/wait-session-step "sock" "session" 20)))
  (with-redefs [retire/session-exists? (constantly false)]
    (is (= {:stopped? true :detail "tmux session stopped"}
           (retire/wait-session-stopped "sock" "session")))
    (is (= {:stopped? false :detail "tmux session was not running"}
           (retire/stop-session! "sock" "session"))))
  (with-redefs [retire/session-exists? (constantly true)]
    (is (= :timed-out (retire/wait-session-step "sock" "session" 0)))
    (is (= :retry (retire/wait-session-step "sock" "session" 1))))
  (is (= {:stopped? false :detail "tmux socket or session metadata missing"}
         (retire/stop-session! "" "session")))
  (with-redefs [retire/session-exists? (constantly true)
                retire/stop-running-session! (constantly {:stopped? true :detail "stopped"})]
    (is (= {:stopped? true :detail "stopped"}
           (retire/stop-session! "sock" "session"))))
  (spawn-request/validate! "implementer" "story-1"))

(deftest squadd-pane-and-message-helper-branches
  (let [root (tmp-dir)]
    (is (= :missing-session-metadata (squadd/pane-liveness-kind "sock" "")))
    (with-redefs [squadd/tmux-session-exists? (constantly false)]
      (is (= :missing-session (squadd/pane-liveness-kind "sock" "session"))))
    (with-redefs [squadd/tmux-session-exists? (constantly false)]
      (is (true? (squadd/wait-session-gone "sock" "session"))))
    (with-redefs [squadd/tmux-session-exists? (constantly true)
                  squadd/pane-dead? (constantly true)]
      (is (= :dead-pane (squadd/pane-liveness-kind "sock" "session"))))
    (with-redefs [squadd/tmux-session-exists? (constantly true)
                  squadd/pane-dead? (constantly false)]
      (is (= :live-pane (squadd/pane-liveness-kind "sock" "session"))))
    (with-redefs [squadd/missing-session-alert (fn [_ agent session]
                                                 (str agent ":" session))
                  squadd/live-pane-alert (fn [_ _ agent _ age]
                                           (str agent ":" age))]
      (is (str/includes? (squadd/pane-liveness-message root "sock" "agent" "" 10 :missing-session-metadata)
                         "metadata"))
      (is (= "agent:session" (squadd/pane-liveness-message root "sock" "agent" "session" 10 :missing-session)))
      (is (str/includes? (squadd/pane-liveness-message root "sock" "agent" "session" 10 :dead-pane)
                         "dead"))
      (is (= "agent:10" (squadd/pane-liveness-message root "sock" "agent" "session" 10 :live-pane))))
    (with-redefs [squadd/socket-value (constantly nil)]
      (is (= "Missing tmux socket\n" (:error (squadd/sl-message-web-action! root "hello")))))
    (with-redefs [squadd/socket-value (constantly "sock")
                  squadd/send-sl-dashboard-message! (constantly true)
                  squadd/log! (fn [& _])]
      (is (:ok (squadd/sl-message-web-action! root "hello"))))
    (with-redefs [squadd/socket-value (constantly "sock")
                  squadd/send-sl-dashboard-message! (constantly false)]
      (is (= "Could not send message to squad leader\n"
             (:error (squadd/sl-message-web-action! root "hello")))))))
