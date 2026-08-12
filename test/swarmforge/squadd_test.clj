(ns swarmforge.squadd-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [swarmforge.test-support :refer :all]))

(deftest squadd-throttles-stale-heartbeat-notifications
  (let [root (tmp-dir)
        bin (fs/path root "bin")
        fake-tmux (fs/path bin "tmux")
        fake-state (fs/path root "fake-tmux-state")]
    (try
      (init-repo! root)
      (fs/create-dirs bin)
      (write-file fake-tmux
                  (str "#!/usr/bin/env sh\n"
                       "mkdir -p \"$FAKE_TMUX_STATE\"\n"
                       "cmd=\"$3\"\n"
                       "case \"$cmd\" in\n"
                       "  send-keys)\n"
                       "    count_file=\"$FAKE_TMUX_STATE/count\"\n"
                       "    count=0\n"
                       "    test -f \"$count_file\" && read count < \"$count_file\"\n"
                       "    count=$((count + 1))\n"
                       "    echo \"$count\" > \"$count_file\"\n"
                       "    case \"$*\" in\n"
                       "      *\"Squad status needs attention\"*|*\"run squad_next.sh\"*) touch \"$FAKE_TMUX_STATE/notify-$count\" ;;\n"
                       "    esac\n"
                       "    exit 0\n"
                       "    ;;\n"
                       "  *) exit 0 ;;\n"
                       "esac\n"))
      (run {:dir root} "chmod" "+x" (str fake-tmux))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "analyst-001\tanalyst-001\t" root "/.worktrees/analyst-001\tswarmforge-analyst-001\tAnalyst 001\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket")
                  "/tmp/swarmforge-test.sock\n")
      (write-agent-status! root "analyst-001" "running" "2000-01-01T00:00:00Z")
      (write-file (fs/path root ".squad/agents/analyst-001/liveness")
                  (str "state: running_pane_idle\n"
                       "observed_at: 2000-01-01T00:00:00Z\n"
                       "pane_changed: false\n"
                       "pane_hash: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n"
                       "last_10_lines:\n"))
      (run {:dir root :ok? false
	            :env {"PATH" (str bin ":" (System/getenv "PATH"))
	                  "FAKE_TMUX_STATE" (str fake-state)
	                  "SWARMFORGE_SQUAD_STALE_SECONDS" "1"
	                  "SWARMFORGE_SQUAD_STATUS_NOTIFY_COOLDOWN_SECONDS" "999999"
	                  "SWARMFORGE_SQUADD_WEB" "0"}}
           "sh" "-c"
           (str "bb " (script "squadd.clj") " " root " >/dev/null 2>&1 &"))
      (Thread/sleep 6500)
      (let [stop (run {:dir root} (script "stop_squadd.clj") (str root))
            daemon-log (slurp (str (fs/path root ".swarmforge/daemon/squadd.log")))
            notify-markers (if (fs/exists? fake-state)
                             (filter #(str/starts-with? (fs/file-name %) "notify-")
                                     (fs/list-dir fake-state))
                             [])]
        (is (= 0 (:exit stop)))
        (is (= 1 (count notify-markers)))
        (is (= 1 (count (filter #(str/includes? % " status-notified squad-leader ") (str/split-lines daemon-log)))))
        (is (str/includes? daemon-log "status-notify-throttled")))
      (finally
        (run {:dir root :ok? false} (script "stop_squadd.clj") (str root))
        (fs/delete-tree root)))))

(deftest squadd-processes-status-and-daemon-owned-spawn-requests
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  "window squad-leader codex master task\n")
      (write-file (fs/path root "swarmforge/roles/squad-leader.prompt")
                  "leader\n")
      (write-file (fs/path root "swarmforge/role-templates/analyst.prompt")
                  "specify\n")
      (write-file (fs/path root "assignment.md")
                  "Find the original rules.\n")
      (run {:dir root} (script "swarmforge.clj") "--test-parse" (str root))
      (let [request (run {:dir root}
                         (script "squad_spawn_request.sh")
                         "analyst"
                         "wumpus-theme"
                         "assignment.md")]
        (is (str/includes? (:out request) "STATE: requested"))
        (is (= 1 (count (fs/list-dir (fs/path root ".squad/spawn-requests/new"))))))
      (let [once (run {:dir root
                       :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"
                             "SWARMFORGE_SQUADD_SKIP_TMUX" "1"}}
                      (script "squadd.sh")
                      "--once"
                      "--no-notify"
                      (str root))
            roles (str/split-lines (slurp (str (fs/path root ".swarmforge/roles.tsv"))))
            completed (fs/list-dir (fs/path root ".squad/spawn-requests/completed"))]
        (is (str/includes? (:out once) "SQUAD_STATUS_OK"))
        (is (= 2 (count roles)))
        (is (some #(str/starts-with? % "analyst-001\t") roles))
        (is (some #(str/ends-with? (fs/file-name %) ".request") completed))
        (is (some #(str/ends-with? (fs/file-name %) ".request.out") completed))
        (is (fs/exists? (fs/path root ".squad/agents/analyst-001/status")))
        (is (str/includes? (slurp (str (fs/path root ".swarmforge/daemon/squadd.log")))
                           "spawn-request-completed")))
      (finally
        (fs/delete-tree root)))))

(deftest squadd-defers-spawn-requests-when-transient-slots-are-full
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "analyst-001\tanalyst-001\t" root "/.worktrees/analyst-001\tswarmforge-analyst-001\tAnalyst 001\tcodex\ttask\n"
                       "analyst-002\tanalyst-002\t" root "/.worktrees/analyst-002\tswarmforge-analyst-002\tAnalyst 002\tcodex\ttask\n"
                       "implementer-001\timplementer-001\t" root "/.worktrees/implementer-001\tswarmforge-implementer-001\tImplementer 001\tcodex\ttask\n"
                       "code-reviewer-001\tcode-reviewer-001\t" root "/.worktrees/code-reviewer-001\tswarmforge-code-reviewer-001\tCode Reviewer 001\tcodex\ttask\n"
                       "qa-001\tqa-001\t" root "/.worktrees/qa-001\tswarmforge-qa-001\tQa 001\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket")
                  "/tmp/swarmforge-test.sock\n")
      (write-file (fs/path root "swarmforge/squad.conf")
                  "max_transient_agents 5\n")
      (doseq [agent-id ["analyst-001" "analyst-002" "implementer-001" "code-reviewer-001" "qa-001"]]
        (write-agent-status! root agent-id "running"))
      (write-file (fs/path root "swarmforge/role-templates/code-reviewer.prompt")
                  "review\n")
      (write-file (fs/path root "assignment.md")
                  "Review the story implementation.\n")
      (run {:dir root}
           (script "squad_spawn_request.sh")
           "code-reviewer"
           "next-review"
           "assignment.md")
      (let [once (run {:dir root
                       :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"
                             "SWARMFORGE_SQUADD_SKIP_TMUX" "1"}}
                      (script "squadd.sh")
                      "--once"
                      "--no-notify"
                      (str root))
            roles (str/split-lines (slurp (str (fs/path root ".swarmforge/roles.tsv"))))
            requests (fs/list-dir (fs/path root ".squad/spawn-requests/new"))
            daemon-log (slurp (str (fs/path root ".swarmforge/daemon/squadd.log")))]
        (is (str/includes? (:out once) "SQUAD_STATUS_OK"))
        (is (= 6 (count roles)))
        (is (= 1 (count requests)))
        (is (str/includes? daemon-log "spawn-request-deferred"))
        (is (str/includes? daemon-log "capacity-full")))
      (finally
        (fs/delete-tree root)))))

