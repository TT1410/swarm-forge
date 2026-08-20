(ns swarmforge.bugs-remaining-test
  "Regression tests for bugs 4–9 and 11 (bug 10 deferred)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [squadd.web :as web]
            [swarmforge.test-support :refer :all]))

(defn- write-implementer-template! [root]
  (fs/create-dirs (fs/path root "swarmforge/role-templates"))
  (write-file (fs/path root "swarmforge/role-templates/implementer.prompt")
              "implement\n"))

(deftest assignment-reject-creates-dashboard-blocker
  ;; Given a rejected assignment (merge-conflict style park)
  ;; When the dashboard builds blockers
  ;; Then the rejection is a first-class blocker with durable artifacts
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-implementer-template! root)
      (write-file (fs/path root "theme.md") "Theme.\n")
      (write-file (fs/path root "module-map.md") minimal-module-map)
      (write-file (fs/path root "stories/cave.md") "Story.\n")
      (write-file (fs/path root "instructions.md") "Do work.\n")
      (write-file (fs/path root "rejection.md") "Merge conflict; park for operator.\n")
      (prepare-implementation-packet! root "wumpus" "cave")
      (run {:dir root} (script "squad_assign.sh") "create" "cave" "implementer"
           "cave-impl" "instructions.md")
      (let [reject (run {:dir root} (script "squad_assign.sh") "reject" "cave-impl" "rejection.md")
            blockers (web/assignment-blocker-state root)
            blocker (first (filter #(= "cave-impl" (get % "assignment_id")) blockers))]
        (is (str/includes? (:out reject) "STATE: rejected"))
        (is (str/includes? (:out reject) "BLOCKER:"))
        (is (fs/exists? (fs/path root ".squad/assignments/cave-impl/blocker")))
        (is (fs/exists? (fs/path root ".squad/assignments/cave-impl/blocker.md")))
        (is (fs/exists? (fs/path root ".squad/rejections/cave-impl.md")))
        (is (some? blocker))
        (is (= "assignment-rejection" (get blocker "kind"))))
      (finally
        (fs/delete-tree root)))))

(deftest approval-reject-creates-global-blocker-and-packet-mark
  ;; Given a pending story approval
  ;; When the user rejects it
  ;; Then a durable blocker exists and the packet records rejected
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md") "Theme.\n")
      (write-file (fs/path root "module-map.md") minimal-module-map)
      (write-file (fs/path root "stories/cave.md") "Story cave.\n")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root} (script "squad_packet.sh") "create" "cave" "cave-story" "master" sha)
        (run {:dir root} (script "squad_approval.sh") "request"
             "story__cave" "story" "cave" "story" "Approve story" "please review")
        (let [reject (run {:dir root} (script "squad_approval.sh") "reject"
                          "story__cave" "needs revision")
              blockers (web/global-blocker-state root)
              packet (slurp (str (fs/path root ".squad/stories/cave/packet")))]
          (is (str/includes? (:out reject) "STATE: rejected"))
          (is (str/includes? (:out reject) "BLOCKER:"))
          (is (fs/exists? (fs/path root ".squad/blockers/story__cave")))
          (is (fs/exists? (fs/path root ".squad/blockers/story__cave.md")))
          (is (some #(= "approval-rejection" (get % "kind")) blockers))
          (is (str/includes? packet "story_approval: rejected"))
          (is (str/includes? packet "needs revision"))))
      (finally
        (fs/delete-tree root)))))

