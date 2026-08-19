(ns swarmforge.redo-next-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [swarmforge.test-support :refer :all]))

(defn- write-roles! [root]
  (write-file (fs/path root ".swarmforge/roles.tsv")
              (str "squad-leader\tmaster\t" root
                   "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n")))

(deftest worker-handoff-tells-sl-to-merge
  ;; Given an implementer handed a SHA to SL
  ;; When squad_next runs
  ;; Then residual is SL merge of that SHA — not merger, not daemon accept-merge
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root
                       "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "impl-001\timpl-001\t" root "/.worktrees/impl-001"
                       "\tswarmforge-impl-001\tImplementer 001\tcodex\ttask\n"))
      (write-agent-status! root "impl-001" "handoff_sent")
      (write-file (fs/path root ".squad/assignments/cave-impl/metadata")
                  (str "assignment_id: cave-impl\n"
                       "story_id: cave-graph\n"
                       "template: implementer\n"
                       "assignment_file: " root "/instructions.md\n"))
      (write-file (fs/path root ".squad/assignments/cave-impl/status")
                  "assignment_id: cave-impl\nstate: merge_ready\n")
      (write-file (fs/path root ".swarmforge/handoffs/inbox/in_process/50_20260803T000000Z_000001_from_impl-001_to_squad-leader.handoff")
                  (str "type: git_handoff\n"
                       "to: squad-leader\n"
                       "from: impl-001\n"
                       "priority: 50\n"
                       "task: cave-impl\n"
                       "commit: abcdef1234\n"
                       "assignment: cave-impl\n"
                       "agent: impl-001\n"
                       "template: implementer\n"
                       "artifacts: none\n\n"
                       "merge_and_process impl-001 abcdef1234\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))
            residual (:out (run {:dir root} (script "squad_next.sh") "--residual-only"))]
        (is (not (str/includes? out "TEMPLATE: merger")))
        (is (not (str/includes? out "create-merger")))
        (is (not (str/includes? residual "wait_for_daemon_main_git")))
        (is (not (str/includes? residual "check_merge_readiness")))
        (is (str/includes? residual "accept-merge"))
        (is (or (str/includes? out "accept_merge")
                (str/includes? out "accept-merge"))))
      (finally
        (fs/delete-tree root)))))

(deftest merge-blocked-is-gone
  ;; Given leftover merge_blocked status from the old machine
  ;; When squad_next runs
  ;; Then it does not create a merger and does not treat merge_blocked as a live state
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/assignments/cave-impl/metadata")
                  (str "assignment_id: cave-impl\nstory_id: cave-graph\n"
                       "template: implementer\n"))
      (write-file (fs/path root ".squad/assignments/cave-impl/status")
                  "assignment_id: cave-impl\nstate: merge_blocked\n")
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (not (str/includes? out "TEMPLATE: merger")))
        (is (not (str/includes? out "create-merger"))))
      (finally
        (fs/delete-tree root)))))
