#!/usr/bin/env bb

(ns stop-squadd
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def default-timeout-ms 5000)
(def poll-ms 100)

(defn usage []
  (binding [*out* *err*]
    (println "Usage: stop_squadd.bb <project-root>"))
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
                              (str/includes? command "squadd.bb")
                              (str/includes? command root))
                     pid))))
         distinct
         vec)))

(defn terminate-pid! [pid timeout-ms]
  (when (and (re-matches #"[0-9]+" pid)
             (process-alive? pid))
    (process/sh {:continue true} "kill" "-TERM" pid)
    (loop [waited 0]
      (when (and (< waited timeout-ms) (process-alive? pid))
        (Thread/sleep poll-ms)
        (recur (+ waited poll-ms))))
    (when (process-alive? pid)
      (process/sh {:continue true} "kill" "-KILL" pid)
      (Thread/sleep poll-ms))))

(defn stop! [project-root & {:keys [timeout-ms] :or {timeout-ms default-timeout-ms}}]
  (let [daemon-dir (fs/path project-root ".swarmforge" "daemon")
        pid-file (fs/path daemon-dir "squadd.pid")
        stop-file (fs/path daemon-dir "squadd.stop")]
    (fs/create-dirs daemon-dir)
    (when-not (fs/exists? stop-file)
      (spit (str stop-file) ""))
    (when (fs/exists? pid-file)
      (let [pid (str/trim (slurp (str pid-file)))]
        (terminate-pid! pid timeout-ms))
      (fs/delete-if-exists pid-file))
    (doseq [pid (matching-orphan-pids project-root)]
      (terminate-pid! pid timeout-ms))
    (fs/delete-if-exists stop-file)))

(defn -main [& args]
  (stop! (or (first args) (usage)))
  (System/exit 0))

(apply -main *command-line-args*)
