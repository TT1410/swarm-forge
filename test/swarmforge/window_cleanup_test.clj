(ns swarmforge.window-cleanup-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [swarmforge.test-support :refer :all]))

(deftest window-watchdog-rewrites-window-state-and-id-list
  (let [root (tmp-dir)
        state-file (fs/path root "windows.tsv")
        ids-file (fs/path root "window-ids")]
    (try
      (write-file state-file
                  (str "1\told-a\tswarmforge-coder\tSwarmForge Coder\n"
                       "2\told-b\tswarmforge-cleaner\tSwarmForge Cleaner\n"))
      (write-file ids-file "old-a\nold-b\n")
      (run {:dir root} (script "swarm_window_watchdog.clj") "--rewrite-window-id" "windows.tsv" "window-ids" "2" "new-b")
      (let [state (slurp (str state-file))
            ids (slurp (str ids-file))]
        (is (str/includes? state "1\told-a\tswarmforge-coder\tSwarmForge Coder"))
        (is (str/includes? state "2\tnew-b\tswarmforge-cleaner\tSwarmForge Cleaner"))
        (is (= "old-a\nnew-b\n" ids)))
      (finally
        (fs/delete-tree root)))))

(deftest window-watchdog-cleanup-kills-transient-only-sessions
  (let [root (tmp-dir)
        sock (str (fs/path root "swarm.sock"))
        state-file (fs/path root ".swarmforge/windows.tsv")
        ids-file (fs/path root ".swarmforge/window-ids")]
    (try
      (write-file state-file
                  "1\tmissing-cleanup-window\tswarmforge-squad-leader\tSquad Leader\n")
      (write-file ids-file "missing-cleanup-window\n")
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "analyst-001\tanalyst-001\t" root "/.worktrees/analyst-001\tswarmforge-analyst-001\tAnalyst 001\tcodex\ttask\n"))
      (run {:dir root} "tmux" "-S" sock "new-session" "-d" "-s" "swarmforge-squad-leader" "sleep" "120")
      (run {:dir root} "tmux" "-S" sock "new-session" "-d" "-s" "swarmforge-analyst-001" "sleep" "120")
      (let [result (run {:dir root}
                        (script "swarm_window_watchdog.clj")
                        (str state-file)
                        (str ids-file)
                        "1"
                        sock
                        (str root)
                        "none")]
        (is (= 0 (:exit result)))
        (is (not= 0 (:exit (run {:dir root :ok? false}
                                "tmux" "-S" sock "has-session" "-t" "swarmforge-squad-leader"))))
        (is (not= 0 (:exit (run {:dir root :ok? false}
                                "tmux" "-S" sock "has-session" "-t" "swarmforge-analyst-001"))))
        (is (not= 0 (:exit (run {:dir root :ok? false}
                                "tmux" "-S" sock "list-sessions")))))
      (finally
        (run {:dir root :ok? false} "tmux" "-S" sock "kill-server")
        (fs/delete-tree root)))))

(deftest swarm-cleanup-tolerates-missing-runtime-state
  (let [root (tmp-dir)
        ids-file (fs/path root ".swarmforge/window-ids")]
    (try
      (write-file ids-file "window-a\nwindow-b\n")
      (let [result (run {:dir root
                         :env {"SWARMFORGE_TERMINAL_BACKEND" "none"}}
                        (str (fs/path scripts-dir "swarm-cleanup.sh"))
                        "/tmp/nonexistent.sock"
                        (str ids-file))]
        (is (= 0 (:exit result)))
        (is (= "" (:err result))))
      (finally
        (fs/delete-tree root)))))

(deftest swarm-cleanup-kills-unlisted-tmux-sessions-on-socket
  (let [root (tmp-dir)
        sock (str (fs/path root "swarm.sock"))
        ids-file (fs/path root ".swarmforge/window-ids")]
    (try
      (write-file ids-file "")
      (run {:dir root} "tmux" "-S" sock "new-session" "-d" "-s" "swarmforge-listed" "sleep" "120")
      (run {:dir root} "tmux" "-S" sock "new-session" "-d" "-s" "swarmforge-orphan-worker" "sleep" "120")
      (let [result (run {:dir root
                         :env {"SWARMFORGE_TERMINAL_BACKEND" "none"}}
                        (str (fs/path scripts-dir "swarm-cleanup.sh"))
                        sock
                        (str ids-file)
                        "swarmforge-listed")]
        (is (= 0 (:exit result)))
        (is (not= 0 (:exit (run {:dir root :ok? false}
                                "tmux" "-S" sock "has-session" "-t" "swarmforge-listed"))))
        (is (not= 0 (:exit (run {:dir root :ok? false}
                                "tmux" "-S" sock "has-session" "-t" "swarmforge-orphan-worker"))))
        (is (not= 0 (:exit (run {:dir root :ok? false}
                                "tmux" "-S" sock "list-sessions")))))
      (finally
        (run {:dir root :ok? false} "tmux" "-S" sock "kill-server")
        (fs/delete-tree root)))))

