#!/usr/bin/env bb

(ns squad-retire
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-config :as cfg]
            [squad-lease :as lease]
            [clojure.string :as str]))

(def usage-text
  "Usage: squad_retire.sh <agent-id>")

(defn exit! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn run-continue [& args]
  (apply process/sh (concat [{:continue true}] args)))

(defn git-continue [root & args]
  (apply run-continue (concat ["git" "-C" (str root)] args)))

(defn project-root []
  (or (cfg/project-root)
      (exit! 1 "Cannot find SwarmForge project root")))

(defn validate-agent-id! [agent-id]
  (when-not (re-matches #"[a-z][a-z0-9-]*-[0-9][0-9][0-9]" agent-id)
    (exit! 2 "Agent ids must look like template-001 and use lowercase letters, digits, and hyphens."))
  (when (#{"squad-leader" "troubleshooter"} agent-id)
    (exit! 2 (str "Refusing to retire persistent role " agent-id "."))))

(defn read-role-rows [roles-file]
  (if (fs/exists? roles-file)
    (->> (str/split-lines (slurp (str roles-file)))
         (remove str/blank?)
         (map #(str/split % #"\t" -1))
         vec)
    []))

(defn write-atomic! [file content]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                  :prefix (str "." (fs/file-name file) ".")})]
    (spit (str tmp) content)
    (fs/move tmp file {:replace-existing true})))

