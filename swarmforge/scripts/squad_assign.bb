#!/usr/bin/env bb

(ns squad-assign
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def usage-text
  (str "Usage:\n"
       "  squad_assign.sh create <theme-id> <story-id> <template> <assignment-id> <instructions-file>\n"
       "  squad_assign.sh status <assignment-id>"))

(def valid-id #"[A-Za-z0-9][A-Za-z0-9._-]*")

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

(defn validate-id! [kind value]
  (when-not (re-matches valid-id value)
    (exit! 2 (str kind " must use letters, digits, dots, underscores, and hyphens.")))
  (when (or (str/includes? value "/") (str/includes? value "\\"))
    (exit! 2 (str kind " may not contain path separators."))))

(defn validate-template! [template]
  (when-not (re-matches #"[a-z][a-z0-9-]*" template)
    (exit! 2 "Template names must use lowercase letters, digits, and hyphens."))
  (when (str/includes? template "_")
    (exit! 2 "Template names may not contain underscores.")))

(defn source-file! [path]
  (let [file (fs/path path)
        file (if (fs/absolute? file) file (fs/path (fs/cwd) file))]
    (when-not (fs/regular-file? file)
      (exit! 1 (str "Source file not found: " file)))
    file))

(defn write-atomic! [file content]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                  :prefix (str "." (fs/file-name file) ".")})]
    (spit (str tmp) content)
    (fs/move tmp file {:replace-existing true})))

(defn append-line! [file line]
  (fs/create-dirs (fs/parent file))
  (spit (str file) (str line "\n") :append true))

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(defn theme-dir [root theme-id]
  (fs/path root ".squad" "themes" theme-id))

(defn assignment-dir [root assignment-id]
  (fs/path root ".squad" "assignments" assignment-id))

(defn ensure-file! [message file]
  (when-not (fs/regular-file? file)
    (exit! 1 (str message ": " file))))

(defn result-handoff-template [assignment-id]
  (str "type: git_handoff\n"
       "to: squad-leader\n"
       "priority: 50\n"
       "task: " assignment-id "\n"
       "commit: <10-char-commit>\n"))

(defn render-assignment [{:keys [theme-id story-id template assignment-id theme-text story-text instructions-text]}]
  (str "# Squad Assignment\n\n"
       "assignment_id: " assignment-id "\n"
       "theme_id: " theme-id "\n"
       "story_id: " story-id "\n"
       "template: " template "\n\n"
       "## Theme\n\n"
       theme-text "\n\n"
       "## Story\n\n"
       story-text "\n\n"
       "## Leader Instructions\n\n"
       instructions-text "\n\n"
       "## Required Transient Protocol\n\n"
       "- Stay inside this assignment boundary.\n"
       "- Use `squad_event.sh` for meaningful progress updates.\n"
       "- Commit completed work on your transient branch.\n"
       "- Send the result to `squad-leader` with `swarm_handoff.sh` using this draft shape:\n\n"
       "```text\n"
       (result-handoff-template assignment-id)
       "```\n"))

(defn create-assignment! [theme-id story-id template assignment-id instructions-file]
  (doseq [[kind value] [["Theme id" theme-id]
                        ["Story id" story-id]
                        ["Assignment id" assignment-id]]]
    (validate-id! kind value))
  (validate-template! template)
  (let [root (fs/absolutize (project-root))
        theme (theme-dir root theme-id)
        theme-file (fs/path theme "theme.md")
        story-file (fs/path theme "stories" (str story-id ".md"))
        template-file (fs/path root "swarmforge" "role-templates" (str template ".prompt"))
        instructions (source-file! instructions-file)
        dir (assignment-dir root assignment-id)
        now (timestamp)]
    (ensure-file! "Theme file not found" theme-file)
    (ensure-file! "Story file not found" story-file)
    (ensure-file! "Role template not found" template-file)
    (when (fs/exists? dir)
      (exit! 2 (str "Assignment already exists: " assignment-id)))
    (fs/create-dirs dir)
    (let [assignment-text (render-assignment {:theme-id theme-id
                                              :story-id story-id
                                              :template template
                                              :assignment-id assignment-id
                                              :theme-text (slurp (str theme-file))
                                              :story-text (slurp (str story-file))
                                              :instructions-text (slurp (str instructions))})
          assignment-file (fs/path dir "assignment.md")]
      (write-atomic! assignment-file assignment-text)
      (write-atomic! (fs/path dir "result-handoff.draft")
                     (result-handoff-template assignment-id))
      (write-atomic! (fs/path dir "metadata")
                     (str "assignment_id: " assignment-id "\n"
                          "theme_id: " theme-id "\n"
                          "story_id: " story-id "\n"
                          "template: " template "\n"
                          "assignment_file: " assignment-file "\n"
                          "created_at: " now "\n"))
      (write-atomic! (fs/path dir "status")
                     (str "assignment_id: " assignment-id "\n"
                          "state: assignment_created\n"
                          "detail: " template " for " story-id "\n"
                          "updated_at: " now "\n"))
      (fs/create-dirs (fs/path theme "assignments"))
      (write-atomic! (fs/path theme "assignments" (str assignment-id ".md"))
                     assignment-text)
      (append-line! (fs/path theme "events.log")
                    (str now "\tassignment_created\t" assignment-id "\t" template "\t" story-id))
      (println "SQUAD_ASSIGNMENT:" assignment-id)
      (println "THEME:" theme-id)
      (println "STORY:" story-id)
      (println "TEMPLATE:" template)
      (println "ASSIGNMENT:" (str assignment-file)))))

(defn print-status! [assignment-id]
  (validate-id! "Assignment id" assignment-id)
  (let [root (fs/absolutize (project-root))
        dir (assignment-dir root assignment-id)
        metadata (fs/path dir "metadata")
        status (fs/path dir "status")]
    (when-not (fs/directory? dir)
      (exit! 1 (str "Unknown assignment: " assignment-id)))
    (println "ASSIGNMENT:" assignment-id)
    (println "THEME:" (or (read-value metadata "theme_id") "unknown"))
    (println "STORY:" (or (read-value metadata "story_id") "unknown"))
    (println "TEMPLATE:" (or (read-value metadata "template") "unknown"))
    (println "STATE:" (or (read-value status "state") "unknown"))
    (println "DETAIL:" (or (read-value status "detail") ""))
    (println "ASSIGNMENT_FILE:" (or (read-value metadata "assignment_file") "unknown"))))

(defn -main [& args]
  (case (first args)
    "create" (if (= 6 (count args))
               (create-assignment! (second args) (nth args 2) (nth args 3) (nth args 4) (nth args 5))
               (exit! 1 usage-text))
    "status" (if (= 2 (count args))
               (print-status! (second args))
               (exit! 1 usage-text))
    (exit! 1 usage-text)))

(apply -main *command-line-args*)
