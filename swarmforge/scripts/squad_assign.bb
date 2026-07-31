#!/usr/bin/env bb

(ns squad-assign
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def usage-text
  (str "Usage:\n"
       "  squad_assign.sh create <theme-id> <story-id> <template> <assignment-id> <instructions-file> [--requires approval:<gate>]\n"
       "  squad_assign.sh result <assignment-id> <handoff-file>\n"
       "  squad_assign.sh merge-ready <assignment-id>\n"
       "  squad_assign.sh status <assignment-id>"))

(def valid-id #"[A-Za-z0-9][A-Za-z0-9._-]*")

(defn exit! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn sh-continue [& args]
  (apply process/sh (concat [{:continue true}] args)))

(defn sh-at [dir & args]
  (apply process/sh (concat [{:dir (str dir) :continue true}] args)))

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

(defn approval-gates [theme]
  (let [file (fs/path theme "approvals.tsv")]
    (if (fs/exists? file)
      (->> (str/split-lines (slurp (str file)))
           (keep (fn [line]
                   (let [[_ gate] (str/split line #"\t" 3)]
                     (when-not (str/blank? gate)
                       gate))))
           set)
      #{})))

(defn parse-requirement! [requirement]
  (when requirement
    (let [[kind value] (str/split requirement #":" 2)]
      (when-not (= "approval" kind)
        (exit! 2 "Requirement must use approval:<gate>."))
      (when (str/blank? value)
        (exit! 2 "Requirement approval gate may not be blank."))
      (validate-id! "Approval gate" value)
      {:kind kind
       :value value
       :text requirement})))

(defn parse-create-args! [args]
  (when-not (#{6 8} (count args))
    (exit! 1 usage-text))
  (let [[_ theme-id story-id template assignment-id instructions-file flag requirement] args]
    (when (and flag (not= "--requires" flag))
      (exit! 1 usage-text))
    {:theme-id theme-id
     :story-id story-id
     :template template
     :assignment-id assignment-id
     :instructions-file instructions-file
     :requirement (parse-requirement! requirement)}))

(defn theme-dir [root theme-id]
  (fs/path root ".squad" "themes" theme-id))

(defn assignment-dir [root assignment-id]
  (fs/path root ".squad" "assignments" assignment-id))

(defn ensure-assignment-dir! [dir assignment-id]
  (when-not (fs/directory? dir)
    (exit! 1 (str "Unknown assignment: " assignment-id))))

(defn ensure-file! [message file]
  (when-not (fs/regular-file? file)
    (exit! 1 (str message ": " file))))

(defn handoff-body [file]
  (let [[_ body] (str/split (slurp (str file)) #"\n\n" 2)]
    (or body "")))

(defn result-handoff-template [assignment-id]
  (str "type: git_handoff\n"
       "to: squad-leader\n"
       "priority: 50\n"
       "task: " assignment-id "\n"
       "commit: <10-char-commit>\n"))

(defn render-assignment [{:keys [theme-id story-id template assignment-id theme-text story-text instructions-text requirement]}]
  (str "# Squad Assignment\n\n"
       "assignment_id: " assignment-id "\n"
       "theme_id: " theme-id "\n"
       "story_id: " story-id "\n"
       "template: " template "\n"
       (when requirement
         (str "requires: " (:text requirement) "\n"))
       "\n"
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

(defn create-assignment! [{:keys [theme-id story-id template assignment-id instructions-file requirement]}]
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
    (when (and requirement
               (= "approval" (:kind requirement))
               (not (contains? (approval-gates theme) (:value requirement))))
      (exit! 3
             (str "SQUAD_ASSIGNMENT_BLOCKED: " assignment-id)
             (str "REASON: missing required approval gate " (:value requirement))))
    (when (fs/exists? dir)
      (exit! 2 (str "Assignment already exists: " assignment-id)))
    (fs/create-dirs dir)
    (let [assignment-text (render-assignment {:theme-id theme-id
                                              :story-id story-id
                                              :template template
                                              :assignment-id assignment-id
                                              :theme-text (slurp (str theme-file))
                                              :story-text (slurp (str story-file))
                                              :instructions-text (slurp (str instructions))
                                              :requirement requirement})
          assignment-file (fs/path dir "assignment.md")]
      (write-atomic! assignment-file assignment-text)
      (write-atomic! (fs/path dir "result-handoff.draft")
                     (result-handoff-template assignment-id))
      (write-atomic! (fs/path dir "metadata")
                     (str "assignment_id: " assignment-id "\n"
                          "theme_id: " theme-id "\n"
                          "story_id: " story-id "\n"
                          "template: " template "\n"
                          (when requirement
                            (str "requires: " (:text requirement) "\n"))
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
      (when requirement
        (println "REQUIRES:" (:text requirement)))
      (println "ASSIGNMENT:" (str assignment-file)))))

(defn print-status! [assignment-id]
  (validate-id! "Assignment id" assignment-id)
  (let [root (fs/absolutize (project-root))
        dir (assignment-dir root assignment-id)
        metadata (fs/path dir "metadata")
        status (fs/path dir "status")
        result-file (fs/path dir "result.handoff")
        merge-file (fs/path dir "merge")]
    (ensure-assignment-dir! dir assignment-id)
    (println "ASSIGNMENT:" assignment-id)
    (println "THEME:" (or (read-value metadata "theme_id") "unknown"))
    (println "STORY:" (or (read-value metadata "story_id") "unknown"))
    (println "TEMPLATE:" (or (read-value metadata "template") "unknown"))
    (println "STATE:" (or (read-value status "state") "unknown"))
    (println "DETAIL:" (or (read-value status "detail") ""))
    (println "ASSIGNMENT_FILE:" (or (read-value metadata "assignment_file") "unknown"))
    (println "RESULT:" (if (fs/exists? result-file) (str result-file) "none"))
    (println "MERGE:" (if (fs/exists? merge-file) (str merge-file) "none"))))

(defn validate-result-handoff! [assignment-id handoff-file]
  (let [type (read-value handoff-file "type")
        to (read-value handoff-file "to")
        task (read-value handoff-file "task")
        commit (read-value handoff-file "commit")
        from (read-value handoff-file "from")]
    (when-not (= "git_handoff" type)
      (exit! 2 "Result handoff must have type: git_handoff."))
    (when-not (= "squad-leader" to)
      (exit! 2 "Result handoff must have to: squad-leader."))
    (when-not (= assignment-id task)
      (exit! 2 (str "Result handoff task must match assignment id: " assignment-id)))
    (when-not (and commit (re-matches #"[0-9a-fA-F]{10}" commit))
      (exit! 2 "Result handoff must have a 10-character commit header."))
    {:from (or from "unknown")
     :commit commit
     :body (handoff-body handoff-file)}))

(defn record-result! [assignment-id handoff-path]
  (validate-id! "Assignment id" assignment-id)
  (let [root (fs/absolutize (project-root))
        dir (assignment-dir root assignment-id)
        metadata (fs/path dir "metadata")
        status (fs/path dir "status")
        handoff-file (source-file! handoff-path)
        {:keys [from commit body]} (validate-result-handoff! assignment-id handoff-file)
        theme-id (or (read-value metadata "theme_id") "unknown")
        story-id (or (read-value metadata "story_id") "unknown")
        now (timestamp)]
    (ensure-assignment-dir! dir assignment-id)
    (write-atomic! (fs/path dir "result.handoff")
                   (slurp (str handoff-file)))
    (write-atomic! (fs/path dir "result")
                   (str "assignment_id: " assignment-id "\n"
                        "from: " from "\n"
                        "commit: " commit "\n"
                        "received_at: " now "\n"))
    (write-atomic! status
                   (str "assignment_id: " assignment-id "\n"
                        "state: result_received\n"
                        "detail: " from " " commit "\n"
                        "updated_at: " now "\n"))
    (append-line! (fs/path dir "events.log")
                  (str now "\tresult_received\t" from "\t" commit))
    (when-not (= "unknown" theme-id)
      (append-line! (fs/path root ".squad" "themes" theme-id "events.log")
                    (str now "\tassignment_result_received\t" assignment-id "\t" from "\t" commit "\t" story-id)))
    (println "SQUAD_ASSIGNMENT:" assignment-id)
    (println "STATE: result_received")
    (println "FROM:" from)
    (println "COMMIT:" commit)
    (when-not (str/blank? body)
      (println "BODY_RECORDED: true"))))

(defn merge-head-exists? [root]
  (fs/exists? (fs/path root ".git" "MERGE_HEAD")))

(defn abort-merge! [root]
  (when (merge-head-exists? root)
    (sh-at root "git" "merge" "--abort")))

(defn write-merge-state! [root dir assignment-id state detail commit now]
  (write-atomic! (fs/path dir "merge")
                 (str "assignment_id: " assignment-id "\n"
                      "state: " state "\n"
                      "commit: " commit "\n"
                      "detail: " detail "\n"
                      "updated_at: " now "\n"))
  (write-atomic! (fs/path dir "status")
                 (str "assignment_id: " assignment-id "\n"
                      "state: " state "\n"
                      "detail: " detail "\n"
                      "updated_at: " now "\n"))
  (append-line! (fs/path dir "events.log")
                (str now "\t" state "\t" commit "\t" detail))
  (let [metadata (fs/path dir "metadata")
        theme-id (read-value metadata "theme_id")
        story-id (read-value metadata "story_id")]
    (when theme-id
      (append-line! (fs/path root ".squad" "themes" theme-id "events.log")
                    (str now "\tassignment_" state "\t" assignment-id "\t" commit "\t" (or story-id "unknown"))))))

(defn mark-merge-ready! [assignment-id]
  (validate-id! "Assignment id" assignment-id)
  (let [root (fs/absolutize (project-root))
        dir (assignment-dir root assignment-id)
        result-file (fs/path dir "result")
        now (timestamp)]
    (ensure-assignment-dir! dir assignment-id)
    (ensure-file! "Assignment result not found" result-file)
    (let [commit (read-value result-file "commit")]
      (when-not (and commit (re-matches #"[0-9a-fA-F]{10}" commit))
        (exit! 2 "Assignment result must contain a 10-character commit."))
      (try
        (let [known (sh-at root "git" "rev-parse" "--verify" (str commit "^{commit}"))]
          (when-not (zero? (:exit known))
            (exit! 2 (str "Unknown result commit: " commit))))
        (let [ancestor (sh-at root "git" "merge-base" "--is-ancestor" commit "HEAD")]
          (if (zero? (:exit ancestor))
            (do
              (write-merge-state! root dir assignment-id "merge_ready" "commit already reachable from HEAD" commit now)
              (println "SQUAD_ASSIGNMENT:" assignment-id)
              (println "STATE: merge_ready")
              (println "COMMIT:" commit)
              (println "DETAIL: commit already reachable from HEAD"))
            (let [merge (sh-at root "git" "merge" "--no-commit" "--no-ff" commit)]
              (if (zero? (:exit merge))
                (do
                  (abort-merge! root)
                  (write-merge-state! root dir assignment-id "merge_ready" "dry-run merge passed" commit now)
                  (println "SQUAD_ASSIGNMENT:" assignment-id)
                  (println "STATE: merge_ready")
                  (println "COMMIT:" commit)
                  (println "DETAIL: dry-run merge passed"))
                (do
                  (abort-merge! root)
                  (write-merge-state! root dir assignment-id "merge_blocked" "dry-run merge failed" commit now)
                  (binding [*out* *err*]
                    (println "SQUAD_ASSIGNMENT:" assignment-id)
                    (println "STATE: merge_blocked")
                    (println "COMMIT:" commit)
                    (println "DETAIL: dry-run merge failed"))
                  (System/exit 4))))))
        (finally
          (abort-merge! root))))))

(defn -main [& args]
  (case (first args)
    "create" (create-assignment! (parse-create-args! args))
    "result" (if (= 3 (count args))
               (record-result! (second args) (nth args 2))
               (exit! 1 usage-text))
    "merge-ready" (if (= 2 (count args))
                    (mark-merge-ready! (second args))
                    (exit! 1 usage-text))
    "status" (if (= 2 (count args))
               (print-status! (second args))
               (exit! 1 usage-text))
    (exit! 1 usage-text)))

(apply -main *command-line-args*)