(defn acquire-lock!
  "Shared spawn/registry lease."
  [lock-dir]
  (let [lock-dir (fs/path lock-dir)
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

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(defn write-status! [agent-dir state detail]
  (write-atomic! (fs/path agent-dir "status")
                 (str "state: " state "\n"
                      "detail: " detail "\n"
                      "updated_at: " (timestamp) "\n")))

(defn write-heartbeat! [agent-dir agent-id state detail]
  (let [task-id (or (read-value (fs/path agent-dir "metadata") "task_id") "unknown")]
    (write-atomic! (fs/path agent-dir "heartbeat")
                   (str "agent: " agent-id "\n"
                        "task_id: " task-id "\n"
                        "state: " state "\n"
                        "detail: " detail "\n"
                        "updated_at: " (timestamp) "\n"))))

(defn remove-role! [roles-file agent-id]
  (let [rows (read-role-rows roles-file)
        matching (filter #(= agent-id (first %)) rows)
        remaining (remove #(= agent-id (first %)) rows)]
    (when (empty? matching)
      (exit! 1 (str "Unknown transient agent: " agent-id)))
    (write-atomic! roles-file
                   (apply str
                          (for [row remaining]
                            (str (str/join "\t" row) "\n"))))
    (first matching)))

(defn transient-branch [agent-id]
  (str "swarmforge-" agent-id))

(defn managed-worktree? [root agent-id worktree]
  (let [managed-root (fs/absolutize (fs/path root ".worktrees"))
        expected (fs/absolutize (fs/path managed-root agent-id))
        actual (fs/absolutize worktree)]
    (= (str expected) (str actual))))

(defn worktree-removable? [root agent-id worktree]
  (cond
    (str/blank? (str worktree)) {:ok? false :detail "worktree metadata missing"}
    (not (managed-worktree? root agent-id worktree)) {:ok? false :detail "worktree path is outside managed transient worktrees"}
    :else {:ok? true}))

(defn force-remove-worktree! [root worktree]
  (let [result (git-continue root "worktree" "remove" "--force" (str worktree))]
    (when-not (zero? (:exit result))
      (when (fs/exists? worktree)
        (fs/delete-tree worktree))
      (git-continue root "worktree" "prune"))))

(defn worktree-remove-result [worktree]
  {:removed? (not (fs/exists? worktree))
   :detail (if (fs/exists? worktree)
             "worktree removal failed"
             "worktree removed")})

(defn remove-worktree! [root agent-id worktree]
  (let [{:keys [ok? detail]} (worktree-removable? root agent-id worktree)]
    (if-not ok?
      {:removed? false :detail detail}
      (do
        (force-remove-worktree! root worktree)
        (worktree-remove-result worktree)))))

(defn delete-branch! [root agent-id]
  (let [branch (transient-branch agent-id)
        result (git-continue root "branch" "-D" branch)]
    {:deleted? (zero? (:exit result))
     :branch branch}))

(defn session-target
  "Exact tmux session target (Avoid prefix false matches / misses)."
  [session]
  (str "=" session))

(defn pane-target
  "Exact pane in the named session. capture-pane -t is a pane target.
  `=session` is a session target; tmux reports can't find pane."
  [session]
  (str "=" session ":"))

(defn session-exists? [socket session]
  (and (not (str/blank? socket))
       (not (str/blank? session))
       (zero? (:exit (run-continue "tmux" "-S" socket "has-session" "-t"
                                   (session-target session))))))

(defn stopped-session-result []
  {:stopped? true :detail "tmux session stopped"})

(defn lingering-session-result []
  {:stopped? false :detail "tmux session still exists after kill-session"})

(defn wait-session-step [socket session remaining]
  (cond
    (not (session-exists? socket session)) :stopped
    (zero? remaining) :timed-out
    :else :retry))

(defn wait-session-stopped [socket session]
  (loop [remaining 20]
    (case (wait-session-step socket session remaining)
      :stopped (stopped-session-result)
      :timed-out (lingering-session-result)
      :retry (do
               (Thread/sleep 100)
               (recur (dec remaining))))))

(defn kill-session-attempts! [socket session]
  "Kill with exact target, then bare name (tmux version quirks)."
  (run-continue "tmux" "-S" socket "kill-session" "-t" (session-target session))
  (run-continue "tmux" "-S" socket "kill-session" "-t" session))

(defn stop-running-session! [socket session]
  "Kill, wait, force kill again if lingering, wait once more."
  (kill-session-attempts! socket session)
  (let [first-wait (wait-session-stopped socket session)]
    (if (:stopped? first-wait)
      first-wait
      (do
        (kill-session-attempts! socket session)
        (wait-session-stopped socket session)))))

(defn stop-session! [socket session]
  (cond
    (or (str/blank? socket) (str/blank? session))
    {:stopped? false :detail "tmux socket or session metadata missing"}

    (not (session-exists? socket session))
    ;; Best-effort kill for has-session false-negatives; absent session is not a leak.
    (do
      (kill-session-attempts! socket session)
      (if (session-exists? socket session)
        (stop-running-session! socket session)
        {:stopped? false :detail "tmux session was not running"}))

    :else
    (stop-running-session! socket session)))

(def resolved-handoff-assignment-states
  #{"merged" "rejected" "blocked" "replacement_created" "superseded"
    "review_accepted" "review_changes_requested" "cancelled" "abandoned"
    "merge_blocked"})

(defn agent-task-id [root agent-id]
  (read-value (fs/path root ".squad" "agents" agent-id "metadata") "task_id"))

(defn assignment-status-state [root assignment-id]
  (or (read-value (fs/path root ".squad" "assignments" assignment-id "status") "state")
      "unknown"))

(defn handoff-resolved-for-retire? [root assignment-id]
  "Leftover merge_blocked is terminal. A live assignment must be merged,
  rejected, blocked, or otherwise closed before retire."
  (if-not (and assignment-id
               (not= "unknown" assignment-id)
               (fs/directory? (fs/path root ".squad" "assignments" assignment-id)))
    true
    (contains? resolved-handoff-assignment-states
               (assignment-status-state root assignment-id))))

(defn ensure-handoff-resolved-for-retire! [root agent-id]
  (let [task-id (agent-task-id root agent-id)]
    (when-not (handoff-resolved-for-retire? root task-id)
      (exit! 2
             (str "Cannot retire " agent-id
                  ": assignment handoff is not terminal (task_id="
                  (or task-id "unknown")
                  " state="
                  (assignment-status-state root task-id)
                  "). Resolve merge/reject/block first, then run squad_retire.sh.")))))

(defn save-agent-sessions? [root]
  (cfg/squad-config-bool root "save_agent_sessions" true))

(defn liveness-pane-tail [root agent-id]
  (let [file (fs/path root ".squad" "agents" agent-id "liveness")]
    (when (fs/regular-file? file)
      (not-empty (second (str/split (slurp (str file)) #"(?m)^last_10_lines:\s*\n" 2))))))

(defn capture-pane-text [socket session]
  (when (and (not (str/blank? socket)) (not (str/blank? session)))
    (not-empty
     (:out (run-continue "tmux" "-S" socket "capture-pane" "-p" "-t"
                         (pane-target session) "-S" "-2000")))))

(defn archive-agent-session! [root agent-id socket session]
  (let [dir (fs/path root ".squad" "sessions" agent-id)
        pane (or (capture-pane-text socket session)
                 (liveness-pane-tail root agent-id)
                 "")
        liveness (fs/path root ".squad" "agents" agent-id "liveness")]
    (fs/create-dirs dir)
    (spit (str (fs/path dir "pane.txt")) pane)
    (when (fs/regular-file? liveness)
      (fs/copy liveness (fs/path dir "liveness") {:replace-existing true}))
    dir))

(defn retire! [agent-id]
  (validate-agent-id! agent-id)
  (let [root (fs/absolutize (project-root))
        state-dir (fs/path root ".swarmforge")
        roles-file (fs/path state-dir "roles.tsv")
        lock-dir (fs/path state-dir "squad" "spawn.lock")
        agent-dir (fs/path root ".squad" "agents" agent-id)]
    (when-not (fs/regular-file? roles-file)
      (exit! 1 "Run ./swarm before retiring transient agents; .swarmforge/roles.tsv is missing."))
    (ensure-handoff-resolved-for-retire! root agent-id)
    (fs/create-dirs (fs/parent lock-dir))
    (acquire-lock! lock-dir)
    (try
      (let [row (remove-role! roles-file agent-id)
            worktree (nth row 2)
            session (nth row 3)
            socket-file (fs/path state-dir "tmux-socket")
            socket (when (fs/exists? socket-file)
                     (str/trim (slurp (str socket-file))))
            _ (when (save-agent-sessions? root)
                (archive-agent-session! root agent-id socket session))
            {:keys [stopped? detail]} (stop-session! socket session)
            worktree-cleanup (remove-worktree! root agent-id worktree)
            branch-cleanup (delete-branch! root agent-id)
            ;; After kill path, session may still linger; role is gone so
            ;; squadd orphan reconcile can finish it off.
            leak? (and (not (str/blank? session))
                       (not (str/blank? socket))
                       (session-exists? socket session))
            retire-detail (str detail "; " (:detail worktree-cleanup)
                               "; branch " (:branch branch-cleanup)
                               (if (:deleted? branch-cleanup) " deleted" " absent")
                               (when leak?
                                 "; session still live — orphan reconcile should kill"))]
        (fs/create-dirs agent-dir)
        (write-status! agent-dir "retired" retire-detail)
        (write-heartbeat! agent-dir agent-id "retired" retire-detail)
        (println "SQUAD_AGENT_RETIRED:" agent-id)
        (println "SESSION:" session)
        (println "SESSION_STOPPED:" (boolean (and stopped? (not leak?))))
        (when leak?
          (println "SESSION_LEAK: true")
          (println "SESSION_LEAK_DETAIL: tmux session still exists after kill; daemon orphan reconcile will retry"))
        (println "WORKTREE:" worktree)
        (println "WORKTREE_REMOVED:" (:removed? worktree-cleanup))
        (println "BRANCH:" (:branch branch-cleanup))
        (println "BRANCH_DELETED:" (:deleted? branch-cleanup))
        (println "STATUS:" (str (fs/path agent-dir "status"))))
      (finally
        (fs/delete-tree lock-dir)))))

(defn -main [& args]
  (when-not (= 1 (count args))
    (exit! 1 usage-text))
  (retire! (first args)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
