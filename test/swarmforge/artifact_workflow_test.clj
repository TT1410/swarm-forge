(ns swarmforge.artifact-workflow-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [swarmforge.test-support :refer :all]))

(deftest squad-batch-tracks-story-to-batch-accounting
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (let [create (run {:dir root}
                        (script "squad_batch.sh")
                        "create"
                        "hardener"
                        "hardener-20260802")
            add (run {:dir root}
                     (script "squad_batch.sh")
                     "add"
                     "hardener-20260802"
                     "cave-topology"
                     "code_reviewed"
                     "cave-clean"
                     "swarmforge-cleaner-001"
                     "abcdef1234")
            status (run {:dir root}
                        (script "squad_batch.sh")
                        "status"
                        "hardener-20260802")
            ready (run {:dir root}
                       (script "squad_batch.sh")
                       "ready"
                       "hardener")]
        (is (str/includes? (:out create) "STATE: open"))
        (is (str/includes? (:out add) "STATE: story_added"))
        (is (str/includes? (:out status) "STORIES: 1"))
        (is (str/includes? (:out ready) "BATCH: hardener-20260802"))
        (is (fs/exists? (fs/path root ".squad/batches/hardener-20260802/status")))
        (is (fs/exists? (fs/path root ".squad/batches/hardener-20260802/manifest")))
        (is (str/includes? (slurp (str (fs/path root ".squad/batches/hardener-20260802/status")))
                           "state: open"))
        (is (str/includes? (slurp (str (fs/path root ".squad/batches/hardener-20260802/manifest.tsv")))
                           "cave-topology\tcode_reviewed\tcave-clean\tswarmforge-cleaner-001\tabcdef1234"))
        (is (str/includes? (slurp (str (fs/path root ".squad/batches/hardener-20260802/manifest")))
                           "cave-topology\tcode_reviewed\tcave-clean\tswarmforge-cleaner-001\tabcdef1234"))
        (is (= "hardener-20260802"
               (str/trim (slurp (str (fs/path root ".squad/stories/cave-topology/active-batches/hardener")))))))
      (run {:dir root}
           (script "squad_batch.sh")
           "create"
           "hardener"
           "hardener-20260803")
      (let [blocked (run {:dir root :ok? false}
                         (script "squad_batch.sh")
                         "add"
                         "hardener-20260803"
                         "cave-topology"
                         "code_reviewed"
                         "cave-clean-2"
                         "swarmforge-cleaner-002"
                         "bbbbbbbbbb")]
        (is (= 3 (:exit blocked)))
        (is (str/includes? (:err blocked) "already in active hardener batch hardener-20260802")))
      (let [result (run {:dir root}
                        (script "squad_batch.sh")
                        "result"
                        "hardener-20260802"
                        "hardener-batch"
                        "swarmforge-hardener-001"
                        "cccccccccc")
            status (run {:dir root}
                        (script "squad_batch.sh")
                        "status"
                        "hardener-20260802")]
        (is (str/includes? (:out result) "STATE: result_received"))
        (is (str/includes? (:out status) "STATE: result_received"))
        (is (str/includes? (slurp (str (fs/path root ".squad/batches/hardener-20260802/status")))
                           "state: result_received"))
        (is (str/includes? (slurp (str (fs/path root ".squad/batches/hardener-20260802/result")))
                           "sha: cccccccccc")))
      (let [add-after-result (run {:dir root}
                                  (script "squad_batch.sh")
                                  "add"
                                  "hardener-20260803"
                                  "cave-topology"
                                  "code_reviewed"
                                  "cave-clean-2"
                                  "swarmforge-cleaner-002"
                                  "bbbbbbbbbb")]
        (is (str/includes? (:out add-after-result) "STATE: story_added"))
        (is (= "hardener-20260803"
               (str/trim (slurp (str (fs/path root ".squad/stories/cave-topology/active-batches/hardener")))))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-packet-reunifies-story-gherkin-and-qa-artifacts
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md")
                  "Story: cave topology and setup.\n")
      (write-file (fs/path root "features/cave-topology.feature")
                  "Feature: cave topology\n")
      (write-file (fs/path root "qa/cave-topology.md")
                  "# QA Procedure: cave topology\n")
      (run {:dir root} "git" "add" "stories" "features" "qa")
      (run {:dir root} "git" "commit" "-q" "-m" "Prepare cave topology packet artifacts")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
            create (run {:dir root}
                        (script "squad_packet.sh")
                        "create"
                        "cave-topology"
                        "wumpus-analysis"
                        "swarmforge-analyst-001"
                        sha)
            plan-approved (run {:dir root}
                               (script "squad_packet.sh")
                               "approve"
                               "cave-topology"
                               "implementation-plan"
                               "user approved plan")
            gherkin (run {:dir root}
                         (script "squad_packet.sh")
                         "attach"
                         "cave-topology"
                         "gherkin"
                         "wumpus-cave-gherkin"
                         "swarmforge-gherkin-writer-001"
                         sha
                         "features/cave-topology.feature")
            gherkin-approval (run {:dir root}
                                  (script "squad_packet.sh")
                                  "approve"
                                  "cave-topology"
                                  "gherkin"
                                  "user approved gherkin")
            qa-procedure (run {:dir root}
                              (script "squad_packet.sh")
                              "attach"
                              "cave-topology"
                              "qa-procedure"
                              "wumpus-cave-qa-procedure"
                              "swarmforge-qa-procedure-writer-001"
                              sha
                              "qa/cave-topology.md")
            qa-approval (run {:dir root}
                             (script "squad_packet.sh")
                             "approve"
                             "cave-topology"
                             "qa-procedure"
                             "user approved qa procedure")
            ready (run {:dir root} (script "squad_packet.sh") "status" "cave-topology")
            implementation-approval (run {:dir root}
                                         (script "squad_packet.sh")
                                         "approve"
                                         "cave-topology"
                                         "implementation"
                                         "user approved implementation")
            approved (run {:dir root} (script "squad_packet.sh") "status" "cave-topology")
            packet (slurp (str (fs/path root ".squad/stories/cave-topology/packet")))]
        (is (str/includes? (:out create) "STATE: story_recorded"))
        (is (str/includes? (:out plan-approved) "APPROVAL: implementation-plan"))
        (is (str/includes? (:out gherkin) "PATH: features/cave-topology.feature"))
        (is (str/includes? (:out gherkin-approval) "APPROVAL: gherkin"))
        (is (str/includes? (:out qa-procedure) "PATH: qa/cave-topology.md"))
        (is (str/includes? (:out qa-approval) "APPROVAL: qa-procedure"))
        (is (str/includes? (:out ready) "STATE: implementation_approval_ready"))
        (is (str/includes? (:out implementation-approval) "STATE: implementation_approved"))
        (is (str/includes? (:out approved) "FINAL_STATE: implementation_approved"))
        (is (str/includes? (:out approved) "GHERKIN_ASSIGNMENT_STATE: complete"))
        (is (str/includes? (:out approved) "GHERKIN_APPROVAL: approved"))
        (is (str/includes? (:out approved) "QA_PROCEDURE_ASSIGNMENT_STATE: complete"))
        (is (str/includes? (:out approved) "QA_PROCEDURE_APPROVAL: approved"))
        (is (str/includes? packet "gherkin_path: features/cave-topology.feature"))
        (is (str/includes? packet "qa_procedure_path: qa/cave-topology.md"))
        (is (str/includes? packet "final_state: implementation_approved"))
        (is (str/includes? packet "story_iterations: wumpus-analysis=recorded"))
        (is (str/includes? packet "gherkin_iterations: wumpus-cave-gherkin=attached"))
        (is (str/includes? packet "qa_procedure_iterations: wumpus-cave-qa-procedure=attached"))
        (is (str/includes? packet "implementation_approval: approved")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-packet-records-explicit-replacement-iterations
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md")
                  "Story: cave topology and setup.\n")
      (write-file (fs/path root "features/cave-topology.feature")
                  "Feature: cave topology\n")
      (write-file (fs/path root "features/cave-topology-v2.feature")
                  "Feature: cave topology revised\n")
      (run {:dir root} "git" "add" "stories" "features")
      (run {:dir root} "git" "commit" "-q" "-m" "Prepare revised gherkin artifacts")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root}
             (script "squad_packet.sh")
             "create"
             "cave-topology"
             "wumpus-analysis"
             "swarmforge-analyst-001"
             sha)
        (run {:dir root}
             (script "squad_packet.sh")
             "attach"
             "cave-topology"
             "gherkin"
             "wumpus-cave-gherkin"
             "swarmforge-gherkin-writer-001"
             sha
             "features/cave-topology.feature")
        (run {:dir root}
             (script "squad_packet.sh")
             "attach"
             "cave-topology"
             "gherkin"
             "wumpus-cave-gherkin-r2"
             "swarmforge-gherkin-writer-002"
             sha
             "features/cave-topology-v2.feature")
        (let [packet (slurp (str (fs/path root ".squad/stories/cave-topology/packet")))]
          (is (str/includes? packet "gherkin_path: features/cave-topology-v2.feature"))
          (is (str/includes? packet "gherkin_iterations: wumpus-cave-gherkin=attached,wumpus-cave-gherkin-r2=attached"))))

      (finally
        (fs/delete-tree root)))))