(deftest squadd-defers-spawn-requests-when-singleton-template-is-active
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "qa-001\tqa-001\t" root "/.worktrees/qa-001\tswarmforge-qa-001\tQa 001\tcodex\ttask\n"
                       "architect-001\tarchitect-001\t" root "/.worktrees/architect-001\tswarmforge-architect-001\tArchitect 001\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket")
                  "/tmp/swarmforge-test.sock\n")
      (write-agent-status! root "qa-001" "running")
      (write-agent-status! root "architect-001" "running")
      (write-file (fs/path root "swarmforge/squad.conf")
                  (str "max_transient_agents 5\n"
                       "max_active_template hardener 1\n"
                       "max_active_template qa 1\n"
                       "max_active_template merger 1\n"
                       "max_active_group architecture 1 architect senior-implementer\n"))
      (write-file (fs/path root "swarmforge/role-templates/qa.prompt")
                  "qa\n")
      (write-file (fs/path root "swarmforge/role-templates/senior-implementer.prompt")
                  "clean architecture\n")
      (write-file (fs/path root "qa-assignment.md")
                  "Run QA.\n")
      (write-file (fs/path root "architecture-assignment.md")
                  "Clean architecture.\n")
      (run {:dir root}
           (script "squad_spawn_request.sh")
           "qa"
           "second-qa"
           "qa-assignment.md")
      (run {:dir root}
           (script "squad_spawn_request.sh")
           "senior-implementer"
           "architecture-cleanup"
           "architecture-assignment.md")
      (let [once (run {:dir root
                       :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"
                             "SWARMFORGE_SQUADD_SKIP_TMUX" "1"}}
                      (script "squadd.sh")
                      "--once"
                      "--no-notify"
                      (str root))
            requests (fs/list-dir (fs/path root ".squad/spawn-requests/new"))
            daemon-log (slurp (str (fs/path root ".swarmforge/daemon/squadd.log")))]
        (is (str/includes? (:out once) "SQUAD_STATUS_OK"))
        (is (= 2 (count requests)))
        (is (str/includes? daemon-log "spawn-request-deferred"))
        (is (str/includes? daemon-log "template-capacity-full:qa"))
        (is (str/includes? daemon-log "group-capacity-full:architecture")))
      (finally
        (fs/delete-tree root)))))

