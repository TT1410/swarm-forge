(ns swarmforge.residual-priority-test
  "Regression coverage for dashboard residual, dirt deferral, YOLO, and WIF labels."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [squad-config :as cfg]
            [squad-control-plane :as plane]
            [squad-assign :as assign]
            [squad-next :as next]
            [squad-packet :as packet]
            [squadd :as daemon]
            [squadd.web :as web]
            [swarmforge :as forge]
            [swarmforge.test-support :refer :all]))

(deftest dashboard-request-outranks-pending-spawn
  (is (plane/residual-class-before? :dashboard-request :pending-spawn)
      "Product dashboard residual beats spawn wait")
  (is (not (plane/residual-class-before? :pending-spawn :dashboard-request))))

(deftest dirt-detail-not-replayed
  (is (true? (assign/dirt-defer-detail? "tracked checkout dirty")))
  (is (false? (assign/dirt-defer-detail? "dry-run merge failed")))
  (let [root (tmp-dir)]
    (try
      (let [dir (fs/path root ".squad" "assignments" "a1")]
        (write-file (fs/path dir "merge")
                    "state: merge_blocked\ncommit: abcdef0123\ndetail: tracked checkout dirty\n")
        (is (nil? (assign/existing-merge-evaluation dir "abcdef0123"))
            "Dirt block is not durable merge evaluation"))
      (finally
        (fs/delete-tree root)))))

(deftest persistent-yolo-roles
  (is (true? (forge/persistent-yolo-role? "squad-leader")))
  (is (true? (forge/persistent-yolo-role? "troubleshooter")))
  (is (false? (forge/persistent-yolo-role? "implementer")))
  (let [cmd (forge/codex-launch-command
             nil
             {:role "troubleshooter"
              :worktree-path "/tmp/proj"
              :display-name "TS"
              :extra-args ""}
             (fs/path "/tmp/prompt.md"))]
    (is (str/includes? cmd "--dangerously-bypass-approvals-and-sandbox")
        "TS codex is YOLO")))

(deftest parent-batch-id-and-wif-resolve
  (is (= "htw-architecture" (web/parent-batch-id "htw-architecture-fix")))
  (is (= "htw-qa" (web/parent-batch-id "htw-qa-r2")))
  (let [batches [{"batch_id" "htw-architecture"
                  "members" ["room-perception"]
                  "batch_kind" "architecture"}]
        batch-by-id (into {} (map (fn [b] [(get b "batch_id") b]) batches))
        a {"assignment_id" "htw-architecture-fix"
           "batch_id" "htw-architecture-fix"
           "story_id" "batch"
           "template" "senior-implementer"
           "state" "in_progress"}
        b (web/resolve-wif-batch batch-by-id a)]
    (is (= ["room-perception"] (get b "members")))
    (let [rows (web/work-in-flight-rows [a] batches)]
      (is (= ["room-perception"] (get (first rows) "story_ids")))
      (is (true? (get (first rows) "is_batch"))))))

(deftest stage-labels-are-short-pills
  (is (= "plan" (web/stage-label "story_recorded")))
  (is (= "plan" (web/stage-label "story_approved")))
  (is (= "gherkin" (web/stage-label "specification_in_progress"))))

(deftest qa-fail-subject-detection
  (with-redefs [packet/git-commit-subject
                (fn [_ _] "Record HTW batch QA failure")]
    (is (true? (packet/qa-commit-failed? "/tmp" "abc"))))
  (with-redefs [packet/git-commit-subject
                (fn [_ _] "Merge squad assignment htw-qa")]
    (is (false? (packet/qa-commit-failed? "/tmp" "abc")))))

(deftest wif-theme-label-not-placeholder
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".squad" "themes" "htw" "theme.md")
                  "# Hunt the Wumpus\n\nScope text.\n")
      (let [a {"assignment_id" "htw-analysis"
               "story_id" "theme"
               "scope" "theme"
               "theme_id" "htw"
               "template" "analyst"
               "state" "in_progress"}
            rows (web/work-in-flight-rows root [a] [])]
        (is (= "Hunt the Wumpus" (get (first rows) "story"))
            "Theme-scoped analyst shows theme title not Theme"))
      (let [a {"assignment_id" "cave-analysis"
               "story_id" "domain-cave-state"
               "theme_id" "htw"
               "template" "analyst"
               "state" "in_progress"}
            rows (web/work-in-flight-rows root [a] [])]
        (is (= "htw:domain-cave-state" (get (first rows) "story"))))
      (finally
        (fs/delete-tree root)))))

(deftest singleton-templates-come-from-config
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge" "squad.conf")
                  "max_active_template hardener 1\nmax_active_template analyst 3\n")
      (is (contains? (cfg/singleton-templates root) "hardener"))
      (is (not (contains? (cfg/singleton-templates root) "analyst")))
      (is (= 3 (cfg/squad-template-limit root "analyst")))
      (finally
        (fs/delete-tree root)))))
