(ns swarmforge.crap-coverage-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [done-with-current-batch :as done-batch]
            [done-with-current-task :as done-task]
            [done-with-current :as done-current]
            [ready-for-next-batch :as ready-batch]
            [ready-for-next-task :as ready-task]
            [ready-for-next :as ready-next]
            [squad-assign :as assign]
            [squad-approval :as approval]
            [squad-batch :as batch]
            [squad-packet :as packet-tool]
            [squad-config :as config]
            [squad-lease :as lease]
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
            [squadd.web :as squadd-web]
            [stop-handoff-daemon :as stop-handoff]
            [stop-squadd :as stop-squadd]
            [swarm-handoff :as handoff]
            [handoffd :as handoffd]
            [handoff-lib :as handoff-lib]
            [swarm-window-watchdog :as window-watchdog]
            [swarmforge :as forge]))

(def temp-dirs (atom []))

(def minimal-module-map
  (str "# Theme Module Map\n\n"
       "## Purpose\n\nSample purpose.\n\n"
       "## Dependency Rule\n\nDependencies point inward.\n\n"
       "## Use Cases (Business / Process Rules)\n\n"
       "### Use case: sample\n\n- **Intent:** sample.\n\n"
       "## UI (Interface Adapters)\n\nCLI prompts.\n\n"
       "## IO (Interface Adapters / Drivers)\n\nStdin/stdout.\n\n"
       "## Out of Scope\n\nDetailed APIs.\n"))

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

(defn exit-exception [status lines]
  (ex-info "exit" {:status status :lines lines}))

(defn exit-status [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo ex
      (:status (ex-data ex)))))

(deftest squad-state-recomputes-story-lifecycle-states
  (let [cases [{"story_id" "s1"}
               {"story_id" "s1" "story_approval" "approved"}
               {"story_id" "s1" "story_approval" "approved" "gherkin_assignment" "a1"}
               {"story_id" "s1"
                "implementation_plan_approval" "approved"
                "gherkin_approval" "approved"
                "qa_procedure_approval" "approved"}
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
               {"senior_implementer_sha" "sri1"}
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
                     "to: code-reviewer\n"
                     "priority: 05\n"
                     "task: story-one\n"
                     "commit: 0123456789\n"
                     "assignment: story-one\n"
                     "template: code-reviewer\n"
                     "artifacts: none\n"))
    (with-redefs [handoff/role-known? #{"code-reviewer"}
                  handoff/canonical-commit (fn [commit] [commit nil])]
      (let [{:keys [headers ordered errors]} (handoff/parse-draft draft)
            validated (handoff/validate headers ordered)]
        (is (= [] errors))
        (is (= ["type" "to" "priority" "task" "commit" "assignment" "template" "artifacts"] ordered))
        (is (= ["code-reviewer"] (:recipients validated)))
        (is (= "0123456789" (:canonical-commit validated)))
        (is (= [] (:errors validated)))))))