(deftest squadd-recovers-active-transients-missing-from-roles
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket")
                  "/tmp/swarmforge-test.sock\n")
      (let [worktree (fs/path root ".worktrees/analyst-001")
            agent-dir (fs/path root ".squad/agents/analyst-001")]
        (fs/create-dirs worktree)
        (write-file (fs/path agent-dir "metadata")
                    (str "agent_id: analyst-001\n"
                         "template: analyst\n"
                         "task_id: wumpus-spec\n"
                         "project_root: " root "\n"
                         "worktree: " worktree "\n"
                         "session: swarmforge-analyst-001\n"
                         "display: Analyst 001\n"
                         "backend: codex\n"))
        (write-file (fs/path agent-dir "status")
                    "state: running\ndetail: writing specs\nupdated_at: 2026-07-31T16:00:00Z\n")
        (write-file (fs/path agent-dir "heartbeat")
                    "agent: analyst-001\ntask_id: wumpus-spec\nstate: running\ndetail: writing specs\nupdated_at: 2026-07-31T16:00:00Z\n")
        (let [once (run {:dir root
                         :env {"SWARMFORGE_SQUADD_SKIP_TMUX" "1"
                               "SWARMFORGE_SQUAD_STALE_SECONDS" "999999999"}}
                        (script "squadd.sh")
                        "--once"
                        "--no-notify"
                        (str root))
              roles (slurp (str (fs/path root ".swarmforge/roles.tsv")))
              daemon-log (slurp (str (fs/path root ".swarmforge/daemon/squadd.log")))]
          (is (str/includes? (:out once) "SQUAD_STATUS_OK"))
          (is (str/includes? roles "analyst-001\tanalyst-001\t"))
          (is (str/includes? roles "swarmforge-analyst-001\tAnalyst 001\tcodex\ttask"))
          (is (str/includes? daemon-log "role-recovered analyst-001"))
          (is (not (str/includes? (:out once) "is not registered in roles.tsv")))))
      (finally
        (fs/delete-tree root)))))

