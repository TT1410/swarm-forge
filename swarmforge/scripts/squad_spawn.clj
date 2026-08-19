#!/usr/bin/env bb

(ns squad-spawn
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-config :as cfg]
            [squad-lease :as lease]
            [clojure.string :as str]))

(def usage-text
  (str "Usage: squad_spawn.sh <template> <task-id> <assignment-file>\n\n"
       "Creates one invisible transient agent from swarmforge/role-templates/<template>.prompt."))

(def script-dir (fs/parent *file*))

(defn exit! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn sh [& args]
  (apply process/sh args))

(defn sh-ok? [& args]
  (zero? (:exit (apply process/sh (concat [{:continue true}] args)))))

(defn tmux-session-exists? [socket session]
  (and (not (str/blank? socket))
       (not (str/blank? session))
       (sh-ok? "tmux" "-S" socket "has-session" "-t" session)))

(defn run! [& args]
  (let [result (apply process/sh (concat [{:continue true}] args))]
    (when-not (zero? (:exit result))
      (exit! 1
             (str "Command failed: " (str/join " " args))
             (str/trim (str (:err result)))))
    result))

(defn sh-out [& args]
  (str/trim (:out (apply process/sh args))))

(defn sq [value]
  (str "'" (str/replace (str value) #"'" "'\"'\"'") "'"))

(defn project-root []
  (or (cfg/project-root)
      (exit! 1 "Cannot find SwarmForge project root")))

(defn validate-template! [template]
  (when-not (re-matches #"[a-z][a-z0-9-]*" template)
    (exit! 2 "Template names must use lowercase letters, digits, and hyphens."))
  (when (str/includes? template "_")
    (exit! 2 "Template names may not contain underscores.")))

(defn validate-task-id! [task-id]
  (when-not (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" task-id)
    (exit! 2 "Task ids must use letters, digits, dots, underscores, and hyphens."))
  (when (or (str/includes? task-id "/") (str/includes? task-id "\\"))
    (exit! 2 "Task ids may not contain path separators.")))

(defn display-name-for-role [role]
  (->> (str/split (str/replace role #"[-_]" " ") #"\s+")
       (remove str/blank?)
       (map str/capitalize)
       (str/join " ")))

(defn role-rows [roles-file]
  (if (fs/exists? roles-file)
    (->> (str/split-lines (slurp (str roles-file)))
         (remove str/blank?)
         (map #(str/split % #"\t" -1))
         vec)
    []))

(defn numeric-suffix [prefix name]
  (when (str/starts-with? name prefix)
    (some-> (re-find #"\d{3}$" name) Long/parseLong)))

(defn existing-agent-numbers [root rows template]
  (let [prefix (str template "-")
        row-numbers (keep #(numeric-suffix prefix (first %)) rows)
        worktree-dir (fs/path root ".worktrees")
        worktree-numbers (when (fs/exists? worktree-dir)
                           (keep #(numeric-suffix prefix (fs/file-name %))
                                 (fs/list-dir worktree-dir)))
        agent-dir (fs/path root ".squad" "agents")
        agent-numbers (when (fs/exists? agent-dir)
                        (keep #(numeric-suffix prefix (fs/file-name %))
                              (fs/list-dir agent-dir)))]
    (concat row-numbers worktree-numbers agent-numbers)))

(def active-agent-states
  #{"starting" "running" "failed" "blocked" "handoff_ready" "handoff_sent"})
(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(defn skip-tmux-capacity? []
  (= "1" (System/getenv "SWARMFORGE_SQUAD_NO_LAUNCH")))

(defn tmux-socket [root]
  (let [socket-file (fs/path root ".swarmforge" "tmux-socket")]
    (when (fs/regular-file? socket-file)
      (str/trim (slurp (str socket-file))))))

(defn visible-handoff-agents [root]
  (->> ["new" "in_process" "completed"]
       (mapcat (fn [state]
                 (let [dir (fs/path root ".swarmforge" "handoffs" "inbox" state)]
                   (when (fs/exists? dir)
                     (->> (fs/list-dir dir)
                          (filter #(and (fs/regular-file? %)
                                        (str/ends-with? (fs/file-name %) ".handoff")))
                          (map #(read-value % "from"))
                          (remove str/blank?))))))
       set))

(declare role-template)

(defn template-active-row? [root socket row]
  "True when the role is a live transient that should count toward template caps."
  (let [role (first row)
        session (nth row 3 nil)
        state (read-value (fs/path root ".squad" "agents" role "status") "state")]
    (and (not (#{"squad-leader" "troubleshooter"} role))
         (not= "retired" state)
         (if (skip-tmux-capacity?)
           (contains? active-agent-states state)
           (tmux-session-exists? socket session))
         (not (and (= "handoff_sent" state)
                   (contains? (visible-handoff-agents root) role))))))

(defn capacity-counted-row? [root socket row]
  "True when the role consumes max_transient_agents."
  (template-active-row? root socket row))

(defn active-transient-count [root rows]
  (count
   (let [socket (tmux-socket root)]
     (for [row rows
           :when (capacity-counted-row? root socket row)]
       (first row)))))

(defn template-from-role [role]
  (str/replace role #"-\d{3}$" ""))

(defn role-template [root role]
  (or (read-value (fs/path root ".squad" "agents" role "metadata") "template")
      (template-from-role role)))

(defn role-task-id [root role]
  (or (read-value (fs/path root ".squad" "agents" role "metadata") "task_id")
      (read-value (fs/path root ".squad" "agents" role "metadata") "task-id")))

(defn counts-toward-template-cap?
  [root socket row template]
  (let [role (first row)]
    (and (template-active-row? root socket row)
         (= template (role-template root role)))))

(defn leader-agent [rows]
  (some (fn [row]
          (when (= "squad-leader" (first row))
            (nth row 5 nil)))
        rows))

(defn configured-transient-agent
  ([root rows]
   (configured-transient-agent root rows nil))
  ([root rows template]
   (let [configured (if (str/blank? template)
                      (cfg/squad-transient-agent-config root)
                      (cfg/squad-transient-agent-for-template root template))]
     (cond
       (str/blank? configured) nil
       (#{"leader" "squad-leader"} configured) (leader-agent rows)
       :else configured))))

(defn transient-agent
  ([root rows]
   (transient-agent root rows nil))
  ([root rows template]
   (or (not-empty (System/getenv "SWARMFORGE_SQUAD_AGENT"))
       (configured-transient-agent root rows template)
       (leader-agent rows)
       "codex")))

(defn active-template-count [root rows template]
  (count
   (let [socket (tmux-socket root)]
     (for [row rows
           :let [role (first row)]
           :when (counts-toward-template-cap? root socket row template)]
       role))))

(defn active-group-count [root rows templates]
  (count
   (let [socket (tmux-socket root)]
     (for [row rows
           :let [role (first row)]
           :when (and (template-active-row? root socket row)
                      (contains? templates (role-template root role)))]
       role))))

(defn template-capacity-error-line [template active limit]
  [3
   "SQUAD_SPAWN_TEMPLATE_CAPACITY_FULL"
   (str "TEMPLATE: " template)
   (str "ACTIVE_TEMPLATE_TRANSIENTS: " active)
   (str "MAX_TEMPLATE_TRANSIENTS: " limit)])

(defn group-capacity-error-line [template group active limit]
  [3
   "SQUAD_SPAWN_GROUP_CAPACITY_FULL"
   (str "GROUP: " group)
   (str "TEMPLATE: " template)
   (str "ACTIVE_GROUP_TRANSIENTS: " active)
   (str "MAX_GROUP_TRANSIENTS: " limit)])

(defn template-limit-error [root rows template]
  (when-let [limit (cfg/squad-template-limit root template)]
    (let [active (active-template-count root rows template)]
      (when (>= active limit)
        (template-capacity-error-line template active limit)))))

(defn group-limit-error [root rows template {:keys [group limit templates]}]
  (let [active (active-group-count root rows templates)]
    (when (>= active limit)
      (group-capacity-error-line template group active limit))))

(defn template-capacity-error [root rows template]
  (or
   (template-limit-error root rows template)
   (some #(group-limit-error root rows template %)
         (cfg/squad-template-group-limits root template))))

(defn next-agent-id [root rows template]
  (let [numbers (existing-agent-numbers root rows template)]
    (format "%s-%03d" template (inc (reduce max 0 numbers)))))

(defn create-dirs! [dirs]
  (doseq [dir dirs]
    (fs/create-dirs dir)))

(defn write-atomic! [file content]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                  :prefix (str "." (fs/file-name file) ".")})]
    (spit (str tmp) content)
    (fs/move tmp file {:replace-existing true})))

(defn copy-scripts! [worktree]
  (let [target-dir (fs/path worktree "swarmforge" "scripts")]
    (fs/create-dirs target-dir)
    (doseq [entry (fs/list-dir script-dir)]
      (let [target (fs/path target-dir (fs/file-name entry))]
        (if (fs/directory? entry)
          (fs/copy-tree entry target {:replace-existing true})
          (fs/copy entry target {:replace-existing true}))))))

(defn sync-runtime-files! [root worktree]
  (let [root-state (fs/path root ".swarmforge")
        role-state (fs/path worktree ".swarmforge")]
    (fs/create-dirs role-state)
    (doseq [file ["sessions.tsv" "tmux-socket" "tmux-env"]]
      (let [source (fs/path root-state file)]
        (when (fs/exists? source)
          (fs/copy source (fs/path role-state file) {:replace-existing true}))))))

(defn handoff-dirs [worktree]
  (for [dir ["outbox/tmp" "sent" "failed" "inbox/new" "inbox/in_process" "inbox/completed"]]
    (fs/path worktree ".swarmforge" "handoffs" dir)))

(defn render-prompt [{:keys [agent-id template task-id assignment assignment-text root worktree tool-cache-dir]}]
  (str "Read swarmforge/worker-common.prompt.\n"
       "Read swarmforge/role-templates/" template ".prompt.\n"
       "Then follow the runtime facts and assignment below.\n\n"
       "# Transient Agent\n\n"
       "agent_id: " agent-id "\n"
       "template: " template "\n"
       "task_id: " task-id "\n"
       "project_root: " root "\n"
       "assigned_worktree: " worktree "\n"
       "tool_cache_dir: " tool-cache-dir "\n\n"
       "# Assignment\n\n"
       "Source file: " assignment "\n\n"
       assignment-text))

(defn claude-command [prompt-file _ _ display]
  (str "claude --append-system-prompt-file " (sq (str prompt-file))
       " --permission-mode acceptEdits -n " (sq (str "SwarmForge " display))
       " \"$(cat " (sq (str prompt-file)) ")\""))

(defn codex-command [prompt-file _ _ _]
  (str "codex --dangerously-bypass-approvals-and-sandbox \"$(cat " (sq (str prompt-file)) ")\""))

(defn copilot-command [prompt-file worktree _ display]
  (str "copilot -C " (sq (str worktree))
       " --name " (sq (str "SwarmForge " display))
       " -i \"$(cat " (sq (str prompt-file)) ")\""))

(defn grok-command [prompt-file worktree _ _]
  ;; --minimal / --no-alt-screen: scrollable agent panes; see docs/grok-agent-window-scroll.md
  (str "grok --cwd " (sq (str worktree))
       " --minimal --no-alt-screen"
       " --permission-mode bypassPermissions --rules \"$(cat " (sq (str prompt-file))
       ")\" --verbatim \"$(cat " (sq (str prompt-file)) ")\""))

(def backend-commands
  {"claude" claude-command
   "codex" codex-command
   "copilot" copilot-command
   "grok" grok-command})

(defn agent-exec-command [agent prompt-file worktree root display]
  (let [override (System/getenv "SWARMFORGE_SQUAD_AGENT_COMMAND")]
    (if (not (str/blank? override))
      override
      ((backend-commands agent) prompt-file worktree root display))))

(defn render-launch-script [{:keys [agent-id root worktree prompt-file script-dir tool-cache-dir agent display]}]
  (str "#!/usr/bin/env zsh\n"
       "set -euo pipefail\n\n"
       "export SWARMFORGE_ROLE=" (sq agent-id) "\n"
       "export SWARMFORGE_PROJECT_ROOT=" (sq (str root)) "\n"
       "export SWARMFORGE_WORKTREE=" (sq (str worktree)) "\n"
       "export SWARMFORGE_TOOL_CACHE_DIR=" (sq (str tool-cache-dir)) "\n"
       "export PATH=\"$SWARMFORGE_WORKTREE/.swarmforge/tools/bin:" (str script-dir) ":$PATH\"\n\n"
       "mkdir -p \"$SWARMFORGE_TOOL_CACHE_DIR/bin\" \"$SWARMFORGE_TOOL_CACHE_DIR/src\" \"$SWARMFORGE_TOOL_CACHE_DIR/cache\" \"$SWARMFORGE_TOOL_CACHE_DIR/manifests\" \"$SWARMFORGE_TOOL_CACHE_DIR/locks\" \"$SWARMFORGE_WORKTREE/.swarmforge/tools/bin\" \"$SWARMFORGE_WORKTREE/.swarmforge/tools/manifests\"\n"
       "cd \"$SWARMFORGE_WORKTREE\"\n"
       "exec zsh -lc " (sq (agent-exec-command agent prompt-file worktree root display)) "\n"))

(defn launch-session! [{:keys [socket session display launch-script]}]
  (when (str/blank? socket)
    (exit! 1 "Cannot launch transient agent: .swarmforge/tmux-socket is empty."))
  (when (sh-ok? "tmux" "-S" socket "has-session" "-t" session)
    (exit! 2 (str "Transient tmux session already exists: " session)))
  ;; Large geometry + deep history so Grok/Codex panes are not stuck at default ~25 lines.
  (run! "tmux" "-S" socket "new-session" "-d" "-s" session "-n" display "-x" "200" "-y" "50" (str launch-script))
  (run! "tmux" "-S" socket "set-window-option" "-t" session "allow-rename" "off")
  (run! "tmux" "-S" socket "set-option" "-t" session "history-limit" "50000"))

(defn acquire-lock!
  "Registry spawn lease (directory name spawn.lock via resource \"spawn\")."
  [lock-dir]
  ;; Callers pass full path ending in spawn.lock; extract root from parent of squad/.
  (let [lock-dir (fs/path lock-dir)
        ;; .../project/.swarmforge/squad/spawn.lock
        root (-> lock-dir fs/parent fs/parent fs/parent)]
    (try
      (lease/acquire! root "spawn"
                      {:timeout-ms 10000
                       :timeout-message
                       (str "Timed out waiting for squad registry lock: " lock-dir
                            "\nIf no squad_spawn.sh or squad_retire.sh process is running, remove the stale lock directory and retry.")})
      nil
      (catch clojure.lang.ExceptionInfo e
        (exit! 2 (ex-message e))))))

(defn append-role-atomic! [roles-file row]
  (let [existing (if (fs/exists? roles-file) (slurp (str roles-file)) "")
        content (str existing
                     (when (and (seq existing) (not (str/ends-with? existing "\n"))) "\n")
                     (str/join "\t" row)
                     "\n")]
    (write-atomic! roles-file content)))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn write-status-and-heartbeat! [agent-dir agent-id task-id state detail]
  (let [now (timestamp)]
    (write-atomic! (fs/path agent-dir "status")
                   (str "state: " state "\n"
                        "detail: " detail "\n"
                        "updated_at: " now "\n"))
    (write-atomic! (fs/path agent-dir "heartbeat")
                   (str "agent: " agent-id "\n"
                        "task_id: " task-id "\n"
                        "state: " state "\n"
                        "detail: " detail "\n"
                        "updated_at: " now "\n"))))

(defn write-metadata! [agent-dir {:keys [agent-id template task-id root worktree session display agent tool-cache-dir launch-script]}]
  (write-atomic! (fs/path agent-dir "metadata")
                 (str "agent_id: " agent-id "\n"
                      "template: " template "\n"
                      "task_id: " task-id "\n"
                      "project_root: " root "\n"
                      "worktree: " worktree "\n"
                      "session: " session "\n"
                      "display: " display "\n"
                      "backend: " agent "\n"
                      "tool_cache_dir: " tool-cache-dir "\n"
                      "launch_script: " launch-script "\n")))

(defn assignment-id-from-file [assignment]
  (or (read-value assignment "assignment_id")
      (let [parent (fs/parent assignment)]
        (when (= "assignment.md" (fs/file-name assignment))
          (fs/file-name parent)))))

(defn mark-assignment-in-progress! [root assignment agent-id session]
  (when-let [assignment-id (assignment-id-from-file assignment)]
    (let [status (fs/path root ".squad" "assignments" assignment-id "status")]
      (when (fs/exists? status)
        (write-atomic! status
                       (str "assignment_id: " assignment-id "\n"
                            "state: in_progress\n"
                            "agent_id: " agent-id "\n"
                            "session: " session "\n"
                            "detail: assigned to " agent-id "\n"
                            "updated_at: " (timestamp) "\n"))))))

(defn absolute-input-file [path]
  (let [file (fs/path path)]
    (if (fs/absolute? file) file (fs/path (fs/cwd) file))))

(defn ensure-spawn-inputs! [root template assignment]
  (when-not (fs/regular-file? assignment)
    (exit! 1 (str "Assignment file not found: " assignment)))
  (let [template-file (fs/path root "swarmforge" "role-templates" (str template ".prompt"))]
    (when-not (fs/regular-file? template-file)
      (exit! 1 (str "Role template not found: " template-file)))
    template-file))

(defn ensure-roles-file! [roles-file]
  (when-not (fs/regular-file? roles-file)
    (exit! 1 "Run ./swarm before spawning transient agents; .swarmforge/roles.tsv is missing.")))

(defn global-capacity-error [root rows]
  (let [active (active-transient-count root rows)
        limit (cfg/squad-max-transient-agents root)]
    (when (>= active limit)
      [3
       "SQUAD_SPAWN_CAPACITY_FULL"
       (str "ACTIVE_TRANSIENTS: " active)
       (str "MAX_TRANSIENTS: " limit)])))

(defn capacity-error [root rows template]
  (or (global-capacity-error root rows)
      (template-capacity-error root rows template)))

(defn agent-status-state [root agent-id]
  (when agent-id
    (read-value (fs/path root ".squad" "agents" agent-id "status") "state")))

(defn live-agent? [root agent-id]
  (boolean
   (when-not (str/blank? agent-id)
     (contains? active-agent-states (or (agent-status-state root agent-id) "")))))
(defn active-agent-for-task [root task-id]
  (let [agents-dir (fs/path root ".squad" "agents")]
    (when (fs/directory? agents-dir)
      (some (fn [dir]
              (let [agent-id (fs/file-name dir)
                    meta-task (read-value (fs/path dir "metadata") "task_id")]
                (when (and (= task-id meta-task)
                           (live-agent? root agent-id))
                  agent-id)))
            (fs/list-dir agents-dir)))))

(defn task-occupancy-error [root task-id assignment]
  "Refuse a second live agent for the same task_id / assignment."
  (let [assignment-id (or (assignment-id-from-file assignment) task-id)
        status-file (fs/path root ".squad" "assignments" assignment-id "status")
        assigned-agent (read-value status-file "agent_id")
        task-holder (active-agent-for-task root task-id)]
    (cond
      task-holder
      [3
       "SQUAD_SPAWN_TASK_OCCUPIED"
       (str "TASK_ID: " task-id)
       (str "AGENT: " task-holder)
       "DETAIL: an active agent already covers this task_id"]

      (live-agent? root assigned-agent)
      [3
       "SQUAD_SPAWN_ASSIGNMENT_OCCUPIED"
       (str "ASSIGNMENT: " assignment-id)
       (str "AGENT: " assigned-agent)
       "DETAIL: assignment already has an active agent"]

      :else nil)))
(defn ensure-agent-available! [rows agent-id worktree agent-dir]
  (when (some #(= agent-id (first %)) rows)
    (exit! 2 (str "Role already registered: " agent-id)))
  (when (fs/exists? worktree)
    (exit! 2 (str "Worktree already exists: " worktree)))
  (when (fs/exists? agent-dir)
    (exit! 2 (str "Agent state already exists: " agent-dir))))

(defn ensure-backend-supported! [agent]
  (when-not (#{"claude" "codex" "copilot" "grok"} agent)
    (exit! 2 (str "Unsupported transient agent backend: " agent))))

(defn spawn-context [root rows template task-id assignment template-file]
  (let [agent-id (next-agent-id root rows template)
        worktree (fs/path root ".worktrees" agent-id)
        agent (transient-agent root rows template)
        squad-dir (fs/path root ".squad")
        agent-dir (fs/path squad-dir "agents" agent-id)]
    {:root root
     :rows rows
     :template template
     :task-id task-id
     :assignment assignment
     :template-file template-file
     :agent-id agent-id
     :worktree worktree
     :branch (str "swarmforge-" agent-id)
     :session (str "swarmforge-" agent-id)
     :display (display-name-for-role agent-id)
     :agent agent
     :squad-dir squad-dir
     :agent-dir agent-dir
     :task-dir (fs/path squad-dir "tasks" task-id)
     :prompt-file (fs/path agent-dir "prompt.md")
     :launch-script (fs/path agent-dir "launch.sh")
     :script-dir (fs/path worktree "swarmforge" "scripts")
     :tool-cache-dir (fs/path root ".swarmforge" "tools")}))

(defn spawn-row [{:keys [agent-id worktree session display agent]}]
  [agent-id agent-id (str worktree) session display agent "task"])

(defn create-worktree! [{:keys [root branch worktree]}]
  (run! "git" "-C" (str root) "worktree" "add" "--force" "-B" branch (str worktree) "HEAD"))

(defn create-spawn-dirs! [{:keys [task-dir agent-dir worktree]}]
  (create-dirs! (concat [(fs/path task-dir "assignments")
                         agent-dir
                         (fs/path worktree ".swarmforge" "tools" "bin")
                         (fs/path worktree ".swarmforge" "tools" "manifests")]
                        (handoff-dirs worktree))))

(defn write-spawn-files! [{:keys [agent-id template task-id assignment root worktree tool-cache-dir template-file
                                  prompt-file launch-script script-dir agent display]}]
  (copy-scripts! worktree)
  (write-atomic! prompt-file
                 (render-prompt {:agent-id agent-id
                                 :template template
                                 :task-id task-id
                                 :assignment (str assignment)
                                 :root (str root)
                                 :worktree (str worktree)
                                 :tool-cache-dir (str tool-cache-dir)
                                 :template-text (slurp (str template-file))
                                 :assignment-text (slurp (str assignment))}))
  (write-atomic! launch-script
                 (render-launch-script {:agent-id agent-id
                                        :root root
                                        :worktree worktree
                                        :prompt-file prompt-file
                                        :script-dir script-dir
                                        :tool-cache-dir tool-cache-dir
                                        :agent agent
                                        :display display}))
  (fs/set-posix-file-permissions launch-script "rwxr-xr-x"))

(defn launch-transient-if-needed! [state-dir {:keys [session display launch-script agent-dir agent-id task-id]}]
  (when-not (= "1" (System/getenv "SWARMFORGE_SQUAD_NO_LAUNCH"))
    (let [socket (str/trim (slurp (str (fs/path state-dir "tmux-socket"))))]
      (launch-session! {:socket socket
                        :session session
                        :display display
                        :launch-script launch-script})
      (write-status-and-heartbeat! agent-dir agent-id task-id "running" "detached tmux session started"))))

(defn print-spawn-result! [{:keys [agent-id template task-id worktree session prompt-file]}]
  (println "SQUAD_AGENT:" agent-id)
  (println "TEMPLATE:" template)
  (println "TASK_ID:" task-id)
  (println "WORKTREE:" (str worktree))
  (println "SESSION:" session)
  (println "PROMPT:" (str prompt-file)))

(defn register-spawn! [state-dir roles-file context]
  (let [{:keys [root assignment agent-id task-id task-dir agent-dir session]} context]
    (create-worktree! context)
    (create-spawn-dirs! context)
    (write-spawn-files! context)
    (fs/copy assignment (fs/path task-dir "assignments" (str agent-id ".md")) {:replace-existing true})
    (append-role-atomic! roles-file (spawn-row context))
    (sync-runtime-files! root (:worktree context))
    (write-metadata! agent-dir context)
    (mark-assignment-in-progress! root assignment agent-id session)
    (write-status-and-heartbeat! agent-dir agent-id task-id "starting" "registered transient agent")
    (launch-transient-if-needed! state-dir context)
    (print-spawn-result! context)))

(defn spawn! [template task-id assignment-file]
  (validate-template! template)
  (validate-task-id! task-id)
  (let [root (fs/absolutize (project-root))
        assignment (absolute-input-file assignment-file)
        template-file (ensure-spawn-inputs! root template assignment)
        state-dir (fs/path root ".swarmforge")
        roles-file (fs/path state-dir "roles.tsv")
        lock-dir (fs/path state-dir "squad" "spawn.lock")
        pending-error (atom nil)]
    (ensure-roles-file! roles-file)
    (fs/create-dirs (fs/parent lock-dir))
    (acquire-lock! lock-dir)
    (try
      (let [rows (role-rows roles-file)]
        (if-let [error (or (task-occupancy-error root task-id assignment)
                           (capacity-error root rows template))]
          (reset! pending-error error)
          (let [context (spawn-context root rows template task-id assignment template-file)]
            (ensure-backend-supported! (:agent context))
            (ensure-agent-available! rows (:agent-id context) (:worktree context) (:agent-dir context))
            (register-spawn! state-dir roles-file context))))
      (finally
        (fs/delete-tree lock-dir)))
    (when-let [error @pending-error]
      (apply exit! error))))
(defn -main [& args]
  (when-not (= 3 (count args))
    (exit! 1 usage-text))
  (apply spawn! args))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
