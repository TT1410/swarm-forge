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

(deftest session-dead-dirty-open-assignment-owned-by-troubleshooter
  ;; Given dead session, open assignment, dirty worktree
  ;; When classify
  ;; Then session_dead + REPAIR_OWNER troubleshooter + repair command
  (let [root (tmp-dir)
        worktree (fs/path root ".worktrees/hardener-001")]
    (try
      (init-repo! root)
      (run {:dir root} "git" "worktree" "add" "-q" "-b" "swarmforge-hardener-001" (str worktree))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "hardener-001\thardener-001\t" worktree "\tswarmforge-hardener-001\tHardener 001\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "missing.sock") "\n"))
      (write-file (fs/path root ".squad/agents/hardener-001/metadata")
                  (str "agent_id: hardener-001\n"
                       "template: hardener\n"
                       "task_id: theme-hardener\n"
                       "worktree: " worktree "\n"
                       "session: swarmforge-hardener-001\n"))
      (write-file (fs/path root ".squad/agents/hardener-001/status")
                  "state: running\ndetail: quality passed\nupdated_at: 2020-01-01T00:00:00Z\n")
      (write-file (fs/path root ".squad/assignments/theme-hardener/status")
                  "assignment_id: theme-hardener\nstate: in_progress\nagent_id: hardener-001\n")
      (write-file (fs/path root ".squad/assignments/theme-hardener/metadata")
                  "assignment_id: theme-hardener\ntemplate: hardener\ntheme_id: theme\nstory_id: batch\n")
      (write-file (fs/path worktree "src/x.clj") ";; uncommitted work\n")
      (let [result (run {:dir root
                         :env {"SWARMFORGE_SQUAD_RECOVERY_GRACE_SECONDS" "0"}}
                        (script "squad_recover.sh")
                        "hardener-001")]
        (is (str/includes? (:out result) "SESSION_LIVE: false"))
        (is (str/includes? (:out result) "RECOVERY_STATE: session_dead"))
        (is (str/includes? (:out result) "REPAIR_OWNER: troubleshooter"))
        (is (str/includes? (:out result) "COMMAND_ON_REPAIR: squad_recover.sh repair hardener-001"))
        (is (str/includes? (:out result) "OPEN_ASSIGNMENT: true")))
      (finally
        (fs/delete-tree root)))))

(deftest session-dead-clean-open-assignment-owned-by-squad-leader
  (let [root (tmp-dir)
        worktree (fs/path root ".worktrees/implementer-001")]
    (try
      (init-repo! root)
      (run {:dir root} "git" "worktree" "add" "-q" "-b" "swarmforge-implementer-001" (str worktree))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "implementer-001\timplementer-001\t" worktree
                       "\tswarmforge-implementer-001\tImplementer 001\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "missing.sock") "\n"))
      (write-file (fs/path root ".squad/agents/implementer-001/metadata")
                  (str "agent_id: implementer-001\n"
                       "template: implementer\n"
                       "task_id: story-implementation\n"
                       "worktree: " worktree "\n"
                       "session: swarmforge-implementer-001\n"))
      (write-file (fs/path root ".squad/agents/implementer-001/status")
                  "state: running\ndetail: working\nupdated_at: 2020-01-01T00:00:00Z\n")
      (write-file (fs/path root ".squad/assignments/story-implementation/status")
                  "assignment_id: story-implementation\nstate: in_progress\n")
      (write-file (fs/path root ".squad/assignments/story-implementation/metadata")
                  "assignment_id: story-implementation\ntemplate: implementer\ntheme_id: t\nstory_id: s\n")
      (let [result (run {:dir root
                         :env {"SWARMFORGE_SQUAD_RECOVERY_GRACE_SECONDS" "0"}}
                        (script "squad_recover.sh")
                        "implementer-001")]
        (is (str/includes? (:out result) "RECOVERY_STATE: session_dead"))
        (is (str/includes? (:out result) "REPAIR_OWNER: squad-leader"))
        (is (str/includes? (:out result) "COMMAND_ON_REPAIR: squad_recover.sh repair implementer-001")))
      (finally
        (fs/delete-tree root)))))