(deftest squadd-preserves-agent-authored-retired-state-for-workflow-retirement
  (let [root (tmp-dir)
        bin (fs/path root "bin")
        fake-state (fs/path root "fake-tmux-state")
        fake-tmux (fs/path bin "tmux")]
    (try
      (init-repo! root)
      (fs/create-dirs bin)
      (write-file fake-tmux
                  (str "#!/usr/bin/env sh\n"
                       "mkdir -p \"$FAKE_TMUX_STATE\"\n"
                       "cmd=\"$3\"\n"
                       "case \"$cmd\" in\n"
                       "  has-session) test ! -f \"$FAKE_TMUX_STATE/killed\" ;;\n"
                       "  kill-session) touch \"$FAKE_TMUX_STATE/killed\" ; exit 0 ;;\n"
                       "  list-panes) printf '0\\n' ; exit 0 ;;\n"
                       "  send-keys) exit 0 ;;\n"
                       "  *) exit 0 ;;\n"
                       "esac\n"))
      (run {:dir root} "chmod" "+x" (str fake-tmux))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "analyst-001\tanalyst-001\t" root "/.worktrees/analyst-001\tswarmforge-analyst-001\tAnalyst 001\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket")
                  "/tmp/swarmforge-test.sock\n")
      (let [worktree (fs/path root ".worktrees/analyst-001")]
        (run {:dir root} "git" "worktree" "add" "-q" "-b" "swarmforge-analyst-001" (str worktree) "HEAD")
        (write-file (fs/path root ".squad/agents/analyst-001/metadata")
                    (str "agent_id: analyst-001\n"
                         "template: analyst\n"
                         "task_id: wumpus-spec\n"
                         "project_root: " root "\n"
                         "worktree: " worktree "\n"
                       "session: swarmforge-analyst-001\n"
                       "display: Analyst 001\n"
                       "backend: codex\n"))
        (write-file (fs/path root ".squad/agents/analyst-001/status")
                    "state: retired\ndetail: done\nupdated_at: 2026-07-31T16:00:00Z\n")
        (write-file (fs/path root ".squad/agents/analyst-001/heartbeat")
                    "agent: analyst-001\ntask_id: wumpus-spec\nstate: retired\ndetail: done\nupdated_at: 2026-07-31T16:00:00Z\n")
        (let [once (run {:dir root
                         :env {"PATH" (str bin ":" (System/getenv "PATH"))
                               "FAKE_TMUX_STATE" (str fake-state)}}
                        (script "squadd.sh")
                        "--once"
                        "--no-notify"
                        (str root))
              roles (slurp (str (fs/path root ".swarmforge/roles.tsv")))
              daemon-log (slurp (str (fs/path root ".swarmforge/daemon/squadd.log")))]
          ;; Daemon-owned mechanical apply retires agents that already reported retired.
          (is (str/includes? daemon-log "workflow-mechanical-applied"))
          (is (not (str/includes? roles "analyst-001\t"))
              "mechanical retire removes the role registration")
          (is (str/includes? (slurp (str (fs/path root ".squad/agents/analyst-001/status")))
                             "state: retired"))))
      (finally
        (fs/delete-tree root)))))