(deftest squad-approval-tracks-required-gates-and-durable-requests
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md")
                  "Story: cave topology and setup.\n")
      (run {:dir root} "git" "add" "stories")
      (run {:dir root} "git" "commit" "-q" "-m" "Prepare story artifact")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root}
             (script "squad_packet.sh")
             "create"
             "cave-topology"
             "wumpus-analysis"
             "swarmforge-analyst-001"
             sha))
      (let [theme-required (run {:dir root} (script "squad_approval.sh") "required" "theme")
            plan-required (run {:dir root} (script "squad_approval.sh") "required" "implementation-plan")
            implementation-required (run {:dir root} (script "squad_approval.sh") "required" "implementation")
            order-required (run {:dir root} (script "squad_approval.sh") "required" "implementation-order")
            checker-required (run {:dir root} (script "squad_approval.sh") "required" "dependency-checker")]
        (is (str/includes? (:out theme-required) "REQUIRED: false"))
        (is (str/includes? (:out plan-required) "REQUIRED: true"))
        (is (str/includes? (:out implementation-required) "REQUIRED: false"))
        (is (str/includes? (:out order-required) "REQUIRED: false")
            "Implementation-order is not a user gate")
        (is (str/includes? (:out checker-required) "REQUIRED: false")
            "Dependency-checker is not a user gate"))
      (write-file (fs/path root "swarmforge/squad.conf")
                  "approval_required implementation true\napproval_required gherkin false\n")
      (let [implementation-required (run {:dir root} (script "squad_approval.sh") "required" "implementation")
            gherkin-required (run {:dir root} (script "squad_approval.sh") "required" "gherkin")]
        (is (str/includes? (:out implementation-required) "REQUIRED: true"))
        (is (str/includes? (:out gherkin-required) "REQUIRED: false")))
      (write-file (fs/path root "swarmforge/squad.conf")
                  "approval_required qa_procedure true\n")
      (let [qa-procedure-required (run {:dir root} (script "squad_approval.sh") "required" "qa-procedure")]
        (is (str/includes? (:out qa-procedure-required) "REQUIRED: true")))
      (let [request (run {:dir root}
                         (script "squad_approval.sh")
                         "request"
                         "implementation-plan__cave-topology"
                         "story"
                         "cave-topology"
                         "implementation-plan"
                         "Approve plan"
                         "plan is ready")
            duplicate-request (run {:dir root}
                                   (script "squad_approval.sh")
                                   "request"
                                   "approve-cave-plan"
                                   "story"
                                   "cave-topology"
                                   "implementation-plan"
                                   "Approve plan again"
                                   "alternate id should be ignored")
            approve (run {:dir root}
                         (script "squad_approval.sh")
                         "approve"
                         "implementation-plan__cave-topology"
                         "approved by test")
            status (run {:dir root}
                        (script "squad_approval.sh")
                        "status"
                        "implementation-plan__cave-topology")
            packet-status (run {:dir root}
                        (script "squad_packet.sh")
                        "status"
                        "cave-topology")]
        (is (str/includes? (:out request) "STATE: pending"))
        (is (str/includes? (:out duplicate-request) "SQUAD_APPROVAL: implementation-plan__cave-topology"))
        (is (not (fs/exists? (fs/path root ".squad/approvals/pending/approve-cave-plan.approval"))))
        (is (fs/exists? (fs/path root ".squad/approvals/approved/implementation-plan__cave-topology.approval")))
        (is (not (fs/exists? (fs/path root ".squad/approvals/pending/implementation-plan__cave-topology.approval"))))
        (is (str/includes? (:out approve) "STATE: approved"))
        (is (str/includes? (:out status) "STATE: approved"))
        (is (str/includes? (slurp (str (fs/path root ".squad/stories/cave-topology/packet")))
                           "implementation_plan_approval: approved")))
      (let [request (run {:dir root}
                         (script "squad_approval.sh")
                         "request"
                         "gherkin__cave-topology"
                         "story"
                         "cave-topology"
                         "gherkin"
                         "Approve Gherkin"
                         "gherkin needs user review")
            reject (run {:dir root}
                        (script "squad_approval.sh")
                        "reject"
                        "gherkin__cave-topology"
                        "needs revision")]
        (is (str/includes? (:out request) "STATE: pending"))
        (is (str/includes? (:out reject) "STATE: rejected"))
        (is (fs/exists? (fs/path root ".squad/approvals/rejected/gherkin__cave-topology.approval"))))
    (finally
      (fs/delete-tree root)))))

