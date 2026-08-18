(ns swarmforge.issues-b96-test
  "B96: analyst implementer batches of at most two same-level stories."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [squad-next :as next]
            [swarmforge.test-support :refer :all]))

(defn- impl-ready-packet [story]
  (str "story_id: " story "\n"
       "theme_id: wumpus\n"
       "story_approval: approved\n"
       "gherkin_path: features/" story ".feature\n"
       "gherkin_assignment: " story "-gherkin\n"
       "gherkin_sha: abcdef1111\n"
       "gherkin_review: accepted\n"
       "gherkin_review_target_sha: abcdef1111\n"
       "gherkin_approval: approved\n"
       "qa_procedure_path: qa/" story ".md\n"
       "qa_procedure_assignment: " story "-qa\n"
       "qa_procedure_sha: abcdef1111\n"
       "qa_procedure_review: accepted\n"
       "qa_procedure_review_target_sha: abcdef1111\n"
       "qa_procedure_approval: approved\n"
       "implementation_approval: approved\n"))

(deftest b96-two-independent-stories-form-one-batch
  (is (= [["alpha" "beta"]]
         (next/derive-implementer-batches ["alpha" "beta"] {}))))

(deftest b96-dependent-stories-stay-solo
  (is (= [["alpha"] ["beta"]]
         (next/derive-implementer-batches ["alpha" "beta"] {"beta" ["alpha"]}))))

(deftest b96-analyst-prompt-and-order-template-document-batches
  (let [analyst (slurp (str (fs/path repo-root "swarmforge/role-templates/analyst.prompt")))
        order (slurp (str (fs/path repo-root "swarmforge/templates/theme-implementation-order.md")))]
    (is (str/includes? analyst "task")
        "analyst emits module tasks")
    (is (str/includes? order "Implementer batches"))))

(deftest b96-residual-creates-one-implementer-for-two-ready-stories
  ;; Given two independent implementer-ready stories
  ;; When residual runs
  ;; Then one implementer assignment covers both ids
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root
                       "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "swarmforge/squad.conf") implementer-gate-conf)
      (write-file (fs/path root ".squad/themes/wumpus/implementation-order.md")
                  "# No multi-story implementer dependencies.\n")
      (write-nontrivial-checker! root)
      (write-file (fs/path root ".squad/stories/alpha/packet") (impl-ready-packet "alpha"))
      (write-file (fs/path root ".squad/stories/beta/packet") (impl-ready-packet "beta"))
      (doseq [story ["alpha" "beta"]]
        (write-file (fs/path root "stories" (str story ".md")) (str "Story " story ".\n")))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "TEMPLATE: implementer"))
        (is (or (str/includes? out "--batch-stories")
                (and (str/includes? out "alpha")
                     (str/includes? out "beta")
                     (str/includes? out "ASSIGNMENT:")))
            "B96: one implementer carries both story ids")
        (is (not (and (str/includes? out "alpha-implementation")
                      (str/includes? out "beta-implementation")))
            "B96: must not spawn one implementer per story"))
      (finally
        (fs/delete-tree root)))))

(deftest b96-merged-batch-implementer-stamps-both-packets
  ;; Given a merged implementer that covered two stories
  ;; When mechanical residual runs
  ;; Then both packets get the same implementation_sha
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root
                       "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "swarmforge/squad.conf") implementer-gate-conf)
      (write-file (fs/path root ".squad/themes/wumpus/implementation-order.md")
                  "# No multi-story implementer dependencies.\n")
      (write-nontrivial-checker! root)
      (write-file (fs/path root ".squad/stories/alpha/packet") (impl-ready-packet "alpha"))
      (write-file (fs/path root ".squad/stories/beta/packet") (impl-ready-packet "beta"))
      (write-file (fs/path root ".squad/assignments/alpha-beta-implementation/metadata")
                  (str "assignment_id: alpha-beta-implementation\n"
                       "theme_id: wumpus\n"
                       "story_id: alpha\n"
                       "template: implementer\n"
                       "batch_stories: alpha,beta\n"
                       "assignment_file: " root "/i.md\n"))
      (write-file (fs/path root ".squad/assignments/alpha-beta-implementation/status")
                  "assignment_id: alpha-beta-implementation\nstate: merged\n")
      (write-file (fs/path root ".squad/assignments/alpha-beta-implementation/accepted-merge")
                  (str "assignment_id: alpha-beta-implementation\n"
                       "state: merged\n"
                       "commit: 1111111111\n"
                       "merge_commit: abcdef1234\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh") "--apply-mechanical"))
            alpha (slurp (str (fs/path root ".squad/stories/alpha/packet")))
            beta (slurp (str (fs/path root ".squad/stories/beta/packet")))]
        (is (str/includes? out "record_merged_result"))
        (is (str/includes? alpha "implementation_sha: abcdef1234"))
        (is (str/includes? beta "implementation_sha: abcdef1234")))
      (finally
        (fs/delete-tree root)))))