(deftest merge-ready-is-idempotent-for-same-commit
  ;; Given an assignment already merge_ready for a commit
  ;; When merge-ready runs again for the same commit
  ;; Then it replays the prior outcome without a second dry-run event
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-implementer-template! root)
      (write-file (fs/path root "theme.md") "Theme.\n")
      (write-file (fs/path root "module-map.md") minimal-module-map)
      (write-file (fs/path root "stories/cave.md") "Story.\n")
      (write-file (fs/path root "instructions.md") "Do work.\n")
      (prepare-implementation-packet! root "wumpus" "cave")
      (run {:dir root} (script "squad_assign.sh") "create" "cave" "implementer"
           "cave-impl" "instructions.md")
      (let [commit (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (write-file (fs/path root "result.handoff")
                    (str "id: 1\nfrom: implementer-001\nto: squad-leader\npriority: 50\n"
                         "type: git_handoff\ntask: cave-impl\ncommit: " commit "\n"
                         "assignment: cave-impl\nagent: implementer-001\ntemplate: implementer\n"
                         "artifacts: none\n\nmerge_and_process implementer-001 " commit "\n"))
        (run {:dir root} (script "squad_assign.sh") "result" "cave-impl" "result.handoff")
        (let [first (run {:dir root} (script "squad_assign.sh") "merge-ready" "cave-impl")
              events-before (slurp (str (fs/path root ".squad/assignments/cave-impl/events.log")))
              second (run {:dir root} (script "squad_assign.sh") "merge-ready" "cave-impl")
              events-after (slurp (str (fs/path root ".squad/assignments/cave-impl/events.log")))]
          (is (str/includes? (:out first) "STATE: merge_ready"))
          (is (str/includes? (:out second) "STATE: merge_ready"))
          (is (= (count (re-seq #"\tmerge_ready\t" events-before))
                 (count (re-seq #"\tmerge_ready\t" events-after)))
              "second merge-ready must not append another merge_ready event")
          ;; Status must stay merge_ready even if something overwrote it mid-flight.
          (write-file (fs/path root ".squad/assignments/cave-impl/status")
                      "assignment_id: cave-impl\nstate: result_received\ndetail: drift\nupdated_at: now\n")
          (run {:dir root} (script "squad_assign.sh") "merge-ready" "cave-impl")
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/cave-impl/status")))
                             "state: merge_ready")
              "replay must re-sync status to merge_ready")))
      (finally
        (fs/delete-tree root)))))

(deftest merge-ready-and-result-respect-already-merged
  ;; Given accepted-merge exists but status was regressed to result_received
  ;; When merge-ready or result is attempted
  ;; Then merge-ready reports merged (and resyncs), result is refused
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/assignments/done-work/metadata")
                  "assignment_id: done-work\ntemplate: implementer\ntheme_id: wumpus\nstory_id: cave\n")
      (write-file (fs/path root ".squad/assignments/done-work/result")
                  "assignment_id: done-work\nfrom: implementer-001\ncommit: abcdef1234\n")
      (write-file (fs/path root ".squad/assignments/done-work/merge")
                  "assignment_id: done-work\nstate: merge_ready\ncommit: abcdef1234\ndetail: dry-run merge passed\n")
      (write-file (fs/path root ".squad/assignments/done-work/accepted-merge")
                  "assignment_id: done-work\nstate: merged\ncommit: abcdef1234\nmerge_commit: deadbeef01\n")
      (write-file (fs/path root ".squad/assignments/done-work/status")
                  "assignment_id: done-work\nstate: result_received\ndetail: drift\n")
      (let [mr (run {:dir root} (script "squad_assign.sh") "merge-ready" "done-work")]
        (is (str/includes? (:out mr) "STATE: merged"))
        (is (str/includes? (slurp (str (fs/path root ".squad/assignments/done-work/status")))
                           "state: merged")))
      (write-file (fs/path root "again.handoff")
                  (str "id: 1\nfrom: implementer-001\nto: squad-leader\npriority: 50\n"
                       "type: git_handoff\ntask: done-work\ncommit: abcdef1234\n"
                       "assignment: done-work\nagent: implementer-001\ntemplate: implementer\n"
                       "artifacts: none\n\nbody\n"))
      (let [res (run {:dir root :ok? false}
                     (script "squad_assign.sh") "result" "done-work" "again.handoff")]
        (is (= 2 (:exit res)))
        (is (str/includes? (:err res) "already merged")))
      (finally
        (fs/delete-tree root)))))

