#!/usr/bin/env bb

(ns stop-squadd
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def default-timeout-ms 5000)
(def poll-ms 100)

(defn usage []
  (binding [*out* *err*]
    (println "Usage: stop_squadd.clj <project-root> [--full-teardown]"))
  (System/exit 1))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn process-alive? [pid]
  (zero? (:exit (process/sh {:continue true} "kill" "-0" pid))))

(defn current-pid []
  (str (.pid (java.lang.ProcessHandle/current))))

(defn process-lines []
  (str/split-lines
   (:out (process/sh {:continue true} "ps" "-ax" "-o" "pid=,command="))))

(defn matching-orphan-pids [project-root]
  (let [root (str (fs/absolutize project-root))
        current (current-pid)]
    (->> (process-lines)
         (keep (fn [line]
                 (let [[_ pid command] (re-matches #"\s*([0-9]+)\s+(.*)" line)]
                   (when (and pid
                              command
                              (not= pid current)
                              (str/includes? command "squadd.clj")
                              (str/includes? command root))
                     pid))))
         distinct
         vec)))

(defn numeric-pid? [pid]
  (boolean (re-matches #"[0-9]+" pid)))

(defn wait-for-exit! [pid timeout-ms]
  (loop [waited 0]
    (when (and (< waited timeout-ms) (process-alive? pid))
      (Thread/sleep poll-ms)
      (recur (+ waited poll-ms)))))

(defn terminate-pid! [pid timeout-ms]
  (when (and (numeric-pid? pid) (process-alive? pid))
    (process/sh {:continue true} "kill" "-TERM" pid)
    (wait-for-exit! pid timeout-ms)
    (when (process-alive? pid)
      (process/sh {:continue true} "kill" "-KILL" pid)
      (Thread/sleep poll-ms))))

(defn pid-file-pid [pid-file]
  (when (fs/exists? pid-file)
    (str/trim (slurp (str pid-file)))))

(defn read-lines [file]
  (if (fs/exists? file)
    (str/split-lines (slurp (str file)))
    []))

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (read-lines file)))))

(defn transient-roles [project-root]
  (for [line (read-lines (fs/path project-root ".swarmforge" "roles.tsv"))
        :when (not (str/blank? line))
        :let [[role _ worktree session _ _ _] (str/split line #"\t" -1)]
        :when (not= "squad-leader" role)]
    {:role role
     :worktree worktree
     :session session}))

(defn assignment-id [project-root role]
  (read-value (fs/path project-root ".squad" "agents" role "metadata") "task_id"))

(defn assignment-merge-blocked? [project-root assignment]
  (and (not (str/blank? assignment))
       (or (= "merge_blocked"
              (read-value (fs/path project-root ".squad" "assignments" assignment "status") "state"))
           (= "merge_blocked"
              (read-value (fs/path project-root ".squad" "assignments" assignment "merge") "state")))))

(defn managed-worktree? [project-root role worktree]
  (and (not (str/blank? worktree))
       (= (str (fs/absolutize (fs/path project-root ".worktrees" role)))
          (str (fs/absolutize worktree)))))

(defn git! [project-root & args]
  (apply process/sh (concat [{:continue true :dir (str project-root)} "git"] args)))

(defn remove-worktree! [project-root role worktree]
  (when (managed-worktree? project-root role worktree)
    (let [result (git! project-root "worktree" "remove" "--force" worktree)]
      (when-not (zero? (:exit result))
        (when (fs/exists? worktree)
          (fs/delete-tree worktree))
        (git! project-root "worktree" "prune")))))

(defn delete-branch! [project-root role]
  (git! project-root "branch" "-D" (str "swarmforge-" role)))

(defn cleanup-transient-git! [project-root]
  (doseq [{:keys [role worktree]} (transient-roles project-root)
          :let [assignment (assignment-id project-root role)]
          :when (not (assignment-merge-blocked? project-root assignment))]
    (remove-worktree! project-root role worktree)
    (delete-branch! project-root role)))

(defn write-retired-agent-status! [project-root agent-id detail]
  (let [agent-dir (fs/path project-root ".squad" "agents" agent-id)
        now (timestamp)
        task-id (or (read-value (fs/path agent-dir "metadata") "task_id") "unknown")]
    (fs/create-dirs agent-dir)
    (spit (str (fs/path agent-dir "status"))
          (str "state: retired\n"
               "detail: " detail "\n"
               "updated_at: " now "\n"))
    (spit (str (fs/path agent-dir "heartbeat"))
          (str "agent: " agent-id "\n"
               "task_id: " task-id "\n"
               "state: retired\n"
               "detail: " detail "\n"
               "updated_at: " now "\n"))))

(defn agent-ids-on-disk [project-root]
  (let [dir (fs/path project-root ".squad" "agents")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (map #(fs/file-name %))
           sort
           vec)
      [])))

(defn retire-all-agents! [project-root detail]
  (doseq [agent-id (agent-ids-on-disk project-root)]
    (write-retired-agent-status! project-root agent-id detail)))

(defn registered-managed-worktrees [project-root]
  (let [root (str (fs/absolutize project-root))
        prefix (str root "/.worktrees/")]
    (->> (str/split-lines (:out (git! project-root "worktree" "list" "--porcelain")))
         (keep (fn [line]
                 (when (str/starts-with? line "worktree ")
                   (let [path (subs line (count "worktree "))]
                     (when (str/starts-with? path prefix)
                       path)))))
         vec)))

(defn filesystem-managed-worktrees [project-root]
  (let [dir (fs/path project-root ".worktrees")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (map str)
           vec)
      [])))

(defn force-remove-worktree-path! [project-root worktree-path]
  (let [path (str worktree-path)
        result (git! project-root "worktree" "remove" "--force" path)]
    (when (fs/exists? path)
      (fs/delete-tree path))
    (when-not (zero? (:exit result))
      (git! project-root "worktree" "prune"))
    (let [agent-id (fs/file-name path)]
      (delete-branch! project-root agent-id))))

(defn force-cleanup-all-managed-worktrees! [project-root]
  (let [paths (distinct (concat (registered-managed-worktrees project-root)
                                (filesystem-managed-worktrees project-root)))]
    (doseq [path paths]
      (force-remove-worktree-path! project-root path))
    (git! project-root "worktree" "prune")
    paths))

(defn keep-only-squad-leader-roles! [project-root]
  (let [roles-file (fs/path project-root ".swarmforge" "roles.tsv")
        lines (read-lines roles-file)
        kept (filter #(str/starts-with? % "squad-leader\t") lines)]
    (when (fs/exists? roles-file)
      (spit (str roles-file)
            (if (seq kept)
              (str (str/join "\n" kept) "\n")
              "")))))

(defn remaining-managed-worktrees [project-root]
  (distinct (concat (registered-managed-worktrees project-root)
                    (filesystem-managed-worktrees project-root))))

(defn agent-worktree [project-root agent-id]
  (or (not-empty (read-value (fs/path project-root ".squad" "agents" agent-id "metadata")
                             "worktree"))
      (str (fs/path project-root ".worktrees" agent-id))))

(defn merge-blocked-worktree-paths
  "Discover merge_blocked recovery worktrees from agent metadata + assignment
  status so paths survive after roles.tsv is reduced to squad-leader."
  [project-root]
  (set
   (for [agent-id (agent-ids-on-disk project-root)
         :when (not= "squad-leader" agent-id)
         :let [assignment (assignment-id project-root agent-id)
               worktree (agent-worktree project-root agent-id)]
         :when (and (assignment-merge-blocked? project-root assignment)
                    (not (str/blank? worktree)))]
     (str (fs/absolutize worktree)))))

(defn cleanup-non-merge-blocked-worktrees!
  "Remove managed worktrees except those held for merge_blocked recovery."
  [project-root]
  (let [preserve (merge-blocked-worktree-paths project-root)
        paths (distinct (concat (registered-managed-worktrees project-root)
                                (filesystem-managed-worktrees project-root)))]
    (doseq [path paths
            :when (not (contains? preserve (str (fs/absolutize path))))]
      (force-remove-worktree-path! project-root path))
    (git! project-root "worktree" "prune")
    (doseq [path (sort preserve)]
      (println "PRESERVED_FOR_RECOVERY:" path
               "reason=merge_blocked"))
    preserve))

(defn report-remaining-worktrees! [project-root]
  (let [preserve (merge-blocked-worktree-paths project-root)]
    (doseq [path (remaining-managed-worktrees project-root)]
      (if (contains? preserve (str (fs/absolutize path)))
        (println "PRESERVED_FOR_RECOVERY:" path "reason=merge_blocked")
        (println "PRESERVED_WORKTREE:" path "reason=cleanup_residue")))))

(defn full-teardown-reconcile!
  "After processes are dead: retire agents, remove worktrees that are not held
  for merge_blocked recovery, prune, reduce roles to squad-leader, and report
  intentionally preserved vs residual worktrees."
  [project-root]
  (retire-all-agents! project-root "swarm terminated by cleanup")
  (cleanup-non-merge-blocked-worktrees! project-root)
  (keep-only-squad-leader-roles! project-root)
  (report-remaining-worktrees! project-root))

(defn stop! [project-root & {:keys [timeout-ms full-teardown?]
                             :or {timeout-ms default-timeout-ms
                                  full-teardown? false}}]
  (let [daemon-dir (fs/path project-root ".swarmforge" "daemon")
        pid-file (fs/path daemon-dir "squadd.pid")
        stop-file (fs/path daemon-dir "squadd.stop")]
    (fs/create-dirs daemon-dir)
    (when-not (fs/exists? stop-file)
      (spit (str stop-file) ""))
    (when-let [pid (pid-file-pid pid-file)]
      (terminate-pid! pid timeout-ms)
      (fs/delete-if-exists pid-file))
    (doseq [pid (matching-orphan-pids project-root)]
      (terminate-pid! pid timeout-ms))
    (if full-teardown?
      (full-teardown-reconcile! project-root)
      (cleanup-transient-git! project-root))
    (fs/delete-if-exists (fs/path daemon-dir "squad-web-url"))
    (fs/delete-if-exists stop-file)))

(defn -main [& args]
  (when (or (empty? args) (= "-h" (first args)) (= "--help" (first args)))
    (usage))
  (let [project-root (first args)
        full-teardown? (some #{"--full-teardown"} (rest args))]
    (stop! project-root :full-teardown? full-teardown?)
    (System/exit 0)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