(deftest squadd-preserves-failed-transients-for-recovery
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "analyst-001\tanalyst-001\t" root "/.worktrees/analyst-001\tswarmforge-analyst-001\tAnalyst 001\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket")
                  "/tmp/swarmforge-test.sock\n")
      (let [worktree (fs/path root ".worktrees/analyst-001")]
        (run {:dir root} "git" "worktree" "add" "-q" "-b" "swarmforge-analyst-001" (str worktree) "HEAD")
        (write-file (fs/path root ".squad/agents/analyst-001/metadata")
                    (str "agent_id: analyst-001\n"
                         "template: analyst\n"
                         "task_id: wumpus-spec\n"
                         "project_root: " root "\n"
                         "worktree: " worktree "\n"
                         "session: swarmforge-analyst-001\n"
                         "display: Analyst 001\n"
                         "backend: codex\n"))
        (write-file (fs/path root ".squad/agents/analyst-001/status")
                    "state: failed\ndetail: verification failed\nupdated_at: 2026-07-31T16:00:00Z\n")
        (write-file (fs/path root ".squad/agents/analyst-001/heartbeat")
                    "agent: analyst-001\ntask_id: wumpus-spec\nstate: failed\ndetail: verification failed\nupdated_at: 2026-07-31T16:00:00Z\n")
        (write-file (fs/path worktree "stories/preserved.md") "dirty story\n")
        (let [once (run {:dir root
                         :env {"SWARMFORGE_SQUADD_SKIP_TMUX" "1"}}
                        (script "squadd.sh")
                        "--once"
                        "--no-notify"
                        (str root))
              roles (slurp (str (fs/path root ".swarmforge/roles.tsv")))
              daemon-log-file (fs/path root ".swarmforge/daemon/squadd.log")
              daemon-log (if (fs/exists? daemon-log-file)
                           (slurp (str daemon-log-file))
                           "")]
          (is (str/includes? (:out once) "SQUAD_STATUS_OK"))
          (is (str/includes? roles "analyst-001\t"))
          (is (fs/exists? worktree))
          (is (git-worktree-registered? root worktree))
          (is (git-branch-exists? root "swarmforge-analyst-001"))
          (is (fs/exists? (fs/path worktree "stories/preserved.md")))
          (is (not (str/includes? daemon-log "git-worktree-removed analyst-001")))
          (is (not (str/includes? daemon-log "role-retired-reconciled analyst-001")))))
    (finally
      (fs/delete-tree root)))))

