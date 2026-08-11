#!/usr/bin/env bb

(ns squad-assign
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-config :as cfg]
            [squad-tool-table :as tools]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def usage-text
  (str "Usage:\n"
       "  squad_assign.sh create <theme-id> <story-id> <template> <assignment-id> <instructions-file|--auto-instructions> [--requires approval:<gate>] [--queue-spawn]\n"
       "  squad_assign.sh create-batch <theme-id> <template> <assignment-id> <instructions-file|--auto-instructions> [--requires approval:<gate>] [--queue-spawn]\n"
       "  squad_assign.sh create-merger <blocked-assignment-id> <merger-assignment-id> <instructions-file|--auto-instructions> [--queue-spawn]\n"
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

(defn auto-instructions? [path]
  (= "--auto-instructions" path))

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

(defn parse-create-options! [tokens]
  (loop [tokens tokens
         options {:requirement nil :queue-spawn? false}]
    (if (empty? tokens)
      options
      (case (first tokens)
        "--requires"
        (let [requirement (second tokens)]
          (when-not requirement
            (exit! 1 usage-text))
          (recur (nnext tokens)
                 (assoc options :requirement (parse-requirement! requirement))))

        "--queue-spawn"
        (recur (rest tokens) (assoc options :queue-spawn? true))

        (exit! 1 usage-text)))))

(defn parse-create-args! [args]
  (when-not (>= (count args) 6)
    (exit! 1 usage-text))
  (let [[_ theme-id story-id template assignment-id instructions-file & option-tokens] args]
    (merge {:theme-id theme-id
            :story-id story-id
            :template template
            :assignment-id assignment-id
            :instructions-file instructions-file}
           (parse-create-options! option-tokens))))

(defn parse-create-batch-args! [args]
  (when-not (>= (count args) 5)
    (exit! 1 usage-text))
  (let [[_ theme-id template assignment-id instructions-file & option-tokens] args]
    (merge {:theme-id theme-id
            :story-id "batch"
            :template template
            :assignment-id assignment-id
            :instructions-file instructions-file
            :scope "batch"}
           (parse-create-options! option-tokens))))

(defn parse-create-merger-args! [args]
  (when-not (>= (count args) 4)
    (exit! 1 usage-text))
  (let [[_ blocked-assignment-id assignment-id instructions-file & option-tokens] args]
    (merge {:blocked-assignment-id blocked-assignment-id
            :assignment-id assignment-id
            :instructions-file instructions-file}
           (parse-create-options! option-tokens))))

(def valid-review-decisions
  {"accepted" "review_accepted"
   "changes-requested" "review_changes_requested"})

(def reviewer-templates
  #{"gherkin-reviewer"
    "qa-procedure-reviewer"
    "code-reviewer"
    "architect"})

(defn reviewer-template? [template]
  (contains? reviewer-templates template))

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

(defn durable-review-relative? [relative]
  (and relative
       (str/ends-with? relative ".md")
       (or (str/starts-with? relative "reviews/")
           (str/starts-with? relative ".squad/reviews/"))))

(defn durable-review-file? [root file]
  (when-let [relative (relative-to-root root file)]
    (durable-review-relative? relative)))
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
         (filter durable-review-relative?)
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

