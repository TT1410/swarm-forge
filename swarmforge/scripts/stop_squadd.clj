#!/usr/bin/env bb

(ns stop-squadd
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def default-timeout-ms 5000)
(def poll-ms 100)

(defn usage []
  (binding [*out* *err*]
    (println "Usage: stop_squadd.clj <project-root>"))
  (System/exit 1))

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

(defn stop! [project-root & {:keys [timeout-ms] :or {timeout-ms default-timeout-ms}}]
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
    (cleanup-transient-git! project-root)
    (fs/delete-if-exists (fs/path daemon-dir "squad-web-url"))
    (fs/delete-if-exists stop-file)))

(defn -main [& args]
  (stop! (or (first args) (usage)))
  (System/exit 0))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