(defn close-swarm []
  (str (fs/path repo-root "close-swarm")))

(deftest close-swarm-reports-when-no-swarm-state
  (let [root (tmp-dir)]
    (try
      (let [result (run {:dir root :ok? false
                         :env {"SWARMFORGE_TERMINAL_BACKEND" "none"}}
                        (close-swarm)
                        (str root))]
        (is (not= 0 (:exit result)))
        (is (str/includes? (str (:err result) (:out result)) "No SwarmForge swarm")))
      (finally
        (fs/delete-tree root)))))

(deftest close-swarm-prefers-target-project-cleanup-script
  (let [root (tmp-dir)
        cleanup (fs/path root "swarmforge/scripts/swarm-cleanup.sh")
        marker (fs/path root "target-cleanup-used")]
    (try
      (write-file (fs/path root ".swarmforge/tmux-socket") "/tmp/nonexistent.sock\n")
      (write-file (fs/path root ".swarmforge/window-ids") "")
      (write-file cleanup
                  (str "#!/usr/bin/env zsh\n"
                       "set -euo pipefail\n"
                       "touch " marker "\n"))
      (run {:dir root} "chmod" "+x" (str cleanup))
      (let [result (run {:dir root
                         :env {"SWARMFORGE_TERMINAL_BACKEND" "none"}}
                        (close-swarm)
                        (str root))]
        (is (= 0 (:exit result)))
        (is (fs/exists? marker)))
      (finally
        (fs/delete-tree root)))))

