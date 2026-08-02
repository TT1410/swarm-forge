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

(defn now []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

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

(defn read-liveness-tail [file]
  (when (fs/exists? file)
    (let [lines (str/split-lines (slurp (str file)))
          tail-lines (rest (drop-while #(not= "last_10_lines:" %) lines))]
      (when (seq tail-lines)
        (str/join "\n" tail-lines)))))

(defn tmux-session-exists? [socket session]
  (and (not (str/blank? socket))
       (not (str/blank? session))
       (or (zero? (:exit (sh-continue "tmux" "-S" socket "has-session" "-t" session)))
           (let [result (sh-continue "tmux" "-S" socket "list-sessions" "-F" "#S")]
             (and (zero? (:exit result))
                  (contains? (set (str/split-lines (:out result))) session))))))

(defn pane-dead? [socket session]
  (let [result (sh-continue "tmux" "-S" socket "list-panes" "-t" session "-F" "#{pane_dead}")]
    (and (zero? (:exit result))
         (some #{"1"} (str/split-lines (:out result))))))

(defn capture-pane-tail [socket session]
  (let [result (sh-continue "tmux" "-S" socket "capture-pane" "-p" "-t" session "-S" "-20")]
    (when (zero? (:exit result))
      (:out result))))

(defn agent-dirs [root maybe-agent]
  (let [agents-dir (fs/path root ".squad" "agents")]
    (cond
      maybe-agent [(fs/path agents-dir maybe-agent)]
      (fs/exists? agents-dir) (->> (fs/list-dir agents-dir)
                                   (filter fs/directory?)
                                   (sort-by fs/file-name)
                                   vec)
      :else [])))

(defn print-pane-tail! [root session]
  (let [socket-file (fs/path root ".swarmforge" "tmux-socket")
        socket (when (fs/exists? socket-file) (str/trim (slurp (str socket-file))))]
    (cond
      (str/blank? socket)
      (println "PANE_LIVE: unknown")

      (str/blank? session)
      (println "PANE_LIVE: false")

      (not (tmux-session-exists? socket session))
      (println "PANE_LIVE: false")

      (pane-dead? socket session)
      (do
        (println "PANE_LIVE: false")
        (println "PANE_DEAD: true"))

      :else
      (do
        (println "PANE_LIVE: true")
        (println "PANE_CAPTURED_AT:" (now))
        (println "LAST_20_LINES:")
        (println (or (capture-pane-tail socket session) ""))))))

(defn print-agent [root dir]
  (let [agent (fs/file-name dir)
        metadata (fs/path dir "metadata")
        status (fs/path dir "status")
        heartbeat (fs/path dir "heartbeat")
        liveness (fs/path dir "liveness")
        session (or (read-value metadata "session") "unknown")]
    (when-not (fs/exists? dir)
      (exit! 1 (str "Unknown squad agent: " agent)))
    (println "AGENT:" agent)
    (println "TASK_ID:" (or (read-value metadata "task_id") "unknown"))
    (println "TEMPLATE:" (or (read-value metadata "template") "unknown"))
    (println "SESSION:" session)
    (println "STATE:" (or (read-value status "state") "unknown"))
    (println "DETAIL:" (or (read-value status "detail") ""))
    (println "UPDATED_AT:" (or (read-value status "updated_at") "unknown"))
    (println "HEARTBEAT_AT:" (or (read-value heartbeat "updated_at") "none"))
    (print-pane-tail! root session)
    (when (fs/exists? liveness)
      (println "LIVENESS_STATE:" (or (read-value liveness "state") "unknown"))
      (println "LIVENESS_AT:" (or (read-value liveness "observed_at") "unknown"))
      (println "PANE_CHANGED:" (or (read-value liveness "pane_changed") "unknown"))
      (println "LAST_10_LINES:")
      (println (or (read-liveness-tail liveness) "")))
    (println)))

(defn -main [& args]
  (when (> (count args) 1)
    (exit! 1 usage-text))
  (let [root (fs/absolutize (project-root))
        dirs (agent-dirs root (first args))]
    (if (empty? dirs)
      (println "NO_SQUAD_AGENTS")
      (doseq [dir dirs]
        (print-agent root dir)))))

(apply -main *command-line-args*)
