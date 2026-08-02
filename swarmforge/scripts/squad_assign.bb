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
       "  squad_assign.sh review <assignment-id> <accepted|changes-requested> <review-file>\n"
       "  squad_assign.sh accept-merge <assignment-id>\n"
       "  squad_assign.sh block <assignment-id> <reason-file>\n"
       "  squad_assign.sh reject <assignment-id> <reason-file>\n"
       "  squad_assign.sh replace <old-assignment-id> <new-assignment-id> <template> <instructions-file>\n"
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

(defn referenced-project-file [root ref-file]
  (when (fs/exists? ref-file)
    (when-let [relative (read-value ref-file "path")]
      (fs/path root relative))))

(defn story-packet-file [root story-id]
  (fs/path root ".squad" "stories" story-id "packet"))

(defn ensure-implementer-packet-ready! [root story-id assignment-id]
  (let [packet (story-packet-file root story-id)]
    (when-not (fs/regular-file? packet)
      (exit! 3
             (str "SQUAD_ASSIGNMENT_BLOCKED: " assignment-id)
             (str "REASON: missing story packet for " story-id)))
    (when-not (= "implementation_approved" (read-value packet "state"))
      (exit! 3
             (str "SQUAD_ASSIGNMENT_BLOCKED: " assignment-id)
             (str "REASON: story packet " story-id " is not implementation_approved")))
    packet))

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