(deftest swarm-handoff-reports-invalid-drafts
  (let [root (tmp-dir)
        draft (fs/path root "bad.handoff")]
    (write-file draft
                (str "type: note\n"
                     "to: code-reviewer,,code-reviewer,bad_role,missing\n"
                     "priority: high\n"
                     "commit: 0123456789\n"
                     "message: " (apply str (repeat 81 "x")) "\n"
                     "message: duplicate\n"
                     "id: reserved\n"
                     "unknown: nope\n"
                     "\n"
                     "payload\n"))
    (with-redefs [handoff/role-known? #{"code-reviewer"}]
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

(deftest swarm-handoff-writes-git-and-note-handoffs
  (let [root (tmp-dir)]
    (fs/create-dirs (fs/path root ".swarmforge/handoffs"))
    (with-redefs [handoff/state-dir (fn [] (fs/path root ".swarmforge/handoffs"))
                  handoff/id-timestamp (constantly "20260804T000000Z")
                  handoff/timestamp (constantly "2026-08-04T00:00:00Z")]
      (is (= "000001" (handoff/write-next-sequence! (fs/path root ".swarmforge/handoffs/sequence"))))
      (let [git-file (handoff/write-handoff! {:headers {"priority" "50"
                                                        "type" "git_handoff"
                                                        "task" "story-1"}
                                              :recipients ["squad-leader"]
                                              :canonical-commit "abcdef1234"
                                              :sender "implementer-001"})
            git-text (slurp (str git-file))
            note-file (handoff/write-handoff! {:headers {"priority" "60"
                                                         "type" "note"
                                                         "message" "status update"}
                                               :recipients ["squad-leader" "qa"]
                                               :sender "analyst-001"})
            note-text (slurp (str note-file))]
        (is (str/includes? git-text "commit: abcdef1234"))
        (is (str/includes? git-text "merge_and_process implementer-001 abcdef1234"))
        (is (str/includes? note-text "to: squad-leader,qa"))
        (is (str/includes? note-text "status update"))
        (is (fs/exists? (fs/path root ".swarmforge/handoffs/sent")))
        (is (fs/exists? (fs/path root ".swarmforge/handoffs/failed")))))))

(deftest squad-packet-create-covers-success-and-guards
  (let [root (tmp-dir)]
    (write-file (fs/path root "stories/cave.md") "Cave.\n")
    (with-redefs [packet-tool/project-root (constantly root)
                  packet-tool/timestamp (constantly "2026-08-04T00:00:00Z")
                  packet-tool/exit! (fn [status & lines]
                                      (throw (exit-exception status lines)))]
      (packet-tool/create-packet! "cave" "analysis-001" "branch-1" "abcdef1234")
      (let [packet-text (slurp (str (fs/path root ".squad/stories/cave/packet")))
            events (slurp (str (fs/path root ".squad/stories/cave/events.log")))]
        (is (str/includes? packet-text "story_path: stories/cave.md"))
        (is (str/includes? packet-text "story_iterations: analysis-001=recorded"))
        (is (not (str/includes? packet-text "theme_id:")))
        (is (str/includes? events "story_recorded")))
      (is (= 2 (exit-status #(packet-tool/create-packet! "bad/id" "analysis" "branch" "abcdef1234"))))
      (is (= 1 (exit-status #(packet-tool/create-packet! "cave2" "analysis" "branch" "abcdef1234"))))
      (is (= 2 (exit-status #(packet-tool/create-packet! "cave" "analysis" "branch" "abcdef1234")))))))

(deftest squad-assign-parses-requirements-and-renders-assignments
  (with-redefs [assign/exit! (fn [status & lines]
                               (throw (exit-exception status lines)))]
    (is (= {:kind "approval" :value "story" :text "approval:story"}
           (assign/parse-requirement! "approval:story")))
    (is (nil? (assign/parse-requirement! nil)))
    (is (= 2 (exit-status #(assign/parse-requirement! "gate:story"))))
    (is (= 2 (exit-status #(assign/parse-requirement! "approval:"))))
    (is (= "story-1" (:story-id (assign/parse-create-args!
                                 ["create" "story-1" "implementer" "story-1-impl" "instructions.md"]))))
    (is (= "story" (get-in (assign/parse-create-args!
                            ["create" "story-1" "implementer" "story-1-impl" "instructions.md"
                             "--requires" "approval:story"])
                           [:requirement :value])))
    (is (= 1 (exit-status #(assign/parse-create-args! ["create" "too-short"]))))
    (is (= 1 (exit-status #(assign/parse-create-args!
                            ["create" "story" "template" "assignment" "file" "--bad" "approval:story"])))))
  (let [text (assign/render-assignment {:theme-id "theme"
                                        :story-id "story-1"
                                        :template "cleaner"
                                        :assignment-id "story-1-cleaner"
                                        :scope "story"
                                        :theme-text "Theme text"
                                        :story-text "Story text"
                                        :instructions-text "Instructions"
                                        :requirement {:text "approval:story"}
                                        :packet-text "state: implemented\n"
                                        :required-tools [{:name "crap4clj"
                                                          :source "github.com/unclebob/crap4clj"
                                                          :version "latest"
                                                          :purpose "CRAP"}]
                                        :optional-tools [{:name "dry4clj"
                                                          :source "github.com/unclebob/dry4clj"
                                                          :version "latest"}]})]
    (is (str/includes? text "requires: approval:story"))
    (is (str/includes? text "## Story Packet"))
    (is (str/includes? text "crap4clj (CRAP)"))
    (is (str/includes? text "dry4clj"))))

(deftest squad-retire-orchestrates-registration-and-cleanup
  (let [root (tmp-dir)
        worktree (fs/path root ".worktrees/agent-001")]
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                     "agent-001\tagent-001\t" worktree "\tswarmforge-agent-001\tAgent 001\tcodex\ttask\n"))
    (write-file (fs/path root ".swarmforge/tmux-socket") "sock\n")
    (write-file (fs/path root ".squad/agents/agent-001/metadata") "task_id: task-1\n")
    (fs/create-dirs worktree)
    (with-redefs [retire/project-root (constantly root)
                  retire/acquire-lock! (fn [_])
                  retire/stop-session! (fn [socket session]
                                         {:stopped? (= ["sock" "swarmforge-agent-001"] [socket session])
                                          :detail "tmux session stopped"})
                  retire/remove-worktree! (fn [_ agent-id path]
                                            {:removed? (= [(str worktree) "agent-001"]
                                                          [(str path) agent-id])
                                             :detail "worktree removed"})
                  retire/delete-branch! (fn [_ agent-id]
                                          {:deleted? (= "agent-001" agent-id)
                                           :branch (str "swarmforge-" agent-id)})
                  retire/timestamp (constantly "2026-08-04T00:00:00Z")]
      (retire/retire! "agent-001")
      (let [roles (slurp (str (fs/path root ".swarmforge/roles.tsv")))
            status (slurp (str (fs/path root ".squad/agents/agent-001/status")))
            heartbeat (slurp (str (fs/path root ".squad/agents/agent-001/heartbeat")))]
        (is (not (str/includes? roles "agent-001")))
        (is (str/includes? status "state: retired"))
        (is (str/includes? status "branch swarmforge-agent-001 deleted"))
        (is (str/includes? heartbeat "task_id: task-1"))))))

(deftest current-command-dispatches-by-receive-mode
  (let [calls (atom [])]
    (with-redefs [done-current/role (constantly "agent-001")
                  done-current/receive-mode (constantly "batch")
                  done-current/run-helper! #(swap! calls conj [:done %])]
      (done-current/-main)
      (is (= [[:done "done_with_current_batch.sh"]] @calls)))
    (reset! calls [])
    (with-redefs [done-current/role (constantly "agent-001")
                  done-current/receive-mode (constantly "task")
                  done-current/run-helper! #(swap! calls conj [:done %])]
      (done-current/-main)
      (is (= [[:done "done_with_current_task.sh"]] @calls)))
    (with-redefs [done-current/role (constantly "agent-001")
                  done-current/receive-mode (constantly "bogus")
                  done-current/exit! (fn [status message]
                                       (throw (exit-exception status [message])))]
      (is (= 2 (exit-status done-current/-main)))))
  (let [calls (atom [])]
    (with-redefs [ready-next/role (constantly "agent-001")
                  ready-next/receive-mode (constantly "batch")
                  ready-next/run-helper! #(swap! calls conj [:ready %])]
      (ready-next/-main)
      (is (= [[:ready "ready_for_next_batch.sh"]] @calls)))
    (reset! calls [])
    (with-redefs [ready-next/role (constantly "agent-001")
                  ready-next/receive-mode (constantly "task")
                  ready-next/run-helper! #(swap! calls conj [:ready %])]
      (ready-next/-main)
      (is (= [[:ready "ready_for_next_task.sh"]] @calls)))
    (with-redefs [ready-next/role (constantly "agent-001")
                  ready-next/receive-mode (constantly "bogus")
                  ready-next/exit! (fn [status message]
                                     (throw (exit-exception status [message])))]
      (is (= 2 (exit-status ready-next/-main))))))

(deftest theme-and-approval-helper-branches
  (let [root (tmp-dir)]
    (write-file (fs/path root ".squad/stories/cave/packet") "story_id: cave\n")
    (is (false? (approval/target-exists? root "theme" "wumpus")))
    (is (approval/target-exists? root "story" "cave"))
    (is (false? (approval/target-exists? root "batch" "x")))
    (is (empty? (approval/command-for "theme" "wumpus" "theme" "ok")))
    (is (= "qa-procedure" (nth (approval/command-for "story" "cave" "qa_procedure" "ok") 3)))))

(deftest squadd-web-approval-and-role-reconciliation-branches
  (let [root (tmp-dir)]
    (write-file (fs/path root ".swarmforge/tmux-socket") "sock\n")
    (with-redefs [process/sh (fn [& _] {:exit 0 :out "approved\n" :err ""})
                  squadd-web/tmux-notify! (fn [socket session message]
                                            (= ["sock" "swarmforge-squad-leader" squadd-web/approval-wake-message]
                                           [socket session message]))]
      (is (= {:ok true :output "approved\n"}
             (squadd-web/approval-web-action! root "story__cave" "approve"))))
    (fs/delete-if-exists (fs/path root ".swarmforge/tmux-socket"))
    (with-redefs [process/sh (fn [& _] {:exit 0 :out "rejected\n" :err ""})
                  squadd-web/tmux-notify! (fn [& _] (throw (ex-info "should not notify" {})))]
      (is (= {:ok true :output "rejected\n"}
             (squadd-web/approval-web-action! root "story__cave" "reject"))))
    (with-redefs [process/sh (fn [& _] {:exit 1 :out "out" :err "err"})]
      (is (= {:ok false :status 409 :error "errout"}
             (squadd-web/approval-web-action! root "story__cave" "approve")))))
  (let [root (tmp-dir)]
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
    (write-file (fs/path root ".squad/agents/agent-001/metadata")
                (str "agent_id: agent-001\n"
                     "template: implementer\n"
                     "worktree: " root "/.worktrees/agent-001\n"
                     "session: swarmforge-agent-001\n"
                     "display: Agent 001\n"
                     "backend: codex\n"))
    (write-file (fs/path root ".squad/agents/agent-001/status") "state: running\n")
    (let [roles (squadd/reconcile-roles! root)]
      (is (contains? roles "agent-001"))
      (is (str/includes? (slurp (str (fs/path root ".swarmforge/roles.tsv"))) "agent-001")))
    (is (= (squadd/load-roles root) (squadd/reconcile-roles! root)))))

(deftest squadd-artifact-content-reads-dashboard-artifacts
  (let [root (tmp-dir)]
    (write-file (fs/path root ".squad/themes/wumpus/theme.md")
                "Theme text\n")
    (write-file (fs/path root ".squad/themes/wumpus/module-map.md")
                "Module map text\n")
    (write-file (fs/path root "stories/cave.md")
                "Story text\n")
    (write-file (fs/path root "features/cave.feature")
                "Gherkin text\n")
    (write-file (fs/path root "qa/cave.md")
                "QA procedure text\n")
    (write-file (fs/path root ".squad/stories/cave/packet")
                (str "story_path: stories/cave.md\n"
                     "gherkin_path: features/cave.feature\n"
                     "qa_procedure_path: qa/cave.md\n"))
    (write-file (fs/path root ".squad/stories/fallback/packet")
                "story_id: fallback\n")
    (write-file (fs/path root ".squad/reviews/review-001.md")
                "Review text\n")
    (write-file (fs/path root ".squad/assignments/review-002/review")
                "Assignment review text\n")
    (write-file (fs/path root ".squad/assignments/blocker-001/blocker.md")
                "Blocker text\n")
    (is (str/includes? (squadd-web/artifact-content root "theme" "wumpus")
                       "Theme text\n"))
    (is (str/includes? (squadd-web/artifact-content root "theme" "wumpus")
                       "Implementation Order"))
    (is (str/includes? (squadd-web/artifact-content root "theme" "wumpus")
                       "## Module Map\n\nModule map text\n"))
    (is (str/includes? (squadd-web/artifact-content root "story" "cave")
                       "## Story\n\nStory text\n"))
    (is (str/includes? (squadd-web/artifact-content root "story" "cave")
                       "## Story Packet\n\nstory_path: stories/cave.md"))
    (is (str/includes? (squadd-web/artifact-content root "story" "cave")
                       "## Gherkin\n\nGherkin text\n"))
    (is (str/includes? (squadd-web/artifact-content root "story" "cave")
                       "## QA Procedure\n\nQA procedure text\n"))
    (is (= "Gherkin text\n" (squadd-web/artifact-content root "gherkin" "cave")))
    (is (= "QA procedure text\n" (squadd-web/artifact-content root "qa-procedure" "cave")))
    (is (= "## Story Packet\n\nstory_id: fallback\n\n"
           (squadd-web/artifact-content root "story" "fallback")))
    (is (= "Review text\n" (squadd-web/artifact-content root "review" "review-001")))
    (is (= "Assignment review text\n" (squadd-web/artifact-content root "review" "review-002")))
    (is (= "Blocker text\n" (squadd-web/artifact-content root "blocker" "blocker-001")))
    (is (nil? (squadd-web/artifact-content root "unknown" "cave")))))

(deftest stop-handoff-daemon-covers-pid-and-stop-file-branches
  (let [root (tmp-dir)
        daemon-dir (fs/path root ".swarmforge/daemon")
        pid-file (fs/path daemon-dir "handoffd.pid")
        terminated (atom [])]
    (write-file pid-file "12345\n")
    (with-redefs [stop-handoff/process-alive? (fn [pid] (= pid "12345"))
                  stop-handoff/terminate-process! (fn [pid timeout]
                                                    (swap! terminated conj [pid timeout]))]
      (stop-handoff/stop! root :timeout-ms 17)
      (is (= [["12345" 17]] @terminated))
      (is (not (fs/exists? pid-file)))
      (is (not (fs/exists? (fs/path daemon-dir "stop")))))
    (reset! terminated [])
    (write-file pid-file "not-a-pid\n")
    (stop-handoff/stop! root :timeout-ms 17)
    (is (= [] @terminated))
    (is (not (fs/exists? pid-file)))))

(deftest handoff-canonical-commit-covers-resolution-branches
  (with-redefs [handoff/command (fn [_ & args]
                                  (let [args (vec args)]
                                    (cond
                                    (= ["git" "rev-parse" "--disambiguate=none"] args)
                                    {:exit 0 :out "\n"}
                                    (= ["git" "rev-parse" "--disambiguate=many"] args)
                                    {:exit 0 :out "a\nb\n"}
                                    (= ["git" "rev-parse" "--disambiguate=treeish"] args)
                                    {:exit 0 :out "abc\n"}
                                    (= ["git" "cat-file" "-t" "abc"] args)
                                    {:exit 0 :out "tree\n"}
                                    (= ["git" "rev-parse" "--disambiguate=commitish"] args)
                                    {:exit 0 :out "def\n"}
                                    (= ["git" "cat-file" "-t" "def"] args)
                                    {:exit 0 :out "commit\n"}
                                    (= ["git" "rev-parse" "--short=10" "def"] args)
                                    {:exit 0 :out "def1234567\n"})))]
    (is (str/includes? (second (handoff/canonical-commit "none")) "matched 0"))
    (is (str/includes? (second (handoff/canonical-commit "many")) "matched 2"))
    (is (str/includes? (second (handoff/canonical-commit "treeish")) "resolves to 'tree'"))
    (is (= ["def1234567" nil] (handoff/canonical-commit "commitish")))))

(deftest simulator-spawn-and-wait-helpers-cover-stall-branches
  (let [root (tmp-dir)
        counters (atom {})
        scheduled (atom [])
        command {"template" "implementer" "assignment" "story-impl"}]
    (write-file (fs/path root ".swarmforge/roles.tsv") "")
    (let [normal (simulator/spawn-agent! root counters scheduled 10
                                         (assoc simulator/default-options
                                                :handoff-ticks-range [3 3]
                                                :stall-percent 0)
                                         (java.util.Random. 1)
                                         command)]
      (is (= {:agent "implementer-001" :due 13 :stalled? false} normal))
      (is (= [{:type :handoff :due 13 :agent "implementer-001" :assignment "story-impl"}]
             @scheduled)))
    (reset! scheduled [])
    (let [dark (simulator/spawn-agent! root counters scheduled 20
                                       (assoc simulator/default-options
                                              :handoff-ticks-range [3 3]
                                              :stall-percent 100
                                              :stall-mode "dark")
                                       (java.util.Random. 1)
                                       command)]
      (is (= {:agent "implementer-002" :stalled? true :stall-mode "dark"} dark))
      (is (= [] @scheduled)))
    (reset! scheduled [])
    (let [active (simulator/spawn-agent! root counters scheduled 30
                                         (assoc simulator/default-options
                                                :handoff-ticks-range [3 3]
                                                :stall-percent 100
                                                :stall-mode "active-then-handoff"
                                                :stall-active-ticks-range [2 2])
                                         (java.util.Random. 1)
                                         command)]
      (is (= "active-then-handoff" (:stall-mode active)))
      (is (= 35 (:due active)))
      (is (= #{:liveness :handoff} (set (map :type @scheduled))))))
  (let [pending (atom nil)]
    (simulator/record-wait! pending 1 "NEXT_ACTION: wait\n")
    (simulator/record-wait! pending 2 "NEXT_ACTION: wait\n")
    (let [out (with-out-str (simulator/flush-wait! pending))]
      (is (str/includes? out "TICK 001..002"))
      (is (str/includes? out "WAIT_TICKS: 2"))
      (is (nil? @pending)))))

(deftest recover-context-and-state-helper-branches
  (let [root (tmp-dir)]
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (str "agent-001\tagent-001\t" root "/.worktrees/agent-001\tsession\tAgent\tcodex\ttask\n"))
    (write-file (fs/path root ".squad/agents/agent-001/metadata")
                "task_id: story-1\ntemplate: implementer\n")
    (with-redefs [recover/tmux-session-exists? (constantly true)
                  recover/dirty-lines (constantly [])
                  recover/committed-count (constantly 0)
                  recover/handoff-files (constantly [])]
      (let [ctx (recover/recovery-context root "agent-001")]
        (is (= "story-1" (:task-id ctx)))
        (is (= "implementer" (:template ctx)))
        (let [checked (recover/checked-recovery root "agent-001" ctx)]
          (is (:live? checked))
          (is (= "live" (:state checked))))))
    (is (= "unknown" (recover/value-or-unknown nil)))
    (is (= "false" (recover/bool-string false)))))

(deftest lock-and-validation-helper-branches
  (let [root (tmp-dir)
        paths (tool/cache-paths (fs/path root "tools"))]
    (fs/create-dirs (:locks paths))
    (with-redefs [tool/exit! (fn [status & lines]
                               (throw (exit-exception status lines)))]
      (is (= 2 (exit-status #(tool/validate-tool! "bad/tool"))))
      (is (= 3 (exit-status #(tool/ensure-tool-state! "crap" {:state :missing :reason "missing"}))))
      (is (= 4 (exit-status #(tool/ensure-tool-state! "crap" {:state :mismatch
                                                               :field "source"
                                                               :expected "new"
                                                               :actual "old"}))))
      (is (= {:state :available :executable "bin/crap"}
             (tool/ensure-tool-state! "crap" {:state :available :executable "bin/crap"})))
      (let [lock (tool/acquire-lock! (:locks paths) "crap")]
        (is (fs/directory? lock))
        (is (fs/regular-file? (tool/lock-owner-file lock)))
        (fs/delete-tree lock))
      (let [stale-lock (fs/path (:locks paths) "stale.lock")]
        (fs/create-dir stale-lock)
        (with-redefs [tool/old-enough-no-owner-lock? (constantly true)]
          (let [lock (tool/acquire-lock! (:locks paths) "stale")]
            (is (fs/directory? lock))
            (is (fs/regular-file? (tool/lock-owner-file lock)))
            (fs/delete-tree lock))))
      (with-redefs [tool/lock-timeout? (constantly true)]
        (is (= 2 (exit-status #(tool/acquire-lock! (:locks paths) "stuck")))))))
  (let [root (tmp-dir)]
    (with-redefs [retire/exit! (fn [status & lines]
                                 (throw (exit-exception status lines)))]
      (is (= 2 (exit-status #(retire/validate-agent-id! "bad/id"))))
      (is (= 2 (exit-status #(retire/validate-agent-id! "squad-leader"))))
      (let [lock-dir (fs/path root ".swarmforge/squad/spawn.lock")]
        (fs/create-dirs (fs/parent lock-dir))
        (is (nil? (retire/acquire-lock! lock-dir)))
        (is (fs/directory? lock-dir))
        (fs/delete-tree lock-dir))
      (with-redefs [lease/acquire! (fn [_ _ & _]
                                     (throw (ex-info "Timed out waiting for squad registry lock: never.lock"
                                                     {:lease "never.lock"})))]
        (is (= 2 (exit-status #(retire/acquire-lock! (fs/path root "never.lock")))))))
    (is (= {:ok? false :detail "worktree metadata missing"}
           (retire/worktree-removable? root "agent-001" "")))
    (is (= {:ok? false :detail "worktree path is outside managed transient worktrees"}
           (retire/worktree-removable? root "agent-001" (fs/path root "outside"))))
    (is (= {:ok? true}
           (retire/worktree-removable? root "agent-001" (fs/path root ".worktrees/agent-001")))))
  (let [root (tmp-dir)]
    (with-redefs [spawn/exit! (fn [status & lines]
                                (throw (exit-exception status lines)))]
      (is (= 2 (exit-status #(spawn/validate-task-id! "bad/task"))))
      (let [lock-dir (fs/path root ".swarmforge/squad/spawn.lock")]
        (fs/create-dirs (fs/parent lock-dir))
        (is (nil? (spawn/acquire-lock! lock-dir)))
        (is (fs/directory? lock-dir))
        (fs/delete-tree lock-dir))
      (with-redefs [lease/acquire! (fn [_ _ & _]
                                     (throw (ex-info "Timed out waiting for squad registry lock: never.lock"
                                                     {:lease "never.lock"})))]
        (is (= 2 (exit-status #(spawn/acquire-lock! (fs/path root "never.lock")))))))))

(deftest packet-map-and-validation-helper-branches
  (let [root (tmp-dir)]
    (is (= {} (packet-tool/packet-map root "missing")))
    (write-file (fs/path root ".squad/stories/cave/packet")
                "story_id: cave\nignored line\nstate: story_recorded\n")
    (is (= {"story_id" "cave" "state" "story_recorded"}
           (packet-tool/packet-map root "cave")))
    (with-redefs [packet-tool/exit! (fn [status & lines]
                                      (throw (exit-exception status lines)))]
      (is (= 2 (exit-status #(packet-tool/validate-id! "Story id" "bad/id"))))
      (is (= 2 (exit-status #(packet-tool/validate-sha! "not-a-sha"))))
      (is (= 2 (exit-status #(packet-tool/validate-artifact-kind! "code")))))))

(deftest batch-and-task-handoff-helper-branches
  (let [lines ["id: one" "completed_at: old" "" "body"]]
    (is (= ["id: one" "completed_at: now" "" "body"]
           (done-batch/header-lines-with lines "completed_at" "now")))
    (is (= ["id: one" "dequeued_at: now" "" "body"]
           (ready-task/header-lines-with ["id: one" "" "body"] "dequeued_at" "now")))
    (is (= :insert (done-task/header-line-action false "x: " "")))
    (is (= :replace (ready-batch/header-line-action false "x: " "x: old")))
    (is (= :copy (ready-task/header-line-action true "x: " "x: old"))))
  (with-redefs [done-batch/fail! (fn [status & lines]
                                   (throw (exit-exception status lines)))]
    (is (= 2 (exit-status #(done-batch/ensure-current-batch-state! ["task.handoff"] []))))
    (is (= 1 (exit-status #(done-batch/ensure-current-batch-state! [] []))))
    (is (= 2 (exit-status #(done-batch/ensure-current-batch-state! [] ["batch_1" "batch_2"]))))
    (is (nil? (done-batch/ensure-current-batch-state! [] ["batch_1"]))))
  (with-redefs [done-task/fail! (fn [status & lines]
                                  (throw (exit-exception status lines)))]
    (is (= 2 (exit-status #(done-task/ensure-current-task-state! ["batch_1"] []))))
    (is (= 1 (exit-status #(done-task/ensure-current-task-state! [] []))))
    (is (= 2 (exit-status #(done-task/ensure-current-task-state! [] ["a.handoff" "b.handoff"]))))
    (is (nil? (done-task/ensure-current-task-state! [] ["a.handoff"]))))
  (with-redefs [ready-batch/fail! (fn [status & lines]
                                    (throw (exit-exception status lines)))]
    (is (= 2 (exit-status #(ready-batch/ensure-ready-batch-state! ["task.handoff"] []))))
    (is (= 2 (exit-status #(ready-batch/ensure-ready-batch-state! [] ["batch_1" "batch_2"]))))
    (is (nil? (ready-batch/ensure-ready-batch-state! [] ["batch_1"]))))
  (with-redefs [ready-task/fail! (fn [status & lines]
                                   (throw (exit-exception status lines)))]
    (is (= 2 (exit-status #(ready-task/ensure-no-batch-in-process! ["batch_1"]))))
    (is (= 2 (exit-status #(ready-task/ensure-unambiguous-task! ["a.handoff" "b.handoff"]))))
    (is (nil? (ready-task/ensure-no-batch-in-process! [])))
    (is (nil? (ready-task/ensure-unambiguous-task! ["a.handoff"])))))

(deftest ready-and-done-file-movement-helper-branches
  (let [root (tmp-dir)
        new-dir (fs/path root "new")
        in-process-dir (fs/path root "in_process")
        completed-dir (fs/path root "completed")
        source (fs/path new-dir "50_a.handoff")]
    (write-file source "id: a\npriority: 50\n\npayload\n")
    (fs/create-dirs in-process-dir)
    (with-redefs [ready-task/timestamp (constantly "now")]
      (let [target (ready-task/move-new-task! source in-process-dir)]
        (is (fs/exists? target))
        (is (str/includes? (slurp (str target)) "dequeued_at: now"))))
    (write-file source "id: a\npriority: 50\n\npayload\n")
    (with-redefs [ready-task/fail! (fn [status & lines]
                                     (throw (exit-exception status lines)))]
      (is (= 2 (exit-status #(ready-task/move-new-task! source in-process-dir))))))
  (let [root (tmp-dir)
        source-dir (fs/path root "in_process/batch_1")
        completed-dir (fs/path root "completed")
        handoff (fs/path source-dir "50_a.handoff")]
    (write-file handoff "id: a\n\npayload\n")
    (fs/create-dirs completed-dir)
    (with-redefs [done-batch/timestamp (constantly "done")
                  done-batch/run-ready! (fn [])]
      (done-batch/complete-batch! source-dir completed-dir)
      (let [target (fs/path completed-dir "batch_1" "50_a.handoff")]
        (is (fs/exists? target))
        (is (str/includes? (slurp (str target)) "completed_at: done"))))
    (write-file handoff "id: a\n\npayload\n")
    (fs/create-dirs (fs/path completed-dir "batch_1"))
    (with-redefs [done-batch/fail! (fn [status & lines]
                                     (throw (exit-exception status lines)))]
      (is (= 2 (exit-status #(done-batch/complete-batch! source-dir completed-dir)))))))

(deftest squadd-watchdog-and-spawn-archive-helper-branches
  (let [root (tmp-dir)
        log-lines (atom [])]
    (write-file (fs/path root ".swarmforge/tmux-socket") "sock\n")
    (with-redefs [squadd/pending-approval? (constantly false)
                  squadd/tmux-session-exists? (constantly true)
                  squadd/pane-dead? (constantly false)
                  squadd/sl-watchdog-observation (constantly {:state-file (fs/path root ".swarmforge/daemon/sl-watchdog")
                                                              :current-hash "h"
                                                              :unchanged-since "2026-08-04T00:00:00Z"
                                                              :idle-for 61
                                                              :prompt? true
                                                              :changed? false
                                                              :tail "ready>"})
                  squadd/sl-watchdog-due? (constantly true)
                  squadd/tmux-notify! (constantly true)
                  squadd/log! (fn [_ & parts] (swap! log-lines conj parts))]
      (squadd/poll-sl-watchdog! {:root root})
      (is (fs/exists? (fs/path root ".swarmforge/daemon/sl-watchdog")))
      (is (some #(= ["sl-watchdog-notified" "61"] %) @log-lines)))
    (reset! log-lines [])
    (with-redefs [squadd/pending-approval? (constantly true)
                  squadd/log! (fn [_ & parts] (swap! log-lines conj parts))]
      (is (nil? (squadd/poll-sl-watchdog! {:root root})))
      (is (= [] @log-lines)))
    (with-redefs [squadd/pending-approval? (constantly false)
                  squadd/tmux-session-exists? (constantly false)]
      (is (nil? (squadd/poll-sl-watchdog! {:root root})))))
  (let [root (tmp-dir)
        active (fs/path root ".squad/spawn-requests/in_process/story.request")
        completed (fs/path root ".squad/spawn-requests/completed")
        failed (fs/path root ".squad/spawn-requests/failed")
        logs (atom [])]
    (write-file active "template: implementer\n")
    (with-redefs [squadd/log! (fn [_ & parts] (swap! logs conj parts))]
      (squadd/archive-spawn-result! root active "story.request" completed failed
                                    {:exit 0 :out "ok\n" :err ""})
      (is (fs/exists? (fs/path completed "story.request")))
      (is (= "ok\n" (slurp (str (fs/path completed "story.request.out")))))
      (is (some #(= ["spawn-request-completed" (str active)] %) @logs)))
    (reset! logs [])
    (write-file active "template: implementer\n")
    (with-redefs [squadd/log! (fn [_ & parts] (swap! logs conj parts))]
      (squadd/archive-spawn-result! root active "story.request" completed failed
                                    {:exit 9 :out "" :err "bad\n"})
      (is (fs/exists? (fs/path failed "story.request")))
      (is (= "exit 9\n" (slurp (str (fs/path failed "story.request.error")))))
      (is (some #(= ["spawn-request-failed" (str active) "exit 9"] %) @logs)))))

(deftest squad-next-retirement-and-lock-helper-branches
  (let [root (tmp-dir)]
    (write-file (fs/path root ".swarmforge/squad/spawn.lock/owner") "pid: 999999999\n")
    (with-redefs [next/pid-alive? (constantly false)]
      (let [lock (next/stale-lock root)]
        (is (= 999999999 (:pid lock)))
        (is (str/ends-with? (str (:lock lock)) "spawn.lock"))))
    (fs/delete-tree (fs/path root ".swarmforge/squad/spawn.lock"))
    (fs/create-dirs (fs/path root ".swarmforge/squad/spawn.lock"))
    (is (nil? (:pid (next/stale-lock root))))
    (let [lock-dir (fs/path root "lock2")]
      (fs/create-dirs lock-dir)
      (write-file (fs/path lock-dir "owner") "pid: 123\n")
      (is (= 123 (next/lock-owner-pid lock-dir)))))
  (let [candidate {:next-action "wait" :theme-id "theme"}]
    (let [out (with-out-str
                (next/print-candidate-field! candidate ["NEXT_ACTION" :next-action true])
                (next/print-candidate-field! candidate ["MISSING_REQUIRED" :missing true])
                (next/print-candidate-field! candidate ["MISSING_OPTIONAL" :optional false]))]
      (is (str/includes? out "NEXT_ACTION: wait"))
      (is (str/includes? out "MISSING_REQUIRED: "))
      (is (not (str/includes? out "MISSING_OPTIONAL")))))
  (let [root (tmp-dir)
        rows [["agent-001" "agent-001" "/tmp/agent" "session" "Agent" "codex" "task"]]]
    (write-file (fs/path root ".squad/agents/agent-001/status") "state: handoff_sent\n")
    (write-file (fs/path root ".swarmforge/handoffs/inbox/completed/50_from_agent-001_to_squad-leader.handoff")
                "task: agent-001\n")
    (let [candidate (next/retirement-candidate root rows)]
      (is (= "agent-001" (:agent candidate)))
      (is (= "handoff_sent" (:state candidate))))
    (fs/delete-if-exists (fs/path root ".swarmforge/handoffs/inbox/completed/50_from_agent-001_to_squad-leader.handoff"))
    (write-file (fs/path root ".squad/agents/agent-001/status") "state: running\n")
    (is (nil? (next/retirement-candidate root rows)))))

(deftest squad-status-agent-listing-helper-branches
  (let [root (tmp-dir)]
    (is (= [] (status/agent-dirs root nil)))
    (is (= [(fs/path root ".squad/agents/agent-001")]
           (status/agent-dirs root "agent-001")))
    (fs/create-dirs (fs/path root ".squad/agents/b"))
    (fs/create-dirs (fs/path root ".squad/agents/a"))
    (is (= ["a" "b"] (map fs/file-name (status/agent-dirs root nil)))))
  (let [root (tmp-dir)
        dir (fs/path root ".squad/agents/agent-001")]
    (with-redefs [status/exit! (fn [status & lines]
                                 (throw (exit-exception status lines)))]
      (is (= 1 (exit-status #(status/print-agent root dir)))))
    (write-file (fs/path dir "metadata") "task_id: t\nsession: session\n")
    (write-file (fs/path dir "status") "state: running\n")
    (with-redefs [status/print-pane-tail! (fn [_ _] (println "PANE_LIVE: stub"))
                  status/print-liveness! (fn [_])]
      (let [out (with-out-str (status/print-agent root dir))]
        (is (str/includes? out "AGENT: agent-001"))
        (is (str/includes? out "PANE_LIVE: stub"))))))

(deftest squad-next-selects-approval-and-assignment-candidates
  (let [root (tmp-dir)
        story-dir (fs/path root ".squad/stories/s1")]
    (write-file (fs/path root ".swarmforge/roles.tsv") "squad-leader\tmaster\t/tmp\ts\tw\tcodex\ttask\n")
    (write-file (fs/path root "swarmforge/squad.conf")
                (str "approval_required implementation-plan true\n"
                     "approval_required gherkin true\n"
                     "approval_required qa_procedure true\n"
                     "approval_required implementation true\n"))
    (write-file (fs/path story-dir "packet")
                (str "theme_id: theme-a\n"
                     "story_id: s1\n"))
    (let [candidate (first (next/story-candidates root []))]
      (is (= "create_assignment" (:next-action candidate)))
      (is (= "analyst" (:template candidate))))
    (write-file (fs/path story-dir "packet")
                (str "theme_id: theme-a\n"
                     "story_id: s1\n"
                     "implementation_plan_path: .squad/stories/s1/plan.md\n"
                     "implementation_plan_sha: abcdef1234\n"
                     "implementation_plan_approval: approved\n"))
    (let [candidates (next/story-candidates root [])]
      (is (= #{"gherkin-writer" "qa-procedure-writer"}
             (set (map :template candidates)))))
    (write-file (fs/path story-dir "packet")
                (str "theme_id: theme-a\n"
                     "story_id: s1\n"
                     "implementation_plan_path: .squad/stories/s1/plan.md\n"
                     "implementation_plan_approval: approved\n"
                     "gherkin_path: features/s1.feature\n"
                     "gherkin_sha: gsha\n"
                     "qa_procedure_path: qa/s1.md\n"
                     "qa_procedure_sha: qsha\n"))
    (let [candidates (next/story-candidates root [])]
      (is (= #{"create_approval_request"} (set (map :next-action candidates))))
      (is (= #{"gherkin" "qa-procedure"} (set (map :gate candidates)))))))

(deftest squad-next-selects-downstream-and-batch-candidates
  (let [root (tmp-dir)
        story-dir (fs/path root ".squad/stories/s1")]
    (write-file (fs/path root "swarmforge/squad.conf")
                (str "approval_required story false\n"
                     "approval_required gherkin false\n"
                     "approval_required qa_procedure false\n"
                     "approval_required implementation-plan false\n"
                     "approval_required implementation false\n"
                     "approval_required implementation_order false\n"
                     "approval_required dependency_checker false\n"
                     "approval_required code_review false\n"
                     "approval_required hardening false\n"
                     "approval_required qa false\n"
                     "approval_required architecture false\n"
                     "approval_required final false\n"))
    (write-file (fs/path root ".squad/themes/theme-a/implementation-order.md") "")
    (write-file (fs/path root "dependency-checker.edn")
                "{:allowed-dependencies {:domain [] :ui [:domain]}\n :fail-on-cycles true}\n")
    (write-file (fs/path story-dir "packet")
                (str "theme_id: theme-a\n"
                     "story_id: s1\n"
                     "implementation_plan_path: .squad/stories/s1/plan.md\n"
                     "implementation_plan_approval: approved\n"
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
                     "implementation_plan_path: .squad/stories/s1/plan.md\n"
                     "implementation_plan_approval: approved\n"
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
                     "implementation_plan_path: .squad/stories/s1/plan.md\n"
                     "implementation_plan_approval: approved\n"
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
                     "implementation_plan_path: .squad/stories/s1/plan.md\n"
                     "implementation_plan_approval: approved\n"
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
                     "implementation_plan_path: .squad/stories/s1/plan.md\n"
                     "implementation_plan_approval: approved\n"
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

(deftest squad-next-excludes-visible-handoff-sent-agents-from-capacity
  (let [root (tmp-dir)
        rows [["implementer-001" "story-1-implementation" "ignored"]]]
    (write-file (fs/path root "swarmforge/squad.conf") "max_transient_agents 1\n")
    (write-file (fs/path root ".squad/agents/implementer-001/metadata")
                "template: implementer\ntask_id: story-1-implementation\n")
    (write-file (fs/path root ".squad/agents/implementer-001/status")
                "state: handoff_sent\nupdated_at: 2026-08-04T00:00:00Z\n")
    (write-file (fs/path root ".swarmforge/handoffs/inbox/new/50_from_implementer-001_to_squad-leader.handoff")
                "from: implementer-001\nto: squad-leader\ntype: git_handoff\n")
    (let [agents (next/agent-records root rows)]
      (is (next/active-assignment? agents "story-1-implementation"))
      (is (next/spawn-capacity? root agents "implementer")))))

(deftest config-and-forge-helper-branches
  (let [root (tmp-dir)]
    (is (= 10 (config/squad-max-transient-agents root)))
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
    (is (= "hardener" (next/next-batch-id root [] "theme" "hardener" "hardener")))
    (is (= "qa" (next/next-batch-id root [] "theme" "qa" "qa")))
    (is (true? (next/requirement-satisfied? root {"story_approval" "approved"} "approval:story")))
    (is (false? (next/requirement-satisfied? root nil "bogus:story")))))

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
    (with-redefs [config/squad-transient-agent-config (constantly "leader")
                  config/squad-transient-agent-for-template (fn [_ template]
                                                              (if (= "implementer" template)
                                                                "codex"
                                                                "leader"))]
      (is (= "claude" (spawn/configured-transient-agent (tmp-dir) rows)))
      (is (= "codex" (spawn/configured-transient-agent (tmp-dir) rows "implementer")))
      (is (= "claude" (spawn/configured-transient-agent (tmp-dir) rows "analyst"))))
    (with-redefs [config/squad-transient-agent-config (constantly "codex")
                  config/squad-transient-agent-for-template (constantly "codex")]
      (is (= "codex" (spawn/configured-transient-agent (tmp-dir) rows))))
    (with-redefs [config/squad-transient-agent-config (constantly "")
                  config/squad-transient-agent-for-template (constantly "")]
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
    (is (not (:ok (squadd-web/create-sl-request-action! root ""))))
    (is (str/includes? (:error (squadd-web/create-sl-request-action! root "   ")) "empty"))
    (with-redefs [squadd-web/wake-sl-for-request! (constantly true)
                  squadd-web/log! (fn [& _])]
      (is (:ok (squadd-web/create-sl-request-action! root "hello")))
      (is (seq (.list (java.io.File. (str root "/.swarmforge/dashboard/requests/pending"))))))
    (with-redefs [squadd-web/wake-sl-for-request! (constantly false)
                  squadd-web/log! (fn [& _])]
      (let [result (squadd-web/create-sl-request-action! root "still durable")]
        (is (:ok result))
        (is (:wake_failed result))))))

(deftest launcher-config-and-terminal-helper-branches
  (let [root (tmp-dir)
        script-dir (fs/path root "scripts")
        role-dir (fs/path root "swarmforge" "roles")
        ctx {:working-dir root
             :script-dir script-dir
             :roles-dir role-dir
             :config-file (fs/path root "swarmforge" "swarm.conf")
             :constitution-file (fs/path root "swarmforge" "constitution.prompt")
             :state-dir (fs/path root ".swarmforge")
             :prompts-dir (fs/path root ".swarmforge" "prompts")
             :worktrees-dir (fs/path root ".worktrees")
             :terminal-backend "none"
             :tmux-socket "sock"
             :window-ids-file (fs/path root ".swarmforge" "window-ids")
             :window-state-file (fs/path root ".swarmforge" "window-state")
             :roles [{:role "squad-leader"
                      :agent "codex"
                      :session "swarmforge-squad-leader"
                      :display-name "Squad Leader"
                      :worktree-name "master"
                      :worktree-path root
                      :receive-mode "task"}
                     {:role "implementer"
                      :agent "codex"
                      :session "swarmforge-implementer"
                      :display-name "Implementer"
                      :worktree-name "implementer"
                      :worktree-path (fs/path root ".worktrees" "implementer")
                      :receive-mode "task"}]}]
    (write-file (:constitution-file ctx) "constitution\n")
    (write-file (fs/path role-dir "squad-leader.prompt") "leader\n")
    (write-file (fs/path role-dir "implementer.prompt") "implementer\n")
    (write-file (:config-file ctx)
                (str "# comment\n"
                     "window squad-leader codex master\n"
                     "\n"
                     "window implementer codex implementer task --dangerously-bypass-approvals-and-sandbox\n"))
    (is (= ["task" ["--flag"]] (forge/receive-mode-and-extra ["--flag"])))
    (is (= ["batch" ["--flag"]] (forge/receive-mode-and-extra ["batch" "--flag"])))
    (is (= root (forge/worktree-path ctx "master")))
    (is (= (fs/path root ".worktrees" "implementer") (forge/worktree-path ctx "implementer")))
    (is (= 2 (count (:roles (forge/parse-config ctx)))))
    (with-redefs [process/sh (fn [& args]
                               (let [argv (if (map? (first args)) (rest args) args)]
                                 (cond
                                   (= ["tmux" "-S" "sock" "show-options" "-gqv" "base-index"] (vec argv))
                                   {:exit 0 :out "1\n"}

                                   (= ["tmux" "-S" "sock" "show-options" "-gwqv" "pane-base-index"] (vec argv))
                                   {:exit 0 :out "2\n"}

                                   :else {:exit 0 :out ""})))
                  forge/sh-ok? (constantly true)]
      (is (= 1 (forge/tmux-option "sock" "base-index" :session 0)))
      (is (= 2 (forge/tmux-option "sock" "pane-base-index" :window 0)))
      (is (= 0 (forge/tmux-option "sock" "bad" :session 0))))
    (with-redefs [forge/terminal-call-out (fn [_ command & args]
                                            (case command
                                              "terminal_open_session" (str "win-" (first args))
                                              "terminal_backend_label" "TestTerm"))
                  forge/tracks-windows? (constantly true)]
      (fs/create-dirs (fs/parent (:window-ids-file ctx)))
      (forge/open-role-surfaces! ctx)
      (is (str/includes? (slurp (str (:window-state-file ctx))) "swarmforge-squad-leader")))
    ;; window-invisible: session still configured, but no Terminal surface opened
    (write-file (fs/path role-dir "troubleshooter.prompt") "troubleshoot\n")
    (write-file (:config-file ctx)
                (str "window squad-leader codex master task\n"
                     "window-invisible troubleshooter codex master task\n"))
    (let [cfg (forge/parse-config ctx)
          by-role (into {} (map (juxt :role identity) (:roles cfg)))
          opened (atom [])]
      (is (= 2 (count (:roles cfg))))
      (is (true? (get-in by-role ["squad-leader" :visible?])))
      (is (false? (get-in by-role ["troubleshooter" :visible?])))
      (is (= 1 (count (forge/visible-roles cfg))))
      (with-redefs [forge/terminal-call-out (fn [_ command & args]
                                              (when (= command "terminal_open_session")
                                                (swap! opened conj (first args)))
                                              (case command
                                                "terminal_open_session" (str "win-" (first args))
                                                "terminal_backend_label" "TestTerm"
                                                ""))
                    forge/tracks-windows? (constantly true)]
        (spit (str (:window-state-file ctx)) "")
        (forge/open-role-surfaces! cfg)
        (is (= ["swarmforge-squad-leader"] @opened)
            "invisible troubleshooter must not open a Terminal surface")
        (is (not (str/includes? (slurp (str (:window-state-file ctx))) "troubleshooter")))))
    (fs/create-dirs (:prompts-dir ctx))
    (is (str/includes? (forge/launch-command ctx 1 (second (:roles ctx)))
                       "codex -C"))))

(deftest stop-squadd-helper-branches
  (let [root (tmp-dir)
        killed (atom [])]
    (is (true? (stop-squadd/numeric-pid? "123")))
    (is (false? (stop-squadd/numeric-pid? "abc")))
    (with-redefs [stop-squadd/current-pid (constantly "99")
                  stop-squadd/process-lines (constantly [" 10 bb /x/squadd.clj /tmp/nope"
                                                         (str " 11 bb /x/squadd.clj " (fs/absolutize root))
                                                         (str " 99 bb /x/squadd.clj " (fs/absolutize root))
                                                         "bogus"])]
      (is (= ["11"] (stop-squadd/matching-orphan-pids root))))
    (with-redefs [stop-squadd/process-alive? (fn [pid] (= "7" pid))
                  process/sh (fn [& args]
                               (swap! killed conj (vec (if (map? (first args)) (rest args) args)))
                               {:exit 0 :out ""})
                  stop-squadd/wait-for-exit! (fn [& _])]
      (stop-squadd/terminate-pid! "bad" 0)
      (stop-squadd/terminate-pid! "7" 0)
      (is (some #(= ["kill" "-TERM" "7"] %) @killed)))
    (let [pid-file (fs/path root ".swarmforge" "daemon" "squadd.pid")]
      (write-file pid-file "42\n")
      (with-redefs [stop-squadd/terminate-pid! (fn [pid _] (swap! killed conj ["term" pid]))
                    stop-squadd/matching-orphan-pids (constantly ["43"])
                    stop-squadd/cleanup-transient-git! (fn [_])]
        (stop-squadd/stop! root :timeout-ms 1)
        (is (not (fs/exists? pid-file)))
        (is (some #(= ["term" "43"] %) @killed))))
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (str "squad-leader\tmaster\t" root "\tsl\tSL\tcodex\ttask\n"
                     "agent-001\tagent-001\t" root "/.worktrees/agent-001\ts1\tA1\tcodex\ttask\n"
                     "agent-002\tagent-002\t" root "/.worktrees/agent-002\ts2\tA2\tcodex\ttask\n"))
    (write-file (fs/path root ".squad/agents/agent-001/metadata") "task_id: assignment-1\n")
    (write-file (fs/path root ".squad/agents/agent-002/metadata") "task_id: assignment-2\n")
    (write-file (fs/path root ".squad/assignments/assignment-2/status") "state: merge_blocked\n")
    (with-redefs [stop-squadd/remove-worktree! (fn [_ role worktree] (swap! killed conj ["worktree" role worktree]))
                  stop-squadd/delete-branch! (fn [_ role] (swap! killed conj ["branch" role]))]
      (stop-squadd/cleanup-transient-git! root)
      (is (some #(= ["branch" "agent-001"] %) @killed))
      (is (some #(= ["branch" "agent-002"] %) @killed)))))

(deftest window-watchdog-helper-branches
  (let [root (tmp-dir)
        state-file (fs/path root "window-state")
        ids-file (fs/path root "window-ids")]
    (write-file state-file "1\twin-1\tsession-1\tTitle 1\n2\twin-2\tsession-2\tTitle 2\n")
    (is (= ["session-1" "session-2"] (map :session (window-watchdog/rows state-file))))
    (window-watchdog/rewrite-window-id! state-file ids-file "2" "win-2b")
    (is (str/includes? (slurp (str state-file)) "win-2b"))
    (with-redefs [window-watchdog/tmux-session? (constantly false)]
      (is (= {} (window-watchdog/maybe-reopen-window! root state-file ids-file root "sock" "none" "1" "cleanup" {} {:index "2" :session "missing"}))))
    (with-redefs [window-watchdog/tmux-session? (constantly true)
                  window-watchdog/row-window-present? (constantly true)]
      (is (= {"2" 0}
             (window-watchdog/maybe-reopen-window! root state-file ids-file root "sock" "none" "1" "cleanup" {} {:index "2" :window-id "win" :session "live"}))))
    (with-redefs [window-watchdog/tmux-session? (constantly true)
                  window-watchdog/row-window-present? (constantly false)
                  window-watchdog/terminal-out (constantly "new-win")]
      (is (= {"2" 1}
             (window-watchdog/maybe-reopen-window! root state-file ids-file root "sock" "none" "1" "cleanup" {} {:index "2" :window-id "win" :session "live" :title "Title"})))
      (is (= {"2" 0}
             (window-watchdog/maybe-reopen-window! root state-file ids-file root "sock" "none" "1" "cleanup" {"2" 2} {:index "2" :window-id "win" :session "live" :title "Title"}))))
    (with-redefs [window-watchdog/cleanup-window-present? (constantly true)
                  window-watchdog/tmux-session? (constantly true)
                  window-watchdog/row-window-present? (constantly true)]
      (is (= {"1" 0 "2" 0}
             (window-watchdog/next-missing-counts root state-file ids-file root "sock" "none" "1" {}))))
    (with-redefs [window-watchdog/cleanup-window-present? (constantly false)
                  window-watchdog/tmux-session? (constantly true)
                  window-watchdog/kill-all-sessions! (fn [& _] :killed)]
      (is (= {"1" 1}
             (window-watchdog/next-missing-counts root state-file ids-file root "sock" "none" "1" {})))
      (is (= :killed
             (window-watchdog/next-missing-counts root state-file ids-file root "sock" "none" "1" {"1" 2}))))))

(deftest squadd-web-routing-and-request-helper-branches
  (let [root (tmp-dir)]
    (is (true? (squadd-web/route-matches? {:method "GET" :path "/api/state"} "GET" "/api/state")))
    (is (boolean (squadd-web/route-matches? {:method "POST" :pattern #"/items/[0-9]+"} "POST" "/items/42")))
    (is (false? (squadd-web/route-matches? {:method "GET" :path "/api/state"} "POST" "/api/state")))
    (is (= 404 (:status (squadd-web/route-web-request root "GET" "/missing" ""))))
    (is (= 405 (:status (squadd-web/route-web-request root "DELETE" "/api/state" ""))))
    (is (= 400 (:status (squadd-web/request-response root nil ""))))
    (is (= "/api/state" (squadd-web/target-path "/api/state?cache=false")))
    (is (= {:method "GET" :target "/api/state"} (squadd-web/parse-request-line "GET /api/state HTTP/1.1")))
    (let [reader (java.io.BufferedReader.
                  (java.io.StringReader. "Content-Length: 5\r\nBadHeader\r\nX-Test: value\r\n\r\nhello"))]
      (is (= {"content-length" "5" "x-test" "value"} (squadd-web/read-headers reader)))
      (is (= "hello" (squadd-web/read-body reader 5))))
    (is (= 0 (squadd-web/content-length {"content-length" "bad"})))
    (is (= "" (squadd-web/read-body (java.io.BufferedReader. (java.io.StringReader. "")) 0)))
    (with-redefs [squadd-web/web-state (fn [_] {"ok" true})]
      (is (= 200 (:status (squadd-web/state-response root)))))
    (with-redefs [squadd-web/approval-web-action! (fn [_ approval action]
                                                {:ok true :approval approval :action action})]
      (is (= 200 (:status (squadd-web/approval-response root "/api/approvals/a%201/approve")))))
    (with-redefs [squadd-web/approval-web-action! (constantly {:ok false :status 409 :error "no\n"})]
      (is (= 409 (:status (squadd-web/approval-response root "/api/approvals/a%201/reject")))))
    (with-redefs [squadd-web/create-sl-request-action!
                  (constantly {:ok true :request {"id" "dashboard-test-001"}})]
      (is (= 200 (:status (squadd-web/sl-message-response root "hello"))))
      (is (= 200 (:status (squadd-web/sl-request-create-response root "{\"kind\":\"question\",\"body\":\"why?\"}")))))
    (with-redefs [squadd-web/create-sl-request-action!
                  (constantly {:ok false :status 409 :error "empty\n"})]
      (is (= 409 (:status (squadd-web/sl-message-response root "")))))
    (with-redefs [squadd-web/cancel-sl-request-action! (constantly {:ok true})]
      (is (= 200 (:status (squadd-web/sl-request-cancel-response root "/api/sl-requests/dashboard-test-001/cancel")))))
    (with-redefs [squadd-web/cancel-sl-request-action!
                  (constantly {:ok false :status 409 :error "missing\n"})]
      (is (= 409 (:status (squadd-web/sl-request-cancel-response root "/api/sl-requests/missing/cancel")))))))

(deftest squadd-spawn-request-and-args-helper-branches
  (let [root (tmp-dir)
        request (fs/path root ".squad" "spawn-requests" "new" "story.request")
        logs (atom [])]
    (write-file request "template: implementer\ntask_id: story-1\nassignment: assignment.md\n")
    (with-redefs [squadd/spawn-capacity-blocker (constantly "capacity-full")
                  squadd/log! (fn [_ & parts] (swap! logs conj parts))]
      (squadd/process-spawn-request! root request)
      (is (fs/exists? request))
      (is (some #(= ["spawn-request-deferred" (str request) "capacity-full"] %) @logs)))
    (reset! logs [])
    (with-redefs [squadd/spawn-capacity-blocker (constantly nil)
                  squadd/run-spawn-request! (constantly {:exit 0 :out "ok\n" :err ""})
                  squadd/log! (fn [_ & parts] (swap! logs conj parts))]
      (squadd/process-spawn-request! root request)
      (is (fs/exists? (fs/path root ".squad" "spawn-requests" "completed" "story.request")))
      (is (some #(= ["spawn-request-completed"
                     (str (fs/path root ".squad" "spawn-requests" "in_process" "story.request"))] %)
                @logs)))
    (let [invalid (fs/path root ".squad" "spawn-requests" "new" "invalid.request")]
      (write-file invalid "template: implementer\n")
      (with-redefs [squadd/spawn-capacity-blocker (constantly nil)
                    squadd/log! (fn [_ & parts] (swap! logs conj parts))]
        (squadd/process-spawn-request! root invalid)
        (is (fs/exists? (fs/path root ".squad" "spawn-requests" "failed" "invalid.request")))
        (is (fs/exists? (fs/path root ".squad" "spawn-requests" "in_process" "invalid.request.error")))))
    (with-redefs [squadd/project-root (constantly root)]
      (is (= {:once? true :no-notify? true :root "project"}
             (squadd/parse-args ["--once" "--no-notify" "project"])))
      (is (= root (:root (squadd/parse-args [])))))
    (with-redefs [squadd/exit! (fn [status & lines] (throw (exit-exception status lines)))]
      (is (= 1 (exit-status #(squadd/apply-arg! {:root "one"} "two"))))
      (is (= 1 (exit-status #(squadd/apply-arg! {} "--bogus")))))))

(deftest spawn-capacity-and-availability-helper-branches
  (let [root (tmp-dir)
        rows [["implementer-001"]]]
    (write-file (fs/path root "swarmforge" "squad.conf")
                (str "max_transient_agents 1\n"
                     "max_active_template implementer 1\n"
                     "max_active_group builders 1 implementer cleaner\n"))
    (write-file (fs/path root ".squad" "agents" "implementer-001" "status") "state: running\n")
    (write-file (fs/path root ".squad" "agents" "implementer-001" "metadata") "template: implementer\n")
    (write-file (fs/path root ".swarmforge" "roles.tsv")
                (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                     "implementer-001\timplementer-001\t" root "\tswarmforge-implementer-001\tImplementer\tcodex\ttask\n"))
    (with-redefs [spawn/tmux-session-exists? (constantly true)
                  squadd/tmux-session-exists? (constantly true)]
      (is (= [3 "SQUAD_SPAWN_CAPACITY_FULL" "ACTIVE_TRANSIENTS: 1" "MAX_TRANSIENTS: 1"]
             (spawn/global-capacity-error root rows)))
      (is (= [3 "SQUAD_SPAWN_TEMPLATE_CAPACITY_FULL" "TEMPLATE: implementer" "ACTIVE_TEMPLATE_TRANSIENTS: 1" "MAX_TEMPLATE_TRANSIENTS: 1"]
             (spawn/template-limit-error root rows "implementer")))
      (is (= "capacity-full" (squadd/spawn-capacity-blocker root "implementer")))
      (is (= [3 "SQUAD_SPAWN_CAPACITY_FULL" "ACTIVE_TRANSIENTS: 1" "MAX_TRANSIENTS: 1"]
             (spawn/capacity-error root rows "merger")))
      (is (= "capacity-full" (squadd/spawn-capacity-blocker root "merger"))))
    (with-redefs [spawn/exit! (fn [status & lines] (throw (exit-exception status lines)))]
      (is (= 2 (exit-status #(spawn/ensure-agent-available! [["agent-001"]] "agent-001" (fs/path root "missing") (fs/path root "missing-agent")))))
      (fs/create-dirs (fs/path root "exists-worktree"))
      (is (= 2 (exit-status #(spawn/ensure-agent-available! [] "agent-002" (fs/path root "exists-worktree") (fs/path root "missing-agent")))))
      (fs/create-dirs (fs/path root "exists-agent"))
      (is (= 2
             (exit-status
              #(spawn/ensure-agent-available! [] "agent-003"
                                               (fs/path root "missing-worktree")
                                               (fs/path root "exists-agent"))))))))

(deftest config-report-status-and-approval-helper-branches
  (let [root (tmp-dir)
        theme-dir (fs/path root ".squad" "themes" "theme-a")]
    (write-file (fs/path root ".swarmforge" "roles.tsv") "squad-leader\tmaster\t/root\tsession\tSquad Leader\tcodex\ttask\n")
    (with-redefs [shell/sh (fn [& args]
                             (case (last args)
                               "--git-common-dir" {:exit 0 :out (str (fs/path root ".git") "\n")}
                               "--show-toplevel" {:exit 0 :out (str root "\n")}
                               {:exit 1 :out "" :err ""}))]
      (is (= root (config/git-common-project-root)))
      (is (= root (config/git-project-root))))
    (write-file (fs/path root "swarmforge" "squad.conf")
                (str "max_active_group reviewers 2 code-reviewer architect\n"
                     "max_active_group broken no code-reviewer\n"))
    (is (= [{:group "reviewers" :limit 2 :templates #{"code-reviewer" "architect"}}]
           (config/squad-template-group-limits root "code-reviewer")))
    (write-file (fs/path theme-dir "status") "state: approved\ndetail: ready\nupdated_at: now\n")
    (write-file (fs/path theme-dir "stories" "story-1.md") "story\n")
    (write-file (fs/path theme-dir "acceptance" "story-1.md") "gherkin\n")
    (is (str/includes? (with-out-str (report/print-theme-section! theme-dir (fs/path theme-dir "status")))
                       "- Stories: story-1"))
    (is (str/includes? (with-out-str (report/print-approvals-section! theme-dir)) "- none"))
    (write-file (fs/path theme-dir "approvals.tsv") "t1\tstory\tapproved\n")
    (is (str/includes? (with-out-str (report/print-approvals-section! theme-dir)) "story: approved"))
    (with-redefs [status/sh-continue (fn [& args]
                                       (cond
                                         (= ["tmux" "-S" "sock" "has-session" "-t" "session"] (vec args))
                                         {:exit 1 :out ""}

                                         (= ["tmux" "-S" "sock" "list-sessions" "-F" "#S"] (vec args))
                                         {:exit 0 :out "other\nsession\n"}

                                         (= ["tmux" "-S" "sock" "list-panes" "-t" "session" "-F" "#{pane_dead}"] (vec args))
                                         {:exit 0 :out "1\n"}

                                         (= ["tmux" "-S" "sock" "capture-pane" "-p" "-t" "session" "-S" "-20"] (vec args))
                                         {:exit 0 :out "tail\n"}

                                         :else {:exit 1 :out ""}))]
      (is (true? (status/tmux-session-exists? "sock" "session")))
      (is (boolean (status/pane-dead? "sock" "session")))
      (is (= "tail\n" (status/capture-pane-tail "sock" "session"))))
    (let [approval-file (fs/path root ".squad" "approvals" "pending" "a1.approval")]
      (write-file approval-file "target_kind: story\ntarget_id: s1\ngate: story\nstate: pending\n")
      (is (= approval-file (approval/equivalent-approval-file root "story" "s1" "story")))
      (is (nil? (approval/equivalent-approval-file root "story" "s2" "story"))))))


(deftest handoff-header-update-helper-branches
  (let [root (tmp-dir)
        file (fs/path root "task.handoff")
        batch-dir (fs/path root "batch_1")]
    (is (= :insert (handoff-lib/header-line-action false "x: " "")))
    (is (= :replace (handoff-lib/header-line-action false "x: " "x: old")))
    (is (= :copy (handoff-lib/header-line-action true "x: " "x: old")))
    (is (= ["from: a" "x: 1" "" "body"]
           (handoff-lib/update-header-lines ["from: a" "" "body"] "x" "1")))
    (is (= ["x: 2" "" "body"]
           (handoff-lib/update-header-lines ["x: 1" "" "body"] "x" "2")))
    (is (= ["body" "x: 3"]
           (handoff-lib/update-header-lines ["body"] "x" "3")))
    (write-file file "from: analyst\ntype: story\ntask: story-1\n\npayload\n")
    (handoff-lib/set-header! file "priority" "10")
    (is (= "10" (handoff-lib/header-field file "priority")))
    (is (str/includes? (with-out-str (handoff-lib/print-task file)) "TASK_NAME: story-1"))
    (fs/create-dirs batch-dir)
    (fs/copy file (fs/path batch-dir "01.handoff"))
    (is (str/includes? (with-out-str (handoff-lib/print-batch batch-dir)) "BATCH_ITEM: 1"))
    (is (= 2 (:exit (ex-data (try
                               (handoff-lib/print-batch (fs/path root "empty-batch"))
                               (catch clojure.lang.ExceptionInfo ex ex))))))))

(deftest ready-done-header-update-helper-branches
  (let [root (tmp-dir)
        ready-file (fs/path root "ready.handoff")
        done-file (fs/path root "done.handoff")
        completed-dir (fs/path root "completed")]
    (is (= :insert (ready-batch/header-line-action false "x: " "")))
    (is (= :replace (ready-batch/header-line-action false "x: " "x: old")))
    (is (= :copy (ready-batch/header-line-action true "x: " "x: old")))
    (is (= ["x: 1" "" "body"] (ready-batch/header-lines-with ["" "body"] "x" "1")))
    (is (= ["x: 2" "" "body"] (ready-batch/header-lines-with ["x: 1" "" "body"] "x" "2")))
    (write-file ready-file "from: a\npriority: 20\n\nbody\n")
    (ready-batch/set-header! ready-file "dequeued_at" "now")
    (is (= "now" (ready-batch/header-field ready-file "dequeued_at")))
    (is (str/includes? (with-out-str (ready-batch/print-task ready-file)) "PRIORITY: 20"))
    (is (= :insert (done-task/header-line-action false "x: " "")))
    (is (= :replace (done-task/header-line-action false "x: " "x: old")))
    (is (= :copy (done-task/header-line-action true "x: " "x: old")))
    (is (= ["x: 1" "" "body"] (done-task/header-lines-with ["" "body"] "x" "1")))
    (write-file done-file "from: a\n\nbody\n")
    (fs/create-dirs completed-dir)
    (let [target (done-task/complete-task! done-file completed-dir)]
      (is (fs/exists? target))
      (is (str/includes? (slurp (str target)) "completed_at:")))))

(deftest assignment-validation-and-record-helper-branches
  (let [root (tmp-dir)
        dir (fs/path root ".squad" "assignments" "a1")
        handoff-file (fs/path root "handoff.handoff")
        review-file (fs/path root ".squad" "reviews" "r1.md")]
    (with-redefs [assign/exit! (fn [status & lines] (throw (exit-exception status lines)))]
      (is (= 2 (exit-status #(assign/validate-id! "Thing" "bad/id"))))
      (is (= 2 (exit-status #(assign/validate-template! "Bad_Template"))))
      (is (= 2 (exit-status #(assign/parse-requirement! "file:thing"))))
      (is (= 2 (exit-status #(assign/review-state! "maybe"))))
      (is (= 1 (exit-status #(assign/ensure-assignment-dir! (fs/path root "missing") "missing"))))
      (is (= 1 (exit-status #(assign/ensure-file! "Missing" (fs/path root "missing"))))))
    (is (= {:kind "approval" :value "story" :text "approval:story"}
           (assign/parse-requirement! "approval:story")))
    (is (= "review_accepted" (assign/review-state! "accepted")))
    (is (= "review_changes_requested" (assign/review-state! "changes-requested")))
    (write-file (fs/path dir "metadata") "theme_id: theme-a\nstory_id: story-1\n")
    (write-file handoff-file "type: git_handoff\nfrom: implementer-001\nto: squad-leader\ntask: a1\ncommit: abcdef1234\nassignment: a1\nagent: implementer-001\ntemplate: implementer\nartifacts: none\n\nbody\n")
    (is (= "abcdef1234" (assign/handoff-commit handoff-file)))
    (is (= {:from "implementer-001"
            :commit "abcdef1234"
            :manifest {"assignment" "a1"
	                       "agent" "implementer-001"
	                       "template" "implementer"
	                       "artifacts" "none"
	                       "review_decision" nil}
            :body "body\n"}
           (assign/validate-result-handoff! "a1" "implementer" handoff-file)))
    (write-file review-file "review\n")
    (let [source (assign/review-source! root review-file)]
      (is (:durable? source))
      (is (= "review\n" (:content source))))
    (assign/write-result-record! dir "a1" "implementer-001" "abcdef1234" "now")
    (is (= "result_received" (assign/read-value (fs/path dir "status") "state")))
    (assign/write-merge-state! root dir "a1" "merge_ready" "clean" "abcdef1234" "now")
    (is (= "merge_ready" (assign/read-value (fs/path dir "merge") "state")))))

(deftest assignment-create-context-helper-branches
  (let [root (tmp-dir)
        instructions (fs/path root "instructions.md")]
    (write-file instructions "Do the work.\n")
    (write-file (fs/path root ".squad/themes/wumpus/theme.md") "Theme\n")
    (write-file (fs/path root ".squad/themes/wumpus/stories/cave.ref") "path: stories/cave.md\n")
    (write-file (fs/path root "stories/cave.md") "Story\n")
    (write-file (fs/path root "swarmforge/role-templates/implementer.prompt") "Implement\n")
    (write-file (fs/path root "swarmforge/role-templates/analyst.prompt") "Analyze\n")
    (write-file (fs/path root "swarmforge/role-templates/hardener.prompt") "Harden\n")
    (write-file (fs/path root ".squad/stories/cave/packet") "theme_id: wumpus\n")
    (with-redefs [assign/project-root (constantly root)
                  assign/timestamp (constantly "now")]
      (is (= "story" (assign/assignment-scope {:template "implementer" :story-id "cave"})))
      (is (= "theme" (assign/assignment-scope {:template "analyst" :story-id "theme"})))
      (is (= "batch" (assign/assignment-scope {:template "hardener" :story-id "batch" :scope "batch"})))
      (is (true? (assign/story-file-required? "story")))
      (is (false? (assign/story-file-required? "theme")))
      (is (false? (assign/story-file-required? "batch")))
      (is (= {:theme-scoped? true :batch-scoped? false}
             (assign/assignment-scope-flags "theme")))
      (is (= {:theme-scoped? false :batch-scoped? true}
             (assign/assignment-scope-flags "batch")))
      (let [story-context (assign/assignment-create-context
                           {:theme-id "wumpus"
                            :story-id "cave"
                            :template "implementer"
                            :assignment-id "impl-001"
                            :instructions-file instructions
                            :requirement {:kind "approval"
                                          :value "implementation"
                                          :text "approval:implementation"}})
            theme-context (assign/assignment-create-context
                           {:theme-id "wumpus"
                            :story-id "theme"
                            :template "analyst"
                            :assignment-id "analysis-001"
                            :instructions-file instructions})
            batch-context (assign/assignment-create-context
                           {:theme-id "wumpus"
                            :story-id "batch"
                            :scope "batch"
                            :template "hardener"
                            :assignment-id "hardener-001"
                            :instructions-file instructions})]
        (is (= "story" (:scope story-context)))
        (is (= (fs/path root "stories/cave.md") (:story-file story-context)))
        (is (= "approval:implementation" (get-in story-context [:requirement :text])))
        (is (= (fs/path root ".squad/stories/cave/packet") (:packet story-context)))
        (is (= "theme" (:scope theme-context)))
        (is (:theme-scoped? theme-context))
        (is (nil? (:story-file theme-context)))
        (is (= "batch" (:scope batch-context)))
        (is (:batch-scoped? batch-context))
        (is (nil? (:story-file batch-context)))))))

(deftest packet-validation-and-map-helper-branches
  (let [root (tmp-dir)
        story-id "story-1"
        packet-file (packet-tool/packet-file root story-id)
        artifact (fs/path root "features" "story.feature")]
    (with-redefs [packet-tool/exit! (fn [status & lines] (throw (exit-exception status lines)))]
      (is (= 2 (exit-status #(packet-tool/validate-id! "Thing" "bad/id"))))
      (is (= 2 (exit-status #(packet-tool/validate-sha! "bad"))))
      (is (= 1 (exit-status #(packet-tool/ensure-packet! root "missing"))))
      (is (= 2 (exit-status #(packet-tool/validate-artifact-kind! "docs"))))
      (is (= 2 (exit-status #(packet-tool/relative-project-file! root (fs/path "/tmp/outside") ["features/"] "bad path")))))
    (write-file packet-file "story_id: story-1\ntheme_id: theme-a\n")
    (write-file artifact "Feature: Story\n")
    (is (= {"story_id" "story-1" "theme_id" "theme-a"} (packet-tool/packet-map root story-id)))
    (is (= "features/story.feature"
           (packet-tool/relative-project-file! root artifact ["features/"] "bad path")))
    (is (= "features/story.feature"
           (packet-tool/relative-artifact-file! root "gherkin" artifact)))
    (is (= {"gherkin_iterations" "a1=accepted"}
           (packet-tool/append-iteration {} "gherkin" "a1" "accepted")))
    (is (= "fallback" (packet-tool/value-or "" "fallback")))
    (is (= "value" (packet-tool/value-or "value" "fallback")))))

(deftest simulator-option-writer-and-output-helper-branches
  (let [root (tmp-dir)
        calls (atom [])]
    (is (= (assoc simulator/default-options :keep? true)
           (simulator/parse-options ["--keep"])))
    (is (= 42 (:seed (simulator/parse-options ["--seed" "42"]))))
    (is (= [2 4] (:stories-range (simulator/parse-options ["--stories" "2..4"]))))
    (is (= 25 (:stall-percent (simulator/parse-options ["--stall-percent" "25"]))))
    (is (= "active-then-dark" (:stall-mode (simulator/parse-options ["--stall-mode" "active-then-dark"]))))
    (with-redefs [simulator/exit! (fn [status & lines] (throw (exit-exception status lines)))]
      (is (= 2 (exit-status #(simulator/parse-int-range! "Range" "4..2"))))
      (is (= 2 (exit-status #(simulator/stall-percent! "101"))))
      (is (= 2 (exit-status #(simulator/stall-mode! "unknown"))))
      (is (= 1 (exit-status #(simulator/parse-options ["--unknown" "x"])))))
    (with-redefs [simulator/git-commit! (fn [_ message]
                                          (swap! calls conj [:commit message])
                                          "abcdef1234")
                  simulator/run-script! (fn [_ script & args]
                                          (swap! calls conj (into [:script script] args))
                                          {:exit 0 :out ""})]
      (simulator/process-writer! root "a1" "gherkin-writer-001" "gherkin-writer" "story-1")
      (simulator/process-writer! root "a2" "qa-procedure-writer-001" "qa-procedure-writer" "story-1")
      (is (fs/exists? (fs/path root "features" "story-1.feature")))
      (is (fs/exists? (fs/path root "qa" "story-1.md")))
      (is (some #(= [:script "squad_packet.sh" "attach" "story-1" "gherkin" "a1" "gherkin-writer-001" "abcdef1234" "features/story-1.feature"] %)
                @calls))
      (is (some #(= [:script "squad_packet.sh" "attach" "story-1" "qa-procedure" "a2" "qa-procedure-writer-001" "abcdef1234" "qa/story-1.md"] %)
                @calls)))
    (let [pending (atom nil)]
      (simulator/record-wait! pending 5 "NEXT_ACTION: wait\n")
      (simulator/record-wait! pending 6 "NEXT_ACTION: wait\n")
      (let [printed (with-out-str (simulator/flush-wait! pending))]
        (is (str/includes? printed "TICK 005..006"))
        (is (str/includes? printed "WAIT_TICKS: 2"))
        (is (nil? @pending))))
    (write-file (fs/path root ".squad" "stories" "b" "packet") "state: final_approved\n")
    (is (= "a=unknown b=final_approved"
           (simulator/story-summary root ["a" "b"])))
    (is (= ["a"] (vec (simulator/unfinished-stories root ["a" "b"]))))
    (is (false? (simulator/active-agents? root)))
    (write-file (fs/path root ".swarmforge" "roles.tsv") "squad-leader\tmaster\t/root\tsession\tSquad Leader\tcodex\ttask\nagent-001\ta\t/root\ts\tA\tcodex\ttask\n")
    (is (true? (simulator/active-agents? root)))))

(deftest handoffd-delivery-and-notify-helper-branches
  (let [root (tmp-dir)
        sender-root (fs/path root "sender")
        recipient-root (fs/path root "recipient")
        source (fs/path sender-root ".swarmforge" "handoffs" "outbox" "50_test.handoff")
        roles {"sender" {:worktree-path sender-root :session "sender-session"}
               "recipient" {:worktree-path recipient-root :session "recipient-session"}}
        calls (atom [])]
    (write-file source "from: sender\nto: recipient\npriority: 50\ntype: note\n\nhello\n")
    (with-redefs [handoffd/now (constantly "now")
                  handoffd/log! (fn [& parts] (swap! calls conj parts))
                  handoffd/notify! (fn [socket session] (swap! calls conj [:notify socket session]))]
      (handoffd/deliver! roles "sock" "sender" source)
      (is (fs/exists? (fs/path recipient-root ".swarmforge" "handoffs" "inbox" "new" "50_test.handoff")))
      (is (fs/exists? (fs/path sender-root ".swarmforge" "handoffs" "sent" "50_test.handoff")))
      (is (some #(= [:notify "sock" "recipient-session"] %) @calls)))
    (let [bad (fs/path sender-root ".swarmforge" "handoffs" "outbox" "bad.handoff")]
      (write-file bad "from: sender\npriority: 50\n\nmissing recipient\n")
      (with-redefs [handoffd/now (constantly "now")
                    handoffd/log! (fn [& parts] (swap! calls conj parts))]
        (handoffd/deliver! roles "sock" "sender" bad)
        (is (fs/exists? (fs/path sender-root ".swarmforge" "handoffs" "failed" "bad.handoff")))
        (is (fs/exists? (fs/path sender-root ".swarmforge" "handoffs" "outbox" "bad.handoff.error")))))
    (let [collision-source (fs/path root "collision.handoff")
          collision-dir (fs/path root "collisions")]
      (write-file collision-source "one")
      (write-file (fs/path collision-dir "collision.handoff") "existing")
      (with-redefs [handoffd/now (constantly "now")]
        (handoffd/move-with-collision collision-source collision-dir)
        (is (fs/exists? (fs/path collision-dir "now_collision.handoff")))))
    (with-redefs [shell/sh (fn [& _] {:exit 0 :out ""})]
      (is (nil? (handoffd/notify! "sock" "session"))))
    (with-redefs [shell/sh (fn [& args]
                             (if (= "C-m" (last args))
                               {:exit 1 :err "bad return"}
                               {:exit 0 :out ""}))]
      (is (thrown? clojure.lang.ExceptionInfo (handoffd/notify! "sock" "session"))))
    (with-redefs [handoffd/should-stop? (constantly true)]
      (is (nil? (handoffd/sleep-poll! 100))))))

(deftest daemon-web-validation-and-wait-helper-branches
  (let [root (tmp-dir)
        old-os (System/getProperty "os.name")]
    (try
      (System/setProperty "os.name" "Mac OS X")
      (is (= ["open"] (squadd-web/web-open-command)))
      (System/setProperty "os.name" "Linux")
      (is (= ["xdg-open"] (squadd-web/web-open-command)))
      (finally
        (System/setProperty "os.name" old-os)))
    (with-redefs [squadd/should-stop? (constantly true)]
      (is (nil? (squadd/sleep-poll! root 100))))
    (with-redefs [stop-handoff/process-alive? (constantly false)]
      (is (nil? (stop-handoff/wait-for-exit! "123" 100))))
    (with-redefs [stop-squadd/process-alive? (constantly false)]
      (is (nil? (stop-squadd/wait-for-exit! "123" 100))))
    (with-redefs [approval/exit! (fn [status & lines] (throw (exit-exception status lines)))
                  batch/exit! (fn [status & lines] (throw (exit-exception status lines)))
                  report/exit! (fn [status & lines] (throw (exit-exception status lines)))]
      (is (= 2 (exit-status #(approval/validate-id! "Approval id" "bad/id"))))
      (is (= 2 (exit-status #(approval/validate-gate! "bogus"))))
      (is (= 2 (exit-status #(batch/validate-id! "Batch id" "bad/id"))))
      (is (= 2 (exit-status #(batch/validate-kind! "bogus"))))
      (is (= 2 (exit-status #(batch/validate-sha! "bad"))))
      (is (= 2 (exit-status #(report/validate-id! "Theme id" "bad/id")))))))

(deftest p1-file-map-survives-missing-and-vanished-files
  ;; Missing or race-deleted agent files must not crash mechanical apply
  (let [missing (fs/path "/tmp/swarmforge-no-such-file-map-test")
        dir (fs/create-temp-dir {:prefix "swarmforge-file-map."})
        path (fs/path dir "heartbeat")]
    (try
      (is (= {} (next/file-map missing)))
      (fs/create-dirs dir)
      (spit (str path) "state: running\n")
      (is (= "running" (get (next/file-map path) "state")))
      (fs/delete path)
      (is (= {} (next/file-map path))
          "vanished file must not throw")
      (finally
        (fs/delete-tree dir)))))

(deftest start-story-helpers-cover-slug-and-plan-gates
  (let [root (tmp-dir)]
    (is (= "cave-graph" (squadd-web/story-slug "Cave graph")))
    (is (= "story" (squadd-web/story-slug "!!!")))
    (write-file (fs/path root "stories/cave-graph.md") "taken\n")
    (is (true? (squadd-web/story-id-taken? root "cave-graph")))
    (is (= "cave-graph-2" (squadd-web/unused-story-id root "cave-graph")))
    (is (true? (next/awaiting-implementation-plan? {"story_id" "s1"})))
    (is (false? (next/awaiting-implementation-plan? {"gherkin_path" "features/s1.feature"})))
    (is (true? (assign/themeless-theme? "none")))
    (is (false? (assign/themeless-theme? "wumpus")))
    (is (true? (approval/spec-gate? "implementation-plan")))
    (is (false? (approval/spec-gate? "hardening")))
    (write-file (fs/path root ".squad/backlog/bl-1.item")
                "id: bl-1\nstory_id: cave-graph\nstatus: started\n")
    (is (= "bl-1.item" (fs/file-name (approval/backlog-file-for-story root "cave-graph"))))
    (is (nil? (approval/backlog-file-for-story root "missing")))
    (is (str/includes? (approval/rejected-story-body root "missing" "nope") "Rejected story"))
    (write-file (fs/path root "stories/s1.md") "body\n")
    (is (str/includes? (approval/rejected-story-body root "s1" "nope") "body"))
    (is (nil? (assign/first-existing-file [(fs/path root "nope.md")])))
    (write-file (fs/path root ".squad/stories/s1/packet") "theme_id: wumpus\n")
    (is (true? (assign/mismatched-packet-theme?
                (fs/path root ".squad/stories/s1/packet") "other")))
    (is (false? (assign/mismatched-packet-theme?
                 (fs/path root ".squad/stories/s1/packet") "wumpus")))))
