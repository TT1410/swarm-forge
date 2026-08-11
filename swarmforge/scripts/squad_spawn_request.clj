#!/usr/bin/env bb

(ns squad-spawn-request
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-config :as cfg]
            [clojure.string :as str]))

(def usage-text
  "Usage: squad_spawn_request.sh <template> <task-id> <assignment-file>")

(def valid-template #"[a-z][a-z0-9-]*")
(def valid-id #"[A-Za-z0-9][A-Za-z0-9._-]*")

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

(defn validate-template! [template]
  (when-not (re-matches valid-template template)
    (exit! 2 "Template names must use lowercase letters, digits, and hyphens.")))

(defn validate-task-id! [task-id]
  (when-not (re-matches valid-id task-id)
    (exit! 2 "Task ids must use letters, digits, dots, underscores, and hyphens."))
  (when (or (str/includes? task-id "/") (str/includes? task-id "\\"))
    (exit! 2 "Task ids may not contain path separators.")))

(defn validate! [template task-id]
  (validate-template! template)
  (validate-task-id! task-id))

(defn source-file! [path]
  (let [file (fs/path path)
        file (if (fs/absolute? file) file (fs/path (fs/cwd) file))]
    (when-not (fs/regular-file? file)
      (exit! 1 (str "Assignment file not found: " file)))
    file))

(defn write-atomic! [file content]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                  :prefix (str "." (fs/file-name file) ".")})]
    (spit (str tmp) content)
    (fs/move tmp file {:replace-existing true})))

(defn request-id [template task-id]
  (str (str/replace (timestamp) #"[^\dTZ]" "")
       "_"
       template
       "_"
       task-id
       "_"
       (.toString (java.util.UUID/randomUUID))))

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(def active-agent-states
  #{"starting" "running" "failed" "blocked" "handoff_ready" "handoff_sent"})

(defn live-agent? [root agent-id]
  (boolean
   (when-not (str/blank? agent-id)
     (contains? active-agent-states
                (or (read-value (fs/path root ".squad" "agents" agent-id "status") "state")
                    "")))))
(defn spawn-request-task-ids [root]
  (->> ["new" "in_process"]
       (mapcat (fn [state]
                 (let [dir (fs/path root ".squad" "spawn-requests" state)]
                   (when (fs/directory? dir)
                     (->> (fs/list-dir dir)
                          (filter #(and (fs/regular-file? %)
                                        (str/ends-with? (fs/file-name %) ".request")))
                          (keep #(read-value % "task_id")))))))
       (remove str/blank?)
       set))

(defn active-agent-for-task [root task-id]
  (let [agents-dir (fs/path root ".squad" "agents")]
    (when (fs/directory? agents-dir)
      (some (fn [dir]
              (let [agent-id (fs/file-name dir)
                    meta-task (read-value (fs/path dir "metadata") "task_id")]
                (when (and (= task-id meta-task)
                           (live-agent? root agent-id))
                  agent-id)))
            (fs/list-dir agents-dir)))))

(defn task-already-covered? [root task-id]
  (or (contains? (spawn-request-task-ids root) task-id)
      (some? (active-agent-for-task root task-id))
      (let [status (fs/path root ".squad" "assignments" task-id "status")
            agent-id (read-value status "agent_id")]
        (live-agent? root agent-id))))

(defn create-request! [template task-id assignment-file]
  (validate! template task-id)
  (let [root (fs/absolutize (project-root))
        assignment (source-file! assignment-file)]
    (when (task-already-covered? root task-id)
      (exit! 3
             "SQUAD_SPAWN_REQUEST_OCCUPIED"
             (str "TASK_ID: " task-id)
             "DETAIL: active agent or pending spawn already covers this task_id"))
    (let [request-dir (fs/path root ".squad" "spawn-requests" "new")
          request (fs/path request-dir (str (request-id template task-id) ".request"))
          now (timestamp)]
      (write-atomic! request
                     (str "template: " template "\n"
                          "task_id: " task-id "\n"
                          "assignment: " assignment "\n"
                          "requested_at: " now "\n"))
      (println "SQUAD_SPAWN_REQUEST:" (fs/file-name request))
      (println "TEMPLATE:" template)
      (println "TASK_ID:" task-id)
      (println "ASSIGNMENT:" (str assignment))
      (println "STATE: requested"))))
(defn -main [& args]
  (if (= 3 (count args))
    (apply create-request! args)
    (exit! 1 usage-text)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
