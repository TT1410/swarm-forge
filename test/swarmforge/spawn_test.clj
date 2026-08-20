(ns swarmforge.spawn-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [swarmforge.test-support :refer :all]))

(deftest squad-spawn-registers-one-invisible-transient-without-launch
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  "window squad-leader codex master task\n")
      (write-file (fs/path root "swarmforge/squad.conf")
                  "transient_agent squad-leader\n")
      (write-file (fs/path root "swarmforge/roles/squad-leader.prompt")
                  "leader\n")
      (write-file (fs/path root "swarmforge/role-templates/analyst.prompt")
                  "specify\n")
      (write-file (fs/path root "assignment.md")
                  "Find the original rules.\n")
      (run {:dir root} (script "swarmforge.clj") "--test-parse" (str root))
      (fs/create-dirs (fs/path root ".swarmforge/squad/spawn.lock"))
      (let [result (run {:dir root
                         :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"}}
                        (script "squad_spawn.sh")
                        "analyst"
                        "wumpus-theme"
                        "assignment.md")
            out (:out result)
            roles (str/split-lines (slurp (str (fs/path root ".swarmforge/roles.tsv"))))
            transient-row (second roles)
            fields (str/split transient-row #"\t" -1)
            worktree (fs/path (nth fields 2))
            agent-dir (fs/path root ".squad/agents/analyst-001")
            prompt-file (fs/path agent-dir "prompt.md")
            launch-script (fs/path agent-dir "launch.sh")
            metadata-file (fs/path agent-dir "metadata")
            expected-root (.getCanonicalPath (fs/file root))
            expected-worktree (.getCanonicalPath (fs/file worktree))
            expected-launch-script (.getCanonicalPath (fs/file launch-script))
            prompt (slurp (str prompt-file))]
        (is (str/includes? out "SQUAD_AGENT: analyst-001"))
        (is (= 2 (count roles)))
        (is (= 7 (count fields)))
        (is (= "analyst-001" (nth fields 0)))
        (is (= "analyst-001" (nth fields 1)))
        (is (str/ends-with? (nth fields 2) "/.worktrees/analyst-001"))
        (is (= "swarmforge-analyst-001" (nth fields 3)))
        (is (= "Analyst 001" (nth fields 4)))
        (is (= "codex" (nth fields 5)))
        (is (= "task" (nth fields 6)))
        (is (fs/exists? (fs/path worktree ".git")))
        (is (fs/exists? (fs/path worktree "swarmforge/scripts/squad_spawn.sh")))
        (is (fs/exists? (fs/path worktree "swarmforge/scripts/squad_assign.sh")))
        (is (fs/exists? (fs/path worktree "swarmforge/scripts/squad_approval.sh")))
        (is (fs/exists? (fs/path worktree "swarmforge/scripts/squad_batch.sh")))
        (is (fs/exists? (fs/path worktree "swarmforge/scripts/squad_next.sh")))
        (is (fs/exists? (fs/path worktree "swarmforge/scripts/squad_packet.sh")))
        (is (fs/exists? (fs/path worktree "swarmforge/scripts/squad_tool.sh")))
        (is (not (fs/exists? (fs/path worktree "swarmforge/scripts/squad_theme.sh"))))
        (is (fs/exists? (fs/path worktree "swarmforge/scripts/squad_event.sh")))
        (is (fs/exists? (fs/path worktree "swarmforge/scripts/squadd.sh")))
        (is (fs/exists? (fs/path worktree ".swarmforge/handoffs/inbox/new")))
        (is (fs/directory? (fs/path worktree ".swarmforge/tools")))
        (is (not (fs/sym-link? (fs/path worktree ".swarmforge/tools"))))
        (is (fs/exists? (fs/path worktree ".swarmforge/tools/bin")))
        (is (fs/exists? (fs/path worktree ".swarmforge/tools/manifests")))
        (is (not (fs/exists? (fs/path worktree ".swarmforge/roles.tsv"))))
        (is (fs/exists? prompt-file))
        (is (fs/exists? launch-script))
        (is (str/includes? (slurp (str metadata-file)) "task_id: wumpus-theme"))
        (is (str/includes? (slurp (str metadata-file)) (str "project_root: " expected-root)))
        (is (str/includes? (slurp (str metadata-file)) (str "tool_cache_dir: " expected-root "/.swarmforge/tools")))
        (is (str/includes? (slurp (str metadata-file)) (str "launch_script: " expected-launch-script)))
        (is (str/includes? prompt "Read swarmforge/worker-common.prompt."))
        (is (str/includes? prompt "Read swarmforge/role-templates/analyst.prompt."))
        (is (not (str/includes? prompt "Read swarmforge/constitution.prompt")))
        (is (not (str/includes? prompt "read every file it refers to recursively")))
        (is (str/includes? prompt "assigned_worktree:"))
        (is (str/includes? prompt "tool_cache_dir:"))
        (is (str/includes? prompt "Find the original rules."))
        (let [launcher (slurp (str launch-script))]
          (is (str/includes? launcher "export SWARMFORGE_PROJECT_ROOT="))
          (is (str/includes? launcher "export SWARMFORGE_WORKTREE="))
          (is (str/includes? launcher "export SWARMFORGE_TOOL_CACHE_DIR="))
          (is (str/includes? launcher "$SWARMFORGE_WORKTREE/.swarmforge/tools/bin"))
          (is (str/includes? launcher "cd \"$SWARMFORGE_WORKTREE\""))
          (is (str/includes? launcher "codex --dangerously-bypass-approvals-and-sandbox"))
          (is (not (str/includes? launcher "codex -C")))
          (is (not (str/includes? launcher (str "codex -C '\"'\"'" expected-root "'\"'\"'"))))
          (is (not (str/includes? launcher (str "codex -C '\"'\"'" expected-worktree "'\"'\"'")))))
        (is (str/includes? (slurp (str (fs/path root ".squad/agents/analyst-001/status")))
                           "state: starting"))
        (is (str/includes? (slurp (str (fs/path root ".squad/agents/analyst-001/heartbeat")))
                           "state: starting"))
        (let [squadd (run {:dir root
                            :env {"SWARMFORGE_SQUADD_SKIP_TMUX" "1"}}
                           (script "squadd.sh")
                           "--once"
                           "--no-notify"
                           (str root))]
          (is (str/includes? (:out squadd) "SQUAD_STATUS_OK")))
        (let [event (run {:dir root
                          :env {"SWARMFORGE_ROLE" "analyst-001"}}
                         (script "squad_event.sh")
                         "running"
                         "reading original rules")
              status (run {:dir root} (script "squad_status.sh") "analyst-001")]
        (is (str/includes? (:out event) "SQUAD_EVENT: running"))
          (is (str/includes? (:out status) "STATE: running"))
          (is (str/includes? (:out status) "TASK_ID: wumpus-theme"))
          (is (str/includes? (slurp (str (fs/path root ".squad/agents/analyst-001/heartbeat")))
                             "state: running"))
          (is (str/includes? (slurp (str (fs/path root ".squad/tasks/wumpus-theme/events.log")))
                             "analyst-001\trunning\treading original rules")))
        (let [event (run {:dir worktree
                          :env {"SWARMFORGE_ROLE" "analyst-001"
                                "SWARMFORGE_PROJECT_ROOT" (str root)}}
                         (str (fs/path worktree "swarmforge/scripts/squad_event.sh"))
                         "running"
                         "worktree helper lookup")
              status (run {:dir root} (script "squad_status.sh") "analyst-001")]
          (is (str/includes? (:out event) "SQUAD_EVENT: running"))
          (is (str/includes? (:out status) "DETAIL: worktree helper lookup"))
          (is (str/includes? (slurp (str (fs/path root ".squad/tasks/wumpus-theme/events.log")))
                             "analyst-001\trunning\tworktree helper lookup")))
        (let [bad-event (run {:dir root
                              :env {"SWARMFORGE_ROLE" "analyst-001"}
                              :ok? false}
                             (script "squad_event.sh")
                             "analyst-001"
                             "running"
                             "wrong argument order")]
          (is (= 2 (:exit bad-event)))
          (is (str/includes? (:err bad-event)
                             "first argument is the state, not the agent id.")))
        (let [run-result (run {:dir root
                               :env {"SWARMFORGE_ROLE" "analyst-001"}}
                              (script "squad_run.sh")
                              "verifying"
                              "quick command"
                              "--"
                              "sh"
                              "-c"
                              "exit 0")
              status (run {:dir root} (script "squad_status.sh") "analyst-001")]
          (is (= 0 (:exit run-result)))
          (is (str/includes? (:out status) "STATE: running"))
          (is (str/includes? (:out status) "DETAIL: verifying passed: quick command"))
          (is (str/includes? (slurp (str (fs/path root ".squad/tasks/wumpus-theme/events.log")))
                             "analyst-001\trunning\tverifying passed: quick command")))
        (let [bad-state (run {:dir root
                              :env {"SWARMFORGE_ROLE" "analyst-001"}
                              :ok? false}
                             (script "squad_event.sh")
                             "verifying_passed"
                             "expressive state")]
          (is (= 2 (:exit bad-state)))
          (is (str/includes? (:err bad-state)
                             "unsupported lifecycle state: verifying_passed"))
          (is (str/includes? (:err bad-state)
                             "Allowed states: starting, running, blocked, failed, handoff_ready, handoff_sent")))
        (let [self-retire (run {:dir root
                                :env {"SWARMFORGE_ROLE" "analyst-001"}
                                :ok? false}
                               (script "squad_event.sh")
                               "retired"
                               "done with handoff")]
          (is (= 2 (:exit self-retire)))
          (is (str/includes? (:err self-retire) "must not self-retire"))
          (is (str/includes? (:err self-retire) "handoff_sent"))
          (is (str/includes? (:err self-retire) "squad_retire.sh"))
          (is (not (str/includes? (slurp (str (fs/path root ".squad/agents/analyst-001/status")))
                                  "state: retired"))))
        (write-file (fs/path root ".squad/agents/analyst-001/heartbeat")
                    (str "agent: analyst-001\n"
                         "task_id: wumpus-theme\n"
                         "state: running\n"
                         "detail: stale for test\n"
                         "updated_at: 2000-01-01T00:00:00Z\n"))
        (let [squadd (run {:dir root
                            :env {"SWARMFORGE_SQUAD_STALE_SECONDS" "1"
                                  "SWARMFORGE_SQUADD_SKIP_TMUX" "1"}}
                           (script "squadd.sh")
                           "--once"
                           "--no-notify"
                           (str root))]
          (is (str/includes? (:out squadd) "SQUAD_STATUS_ALERT: agent analyst-001 heartbeat stale"))
          (is (str/includes? (slurp (str (fs/path root ".swarmforge/daemon/squadd.log")))
                             "status-alert agent analyst-001 heartbeat stale")))
        (fs/create-dirs (fs/path root ".swarmforge/squad/spawn.lock"))
        (let [retire (run {:dir root}
                          (script "squad_retire.sh")
                          "analyst-001")
              retired-roles (str/split-lines (slurp (str (fs/path root ".swarmforge/roles.tsv"))))]
          (is (str/includes? (:out retire) "SQUAD_AGENT_RETIRED: analyst-001"))
          (is (str/includes? (:out retire) "SESSION_STOPPED: false"))
          (is (str/includes? (:out retire) "WORKTREE_REMOVED: true"))
          (is (str/includes? (:out retire) "BRANCH_DELETED: true"))
          (is (= 1 (count retired-roles)))
          (is (str/starts-with? (first retired-roles) "squad-leader\t"))
          (is (not (fs/exists? worktree)))
          (is (not (git-worktree-registered? root worktree)))
          (is (not (git-branch-exists? root "swarmforge-analyst-001")))
          (is (str/includes? (slurp (str (fs/path root ".squad/agents/analyst-001/status")))
                             "state: retired"))
        (is (str/includes? (slurp (str (fs/path root ".squad/agents/analyst-001/heartbeat")))
                             "state: retired"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-spawn-marks-durable-assignment-in-progress
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  "window squad-leader codex master task\n")
      (write-file (fs/path root "swarmforge/roles/squad-leader.prompt")
                  "leader\n")
      (write-file (fs/path root "swarmforge/role-templates/implementer.prompt")
                  "implement\n")
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md")
                  "Story: cave topology and setup.\n")
      (write-file (fs/path root "instructions.md")
                  "Implement cave topology.\n")
      (run {:dir root} (script "swarmforge.clj") "--test-parse" (str root))
      (run {:dir root}
           (script "squad_assign.sh")
           "create"
           "cave-topology"
           "implementer"
           "wumpus-cave-impl"
           "instructions.md")
      (let [assignment (fs/path root ".squad/assignments/wumpus-cave-impl/assignment.md")
            spawn (run {:dir root
                        :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"}}
                       (script "squad_spawn.sh")
                       "implementer"
                       "wumpus-cave-impl"
                       (str assignment))
            status (run {:dir root}
                        (script "squad_assign.sh")
                        "status"
                        "wumpus-cave-impl")
            status-file (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl/status")))
            agent-id (second (re-find #"SQUAD_AGENT: ([^\n]+)" (:out spawn)))]
        (is (some? agent-id))
        (is (str/includes? (:out status) "STATE: in_progress"))
        (is (str/includes? status-file (str "agent_id: " agent-id)))
        (is (str/includes? status-file (str "session: swarmforge-" agent-id))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-spawn-supports-additional-templates
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  "window squad-leader codex master task\n")
      (write-file (fs/path root "swarmforge/roles/squad-leader.prompt")
                  "leader\n")
      (write-file (fs/path root "swarmforge/role-templates/implementer.prompt")
                  "implement\n")
      (write-file (fs/path root "assignment.md")
                  "Implement a tiny behavior slice.\n")
      (run {:dir root} (script "swarmforge.clj") "--test-parse" (str root))
      (let [result (run {:dir root
                         :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"}}
                        (script "squad_spawn.sh")
                        "implementer"
                        "tiny-story"
                        "assignment.md")
            roles (str/split-lines (slurp (str (fs/path root ".swarmforge/roles.tsv"))))
            fields (str/split (second roles) #"\t" -1)
            prompt (slurp (str (fs/path root ".squad/agents/implementer-001/prompt.md")))]
        (is (str/includes? (:out result) "SQUAD_AGENT: implementer-001"))
        (is (= "implementer-001" (first fields)))
        (is (= "Implementer 001" (nth fields 4)))
        (is (str/includes? prompt "template: implementer"))
        (is (str/includes? prompt "Implement a tiny behavior slice."))
        (run {:dir root} (script "squad_retire.sh") "implementer-001")
        (let [second-result (run {:dir root
                                  :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"}}
                                 (script "squad_spawn.sh")
                                 "implementer"
                                 "second-story"
                                 "assignment.md")
              roles (str/split-lines (slurp (str (fs/path root ".swarmforge/roles.tsv"))))
              second-fields (str/split (second roles) #"\t" -1)]
          (is (str/includes? (:out second-result) "SQUAD_AGENT: implementer-002"))
          (is (= "implementer-002" (first second-fields)))
          (is (not (fs/exists? (fs/path root ".worktrees/implementer-001"))))
          (is (not (git-branch-exists? root "swarmforge-implementer-001")))
          (is (fs/exists? (fs/path root ".worktrees/implementer-002")))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-spawn-inherits-squad-leader-agent-backend
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  "window squad-leader grok master task\n")
      (write-file (fs/path root "swarmforge/squad.conf")
                  "transient_agent squad-leader\n")
      (write-file (fs/path root "swarmforge/roles/squad-leader.prompt")
                  "leader\n")
      (write-file (fs/path root "swarmforge/role-templates/analyst.prompt")
                  "analyze\n")
      (write-file (fs/path root "assignment.md")
                  "Write stories.\n")
      (run {:dir root} (script "swarmforge.clj") "--test-parse" (str root))
      (let [result (run {:dir root
                         :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"}}
                        (script "squad_spawn.sh")
                        "analyst"
                        "theme-analysis"
                        "assignment.md")
            roles (str/split-lines (slurp (str (fs/path root ".swarmforge/roles.tsv"))))
            fields (str/split (second roles) #"\t" -1)
            metadata (slurp (str (fs/path root ".squad/agents/analyst-001/metadata")))
            launcher (slurp (str (fs/path root ".squad/agents/analyst-001/launch.sh")))]
        (is (str/includes? (:out result) "SQUAD_AGENT: analyst-001"))
        (is (= "grok" (nth fields 5)))
        (is (str/includes? metadata "backend: grok"))
        (is (str/includes? launcher "grok --cwd"))
        (is (str/includes? launcher "--minimal"))
        (is (str/includes? launcher "--no-alt-screen")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-spawn-config-can-override-transient-agent-backend
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  "window squad-leader codex master task\n")
      (write-file (fs/path root "swarmforge/squad.conf")
                  "transient_agent claude\n")
      (write-file (fs/path root "swarmforge/roles/squad-leader.prompt")
                  "leader\n")
      (write-file (fs/path root "swarmforge/role-templates/analyst.prompt")
                  "analyze\n")
      (write-file (fs/path root "assignment.md")
                  "Write stories.\n")
      (run {:dir root} (script "swarmforge.clj") "--test-parse" (str root))
      (let [result (run {:dir root
                         :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"}}
                        (script "squad_spawn.sh")
                        "analyst"
                        "theme-analysis"
                        "assignment.md")
            roles (str/split-lines (slurp (str (fs/path root ".swarmforge/roles.tsv"))))
            fields (str/split (second roles) #"\t" -1)
            metadata (slurp (str (fs/path root ".squad/agents/analyst-001/metadata")))
            launcher (slurp (str (fs/path root ".squad/agents/analyst-001/launch.sh")))]
        (is (str/includes? (:out result) "SQUAD_AGENT: analyst-001"))
        (is (= "claude" (nth fields 5)))
        (is (str/includes? metadata "backend: claude"))
        (is (str/includes? launcher "claude --append-system-prompt-file")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-spawn-honors-per-template-transient-agent-backend
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root "swarmforge/constitution.prompt") "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  "window squad-leader codex master task\n")
      (write-file (fs/path root "swarmforge/squad.conf")
                  (str "transient_agent claude\n"
                       "transient_agent implementer codex\n"
                       "transient_agent code-reviewer grok\n"))
      (write-file (fs/path root "swarmforge/roles/squad-leader.prompt") "leader\n")
      (write-file (fs/path root "swarmforge/role-templates/implementer.prompt") "implement\n")
      (write-file (fs/path root "swarmforge/role-templates/code-reviewer.prompt") "review\n")
      (write-file (fs/path root "swarmforge/role-templates/analyst.prompt") "analyze\n")
      (write-file (fs/path root "impl.md") "Implement story.\n")
      (write-file (fs/path root "review.md") "Review code.\n")
      (write-file (fs/path root "analysis.md") "Analyze theme.\n")
      (run {:dir root} (script "swarmforge.clj") "--test-parse" (str root))
      (run {:dir root :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"}}
           (script "squad_spawn.sh") "implementer" "story-impl" "impl.md")
      (run {:dir root :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"}}
           (script "squad_spawn.sh") "code-reviewer" "story-review" "review.md")
      (run {:dir root :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"}}
           (script "squad_spawn.sh") "analyst" "theme-analysis" "analysis.md")
      (let [roles (str/split-lines (slurp (str (fs/path root ".swarmforge/roles.tsv"))))
            by-role (into {}
                          (map (fn [line]
                                 (let [f (str/split line #"\t" -1)]
                                   [(first f) (nth f 5)]))
                               (rest roles)))]
        (is (= "codex" (get by-role "implementer-001")))
        (is (= "grok" (get by-role "code-reviewer-001")))
        (is (= "claude" (get by-role "analyst-001"))
            "templates without override use global transient_agent default")
        (is (str/includes? (slurp (str (fs/path root ".squad/agents/implementer-001/metadata")))
                           "backend: codex"))
        (is (str/includes? (slurp (str (fs/path root ".squad/agents/code-reviewer-001/metadata")))
                           "backend: grok"))
        (is (str/includes? (slurp (str (fs/path root ".squad/agents/code-reviewer-001/launch.sh")))
                           "--permission-mode bypassPermissions"))
        (is (not (str/includes? (slurp (str (fs/path root ".squad/agents/code-reviewer-001/launch.sh")))
                                "--permission-mode acceptEdits")))
        (is (str/includes? (slurp (str (fs/path root ".squad/agents/analyst-001/metadata")))
                           "backend: claude")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-spawn-enforces-transient-slot-limit
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
      (write-file (fs/path root "swarmforge/squad.conf")
                  "max_transient_agents 5\n")
      (doseq [agent-id ["analyst-001" "analyst-002" "implementer-001" "code-reviewer-001" "qa-001"]]
        (write-agent-status! root agent-id "running"))
      (write-file (fs/path root "swarmforge/role-templates/code-reviewer.prompt")
                  "review\n")
      (write-file (fs/path root "assignment.md")
                  "Review the story implementation.\n")
      (let [result (run {:dir root
                         :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"}
                         :ok? false}
                        (script "squad_spawn.sh")
                        "code-reviewer"
                        "another-review"
                        "assignment.md")]
        (is (= 3 (:exit result)))
        (is (str/includes? (:err result) "SQUAD_SPAWN_CAPACITY_FULL"))
        (is (str/includes? (:err result) "ACTIVE_TRANSIENTS: 5"))
        (is (str/includes? (:err result) "MAX_TRANSIENTS: 5")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-spawn-reads-transient-slot-limit-from-squad-config
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "analyst-001\tanalyst-001\t" root "/.worktrees/analyst-001\tswarmforge-analyst-001\tAnalyst 001\tcodex\ttask\n"))
      (write-agent-status! root "analyst-001" "running")
      (write-file (fs/path root "swarmforge/squad.conf")
                  "max_transient_agents 1\n")
      (write-file (fs/path root "swarmforge/role-templates/code-reviewer.prompt")
                  "review\n")
      (write-file (fs/path root "assignment.md")
                  "Review the story implementation.\n")
      (let [result (run {:dir root
                         :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"}
                         :ok? false}
                        (script "squad_spawn.sh")
                        "code-reviewer"
                        "another-review"
                        "assignment.md")]
        (is (= 3 (:exit result)))
        (is (str/includes? (:err result) "ACTIVE_TRANSIENTS: 1"))
        (is (str/includes? (:err result) "MAX_TRANSIENTS: 1")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-spawn-enforces-singleton-quality-gate-limits
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "hardener-001\thardener-001\t" root "/.worktrees/hardener-001\tswarmforge-hardener-001\tHardener 001\tcodex\ttask\n"
                       "architect-001\tarchitect-001\t" root "/.worktrees/architect-001\tswarmforge-architect-001\tArchitect 001\tcodex\ttask\n"))
      (write-agent-status! root "hardener-001" "running")
      (write-agent-status! root "architect-001" "running")
      (write-file (fs/path root "swarmforge/squad.conf")
                  (str "max_transient_agents 5\n"
                       "max_active_template hardener 1\n"
                       "max_active_template qa 1\n"
                       "max_active_group architecture 1 architect senior-implementer\n"))
      (write-file (fs/path root "swarmforge/role-templates/hardener.prompt")
                  "harden\n")
      (write-file (fs/path root "swarmforge/role-templates/senior-implementer.prompt")
                  "clean architecture\n")
      (write-file (fs/path root "assignment.md")
                  "Run a quality gate.\n")
      (let [second-hardener (run {:dir root
                                  :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"}
                                  :ok? false}
                                 (script "squad_spawn.sh")
                                 "hardener"
                                 "second-hardening"
                                 "assignment.md")
            senior-implementer (run {:dir root
                                       :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"}
                                       :ok? false}
                                      (script "squad_spawn.sh")
                                      "senior-implementer"
                                      "architecture-cleanup"
                                      "assignment.md")]
        (is (= 3 (:exit second-hardener)))
        (is (str/includes? (:err second-hardener) "SQUAD_SPAWN_TEMPLATE_CAPACITY_FULL"))
        (is (str/includes? (:err second-hardener) "TEMPLATE: hardener"))
        (is (= 3 (:exit senior-implementer)))
        (is (str/includes? (:err senior-implementer) "SQUAD_SPAWN_GROUP_CAPACITY_FULL"))
        (is (str/includes? (:err senior-implementer) "GROUP: architecture")))
      (finally
        (fs/delete-tree root)))))
(deftest squad-spawn-refuses-second-agent-for-same-task-id
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "swarmforge/role-templates/analyst.prompt")
                  "specify\n")
      (write-file (fs/path root "assignment.md")
                  "assignment_id: shared-analysis\n\nFind the original rules.\n")
      (write-file (fs/path root ".squad/assignments/shared-analysis/status")
                  "assignment_id: shared-analysis\nstate: created\n")
      (write-file (fs/path root ".squad/assignments/shared-analysis/assignment.md")
                  "assignment_id: shared-analysis\n\nFind the original rules.\n")
      (let [first (run {:dir root
                        :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"}}
                       (script "squad_spawn.sh")
                       "analyst"
                       "shared-analysis"
                       ".squad/assignments/shared-analysis/assignment.md")
            second (run {:dir root
                         :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"}
                         :ok? false}
                        (script "squad_spawn.sh")
                        "analyst"
                        "shared-analysis"
                        ".squad/assignments/shared-analysis/assignment.md")]
        (is (str/includes? (:out first) "SQUAD_AGENT: analyst-001"))
        (is (= 3 (:exit second)))
        (is (str/includes? (:err second) "SQUAD_SPAWN_TASK_OCCUPIED"))
        (is (str/includes? (:err second) "TASK_ID: shared-analysis"))
        (is (str/includes? (:err second) "AGENT: analyst-001"))
        (is (not (fs/exists? (fs/path root ".squad/agents/analyst-002")))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-spawn-request-refuses-duplicate-task-id
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "assignment.md")
                  "assignment_id: shared-analysis\n")
      (let [first (run {:dir root}
                       (script "squad_spawn_request.sh")
                       "analyst"
                       "shared-analysis"
                       "assignment.md")
            second (run {:dir root
                         :ok? false}
                        (script "squad_spawn_request.sh")
                        "analyst"
                        "shared-analysis"
                        "assignment.md")]
        (is (str/includes? (:out first) "STATE: requested"))
        (is (= 3 (:exit second)))
        (is (str/includes? (:err second) "SQUAD_SPAWN_REQUEST_OCCUPIED"))
        (is (= 1 (count (fs/list-dir (fs/path root ".squad/spawn-requests/new"))))))
      (finally
        (fs/delete-tree root)))))
