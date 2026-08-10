#!/usr/bin/env bb

(ns squad-event
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-config :as cfg]
            [clojure.string :as str]))

(def agent-reportable-states
  "States agents may set with squad_event.sh. Retirement is leader-owned via
  squad_retire.sh after workflow resolution — agents stop at handoff_sent."
  #{"starting"
    "running"
    "blocked"
    "failed"
    "handoff_ready"
    "handoff_sent"})

(def usage-text
  (str "Usage: squad_event.sh <state> <detail...>\n"
       "Allowed states: starting, running, blocked, failed, handoff_ready, handoff_sent\n"
       "Do not use retired here; squad_retire.sh performs final retirement after the "
       "Squad Leader resolves the handoff.\n"
       "Put command names, phases, and other progress detail in <detail>, not in <state>."))

(defn exit! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn sh-continue [& args]
  (apply process/sh (concat [{:continue true}] args)))

(defn project-root []
  (or (cfg/project-root)
      (exit! 1 "Cannot find SwarmForge project root")))

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

(def state-validation-rules
  [{:invalid? (fn [agent _ state] (= state agent))
    :message (fn [_ _ _] "SQUAD_EVENT_USAGE_ERROR: first argument is the state, not the agent id.")}
   {:invalid? (fn [_ task state] (= state task))
    :message (fn [_ _ _] "SQUAD_EVENT_USAGE_ERROR: first argument is the state, not the task id.")}
   {:invalid? (fn [_ _ state] (re-matches #"[a-z][a-z0-9-]*-[0-9][0-9][0-9]" state))
    :message (fn [_ _ _] "SQUAD_EVENT_USAGE_ERROR: state looks like an agent id.")}
   {:invalid? (fn [_ _ state] (= "retired" state))
    :message (fn [_ _ _]
               (str "SQUAD_EVENT_USAGE_ERROR: agents must not self-retire. "
                    "Report handoff_sent after sending the handoff; "
                    "squad_retire.sh runs only after the Squad Leader resolves workflow state."))}
   {:invalid? (fn [_ _ state] (not (contains? agent-reportable-states state)))
    :message (fn [_ _ state] (str "SQUAD_EVENT_USAGE_ERROR: unsupported lifecycle state: " state))}])

(defn state-validation-error [agent task state]
  (some (fn [{:keys [invalid? message]}]
          (when (invalid? agent task state)
            (message agent task state)))
        state-validation-rules))

(defn validate-state! [agent task state]
  (when-let [message (state-validation-error agent task state)]
    (exit! 2 message usage-text)))

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
    (validate-state! agent task state)
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

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
