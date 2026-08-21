#!/usr/bin/env bb

(ns squad-assign
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-config :as cfg]
            [squad-lease :as lease]
            [squad-product :as product]
            [squad-tool-table :as tools]
            [squad-transition :as transition]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def usage-text
  (str "Usage:\n"
       "  squad_assign.sh create <story-id> <template> <assignment-id> <instructions-file|--auto-instructions> [--requires approval:<gate>] [--queue-spawn]\n"
       "  squad_assign.sh create-batch <template> <assignment-id> <instructions-file|--auto-instructions> [--requires approval:<gate>] [--queue-spawn]\n"
       "  squad_assign.sh create-product <template> <assignment-id> <instructions-file|--auto-instructions> [--queue-spawn]\n"
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

(defn apply-create-option [options token rest-tokens]
  (case token
    "--requires"
    (let [requirement (first rest-tokens)]
      (when-not requirement
        (exit! 1 usage-text))
      [(assoc options :requirement (parse-requirement! requirement))
       (next rest-tokens)])

    "--queue-spawn"
    [(assoc options :queue-spawn? true) rest-tokens]

    (exit! 1 usage-text)))

(defn parse-create-options! [tokens]
  (loop [tokens tokens
         options {:requirement nil :queue-spawn? false}]
    (if (empty? tokens)
      options
      (let [[options tokens] (apply-create-option options (first tokens) (rest tokens))]
        (recur tokens options)))))

(defn parse-create-args! [args]
  (when-not (>= (count args) 5)
    (exit! 1 usage-text))
  (let [[_ story-id template assignment-id instructions-file & option-tokens] args]
    (merge {:story-id story-id
            :template template
            :assignment-id assignment-id
            :instructions-file instructions-file}
           (parse-create-options! option-tokens))))

(defn parse-create-batch-args! [args]
  (when-not (>= (count args) 4)
    (exit! 1 usage-text))
  (let [[_ template assignment-id instructions-file & option-tokens] args]
    (merge {:story-id "batch"
            :template template
            :assignment-id assignment-id
            :instructions-file instructions-file
            :scope "batch"}
           (parse-create-options! option-tokens))))

(defn parse-create-product-args! [args]
  (when-not (>= (count args) 4)
    (exit! 1 usage-text))
  (let [[_ template assignment-id instructions-file & option-tokens] args]
    (merge {:story-id nil
            :template template
            :assignment-id assignment-id
            :instructions-file instructions-file
            :scope "product"}
           (parse-create-options! option-tokens))))

(def valid-review-decisions
  {"accepted" "review_accepted"
   "changes-requested" "review_changes_requested"})

(def reviewer-templates
  #{"code-reviewer"
    "architect"})

(defn reviewer-template? [template]
  (contains? reviewer-templates template))

(defn review-state! [decision]
  (or (valid-review-decisions decision)
      (exit! 2 "Review decision must be accepted or changes-requested.")))

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

(defn product-scoped-assignment? [scope template]
  (or (= "product" scope)
      (= "system-analyst" template)))

(defn tool-lines [label tools]
  (when (seq tools)
    (str "## " label "\n\n"
         (apply str
                (for [{:keys [name source version purpose]} tools]
                  (str "- " name
                       (when purpose (str " (" purpose ")"))
                       ": `squad_tool.sh require " name " " source " " version "`\n")))
         "\n")))

(defn tool-startup-lines [template tools]
  (tools/startup-instructions tools
                              (tools/verification-prerequisites
                               (cfg/project-root)
                               template)))

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

(defn other-backlog-titles [root story-id]
  (let [dir (fs/path root ".squad" "backlog")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".item")))
           (keep (fn [file]
                   (let [title (read-value file "title")
                         status (or (read-value file "status") "open")
                         item-story (read-value file "story_id")]
                     (when (and (not (str/blank? title))
                                (not= "mission" status)
                                (or (str/blank? story-id)
                                    (not= story-id item-story)))
                       title))))
           sort
           vec)
      [])))

