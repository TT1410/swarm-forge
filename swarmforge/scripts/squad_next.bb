#!/usr/bin/env bb

(ns squad-next
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(load-file (str (fs/path (fs/parent *file*) "squad_config.bb")))

(def usage-text
  "Usage: squad_next.sh")

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
    (cond
      (and configured (fs/exists? configured-roles)) (fs/path configured)
      (fs/exists? direct) cwd
      :else (let [git-root (str/trim (:out (sh-continue "git" "rev-parse" "--show-toplevel")))]
              (if (and (not (str/blank? git-root))
                       (fs/exists? (fs/path git-root ".swarmforge" "roles.tsv")))
                (fs/path git-root)
                (exit! 1 "Cannot find SwarmForge project root"))))))

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

(defn print-approval-action! [file]
  (let [approval (file-map file)
        approval-id (get approval "approval_id" (str/replace (fs/file-name file) #"\.approval$" ""))]
    (println "NEXT_ACTION: request_user_approval")
    (println "APPROVAL:" approval-id)
    (println "GATE:" (get approval "gate" "unknown"))
    (println "TARGET_KIND:" (get approval "target_kind" "unknown"))
    (println "TARGET_ID:" (get approval "target_id" "unknown"))
    (println "TITLE:" (get approval "title" ""))
    (println "REASON:" (get approval "reason" "approval requested"))
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
  (= "accepted" (get packet field)))

(defn field-present? [packet field]
  (not (str/blank? (get packet field))))

(defn approval-satisfied? [root packet gate]
  (or (field-approved? packet (str (gate-key gate) "_approval"))
      (not (squad-approval-required? root gate))))

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
                    status (file-map (fs/path dir "status"))]
                {:assignment-id assignment-id
                 :template (get metadata "template")
                 :story-id (get metadata "story_id")
                 :assignment-file (get metadata "assignment_file")
                 :state (get status "state" "unknown")})))
       vec))