(deftest repair-removes-agent-and-requeues-assignment
  ;; Given session-dead hardener with open assignment and dirty tree
  ;; When repair runs
  ;; Then agent removed from roles, assignment created, worktree archived
  (let [root (tmp-dir)
        worktree (fs/path root ".worktrees/hardener-001")]
    (try
      (init-repo! root)
      (run {:dir root} "git" "worktree" "add" "-q" "-b" "swarmforge-hardener-001" (str worktree))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "hardener-001\thardener-001\t" worktree "\tswarmforge-hardener-001\tHardener 001\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "missing.sock") "\n"))
      (write-file (fs/path root ".squad/agents/hardener-001/metadata")
                  (str "agent_id: hardener-001\n"
                       "template: hardener\n"
                       "task_id: theme-hardener\n"
                       "worktree: " worktree "\n"
                       "session: swarmforge-hardener-001\n"))
      (write-file (fs/path root ".squad/agents/hardener-001/status")
                  "state: running\ndetail: quality passed\nupdated_at: 2020-01-01T00:00:00Z\n")
      (write-file (fs/path root ".squad/agents/hardener-001/missing-session")
                  "session: swarmforge-hardener-001\nfirst_seen: 2020-01-01T00:00:00Z\n")
      (write-file (fs/path root ".squad/assignments/theme-hardener/status")
                  "assignment_id: theme-hardener\nstate: in_progress\nagent_id: hardener-001\n")
      (write-file (fs/path root ".squad/assignments/theme-hardener/metadata")
                  "assignment_id: theme-hardener\ntemplate: hardener\ntheme_id: theme\nstory_id: batch\n")
      (write-file (fs/path root ".squad/assignments/theme-hardener/blocker")
                  "blocker_id: theme-hardener\nkind: death\n")
      (write-file (fs/path worktree "src/x.clj") ";; dirty\n")
      (let [result (run {:dir root} (script "squad_recover.sh") "repair" "hardener-001")
            roles (slurp (str (fs/path root ".swarmforge/roles.tsv")))
            status (slurp (str (fs/path root ".squad/assignments/theme-hardener/status")))
            agent-status (slurp (str (fs/path root ".squad/agents/hardener-001/status")))]
        (is (str/includes? (:out result) "SQUAD_REPAIR: hardener-001"))
        (is (str/includes? (:out result) "REQUEUED_ASSIGNMENT: theme-hardener"))
        (is (str/includes? (:out result) "ARCHIVED_WORKTREE:"))
        (is (str/includes? (:out result) "AGENT_REMOVED: hardener-001"))
        (is (not (str/includes? roles "hardener-001")))
        (is (str/includes? status "state: created"))
        (is (str/includes? agent-status "state: retired"))
        (is (not (fs/exists? (fs/path root ".squad/agents/hardener-001/missing-session"))))
        (is (not (fs/exists? (fs/path root ".squad/assignments/theme-hardener/blocker"))))
        (is (seq (fs/list-dir (fs/path root ".squad/recovery-archive")))))
      (finally
        (fs/delete-tree root)))))

(deftest residual-offers-repair-dead-agent-when-session-gone
  (let [root (tmp-dir)
        worktree (fs/path root ".worktrees/hardener-001")]
    (try
      (init-repo! root)
      (run {:dir root} "git" "worktree" "add" "-q" "-b" "swarmforge-hardener-001" (str worktree))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "hardener-001\thardener-001\t" worktree "\tswarmforge-hardener-001\tHardener 001\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "missing.sock") "\n"))
      (write-file (fs/path root "swarmforge/squad.conf")
                  "recovery_quiet_seconds 0\nrecovery_retry_seconds 0\n")
      (write-file (fs/path root ".squad/agents/hardener-001/metadata")
                  (str "agent_id: hardener-001\n"
                       "template: hardener\n"
                       "task_id: theme-hardener\n"
                       "worktree: " worktree "\n"
                       "session: swarmforge-hardener-001\n"))
      (write-file (fs/path root ".squad/agents/hardener-001/status")
                  "state: running\ndetail: quality passed\nupdated_at: 2020-01-01T00:00:00Z\n")
      (write-file (fs/path root ".squad/agents/hardener-001/heartbeat")
                  "agent: hardener-001\ntask_id: theme-hardener\nstate: running\nupdated_at: 2020-01-01T00:00:00Z\n")
      (write-file (fs/path root ".squad/assignments/theme-hardener/status")
                  "assignment_id: theme-hardener\nstate: in_progress\n")
      (write-file (fs/path worktree "src/x.clj") "x\n")
      (let [out (:out (run {:dir root
                            :env {"SWARMFORGE_SQUAD_RECOVERY_QUIET_SECONDS" "0"
                                  "SWARMFORGE_SQUAD_RECOVERY_RETRY_SECONDS" "0"}}
                           (script "squad_next.sh") "--residual-only"))]
        (is (str/includes? out "NEXT_ACTION: repair_dead_agent")
            out)
        (is (str/includes? out "REPAIR_OWNER: troubleshooter"))
        (is (str/includes? out "COMMAND: squad_recover.sh repair hardener-001")))
      (finally
        (fs/delete-tree root)))))