(defn header-map [file]
  (into {}
        (keep (fn [line]
                (when-let [[_ k v] (re-matches #"([^:]+):\s*(.*)" line)]
                  [k v])))
        (take-while (complement str/blank?)
                    (str/split-lines (slurp (str file))))))

(defn result-handoff-template [assignment-id template]
  (str "type: git_handoff\n"
       "to: squad-leader\n"
       "priority: 50\n"
       "task: " assignment-id "\n"
       "commit: <10-char-commit>\n"
       "assignment: " assignment-id "\n"
       "template: " template "\n"
       "artifacts: <comma-separated-paths-or-none>\n"
       (when (reviewer-template? template)
         "review_decision: <accepted|changes-requested>\n")))

(defn split-list [value]
  (->> (str/split (or value "") #",")
       (map str/trim)
       (remove str/blank?)
       (remove #{"none"})
       vec))

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

(defn tool-startup-lines [tools]
  (tools/startup-instructions tools))

(defn tool-evidence-lines [evidence]
  (tools/evidence-instructions evidence))

(defn role-contract [root template]
  (let [file (fs/path root "swarmforge" "role-templates" (str template ".contract.edn"))]
    (when (fs/regular-file? file)
      (edn/read-string (slurp (str file))))))

(def module-map-templates
  #{"analyst" "implementer" "architect" "senior-implementer" "cleaner" "code-reviewer"})

(defn include-module-map? [template]
  (contains? module-map-templates template))

(defn render-assignment [{:keys [theme-id story-id template assignment-id scope theme-text module-map-text story-text instructions-text requirement packet-text required-tools optional-tools required-evidence merge-text]}]
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
       (when module-map-text
         (str "## Theme Module Map\n\n"
              module-map-text "\n\n"))
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
       (tool-startup-lines required-tools)
       (tool-evidence-lines required-evidence)
       (when merge-text
         (str "## Merge Source\n\n"
              merge-text "\n\n"))
       "## Leader Instructions\n\n"
       instructions-text "\n\n"
       "## Required Transient Protocol\n\n"
       "- Stay inside this assignment boundary.\n"
       "- Use `squad_event.sh` only with lifecycle states: starting, running, blocked, failed, handoff_ready, handoff_sent. Do not self-retire; after handoff report handoff_sent and leave retirement to squad_retire.sh after the Squad Leader resolves the workflow. Put phase names and progress wording in the detail argument, not the state.\n"
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

(defn assignment-scope [{:keys [template story-id scope]}]
  (cond
    (theme-scoped-assignment? template story-id) "theme"
    (batch-scoped-assignment? scope story-id) "batch"
    :else "story"))

(defn story-file-required? [scope]
  (= "story" scope))

(defn assignment-scope-flags [scope]
  {:theme-scoped? (= "theme" scope)
   :batch-scoped? (= "batch" scope)})

(defn assignment-create-context [{:keys [theme-id story-id template assignment-id instructions-file requirement scope queue-spawn?]}]
  (let [root (fs/absolutize (project-root))
        theme (theme-dir root theme-id)
        resolved-scope (assignment-scope {:template template :story-id story-id :scope scope})
        scope-flags (assignment-scope-flags resolved-scope)
        auto-instructions? (auto-instructions? instructions-file)]
    {:root root
     :theme theme
     :theme-id theme-id
     :story-id story-id
     :template template
     :assignment-id assignment-id
     :requirement requirement
     :queue-spawn? queue-spawn?
     :theme-scoped? (:theme-scoped? scope-flags)
     :batch-scoped? (:batch-scoped? scope-flags)
     :scope resolved-scope
     :theme-file (fs/path theme "theme.md")
     :module-map-file (fs/path theme "module-map.md")
     :story-file (when (story-file-required? resolved-scope)
                   (assignment-story-file root theme story-id false))
     :template-file (fs/path root "swarmforge" "role-templates" (str template ".prompt"))
     :instructions (when-not auto-instructions?
                     (source-file! instructions-file))
     :auto-instructions? auto-instructions?
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

(defn default-instructions [{:keys [template story-id scope]}]
  (str "Follow the " template " role contract for this " scope " assignment.\n"
       "Use the provided theme, story packet, and role prompt as the source of truth.\n"
       "Produce the required artifact for " story-id ", commit the work, and hand it off with the provided draft.\n"))

(defn assignment-instructions-text [context]
  (if (:auto-instructions? context)
    (default-instructions context)
    (slurp (str (:instructions context)))))

(defn module-map-text-for [context]
  (when (and (include-module-map? (:template context))
             (fs/regular-file? (:module-map-file context)))
    (slurp (str (:module-map-file context)))))

(defn assignment-text [context]
  (render-assignment (merge context
                            {:theme-text (slurp (str (:theme-file context)))
                             :module-map-text (module-map-text-for context)
                             :story-text (when-let [story-file (:story-file context)]
                                           (slurp (str story-file)))
                             :instructions-text (assignment-instructions-text context)
                             :packet-text (when-let [packet (:packet context)]
                                            (slurp (str packet)))
                             :required-tools (tools/required-tools (:root context) (:template context))
                             :optional-tools (tools/optional-tools (:root context) (:template context))
                             :required-evidence (tools/required-evidence (:root context) (:template context))})))

(defn assignment-metadata-text [{:keys [assignment-id theme-id scope story-id template requirement assignment-file now merge-for conflicting-template conflicting-agent conflicting-commit]}]
  (str "assignment_id: " assignment-id "\n"
       "theme_id: " theme-id "\n"
       "scope: " scope "\n"
       "story_id: " story-id "\n"
       "template: " template "\n"
       (when requirement
         (str "requires: " (:text requirement) "\n"))
       (when merge-for
         (str "merge_for: " merge-for "\n"))
       (when conflicting-template
         (str "conflicting_template: " conflicting-template "\n"))
       (when conflicting-agent
         (str "conflicting_agent: " conflicting-agent "\n"))
       (when conflicting-commit
         (str "conflicting_commit: " conflicting-commit "\n"))
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

(defn spawn-request-id [template assignment-id]
  (str (str/replace (timestamp) #"[^\dTZ]" "")
       "_" template "_" assignment-id "_"
       (.toString (java.util.UUID/randomUUID))))

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

(defn pending-or-active-spawn? [root assignment-id]
  (or (contains? (spawn-request-task-ids root) assignment-id)
      (let [agent-id (read-value (fs/path root ".squad" "assignments" assignment-id "status")
                                 "agent_id")
            state (when agent-id
                    (read-value (fs/path root ".squad" "agents" agent-id "status") "state"))]
        (contains? #{"starting" "running" "failed" "blocked" "handoff_ready" "handoff_sent"}
                   (or state "")))))

(defn queue-spawn-request! [root template assignment-id assignment-file]
  (if (pending-or-active-spawn? root assignment-id)
    (do
      (println "SQUAD_SPAWN_REQUEST: skipped")
      (println "TASK_ID:" assignment-id)
      (println "STATE: occupied")
      (println "DETAIL: active agent or pending spawn already covers this task_id")
      nil)
    (let [request-dir (fs/path root ".squad" "spawn-requests" "new")
          request (fs/path request-dir (str (spawn-request-id template assignment-id) ".request"))]
      (write-atomic! request
                     (str "template: " template "\n"
                          "task_id: " assignment-id "\n"
                          "assignment: " assignment-file "\n"
                          "requested_at: " (timestamp) "\n"))
      request)))

(defn maybe-queue-spawn! [{:keys [root template assignment-id requirement queue-spawn?]} assignment-file]
  (when (and queue-spawn? (nil? requirement))
    (when-let [request (queue-spawn-request! root template assignment-id assignment-file)]
      (println "SQUAD_SPAWN_REQUEST:" (fs/file-name request))
      (println "STATE: requested"))))

(defn create-assignment! [{:keys [theme-id story-id template assignment-id instructions-file requirement scope] :as args}]
  (validate-create-ids! theme-id story-id assignment-id)
  (validate-template! template)
  (validate-template-requirement! template story-id requirement)
  (let [context (assignment-create-context {:theme-id theme-id
                                            :story-id story-id
                                            :template template
                                            :assignment-id assignment-id
                                            :instructions-file instructions-file
                                            :scope scope
                                            :requirement requirement
                                            :queue-spawn? (:queue-spawn? args)})]
    (ensure-create-context! context)
    (let [assignment-file (write-assignment-records! context (assignment-text context))]
      (print-create-result! context assignment-file)
      (maybe-queue-spawn! context assignment-file))))

(def batch-template-kinds
  {"hardener" "hardener"
   "qa" "qa"
   "architect" "architecture"})

(defn manifest-story-count [manifest]
  (if (fs/regular-file? manifest)
    (max 0 (dec (count (str/split-lines (slurp (str manifest))))))
    0))

(defn ensure-batch-manifest! [root template assignment-id]
  (when-let [kind (get batch-template-kinds template)]
    (let [batch-dir (fs/path root ".squad" "batches" assignment-id)
          metadata (fs/path batch-dir "metadata")
          manifest (fs/path batch-dir "manifest.tsv")]
      (when-not (fs/directory? batch-dir)
        (exit! 2 (str "Batch record is missing: " assignment-id)))
      (when-not (= kind (read-value metadata "kind"))
        (exit! 2 (str "Batch " assignment-id " is not a " kind " batch.")))
      (when-not (pos? (manifest-story-count manifest))
        (exit! 2 (str "Batch manifest is missing or empty: " manifest))))))

(defn close-batch-for-assignment! [root assignment-id]
  (let [script (str (fs/path (fs/parent *file*) "squad_batch.sh"))
        result (sh-at root script "close" assignment-id)]
    (when-not (zero? (:exit result))
      (exit! (or (:exit result) 2)
             (str "Failed to close batch " assignment-id " after create-batch.")
             (str/trim (or (:err result) ""))))))

(defn create-batch-assignment! [args]
  (let [root (fs/absolutize (project-root))]
    (ensure-batch-manifest! root (:template args) (:assignment-id args))
    (create-assignment! args)
    (close-batch-for-assignment! root (:assignment-id args))))

(defn merge-source-text [{:keys [merge-for conflicting-template conflicting-agent conflicting-commit]}]
  (str "blocked_assignment_id: " merge-for "\n"
       "blocked_template: " (or conflicting-template "unknown") "\n"
       "conflicting_agent: " (or conflicting-agent "unknown") "\n"
       "conflicting_commit: " (or conflicting-commit "unknown") "\n"
       "Merge this result commit into the squad leader's current integration state, resolve mechanical conflicts, run the relevant test suites, and hand back one unconflicted result commit.\n"))

(defn merge-suffix-depth [assignment-id]
  (count (re-seq #"-merge" (str assignment-id))))

(defn merge-lineage-root [assignment-id]
  (str/replace (str assignment-id) #"(?:-merge)+$" ""))

(defn assignment-in-merge-lineage? [assignment-id lineage-root]
  (or (= assignment-id lineage-root)
      (str/starts-with? (str assignment-id) (str lineage-root "-merge"))))

(defn assignment-blocked-for-merge-limit? [root assignment-id]
  (or (= "blocked" (read-value (fs/path root ".squad" "assignments" assignment-id "status") "state"))
      (fs/regular-file? (fs/path root ".squad" "assignments" assignment-id "blocker"))
      (fs/regular-file? (fs/path root ".squad" "assignments" assignment-id "blocker.md"))))

(defn lineage-max-depth-exhausted? [root lineage-root max-depth]
  (let [assignments-dir (fs/path root ".squad" "assignments")]
    (boolean
     (when (fs/directory? assignments-dir)
       (some (fn [dir]
               (let [id (fs/file-name dir)]
                 (and (assignment-in-merge-lineage? id lineage-root)
                      (>= (merge-suffix-depth id) max-depth)
                      (assignment-blocked-for-merge-limit? root id))))
             (filter fs/directory? (fs/list-dir assignments-dir)))))))

(defn ensure-merger-create-allowed! [root blocked-assignment-id]
  (let [max-depth (cfg/squad-max-merger-depth root)
        depth (merge-suffix-depth blocked-assignment-id)
        lineage-root (merge-lineage-root blocked-assignment-id)]
    (when (>= depth max-depth)
      (exit! 2
             (str "Cannot create merger: assignment already at max_merger_depth (" max-depth "). "
                  "Use squad_assign.sh block, not create-merger or reject/replace.")))
    (when (lineage-max-depth-exhausted? root lineage-root max-depth)
      (exit! 2
             (str "Cannot create merger: merge lineage " lineage-root
                  " already exhausted max_merger_depth (" max-depth "). "
                  "Resolve the existing merge blocker; do not restart the chain.")))))

(defn original-merge-context [root blocked-assignment-id]
  (validate-id! "Blocked assignment id" blocked-assignment-id)
  (let [dir (assignment-dir root blocked-assignment-id)
        metadata (fs/path dir "metadata")
        status (fs/path dir "status")
        result (fs/path dir "result")]
    (ensure-assignment-dir! dir blocked-assignment-id)
    (when-not (= "merge_blocked" (read-value status "state"))
      (exit! 2 (str "Assignment is not merge_blocked: " blocked-assignment-id)))
    (ensure-merger-create-allowed! root blocked-assignment-id)
    {:dir dir
     :theme-id (read-value metadata "theme_id")
     :story-id (read-value metadata "story_id")
     :conflicting-template (read-value metadata "template")
     :conflicting-agent (read-value result "from")
     :conflicting-commit (read-value result "commit")}))

(defn create-merger-assignment! [{:keys [blocked-assignment-id assignment-id instructions-file queue-spawn?]}]
  (validate-id! "Merger assignment id" assignment-id)
  (let [root (fs/absolutize (project-root))
        original (original-merge-context root blocked-assignment-id)
        context (assignment-create-context {:theme-id (:theme-id original)
                                            :story-id (:story-id original)
                                            :template "merger"
                                            :assignment-id assignment-id
                                            :instructions-file instructions-file
                                            :queue-spawn? queue-spawn?})
        context (merge context
                       {:merge-for blocked-assignment-id
                        :conflicting-template (:conflicting-template original)
                        :conflicting-agent (:conflicting-agent original)
                        :conflicting-commit (:conflicting-commit original)
                        :merge-text (merge-source-text
                                     {:merge-for blocked-assignment-id
                                      :conflicting-template (:conflicting-template original)
                                      :conflicting-agent (:conflicting-agent original)
                                      :conflicting-commit (:conflicting-commit original)})})]
    (ensure-create-context! context)
    (let [assignment-file (write-assignment-records! context (assignment-text context))]
      (print-create-result! context assignment-file)
      (maybe-queue-spawn! context assignment-file))))

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

(defn validate-result-review-decision! [template review-decision]
  (cond
    (and (reviewer-template? template)
         (str/blank? review-decision))
    (exit! 2 "Review result handoff must include review_decision: accepted or changes-requested.")

    (and (not (reviewer-template? template))
         (not (str/blank? review-decision)))
    (exit! 2 "Only reviewer assignments may include review_decision.")

    (and (not (str/blank? review-decision))
         (not (contains? valid-review-decisions review-decision)))
    (exit! 2 "Review decision must be accepted or changes-requested.")))

(defn validate-result-manifest! [assignment-id template from manifest]
  (let [{handoff-assignment "assignment"
         handoff-agent "agent"
         handoff-template "template"
         artifacts "artifacts"
         review-decision "review_decision"} manifest]
    (when-not (= assignment-id handoff-assignment)
      (exit! 2 (str "Result manifest assignment must match assignment id: " assignment-id)))
    (when-not (= from handoff-agent)
      (exit! 2 "Result manifest agent must match handoff sender."))
    (when-not (= template handoff-template)
      (exit! 2 (str "Result manifest template must match assignment template: " template)))
    (when (str/blank? artifacts)
      (exit! 2 "Result manifest must include artifacts, or artifacts: none."))
    (validate-result-review-decision! template review-decision)
    manifest))

(defn validate-sender-assignment-lineage! [root assignment-id from]
  (let [agent-metadata (fs/path root ".squad" "agents" from "metadata")]
    (when (fs/exists? agent-metadata)
      (let [task-id (read-value agent-metadata "task_id")]
        (when-not (= assignment-id task-id)
          (exit! 2 (str "Result sender " from " is assigned to " task-id ", not " assignment-id)))
        true))))

(defn validate-result-handoff!
  ([assignment-id template handoff-file]
   (validate-result-handoff! (project-root) assignment-id template handoff-file))
  ([root assignment-id template handoff-file]
   (let [type (read-value handoff-file "type")
         to (read-value handoff-file "to")
         task (read-value handoff-file "task")
         commit (read-value handoff-file "commit")
         from (read-value handoff-file "from")
         manifest {"assignment" (read-value handoff-file "assignment")
                   "agent" (read-value handoff-file "agent")
                   "template" (read-value handoff-file "template")
                   "artifacts" (read-value handoff-file "artifacts")
                   "review_decision" (read-value handoff-file "review_decision")}]
     (validate-result-type! type)
     (validate-result-recipient! to)
     (validate-result-task! assignment-id task)
     (validate-result-commit! commit)
     (validate-result-sender! from)
     (validate-result-manifest! assignment-id template from manifest)
     {:from from
      :commit commit
      :manifest manifest
      :body (handoff-body handoff-file)})))

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

(defn accepted-merge-state [dir]
  (when (fs/regular-file? (fs/path dir "accepted-merge"))
    (read-value (fs/path dir "accepted-merge") "state")))

(defn assignment-already-merged? [dir]
  (or (= "merged" (read-value (fs/path dir "status") "state"))
      (= "merged" (accepted-merge-state dir))))

(defn resync-status! [dir assignment-id state detail now]
  "Update status only — used when replaying prior outcomes without re-logging events."
  (write-atomic! (fs/path dir "status")
                 (str "assignment_id: " assignment-id "\n"
                      "state: " state "\n"
                      "detail: " detail "\n"
                      "updated_at: " now "\n")))

(defn record-result! [assignment-id handoff-path]
  (validate-id! "Assignment id" assignment-id)
  (let [root (fs/absolutize (project-root))
        dir (assignment-dir root assignment-id)
        metadata (fs/path dir "metadata")
        template (read-value metadata "template")
        handoff-file (source-file! handoff-path)
        theme-id (or (read-value metadata "theme_id") "unknown")
        now (timestamp)]
    (ensure-assignment-dir! dir assignment-id)
    (when (assignment-already-merged? dir)
      (exit! 2
             (str "Cannot record result for assignment " assignment-id
                  ": already merged. Do not re-record results after accept-merge.")))
    (when (contains? #{"rejected" "blocked" "superseded" "cancelled" "abandoned"}
                     (read-value (fs/path dir "status") "state"))
      (exit! 2
             (str "Cannot record result for assignment " assignment-id
                  ": assignment is already terminal ("
                  (read-value (fs/path dir "status") "state") ").")))
    (let [{:keys [from commit body manifest]} (validate-result-handoff! root assignment-id template handoff-file)]
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
                        (when-let [decision (not-empty (get manifest "review_decision"))]
                          (str "review_decision: " decision "\n"))
                        "received_at: " now "\n"))
    (write-result-record! dir assignment-id from commit now)
    (when-not (= "unknown" theme-id)
      (assignment-theme-event! root dir "result_received" assignment-id from commit))
      (print-result-recorded! assignment-id from commit body))))

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

(defn existing-merge-evaluation [dir commit]
  "Return prior merge_ready/merge_blocked outcome for the same result commit, if any."
  (let [merge-file (fs/path dir "merge")
        prior-state (read-value merge-file "state")
        prior-commit (read-value merge-file "commit")
        prior-detail (read-value merge-file "detail")]
    (when (and (= commit prior-commit)
               (contains? #{"merge_ready" "merge_blocked"} prior-state))
      {:state prior-state
       :detail (or prior-detail
                   (if (= "merge_ready" prior-state)
                     "dry-run merge passed"
                     "dry-run merge failed"))})))

(defn replay-existing-merge-evaluation! [dir assignment-id commit {:keys [state detail]}]
  "Replay prior merge evaluation and re-sync status so handoff FSM cannot stick
  on result_received after status was overwritten (e.g. double result record)."
  (let [now (timestamp)]
    (resync-status! dir assignment-id state detail now)
    (if (= "merge_ready" state)
      (do
        (print-merge-ready! assignment-id commit detail)
        nil)
      (do
        (print-merge-blocked-ready! assignment-id commit)
        (System/exit 4)))))

(defn print-already-merged! [assignment-id commit]
  (println "SQUAD_ASSIGNMENT:" assignment-id)
  (println "STATE: merged")
  (println "COMMIT:" (or commit "unknown"))
  (println "DETAIL: assignment already merged"))

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
      (cond
        (assignment-already-merged? dir)
        (do
          (when-not (= "merged" (read-value (fs/path dir "status") "state"))
            (resync-status! dir assignment-id "merged" "assignment already merged" now))
          (print-already-merged! assignment-id commit))

        :else
        (if-let [prior (existing-merge-evaluation dir commit)]
          (replay-existing-merge-evaluation! dir assignment-id commit prior)
          (try
            (ensure-known-commit! root commit)
            (check-merge-ready! root dir assignment-id commit now)
            (finally
              (abort-merge! root))))))))

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
    (when-not (:durable? review-source)
      (exit! 2
             "Review decisions for worker assignments must use a durable review report under reviews/ (or legacy .squad/reviews/)."))    (write-atomic! (fs/path dir "review.md")
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

(defn mark-original-resolved-by-merger! [root merger-dir merger-assignment-id merger-commit merge-commit now]
  (let [metadata (fs/path merger-dir "metadata")
        original-id (read-value metadata "merge_for")]
    (when-not (str/blank? original-id)
      (let [original-dir (assignment-dir root original-id)
            original-metadata (fs/path original-dir "metadata")
            original-commit (or (read-value metadata "conflicting_commit") merger-commit)
            detail (str "resolved by merger assignment " merger-assignment-id)]
        (ensure-assignment-dir! original-dir original-id)
        (when (= "merger" (read-value original-metadata "template"))
          (mark-original-resolved-by-merger! root original-dir original-id merger-commit merge-commit now))
        (fs/delete-if-exists (fs/path original-dir "blocker"))
        (fs/delete-if-exists (fs/path original-dir "blocker.md"))
        (write-atomic! (fs/path original-dir "accepted-merge")
                       (str "assignment_id: " original-id "\n"
                            "state: merged\n"
                            "commit: " original-commit "\n"
                            "merge_commit: " merge-commit "\n"
                            "resolved_by: " merger-assignment-id "\n"
                            "detail: " detail "\n"
                            "updated_at: " now "\n"))
        (write-atomic! (fs/path original-dir "merge")
                       (str "assignment_id: " original-id "\n"
                            "state: merged\n"
                            "commit: " merger-commit "\n"
                            "detail: " detail "\n"
                            "updated_at: " now "\n"))
        (write-atomic! (fs/path original-dir "status")
                       (str "assignment_id: " original-id "\n"
                            "state: merged\n"
                            "detail: " detail "\n"
                            "updated_at: " now "\n"))
        (append-line! (fs/path original-dir "events.log")
                      (str now "\tmerged_by_merger\t" merger-assignment-id "\t" merge-commit))
        (assignment-theme-event! root original-dir "merged" original-id merger-assignment-id merge-commit)))))

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

(defn untracked-path? [root path]
  (let [tracked (sh-at root "git" "ls-files" "--error-unmatch" "--" path)]
    (not (zero? (:exit tracked)))))

(defn clear-colliding-untracked-reviews!
  "Remove untracked local review artifacts that match the incoming commit so
  git merge is not blocked by materialised review copies in the root worktree."
  [root commit]
  (doseq [path (review-paths-in-commit root commit)]
    (let [file (fs/path root path)]
      (when (and (fs/regular-file? file) (untracked-path? root path))
        (let [incoming (sh-at root "git" "show" (str commit ":" path))]
          (when (and (zero? (:exit incoming))
                     (= (slurp (str file)) (:out incoming)))
            (fs/delete-if-exists file)))))))

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
        (clear-colliding-untracked-reviews! root commit)
        (let [detail (merge-detail! root dir assignment-id commit now)
              merge-commit (record-accepted-merge! root dir assignment-id commit detail now)]
          (mark-original-resolved-by-merger! root dir assignment-id commit merge-commit now)
          (print-merge-accepted! assignment-id commit merge-commit detail))
        (finally
          (abort-merge! root))))))

(defn ensure-not-max-depth-merge-escape! [root assignment-id action]
  "At max_merger_depth, merge_blocked work must hard-block — not reject/replace rework."
  (let [max-depth (cfg/squad-max-merger-depth root)
        depth (merge-suffix-depth assignment-id)
        state (read-value (fs/path root ".squad" "assignments" assignment-id "status") "state")
        lineage-root (merge-lineage-root assignment-id)]
    (when (and (= "merge_blocked" state)
               (or (>= depth max-depth)
                   (lineage-max-depth-exhausted? root lineage-root max-depth)))
      (exit! 2
             (str "Cannot " action " assignment " assignment-id
                  ": merge recovery is at max_merger_depth (" max-depth "). "
                  "Use squad_assign.sh block (declare_merge_blocker), not reject/replace.")))))

(defn archive-rejection! [root assignment-id reason-text]
  (let [archive (fs/path root ".squad" "rejections" (str assignment-id ".md"))]
    (write-atomic! archive reason-text)
    archive))

(defn write-rejection-blocker! [dir assignment-id now]
  "Mirror rejection into blocker files so the dashboard Blockers panel surfaces it."
  (write-atomic! (fs/path dir "blocker")
                 (str "assignment_id: " assignment-id "\n"
                      "state: blocked\n"
                      "kind: assignment-rejection\n"
                      "reason_file: " (fs/path dir "rejection.md") "\n"
                      "updated_at: " now "\n"))
  (when (fs/regular-file? (fs/path dir "rejection.md"))
    (write-atomic! (fs/path dir "blocker.md")
                   (slurp (str (fs/path dir "rejection.md"))))))

(defn reject-assignment! [assignment-id reason-path]
  (validate-id! "Assignment id" assignment-id)
  (let [root (fs/absolutize (project-root))
        dir (assignment-dir root assignment-id)
        reason-source (source-file! reason-path)
        reason-text (slurp (str reason-source))
        now (timestamp)]
    (ensure-assignment-dir! dir assignment-id)
    (ensure-not-max-depth-merge-escape! root assignment-id "reject")
    (write-atomic! (fs/path dir "rejection.md") reason-text)
    (write-atomic! (fs/path dir "rejection")
                   (str "assignment_id: " assignment-id "\n"
                        "state: rejected\n"
                        "reason_file: " (fs/path dir "rejection.md") "\n"
                        "updated_at: " now "\n"))
    (write-rejection-blocker! dir assignment-id now)
    (archive-rejection! root assignment-id reason-text)
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
    (println "REJECTION:" (str (fs/path dir "rejection.md")))
    (println "BLOCKER:" (str (fs/path dir "blocker.md")))))

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
    (ensure-not-max-depth-merge-escape! root old-assignment-id "replace")
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
   "create-merger" (fn [args] (create-merger-assignment! (parse-create-merger-args! args)))
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
