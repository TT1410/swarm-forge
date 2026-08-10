#!/usr/bin/env bb

(ns squad-next
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-config :as cfg]
            [squad-state :as squad-state]
            [clojure.set]
            [clojure.string :as str]))

(def usage-text
  "Usage: squad_next.sh [--apply-mechanical]")

(def script-dir
  (fs/parent *file*))

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

(defn files-with-extension [dir extension]
  (if (fs/exists? dir)
    (->> (fs/list-dir dir)
         (filter #(and (fs/regular-file? %) (str/ends-with? (fs/file-name %) extension)))
         (sort-by #(fs/file-name %))
         vec)
    []))

(defn file-map [file]
  (if (fs/exists? file)
    (into {}
          (keep (fn [line]
                  (when-let [[_ k v] (re-matches #"([^:]+):\s*(.*)" line)]
                    [k v])))
          (take-while (complement str/blank?)
                      (str/split-lines (slurp (str file)))))
	    {}))

(defn parse-instant [value]
  (try
    (when-not (str/blank? value)
      (java.time.Instant/parse value))
    (catch Exception _ nil)))

(defn now-instant []
  (or (parse-instant (System/getenv "SWARMFORGE_NOW"))
      (java.time.Instant/now)))

(defn seconds-between [earlier later]
  (when earlier
    (.getSeconds (java.time.Duration/between earlier later))))

(defn handoff-sender [file]
  (or (second (re-find #"_from_([^_]+)_to_" (fs/file-name file)))
      (get (file-map file) "from")
      "unknown"))

(defn handoff-task [file]
  (get (file-map file) "task" "unknown"))

(defn handoff-type [file]
  (get (file-map file) "type" "unknown"))

(defn print-handoff-action! [action file reason command]
  (let [headers (file-map file)]
    (println "NEXT_ACTION:" action)
    (println "HANDOFF:" (str file))
    (println "TASK:" (get headers "task" "unknown"))
    (println "FROM:" (handoff-sender file))
    (println "COMMIT:" (get headers "commit" "none"))
    (println "REASON:" reason)
    (println "COMMAND:" command)))

(defn pending-approval [root]
  (first (files-with-extension (fs/path root ".squad" "approvals" "pending") ".approval")))

(defn approval-record-exists? [root approval-id]
  (boolean
   (some #(fs/exists? (fs/path root ".squad" "approvals" % (str approval-id ".approval")))
         ["pending" "approved" "rejected"])))

(defn approval-records [root]
  (for [state ["pending" "approved" "rejected"]
        file (files-with-extension (fs/path root ".squad" "approvals" state) ".approval")]
    (assoc (file-map file)
           :approval-id (str/replace (fs/file-name file) #"\.approval$" "")
           :state state
           :file (str file))))

(defn approval-record-exists-for? [root target-kind target-id gate]
  (boolean
   (some #(and (= target-kind (get % "target_kind"))
               (= target-id (get % "target_id"))
               (= gate (get % "gate")))
         (approval-records root))))

(defn dashboard-url [root]
  (let [file (fs/path root ".swarmforge" "daemon" "squad-web-url")]
    (when (fs/regular-file? file)
      (not-empty (str/trim (slurp (str file)))))))

(defn print-approval-action! [file]
  (let [approval (file-map file)
        root (fs/absolutize (project-root))
        approval-id (get approval "approval_id" (str/replace (fs/file-name file) #"\.approval$" ""))]
    (println "NEXT_ACTION: request_user_approval")
    (println "APPROVAL:" approval-id)
    (println "GATE:" (get approval "gate" "unknown"))
    (println "TARGET_KIND:" (get approval "target_kind" "unknown"))
    (println "TARGET_ID:" (get approval "target_id" "unknown"))
    (println "TITLE:" (get approval "title" ""))
    (println "REASON:" (get approval "reason" "approval requested"))
    (when-let [url (dashboard-url root)]
      (println "DASHBOARD_URL:" url)
      (println "WEB_APPROVAL_PATH:" (str url "api/approvals/" approval-id "/approve")))
    (println "COMMAND_ON_APPROVAL:" (str "squad_approval.sh approve " approval-id " approved-by-user"))
    (println "COMMAND_ON_REJECTION:" (str "squad_approval.sh reject " approval-id " <reason>"))))

(defn gate-key [gate]
  (str/replace gate "-" "_"))

(defn packet-files [root]
  (let [stories-dir (fs/path root ".squad" "stories")]
    (if (fs/exists? stories-dir)
      (->> (fs/list-dir stories-dir)
           (map #(fs/path % "packet"))
           (filter fs/regular-file?)
           (sort-by #(fs/file-name (fs/parent %)))
           vec)
      [])))

(defn packets [root]
  (->> (packet-files root)
       (map (fn [file]
              (assoc (file-map file)
                     "_packet_file" (str file)
                     "_story_id" (fs/file-name (fs/parent file)))))
       vec))

(defn field-approved? [packet field]
  (= "approved" (get packet field)))

(defn field-accepted? [packet field]
  (if (contains? squad-state/stage-target-fields field)
    (squad-state/current-accepted? packet field)
    (= "accepted" (get packet field))))

(defn field-changes-requested? [packet field]
  (if (contains? squad-state/stage-target-fields field)
    (squad-state/current-changes-requested? packet field)
    (= "changes-requested" (get packet field))))

(defn field-present? [packet field]
  (not (str/blank? (get packet field))))

(defn approval-satisfied? [root packet gate]
  (or (field-approved? packet (str (gate-key gate) "_approval"))
      (not (cfg/squad-approval-required? root gate))))

(defn assignment-dirs [root]
  (let [dir (fs/path root ".squad" "assignments")]
    (if (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (sort-by fs/file-name)
           vec)
      [])))

(defn assignment-records [root]
  (->> (assignment-dirs root)
       (map (fn [dir]
              (let [assignment-id (fs/file-name dir)
                    metadata (file-map (fs/path dir "metadata"))
                    status (file-map (fs/path dir "status"))
                    result-manifest (file-map (fs/path dir "result-manifest"))
                    accepted-merge (file-map (fs/path dir "accepted-merge"))
                    review (file-map (fs/path dir "review"))]
                {:assignment-id assignment-id
                 :template (get metadata "template")
                 :theme-id (get metadata "theme_id")
                 :story-id (get metadata "story_id")
                 :requires (get metadata "requires")
                 :replaces (get metadata "replaces")
                 :merge-for (get metadata "merge_for")
                 :assignment-file (get metadata "assignment_file")
                 :created-at (get metadata "created_at")
                 :state (get status "state" "unknown")
                 :artifacts (get result-manifest "artifacts")
                 :result-commit (get result-manifest "commit")
                 :merge-commit (get accepted-merge "merge_commit")
                 :accepted-commit (get accepted-merge "commit")
                 :resolved-by (get accepted-merge "resolved_by")
                 :review-decision (or (get result-manifest "review_decision")
                                      (get review "decision"))
                 :review-file (get review "review_file")})))
       vec))

(defn split-list [value]
  (->> (str/split (or value "") #",")
       (map str/trim)
       (remove str/blank?)
       (remove #{"none"})
       vec))

(defn artifact-paths [assignment prefix suffix]
  (->> (split-list (:artifacts assignment))
       (filter #(and (str/starts-with? % prefix)
                     (str/ends-with? % suffix)))
       sort
       vec))

(defn artifact-story-id [path]
  (-> (fs/file-name path)
      (str/replace #"\.[^.]+$" "")))

(defn artifact-sha [assignment]
  (or (:merge-commit assignment)
      (:accepted-commit assignment)
      (:result-commit assignment)))

(defn assignment-by-id [assignments assignment-id]
  (some #(when (= assignment-id (:assignment-id %)) %) assignments))

(defn theme-story-ref-exists? [root theme-id story-id]
  (fs/regular-file? (fs/path root ".squad" "themes" theme-id "stories" (str story-id ".ref"))))

(defn story-packet-exists? [root story-id]
  (fs/regular-file? (fs/path root ".squad" "stories" story-id "packet")))

(defn theme-story-ref-files [root]
  (let [themes-dir (fs/path root ".squad" "themes")]
    (if (fs/directory? themes-dir)
      (->> (fs/list-dir themes-dir)
           (filter fs/directory?)
           (mapcat (fn [theme-dir]
                     (let [stories-dir (fs/path theme-dir "stories")]
                       (if (fs/directory? stories-dir)
                         (fs/list-dir stories-dir)
                         []))))
           (filter fs/regular-file?)
           (filter #(str/ends-with? (fs/file-name %) ".ref"))
           (sort-by str)
           vec)
      [])))

(defn theme-story-ref-record [file]
  (let [record (file-map file)
        story-id (or (get record "story_id")
                     (str/replace (fs/file-name file) #"\.ref$" ""))
        theme-id (fs/file-name (fs/parent (fs/parent file)))]
    {:theme-id theme-id
     :story-id story-id
     :path (get record "path")}))

(def terminal-assignment-states
  #{"merged" "rejected" "blocked" "replacement_created" "superseded" "retired"
    "review_accepted" "review_changes_requested"})

(defn assignment-created? [state]
  (contains? #{"created" "assignment_created"} state))

(defn assignment-for
  ([assignments story-id template]
   (assignment-for assignments nil story-id template))
  ([assignments theme-id story-id template]
  (some (fn [assignment]
          (when (and (or (nil? theme-id)
                         (= theme-id (:theme-id assignment)))
                     (= story-id (:story-id assignment))
                     (= template (:template assignment))
                     (not (contains? terminal-assignment-states (:state assignment))))
            assignment))
        assignments)))

(defn active-or-created-assignment-for? [assignments story-id template]
  (boolean (assignment-for assignments story-id template)))

(defn assignment-ever-for? [assignments story-id template]
  (boolean
   (some #(and (= story-id (:story-id %))
               (= template (:template %)))
         assignments)))

(defn assignment-count-for [assignments story-id template]
  (count
   (filter #(and (= story-id (:story-id %))
                 (= template (:template %)))
           assignments)))

(defn assignment-exists? [assignments assignment-id]
  (boolean (some #(= assignment-id (:assignment-id %)) assignments)))

(defn next-assignment-id [assignments story-id suffix]
  (let [base (str story-id "-" suffix)]
    (loop [iteration 1]
      (let [candidate (if (= 1 iteration)
                        base
                        (str base "-r" iteration))]
        (if (assignment-exists? assignments candidate)
          (recur (inc iteration))
          candidate)))))

(declare agent-state transient-row? next-batch-id visible-handoff-agents capacity-counted-agent? ready-actions)

(defn agent-files [root agent]
  (let [agent-dir (fs/path root ".squad" "agents" agent)]
    {:metadata (file-map (fs/path agent-dir "metadata"))
     :status (file-map (fs/path agent-dir "status"))
     :heartbeat (file-map (fs/path agent-dir "heartbeat"))
     :liveness (file-map (fs/path agent-dir "liveness"))}))

(defn liveness-active? [liveness]
  (or (= "true" (get liveness "pane_changed"))
      (= "false" (get liveness "pane_idle_prompt"))))

(defn activity-instants [{:keys [status heartbeat liveness]}]
  (keep parse-instant
        [(get status "updated_at")
         (get heartbeat "updated_at")
         (when (liveness-active? liveness)
           (get liveness "observed_at"))]))

(defn last-activity [files]
  (let [instants (activity-instants files)]
    (when (seq instants)
      (apply max-key #(.toEpochMilli %) instants))))

(def activity-source-rules
  [["pane" #(liveness-active? (:liveness %))]
   ["heartbeat" #(get-in % [:heartbeat "updated_at"])]
   ["status" #(get-in % [:status "updated_at"])]])

(defn activity-source [files]
  (or (some (fn [[source predicate]]
              (when (predicate files)
                source))
            activity-source-rules)
      "none"))

(defn agent-record [root row]
  (let [agent (first row)
        row-task (second row)
        {:keys [metadata status] :as files} (agent-files root agent)]
    {:agent agent
     :template (get metadata "template")
     :task-id (or (get metadata "task_id") row-task)
     :state (get status "state" "unknown")
     :last-activity-at (last-activity files)
     :activity-source (activity-source files)}))

(defn agent-records [root rows]
  (->> rows
       (filter transient-row?)
       (map #(agent-record root %))
       vec))

(defn active-agent? [agent]
  (not (contains? #{"retired" "failed"} (:state agent))))

(defn active-assignment? [agents assignment-id]
  (boolean (some #(and (= assignment-id (:task-id %)) (active-agent? %)) agents)))

(defn active-template? [agents template]
  (boolean (some #(and (= template (:template %)) (active-agent? %)) agents)))

(defn capacity-active-template? [root agents template]
  (boolean (some #(and (= template (:template %))
                       (capacity-counted-agent? root %))
                 agents)))

(def singleton-templates #{"hardener" "qa" "architect"})

(defn handoff-visible-agent? [root agent]
  (contains? (visible-handoff-agents root) agent))

(defn capacity-counted-agent? [root agent]
  (and (active-agent? agent)
       (not= "merger" (:template agent))
       (not (and (= "handoff_sent" (:state agent))
                 (handoff-visible-agent? root (:agent agent))))))

(defn spawn-capacity? [root agents template]
  (let [active (filter #(capacity-counted-agent? root %) agents)
        max-agents (cfg/squad-max-transient-agents root)]
    (and (< (count active) max-agents)
         (or (not (contains? singleton-templates template))
             (not (capacity-active-template? root agents template))))))

(defn approval-id [gate story-id]
  (str gate "__" story-id))

(defn theme-dirs [root]
  (let [dir (fs/path root ".squad" "themes")]
    (if (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (sort-by fs/file-name)
           vec)
      [])))

(defn theme-approved? [theme-dir gate]
  (let [file (fs/path theme-dir "approvals.tsv")]
    (and (fs/exists? file)
         (some (fn [line]
                 (let [[_ recorded-gate] (str/split line #"\t" 3)]
                   (= gate recorded-gate)))
               (str/split-lines (slurp (str file)))))))

(defn theme-records [root]
  (->> (theme-dirs root)
       (map (fn [dir]
              (let [theme-id (fs/file-name dir)]
                {:theme-id theme-id
                 :approved-theme? (theme-approved? dir "theme")})))
       vec))

(defn approval-candidate [root packet gate title reason priority stage-order]
  (let [story-id (get packet "story_id" (get packet "_story_id"))
        field (str (gate-key gate) "_approval")
        id (approval-id gate story-id)]
    (when (and (not (field-approved? packet field))
               (not (approval-record-exists-for? root "story" story-id gate)))
      (if (cfg/squad-approval-required? root gate)
        {:priority priority
         :stage-order stage-order
         :next-action "create_approval_request"
         :theme-id (get packet "theme_id")
         :story-id story-id
         :gate gate
         :reason reason
         :command (str "squad_approval.sh request " id
                       " story " story-id " " gate " " title " " reason)}
        {:priority priority
         :stage-order stage-order
         :next-action "record_auto_approval"
         :theme-id (get packet "theme_id")
         :story-id story-id
         :gate gate
         :reason (str gate " approval is not required by configuration")
         :command (str "squad_packet.sh approve " story-id " " gate " auto-approved-by-config")}))))

(declare assignment-create-candidate assignment-spawn-candidate spawnable-assignment?
         stale-changes-requested?
         architecture-gate-satisfied-for-final?)

(defn assignment-candidate [root assignments agents packet template assignment-suffix reason priority stage-order requirement]
  (let [story-id (get packet "story_id" (get packet "_story_id"))
        theme-id (get packet "theme_id")
        assignment-id (next-assignment-id assignments story-id assignment-suffix)
        assignment (assignment-for assignments theme-id story-id template)]
    (if assignment
      (when (spawnable-assignment? root agents template assignment)
        (assignment-spawn-candidate assignment theme-id story-id template reason priority stage-order))
      (assignment-create-candidate theme-id story-id template assignment-id reason priority stage-order requirement))))

(defn one-cycle-revision-candidate [root assignments agents packet template assignment-suffix review-field reason priority stage-order]
  (let [story-id (get packet "story_id" (get packet "_story_id"))]
    (when (and (field-changes-requested? packet review-field)
               (not (stale-changes-requested? packet review-field))
               (not (active-or-created-assignment-for? assignments story-id (str (str/replace review-field #"_" "-") "er"))))
      (if-let [assignment (assignment-for assignments (get packet "theme_id") story-id template)]
        (when (spawnable-assignment? root agents template assignment)
          (assignment-spawn-candidate assignment (get packet "theme_id") story-id template reason priority stage-order))
        (when (<= (assignment-count-for assignments story-id template) 1)
          (assignment-create-candidate (get packet "theme_id") story-id template
                                       (next-assignment-id assignments story-id assignment-suffix)
                                       reason priority stage-order nil))))))

(defn one-cycle-review-candidate [root assignments agents packet template assignment-suffix review-field reason priority stage-order]
  (let [story-id (get packet "story_id" (get packet "_story_id"))]
    (if-let [assignment (assignment-for assignments (get packet "theme_id") story-id template)]
      (when (spawnable-assignment? root agents template assignment)
        (assignment-spawn-candidate assignment (get packet "theme_id") story-id template reason priority stage-order))
      (when-not (assignment-ever-for? assignments story-id template)
        (assignment-create-candidate (get packet "theme_id") story-id template
                                     (next-assignment-id assignments story-id assignment-suffix)
                                     reason priority stage-order nil)))))

(defn cleaner-version-count [assignments packet story-id]
  (let [n (assignment-count-for assignments story-id "cleaner")]
    (if (and (zero? n) (field-present? packet "cleaner_sha"))
      1
      n)))

(defn code-review-create-allowed?
  "At most one code-reviewer assignment per cleaner version. Stops unbounded
  *-code-review-rN when a review result was never recorded on the packet."
  [assignments packet story-id]
  (let [reviewers (assignment-count-for assignments story-id "code-reviewer")
        cleaners (cleaner-version-count assignments packet story-id)]
    (< reviewers cleaners)))

(defn code-review-assignment-candidate [root assignments agents packet]
  (let [story-id (get packet "story_id" (get packet "_story_id"))
        theme-id (get packet "theme_id")]
    (when (and (field-present? packet "cleaner_sha")
               (not (field-accepted? packet "code_review"))
               (not (field-changes-requested? packet "code_review")))
      (if-let [assignment (assignment-for assignments theme-id story-id "code-reviewer")]
        (when (spawnable-assignment? root agents "code-reviewer" assignment)
          (assignment-spawn-candidate assignment theme-id story-id "code-reviewer"
                                      "cleaned story needs code review" 60 110))
        (when (code-review-create-allowed? assignments packet story-id)
          (assignment-create-candidate theme-id story-id "code-reviewer"
                                       (next-assignment-id assignments story-id "code-review")
                                       "cleaned story needs code review" 60 110 nil))))))

(defn assignment-create-candidate [theme-id story-id template assignment-id reason priority stage-order requirement]
  {:priority priority
   :stage-order stage-order
   :next-action "create_assignment"
   :theme-id theme-id
   :story-id story-id
   :template template
   :assignment-id assignment-id
   :reason reason
   :command (str "squad_assign.sh create " theme-id " " story-id " " template " "
                 assignment-id " --auto-instructions"
                 (when requirement
                   (str " --requires approval:" requirement))
                 (when-not requirement
                   " --queue-spawn"))})

(defn batch-assignment-create-candidate [theme-id template assignment-id reason priority stage-order requirement]
  {:priority priority
   :stage-order stage-order
   :next-action "create_assignment"
   :theme-id theme-id
   :story-id "batch"
   :template template
   :assignment-id assignment-id
   :reason reason
   :command (str "squad_assign.sh create-batch " theme-id " " template " "
                 assignment-id " --auto-instructions"
                 (when requirement
                   (str " --requires approval:" requirement))
                 (when-not requirement
                   " --queue-spawn"))})

(defn assignment-spawn-candidate [assignment theme-id story-id template reason priority stage-order]
  {:priority priority
   :stage-order stage-order
   :next-action "request_spawn"
   :theme-id theme-id
   :story-id story-id
   :template template
   :assignment-id (:assignment-id assignment)
   :reason reason
   :command (str "squad_spawn_request.sh " template " " (:assignment-id assignment)
                 " " (:assignment-file assignment))})

(defn spawn-request-task-ids [root]
  (->> ["new" "in_process"]
       (mapcat (fn [state]
                 (files-with-extension
                  (fs/path root ".squad" "spawn-requests" state)
                  ".request")))
       (keep #(get (file-map %) "task_id"))
       set))

(defn pending-spawn-for-assignment? [root assignment-id]
  (contains? (spawn-request-task-ids root) assignment-id))

(defn spawnable-assignment? [root agents template assignment]
  (and (assignment-created? (:state assignment))
       (not (active-assignment? agents (:assignment-id assignment)))
       (not (pending-spawn-for-assignment? root (:assignment-id assignment)))
       (spawn-capacity? root agents template)))
(defn batch-candidate [root assignments packet kind batch-suffix stage reason priority stage-order prerequisite-assignment-field]
  (let [story-id (get packet "story_id" (get packet "_story_id"))
        theme-id (get packet "theme_id")
        kind-key (gate-key kind)
        batch-id (next-batch-id root assignments theme-id kind batch-suffix)
        assignment-id (get packet (str prerequisite-assignment-field "_assignment"))
        branch (get packet (str prerequisite-assignment-field "_branch"))
        sha (get packet (str prerequisite-assignment-field "_sha"))]
    (when-not (field-present? packet (str kind-key "_batch"))
      {:priority priority
       :stage-order stage-order
       :next-action "record_batch_membership"
       :theme-id theme-id
       :story-id story-id
	       :batch-kind kind
	       :batch-id batch-id
	       :reason reason
	       :command (str "squad_batch_story.sh add " story-id " " kind " " batch-id " "
	                     stage " " assignment-id " " branch " " sha)})))

(defn next-id-with-base [assignments base]
  (loop [iteration 1]
    (let [candidate (if (= 1 iteration)
                      base
                      (str base "-r" iteration))]
      (if (assignment-exists? assignments candidate)
        (recur (inc iteration))
        candidate))))

(defn batch-assignment-candidate [root assignments agents theme-id template assignment-base reason priority stage-order]
  (let [assignment-id assignment-base
        assignment (assignment-by-id assignments assignment-id)]
    (if assignment
      (when (spawnable-assignment? root agents template assignment)
        (assignment-spawn-candidate assignment theme-id "batch" template reason priority stage-order))
      (batch-assignment-create-candidate theme-id template assignment-id reason priority stage-order nil))))

(defn theme-assignment-candidate [root assignments agents theme template assignment-suffix reason priority stage-order requirement]
  (let [theme-id (:theme-id theme)
        assignment-id (next-assignment-id assignments theme-id assignment-suffix)
        assignment (assignment-for assignments theme-id "theme" template)]
    (if assignment
      (when (spawnable-assignment? root agents template assignment)
        (assignment-spawn-candidate assignment theme-id "theme" template reason priority stage-order))
      (assignment-create-candidate theme-id "theme" template assignment-id reason priority stage-order requirement))))

(defn theme-analysis-complete? [assignments theme-id]
  (boolean
   (some #(and (= theme-id (:theme-id %))
               (= "theme" (:story-id %))
               (= "analyst" (:template %))
               (= "merged" (:state %)))
         assignments)))

(defn theme-candidates [root rows]
  (let [assignments (assignment-records root)
        agents (agent-records root rows)
        packet-themes (set (map #(get % "theme_id") (packets root)))]
    (->> (for [theme (theme-records root)
               :when (not (or (contains? packet-themes (:theme-id theme))
                              (theme-analysis-complete? assignments (:theme-id theme))))
               :let [approval-id (str "theme__" (:theme-id theme))
                     approval (when-not (approval-record-exists-for? root "theme" (:theme-id theme) "theme")
                                {:priority 20
                                 :stage-order 1
                                 :next-action "create_approval_request"
                                 :theme-id (:theme-id theme)
                                 :story-id "theme"
                                 :gate "theme"
                                 :reason "theme-ready"
                                 :command (str "squad_approval.sh request " approval-id
                                               " theme " (:theme-id theme)
                                               " theme Approve_theme theme-ready")})
                     analyst (when (:approved-theme? theme)
                               (theme-assignment-candidate root assignments agents theme
                                                           "analyst" "analysis"
                                                           "approved theme needs story analysis"
                                                           60 5 "theme"))
                     candidate (if (:approved-theme? theme) analyst approval)]
               :when candidate]
           candidate)
         (sort-by (juxt :priority :theme-id :stage-order :assignment-id))
         vec)))

(defn analyst-story-registration-candidate [root assignment path]
  (let [story-id (artifact-story-id path)
        theme-id (:theme-id assignment)
        sha (artifact-sha assignment)
        ref-exists? (theme-story-ref-exists? root theme-id story-id)
        packet-exists? (story-packet-exists? root story-id)]
    (when (and (= "merged" (:state assignment))
               (= "analyst" (:template assignment))
               (= "theme" (:story-id assignment))
               (not (str/blank? sha))
               (or (not ref-exists?)
                   (not packet-exists?)))
      {:priority 25
       :stage-order 1
       :next-action "register_story_artifact"
       :theme-id theme-id
       :story-id story-id
       :assignment-id (:assignment-id assignment)
       :reason "merged analyst story artifact must be registered before story workflow can continue"
       :command (str (when-not ref-exists?
                       (str "squad_theme.sh story " theme-id " " story-id " " path
                            " && "))
                     (when-not packet-exists?
                       (str "squad_packet.sh create " theme-id " " story-id " "
                            (:assignment-id assignment) " master " sha)))})))

(defn analyst-story-registration-candidates [root assignments]
  (->> (for [assignment assignments
             path (artifact-paths assignment "stories/" ".md")
             :let [candidate (analyst-story-registration-candidate root assignment path)]
             :when candidate]
         candidate)
       (sort-by (juxt :priority :theme-id :story-id :stage-order :assignment-id))
       vec))

(defn direct-story-packet-candidate [root {:keys [theme-id story-id]}]
  (when (and (not (str/blank? theme-id))
             (not (str/blank? story-id))
             (not (story-packet-exists? root story-id)))
    {:priority 26
     :stage-order 1
     :next-action "register_story_packet"
     :theme-id theme-id
     :story-id story-id
     :assignment-id "squad-leader"
     :reason "direct squad-leader story must be registered as an approved story before downstream workflow can continue"
     :command (str "squad_packet.sh create " theme-id " " story-id
                   " squad-leader master $(git rev-parse --short=10 HEAD)"
                   " && squad_packet.sh approve " story-id " story approved-by-user")}))

(defn direct-story-packet-candidates [root]
  (->> (theme-story-ref-files root)
       (map theme-story-ref-record)
       (keep #(direct-story-packet-candidate root %))
       (sort-by (juxt :priority :theme-id :story-id :stage-order :assignment-id))
       vec))

(def artifact-assignment-rules
  {"gherkin-writer" {:kind "gherkin"
                     :prefix "features/"
                     :suffix ".feature"
                     :packet-path-field "gherkin_path"}
   "qa-procedure-writer" {:kind "qa-procedure"
                          :prefix "qa/"
                          :suffix ".md"
                          :packet-path-field "qa_procedure_path"}})

(defn assignment-revision-rank [assignment-id]
  (if (str/blank? assignment-id)
    0
    (if-let [[_ n] (re-find #"-r([0-9]+)$" assignment-id)]
      (Long/parseLong n)
      1)))

(defn packet-artifact-stale?
  "True when the packet should adopt this merged artifact. Same path with a
  newer assignment revision or sha still needs attach. Older revisions must not
  overwrite a newer packet attachment."
  [packet rule path assignment sha]
  (let [kind-key (gate-key (:kind rule))
        path-field (or (:packet-path-field rule) (str kind-key "_path"))
        assignment-field (str kind-key "_assignment")
        sha-field (str kind-key "_sha")
        current-path (get packet path-field)
        current-assignment (get packet assignment-field)
        current-sha (get packet sha-field)
        new-id (:assignment-id assignment)
        new-rank (assignment-revision-rank new-id)
        old-rank (assignment-revision-rank current-assignment)]
    (cond
      (str/blank? current-path) true
      (not= path current-path) true
      (str/blank? current-assignment) true
      (< new-rank old-rank) false
      (> new-rank old-rank) true
      (not= new-id current-assignment) (pos? (compare new-id (str current-assignment)))
      :else (not= sha current-sha))))

(declare packet-by-story)

(defn artifact-attachment-candidate [root packets-by-story assignment rule path]
  (let [story-id (:story-id assignment)
        sha (artifact-sha assignment)
        packet (get packets-by-story story-id)]
    (when (and (= "merged" (:state assignment))
               packet
               (not (str/blank? story-id))
               (not (str/blank? sha))
               (packet-artifact-stale? packet rule path assignment sha))
      {:priority 25
       :stage-order 2
       :next-action "attach_story_artifact"
       :theme-id (:theme-id assignment)
       :story-id story-id
       :template (:template assignment)
       :assignment-id (:assignment-id assignment)
       :reason (str "merged " (:kind rule) " artifact must be attached to story packet")
       :command (str "squad_packet.sh attach " story-id " " (:kind rule) " "
                     (:assignment-id assignment) " master " sha " " path)})))

(defn artifact-attachment-candidates [root assignments packets]
  (let [packets-by-story (packet-by-story packets)]
    (->> (for [assignment assignments
               :let [rule (get artifact-assignment-rules (:template assignment))]
               :when rule
               path (artifact-paths assignment (:prefix rule) (:suffix rule))
               :let [candidate (artifact-attachment-candidate root packets-by-story assignment rule path)]
               :when candidate]
           candidate)
         (sort-by (juxt :priority :theme-id :story-id :stage-order :assignment-id))
         vec)))

(def result-assignment-rules
  {"implementer" "implementation"
   "cleaner" "cleaner"
   "hardener" "hardener"
   "qa" "qa"
   "senior-implementor" "senior-implementor"})

(def review-assignment-rules
  {"gherkin-reviewer" "gherkin"
   "qa-procedure-reviewer" "qa-procedure"
   "code-reviewer" "code"
   "architect" "architecture"})

(defn packet-result-missing? [packet kind]
  (not (field-present? packet (str (gate-key kind) "_sha"))))

(defn merged-assignment? [assignment]
  (= "merged" (:state assignment)))

(defn assignment-effective-sha [assignment]
  (artifact-sha assignment))

(defn batch-result-map [root batch-id]
  (file-map (fs/path root ".squad" "batches" batch-id "result")))

(defn batch-effective-sha [root assignment]
  (or (assignment-effective-sha assignment)
      (get (batch-result-map root (:assignment-id assignment)) "sha")))

(defn batch-result-available? [root assignment]
  (or (merged-assignment? assignment)
      (not (str/blank? (get (batch-result-map root (:assignment-id assignment)) "sha")))))

(defn result-record-candidate [packet assignment kind]
  (let [story-id (get packet "story_id" (get packet "_story_id"))
        sha (assignment-effective-sha assignment)]
    (when (and (merged-assignment? assignment)
               (= story-id (:story-id assignment))
               (packet-result-missing? packet kind)
               (not (str/blank? sha)))
      {:priority 25
       :stage-order 3
       :next-action "record_merged_result"
       :theme-id (:theme-id assignment)
       :story-id story-id
       :template (:template assignment)
       :assignment-id (:assignment-id assignment)
       :reason (str "merged " kind " assignment must be recorded in story packet")
       :command (str "squad_packet.sh record " story-id " " kind " "
                     (:assignment-id assignment) " master " sha)})))

(defn direct-result-record-candidates [assignments packets]
  (let [packets-by-story (packet-by-story packets)]
    (->> (for [assignment assignments
               :let [kind (get result-assignment-rules (:template assignment))
                     packet (get packets-by-story (:story-id assignment))]
               :when (and kind packet (not= "batch" (:story-id assignment)))
               :let [candidate (result-record-candidate packet assignment kind)]
               :when candidate]
           candidate)
         (sort-by (juxt :priority :theme-id :story-id :stage-order :assignment-id))
         vec)))

(defn batch-manifest-rows [root batch-id]
  (let [manifest (fs/path root ".squad" "batches" batch-id "manifest.tsv")]
    (if (fs/regular-file? manifest)
      (->> (rest (str/split-lines (slurp (str manifest))))
           (map #(str/split % #"\t" -1))
           (keep (fn [[story-id stage assignment-id branch sha]]
                   (when-not (str/blank? story-id)
                     {:story-id story-id
                      :stage stage
                      :assignment-id assignment-id
                      :branch branch
                      :sha sha})))
           vec)
      [])))

(defn batch-result-record-candidate [root packets-by-story assignment kind member]
  (let [story-id (:story-id member)
        packet (get packets-by-story story-id)
        sha (batch-effective-sha root assignment)]
    (when (and packet
               (batch-result-available? root assignment)
               (packet-result-missing? packet kind)
               (not (str/blank? sha)))
      {:priority 25
       :stage-order 4
       :next-action "record_merged_batch_result"
       :theme-id (:theme-id assignment)
       :story-id story-id
       :template (:template assignment)
       :assignment-id (:assignment-id assignment)
       :batch-kind kind
       :batch-id (:assignment-id assignment)
       :reason (str "merged " kind " batch result must be recorded in story packet")
       :command (str "squad_packet.sh record " story-id " " kind " "
                     (:assignment-id assignment) " master " sha)})))

(defn batch-result-record-candidates [root assignments packets]
  (let [packets-by-story (packet-by-story packets)]
    (->> (for [assignment assignments
               :let [kind (get result-assignment-rules (:template assignment))]
               :when (and kind (= "batch" (:story-id assignment)))
               member (batch-manifest-rows root (:assignment-id assignment))
               :let [candidate (batch-result-record-candidate root packets-by-story assignment kind member)]
               :when candidate]
           candidate)
         (sort-by (juxt :priority :theme-id :story-id :stage-order :assignment-id))
         vec)))

(defn batch-status-value [root batch-id]
  (or (get (file-map (fs/path root ".squad" "batches" batch-id "status")) "state")
      (get (file-map (fs/path root ".squad" "batches" batch-id "state")) "state")))

(def batch-completion-states
  "Batch statuses that still need completion after member packet projection."
  #{"open" "closed" "result_received" "unknown"})

(defn batch-complete-candidate [root packets-by-story assignment kind]
  (let [batch-id (:assignment-id assignment)
        members (batch-manifest-rows root batch-id)
        state (or (batch-status-value root batch-id) "unknown")]
    (when (and (seq members)
               (batch-result-available? root assignment)
               (contains? batch-completion-states state)
               (every? (fn [member]
                         (let [packet (get packets-by-story (:story-id member))]
                           (and packet (not (packet-result-missing? packet kind)))))
                       members))
      {:priority 24
       :stage-order 4
       :next-action "complete_batch"
       :theme-id (:theme-id assignment)
       :story-id "batch"
       :template (:template assignment)
       :assignment-id batch-id
       :batch-kind kind
       :batch-id batch-id
       :reason (str kind " batch results are recorded on all member packets")
       :command (str "squad_batch.sh complete " batch-id)})))

(defn batch-complete-candidates [root assignments packets]
  (let [packets-by-story (packet-by-story packets)]
    (->> (for [assignment assignments
               :let [kind (get result-assignment-rules (:template assignment))]
               :when (and kind (= "batch" (:story-id assignment)))
               :let [candidate (batch-complete-candidate root packets-by-story assignment kind)]
               :when candidate]
           candidate)
         (sort-by (juxt :priority :theme-id :story-id :stage-order :assignment-id))
         vec)))

(def review-decision-lines
  [["accepted" #{"accept" "accepted" "accept." "accepted."}]
   ["changes-requested" #{"changes-requested" "changes requested" "request changes" "request changes."}]])

(defn decision-line [line]
  (let [line (-> line str/trim str/lower-case)]
    (some (fn [[decision values]]
            (when (contains? values line)
              decision))
          review-decision-lines)))

(defn review-decision-from-content [content]
  (some decision-line (str/split-lines (or content ""))))

(defn review-content-paths [root assignment]
  (concat
   (when-not (str/blank? (:review-file assignment))
     [(fs/path (:review-file assignment))])
   [(fs/path root ".squad" "assignments" (:assignment-id assignment) "review.md")
    (fs/path root ".squad" "reviews" (str (:assignment-id assignment) ".md"))]
   (map #(fs/path root %) (artifact-paths assignment ".squad/reviews/" ".md"))))

(defn review-decision [root assignment]
  (or (:review-decision assignment)
      (when (= "review_accepted" (:state assignment)) "accepted")
      (when (= "review_changes_requested" (:state assignment)) "changes-requested")
      (some (fn [file]
              (when (fs/regular-file? file)
                (review-decision-from-content (slurp (str file)))))
            (review-content-paths root assignment))))

(defn packet-review-current-for-assignment? [packet review-field assignment]
  (and (= (:assignment-id assignment) (get packet (str review-field "_assignment")))
       (contains? #{"accepted" "changes-requested"} (get packet review-field))
       (squad-state/review-current? packet review-field)))

(defn review-record-superseded?
  "True when re-recording this review would undo one-cycle acceptance or apply an
  old decision after the artifact target has moved past the review."
  [packet review-field]
  (or (squad-state/current-accepted? packet review-field)
      (stale-changes-requested? packet review-field)))

(defn review-record-candidate-for-story [root packet assignment kind decision]
  (let [story-id (get packet "story_id" (get packet "_story_id"))
        review-field (str (gate-key kind) "_review")
        sha (assignment-effective-sha assignment)]
    (when (and (not (str/blank? sha))
               decision
               (not (packet-review-current-for-assignment? packet review-field assignment))
               (not (review-record-superseded? packet review-field)))
      {:priority 25
       :stage-order 5
       :next-action "record_review_result"
       :theme-id (:theme-id assignment)
       :story-id story-id
       :template (:template assignment)
       :assignment-id (:assignment-id assignment)
       :reason (str "merged " kind " review must be recorded in story packet")
       :command (str "squad_packet.sh review " story-id " " kind " " decision " "
                     (:assignment-id assignment) " master " sha)})))

(defn review-record-candidates [root assignments packets]
  (let [packets-by-story (packet-by-story packets)]
    (->> (for [assignment assignments
               :let [kind (get review-assignment-rules (:template assignment))
                     decision (review-decision root assignment)]
               :when (and kind
                          (contains? #{"merged" "review_accepted" "review_changes_requested"}
                                     (:state assignment)))
               story-id (if (= "batch" (:story-id assignment))
                          (map :story-id (batch-manifest-rows root (:assignment-id assignment)))
                          [(:story-id assignment)])
               :let [packet (get packets-by-story story-id)
                     candidate (when packet
                                 (review-record-candidate-for-story root packet assignment kind decision))]
               :when candidate]
           candidate)
         (sort-by (juxt :priority :theme-id :story-id :stage-order :assignment-id))
         vec)))

(def one-cycle-review-kinds
  {"gherkin" "gherkin_review"
   "qa-procedure" "qa_procedure_review"})

(defn stale-changes-requested? [packet review-field]
  (and (= "changes-requested" (get packet review-field))
       (not (squad-state/review-current? packet review-field))))

(defn post-revision-acceptance-candidate [packet kind review-field]
  (let [story-id (get packet "story_id" (get packet "_story_id"))
        kind-key (gate-key kind)
        assignment (get packet (str kind-key "_assignment"))
        sha (get packet (str kind-key "_sha"))]
    (when (and (stale-changes-requested? packet review-field)
               (not (str/blank? assignment))
               (not (str/blank? sha)))
      {:priority 25
       :stage-order 6
       :next-action "record_post_revision_review_acceptance"
       :theme-id (get packet "theme_id")
       :story-id story-id
       :assignment-id assignment
       :reason (str kind " revision after changes-requested completes the one-review cycle")
       :command (str "squad_packet.sh review " story-id " " kind " accepted "
                     assignment " master " sha)})))

(defn post-revision-acceptance-candidates [packets]
  (->> (for [packet packets
             [kind review-field] one-cycle-review-kinds
             :let [candidate (post-revision-acceptance-candidate packet kind review-field)]
             :when candidate]
         candidate)
       (sort-by (juxt :priority :theme-id :story-id :stage-order :assignment-id))
       vec))

(defn packet-repair-candidates [root]
  (let [assignments (assignment-records root)
        packets (packets root)]
    (vec (concat (analyst-story-registration-candidates root assignments)
                 (direct-story-packet-candidates root)
                 (artifact-attachment-candidates root assignments packets)
                 (direct-result-record-candidates assignments packets)
                 (batch-result-record-candidates root assignments packets)
                 (batch-complete-candidates root assignments packets)
                 (review-record-candidates root assignments packets)
                 (post-revision-acceptance-candidates packets)))))

(defn packet-by-story [packets]
  (into {}
        (map (fn [packet]
               [(get packet "story_id" (get packet "_story_id")) packet]))
        packets))

(defn requirement-satisfied? [root packet themes requirement]
  (if (str/blank? requirement)
    true
    (let [[kind gate] (str/split requirement #":" 2)]
      (and (= "approval" kind)
           (some? gate)
           (if packet
             (approval-satisfied? root packet gate)
             (boolean
              (some #(and (= gate "theme")
                          (= (:theme-id %) (get (meta themes) :theme-id))
                          (:approved-theme? %))
                    themes)))))))

(defn assignment-file-ok? [assignment-file]
  (and (not (str/blank? assignment-file))
       (fs/regular-file? (fs/path assignment-file))))

(defn theme-requirement-satisfied? [themes theme-id requires]
  (and (= requires "approval:theme")
       (some #(and (= theme-id (:theme-id %))
                   (:approved-theme? %))
             themes)))

(defn ready-assignment-requirement-ok? [root packet themes story-id theme-id requires]
  (or (str/blank? requires)
      (if (= story-id "theme")
        (theme-requirement-satisfied? themes theme-id requires)
        (requirement-satisfied? root packet themes requires))))

(defn generic-ready-assignment? [root packet themes agents
                                 {:keys [assignment-id template story-id assignment-file state requires theme-id]}]
  (and (assignment-created? state)
       (assignment-file-ok? assignment-file)
       (ready-assignment-requirement-ok? root packet themes story-id theme-id requires)
       (not (active-assignment? agents assignment-id))
       (not (pending-spawn-for-assignment? root assignment-id))
       (spawn-capacity? root agents template)))
(defn generic-ready-candidate [{:keys [assignment-id template story-id assignment-file theme-id created-at]}]
  {:priority 10
   :stage-order 0
   :next-action "request_spawn"
   :theme-id theme-id
   :story-id story-id
   :template template
   :assignment-id assignment-id
   :created-at created-at
   :reason "existing ready assignment can be spawned"
   :command (str "squad_spawn_request.sh " template " " assignment-id " " assignment-file)})

(defn generic-ready-assignment-candidates [root rows]
  (let [assignments (assignment-records root)
        agents (agent-records root rows)
        packet-map (packet-by-story (packets root))
        themes (theme-records root)]
    (->> (for [assignment assignments
               :let [packet (get packet-map (:story-id assignment))]
               :when (generic-ready-assignment? root packet themes agents assignment)]
           (generic-ready-candidate assignment))
         (sort-by (juxt :priority :theme-id :story-id :created-at :assignment-id))
         vec)))

(def story-transition-table
  [{:id :story-approval
    :priority 30
    :stage-order 10
    :candidate (fn [ctx packet]
                 (approval-candidate (:root ctx) packet "story" "Approve_story" "story-ready-for-approval" 30 10))}
   {:id :gherkin-assignment
    :priority 60
    :stage-order 20
    :candidate (fn [ctx packet]
                 (when (approval-satisfied? (:root ctx) packet "story")
                   (when-not (field-present? packet "gherkin_path")
                     (assignment-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
                                           "gherkin-writer" "gherkin"
                                           "approved story needs Gherkin" 60 20 nil))))}
   {:id :gherkin-revision-assignment
    :priority 60
    :stage-order 21
    :candidate (fn [ctx packet]
                 (one-cycle-revision-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
                                               "gherkin-writer" "gherkin" "gherkin_review"
                                               "Gherkin review requested changes" 60 21))}
   {:id :qa-procedure-assignment
    :priority 60
    :stage-order 30
    :candidate (fn [ctx packet]
                 (when (approval-satisfied? (:root ctx) packet "story")
                   (when-not (field-present? packet "qa_procedure_path")
                     (assignment-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
                                           "qa-procedure-writer" "qa-procedure"
                                           "approved story needs QA procedure" 60 30 nil))))}
   {:id :qa-procedure-revision-assignment
    :priority 60
    :stage-order 31
    :candidate (fn [ctx packet]
                 (one-cycle-revision-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
                                               "qa-procedure-writer" "qa-procedure" "qa_procedure_review"
                                               "QA procedure review requested changes" 60 31))}
   {:id :gherkin-review-assignment
    :priority 60
    :stage-order 40
    :candidate (fn [ctx packet]
                 (when (and (field-present? packet "gherkin_path")
                            (not (field-accepted? packet "gherkin_review"))
                            (or (not (field-changes-requested? packet "gherkin_review"))
                                (active-or-created-assignment-for? (:assignments ctx)
                                                                   (get packet "story_id" (get packet "_story_id"))
                                                                   "gherkin-reviewer")))
                   (one-cycle-review-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
                                               "gherkin-reviewer" "gherkin-review" "gherkin_review"
                                               "Gherkin artifact needs review" 60 40)))}
   {:id :qa-procedure-review-assignment
    :priority 60
    :stage-order 50
    :candidate (fn [ctx packet]
                 (when (and (field-present? packet "qa_procedure_path")
                            (not (field-accepted? packet "qa_procedure_review"))
                            (or (not (field-changes-requested? packet "qa_procedure_review"))
                                (active-or-created-assignment-for? (:assignments ctx)
                                                                   (get packet "story_id" (get packet "_story_id"))
                                                                   "qa-procedure-reviewer")))
                   (one-cycle-review-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
                                               "qa-procedure-reviewer" "qa-procedure-review" "qa_procedure_review"
                                               "QA procedure artifact needs review" 60 50)))}
   {:id :gherkin-approval
    :priority 30
    :stage-order 60
    :candidate (fn [ctx packet]
                 (when (field-accepted? packet "gherkin_review")
                   (approval-candidate (:root ctx) packet "gherkin" "Approve_Gherkin" "gherkin-review-accepted" 30 60)))}
   {:id :qa-procedure-approval
    :priority 30
    :stage-order 70
    :candidate (fn [ctx packet]
                 (when (field-accepted? packet "qa_procedure_review")
                   (approval-candidate (:root ctx) packet "qa-procedure" "Approve_QA_procedure" "qa-procedure-review-accepted" 30 70)))}
   {:id :implementation-approval
    :priority 30
    :stage-order 80
    :candidate (fn [ctx packet]
                 (when (and (approval-satisfied? (:root ctx) packet "story")
                            (approval-satisfied? (:root ctx) packet "gherkin")
                            (approval-satisfied? (:root ctx) packet "qa-procedure")
                            (field-accepted? packet "gherkin_review")
                            (field-accepted? packet "qa_procedure_review"))
                   (approval-candidate (:root ctx) packet "implementation" "Approve_implementation" "story-ready-for-implementation" 30 80)))}
   {:id :implementation-assignment
    :priority 60
    :stage-order 90
    :candidate (fn [ctx packet]
                 (when (and (approval-satisfied? (:root ctx) packet "implementation")
                            (squad-state/implementation-ready? packet)
                            (not (field-present? packet "implementation_sha")))
                   (assignment-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
                                         "implementer" "implementation"
                                         "story is approved for implementation" 60 90
                                         nil)))}
   {:id :implementation-revision-assignment
    :priority 60
    :stage-order 95
    :candidate (fn [ctx packet]
                 (when (and (field-changes-requested? packet "code_review")
                            (approval-satisfied? (:root ctx) packet "story")
                            (approval-satisfied? (:root ctx) packet "gherkin")
                            (approval-satisfied? (:root ctx) packet "qa-procedure")
                            (approval-satisfied? (:root ctx) packet "implementation"))
                   (assignment-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
                                         "implementer" "implementation"
                                         "code review requested implementation changes" 60 95
                                         nil)))}
   {:id :cleaner-assignment
    :priority 60
    :stage-order 100
    :candidate (fn [ctx packet]
                 (when (and (field-present? packet "implementation_sha")
                            (not (field-present? packet "cleaner_sha")))
                   (assignment-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
	                                           "cleaner" "cleaner"
	                                         "implemented story needs cleaning" 60 100 nil)))}
	   {:id :code-review-assignment
	    :priority 60
	    :stage-order 110
    :candidate (fn [ctx packet]
                 (code-review-assignment-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet))}
   {:id :code-review-approval
    :priority 30
    :stage-order 120
    :candidate (fn [ctx packet]
                 (when (and (field-present? packet "cleaner_sha")
                            (field-accepted? packet "code_review"))
                   (approval-candidate (:root ctx) packet "code-review" "Approve_code_review"
                                       "code-review-accepted" 30 120)))}
   {:id :hardener-assignment
    :priority 60
    :stage-order 130
    :candidate (fn [ctx packet]
                 (when (and (approval-satisfied? (:root ctx) packet "code-review")
                            (field-accepted? packet "code_review")
                            (field-present? packet "code_review_sha")
                            (not (field-present? packet "hardener_batch")))
                   (batch-candidate (:root ctx) (:assignments ctx) packet "hardener" "hardener"
                                    "code_reviewed"
                                    "code-reviewed story is ready for hardener batch"
                                    60 125 "code_review")))}
   {:id :hardening-approval
    :priority 30
    :stage-order 140
    :candidate (fn [ctx packet]
                 (when (field-present? packet "hardener_sha")
                   (approval-candidate (:root ctx) packet "hardening" "Approve_hardening"
                                       "hardening-returned" 30 140)))}
   {:id :qa-assignment
    :priority 60
    :stage-order 150
    :candidate (fn [ctx packet]
                 (when (and (approval-satisfied? (:root ctx) packet "hardening")
                            (field-present? packet "hardener_sha")
                            (not (field-present? packet "qa_batch")))
                   (batch-candidate (:root ctx) (:assignments ctx) packet "qa" "qa"
                                    "hardening_approved"
                                    "hardened story is ready for QA batch"
                                    60 145 "hardener")))}
   {:id :qa-approval
    :priority 30
    :stage-order 160
    :candidate (fn [ctx packet]
                 (when (field-present? packet "qa_sha")
                   (approval-candidate (:root ctx) packet "qa" "Approve_QA"
                                       "qa-returned" 30 160)))}
   {:id :architect-assignment
    :priority 60
    :stage-order 170
    :candidate (fn [ctx packet]
                 (when (and (approval-satisfied? (:root ctx) packet "qa")
                            (field-present? packet "qa_sha")
                            (not (field-present? packet "architecture_batch")))
                   (batch-candidate (:root ctx) (:assignments ctx) packet "architecture" "architecture"
                                    "qa_approved"
                                    "QA-verified story is ready for architecture batch"
                                    60 165 "qa")))}
   {:id :architecture-approval
    :priority 30
    :stage-order 180
    :candidate (fn [ctx packet]
                 (when (field-accepted? packet "architecture_review")
                   (approval-candidate (:root ctx) packet "architecture" "Approve_architecture"
                                       "architecture-review-accepted" 30 180)))}
   {:id :final-approval
    :priority 30
    :stage-order 190
    :candidate (fn [ctx packet]
                 (when (architecture-gate-satisfied-for-final? (:root ctx) packet)
                   (approval-candidate (:root ctx) packet "final" "Approve_final"
                                       "story-ready-for-final-acceptance" 30 190)))}])

(defn story-candidates [root rows]
  (let [ctx {:root root
             :rows rows
             :assignments (assignment-records root)
             :agents (agent-records root rows)}]
    (->> (for [packet (packets root)
               transition story-transition-table
               :let [candidate ((:candidate transition) ctx packet)]
               :when candidate]
           candidate)
	         (sort-by (juxt :theme-id :story-id :stage-order :priority :assignment-id))
	         vec)))

(defn same-theme-packets [all-packets theme-id]
  (filter #(= theme-id (get % "theme_id")) all-packets))

(defn hardener-member-ready? [root packet]
  (and (approval-satisfied? root packet "code-review")
       (field-accepted? packet "code_review")
       (field-present? packet "code_review_sha")
       (not (field-present? packet "hardener_sha"))))

(defn hardener-stage-clear? [root packet]
  (or (field-present? packet "hardener_sha")
      (field-present? packet "hardener_batch")
      (hardener-member-ready? root packet)))

(defn qa-member-ready? [root packet]
  (and (approval-satisfied? root packet "hardening")
       (field-present? packet "hardener_sha")
       (not (field-present? packet "qa_sha"))))

(defn qa-stage-clear? [root packet]
  (or (field-present? packet "qa_sha")
      (field-present? packet "qa_batch")
      (qa-member-ready? root packet)))

(defn architecture-member-ready? [root packet]
  (and (approval-satisfied? root packet "qa")
       (field-present? packet "qa_sha")
       (not (field-present? packet "senior_implementor_sha"))
       (not (or (field-accepted? packet "architecture_review")
                (field-changes-requested? packet "architecture_review")))))

(defn architecture-stage-clear? [root packet]
  (or (field-accepted? packet "architecture_review")
      (field-changes-requested? packet "architecture_review")
      (field-present? packet "architecture_batch")
      (architecture-member-ready? root packet)))

(defn batch-id-needing-result [packets batch-field result-field]
  (first
   (sort
    (keep #(when (and (field-present? % batch-field)
                      (not (field-present? % result-field)))
             (get % batch-field))
          packets))))

(defn architecture-batch-needing-review [packets]
  (first
   (sort
    (keep #(when (and (field-present? % "architecture_batch")
                      (not (or (field-accepted? % "architecture_review")
                               (field-changes-requested? % "architecture_review"))))
             (get % "architecture_batch"))
          packets))))

(defn any-architecture-needs-senior? [packets]
  (boolean (some #(and (field-changes-requested? % "architecture_review")
                       (not (field-present? % "senior_implementor_sha")))
                 packets)))

(defn architecture-complete? [packet]
  (or (field-accepted? packet "architecture_review")
      (and (field-changes-requested? packet "architecture_review")
           (field-present? packet "senior_implementor_sha"))))

(defn architecture-gate-satisfied-for-final? [root packet]
  (or (and (field-accepted? packet "architecture_review")
           (approval-satisfied? root packet "architecture"))
      (and (field-changes-requested? packet "architecture_review")
           (field-present? packet "senior_implementor_sha"))))

(defn any-hardener-member-ready? [root packets]
  (boolean (some #(hardener-member-ready? root %) packets)))

(defn any-qa-member-ready? [root packets]
  (boolean (some #(qa-member-ready? root %) packets)))

(defn any-architecture-member-ready? [root packets]
  (boolean (some #(architecture-member-ready? root %) packets)))

(defn unbatched-hardener-member-ready? [root packet]
  (and (hardener-member-ready? root packet)
       (not (field-present? packet "hardener_batch"))))

(defn unbatched-qa-member-ready? [root packet]
  (and (qa-member-ready? root packet)
       (not (field-present? packet "qa_batch"))))

(defn unbatched-architecture-member-ready? [root packet]
  (and (architecture-member-ready? root packet)
       (not (field-present? packet "architecture_batch"))))

(defn any-unbatched-hardener-member-ready? [root packets]
  (boolean (some #(unbatched-hardener-member-ready? root %) packets)))

(defn any-unbatched-qa-member-ready? [root packets]
  (boolean (some #(unbatched-qa-member-ready? root %) packets)))

(defn any-unbatched-architecture-member-ready? [root packets]
  (boolean (some #(unbatched-architecture-member-ready? root %) packets)))

(defn all-batched-or-done? [packets batch-field done?]
  (every? #(or (field-present? % batch-field)
               (done? %))
          packets))

(defn batch-records [root]
  (let [dir (fs/path root ".squad" "batches")]
    (if (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (map (fn [batch-dir]
                  (let [metadata (file-map (fs/path batch-dir "metadata"))
                        status (file-map (fs/path batch-dir "status"))
                        manifest (fs/path batch-dir "manifest.tsv")
                        story-count (if (fs/regular-file? manifest)
                                      (max 0 (dec (count (str/split-lines (slurp (str manifest))))))
                                      0)]
                    {:batch-id (fs/file-name batch-dir)
                     :kind (get metadata "kind")
                     :state (get status "state" "unknown")
                     :story-count story-count})))
           vec)
      [])))

(defn open-batch-with-members [batches requested-kind base]
  (some (fn [{:keys [batch-id kind state story-count]}]
          (when (and (= requested-kind kind)
                     (str/starts-with? batch-id base)
                     (= "open" state)
                     (pos? story-count))
            batch-id))
        (sort-by :batch-id batches)))

(defn reusable-batch-id [batches assignments requested-kind base]
  (some (fn [{:keys [batch-id kind state]}]
          (when (and (= requested-kind kind)
                     (str/starts-with? batch-id base)
                     (= "open" state)
                     (not (assignment-exists? assignments batch-id)))
            batch-id))
        (sort-by :batch-id batches)))

(defn unique-batch-id [assignments batch-ids base]
  (loop [iteration 1]
    (let [candidate (if (= 1 iteration)
                      base
                      (str base "-r" iteration))]
      (if (or (contains? batch-ids candidate)
              (assignment-exists? assignments candidate))
        (recur (inc iteration))
        candidate))))

(defn next-batch-id [root assignments theme-id requested-kind suffix]
  (let [base (str theme-id "-" suffix)
        batches (batch-records root)
        batch-ids (set (map :batch-id batches))]
    (or (reusable-batch-id batches assignments requested-kind base)
        (unique-batch-id assignments batch-ids base))))

(def batch-action-rules
  [{:ready? :hardener-ready?
    :template "hardener"
    :suffix "-hardener"
    :reason "hardener batch is ready"
    :stage-order 130}
   {:ready? :qa-ready?
    :template "qa"
    :suffix "-qa"
    :reason "QA batch is ready"
    :stage-order 150}
   {:ready? :senior-ready?
    :template "senior-implementor"
    :suffix "-architecture-fix"
    :reason "architecture critique needs senior implementation"
    :stage-order 166}
   {:ready? :architecture-ready?
    :template "architect"
    :suffix "-architecture"
    :reason "architecture batch is ready after QA"
    :stage-order 170}])

(defn batch-readiness [root theme-packets]
  (let [theme-id (get (first theme-packets) "theme_id")
        batches (batch-records root)]
    {:hardener-ready? (and (seq theme-packets)
                           (or (batch-id-needing-result theme-packets "hardener_batch" "hardener_sha")
                               (when-not (any-unbatched-hardener-member-ready? root theme-packets)
                                 (open-batch-with-members batches "hardener" (str theme-id "-hardener")))))
     :qa-ready? (and (seq theme-packets)
                     (or (batch-id-needing-result theme-packets "qa_batch" "qa_sha")
                         (when-not (any-unbatched-qa-member-ready? root theme-packets)
                           (open-batch-with-members batches "qa" (str theme-id "-qa")))))
     :architecture-ready? (and (seq theme-packets)
                               (or (architecture-batch-needing-review theme-packets)
                                   (when-not (any-unbatched-architecture-member-ready? root theme-packets)
                                     (open-batch-with-members batches "architecture" (str theme-id "-architecture")))))
   :senior-ready? (and (seq theme-packets)
                       (any-architecture-needs-senior? theme-packets))}))

(defn batch-candidate-for-rule [root assignments agents theme-id readiness
                                {:keys [ready? template suffix reason stage-order]}]
  (when-let [ready-value (get readiness ready?)]
    (let [assignment-base (if (string? ready-value)
                            ready-value
                            (str theme-id suffix))]
      (batch-assignment-candidate root assignments agents theme-id
                                  template assignment-base
                                  reason 60 stage-order))))

(defn batch-candidate-for-theme [root assignments agents all-packets theme-id]
  (let [readiness (batch-readiness root (vec (same-theme-packets all-packets theme-id)))]
    (some #(batch-candidate-for-rule root assignments agents theme-id readiness %)
          batch-action-rules)))

(defn batch-candidates [root rows]
  (let [all-packets (packets root)
        assignments (assignment-records root)
        agents (agent-records root rows)
        theme-ids (sort (set (keep #(get % "theme_id") all-packets)))]
    (->> (keep #(batch-candidate-for-theme root assignments agents all-packets %) theme-ids)
         (sort-by (juxt :priority :theme-id :story-id :stage-order :assignment-id))
         vec)))

(defn existing-merger-assignment [assignments base]
  (some (fn [assignment]
          (when (and (= "merger" (:template assignment))
                     (str/starts-with? (:assignment-id assignment) base))
            assignment))
        assignments))

(defn merge-suffix-depth
  "How many -merge segments are already in the assignment id lineage."
  [assignment-id]
  (count (re-seq #"-merge" (str assignment-id))))

(defn merger-spawn-candidate [root agents existing]
  (when (and (assignment-created? (:state existing))
             (not (active-assignment? agents (:assignment-id existing)))
             (not (pending-spawn-for-assignment? root (:assignment-id existing))))
    {:priority 50
     :stage-order 5
     :next-action "request_spawn"
     :theme-id (:theme-id existing)
     :story-id (:story-id existing)
     :template "merger"
     :assignment-id (:assignment-id existing)
     :reason "merge-blocked assignment needs merger"
     :command (str "squad_spawn_request.sh merger " (:assignment-id existing)
                   " " (:assignment-file existing))}))
(defn merger-create-candidate [theme-id blocked-assignment-id story-id merger-id]
  {:priority 50
   :stage-order 5
   :next-action "create_assignment"
   :theme-id theme-id
   :story-id story-id
   :template "merger"
   :assignment-id merger-id
   :reason "merge-blocked assignment needs merger"
   :command (str "squad_assign.sh create-merger " blocked-assignment-id " "
                 merger-id " --auto-instructions --queue-spawn")})

(defn merger-limit-blocker-candidate [root {:keys [assignment-id theme-id story-id]} max-depth]
  (let [reason-path (str ".squad/assignments/" assignment-id "/merge-limit.md")
        reason (str "Merge recovery exceeded max_merger_depth (" max-depth "). "
                    "Manual resolution required for assignment " assignment-id ".\n")]
    {:priority 40
     :stage-order 5
     :next-action "declare_merge_blocker"
     :theme-id theme-id
     :story-id story-id
     :template "merger"
     :assignment-id assignment-id
     :reason (str "merge recovery exceeded max_merger_depth " max-depth)
     :command (str "printf '%s' " (pr-str reason) " > " reason-path
                   " && squad_assign.sh block " assignment-id " " reason-path)}))

(defn merger-candidate [root assignments agents {:keys [assignment-id theme-id story-id] :as assignment}]
  (let [depth (merge-suffix-depth assignment-id)
        max-depth (cfg/squad-max-merger-depth root)]
    (if (>= depth max-depth)
      (when-not (or (= "blocked" (:state assignment))
                    (fs/regular-file? (fs/path root ".squad" "assignments" assignment-id "blocker")))
        (merger-limit-blocker-candidate root assignment max-depth))
      (let [base (str assignment-id "-merge")
            existing (existing-merger-assignment assignments base)]
        (if existing
          (when-not (active-assignment? agents (:assignment-id existing))
            (merger-spawn-candidate root agents existing))
          (merger-create-candidate theme-id assignment-id story-id
                                   (next-id-with-base assignments base)))))))

(defn merger-candidates [root rows]
  (let [assignments (assignment-records root)
        agents (agent-records root rows)]
    (->> (for [{:keys [state] :as assignment} assignments
               :when (= "merge_blocked" state)
               :let [candidate (merger-candidate root assignments agents assignment)]
               :when candidate]
           candidate)
         (remove nil?)
         (sort-by (juxt :priority :theme-id :story-id :stage-order :assignment-id))
         vec)))

(def story-candidate-fields
  [["NEXT_ACTION" :next-action true]
   ["THEME" :theme-id true]
   ["STORY" :story-id false]
   ["GATE" :gate false]
   ["TEMPLATE" :template false]
   ["ASSIGNMENT" :assignment-id false]
   ["BATCH_KIND" :batch-kind false]
   ["BATCH" :batch-id false]
   ["REASON" :reason true]])

(defn print-candidate-field! [candidate [label key required?]]
  (when-let [value (or (get candidate key)
                       (when required? ""))]
    (println (str label ":") value)))

(defn print-story-candidate! [candidate total]
  (doseq [field story-candidate-fields]
    (print-candidate-field! candidate field))
  (println "CANDIDATES:" total)
  (println "COMMAND:" (:command candidate)))

(def concurrent-action-fields
  [["CONCURRENT_ACTION_NAME" :next-action true]
   ["CONCURRENT_THEME" :theme-id false]
   ["CONCURRENT_STORY" :story-id false]
   ["CONCURRENT_GATE" :gate false]
   ["CONCURRENT_TEMPLATE" :template false]
   ["CONCURRENT_AGENT" :agent false]
   ["CONCURRENT_ASSIGNMENT" :assignment-id false]
   ["CONCURRENT_BATCH_KIND" :batch-kind false]
   ["CONCURRENT_BATCH" :batch-id false]
   ["CONCURRENT_REASON" :reason false]
   ["CONCURRENT_COMMAND" :command true]])

(defn print-concurrent-action! [index candidate]
  (println "CONCURRENT_ACTION:" index)
  (doseq [field concurrent-action-fields]
    (print-candidate-field! candidate field)))

(defn print-concurrent-actions! [actions]
  (println "CONCURRENT_ACTIONS:" (count actions))
  (println "CONCURRENT_ACTION_ORDER:"
           (if (some #(= "retire_agent" (:next-action %)) actions)
             "retire_agent commands share the registry lock and must run one at a time; other independent commands may run concurrently"
             "execute listed order when capacity changes depend on prior actions; otherwise independent commands may run concurrently"))
  (doseq [[index action] (map-indexed vector actions)]
    (print-concurrent-action! (inc index) action)))

;; Bookkeeping-only actions: safe to apply all ready instances without capacity scheduling.
(def bookkeeping-actions
  #{"register_story_artifact"
    "register_story_packet"
    "attach_story_artifact"
    "record_merged_result"
    "record_merged_batch_result"
    "complete_batch"
    "record_review_result"
    "record_post_revision_review_acceptance"
    "record_auto_approval"
    "record_batch_membership"
    "declare_merge_blocker"})

;; Deterministic ready-actions the daemon applies under capacity/dependency scheduling.
(def daemon-ready-actions
  #{"create_assignment"
    "request_spawn"
    "create_approval_request"})

;; Union retained for callers/tests that ask "is this mechanical?"
(def mechanical-actions
  (into bookkeeping-actions daemon-ready-actions))

(defn mechanical-action? [candidate]
  (contains? mechanical-actions (:next-action candidate)))

(defn bookkeeping-action? [candidate]
  (contains? bookkeeping-actions (:next-action candidate)))

(defn daemon-ready-action? [candidate]
  (contains? daemon-ready-actions (:next-action candidate)))

(defn shell-command! [root command]
  (process/sh {:dir (str root) :continue true}
              "bash" "-c" (str "PATH=" script-dir ":$PATH; " command)))

(defn apply-candidate! [root candidate]
  (let [result (shell-command! root (:command candidate))]
    (assoc candidate
           :exit (:exit result)
           :out (:out result)
           :err (:err result))))

(defn print-applied-transition! [{:keys [next-action story-id assignment-id batch-id exit err]}]
  (println "APPLIED_TRANSITION:" next-action
           (str "story=" (or story-id "none"))
           (str "assignment=" (or assignment-id "none"))
           (str "batch=" (or batch-id "none"))
           (str "exit=" exit))
  (when (and (not= 0 exit) (not (str/blank? err)))
    (println "APPLIED_ERROR:" (str/trim err))))

(defn print-applied-transitions! [applied]
  (when (seq applied)
    (println "APPLIED_TRANSITIONS:" (count applied))
    (doseq [transition applied]
      (print-applied-transition! transition))))

(defn apply-bookkeeping-ready-actions! [root rows]
  (loop [applied []
         remaining 100]
    (let [actions (ready-actions root rows)
          bookkeeping (filter bookkeeping-action? actions)]
      (if (or (zero? remaining) (empty? bookkeeping))
        applied
        (let [results (mapv #(apply-candidate! root %) bookkeeping)
              failed (some #(when-not (zero? (:exit %)) %) results)
              applied (into applied results)]
          (if failed
            applied
            (recur applied (dec remaining))))))))

(defn apply-mechanical-ready-actions!
  "Backward-compatible name: applies bookkeeping mechanical actions only.
  Daemon-ready actions (create/spawn/approval request) use capacity scheduling."
  [root rows]
  (apply-bookkeeping-ready-actions! root rows))(defn lock-owner-pid [lock-dir]
  (let [owner (fs/path lock-dir "owner")]
    (when (fs/exists? owner)
      (some->> (str/split-lines (slurp (str owner)))
               (some #(second (re-find #"^pid:\s*([0-9]+)" %)))
               parse-long))))

(defn pid-alive? [pid]
  (when pid
    (let [handle (java.lang.ProcessHandle/of pid)]
      (and (.isPresent handle)
           (.isAlive (.get handle))))))

(defn stale-lock [root]
  (let [lock-dir (fs/path root ".swarmforge" "squad" "spawn.lock")
        pid (lock-owner-pid lock-dir)]
    (when (and (fs/directory? lock-dir)
               (or (nil? pid) (not (pid-alive? pid))))
      {:lock lock-dir :pid pid})))

(defn print-stale-lock-action! [{:keys [lock pid]}]
  (println "NEXT_ACTION: clear_stale_lock")
  (println "LOCK:" (str lock))
  (println "OWNER_PID:" (or pid "unknown"))
  (println "REASON: squad registry lock owner is not running")
  (println "COMMAND:" (str "rm -rf " lock)))

(defn pending-spawn-request [root]
  (or (first (files-with-extension (fs/path root ".squad" "spawn-requests" "in_process") ".request"))
      (first (files-with-extension (fs/path root ".squad" "spawn-requests" "new") ".request"))))

(defn print-spawn-wait-action! [file]
  (println "NEXT_ACTION: wait_for_spawn")
  (println "REQUEST:" (str file))
  (println "REASON: spawn request is waiting for daemon processing")
  (println "CHECK_AFTER_SECONDS: 10")
  (println "COMMAND: sleep 10 && squad_next.sh"))

(defn role-rows [root]
  (let [roles-file (fs/path root ".swarmforge" "roles.tsv")]
    (if (fs/exists? roles-file)
      (->> (str/split-lines (slurp (str roles-file)))
           (remove str/blank?)
           (map #(str/split % #"\t" -1))
           vec)
      [])))

(defn transient-row? [row]
  (not= "squad-leader" (first row)))

(defn agent-state [root agent]
  (get (file-map (fs/path root ".squad" "agents" agent "status")) "state" "unknown"))

(defn completed-handoff-records [root]
  (->> (files-with-extension (fs/path root ".swarmforge" "handoffs" "inbox" "completed") ".handoff")
       (map (fn [file]
              {:agent (handoff-sender file)
               :assignment-id (handoff-task file)}))
       (remove #(= "unknown" (:agent %)))))

(defn assignment-merge-blocked? [root assignment-id]
  (= "merge_blocked"
     (get (file-map (fs/path root ".squad" "assignments" assignment-id "status")) "state")))

(defn assignment-result-recorded? [root assignment-id]
  (fs/regular-file? (fs/path root ".squad" "assignments" assignment-id "result")))

(defn assignment-status-state [root assignment-id]
  (get (file-map (fs/path root ".squad" "assignments" assignment-id "status")) "state" "unknown"))

(defn assignment-dir-exists? [root assignment-id]
  (fs/directory? (fs/path root ".squad" "assignments" assignment-id)))

(def resolved-handoff-assignment-states
  #{"merged" "rejected" "blocked" "replacement_created" "superseded"
    "review_accepted" "review_changes_requested"})

(defn downstream-merger-result-recorded? [root assignment-id]
  (boolean
   (let [assignments-dir (fs/path root ".squad" "assignments")]
     (when (fs/directory? assignments-dir)
       (some (fn [dir]
               (let [merger-id (fs/file-name dir)]
                 (when (and (= assignment-id
                               (get (file-map (fs/path dir "metadata")) "merge_for"))
                            (assignment-result-recorded? root merger-id))
                   merger-id)))
             (filter fs/directory? (fs/list-dir assignments-dir)))))))

(defn completed-handoff-retirable? [root {:keys [agent assignment-id]}]
  (and (not= "unknown" agent)
       (if (assignment-dir-exists? root assignment-id)
         (let [state (assignment-status-state root assignment-id)]
           (or (contains? resolved-handoff-assignment-states state)
               (and (= "merge_blocked" state)
                    (downstream-merger-result-recorded? root assignment-id))))
         true)))

(defn in-process-git-handoff-command [root file]
  (let [assignment-id (handoff-task file)
        state (assignment-status-state root assignment-id)]
    (when (and (= "git_handoff" (handoff-type file))
               (assignment-dir-exists? root assignment-id))
      (case state
        ("created" "assignment_created" "in_progress" "handoff_sent" "unknown")
        {:action "record_assignment_result"
         :reason "claimed git handoff must be recorded before completion"
         :command (str "squad_assign.sh result " assignment-id " " file)}

        "result_received"
        {:action "check_merge_readiness"
         :reason "recorded result must be checked for merge readiness before handoff completion"
         :command (str "squad_assign.sh merge-ready " assignment-id)}

        "merge_ready"
        {:action "accept_merge"
         :reason "merge-ready result must be accepted before handoff completion"
         :command (str "squad_assign.sh accept-merge " assignment-id)}

        ;; merge_blocked and other unresolved states: no handoff step here.
        nil))))

(defn in-process-merge-blocked? [root file]
  (and file
       (= "git_handoff" (handoff-type file))
       (assignment-dir-exists? root (handoff-task file))
       (assignment-merge-blocked? root (handoff-task file))))

(defn in-process-needs-action?
  "True when the in-process handoff still has a daemon/SL step other than
  waiting on merge-block recovery (which is expressed via ready-actions)."
  [{:keys [root in-process]}]
  (when in-process
    (boolean
     (or (in-process-git-handoff-command root in-process)
         (not (in-process-merge-blocked? root in-process))))))

(defn print-in-process-handoff-action! [root file]
  (if-let [{:keys [action reason command]} (in-process-git-handoff-command root file)]
    (print-handoff-action! action file reason command)
    (if (in-process-merge-blocked? root file)
      (print-handoff-action! "hold_merge_blocked_handoff"
                             file
                             "merge-blocked assignment must be resolved before handoff completion"
                             (str "true  # hold in_process until merge recovery; do not run done_with_current.sh"))
      (print-handoff-action! "finish_in_process_handoff"
                             file
                             "handoff is already claimed and must be completed before new mail"
                             (str "done_with_current.sh " file)))))

(def daemon-handoff-step-actions
  #{"record_assignment_result" "check_merge_readiness" "accept_merge"})

(defn visible-handoff-agents [root]
  (->> ["new" "in_process" "completed"]
       (mapcat #(files-with-extension (fs/path root ".swarmforge" "handoffs" "inbox" %) ".handoff"))
       (map handoff-sender)
       (remove #{"unknown"})
       set))

(defn retirement-candidates [root rows]
  (let [completed (->> (completed-handoff-records root)
                       (filter #(completed-handoff-retirable? root %))
                       (map :agent)
                       set)]
    (->> rows
         (keep (fn [row]
                 (let [agent (first row)
                       state (agent-state root agent)]
                   (when (and (transient-row? row)
                              (or (contains? completed agent)
                                  (= "retired" state)))
                     {:priority 5
                      :stage-order 0
                      :next-action "retire_agent"
                      :agent agent
                      :state state
                      :reason "completed handoff has been processed and role is still registered"
                      :command (str "squad_retire.sh " agent)}))))
         vec)))

(defn apply-retirement-actions!
  "Retire completed agents one at a time under spawn.lock — never in parallel."
  [root rows]
  (loop [applied []
         remaining 50
         current-rows rows]
    (let [candidates (retirement-candidates root current-rows)]
      (if (or (zero? remaining) (empty? candidates))
        applied
        (let [candidate (first candidates)
              result (apply-candidate! root candidate)
              applied (conj applied result)]
          (if (zero? (:exit result))
            (recur applied
                   (dec remaining)
                   (remove #(= (:agent candidate) (first %)) current-rows))
            applied))))))

(defn retirement-candidate [root rows]
  (first (retirement-candidates root rows)))

(defn print-retirement-action! [{:keys [agent state]}]
  (println "NEXT_ACTION: retire_agent")
  (println "AGENT:" agent)
  (println "STATE:" state)
  (println "REASON: completed handoff has been processed and role is still registered")
  (println "COMMAND:" (str "squad_retire.sh " agent)))

(defn recovery-checked-age [root now agent]
  (let [recovery (file-map (fs/path root ".squad" "agents" agent "recovery"))
        checked-at (parse-instant (get recovery "checked_at"))]
    (seconds-between checked-at now)))

(defn recovery-retry-due? [checked-age retry-threshold]
  (or (nil? checked-age)
      (>= checked-age retry-threshold)))

(defn quiet-recovery-due? [quiet-for threshold]
  (>= quiet-for threshold))

(defn recovery-quiet-for [last-activity-at now]
  (or (seconds-between last-activity-at now) Long/MAX_VALUE))

(defn recovery-agent-due? [root now threshold retry-threshold agent quiet-for]
  (and (quiet-recovery-due? quiet-for threshold)
       (recovery-retry-due? (recovery-checked-age root now agent) retry-threshold)))

(defn recovery-candidate-record [threshold retry-threshold quiet-for
                                 {:keys [agent task-id state last-activity-at activity-source]}]
  {:agent agent
   :task-id task-id
   :state state
   :last-activity-at last-activity-at
   :activity-source activity-source
   :quiet-for quiet-for
   :threshold threshold
   :retry-threshold retry-threshold})

(defn recovery-candidate-for-agent [root now threshold retry-threshold
                                    {:keys [agent task-id state last-activity-at activity-source] :as record}]
  (when (active-agent? record)
    (let [quiet-for (recovery-quiet-for last-activity-at now)]
      (when (recovery-agent-due? root now threshold retry-threshold agent quiet-for)
        (recovery-candidate-record threshold retry-threshold quiet-for record)))))

(defn recovery-candidate [root rows]
  (let [now (now-instant)
        threshold (cfg/squad-recovery-quiet-seconds root)
        retry-threshold (cfg/squad-recovery-retry-seconds root)]
    (some #(recovery-candidate-for-agent root now threshold retry-threshold %)
          (agent-records root rows))))

(defn print-recovery-action! [{:keys [agent task-id state last-activity-at activity-source quiet-for threshold retry-threshold]}]
  (println "NEXT_ACTION: recover_agent")
  (println "AGENT:" agent)
  (println "TASK_ID:" (or task-id "unknown"))
  (println "STATE:" state)
  (println "LAST_ACTIVITY_AT:" (or last-activity-at "none"))
  (println "ACTIVITY_SOURCE:" activity-source)
  (println "QUIET_FOR_SECONDS:" quiet-for)
  (println "RECOVERY_QUIET_SECONDS:" threshold)
  (println "RECOVERY_RETRY_SECONDS:" retry-threshold)
  (println "REASON: active agent has no recent activity; classify recovery before waiting longer")
  (println "COMMAND:" (str "squad_recover.sh " agent)))

(defn active-transients [root rows]
  (let [now (now-instant)]
    (->> (agent-records root rows)
         (map (fn [agent]
                (assoc agent :quiet-for (seconds-between (:last-activity-at agent) now))))
         (filter active-agent?)
         vec)))

(defn print-wait-action! [active]
  (println "NEXT_ACTION: wait")
  (println "REASON:" (if (seq active)
                       "active agents are still working or awaiting handoff delivery"
                       "no handoffs, pending approvals, active transient agents, or stale locks"))
  (doseq [{:keys [agent task-id state quiet-for activity-source]} active]
    (println "ACTIVE:" agent task-id state
             (str "quiet_for=" (or quiet-for "unknown"))
             (str "activity_source=" activity-source)))
  (println "CHECK_AFTER_SECONDS: 30")
  (println "COMMAND: sleep 30 && squad_next.sh"))

(defn ready-actions [root rows]
  (sort-by (juxt :priority :theme-id :stage-order :story-id :assignment-id)
           (concat (packet-repair-candidates root)
                   (theme-candidates root rows)
                   (story-candidates root rows)
                   (batch-candidates root rows)
                   (merger-candidates root rows)
                   (generic-ready-assignment-candidates root rows))))

(defn rows-without-agents [rows agents]
  (remove #(contains? agents (first %)) rows))

(defn capacity-used [root agents]
  (count (filter #(capacity-counted-agent? root %) agents)))

(defn active-singleton-templates [root agents]
  (->> agents
       (filter #(capacity-counted-agent? root %))
       (keep :template)
       (filter singleton-templates)
       set))

(defn spawn-action? [action]
  (= "request_spawn" (:next-action action)))

(defn merger-spawn-action? [action]
  (and (spawn-action? action)
       (= "merger" (:template action))))

(defn singleton-spawn-blocked? [active-singletons action]
  (and (contains? singleton-templates (:template action))
       (contains? active-singletons (:template action))))

(defn spawn-fits? [used max-agents active-singletons action]
  (or (merger-spawn-action? action)
      (and (< used max-agents)
           (not (singleton-spawn-blocked? active-singletons action)))))

(defn account-spawn [used active-singletons action]
  (if (merger-spawn-action? action)
    [used active-singletons]
    [(inc used)
     (cond-> active-singletons
       (contains? singleton-templates (:template action))
       (conj (:template action)))]))

(defn action-dependency-keys [{:keys [next-action story-id assignment-id batch-id agent gate template batch-kind]}]
  (set
   (concat
    (when (and story-id
               (not= "batch" story-id)
               (contains? #{"register_story_packet"
                            "attach_story_artifact"
                            "record_merged_result"
                            "record_merged_batch_result"
                            "record_review_result"
                            "record_post_revision_review_acceptance"
                            "record_auto_approval"
                            "record_batch_membership"
                            "create_approval_request"} next-action))
      [[:story-action story-id next-action gate template batch-kind]])
    (when (and assignment-id
               (contains? #{"create_assignment" "request_spawn"} next-action))
      [[:assignment-action assignment-id next-action]])
    (when (and batch-id
               (contains? #{"create_assignment" "request_spawn"} next-action))
      [[:batch-action batch-id next-action]])
    ;; Retirements share spawn.lock; never schedule more than one concurrent retire.
    (when (= "retire_agent" next-action)
      [[:registry-lock]])
    (when agent
      [[:agent agent]]))))

(defn dependency-conflict? [state action]
  (boolean (seq (clojure.set/intersection (:dependency-keys state)
                                          (action-dependency-keys action)))))

(defn account-dependencies [state action]
  (update state :dependency-keys into (action-dependency-keys action)))

(defn include-concurrent-action [state action]
  (if (dependency-conflict? state action)
    state
    (if-not (spawn-action? action)
      (-> state
          (update :actions conj action)
          (account-dependencies action))
    (let [{:keys [used max-agents active-singletons]} state]
      (if-not (spawn-fits? used max-agents active-singletons action)
        state
        (let [[used active-singletons] (account-spawn used active-singletons action)]
          (-> state
              (assoc :used used :active-singletons active-singletons)
              (update :actions conj action)
              (account-dependencies action))))))))

(defn schedule-concurrent-actions [root rows retire-actions ready-actions]
  (let [retired-agents (set (keep :agent retire-actions))
        adjusted-rows (rows-without-agents rows retired-agents)
        agents (agent-records root adjusted-rows)
        ;; Retirements share the registry lock — schedule at most one, then ready work.
        initial {:used (capacity-used root agents)
                 :max-agents (cfg/squad-max-transient-agents root)
                 :active-singletons (active-singleton-templates root agents)
                 :dependency-keys #{}
                 :actions []}
        with-retires (reduce include-concurrent-action initial retire-actions)]
    (:actions (reduce include-concurrent-action with-retires ready-actions))))

(defn concurrent-action-context [root rows]
  (let [retire-actions (retirement-candidates root rows)
        adjusted-rows (rows-without-agents rows (set (keep :agent retire-actions)))
        ready (ready-actions root adjusted-rows)]
    {:retire-actions retire-actions
     :ready-actions ready
     :concurrent-actions (schedule-concurrent-actions root rows retire-actions ready)}))

(defn next-action-context []
  (let [root (fs/absolutize (project-root))
        inbox (fs/path root ".swarmforge" "handoffs" "inbox")
        rows (role-rows root)
        concurrent (concurrent-action-context root rows)]
    (merge
     {:root root
      :rows rows
      :in-process (first (files-with-extension (fs/path inbox "in_process") ".handoff"))
      :new-handoff (first (files-with-extension (fs/path inbox "new") ".handoff"))
      :stale-lock-info (stale-lock root)
      :pending-spawn-file (pending-spawn-request root)
      :retire-candidate (first (:retire-actions concurrent))
      :recover-candidate (recovery-candidate root rows)
      :pending-approval-file (pending-approval root)}
     concurrent)))

(def action-rules
  [[:finish-in-process in-process-needs-action?]
   [:process-handoff :new-handoff]
   [:stale-lock :stale-lock-info]
   [:pending-spawn :pending-spawn-file]
   [:retire :retire-candidate]
   [:recover :recover-candidate]
   [:ready-action #(seq (:ready-actions %))]
   [:pending-approval :pending-approval-file]])

(defn action-rule-matches? [ctx [_ predicate]]
  (if (keyword? predicate)
    (get ctx predicate)
    (predicate ctx)))
(defn action-printer [ctx]
  (or (some (fn [[action :as rule]]
              (when (action-rule-matches? ctx rule)
                action))
            action-rules)
      :wait))

(def action-print-handlers
  {:finish-in-process
   (fn [{:keys [root in-process]}]
     (print-in-process-handoff-action! root in-process))
   :process-handoff
   (fn [{:keys [new-handoff]}]
     (print-handoff-action! "process_handoff" new-handoff "new handoff mail is waiting" "ready_for_next.sh"))
   :stale-lock (fn [{:keys [stale-lock-info]}] (print-stale-lock-action! stale-lock-info))
   :pending-spawn (fn [{:keys [pending-spawn-file]}] (print-spawn-wait-action! pending-spawn-file))
   :retire (fn [{:keys [retire-candidate concurrent-actions]}]
             (print-retirement-action! retire-candidate)
             (print-concurrent-actions! concurrent-actions))
   :recover (fn [{:keys [recover-candidate]}] (print-recovery-action! recover-candidate))
   :ready-action (fn [{:keys [ready-actions concurrent-actions]}]
                   (print-story-candidate! (first ready-actions) (count ready-actions))
                   (print-concurrent-actions! concurrent-actions))
   :pending-approval (fn [{:keys [pending-approval-file]}] (print-approval-action! pending-approval-file))
   :wait (fn [{:keys [root rows]}] (print-wait-action! (active-transients root rows)))})

(defn print-selected-action! [ctx]
  ((action-print-handlers (action-printer ctx)) ctx))

(defn next-action! []
  (print-selected-action! (next-action-context)))

(defn apply-daemon-ready-actions!
  "Apply capacity-scheduled create_assignment / request_spawn / create_approval_request."
  [root]
  (loop [applied []
         remaining 50]
    (let [rows (role-rows root)
          concurrent (:concurrent-actions (concurrent-action-context root rows))
          daemon (filterv daemon-ready-action? concurrent)]
      (if (or (zero? remaining) (empty? daemon))
        applied
        (let [results (mapv #(apply-candidate! root %) daemon)
              failed (some #(when-not (zero? (:exit %)) %) results)
              applied (into applied results)]
          (if failed
            applied
            (recur applied (dec remaining))))))))

(defn apply-in-process-handoff-step!
  "Apply at most one deterministic in-process handoff step."
  [root]
  (let [file (first (files-with-extension
                     (fs/path root ".swarmforge" "handoffs" "inbox" "in_process")
                     ".handoff"))]
    (when file
      (if-let [{:keys [action command]} (in-process-git-handoff-command root file)]
        (when (contains? daemon-handoff-step-actions action)
          [(apply-candidate! root {:next-action action
                                   :assignment-id (handoff-task file)
                                   :command command})])
        (when (and (not (in-process-merge-blocked? root file))
                   (in-process-needs-action? {:root root :in-process file}))
          [(apply-candidate! root {:next-action "finish_in_process_handoff"
                                   :assignment-id (handoff-task file)
                                   :command (str "done_with_current.sh " file)})])))))

(defn apply-process-new-handoff-step!
  "Claim the next new handoff into in_process when the inbox is free."
  [root]
  (let [inbox (fs/path root ".swarmforge" "handoffs" "inbox")
        in-process (first (files-with-extension (fs/path inbox "in_process") ".handoff"))
        new-handoff (first (files-with-extension (fs/path inbox "new") ".handoff"))]
    (when (and new-handoff (nil? in-process))
      [(apply-candidate! root {:next-action "process_handoff"
                               :command "ready_for_next.sh"})])))

(defn apply-clear-stale-lock-step! [root]
  (when-let [{:keys [lock]} (stale-lock root)]
    [(apply-candidate! root {:next-action "clear_stale_lock"
                             :command (str "rm -rf " lock)})]))

(defn apply-one-mechanical-pass!
  "One drain pass: bookkeeping, retires, daemon-ready concurrent work, handoff steps."
  [root]
  (let [rows (role-rows root)
        bookkeeping (apply-bookkeeping-ready-actions! root rows)
        retires (apply-retirement-actions! root (role-rows root))
        daemon-ready (apply-daemon-ready-actions! root)
        stale (or (apply-clear-stale-lock-step! root) [])
        claim (or (apply-process-new-handoff-step! root) [])
        handoff (or (apply-in-process-handoff-step! root) [])]
    (into [] (concat bookkeeping retires daemon-ready stale claim handoff))))

(defn apply-mechanical-and-print-next! []
  (let [root (fs/absolutize (project-root))]
    (loop [applied []
           remaining 100]
      (let [batch (apply-one-mechanical-pass! root)]
        (cond
          (zero? remaining)
          (do (print-applied-transitions! applied)
              (print-selected-action! (next-action-context)))

          (empty? batch)
          (do (print-applied-transitions! applied)
              (print-selected-action! (next-action-context)))

          (some #(and (contains? % :exit) (not (zero? (:exit %)))) batch)
          (do (print-applied-transitions! (into applied batch))
              (print-selected-action! (next-action-context)))

          :else
          (recur (into applied batch) (dec remaining)))))))(defn -main [& args]
  (case (count args)
    0 (next-action!)
    1 (if (= "--apply-mechanical" (first args))
        (apply-mechanical-and-print-next!)
        (exit! 1 usage-text))
    (exit! 1 usage-text)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
