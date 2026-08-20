(ns swarmforge.squadd-web-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [squad-dashboard-request :as dashreq]
            [squadd.web :as web]
            [swarmforge.test-support :refer :all]))

(defn- write-agent! [root agent-id template state]
  (write-file (fs/path root ".squad" "agents" agent-id "metadata")
              (str "agent_id: " agent-id "\ntemplate: " template "\ntask_id: task-" agent-id "\n"))
  (write-file (fs/path root ".squad" "agents" agent-id "status")
              (str "state: " state "\ndetail: test\nupdated_at: 2026-08-10T00:00:00Z\n")))

(deftest dashboard-lists-agents-from-spawn-until-retirement
  ;; Given agents in every lifecycle state including failed and retired
  ;; When the dashboard builds its agent list
  ;; Then every non-retired agent is shown and retired agents are omitted
  (let [root (tmp-dir)]
    (try
      (doseq [[agent-id template state]
              [["starting-001" "implementer" "starting"]
               ["running-001" "implementer" "running"]
               ["blocked-001" "gherkin-writer" "blocked"]
               ["failed-001" "gherkin-writer" "failed"]
               ["handoff-ready-001" "cleaner" "handoff_ready"]
               ["handoff-sent-001" "cleaner" "handoff_sent"]
               ["retired-001" "analyst" "retired"]]]
        (write-agent! root agent-id template state))
      (let [ids (set (map #(get % "agent_id") (web/agent-state root)))]
        (is (contains? ids "starting-001"))
        (is (contains? ids "running-001"))
        (is (contains? ids "blocked-001"))
        (is (contains? ids "failed-001"))
        (is (contains? ids "handoff-ready-001"))
        (is (contains? ids "handoff-sent-001"))
        (is (not (contains? ids "retired-001"))))
      (finally
        (fs/delete-tree root)))))

(deftest stall-report-surfaces-ts-needed-including-leftover-merge-blocked
  ;; Failed agents, held handoffs, and leftover merge_blocked all stall.
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".squad/assignments/cave-impl/metadata")
                  "assignment_id: cave-impl\ntemplate: implementer\nstory_id: cave\n")
      (write-file (fs/path root ".squad/assignments/cave-impl/status")
                  "state: merge_blocked\ndetail: dry-run failed\nupdated_at: 2026-08-14T00:00:00Z\n")
      (write-file (fs/path root ".squad/assignments/cave-impl/merge-error")
                  "CONFLICT (content): Merge conflict in src/core.clj\nAutomatic merge failed\n")
      (write-file (fs/path root ".squad/agents/implementer-001/metadata")
                  "agent_id: implementer-001\ntemplate: implementer\ntask_id: cave-impl\n")
      (write-file (fs/path root ".squad/agents/implementer-001/status")
                  "state: failed\ndetail: tools missing\nupdated_at: 2026-08-14T00:00:00Z\n")
      (write-file (fs/path root ".swarmforge/handoffs/inbox/held/50_held.handoff")
                  "from: cleaner-001\ntask: cave-clean\ntype: git_handoff\n")
      (let [assignments (web/assignment-state root)
            agents (web/agent-state root)
            report (web/stall-report root assignments agents)]
        (is (true? (get report "stalled")))
        (is (>= (get report "count") 2))
        (is (str/includes? (get report "summary") "stalled"))
        (is (some #(= "cave-impl" (get % "id")) (get report "items"))
            "leftover merge_blocked is a stall")
        (is (some #(and (= "agent" (get % "kind"))
                        (= "implementer-001" (get % "id")))
                  (get report "items")))
        (is (some #(= "held_handoff" (get % "kind"))
                  (get report "items")))
        (is (true? (get-in (web/web-state root) ["stalls" "stalled"]))))
      (finally
        (fs/delete-tree root)))))

(deftest dashboard-html-includes-stall-and-progress-ui
  (is (str/includes? web/dashboard-html "stall-section"))
  (is (str/includes? web/dashboard-html "data.stalls"))
  (is (str/includes? web/dashboard-html "req-progress"))
  (is (str/includes? web/dashboard-html "r.progress")))

(deftest backlog-crud-and-approve-for-analysis
  ;; Durable backlog under .squad/backlog; Start writes the story file, no classify request
  (let [root (tmp-dir)]
    (try
      (let [created (web/create-backlog! root {:title "Fog cues" :body "Stronger adjacency hints."})]
        (is (true? (:ok created)))
        (is (= "open" (get-in created [:item "status"])))
        (is (seq (get-in created [:item "created_at"])))
        (write-frame-ready! root)
        (let [id (get-in created [:item "id"])
              listed (web/list-backlog root)
              approved (web/approve-backlog! root id)
              story-id (get-in approved [:item "story_id"])]
          (is (= 1 (count listed)))
          (is (true? (:ok approved)))
          (is (= "started" (get-in approved [:item "status"])))
          (is (nil? (:request approved)))
          (is (not (str/includes? (str (get-in approved [:request "body"])) "NEW THEME")))
          (is (seq story-id))
          (let [story-file (fs/path root "stories" (str story-id ".md"))]
            (is (fs/regular-file? story-file))
            (is (str/includes? (slurp (str story-file)) "Fog cues"))
            (is (str/includes? (slurp (str story-file)) "Stronger adjacency hints.")))))
      (finally
        (fs/delete-tree root)))))

(deftest board-column-mapping-for-stories
  ;;  Specifying / Coding / Finalizing (no Ready)
  (is (= "done" (web/board-column "final_approved")))
  (is (= "coding" (web/board-column "cleaned")))
  (is (= "coding" (web/board-column "implemented")))
  (is (= "coding" (web/board-column "implementation_approved")))
  (is (= "coding" (web/board-column "code_reviewed")))
  (is (= "coding" (web/board-column "code_review_approved")))
  (is (= "finalizing" (web/board-column "hardening_approved")))
  (is (= "finalizing" (web/board-column "qa_approved")))
  (is (= "finalizing" (web/board-column "architecture_reviewed")))
  (is (= "specifying" (web/board-column "specification_in_progress")))
  (is (= "specifying" (web/board-column "implementation_approval_ready")))
  (is (= "specifying" (web/board-column "story_recorded")))
  (is (> (web/pipeline-rank "hardening_approved")
         (web/pipeline-rank "implemented")))
  (is (> (web/pipeline-rank "senior-implementer")
         (web/pipeline-rank "implementer"))))