(deftest squadd-records-live-pane-liveness-for-stale-heartbeat
  (let [root (tmp-dir)
        bin (fs/path root "bin")
        fake-tmux (fs/path bin "tmux")]
    (try
      (init-repo! root)
      (fs/create-dirs bin)
      (write-file fake-tmux
                  (str "#!/usr/bin/env sh\n"
                       "cmd=\"$3\"\n"
                       "case \"$cmd\" in\n"
                       "  has-session) exit 0 ;;\n"
                       "  list-sessions) printf 'swarmforge-analyst-001\\n' ; exit 0 ;;\n"
                       "  list-panes) printf '0\\n' ; exit 0 ;;\n"
                       "  capture-pane) printf 'line 1\\nline 2\\nline 3\\n' ; exit 0 ;;\n"
                       "  send-keys) exit 0 ;;\n"
                       "  *) exit 0 ;;\n"
                       "esac\n"))
      (run {:dir root} "chmod" "+x" (str fake-tmux))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "analyst-001\tanalyst-001\t" root "/.worktrees/analyst-001\tswarmforge-analyst-001\tAnalyst 001\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket")
                  "/tmp/swarmforge-test.sock\n")
      (write-file (fs/path root ".squad/agents/analyst-001/metadata")
                  (str "agent_id: analyst-001\n"
                       "template: analyst\n"
                       "task_id: wumpus-spec\n"
                       "project_root: " root "\n"
                       "worktree: " root "/.worktrees/analyst-001\n"
                       "session: swarmforge-analyst-001\n"
                       "display: Analyst 001\n"
                       "backend: codex\n"))
      (write-file (fs/path root ".squad/agents/analyst-001/status")
                  "state: running\ndetail: drafting\nupdated_at: 2000-01-01T00:00:00Z\n")
      (write-file (fs/path root ".squad/agents/analyst-001/heartbeat")
                  "agent: analyst-001\ntask_id: wumpus-spec\nstate: running\ndetail: drafting\nupdated_at: 2000-01-01T00:00:00Z\n")
      (let [first-pass (run {:dir root
                             :env {"PATH" (str bin ":" (System/getenv "PATH"))
                                   "SWARMFORGE_SQUAD_STALE_SECONDS" "1"}}
                            (script "squadd.sh")
                            "--once"
                            "--no-notify"
                            (str root))
            liveness-file (fs/path root ".squad/agents/analyst-001/liveness")
            first-liveness (slurp (str liveness-file))
            status (run {:dir root
                         :env {"PATH" (str bin ":" (System/getenv "PATH"))}}
                        (script "squad_status.sh")
                        "analyst-001")
            second-pass (run {:dir root
                              :env {"PATH" (str bin ":" (System/getenv "PATH"))
                                    "SWARMFORGE_SQUAD_STALE_SECONDS" "1"}}
                             (script "squadd.sh")
                             "--once"
                             "--no-notify"
                             (str root))]
        (is (str/includes? (:out first-pass) "SQUAD_STATUS_OK"))
        (is (str/includes? first-liveness "state: running_pane_active"))
        (is (str/includes? first-liveness "last_10_lines:\nline 1\nline 2\nline 3\n"))
        (is (str/includes? (:out status) "LIVENESS_STATE: running_pane_active"))
        (is (str/includes? (:out status) "LAST_10_LINES:\nline 1\nline 2\nline 3"))
        (is (str/includes? (:out status) "PANE_LIVE: true"))
        (is (str/includes? (:out status) "LAST_20_LINES:\nline 1\nline 2\nline 3"))
        (is (str/includes? (:out second-pass) "tmux pane alive but unchanged")))
    (finally
      (fs/delete-tree root)))))

(deftest squadd-graces-transient-missing-tmux-session
  (let [root (tmp-dir)
        bin (fs/path root "bin")
        fake-tmux (fs/path bin "tmux")]
    (try
      (init-repo! root)
      (fs/create-dirs bin)
      (write-file fake-tmux
                  (str "#!/usr/bin/env sh\n"
                       "cmd=\"$3\"\n"
                       "case \"$cmd\" in\n"
                       "  has-session) exit 1 ;;\n"
                       "  list-sessions) exit 0 ;;\n"
                       "  send-keys) exit 0 ;;\n"
                       "  *) exit 0 ;;\n"
                       "esac\n"))
      (run {:dir root} "chmod" "+x" (str fake-tmux))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "analyst-001\tanalyst-001\t" root "/.worktrees/analyst-001\tswarmforge-analyst-001\tAnalyst 001\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket")
                  "/tmp/swarmforge-test.sock\n")
      (write-file (fs/path root ".squad/agents/analyst-001/metadata")
                  (str "agent_id: analyst-001\n"
                       "template: analyst\n"
                       "task_id: wumpus-spec\n"
                       "project_root: " root "\n"
                       "worktree: " root "/.worktrees/analyst-001\n"
                       "session: swarmforge-analyst-001\n"
                       "display: Analyst 001\n"
                       "backend: codex\n"))
      (write-file (fs/path root ".squad/agents/analyst-001/status")
                  "state: running\ndetail: starting\nupdated_at: 2000-01-01T00:00:00Z\n")
      (write-file (fs/path root ".squad/agents/analyst-001/heartbeat")
                  "agent: analyst-001\ntask_id: wumpus-spec\nstate: running\ndetail: starting\nupdated_at: 2000-01-01T00:00:00Z\n")
      (let [first-pass (run {:dir root
                             :env {"PATH" (str bin ":" (System/getenv "PATH"))
                                   "SWARMFORGE_SQUAD_STALE_SECONDS" "1"
                                   "SWARMFORGE_SQUAD_MISSING_SESSION_GRACE_SECONDS" "60"}}
                            (script "squadd.sh")
                            "--once"
                            "--no-notify"
                            (str root))
            missing-file (fs/path root ".squad/agents/analyst-001/missing-session")
            second-pass (run {:dir root
                              :env {"PATH" (str bin ":" (System/getenv "PATH"))
                                    "SWARMFORGE_SQUAD_STALE_SECONDS" "1"
                                    "SWARMFORGE_SQUAD_MISSING_SESSION_GRACE_SECONDS" "0"}}
                             (script "squadd.sh")
                             "--once"
                             "--no-notify"
                             (str root))]
        (is (str/includes? (:out first-pass) "SQUAD_STATUS_OK"))
        (is (str/includes? (slurp (str missing-file)) "session: swarmforge-analyst-001"))
        (is (str/includes? (:out second-pass) "tmux session missing for")))
    (finally
      (fs/delete-tree root)))))

