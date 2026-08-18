(ns swarmforge.issues-b68-b82-test
  "Regression coverage for issue batch B68–B82 (2026-08-17)."
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

(deftest b68-dashboard-request-outranks-pending-spawn
  (is (plane/residual-class-before? :dashboard-request :pending-spawn)
      "B68: product dashboard residual beats spawn wait")
  (is (not (plane/residual-class-before? :pending-spawn :dashboard-request))))

(deftest b73-merger-handoff-merge-blocked-not-counted
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".swarmforge" "roles.tsv")
                  (str "merger-001\tmaster\t" root
                       "\tswarmforge-merger-001\tMerger 001\tcodex\ttask\n"))
      (write-file (fs/path root ".squad" "agents" "merger-001" "status")
                  "state: handoff_sent\ndetail: done\n")
      (write-file (fs/path root ".squad" "agents" "merger-001" "metadata")
                  "template: merger\ntask_id: blocked-merge\n")
      (write-file (fs/path root ".squad" "assignments" "blocked-merge" "status")
                  "state: merge_blocked\ndetail: tracked checkout dirty\n")
      (with-redefs [daemon/skip-tmux-env? (constantly true)
                    daemon/active-state? (constantly true)]
        (is (false? (daemon/merger-holds-capacity-slot? root "merger-001")))
        (is (zero? (daemon/active-template-count root "merger"))
            "B73: handoff_sent+merge_blocked merger free for spawn"))
      (finally
        (fs/delete-tree root)))))

(deftest b75-dirt-detail-not-replayed
  (is (true? (assign/dirt-defer-detail? "tracked checkout dirty")))
  (is (false? (assign/dirt-defer-detail? "dry-run merge failed")))
  (let [root (tmp-dir)]
    (try
      (let [dir (fs/path root ".squad" "assignments" "a1")]
        (write-file (fs/path dir "merge")
                    "state: merge_blocked\ncommit: abcdef0123\ndetail: tracked checkout dirty\n")
        (is (nil? (assign/existing-merge-evaluation dir "abcdef0123"))
            "B75: dirt block is not durable merge evaluation"))
      (finally
        (fs/delete-tree root)))))

(deftest b76-persistent-yolo-roles
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
        "B76: TS codex is YOLO")))

(deftest b80-parent-batch-id-and-wif-resolve
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

(deftest b67-stage-labels-written-approved-in-process
  (is (= "written" (web/stage-label "story_recorded")))
  (is (= "approved" (web/stage-label "story_approved")))
  (is (= "in-process" (web/stage-label "specification_in_progress"))))

(deftest b79-qa-fail-subject-detection
  (with-redefs [packet/git-commit-subject
                (fn [_ _] "Record HTW batch QA failure")]
    (is (true? (packet/qa-commit-failed? "/tmp" "abc"))))
  (with-redefs [packet/git-commit-subject
                (fn [_ _] "Merge squad assignment htw-qa")]
    (is (false? (packet/qa-commit-failed? "/tmp" "abc")))))

(deftest b83-wif-theme-label-not-placeholder
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
            "B83: theme-scoped analyst shows theme title not Theme"))
      (let [a {"assignment_id" "cave-analysis"
               "story_id" "domain-cave-state"
               "theme_id" "htw"
               "template" "analyst"
               "state" "in_progress"}
            rows (web/work-in-flight-rows root [a] [])]
        (is (= "htw:domain-cave-state" (get (first rows) "story"))))
      (finally
        (fs/delete-tree root)))))

(deftest b85-analyst-is-singleton-template
  (is (contains? next/singleton-templates "analyst")
      "B85: analyst in singleton-templates")
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge" "squad.conf")
                  "max_active_template analyst 1\nmax_active_template implementer 3\n")
      (is (= 1 (cfg/squad-template-limit root "analyst"))
          "B85: max_active_template analyst 1")
      (finally
        (fs/delete-tree root)))))