(deftest leftover-merge-blocked-is-an-attention-stall
  ;; Leftover merge_blocked and its held handoff are stalls; there is no merger recovery.
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".squad" "assignments" "story-impl" "metadata")
                  "assignment_id: story-impl\ntemplate: implementer\nstory_id: s1\n")
      (write-file (fs/path root ".squad" "assignments" "story-impl" "status")
                  "state: merge_blocked\ndetail: dry-run merge failed\nupdated_at: 2026-08-17T16:00:00Z\n")
      (write-file (fs/path root ".squad" "assignments" "story-other" "metadata")
                  "assignment_id: story-other\ntemplate: implementer\nstory_id: s2\n")
      (write-file (fs/path root ".squad" "assignments" "story-other" "status")
                  "state: blocked\ndetail: needs human\nupdated_at: 2026-08-17T16:00:00Z\n")
      (fs/create-dirs (fs/path root ".swarmforge" "handoffs" "inbox" "held"))
      (write-file (fs/path root ".swarmforge" "handoffs" "inbox" "held" "h1.handoff")
                  "from: implementer-1\ntask: story-impl\nassignment: story-impl\n")
      (write-file (fs/path root ".swarmforge" "handoffs" "inbox" "held" "h2.handoff")
                  "from: implementer-2\ntask: unknown-task\n")
      (let [as (web/assignment-state root)
            report (web/stall-report root as [])
            kinds (set (map #(get % "kind") (get report "items")))]
        (is (some #(= "merge_blocked" (get % "state")) (get report "items")))
        (is (true? (get report "stalled")))
        (is (contains? kinds "assignment"))
        (is (some #(= "story-other" (get % "id")) (get report "items")))
        (is (some #(= "story-impl" (get % "id")) (get report "items")))
        (is (some #(= "held_handoff" (get % "kind")) (get report "items"))))
      (finally
        (fs/delete-tree root)))))

(deftest wif-sorts-in-progress-above-created
  ;; Assignment lifecycle outranks same-role newer-created rows
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".squad" "assignments" "a-created" "metadata")
                  "assignment_id: a-created\ntemplate: cleaner\nstory_id: story-a\n")
      (write-file (fs/path root ".squad" "assignments" "a-created" "status")
                  "state: created\ndetail: new\nupdated_at: 2026-08-17T16:00:00Z\n")
      (write-file (fs/path root ".squad" "assignments" "b-progress" "metadata")
                  "assignment_id: b-progress\ntemplate: cleaner\nstory_id: story-b\nagent_id: gr-1\n")
      (write-file (fs/path root ".squad" "assignments" "b-progress" "status")
                  "state: in_progress\ndetail: working\nupdated_at: 2026-08-17T15:00:00Z\n")
      (write-file (fs/path root ".squad" "assignments" "c-writer" "metadata")
                  "assignment_id: c-writer\ntemplate: implementer\nstory_id: story-c\n")
      (write-file (fs/path root ".squad" "assignments" "c-writer" "status")
                  "state: in_progress\ndetail: writing\nupdated_at: 2026-08-17T15:30:00Z\n")
      (let [rows (web/work-in-flight-rows (web/assignment-state root) [])
            ids (mapv #(get % "assignment_id") rows)]
        (is (= "b-progress" (first ids))
            "in_progress same role beats newer created")
        (is (> (.indexOf ids "a-created") (.indexOf ids "b-progress")))
        (is (> (web/assignment-progress-rank "in_progress")
               (web/assignment-progress-rank "created")))
        (is (> (web/assignment-progress-rank "handoff_ready")
               (web/assignment-progress-rank "in_progress"))))
      (finally
        (fs/delete-tree root)))))

(deftest teardown-requires-confirm-and-is-wired-in-ui
  ;; Teardown button + POST /api/teardown with TEARDOWN confirm
  (is (str/includes? web/dashboard-html "teardownSwarm()"))
  (is (str/includes? web/dashboard-html "id=\"teardown-btn\""))
  (is (str/includes? web/dashboard-html "/api/teardown"))
  (is (true? (web/teardown-confirm-ok? "{\"confirm\":\"TEARDOWN\"}")))
  (is (true? (web/teardown-confirm-ok? "TEARDOWN")))
  (is (false? (web/teardown-confirm-ok? "{}")))
  (is (false? (web/teardown-confirm-ok? "{\"confirm\":\"no\"}")))
  (let [root (tmp-dir)
        scheduled (atom false)]
    (try
      (with-redefs [web/schedule-teardown! (fn [_] (reset! scheduled true) true)
                    web/log! (fn [& _] nil)]
        (let [bad (web/teardown-response root "{}")
              good (web/teardown-response root "{\"confirm\":\"TEARDOWN\"}")]
          (is (= 400 (:status bad)))
          (is (= 200 (:status good)))
          (is (true? @scheduled))
          (is (str/includes? (:body good) "teardown_started"))))
      (finally
        (fs/delete-tree root)))))

(deftest theme-package-always-shows-implementation-order
  ;; Given a theme with scheme and module map but no order file
  ;; When building the theme package
  ;; Then Implementation Order is still a section (explicit missing), not omitted
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".squad/themes/hello/theme.md") "Hello theme.\n")
      (write-file (fs/path root ".squad/themes/hello/module-map.md") "Map.\n")
      (let [parts (web/theme-package-parts root "hello")
            titles (set (map :title parts))
            order (first (filter #(= "Implementation Order" (:title %)) parts))]
        (is (contains? titles "Implementation Order"))
        (is (str/includes? (:body order) "Missing")))
      (write-file (fs/path root "implementation-order.md")
                  "# No multi-story implementer dependencies for this theme.\n")
      (let [order (first (filter #(= "Implementation Order" (:title %))
                                 (web/theme-package-parts root "hello")))]
        (is (str/includes? (:body order) "Not yet recorded"))
        (is (str/includes? (:body order) "No multi-story")))
      (finally
        (fs/delete-tree root)))))

(deftest theme-package-includes-dependency-checker-card
  ;; Theme package always shows dependency-checker (content or missing)
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".squad/themes/hello/theme.md") "Hello theme.\n")
      (write-file (fs/path root ".squad/themes/hello/module-map.md") "Map.\n")
      (let [parts (web/theme-package-parts root "hello")
            titles (map :title parts)
            checker (first (filter #(= "Dependency Checker" (:title %)) parts))]
        (is (some #{"Dependency Checker"} titles))
        (is (str/includes? (:body checker) "Missing"))
        (is (str/includes? (:body checker) "dependency-checker.edn")))
      (write-file (fs/path root "dependency-checker.edn")
                  "{:allowed-dependencies {:greeting [] :ui [:greeting]}\n :fail-on-cycles true}\n")
      (let [checker (first (filter #(= "Dependency Checker" (:title %))
                                   (web/theme-package-parts root "hello")))]
        (is (str/includes? (:body checker) ":greeting"))
        (is (str/includes? (:body checker) ":ui"))
        (is (str/includes? (:body checker) "Status:"))
        (is (str/includes? (:body checker) "awaiting user approval"))
        (is (not (str/includes? (:body checker) "_(Missing.)_"))))
      (finally
        (fs/delete-tree root)))))

(deftest theme-package-shows-architecture-gate-status
  ;; Order and checker sections show approval status
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".squad/themes/hello/theme.md") "Hello theme.\n")
      (write-file (fs/path root ".squad/themes/hello/module-map.md") "Map.\n")
      (write-file (fs/path root ".squad/themes/hello/implementation-order.md")
                  "story-b: story-a\n")
      (write-file (fs/path root "dependency-checker.edn")
                  "{:allowed-dependencies {:greeting [] :ui [:greeting]}\n :fail-on-cycles true}\n")
      (let [parts (web/theme-package-parts root "hello")
            order (first (filter #(= "Implementation Order" (:title %)) parts))
            checker (first (filter #(= "Dependency Checker" (:title %)) parts))
            life (first (filter #(= "Project Lifecycle" (:title %)) parts))]
        (is (str/includes? (:body order) "awaiting user approval"))
        (is (str/includes? (:body checker) "awaiting user approval"))
        (is (str/includes? (:body life) "open")))
      (write-file (fs/path root ".squad/themes/hello/lifecycle")
                  "theme_id: hello\nlifecycle: finalized\ndetail: shipped\n")
      (let [life (first (filter #(= "Project Lifecycle" (:title %))
                                (web/theme-package-parts root "hello")))]
        (is (str/includes? (:body life) "finalized")))
      (finally
        (fs/delete-tree root)))))

(deftest dashboard-hides-troubleshooter-from-agent-list
  ;; Given a persistent Troubleshooter under .squad/agents
  ;; When the dashboard builds its agent list
  ;; Then Troubleshooter is omitted (operator surface, not fleet)
  (let [root (tmp-dir)]
    (try
      (write-agent! root "running-001" "implementer" "running")
      (write-agent! root "troubleshooter" "troubleshooter" "running")
      (write-file (fs/path root ".squad/agents/troubleshooter/recovery")
                  "state: dirty_worktree\nchecked_at: 2026-08-12T00:00:00Z\n")
      (let [ids (set (map #(get % "agent_id") (web/agent-state root)))]
        (is (contains? ids "running-001"))
        (is (not (contains? ids "troubleshooter")))
        (is (false? (web/dashboard-agent-visible?
                     {"agent_id" "troubleshooter" "state" "running"}))))
      (finally
        (fs/delete-tree root)))))

(deftest dashboard-links-assignments-to-assignment-documents
  ;; Given an assignment with assignment.md
  ;; When the dashboard serves artifact/assignment/<id> and the main page
  ;; Then the document content is available and the assignments table links to it
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".squad" "assignments" "story-1-implementer" "assignment.md")
                  "# Squad Assignment\n\nassignment_id: story-1-implementer\nLeader instructions for implementer.\n")
      (write-file (fs/path root ".squad" "assignments" "story-1-implementer" "metadata")
                  "assignment_id: story-1-implementer\ntemplate: implementer\nstory_id: story-1\n")
      (write-file (fs/path root ".squad" "assignments" "story-1-implementer" "status")
                  "state: created\ndetail: active\nupdated_at: 2026-08-10T00:00:00Z\n")
      (is (str/includes? (web/artifact-content root "assignment" "story-1-implementer")
                         "Leader instructions for implementer"))
      ;; Combined cockpit: work-in-flight table + artifact routes still available
      (is (str/includes? web/dashboard-html "work_in_flight"))
      (is (str/includes? web/dashboard-html "/artifact/"))
      (finally
        (fs/delete-tree root)))))

(deftest pending-approvals-always-include-document-link
  ;; Given pending approvals for story and theme gates
  ;; When the dashboard builds approval state
  ;; Then every approval has a document_url that opens the artifact to approve
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".squad" "approvals" "pending" "story__cave.approval")
                  (str "approval_id: story__cave\n"
                       "target_kind: story\n"
                       "target_id: cave\n"
                       "gate: story\n"
                       "state: pending\n"
                       "title: Approve story\n"
                       "reason: ready\n"))
      (write-file (fs/path root ".squad" "approvals" "pending" "gherkin__cave.approval")
                  (str "approval_id: gherkin__cave\n"
                       "target_kind: story\n"
                       "target_id: cave\n"
                       "gate: gherkin\n"
                       "state: pending\n"
                       "title: Approve gherkin\n"
                       "reason: ready\n"))
      (write-file (fs/path root ".squad" "approvals" "pending" "order__wumpus.approval")
                  (str "approval_id: order__wumpus\n"
                       "target_kind: theme\n"
                       "target_id: wumpus\n"
                       "gate: implementation-order\n"
                       "state: pending\n"
                       "title: Approve order\n"
                       "reason: ready\n"))
      (let [pending (web/approval-state-for root "pending")
            by-id (into {} (map (juxt #(get % "approval_id") identity) pending))]
        (is (= 1 (count pending)))
        (is (nil? (get by-id "story__cave")))
        (is (nil? (get by-id "order__wumpus")))
        (is (= "/artifact/story/cave#gherkin" (get-in by-id ["gherkin__cave" "document_url"])))
        (doseq [a pending]
          (is (not (str/blank? (get a "document_url")))
              (str "approval missing document_url: " (get a "approval_id")))
          (is (not (str/blank? (get a "document_label"))))))
      (is (str/includes? web/dashboard-html "document_url")
          "Attention strip must render View document from approval document_url")
      (is (str/includes? web/dashboard-html "data-doc")
          "Approval rows expose document open control")
      (is (str/includes? web/dashboard-html "openAgentWindow")
          "Document opens in popup window")
      (finally
        (fs/delete-tree root)))))

(deftest dashboard-html-has-navigation-and-layout-affordances
  ;;  theme,  WIF agent,  therm,  buttons,  no Live agents,
  ;;  icons,  Specifying,  splitter,  chat stick
  (let [html web/dashboard-html]
    (is (not (str/includes? html "View project")))
    (is (not (str/includes? html "data-view-theme")))
    (is (str/includes? html "data-open-agent"))
    (is (str/includes? html "finalizing"))
    (is (str/includes? html "Finalizing"))
    (is (str/includes? html "Specifying"))
    (is (str/includes? html "specifying"))
    (is (not (str/includes? html "Live agents")))
    (is (not (str/includes? html "id=\"agents\"")))
    (is (str/includes? html "sl-therm"))
    (is (str/includes? html "stateIcon"))
    (is (str/includes? html "card-glow"))
    (is (str/includes? html "chatStickBottom"))
    (is (str/includes? html "fmtStamp"))
    (is (str/includes? html "next action:"))
    (is (not (str/includes? html "chat-send"))
        "No Send button")
    (is (not (str/includes? html "ui-design.md · mockup"))
        "No mockup footer")
    (is (str/includes? html "id=\"splitter\""))
    (is (str/includes? html "scrollbar-gutter:stable"))
    (is (str/includes? html "sortByProgress"))
    (is (str/includes? html "max-height:100%"))
    (is (str/includes? html ".btn:active"))))

(deftest dashboard-shows-merge-blocked-hides-terminal-assignments
  ;; Given merge_blocked (non-terminal) and merged (terminal) assignments
  ;; When assignment-state is built
  ;; Then merge_blocked is listed and merged is not
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".squad" "assignments" "blocked-impl" "metadata")
                  "assignment_id: blocked-impl\ntemplate: implementer\nstory_id: cave\n")
      (write-file (fs/path root ".squad" "assignments" "blocked-impl" "status")
                  "state: merge_blocked\ndetail: dry-run merge failed\nupdated_at: 2026-08-12T00:00:00Z\n")
      (write-file (fs/path root ".squad" "assignments" "done-impl" "metadata")
                  "assignment_id: done-impl\ntemplate: implementer\nstory_id: other\n")
      (write-file (fs/path root ".squad" "assignments" "done-impl" "status")
                  "state: merged\ndetail: ok\nupdated_at: 2026-08-12T00:00:00Z\n")
      (write-file (fs/path root ".squad" "assignments" "active-impl" "metadata")
                  "assignment_id: active-impl\ntemplate: implementer\nstory_id: mid\n")
      (write-file (fs/path root ".squad" "assignments" "active-impl" "status")
                  "state: in_progress\ndetail: working\nupdated_at: 2026-08-12T00:00:00Z\n")
      (let [ids (set (map #(get % "assignment_id") (web/assignment-state root)))]
        (is (contains? ids "blocked-impl"))
        (is (contains? ids "active-impl"))
        (is (not (contains? ids "done-impl")))
        (is (web/web-active-assignment? "merge_blocked"))
        (is (not (web/web-active-assignment? "merged")))
        (is (not (web/web-active-assignment? "superseded"))))
      (finally
        (fs/delete-tree root)))))

(deftest agent-session-resolves-from-roles-tsv-for-persistent-roles
  ;; Given a persistent troubleshooter with no .squad/agents metadata
  ;; When resolving the tmux session for the dashboard pane
  ;; Then roles.tsv supplies swarmforge-troubleshooter
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".swarmforge" "roles.tsv")
                  (str "troubleshooter\tmaster\t" root
                       "\tswarmforge-troubleshooter\tTroubleshooter\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge" "sessions.tsv")
                  "2\ttroubleshooter\tswarmforge-troubleshooter\tTroubleshooter\tcodex\n")
      (is (= "swarmforge-troubleshooter"
             (web/agent-session-name root "troubleshooter")))
      (is (= "codex" (web/agent-backend-name root "troubleshooter")))
      (finally
        (fs/delete-tree root)))))

(deftest troubleshooter-working-when-pending-request-or-agent-signals
  ;; Given a persistent Troubleshooter that does not write status/heartbeat
  ;; When the operator has a pending dashboard request
  ;; Then web-state reports troubleshooter.working true for the busy indicator
  ;; And when idle with no pending work, working is false
  ;; And daemon-style status/liveness still count as working
  (let [root (tmp-dir)]
    (try
      (is (false? (web/troubleshooter-working? root))
          "idle with no metadata is not working")
      (is (false? (get-in (web/web-state root) ["troubleshooter" "working"])))
      (write-file (fs/path root ".swarmforge/dashboard/requests/pending/dashboard-1.request")
                  (str "id: dashboard-1\n"
                       "kind: request\n"
                       "status: pending\n"
                       "created_at: 2026-08-12T00:00:00Z\n"
                       "updated_at: 2026-08-12T00:00:00Z\n"
                       "body: Why is this stuck?\n"))
      (is (true? (web/troubleshooter-working? root))
          "pending dashboard request means busy")
      (is (true? (get-in (web/web-state root) ["troubleshooter" "working"])))
      (fs/delete-if-exists (fs/path root ".swarmforge/dashboard/requests/pending/dashboard-1.request"))
      (write-file (fs/path root ".squad/agents/troubleshooter/status")
                  "state: running\ndetail: investigating\nupdated_at: 2026-08-12T00:00:00Z\n")
      (is (true? (web/troubleshooter-working? root))
          "active status still means busy without pending request")
      (write-file (fs/path root ".squad/agents/troubleshooter/status")
                  "state: idle\ndetail: waiting\nupdated_at: 2026-08-12T00:00:00Z\n")
      (is (false? (web/troubleshooter-working? root))
          "idle status without pending is not busy")
      (write-file (fs/path root ".squad/agents/troubleshooter/status")
                  "state: running\ndetail: thinking\nupdated_at: 2026-08-12T00:00:00Z\n")
      (write-file (fs/path root ".squad/agents/troubleshooter/liveness")
                  "state: running_pane_idle\npane_idle_prompt: true\nobserved_at: now\n")
      (is (false? (web/troubleshooter-working? root))
          "pane idle prompt overrides active-looking status")
      (finally
        (fs/delete-tree root)))))

(deftest squadd-serves-web-status-and-registers-approvals
  (let [root (tmp-dir)
        bin (fs/path root "bin")
        fake-tmux (fs/path bin "tmux")
        fake-state (fs/path root "fake-tmux-state")]
    (try
      (init-repo! root)
      (fs/create-dirs bin)
      (write-file fake-tmux
                  (str "#!/usr/bin/env sh\n"
                       "mkdir -p \"$FAKE_TMUX_STATE\"\n"
                       "cmd=\"$3\"\n"
	                       "case \"$cmd\" in\n"
	                       "  send-keys)\n"
	                       "    count_file=\"$FAKE_TMUX_STATE/returns\"\n"
	                       "    count=0\n"
	                       "    test -f \"$count_file\" && read count < \"$count_file\"\n"
	                       "    case \"$*\" in\n"
	                       "      *\"web approval changed state\"*|*\"run squad_next.sh\"*) touch \"$FAKE_TMUX_STATE/web-approval-notify\" ;;\n"
	                       "      *\"User message from dashboard\"*|*dashboard-*|*Dashboard*request*|*REQUEST_ID*|*squad_dashboard_request*) touch \"$FAKE_TMUX_STATE/sl-message\" ;;\n"
	                       "      *\" C-m\") count=$((count + 1)); echo \"$count\" > \"$count_file\" ;;\n"
	                       "    esac\n"
	                       "    exit 0\n"
                       "    ;;\n"
                       "  capture-pane)\n"
                       "    printf '%s\\n' 'active pane line 1' 'active pane line 2' '› Explain this codebase' '  gpt-5.5 medium · ~/junk/squad'\n"
                       "    exit 0\n"
                       "    ;;\n"
                       "  *) exit 0 ;;\n"
                       "esac\n"))
      (run {:dir root} "chmod" "+x" (str fake-tmux))
      (fs/create-dirs (fs/path root ".swarmforge/daemon"))
      (write-file (fs/path root ".swarmforge/tmux-socket")
                  "/tmp/swarmforge-test.sock\n")
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "module-map.md") minimal-module-map)
      (write-file (fs/path root "stories/cave-topology.md")
                  "Story: cave topology and setup.\n")
      (write-file (fs/path root "features/cave-topology.feature")
                  "Feature: Cave topology\n")
      (write-file (fs/path root "qa/cave-topology.md")
                  "# QA Procedure\n")
      (run {:dir root} "git" "add" "stories" "features" "qa")
      (run {:dir root} "git" "commit" "-q" "-m" "Prepare story for dashboard")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root}
             (script "squad_packet.sh")
             "create"
             "cave-topology"
             "wumpus-analysis"
	             "swarmforge-analyst-001"
	             sha))
	      (spit (str (fs/path root ".squad/stories/cave-topology/packet"))
	            "state: specification_in_progress\nimplementation_sha: implementation-sha\n"
	            :append true)
	      (spit (str (fs/path root ".squad/stories/cave-topology/packet"))
	            "gherkin_path: features/cave-topology.feature\nqa_procedure_path: qa/cave-topology.md\ngherkin_review_assignment: active-assignment\n"
	            :append true)
      (run {:dir root}
           (script "squad_approval.sh")
           "request"
           "gherkin__cave-topology"
           "story"
           "cave-topology"
           "gherkin"
           "Approve Gherkin"
           "gherkin is ready")
      (write-file (fs/path root ".squad/agents/active-001/metadata")
                  "agent_id: active-001\ntemplate: implementer\ntask_id: active-task\nsession: active-session\n")
      (write-file (fs/path root ".squad/agents/active-001/status")
                  "state: running\ndetail: active\nupdated_at: 2026-08-03T00:00:00Z\n")
      (write-file (fs/path root ".squad/agents/blocked-001/metadata")
                  "agent_id: blocked-001\ntemplate: gherkin-writer\ntask_id: blocked-assignment\nsession: blocked-session\n")
      (write-file (fs/path root ".squad/agents/blocked-001/status")
                  "state: blocked\ndetail: missing gherkin-parser\nupdated_at: 2026-08-05T00:00:00Z\n")
      (write-file (fs/path root ".squad/agents/retired-001/metadata")
                  "agent_id: retired-001\ntemplate: analyst\ntask_id: retired-task\n")
      (write-file (fs/path root ".squad/agents/retired-001/status")
                  "state: retired\ndetail: done\nupdated_at: 2026-08-03T00:00:00Z\n")
      (write-file (fs/path root ".squad/assignments/active-assignment/metadata")
                  "assignment_id: active-assignment\ntemplate: implementer\nstory_id: cave-topology\n")
	      (write-file (fs/path root ".squad/assignments/active-assignment/status")
	                  "state: created\ndetail: active\nupdated_at: 2026-08-03T00:00:00Z\n")
	      (write-file (fs/path root ".squad/assignments/active-assignment/assignment.md")
	                  "# Squad Assignment\n\nassignment_id: active-assignment\nImplement cave topology.\n")
	      (write-file (fs/path root ".squad/assignments/blocked-assignment/metadata")
	                  "assignment_id: blocked-assignment\ntemplate: gherkin-writer\nstory_id: cave-topology\n")
	      (write-file (fs/path root ".squad/assignments/blocked-assignment/status")
	                  "state: in_progress\nagent_id: blocked-001\ndetail: assigned\nupdated_at: 2026-08-05T00:00:00Z\n")
	      (write-file (fs/path root ".squad/assignments/newer-assignment/metadata")
	                  "assignment_id: newer-assignment\ntemplate: cleaner\nstory_id: cave-topology\n")
	      (write-file (fs/path root ".squad/assignments/newer-assignment/status")
	                  "state: blocked\ndetail: active\nupdated_at: 2026-08-04T00:00:00Z\n")
	      (write-file (fs/path root ".squad/assignments/newer-assignment/blocker")
	                  "assignment_id: newer-assignment\nstate: blocked\nkind: required-tool-evidence\nupdated_at: 2026-08-04T00:00:00Z\n")
	      (write-file (fs/path root ".squad/assignments/newer-assignment/blocker.md")
	                  "Cleaner could not load crap4clj.\n")
	      (write-file (fs/path root ".squad/assignments/resolved-assignment/metadata")
	                  "assignment_id: resolved-assignment\ntemplate: gherkin-writer\nstory_id: cave-topology\n")
	      (write-file (fs/path root ".squad/assignments/resolved-assignment/status")
	                  "state: merged\ndetail: done\nupdated_at: 2026-08-04T00:00:00Z\n")
	      (write-file (fs/path root ".squad/assignments/resolved-assignment/blocker")
	                  "assignment_id: resolved-assignment\nstate: blocked\nkind: stale\nupdated_at: 2026-08-04T00:00:00Z\n")
	      (write-file (fs/path root ".squad/reviews/active-assignment.md")
	                  "accepted\n")
      (write-file (fs/path root ".squad/assignments/merged-assignment/metadata")
                  "assignment_id: merged-assignment\ntemplate: analyst\nstory_id: cave-topology\n")
      (write-file (fs/path root ".squad/assignments/merged-assignment/status")
                  "state: merged\ndetail: done\nupdated_at: 2026-08-03T00:00:00Z\n")
      (run {:dir root :ok? false}
           "sh" "-c"
           (str "FAKE_TMUX_STATE=" fake-state
                " PATH=" bin ":$PATH"
                " SWARMFORGE_SQUADD_SKIP_TMUX=1 SWARMFORGE_SQUADD_WEB_PORT=0 bb "
                (script "squadd.clj") " " root " >/dev/null 2>&1 &"))
      (let [url-file (fs/path root ".swarmforge/daemon/squad-web-url")]
	        (is (wait-for-file url-file 3000))
	        (let [base-url (str/trim (slurp (str url-file)))
	              page (slurp base-url)
	              state (slurp (str base-url "api/state"))
	              theme-page (slurp (str base-url "artifact/theme/wumpus"))
	              story-page (slurp (str base-url "artifact/story/cave-topology"))
	              gherkin-page (slurp (str base-url "artifact/gherkin/cave-topology"))
	              qa-page (slurp (str base-url "artifact/qa-procedure/cave-topology"))
	              review-page (slurp (str base-url "artifact/review/active-assignment"))
	              assignment-page (slurp (str base-url "artifact/assignment/active-assignment"))
	              blocker-page (slurp (str base-url "artifact/blocker/newer-assignment"))
	              agent-page (slurp (str base-url "agent/active-001"))
	              pane-tail (slurp (str base-url "api/agents/active-001/pane"))
	              approve (http-post (str base-url "api/approvals/gherkin__cave-topology/approve"))
	              returns-before-message (Long/parseLong
	                                      (str/trim
	                                       (slurp (str (fs/path fake-state "returns")))))
	              message (http-post (str base-url "api/sl-message") "Duplicate hazard messages should not appear.")
	              returns-after-message (Long/parseLong
	                                     (str/trim
	                                      (slurp (str (fs/path fake-state "returns")))))
	              approved (slurp (str base-url "api/state"))]
	          (is (str/includes? state "\"approval_id\":\"gherkin__cave-topology\""))
	          (is (str/includes? state "\"story_id\":\"cave-topology\""))
	          (is (str/includes? state "\"state\":\"implemented\""))
	          (is (str/includes? state "\"stage_label\":\"implement\""))
	          (is (not (str/includes? state "\"state\":\"specification_in_progress\"")))
	          (is (str/includes? state "\"agent_id\":\"active-001\""))
	          (is (not (str/includes? state "\"agent_id\":\"retired-001\"")))
	          (is (str/includes? state "\"assignment_id\":\"active-assignment\""))
	          (is (str/includes? state "\"assignment_id\":\"newer-assignment\""))
	          (is (str/includes? state "\"blockers\""))
	          (is (str/includes? state "\"kind\":\"required-tool-evidence\""))
	          (is (str/includes? state "\"assignment_id\":\"blocked-assignment\""))
	          (is (str/includes? state "\"kind\":\"agent-blocked\""))
	          (is (str/includes? state "\"detail\":\"missing gherkin-parser\""))
	          (is (not (str/includes? state "\"assignment_id\":\"resolved-assignment\"")))
	          (is (< (str/index-of state "\"assignment_id\":\"newer-assignment\"")
	                 (str/index-of state "\"assignment_id\":\"active-assignment\"")))
	          (is (not (str/includes? state "\"assignment_id\":\"merged-assignment\"")))
	          ;; Combined cockpit UI — markers, not legacy table JS
	          (is (str/includes? page "Troubleshooter"))
	          (is (str/includes? page "id=\"ts-busy\""))
	          (is (str/includes? page "data.troubleshooter"))
	          (is (str/includes? page "id=\"sl-message\""))
	          (is (str/includes? page "Add Story"))
	          (is (str/includes? page "work_in_flight"))
	          (is (str/includes? page "backlog"))
	          (is (str/includes? page "/api/backlog"))
	          (is (str/includes? page "/agent/troubleshooter"))
	          (is (str/includes? page "/agent/squad-leader"))
	          (is (str/includes? page "teardownSwarm()"))
	          (is (str/includes? theme-page "Project package: wumpus"))
	          (is (not (str/includes? theme-page "faithful Hunt the Wumpus")))
	          (is (str/includes? story-page "cave topology and setup"))
	          (is (str/includes? gherkin-page "Feature: Cave topology"))
	          (is (str/includes? qa-page "QA Procedure"))
	          (is (str/includes? review-page "accepted"))
	          (is (str/includes? assignment-page "Implement cave topology"))
	          (is (str/includes? blocker-page "crap4clj"))
	          (is (str/includes? agent-page "/api/agents/active-001/pane"))
	          (is (str/includes? agent-page "text!==pane.textContent"))
	          (is (or (str/includes? agent-page "nearBottom")
                  (str/includes? agent-page "stickBottom"))
              "pane page preserves scroll stickiness")
	          (is (str/includes? agent-page "marker.style.display='block'"))
	          (is (str/includes? agent-page "New output"))
	          (is (not (str/includes? agent-page "› Improve documentation")))
	          (is (str/includes? pane-tail "active pane line 2"))
	          (is (not (str/includes? pane-tail "Explain this codebase")))
	          (is (not (str/includes? pane-tail "gpt-5.5 medium")))
	          (is (= 200 (:status approve)))
	          (is (= 200 (:status message)))
	          (is (str/includes? approved "\"approved\""))
	          (is (fs/exists? (fs/path fake-state "web-approval-notify")))
	          (is (fs/exists? (fs/path fake-state "sl-message")))
	          ;; The web daemon can emit adjacent wakeups into the same fake tmux
	          ;; stream. This assertion only needs to prove the dashboard message
	          ;; sent the required double-return wakeup.
	          (is (<= 2 (- returns-after-message returns-before-message)))
          (is (fs/exists? (fs/path root ".squad/approvals/approved/gherkin__cave-topology.approval")))
          (is (not (fs/exists? (fs/path root ".squad/approvals/pending/gherkin__cave-topology.approval"))))))
      (finally
        (run {:dir root :ok? false} (script "stop_squadd.clj") (str root))
        (fs/delete-tree root)))))