(def approval-required-templates
  #{"implementer"})

(defn story-acceptance-gate [story-id]
  (str "acceptance-" story-id))

(defn validate-template-requirement! [template story-id requirement]
  (when (contains? approval-required-templates template)
    (let [required (story-acceptance-gate story-id)]
      (cond
        (nil? requirement)
        (exit! 2
               (str "Template " template " requires story-level approval gate approval:" required))

        (= "acceptance" (:value requirement))
        (exit! 2
               "Theme-wide acceptance approval is not allowed for implementer assignments."
               (str "Use story-level approval gate approval:" required))

        (not= required (:value requirement))
        (exit! 2
               (str "Template " template " for story " story-id
                    " requires approval:" required
                    ", not " (:text requirement)))))))

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

(def valid-review-decisions
  {"accepted" "review_accepted"
   "changes-requested" "review_changes_requested"})

(defn review-state! [decision]
  (or (valid-review-decisions decision)
      (exit! 2 "Review decision must be accepted or changes-requested.")))

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

(defn append-file! [file content]
  (spit (str file) content :append true))

(defn relative-to-root [root file]
  (let [root-path (.normalize (.toAbsolutePath (fs/path root)))
        file-path (.normalize (.toAbsolutePath (fs/path file)))]
    (when (.startsWith file-path root-path)
      (str/replace (str (.relativize root-path file-path)) "\\" "/"))))

(defn durable-review-file? [root file]
  (when-let [relative (relative-to-root root file)]
    (str/starts-with? relative ".squad/reviews/")))

(defn read-header [file field]
  (let [prefix (str field ": ")]
    (some (fn [line]
            (when (str/starts-with? line prefix)
              (subs line (count prefix))))
          (take-while #(not (str/blank? %))
                      (str/split-lines (slurp (str file)))))))

(defn reviewer-report-from-handoff [root file]
  (when (= "git_handoff" (read-header file "type"))
    (when-let [commit (read-header file "commit")]
      (when (re-matches #"[0-9a-fA-F]{10}" commit)
        (let [paths-result (sh-at root "git" "show" "--name-only" "--format=" commit)
              review-paths (->> (str/split-lines (:out paths-result))
                                (filter #(and (str/starts-with? % ".squad/reviews/")
                                              (str/ends-with? % ".md")))
                                distinct
                                vec)]
          (when (= 1 (count review-paths))
            (let [path (first review-paths)
                  content (sh-at root "git" "show" (str commit ":" path))]
              (when (zero? (:exit content))
                {:content (:out content)
                 :source (str commit ":" path)
                 :durable? true}))))))))

(defn review-source! [root path]
  (let [file (source-file! path)]
    (or (reviewer-report-from-handoff root file)
        {:content (slurp (str file))
         :source (str file)
         :durable? (boolean (durable-review-file? root file))})))

(defn assignment-theme-event! [root dir state assignment-id & fields]
  (let [metadata (fs/path dir "metadata")
        theme-id (read-value metadata "theme_id")
        story-id (read-value metadata "story_id")]
    (when theme-id
      (append-line! (fs/path root ".squad" "themes" theme-id "events.log")
                    (str/join "\t" (concat [(timestamp)
                                             (str "assignment_" state)
                                             assignment-id]
                                            fields
                                            [(or story-id "unknown")]))))))

(defn handoff-body [file]
  (let [[_ body] (str/split (slurp (str file)) #"\n\n" 2)]
    (or body "")))

(defn result-handoff-template [assignment-id]
  (str "type: git_handoff\n"
       "to: squad-leader\n"
       "priority: 50\n"
       "task: " assignment-id "\n"
       "commit: <10-char-commit>\n"))

(defn render-assignment [{:keys [theme-id story-id template assignment-id theme-text story-text instructions-text requirement packet-text]}]
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
       (when packet-text
         (str "## Story Packet\n\n"
              "```text\n"
              packet-text
              "```\n\n"))
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
  (validate-template-requirement! template story-id requirement)
  (let [root (fs/absolutize (project-root))
        theme (theme-dir root theme-id)
        theme-file (fs/path theme "theme.md")
        story-ref (fs/path theme "stories" (str story-id ".ref"))
        legacy-story-file (fs/path theme "stories" (str story-id ".md"))
        story-file (or (referenced-project-file root story-ref)
                       legacy-story-file)
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
    (let [packet (when (= "implementer" template)
                   (ensure-implementer-packet-ready! root story-id assignment-id))]
      (when (and packet
                 (not= theme-id (read-value packet "theme_id")))
        (exit! 3
               (str "SQUAD_ASSIGNMENT_BLOCKED: " assignment-id)
               (str "REASON: story packet " story-id " belongs to a different theme"))))
    (when (fs/exists? dir)
      (exit! 2 (str "Assignment already exists: " assignment-id)))
    (fs/create-dirs dir)
    (let [packet (when (= "implementer" template)
                   (story-packet-file root story-id))
          assignment-text (render-assignment {:theme-id theme-id
                                              :story-id story-id
                                              :template template
                                              :assignment-id assignment-id
                                              :theme-text (slurp (str theme-file))
                                              :story-text (slurp (str story-file))
                                              :instructions-text (slurp (str instructions))
                                              :requirement requirement
                                              :packet-text (when packet
                                                             (slurp (str packet)))})
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
        merge-file (fs/path dir "merge")
        accepted-merge-file (fs/path dir "accepted-merge")
        review-file (fs/path dir "review")
        merge-error-file (fs/path dir "merge-error")
        blocker-file (fs/path dir "blocker")
        rejection-file (fs/path dir "rejection")
        replacement-file (fs/path dir "replacement")]
    (ensure-assignment-dir! dir assignment-id)
    (println "ASSIGNMENT:" assignment-id)
    (println "THEME:" (or (read-value metadata "theme_id") "unknown"))
    (println "STORY:" (or (read-value metadata "story_id") "unknown"))
    (println "TEMPLATE:" (or (read-value metadata "template") "unknown"))
    (println "STATE:" (or (read-value status "state") "unknown"))
    (println "DETAIL:" (or (read-value status "detail") ""))
    (println "ASSIGNMENT_FILE:" (or (read-value metadata "assignment_file") "unknown"))
    (println "RESULT:" (if (fs/exists? result-file) (str result-file) "none"))
    (println "MERGE:" (if (fs/exists? merge-file) (str merge-file) "none"))
    (println "ACCEPTED_MERGE:" (if (fs/exists? accepted-merge-file) (str accepted-merge-file) "none"))
    (println "MERGE_ERROR:" (if (fs/exists? merge-error-file) (str merge-error-file) "none"))
    (println "REVIEW:" (if (fs/exists? review-file) (str review-file) "none"))
    (println "BLOCKER:" (if (fs/exists? blocker-file) (str blocker-file) "none"))
    (println "REJECTION:" (if (fs/exists? rejection-file) (str rejection-file) "none"))
    (println "REPLACEMENT:" (if (fs/exists? replacement-file) (str replacement-file) "none"))))

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
    (when (str/blank? from)
      (exit! 2 "Result handoff must have a from header."))
    (when (= "squad-leader" from)
      (exit! 2 "Transient result handoff may not be from: squad-leader."))
    {:from from
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
    (let [sender-branch (str "swarmforge-" from)
          branch-exists (sh-at root "git" "rev-parse" "--verify" (str sender-branch "^{commit}"))]
      (when (zero? (:exit branch-exists))
        (let [reachable (sh-at root "git" "merge-base" "--is-ancestor" commit sender-branch)]
          (when-not (zero? (:exit reachable))
            (exit! 2
                   (str "Result commit " commit " is not reachable from sender branch " sender-branch))))))
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
      (assignment-theme-event! root dir "result_received" assignment-id from commit))
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

(defn tracked-dirty? [root]
  (not (str/blank?
        (str/trim (:out (sh-at root "git" "status" "--porcelain" "--untracked-files=no"))))))

(defn write-merge-error! [dir phase result]
  (write-atomic! (fs/path dir "merge-error")
                 (str "phase: " phase "\n"
                      "exit: " (:exit result) "\n"
                      "\n"
                      "stdout:\n"
                      (:out result)
                      "\n"
                      "stderr:\n"
                      (:err result)
                      "\n")))

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
        (when (tracked-dirty? root)
          (write-merge-state! root dir assignment-id "merge_blocked" "tracked checkout dirty" commit now)
          (binding [*out* *err*]
            (println "SQUAD_ASSIGNMENT:" assignment-id)
            (println "STATE: merge_blocked")
            (println "COMMIT:" commit)
            (println "DETAIL: tracked checkout dirty"))
          (System/exit 4))
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
                  (write-merge-error! dir "merge-ready" merge)
                  (write-merge-state! root dir assignment-id "merge_blocked" "dry-run merge failed" commit now)
                  (binding [*out* *err*]
                    (println "SQUAD_ASSIGNMENT:" assignment-id)
                    (println "STATE: merge_blocked")
                    (println "COMMIT:" commit)
                    (println "DETAIL: dry-run merge failed"))
                  (System/exit 4))))))
        (finally
          (abort-merge! root))))))