(deftest close-swarm-kills-tmux-sessions-and-stops-daemon
  (let [root (tmp-dir)
        sock (str (fs/path root "swarm.sock"))
        squadd-pid-file (fs/path root ".swarmforge/daemon/squadd.pid")
        pid-file (fs/path root ".swarmforge/daemon/handoffd.pid")
        squadd (.start (java.lang.ProcessBuilder. ["sleep" "120"]))
        daemon (.start (java.lang.ProcessBuilder. ["sleep" "120"]))
        squadd-pid (str (.pid squadd))
        pid (str (.pid daemon))]
    (try
      (init-repo! root)
      (let [code-reviewer-worktree (fs/path root ".worktrees/code-reviewer-001")
            merger-worktree (fs/path root ".worktrees/merger-003")]
        (run {:dir root} "git" "worktree" "add" "-q" "-b" "swarmforge-code-reviewer-001" (str code-reviewer-worktree) "HEAD")
        (run {:dir root} "git" "worktree" "add" "-q" "-b" "swarmforge-merger-003" (str merger-worktree) "HEAD")
        (fs/create-dirs (fs/path root ".worktrees/orphan-001"))
        (write-file (fs/path root ".worktrees/orphan-001/leftover.txt") "leftover\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
      (write-file (fs/path root ".swarmforge/sessions.tsv")
                  (str "1\tcoder\tswarmforge-coder\tCoder\tcodex\n"
                       "2\tcleaner\tswarmforge-cleaner\tCleaner\tcodex\n"))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "code-reviewer-001\tcode-reviewer-001\t" root "/.worktrees/code-reviewer-001\tswarmforge-code-reviewer-001\tCode Reviewer 001\tcodex\ttask\n"
                       "merger-003\tmerger-003\t" root "/.worktrees/merger-003\tswarmforge-merger-003\tMerger 003\tcodex\ttask\n"
                       "code-reviewer-085\tcode-reviewer-085\t" root "/.worktrees/code-reviewer-085\tswarmforge-code-reviewer-085\tCR 085\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/agents/squad-leader/status")
                  "state: running\ndetail: active\nupdated_at: 2026-08-03T00:00:00Z\n")
      (write-file (fs/path root ".squad/agents/code-reviewer-001/status")
                  "state: running\ndetail: active\nupdated_at: 2026-08-03T00:00:00Z\n")
      (write-file (fs/path root ".squad/agents/merger-003/status")
                  "state: handoff_sent\ndetail: waiting\nupdated_at: 2026-08-03T00:00:00Z\n")
      (write-file (fs/path root ".squad/agents/merger-003/metadata")
                  "task_id: cave-impl-merge-merge\n")
      (write-file (fs/path root ".squad/assignments/cave-impl-merge-merge/status")
                  "state: merge_blocked\n")
      (write-file (fs/path root ".squad/agents/code-reviewer-085/status")
                  "state: running\ndetail: reviewing\nupdated_at: 2026-08-03T00:00:00Z\n")
      (write-file (fs/path root ".squad/agents/code-reviewer-086/status")
                  "state: running\ndetail: reviewing\nupdated_at: 2026-08-03T00:00:00Z\n")
      (write-file (fs/path root ".swarmforge/window-ids") "win-a\nwin-b\n")
      (write-file squadd-pid-file (str squadd-pid "\n"))
      (write-file pid-file (str pid "\n"))
      (write-file (fs/path root ".swarmforge/daemon/squad-web-url")
                  "http://127.0.0.1:9999/\n")
      (run {:dir root} "tmux" "-S" sock "new-session" "-d" "-s" "swarmforge-coder" "sleep" "120")
      (run {:dir root} "tmux" "-S" sock "new-session" "-d" "-s" "swarmforge-cleaner" "sleep" "120")
      (run {:dir root} "tmux" "-S" sock "new-session" "-d" "-s" "swarmforge-code-reviewer-001" "sleep" "120")
      (let [result (run {:dir root
                         :env {"SWARMFORGE_TERMINAL_BACKEND" "none"}}
                        (close-swarm)
                        (str root))]
        (is (= 0 (:exit result)))
        (is (not= 0 (:exit (run {:dir root :ok? false}
                                "tmux" "-S" sock "has-session" "-t" "swarmforge-coder"))))
        (is (not= 0 (:exit (run {:dir root :ok? false}
                                "tmux" "-S" sock "has-session" "-t" "swarmforge-cleaner"))))
        (is (not= 0 (:exit (run {:dir root :ok? false}
                                "tmux" "-S" sock "has-session" "-t" "swarmforge-code-reviewer-001"))))
        (is (not (fs/exists? pid-file)))
        (is (not (fs/exists? squadd-pid-file)))
        (is (not (fs/exists? (fs/path root ".swarmforge/daemon/squad-web-url"))))
        (is (not (fs/exists? (fs/path root ".worktrees/code-reviewer-001"))))
        (is (not (fs/exists? (fs/path root ".worktrees/merger-003")))
            "leftover merge_blocked worktrees are not preserved")
        (is (not (str/includes? (:out result) "PRESERVED_FOR_RECOVERY:"))
            "teardown does not hold merge_blocked for recovery")
        (is (not (fs/exists? (fs/path root ".worktrees/orphan-001"))))
        (is (not (git-worktree-registered? root (fs/path root ".worktrees/code-reviewer-001"))))
        (is (not (git-worktree-registered? root (fs/path root ".worktrees/merger-003"))))
        (is (not (git-branch-exists? root "swarmforge-code-reviewer-001")))
        (is (not (git-branch-exists? root "swarmforge-merger-003")))
        (is (= [(str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask")]
               (str/split-lines (slurp (str (fs/path root ".swarmforge/roles.tsv"))))))
        (is (str/includes? (slurp (str (fs/path root ".squad/agents/squad-leader/status")))
                           "state: retired"))
        (is (str/includes? (slurp (str (fs/path root ".squad/agents/code-reviewer-001/status")))
                           "state: retired"))
        (is (str/includes? (slurp (str (fs/path root ".squad/agents/merger-003/status")))
                           "state: retired"))
        (is (str/includes? (slurp (str (fs/path root ".squad/agents/code-reviewer-085/status")))
                           "state: retired"))
        (is (str/includes? (slurp (str (fs/path root ".squad/agents/code-reviewer-086/status")))
                           "state: retired"))
        (is (false? (.isAlive squadd)))
        (is (false? (.isAlive daemon))))
      (finally
        (when (.isAlive squadd)
          (.destroyForcibly squadd))
        (when (.isAlive daemon)
          (.destroyForcibly daemon))
        (run {:dir root :ok? false} "tmux" "-S" sock "kill-server")
        (fs/delete-tree root)))))