(deftest squadd-omits-approval-history
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (fs/create-dirs (fs/path root ".swarmforge/daemon"))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "stories/cave.md") "Cave.\n")
      (write-file (fs/path root ".squad/stories/cave/packet")
                  "story_id: cave\nstory_path: stories/cave.md\n")
      (run {:dir root}
           (script "squad_approval.sh")
           "request"
           "implementation-plan__cave"
           "story"
           "cave"
           "implementation-plan"
           "Approve plan"
           "plan is ready")
      (run {:dir root :ok? false}
           "sh" "-c"
           (str "SWARMFORGE_SQUADD_SKIP_TMUX=1 SWARMFORGE_SQUADD_WEB_PORT=0 bb "
                (script "squadd.clj") " " root " >/dev/null 2>&1 &"))
	      (let [url-file (fs/path root ".swarmforge/daemon/squad-web-url")]
	        (is (wait-for-file url-file 3000))
	        (let [base-url (str/trim (slurp (str url-file)))
	              page (slurp base-url)
	              state (slurp (str base-url "api/state"))]
	          (is (not (str/includes? page "Approval History")))
	          (is (not (str/includes? state "\"resolution_detail\":\"approved by test\"")))))
      (finally
        (run {:dir root :ok? false} (script "stop_squadd.clj") (str root))
        (fs/delete-tree root)))))