(defn record-review! [assignment-id decision review-path]
  (validate-id! "Assignment id" assignment-id)
  (let [state (review-state! decision)
        root (fs/absolutize (project-root))
        dir (assignment-dir root assignment-id)
        review-source (review-source! root review-path)
        result-file (fs/path dir "result")
        metadata (fs/path dir "metadata")
        template (read-value metadata "template")
        now (timestamp)]
    (ensure-assignment-dir! dir assignment-id)
    (ensure-file! "Assignment result not found" result-file)
    (when-not (#{"reviewer" "architecture-reviewer"} template)
      (when-not (:durable? review-source)
        (exit! 2
               "Review decisions for worker assignments must use a durable reviewer report under .squad/reviews/.")))
    (write-atomic! (fs/path dir "review.md")
                   (:content review-source))
    (write-atomic! (fs/path dir "review")
                   (str "assignment_id: " assignment-id "\n"
                        "state: " state "\n"
                        "decision: " decision "\n"
                        "review_file: " (fs/path dir "review.md") "\n"
                        "source: " (:source review-source) "\n"
                        "updated_at: " now "\n"))
    (write-atomic! (fs/path dir "status")
                   (str "assignment_id: " assignment-id "\n"
                        "state: " state "\n"
                        "detail: " decision "\n"
                        "updated_at: " now "\n"))
    (append-line! (fs/path dir "events.log")
                  (str now "\t" state "\t" decision))
    (assignment-theme-event! root dir state assignment-id decision)
    (println "SQUAD_ASSIGNMENT:" assignment-id)
    (println "STATE:" state)
    (println "DECISION:" decision)
    (println "REVIEW:" (str (fs/path dir "review.md")))))

(defn block-assignment! [assignment-id reason-path]
  (validate-id! "Assignment id" assignment-id)
  (let [root (fs/absolutize (project-root))
        dir (assignment-dir root assignment-id)
        reason-source (source-file! reason-path)
        now (timestamp)]
    (ensure-assignment-dir! dir assignment-id)
    (write-atomic! (fs/path dir "blocker.md")
                   (slurp (str reason-source)))
    (write-atomic! (fs/path dir "blocker")
                   (str "assignment_id: " assignment-id "\n"
                        "state: blocked\n"
                        "reason_file: " (fs/path dir "blocker.md") "\n"
                        "updated_at: " now "\n"))
    (write-atomic! (fs/path dir "status")
                   (str "assignment_id: " assignment-id "\n"
                        "state: blocked\n"
                        "detail: blocked by squad leader\n"
                        "updated_at: " now "\n"))
    (append-line! (fs/path dir "events.log")
                  (str now "\tblocked\t" (fs/path dir "blocker.md")))
    (assignment-theme-event! root dir "blocked" assignment-id)
    (println "SQUAD_ASSIGNMENT:" assignment-id)
    (println "STATE: blocked")
    (println "BLOCKER:" (str (fs/path dir "blocker.md")))))

(defn record-accepted-merge! [root dir assignment-id commit detail now]
  (let [head (str/trim (:out (sh-at root "git" "rev-parse" "--short=10" "HEAD")))]
    (write-atomic! (fs/path dir "accepted-merge")
                   (str "assignment_id: " assignment-id "\n"
                        "state: merged\n"
                        "commit: " commit "\n"
                        "merge_commit: " head "\n"
                        "detail: " detail "\n"
                        "updated_at: " now "\n"))
    (write-atomic! (fs/path dir "status")
                   (str "assignment_id: " assignment-id "\n"
                        "state: merged\n"
                        "detail: " detail "\n"
                        "updated_at: " now "\n"))
    (append-line! (fs/path dir "events.log")
                  (str now "\tmerged\t" commit "\t" head "\t" detail))
    (assignment-theme-event! root dir "merged" assignment-id commit head)
    head))

(defn accept-merge! [assignment-id]
  (validate-id! "Assignment id" assignment-id)
  (let [root (fs/absolutize (project-root))
        dir (assignment-dir root assignment-id)
        result-file (fs/path dir "result")
        merge-file (fs/path dir "merge")
        now (timestamp)]
    (ensure-assignment-dir! dir assignment-id)
    (ensure-file! "Assignment result not found" result-file)
    (ensure-file! "Assignment merge readiness not found" merge-file)
    (when-not (= "merge_ready" (read-value merge-file "state"))
      (exit! 3 "Assignment is not merge_ready."))
    (let [commit (read-value result-file "commit")]
      (when-not (and commit (re-matches #"[0-9a-fA-F]{10}" commit))
        (exit! 2 "Assignment result must contain a 10-character commit."))
      (try
        (when (tracked-dirty? root)
          (write-merge-state! root dir assignment-id "merge_blocked" "tracked checkout dirty" commit now)
          (binding [*out* *err*]
            (println "SQUAD_ASSIGNMENT:" assignment-id)
            (println "STATE: merge_blocked")
            (println "COMMIT:" commit)
            (println "DETAIL: tracked checkout dirty"))
          (System/exit 4))
        (let [ancestor (sh-at root "git" "merge-base" "--is-ancestor" commit "HEAD")
              detail (if (zero? (:exit ancestor))
                       "commit already reachable from HEAD"
                       (let [merge (sh-at root "git" "merge" "--no-ff" "-m" (str "Merge squad assignment " assignment-id) commit)]
                         (when-not (zero? (:exit merge))
                           (abort-merge! root)
                           (write-merge-error! dir "accept-merge" merge)
                           (write-merge-state! root dir assignment-id "merge_blocked" "accepted merge failed" commit now)
                           (binding [*out* *err*]
                             (println "SQUAD_ASSIGNMENT:" assignment-id)
                             (println "STATE: merge_blocked")
                             (println "COMMIT:" commit)
                             (println "DETAIL: accepted merge failed"))
                           (System/exit 4))
                         "merged result commit"))]
          (let [merge-commit (record-accepted-merge! root dir assignment-id commit detail now)]
            (println "SQUAD_ASSIGNMENT:" assignment-id)
            (println "STATE: merged")
            (println "COMMIT:" commit)
            (println "MERGE_COMMIT:" merge-commit)
            (println "DETAIL:" detail)))
        (finally
          (abort-merge! root))))))

(defn reject-assignment! [assignment-id reason-path]
  (validate-id! "Assignment id" assignment-id)
  (let [root (fs/absolutize (project-root))
        dir (assignment-dir root assignment-id)
        reason-source (source-file! reason-path)
        now (timestamp)]
    (ensure-assignment-dir! dir assignment-id)
    (write-atomic! (fs/path dir "rejection.md")
                   (slurp (str reason-source)))
    (write-atomic! (fs/path dir "rejection")
                   (str "assignment_id: " assignment-id "\n"
                        "state: rejected\n"
                        "reason_file: " (fs/path dir "rejection.md") "\n"
                        "updated_at: " now "\n"))
    (write-atomic! (fs/path dir "status")
                   (str "assignment_id: " assignment-id "\n"
                        "state: rejected\n"
                        "detail: rejected by squad leader\n"
                        "updated_at: " now "\n"))
    (append-line! (fs/path dir "events.log")
                  (str now "\trejected\t" (fs/path dir "rejection.md")))
    (assignment-theme-event! root dir "rejected" assignment-id)
    (println "SQUAD_ASSIGNMENT:" assignment-id)
    (println "STATE: rejected")
    (println "REJECTION:" (str (fs/path dir "rejection.md")))))

(defn replace-assignment! [old-assignment-id new-assignment-id template instructions-file]
  (doseq [[kind value] [["Old assignment id" old-assignment-id]
                        ["New assignment id" new-assignment-id]]]
    (validate-id! kind value))
  (validate-template! template)
  (let [root (fs/absolutize (project-root))
        old-dir (assignment-dir root old-assignment-id)
        old-metadata (fs/path old-dir "metadata")
        theme-id (read-value old-metadata "theme_id")
        story-id (read-value old-metadata "story_id")
        requirement-text (read-value old-metadata "requires")
        now (timestamp)]
    (ensure-assignment-dir! old-dir old-assignment-id)
    (when-not (and theme-id story-id)
      (exit! 2 "Original assignment metadata must include theme_id and story_id."))
    (create-assignment! {:theme-id theme-id
                         :story-id story-id
                         :template template
                         :assignment-id new-assignment-id
                         :instructions-file instructions-file
                         :requirement (parse-requirement! requirement-text)})
    (let [new-dir (assignment-dir root new-assignment-id)]
      (append-file! (fs/path new-dir "metadata")
                    (str "replaces: " old-assignment-id "\n"))
      (write-atomic! (fs/path new-dir "replaces")
                     (str "assignment_id: " new-assignment-id "\n"
                          "replaces: " old-assignment-id "\n"
                          "created_at: " now "\n"))
      (write-atomic! (fs/path old-dir "replacement")
                     (str "assignment_id: " old-assignment-id "\n"
                          "state: replacement_created\n"
                          "replacement: " new-assignment-id "\n"
                          "updated_at: " now "\n"))
      (write-atomic! (fs/path old-dir "status")
                     (str "assignment_id: " old-assignment-id "\n"
                          "state: replacement_created\n"
                          "detail: " new-assignment-id "\n"
                          "updated_at: " now "\n"))
      (append-line! (fs/path old-dir "events.log")
                    (str now "\treplacement_created\t" new-assignment-id))
      (assignment-theme-event! root old-dir "replacement_created" old-assignment-id new-assignment-id)
      (println "REPLACES:" old-assignment-id)
      (println "STATE: replacement_created"))))

(defn -main [& args]
  (case (first args)
    "create" (create-assignment! (parse-create-args! args))
    "result" (if (= 3 (count args))
               (record-result! (second args) (nth args 2))
               (exit! 1 usage-text))
    "merge-ready" (if (= 2 (count args))
                    (mark-merge-ready! (second args))
                    (exit! 1 usage-text))
    "review" (if (= 4 (count args))
               (record-review! (second args) (nth args 2) (nth args 3))
               (exit! 1 usage-text))
    "accept-merge" (if (= 2 (count args))
                     (accept-merge! (second args))
                     (exit! 1 usage-text))
    "block" (if (= 3 (count args))
              (block-assignment! (second args) (nth args 2))
              (exit! 1 usage-text))
    "reject" (if (= 3 (count args))
               (reject-assignment! (second args) (nth args 2))
               (exit! 1 usage-text))
    "replace" (if (= 5 (count args))
                (replace-assignment! (second args) (nth args 2) (nth args 3) (nth args 4))
                (exit! 1 usage-text))
    "status" (if (= 2 (count args))
               (print-status! (second args))
               (exit! 1 usage-text))
    (exit! 1 usage-text)))

(apply -main *command-line-args*)
