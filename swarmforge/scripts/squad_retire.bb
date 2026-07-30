#!/usr/bin/env bb

(ns squad-retire
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
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

(defn project-root []
  (let [cwd (fs/cwd)
        direct (fs/path cwd ".swarmforge" "roles.tsv")]
    (if (fs/exists? direct)
      cwd
      (let [git-root (str/trim (:out (run-continue "git" "rev-parse" "--show-toplevel")))]
        (if (and (not (str/blank? git-root))
                 (fs/exists? (fs/path git-root ".swarmforge" "roles.tsv")))
          (fs/path git-root)
          (exit! 1 "Cannot find SwarmForge project root"))))))

(defn validate-agent-id! [agent-id]
  (when-not (re-matches #"[a-z][a-z0-9-]*-[0-9][0-9][0-9]" agent-id)
    (exit! 2 "Agent ids must look like template-001 and use lowercase letters, digits, and hyphens."))
  (when (= "squad-leader" agent-id)
    (exit! 2 "Refusing to retire persistent role squad-leader.")))

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

(defn stop-session! [socket session]
  (if (str/blank? socket)
    false
    (let [result (run-continue "tmux" "-S" socket "kill-session" "-t" session)]
      (zero? (:exit result)))))

(defn retire! [agent-id]
  (validate-agent-id! agent-id)
  (let [root (fs/absolutize (project-root))
        state-dir (fs/path root ".swarmforge")
        roles-file (fs/path state-dir "roles.tsv")
        lock-dir (fs/path state-dir "squad" "spawn.lock")
        agent-dir (fs/path root ".squad" "agents" agent-id)]
    (when-not (fs/regular-file? roles-file)
      (exit! 1 "Run ./swarm before retiring transient agents; .swarmforge/roles.tsv is missing."))
    (fs/create-dirs (fs/parent lock-dir))
    (acquire-lock! lock-dir)
    (try
      (let [row (remove-role! roles-file agent-id)
            session (nth row 3)
            socket-file (fs/path state-dir "tmux-socket")
            socket (when (fs/exists? socket-file)
                     (str/trim (slurp (str socket-file))))
            stopped? (stop-session! socket session)]
        (fs/create-dirs agent-dir)
        (let [detail (if stopped?
                       "tmux session stopped; worktree preserved"
                       "tmux session was not running; worktree preserved")]
          (write-status! agent-dir "retired" detail)
          (write-heartbeat! agent-dir agent-id "retired" detail))
        (println "SQUAD_AGENT_RETIRED:" agent-id)
        (println "SESSION:" session)
        (println "SESSION_STOPPED:" stopped?)
        (println "WORKTREE:" (nth row 2))
        (println "STATUS:" (str (fs/path agent-dir "status"))))
      (finally
        (fs/delete-tree lock-dir)))))

(defn -main [& args]
  (when-not (= 1 (count args))
    (exit! 1 usage-text))
  (retire! (first args)))

(apply -main *command-line-args*)
