(ns swarmforge.code-review-and-stamps-test
  "Regression coverage for  (one CR),  (depth-2 accept pause),  (merger stamps)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [swarmforge.test-support :refer :all]))

(defn- write-roles! [root]
  (write-file (fs/path root ".swarmforge/roles.tsv")
              (str "squad-leader\tmaster\t" root
                   "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n")))

(defn- write-assignment!
  [root assignment-id {:keys [theme story template state merge-for
                              merge-commit resolved-by merge-file-state]}]
  (write-file (fs/path root ".squad/assignments" assignment-id "metadata")
              (str "assignment_id: " assignment-id "\n"
                   "theme_id: " (or theme "wumpus") "\n"
                   "story_id: " (or story "alpha") "\n"
                   "template: " template "\n"
                   (when merge-for (str "merge_for: " merge-for "\n"))
                   "assignment_file: " root "/" assignment-id ".md\n"))
  (write-file (fs/path root ".squad/assignments" assignment-id "status")
              (str "assignment_id: " assignment-id "\n"
                   "state: " (or state "merged") "\n"))
  (when merge-commit
    (write-file (fs/path root ".squad/assignments" assignment-id "accepted-merge")
                (str "assignment_id: " assignment-id "\n"
                     "state: merged\n"
                     "commit: " merge-commit "\n"
                     "merge_commit: " merge-commit "\n"
                     (when resolved-by (str "resolved_by: " resolved-by "\n")))))
  (when merge-file-state
    (write-file (fs/path root ".squad/assignments" assignment-id "merge")
                (str "assignment_id: " assignment-id "\n"
                     "state: " merge-file-state "\n"
                     "commit: abcdef1234\n"))))

(defn- write-in-process-handoff! [root assignment-id from]
  (write-file (fs/path root ".swarmforge/handoffs/inbox/in_process"
                       (str "50_from_" from "_to_squad-leader.handoff"))
              (str "type: git_handoff\n"
                   "to: squad-leader\n"
                   "from: " from "\n"
                   "priority: 50\n"
                   "task: " assignment-id "\n"
                   "commit: abcdef1234\n"
                   "assignment: " assignment-id "\n"
                   "agent: " from "\n"
                   "template: implementer\n\n"
                   "merge_and_process " from " abcdef1234\n")))

(deftest existing-code-review-assignment-blocks-second-create
  ;; Given: a story already has a code-reviewer assignment
  ;; When: a newer cleaner is recorded
  ;; Then: residual does not create code-review-r2
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\n"
                       "theme_id: wumpus\n"
                       "implementation_sha: abcdef2222\n"
                       "cleaner_sha: abcdef2222\n"
                       "cleaner_assignment: alpha-cleaner-r2\n"
                       "code_review_iterations: alpha-code-review=changes-requested\n"))
      (doseq [id ["alpha-cleaner" "alpha-cleaner-r2"]]
        (write-assignment! root id {:story "alpha" :template "cleaner" :state "merged"}))
      (write-assignment! root "alpha-code-review"
                         {:story "alpha" :template "code-reviewer" :state "merged"})
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (not (str/includes? out "alpha-code-review-r2")))
        (is (not (str/includes? out "TEMPLATE: code-reviewer"))))
      (finally
        (fs/delete-tree root)))))

(deftest resumes-product-accept-after-depth-2-resolves
  ;; Given: depth-2 merger is terminal-merged and a product assignment is merge_ready
  ;; When: squad_next inspects
  ;; Then: product accept-merge proceeds again
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-assignment! root "cave-impl-merge-merge"
                         {:story "cave" :template "merger" :state "merged"
                          :merge-for "cave-impl-merge"
                          :merge-commit "7931912abc"})
      (write-assignment! root "htw-move-player"
                         {:story "move-player" :template "implementer"
                          :state "merge_ready" :merge-file-state "merge_ready"})
      (write-in-process-handoff! root "htw-move-player" "implementer-001")
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "NEXT_ACTION: accept_merge"))
        (is (str/includes? out "htw-move-player"))
        (is (not (str/includes? out "wait_for_merge_recovery"))))
      (finally
        (fs/delete-tree root)))))

(deftest merger-resolved-senior-impl-stamps-member-packets
  ;; Given: senior-implementer reform landed via merger; no batch manifest
  ;;        and stories still have architecture changes-requested
  ;; When: mechanical residual runs
  ;; Then: each member packet gets senior_implementer_sha from the merged result
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (doseq [story ["alpha" "beta"]]
        (write-file (fs/path root ".squad/stories" story "packet")
                    (str "story_id: " story "\n"
                         "theme_id: wumpus\n"
                         "qa_sha: abcdef1234\n"
                         "architecture_review: changes-requested\n"
                         "architecture_review_assignment: wumpus-architecture\n")))
      (write-assignment! root "wumpus-architecture-fix"
                         {:story "batch" :template "senior-implementer"
                          :state "merged" :merge-commit "7931912abc"
                          :resolved-by "wumpus-architecture-fix-merge-merge"})
      (write-assignment! root "wumpus-architecture-fix-merge-merge"
                         {:story "batch" :template "merger" :state "merged"
                          :merge-for "wumpus-architecture-fix-merge"
                          :merge-commit "7931912abc"})
      (let [out (:out (run {:dir root} (script "squad_next.sh") "--apply-mechanical"))
            alpha (slurp (str (fs/path root ".squad/stories/alpha/packet")))
            beta (slurp (str (fs/path root ".squad/stories/beta/packet")))]
        (is (str/includes? out "record_merged_batch_result")
            "Merger resolution must project the product result")
        (is (str/includes? alpha "senior_implementer_sha: 7931912abc"))
        (is (str/includes? beta "senior_implementer_sha: 7931912abc")))
      (finally
        (fs/delete-tree root)))))

(deftest merger-resolved-implementer-stamps-story-packet
  ;; Given: a story implementer was merge_blocked then marked merged by a merger
  ;; When: mechanical residual runs
  ;; Then: the story packet records implementation_sha
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/stories/alpha/packet")
                  (str "story_id: alpha\n"
                       "theme_id: wumpus\n"
                       "story_approval: approved\n"
                       "gherkin_review: accepted\n"
                       "qa_procedure_review: accepted\n"
                       "implementation_approval: approved\n"))
      (write-assignment! root "alpha-implementation"
                         {:story "alpha" :template "implementer"
                          :state "merged" :merge-commit "844ffd5aaa"
                          :resolved-by "alpha-implementation-merge"})
      (let [out (:out (run {:dir root} (script "squad_next.sh") "--apply-mechanical"))
            packet (slurp (str (fs/path root ".squad/stories/alpha/packet")))]
        (is (str/includes? out "record_merged_result"))
        (is (str/includes? packet "implementation_sha: 844ffd5aaa")))
      (finally
        (fs/delete-tree root)))))
