(ns swarmforge.script-test
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (fs/cwd))
(def scripts-dir (fs/path repo-root "swarmforge" "scripts"))

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn run
  [{:keys [dir env ok?]} & args]
  (let [result (apply sh/sh (concat args [:dir (str dir)
                                          :env (merge {"PATH" (System/getenv "PATH")
                                                       "GIT_CONFIG_NOSYSTEM" "1"}
                                                      env)]))]
    (when (and (not (false? ok?)) (not= 0 (:exit result)))
      (throw (ex-info (str "Command failed: " (str/join " " args))
                      (assoc result :args args))))
    result))

(defn init-repo! [root]
  (run {:dir root} "git" "init" "-q")
  (run {:dir root} "git" "config" "user.email" "test@example.com")
  (run {:dir root} "git" "config" "user.name" "Test User")
  (write-file (fs/path root "README.md") "initial\n")
  (run {:dir root} "git" "add" "README.md")
  (run {:dir root} "git" "commit" "-q" "-m" "Initial commit"))

(defn tmp-dir []
  (fs/create-temp-dir {:prefix "swarmforge-script-test."}))

(defn script [name]
  (str (fs/path scripts-dir name)))

(deftest squad-role-templates-exist
  (doseq [template ["specifier"
                    "implementer"
                    "reviewer"
                    "cleaner"
                    "hardener"
                    "qa"
                    "architecture-reviewer"
                    "architecture-cleaner"]]
    (is (fs/exists? (fs/path repo-root "swarmforge/role-templates" (str template ".prompt"))))))

(deftest squad-role-templates-handoff-only-to-leader
  (doseq [template ["specifier"
                    "implementer"
                    "reviewer"
                    "cleaner"
                    "hardener"
                    "qa"
                    "architecture-reviewer"
                    "architecture-cleaner"]]
    (let [prompt (slurp (str (fs/path repo-root "swarmforge/role-templates" (str template ".prompt"))))]
      (is (str/includes? prompt "Do not hand off to another transient worker.")
          template)
      (is (str/includes? prompt "Send a `git_handoff` back to `squad-leader`")
          template)
      (is (str/includes? prompt "Do not search the web unless the assignment explicitly asks you to.")
          template)
      (is (str/includes? prompt "Do not fetch, clone, install, or update external tools unless the assignment explicitly asks you to.")
          template))))

(deftest squad-review-cleaning-responsibilities-are-split
  (let [reviewer (slurp (str (fs/path repo-root "swarmforge/role-templates/reviewer.prompt")))
        cleaner (slurp (str (fs/path repo-root "swarmforge/role-templates/cleaner.prompt")))
        architecture-reviewer (slurp (str (fs/path repo-root "swarmforge/role-templates/architecture-reviewer.prompt")))
        architecture-cleaner (slurp (str (fs/path repo-root "swarmforge/role-templates/architecture-cleaner.prompt")))
        hardener (slurp (str (fs/path repo-root "swarmforge/role-templates/hardener.prompt")))
        qa (slurp (str (fs/path repo-root "swarmforge/role-templates/qa.prompt")))
        local-workflow (slurp (str (fs/path repo-root "swarmforge/constitution/articles/local-workflow.prompt")))
        leader (slurp (str (fs/path repo-root "swarmforge/roles/squad-leader.prompt")))]
    (is (str/includes? reviewer "Produce a review report only"))
    (is (str/includes? reviewer "Do not make production changes."))
    (is (str/includes? cleaner "Work one story at a time."))
    (is (str/includes? cleaner "Do not implement reviewer recommendations unless they are listed in the assignment from the squad leader."))
    (is (str/includes? architecture-reviewer "Produce an architecture review report only"))
    (is (str/includes? architecture-cleaner "Do not implement architecture-reviewer recommendations unless they are listed in the assignment from the squad leader."))
    (is (str/includes? hardener "based on the six-pack hardender"))
    (is (str/includes? hardener "soft Gherkin acceptance mutation"))
    (is (str/includes? qa "based on the six-pack QA"))
    (is (str/includes? qa "Fix bugs found by the QA suite or final verification"))
    (is (str/includes? leader "Reviewers and architecture-reviewers report recommendations only."))
    (is (str/includes? leader "After QA completes, batch architecture-reviewer work"))
    (is (str/includes? leader "Do not forge transient result handoffs"))
    (is (str/includes? local-workflow "Architecture-reviewer should normally follow QA"))
    (is (str/includes? local-workflow "must not forge transient result handoffs"))))

(deftest handoff-lib-parses-and-prints-handoff-files
  (let [root (tmp-dir)
        handoff-file (fs/path root "task.handoff")]
    (try
      (write-file handoff-file
                  (str "id: 1\n"
                       "from: coder\n"
                       "to: cleaner\n"
                       "priority: 10\n"
                       "type: git_handoff\n"
                       "task: task-alpha\n"
                       "\n"
                       "merge_and_process coder abcdef1234\n"))
      (let [header (run {:dir root} (script "handoff_lib.bb") "header-field" "task.handoff" "task")
            body (run {:dir root} (script "handoff_lib.bb") "body" "task.handoff")
            task (run {:dir root} (script "handoff_lib.bb") "print-task" "task.handoff")]
        (is (str/includes? (:out header) "task-alpha"))
        (is (str/includes? (:out body) "merge_and_process coder abcdef1234"))
        (is (str/includes? (:out task) "TASK: task.handoff"))
        (is (str/includes? (:out task) "FROM: coder"))
        (is (str/includes? (:out task) "TASK_NAME: task-alpha")))
      (finally
        (fs/delete-tree root)))))

