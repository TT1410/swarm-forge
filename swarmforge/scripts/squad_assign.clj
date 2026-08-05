#!/usr/bin/env bb

(ns squad-assign
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-config :as cfg]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def usage-text
  (str "Usage:\n"
       "  squad_assign.sh create <theme-id> <story-id> <template> <assignment-id> <instructions-file> [--requires approval:<gate>]\n"
       "  squad_assign.sh create-batch <theme-id> <template> <assignment-id> <instructions-file> [--requires approval:<gate>]\n"
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
  (or (cfg/project-root)
      (exit! 1 "Cannot find SwarmForge project root")))

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

(defn optional-story-packet [root story-id]
  (let [packet (story-packet-file root story-id)]
    (when (fs/regular-file? packet)
      packet)))

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

(defn validate-template-requirement! [template story-id requirement]
  nil)

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

(defn parse-create-batch-args! [args]
  (when-not (#{5 7} (count args))
    (exit! 1 usage-text))
  (let [[_ theme-id template assignment-id instructions-file flag requirement] args]
    (when (and flag (not= "--requires" flag))
      (exit! 1 usage-text))
    {:theme-id theme-id
     :story-id "batch"
     :template template
     :assignment-id assignment-id
     :instructions-file instructions-file
     :scope "batch"
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

(defn handoff-commit [file]
  (when (= "git_handoff" (read-header file "type"))
    (let [commit (read-header file "commit")]
      (when (and commit (re-matches #"[0-9a-fA-F]{10}" commit))
        commit))))

(defn review-paths-in-commit [root commit]
  (let [paths-result (sh-at root "git" "show" "--name-only" "--format=" commit)]
    (->> (str/split-lines (:out paths-result))
         (filter #(and (str/starts-with? % ".squad/reviews/")
                       (str/ends-with? % ".md")))
         distinct
         vec)))

(defn review-content-in-commit [root commit path]
  (let [content (sh-at root "git" "show" (str commit ":" path))]
    (when (zero? (:exit content))
      {:content (:out content)
       :source (str commit ":" path)
       :durable? true})))

(defn reviewer-report-from-handoff [root file]
  (when-let [commit (handoff-commit file)]
    (let [review-paths (review-paths-in-commit root commit)]
      (when (= 1 (count review-paths))
        (review-content-in-commit root commit (first review-paths))))))

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

(defn result-handoff-template [assignment-id template]
  (str "type: git_handoff\n"
       "to: squad-leader\n"
       "priority: 50\n"
       "task: " assignment-id "\n"
       "commit: <10-char-commit>\n"
       "assignment: " assignment-id "\n"
       "template: " template "\n"
       "artifacts: <comma-separated-paths-or-none>\n"))

(defn theme-scoped-assignment? [template story-id]
  (and (= "analyst" template)
       (= "theme" story-id)))

(defn batch-scoped-assignment? [scope story-id]
  (or (= "batch" scope)
      (= "batch" story-id)))

(defn tool-lines [label tools]
  (when (seq tools)
    (str "## " label "\n\n"
         (apply str
                (for [{:keys [name source version purpose]} tools]
                  (str "- " name
                       (when purpose (str " (" purpose ")"))
                       ": `squad_tool.sh require " name " " source " " version "`\n")))
         "\n")))

(defn role-contract [root template]
  (let [file (fs/path root "swarmforge" "role-templates" (str template ".contract.edn"))]
    (when (fs/regular-file? file)
      (edn/read-string (slurp (str file))))))

(defn render-assignment [{:keys [theme-id story-id template assignment-id scope theme-text story-text instructions-text requirement packet-text required-tools optional-tools]}]
  (str "# Squad Assignment\n\n"
       "assignment_id: " assignment-id "\n"
       "theme_id: " theme-id "\n"
       "scope: " scope "\n"
       "story_id: " story-id "\n"
       "template: " template "\n"
       (when requirement
         (str "requires: " (:text requirement) "\n"))
       "\n"
       "## Theme\n\n"
       theme-text "\n\n"
       (when story-text
         (str "## Story\n\n"
              story-text "\n\n"))
       (when packet-text
         (str "## Story Packet\n\n"
              "```text\n"
              packet-text
              "```\n\n"))
       (tool-lines "Required Tools" required-tools)
       (tool-lines "Optional Tools" optional-tools)
       "## Leader Instructions\n\n"
       instructions-text "\n\n"
       "## Required Transient Protocol\n\n"
       "- Stay inside this assignment boundary.\n"
       "- Use `squad_event.sh` only with lifecycle states: starting, running, blocked, failed, handoff_ready, handoff_sent, retired. Put phase names and progress wording in the detail argument, not the state.\n"
       "- Commit completed work on your transient branch.\n"
       "- Send the result to `squad-leader` with `swarm_handoff.sh` using this draft shape:\n\n"
       "```text\n"
       (result-handoff-template assignment-id template)
       "```\n"))

(defn validate-create-ids! [theme-id story-id assignment-id]
  (doseq [[kind value] [["Theme id" theme-id]
                        ["Story id" story-id]
                        ["Assignment id" assignment-id]]]
    (validate-id! kind value)))

(defn assignment-story-file [root theme story-id skip-story?]
  (when-not skip-story?
    (or (referenced-project-file root (fs/path theme "stories" (str story-id ".ref")))
        (fs/path theme "stories" (str story-id ".md")))))

(defn assignment-create-context [{:keys [theme-id story-id template assignment-id instructions-file requirement scope]}]
  (let [root (fs/absolutize (project-root))
        theme (theme-dir root theme-id)
        theme-scoped? (theme-scoped-assignment? template story-id)
        batch-scoped? (batch-scoped-assignment? scope story-id)]
    {:root root
     :theme theme
     :theme-id theme-id
     :story-id story-id
     :template template
     :assignment-id assignment-id
     :requirement requirement
     :theme-scoped? theme-scoped?
     :batch-scoped? batch-scoped?
     :scope (cond
              theme-scoped? "theme"
              batch-scoped? "batch"
              :else "story")
     :theme-file (fs/path theme "theme.md")
     :story-file (assignment-story-file root theme story-id (or theme-scoped? batch-scoped?))
     :template-file (fs/path root "swarmforge" "role-templates" (str template ".prompt"))
     :instructions (source-file! instructions-file)
     :dir (assignment-dir root assignment-id)
     :contract (role-contract root template)
     :packet (optional-story-packet root story-id)
     :now (timestamp)}))

(defn ensure-packet-theme! [{:keys [packet theme-id story-id]}]
  (when (and packet
             (not= theme-id (read-value packet "theme_id")))
    (exit! 2
           (str "Story packet " story-id " belongs to a different theme."))))

(defn ensure-create-context! [{:keys [theme-file theme-scoped? batch-scoped? story-file template-file dir] :as context}]
  (ensure-file! "Theme file not found" theme-file)
  (when-not (or theme-scoped? batch-scoped?)
    (ensure-file! "Story file not found" story-file))
  (ensure-file! "Role template not found" template-file)
  (ensure-packet-theme! context)
  (when (fs/exists? dir)
    (exit! 2 (str "Assignment already exists: " (:assignment-id context)))))

(defn assignment-text [context]
  (render-assignment (merge context
                            {:theme-text (slurp (str (:theme-file context)))
                             :story-text (when-let [story-file (:story-file context)]
                                           (slurp (str story-file)))
                             :instructions-text (slurp (str (:instructions context)))
                             :packet-text (when-let [packet (:packet context)]
                                            (slurp (str packet)))
                             :required-tools (get-in context [:contract :required-tools])
                             :optional-tools (get-in context [:contract :optional-tools])})))

(defn assignment-metadata-text [{:keys [assignment-id theme-id scope story-id template requirement assignment-file now]}]
  (str "assignment_id: " assignment-id "\n"
       "theme_id: " theme-id "\n"
       "scope: " scope "\n"
       "story_id: " story-id "\n"
       "template: " template "\n"
       (when requirement
         (str "requires: " (:text requirement) "\n"))
       "assignment_file: " assignment-file "\n"
       "created_at: " now "\n"))

(defn assignment-status-text [{:keys [assignment-id template story-id now]}]
  (str "assignment_id: " assignment-id "\n"
       "state: created\n"
       "detail: " template " for " story-id "\n"
       "updated_at: " now "\n"))

(defn write-assignment-records! [{:keys [dir theme assignment-id template story-id now] :as context} text]
  (fs/create-dirs dir)
  (let [assignment-file (fs/path dir "assignment.md")
        context (assoc context :assignment-file assignment-file)]
    (write-atomic! assignment-file text)
    (write-atomic! (fs/path dir "result-handoff.draft")
                   (result-handoff-template assignment-id template))
    (write-atomic! (fs/path dir "metadata") (assignment-metadata-text context))
    (write-atomic! (fs/path dir "status") (assignment-status-text context))
    (fs/create-dirs (fs/path theme "assignments"))
    (write-atomic! (fs/path theme "assignments" (str assignment-id ".md")) text)
    (append-line! (fs/path theme "events.log")
                  (str now "\tassignment_created\t" assignment-id "\t" template "\t" story-id))
    assignment-file))

(defn print-create-result! [{:keys [assignment-id theme-id story-id template requirement]} assignment-file]
  (println "SQUAD_ASSIGNMENT:" assignment-id)
  (println "THEME:" theme-id)
  (println "STORY:" story-id)
  (println "TEMPLATE:" template)
  (when requirement
    (println "REQUIRES:" (:text requirement)))
  (println "ASSIGNMENT:" (str assignment-file)))

(defn create-assignment! [{:keys [theme-id story-id template assignment-id instructions-file requirement scope]}]
  (validate-create-ids! theme-id story-id assignment-id)
  (validate-template! template)
  (validate-template-requirement! template story-id requirement)
  (let [context (assignment-create-context {:theme-id theme-id
                                            :story-id story-id
                                            :template template
                                            :assignment-id assignment-id
                                            :instructions-file instructions-file
                                            :scope scope
                                            :requirement requirement})]
    (ensure-create-context! context)
    (print-create-result! context
                          (write-assignment-records! context (assignment-text context)))))

(defn create-batch-assignment! [args]
  (create-assignment! args))

(defn assignment-status-paths [dir]
  {:metadata (fs/path dir "metadata")
   :status (fs/path dir "status")
   :result-file (fs/path dir "result.handoff")
   :merge-file (fs/path dir "merge")
   :accepted-merge-file (fs/path dir "accepted-merge")
   :review-file (fs/path dir "review")
   :merge-error-file (fs/path dir "merge-error")
   :blocker-file (fs/path dir "blocker")
   :rejection-file (fs/path dir "rejection")
   :replacement-file (fs/path dir "replacement")})

(defn print-status-value! [label value]
  (println (str label ":") value))

(defn print-status-file! [label file]
  (print-status-value! label (if (fs/exists? file) (str file) "none")))

(defn field-value [file field default]
  (or (read-value file field) default))

(defn assignment-metadata-fields [assignment-id metadata status]
  [["ASSIGNMENT" assignment-id]
   ["THEME" (field-value metadata "theme_id" "unknown")]
   ["STORY" (field-value metadata "story_id" "unknown")]
   ["TEMPLATE" (field-value metadata "template" "unknown")]
   ["STATE" (field-value status "state" "unknown")]
   ["DETAIL" (field-value status "detail" "")]
   ["ASSIGNMENT_FILE" (field-value metadata "assignment_file" "unknown")]])

(defn print-assignment-metadata! [assignment-id {:keys [metadata status]}]
  (doseq [[label value] (assignment-metadata-fields assignment-id metadata status)]
    (print-status-value! label value)))

(defn print-assignment-files! [{:keys [result-file merge-file accepted-merge-file merge-error-file review-file blocker-file rejection-file replacement-file]}]
  (print-status-file! "RESULT" result-file)
  (print-status-file! "MERGE" merge-file)
  (print-status-file! "ACCEPTED_MERGE" accepted-merge-file)
  (print-status-file! "MERGE_ERROR" merge-error-file)
  (print-status-file! "REVIEW" review-file)
  (print-status-file! "BLOCKER" blocker-file)
  (print-status-file! "REJECTION" rejection-file)
  (print-status-file! "REPLACEMENT" replacement-file))

(defn print-status! [assignment-id]
  (validate-id! "Assignment id" assignment-id)
  (let [root (fs/absolutize (project-root))
        dir (assignment-dir root assignment-id)
        paths (assignment-status-paths dir)]
    (ensure-assignment-dir! dir assignment-id)
    (print-assignment-metadata! assignment-id paths)
    (print-assignment-files! paths)))

(defn validate-result-type! [type]
  (when-not (= "git_handoff" type)
    (exit! 2 "Result handoff must have type: git_handoff.")))

(defn validate-result-recipient! [to]
  (when-not (= "squad-leader" to)
    (exit! 2 "Result handoff must have to: squad-leader.")))

(defn validate-result-task! [assignment-id task]
  (when-not (= assignment-id task)
    (exit! 2 (str "Result handoff task must match assignment id: " assignment-id))))

(defn validate-result-commit! [commit]
  (when-not (and commit (re-matches #"[0-9a-fA-F]{10}" commit))
    (exit! 2 "Result handoff must have a 10-character commit header.")))

(defn validate-result-sender! [from]
  (when (str/blank? from)
    (exit! 2 "Result handoff must have a from header."))
  (when (= "squad-leader" from)
    (exit! 2 "Transient result handoff may not be from: squad-leader.")))

(defn validate-result-manifest! [assignment-id template from manifest]
  (let [{handoff-assignment "assignment"
         handoff-agent "agent"
         handoff-template "template"
         artifacts "artifacts"} manifest]
    (when-not (= assignment-id handoff-assignment)
      (exit! 2 (str "Result manifest assignment must match assignment id: " assignment-id)))
    (when-not (= from handoff-agent)
      (exit! 2 "Result manifest agent must match handoff sender."))
    (when-not (= template handoff-template)
      (exit! 2 (str "Result manifest template must match assignment template: " template)))
    (when (str/blank? artifacts)
      (exit! 2 "Result manifest must include artifacts, or artifacts: none."))
    manifest))

(defn validate-sender-assignment-lineage! [root assignment-id from]
  (let [agent-metadata (fs/path root ".squad" "agents" from "metadata")]
    (when (fs/exists? agent-metadata)
      (let [task-id (read-value agent-metadata "task_id")
            worktree (read-value agent-metadata "worktree")]
        (when-not (= assignment-id task-id)
          (exit! 2 (str "Result sender " from " is assigned to " task-id ", not " assignment-id)))
        (when (and (not (str/blank? worktree))
                   (not (fs/exists? worktree)))
          (exit! 2 (str "Result sender worktree is missing: " worktree)))))))

(defn validate-result-handoff! [assignment-id template handoff-file]
  (let [type (read-value handoff-file "type")
        to (read-value handoff-file "to")
        task (read-value handoff-file "task")
        commit (read-value handoff-file "commit")
        from (read-value handoff-file "from")
        manifest {"assignment" (read-value handoff-file "assignment")
                  "agent" (read-value handoff-file "agent")
                  "template" (read-value handoff-file "template")
                  "artifacts" (read-value handoff-file "artifacts")}]
    (validate-result-type! type)
    (validate-result-recipient! to)
    (validate-result-task! assignment-id task)
    (validate-result-commit! commit)
    (validate-result-sender! from)
    (validate-result-manifest! assignment-id template from manifest)
    {:from from
     :commit commit
     :manifest manifest
     :body (handoff-body handoff-file)}))

(defn ensure-result-reachable! [root from commit]
  (let [sender-branch (str "swarmforge-" from)
        branch-exists (sh-at root "git" "rev-parse" "--verify" (str sender-branch "^{commit}"))]
    (when (zero? (:exit branch-exists))
      (let [reachable (sh-at root "git" "merge-base" "--is-ancestor" commit sender-branch)]
        (when-not (zero? (:exit reachable))
          (exit! 2
                 (str "Result commit " commit " is not reachable from sender branch " sender-branch)))))))

(defn write-result-record! [dir assignment-id from commit now]
  (write-atomic! (fs/path dir "result")
                 (str "assignment_id: " assignment-id "\n"
                      "from: " from "\n"
                      "commit: " commit "\n"
                      "received_at: " now "\n"))
  (write-atomic! (fs/path dir "status")
                 (str "assignment_id: " assignment-id "\n"
                      "state: result_received\n"
                      "detail: " from " " commit "\n"
                      "updated_at: " now "\n"))
  (append-line! (fs/path dir "events.log")
                (str now "\tresult_received\t" from "\t" commit)))

(defn print-result-recorded! [assignment-id from commit body]
  (println "SQUAD_ASSIGNMENT:" assignment-id)
  (println "STATE: result_received")
  (println "FROM:" from)
  (println "COMMIT:" commit)
  (when-not (str/blank? body)
    (println "BODY_RECORDED: true")))

(defn record-result! [assignment-id handoff-path]
  (validate-id! "Assignment id" assignment-id)
  (let [root (fs/absolutize (project-root))
        dir (assignment-dir root assignment-id)
        metadata (fs/path dir "metadata")
        template (read-value metadata "template")
        handoff-file (source-file! handoff-path)
        {:keys [from commit body manifest]} (validate-result-handoff! assignment-id template handoff-file)
        theme-id (or (read-value metadata "theme_id") "unknown")
        now (timestamp)]
    (ensure-assignment-dir! dir assignment-id)
    (validate-sender-assignment-lineage! root assignment-id from)
    (ensure-result-reachable! root from commit)
    (write-atomic! (fs/path dir "result.handoff")
                   (slurp (str handoff-file)))
    (write-atomic! (fs/path dir "result-manifest")
                   (str "assignment_id: " assignment-id "\n"
                        "agent: " from "\n"
                        "template: " template "\n"
                        "commit: " commit "\n"
                        "artifacts: " (get manifest "artifacts") "\n"
                        "received_at: " now "\n"))
    (write-result-record! dir assignment-id from commit now)
    (when-not (= "unknown" theme-id)
      (assignment-theme-event! root dir "result_received" assignment-id from commit))
    (print-result-recorded! assignment-id from commit body)))

(defn merge-head-exists? [root]
  (fs/exists? (fs/path root ".git" "MERGE_HEAD")))

(defn abort-merge! [root]
  (when (merge-head-exists? root)
    (sh-at root "git" "merge" "--abort")))

(defn tracked-dirty? [root]
  (not (str/blank?
        (str/trim (:out (sh-at root "git" "status" "--porcelain" "--untracked-files=no"))))))

(defn remove-merge-check-worktree! [root worktree]
  (when (fs/exists? worktree)
    (let [removed (sh-at root "git" "worktree" "remove" "--force" (str worktree))]
      (when-not (zero? (:exit removed))
        (fs/delete-tree worktree)
        (sh-at root "git" "worktree" "prune")))))

(defn with-merge-check-worktree [root f]
  (let [parent (fs/path root ".squad" "tmp" "merge-checks")
        _ (fs/create-dirs parent)
        worktree (fs/create-temp-dir {:dir parent :prefix "merge-ready-"})]
    (fs/delete-tree worktree)
    (try
      (let [added (sh-at root "git" "worktree" "add" "--detach" (str worktree) "HEAD")]
        (when-not (zero? (:exit added))
          (exit! (:exit added)
                 "Could not create isolated merge-check worktree."
                 (:err added)))
        (f worktree))
      (finally
        (remove-merge-check-worktree! root worktree)))))

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

(defn valid-result-commit? [commit]
  (and commit (re-matches #"[0-9a-fA-F]{10}" commit)))

(defn ensure-result-commit! [commit]
  (when-not (valid-result-commit? commit)
    (exit! 2 "Assignment result must contain a 10-character commit.")))

(defn ensure-known-commit! [root commit]
  (let [known (sh-at root "git" "rev-parse" "--verify" (str commit "^{commit}"))]
    (when-not (zero? (:exit known))
      (exit! 2 (str "Unknown result commit: " commit)))))

(defn ancestor-commit? [root commit]
  (zero? (:exit (sh-at root "git" "merge-base" "--is-ancestor" commit "HEAD"))))

(defn dry-run-merge [root commit]
  (with-merge-check-worktree
    root
    (fn [worktree]
      (sh-at worktree "git" "merge" "--no-commit" "--no-ff" commit))))

(defn print-merge-ready! [assignment-id commit detail]
  (println "SQUAD_ASSIGNMENT:" assignment-id)
  (println "STATE: merge_ready")
  (println "COMMIT:" commit)
  (println "DETAIL:" detail))

(defn print-merge-blocked-ready! [assignment-id commit]
  (binding [*out* *err*]
    (println "SQUAD_ASSIGNMENT:" assignment-id)
    (println "STATE: merge_blocked")
    (println "COMMIT:" commit)
    (println "DETAIL: dry-run merge failed")))

(defn mark-merge-ready-state! [root dir assignment-id commit now detail]
  (write-merge-state! root dir assignment-id "merge_ready" detail commit now)
  (print-merge-ready! assignment-id commit detail))

(defn mark-merge-blocked-state! [root dir assignment-id commit now merge]
  (write-merge-error! dir "merge-ready" merge)
  (write-merge-state! root dir assignment-id "merge_blocked" "dry-run merge failed" commit now)
  (print-merge-blocked-ready! assignment-id commit)
  (System/exit 4))

(defn mark-merge-result! [root dir assignment-id commit now merge]
  (if (zero? (:exit merge))
    (mark-merge-ready-state! root dir assignment-id commit now "dry-run merge passed")
    (mark-merge-blocked-state! root dir assignment-id commit now merge)))

(defn check-merge-ready! [root dir assignment-id commit now]
  (if (ancestor-commit? root commit)
    (mark-merge-ready-state! root dir assignment-id commit now "commit already reachable from HEAD")
    (mark-merge-result! root dir assignment-id commit now (dry-run-merge root commit))))

(defn mark-merge-ready! [assignment-id]
  (validate-id! "Assignment id" assignment-id)
  (let [root (fs/absolutize (project-root))
        dir (assignment-dir root assignment-id)
        result-file (fs/path dir "result")
        now (timestamp)]
    (ensure-assignment-dir! dir assignment-id)
    (ensure-file! "Assignment result not found" result-file)
    (let [commit (read-value result-file "commit")]
      (ensure-result-commit! commit)
      (try
        (ensure-known-commit! root commit)
        (check-merge-ready! root dir assignment-id commit now)
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

(defn ensure-merge-ready! [merge-file]
  (when-not (= "merge_ready" (read-value merge-file "state"))
    (exit! 3 "Assignment is not merge_ready.")))

(defn result-commit! [result-file]
  (let [commit (read-value result-file "commit")]
    (when-not (and commit (re-matches #"[0-9a-fA-F]{10}" commit))
      (exit! 2 "Assignment result must contain a 10-character commit."))
    commit))

(defn print-merge-blocked! [assignment-id commit detail]
  (binding [*out* *err*]
    (println "SQUAD_ASSIGNMENT:" assignment-id)
    (println "STATE: merge_blocked")
    (println "COMMIT:" commit)
    (println "DETAIL:" detail))
  (System/exit 4))

(defn block-merge! [root dir assignment-id phase detail commit now result]
  (when result
    (write-merge-error! dir phase result))
  (write-merge-state! root dir assignment-id "merge_blocked" detail commit now)
  (print-merge-blocked! assignment-id commit detail))

(defn merge-detail! [root dir assignment-id commit now]
  (let [ancestor (sh-at root "git" "merge-base" "--is-ancestor" commit "HEAD")]
    (if (zero? (:exit ancestor))
      "commit already reachable from HEAD"
      (let [merge (sh-at root "git" "merge" "--no-ff" "-m" (str "Merge squad assignment " assignment-id) commit)]
        (when-not (zero? (:exit merge))
          (abort-merge! root)
          (block-merge! root dir assignment-id "accept-merge" "accepted merge failed" commit now merge))
        "merged result commit"))))

(defn print-merge-accepted! [assignment-id commit merge-commit detail]
  (println "SQUAD_ASSIGNMENT:" assignment-id)
  (println "STATE: merged")
  (println "COMMIT:" commit)
  (println "MERGE_COMMIT:" merge-commit)
  (println "DETAIL:" detail))

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
    (ensure-merge-ready! merge-file)
    (let [commit (result-commit! result-file)]
      (try
        (when (tracked-dirty? root)
          (block-merge! root dir assignment-id nil "tracked checkout dirty" commit now nil))
        (let [detail (merge-detail! root dir assignment-id commit now)
              merge-commit (record-accepted-merge! root dir assignment-id commit detail now)]
          (print-merge-accepted! assignment-id commit merge-commit detail))
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
                          "state: superseded\n"
                          "replacement: " new-assignment-id "\n"
                          "updated_at: " now "\n"))
      (write-atomic! (fs/path old-dir "status")
                     (str "assignment_id: " old-assignment-id "\n"
                          "state: superseded\n"
                          "detail: " new-assignment-id "\n"
                          "updated_at: " now "\n"))
      (append-line! (fs/path old-dir "events.log")
                    (str now "\tsuperseded\t" new-assignment-id))
      (assignment-theme-event! root old-dir "superseded" old-assignment-id new-assignment-id)
      (println "REPLACES:" old-assignment-id)
      (println "STATE: superseded"))))

(defn exact-count! [args expected]
  (when-not (= expected (count args))
    (exit! 1 usage-text)))

(defn run-counted-command! [args expected f]
  (exact-count! args expected)
  (f args))

(def assignment-commands
  {"create" (fn [args] (create-assignment! (parse-create-args! args)))
   "create-batch" (fn [args] (create-batch-assignment! (parse-create-batch-args! args)))
   "result" (fn [args] (run-counted-command! args 3 #(record-result! (second %) (nth % 2))))
   "merge-ready" (fn [args] (run-counted-command! args 2 #(mark-merge-ready! (second %))))
   "review" (fn [args] (run-counted-command! args 4 #(record-review! (second %) (nth % 2) (nth % 3))))
   "accept-merge" (fn [args] (run-counted-command! args 2 #(accept-merge! (second %))))
   "block" (fn [args] (run-counted-command! args 3 #(block-assignment! (second %) (nth % 2))))
   "reject" (fn [args] (run-counted-command! args 3 #(reject-assignment! (second %) (nth % 2))))
   "replace" (fn [args] (run-counted-command! args 5 #(replace-assignment! (second %) (nth % 2) (nth % 3) (nth % 4))))
   "status" (fn [args] (run-counted-command! args 2 #(print-status! (second %))))})

(defn -main [& args]
  (if-let [command (assignment-commands (first args))]
    (command args)
    (exit! 1 usage-text)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