(defn item-body [file]
  (let [text (slurp (str file))
        marker "body: |\n"]
    (when-let [idx (str/index-of text marker)]
      (->> (subs text (+ idx (count marker)))
           str/split-lines
           (map #(if (str/starts-with? % "  ") (subs % 2) %))
           (str/join "\n")
           str/trim))))

(defn mission-file? [file]
  (and (fs/regular-file? file)
       (str/ends-with? (fs/file-name file) ".item")
       (= "mission" (read-value file "status"))))

(defn mission-section [root]
  (when root
    (let [dir (fs/path root ".squad" "backlog")]
      (when (fs/directory? dir)
        (when-let [file (first (filter mission-file? (fs/list-dir dir)))]
          (when-let [body (not-empty (item-body file))]
            (str "## Mission\n\n" body "\n\n")))))))

(defn non-goals-section [titles]
  (when (seq titles)
    (str "## Non-goals (other backlog items)\n\n"
         (apply str (for [title titles]
                      (str "- " title "\n")))
         "Copy these names into plan.md non-goals. Read `.squad/backlog/<id>.item` only if a port needs a sentence.\n\n")))

(defn open-story-file? [file]
  (and (fs/regular-file? file)
       (str/ends-with? (fs/file-name file) ".item")
       (let [status (or (read-value file "status") "open")]
         (or (str/blank? status) (= "open" status)))))

(defn stories-section [root]
  (when root
    (let [dir (fs/path root ".squad" "backlog")]
      (when (fs/directory? dir)
        (let [blocks (for [file (->> (fs/list-dir dir)
                                     (filter open-story-file?)
                                     (sort-by str))]
                       (let [title (or (not-empty (read-value file "title"))
                                       (fs/file-name file))
                             body (or (item-body file) "")]
                         (str "### " title "\n\n" body "\n\n")))]
          (when (seq blocks)
            (str "## Stories\n\n" (apply str blocks))))))))

(defn backlog-titles-section [scope template root titles]
  (if (or (= "product" scope) (= "system-analyst" template))
    (stories-section root)
    (non-goals-section titles)))

(defn frame-run-line [p]
  (let [run (get p "run")]
    (if (str/blank? run) "see frame.md" run)))

(defn frame-section [root]
  (when root
    (let [p (product/read-product root)]
      (when (product/frame-ready? p)
        (str "## Frame\n\n"
             "The product frame is already on master (`frame.md`). Extend that one executable and UI. Do not add a second -main or probe app. QA-proc writers edit `qa/product.md` placeholders.\n\n"
             "Run: " (frame-run-line p) "\n\n")))))

(defn render-assignment [{:keys [root theme-id story-id template assignment-id scope theme-text module-map-text story-text instructions-text requirement packet-text required-tools optional-tools required-evidence other-backlog-titles]}]
  (str "# Squad Assignment\n\n"
       "assignment_id: " assignment-id "\n"
       (when (and theme-id (not (str/blank? theme-id)) (not= "none" theme-id))
         (str "theme_id: " theme-id "\n"))
       "scope: " scope "\n"
       (when-not (str/blank? story-id)
         (str "story_id: " story-id "\n"))
       "template: " template "\n"
       (when requirement
         (str "requires: " (:text requirement) "\n"))
       "\n"
       (when (and theme-text (not (str/blank? theme-text))
                  (not (str/starts-with? theme-text "No theme")))
         (str "## Context\n\n" theme-text "\n\n"))
       (when module-map-text
         (str "## Module Map\n\n"
              module-map-text "\n\n"))
       (when story-text
         (str "## Story\n\n"
              story-text "\n\n"))
       (when packet-text
         (str "## Story Packet\n\n"
              "```text\n"
              packet-text
              "```\n\n"))
       (frame-section root)
       (when (or (= "product" scope) (= "system-analyst" template))
         (mission-section root))
       (backlog-titles-section scope template root other-backlog-titles)
       (tool-lines "Required Tools" required-tools)
       (tool-lines "Optional Tools" optional-tools)
       (tool-startup-lines template required-tools)
       (tool-evidence-lines required-evidence)
       "## Leader Instructions\n\n"
       instructions-text "\n\n"
       "## Required Transient Protocol\n\n"
       "- Stay inside this assignment boundary.\n"
       (if (or (= "product" scope) (= "system-analyst" template))
         "- The mission and stories are in this document. Do not search for a backlog or stories directory.\n"
         (str "- The story is in this document. Write `.squad/stories/" story-id "/plan.md` only if you are the analyst. Do not search for a stories directory.\n"))
       "- Use `squad_event.sh` only with lifecycle states: starting, running, blocked, failed, handoff_ready, handoff_sent. Do not self-retire; after handoff report handoff_sent and leave retirement to squad_retire.sh after the Squad Leader resolves the workflow. Put phase names and progress wording in the detail argument, not the state.\n"
       "- Commit completed work on your transient branch.\n"
       "- After commit, send the result with `swarm_handoff.sh` (no file).\n"))

(defn themeless-theme? [theme-id]
  (or (str/blank? theme-id) (= "none" theme-id)))

(defn validate-create-ids! [theme-id story-id assignment-id]
  (when-not (themeless-theme? theme-id)
    (validate-id! "Theme id" theme-id))
  (when-not (str/blank? story-id)
    (validate-id! "Story id" story-id))
  (validate-id! "Assignment id" assignment-id))

(defn first-existing-file [files]
  (first (filter fs/regular-file? files)))

(defn assignment-story-file [root story-id skip-story?]
  (when-not skip-story?
    (first-existing-file [(fs/path root "stories" (str story-id ".md"))])))

(defn assignment-scope [{:keys [template story-id scope]}]
  (cond
    (theme-scoped-assignment? template story-id) "theme"
    (batch-scoped-assignment? scope story-id) "batch"
    (product-scoped-assignment? scope template) "product"
    :else "story"))

(defn story-file-required? [scope]
  (= "story" scope))

(defn assignment-scope-flags [scope]
  {:theme-scoped? (= "theme" scope)
   :batch-scoped? (= "batch" scope)})

(defn assignment-create-context [{:keys [theme-id story-id template assignment-id instructions-file requirement scope queue-spawn?]}]
  (let [root (fs/absolutize (project-root))
        resolved-scope (assignment-scope {:template template :story-id story-id :scope scope})
        scope-flags (assignment-scope-flags resolved-scope)
        auto-instructions? (auto-instructions? instructions-file)]
    {:root root
     :theme-id theme-id
     :story-id story-id
     :template template
     :assignment-id assignment-id
     :requirement requirement
     :queue-spawn? queue-spawn?
     :theme-scoped? (:theme-scoped? scope-flags)
     :batch-scoped? (:batch-scoped? scope-flags)
     :scope resolved-scope
     :story-file (when (story-file-required? resolved-scope)
                   (assignment-story-file root story-id false))
     :template-file (fs/path root "swarmforge" "role-templates" (str template ".prompt"))
     :instructions (when-not auto-instructions?
                     (source-file! instructions-file))
     :auto-instructions? auto-instructions?
     :dir (assignment-dir root assignment-id)
     :contract (role-contract root template)
     :packet (when-not (str/blank? story-id)
               (optional-story-packet root story-id))
     :now (timestamp)}))

(defn packet-theme-id [packet]
  (when packet (read-value packet "theme_id")))

(defn mismatched-packet-theme? [packet theme-id]
  (let [packet-theme (packet-theme-id packet)]
    (and (not (str/blank? packet-theme))
         (not= theme-id packet-theme))))

(defn ensure-packet-theme! [{:keys [packet theme-id story-id]}]
  (when (mismatched-packet-theme? packet theme-id)
    (exit! 2
           (str "Story packet " story-id " belongs to a different theme."))))

(defn ensure-story-file! [scope story-file]
  (when (story-file-required? scope)
    (ensure-file! "Story file not found" story-file)))

(defn ensure-create-context! [{:keys [scope story-file template-file dir] :as context}]
  (ensure-story-file! scope story-file)
  (ensure-file! "Role template not found" template-file)
  (ensure-packet-theme! context)
  (when (fs/exists? dir)
    (exit! 2 (str "Assignment already exists: " (:assignment-id context)))))

(defn default-instructions [{:keys [template story-id scope]}]
  (cond
    (= "senior-implementer" template)
    (str "Apply only the architecture review findings for this " scope " assignment.\n"
         "Lead with reviews/*-architecture-review.md (or the critique path in this package).\n"
         "Do not greenfield-rebuild modules the review accepted; preserve working process/domain code.\n"
         "Skip Module Map Recommendations unless the squad leader explicitly assigned a map-only chore.\n"
         "Implement the listed code/structure/acceptance recommendations, verify with bb test and bb acceptance, hand off.\n")

    (or (= "product" scope) (= "system-analyst" template))
    (str "Follow the " template " role contract for this product assignment.\n"
         "Produce frame.md, qa/product.md, and the one executable. "
         "You may edit bb.edn or deps.edn only as needed to run that executable. "
         "Do not write plan.md.\n"
         "Commit the work, then run swarm_handoff.sh (no file).\n")

    :else
    (str "Follow the " template " role contract for this " scope " assignment.\n"
         "Use the story in this assignment and the role prompt as the source of truth.\n"
         "Produce the required artifact for " story-id ", commit the work, then run swarm_handoff.sh (no file).\n")))

(defn assignment-instructions-text [context]
  (if (:auto-instructions? context)
    (default-instructions context)
    (slurp (str (:instructions context)))))

(defn module-map-text-for [context]
  (when-let [module-map-file (:module-map-file context)]
    (when (and (include-module-map? (:template context))
               (fs/regular-file? module-map-file))
      (slurp (str module-map-file)))))

(defn strip-module-map-recommendations
  "Map commentary is not senior-implementer work."
  [text]
  (str/trim
   (str/replace (str text)
                #"(?ms)^#{1,6}\s+Module Map Recommendations\b.*?(?=^#{1,6} |\z)"
                "")))

(defn architecture-review-text
  "Surface architect critique for senior-implementer assignments.
  Drop Module Map Recommendations so they are not assigned work."
  [{:keys [root theme-id assignment-id]}]
  (let [dir (fs/path root "reviews")
        named (cond-> []
                (not (themeless-theme? theme-id))
                (conj (fs/path dir (str theme-id "-architecture-review.md")))
                true
                (conj (fs/path dir (str assignment-id "-review.md")))
                true
                (conj (fs/path dir "htw-architecture-review.md")))
        glob (if (fs/directory? dir)
               (->> (fs/list-dir dir)
                    (filter #(re-find #"architecture-review\.md$" (str (fs/file-name %))))
                    (sort-by str))
               [])
        hit (first (filter fs/regular-file? (concat named glob)))]
    (when hit
      (str "Source: " hit "\n\n" (strip-module-map-recommendations (slurp (str hit)))))))

(defn assignment-text [context]
  (let [base {:theme-text (let [theme-file (:theme-file context)]
                            (if (and theme-file (fs/regular-file? theme-file))
                              (slurp (str theme-file))
                              "No theme. Work this story only.\n"))
              :module-map-text (module-map-text-for context)
              :story-text (when-let [story-file (:story-file context)]
                            (slurp (str story-file)))
              :instructions-text (assignment-instructions-text context)
              :packet-text (when-let [packet (:packet context)]
                             (slurp (str packet)))
              :required-tools (tools/required-tools (:root context) (:template context))
              :optional-tools (tools/optional-tools (:root context) (:template context))
              :required-evidence (tools/required-evidence (:root context) (:template context))
              :other-backlog-titles (other-backlog-titles (:root context) (:story-id context))}
        review (when (= "senior-implementer" (:template context))
                 (architecture-review-text context))]
    (if review
      ;; Findings before full theme dump
      (str "# Squad Assignment\n\n"
           "assignment_id: " (:assignment-id context) "\n"
           (when-not (themeless-theme? (:theme-id context))
             (str "theme_id: " (:theme-id context) "\n"))
           "scope: " (:scope context) "\n"
           "story_id: " (:story-id context) "\n"
           "template: senior-implementer\n\n"
           "## Architecture findings (work order)\n\n"
           review "\n\n"
           "## Leader Instructions\n\n"
           (:instructions-text base) "\n\n"
           (when (and (:theme-text base)
                      (not (str/starts-with? (str (:theme-text base)) "No theme")))
             (str "## Context\n\n" (:theme-text base) "\n\n"))
           (when (:module-map-text base)
             (str "## Module Map (context)\n\n" (:module-map-text base) "\n\n"))
           (tool-lines "Required Tools" (:required-tools base))
           (tool-startup-lines "senior-implementer" (:required-tools base))
           (tool-evidence-lines (:required-evidence base))
           "## Required Transient Protocol\n\n"
           "- Stay inside this assignment boundary.\n"
           "- Apply architecture findings only; do not rewrite healthy modules.\n"
           "- Skip Module Map Recommendations unless the squad leader assigned a map-only chore.\n"
           "- Use `squad_event.sh` only with lifecycle states: starting, running, blocked, failed, handoff_ready, handoff_sent.\n"
           "- Commit completed work on your transient branch.\n"
           "- After commit, send the result with `swarm_handoff.sh` (no file).\n")
      (render-assignment (merge context base)))))
(defn assignment-metadata-text [{:keys [assignment-id theme-id scope story-id template requirement assignment-file now batch-id]}]
  (str "assignment_id: " assignment-id "\n"
       (when-not (themeless-theme? theme-id)
         (str "theme_id: " theme-id "\n"))
       "scope: " scope "\n"
       (when-not (str/blank? story-id)
         (str "story_id: " story-id "\n"))
       "template: " template "\n"
       (when requirement
         (str "requires: " (:text requirement) "\n"))
       (when (or batch-id (= "batch" story-id) (= "batch" scope))
         (str "batch_id: " (or batch-id assignment-id) "\n"))
       "assignment_file: " assignment-file "\n"
       "created_at: " now "\n"))

(defn assignment-status-text [{:keys [assignment-id template story-id now]}]
  (str "assignment_id: " assignment-id "\n"
       "state: created\n"
       "detail: " template " for " story-id "\n"
       "updated_at: " now "\n"))

(defn write-assignment-records! [{:keys [dir assignment-id template story-id now] :as context} text]
  (fs/create-dirs dir)
  (let [assignment-file (fs/path dir "assignment.md")
        context (assoc context :assignment-file assignment-file)]
    (write-atomic! assignment-file text)
    (write-atomic! (fs/path dir "result-handoff.draft")
                   (result-handoff-template assignment-id template))
    (write-atomic! (fs/path dir "metadata") (assignment-metadata-text context))
    (write-atomic! (fs/path dir "status") (assignment-status-text context))
    assignment-file))

(defn print-create-result! [{:keys [assignment-id theme-id story-id template requirement]} assignment-file]
  (println "SQUAD_ASSIGNMENT:" assignment-id)
  (when-not (themeless-theme? theme-id)
    (println "THEME:" theme-id))
  (when-not (str/blank? story-id)
    (println "STORY:" story-id))
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
   "architect" "architecture"
   "senior-implementer" "architecture-fix"})

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
  (let [theme (field-value metadata "theme_id" nil)]
    (cond-> [["ASSIGNMENT" assignment-id]]
      (not (themeless-theme? theme))
      (conj ["THEME" theme])
      true
      (into [["STORY" (field-value metadata "story_id" "unknown")]
             ["TEMPLATE" (field-value metadata "template" "unknown")]
             ["STATE" (field-value status "state" "unknown")]
             ["DETAIL" (field-value status "detail" "")]
             ["ASSIGNMENT_FILE" (field-value metadata "assignment_file" "unknown")]]))))

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

