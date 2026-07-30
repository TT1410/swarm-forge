#!/usr/bin/env bb

(ns squad-status
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def usage-text
  "Usage: squad_status.sh [agent-id]")

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

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(defn agent-dirs [root maybe-agent]
  (let [agents-dir (fs/path root ".squad" "agents")]
    (cond
      maybe-agent [(fs/path agents-dir maybe-agent)]
      (fs/exists? agents-dir) (->> (fs/list-dir agents-dir)
                                   (filter fs/directory?)
                                   (sort-by fs/file-name)
                                   vec)
      :else [])))

(defn print-agent [dir]
  (let [agent (fs/file-name dir)
        metadata (fs/path dir "metadata")
        status (fs/path dir "status")
        heartbeat (fs/path dir "heartbeat")]
    (when-not (fs/exists? dir)
      (exit! 1 (str "Unknown squad agent: " agent)))
    (println "AGENT:" agent)
    (println "TASK_ID:" (or (read-value metadata "task_id") "unknown"))
    (println "TEMPLATE:" (or (read-value metadata "template") "unknown"))
    (println "SESSION:" (or (read-value metadata "session") "unknown"))
    (println "STATE:" (or (read-value status "state") "unknown"))
    (println "DETAIL:" (or (read-value status "detail") ""))
    (println "UPDATED_AT:" (or (read-value status "updated_at") "unknown"))
    (println "HEARTBEAT_AT:" (or (read-value heartbeat "updated_at") "none"))
    (println)))

(defn -main [& args]
  (when (> (count args) 1)
    (exit! 1 usage-text))
  (let [root (fs/absolutize (project-root))
        dirs (agent-dirs root (first args))]
    (if (empty? dirs)
      (println "NO_SQUAD_AGENTS")
      (doseq [dir dirs]
        (print-agent dir)))))

(apply -main *command-line-args*)