(deftest squadd-starts-and-stops
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (fs/create-dirs (fs/path root ".swarmforge/daemon"))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (run {:dir root :ok? false}
           "sh" "-c"
           (str "SWARMFORGE_SQUADD_SKIP_TMUX=1 bb " (script "squadd.clj") " " root " >/dev/null 2>&1 &"))
      (Thread/sleep 1000)
      (let [pid-file (fs/path root ".swarmforge/daemon/squadd.pid")]
        (is (fs/exists? pid-file))
        (let [pid (str/trim (slurp (str pid-file)))
              stop (run {:dir root} (script "stop_squadd.clj") (str root))]
          (is (= 0 (:exit stop)))
          (Thread/sleep 300)
          (is (not (fs/exists? pid-file)))
          (is (not= 0 (:exit (run {:dir root :ok? false} "kill" "-0" pid))))))
      (finally
        (fs/delete-tree root)))))

(deftest squadd-wakes-idle-squad-leader
  (let [root (tmp-dir)
        bin (fs/path root "bin")
        fake-tmux (fs/path bin "tmux")
        fake-state (fs/path root "fake-tmux-state")]
    (try
      (init-repo! root)
      (fs/create-dirs bin)
      (write-file fake-tmux
                  (str "#!/usr/bin/env sh\n"
                       "mkdir -p \"$FAKE_TMUX_STATE\"\n"
                       "cmd=\"$3\"\n"
                       "case \"$cmd\" in\n"
                       "  has-session) exit 0 ;;\n"
                       "  list-sessions) printf 'swarmforge-squad-leader\\n' ; exit 0 ;;\n"
                       "  list-panes) printf '0\\n' ; exit 0 ;;\n"
                       "  capture-pane) printf 'ready for command>\\n' ; exit 0 ;;\n"
                       "  send-keys)\n"
                       "    case \"$*\" in\n"
                      "      *\"Run squad_next.sh --residual-only\"*) touch \"$FAKE_TMUX_STATE/sl-wake\" ;;\n"
                       "      *\" C-m\") count_file=\"$FAKE_TMUX_STATE/returns\"; count=0; test -f \"$count_file\" && read count < \"$count_file\"; count=$((count + 1)); echo \"$count\" > \"$count_file\" ;;\n"
                       "    esac\n"
                       "    exit 0\n"
                       "    ;;\n"
                       "  *) exit 0 ;;\n"
                       "esac\n"))
      (run {:dir root} "chmod" "+x" (str fake-tmux))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket")
                  "/tmp/swarmforge-test.sock\n")
      (let [env {"PATH" (str bin ":" (System/getenv "PATH"))
                 "FAKE_TMUX_STATE" (str fake-state)
                 "SWARMFORGE_SL_IDLE_SECONDS" "0"
                 "SWARMFORGE_SL_WATCHDOG_COOLDOWN_SECONDS" "999999"
                 "SWARMFORGE_SQUADD_WEB" "0"}]
        (run {:dir root :env env}
             (script "squadd.sh")
             "--once"
             (str root))
        (is (not (fs/exists? (fs/path fake-state "sl-wake"))))
        (run {:dir root :env env}
             (script "squadd.sh")
             "--once"
             (str root))
        (is (fs/exists? (fs/path fake-state "sl-wake")))
        (is (= "2" (str/trim (slurp (str (fs/path fake-state "returns")))))))
      (finally
        (fs/delete-tree root)))))

