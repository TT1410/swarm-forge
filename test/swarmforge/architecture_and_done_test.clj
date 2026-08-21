(ns swarmforge.architecture-and-done-test
  "Regression coverage for  (arch after all QA),  (Done semantics),  (no map chores)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [squadd.web :as web]
            [swarmforge.test-support :refer :all]))

(defn- write-roles! [root]
  (write-file (fs/path root ".swarmforge/roles.tsv")
              (str "squad-leader\tmaster\t" root
                   "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n")))

(defn- qa-complete-packet [story]
  (str "story_id: " story "\n"
       "theme_id: wumpus\n"
       "story_approval: approved\n"
       "gherkin_path: features/" story ".feature\n"
       "gherkin_review: accepted\n"
       "gherkin_approval: approved\n"
       "qa_procedure_path: qa/" story ".md\n"
       "qa_procedure_review: accepted\n"
       "qa_procedure_approval: approved\n"
       "implementation_approval: approved\n"
       "implementation_sha: aaaaaaaaaa\n"
       "cleaner_sha: bbbbbbbbbb\n"
       "code_review: accepted\n"
       "code_review_sha: cccccccccc\n"
       "hardener_sha: dddddddddd\n"
       "qa_sha: eeeeeeeeee\n"
       "qa_approval: approved\n"))

(defn- coding-packet [story]
  (str "story_id: " story "\n"
       "theme_id: wumpus\n"
       "story_approval: approved\n"
       "gherkin_path: features/" story ".feature\n"
       "gherkin_review: accepted\n"
       "gherkin_approval: approved\n"
       "qa_procedure_path: qa/" story ".md\n"
       "qa_procedure_review: accepted\n"
       "qa_procedure_approval: approved\n"
       "implementation_approval: approved\n"
       "implementation_sha: aaaaaaaaaa\n"
       "cleaner_sha: bbbbbbbbbb\n"
       "code_review: accepted\n"
       "code_review_sha: cccccccccc\n"))

(deftest architect-takes-ready-stories-without-waiting-for-siblings
  ;; Given two stories and only one has finished QA
  ;; When residual runs
  ;; Then architect may take the ready story; it does not wait for the sibling
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "swarmforge/squad.conf")
                  (str implementer-gate-conf
                       "approval_required qa false\n"
                       "approval_required architecture false\n"))
      (write-file (fs/path root ".squad/stories/alpha/packet") (qa-complete-packet "alpha"))
      (write-file (fs/path root ".squad/stories/beta/packet") (coding-packet "beta"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (or (str/includes? out "TEMPLATE: architect")
                (str/includes? out "architecture")))
        (is (not (str/includes? out "TEMPLATE: senior-implementer"))))
      (finally
        (fs/delete-tree root)))))

(deftest architect-after-all-stories-qa
  ;; Given every project story is qa_approved
  ;; When residual runs
  ;; Then an architecture batch/assignment becomes a candidate
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "swarmforge/squad.conf")
                  (str implementer-gate-conf
                       "approval_required qa false\n"
                       "approval_required architecture false\n"))
      (write-nontrivial-checker! root)
      (write-file (fs/path root ".squad/stories/alpha/packet") (qa-complete-packet "alpha"))
      (write-file (fs/path root ".squad/stories/beta/packet") (qa-complete-packet "beta"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (or (str/includes? out "TEMPLATE: architect")
                (str/includes? out "BATCH_KIND: architecture")
                (str/includes? out "architecture"))
            "Full QA set opens the project architecture pass"))
      (finally
        (fs/delete-tree root)))))

(deftest story-board-done-after-si-or-architect-without-recs
  ;; Given packet states after QA
  ;; Then QA/architect stay Finalizing; Done is SI returned or leftover final_approved
  (is (= "finalizing" (web/board-column "qa_approved")))
  (is (= "finalizing" (web/board-column "architecture_reviewed")))
  (is (= "finalizing" (web/board-column "architecture_approved")))
  (is (= "done" (web/board-column "architecture_revision_returned")))
  (is (= "done" (web/board-column "senior_implementer_returned")))
  (is (= "done" (web/board-column "final_approved")))
  (is (= "coding" (web/board-column "code_review_approved")))
  (is (= "finalizing" (web/board-column "hardening_approved")))
  (is (= "finalizing" (web/board-column "qa_returned"))))

(deftest senior-impl-assignment-omits-map-recommendations
  ;; Given an architecture review that includes Module Map Recommendations
  ;; When a senior-implementer assignment is created
  ;; Then the work order does not treat map bullets as required findings
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (fs/create-dirs (fs/path root "swarmforge/role-templates"))
      (write-file (fs/path root "swarmforge/role-templates/senior-implementer.prompt")
                  "senior\n")
      (write-file (fs/path root "theme.md") "Hunt the Wumpus.\n")
      (write-file (fs/path root "module-map.md") minimal-module-map)
      (write-file (fs/path root "stories/cave-topology.md") "Story: cave.\n")
      (write-file (fs/path root "reviews/wumpus-architecture-review.md")
                  (str "# Architecture review\n\n"
                       "## Requested change\n\n"
                       "Fix IO randomness so acceptance is deterministic.\n\n"
                       "## Module Map Recommendations\n\n"
                       "Add HHG use case to the module map.\n"))
      (run {:dir root}
           (script "squad_assign.sh")
           "create" "batch" "senior-implementer"
           "wumpus-architecture-fix" "--auto-instructions")
      (let [text (slurp (str (fs/path root ".squad/assignments/wumpus-architecture-fix/assignment.md")))]
        (is (str/includes? text "Fix IO randomness")
            "code findings stay in the work order")
        (is (not (str/includes? text "Add HHG use case to the module map."))
            "Map bullets are not assigned work")
        (is (or (str/includes? text "skip")
                (str/includes? text "Module Map Recommendations"))
            "Assignment tells senior-impl to skip map chores"))
      (finally
        (fs/delete-tree root)))))
