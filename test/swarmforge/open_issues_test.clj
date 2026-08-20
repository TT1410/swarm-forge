(ns swarmforge.open-issues-test
  "Open issues.md items (not on hold): prompts, residual, cockpit, retire, backlog CLI."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [squad-config :as cfg]
            [squad-retire :as retire]
            [squad-state :as state]
            [squadd.web :as web]
            [swarmforge.test-support :refer :all]))

(defn- write-roles! [root]
  (write-file (fs/path root ".swarmforge/roles.tsv")
              (str "squad-leader\tmaster\t" root
                   "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n")))

(defn- prompt [name]
  (slurp (str (fs/path repo-root "swarmforge/role-templates" name))))

(defn- role-prompt [name]
  (slurp (str (fs/path repo-root "swarmforge/roles" name))))

;;; --- Pipeline prompts ---

(deftest analyst-plan-mocks-other-backlog-items
  ;; Given the analyst prompt
  ;; Then the plan treats this story as the only real item, mocks the rest,
  ;; and includes ports, dummy state, how to run the stub, and non-goals
  (let [p (prompt "analyst.prompt")]
    (is (str/includes? p "mocked ports"))
    (is (str/includes? p "dummy state"))
    (is (re-find #"(?i)how to run" p))
    (is (str/includes? p "non-goals"))
    (is (re-find #"(?i)only this story is real" p))
    (is (re-find #"(?i)mock" p))))

(deftest qa-proc-writer-commits-procedure-and-implementer-notes
  ;; Given the QA-procedure-writer prompt
  ;; Then it writes procedure + implementer notes in one commit
  (let [p (prompt "qa-procedure-writer.prompt")]
    (is (re-find #"(?i)implementer notes" p))
    (is (re-find #"(?i)one commit" p))
    (is (re-find #"qa/.+-implementer-notes\.md" p))
    (is (re-find #"(?i)QA-runnable executable" p))))

(deftest implementer-reads-notes-not-qa-procedure-body
  ;; Given the implementer prompt
  ;; Then it reads implementer notes and does not treat the QA procedure as the spec
  (let [p (prompt "implementer.prompt")]
    (is (re-find #"(?i)implementer notes" p))
    (is (not (str/includes? p "The QA procedure is a later role, not an implementer input.")))))

(deftest qa-pass-means-drive-the-real-program
  ;; Given the QA prompt
  ;; Then a pass starts the real program through the UI; calling production
  ;; functions or a test harness is a fail
  (let [p (prompt "qa.prompt")]
    (is (re-find #"(?i)real program|user interface only" p))
    (is (re-find #"(?i)test harness is a fail|calling production functions" p))
    (is (re-find #"(?i)this story" p))))

(deftest cleaner-uses-property-framework-and-additive-tooling
  ;; Given the cleaner prompt
  ;; Then it uses the environment's property-testing library (test.check on bb)
  ;; and may add to bb.edn/deps.edn without changing existing targets
  (let [p (prompt "cleaner.prompt")]
    (is (re-find #"clojure\.test\.check" p))
    (is (re-find #"(?i)already on the classpath" p))
    (is (re-find #"(?i)additive" p))
    (is (not (re-find #"Do \*\*not\*\* edit root tooling files.*unless the assignment explicitly requires tooling cleanup" p)))))

(deftest troubleshooter-prompt-names-backlog-cli
  ;; Given the Troubleshooter prompt
  ;; Then it names the batch-add CLI rather than reverse-engineering HTTP
  (let [p (role-prompt "troubleshooter.prompt")]
    (is (str/includes? p "squad_backlog.sh"))))

(deftest sl-and-local-workflow-wait-for-qa-proc-approval
  (let [sl (role-prompt "squad-leader.prompt")
        wf (slurp (str (fs/path repo-root "swarmforge/constitution/articles/local-workflow.prompt")))]
    (is (not (str/includes? sl "does not wait for QA procedure")))
    (is (not (str/includes? wf "Does not wait for the QA procedure.")))
    (is (re-find #"(?i)qa procedure" sl))
    (is (re-find #"(?i)implementer notes" wf))))

;;; --- Residual: implementer waits on QA-proc approval ---

(deftest implementer-waits-for-qa-procedure-approval-click
  ;; Given plan and Gherkin approved, QA procedure written but not approved
  ;; When residual runs
  ;; Then it does not create an implementer
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "stories/cave-graph.md") "Rooms.\n")
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str "story_id: cave-graph\n"
                       "story_path: stories/cave-graph.md\n"
                       "implementation_plan_path: .squad/stories/cave-graph/plan.md\n"
                       "implementation_plan_approval: approved\n"
                       "gherkin_path: features/cave-graph.feature\n"
                       "gherkin_approval: approved\n"
                       "qa_procedure_path: qa/cave-graph.md\n"
                       "qa_procedure_sha: abcdef1234\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (not (str/includes? out "TEMPLATE: implementer")))
        (is (str/includes? out "qa-procedure")))
      (finally
        (fs/delete-tree root)))))

(deftest implementation-ready-requires-qa-procedure-approval
  ;; Given plan and Gherkin approved without QA-proc approval
  ;; Then the packet is not implementation-ready
  (is (false? (state/implementation-ready?
               {"implementation_plan_approval" "approved"
                "gherkin_approval" "approved"})))
  (is (true? (state/implementation-ready?
              {"implementation_plan_approval" "approved"
               "gherkin_approval" "approved"
               "qa_procedure_approval" "approved"}))))

;;; --- Later-role batches without a theme ---

(deftest themeless-si-merge-stamps-member-packets
  ;; Given themeless packets with architecture changes-requested
  ;; And a merged architecture-fix assignment with no manifest
  ;; When mechanical residual runs
  ;; Then each member packet gets senior_implementer_sha
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (doseq [story ["alpha" "beta"]]
        (write-file (fs/path root "stories" (str story ".md")) (str story "\n"))
        (write-file (fs/path root ".squad/stories" story "packet")
                    (str "story_id: " story "\n"
                         "qa_sha: abcdef1234\n"
                         "architecture_review: changes-requested\n"
                         "architecture_review_assignment: architecture\n")))
      (write-file (fs/path root ".squad/assignments/architecture-fix/metadata")
                  (str "assignment_id: architecture-fix\n"
                       "theme_id: none\n"
                       "story_id: batch\n"
                       "template: senior-implementer\n"))
      (write-file (fs/path root ".squad/assignments/architecture-fix/status")
                  "assignment_id: architecture-fix\nstate: merged\n")
      (write-file (fs/path root ".squad/assignments/architecture-fix/accepted-merge")
                  (str "assignment_id: architecture-fix\n"
                       "state: merged\n"
                       "commit: 7931912abc\n"
                       "merge_commit: 7931912abc\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh") "--apply-mechanical"))
            alpha (slurp (str (fs/path root ".squad/stories/alpha/packet")))
            beta (slurp (str (fs/path root ".squad/stories/beta/packet")))]
        (is (str/includes? out "record_merged_batch_result"))
        (is (str/includes? alpha "senior_implementer_sha: 7931912abc"))
        (is (str/includes? beta "senior_implementer_sha: 7931912abc")))
      (finally
        (fs/delete-tree root)))))

(deftest architecture-fix-records-membership-before-create
  ;; Given a themeless packet that needs SI
  ;; When residual runs
  ;; Then it records architecture-fix batch membership rather than waiting
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "stories/cave-graph.md") "Rooms.\n")
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str "story_id: cave-graph\n"
                       "story_path: stories/cave-graph.md\n"
                       "implementation_plan_approval: approved\n"
                       "gherkin_approval: approved\n"
                       "qa_procedure_approval: approved\n"
                       "implementation_sha: abcdef1111\n"
                       "cleaner_sha: abcdef2222\n"
                       "code_review: accepted\n"
                       "code_review_sha: abcdef3333\n"
                       "hardener_sha: abcdef4444\n"
                       "qa_sha: abcdef5555\n"
                       "architecture_review: changes-requested\n"
                       "architecture_sha: abcdef6666\n"
                       "architecture_assignment: architecture\n"
                       "architecture_branch: master\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (or (str/includes? out "squad_batch_story.sh add")
                (str/includes? out "architecture-fix")
                (str/includes? out "record_batch_membership")))
        (is (not (str/includes? out "NEXT_ACTION: wait"))))
      (finally
        (fs/delete-tree root)))))

(deftest si-returned-is-done-on-the-board
  ;; Given packet states after SI
  ;; Then architecture_revision_returned and senior_implementer_returned are Done
  (is (= "done" (web/board-column "architecture_revision_returned")))
  (is (= "done" (web/board-column "senior_implementer_returned"))))

;;; --- Story package ---

(deftest story-package-includes-plan-and-implementer-notes
  ;; Given a packet with plan, notes, Gherkin, and QA procedure
  ;; When the story package is rendered
  ;; Then all of those sections are present
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "stories/cave.md") "# Cave\n\nWalk.\n")
      (write-file (fs/path root ".squad/stories/cave/plan.md") "Purpose: walk.\n")
      (write-file (fs/path root "qa/cave.md") "Type Y at SAME SET-UP.\n")
      (write-file (fs/path root "qa/cave-implementer-notes.md") "bb run -- --qa-start-rooms\n")
      (write-file (fs/path root "features/cave.feature") "Feature: cave\n")
      (write-file (fs/path root ".squad/stories/cave/packet")
                  (str "story_id: cave\n"
                       "story_path: stories/cave.md\n"
                       "implementation_plan_path: .squad/stories/cave/plan.md\n"
                       "qa_procedure_path: qa/cave.md\n"
                       "qa_implementer_notes_path: qa/cave-implementer-notes.md\n"
                       "gherkin_path: features/cave.feature\n"))
      (let [body (web/story-content root "cave")]
        (is (str/includes? body "Walk."))
        (is (str/includes? body "Purpose: walk."))
        (is (str/includes? body "Type Y at SAME SET-UP."))
        (is (str/includes? body "bb run -- --qa-start-rooms"))
        (is (str/includes? body "Feature: cave")))
      (finally
        (fs/delete-tree root)))))

(deftest qa-procedure-gate-opens-the-story-package
  ;; Given a QA-procedure approval
  ;; Then the operator document is the story package (procedure + notes together)
  (let [ref (web/approval-document-ref
             {"target_kind" "story"
              "target_id" "cave"
              "gate" "qa-procedure"})]
    (is (str/includes? (get ref "document_url") "/artifact/story/cave"))
    (is (re-find #"(?i)package|QA procedure" (get ref "document_label")))))

(deftest story-package-html-has-ids-for-gate-jumps
  ;; Given a story with plan, Gherkin, notes, and QA procedure
  ;; When the story package page is served
  ;; Then section ids match Attention hashes so the browser can jump
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "stories/cave.md") "# Cave\n\nWalk.\n")
      (write-file (fs/path root ".squad/stories/cave/plan.md") "Purpose: walk.\n")
      (write-file (fs/path root "qa/cave.md") "Type Y at SAME SET-UP.\n")
      (write-file (fs/path root "qa/cave-implementer-notes.md") "bb run -- --qa-start-rooms\n")
      (write-file (fs/path root "features/cave.feature") "Feature: cave\n")
      (write-file (fs/path root ".squad/stories/cave/packet")
                  (str "story_id: cave\n"
                       "story_path: stories/cave.md\n"
                       "implementation_plan_path: .squad/stories/cave/plan.md\n"
                       "qa_procedure_path: qa/cave.md\n"
                       "qa_implementer_notes_path: qa/cave-implementer-notes.md\n"
                       "gherkin_path: features/cave.feature\n"))
      (let [page (:body (web/artifact-response root "/artifact/story/cave"))
            ref (web/approval-document-ref
                 {"target_kind" "story" "target_id" "cave" "gate" "gherkin"})]
        (is (str/includes? page "id=\"gherkin\""))
        (is (str/includes? page "id=\"qa-procedure\""))
        (is (str/includes? page "id=\"implementation-plan\""))
        (is (str/includes? page "id=\"implementer-notes\""))
        (is (str/includes? page "bb run -- --qa-start-rooms"))
        (is (str/includes? (get ref "document_url") "#gherkin")))
      (finally
        (fs/delete-tree root)))))

(deftest assign-create-has-no-theme-id-slot
  ;; Given a story with no theme
  ;; When assign create / residual create run
  ;; Then usage and COMMAND have no theme-id, and metadata has no theme_id: none
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "swarmforge/role-templates/analyst.prompt") "plan\n")
      (write-file (fs/path root "stories/cave-graph.md") "Rooms.\n")
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  "story_id: cave-graph\nstory_path: stories/cave-graph.md\n")
      (write-file (fs/path root "instructions.md") "Write the plan.\n")
      (let [usage (:err (run {:dir root :ok? false} (script "squad_assign.sh")))
            created (run {:dir root} (script "squad_assign.sh")
                         "create" "cave-graph" "analyst" "cave-graph-analysis"
                         "instructions.md")
            meta (slurp (str (fs/path root ".squad/assignments/cave-graph-analysis/metadata")))
            md (slurp (str (fs/path root ".squad/assignments/cave-graph-analysis/assignment.md")))
            residual (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? usage "create <story-id>"))
        (is (not (str/includes? usage "create <theme-id>")))
        (is (str/includes? usage "create-batch <template>"))
        (is (not (str/includes? usage "create-batch <theme-id>")))
        (is (zero? (:exit created)))
        (is (not (str/includes? meta "theme_id:")))
        (is (not (str/includes? md "theme_id:")))
        (is (not (str/includes? residual "create none ")))
        (is (not (str/includes? residual "create-batch none ")))
        (is (not (re-find #"THEME: none" residual))))
      (finally
        (fs/delete-tree root)))))

;;; --- Cockpit ---

(deftest backlog-deck-has-a-visible-label
  ;; Given the cockpit HTML
  ;; Then the backlog control is labeled Backlog without hovering
  (is (re-find #"id=\"backlog-deck\"[^>]*>[\s\S]*Backlog" web/dashboard-html)))

(deftest story-card-popup-is-not-mock-host-copy
  ;; Given the story card float
  ;; Then it does not say Detached window (mock host)
  (is (not (str/includes? web/dashboard-html "Detached window (mock host)"))))

(deftest story-cards-do-not-show-a-theme-id
  ;; Given the story card renderer
  ;; Then it does not print theme_id as operator copy
  (is (not (str/includes? web/dashboard-html "s.theme_id"))))

(deftest grok-working-counter-is-not-thermometer-activity
  ;; Given a Grok pane whose only change is Working (Ns) above the footer
  ;; When samples are taken for the activity hash
  ;; Then the samples match
  (let [a "work output\nWorking (12s)\n┌─ Grok ──\n│ ❯\n└─\n"
        b "work output\nWorking (13s)\n┌─ Grok ──\n│ ❯\n└─\n"
        c "work output\nchanged\nWorking (13s)\n┌─ Grok ──\n│ ❯\n└─\n"]
    (is (= (web/pane-sample-for-hash a "grok")
           (web/pane-sample-for-hash b "grok")))
    (is (not= (web/pane-sample-for-hash a "grok")
              (web/pane-sample-for-hash c "grok")))))

(deftest wif-icon-uses-agent-lifecycle-when-agent-present
  ;; Given a WIF row with an agent in handoff_ready
  ;; When rows are built
  ;; Then agent_state is handoff_ready so the glyph can be an arrow
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".squad/assignments/cave-impl/metadata")
                  "assignment_id: cave-impl\ntemplate: implementer\nstory_id: cave\nagent_id: impl-001\n")
      (write-file (fs/path root ".squad/assignments/cave-impl/status")
                  "state: in_progress\nupdated_at: 2026-08-20T00:00:00Z\n")
      (write-agent-status! root "impl-001" "handoff_ready")
      (let [rows (web/work-in-flight-rows root (web/assignment-state root) [])
            row (first rows)]
        (is (= "impl-001" (get row "agent_id")))
        (is (= "handoff_ready" (get row "agent_state"))))
      (finally
        (fs/delete-tree root)))))

(deftest wif-render-prefers-agent-state-for-icon
  (is (re-find #"agent_id\s*\?\s*\(w\.agent_state" web/dashboard-html)))

;;; --- Backlog CLI ---

(deftest backlog-cli-imports-a-directory-of-markdown-as-open-items
  ;; Given a directory of story markdown files
  ;; When squad_backlog.sh import runs
  ;; Then each file lands as an open backlog item and is not started
  (let [root (tmp-dir)
        stories (fs/path root "incoming")]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path stories "walk.md") "# Walk the cave\n\nMove between rooms.\n")
      (write-file (fs/path stories "pits.md") "# Bottomless pits\n\nFalling is death.\n")
      (let [result (run {:dir root} (script "squad_backlog.sh") "import" (str stories))
            items (web/list-backlog root)]
        (is (zero? (:exit result)))
        (is (str/includes? (:out result) "SQUAD_BACKLOG"))
        (is (= 2 (count items)))
        (is (every? #(= "open" (get % "status")) items))
        (is (some #(str/includes? (get % "title") "Walk") items))
        (is (some #(str/includes? (get % "body") "Falling is death") items))
        (is (not (fs/directory? (fs/path root "stories")))
            "import does not Start"))
      (finally
        (fs/delete-tree root)))))

;;; --- Retire: save sessions ---

(deftest retire-saves-session-files-when-configured
  ;; Given save_agent_sessions true
  ;; When an agent is retired
  ;; Then a pane capture is stored and tmux is still killed
  (let [root (tmp-dir)
        worktree (fs/path root ".worktrees/agent-001")
        captured (atom false)]
    (write-file (fs/path root "swarmforge/squad.conf")
                "save_agent_sessions true\n")
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                     "agent-001\tagent-001\t" worktree "\tswarmforge-agent-001\tAgent 001\tcodex\ttask\n"))
    (write-file (fs/path root ".swarmforge/tmux-socket") "sock\n")
    (write-file (fs/path root ".squad/agents/agent-001/metadata") "task_id: task-1\n")
    (write-file (fs/path root ".squad/agents/agent-001/liveness")
                "state: running\nlast_10_lines:\nhello from pane\n")
    (fs/create-dirs worktree)
    (with-redefs [retire/project-root (constantly root)
                  retire/acquire-lock! (fn [_])
                  retire/stop-session! (fn [socket session]
                                         (reset! captured true)
                                         {:stopped? (= ["sock" "swarmforge-agent-001"] [socket session])
                                          :detail "tmux session stopped"})
                  retire/remove-worktree! (fn [_ _ _] {:removed? true :detail "worktree removed"})
                  retire/delete-branch! (fn [_ agent-id]
                                          {:deleted? true :branch (str "swarmforge-" agent-id)})
                  retire/timestamp (constantly "2026-08-20T00:00:00Z")]
      (retire/retire! "agent-001")
      (is (true? @captured) "tmux still killed")
      (let [archive (fs/path root ".squad/sessions/agent-001")
            pane (or (fs/path archive "pane.txt")
                     (fs/path root ".squad/agents/agent-001/pane.txt"))]
        (is (or (fs/regular-file? (fs/path archive "pane.txt"))
                (fs/regular-file? (fs/path root ".squad/agents/agent-001/session-pane.txt"))
                (fs/regular-file? (fs/path archive "session.log")))
            "session capture stored")))))
