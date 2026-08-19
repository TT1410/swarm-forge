#!/usr/bin/env bb

(ns swarmforge
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def session-prefix "swarmforge")
(def agent-window "swarm")
(def red "\u001b[0;31m")
(def green "\u001b[0;32m")
(def yellow "\u001b[1;33m")
(def cyan "\u001b[0;36m")
(def bold "\u001b[1m")
(def reset "\u001b[0m")

(defn sh [& args]
  (apply process/sh args))

(defn sh-ok? [& args]
  (zero? (:exit (apply process/sh (concat [{:continue true}] args)))))

(defn sh-out [& args]
  (str/trim (:out (apply process/sh args))))

(defn command-exists? [command]
  (sh-ok? "sh" "-c" (str "command -v " command " >/dev/null 2>&1")))

(defn env-long [name default-value]
  (if-let [value (System/getenv name)]
    (if (re-matches #"[0-9]+" value)
      (Long/parseLong value)
      default-value)
    default-value))

(defn fail! [message]
  (binding [*out* *err*]
    (println message))
  (System/exit 1))

(defn sq [value]
  (str "'" (str/replace (str value) #"'" "'\"'\"'") "'"))

(def terminal-backend-aliases
  {"iterm" "iterm2"
   "iterm2" "iterm2"
   "iterm.app" "iterm2"
   "terminal" "terminal-app"
   "terminal-app" "terminal-app"
   "terminal.app" "terminal-app"
   "windows" "windows-terminal"
   "windows-terminal" "windows-terminal"
   "wt" "windows-terminal"
   "none" "none"
   "current" "none"
   "fallback" "none"})

(defn normalize-terminal-backend [backend]
  (let [backend (str/lower-case backend)]
    (get terminal-backend-aliases backend backend)))

(defn osascript-terminal-backend []
  (if (= (System/getenv "TERM_PROGRAM") "iTerm.app")
    "iterm2"
    "terminal-app"))

(defn detect-terminal-backend []
  (if-let [backend (System/getenv "SWARMFORGE_TERMINAL")]
    (normalize-terminal-backend backend)
    (cond
      (command-exists? "osascript") (osascript-terminal-backend)
      (command-exists? "wt.exe") "windows-terminal"
      :else "none")))

(defn display-name-for-role [role]
  (->> (str/split (str/replace role #"[-_]" " ") #"\s+")
       (remove str/blank?)
       (map str/capitalize)
       (str/join " ")))

(defn session-name-for-role [role]
  (str session-prefix "-" role))

(defn worktree-path-for-name [worktrees-dir worktree]
  (fs/path worktrees-dir worktree))

(defn tmux-agent-target [window pane-base-index session]
  (str session ":" window "." pane-base-index))

(defn tmux-option [tmux-socket option scope default-value]
  (let [args (case scope
               :session ["tmux" "-S" tmux-socket "show-options" "-gqv" option]
               :window ["tmux" "-S" tmux-socket "show-options" "-gwqv" option])
        result (apply process/sh (concat [{:continue true}] args))
        value (str/trim (:out result))]
    (if (re-matches #"[0-9]+" value)
      (Long/parseLong value)
      default-value)))

(defn detect-tmux-base-indexes [ctx]
  (fs/create-dirs (:tmux-socket-dir ctx))
  (let [probe-session (when-not (sh-ok? "tmux" "-S" (:tmux-socket ctx) "info")
                        (let [session (str "swarmforge-probe-" (.pid (java.lang.ProcessHandle/current)))]
                          (sh "tmux" "-S" (:tmux-socket ctx) "new-session" "-d" "-s" session "sleep 60")
                          session))
        window-base (tmux-option (:tmux-socket ctx) "base-index" :session 0)
        pane-base (tmux-option (:tmux-socket ctx) "pane-base-index" :window 0)]
    (when probe-session
      (process/sh {:continue true} "tmux" "-S" (:tmux-socket ctx) "kill-session" "-t" probe-session))
    (assoc ctx :tmux-window-base-index window-base :tmux-pane-base-index pane-base)))

(defn ensure-in-file! [file pattern]
  (fs/create-dirs (fs/parent file))
  (when-not (fs/exists? file)
    (spit (str file) ""))
  (let [lines (set (str/split-lines (slurp (str file))))]
    (when-not (contains? lines pattern)
      (spit (str file) (str pattern "\n") :append true))))

(defn ensure-initial-gitignore! [ctx]
  (let [gitignore (fs/path (:working-dir ctx) ".gitignore")]
    (if-not (fs/exists? gitignore)
      (spit (str gitignore) ".swarmforge/\n.worktrees/\n")
      (do
        (ensure-in-file! gitignore ".swarmforge/")
        (ensure-in-file! gitignore ".worktrees/")))))

(defn ensure-runtime-git-excludes! [ctx]
  (let [exclude-file (fs/path (sh-out "git" "-C" (str (:working-dir ctx)) "rev-parse" "--git-path" "info/exclude"))]
    (fs/create-dirs (fs/parent exclude-file))
    (ensure-in-file! exclude-file ".swarmforge/")
    (ensure-in-file! exclude-file ".worktrees/")))

(defn initialize-git-repo! [ctx]
  (when-not (fs/exists? (fs/path (:working-dir ctx) ".git"))
    (sh "git" "init" (str (:working-dir ctx)))
    (sh "git" "-C" (str (:working-dir ctx)) "branch" "-M" "master")
    (ensure-initial-gitignore! ctx)
    (sh "git" "-C" (str (:working-dir ctx)) "add" ".")
    (sh "git" "-C" (str (:working-dir ctx)) "commit" "-m" "Initial swarmforge repository")))

(def valid-agents #{"claude" "codex" "copilot" "grok"})
(def valid-receive-modes #{"task" "batch"})
(def shared-worktrees #{"none" "master"})

(defn config-error! [message]
  (fail! (str red "Error:" reset " " message)))

(defn config-line-fields! [line-no line]
  (let [fields (str/split line #"\s+")]
    (when (< (count fields) 4)
      (config-error! (str "Invalid config line " line-no ": " line)))
    fields))

(defn receive-mode-and-extra [trailing]
  (if (valid-receive-modes (first trailing))
    [(first trailing) (rest trailing)]
    ["task" trailing]))

(def window-directives
  #{"window" "window-invisible"})

(defn validate-directive! [line-no keyword]
  (when-not (contains? window-directives keyword)
    (config-error! (str "Unknown config directive on line " line-no ": " keyword
                        " (expected window or window-invisible)"))))

(defn visible-window-directive? [keyword]
  (= "window" keyword))

(defn validate-role-name! [line-no role]
  (when (str/includes? role "_")
    (config-error! (str "Invalid role '" role "' on line " line-no ": role names may not contain underscores"))))

(defn validate-unique-role! [ctx role roles]
  (when (contains? roles role)
    (config-error! (str "Duplicate role '" role "' in " (:config-file ctx)))))

(defn validate-unique-worktree! [ctx worktree worktrees]
  (when (and (not (shared-worktrees worktree)) (contains? worktrees worktree))
    (config-error! (str "Duplicate worktree '" worktree "' in " (:config-file ctx)))))

(defn validate-worktree-name! [role worktree]
  (when (or (str/includes? worktree "/") (#{"." ".."} worktree))
    (config-error! (str "Invalid worktree '" worktree "' for role '" role "'"))))

(defn validate-agent! [role agent]
  (when-not (valid-agents agent)
    (config-error! (str "Unsupported agent '" agent "' for role '" role "'"))))

(defn validate-receive-mode! [line-no role receive-mode]
  (when-not (valid-receive-modes receive-mode)
    (config-error! (str "Invalid receive mode '" receive-mode "' for role '" role "' on line " line-no ": expected task or batch"))))

(defn validate-role-prompt! [ctx role]
  (when-not (fs/exists? (fs/path (:roles-dir ctx) (str role ".prompt")))
    (config-error! (str "Missing role prompt " (fs/path (:roles-dir ctx) (str role ".prompt"))))))

(defn validate-config-row! [ctx line-no keyword role agent worktree receive-mode roles worktrees]
  (validate-directive! line-no keyword)
  (validate-role-name! line-no role)
  (validate-unique-role! ctx role roles)
  (validate-unique-worktree! ctx worktree worktrees)
  (validate-worktree-name! role worktree)
  (validate-agent! role agent)
  (validate-receive-mode! line-no role receive-mode)
  (validate-role-prompt! ctx role))

(defn worktree-path [ctx worktree]
  (if (shared-worktrees worktree)
    (:working-dir ctx)
    (worktree-path-for-name (:worktrees-dir ctx) worktree)))

(defn config-row [ctx role agent worktree receive-mode extra-arg-tokens visible?]
  {:role role
   :agent agent
   :session (session-name-for-role role)
   :display-name (display-name-for-role role)
   :worktree-name worktree
   :worktree-path (worktree-path ctx worktree)
   :receive-mode receive-mode
   :visible? visible?
   :extra-args (when (seq extra-arg-tokens)
                 (str/join " " extra-arg-tokens))})

(defn parse-config-row! [ctx line-no line roles worktrees]
  (let [[keyword role agent worktree & trailing] (config-line-fields! line-no line)
        agent (str/lower-case agent)
        [receive-mode extra-arg-tokens] (receive-mode-and-extra trailing)
        visible? (visible-window-directive? keyword)]
    (validate-config-row! ctx line-no keyword role agent worktree receive-mode roles worktrees)
    (config-row ctx role agent worktree receive-mode extra-arg-tokens visible?)))

(defn active-config-line? [raw-line]
  (let [line (str/trim raw-line)]
    (when-not (or (str/blank? line) (str/starts-with? line "#"))
      line)))

(defn parse-config-step [ctx {:keys [rows roles worktrees]} [line-index raw-line]]
  (if-let [line (active-config-line? raw-line)]
    (let [row (parse-config-row! ctx (inc line-index) line roles worktrees)
          worktree (:worktree-name row)]
      {:rows (conj rows row)
       :roles (conj roles (:role row))
       :worktrees (cond-> worktrees (not (shared-worktrees worktree)) (conj worktree))})
    {:rows rows :roles roles :worktrees worktrees}))

(defn parse-config-lines! [ctx]
  (:rows
   (reduce (partial parse-config-step ctx)
           {:rows [] :roles #{} :worktrees #{}}
           (map-indexed vector (str/split-lines (slurp (str (:config-file ctx))))))))

(defn parse-config [ctx]
  (when-not (fs/exists? (:config-file ctx))
    (config-error! (str "Config not found at " (:config-file ctx))))
  (when-not (fs/exists? (:constitution-file ctx))
    (config-error! (str "Constitution prompt not found at " (:constitution-file ctx))))
  (let [rows (parse-config-lines! ctx)]
    (when (empty? rows)
      (config-error! (str "No windows defined in " (:config-file ctx))))
    (assoc ctx :roles rows)))

(defn write-sessions-file! [ctx]
  (spit (str (:sessions-file ctx))
        (apply str
               (map-indexed
                (fn [index row]
                  (format "%d\t%s\t%s\t%s\t%s\n"
                          (inc index) (:role row) (:session row) (:display-name row) (:agent row)))
                (:roles ctx)))))

(defn write-roles-file! [ctx]
  (spit (str (:roles-file ctx))
        (apply str
               (for [row (:roles ctx)]
                 (format "%s\t%s\t%s\t%s\t%s\t%s\t%s\n"
                         (:role row)
                         (:worktree-name row)
                         (:worktree-path row)
                         (:session row)
                         (:display-name row)
                         (:agent row)
                         (:receive-mode row))))))

(def required-helpers
  ["bb.edn"
   "handoff_lib.clj" "swarm_handoff.sh" "swarm_handoff.clj"
   "ready_for_next.sh" "ready_for_next.clj"
   "done_with_current.sh" "done_with_current.clj"
   "ready_for_next_task.sh" "ready_for_next_task.clj"
   "done_with_current_task.sh" "done_with_current_task.clj"
   "ready_for_next_batch.sh" "ready_for_next_batch.clj"
   "done_with_current_batch.sh" "done_with_current_batch.clj"
   "handoffd.clj" "stop_handoff_daemon.clj" "stop_handoff_daemon.sh"
   "squadd.clj" "squadd.sh" "stop_squadd.clj" "stop_squadd.sh"
   "squad_assign.clj" "squad_assign.sh"
	   "squad_approval.clj" "squad_approval.sh"
   "squad_dashboard_request.clj" "squad_dashboard_request.sh"
	   "squad_batch.clj" "squad_batch.sh"
	   "squad_batch_story.clj" "squad_batch_story.sh"
	   "squad_config.clj"
   "squad_next.clj" "squad_next.sh"
   "squad_packet.clj" "squad_packet.sh"
   "squad_recover.clj" "squad_recover.sh"
   "squad_report.clj" "squad_report.sh"
   "squad_simulator.clj" "squad_simulator.sh"
   "squad_tool.clj" "squad_tool.sh" "squad_tool_table.clj" "install_bb_tool.sh"
   "squad_spawn_request.clj" "squad_spawn_request.sh"
   "swarm-cleanup.sh" "swarm-window-watchdog.sh" "swarm_window_watchdog.clj"
   "swarm-terminal-adapter.sh" "swarmforge.sh" "swarmforge.clj"])

(def non-executable-helpers
  #{"bb.edn"})

(def terminal-helpers
  ["terminal-app.sh" "iterm2.sh" "ghostty.sh" "windows-terminal.sh" "none.sh"])

(defn required-helper-ok? [path helper]
  (and (fs/exists? path)
       (or (contains? non-executable-helpers helper)
           (fs/executable? path))))

(defn terminal-helper-ok? [path]
  (and (fs/exists? path) (fs/executable? path)))

(defn ensure-required-helper! [ctx helper]
  (let [path (fs/path (:script-dir ctx) helper)]
    (when-not (required-helper-ok? path helper)
      (fail! (str red "Error:" reset " Required helper file not found or not usable: " path)))))

(defn ensure-terminal-helper! [ctx helper]
  (let [path (fs/path (:script-dir ctx) "terminal-adapters" helper)]
    (when-not (terminal-helper-ok? path)
      (fail! (str red "Error:" reset " Required terminal adapter not found or not executable: " path)))))

(defn check-helper-scripts! [ctx]
  (doseq [helper required-helpers]
    (ensure-required-helper! ctx helper))
  (doseq [helper terminal-helpers]
    (ensure-terminal-helper! ctx helper)))

(defn prepare-workspace! [ctx]
  (doseq [dir [(:state-dir ctx) (:notify-dir ctx) (:prompts-dir ctx)
               (:worktrees-dir ctx) (:tmux-socket-dir ctx) (:daemon-dir ctx)]]
    (fs/create-dirs dir))
  (spit (str (:tmux-socket-file ctx)) (str (:tmux-socket ctx) "\n"))
  (check-helper-scripts! ctx)
  (write-sessions-file! ctx)
  (write-roles-file! ctx))

(defn prepare-worktrees! [ctx]
  (doseq [row (:roles ctx)
          :let [worktree-name (:worktree-name row)
                worktree-path (:worktree-path row)
                branch-name (str "swarmforge-" worktree-name)]
          :when (not (#{"none" "master"} worktree-name))]
    (when-not (or (fs/exists? (fs/path worktree-path ".git"))
                  (fs/directory? (fs/path worktree-path ".git")))
      (sh "git" "-C" (str (:working-dir ctx)) "worktree" "add" "--force" "-B" branch-name (str worktree-path) "HEAD"))))

(defn prepare-handoff-dirs! [ctx]
  (doseq [row (:roles ctx)
          dir ["outbox/tmp" "sent" "failed" "inbox/new" "inbox/in_process" "inbox/completed"]]
    (fs/create-dirs (fs/path (:worktree-path row) ".swarmforge" "handoffs" dir))))

(defn write-tmux-env-file! [ctx]
  (spit (str (:tmux-env-file ctx))
        (str (sh-out "tmux" "-S" (:tmux-socket ctx) "display-message" "-p" "#{socket_path},#{pid},#{pane_id}") "\n")))

(defn sync-worktree-scripts! [ctx]
  (doseq [row (:roles ctx)
          :let [worktree-path (:worktree-path row)]
          :when (not= (str worktree-path) (str (:working-dir ctx)))]
    (let [role-scripts-dir (fs/path worktree-path "swarmforge" "scripts")
          role-state-dir (fs/path worktree-path ".swarmforge")]
      (fs/create-dirs role-scripts-dir)
      (doseq [entry (fs/list-dir (:script-dir ctx))]
        (let [target (fs/path role-scripts-dir (fs/file-name entry))]
          (if (fs/directory? entry)
            (fs/copy-tree entry target {:replace-existing true})
            (fs/copy entry target {:replace-existing true}))))
      (fs/create-dirs (fs/path role-state-dir "notify"))
      (fs/copy (:sessions-file ctx) (fs/path role-state-dir "sessions.tsv") {:replace-existing true})
      (fs/copy (:roles-file ctx) (fs/path role-state-dir "roles.tsv") {:replace-existing true})
      (fs/copy (:tmux-socket-file ctx) (fs/path role-state-dir "tmux-socket") {:replace-existing true})
      (fs/copy (:tmux-env-file ctx) (fs/path role-state-dir "tmux-env") {:replace-existing true}))))

(defn check-dependency! [command]
  (when-not (command-exists? command)
    (fail! (str red "Error:" reset " '" command "' is required but not installed."))))

(defn check-backend-dependencies! [ctx]
  (doseq [agent (map :agent (:roles ctx))]
    (check-dependency! agent)))

(defn create-role-session! [ctx session title]
  ;; Large default size + deep history so detached/agent panes are not stuck at ~25 lines.
  (sh "tmux" "-S" (:tmux-socket ctx) "new-session" "-d" "-s" session "-n" agent-window "-x" "200" "-y" "50")
  (sh "tmux" "-S" (:tmux-socket ctx) "rename-window" "-t" (str session ":" agent-window) title)
  (sh "tmux" "-S" (:tmux-socket ctx) "set-window-option" "-t" (str session ":" title) "allow-rename" "off")
  (sh "tmux" "-S" (:tmux-socket ctx) "set-option" "-t" session "history-limit" "50000"))

(defn write-agent-instruction-file! [role prompt-file]
  (spit (str prompt-file)
        (str "Read swarmforge/constitution.prompt, then read every file it refers to recursively, and obey all of those instructions.\n"
             "Read swarmforge/roles/" role ".prompt, then read every file it refers to recursively, and follow all of those instructions.\n")))

(defn extra-args-prefix [row]
  (let [args (:extra-args row)]
    (if (str/blank? args) "" (str args " "))))

(defn persistent-yolo-role?
  "SL and TS always run fully auto-approved (in-swarm operators)."
  [role]
  (contains? #{"squad-leader" "troubleshooter"} role))

(defn grok-wants-auto-approve? [row]
  (or (persistent-yolo-role? (:role row))
      (when-let [args (:extra-args row)]
        (or (str/includes? args "--always-approve")
            (str/includes? args "--yolo")
            (re-find #"--permission-mode\s+bypassPermissions" args)))))

(defn grok-permission-prefix [row]
  ;; acceptEdits only auto-approves file edits; bypassPermissions is the
  ;; CLI-enforced mode that matches --always-approve / --yolo / SL+TS.
  (if (grok-wants-auto-approve? row)
    "--permission-mode bypassPermissions "
    "--permission-mode acceptEdits "))

(declare agent-launchers)

(defn launch-command [ctx index row]
  (let [role (:role row)
        agent (:agent row)
        display (:display-name row)
        role-worktree (:worktree-path row)
        role-script-dir (if (= (str role-worktree) (str (:working-dir ctx)))
                          (:script-dir ctx)
                          (fs/path role-worktree "swarmforge" "scripts"))
        prompt-file (fs/path (:prompts-dir ctx) (str role ".md"))
        base (str "export SWARMFORGE_ROLE=" (sq role)
                  " && export PATH=" (sq (str role-script-dir)) ":$PATH"
                  " && cd " (sq (str role-worktree))
                  " && ")]
    (write-agent-instruction-file! role prompt-file)
    (cond-> (str base
                ((agent-launchers agent) ctx row prompt-file))
      (= index 0)
      (str "; exit_code=$?; SWARMFORGE_TERMINAL_BACKEND=" (sq (:terminal-backend ctx))
           " nohup " (sq (str (fs/path (:script-dir ctx) "swarm-cleanup.sh")))
           " " (sq (:tmux-socket ctx))
           " " (sq (str (:window-ids-file ctx)))
           (apply str (map #(str " " (sq (:session %))) (:roles ctx)))
           " >/dev/null 2>&1 &!; exit $exit_code"))))

(defn launch-role! [ctx index row]
  (let [session (:session row)
        display (:display-name row)
        prompt-file (fs/path (:prompts-dir ctx) (str (:role row) ".md"))
        command (launch-command ctx index row)]
    (sh "tmux" "-S" (:tmux-socket ctx) "send-keys" "-t"
        (tmux-agent-target display (:tmux-pane-base-index ctx) session)
        command "Enter")
    (println (str "  " cyan "[" display "]" reset " started in session " session))))

(defn stop-handoff-daemon! [ctx]
  (process/sh {:continue true}
              "bb" (str (fs/path (:script-dir ctx) "stop_handoff_daemon.clj"))
              (str (:working-dir ctx))))

(defn stop-squadd! [ctx]
  (when (fs/exists? (fs/path (:script-dir ctx) "stop_squadd.clj"))
    (process/sh {:continue true}
                "bb" (str (fs/path (:script-dir ctx) "stop_squadd.clj"))
                (str (:working-dir ctx)))))

(defn uname []
  (str/trim (:out (process/sh {:continue true} "uname" "-s"))))

(defn linux-systemd-running? []
  (let [result (process/sh {:continue true} "systemctl" "is-system-running")
        state (str/trim (:out result))]
    (#{"running" "degraded"} state)))

(defn darwin-sleep-inhibitor []
  (when (command-exists? "caffeinate")
    ["caffeinate" "-dims"]))

(defn linux-sleep-inhibitor []
  (when (and (command-exists? "systemd-inhibit")
             (command-exists? "systemctl")
             (linux-systemd-running?))
    ["systemd-inhibit"
     "--what=sleep:idle"
     "--who=SwarmForge"
     "--why=SwarmForge swarm is active"]))

(def sleep-inhibitors
  {"Darwin" darwin-sleep-inhibitor
   "Linux" linux-sleep-inhibitor})

(defn sleep-inhibitor-prefix []
  (when-not (= "0" (System/getenv "SWARMFORGE_PREVENT_SLEEP"))
    (when-let [inhibitor (sleep-inhibitors (uname))]
      (inhibitor))))

(defn claude-launch-command [_ row prompt-file]
  (let [perm (if (persistent-yolo-role? (:role row))
               "bypassPermissions"
               "acceptEdits")]
    (str "claude --append-system-prompt-file " (sq (str prompt-file))
         " --permission-mode " perm " -n " (sq (str "SwarmForge " (:display-name row))) " "
         (extra-args-prefix row)
         "\"$(cat " (sq (str prompt-file)) ")\"")))

(defn codex-launch-command [_ row prompt-file]
  ;; SL/TS always YOLO; same bypass as squad_spawn for transients.
  (str "codex -C " (sq (str (:worktree-path row))) " "
       (when (persistent-yolo-role? (:role row))
         "--dangerously-bypass-approvals-and-sandbox ")
       (extra-args-prefix row)
       "\"$(cat " (sq (str prompt-file)) ")\""))

(defn copilot-launch-command [_ row prompt-file]
  (str "copilot -C " (sq (str (:worktree-path row)))
       " --name " (sq (str "SwarmForge " (:display-name row))) " "
       (extra-args-prefix row)
       "-i \"$(cat " (sq (str prompt-file)) ")\""))

(defn grok-launch-command [_ row prompt-file]
  ;; --minimal: scrollback-native TUI so terminal/tmux scroll works.
  ;; --no-alt-screen: keep output in main buffer for capture-pane / host scroll.
  (str "grok --cwd " (sq (str (:worktree-path row))) " "
       "--minimal --no-alt-screen "
       (grok-permission-prefix row)
       (extra-args-prefix row)
       "--rules \"$(cat " (sq (str prompt-file)) ")\" --verbatim \"$(cat " (sq (str prompt-file)) ")\""))

(def agent-launchers
  {"claude" claude-launch-command
   "codex" codex-launch-command
   "copilot" copilot-launch-command
   "grok" grok-launch-command})

(defn start-handoff-daemon! [ctx]
  (fs/delete-if-exists (fs/path (:daemon-dir ctx) "stop"))
  (let [command (into (vec (sleep-inhibitor-prefix))
                      [(str (fs/path (:script-dir ctx) "handoffd.clj"))
                       (str (:working-dir ctx))])]
    (process/process command
                     {:dir "/"
                      :out (str (:handoff-daemon-log ctx))
                      :err :out})
    (println (str green "Started handoff daemon"
                  (when (> (count command) 2) " with OS sleep prevention")
                  "."
                  reset))))

(defn start-squadd! [ctx]
  (fs/delete-if-exists (fs/path (:daemon-dir ctx) "squadd.stop"))
  (let [command (into (vec (sleep-inhibitor-prefix))
                      [(str (fs/path (:script-dir ctx) "squadd.clj"))
                       (str (:working-dir ctx))])
        ;; Process stdout must not share squadd.log with log! appends — concurrent
        ;; writers tear event lines. Durable events go only through log!.
        stdout-file (str (fs/path (:daemon-dir ctx) "squadd.stdout"))]
    (process/process command
                     {:dir "/"
                      :out stdout-file
                      :err :out})
    (println (str green "Started squad daemon"
                  (when (> (count command) 2) " with OS sleep prevention")
                  "."
                  reset))))

(defn adapter-script [ctx command & args]
  (let [script (str "SCRIPT_DIR=" (sq (str (:script-dir ctx))) "\n"
                    "WORKING_DIR=" (sq (str (:working-dir ctx))) "\n"
                    "TMUX_SOCKET=" (sq (:tmux-socket ctx)) "\n"
                    "source " (sq (str (fs/path (:script-dir ctx) "swarm-terminal-adapter.sh")))
                    " && load_terminal_backend " (sq (:terminal-backend ctx))
                    " && " command
                    (apply str (map #(str " " (sq %)) args)))]
    ["zsh" "-c" script]))

(defn terminal-call [ctx command & args]
  (apply process/sh (apply adapter-script ctx command args)))

(defn terminal-call-ok? [ctx command & args]
  (zero? (:exit (apply process/sh (concat [{:continue true}] (apply adapter-script ctx command args))))))

(defn terminal-call-out [ctx command & args]
  (str/trim (:out (apply terminal-call ctx command args))))

(defn tracks-windows? [ctx]
  (terminal-call-ok? ctx "terminal_backend_tracks_windows"))

(defn reset-window-tracking! [ctx]
  (when (tracks-windows? ctx)
    (spit (str (:window-ids-file ctx)) "")
    (spit (str (:window-state-file ctx)) "")))

(defn record-window! [ctx index window-id row]
  (spit (str (:window-ids-file ctx)) (str window-id "\n") :append true)
  (spit (str (:window-state-file ctx))
        (format "%d\t%s\t%s\t%s\n" (inc index) window-id (:session row) (str "SwarmForge " (:display-name row)))
        :append true))

(defn visible-roles [ctx]
  "Roles that get a Terminal (or backend) surface at startup.
  window-invisible roles still get a detached tmux session + agent process."
  (filterv #(not= false (:visible? %)) (:roles ctx)))

(defn open-role-surfaces! [ctx]
  (loop [rows (visible-roles ctx)
         index 0
         previous-window-id ""]
    (when-let [row (first rows)]
      (let [window-id (terminal-call-out ctx "terminal_open_session" (:session row) (str "SwarmForge " (:display-name row)) previous-window-id)]
        (if (tracks-windows? ctx)
          (do
            (record-window! ctx index window-id row)
            (recur (next rows) (inc index) window-id))
          (recur (next rows) (inc index) previous-window-id))))))

(defn start-window-watchdog! [ctx]
  (process/process [(str (fs/path (:script-dir ctx) "swarm-window-watchdog.sh"))
                    (str (:window-state-file ctx))
                    (str (:window-ids-file ctx))
                    "1"
                    (:tmux-socket ctx)
                    (str (:working-dir ctx))
                    (:terminal-backend ctx)]
                   {:dir "/"
                    :out (str (:window-watchdog-log ctx))
                    :err :out}))

(defn handle-window-watchdog! [ctx]
  (if (tracks-windows? ctx)
    (start-window-watchdog! ctx)
    (println (str yellow (terminal-call-out ctx "terminal_backend_label") " surfaces are not trackable; window watchdog is disabled for this backend." reset))))

(defn attach-current-shell! [ctx]
  (println (str yellow "No terminal backend found; attaching current shell to '" (-> ctx :roles first :session) "' instead." reset))
  (sh "tmux" "-S" (:tmux-socket ctx) "attach-session" "-t" (-> ctx :roles first :session)))

(defn open-terminal-surfaces! [ctx]
  (if (terminal-call-ok? ctx "terminal_backend_can_open_sessions")
    (do
      (println (str "Opening separate " (terminal-call-out ctx "terminal_backend_label") " surfaces for each session..."))
      (reset-window-tracking! ctx)
      (open-role-surfaces! ctx)
      (handle-window-watchdog! ctx))
    (attach-current-shell! ctx)))

(defn context [working-dir]
  (let [working-dir (fs/absolutize (fs/path working-dir))
        script-dir (fs/parent *file*)
        swarm-forge-dir (fs/path working-dir "swarmforge")
        state-dir (fs/path working-dir ".swarmforge")
        daemon-dir (fs/path state-dir "daemon")
        crc (java.util.zip.CRC32.)
        _ (.update crc (.getBytes (str working-dir) java.nio.charset.StandardCharsets/UTF_8))
        socket-id (str (.getValue crc))
        tmux-socket-dir (fs/path "/tmp" (str "swarmforge-" (or (System/getenv "UID") (System/getProperty "user.name"))))
        tmux-socket (str (fs/path tmux-socket-dir (str socket-id ".sock")))]
    {:working-dir working-dir
     :script-dir script-dir
     :swarm-forge-dir swarm-forge-dir
     :worktrees-dir (fs/path working-dir ".worktrees")
     :config-file (fs/path swarm-forge-dir "swarmforge.conf")
     :roles-dir (fs/path swarm-forge-dir "roles")
     :constitution-file (fs/path swarm-forge-dir "constitution.prompt")
     :state-dir state-dir
     :notify-dir (fs/path state-dir "notify")
     :window-ids-file (fs/path state-dir "window-ids")
     :window-state-file (fs/path state-dir "windows.tsv")
     :window-watchdog-log (fs/path state-dir "window-watchdog.log")
     :sessions-file (fs/path state-dir "sessions.tsv")
     :roles-file (fs/path state-dir "roles.tsv")
     :prompts-dir (fs/path state-dir "prompts")
     :daemon-dir daemon-dir
     :handoff-daemon-log (fs/path daemon-dir "handoffd.log")
     :squadd-log (fs/path daemon-dir "squadd.log")
     :tmux-socket-dir tmux-socket-dir
     :tmux-socket tmux-socket
     :tmux-socket-file (fs/path state-dir "tmux-socket")
     :tmux-env-file (fs/path state-dir "tmux-env")
     :tmux-window-base-index 0
     :tmux-pane-base-index 0}))

(defn prepare-ctx [ctx]
  (-> ctx
      parse-config
      (assoc :terminal-backend (detect-terminal-backend))))

(defn test-parse! [root]
  (let [ctx (prepare-ctx (context root))]
    (prepare-workspace! ctx)
    (doseq [row (:roles ctx)]
      (println (str (:role row) " " (:display-name row) " " (:worktree-path row) " "
                    (:receive-mode row)
                    (when-let [extra (:extra-args row)] (str " " extra)))))
    (print (slurp (str (:roles-file ctx))))
    (print (slurp (str (:sessions-file ctx))))))

(defn check-runtime-dependencies! []
  (doseq [command ["tmux" "git" "bb"]]
    (check-dependency! command)))

(defn prepare-runtime-context! [root]
  (let [ctx (-> (context root)
                detect-tmux-base-indexes)]
    (initialize-git-repo! ctx)
    (ensure-runtime-git-excludes! ctx)
    (let [ctx (prepare-ctx ctx)]
      (check-backend-dependencies! ctx)
      (prepare-workspace! ctx)
      (prepare-worktrees! ctx)
      (prepare-handoff-dirs! ctx)
      (assoc ctx :terminal-backend (detect-terminal-backend)))))

(defn stop-existing-daemons! [ctx]
  (stop-squadd! ctx)
  (stop-handoff-daemon! ctx))

(defn kill-existing-role-sessions! [ctx]
  (doseq [row (:roles ctx)]
    (when (sh-ok? "tmux" "-S" (:tmux-socket ctx) "has-session" "-t" (:session row))
      (println (str yellow "Existing SwarmForge session found: " (:session row) ". Killing it..." reset))
      (sh "tmux" "-S" (:tmux-socket ctx) "kill-session" "-t" (:session row)))))

(defn print-start-banner! []
  (println (str cyan bold))
  (println "  SwarmForge v1.0 Starting")
  (println "  Disciplined agents build better software")
  (println reset))

(defn create-role-sessions! [ctx]
  (println (str green "Launching SwarmForge tmux sessions..." reset))
  (doseq [row (:roles ctx)]
    (create-role-session! ctx (:session row) (:display-name row))))

(defn launch-agents! [ctx]
  (println (str green "Starting agents..." reset))
  (let [delay-ms (env-long "SWARMFORGE_AGENT_START_DELAY_MS" 1500)]
    (doseq [[index row] (map-indexed vector (:roles ctx))]
      (when (pos? index)
        (Thread/sleep delay-ms))
      (launch-role! ctx index row))))

(defn print-ready-message! [ctx]
  (println)
  (println (str green bold "SwarmForge is ready." reset))
  (println "Working directory:" (str (:working-dir ctx)))
  (println "Sessions:")
  (doseq [row (:roles ctx)]
    (println (str "  " (:display-name row) ": " (:session row)
                  (when (false? (:visible? row)) " (invisible)"))))
  (println)
  (println (str green "Tip: Write a handoff draft and run swarm_handoff.sh while the swarm is running." reset))
  (println (str green "Tip: Reattach manually with 'tmux -S " (:tmux-socket ctx) " attach-session -t <session-name>' if needed." reset))
  (println (str green "Tip: Squad Leader and Troubleshooter are invisible by default; open via dashboard Open SL / chat or tmux attach." reset))
  (println))

(defn start-runtime! [ctx]
  (stop-existing-daemons! ctx)
  (kill-existing-role-sessions! ctx)
  (print-start-banner!)
  (create-role-sessions! ctx)
  (write-tmux-env-file! ctx)
  (sync-worktree-scripts! ctx)
  (start-squadd! ctx)
  (launch-agents! ctx)
  (print-ready-message! ctx)
  (open-terminal-surfaces! ctx))

(defn run-main! [root]
  (check-runtime-dependencies!)
  (start-runtime! (prepare-runtime-context! root)))

(defn test-terminal-bridge! [root backend]
  (let [local-script-dir (fs/path root "swarmforge" "scripts")
        ctx (cond-> (assoc (context root) :terminal-backend backend)
              (fs/exists? local-script-dir) (assoc :script-dir local-script-dir))]
    (println (terminal-call-out ctx "terminal_open_session" "swarmforge-specifier" "SwarmForge Specifier" ""))))

(defn test-tmux-base-indexes! [tmux-socket]
  (let [ctx (detect-tmux-base-indexes {:tmux-socket tmux-socket
                                        :tmux-socket-dir (str (fs/parent (fs/path tmux-socket)))})]
    (println (:tmux-window-base-index ctx) (:tmux-pane-base-index ctx))))

(defn test-launch-command! [root agent & [extra-args]]
  (let [ctx (assoc (context root) :terminal-backend "none")
        row {:role "coder"
             :agent agent
             :session "swarmforge-coder"
             :display-name "Coder"
             :worktree-name "master"
             :worktree-path (fs/path root)
             :receive-mode "task"
             :extra-args extra-args}]
    (fs/create-dirs (:prompts-dir ctx))
    (println (launch-command ctx 1 row))))

(defn test-sleep-inhibitor-prefix! []
  (println (str/join " " (or (sleep-inhibitor-prefix) []))))

(def main-commands
  {"--test-parse" (fn [args] (test-parse! (or (second args) (System/getProperty "user.dir"))))
   "--test-terminal-bridge" (fn [args] (test-terminal-bridge! (or (second args) (System/getProperty "user.dir")) (nth args 2)))
   "--test-launch-command" (fn [args] (apply test-launch-command!
                                             (or (second args) (System/getProperty "user.dir"))
                                             (drop 2 args)))
   "--test-agent-start-delay" (fn [_] (println (env-long "SWARMFORGE_AGENT_START_DELAY_MS" 1500)))
   "--test-sleep-inhibitor-prefix" (fn [_] (test-sleep-inhibitor-prefix!))
   "--test-tmux-base-indexes" (fn [args] (test-tmux-base-indexes! (second args)))})

(defn -main [& args]
  (if-let [command (main-commands (first args))]
    (command args)
    (run-main! (or (first args) (System/getProperty "user.dir")))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
