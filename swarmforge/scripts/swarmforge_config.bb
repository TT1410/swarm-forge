;; Config parse, workspace, and worktree sync. Loaded into swarmforge.

(defn config-fail! [message]
  (fail! (str red "Error:" reset " " message)))

(defn skip-config-line? [line]
  (or (str/blank? line) (str/starts-with? line "#")))

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
        (if (skip-config-line? line)
          (recur (next lines) rows roles worktrees)
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

(defn sync-worktree-roles! [ctx worktree-path]
  (copy-tree-into! (:roles-dir ctx) (fs/path worktree-path "swarmforge" "roles"))
  (copy-tree-into! (fs/path (:swarm-forge-dir ctx) "constitution")
                   (fs/path worktree-path "swarmforge" "constitution"))
  (when (fs/exists? (:constitution-file ctx))
    (fs/create-dirs (fs/path worktree-path "swarmforge"))
    (fs/copy (:constitution-file ctx)
             (fs/path worktree-path "swarmforge" "constitution.prompt")
             {:replace-existing true})))

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

