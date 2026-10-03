#!/usr/bin/env bb

(ns swarmforge
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(try
  (require 'ready-for-next-guard)
  (catch Exception _
    (load-file (str (fs/path (fs/parent *file*) "ready_for_next_guard.bb")))))

(def session-prefix "swarmforge")
(def agent-window "swarm")
(def pane-history-limit 10000)
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

(defn normalize-terminal-backend [backend]
  (case (str/lower-case backend)
    ("iterm" "iterm2" "iterm.app") "iterm2"
    ("terminal" "terminal-app" "terminal.app") "terminal-app"
    ("windows" "windows-terminal" "wt") "windows-terminal"
    ("none" "current" "fallback") "none"
    (str/lower-case backend)))

(defn detect-terminal-backend []
  (if-let [backend (System/getenv "SWARMFORGE_TERMINAL")]
    (normalize-terminal-backend backend)
    (cond
      (command-exists? "osascript") (if (= (System/getenv "TERM_PROGRAM") "iTerm.app")
                                      "iterm2"
                                      "terminal-app")
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

(defn config-fail! [message]
  (fail! (str red "Error:" reset " " message)))

(defn skip-config-line? [line]
  (or (str/blank? line)
      (str/starts-with? line "#")
      (re-matches #"merge-check(\s.*)?" line)))

(defn special-worktree? [worktree]
  (#{"none" "master"} worktree))

(defn visible-window? [directive line-no]
  (case directive
    "window" true
    "window-invisible" false
    (config-fail! (str "Unknown config directive on line " line-no ": " directive))))

(def receive-modes #{"task" "batch"})
(def propagation-modes #{"forward-only" "back-one" "back-all"})
(def known-agents #{"claude" "codex" "copilot" "grok"})

(defn receive-fields [trailing]
  (let [[receive-mode after-receive]
        (if (receive-modes (first trailing))
          [(first trailing) (rest trailing)]
          ["task" trailing])
        [propagation extra]
        (if (propagation-modes (first after-receive))
          [(first after-receive) (rest after-receive)]
          ["forward-only" after-receive])]
    [receive-mode propagation extra]))

(defn extra-args-str [tokens]
  (when (seq tokens)
    (str/join " " tokens)))

(defn reject-if [pred message]
  (when pred (config-fail! message)))

(defn validate-window! [ctx line-no role agent worktree receive-mode roles worktrees]
  (reject-if (str/includes? role "_")
             (str "Invalid role '" role "' on line " line-no ": role names may not contain underscores"))
  (reject-if (contains? roles role)
             (str "Duplicate role '" role "' in " (:config-file ctx)))
  (reject-if (and (not (special-worktree? worktree)) (contains? worktrees worktree))
             (str "Duplicate worktree '" worktree "' in " (:config-file ctx)))
  (reject-if (or (str/includes? worktree "/") (#{"." ".."} worktree))
             (str "Invalid worktree '" worktree "' for role '" role "'"))
  (reject-if (not (known-agents agent))
             (str "Unsupported agent '" agent "' for role '" role "'"))
  (reject-if (not (#{"task" "batch"} receive-mode))
             (str "Invalid receive mode '" receive-mode "' for role '" role "' on line " line-no ": expected task or batch"))
  (reject-if (not (fs/exists? (fs/path (:roles-dir ctx) (str role ".prompt"))))
             (str "Missing role prompt " (fs/path (:roles-dir ctx) (str role ".prompt")))))

(defn window-row [ctx role agent worktree receive-mode propagation extra-args visible?]
  {:role role
   :agent agent
   :session (session-name-for-role role)
   :display-name (display-name-for-role role)
   :worktree-name worktree
   :worktree-path (if (special-worktree? worktree)
                    (:working-dir ctx)
                    (worktree-path-for-name (:worktrees-dir ctx) worktree))
   :receive-mode receive-mode
   :propagation propagation
   :extra-args extra-args
   :visible? visible?})

(defn parse-window-line [ctx line-no line roles worktrees]
  (let [fields (str/split line #"\s+")]
    (reject-if (< (count fields) 4)
               (str "Invalid config line " line-no ": " line))
    (let [[directive role agent worktree & trailing] fields
          agent (str/lower-case agent)
          [receive-mode propagation extra-tokens] (receive-fields trailing)
          visible? (visible-window? directive line-no)]
      (validate-window! ctx line-no role agent worktree receive-mode roles worktrees)
      (window-row ctx role agent worktree receive-mode propagation (extra-args-str extra-tokens) visible?))))

(def setting-directives
  "Optional single-line settings in swarmforge.conf (not role windows):
  dashboard-port <port>   keep the dashboard on this localhost port
  notify-cmd <command>    run on a new clarification or pending approval (read by pack_web)"
  #{"dashboard-port" "notify-cmd"})

(defn setting-line [line]
  (let [[directive value] (str/split (str/trim line) #"\s+" 2)]
    (when (setting-directives directive)
      [directive (str/trim (or value ""))])))

(defn valid-port? [value]
  (boolean (and (re-matches #"[0-9]{1,5}" (or value ""))
                (<= 1 (Long/parseLong value) 65535))))

(defn validate-setting! [line-no [directive value]]
  (case directive
    "dashboard-port"
    (reject-if (not (valid-port? value))
               (str "Invalid dashboard-port on line " line-no ": expected a port from 1 to 65535"))
    "notify-cmd"
    (reject-if (str/blank? value)
               (str "Missing command for notify-cmd on line " line-no))))

(defn config-setting [ctx directive]
  (let [file (:config-file ctx)]
    (when (fs/regular-file? file)
      (some (fn [raw]
              (let [line (str/trim raw)]
                (when-not (skip-config-line? line)
                  (when-let [[found value] (setting-line line)]
                    (when (= directive found) (not-empty value))))))
            (str/split-lines (slurp (str file)))))))

(defn require-master-worktree! [rows]
  (let [masters (filterv #(= "master" (:worktree-name %)) rows)]
    (reject-if (not= 1 (count masters))
               "Config must name exactly one master worktree")))

(defn parse-config [ctx]
  (when-not (fs/exists? (:config-file ctx))
    (config-fail! (str "Config not found at " (:config-file ctx))))
  (when-not (fs/exists? (:constitution-file ctx))
    (config-fail! (str "Constitution prompt not found at " (:constitution-file ctx))))
  (loop [lines (map-indexed vector (str/split-lines (slurp (str (:config-file ctx)))))
         rows []
         roles #{}
         worktrees #{}]
    (if-let [[line-index raw-line] (first lines)]
      (let [line-no (inc line-index)
            line (str/trim raw-line)]
        (cond
          (skip-config-line? line)
          (recur (next lines) rows roles worktrees)

          (setting-line line)
          (do (validate-setting! line-no (setting-line line))
              (recur (next lines) rows roles worktrees))

          :else
          (let [row (parse-window-line ctx line-no line roles worktrees)
                worktree (:worktree-name row)]
            (recur (next lines)
                   (conj rows row)
                   (conj roles (:role row))
                   (cond-> worktrees (not (special-worktree? worktree)) (conj worktree))))))
      (do
        (reject-if (empty? rows)
                   (str "No windows defined in " (:config-file ctx)))
        (require-master-worktree! rows)
        (assoc ctx :roles rows)))))

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
                 (format "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n"
                         (:role row)
                         (:worktree-name row)
                         (:worktree-path row)
                         (:session row)
                         (:display-name row)
                         (:agent row)
                         (:receive-mode row)
                         (:propagation row))))))

(def required-helpers
  ["handoff_lib.bb" "swarm_handoff.sh" "swarm_handoff.bb"
   "swarm_tool.sh" "swarm_tool.bb"
   "commit-msg-hook.sh" "commit_msg_hook.bb"
   "merge_and_process.sh" "merge_and_process.bb"
   "ready_for_next.sh" "ready_for_next.bb"
   "ready_for_next_guard.bb"
   "done_with_current.sh" "done_with_current.bb"
   "ready_for_next_task.sh" "ready_for_next_task.bb"
   "done_with_current_task.sh" "done_with_current_task.bb"
   "ready_for_next_batch.sh" "ready_for_next_batch.bb"
   "done_with_current_batch.sh" "done_with_current_batch.bb"
   "handoffd.bb" "stop_handoff_daemon.bb" "stop_handoff_daemon.sh"
   "swarm-cleanup.sh" "swarm-window-watchdog.sh" "swarm_window_watchdog.bb"
   "swarm-terminal-adapter.sh" "swarmforge.sh" "swarmforge.bb"
   "pack_board.sh" "pack_board.bb"
   "pack_web.sh" "pack_web.bb"
   "pack_dashboard_request.sh" "pack_dashboard_request.bb"])

(def terminal-helpers
  ["terminal-app.sh" "iterm2.sh" "ghostty.sh" "windows-terminal.sh" "none.sh"])

(defn check-helper-scripts! [ctx]
  (doseq [helper required-helpers]
    (let [path (fs/path (:script-dir ctx) helper)]
      (when-not (and (fs/exists? path) (fs/executable? path))
        (fail! (str red "Error:" reset " Required helper script not found or not executable: " path)))))
  (doseq [helper terminal-helpers]
    (let [path (fs/path (:script-dir ctx) "terminal-adapters" helper)]
      (when-not (and (fs/exists? path) (fs/executable? path))
        (fail! (str red "Error:" reset " Required terminal adapter not found or not executable: " path))))))

(defn git-hooks-dir [ctx]
  (let [path (sh-out "git" "-C" (str (:working-dir ctx)) "rev-parse" "--git-path" "hooks")
        dir (fs/path path)]
    (if (fs/absolute? dir)
      dir
      (fs/path (:working-dir ctx) dir))))

(defn install-commit-msg-hook! [ctx]
  (let [dir (git-hooks-dir ctx)
        hook (fs/path dir "commit-msg")
        bb (str (fs/absolutize (fs/path (:script-dir ctx) "commit_msg_hook.bb")))]
    (fs/create-dirs dir)
    (spit (str hook)
          (str "#!/usr/bin/env zsh\n"
               "set -euo pipefail\n"
               "exec bb " (sq bb) " \"$@\"\n"))
    (fs/set-posix-file-permissions hook "rwxr-xr-x")))

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

(defn copy-tree-into! [src dest]
  (when (fs/directory? src)
    (fs/create-dirs dest)
    (fs/copy-tree src dest {:replace-existing true})))

(def synced-role-paths
  ["swarmforge/roles" "swarmforge/constitution" "swarmforge/constitution.prompt"])

(defn git-in [dir & args]
  (apply process/sh {:continue true :dir (str dir)} "git" args))

(defn master-tracked-role-files [ctx]
  (let [result (apply git-in (:working-dir ctx) "ls-files" "--" synced-role-paths)]
    (if (zero? (:exit result))
      (vec (remove str/blank? (str/split-lines (:out result))))
      [])))

(defn mid-merge? [worktree-path]
  (zero? (:exit (git-in worktree-path "rev-parse" "-q" "--verify" "MERGE_HEAD"))))

(defn master-dirty-role-files
  "Synced paths whose master copy differs from master's HEAD. Committing such a
  copy into a role branch makes master's later merge of that branch abort with
  \"local changes would be overwritten\"."
  [ctx]
  (let [result (apply git-in (:working-dir ctx) "diff" "--name-only" "HEAD" "--" synced-role-paths)]
    (if (zero? (:exit result))
      (set (remove str/blank? (str/split-lines (:out result))))
      #{})))

(defn use-master-head-for-dirty! [ctx worktree-path dirty]
  (when (seq dirty)
    (println (str yellow "Warning: uncommitted edits in the project checkout are not synced to "
                  worktree-path "; it gets master's committed version of: "
                  (str/join ", " (sort dirty))
                  ". Commit them on master and restart to sync them." reset))
    (if (mid-merge? worktree-path)
      dirty
      (let [head (str/trim (:out (git-in (:working-dir ctx) "rev-parse" "HEAD")))]
        (set (remove #(zero? (:exit (git-in worktree-path "checkout" head "--" %))) dirty))))))

(defn commit-synced-roles!
  "Commit the synced prompts so a role's later merge of master is clean. Paths
  with uncommitted master edits get master's HEAD version; one not in HEAD yet
  is left out of the commit."
  [ctx worktree-path]
  (let [unsynced (or (use-master-head-for-dirty! ctx worktree-path (master-dirty-role-files ctx)) #{})
        files (filterv #(and (fs/exists? (fs/path worktree-path %)) (not (unsynced %)))
                       (master-tracked-role-files ctx))]
    (when (seq files)
      (apply git-in worktree-path "add" "--" files)
      (when-not (zero? (:exit (apply git-in worktree-path "diff" "--cached" "--quiet" "--" files)))
        (if (mid-merge? worktree-path)
          (println (str yellow "Warning: " worktree-path " is mid-merge; synced role prompts left uncommitted." reset))
          (let [result (apply git-in worktree-path "commit" "-q" "--no-verify"
                              "-m" "Sync SwarmForge roles and constitution from the project checkout"
                              "--" files)]
            (when-not (zero? (:exit result))
              (println (str yellow "Warning: could not commit synced role prompts in " worktree-path ": "
                            (str/trim (str (:err result) (:out result))) reset)))))))))

(def shared-article-names ["engineering.prompt" "workflow.prompt" "handoffs.prompt"])

(defn install-shared-articles!
  "A pack installed by its `swarm` wrapper carries main's articles in
  scripts/shared-articles. Put the shared ones where the constitution reads
  articles; the pack's own project and local-* articles stay as they are."
  [ctx]
  (let [src-dir (fs/path (:script-dir ctx) "shared-articles")
        dest-dir (fs/path (:swarm-forge-dir ctx) "constitution" "articles")]
    (doseq [name shared-article-names
            :let [src (fs/path src-dir name)]
            :when (fs/regular-file? src)]
      (fs/create-dirs dest-dir)
      (fs/copy src (fs/path dest-dir name) {:replace-existing true}))))

(defn sync-worktree-roles! [ctx worktree-path]
  (copy-tree-into! (:roles-dir ctx) (fs/path worktree-path "swarmforge" "roles"))
  (copy-tree-into! (fs/path (:swarm-forge-dir ctx) "constitution")
                   (fs/path worktree-path "swarmforge" "constitution"))
  (when (fs/exists? (:constitution-file ctx))
    (fs/create-dirs (fs/path worktree-path "swarmforge"))
    (fs/copy (:constitution-file ctx)
             (fs/path worktree-path "swarmforge" "constitution.prompt")
             {:replace-existing true}))
  (commit-synced-roles! ctx worktree-path))

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
      (sync-worktree-roles! ctx worktree-path)
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
  (sh "tmux" "-S" (:tmux-socket ctx) "new-session" "-d" "-s" session "-n" agent-window)
  (sh "tmux" "-S" (:tmux-socket ctx) "set-option" "-t" session "history-limit" (str pane-history-limit))
  (sh "tmux" "-S" (:tmux-socket ctx) "rename-window" "-t" (str session ":" agent-window) title)
  (sh "tmux" "-S" (:tmux-socket ctx) "set-window-option" "-t" (str session ":" title) "allow-rename" "off"))

(def aps-tool-purpose
  {"gherkin-parser" "APS parsing"
   "ir-dry-checker" "IR DRY"
   "gherkin-mutator" "Gherkin mutation"})

(def role-required-tools
  {"specifier" ["gherkin-parser" "ir-dry-checker"]
   "coder" ["gherkin-parser"]
   "refactorer" ["gherkin-parser"]
   "hardender" ["gherkin-parser" "gherkin-mutator"]
   "architect" ["gherkin-parser" "gherkin-mutator"]
   "QA" ["gherkin-parser"]})

(defn require-ensure-lines [tools]
  (apply str
         (for [tool tools]
           (str "- `" tool "` (" (get aps-tool-purpose tool) "): `swarm_tool.sh require " tool "`\n"
                "  If missing, run exactly: `swarm_tool.sh ensure " tool "`\n"))))

(defn parse-dry-check-lines [tools]
  (str (when (some #{"gherkin-parser"} tools)
         "- Parse with the two-arg form: `gherkin-parser <feature> ./tmp/<stem>.json`\n")
       (when (some #{"ir-dry-checker"} tools)
         "- Dry-check with the two-arg form: `ir-dry-checker <ir> ./tmp/<stem>.dry.json`\n")))

(defn tool-startup-section [role last-role?]
  (let [tools (get role-required-tools role [])]
    (str "## Tool Startup\n\n"
         "- Do not search `$HOME` or run `find` for APS tools.\n"
         (require-ensure-lines tools)
         (parse-dry-check-lines tools)
         "- Write scratch files and handoff drafts in `./tmp/` in the assigned worktree.\n"
         "- Do not use `/tmp` or `.swarmforge/handoffs/outbox/tmp/` as scratch.\n"
         "- Receive with `ready_for_next.sh`. Send with `swarm_handoff.sh ./tmp/<draft>`.\n"
         "- Do not search the tree or `$HOME` for those scripts.\n"
         "- Do not invoke helpers as `./swarmforge/scripts/...`. They are already on PATH.\n"
         "- Board cards live in `.swarmforge/board/tasks.tsv`. Use that card name as `task:`.\n"
         "- Operator task documents live in `tasks/<task-name>.md`. Re-read that file as operator intent. The master agent commits it with the task's first git work.\n"
         "- A retry audit may include remedial comments on named documents. Read those comments as findings.\n"
         "- Do not search the worktree for `.swarmforge/board/tasks.tsv`. That file is on the project (master).\n"
         "- Use TASK_NAME from `ready_for_next.sh` or the inbound `task:` header. For a batch, that name is the top item. The helper fills `task:` from the in-process batch, else the sender-lane card.\n"
         "- Do not invent a name or hunt `sessions.tsv`.\n"
         "- Constitution tools: `swarm_tool.sh require crap4clj` (also dry4clj, clj-mutate, cloverage, speclj, speclj-structure-check, crap4py, mutate4py, symilar, pytest, coverage, stryker, crap4js, jscpd, vitest, APS, or the language table). If missing, `swarm_tool.sh ensure <tool>`. Do not invent project `bb` proxies.\n"
         "- Run constitution tools one at a time. Worker-limited tools use `--max-workers 4` or `--workers 4`. Mutation is differential: no `--mutate-all`, no `--level full`.\n"
         "- Do not clone those repos into `./tmp`.\n"
         "- If merge_and_process.sh or ready_for_next reports a merge conflict, resolve the conflicted files, git add, and commit. Do not invent git merge. Parallel cards on one tree will conflict; that is expected.\n"
         "- Operator follow-ups arrive as `[id] text` in this pane. Answer with `pack_dashboard_request.sh answer <id> ./tmp/answer.txt`.\n"
         "- Ask the operator with `pack_dashboard_request.sh clarify ./tmp/question.txt`. Do not ask in the pane.\n"
         "- Do not ask for approval in the pane. Queue `git_handoff`; the operator uses Attention.\n"
         (when last-role?
           (str "- You are the last role in this pack. After this pack step, queue a git_handoff. The helper marks the card Done. Do not list every other role on to: to finish the card.\n"))
         (when (= role "specifier")
           (str "- Specify from the board card and the current product tree. Do not import behavior from sibling projects.\n"
                "- Do not ask the operator what new feature to specify or what the card already states.\n"
                "- Finish the assigned TASK_NAME and payload (the whole card), then one git_handoff. Do not hand off after the first feature in a folder.\n"))
         (when (= role "QA")
           (str "- One commit is one git_handoff. Do not send two git_handoffs of the same SHA.\n")))))

(defn last-pack-role? [ctx role]
  (and (not= role "lieutenant")
       (= role (:role (last (:roles ctx))))))

(defn write-agent-instruction-file! [ctx role prompt-file last-role?]
  (if (= role "lieutenant")
    (fs/copy (fs/path (:roles-dir ctx) "lieutenant.prompt")
             prompt-file
             {:replace-existing true})
    (spit (str prompt-file)
          (str "Read swarmforge/constitution.prompt, then read every file it refers to recursively, and obey all of those instructions.\n"
               "Read swarmforge/roles/" role ".prompt, then read every file it refers to recursively, and follow all of those instructions.\n"
               "\n"
               (tool-startup-section role last-role?)))))

(defn standing-instruction-sources [ctx role]
  (let [constitution-dir (fs/path (:swarm-forge-dir ctx) "constitution")]
    (->> (concat [(:constitution-file ctx)]
                 (when (fs/directory? constitution-dir)
                   (sort-by str (filter fs/regular-file? (fs/glob constitution-dir "**"))))
                 [(fs/path (:roles-dir ctx) (str role ".prompt"))])
         (filter fs/regular-file?))))

(defn file-section [ctx file]
  (str "\n\n----- " (fs/relativize (:working-dir ctx) file) " -----\n\n"
       (slurp (str file))))

(defn write-system-instruction-files!
  "The standing instructions an agent keeps across context compaction: the
  launch prompt plus the full constitution and role prompt text. Codex reads
  them as a TOML string for `-c developer_instructions=`."
  [ctx role prompt-file]
  (let [text (apply str (slurp (str prompt-file))
                    (map #(file-section ctx %) (standing-instruction-sources ctx role)))
        system-file (fs/path (:prompts-dir ctx) (str role ".system.md"))
        toml-file (fs/path (:prompts-dir ctx) (str role ".system.toml-string"))]
    (spit (str system-file) text)
    (spit (str toml-file) (json/generate-string text))
    {:system-file system-file :toml-file toml-file}))

(defn extra-args-prefix [row]
  (let [args (:extra-args row)]
    (if (str/blank? args) "" (str args " "))))

(defn extra-has? [row needle]
  (str/includes? (or (:extra-args row) "") needle))

(defn yolo-flag [agent row]
  (case agent
    "codex" (if (extra-has? row "--yolo") "" "--yolo ")
    "copilot" (if (extra-has? row "--yolo") "" "--yolo ")
    "claude" (if (extra-has? row "bypassPermissions") "" "--permission-mode bypassPermissions ")
    ""))

(defn grok-permission-prefix [row]
  "--permission-mode bypassPermissions ")

(defn alt-screen-env [agent row]
  (if (and (= agent "claude")
           (not (extra-has? row "CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN")))
    "CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN=1 "
    ""))

(defn no-alt-screen-flag [agent row]
  (if (and (#{"codex" "copilot"} agent)
           (not (extra-has? row "--no-alt-screen")))
    "--no-alt-screen "
    ""))

(defn launch-command [ctx index row]
  (let [role (:role row)
        agent (:agent row)
        display (:display-name row)
        role-worktree (:worktree-path row)
        role-script-dir (if (= (str role-worktree) (str (:working-dir ctx)))
                          (:script-dir ctx)
                          (fs/path role-worktree "swarmforge" "scripts"))
        prompt-file (fs/path (:prompts-dir ctx) (str role ".md"))
        tool-bin (fs/path (:working-dir ctx) ".swarmforge" "bin")
        prompt (str "\"$(cat " (sq (str prompt-file)) ")\"")
        initial-prompt? (not= role "lieutenant")
        base (str "export SWARMFORGE_ROLE=" (sq role)
                  " && export PATH=" (sq (str tool-bin)) ":" (sq (str role-script-dir)) ":$PATH"
                  " && cd " (sq (str role-worktree))
                  " && ")
        _ (write-agent-instruction-file! ctx role prompt-file (last-pack-role? ctx role))
        system (when initial-prompt? (write-system-instruction-files! ctx role prompt-file))
        system-file (or (:system-file system) prompt-file)]
    (cond-> (str base
                (case agent
                  "claude" (str (alt-screen-env agent row)
                                "claude --append-system-prompt-file " (sq (str system-file)) " "
                                (yolo-flag agent row) "-n " (sq (str "SwarmForge " display)) " "
                                (extra-args-prefix row)
                                (when initial-prompt? prompt))
                  "codex" (str "codex -C " (sq (str role-worktree)) " "
                               (no-alt-screen-flag agent row) (yolo-flag agent row)
                               (when system
                                 (str "-c developer_instructions=\"$(cat " (sq (str (:toml-file system))) ")\" "))
                               (extra-args-prefix row)
                               (when initial-prompt? prompt))
                  "copilot" (str "copilot -C " (sq (str role-worktree)) " "
                                 (no-alt-screen-flag agent row)
                                 "--name " (sq (str "SwarmForge " display)) " "
                                 (yolo-flag agent row) (extra-args-prefix row)
                                 (when initial-prompt? (str "-i " prompt)))
                  "grok" (str "grok --cwd " (sq (str role-worktree)) " "
                              (grok-permission-prefix row) (extra-args-prefix row)
                              "--minimal --rules \"$(cat " (sq (str system-file)) ")\""
                              (when initial-prompt? (str " --verbatim " prompt)))))
      (= index 0)
      (str "; exit_code=$?; SWARMFORGE_TERMINAL_BACKEND=" (sq (:terminal-backend ctx))
           " nohup " (sq (str (fs/path (:script-dir ctx) "swarm-cleanup.sh")))
           " " (sq (:tmux-socket ctx))
           " " (sq (str (:window-ids-file ctx)))
           (apply str (map #(str " " (sq (:session %))) (:roles ctx)))
           " >/dev/null 2>&1 &!; exit $exit_code"))))

(defn codex-home []
  (or (not-empty (System/getenv "CODEX_HOME"))
      (str (fs/path (System/getProperty "user.home") ".codex"))))

(defn project-table-header [dir]
  (str "[projects." (pr-str (str (fs/absolutize dir))) "]"))

(defn ensure-newline [text]
  (cond
    (str/blank? text) ""
    (str/ends-with? text "\n") text
    :else (str text "\n")))

(defn ensure-codex-trust! [dir]
  (when-not (str/blank? (str dir))
    (let [home (codex-home)
          cfg (fs/path home "config.toml")
          header (project-table-header dir)
          text (if (fs/exists? cfg) (slurp (str cfg)) "")]
      (when-not (str/includes? text header)
        (fs/create-dirs home)
        (spit (str cfg)
              (str (ensure-newline text)
                   "\n" header "\ntrust_level = \"trusted\"\n"))))))

(defn launch-role! [ctx index row]
  (when (= "codex" (:agent row))
    (ensure-codex-trust! (:worktree-path row)))
  (let [session (:session row)
        display (:display-name row)
        command (launch-command ctx index row)]
    (sh "tmux" "-S" (:tmux-socket ctx) "send-keys" "-t"
        (tmux-agent-target display (:tmux-pane-base-index ctx) session)
        command "Enter")
    (println (str "  " cyan "[" display "]" reset " started in session " session))))

(defn stop-handoff-daemon! [ctx]
  (process/sh {:continue true}
              "bb" (str (fs/path (:script-dir ctx) "stop_handoff_daemon.bb"))
              (str (:working-dir ctx))))

(defn uname []
  (str/trim (:out (process/sh {:continue true} "uname" "-s"))))

(defn linux-systemd-running? []
  (let [result (process/sh {:continue true} "systemctl" "is-system-running")
        state (str/trim (:out result))]
    (#{"running" "degraded"} state)))

(defn sleep-inhibitor-prefix []
  (when-not (= "0" (System/getenv "SWARMFORGE_PREVENT_SLEEP"))
    (case (uname)
      "Darwin" (when (command-exists? "caffeinate")
                 ["caffeinate" "-dims"])
      "Linux" (when (and (command-exists? "systemd-inhibit")
                         (command-exists? "systemctl")
                         (linux-systemd-running?))
                ["systemd-inhibit"
                 "--what=sleep:idle"
                 "--who=SwarmForge"
                 "--why=SwarmForge swarm is active"])
      nil)))

(defn start-handoff-daemon! [ctx]
  (fs/delete-if-exists (fs/path (:daemon-dir ctx) "stop"))
  (let [command (into (vec (sleep-inhibitor-prefix))
                      [(str (fs/path (:script-dir ctx) "handoffd.bb"))
                       (str (:working-dir ctx))])]
    (process/process command
                     {:out (str (:handoff-daemon-log ctx))
                      :err :out})
    (println (str green "Started handoff daemon"
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

(defn skip-terminal? [row]
  (not (:visible? row)))

(defn record-window! [ctx index window-id row]
  (spit (str (:window-ids-file ctx)) (str window-id "\n") :append true)
  (spit (str (:window-state-file ctx))
        (format "%d\t%s\t%s\t%s\n"
                (inc index) window-id (:session row)
                (str "SwarmForge " (:display-name row)))
        :append true))

(defn open-one-session! [ctx row previous-window-id]
  (terminal-call-out ctx "terminal_open_session"
                     (:session row)
                     (str "SwarmForge " (:display-name row))
                     previous-window-id))

(defn open-role-terminal! [ctx row previous-window-id index]
  (if (skip-terminal? row)
    previous-window-id
    (let [window-id (open-one-session! ctx row previous-window-id)]
      (when (terminal-call-ok? ctx "terminal_backend_tracks_windows")
        (record-window! ctx index window-id row))
      window-id)))

(defn start-window-watchdog! [ctx]
  (process/process [(str (fs/path (:script-dir ctx) "swarm-window-watchdog.sh"))
                    (str (:window-state-file ctx))
                    (str (:window-ids-file ctx))
                    "1"
                    (:tmux-socket ctx)
                    (str (:working-dir ctx))
                    (:terminal-backend ctx)]
                   {:out (str (:window-watchdog-log ctx))
                    :err :out}))

(defn open-sessions-in-terminals! [ctx]
  (println (str "Opening separate " (terminal-call-out ctx "terminal_backend_label") " surfaces for each session..."))
  (when (terminal-call-ok? ctx "terminal_backend_tracks_windows")
    (spit (str (:window-ids-file ctx)) "")
    (spit (str (:window-state-file ctx)) ""))
  (loop [rows (:roles ctx)
         index 0
         previous-window-id ""]
    (when-let [row (first rows)]
      (let [window-id (open-role-terminal! ctx row previous-window-id index)]
        (if (terminal-call-ok? ctx "terminal_backend_tracks_windows")
          (recur (next rows) (inc index) window-id)
          (recur (next rows) (inc index) previous-window-id)))))
  (if (terminal-call-ok? ctx "terminal_backend_tracks_windows")
    (start-window-watchdog! ctx)
    (println (str yellow (terminal-call-out ctx "terminal_backend_label")
                  " surfaces are not trackable; window watchdog is disabled for this backend." reset))))

(defn attach-fallback! [ctx]
  (let [row (or (first (remove skip-terminal? (:roles ctx)))
                (first (:roles ctx)))]
    (println (str yellow "No terminal backend found; attaching current shell to '"
                  (:session row) "' instead." reset))
    (sh "tmux" "-S" (:tmux-socket ctx) "attach-session" "-t" (:session row))))

(defn clear-window-state! [ctx]
  (spit (str (:window-ids-file ctx)) "")
  (spit (str (:window-state-file ctx)) ""))

(defn open-terminal-surfaces! [ctx]
  (cond
    (every? skip-terminal? (:roles ctx))
    (do
      (clear-window-state! ctx)
      (println (str yellow "No visible Terminal surfaces; use the dashboard." reset)))

    (terminal-call-ok? ctx "terminal_backend_can_open_sessions")
    (open-sessions-in-terminals! ctx)

    :else
    (attach-fallback! ctx)))

(defn terminal-plan-line [row]
  (if (skip-terminal? row)
    (str "skip-terminal " (:role row))
    (str "open-terminal " (:role row))))

(defn launch-plan-lines [ctx]
  (cons "pack_web start" (map terminal-plan-line (:roles ctx))))

(defn wait-for-file [path timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (fs/exists? path) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 50) (recur))))))

(defn dashboard-url-file [ctx]
  (fs/path (:state-dir ctx) "dashboard-url"))

(defn pack-web-pid-file [ctx]
  (fs/path (:state-dir ctx) "pack_web.pid"))

(defn stop-existing-pack-web! [ctx]
  (let [file (pack-web-pid-file ctx)
        pid (when (fs/regular-file? file)
              (not-empty (str/trim (slurp (str file)))))]
    (when pid
      (process/sh {:continue true} "kill" "-TERM" pid))
    (fs/delete-if-exists file)
    (fs/delete-if-exists (dashboard-url-file ctx))))

(defn open-browser? []
  (not= "0" (System/getenv "SWARMFORGE_OPEN_BROWSER")))

(defn maybe-open-browser! [url]
  (when (and (open-browser?) (command-exists? "open"))
    (process/sh {:continue true} "open" url)))

(defn dashboard-port-file [ctx]
  (fs/path (:state-dir ctx) "dashboard-port"))

(defn previous-dashboard-port [ctx]
  (let [file (dashboard-port-file ctx)]
    (when (fs/regular-file? file)
      (let [value (str/trim (slurp (str file)))]
        (when (valid-port? value) value)))))

(defn port-free? [port]
  (try
    (with-open [_ (java.net.ServerSocket. (Integer/parseInt port) 1
                                          (java.net.InetAddress/getByName "127.0.0.1"))]
      true)
    (catch Exception _ false)))

(defn wait-port-free [port timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (port-free? port) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 100) (recur))))))

(defn dashboard-port
  "The configured dashboard-port, else the port of the previous run when it
  is free again, else nil (pack_web then picks a free port)."
  [ctx]
  (let [configured (config-setting ctx "dashboard-port")
        previous (previous-dashboard-port ctx)]
    (cond
      (valid-port? configured) (do (wait-port-free configured 3000) configured)
      (and previous (wait-port-free previous 3000)) previous
      :else nil)))

(defn start-pack-web! [ctx]
  (stop-existing-pack-web! ctx)
  (let [script (str (fs/path (:script-dir ctx) "pack_web.sh"))
        log (fs/path (:state-dir ctx) "dashboard.log")
        port (dashboard-port ctx)]
    (process/process (cond-> [script "--serve" (str (:working-dir ctx))] port (conj port))
                     {:out (str log) :err :out})
    (when-not (wait-for-file (dashboard-url-file ctx) 5000)
      (fail! (str red "Error:" reset " Dashboard did not start.")))
    (let [url (str/trim (slurp (str (dashboard-url-file ctx))))]
      (println (str green "Dashboard: " url reset))
      (maybe-open-browser! url)
      url)))

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

(defn visibility-label [row]
  (if (:visible? row) "visible" "invisible"))

(defn test-parse! [root]
  (let [ctx (prepare-ctx (context root))]
    (prepare-workspace! ctx)
    (doseq [row (:roles ctx)]
      (println (str (:role row) " " (:display-name row) " " (:worktree-path row) " "
                    (:receive-mode row) " " (:propagation row)
                    (when-let [extra (:extra-args row)] (str " " extra))
                    " " (visibility-label row))))
    (print (slurp (str (:roles-file ctx))))
    (print (slurp (str (:sessions-file ctx))))))

(defn test-required-helpers! []
  (doseq [helper required-helpers]
    (println helper)))

(defn test-launch-plan! [root]
  (doseq [line (launch-plan-lines (prepare-ctx (context root)))]
    (println line)))

(defn test-start-order! [_root]
  (println "pack_web start")
  (println "start-agents")
  (println "open-terminals"))

(defn kill-existing-sessions! [ctx]
  (doseq [row (:roles ctx)]
    (when (sh-ok? "tmux" "-S" (:tmux-socket ctx) "has-session" "-t" (:session row))
      (println (str yellow "Existing SwarmForge session found: " (:session row) ". Killing it..." reset))
      (sh "tmux" "-S" (:tmux-socket ctx) "kill-session" "-t" (:session row)))))

(defn warn-if-paused! [ctx]
  (when (ready-for-next-guard/paused-at? (:working-dir ctx))
    (println (str yellow bold "Warning: Swarm is drained; run ./swarm resume" reset
                  yellow " (roles take no new mail while " (ready-for-next-guard/pause-file (:working-dir ctx))
                  " exists)." reset))))

(defn announce-ready! [ctx]
  (println)
  (println (str green bold "SwarmForge is ready." reset))
  (println "Working directory:" (str (:working-dir ctx)))
  (println "Sessions:")
  (doseq [row (:roles ctx)]
    (println (str "  " (:display-name row) ": " (:session row))))
  (println)
  (println (str green "Tip: Write a handoff draft and run swarm_handoff.sh while the swarm is running." reset))
  (println (str green "Tip: Reattach manually with 'tmux -S " (:tmux-socket ctx) " attach-session -t <session-name>' if needed." reset))
  (warn-if-paused! ctx)
  (println))

(defn launch-roles! [ctx]
  (println (str green "Starting agents..." reset))
  (let [delay-ms (env-long "SWARMFORGE_AGENT_START_DELAY_MS" 1500)]
    (doseq [[index row] (map-indexed vector (:roles ctx))]
      (when (pos? index)
        (Thread/sleep delay-ms))
      (launch-role! ctx index row))))

(defn boot-sessions! [ctx]
  (println (str cyan bold))
  (println "  SwarmForge v1.0 Starting")
  (println "  Disciplined agents build better software")
  (println reset)
  (println (str green "Launching SwarmForge tmux sessions..." reset))
  (doseq [row (:roles ctx)]
    (create-role-session! ctx (:session row) (:display-name row)))
  (write-tmux-env-file! ctx))

(defn run-main! [root]
  (check-dependency! "tmux")
  (check-dependency! "git")
  (check-dependency! "bb")
  (let [ctx (-> (context root)
                detect-tmux-base-indexes)]
    (initialize-git-repo! ctx)
    (ensure-runtime-git-excludes! ctx)
    (install-commit-msg-hook! ctx)
    (install-shared-articles! ctx)
    (let [ctx (prepare-ctx ctx)]
      (check-backend-dependencies! ctx)
      (prepare-workspace! ctx)
      (prepare-worktrees! ctx)
      (prepare-handoff-dirs! ctx)
      (let [ctx (assoc ctx :terminal-backend (detect-terminal-backend))]
        (stop-handoff-daemon! ctx)
        (kill-existing-sessions! ctx)
        (boot-sessions! ctx)
        (sync-worktree-scripts! ctx)
        (start-handoff-daemon! ctx)
        (start-pack-web! ctx)
        (launch-roles! ctx)
        (announce-ready! ctx)
        (open-terminal-surfaces! ctx)))))

(defn parse-lieutenant-config [ctx]
  (let [file (:config-file ctx)
        fallback (str/lower-case (or (not-empty (System/getenv "SWARMFORGE_LIEUTENANT_AGENT")) "grok"))]
    (if-not (fs/regular-file? file)
      {:agent fallback :extra-args nil}
      (or (some (fn [raw]
                  (let [line (str/trim raw)]
                    (when-not (skip-config-line? line)
                      (let [fields (str/split line #"\s+")]
                        (when (and (>= (count fields) 2)
                                   (= (str/lower-case (first fields)) "lieutenant"))
                          (let [agent (str/lower-case (second fields))]
                            (reject-if (not (known-agents agent))
                                       (str "Unsupported agent '" (second fields)
                                            "' for lieutenant"))
                            {:agent agent
                             :extra-args (extra-args-str (drop 2 fields))}))))))
                (str/split-lines (slurp (str file))))
          {:agent fallback :extra-args nil}))))

(defn lieutenant-row [ctx]
  (let [{:keys [agent extra-args]} (parse-lieutenant-config ctx)]
    (window-row ctx "lieutenant" agent "master" "task" "forward-only" extra-args false)))

(defn forge-root? [root]
  (fs/directory? (fs/path root "packs")))

(defn session-names-from-file [ctx]
  (let [file (:sessions-file ctx)]
    (if (fs/regular-file? file)
      (->> (str/split-lines (slurp (str file)))
           (remove str/blank?)
           (map #(nth (str/split % #"\t") 2))
           vec)
      [])))

(defn run-stop-project! [root]
  (let [ctx (context root)
        socket (when (fs/regular-file? (:tmux-socket-file ctx))
                 (not-empty (str/trim (slurp (str (:tmux-socket-file ctx))))))
        sessions (session-names-from-file ctx)
        script (str (fs/path (:script-dir ctx) "swarm-cleanup.sh"))]
    (stop-handoff-daemon! ctx)
    (when socket
      (apply process/sh {:continue true}
             (into [script socket (str (:window-ids-file ctx))] sessions)))))

(defn run-host! [root]
  (check-dependency! "tmux")
  (check-dependency! "git")
  (check-dependency! "bb")
  (let [ctx (-> (context root)
                detect-tmux-base-indexes)
        row (lieutenant-row ctx)
        ctx (assoc ctx :roles [row] :host? true :terminal-backend (detect-terminal-backend))]
    (when-not (fs/exists? (fs/path (:roles-dir ctx) "lieutenant.prompt"))
      (fail! (str red "Error:" reset " Missing lieutenant prompt at "
                  (fs/path (:roles-dir ctx) "lieutenant.prompt"))))
    (check-backend-dependencies! ctx)
    (fs/create-dirs (fs/path (:working-dir ctx) "projects"))
    (prepare-workspace! ctx)
    (let [open-file (fs/path (:state-dir ctx) "open-projects")
          lingering (if (fs/regular-file? open-file)
                      (->> (str/split-lines (slurp (str open-file)))
                           (map str/trim)
                           (remove str/blank?)
                           vec)
                      [])]
      (doseq [name lingering]
        (run-stop-project! (str (fs/path (:working-dir ctx) "projects" name))))
      (fs/create-dirs (:state-dir ctx))
      (spit (str open-file) ""))
    (kill-existing-sessions! ctx)
    (boot-sessions! ctx)
    (start-pack-web! ctx)
    (launch-roles! ctx)
    (announce-ready! ctx)
    (open-terminal-surfaces! ctx)))

(defn run-project! [root]
  (check-dependency! "tmux")
  (check-dependency! "git")
  (check-dependency! "bb")
  (let [ctx (-> (context root)
                detect-tmux-base-indexes)]
    (initialize-git-repo! ctx)
    (ensure-runtime-git-excludes! ctx)
    (install-commit-msg-hook! ctx)
    (install-shared-articles! ctx)
    (let [ctx (prepare-ctx ctx)]
      (check-backend-dependencies! ctx)
      (prepare-workspace! ctx)
      (prepare-worktrees! ctx)
      (prepare-handoff-dirs! ctx)
      (let [ctx (assoc ctx :terminal-backend (detect-terminal-backend))]
        (stop-handoff-daemon! ctx)
        (kill-existing-sessions! ctx)
        (boot-sessions! ctx)
        (sync-worktree-scripts! ctx)
        (start-handoff-daemon! ctx)
        (launch-roles! ctx)
        (announce-ready! ctx)))))

(defn test-terminal-bridge! [root backend]
  (let [local-script-dir (fs/path root "swarmforge" "scripts")
        ctx (cond-> (assoc (context root) :terminal-backend backend)
              (fs/exists? local-script-dir) (assoc :script-dir local-script-dir))]
    (println (terminal-call-out ctx "terminal_open_session" "swarmforge-specifier" "SwarmForge Specifier" ""))))

(defn test-tmux-base-indexes! [tmux-socket]
  (let [ctx (detect-tmux-base-indexes {:tmux-socket tmux-socket
                                        :tmux-socket-dir (str (fs/parent (fs/path tmux-socket)))})]
    (println (:tmux-window-base-index ctx) (:tmux-pane-base-index ctx))))

(defn test-create-role-session! [tmux-socket session]
  (create-role-session! {:tmux-socket tmux-socket} session "Specifier")
  (println (sh-out "tmux" "-S" tmux-socket "show-options" "-t" session "-qv" "history-limit")))

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

(defn test-lieutenant-launch-command! [root]
  (let [ctx (assoc (context root) :terminal-backend "none")
        row (assoc (lieutenant-row ctx) :worktree-path (fs/path root))]
    (fs/create-dirs (:prompts-dir ctx))
    (fs/create-dirs (:roles-dir ctx))
    (when-not (fs/exists? (fs/path (:roles-dir ctx) "lieutenant.prompt"))
      (spit (str (fs/path (:roles-dir ctx) "lieutenant.prompt")) "lieutenant\n"))
    (println (launch-command ctx 1 row))))

(defn test-install-hooks! [root]
  (let [ctx (context root)]
    (install-commit-msg-hook! ctx)
    (println (str (fs/path (git-hooks-dir ctx) "commit-msg")))))

(defn test-sleep-inhibitor-prefix! []
  (println (str/join " " (or (sleep-inhibitor-prefix) []))))

(defn test-ensure-codex-trust! [dir]
  (ensure-codex-trust! dir))

(defn test-reset-pack-web-state! [root]
  (let [ctx (context root)]
    (fs/create-dirs (:state-dir ctx))
    (stop-existing-pack-web! ctx)
    (println (str (boolean (fs/exists? (dashboard-url-file ctx))) " "
                  (boolean (fs/exists? (pack-web-pid-file ctx)))))))

(defn test-dashboard-port! [root]
  (println (or (dashboard-port (context root)) "")))

(defn require-swarm-root! [root]
  (when-not (fs/regular-file? (fs/path root ".swarmforge" "roles.tsv"))
    (fail! (str red "Error:" reset " No swarm at " root
                ": .swarmforge/roles.tsv is missing. Run this from the project folder (or pass it) after ./swarm has started there."))))

(defn print-drain-status! [root]
  (let [state (ready-for-next-guard/drain-state root)]
    (println (str "PAUSED: " (if (:paused state) "yes" "no")))
    (println (str "DRAINED: " (if (:drained state) "yes" "no")))
    (doseq [{:keys [role in_process outbox]} (:busy state)]
      (println (str "BUSY: " role " in_process=" in_process " outbox=" outbox)))
    (when (pos? (:project_outbox state))
      (println (str "BUSY: project outbox=" (:project_outbox state))))))

(defn run-drain! [root]
  (let [file (ready-for-next-guard/pause-file root)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) (str (java.time.Instant/now) "\n"))
    (println "Draining: roles finish in-process work and take no new mail.")
    (print-drain-status! root)))

(def wake-message
  "You have new handoff mail. If idle, run ready_for_next.sh.")

(defn wake-role-with-mail! [socket cols]
  (let [session (nth cols 3 "")
        worktree (nth cols 2 "")
        new-dir (fs/path worktree ".swarmforge" "handoffs" "inbox" "new")]
    (when (and (not (str/blank? session))
               (seq (ready-for-next-guard/dir-entries new-dir ready-for-next-guard/handoff-file?))
               (sh-ok? "tmux" "-S" socket "has-session" "-t" session))
      (process/sh {:continue true} "tmux" "-S" socket "send-keys" "-t" session "-l" wake-message)
      (Thread/sleep 150)
      (process/sh {:continue true} "tmux" "-S" socket "send-keys" "-t" session "C-m")
      (println (str "Woke " (first cols) ".")))))

(defn run-resume! [root]
  (let [ctx (context root)
        socket (when (fs/regular-file? (:tmux-socket-file ctx))
                 (not-empty (str/trim (slurp (str (:tmux-socket-file ctx))))))]
    (fs/delete-if-exists (ready-for-next-guard/pause-file (:working-dir ctx)))
    (println "Resumed: roles take new mail again.")
    (when socket
      (doseq [cols (ready-for-next-guard/role-rows-at (:working-dir ctx))]
        (wake-role-with-mail! socket cols)))))

(defn test-install-shared-articles! [root]
  (install-shared-articles! (assoc (context root) :script-dir (fs/path root "swarmforge" "scripts"))))

(defn test-sync-worktree-roles! [root worktree-path]
  (sync-worktree-roles! (context root) (fs/absolutize worktree-path)))

(defn -main [& args]
  (case (first args)
    "drain" (let [root (str (fs/absolutize (or (second args) (System/getProperty "user.dir"))))]
              (require-swarm-root! root)
              (run-drain! root))
    "resume" (run-resume! (or (second args) (System/getProperty "user.dir")))
    "status" (let [root (str (fs/absolutize (or (second args) (System/getProperty "user.dir"))))]
               (require-swarm-root! root)
               (print-drain-status! root))
    "--test-sync-worktree-roles" (test-sync-worktree-roles! (second args) (nth args 2))
    "--test-install-shared-articles" (test-install-shared-articles! (second args))
    "--test-parse" (test-parse! (or (second args) (System/getProperty "user.dir")))
    "--test-required-helpers" (test-required-helpers!)
    "--test-launch-plan" (test-launch-plan! (or (second args) (System/getProperty "user.dir")))
    "--test-start-order" (test-start-order! (or (second args) (System/getProperty "user.dir")))
    "--test-terminal-bridge" (test-terminal-bridge! (or (second args) (System/getProperty "user.dir")) (nth args 2))
    "--test-launch-command" (apply test-launch-command!
                                     (or (second args) (System/getProperty "user.dir"))
                                     (drop 2 args))
    "--test-lieutenant-launch-command" (test-lieutenant-launch-command!
                                        (or (second args) (System/getProperty "user.dir")))
    "--test-install-hooks" (test-install-hooks! (second args))
    "--test-agent-start-delay" (println (env-long "SWARMFORGE_AGENT_START_DELAY_MS" 1500))
    "--test-sleep-inhibitor-prefix" (test-sleep-inhibitor-prefix!)
    "--test-ensure-codex-trust" (test-ensure-codex-trust! (second args))
    "--test-reset-pack-web-state" (test-reset-pack-web-state! (second args))
    "--test-dashboard-port" (test-dashboard-port! (second args))
    "--test-tmux-base-indexes" (test-tmux-base-indexes! (second args))
    "--test-create-role-session" (test-create-role-session! (second args) (nth args 2))
    "--start-project" (run-project! (second args))
    "--stop-project" (run-stop-project! (second args))
    (let [root (or (first args) (System/getProperty "user.dir"))]
      (if (forge-root? root)
        (run-host! root)
        (run-main! root)))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