(deftest handoff-lib-updates-headers-and-reads-role-state
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "coder\tmaster\t" root "\tsession\tCoder\tcodex\ttask\n"
                       "cleaner\tcleaner\t" root "/.worktrees/cleaner\tsession\tCleaner\tcodex\tbatch\n"))
      (write-file (fs/path root ".swarmforge/handoffs/inbox/new/item.handoff")
                  (str "id: 1\n"
                       "from: coder\n"
                       "to: cleaner\n"
                       "priority: 20\n"
                       "type: note\n"
                       "\n"
                       "payload\n"))
      (run {:dir root} (script "handoff_lib.bb") "role-known" "cleaner")
      (run {:dir root} (script "handoff_lib.bb") "set-header" ".swarmforge/handoffs/inbox/new/item.handoff" "dequeued_at" "2026-06-16T00:00:00Z")
      (let [mode (run {:dir root} (script "handoff_lib.bb") "role-receive-mode" "cleaner")
            worktree (run {:dir root} (script "handoff_lib.bb") "role-worktree-name" "cleaner")
            dequeued (run {:dir root} (script "handoff_lib.bb") "header-field" ".swarmforge/handoffs/inbox/new/item.handoff" "dequeued_at")
            seq-1 (run {:dir root} (script "handoff_lib.bb") "next-sequence")
            seq-2 (run {:dir root} (script "handoff_lib.bb") "next-sequence")]
        (is (str/includes? (:out mode) "batch"))
        (is (str/includes? (:out worktree) "cleaner"))
        (is (str/includes? (:out dequeued) "2026-06-16T00:00:00Z"))
        (is (str/includes? (:out seq-1) "000001"))
        (is (str/includes? (:out seq-2) "000002")))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-launcher-parses-config-and-writes-state-files
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  (str "# comment\n"
                       "window coder codex master\n"
                       "window cleaner codex cleaner batch\n"))
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (write-file (fs/path root "swarmforge/roles/cleaner.prompt") "cleaner\n")
      (let [result (run {:dir root} (script "swarmforge.bb") "--test-parse" (str root))]
        (is (str/includes? (:out result) "coder Coder"))
        (is (str/includes? (:out result) "cleaner Cleaner"))
        (is (str/includes? (:out result) "cleaner batch"))
        (is (str/includes? (:out result) "swarmforge-coder"))
        (is (str/includes? (:out result) "swarmforge-cleaner"))
        (is (fs/exists? (fs/path root ".swarmforge/tmux-socket"))))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-uses-portable-tmux-socket-dir
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  "window coder codex master\n")
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (run {:dir root} (script "swarmforge.bb") "--test-parse" (str root))
      (let [socket-path (str/trim (slurp (str (fs/path root ".swarmforge/tmux-socket"))))]
        (is (str/starts-with? socket-path "/tmp/swarmforge-"))
        (is (not (str/starts-with? socket-path "/private/tmp/"))))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-launcher-rejects-invalid-config
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  (str "window coder codex master\n"
                       "window coder codex other\n"))
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (let [result (run {:dir root :ok? false} (script "swarmforge.bb") "--test-parse" (str root))]
        (is (= 1 (:exit result)))
        (is (str/includes? (:err result) "Duplicate role 'coder'")))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-terminal-bridge-preserves-adapter-globals
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/scripts/swarm-terminal-adapter.sh")
                  (str "load_terminal_backend() {\n"
                       "  source \"$SCRIPT_DIR/terminal-adapters/$1.sh\"\n"
                       "}\n"))
      (write-file (fs/path root "swarmforge/scripts/terminal-adapters/probe.sh")
                  (str "terminal_open_session() {\n"
                       "  printf '%s\\n' \"$WORKING_DIR|$TMUX_SOCKET|$1|$2|$3\"\n"
                       "}\n"))
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-terminal-bridge"
                        (str root)
                        "probe")]
        (is (str/includes? (:out result) (str root "|")))
        (is (str/includes? (:out result) "|swarmforge-specifier|SwarmForge Specifier|"))
        (is (not (str/includes? (:out result) "cd ''")))
        (is (not (str/includes? (:out result) "-S ''"))))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-agent-start-delay-is-configurable
  (let [default-result (run {:dir repo-root}
                            (script "swarmforge.bb")
                            "--test-agent-start-delay")
        configured-result (run {:dir repo-root
                                :env {"SWARMFORGE_AGENT_START_DELAY_MS" "2750"}}
                               (script "swarmforge.bb")
                               "--test-agent-start-delay")
        invalid-result (run {:dir repo-root
                             :env {"SWARMFORGE_AGENT_START_DELAY_MS" "fast"}}
                            (script "swarmforge.bb")
                            "--test-agent-start-delay")]
    (is (= "1500" (str/trim (:out default-result))))
    (is (= "2750" (str/trim (:out configured-result))))
    (is (= "1500" (str/trim (:out invalid-result))))))

(deftest swarmforge-sleep-prevention-can-be-disabled
  (let [result (run {:dir repo-root
                     :env {"SWARMFORGE_PREVENT_SLEEP" "0"}}
                    (script "swarmforge.bb")
                    "--test-sleep-inhibitor-prefix")]
    (is (= "" (str/trim (:out result))))))

(deftest swarmforge-launcher-parses-extra-cli-args
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  (str "window coder copilot master --yolo\n"
                       "window cleaner copilot cleaner batch --allow-all-tools\n"))
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (write-file (fs/path root "swarmforge/roles/cleaner.prompt") "cleaner\n")
      (let [result (run {:dir root} (script "swarmforge.bb") "--test-parse" (str root))]
        (is (str/includes? (:out result) "coder Coder"))
        (is (str/includes? (:out result) "task --yolo"))
        (is (str/includes? (:out result) "batch --allow-all-tools")))
      (finally
        (fs/delete-tree root)))))

(deftest copilot-launch-command-passes-extra-cli-args
  (let [root (tmp-dir)]
    (try
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-launch-command"
                        (str root)
                        "copilot"
                        "--yolo")
            command (:out result)]
        (is (str/includes? command "copilot -C "))
        (is (re-find #"--name 'SwarmForge Coder' --yolo -i" command)))
      (finally
        (fs/delete-tree root)))))