(deftest full-teardown-cancels-open-assignments-and-handoffs
  ;; Given open assignments and inbox handoffs
  ;; When full teardown runs
  ;; Then open work is cancelled and handoffs leave live queues
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/agents/impl-001/metadata")
                  "agent_id: impl-001\ntemplate: implementer\ntask_id: open-work\n")
      (write-file (fs/path root ".squad/agents/impl-001/status")
                  "state: running\ndetail: working\nupdated_at: 2026-08-10T00:00:00Z\n")
      (write-file (fs/path root ".squad/assignments/open-work/metadata")
                  "assignment_id: open-work\ntemplate: implementer\nstory_id: s1\n")
      (write-file (fs/path root ".squad/assignments/open-work/status")
                  "assignment_id: open-work\nstate: in_progress\nagent_id: impl-001\nupdated_at: 2026-08-10T00:00:00Z\n")
      (write-file (fs/path root ".squad/assignments/merge-hold/metadata")
                  "assignment_id: merge-hold\ntemplate: implementer\nstory_id: s1\n")
      (write-file (fs/path root ".squad/assignments/merge-hold/status")
                  "assignment_id: merge-hold\nstate: merge_blocked\nupdated_at: 2026-08-10T00:00:00Z\n")
      (write-file (fs/path root ".swarmforge/handoffs/inbox/new/50_test.handoff")
                  "type: git_handoff\nfrom: impl-001\nto: squad-leader\ntask: open-work\n\nbody\n")
      (run {:dir root} (script "stop_squadd.clj") (str root) "--full-teardown")
      (is (str/includes? (slurp (str (fs/path root ".squad/assignments/open-work/status")))
                         "state: cancelled"))
      (is (str/includes? (slurp (str (fs/path root ".squad/assignments/merge-hold/status")))
                         "state: cancelled")
          "leftover merge_blocked is cancelled, not held for recovery")
      (is (str/includes? (slurp (str (fs/path root ".squad/agents/impl-001/status")))
                         "state: retired"))
      (is (not (fs/exists? (fs/path root ".swarmforge/handoffs/inbox/new/50_test.handoff"))))
      (is (seq (fs/list-dir (fs/path root ".swarmforge/handoffs/inbox/cancelled"))))
      (finally
        (fs/delete-tree root)))))

(deftest leftover-merge-blocked-does-not-block-retire
  ;; Given leftover merge_blocked on an assignment
  ;; When squad_retire runs
  ;; Then retirement succeeds — merge_blocked is not a live recovery hold
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "implementer-001\timplementer-001\t" root "/.worktrees/implementer-001\t"
                       "swarmforge-implementer-001\tImpl 001\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/agents/implementer-001/metadata")
                  "agent_id: implementer-001\ntemplate: implementer\ntask_id: still-open\n")
      (write-file (fs/path root ".squad/agents/implementer-001/status")
                  "state: handoff_sent\ndetail: done\nupdated_at: 2026-08-10T00:00:00Z\n")
      (write-file (fs/path root ".squad/assignments/still-open/status")
                  "assignment_id: still-open\nstate: merge_blocked\n")
      (let [result (run {:dir root} (script "squad_retire.sh") "implementer-001")]
        (is (= 0 (:exit result)))
        (is (not (str/includes? (slurp (str (fs/path root ".swarmforge/roles.tsv")))
                                "implementer-001"))))
      (finally
        (fs/delete-tree root)))))

(deftest squadd-log-lines-are-timestamped-and-locked
  ;; Given concurrent log! calls
  ;; When lines are written
  ;; Then every line is complete and ISO-timestamped
  (let [root (tmp-dir)]
    (try
      (require 'squadd)
      (let [log! (resolve 'squadd/log!)
            log-file (fs/path root ".swarmforge/daemon/squadd.log")
            futs (mapv (fn [i]
                         (future
                           (dotimes [j 20]
                             (log! root (str "event-" i "-" j)))))
                       (range 4))]
        (doseq [f futs] @f)
        (let [lines (str/split-lines (slurp (str log-file)))]
          (is (= 80 (count lines)))
          (doseq [line lines]
            (is (re-find #"^\d{4}-\d{2}-\d{2}T" line)
                (str "expected timestamped line, got: " line))
            (is (re-find #"event-\d+-\d+$" line)
                (str "expected complete event token, got: " line)))))
      (finally
        (fs/delete-tree root)))))