(defn- long-story-body []
  (apply str (repeat 2000 "The hunter says \"I smell a wumpus\" in the \\south\\ tunnel.\n")))

(defn- http-get-with-timeout [url timeout-ms]
  (let [[_ host port path] (re-matches #"http://([^:/]+):([0-9]+)(/.*)" url)
        fut (future
              (let [socket (java.net.Socket. host (Long/parseLong port))]
                (with-open [socket socket
                            reader (java.io.BufferedReader.
                                    (java.io.InputStreamReader. (.getInputStream socket) "UTF-8"))]
                  (let [req (str "GET " path " HTTP/1.1\r\nHost: " host "\r\nConnection: close\r\n\r\n")]
                    (.write (.getOutputStream socket) (.getBytes req "UTF-8"))
                    (.flush (.getOutputStream socket))
                    (slurp reader)))))
        result (deref fut timeout-ms ::timeout)]
    (when (= result ::timeout)
      (future-cancel fut)
      (throw (ex-info (str "GET timed out: " url) {:url url})))
    result))

(deftest extract-json-field-tolerates-truncated-json
  ;; Given a Start/save body that is cut off mid-string
  ;; When the dashboard extracts fields
  ;; Then it returns nil instead of throwing
  (is (nil? (web/extract-json-field "{\"title\":\"x\",\"body\":\"unterminated" "body")))
  (is (nil? (web/extract-json-field "{" "title")))
  (is (= "x" (web/extract-json-field "{\"title\":\"x\"" "title"))))

(deftest extract-json-field-skips-non-string-neighbors
  ;; Given JSON with numbers, arrays, and nested objects around the story
  ;; When the dashboard extracts body
  ;; Then it still returns the string field
  (is (= "ok" (web/extract-json-field
               "{\"n\":1,\"a\":[true,{\"k\":\"v\"}],\"flag\":false,\"body\":\"ok\"}"
               "body"))))

(deftest extract-json-field-parses-large-story-bodies
  ;; Given a long story JSON body with quotes, newlines, and backslashes
  ;; When the dashboard extracts title and body
  ;; Then both fields come back quickly without overflowing
  (let [story (long-story-body)
        payload (web/to-json {"title" "Replay hunt" "body" story})
        t0 (System/nanoTime)
        title (web/extract-json-field payload "title")
        body (web/extract-json-field payload "body")
        via-string (web/extract-json-string payload "body")
        ms (/ (double (- (System/nanoTime) t0)) 1.0e6)]
    (is (= "Replay hunt" title))
    (is (= story body))
    (is (= story via-string))
    (is (< ms 500) (str "extract-json-field took " ms "ms"))))

(deftest start-second-backlog-story-via-dashboard-http
  ;; Given two open backlog items with long bodies
  ;; When Start saves then approves each through the dashboard HTTP handlers
  ;; Then both become started stories with packets
  (let [root (tmp-dir)]
    (try
      (let [story (long-story-body)
            first (web/create-backlog! root {:title "Replay hunt" :body story})
            second (web/create-backlog! root {:title "Pit warnings" :body story})
            id1 (get-in first [:item "id"])
            id2 (get-in second [:item "id"])
            _ (write-frame-ready! root)
            save1 (web/handle-web-request root "POST" (str "/api/backlog/" id1)
                                          (web/to-json {"title" "Replay hunt" "body" story}))
            start1 (web/handle-web-request root "POST" (str "/api/backlog/" id1 "/approve") "{}")
            save2 (web/handle-web-request root "POST" (str "/api/backlog/" id2)
                                          (web/to-json {"title" "Pit warnings" "body" story}))
            start2 (web/handle-web-request root "POST" (str "/api/backlog/" id2 "/approve") "{}")]
        (is (= 200 (:status save1)))
        (is (= 200 (:status start1)))
        (is (= 200 (:status save2)))
        (is (= 200 (:status start2)))
        (is (fs/regular-file? (fs/path root "stories/replay-hunt.md")))
        (is (fs/regular-file? (fs/path root "stories/pit-warnings.md")))
        (is (fs/regular-file? (fs/path root ".squad/stories/replay-hunt/packet")))
        (is (fs/regular-file? (fs/path root ".squad/stories/pit-warnings/packet"))))
      (finally
        (fs/delete-tree root)))))

(deftest web-accept-loop-serves-while-another-client-is-held
  ;; Given the dashboard HTTP server
  ;; When one client holds a connection without finishing the request
  ;; Then /api/state still returns
  (let [root (tmp-dir)
        server (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
        port (.getLocalPort server)
        url (str "http://127.0.0.1:" port "/api/state")]
    (try
      (web/start-web-thread! root server)
      (let [stuck (java.net.Socket. "127.0.0.1" port)]
        (try
          (Thread/sleep 80)
          (let [body (http-get-with-timeout url 2000)]
            (is (str/includes? body "\"backlog\"")))
          (finally
            (.close stuck))))
      (finally
        (.close server)
        (fs/delete-tree root)))))

(deftest session-window-html-includes-pane-snapshot
  ;; Given an agent with liveness output
  ;; When the session window HTML is served
  ;; Then the pane already contains that output
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".squad/agents/analyst-001/liveness")
                  "state: running\nlast_10_lines:\nWumpus plan committed\nnext: request_user_approval\n")
      (let [resp (web/agent-page-response root "/agent/analyst-001")]
        (is (= 200 (:status resp)))
        (is (str/includes? (:body resp) "Wumpus plan committed"))
        (is (str/includes? (:body resp) "<pre id=\"pane\">"))
        (is (not (str/includes? (:body resp) "<pre id=\"pane\"></pre>"))))
      (finally
        (fs/delete-tree root)))))

