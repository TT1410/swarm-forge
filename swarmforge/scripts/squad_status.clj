#!/usr/bin/env bb

(ns squad-status
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-config :as cfg]
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
  (or (cfg/project-root)
      (exit! 1 "Cannot find SwarmForge project root")))

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

(defn pane-status [socket session]
  (cond
    (str/blank? socket) :unknown
    (str/blank? session) :not-live
    (not (tmux-session-exists? socket session)) :not-live
    (pane-dead? socket session) :dead
    :else :live))

(defn print-live-pane-tail! [socket session]
  (println "PANE_LIVE: true")
  (println "PANE_CAPTURED_AT:" (now))
  (println "LAST_20_LINES:")
  (println (or (capture-pane-tail socket session) "")))

(def pane-status-printers
  {:unknown (fn [_ _] (println "PANE_LIVE: unknown"))
   :not-live (fn [_ _] (println "PANE_LIVE: false"))
   :dead (fn [_ _]
           (println "PANE_LIVE: false")
           (println "PANE_DEAD: true"))
   :live print-live-pane-tail!})

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
        socket (when (fs/exists? socket-file) (str/trim (slurp (str socket-file))))
        status (pane-status socket session)]
    ((pane-status-printers status) socket session)))

(defn field-value [file field default]
  (or (read-value file field) default))

(defn agent-metadata-fields [agent metadata status heartbeat session]
  [["AGENT" agent]
   ["TASK_ID" (field-value metadata "task_id" "unknown")]
   ["TEMPLATE" (field-value metadata "template" "unknown")]
   ["SESSION" session]
   ["STATE" (field-value status "state" "unknown")]
   ["DETAIL" (field-value status "detail" "")]
   ["UPDATED_AT" (field-value status "updated_at" "unknown")]
   ["HEARTBEAT_AT" (field-value heartbeat "updated_at" "none")]])

(defn print-agent-metadata! [agent metadata status heartbeat session]
  (doseq [[label value] (agent-metadata-fields agent metadata status heartbeat session)]
    (println (str label ":") value)))

(defn liveness-fields [liveness]
  [["LIVENESS_STATE" (or (read-value liveness "state") "unknown")]
   ["LIVENESS_AT" (or (read-value liveness "observed_at") "unknown")]
   ["PANE_CHANGED" (or (read-value liveness "pane_changed") "unknown")]])

(defn print-liveness-tail! [liveness]
  (println "LAST_10_LINES:")
  (println (or (read-liveness-tail liveness) "")))

(defn print-liveness! [liveness]
  (when (fs/exists? liveness)
    (doseq [[label value] (liveness-fields liveness)]
      (println (str label ":") value))
    (print-liveness-tail! liveness)))

(defn print-agent [root dir]
  (let [agent (fs/file-name dir)
        metadata (fs/path dir "metadata")
        status (fs/path dir "status")
        heartbeat (fs/path dir "heartbeat")
        liveness (fs/path dir "liveness")
        session (or (read-value metadata "session") "unknown")]
    (when-not (fs/exists? dir)
      (exit! 1 (str "Unknown squad agent: " agent)))
    (print-agent-metadata! agent metadata status heartbeat session)
    (print-pane-tail! root session)
    (print-liveness! liveness)
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

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
