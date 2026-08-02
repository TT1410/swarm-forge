#!/usr/bin/env bb

(ns squad-recover
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def usage-text
  "Usage: squad_recover.sh <agent-id>")

(defn exit! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn sh-continue [& args]
  (apply process/sh (concat [{:continue true}] args)))

(defn project-root []
  (let [configured (not-empty (System/getenv "SWARMFORGE_PROJECT_ROOT"))
        configured-roles (when configured (fs/path configured ".swarmforge" "roles.tsv"))
        cwd (fs/cwd)
        direct (fs/path cwd ".swarmforge" "roles.tsv")]
    (if (and configured (fs/exists? configured-roles))
      (fs/path configured)
      (if (fs/exists? direct)
        cwd
        (let [git-root (str/trim (:out (sh-continue "git" "rev-parse" "--show-toplevel")))]
          (if (and (not (str/blank? git-root))
                   (fs/exists? (fs/path git-root ".swarmforge" "roles.tsv")))
            (fs/path git-root)
            (exit! 1 "Cannot find SwarmForge project root")))))))

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

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
       (or (zero? (:exit (sh-continue "tmux" "-S" socket "has-session" "-t" session)))
           (let [result (sh-continue "tmux" "-S" socket "list-sessions" "-F" "#S")]
             (and (zero? (:exit result))
                  (contains? (set (str/split-lines (:out result))) session))))))

(defn git-lines [dir & args]
  (let [result (apply sh-continue (concat ["git" "-C" (str dir)] args))]
    (when (zero? (:exit result))
      (remove str/blank? (str/split-lines (:out result))))))

(defn dirty-lines [worktree]
  (vec (or (git-lines worktree "status" "--porcelain=v1" "--untracked-files=all") [])))

(defn committed-count [root worktree]
  (let [base (str/trim (:out (sh-continue "git" "-C" (str root) "rev-parse" "HEAD")))
        result (sh-continue "git" "-C" (str worktree) "rev-list" "--count" (str base "..HEAD"))]
    (if (and (zero? (:exit result))
             (re-matches #"[0-9]+" (str/trim (:out result))))
      (Long/parseLong (str/trim (:out result)))
      0)))

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

(defn recent-status? [root agent]
  (let [grace-seconds (env-long "SWARMFORGE_SQUAD_RECOVERY_GRACE_SECONDS" 300)
        status-file (fs/path root ".squad" "agents" agent "status")
        heartbeat-file (fs/path root ".squad" "agents" agent "heartbeat")
        instants (keep #(parse-instant (read-value % "updated_at"))
                       [status-file heartbeat-file])
        newest (when (seq instants)
                 (apply max-key #(.toEpochMilli %) instants))]
    (and newest
         (< (.getSeconds (java.time.Duration/between newest (java.time.Instant/now)))
            grace-seconds))))

(defn handoff-files [root worktree agent]
  (let [dirs [(fs/path root ".swarmforge" "handoffs")
              (fs/path worktree ".swarmforge" "handoffs")]]
    (->> dirs
         (filter fs/exists?)
         (mapcat #(fs/glob % (str "**/*from_" agent "_to_squad-leader*.handoff")))
         (map str)
         sort
         vec)))

(defn print-recovery [{:keys [agent task-id template session worktree live? dirty committed handoffs state recommendation]}]
  (println "AGENT:" agent)
  (println "TASK_ID:" (or task-id "unknown"))
  (println "TEMPLATE:" (or template "unknown"))
  (println "SESSION:" (or session "unknown"))
  (println "SESSION_LIVE:" (if live? "true" "false"))
  (println "WORKTREE:" (or worktree "unknown"))
  (println "DIRTY_FILES:" (count dirty))
  (doseq [line dirty]
    (println "DIRTY:" line))
  (println "COMMITS_AHEAD:" committed)
  (println "HANDOFFS:" (count handoffs))
  (doseq [handoff handoffs]
    (println "HANDOFF:" handoff))
  (println "RECOVERY_STATE:" state)
  (println "RECOMMENDATION:" recommendation))

(defn -main [& args]
  (when-not (= 1 (count args))
    (exit! 2 usage-text))
  (let [agent (first args)
        root (fs/absolutize (project-root))
        metadata (fs/path root ".squad" "agents" agent "metadata")
        row (role-row root agent)
        worktree (or (read-value metadata "worktree")
                     (:worktree-path row))
        session (or (read-value metadata "session")
                    (:session row))
        template (read-value metadata "template")
        task-id (read-value metadata "task_id")
        socket-file (fs/path root ".swarmforge" "tmux-socket")
        socket (when (fs/exists? socket-file) (str/trim (slurp (str socket-file))))]
    (when (str/blank? worktree)
      (exit! 1 (str "Unknown squad agent worktree: " agent)))
    (let [live? (tmux-session-exists? socket session)
          dirty (dirty-lines worktree)
          committed (committed-count root worktree)
          handoffs (handoff-files root worktree agent)
          [state recommendation]
          (cond
            live? ["live" "Do not retire or replace this agent based on missing-session status."]
            (seq handoffs) ["delivered_handoff" "Process the delivered handoff before deciding recovery."]
            (seq dirty) ["dirty_worktree" "Ask the user before retiring, replacing, editing, or recovering this worktree."]
            (pos? committed) ["committed_no_handoff" "Ask the user before recovering committed work without a handoff."]
            (recent-status? root agent) ["recently_active_no_work" "Do not reject or replace yet. Wait for handoff delivery or another recovery check after the grace period."]
            :else ["failed_no_work" "It is safe to reject or replace if the assignment still needs work."])]
      (print-recovery {:agent agent
                       :task-id task-id
                       :template template
                       :session session
                       :worktree worktree
                       :live? live?
                       :dirty dirty
                       :committed committed
                       :handoffs handoffs
                       :state state
                       :recommendation recommendation}))))

(apply -main *command-line-args*)
