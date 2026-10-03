;; Config parse, workspace, and worktree sync. Loaded into swarmforge.

(defn config-fail! [message]
  (fail! (str red "Error:" reset " " message)))

(defn skip-config-line? [line]
  (or (str/blank? line)
      (str/starts-with? line "#")
      (re-matches #"merge-check(\s.*)?" line)))

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

(defn config-setting [ctx directive]
  (let [file (:config-file ctx)]
    (when (fs/regular-file? file)
      (some (fn [raw]
              (let [line (str/trim raw)]
                (when-not (skip-config-line? line)
                  (when-let [[found value] (setting-line line)]
                    (when (= directive found) (not-empty value))))))
            (str/split-lines (slurp (str file)))))))

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

(defn role-name? [value]
  (boolean (re-matches #"[A-Za-z][A-Za-z0-9-]*" (or value ""))))

(defn worktree-name? [value]
  (or (special-worktree? value)
      (boolean (and (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" (or value ""))
                    (not (#{"." ".."} value))))))

(defn card-type-name? [value]
  (boolean (re-matches #"[A-Za-z][A-Za-z0-9_-]*" (or value ""))))

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
  (reject-if (not (role-name? role))
             (str "Invalid role '" role "' on line " line-no
                  ": use letters, digits, and hyphens, beginning with a letter"))
  (reject-if (contains? roles role)
             (str "Duplicate role '" role "' in " (:config-file ctx)))
  (reject-if (and (not (special-worktree? worktree)) (contains? worktrees worktree))
             (str "Duplicate worktree '" worktree "' in " (:config-file ctx)))
  (reject-if (not (worktree-name? worktree))
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

(defn parse-card-line [ctx line-no line]
  (let [[_ card-type & route] (str/split line #"\s+")]
    (reject-if (not (card-type-name? card-type))
               (str "Invalid card type on line " line-no ": " (or card-type "")))
    (reject-if (empty? route)
               (str "Card route '" card-type "' is empty on line " line-no))
    (reject-if (not= (count route) (count (distinct route)))
               (str "Card route '" card-type "' repeats a role on line " line-no))
    {:type card-type :roles (vec route) :line-no line-no}))

(defn validate-routes! [ctx rows routes]
  (let [known (set (map :role rows))
        positions (into {} (map-indexed (fn [index row] [(:role row) index]) rows))
        types (map :type routes)]
    (reject-if (not= (count types) (count (distinct types)))
               (str "Duplicate card type in " (:config-file ctx)))
    (doseq [{:keys [type roles line-no]} routes]
      (doseq [role roles]
        (reject-if (not (contains? known role))
                   (str "Unknown role '" role "' in card route '" type
                        "' on line " line-no)))
      (let [indices (mapv positions roles)]
        (reject-if (not= indices (vec (sort indices)))
                   (str "Card route '" type "' does not follow configured window order"))))))

(defn validate-setting! [line-no [directive value]]
  (case directive
    "dashboard-port"
    (reject-if (not (valid-port? value))
               (str "Invalid dashboard-port on line " line-no ": expected a port from 1 to 65535"))
    "notify-cmd"
    (reject-if (str/blank? value)
               (str "Missing command for notify-cmd on line " line-no))))

(defn parse-config [ctx]
  (when-not (fs/exists? (:config-file ctx))
    (config-fail! (str "Config not found at " (:config-file ctx))))
  (when-not (fs/exists? (:constitution-file ctx))
    (config-fail! (str "Constitution prompt not found at " (:constitution-file ctx))))
  (loop [lines (map-indexed vector (str/split-lines (slurp (str (:config-file ctx)))))
         rows []
         routes []
         roles #{}
         worktrees #{}]
    (if-let [[line-index raw-line] (first lines)]
      (let [line-no (inc line-index)
            line (str/trim raw-line)]
        (cond
          (skip-config-line? line)
          (recur (next lines) rows routes roles worktrees)

          (setting-line line)
          (do (validate-setting! line-no (setting-line line))
              (recur (next lines) rows routes roles worktrees))

          :else
          (if (str/starts-with? line "card ")
            (recur (next lines) rows (conj routes (parse-card-line ctx line-no line)) roles worktrees)
            (let [row (parse-window-line ctx line-no line roles worktrees)
                  worktree (:worktree-name row)]
              (recur (next lines)
                     (conj rows row)
                     routes
                     (conj roles (:role row))
                     (cond-> worktrees (not (special-worktree? worktree)) (conj worktree)))))))
      (do
        (reject-if (empty? rows)
                   (str "No windows defined in " (:config-file ctx)))
        (require-master-worktree! rows)
        (validate-routes! ctx rows routes)
        (assoc ctx :roles rows :routes routes)))))

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

(defn write-routes-file! [ctx]
  (spit (str (:routes-file ctx))
        (apply str
               (for [{:keys [type roles]} (:routes ctx)]
                 (str type "\t" (str/join "," roles) "\n")))))

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

(def required-libraries ["card_type.bb" "safe_paths.bb" "handoff_state.bb"])

(defn check-helper-scripts! [ctx]
  (doseq [helper required-helpers]
    (let [path (fs/path (:script-dir ctx) helper)]
      (when-not (and (fs/exists? path) (fs/executable? path))
        (fail! (str red "Error:" reset " Required helper script not found or not executable: " path)))))
  (doseq [helper terminal-helpers]
    (let [path (fs/path (:script-dir ctx) "terminal-adapters" helper)]
      (when-not (and (fs/exists? path) (fs/executable? path))
        (fail! (str red "Error:" reset " Required terminal adapter not found or not executable: " path)))))
  (doseq [library required-libraries]
    (let [path (fs/path (:script-dir ctx) library)]
      (when-not (fs/regular-file? path)
        (fail! (str red "Error:" reset " Required script library not found: " path))))))

(defn git-hooks-dir [ctx]
  (let [path (sh-out "git" "-C" (str (:working-dir ctx)) "rev-parse" "--git-path" "hooks")
        dir (fs/path path)]
    (if (fs/absolute? dir)
      dir
      (fs/path (:working-dir ctx) dir))))

(defn install-commit-msg-hook! [ctx]
  (let [dir (git-hooks-dir ctx)
        hook (fs/path dir "commit-msg")
        saved (fs/path dir "commit-msg.before-swarmforge")
        bb (str (fs/absolutize (fs/path (:script-dir ctx) "commit_msg_hook.bb")))]
    (fs/create-dirs dir)
    (let [managed? (and (fs/regular-file? hook)
                        (try
                          (str/includes? (slurp (str hook)) "SWARMFORGE COMBINED COMMIT-MSG HOOK")
                          (catch Exception _ false)))
          hook-present? (or (fs/exists? hook) (fs/sym-link? hook))
          saved-present? (or (fs/exists? saved) (fs/sym-link? saved))]
      (when (and hook-present? (not managed?))
        (when saved-present?
          (config-fail! (str "Cannot install commit-msg hook: " saved " already exists")))
        (fs/move hook saved))
      (spit (str hook)
            (str "#!/usr/bin/env zsh\n"
                 "# SWARMFORGE COMBINED COMMIT-MSG HOOK v1\n"
                 "set -u\n"
                 "bb " (sq bb) " \"$@\" || exit $?\n"
                 "saved=" (sq (str saved)) "\n"
                 "if [[ -x \"$saved\" ]]; then\n"
                 "  \"$saved\" \"$@\"\n"
                 "fi\n")))
    (fs/set-posix-file-permissions hook "rwxr-xr-x")))

(defn remove-commit-msg-hook! [ctx]
  (let [dir (git-hooks-dir ctx)
        hook (fs/path dir "commit-msg")
        saved (fs/path dir "commit-msg.before-swarmforge")
        managed? (and (fs/regular-file? hook)
                      (try
                        (str/includes? (slurp (str hook)) "SWARMFORGE COMBINED COMMIT-MSG HOOK")
                        (catch Exception _ false)))]
    (when (and (or (fs/exists? hook) (fs/sym-link? hook)) (not managed?))
      (config-fail! "Cannot remove SwarmForge hook: commit-msg was changed"))
    (when managed?
      (fs/delete-if-exists hook))
    (when (or (fs/exists? saved) (fs/sym-link? saved))
      (fs/move saved hook))))

(defn prepare-workspace! [ctx]
  (doseq [dir [(:state-dir ctx) (:notify-dir ctx) (:prompts-dir ctx)
               (:worktrees-dir ctx) (:tmux-socket-dir ctx) (:daemon-dir ctx)]]
    (fs/create-dirs dir))
  (spit (str (:tmux-socket-file ctx)) (str (:tmux-socket ctx) "\n"))
  (check-helper-scripts! ctx)
  (write-sessions-file! ctx)
  (write-roles-file! ctx)
  (write-routes-file! ctx))

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

(defn mirror-tree! [src dest]
  (when (fs/exists? dest)
    (fs/delete-tree dest))
  (when (fs/directory? src)
    (fs/create-dirs (fs/parent dest))
    (fs/copy-tree src dest)))

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

(defn sync-worktree-roles! [ctx worktree-path]
  (mirror-tree! (:roles-dir ctx) (fs/path worktree-path "swarmforge" "roles"))
  (mirror-tree! (fs/path (:swarm-forge-dir ctx) "constitution")
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
      (mirror-tree! (:script-dir ctx) role-scripts-dir)
      (sync-worktree-roles! ctx worktree-path)
      (fs/create-dirs (fs/path role-state-dir "notify"))
      (fs/copy (:sessions-file ctx) (fs/path role-state-dir "sessions.tsv") {:replace-existing true})
      (fs/copy (:roles-file ctx) (fs/path role-state-dir "roles.tsv") {:replace-existing true})
      (fs/copy (:routes-file ctx) (fs/path role-state-dir "routes.tsv") {:replace-existing true})
      (fs/copy (:tmux-socket-file ctx) (fs/path role-state-dir "tmux-socket") {:replace-existing true})
      (fs/copy (:tmux-env-file ctx) (fs/path role-state-dir "tmux-env") {:replace-existing true}))))