(def terminal-assignment-states #{"merged" "rejected" "blocked"})

(defn assignment-for [assignments story-id template]
  (some (fn [assignment]
          (when (and (= story-id (:story-id assignment))
                     (= template (:template assignment))
                     (not (contains? terminal-assignment-states (:state assignment))))
            assignment))
        assignments))

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

(declare agent-state transient-row?)

(defn agent-records [root rows]
  (->> rows
       (filter transient-row?)
	      (map (fn [row]
	              (let [agent (first row)
                      row-task (second row)
	                    agent-dir (fs/path root ".squad" "agents" agent)
	                    metadata (file-map (fs/path agent-dir "metadata"))
                    status (file-map (fs/path agent-dir "status"))
                    heartbeat (file-map (fs/path agent-dir "heartbeat"))
                    liveness (file-map (fs/path agent-dir "liveness"))
                    instants (keep parse-instant
                                   [(get status "updated_at")
                                    (get heartbeat "updated_at")
                                    (when (= "true" (get liveness "pane_changed"))
                                      (get liveness "observed_at"))])
                    last-activity (when (seq instants)
                                    (apply max-key #(.toEpochMilli %) instants))]
	                {:agent agent
	                 :template (get metadata "template")
	                 :task-id (or (get metadata "task_id") row-task)
                 :state (get status "state" "unknown")
                 :last-activity-at last-activity
                 :activity-source (cond
                                    (= "true" (get liveness "pane_changed")) "pane"
                                    (get heartbeat "updated_at") "heartbeat"
                                    (get status "updated_at") "status"
                                    :else "none")})))
       vec))

(defn active-agent? [agent]
  (not (contains? #{"retired" "failed" "complete" "handoff_sent"} (:state agent))))

(defn active-assignment? [agents assignment-id]
  (boolean (some #(and (= assignment-id (:task-id %)) (active-agent? %)) agents)))

(defn active-template? [agents template]
  (boolean (some #(and (= template (:template %)) (active-agent? %)) agents)))

(def singleton-templates #{"hardener" "qa" "architect"})

(defn spawn-capacity? [root agents template]
  (let [active (filter active-agent? agents)
        max-agents (squad-max-transient-agents root)]
    (and (< (count active) max-agents)
         (or (not (contains? singleton-templates template))
             (not (active-template? agents template))))))

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
        field (str (gate-key gate) "_approval")]
    (when-not (field-approved? packet field)
      (if (squad-approval-required? root gate)
        {:priority priority
         :stage-order stage-order
         :next-action "create_approval_request"
         :theme-id (get packet "theme_id")
         :story-id story-id
         :gate gate
         :reason reason
         :command (str "squad_approval.sh request " (approval-id gate story-id)
                       " story " story-id " " gate " " title " " reason)}
        {:priority priority
         :stage-order stage-order
         :next-action "record_auto_approval"
         :theme-id (get packet "theme_id")
         :story-id story-id
         :gate gate
         :reason (str gate " approval is not required by configuration")
         :command (str "squad_packet.sh approve " story-id " " gate " auto-approved-by-config")}))))

(defn assignment-candidate [root assignments agents packet template assignment-suffix reason priority stage-order requirement]
  (let [story-id (get packet "story_id" (get packet "_story_id"))
        theme-id (get packet "theme_id")
        assignment-id (next-assignment-id assignments story-id assignment-suffix)
        assignment (assignment-for assignments story-id template)]
    (cond
      (nil? assignment)
      {:priority priority
       :stage-order stage-order
       :next-action "create_assignment"
       :theme-id theme-id
       :story-id story-id
       :template template
       :assignment-id assignment-id
       :reason reason
       :command (str "squad_assign.sh create " theme-id " " story-id " " template " "
                     assignment-id " <instructions-file>"
                     (when requirement
                       (str " --requires approval:" requirement)))}

      (and (= "assignment_created" (:state assignment))
           (not (active-assignment? agents (:assignment-id assignment)))
           (spawn-capacity? root agents template))
      {:priority priority
       :stage-order stage-order
       :next-action "request_spawn"
       :theme-id theme-id
       :story-id story-id
       :template template
       :assignment-id (:assignment-id assignment)
       :reason reason
       :command (str "squad_spawn_request.sh " template " " (:assignment-id assignment)
                     " " (:assignment-file assignment))}

	      :else nil)))

(defn batch-candidate [packet kind batch-suffix stage reason priority stage-order prerequisite-assignment-field]
  (let [story-id (get packet "story_id" (get packet "_story_id"))
        theme-id (get packet "theme_id")
        kind-key (gate-key kind)
        batch-id (str theme-id "-" batch-suffix)
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
  (let [assignment-id (next-id-with-base assignments assignment-base)
        assignment (assignment-for assignments "batch" template)]
    (cond
      (nil? assignment)
      {:priority priority
       :stage-order stage-order
       :next-action "create_assignment"
       :theme-id theme-id
       :story-id "batch"
       :template template
       :assignment-id assignment-id
       :reason reason
       :command (str "squad_assign.sh create " theme-id " batch " template " "
                     assignment-id " <instructions-file>")}

      (and (= "assignment_created" (:state assignment))
           (not (active-assignment? agents (:assignment-id assignment)))
           (spawn-capacity? root agents template))
      {:priority priority
       :stage-order stage-order
       :next-action "request_spawn"
       :theme-id theme-id
       :story-id "batch"
       :template template
       :assignment-id (:assignment-id assignment)
       :reason reason
       :command (str "squad_spawn_request.sh " template " " (:assignment-id assignment)
                     " " (:assignment-file assignment))}

      :else nil)))

(defn theme-assignment-candidate [root assignments agents theme template assignment-suffix reason priority stage-order requirement]
  (let [theme-id (:theme-id theme)
        assignment-id (next-assignment-id assignments theme-id assignment-suffix)
        assignment (assignment-for assignments "theme" template)]
    (cond
      (nil? assignment)
      {:priority priority
       :stage-order stage-order
       :next-action "create_assignment"
       :theme-id theme-id
       :story-id "theme"
       :template template
       :assignment-id assignment-id
       :reason reason
       :command (str "squad_assign.sh create " theme-id " theme " template " "
                     assignment-id " <instructions-file>"
                     (when requirement
                       (str " --requires approval:" requirement)))}

      (and (= "assignment_created" (:state assignment))
           (not (active-assignment? agents (:assignment-id assignment)))
           (spawn-capacity? root agents template))
      {:priority priority
       :stage-order stage-order
       :next-action "request_spawn"
       :theme-id theme-id
       :story-id "theme"
       :template template
       :assignment-id (:assignment-id assignment)
       :reason reason
       :command (str "squad_spawn_request.sh " template " " (:assignment-id assignment)
                     " " (:assignment-file assignment))}

      :else nil)))

(defn theme-candidates [root rows]
  (let [assignments (assignment-records root)
        agents (agent-records root rows)
        packet-themes (set (map #(get % "theme_id") (packets root)))]
    (->> (for [theme (theme-records root)
               :when (not (contains? packet-themes (:theme-id theme)))
               :let [approval {:priority 20
                                :stage-order 1
                                :next-action "create_approval_request"
                                :theme-id (:theme-id theme)
                                :story-id "theme"
                                :gate "theme"
                                :reason "theme-ready"
                                :command (str "squad_approval.sh request theme__" (:theme-id theme)
                                              " theme " (:theme-id theme)
                                              " theme Approve_theme theme-ready")}
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
                 (when (= "changes-requested" (get packet "gherkin_review"))
                   (assignment-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
                                         "gherkin-writer" "gherkin"
                                         "Gherkin review requested changes" 60 21 nil)))}
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
                 (when (= "changes-requested" (get packet "qa_procedure_review"))
                   (assignment-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
                                         "qa-procedure-writer" "qa-procedure"
                                         "QA procedure review requested changes" 60 31 nil)))}
   {:id :gherkin-review-assignment
    :priority 60
    :stage-order 40
    :candidate (fn [ctx packet]
                 (when (and (field-present? packet "gherkin_path")
                            (not (field-accepted? packet "gherkin_review"))
                            (not (= "changes-requested" (get packet "gherkin_review"))))
                   (assignment-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
                                         "gherkin-reviewer" "gherkin-review"
                                         "Gherkin artifact needs review" 60 40 nil)))}
   {:id :qa-procedure-review-assignment
    :priority 60
    :stage-order 50
    :candidate (fn [ctx packet]
                 (when (and (field-present? packet "qa_procedure_path")
                            (not (field-accepted? packet "qa_procedure_review"))
                            (not (= "changes-requested" (get packet "qa_procedure_review"))))
                   (assignment-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
                                         "qa-procedure-reviewer" "qa-procedure-review"
                                         "QA procedure artifact needs review" 60 50 nil)))}
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
                 (when (approval-satisfied? (:root ctx) packet "implementation")
                   (when-not (field-present? packet "implementation_sha")
                     (assignment-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
                                           "implementer" "implementation"
                                           "story is approved for implementation" 60 90
                                           (str "acceptance-" (get packet "story_id" (get packet "_story_id")))))))}
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
                 (when (and (field-present? packet "cleaner_sha")
                            (not (field-present? packet "code_review")))
	                   (assignment-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
	                                         "code-reviewer" "code-review"
	                                         "cleaned story needs code review" 60 110 nil)))}
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
                            (field-present? packet "code_review_sha")
                            (not (field-present? packet "hardener_batch")))
                   (batch-candidate packet "hardener" "hardener"
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
                   (batch-candidate packet "qa" "qa"
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
                   (batch-candidate packet "architecture" "architecture"
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
                 (when (and (field-accepted? packet "architecture_review")
                            (approval-satisfied? (:root ctx) packet "architecture"))
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
	         (sort-by (juxt :priority :theme-id :story-id :stage-order :assignment-id))
	         vec)))

(defn same-theme-packets [all-packets theme-id]
  (filter #(= theme-id (get % "theme_id")) all-packets))

(defn hardener-member-ready? [root packet]
  (and (approval-satisfied? root packet "code-review")
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
       (not (field-accepted? packet "architecture_review"))))

(defn architecture-stage-clear? [root packet]
  (or (field-accepted? packet "architecture_review")
      (= "changes-requested" (get packet "architecture_review"))
      (field-present? packet "architecture_batch")
      (architecture-member-ready? root packet)))

(defn any-batch-member-needs-result? [packets batch-field result-field]
  (boolean (some #(and (field-present? % batch-field)
                       (not (field-present? % result-field)))
                 packets)))

(defn any-architecture-batch-needs-review? [packets]
  (boolean (some #(and (field-present? % "architecture_batch")
                       (not (field-present? % "architecture_review")))
                 packets)))

(defn any-architecture-needs-senior? [packets]
  (boolean (some #(= "changes-requested" (get % "architecture_review")) packets)))

(defn all-batched-or-done? [packets batch-field done?]
  (every? #(or (field-present? % batch-field)
               (done? %))
          packets))

(defn batch-candidates [root rows]
  (let [all-packets (packets root)
        assignments (assignment-records root)
        agents (agent-records root rows)
        theme-ids (sort (set (keep #(get % "theme_id") all-packets)))]
    (->> (for [theme-id theme-ids
               :let [theme-packets (vec (same-theme-packets all-packets theme-id))
                     hardener-ready? (and (seq theme-packets)
                                          (all-batched-or-done? theme-packets
                                                                "hardener_batch"
                                                                #(field-present? % "hardener_sha"))
                                          (any-batch-member-needs-result? theme-packets "hardener_batch" "hardener_sha"))
                     qa-ready? (and (seq theme-packets)
                                    (all-batched-or-done? theme-packets
                                                          "qa_batch"
                                                          #(field-present? % "qa_sha"))
                                    (any-batch-member-needs-result? theme-packets "qa_batch" "qa_sha"))
                     architecture-ready? (and (seq theme-packets)
                                              (all-batched-or-done? theme-packets
                                                                    "architecture_batch"
                                                                    #(field-accepted? % "architecture_review"))
                                              (any-architecture-batch-needs-review? theme-packets))
                     senior-ready? (and (seq theme-packets)
                                        (any-architecture-needs-senior? theme-packets))
                     candidate (cond
                                 hardener-ready?
                                 (batch-assignment-candidate root assignments agents theme-id
                                                             "hardener" (str theme-id "-hardener")
                                                             "hardener batch is ready" 60 130)

                                 qa-ready?
                                 (batch-assignment-candidate root assignments agents theme-id
                                                             "qa" (str theme-id "-qa")
                                                             "QA batch is ready" 60 150)

                                 senior-ready?
                                 (batch-assignment-candidate root assignments agents theme-id
                                                             "senior-implementor" (str theme-id "-architecture-fix")
                                                             "architecture critique needs senior implementation" 60 166)

                                 architecture-ready?
                                 (batch-assignment-candidate root assignments agents theme-id
                                                             "architect" (str theme-id "-architecture")
                                                             "architecture batch is ready after QA" 60 170))]
               :when candidate]
           candidate)
         (sort-by (juxt :priority :theme-id :story-id :stage-order :assignment-id))
         vec)))

(defn print-story-candidate! [candidate total]
  (println "NEXT_ACTION:" (:next-action candidate))
  (println "THEME:" (:theme-id candidate))
  (when-let [story-id (:story-id candidate)]
    (println "STORY:" story-id))
  (when-let [gate (:gate candidate)]
    (println "GATE:" gate))
  (when-let [template (:template candidate)]
    (println "TEMPLATE:" template))
	  (when-let [assignment-id (:assignment-id candidate)]
	    (println "ASSIGNMENT:" assignment-id))
	  (when-let [batch-kind (:batch-kind candidate)]
	    (println "BATCH_KIND:" batch-kind))
	  (when-let [batch-id (:batch-id candidate)]
	    (println "BATCH:" batch-id))
	  (println "REASON:" (:reason candidate))
  (println "CANDIDATES:" total)
  (println "COMMAND:" (:command candidate)))

(defn lock-owner-pid [lock-dir]
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

(defn completed-handoff-agents [root]
  (->> (files-with-extension (fs/path root ".swarmforge" "handoffs" "inbox" "completed") ".handoff")
       (map handoff-sender)
       (remove #{"unknown"})
       set))

(defn retirement-candidate [root rows]
  (let [completed (completed-handoff-agents root)]
    (some (fn [row]
            (let [agent (first row)
                  state (agent-state root agent)]
              (when (and (transient-row? row)
                         (or (contains? completed agent)
                             (= "retired" state)))
                {:agent agent :state state})))
          rows)))

(defn print-retirement-action! [{:keys [agent state]}]
  (println "NEXT_ACTION: retire_agent")
  (println "AGENT:" agent)
  (println "STATE:" state)
  (println "REASON: completed handoff has been processed and role is still registered")
  (println "COMMAND:" (str "squad_retire.sh " agent)))

(defn recovery-candidate [root rows]
  (let [now (now-instant)
        threshold (squad-recovery-quiet-seconds root)
        retry-threshold (squad-recovery-retry-seconds root)]
    (some (fn [{:keys [agent task-id state last-activity-at activity-source] :as record}]
            (when (active-agent? record)
              (let [quiet-for (or (seconds-between last-activity-at now) Long/MAX_VALUE)
                    recovery (file-map (fs/path root ".squad" "agents" agent "recovery"))
                    checked-at (parse-instant (get recovery "checked_at"))
                    checked-age (seconds-between checked-at now)]
                (when (and (>= quiet-for threshold)
                           (or (nil? checked-age)
                               (>= checked-age retry-threshold)))
                  {:agent agent
                   :task-id task-id
                   :state state
                   :last-activity-at last-activity-at
                   :activity-source activity-source
                   :quiet-for quiet-for
                   :threshold threshold
                   :retry-threshold retry-threshold}))))
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
       (remove #(= "retired" (:state %)))
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

(defn next-action! []
  (let [root (fs/absolutize (project-root))
        inbox (fs/path root ".swarmforge" "handoffs" "inbox")
        in-process (first (files-with-extension (fs/path inbox "in_process") ".handoff"))
        new-handoff (first (files-with-extension (fs/path inbox "new") ".handoff"))
        rows (role-rows root)
        pending-approval-file (pending-approval root)
	        stale-lock-info (stale-lock root)
	        pending-spawn-file (pending-spawn-request root)
		        retire-candidate (retirement-candidate root rows)
            recover-candidate (recovery-candidate root rows)
		        theme-actions (theme-candidates root rows)
	        story-actions (story-candidates root rows)
	        batch-actions (batch-candidates root rows)]
    (cond
      in-process
      (print-handoff-action! "finish_in_process_handoff"
                             in-process
                             "handoff is already claimed and must be completed before new mail"
                             "continue processing current handoff; run done_with_current.sh when complete")

      new-handoff
      (print-handoff-action! "process_handoff"
                             new-handoff
                             "new handoff mail is waiting"
                             "ready_for_next.sh")

      pending-approval-file
      (print-approval-action! pending-approval-file)

      stale-lock-info
      (print-stale-lock-action! stale-lock-info)

      pending-spawn-file
      (print-spawn-wait-action! pending-spawn-file)

	      retire-candidate
	      (print-retirement-action! retire-candidate)

        recover-candidate
        (print-recovery-action! recover-candidate)
	
	      (seq (concat theme-actions story-actions batch-actions))
	      (let [actions (sort-by (juxt :priority :theme-id :story-id :stage-order :assignment-id)
	                             (concat theme-actions story-actions batch-actions))]
	        (print-story-candidate! (first actions) (count actions)))

      :else
      (print-wait-action! (active-transients root rows)))))

(defn -main [& args]
  (when (seq args)
    (exit! 1 usage-text))
  (next-action!))

(apply -main *command-line-args*)