(deftest squad-packet-records-post-implementation-story-state
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md")
                  "Story: cave topology and setup.\n")
      (let [sha (prepare-implementation-packet! root "wumpus" "cave-topology")
            implemented (run {:dir root}
                             (script "squad_packet.sh")
                             "record"
                             "cave-topology"
                             "implementation"
                             "wumpus-cave-impl"
                             "swarmforge-implementer-001"
                             sha)
            cleaned (run {:dir root}
                         (script "squad_packet.sh")
                         "record"
                         "cave-topology"
                         "cleaner"
                         "wumpus-cave-clean"
                         "swarmforge-cleaner-001"
                         sha)
            reviewed (run {:dir root}
                          (script "squad_packet.sh")
                          "review"
                          "cave-topology"
                          "code"
                          "accepted"
                          "wumpus-cave-code-review"
                          "swarmforge-code-reviewer-001"
                          sha)
            status (run {:dir root}
                        (script "squad_packet.sh")
                        "status"
                        "cave-topology")
            packet (slurp (str (fs/path root ".squad/stories/cave-topology/packet")))]
        (is (str/includes? (:out implemented) "STATE: implemented"))
        (is (str/includes? (:out cleaned) "STATE: cleaned"))
        (is (str/includes? (:out reviewed) "STATE: code_reviewed"))
        (is (str/includes? (:out status) "IMPLEMENTATION:"))
        (is (str/includes? (:out status) "CLEANER:"))
        (is (str/includes? (:out status) "CODE_REVIEW: accepted"))
        (is (str/includes? packet "implementation_assignment: wumpus-cave-impl"))
        (is (str/includes? packet "cleaner_assignment: wumpus-cave-clean")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-packet-supersedes-stale-code-review-on-replacement-implementation
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md") "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md") "Story: cave topology and setup.\n")
      (let [sha (prepare-implementation-packet! root "wumpus" "cave-topology")
            fix-sha (do
                      (write-file (fs/path root "src/fix.clj") "(ns fix)\n")
                      (run {:dir root} "git" "add" "src/fix.clj")
                      (run {:dir root} "git" "commit" "-q" "-m" "Replacement implementation")
                      (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD"))))]
        (run {:dir root} (script "squad_packet.sh") "record" "cave-topology" "implementation" "impl-1" "master" sha)
        (run {:dir root} (script "squad_packet.sh") "record" "cave-topology" "cleaner" "clean-1" "master" sha)
        (run {:dir root} (script "squad_packet.sh") "review" "cave-topology" "code" "changes-requested" "review-1" "master" sha)
        (run {:dir root} (script "squad_packet.sh") "record" "cave-topology" "implementation" "impl-2" "master" fix-sha)
        (let [packet (slurp (str (fs/path root ".squad/stories/cave-topology/packet")))
              status (run {:dir root} (script "squad_packet.sh") "status" "cave-topology")]
          (is (str/includes? packet "implementation_assignment: impl-2"))
          (is (str/includes? packet "code_review_iterations: review-1=changes-requested"))
          (is (not (str/includes? packet "\ncode_review: changes-requested\n")))
          (is (str/includes? (:out status) "CLEANER_REVIEW_STATE: blocked"))
          (is (str/includes? (:out status) "CONSISTENCY: ok"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-packet-validation-reports-stale-active-batch-index
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md") "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md") "Story: cave topology and setup.\n")
      (let [sha (prepare-implementation-packet! root "wumpus" "cave-topology")]
        (run {:dir root} (script "squad_batch.sh") "create" "hardener" "hardener-old")
        (run {:dir root} (script "squad_batch.sh") "add" "hardener-old" "cave-topology" "code_reviewed" "review-old" "master" sha)
        (run {:dir root} (script "squad_batch.sh") "result" "hardener-old" "hardener-old-assignment" "master" sha)
        (let [validation (run {:dir root :ok? false}
                              (script "squad_packet.sh") "validate" "cave-topology")]
          (is (= 3 (:exit validation)))
          (is (str/includes? (:out validation) "CONSISTENCY: issues"))
          (is (str/includes? (:out validation) "ISSUE: stale-active-batch-index"))))
      (finally
        (fs/delete-tree root)))))