(deftest p0-spawn-queue-continues-past-template-capacity-head
  ;; Given queue head blocked on template-capacity-full and a later free template
  ;; When squadd --once processes the queue
  ;; Then the later free-template request is processed (not HOL-blocked)
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "gherkin-writer-001\tgherkin-writer-001\t" root "/.worktrees/g1\tswarmforge-g1\tG1\tcodex\ttask\n"
                       "gherkin-writer-002\tgherkin-writer-002\t" root "/.worktrees/g2\tswarmforge-g2\tG2\tcodex\ttask\n"
                       "gherkin-writer-003\tgherkin-writer-003\t" root "/.worktrees/g3\tswarmforge-g3\tG3\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket")
                  "/tmp/swarmforge-test.sock\n")
      (doseq [a ["gherkin-writer-001" "gherkin-writer-002" "gherkin-writer-003"]]
        (write-agent-status! root a "running"))
      (write-file (fs/path root "swarmforge/squad.conf")
                  (str "max_transient_agents 20\n"
                       "max_active_template gherkin-writer 3\n"
                       "max_active_template qa-procedure-writer 3\n"))
      (write-file (fs/path root "swarmforge/role-templates/gherkin-writer.prompt") "gw\n")
      (write-file (fs/path root "swarmforge/role-templates/qa-procedure-writer.prompt") "qw\n")
      (write-file (fs/path root "gherkin-assignment.md") "Write gherkin.\n")
      (write-file (fs/path root "qa-assignment.md") "Write QA procedure.\n")
      (run {:dir root}
           (script "squad_spawn_request.sh")
           "gherkin-writer"
           "blocked-gherkin"
           "gherkin-assignment.md")
      (run {:dir root}
           (script "squad_spawn_request.sh")
           "qa-procedure-writer"
           "free-qa-writer"
           "qa-assignment.md")
      (let [once (run {:dir root
                       :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"
                             "SWARMFORGE_SQUADD_SKIP_TMUX" "1"}}
                      (script "squadd.sh")
                      "--once"
                      "--no-notify"
                      (str root))
            new-requests (when (fs/directory? (fs/path root ".squad/spawn-requests/new"))
                           (mapv str (fs/list-dir (fs/path root ".squad/spawn-requests/new"))))
            completed (when (fs/directory? (fs/path root ".squad/spawn-requests/completed"))
                        (mapv str (fs/list-dir (fs/path root ".squad/spawn-requests/completed"))))
            in-process (when (fs/directory? (fs/path root ".squad/spawn-requests/in_process"))
                         (mapv str (fs/list-dir (fs/path root ".squad/spawn-requests/in_process"))))
            daemon-log (slurp (str (fs/path root ".swarmforge/daemon/squadd.log")))
            all-paths (str (str/join " " (concat (or new-requests [])
                                                 (or completed [])
                                                 (or in-process [])))
                           " " daemon-log " " (:out once))]
        (is (str/includes? daemon-log "template-capacity-full:gherkin-writer")
            "head should still defer on template capacity")
        (is (or (some #(str/includes? % "free-qa-writer") (or completed []))
                (some #(str/includes? % "free-qa-writer") (or in-process []))
                (str/includes? all-paths "free-qa-writer")
                (not (some #(str/includes? % "free-qa-writer") (or new-requests []))))
            "later free template must leave new/ (processed despite HOL head)"))
      (finally
        (fs/delete-tree root)))))
