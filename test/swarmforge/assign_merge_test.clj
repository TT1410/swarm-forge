(ns swarmforge.assign-merge-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [swarmforge.test-support :refer :all]))

(deftest squad-assign-generates-durable-assignment-from-theme-story
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (fs/create-dirs (fs/path root "swarmforge/role-templates"))
      (write-file (fs/path root "swarmforge/role-templates/implementer.prompt")
                  "implement\n")
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md")
                  "Story: cave topology and setup.\n")
      (write-file (fs/path root "instructions.md")
                  "Write unit tests first, then production code.\n")
      (write-file (fs/path root ".squad/reviews/wumpus-cave-impl-review.md")
                  "Review: request a smaller implementation boundary.\n")
      (write-file (fs/path root "rejection.md")
                  "Reject because the branch exceeded the story boundary.\n")
      (write-file (fs/path root "replacement-instructions.md")
                  "Reimplement only cave topology.\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "cave-topology" "stories/cave-topology.md")
      (run {:dir root} (script "squad_theme.sh") "approve" "wumpus" "acceptance-cave-topology" "user approved cave topology acceptance spec")
      (prepare-implementation-packet! root "wumpus" "cave-topology")
      (let [create (run {:dir root}
                        (script "squad_assign.sh")
                        "create"
                        "wumpus"
                        "cave-topology"
                        "implementer"
                        "wumpus-cave-impl"
                        "instructions.md"
                        "--requires"
                        "approval:acceptance-cave-topology")
            status (run {:dir root}
                        (script "squad_assign.sh")
                        "status"
                        "wumpus-cave-impl")
            assignment (fs/path root ".squad/assignments/wumpus-cave-impl/assignment.md")
            draft (fs/path root ".squad/assignments/wumpus-cave-impl/result-handoff.draft")
            commit (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (is (str/includes? (:out create) "SQUAD_ASSIGNMENT: wumpus-cave-impl"))
        (is (str/includes? (:out create) "TEMPLATE: implementer"))
        (is (str/includes? (:out status) "STATE: assignment_created"))
        (is (str/includes? (slurp (str assignment)) "assignment_id: wumpus-cave-impl"))
        (is (str/includes? (slurp (str assignment)) "Story: cave topology and setup."))
        (is (str/includes? (slurp (str assignment)) "Write unit tests first"))
        (is (str/includes? (slurp (str assignment)) "swarm_handoff.sh"))
        (is (str/includes? (slurp (str draft)) "type: git_handoff"))
        (is (str/includes? (slurp (str draft)) "to: squad-leader"))
        (is (str/includes? (slurp (str draft)) "task: wumpus-cave-impl"))
        (is (str/includes? (slurp (str (fs/path root ".squad/themes/wumpus/events.log")))
                           "\tassignment_created\twumpus-cave-impl\timplementer\tcave-topology"))
        (write-file (fs/path root "anonymous-result.handoff")
                    (str "id: 1\n"
                         "to: squad-leader\n"
                         "priority: 50\n"
                         "type: git_handoff\n"
                         "task: wumpus-cave-impl\n"
                         "commit: " commit "\n"
                         "\n"
                         "merge_and_process implementer-001 " commit "\n"))
        (write-file (fs/path root "leader-result.handoff")
                    (str "id: 2\n"
                         "from: squad-leader\n"
                         "to: squad-leader\n"
                         "priority: 50\n"
                         "type: git_handoff\n"
                         "task: wumpus-cave-impl\n"
                         "commit: " commit "\n"
                         "\n"
                         "merge_and_process squad-leader " commit "\n"))
        (let [anonymous (run {:dir root :ok? false}
                             (script "squad_assign.sh")
                             "result"
                             "wumpus-cave-impl"
                             "anonymous-result.handoff")
              leader-authored (run {:dir root :ok? false}
                                   (script "squad_assign.sh")
                                   "result"
                                   "wumpus-cave-impl"
                                   "leader-result.handoff")]
          (is (= 2 (:exit anonymous)))
          (is (str/includes? (:err anonymous) "Result handoff must have a from header."))
          (is (= 2 (:exit leader-authored)))
          (is (str/includes? (:err leader-authored) "Transient result handoff may not be from: squad-leader.")))
        (write-file (fs/path root "result.handoff")
                    (str "id: 1\n"
                         "from: implementer-001\n"
                         "to: squad-leader\n"
                         "priority: 50\n"
                         "type: git_handoff\n"
                         "task: wumpus-cave-impl\n"
                         "commit: " commit "\n"
                         "\n"
                         "merge_and_process implementer-001 " commit "\n"))
        (let [result (run {:dir root}
                          (script "squad_assign.sh")
                          "result"
                          "wumpus-cave-impl"
                          "result.handoff")
              result-status (run {:dir root}
                                 (script "squad_assign.sh")
                                 "status"
                                 "wumpus-cave-impl")
              merge-ready (run {:dir root}
                               (script "squad_assign.sh")
                               "merge-ready"
                               "wumpus-cave-impl")
              merge-status (run {:dir root}
                                (script "squad_assign.sh")
                                "status"
                                "wumpus-cave-impl")
              ad-hoc-review (run {:dir root :ok? false}
                                  (script "squad_assign.sh")
                                  "review"
                                  "wumpus-cave-impl"
                                  "changes-requested"
                                  "instructions.md")
              review (run {:dir root}
                          (script "squad_assign.sh")
                          "review"
                          "wumpus-cave-impl"
                          "changes-requested"
                          ".squad/reviews/wumpus-cave-impl-review.md")
              reject (run {:dir root}
                          (script "squad_assign.sh")
                          "reject"
                          "wumpus-cave-impl"
                          "rejection.md")
              replace (run {:dir root}
                           (script "squad_assign.sh")
                           "replace"
                           "wumpus-cave-impl"
                           "wumpus-cave-impl-2"
                           "implementer"
                           "replacement-instructions.md")
              replacement-status (run {:dir root}
                                      (script "squad_assign.sh")
                                      "status"
                                      "wumpus-cave-impl-2")
              report (run {:dir root}
                          (script "squad_report.sh")
                          "wumpus")]
          (is (str/includes? (:out result) "STATE: result_received"))
          (is (str/includes? (:out result) (str "COMMIT: " commit)))
          (is (str/includes? (:out result-status) "STATE: result_received"))
          (is (str/includes? (:out result-status) "RESULT:"))
          (is (str/includes? (:out merge-ready) "STATE: merge_ready"))
          (is (str/includes? (:out merge-ready) "commit already reachable from HEAD"))
          (is (str/includes? (:out merge-status) "STATE: merge_ready"))
          (is (str/includes? (:out merge-status) "MERGE:"))
          (is (= 2 (:exit ad-hoc-review)))
          (is (str/includes? (:err ad-hoc-review) "durable reviewer report under .squad/reviews"))
          (is (str/includes? (:out review) "STATE: review_changes_requested"))
          (is (str/includes? (:out reject) "STATE: rejected"))
          (is (str/includes? (:out replace) "SQUAD_ASSIGNMENT: wumpus-cave-impl-2"))
          (is (str/includes? (:out replace) "REPLACES: wumpus-cave-impl"))
          (is (str/includes? (:out replacement-status) "STATE: assignment_created"))
          (is (str/includes? (:out report) "# Squad Report: wumpus"))
          (is (str/includes? (:out report) "- Stories: cave-topology"))
          (is (str/includes? (:out report) "acceptance-cave-topology: user approved cave topology acceptance spec"))
          (is (str/includes? (:out report) "wumpus-cave-impl [implementer] story=cave-topology state=replacement_created"))
          (is (str/includes? (:out report) "replacement=wumpus-cave-impl-2"))
          (is (str/includes? (:out report) "wumpus-cave-impl-2 [implementer] story=cave-topology state=assignment_created"))
          (is (str/includes? (:out report) "replaces=wumpus-cave-impl"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl/result.handoff")))
                             "from: implementer-001"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl/result")))
                             (str "commit: " commit)))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl/merge")))
                             "state: merge_ready"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl/review")))
                             "state: review_changes_requested"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl/review.md")))
                             "smaller implementation boundary"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl/rejection")))
                             "state: rejected"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl/rejection.md")))
                             "exceeded the story boundary"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl/replacement")))
                             "replacement: wumpus-cave-impl-2"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl-2/replaces")))
                             "replaces: wumpus-cave-impl"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl-2/assignment.md")))
                             "Reimplement only cave topology"))
          (is (str/includes? (slurp (str (fs/path root ".squad/themes/wumpus/events.log")))
                             (str "\tassignment_result_received\twumpus-cave-impl\timplementer-001\t" commit "\tcave-topology")))
          (is (str/includes? (slurp (str (fs/path root ".squad/themes/wumpus/events.log")))
                             (str "\tassignment_merge_ready\twumpus-cave-impl\t" commit "\tcave-topology")))
          (is (str/includes? (slurp (str (fs/path root ".squad/themes/wumpus/events.log")))
                             "\tassignment_review_changes_requested\twumpus-cave-impl\tchanges-requested\tcave-topology"))
          (is (str/includes? (slurp (str (fs/path root ".squad/themes/wumpus/events.log")))
                             "\tassignment_rejected\twumpus-cave-impl\tcave-topology"))
          (is (str/includes? (slurp (str (fs/path root ".squad/themes/wumpus/events.log")))
                             "\tassignment_replacement_created\twumpus-cave-impl\twumpus-cave-impl-2\tcave-topology")))
        (write-file (fs/path root "blocked.md")
                    "Blocked because the worker hit an invisible escalation prompt.\n")
        (let [block (run {:dir root}
                         (script "squad_assign.sh")
                         "block"
                         "wumpus-cave-impl-2"
                         "blocked.md")
              blocked-status (run {:dir root}
                                  (script "squad_assign.sh")
                                  "status"
                                  "wumpus-cave-impl-2")]
          (is (str/includes? (:out block) "STATE: blocked"))
          (is (str/includes? (:out blocked-status) "STATE: blocked"))
          (is (str/includes? (:out blocked-status) "BLOCKER:"))
          (is (str/includes? (slurp (str (fs/path root ".squad/themes/wumpus/events.log")))
                             "\tassignment_blocked\twumpus-cave-impl-2\tcave-topology")))
        (let [spawn (run {:dir root
                          :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"}}
                         (script "squad_spawn.sh")
                         "implementer"
                         "wumpus-cave-impl"
                         (str assignment))]
          (is (str/includes? (:out spawn) "SQUAD_AGENT: implementer-001"))
          (is (str/includes? (slurp (str (fs/path root ".squad/agents/implementer-001/prompt.md")))
                             "assignment_id: wumpus-cave-impl"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-assign-merge-ready-uses-isolated-worktree-for-review-files
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (fs/create-dirs (fs/path root "swarmforge/role-templates"))
      (write-file (fs/path root "swarmforge/role-templates/reviewer.prompt")
                  "review\n")
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md")
                  "Story: cave topology and setup.\n")
      (write-file (fs/path root "instructions.md")
                  "Review the Gherkin.\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "cave-topology" "stories/cave-topology.md")
      (run {:dir root}
           (script "squad_assign.sh")
           "create"
           "wumpus"
           "cave-topology"
           "reviewer"
           "wumpus-cave-gherkin-review"
           "instructions.md")
      (run {:dir root} "git" "add" ".squad" "stories")
      (run {:dir root} "git" "commit" "-q" "-m" "Create review assignment")
      (run {:dir root} "git" "checkout" "-q" "-b" "swarmforge-reviewer-001")
      (write-file (fs/path root ".squad/reviews/wumpus-cave-gherkin-review.md")
                  "Review: accepted by transient reviewer.\n")
      (run {:dir root} "git" "add" ".squad/reviews/wumpus-cave-gherkin-review.md")
      (run {:dir root} "git" "commit" "-q" "-m" "Add review report")
      (let [commit (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root} "git" "checkout" "-q" "master")
        (write-file (fs/path root ".squad/reviews/wumpus-cave-gherkin-review.md")
                    "SL scratch note at colliding review path.\n")
        (write-file (fs/path root "result.handoff")
                    (str "id: 1\n"
                         "from: reviewer-001\n"
                         "to: squad-leader\n"
                         "priority: 50\n"
                         "type: git_handoff\n"
                         "task: wumpus-cave-gherkin-review\n"
                         "commit: " commit "\n"
                         "\n"
                         "review complete\n"))
        (run {:dir root}
             (script "squad_assign.sh")
             "result"
             "wumpus-cave-gherkin-review"
             "result.handoff")
        (let [merge-ready (run {:dir root}
                               (script "squad_assign.sh")
                               "merge-ready"
                               "wumpus-cave-gherkin-review")]
          (is (str/includes? (:out merge-ready) "STATE: merge_ready"))
          (is (str/includes? (:out merge-ready) "dry-run merge passed"))
          (is (str/includes? (slurp (str (fs/path root ".squad/reviews/wumpus-cave-gherkin-review.md")))
                             "SL scratch note"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-assign-allows-analyst-theme-assignment-without-story
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (fs/create-dirs (fs/path root "swarmforge/role-templates"))
      (write-file (fs/path root "swarmforge/role-templates/analyst.prompt")
                  "analyze theme into self-contained stories\n")
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus CLI.\n")
      (write-file (fs/path root "instructions.md")
                  "Break the approved theme into self-contained stories.\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus-cli" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "approve" "wumpus-cli" "theme" "approved by user")
      (let [create (run {:dir root}
                        (script "squad_assign.sh")
                        "create"
                        "wumpus-cli"
                        "theme"
                        "analyst"
                        "wumpus-cli-analysis"
                        "instructions.md"
                        "--requires"
                        "approval:theme")
            status (run {:dir root}
                        (script "squad_assign.sh")
                        "status"
                        "wumpus-cli-analysis")
            assignment (slurp (str (fs/path root ".squad/assignments/wumpus-cli-analysis/assignment.md")))
            metadata (slurp (str (fs/path root ".squad/assignments/wumpus-cli-analysis/metadata")))]
        (is (str/includes? (:out create) "SQUAD_ASSIGNMENT: wumpus-cli-analysis"))
        (is (str/includes? (:out create) "STORY: theme"))
        (is (str/includes? (:out status) "STATE: assignment_created"))
        (is (str/includes? assignment "scope: theme"))
        (is (str/includes? assignment "## Theme"))
        (is (str/includes? assignment "Implement a faithful Hunt the Wumpus CLI."))
        (is (not (str/includes? assignment "## Story")))
        (is (str/includes? assignment "Break the approved theme into self-contained stories."))
        (is (str/includes? metadata "scope: theme"))
        (is (str/includes? (slurp (str (fs/path root ".squad/themes/wumpus-cli/events.log")))
                           "\tassignment_created\twumpus-cli-analysis\tanalyst\ttheme")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-assign-creates-batch-assignment-without-story-file
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (fs/create-dirs (fs/path root "swarmforge/role-templates"))
      (write-file (fs/path root "swarmforge/role-templates/hardener.prompt")
                  "harden a batch\n")
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus CLI.\n")
      (write-file (fs/path root "instructions.md")
                  "Harden all batch members.\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus-cli" "theme.md")
      (let [create (run {:dir root}
                        (script "squad_assign.sh")
                        "create-batch"
                        "wumpus-cli"
                        "hardener"
                        "wumpus-cli-hardener"
                        "instructions.md")
            assignment (slurp (str (fs/path root ".squad/assignments/wumpus-cli-hardener/assignment.md")))
            metadata (slurp (str (fs/path root ".squad/assignments/wumpus-cli-hardener/metadata")))]
        (is (str/includes? (:out create) "SQUAD_ASSIGNMENT: wumpus-cli-hardener"))
        (is (str/includes? (:out create) "STORY: batch"))
        (is (str/includes? assignment "scope: batch"))
        (is (str/includes? assignment "Harden all batch members."))
        (is (not (str/includes? assignment "## Story")))
        (is (str/includes? metadata "scope: batch"))
        (is (str/includes? metadata "story_id: batch")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-theme-supports-bulk-and-approved-direct-stories
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md") "Build the CLI.\n")
      (write-file (fs/path root "stories/one.md") "Story: one.\n")
      (write-file (fs/path root "stories/two.md") "Story: two.\n")
      (write-file (fs/path root "stories/direct.md") "Story: direct.\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus-cli" "theme.md")
      (run {:dir root}
           (script "squad_theme.sh")
           "stories"
           "wumpus-cli"
           "one:stories/one.md"
           "two:stories/two.md")
      (run {:dir root} "git" "add" "stories")
      (run {:dir root} "git" "commit" "-q" "-m" "Add stories")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
            approved (run {:dir root}
                          (script "squad_theme.sh")
                          "approved-story"
                          "wumpus-cli"
                          "direct"
                          "stories/direct.md"
                          "sl-direct-story"
                          "master"
                          sha
                          "approved-by-user")
            packet (slurp (str (fs/path root ".squad/stories/direct/packet")))]
        (is (str/includes? (:out approved) "STATE: story_approved"))
        (is (fs/regular-file? (fs/path root ".squad/themes/wumpus-cli/stories/one.ref")))
        (is (fs/regular-file? (fs/path root ".squad/themes/wumpus-cli/stories/two.ref")))
        (is (str/includes? packet "story_approval: approved"))
        (is (str/includes? packet "story_assignment: sl-direct-story")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-approval-request-is-idempotent-by-semantic-gate-and-supports-bulk
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md") "Build the CLI.\n")
      (write-file (fs/path root "stories/one.md") "Story: one.\n")
      (write-file (fs/path root "stories/two.md") "Story: two.\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "one" "stories/one.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "two" "stories/two.md")
      (run {:dir root} "git" "add" "stories")
      (run {:dir root} "git" "commit" "-q" "-m" "Add stories")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root} (script "squad_packet.sh") "create" "wumpus" "one" "analysis-one" "master" sha)
        (run {:dir root} (script "squad_packet.sh") "create" "wumpus" "two" "analysis-two" "master" sha))
      (let [first-request (run {:dir root}
                               (script "squad_approval.sh")
                               "request"
                               "story__one"
                               "story"
                               "one"
                               "story"
                               "Approve_story"
                               "story-ready")
            duplicate (run {:dir root}
                           (script "squad_approval.sh")
                           "request"
                           "alternate-one"
                           "story"
                           "one"
                           "story"
                           "Approve_story"
                           "story-ready")
            same-id (run {:dir root}
                         (script "squad_approval.sh")
                         "request"
                         "story__one"
                         "story"
                         "one"
                         "story"
                         "Approve_story"
                         "story-ready")
            bulk (run {:dir root}
                      (script "squad_approval.sh")
                      "request-bulk"
                      "story"
                      "story"
                      "Approve_story"
                      "story-ready"
                      "story__one_again:one"
                      "story__two:two")
            pending-files (fs/list-dir (fs/path root ".squad/approvals/pending"))]
        (is (str/includes? (:out first-request) "SQUAD_APPROVAL: story__one"))
        (is (str/includes? (:out duplicate) "SQUAD_APPROVAL: story__one"))
        (is (str/includes? (:out same-id) "SQUAD_APPROVAL: story__one"))
        (is (str/includes? (:out bulk) "SQUAD_APPROVAL: story__two"))
        (is (= #{"story__one.approval" "story__two.approval"}
               (set (map fs/file-name pending-files)))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-assign-records-workflow-metadata-without-enforcing-readiness
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (fs/create-dirs (fs/path root "swarmforge/role-templates"))
      (write-file (fs/path root "swarmforge/role-templates/implementer.prompt")
                  "implement\n")
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md")
                  "Story: cave topology and setup.\n")
      (write-file (fs/path root "instructions.md")
                  "Write unit tests first, then production code.\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "cave-topology" "stories/cave-topology.md")
      (let [created (run {:dir root}
                         (script "squad_assign.sh")
                         "create"
                         "wumpus"
                         "cave-topology"
                         "implementer"
                         "wumpus-cave-impl"
                         "instructions.md"
                         "--requires"
                         "approval:implementation")
            assignment (fs/path root ".squad/assignments/wumpus-cave-impl/assignment.md")
            metadata (fs/path root ".squad/assignments/wumpus-cave-impl/metadata")]
        (is (str/includes? (:out created) "TEMPLATE: implementer"))
        (is (str/includes? (slurp (str assignment)) "requires: approval:implementation"))
        (is (str/includes? (slurp (str metadata)) "requires: approval:implementation")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-assign-materializes-role-tool-contracts
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (fs/create-dirs (fs/path root "swarmforge/role-templates"))
      (write-file (fs/path root "swarmforge/role-templates/cleaner.prompt")
                  "clean\n")
      (write-file (fs/path root "swarmforge/role-templates/cleaner.contract.edn")
                  "{:role \"cleaner\"
                    :required-tools [{:name \"crap4clj\" :source \"github.com/unclebob/crap4clj\" :version \"latest\" :purpose \"CRAP\"}
                                     {:name \"dry4clj\" :source \"github.com/unclebob/dry4clj\" :version \"latest\" :purpose \"DRY\"}]}\n")
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md")
                  "Story: cave topology and setup.\n")
      (write-file (fs/path root "instructions.md")
                  "Clean the implementation.\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "cave-topology" "stories/cave-topology.md")
      (run {:dir root}
           (script "squad_assign.sh")
           "create"
           "wumpus"
           "cave-topology"
           "cleaner"
           "wumpus-cave-clean"
           "instructions.md")
      (let [assignment (slurp (str (fs/path root ".squad/assignments/wumpus-cave-clean/assignment.md")))]
        (is (str/includes? assignment "## Required Tools"))
        (is (str/includes? assignment "crap4clj (CRAP): `squad_tool.sh require crap4clj github.com/unclebob/crap4clj latest`"))
        (is (str/includes? assignment "dry4clj (DRY): `squad_tool.sh require dry4clj github.com/unclebob/dry4clj latest`")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-assign-accepts-reviewed-merge
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (fs/create-dirs (fs/path root "swarmforge/role-templates"))
      (write-file (fs/path root "swarmforge/role-templates/implementer.prompt")
                  "implement\n")
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md")
                  "Story: cave topology and setup.\n")
      (write-file (fs/path root "instructions.md")
                  "Write unit tests first, then production code.\n")
      (write-file (fs/path root ".squad/reviews/wumpus-cave-accepted-review.md")
                  "Review: accepted.\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "cave-topology" "stories/cave-topology.md")
      (run {:dir root} (script "squad_theme.sh") "approve" "wumpus" "acceptance-cave-topology" "user approved cave topology acceptance spec")
      (prepare-implementation-packet! root "wumpus" "cave-topology")
      (run {:dir root}
           (script "squad_assign.sh")
           "create"
           "wumpus"
           "cave-topology"
           "implementer"
           "wumpus-cave-accepted"
           "instructions.md"
           "--requires"
           "approval:acceptance-cave-topology")
      (let [commit (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (write-file (fs/path root "result.handoff")
                    (str "id: 1\n"
                         "from: implementer-001\n"
                         "to: squad-leader\n"
                         "priority: 50\n"
                         "type: git_handoff\n"
                         "task: wumpus-cave-accepted\n"
                         "commit: " commit "\n"
                         "\n"
                         "merge_and_process implementer-001 " commit "\n"))
        (run {:dir root} (script "squad_assign.sh") "result" "wumpus-cave-accepted" "result.handoff")
        (run {:dir root} (script "squad_assign.sh") "merge-ready" "wumpus-cave-accepted")
        (run {:dir root} (script "squad_assign.sh") "review" "wumpus-cave-accepted" "accepted" ".squad/reviews/wumpus-cave-accepted-review.md")
        (let [accepted (run {:dir root} (script "squad_assign.sh") "accept-merge" "wumpus-cave-accepted")
              status (run {:dir root} (script "squad_assign.sh") "status" "wumpus-cave-accepted")
              report (run {:dir root} (script "squad_report.sh") "wumpus")]
          (is (str/includes? (:out accepted) "STATE: merged"))
          (is (str/includes? (:out accepted) "commit already reachable from HEAD"))
          (is (str/includes? (:out status) "STATE: merged"))
          (is (str/includes? (:out status) "ACCEPTED_MERGE:"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-accepted/accepted-merge")))
                             "state: merged"))
          (is (str/includes? (:out report) "wumpus-cave-accepted [implementer] story=cave-topology state=merged"))
          (is (str/includes? (:out report) "accepted_merge=merged"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-assign-accepts-merge-before-review
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "swarmforge/role-templates/analyst.prompt")
                  "analyze\n")
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md")
                  "Story: cave topology and setup.\n")
      (write-file (fs/path root "instructions.md")
                  "Split the theme into self-contained stories.\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "cave-topology" "stories/cave-topology.md")
      (run {:dir root}
           (script "squad_assign.sh")
           "create"
           "wumpus"
           "cave-topology"
           "analyst"
           "wumpus-analysis"
           "instructions.md")
      (let [commit (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (write-file (fs/path root "analysis-result.handoff")
                    (str "id: 1\n"
                         "from: analyst-001\n"
                         "to: squad-leader\n"
                         "priority: 50\n"
                         "type: git_handoff\n"
                         "task: wumpus-analysis\n"
                         "commit: " commit "\n"
                         "\n"
                         "merge_and_process analyst-001 " commit "\n"))
        (run {:dir root} (script "squad_assign.sh") "result" "wumpus-analysis" "analysis-result.handoff")
        (run {:dir root} (script "squad_assign.sh") "merge-ready" "wumpus-analysis")
        (let [accepted (run {:dir root} (script "squad_assign.sh") "accept-merge" "wumpus-analysis")
              status (run {:dir root} (script "squad_assign.sh") "status" "wumpus-analysis")]
          (is (str/includes? (:out accepted) "STATE: merged"))
          (is (str/includes? (:out status) "STATE: merged"))
          (is (str/includes? (:out status) "REVIEW: none"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-assign-review-can-extract-report-from-reviewer-handoff
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "swarmforge/role-templates/implementer.prompt")
                  "implement\n")
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md")
                  "Story: cave topology and setup.\n")
      (write-file (fs/path root "instructions.md")
                  "Write unit tests first, then production code.\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "cave-topology" "stories/cave-topology.md")
      (run {:dir root} (script "squad_theme.sh") "approve" "wumpus" "acceptance-cave-topology" "user approved cave topology acceptance spec")
      (prepare-implementation-packet! root "wumpus" "cave-topology")
      (run {:dir root}
           (script "squad_assign.sh")
           "create"
           "wumpus"
           "cave-topology"
           "implementer"
           "wumpus-cave-impl"
           "instructions.md"
           "--requires"
           "approval:acceptance-cave-topology")
      (let [result-commit (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (write-file (fs/path root "result.handoff")
                    (str "id: 1\n"
                         "from: implementer-001\n"
                         "to: squad-leader\n"
                         "priority: 50\n"
                         "type: git_handoff\n"
                         "task: wumpus-cave-impl\n"
                         "commit: " result-commit "\n"
                         "\n"
                         "merge_and_process implementer-001 " result-commit "\n"))
        (run {:dir root} (script "squad_assign.sh") "result" "wumpus-cave-impl" "result.handoff"))
      (write-file (fs/path root ".squad/reviews/wumpus-cave-impl-review.md")
                  "Review: changes requested for room topology edge cases.\n")
      (run {:dir root} "git" "add" ".squad/reviews/wumpus-cave-impl-review.md")
      (run {:dir root} "git" "commit" "-q" "-m" "Add cave topology review")
      (let [review-commit (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (write-file (fs/path root "review-result.handoff")
                    (str "id: 2\n"
                         "from: reviewer-001\n"
                         "to: squad-leader\n"
                         "priority: 50\n"
                         "type: git_handoff\n"
                         "task: wumpus-cave-review\n"
                         "commit: " review-commit "\n"
                         "\n"
                         "merge_and_process reviewer-001 " review-commit "\n"))
        (let [review (run {:dir root}
                          (script "squad_assign.sh")
                          "review"
                          "wumpus-cave-impl"
                          "changes-requested"
                          "review-result.handoff")
              review-record (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl/review")))
              review-text (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl/review.md")))]
          (is (str/includes? (:out review) "STATE: review_changes_requested"))
          (is (str/includes? review-record (str "source: " review-commit ":.squad/reviews/wumpus-cave-impl-review.md")))
          (is (str/includes? review-text "room topology edge cases"))
          (is (not (str/includes? review-text "merge_and_process")))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-assign-accepts-changes-requested-reviewer-report-merge
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "swarmforge/role-templates/reviewer.prompt")
                  "review\n")
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md")
                  "Story: cave topology and setup.\n")
      (write-file (fs/path root "review-instructions.md")
                  "Review the cave topology implementation.\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "cave-topology" "stories/cave-topology.md")
      (run {:dir root}
           (script "squad_assign.sh")
           "create"
           "wumpus"
           "cave-topology"
           "reviewer"
           "wumpus-cave-review"
           "review-instructions.md")
      (write-file (fs/path root ".squad/reviews/wumpus-cave-review.md")
                  "Review: changes requested, but merge this report for leader disposition.\n")
      (run {:dir root} "git" "add" ".squad/reviews/wumpus-cave-review.md")
      (run {:dir root} "git" "commit" "-q" "-m" "Add reviewer report")
      (let [commit (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (write-file (fs/path root "review-result.handoff")
                    (str "id: 1\n"
                         "from: reviewer-001\n"
                         "to: squad-leader\n"
                         "priority: 50\n"
                         "type: git_handoff\n"
                         "task: wumpus-cave-review\n"
                         "commit: " commit "\n"
                         "\n"
                         "merge_and_process reviewer-001 " commit "\n"))
        (run {:dir root} (script "squad_assign.sh") "result" "wumpus-cave-review" "review-result.handoff")
        (run {:dir root} (script "squad_assign.sh") "merge-ready" "wumpus-cave-review")
        (run {:dir root} (script "squad_assign.sh") "review" "wumpus-cave-review" "changes-requested" "review-result.handoff")
        (let [accepted (run {:dir root} (script "squad_assign.sh") "accept-merge" "wumpus-cave-review")
              status (run {:dir root} (script "squad_assign.sh") "status" "wumpus-cave-review")]
          (is (str/includes? (:out accepted) "STATE: merged"))
          (is (str/includes? (:out accepted) "commit already reachable from HEAD"))
          (is (str/includes? (:out status) "STATE: merged"))))
      (finally
        (fs/delete-tree root)))))
