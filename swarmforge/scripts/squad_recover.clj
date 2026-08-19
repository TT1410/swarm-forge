#!/usr/bin/env bb

(ns squad-recover
  "Classify missing/dead agents and repair session-dead workers.

  Usage:
    squad_recover.sh <agent-id>           — classify and print recovery plan
    squad_recover.sh repair <agent-id>    — remove dead agent, clear death blockers,
                                            requeue the same assignment (SL/TS runs this)"
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-config :as cfg]
            [clojure.string :as str]))

(def usage-text
  (str "Usage:\n"
       "  squad_recover.sh <agent-id>\n"
       "  squad_recover.sh repair <agent-id>\n"))

(def terminal-assignment-states
  #{"merged" "rejected" "blocked" "replacement_created" "superseded"
    "review_accepted" "review_changes_requested" "cancelled" "abandoned"})

(defn exit! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn sh-continue [& args]
  (apply process/sh (concat [{:continue true}] args)))

(defn project-root []
  (or (cfg/project-root)
      (exit! 1 "Cannot find SwarmForge project root")))

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(defn write-atomic! [file content]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                  :prefix (str "." (fs/file-name file) ".")})]
    (spit (str tmp) content)
    (fs/move tmp file {:replace-existing true})))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn role-row [root agent]
  (some (fn [line]
          (let [[role worktree-name worktree-path session display backend receive-mode]
                (str/split line #"\t" -1)]
            (when (= role agent)
              {:role role
               :worktree-name worktree-name
               :worktree-path worktree-path
               :session session
               :display display
               :backend backend
               :receive-mode receive-mode})))
        (when (fs/exists? (fs/path root ".swarmforge" "roles.tsv"))
          (str/split-lines (slurp (str (fs/path root ".swarmforge" "roles.tsv")))))))

(defn tmux-session-exists? [socket session]
  (and (not (str/blank? socket))
       (not (str/blank? session))
       (or (zero? (:exit (sh-continue "tmux" "-S" socket "has-session" "-t" session)))
           (let [result (sh-continue "tmux" "-S" socket "list-sessions" "-F" "#S")]
             (and (zero? (:exit result))
                  (contains? (set (str/split-lines (:out result))) session))))))

(defn git-lines [dir & args]
  (let [result (apply sh-continue (concat ["git" "-C" (str dir)] args))]
    (when (zero? (:exit result))
      (remove str/blank? (str/split-lines (:out result))))))

(defn dirty-lines [worktree]
  (if (and worktree (fs/directory? worktree))
    (vec (or (git-lines worktree "status" "--porcelain=v1" "--untracked-files=all") []))
    []))

(defn committed-count [root worktree]
  (if-not (and worktree (fs/directory? worktree))
    0
    (let [base (str/trim (:out (sh-continue "git" "-C" (str root) "rev-parse" "HEAD")))
          result (sh-continue "git" "-C" (str worktree) "rev-list" "--count" (str base "..HEAD"))]
      (if (and (zero? (:exit result))
               (re-matches #"[0-9]+" (str/trim (:out result))))
        (Long/parseLong (str/trim (:out result)))
        0))))

(defn parse-instant [value]
  (try
    (when-not (str/blank? value)
      (java.time.Instant/parse value))
    (catch Exception _ nil)))

(defn env-long [name default-value]
  (if-let [value (System/getenv name)]
    (if (re-matches #"[0-9]+" value)
      (Long/parseLong value)
      default-value)
    default-value))

(defn now-instant []
  (or (parse-instant (System/getenv "SWARMFORGE_NOW"))
      (java.time.Instant/now)))

(defn recent-status? [root agent]
  (let [grace-seconds (env-long "SWARMFORGE_SQUAD_RECOVERY_GRACE_SECONDS" 300)
        status-file (fs/path root ".squad" "agents" agent "status")
        heartbeat-file (fs/path root ".squad" "agents" agent "heartbeat")
        instants (keep #(parse-instant (read-value % "updated_at"))
                       [status-file heartbeat-file])
        newest (when (seq instants)
                 (apply max-key #(.toEpochMilli %) instants))]
    (and newest
         (< (.getSeconds (java.time.Duration/between newest (now-instant)))
            grace-seconds))))

(defn handoff-files [root worktree agent]
  (let [dirs [(fs/path root ".swarmforge" "handoffs")
              (when worktree (fs/path worktree ".swarmforge" "handoffs"))]]
    (->> dirs
         (remove nil?)
         (filter fs/exists?)
         (mapcat #(fs/glob % (str "**/*from_" agent "_to_squad-leader*.handoff")))
         (map str)
         sort
         vec)))

(defn assignment-status-state [root task-id]
  (when (and task-id (not (str/blank? task-id)) (not= "unknown" task-id))
    (or (read-value (fs/path root ".squad" "assignments" task-id "status") "state")
        (when (fs/directory? (fs/path root ".squad" "assignments" task-id))
          "unknown"))))

(defn open-assignment?
  "True when task still needs work (non-terminal assignment status)."
  [root task-id]
  (let [state (assignment-status-state root task-id)]
    (boolean (and state (not (contains? terminal-assignment-states state))))))

(defn print-dirty-lines! [dirty]
  (println "DIRTY_FILES:" (count dirty))
  (doseq [line dirty]
    (println "DIRTY:" line)))

(defn print-handoffs! [handoffs]
  (println "HANDOFFS:" (count handoffs))
  (doseq [handoff handoffs]
    (println "HANDOFF:" handoff)))

(defn value-or-unknown [value]
  (clojure.core/or value "unknown"))

(defn bool-string [value]
  (if value "true" "false"))

(defn recovery-header-fields [{:keys [agent task-id template session worktree live?]}]
  [["AGENT" agent]
   ["TASK_ID" (value-or-unknown task-id)]
   ["TEMPLATE" (value-or-unknown template)]
   ["SESSION" (value-or-unknown session)]
   ["SESSION_LIVE" (bool-string live?)]
   ["WORKTREE" (value-or-unknown worktree)]])

(defn print-recovery-header! [check]
  (doseq [[label value] (recovery-header-fields check)]
    (println (str label ":") value)))

(def recovery-recommendations
  {"live" "Do not retire or replace this agent based on missing-session status."
   "delivered_handoff" "Process the delivered handoff before deciding recovery."
   "session_dead"
   "Session is gone with open assignment. Repair: remove dead agent, clear death blockers, requeue same task (squad_recover.sh repair <agent>)."
   "dirty_worktree" "Ask the user before retiring, replacing, editing, or recovering this worktree."
   "committed_no_handoff" "Ask the user before recovering committed work without a handoff."
   "recently_active_no_work" "Do not reject or replace yet. Wait for handoff delivery or another recovery check after the grace period."
   "failed_no_work" "It is safe to reject or replace if the assignment still needs work."})

(defn repair-owner
  "Dirty/committed work → Troubleshooter; clean session_dead → Squad Leader."
  [state dirty committed]
  (when (= "session_dead" state)
    (if (or (seq dirty) (pos? committed))
      "troubleshooter"
      "squad-leader")))

(defn session-dead-context?
  "Dead session + open assignment (after grace for empty recent workers)."
  [{:keys [root agent live? dirty committed task-id]}]
  (and (not live?)
       (open-assignment? root task-id)
       (or (seq dirty)
           (pos? committed)
           (not (recent-status? root agent)))))

(def recovery-state-rules
  [["live" :live?]
   ["delivered_handoff" #(seq (:handoffs %))]
   ;; Empty tree + recent heartbeat: wait grace (even if assignment open).
   ["recently_active_no_work" (fn [{:keys [root agent live? dirty committed]}]
                                (and (not live?)
                                     (empty? dirty)
                                     (zero? committed)
                                     (recent-status? root agent)))]
   ;; Silent death with open work — primary repair path.
   ["session_dead" session-dead-context?]
   ["dirty_worktree" #(seq (:dirty %))]
   ["committed_no_handoff" #(pos? (:committed %))]])

(defn recovery-rule-matches? [context [_ predicate]]
  (if (keyword? predicate)
    (predicate context)
    (predicate context)))

(defn recovery-state [{:keys [root agent live? dirty committed handoffs task-id]}]
  (or (some (fn [[state :as rule]]
              (when (recovery-rule-matches? {:root root
                                             :agent agent
                                             :live? live?
                                             :dirty dirty
                                             :committed committed
                                             :handoffs handoffs
                                             :task-id task-id}
                                            rule)
                state))
            recovery-state-rules)
      "failed_no_work"))

(defn recovery-check [root agent worktree socket session task-id]
  (let [live? (tmux-session-exists? socket session)
        dirty (dirty-lines worktree)
        committed (committed-count root worktree)
        handoffs (handoff-files root worktree agent)
        state (recovery-state {:root root
                               :agent agent
                               :live? live?
                               :dirty dirty
                               :committed committed
                               :handoffs handoffs
                               :task-id task-id})
        owner (repair-owner state dirty committed)]
    {:live? live?
     :dirty dirty
     :committed committed
     :handoffs handoffs
     :state state
     :repair-owner owner
     :open-assignment? (open-assignment? root task-id)
     :recommendation (recovery-recommendations state)}))

(defn write-recovery-check! [root agent state]
  (let [file (fs/path root ".squad" "agents" agent "recovery")]
    (fs/create-dirs (fs/parent file))
    (spit (str file)
          (str "state: " state "\n"
               "checked_at: " (str (now-instant)) "\n"))))

(defn print-repair-plan! [{:keys [state repair-owner task-id agent dirty committed]}]
  (when (= "session_dead" state)
    (println "REPAIR_OWNER:" repair-owner)
    (println "REPAIR_PLAN: remove_dead_agent; clear_death_blockers; requeue_assignment")
    (println "ASSIGNMENT:" (value-or-unknown task-id))
    (println "DIRTY_OR_COMMITTED:" (bool-string (or (seq dirty) (pos? committed))))
    (println "COMMAND_ON_REPAIR:" (str "squad_recover.sh repair " agent))
    (when (= "troubleshooter" repair-owner)
      (println "AUTHORITY: troubleshooter")
      (println "NOTE: Dirty or committed worktree — Troubleshooter/operator triage before or via repair (archive then requeue)."))
    (when (= "squad-leader" repair-owner)
      (println "AUTHORITY: squad-leader")
      (println "NOTE: Clean dead session — Squad Leader may run repair to free singleton and requeue task."))))

(defn print-recovery [{:keys [dirty committed handoffs state recommendation] :as check}]
  (print-recovery-header! check)
  (print-dirty-lines! dirty)
  (println "COMMITS_AHEAD:" committed)
  (print-handoffs! handoffs)
  (println "OPEN_ASSIGNMENT:" (bool-string (:open-assignment? check)))
  (println "RECOVERY_STATE:" state)
  (println "RECOMMENDATION:" recommendation)
  (print-repair-plan! check))

(defn recovery-context [root agent]
  (let [metadata (fs/path root ".squad" "agents" agent "metadata")
        row (role-row root agent)
        socket-file (fs/path root ".swarmforge" "tmux-socket")]
    {:metadata metadata
     :row row
     :worktree (or (read-value metadata "worktree") (:worktree-path row))
     :session (or (read-value metadata "session") (:session row))
     :template (read-value metadata "template")
     :task-id (or (read-value metadata "task_id") "unknown")
     :socket (when (fs/exists? socket-file) (str/trim (slurp (str socket-file))))}))

(defn ensure-known-worktree! [agent worktree]
  (when (str/blank? worktree)
    (exit! 1 (str "Unknown squad agent worktree: " agent))))

(defn checked-recovery [root agent {:keys [worktree socket session task-id] :as context}]
  (ensure-known-worktree! agent worktree)
  (let [check (recovery-check root agent worktree socket session task-id)]
    (write-recovery-check! root agent (:state check))
    (merge context check {:agent agent})))

;;; ---  repair: remove dead agent, clear blockers, requeue same task ---

(defn read-role-rows [roles-file]
  (if (fs/exists? roles-file)
    (->> (str/split-lines (slurp (str roles-file)))
         (remove str/blank?)
         (map #(str/split % #"\t" -1))
         vec)
    []))

(defn write-roles! [roles-file rows]
  (write-atomic! roles-file
                 (str (str/join "\n" (map #(str/join "\t" %) rows))
                      (when (seq rows) "\n"))))

(defn remove-role! [roles-file agent-id]
  (let [rows (read-role-rows roles-file)
        match (some #(when (= agent-id (first %)) %) rows)]
    (when-not match
      (exit! 1 (str "Agent not registered in roles.tsv: " agent-id)))
    (write-roles! roles-file (vec (remove #(= agent-id (first %)) rows)))
    match))

(defn archive-worktree! [root agent worktree]
  "Copy worktree to .squad/recovery-archive/<agent>-<ts> when it exists."
  (when (and worktree (fs/directory? worktree))
    (let [stamp (.format java.time.format.DateTimeFormatter/BASIC_ISO_DATE
                         (java.time.LocalDate/now))
          dest (fs/path root ".squad" "recovery-archive"
                        (str agent "-" stamp "-" (System/currentTimeMillis)))]
      (fs/create-dirs (fs/parent dest))
      (fs/copy-tree worktree dest)
      (println "ARCHIVED_WORKTREE:" (str dest))
      (str dest))))

(defn clear-missing-session! [root agent]
  (fs/delete-if-exists (fs/path root ".squad" "agents" agent "missing-session")))

(defn clear-assignment-blockers! [root assignment-id]
  (when (and assignment-id (not= "unknown" assignment-id))
    (let [dir (fs/path root ".squad" "assignments" assignment-id)]
      (doseq [name ["blocker" "blocker.md"]]
        (when (fs/delete-if-exists (fs/path dir name))
          (println "CLEARED_ASSIGNMENT_BLOCKER:" (str (fs/path dir name)))))
      (let [blockers (fs/path root ".squad" "blockers")]
        (when (fs/directory? blockers)
          (doseq [file (fs/list-dir blockers)
                  :when (fs/regular-file? file)
                  :let [text (try (slurp (str file)) (catch Exception _ ""))]
                  :when (or (str/includes? text assignment-id)
                            (str/includes? (fs/file-name file) assignment-id))]
            (fs/delete-if-exists file)
            (fs/delete-if-exists (fs/path blockers (str (fs/file-name file) ".md")))
            (println "CLEARED_BLOCKER:" (str file))))))))

(defn requeue-assignment! [root assignment-id]
  (when (and assignment-id (not= "unknown" assignment-id)
             (fs/directory? (fs/path root ".squad" "assignments" assignment-id)))
    (let [status-file (fs/path root ".squad" "assignments" assignment-id "status")
          meta-file (fs/path root ".squad" "assignments" assignment-id "metadata")
          theme (or (read-value meta-file "theme_id") "unknown")
          template (or (read-value meta-file "template") "unknown")
          story (or (read-value meta-file "story_id") "unknown")
          now (timestamp)]
      (write-atomic! status-file
                     (str "assignment_id: " assignment-id "\n"
                          "state: created\n"
                          "detail: requeued after dead-agent repair\n"
                          "updated_at: " now "\n"))
      (println "REQUEUED_ASSIGNMENT:" assignment-id)
      (println "ASSIGNMENT_STATE: created")
      {:theme theme :template template :story story})))

(defn kill-session-if-any! [socket session]
  (when (and (not (str/blank? socket))
             (not (str/blank? session))
             (tmux-session-exists? socket session))
    (sh-continue "tmux" "-S" socket "kill-session" "-t" session)
    (println "SESSION_KILLED:" session)))

(defn remove-worktree-best-effort! [root worktree agent]
  (when (and worktree (fs/directory? worktree))
    (sh-continue "git" "-C" (str root) "worktree" "remove" "--force" (str worktree))
    (when (fs/directory? worktree)
      (fs/delete-tree worktree))
    (println "WORKTREE_REMOVED:" (str worktree)))
  (let [branch (str "swarmforge-" agent)]
    (sh-continue "git" "-C" (str root) "branch" "-D" branch)
    (println "BRANCH_DELETED:" branch)))

(defn write-agent-retired! [agent-dir agent detail]
  (fs/create-dirs agent-dir)
  (write-atomic! (fs/path agent-dir "status")
                 (str "state: retired\n"
                      "detail: " detail "\n"
                      "updated_at: " (timestamp) "\n"))
  (write-atomic! (fs/path agent-dir "heartbeat")
                 (str "agent: " agent "\n"
                      "task_id: " (or (read-value (fs/path agent-dir "metadata") "task_id") "unknown") "\n"
                      "state: retired\n"
                      "detail: " detail "\n"
                      "updated_at: " (timestamp) "\n")))

(defn cleanup-empty-lock! [lock-dir]
  (when (and (fs/directory? lock-dir)
             (not (fs/exists? (fs/path lock-dir "owner")))
             (empty? (fs/list-dir lock-dir)))
    (fs/delete-tree lock-dir)))

(defn try-acquire-lock! [lock-dir]
  (try
    (fs/create-dir lock-dir)
    (spit (str (fs/path lock-dir "owner"))
          (str "pid: " (.pid (java.lang.ProcessHandle/current)) "\n"))
    true
    (catch java.nio.file.FileAlreadyExistsException _
      (cleanup-empty-lock! lock-dir)
      false)))

(defn acquire-lock! [lock-dir]
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (when (> (System/currentTimeMillis) deadline)
        (exit! 2 (str "Timed out waiting for squad registry lock: " lock-dir)))
      (if (try-acquire-lock! lock-dir)
        nil
        (do (Thread/sleep 50) (recur))))))

(defn repair!
  "Archive dirty tree if needed, requeue assignment, free dead agent."
  [agent]
  (when (or (str/blank? agent)
            (#{"squad-leader" "troubleshooter"} agent))
    (exit! 2 (str "Cannot repair persistent or blank agent: " agent)))
  (let [root (fs/absolutize (project-root))
        context (recovery-context root agent)
        worktree (:worktree context)
        _ (ensure-known-worktree! agent worktree)
        check (recovery-check root agent worktree (:socket context) (:session context) (:task-id context))
        state (:state check)
        lock-dir (fs/path root ".swarmforge" "squad" "spawn.lock")]
    (when (:live? check)
      (exit! 2 (str "Refusing repair: session still live for " agent)))
    (when-not (or (= "session_dead" state)
                  (and (not (:live? check))
                       (open-assignment? root (:task-id context))))
      (exit! 2 (str "Refusing repair: recovery state is " state
                    " (need session_dead or dead session with open assignment)")))
    (fs/create-dirs (fs/parent lock-dir))
    (acquire-lock! lock-dir)
    (try
      (let [task-id (:task-id context)
            dirty (:dirty check)
            committed (:committed check)]
        (println "SQUAD_REPAIR:" agent)
        (println "TASK_ID:" task-id)
        (println "REPAIR_OWNER_WAS:" (or (:repair-owner check) "unknown"))
        (when (or (seq dirty) (pos? committed))
          (archive-worktree! root agent worktree))
        (clear-missing-session! root agent)
        (clear-assignment-blockers! root task-id)
        (requeue-assignment! root task-id)
        (kill-session-if-any! (:socket context) (:session context))
        (when (role-row root agent)
          (remove-role! (fs/path root ".swarmforge" "roles.tsv") agent))
        (remove-worktree-best-effort! root worktree agent)
        (write-agent-retired! (fs/path root ".squad" "agents" agent) agent
                              "dead-agent repair: assignment requeued for new spawn")
        (write-recovery-check! root agent "repaired")
        (println "AGENT_REMOVED:" agent)
        (println "STATE: repaired")
        (println "NEXT: daemon/SL may create_assignment spawn or request_spawn for requeued task"))
      (finally
        (fs/delete-tree lock-dir)))))

(defn classify! [agent]
  (let [root (fs/absolutize (project-root))
        context (recovery-context root agent)]
    (print-recovery (checked-recovery root agent context))))

(defn -main [& args]
  (cond
    (and (= 2 (count args)) (= "repair" (first args)))
    (repair! (second args))

    (= 1 (count args))
    (classify! (first args))

    :else
    (exit! 2 usage-text)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
