#!/usr/bin/env bb

(ns stop-handoff-daemon
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def default-timeout-ms 5000)
(def poll-ms 100)

(defn usage []
  (binding [*out* *err*]
    (println "Usage: stop_handoff_daemon.clj <project-root>"))
  (System/exit 1))

(defn process-alive? [pid]
  (zero? (:exit (process/sh {:continue true} "kill" "-0" pid))))

(defn numeric-pid [pid-file]
  (when (fs/exists? pid-file)
    (let [pid (str/trim (slurp (str pid-file)))]
      (when (re-matches #"[0-9]+" pid)
        pid))))

(defn wait-for-exit! [pid timeout-ms]
  (loop [waited 0]
    (when (and (< waited timeout-ms) (process-alive? pid))
      (Thread/sleep poll-ms)
      (recur (+ waited poll-ms)))))

(defn terminate-process! [pid timeout-ms]
  (when (process-alive? pid)
    (process/sh {:continue true} "kill" "-TERM" pid)
    (wait-for-exit! pid timeout-ms)
    (when (process-alive? pid)
      (process/sh {:continue true} "kill" "-KILL" pid)
      (Thread/sleep poll-ms))))

(defn stop! [project-root & {:keys [timeout-ms] :or {timeout-ms default-timeout-ms}}]
  (let [daemon-dir (fs/path project-root ".swarmforge" "daemon")
        pid-file (fs/path daemon-dir "handoffd.pid")
        stop-file (fs/path daemon-dir "stop")]
    (fs/create-dirs daemon-dir)
    (when-not (fs/exists? stop-file)
      (spit (str stop-file) ""))
    (when-let [pid (numeric-pid pid-file)]
      (terminate-process! pid timeout-ms))
    (when (fs/exists? pid-file)
      (fs/delete-if-exists pid-file))
    (fs/delete-if-exists stop-file)))

(defn -main [& args]
  (stop! (or (first args) (usage)))
  (System/exit 0))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