(defn validate-system-analyst-artifacts! [template artifacts]
  (when (= "system-analyst" template)
    (let [paths (set (split-list artifacts))
          missing (filterv #(not (contains? paths %)) ["frame.md" "qa/product.md"])]
      (when (seq missing)
        (exit! 2 (str "System-analyst result must include " (str/join ", " missing) "."))))))

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
    (validate-system-analyst-artifacts! template artifacts)
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
    (print-result-recorded! assignment-id from commit body))))

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
                (str now "\t" state "\t" commit "\t" detail)))

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

(def merge-lock-retry-max-attempts
  "Total tries including the first (4 retries after failure → 5 attempts)."
  5)

(def merge-lock-retry-base-ms 200)
(def merge-lock-retry-cap-ms 2000)

(def main-git-lock-timeout-ms 60000)
(def main-git-lock-poll-ms 50)

(defn main-git-owner
  "Who may mutate main repo merge-ready. Default daemon (squadd)."
  []
  (or (not-empty (System/getenv "SWARMFORGE_MAIN_GIT_OWNER")) "daemon"))

(defn main-git-allowed?
  "True when this process is the main-git owner (squadd sets these for mechanical apply)."
  []
  (or (= "1" (System/getenv "SWARMFORGE_MAIN_GIT"))
      (= "squadd" (System/getenv "SWARMFORGE_ROLE"))))

