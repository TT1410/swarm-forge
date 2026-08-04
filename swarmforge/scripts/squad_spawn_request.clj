#!/usr/bin/env bb

(ns squad-spawn-request
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
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

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn validate! [template task-id]
  (when-not (re-matches valid-template template)
    (exit! 2 "Template names must use lowercase letters, digits, and hyphens."))
  (when-not (re-matches valid-id task-id)
    (exit! 2 "Task ids must use letters, digits, dots, underscores, and hyphens."))
  (when (or (str/includes? task-id "/") (str/includes? task-id "\\"))
    (exit! 2 "Task ids may not contain path separators.")))

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

(defn create-request! [template task-id assignment-file]
  (validate! template task-id)
  (let [root (fs/absolutize (project-root))
        assignment (source-file! assignment-file)
        request-dir (fs/path root ".squad" "spawn-requests" "new")
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
    (println "STATE: requested")))

(defn -main [& args]
  (if (= 3 (count args))
    (apply create-request! args)
    (exit! 1 usage-text)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
