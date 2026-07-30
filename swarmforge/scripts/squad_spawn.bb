#!/usr/bin/env bb

(ns squad-spawn
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
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
  (let [cwd (fs/cwd)
        direct (fs/path cwd ".swarmforge" "roles.tsv")]
    (if (fs/exists? direct)
      cwd
      (let [git-root (str/trim (:out (sh {:continue true} "git" "rev-parse" "--show-toplevel")))]
        (if (and (not (str/blank? git-root))
                 (fs/exists? (fs/path git-root ".swarmforge" "roles.tsv")))
          (fs/path git-root)
          (exit! 1 "Cannot find SwarmForge project root"))))))

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

(defn next-agent-id [rows template]
  (let [prefix (str template "-")
        numbers (keep (fn [row]
                        (let [role (first row)]
                          (when (str/starts-with? role prefix)
                            (some-> (re-find #"\d{3}$" role) Long/parseLong))))
                      rows)]
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
    (doseq [file ["roles.tsv" "sessions.tsv" "tmux-socket" "tmux-env"]]
      (let [source (fs/path root-state file)]
        (when (fs/exists? source)
          (fs/copy source (fs/path role-state file) {:replace-existing true}))))))

(defn handoff-dirs [worktree]
  (for [dir ["outbox/tmp" "sent" "failed" "inbox/new" "inbox/in_process" "inbox/completed"]]
    (fs/path worktree ".swarmforge" "handoffs" dir)))

(defn render-prompt [{:keys [agent-id template task-id assignment template-text assignment-text]}]
  (str "Read swarmforge/constitution.prompt, then read every file it refers to recursively, and obey all of those instructions.\n"
       "Read the transient role template and assignment below, and follow them exactly.\n\n"
       "# Transient Agent\n\n"
       "agent_id: " agent-id "\n"
       "template: " template "\n"
       "task_id: " task-id "\n\n"
       "You are a transient squad agent. Communicate through handoffs only. Do not talk directly to the user. Do not spawn other agents. Do not broaden this assignment without a squad-leader handoff.\n\n"
       "# Role Template\n\n"
       template-text "\n\n"
       "# Assignment\n\n"
       "Source file: " assignment "\n\n"
       assignment-text))

(defn agent-command [agent worktree prompt-file role script-dir display]
  (let [override (System/getenv "SWARMFORGE_SQUAD_AGENT_COMMAND")
        base (str "export SWARMFORGE_ROLE=" (sq role)
                  " && export PATH=" (sq (str script-dir)) ":$PATH"
                  " && cd " (sq (str worktree))
                  " && ")]
    (if (not (str/blank? override))
      (str base override)
      (str base
           (case agent
             "claude" (str "claude --append-system-prompt-file " (sq (str prompt-file)) " --permission-mode acceptEdits -n " (sq (str "SwarmForge " display)) " \"$(cat " (sq (str prompt-file)) ")\"")
             "codex" (str "codex -C " (sq (str worktree)) " \"$(cat " (sq (str prompt-file)) ")\"")
             "copilot" (str "copilot -C " (sq (str worktree)) " --name " (sq (str "SwarmForge " display)) " -i \"$(cat " (sq (str prompt-file)) ")\"")
             "grok" (str "grok --cwd " (sq (str worktree)) " --permission-mode acceptEdits --rules \"$(cat " (sq (str prompt-file)) ")\" --verbatim \"$(cat " (sq (str prompt-file)) ")\""))))))

(defn launch-session! [{:keys [socket session display command]}]
  (when (str/blank? socket)
    (exit! 1 "Cannot launch transient agent: .swarmforge/tmux-socket is empty."))
  (when (sh-ok? "tmux" "-S" socket "has-session" "-t" session)
    (exit! 2 (str "Transient tmux session already exists: " session)))
  (run! "tmux" "-S" socket "new-session" "-d" "-s" session "-n" display "zsh" "-lc" command)
  (run! "tmux" "-S" socket "set-window-option" "-t" session "allow-rename" "off"))

(defn acquire-lock! [lock-dir]
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (when (> (System/currentTimeMillis) deadline)
        (exit! 2
               (str "Timed out waiting for squad registry lock: " lock-dir)
               "If no squad_spawn.sh or squad_retire.sh process is running, remove the stale lock directory and retry."))
    (if (try
          (fs/create-dir lock-dir)
          true
          (catch java.nio.file.FileAlreadyExistsException _
            false))
      nil
      (do
        (Thread/sleep 50)
        (recur))))))

(defn append-role-atomic! [roles-file row]
  (let [existing (if (fs/exists? roles-file) (slurp (str roles-file)) "")
        content (str existing
                     (when (and (seq existing) (not (str/ends-with? existing "\n"))) "\n")
                     (str/join "\t" row)
                     "\n")]
    (write-atomic! roles-file content)))

(defn write-status! [agent-dir state detail]
  (write-atomic! (fs/path agent-dir "status")
                 (str "state: " state "\n"
                      "detail: " detail "\n"
                      "updated_at: "
                      (.format java.time.format.DateTimeFormatter/ISO_INSTANT
                               (java.time.Instant/now))
                      "\n")))

(defn spawn! [template task-id assignment-file]
  (validate-template! template)
  (validate-task-id! task-id)
  (let [root (fs/absolutize (project-root))
        assignment (fs/path assignment-file)
        assignment (if (fs/absolute? assignment) assignment (fs/path (fs/cwd) assignment))]
    (when-not (fs/regular-file? assignment)
      (exit! 1 (str "Assignment file not found: " assignment)))
    (let [template-file (fs/path root "swarmforge" "role-templates" (str template ".prompt"))]
      (when-not (fs/regular-file? template-file)
        (exit! 1 (str "Role template not found: " template-file)))
      (let [state-dir (fs/path root ".swarmforge")
            roles-file (fs/path state-dir "roles.tsv")
            lock-dir (fs/path state-dir "squad" "spawn.lock")]
        (when-not (fs/regular-file? roles-file)
          (exit! 1 "Run ./swarm before spawning transient agents; .swarmforge/roles.tsv is missing."))
        (fs/create-dirs (fs/parent lock-dir))
        (acquire-lock! lock-dir)
        (try
          (let [rows (role-rows roles-file)
                agent-id (next-agent-id rows template)
                worktree (fs/path root ".worktrees" agent-id)
                branch (str "swarmforge-" agent-id)
                session (str "swarmforge-" agent-id)
                display (display-name-for-role agent-id)
                agent (or (not-empty (System/getenv "SWARMFORGE_SQUAD_AGENT")) "codex")
                squad-dir (fs/path root ".squad")
                agent-dir (fs/path squad-dir "agents" agent-id)
                task-dir (fs/path squad-dir "tasks" task-id)
                prompt-file (fs/path agent-dir "prompt.md")
                script-dir (fs/path worktree "swarmforge" "scripts")
                row [agent-id agent-id (str worktree) session display agent "task"]]
            (when-not (#{"claude" "codex" "copilot" "grok"} agent)
              (exit! 2 (str "Unsupported transient agent backend: " agent)))
            (when (some #(= agent-id (first %)) rows)
              (exit! 2 (str "Role already registered: " agent-id)))
            (when (fs/exists? worktree)
              (exit! 2 (str "Worktree already exists: " worktree)))
            (when (fs/exists? agent-dir)
              (exit! 2 (str "Agent state already exists: " agent-dir)))
            (run! "git" "-C" (str root) "worktree" "add" "--force" "-B" branch (str worktree) "HEAD")
            (create-dirs! (concat [(fs/path task-dir "assignments")
                                   agent-dir]
                                  (handoff-dirs worktree)))
            (copy-scripts! worktree)
            (write-atomic! prompt-file
                           (render-prompt {:agent-id agent-id
                                           :template template
                                           :task-id task-id
                                           :assignment (str assignment)
                                           :template-text (slurp (str template-file))
                                           :assignment-text (slurp (str assignment))}))
            (fs/copy assignment (fs/path task-dir "assignments" (str agent-id ".md")) {:replace-existing true})
            (append-role-atomic! roles-file row)
            (sync-runtime-files! root worktree)
            (write-status! agent-dir "spawned" "registered transient agent")
            (when-not (= "1" (System/getenv "SWARMFORGE_SQUAD_NO_LAUNCH"))
              (let [socket (str/trim (slurp (str (fs/path state-dir "tmux-socket"))))
                    command (agent-command agent worktree prompt-file agent-id script-dir display)]
                (launch-session! {:socket socket
                                  :session session
                                  :display display
                                  :command command})
                (write-status! agent-dir "running" "detached tmux session started")))
            (println "SQUAD_AGENT:" agent-id)
            (println "TEMPLATE:" template)
            (println "TASK_ID:" task-id)
            (println "WORKTREE:" (str worktree))
            (println "SESSION:" session)
            (println "PROMPT:" (str prompt-file)))
          (finally
            (fs/delete-tree lock-dir)))))))

(defn -main [& args]
  (when-not (= 3 (count args))
    (exit! 1 usage-text))
  (apply spawn! args))

(apply -main *command-line-args*)