(defn squad-leader-role? []
  (= "squad-leader" (System/getenv "SWARMFORGE_ROLE")))

(defn accept-merge-allowed? []
  (or (main-git-allowed?)
      (squad-leader-role?)
      (= "any" (main-git-owner))))

(defn ensure-main-git-owner!
  "Reject merge-ready unless caller is the daemon. accept-merge is SL's."
  [op]
  (if (= "accept-merge" op)
    (when-not (accept-merge-allowed?)
      (exit! 3
             "MAIN_GIT_OWNER: squad-leader (or squadd) may run accept-merge"
             "Set SWARMFORGE_ROLE=squad-leader, or SWARMFORGE_MAIN_GIT=1 from tests."))
    (when (and (= "daemon" (main-git-owner))
               (not (main-git-allowed?)))
      (exit! 3
             (str "MAIN_GIT_OWNER: only squadd may run " op)
             "Set SWARMFORGE_MAIN_GIT=1 (or SWARMFORGE_ROLE=squadd) from the daemon, or SWARMFORGE_MAIN_GIT_OWNER=any for tests."))))

(defn main-git-lock-dir [root]
  (lease/lease-dir root "main-git"))

(defn with-main-git-lock
  "Serialize accept-merge under shared lease."
  [root f]
  (try
    (lease/with-lease root "main-git"
                      {:timeout-ms main-git-lock-timeout-ms
                       :poll-ms main-git-lock-poll-ms
                       :timeout-message
                       (str "Timed out waiting for main-git lock: "
                            (lease/lease-dir root "main-git")
                            "\nIf no squadd merge-ready/accept-merge is running, remove the stale lock and retry.")}
                      f)
    (catch clojure.lang.ExceptionInfo e
      (exit! 2 (ex-message e)
             (or (get (ex-data e) :lease) "")))))