(deftest squadd-opens-dashboard-on-startup-when-enabled
  (let [root (tmp-dir)
        opener (fs/path root "open-dashboard")
        marker (fs/path root "opened-dashboard")]
    (try
      (init-repo! root)
      (fs/create-dirs (fs/path root ".swarmforge/daemon"))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file opener
                  (str "#!/usr/bin/env sh\n"
                       "printf '%s\\n' \"$1\" > " marker "\n"))
      (run {:dir root} "chmod" "+x" (str opener))
      (run {:dir root :ok? false}
           "sh" "-c"
           (str "SWARMFORGE_SQUADD_WEB_PORT=0 "
                "SWARMFORGE_SQUADD_WEB_OPEN=1 "
                "SWARMFORGE_SQUADD_WEB_OPEN_COMMAND=" opener " "
                "bb " (script "squadd.clj") " " root " >/dev/null 2>&1 &"))
      (let [url-file (fs/path root ".swarmforge/daemon/squad-web-url")]
        (is (wait-for-file url-file 3000))
        (is (wait-for-file marker 3000))
        (is (= (str/trim (slurp (str url-file)))
               (str/trim (slurp (str marker))))))
      (finally
        (run {:dir root :ok? false} (script "stop_squadd.clj") (str root))
        (fs/delete-tree root)))))