(deftest squad-spawn-registers-one-invisible-transient-without-launch
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  "window squad-leader codex master task\n")
      (write-file (fs/path root "swarmforge/roles/squad-leader.prompt")
                  "leader\n")
      (write-file (fs/path root "swarmforge/role-templates/specifier.prompt")
                  "specify\n")
      (write-file (fs/path root "assignment.md")
                  "Find the original rules.\n")
      (run {:dir root} (script "swarmforge.bb") "--test-parse" (str root))
      (fs/create-dirs (fs/path root ".swarmforge/squad/spawn.lock"))
      (let [result (run {:dir root
                         :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"}}
                        (script "squad_spawn.sh")
                        "specifier"
                        "wumpus-theme"
                        "assignment.md")
            out (:out result)
            roles (str/split-lines (slurp (str (fs/path root ".swarmforge/roles.tsv"))))
            transient-row (second roles)
            fields (str/split transient-row #"\t" -1)
            worktree (fs/path (nth fields 2))
            agent-dir (fs/path root ".squad/agents/specifier-001")
            prompt-file (fs/path agent-dir "prompt.md")
            launch-script (fs/path agent-dir "launch.sh")
            metadata-file (fs/path agent-dir "metadata")
            expected-root (.getCanonicalPath (fs/file root))
            expected-launch-script (.getCanonicalPath (fs/file launch-script))]
        (is (str/includes? out "SQUAD_AGENT: specifier-001"))
        (is (= 2 (count roles)))
        (is (= 7 (count fields)))
        (is (= "specifier-001" (nth fields 0)))
        (is (= "specifier-001" (nth fields 1)))
        (is (str/ends-with? (nth fields 2) "/.worktrees/specifier-001"))
        (is (= "swarmforge-specifier-001" (nth fields 3)))
        (is (= "Specifier 001" (nth fields 4)))
        (is (= "codex" (nth fields 5)))
        (is (= "task" (nth fields 6)))
        (is (fs/exists? (fs/path worktree ".git")))
        (is (fs/exists? (fs/path worktree "swarmforge/scripts/squad_spawn.sh")))
        (is (fs/exists? (fs/path worktree "swarmforge/scripts/squad_assign.sh")))
        (is (fs/exists? (fs/path worktree "swarmforge/scripts/squad_tool.sh")))
        (is (fs/exists? (fs/path worktree "swarmforge/scripts/squad_theme.sh")))
        (is (fs/exists? (fs/path worktree "swarmforge/scripts/squad_event.sh")))
        (is (fs/exists? (fs/path worktree "swarmforge/scripts/squad_statusd.sh")))
        (is (fs/exists? (fs/path worktree ".swarmforge/handoffs/inbox/new")))
        (is (not (fs/exists? (fs/path worktree ".swarmforge/roles.tsv"))))
        (is (fs/exists? prompt-file))
        (is (fs/exists? launch-script))
        (is (str/includes? (slurp (str metadata-file)) "task_id: wumpus-theme"))
        (is (str/includes? (slurp (str metadata-file)) (str "project_root: " expected-root)))
        (is (str/includes? (slurp (str metadata-file)) (str "tool_cache_dir: " expected-root "/.swarmforge/tools")))
        (is (str/includes? (slurp (str metadata-file)) (str "launch_script: " expected-launch-script)))
        (is (str/includes? (slurp (str prompt-file)) "assigned_worktree:"))
        (is (str/includes? (slurp (str prompt-file)) "tool_cache_dir:"))
        (is (str/includes? (slurp (str prompt-file)) "Before task work, verify that your current directory is the assigned worktree."))
        (is (str/includes? (slurp (str prompt-file)) "Do not search the web unless the assignment explicitly asks you to."))
        (is (str/includes? (slurp (str prompt-file)) "Do not fetch, clone, install, update, or check remote versions of external tools"))
        (let [launcher (slurp (str launch-script))]
          (is (str/includes? launcher "export SWARMFORGE_PROJECT_ROOT="))
          (is (str/includes? launcher "export SWARMFORGE_WORKTREE="))
          (is (str/includes? launcher "export SWARMFORGE_TOOL_CACHE_DIR="))
          (is (str/includes? launcher "$SWARMFORGE_TOOL_CACHE_DIR/bin"))
          (is (str/includes? launcher "cd \"$SWARMFORGE_WORKTREE\""))
          (is (str/includes? launcher "codex -C"))
          (is (str/includes? launcher expected-root)))
        (is (str/includes? (slurp (str prompt-file))
                           "Find the original rules."))
        (is (str/includes? (slurp (str (fs/path root ".squad/agents/specifier-001/status")))
                           "state: spawned"))
        (is (str/includes? (slurp (str (fs/path root ".squad/agents/specifier-001/heartbeat")))
                           "state: spawned"))
        (let [statusd (run {:dir root
                            :env {"SWARMFORGE_SQUAD_STATUSD_SKIP_TMUX" "1"}}
                           (script "squad_statusd.sh")
                           "--once"
                           "--no-notify"
                           (str root))]
          (is (str/includes? (:out statusd) "SQUAD_STATUS_OK")))
        (let [event (run {:dir root
                          :env {"SWARMFORGE_ROLE" "specifier-001"}}
                         (script "squad_event.sh")
                         "working"
                         "reading original rules")
              status (run {:dir root} (script "squad_status.sh") "specifier-001")]
        (is (str/includes? (:out event) "SQUAD_EVENT: working"))
          (is (str/includes? (:out status) "STATE: working"))
          (is (str/includes? (:out status) "TASK_ID: wumpus-theme"))
          (is (str/includes? (slurp (str (fs/path root ".squad/agents/specifier-001/heartbeat")))
                             "state: working"))
          (is (str/includes? (slurp (str (fs/path root ".squad/tasks/wumpus-theme/events.log")))
                             "specifier-001\tworking\treading original rules")))
        (let [event (run {:dir worktree
                          :env {"SWARMFORGE_ROLE" "specifier-001"
                                "SWARMFORGE_PROJECT_ROOT" (str root)}}
                         (str (fs/path worktree "swarmforge/scripts/squad_event.sh"))
                         "working"
                         "worktree helper lookup")
              status (run {:dir root} (script "squad_status.sh") "specifier-001")]
          (is (str/includes? (:out event) "SQUAD_EVENT: working"))
          (is (str/includes? (:out status) "DETAIL: worktree helper lookup"))
          (is (str/includes? (slurp (str (fs/path root ".squad/tasks/wumpus-theme/events.log")))
                             "specifier-001\tworking\tworktree helper lookup")))
        (let [bad-event (run {:dir root
                              :env {"SWARMFORGE_ROLE" "specifier-001"}
                              :ok? false}
                             (script "squad_event.sh")
                             "specifier-001"
                             "working"
                             "wrong argument order")]
          (is (= 2 (:exit bad-event)))
          (is (str/includes? (:err bad-event)
                             "first argument is the state, not the agent id.")))
        (let [run-result (run {:dir root
                               :env {"SWARMFORGE_ROLE" "specifier-001"}}
                              (script "squad_run.sh")
                              "verifying"
                              "quick command"
                              "--"
                              "sh"
                              "-c"
                              "exit 0")
              status (run {:dir root} (script "squad_status.sh") "specifier-001")]
          (is (= 0 (:exit run-result)))
          (is (str/includes? (:out status) "STATE: verifying_passed"))
          (is (str/includes? (slurp (str (fs/path root ".squad/tasks/wumpus-theme/events.log")))
                             "specifier-001\tverifying_passed\tquick command")))
        (write-file (fs/path root ".squad/agents/specifier-001/heartbeat")
                    (str "agent: specifier-001\n"
                         "task_id: wumpus-theme\n"
                         "state: working\n"
                         "detail: stale for test\n"
                         "updated_at: 2000-01-01T00:00:00Z\n"))
        (let [statusd (run {:dir root
                            :env {"SWARMFORGE_SQUAD_STALE_SECONDS" "1"
                                  "SWARMFORGE_SQUAD_STATUSD_SKIP_TMUX" "1"}}
                           (script "squad_statusd.sh")
                           "--once"
                           "--no-notify"
                           (str root))]
          (is (str/includes? (:out statusd) "SQUAD_STATUS_ALERT: agent specifier-001 heartbeat stale"))
          (is (str/includes? (slurp (str (fs/path root ".swarmforge/daemon/squad-statusd.log")))
                             "alert agent specifier-001 heartbeat stale")))
        (fs/create-dirs (fs/path root ".swarmforge/squad/spawn.lock"))
        (let [retire (run {:dir root}
                          (script "squad_retire.sh")
                          "specifier-001")
              retired-roles (str/split-lines (slurp (str (fs/path root ".swarmforge/roles.tsv"))))]
          (is (str/includes? (:out retire) "SQUAD_AGENT_RETIRED: specifier-001"))
          (is (str/includes? (:out retire) "SESSION_STOPPED: false"))
          (is (= 1 (count retired-roles)))
          (is (str/starts-with? (first retired-roles) "squad-leader\t"))
          (is (fs/exists? worktree))
          (is (str/includes? (slurp (str (fs/path root ".squad/agents/specifier-001/status")))
                             "state: retired"))
        (is (str/includes? (slurp (str (fs/path root ".squad/agents/specifier-001/heartbeat")))
                             "state: retired"))))
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
      (run {:dir root} (script "swarmforge.bb") "--test-parse" (str root))
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
          (is (fs/exists? (fs/path root ".worktrees/implementer-001")))
          (is (fs/exists? (fs/path root ".worktrees/implementer-002")))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-theme-records-theme-stories-and-approval-gates
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md")
                  "Story: cave topology and setup.\n")
      (write-file (fs/path root "features/cave-topology.feature")
                  (str "Feature: Cave topology\n\n"
                       "  Scenario: The cave has twenty rooms\n"
                       "    Then the cave contains 20 rooms\n"))
      (write-file (fs/path root "qa/cave-topology.md")
                  "QA procedure: verify cave topology through the user interface.\n")
      (let [create (run {:dir root}
                        (script "squad_theme.sh")
                        "create"
                        "wumpus"
                        "theme.md")
            story (run {:dir root}
                       (script "squad_theme.sh")
                       "story"
                       "wumpus"
                       "cave-topology"
                       "stories/cave-topology.md")
            acceptance (run {:dir root}
                            (script "squad_theme.sh")
                            "acceptance"
                            "wumpus"
                            "cave-topology"
                            "features/cave-topology.feature")
            qa-procedure (run {:dir root}
                              (script "squad_theme.sh")
                              "acceptance"
                              "wumpus"
                              "cave-topology-qa"
                              "qa/cave-topology.md")
            approve-stories (run {:dir root}
                                  (script "squad_theme.sh")
                                  "approve"
                                  "wumpus"
                                  "stories"
                                  "user approved story split")
            approve-acceptance (run {:dir root}
                                     (script "squad_theme.sh")
                                     "approve"
                                     "wumpus"
                                     "acceptance"
                                     "user approved acceptance spec")
            status (run {:dir root}
                        (script "squad_theme.sh")
                        "status"
                        "wumpus")
            theme-dir (fs/path root ".squad/themes/wumpus")]
        (is (str/includes? (:out create) "STATE: theme_created"))
        (is (str/includes? (:out story) "STORY: cave-topology"))
        (is (str/includes? (:out story) "PATH: stories/cave-topology.md"))
        (is (str/includes? (:out acceptance) "ACCEPTANCE: cave-topology"))
        (is (str/includes? (:out acceptance) "PATH: features/cave-topology.feature"))
        (is (str/includes? (:out acceptance) "STATE: acceptance_added"))
        (is (str/includes? (:out qa-procedure) "ACCEPTANCE: cave-topology-qa"))
        (is (str/includes? (:out qa-procedure) "PATH: qa/cave-topology.md"))
        (is (str/includes? (:out approve-stories) "STATE: approved_stories"))
        (is (str/includes? (:out approve-acceptance) "STATE: approved_acceptance"))
        (is (str/includes? (:out status) "THEME: wumpus"))
        (is (str/includes? (:out status) "STATE: approved_acceptance"))
        (is (str/includes? (:out status) "STORIES: cave-topology"))
        (is (str/includes? (:out status) "ACCEPTANCE: cave-topology,cave-topology-qa"))
        (is (str/includes? (:out status) "APPROVALS: 2"))
        (is (str/includes? (slurp (str (fs/path theme-dir "theme.md")))
                           "faithful Hunt the Wumpus"))
        (is (str/includes? (slurp (str (fs/path theme-dir "stories/cave-topology.ref")))
                           "path: stories/cave-topology.md"))
        (is (str/includes? (slurp (str (fs/path root "stories/cave-topology.md")))
                           "cave topology"))
        (is (str/includes? (slurp (str (fs/path theme-dir "acceptance/cave-topology.ref")))
                           "path: features/cave-topology.feature"))
        (is (str/includes? (slurp (str (fs/path theme-dir "acceptance/cave-topology-qa.ref")))
                           "path: qa/cave-topology.md"))
        (is (str/includes? (slurp (str (fs/path root "features/cave-topology.feature")))
                           "Feature: Cave topology"))
        (is (str/includes? (slurp (str (fs/path root "qa/cave-topology.md")))
                           "QA procedure"))
        (is (str/includes? (slurp (str (fs/path theme-dir "approvals.tsv")))
                           "\tstories\tuser approved story split"))
        (is (str/includes? (slurp (str (fs/path theme-dir "events.log")))
                           "\tapproved_acceptance\tuser approved acceptance spec"))
        (is (str/includes? (slurp (str (fs/path theme-dir "events.log")))
                           "\tstory_added\tcave-topology\tstories/cave-topology.md"))
        (is (str/includes? (slurp (str (fs/path theme-dir "events.log")))
                           "\tacceptance_added\tcave-topology\tfeatures/cave-topology.feature"))
        (is (str/includes? (slurp (str (fs/path theme-dir "events.log")))
                           "\tacceptance_added\tcave-topology-qa\tqa/cave-topology.md")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-assign-generates-durable-assignment-from-theme-story
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (fs/create-dirs (fs/path root "swarmforge/role-templates"))
      (write-file (fs/path root "swarmforge/role-templates/implementer.prompt")
                  "implement\n")
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md")
                  "Story: cave topology and setup.\n")
      (write-file (fs/path root "instructions.md")
                  "Write unit tests first, then production code.\n")
      (write-file (fs/path root "review.md")
                  "Review: request a smaller implementation boundary.\n")
      (write-file (fs/path root "rejection.md")
                  "Reject because the branch exceeded the story boundary.\n")
      (write-file (fs/path root "replacement-instructions.md")
                  "Reimplement only cave topology.\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "cave-topology" "stories/cave-topology.md")
      (run {:dir root} (script "squad_theme.sh") "approve" "wumpus" "acceptance-cave-topology" "user approved cave topology acceptance spec")
      (let [create (run {:dir root}
                        (script "squad_assign.sh")
                        "create"
                        "wumpus"
                        "cave-topology"
                        "implementer"
                        "wumpus-cave-impl"
                        "instructions.md"
                        "--requires"
                        "approval:acceptance-cave-topology")
            status (run {:dir root}
                        (script "squad_assign.sh")
                        "status"
                        "wumpus-cave-impl")
            assignment (fs/path root ".squad/assignments/wumpus-cave-impl/assignment.md")
            draft (fs/path root ".squad/assignments/wumpus-cave-impl/result-handoff.draft")
            commit (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (is (str/includes? (:out create) "SQUAD_ASSIGNMENT: wumpus-cave-impl"))
        (is (str/includes? (:out create) "TEMPLATE: implementer"))
        (is (str/includes? (:out status) "STATE: assignment_created"))
        (is (str/includes? (slurp (str assignment)) "assignment_id: wumpus-cave-impl"))
        (is (str/includes? (slurp (str assignment)) "Story: cave topology and setup."))
        (is (str/includes? (slurp (str assignment)) "Write unit tests first"))
        (is (str/includes? (slurp (str assignment)) "swarm_handoff.sh"))
        (is (str/includes? (slurp (str draft)) "type: git_handoff"))
        (is (str/includes? (slurp (str draft)) "to: squad-leader"))
        (is (str/includes? (slurp (str draft)) "task: wumpus-cave-impl"))
        (is (str/includes? (slurp (str (fs/path root ".squad/themes/wumpus/events.log")))
                           "\tassignment_created\twumpus-cave-impl\timplementer\tcave-topology"))
        (write-file (fs/path root "anonymous-result.handoff")
                    (str "id: 1\n"
                         "to: squad-leader\n"
                         "priority: 50\n"
                         "type: git_handoff\n"
                         "task: wumpus-cave-impl\n"
                         "commit: " commit "\n"
                         "\n"
                         "merge_and_process implementer-001 " commit "\n"))
        (write-file (fs/path root "leader-result.handoff")
                    (str "id: 2\n"
                         "from: squad-leader\n"
                         "to: squad-leader\n"
                         "priority: 50\n"
                         "type: git_handoff\n"
                         "task: wumpus-cave-impl\n"
                         "commit: " commit "\n"
                         "\n"
                         "merge_and_process squad-leader " commit "\n"))
        (let [anonymous (run {:dir root :ok? false}
                             (script "squad_assign.sh")
                             "result"
                             "wumpus-cave-impl"
                             "anonymous-result.handoff")
              leader-authored (run {:dir root :ok? false}
                                   (script "squad_assign.sh")
                                   "result"
                                   "wumpus-cave-impl"
                                   "leader-result.handoff")]
          (is (= 2 (:exit anonymous)))
          (is (str/includes? (:err anonymous) "Result handoff must have a from header."))
          (is (= 2 (:exit leader-authored)))
          (is (str/includes? (:err leader-authored) "Transient result handoff may not be from: squad-leader.")))
        (write-file (fs/path root "result.handoff")
                    (str "id: 1\n"
                         "from: implementer-001\n"
                         "to: squad-leader\n"
                         "priority: 50\n"
                         "type: git_handoff\n"
                         "task: wumpus-cave-impl\n"
                         "commit: " commit "\n"
                         "\n"
                         "merge_and_process implementer-001 " commit "\n"))
        (let [result (run {:dir root}
                          (script "squad_assign.sh")
                          "result"
                          "wumpus-cave-impl"
                          "result.handoff")
              result-status (run {:dir root}
                                 (script "squad_assign.sh")
                                 "status"
                                 "wumpus-cave-impl")
              merge-ready (run {:dir root}
                               (script "squad_assign.sh")
                               "merge-ready"
                               "wumpus-cave-impl")
              merge-status (run {:dir root}
                                (script "squad_assign.sh")
                                "status"
                                "wumpus-cave-impl")
              review (run {:dir root}
                          (script "squad_assign.sh")
                          "review"
                          "wumpus-cave-impl"
                          "changes-requested"
                          "review.md")
              reject (run {:dir root}
                          (script "squad_assign.sh")
                          "reject"
                          "wumpus-cave-impl"
                          "rejection.md")
              replace (run {:dir root}
                           (script "squad_assign.sh")
                           "replace"
                           "wumpus-cave-impl"
                           "wumpus-cave-impl-2"
                           "implementer"
                           "replacement-instructions.md")
              replacement-status (run {:dir root}
                                      (script "squad_assign.sh")
                                      "status"
                                      "wumpus-cave-impl-2")
              report (run {:dir root}
                          (script "squad_report.sh")
                          "wumpus")]
          (is (str/includes? (:out result) "STATE: result_received"))
          (is (str/includes? (:out result) (str "COMMIT: " commit)))
          (is (str/includes? (:out result-status) "STATE: result_received"))
          (is (str/includes? (:out result-status) "RESULT:"))
          (is (str/includes? (:out merge-ready) "STATE: merge_ready"))
          (is (str/includes? (:out merge-ready) "commit already reachable from HEAD"))
          (is (str/includes? (:out merge-status) "STATE: merge_ready"))
          (is (str/includes? (:out merge-status) "MERGE:"))
          (is (str/includes? (:out review) "STATE: review_changes_requested"))
          (is (str/includes? (:out reject) "STATE: rejected"))
          (is (str/includes? (:out replace) "SQUAD_ASSIGNMENT: wumpus-cave-impl-2"))
          (is (str/includes? (:out replace) "REPLACES: wumpus-cave-impl"))
          (is (str/includes? (:out replacement-status) "STATE: assignment_created"))
          (is (str/includes? (:out report) "# Squad Report: wumpus"))
          (is (str/includes? (:out report) "- Stories: cave-topology"))
          (is (str/includes? (:out report) "acceptance-cave-topology: user approved cave topology acceptance spec"))
          (is (str/includes? (:out report) "wumpus-cave-impl [implementer] story=cave-topology state=replacement_created"))
          (is (str/includes? (:out report) "replacement=wumpus-cave-impl-2"))
          (is (str/includes? (:out report) "wumpus-cave-impl-2 [implementer] story=cave-topology state=assignment_created"))
          (is (str/includes? (:out report) "replaces=wumpus-cave-impl"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl/result.handoff")))
                             "from: implementer-001"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl/result")))
                             (str "commit: " commit)))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl/merge")))
                             "state: merge_ready"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl/review")))
                             "state: review_changes_requested"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl/review.md")))
                             "smaller implementation boundary"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl/rejection")))
                             "state: rejected"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl/rejection.md")))
                             "exceeded the story boundary"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl/replacement")))
                             "replacement: wumpus-cave-impl-2"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl-2/replaces")))
                             "replaces: wumpus-cave-impl"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-impl-2/assignment.md")))
                             "Reimplement only cave topology"))
          (is (str/includes? (slurp (str (fs/path root ".squad/themes/wumpus/events.log")))
                             (str "\tassignment_result_received\twumpus-cave-impl\timplementer-001\t" commit "\tcave-topology")))
          (is (str/includes? (slurp (str (fs/path root ".squad/themes/wumpus/events.log")))
                             (str "\tassignment_merge_ready\twumpus-cave-impl\t" commit "\tcave-topology")))
          (is (str/includes? (slurp (str (fs/path root ".squad/themes/wumpus/events.log")))
                             "\tassignment_review_changes_requested\twumpus-cave-impl\tchanges-requested\tcave-topology"))
          (is (str/includes? (slurp (str (fs/path root ".squad/themes/wumpus/events.log")))
                             "\tassignment_rejected\twumpus-cave-impl\tcave-topology"))
          (is (str/includes? (slurp (str (fs/path root ".squad/themes/wumpus/events.log")))
                             "\tassignment_replacement_created\twumpus-cave-impl\twumpus-cave-impl-2\tcave-topology")))
        (let [spawn (run {:dir root
                          :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"}}
                         (script "squad_spawn.sh")
                         "implementer"
                         "wumpus-cave-impl"
                         (str assignment))]
          (is (str/includes? (:out spawn) "SQUAD_AGENT: implementer-001"))
          (is (str/includes? (slurp (str (fs/path root ".squad/agents/implementer-001/prompt.md")))
                             "assignment_id: wumpus-cave-impl"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-assign-enforces-required-approval-gates
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (fs/create-dirs (fs/path root "swarmforge/role-templates"))
      (write-file (fs/path root "swarmforge/role-templates/implementer.prompt")
                  "implement\n")
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md")
                  "Story: cave topology and setup.\n")
      (write-file (fs/path root "instructions.md")
                  "Write unit tests first, then production code.\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "cave-topology" "stories/cave-topology.md")
      (let [missing-requirement (run {:dir root :ok? false}
                                     (script "squad_assign.sh")
                                     "create"
                                     "wumpus"
                                     "cave-topology"
                                     "implementer"
                                     "wumpus-cave-impl"
                                     "instructions.md")
            theme-wide (run {:dir root :ok? false}
                            (script "squad_assign.sh")
                            "create"
                            "wumpus"
                            "cave-topology"
                            "implementer"
                            "wumpus-cave-impl"
                            "instructions.md"
                            "--requires"
                            "approval:acceptance")
            blocked (run {:dir root :ok? false}
                         (script "squad_assign.sh")
                         "create"
                         "wumpus"
                         "cave-topology"
                         "implementer"
                         "wumpus-cave-impl"
                         "instructions.md"
                         "--requires"
                         "approval:acceptance-cave-topology")]
        (is (= 2 (:exit missing-requirement)))
        (is (str/includes? (:err missing-requirement)
                           "requires story-level approval gate approval:acceptance-cave-topology"))
        (is (= 2 (:exit theme-wide)))
        (is (str/includes? (:err theme-wide)
                           "Theme-wide acceptance approval is not allowed"))
        (is (= 3 (:exit blocked)))
        (is (str/includes? (:err blocked) "SQUAD_ASSIGNMENT_BLOCKED: wumpus-cave-impl"))
        (is (str/includes? (:err blocked) "missing required approval gate acceptance-cave-topology"))
        (is (not (fs/exists? (fs/path root ".squad/assignments/wumpus-cave-impl")))))
      (run {:dir root}
           (script "squad_theme.sh")
           "approve"
           "wumpus"
           "acceptance-cave-topology"
           "user approved cave topology acceptance spec")
      (let [created (run {:dir root}
                         (script "squad_assign.sh")
                         "create"
                         "wumpus"
                         "cave-topology"
                         "implementer"
                         "wumpus-cave-impl"
                         "instructions.md"
                         "--requires"
                         "approval:acceptance-cave-topology")
            assignment (fs/path root ".squad/assignments/wumpus-cave-impl/assignment.md")
            metadata (fs/path root ".squad/assignments/wumpus-cave-impl/metadata")]
        (is (str/includes? (:out created) "REQUIRES: approval:acceptance-cave-topology"))
        (is (str/includes? (slurp (str assignment)) "requires: approval:acceptance-cave-topology"))
        (is (str/includes? (slurp (str metadata)) "requires: approval:acceptance-cave-topology")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-assign-accepts-reviewed-merge
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (fs/create-dirs (fs/path root "swarmforge/role-templates"))
      (write-file (fs/path root "swarmforge/role-templates/implementer.prompt")
                  "implement\n")
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md")
                  "Story: cave topology and setup.\n")
      (write-file (fs/path root "instructions.md")
                  "Write unit tests first, then production code.\n")
      (write-file (fs/path root "review.md")
                  "Review: accepted.\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "cave-topology" "stories/cave-topology.md")
      (run {:dir root} (script "squad_theme.sh") "approve" "wumpus" "acceptance-cave-topology" "user approved cave topology acceptance spec")
      (run {:dir root}
           (script "squad_assign.sh")
           "create"
           "wumpus"
           "cave-topology"
           "implementer"
           "wumpus-cave-accepted"
           "instructions.md"
           "--requires"
           "approval:acceptance-cave-topology")
      (let [commit (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (write-file (fs/path root "result.handoff")
                    (str "id: 1\n"
                         "from: implementer-001\n"
                         "to: squad-leader\n"
                         "priority: 50\n"
                         "type: git_handoff\n"
                         "task: wumpus-cave-accepted\n"
                         "commit: " commit "\n"
                         "\n"
                         "merge_and_process implementer-001 " commit "\n"))
        (run {:dir root} (script "squad_assign.sh") "result" "wumpus-cave-accepted" "result.handoff")
        (run {:dir root} (script "squad_assign.sh") "merge-ready" "wumpus-cave-accepted")
        (run {:dir root} (script "squad_assign.sh") "review" "wumpus-cave-accepted" "accepted" "review.md")
        (let [accepted (run {:dir root} (script "squad_assign.sh") "accept-merge" "wumpus-cave-accepted")
              status (run {:dir root} (script "squad_assign.sh") "status" "wumpus-cave-accepted")
              report (run {:dir root} (script "squad_report.sh") "wumpus")]
          (is (str/includes? (:out accepted) "STATE: merged"))
          (is (str/includes? (:out accepted) "commit already reachable from HEAD"))
          (is (str/includes? (:out status) "STATE: merged"))
          (is (str/includes? (:out status) "ACCEPTED_MERGE:"))
          (is (str/includes? (slurp (str (fs/path root ".squad/assignments/wumpus-cave-accepted/accepted-merge")))
                             "state: merged"))
          (is (str/includes? (:out report) "wumpus-cave-accepted [implementer] story=cave-topology state=merged"))
          (is (str/includes? (:out report) "accepted_merge=merged"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-tool-registers-executables-in-shared-cache
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "fake-tool")
                  "#!/usr/bin/env sh\nprintf 'fake-tool\\n'\n")
      (run {:dir root} "chmod" "+x" "fake-tool")
      (let [init (run {:dir root} (script "squad_tool.sh") "init")
            register (run {:dir root}
                          (script "squad_tool.sh")
                          "register"
                          "fake-tool"
                          "github.com/example/fake-tool"
                          "abcdef1234"
                          "fake-tool")
            status (run {:dir root} (script "squad_tool.sh") "status" "fake-tool")
            all-status (run {:dir root} (script "squad_tool.sh") "status")
            require (run {:dir root}
                         (script "squad_tool.sh")
                         "require"
                         "fake-tool"
                         "github.com/example/fake-tool"
                         "abcdef1234")
            mismatch (run {:dir root :ok? false}
                          (script "squad_tool.sh")
                          "require"
                          "fake-tool"
                          "github.com/example/fake-tool"
                          "ffffffffff")
            missing (run {:dir root :ok? false}
                         (script "squad_tool.sh")
                         "require"
                         "missing-tool"
                         "github.com/example/missing-tool"
                         "abcdef1234")
            cached-tool (fs/path root ".swarmforge/tools/bin/fake-tool")
            manifest (fs/path root ".swarmforge/tools/manifests/fake-tool.manifest")
            run-cached (run {:dir root} (str cached-tool))]
        (is (str/includes? (:out init) "TOOL_CACHE:"))
        (is (str/includes? (:out register) "STATE: registered"))
        (is (str/includes? (:out register) "SQUAD_TOOL: fake-tool"))
        (is (str/includes? (:out status) "STATE: registered"))
        (is (str/includes? (:out status) "SOURCE: github.com/example/fake-tool"))
        (is (str/includes? (:out status) "VERSION: abcdef1234"))
        (is (str/includes? (:out all-status) "TOOLS: fake-tool"))
        (is (str/includes? (:out require) "STATE: available"))
        (is (str/includes? (:out require) "EXECUTABLE:"))
        (is (= 4 (:exit mismatch)))
        (is (str/includes? (:err mismatch) "SQUAD_TOOL_MISMATCH: fake-tool"))
        (is (str/includes? (:err mismatch) "FIELD: version"))
        (is (= 3 (:exit missing)))
        (is (str/includes? (:err missing) "SQUAD_TOOL_MISSING: missing-tool"))
        (is (= "fake-tool" (str/trim (:out run-cached))))
        (is (str/includes? (slurp (str manifest)) "tool: fake-tool"))
        (is (fs/exists? (fs/path root ".swarmforge/tools/src")))
        (is (fs/exists? (fs/path root ".swarmforge/tools/cache")))
        (is (fs/exists? (fs/path root ".swarmforge/tools/locks"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-tool-ensure-installs-once-and-reuses-matching-cache
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (let [install (run {:dir root}
                         (script "squad_tool.sh")
                         "ensure"
                         "built-tool"
                         "github.com/example/built-tool"
                         "1111111111"
                         "--"
                         "sh"
                         "-c"
                         "printf '#!/usr/bin/env sh\nprintf \"built-tool\\\\n\"\n' > \"$SWARMFORGE_TOOL_TARGET\"")
            reuse (run {:dir root}
                       (script "squad_tool.sh")
                       "ensure"
                       "built-tool"
                       "github.com/example/built-tool"
                       "1111111111"
                       "--"
                       "sh"
                       "-c"
                       "exit 99")
            cached-tool (fs/path root ".swarmforge/tools/bin/built-tool")
            run-cached (run {:dir root} (str cached-tool))]
        (is (str/includes? (:out install) "STATE: installed"))
        (is (str/includes? (:out reuse) "STATE: available"))
        (is (= "built-tool" (str/trim (:out run-cached))))
        (is (str/includes? (slurp (str (fs/path root ".swarmforge/tools/manifests/built-tool.manifest")))
                           "source: github.com/example/built-tool")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-status-daemon-starts-and-stops
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (fs/create-dirs (fs/path root ".swarmforge/daemon"))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (run {:dir root :ok? false}
           "sh" "-c"
           (str "bb " (script "squad_statusd.bb") " " root " >/dev/null 2>&1 &"))
      (Thread/sleep 1000)
      (let [pid-file (fs/path root ".swarmforge/daemon/squad-statusd.pid")]
        (is (fs/exists? pid-file))
        (let [pid (str/trim (slurp (str pid-file)))
              stop (run {:dir root} (script "stop_squad_status_daemon.bb") (str root))]
          (is (= 0 (:exit stop)))
          (Thread/sleep 300)
          (is (not (fs/exists? pid-file)))
          (is (not= 0 (:exit (run {:dir root :ok? false} "kill" "-0" pid))))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-status-daemon-stop-finds-orphan-with-missing-pid-file
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (fs/create-dirs (fs/path root ".swarmforge/daemon"))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (run {:dir root :ok? false}
           "sh" "-c"
           (str "bb " (script "squad_statusd.bb") " " root " >/dev/null 2>&1 &"))
      (Thread/sleep 1000)
      (let [pid-file (fs/path root ".swarmforge/daemon/squad-statusd.pid")]
        (is (fs/exists? pid-file))
        (let [pid (str/trim (slurp (str pid-file)))]
          (fs/delete-if-exists pid-file)
          (let [stop (run {:dir root} (script "stop_squad_status_daemon.bb") (str root))]
            (is (= 0 (:exit stop)))
            (Thread/sleep 300)
            (is (not= 0 (:exit (run {:dir root :ok? false} "kill" "-0" pid)))))))
      (finally
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
      (write-file (fs/path root "swarmforge/role-templates/specifier.prompt")
                  "specify\n")
      (write-file (fs/path root "assignment.md")
                  "Find the original rules.\n")
      (run {:dir root} (script "swarmforge.bb") "--test-parse" (str root))
      (let [request (run {:dir root}
                         (script "squad_spawn_request.sh")
                         "specifier"
                         "wumpus-theme"
                         "assignment.md")]
        (is (str/includes? (:out request) "STATE: requested"))
        (is (= 1 (count (fs/list-dir (fs/path root ".squad/spawn-requests/new"))))))
      (let [once (run {:dir root
                       :env {"SWARMFORGE_SQUAD_NO_LAUNCH" "1"
                             "SWARMFORGE_SQUAD_STATUSD_SKIP_TMUX" "1"}}
                      (script "squadd.sh")
                      "--once"
                      "--no-notify"
                      (str root))
            roles (str/split-lines (slurp (str (fs/path root ".swarmforge/roles.tsv"))))
            completed (fs/list-dir (fs/path root ".squad/spawn-requests/completed"))]
        (is (str/includes? (:out once) "SQUAD_STATUS_OK"))
        (is (= 2 (count roles)))
        (is (some #(str/starts-with? % "specifier-001\t") roles))
        (is (some #(str/ends-with? (fs/file-name %) ".request") completed))
        (is (some #(str/ends-with? (fs/file-name %) ".request.out") completed))
        (is (fs/exists? (fs/path root ".squad/agents/specifier-001/status")))
        (is (str/includes? (slurp (str (fs/path root ".swarmforge/daemon/squadd.log")))
                           "spawn-request-completed")))
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
      (let [worktree (fs/path root ".worktrees/specifier-001")
            agent-dir (fs/path root ".squad/agents/specifier-001")]
        (fs/create-dirs worktree)
        (write-file (fs/path agent-dir "metadata")
                    (str "agent_id: specifier-001\n"
                         "template: specifier\n"
                         "task_id: wumpus-spec\n"
                         "project_root: " root "\n"
                         "worktree: " worktree "\n"
                         "session: swarmforge-specifier-001\n"
                         "display: Specifier 001\n"
                         "backend: codex\n"))
        (write-file (fs/path agent-dir "status")
                    "state: working\ndetail: writing specs\nupdated_at: 2026-07-31T16:00:00Z\n")
        (write-file (fs/path agent-dir "heartbeat")
                    "agent: specifier-001\ntask_id: wumpus-spec\nstate: working\ndetail: writing specs\nupdated_at: 2026-07-31T16:00:00Z\n")
        (let [once (run {:dir root
                         :env {"SWARMFORGE_SQUAD_STATUSD_SKIP_TMUX" "1"
                               "SWARMFORGE_SQUAD_STALE_SECONDS" "999999999"}}
                        (script "squadd.sh")
                        "--once"
                        "--no-notify"
                        (str root))
              roles (slurp (str (fs/path root ".swarmforge/roles.tsv")))
              daemon-log (slurp (str (fs/path root ".swarmforge/daemon/squadd.log")))]
          (is (str/includes? (:out once) "SQUAD_STATUS_OK"))
          (is (str/includes? roles "specifier-001\tspecifier-001\t"))
          (is (str/includes? roles "swarmforge-specifier-001\tSpecifier 001\tcodex\ttask"))
          (is (str/includes? daemon-log "role-recovered specifier-001"))
          (is (not (str/includes? (:out once) "is not registered in roles.tsv")))))
      (finally
        (fs/delete-tree root)))))

(deftest squadd-reconciles-retired-transients-and-kills-leftover-tmux-session
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
                       "specifier-001\tspecifier-001\t" root "/.worktrees/specifier-001\tswarmforge-specifier-001\tSpecifier 001\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket")
                  "/tmp/swarmforge-test.sock\n")
      (write-file (fs/path root ".squad/agents/specifier-001/metadata")
                  (str "agent_id: specifier-001\n"
                       "template: specifier\n"
                       "task_id: wumpus-spec\n"
                       "project_root: " root "\n"
                       "worktree: " root "/.worktrees/specifier-001\n"
                       "session: swarmforge-specifier-001\n"
                       "display: Specifier 001\n"
                       "backend: codex\n"))
      (write-file (fs/path root ".squad/agents/specifier-001/status")
                  "state: retired\ndetail: done\nupdated_at: 2026-07-31T16:00:00Z\n")
      (write-file (fs/path root ".squad/agents/specifier-001/heartbeat")
                  "agent: specifier-001\ntask_id: wumpus-spec\nstate: retired\ndetail: done\nupdated_at: 2026-07-31T16:00:00Z\n")
      (let [once (run {:dir root
                       :env {"PATH" (str bin ":" (System/getenv "PATH"))
                             "FAKE_TMUX_STATE" (str fake-state)}}
                      (script "squadd.sh")
                      "--once"
                      "--no-notify"
                      (str root))
            roles (slurp (str (fs/path root ".swarmforge/roles.tsv")))
            daemon-log (slurp (str (fs/path root ".swarmforge/daemon/squadd.log")))]
        (is (str/includes? (:out once) "SQUAD_STATUS_OK"))
        (is (fs/exists? (fs/path fake-state "killed")))
        (is (not (str/includes? roles "specifier-001\t")))
        (is (str/includes? daemon-log "retired-session-killed specifier-001 swarmforge-specifier-001"))
        (is (str/includes? daemon-log "role-retired-reconciled specifier-001")))
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
           (str "SWARMFORGE_SQUAD_STATUSD_SKIP_TMUX=1 bb " (script "squadd.bb") " " root " >/dev/null 2>&1 &"))
      (Thread/sleep 1000)
      (let [pid-file (fs/path root ".swarmforge/daemon/squadd.pid")]
        (is (fs/exists? pid-file))
        (let [pid (str/trim (slurp (str pid-file)))
              stop (run {:dir root} (script "stop_squadd.bb") (str root))]
          (is (= 0 (:exit stop)))
          (Thread/sleep 300)
          (is (not (fs/exists? pid-file)))
          (is (not= 0 (:exit (run {:dir root :ok? false} "kill" "-0" pid))))))
      (finally
        (fs/delete-tree root)))))

(deftest grok-launch-command-passes-initial-prompt
  (let [root (tmp-dir)]
    (try
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-launch-command"
                        (str root)
                        "grok")
            command (:out result)]
        (is (str/includes? command "grok --cwd "))
        (is (str/includes? command "--permission-mode acceptEdits"))
        (is (str/includes? command "--rules \"$(cat "))
        (is (str/includes? command "--verbatim \"$(cat "))
        (is (str/includes? command ".swarmforge/prompts/coder.md"))
        (is (fs/exists? (fs/path root ".swarmforge/prompts/coder.md"))))
      (finally
        (fs/delete-tree root)))))

(deftest grok-launch-command-uses-bypass-permissions-with-always-approve
  (let [root (tmp-dir)]
    (try
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-launch-command"
                        (str root)
                        "grok"
                        "--always-approve")
            command (:out result)]
        (is (str/includes? command "--permission-mode bypassPermissions"))
        (is (str/includes? command "--always-approve"))
        (is (not (str/includes? command "--permission-mode acceptEdits"))))
      (finally
        (fs/delete-tree root)))))

(deftest window-watchdog-rewrites-window-state-and-id-list
  (let [root (tmp-dir)
        state-file (fs/path root "windows.tsv")
        ids-file (fs/path root "window-ids")]
    (try
      (write-file state-file
                  (str "1\told-a\tswarmforge-coder\tSwarmForge Coder\n"
                       "2\told-b\tswarmforge-cleaner\tSwarmForge Cleaner\n"))
      (write-file ids-file "old-a\nold-b\n")
      (run {:dir root} (script "swarm-window-watchdog.bb") "--rewrite-window-id" "windows.tsv" "window-ids" "2" "new-b")
      (let [state (slurp (str state-file))
            ids (slurp (str ids-file))]
        (is (str/includes? state "1\told-a\tswarmforge-coder\tSwarmForge Coder"))
        (is (str/includes? state "2\tnew-b\tswarmforge-cleaner\tSwarmForge Cleaner"))
        (is (= "old-a\nnew-b\n" ids)))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-detects-nonzero-pane-base-index
  (let [root (tmp-dir)
        sock (str root "/test.sock")
        conf (fs/path root "tmux.conf")]
    (try
      (write-file conf "set -g base-index 1\nset -g pane-base-index 1\n")
      (run {:dir root} "tmux" "-S" sock "-f" (str conf) "new-session" "-d" "-s" "probe" "sleep" "120")
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-tmux-base-indexes"
                        sock)]
        (is (= "1 1" (str/trim (:out result)))))
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
        squad-pid-file (fs/path root ".swarmforge/daemon/squad-statusd.pid")
        squadd (.start (java.lang.ProcessBuilder. ["sleep" "120"]))
        daemon (.start (java.lang.ProcessBuilder. ["sleep" "120"]))
        squad-daemon (.start (java.lang.ProcessBuilder. ["sleep" "120"]))
        squadd-pid (str (.pid squadd))
        pid (str (.pid daemon))
        squad-pid (str (.pid squad-daemon))]
    (try
      (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
      (write-file (fs/path root ".swarmforge/sessions.tsv")
                  (str "1\tcoder\tswarmforge-coder\tCoder\tcodex\n"
                       "2\tcleaner\tswarmforge-cleaner\tCleaner\tcodex\n"))
      (write-file (fs/path root ".swarmforge/window-ids") "win-a\nwin-b\n")
      (write-file squadd-pid-file (str squadd-pid "\n"))
      (write-file pid-file (str pid "\n"))
      (write-file squad-pid-file (str squad-pid "\n"))
      (run {:dir root} "tmux" "-S" sock "new-session" "-d" "-s" "swarmforge-coder" "sleep" "120")
      (run {:dir root} "tmux" "-S" sock "new-session" "-d" "-s" "swarmforge-cleaner" "sleep" "120")
      (let [result (run {:dir root
                         :env {"SWARMFORGE_TERMINAL_BACKEND" "none"}}
                        (close-swarm)
                        (str root))]
        (is (= 0 (:exit result)))
        (is (not= 0 (:exit (run {:dir root :ok? false}
                                "tmux" "-S" sock "has-session" "-t" "swarmforge-coder"))))
        (is (not= 0 (:exit (run {:dir root :ok? false}
                                "tmux" "-S" sock "has-session" "-t" "swarmforge-cleaner"))))
        (is (not (fs/exists? pid-file)))
        (is (not (fs/exists? squad-pid-file)))
        (is (not (fs/exists? squadd-pid-file)))
        (is (false? (.isAlive squadd)))
        (is (false? (.isAlive daemon)))
        (is (false? (.isAlive squad-daemon))))
      (finally
        (when (.isAlive squadd)
          (.destroyForcibly squadd))
        (when (.isAlive daemon)
          (.destroyForcibly daemon))
        (when (.isAlive squad-daemon)
          (.destroyForcibly squad-daemon))
        (run {:dir root :ok? false} "tmux" "-S" sock "kill-server")
        (fs/delete-tree root)))))
