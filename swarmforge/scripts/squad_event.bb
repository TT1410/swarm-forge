#!/usr/bin/env bb

(ns squad-event
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def usage-text
  "Usage: squad_event.sh <state> <detail...>")

(defn exit! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn sh-continue [& args]
  (apply process/sh (concat [{:continue true}] args)))

(defn project-root []
  (let [cwd (fs/cwd)
        direct (fs/path cwd ".swarmforge" "roles.tsv")]
    (if (fs/exists? direct)
      cwd
      (let [git-root (str/trim (:out (sh-continue "git" "rev-parse" "--show-toplevel")))]
        (if (and (not (str/blank? git-root))
                 (fs/exists? (fs/path git-root ".swarmforge" "roles.tsv")))
          (fs/path git-root)
          (exit! 1 "Cannot find SwarmForge project root"))))))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn write-atomic! [file content]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                  :prefix (str "." (fs/file-name file) ".")})]
    (spit (str tmp) content)
    (fs/move tmp file {:replace-existing true})))

(defn agent-id []
  (or (not-empty (System/getenv "SWARMFORGE_ROLE"))
      (exit! 1 "Set SWARMFORGE_ROLE.")))

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(defn task-id [root agent]
  (or (read-value (fs/path root ".squad" "agents" agent "metadata") "task_id")
      (exit! 1 (str "Cannot find task id for " agent))))

(defn append-event! [file line]
  (fs/create-dirs (fs/parent file))
  (spit (str file) (str line "\n") :append true))

(defn record! [state detail]
  (let [root (fs/absolutize (project-root))
        agent (agent-id)
        task (task-id root agent)
        now (timestamp)
        agent-dir (fs/path root ".squad" "agents" agent)
        event-file (fs/path root ".squad" "tasks" task "events.log")
        detail (str/replace detail #"\R+" " ")]
    (write-atomic! (fs/path agent-dir "status")
                   (str "state: " state "\n"
                        "detail: " detail "\n"
                        "updated_at: " now "\n"))
    (write-atomic! (fs/path agent-dir "heartbeat")
                   (str "agent: " agent "\n"
                        "task_id: " task "\n"
                        "state: " state "\n"
                        "detail: " detail "\n"
                        "updated_at: " now "\n"))
    (append-event! event-file
                   (str now "\t" agent "\t" state "\t" detail))
    (println "SQUAD_EVENT:" state)
    (println "AGENT:" agent)
    (println "TASK_ID:" task)))

(defn -main [& args]
  (when (< (count args) 2)
    (exit! 1 usage-text))
  (record! (first args) (str/join " " (rest args))))

(apply -main *command-line-args*)