(defn transient-git-lock-error?
  "True when git failed in a way that is safe to retry (ref/lock races, EPERM on lock create)."
  [result]
  (let [text (str (:err result) "\n" (:out result))]
    (boolean
     (or (re-find #"(?i)ORIG_HEAD\.lock" text)
         (re-find #"(?i)Operation not permitted" text)
         (re-find #"(?i)Unable to create '.*\.lock'" text)
         (re-find #"(?i)cannot lock ref" text)
         (re-find #"(?i)index\.lock" text)))))

(defn sleep-ms! [ms]
  (when (and ms (pos? ms))
    (Thread/sleep (long ms))))

(defn random-merge-lock-backoff-ms
  "Full jitter exponential backoff: uniform random in [0, min(cap, base * 2^attempt)].
  attempt is 0-based for the first retry after the initial failure."
  [attempt]
  (let [exp (bit-shift-left 1 (min attempt 4))
        ceiling (min merge-lock-retry-cap-ms (* merge-lock-retry-base-ms exp))]
    (long (rand-int (inc ceiling)))))

(defn log-merge-lock-retry! [label attempt delay-ms result]
  (let [snippet (-> (str (:err result) " " (:out result))
                    (str/replace #"\s+" " ")
                    (str/trim)
                    (#(if (> (count %) 160) (str (subs % 0 160) "…") %)))]
    (binding [*out* *err*]
      (println "MERGE_LOCK_RETRY:"
               (str "label=" label)
               (str "attempt=" attempt)
               (str "next_delay_ms=" delay-ms)
               (str "exit=" (:exit result))
               (str "detail=" (pr-str snippet))))))

(defn log-merge-lock-retry-outcome! [label attempt result]
  (binding [*out* *err*]
    (if (zero? (:exit result))
      (when (pos? attempt)
        (println "MERGE_LOCK_RETRY_OK:"
                 (str "label=" label)
                 (str "attempts=" (inc attempt))))
      (when (and (pos? attempt) (transient-git-lock-error? result))
        (println "MERGE_LOCK_RETRY_EXHAUSTED:"
                 (str "label=" label)
                 (str "attempts=" (inc attempt))
                 (str "exit=" (:exit result)))))))

(defn git-with-lock-retry
  "Run (thunk) which must return a process result map {:exit :out :err}.
  Retry on transient-git-lock-error? with full-jitter exponential backoff.
  Logs each retry and final success-after-retry / exhaustion to stderr."
  ([thunk] (git-with-lock-retry "git" thunk))
  ([label thunk]
   (loop [attempt 0]
     (let [result (thunk)]
       (if (or (zero? (:exit result))
               (not (transient-git-lock-error? result))
               (>= (inc attempt) merge-lock-retry-max-attempts))
         (do
           (log-merge-lock-retry-outcome! label attempt result)
           result)
         (let [delay-ms (random-merge-lock-backoff-ms attempt)]
           (log-merge-lock-retry! label (inc attempt) delay-ms result)
           (sleep-ms! delay-ms)
           (recur (inc attempt))))))))

(defn print-merge-ready! [assignment-id commit detail]
  (println "SQUAD_ASSIGNMENT:" assignment-id)
  (println "STATE: merge_ready")
  (println "COMMIT:" commit)
  (println "DETAIL:" detail))

(defn mark-merge-ready-state! [root dir assignment-id commit now detail]
  (write-merge-state! root dir assignment-id "merge_ready" detail commit now)
  (print-merge-ready! assignment-id commit detail))

(defn check-merge-ready! [root dir assignment-id commit now]
  (if (ancestor-commit? root commit)
    (mark-merge-ready-state! root dir assignment-id commit now "commit already reachable from HEAD")
    (mark-merge-ready-state! root dir assignment-id commit now "ready to accept-merge")))

(defn dirt-defer-detail? [detail]
  "Transient main dirt is not a durable merge evaluation."
  (and detail (str/includes? (str detail) "tracked checkout dirty")))

(defn existing-merge-evaluation [dir commit]
  "Return prior merge_ready/merge_blocked outcome for the same result commit, if any.
  Do not replay tracked-checkout-dirty as a permanent merge_blocked."
  (let [merge-file (fs/path dir "merge")
        prior-state (read-value merge-file "state")
        prior-commit (read-value merge-file "commit")
        prior-detail (read-value merge-file "detail")]
    (when (and (= commit prior-commit)
               (= "merge_ready" prior-state)
               (not (dirt-defer-detail? prior-detail)))
      {:state prior-state
       :detail (or prior-detail "ready to accept-merge")})))

(defn replay-existing-merge-evaluation! [dir assignment-id commit {:keys [state detail]}]
  "Replay prior merge_ready so handoff FSM cannot stick on result_received."
  (let [now (timestamp)]
    (resync-status! dir assignment-id state detail now)
    (print-merge-ready! assignment-id commit detail)
    nil))

(defn print-already-merged! [assignment-id commit]
  (println "SQUAD_ASSIGNMENT:" assignment-id)
  (println "STATE: merged")
  (println "COMMIT:" (or commit "unknown"))
  (println "DETAIL: assignment already merged"))

(defn mark-merge-ready! [assignment-id]
  (validate-id! "Assignment id" assignment-id)
  (ensure-main-git-owner! "merge-ready")
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
          (with-main-git-lock
            root
            (fn []
              (try
                (ensure-known-commit! root commit)
                (check-merge-ready! root dir assignment-id commit now)
                (finally
                  (abort-merge! root))))))))))

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
    (println "SQUAD_ASSIGNMENT:" assignment-id)
    (println "STATE: blocked")
    (println "BLOCKER:" (str (fs/path dir "blocker.md")))))

(defn record-accepted-merge! [root dir assignment-id commit detail now]
  "Durable accept-merge success via transition apply (multi-file side effects)."
  (let [head (str/trim (:out (sh-at root "git" "rev-parse" "--short=10" "HEAD")))
        metadata (fs/path dir "metadata")
        theme-id (read-value metadata "theme_id")
        story-id (read-value metadata "story_id")]
    (transition/apply-transition!
     root :accept-merge
     {:assignment-id assignment-id
      :commit commit
      :merge-commit head
      :detail detail
      :now now
      :theme-id theme-id
      :story-id story-id})
    head))

(defn result-commit! [result-file]
  (let [commit (read-value result-file "commit")]
    (when-not (and commit (re-matches #"[0-9a-fA-F]{10}" commit))
      (exit! 2 "Assignment result must contain a 10-character commit."))
    commit))

(defn print-merge-failed! [assignment-id commit detail]
  (binding [*out* *err*]
    (println "SQUAD_ASSIGNMENT:" assignment-id)
    (println "STATE: merge_failed")
    (println "COMMIT:" commit)
    (println "DETAIL:" detail))
  (System/exit 4))

(defn fail-merge! [root dir assignment-id phase detail commit now result]
  (when result
    (write-merge-error! dir phase result))
  (print-merge-failed! assignment-id commit detail))

(defn merge-detail! [root dir assignment-id commit now]
  (let [ancestor (sh-at root "git" "merge-base" "--is-ancestor" commit "HEAD")]
    (if (zero? (:exit ancestor))
      "commit already reachable from HEAD"
      (let [merge (git-with-lock-retry
                   (str "accept-merge:" assignment-id)
                   #(sh-at root "git" "merge" "--no-ff" "-m"
                           (str "Merge squad assignment " assignment-id) commit))]
        (when-not (zero? (:exit merge))
          (abort-merge! root)
          (fail-merge! root dir assignment-id "accept-merge" "accepted merge failed" commit now merge))
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
  (ensure-main-git-owner! "accept-merge")
  (let [root (fs/absolutize (project-root))
        dir (assignment-dir root assignment-id)
        result-file (fs/path dir "result")
        now (timestamp)]
    (ensure-assignment-dir! dir assignment-id)
    (ensure-file! "Assignment result not found" result-file)
    (let [commit (result-commit! result-file)]
      (with-main-git-lock
        root
        (fn []
          (try
            ;; Transient dirty main soft-defers; leave merge_ready for retry.
            ;; Do not write merge_blocked / spawn merger recovery for dirt alone.
            (if (tracked-dirty? root)
              (do
                (resync-status! dir assignment-id "merge_ready"
                                "tracked checkout dirty; defer accept until main is clean" now)
                (binding [*out* *err*]
                  (println "SQUAD_ASSIGNMENT:" assignment-id)
                  (println "STATE: merge_deferred")
                  (println "COMMIT:" commit)
                  (println "DETAIL: tracked checkout dirty; retry when main is clean"))
                (System/exit 5))
              (do
                (clear-colliding-untracked-reviews! root commit)
                (let [detail (merge-detail! root dir assignment-id commit now)
                      merge-commit (record-accepted-merge! root dir assignment-id commit detail now)]
                  (print-merge-accepted! assignment-id commit merge-commit detail))))
            (finally
              (abort-merge! root))))))))

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
    (when-not story-id
      (exit! 2 "Original assignment metadata must include story_id."))
    (create-assignment! {:theme-id theme-id
                         :story-id story-id
                         :template template
                         :assignment-id new-assignment-id
                         :instructions-file instructions-file
                         :requirement (parse-requirement! requirement-text)})
    (let [new-dir (assignment-dir root new-assignment-id)
          old-batch-id (or (read-value old-metadata "batch_id")
                           (when (= "batch" story-id) old-assignment-id))]
      (append-file! (fs/path new-dir "metadata")
                    (str "replaces: " old-assignment-id "\n"
                         (when old-batch-id
                           (str "batch_id: " old-batch-id "\n"))))
      (write-atomic! (fs/path new-dir "replaces")
                     (str "assignment_id: " new-assignment-id "\n"
                          "replaces: " old-assignment-id "\n"
                          (when old-batch-id
                            (str "batch_id: " old-batch-id "\n"))
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
      (println "REPLACES:" old-assignment-id)
      (when old-batch-id
        (println "BATCH_ID:" old-batch-id))
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
   "create-product" (fn [args] (create-assignment! (parse-create-product-args! args)))
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
