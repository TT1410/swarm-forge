(ns swarmforge.recover-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [swarmforge.test-support :refer :all]))

(deftest squad-recover-classifies-untracked-work-as-dirty
  (let [root (tmp-dir)
        worktree (fs/path root ".worktrees/analyst-001")]
    (try
      (init-repo! root)
      (run {:dir root} "git" "worktree" "add" "-q" "-b" "analyst-001" (str worktree))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "analyst-001\tanalyst-001\t" worktree "\tswarmforge-analyst-001\tAnalyst 001\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "missing.sock") "\n"))
      (write-file (fs/path root ".squad/agents/analyst-001/metadata")
                  (str "agent_id: analyst-001\n"
                       "template: analyst\n"
                       "task_id: hunt-wumpus-analysis\n"
                       "worktree: " worktree "\n"
                       "session: swarmforge-analyst-001\n"))
      (write-file (fs/path worktree "stories/hunt-wumpus-001.md")
                  "Story: self-contained cave setup.\n")
      (let [result (run {:dir root}
                        (script "squad_recover.sh")
                        "analyst-001")]
        (is (str/includes? (:out result) "SESSION_LIVE: false"))
        (is (str/includes? (:out result) "DIRTY_FILES: 1"))
        (is (str/includes? (:out result) "DIRTY: ?? stories/hunt-wumpus-001.md"))
        (is (str/includes? (:out result) "RECOVERY_STATE: dirty_worktree"))
        (is (str/includes? (:out result) "Ask the user before retiring")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-recover-graces-recently-active-missing-worker
  (let [root (tmp-dir)
        worktree (fs/path root ".worktrees/analyst-001")]
    (try
      (init-repo! root)
      (run {:dir root} "git" "worktree" "add" "-q" "-b" "analyst-001" (str worktree))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "analyst-001\tanalyst-001\t" worktree "\tswarmforge-analyst-001\tAnalyst 001\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "missing.sock") "\n"))
      (write-file (fs/path root ".squad/agents/analyst-001/metadata")
                  (str "agent_id: analyst-001\n"
                       "template: analyst\n"
                       "task_id: hunt-wumpus-analysis\n"
                       "worktree: " worktree "\n"
                       "session: swarmforge-analyst-001\n"))
      (write-agent-status! root "analyst-001" "running")
      (let [result (run {:dir root
                         :env {"SWARMFORGE_SQUAD_RECOVERY_GRACE_SECONDS" "999999999"}}
                        (script "squad_recover.sh")
                        "analyst-001")]
        (is (str/includes? (:out result) "SESSION_LIVE: false"))
        (is (str/includes? (:out result) "DIRTY_FILES: 0"))
        (is (str/includes? (:out result) "COMMITS_AHEAD: 0"))
        (is (str/includes? (:out result) "HANDOFFS: 0"))
        (is (str/includes? (:out result) "RECOVERY_STATE: recently_active_no_work"))
        (is (str/includes? (:out result) "Do not reject or replace yet")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-recover-treats-list-sessions-match-as-live
  (let [root (tmp-dir)
        worktree (fs/path root ".worktrees/analyst-001")
        fakebin (fs/path root "fakebin")
        fake-tmux (fs/path fakebin "tmux")]
    (try
      (init-repo! root)
      (run {:dir root} "git" "worktree" "add" "-q" "-b" "analyst-001" (str worktree))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "analyst-001\tanalyst-001\t" worktree "\tswarmforge-analyst-001\tAnalyst 001\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "fake.sock") "\n"))
      (write-file (fs/path root ".squad/agents/analyst-001/metadata")
                  (str "agent_id: analyst-001\n"
                       "template: analyst\n"
                       "task_id: hunt-wumpus-analysis\n"
                       "worktree: " worktree "\n"
                       "session: swarmforge-analyst-001\n"))
      (write-file fake-tmux
                  (str "#!/usr/bin/env bash\n"
                       "set -euo pipefail\n"
                       "if [[ \"$*\" == *\"has-session\"* ]]; then exit 1; fi\n"
                       "if [[ \"$*\" == *\"list-sessions\"* ]]; then echo swarmforge-analyst-001; exit 0; fi\n"
                       "exit 1\n"))
      (run {:dir root} "chmod" "+x" (str fake-tmux))
      (let [result (run {:dir root
                         :env {"PATH" (str fakebin ":" (System/getenv "PATH"))}}
                        (script "squad_recover.sh")
                        "analyst-001")]
        (is (str/includes? (:out result) "SESSION_LIVE: true"))
        (is (str/includes? (:out result) "RECOVERY_STATE: live"))
        (is (str/includes? (:out result) "Do not retire or replace")))
      (finally
        (fs/delete-tree root)))))
