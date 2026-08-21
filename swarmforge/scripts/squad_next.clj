#!/usr/bin/env bb

(ns squad-next
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-actions :as actions]
            [squad-config :as cfg]
            [squad-control-plane :as plane]
            [squad-executor :as executor]
            [squad-product :as product]
            [squad-state :as squad-state]
            [squadd.web :as web]
            [clojure.edn :as edn]
            [clojure.set]
            [clojure.string :as str]))

(def usage-text
  "Usage: squad_next.sh [--apply-mechanical | --residual-only]")

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

(defn file-map
  "Read a line-oriented `key: value` file into a map.

  Missing files and races where the file disappears between exists? and slurp
  (TOCTOU during agent retire) return {} — never throw."
  [file]
  (try
    (if (fs/exists? file)
      (into {}
            (keep (fn [line]
                    (when-let [[_ k v] (re-matches #"([^:]+):\s*(.*)" line)]
                      [k v])))
            (take-while (complement str/blank?)
                        (str/split-lines (slurp (str file)))))
      {})
    (catch java.io.FileNotFoundException _
      {})
    (catch java.nio.file.NoSuchFileException _
      {})
    (catch java.io.IOException _
      {})))

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

(defn durable-blocker-files [root]
  (let [dir (fs/path root ".squad" "blockers")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(and (fs/regular-file? %)
                         (not (str/ends-with? (fs/file-name %) ".md"))))
           (sort-by fs/file-name)
           vec)
      [])))

(defn durable-blocker-record [file]
  (let [m (file-map file)
        id (or (get m "blocker_id")
               (get m "approval_id")
               (fs/file-name file))]
    (merge m
           {"blocker_id" id
            "file" (str file)
            "state" (get m "state" "blocked")
            "kind" (get m "kind" "blocker")})))

(defn oldest-durable-blocker [root]
  (when-let [file (first (durable-blocker-files root))]
    (durable-blocker-record file)))

(defn print-durable-blocker-action! [blocker]
  (let [id (get blocker "blocker_id")
        kind (get blocker "kind" "blocker")
        approval-id (or (get blocker "approval_id") id)]
    (println "NEXT_ACTION: handle_durable_blocker")
    (println "BLOCKER_ID:" id)
    (println "KIND:" kind)
    (println "STATE:" (get blocker "state" "blocked"))
    (println "TARGET_KIND:" (get blocker "target_kind" "unknown"))
    (println "TARGET_ID:" (get blocker "target_id" (get blocker "assignment_id" "unknown")))
    (println "GATE:" (get blocker "gate" "unknown"))
    (println "DETAIL:" (get blocker "detail" ""))
    (println "FILE:" (get blocker "file" ""))
    (println "REASON: durable blocker under .squad/blockers/ is not the same as a pending approval; report it accurately to the operator")
    (when (= "approval-rejection" kind)
      (println "COMMAND_TO_CLEAR:" (str "squad_approval.sh resolve-rejection " approval-id
                                        " rejection-cleared-for-reentry"))
      (println "NOTE: resolve-rejection removes the blocker and reopens the gate for re-request; it does not approve"))
    (when (and (not= "approval-rejection" kind)
               (get blocker "assignment_id"))
      (println "NOTE: assignment-scoped blockers are cleared by resolving the assignment (merge/block/reject/rework), not by ignoring the dashboard"))))

(defn pending-dashboard-request-files [root]
  (let [dir (fs/path root ".swarmforge" "dashboard" "requests" "pending")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".request")))
           (sort-by fs/file-name)
           vec)
      [])))

(defn dashboard-request-owner
  "Missing owner defaults to Troubleshooter (operator chat front door)."
  [m]
  (let [o (str/lower-case (str/trim (or (get m "owner") "")))]
    (if (contains? #{"troubleshooter" "squad-leader"} o)
      o
      "troubleshooter")))

(defn oldest-pending-dashboard-request
  "Squad Leader residual only sees product requests re-owned via route-to-sl.
  Troubleshooter-owned chat is answered from the TS wake path, not residual."
  [root]
  (some (fn [file]
          (let [m (file-map file)
                id (or (get m "id")
                       (str/replace (fs/file-name file) #"\.request$" ""))]
            (when (= "squad-leader" (dashboard-request-owner m))
              (merge m {"id" id
                        "owner" "squad-leader"
                        "file" (str file)}))))
        (pending-dashboard-request-files root)))

(defn body-preview
  "First line of body, truncated for unmissable residual display."
  [body]
  (let [line (or (first (remove str/blank? (str/split-lines (or body "")))) "")
        line (str/trim line)]
    (if (> (count line) 140)
      (str (subs line 0 137) "...")
      line)))

(defn print-dashboard-request-action! [request]
  (let [id (get request "id")
        kind (get request "kind" "command")
        owner (dashboard-request-owner request)
        body (or (get request "body") "")
        nonempty? (not (str/blank? (str/trim body)))
        preview (body-preview body)]
    (println "NEXT_ACTION: answer_dashboard_request")
    (println "REQUEST_ID:" id)
    ;; Put intent where collapsed tool UIs still show it
    (println "BODY_NONEMPTY:" nonempty?)
    (println "BODY_PREVIEW:" (if nonempty? preview "(empty)"))
    (println "KIND:" kind)
    (println "OWNER:" owner)
    (println "BODY:" body)
    (println "REASON: operator product request routed to Squad Leader; answer via the helper after orchestration")
    (println "COMMAND:" (str "squad_dashboard_request.sh answer " id " <answer-file>"))
    (println "COMMAND_ON_REJECTION:" (str "squad_dashboard_request.sh reject " id " <reason-file>"))
    (println "NOTE: request is not complete until the helper succeeds; pane text alone does not resolve it")
    (println "NOTE: Read full BODY (or BODY_PREVIEW) before answering. Do not claim empty body when BODY_NONEMPTY is true.")))

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

(defn awaiting-implementation-plan? [packet]
  (and (not (field-present? packet "implementation_plan_path"))
       (not (field-present? packet "implementation_plan_sha"))
       (not (field-present? packet "gherkin_path"))
       (not (field-present? packet "implementation_sha"))))

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
                 :batch-id (get metadata "batch_id")
                 :batch-stories (get metadata "batch_stories")
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
                     (str/ends-with? % suffix)
                     (not (str/includes? % "implementer-notes"))))
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

(declare agent-state transient-row? next-batch-id visible-handoff-agents
         capacity-counted-agent? ready-actions hardener-member-ready?
         qa-member-ready? architecture-member-ready?
         held-handoff-files)

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
  "Agents count as active until retired. Transient failed/blocked states still
  occupy capacity so a recovering agent cannot free a slot for a new spawn."
  (not= "retired" (:state agent)))
(defn active-assignment? [agents assignment-id]
  (boolean (some #(and (= assignment-id (:task-id %)) (active-agent? %)) agents)))

(defn active-template? [agents template]
  (boolean (some #(and (= template (:template %)) (active-agent? %)) agents)))

(defn handoff-visible-agent? [root agent]
  (contains? (visible-handoff-agents root) agent))

(defn capacity-counted-agent? [root agent]
  "Agents that consume max_transient_agents slots."
  (and (active-agent? agent)
       (not (and (= "handoff_sent" (:state agent))
                 (handoff-visible-agent? root (:agent agent))))))

(defn capacity-active-template? [root agents template]
  "True when a live agent already holds this template for capacity/singleton purposes."
  (boolean
   (some (fn [agent]
           (and (= template (:template agent))
                (capacity-counted-agent? root agent)))
         agents)))

(defn spawn-capacity? [root agents template]
  (let [active (filter #(capacity-counted-agent? root %) agents)
        max-agents (cfg/squad-max-transient-agents root)]
    (and (< (count active) max-agents)
         (or (not (contains? (cfg/singleton-templates root) template))
             (not (capacity-active-template? root agents template))))))

(defn approval-id [gate story-id]
  (str gate "__" story-id))

(defn approval-candidate [root packet gate title reason priority stage-order]
  (let [story-id (get packet "story_id" (get packet "_story_id"))
        field (str (gate-key gate) "_approval")
        id (approval-id gate story-id)
        ;; Failed batch QA must not auto-approve or request approval as pass
        qa-failed? (and (= "qa" gate)
                        (= "failed" (get packet "qa_verdict")))]
    (when (and (not (field-approved? packet field))
               (not (approval-record-exists-for? root "story" story-id gate))
               (not qa-failed?))
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

(def system-analyst-frame-gate-states
  #{"result_received" "merge_ready" "merged"})

(defn system-analyst-for-frame-gate [root]
  (some #(when (and (= "system-analyst" (:template %))
                    (contains? system-analyst-frame-gate-states (:state %)))
           %)
        (assignment-records root)))

(defn frame-approval-candidate
  "Product-scoped frame gate after system-analyst result, before or after merge."
  [root]
  (when (and (fs/regular-file? (product/product-file root))
             (not (product/frame-ready? (product/read-product root)))
             (system-analyst-for-frame-gate root)
             (not (approval-record-exists-for? root "product" "product" "frame"))
             (cfg/squad-approval-required? root "frame"))
    {:priority 20
     :stage-order 1
     :theme-id ""
     :story-id ""
     :next-action "create_approval_request"
     :gate "frame"
     :reason "frame-ready"
     :command (str "squad_approval.sh request frame__product product product frame"
                   " Approve_frame frame-ready")}))

(def live-system-analyst-states
  #{"created" "assignment_created" "in_progress" "result_received" "merge_ready" "merged"})

(defn live-system-analyst [root]
  (some #(when (and (= "system-analyst" (:template %))
                    (contains? live-system-analyst-states (:state %)))
           %)
        (assignment-records root)))

(defn product-assignment-id [p]
  (or (not-empty (get p "assignment_id")) "system-analysis"))

(defn create-product-analyst-candidate [root p]
  (when (and (fs/regular-file? (product/product-file root))
             (not (product/frame-ready? p))
             (not (live-system-analyst root)))
    (let [assignment-id (product-assignment-id p)]
      {:priority 20
       :stage-order 0
       :theme-id ""
       :story-id ""
       :next-action "create_assignment"
       :template "system-analyst"
       :assignment-id assignment-id
       :reason "product frame needs system-analyst"
       :command (str "squad_assign.sh create-product system-analyst " assignment-id
                     " --auto-instructions --queue-spawn")})))

(defn frame-approved? [root]
  (boolean
   (some #(and (= "approved" (:state %))
               (= "frame" (get % "gate")))
         (approval-records root))))

(defn merged-system-analyst-sha [root]
  (some #(when (and (= "system-analyst" (:template %))
                    (= "merged" (:state %)))
           (not-empty (artifact-sha %)))
        (assignment-records root)))

(defn record-frame-sha-candidate [root p]
  (when-let [sha (and (not (product/frame-ready? p))
                      (frame-approved? root)
                      (merged-system-analyst-sha root))]
    {:priority 24
     :stage-order 2
     :theme-id ""
     :story-id ""
     :next-action "record_frame_sha"
     :assignment-id (product-assignment-id p)
     :sha sha
     :reason "approved frame must be recorded on product"
     :command (str "record-frame-sha " sha)}))

(defn snapshot-item-open? [root id]
  (let [file (fs/path root ".squad" "backlog" (str id ".item"))]
    (when (fs/regular-file? file)
      (let [status (get (file-map file) "status")]
        (or (str/blank? status) (= "open" status))))))

(defn start-snapshot-item-candidate [id]
  {:priority 25
   :stage-order 3
   :theme-id ""
   :story-id id
   :item-id id
   :next-action "start_snapshot_item"
   :reason (str "start snapshotted backlog item " id)
   :command (str "start-snapshot-item " id)})

(defn start-snapshot-item-candidates [root p]
  (if (product/frame-ready? p)
    (mapv start-snapshot-item-candidate
          (filter #(snapshot-item-open? root %)
                  (product/open-item-ids p)))
    []))

(defn product-frame-candidates [root _rows]
  (let [p (product/read-product root)]
    (into []
          (concat (remove nil? [(create-product-analyst-candidate root p)
                                (frame-approval-candidate root)
                                (record-frame-sha-candidate root p)])
                  (start-snapshot-item-candidates root p)))))

(declare assignment-create-candidate assignment-spawn-candidate spawnable-assignment?
         stale-changes-requested?)


(defn assignment-candidate [root assignments agents packet template assignment-suffix reason priority stage-order requirement]
  (let [story-id (get packet "story_id" (get packet "_story_id"))
        theme-id (get packet "theme_id")
        assignment-id (next-assignment-id assignments story-id assignment-suffix)
        assignment (assignment-for assignments theme-id story-id template)]
    (if assignment
      (when (spawnable-assignment? root agents template assignment)
        (assignment-spawn-candidate assignment theme-id story-id template reason priority stage-order))
      (assignment-create-candidate theme-id story-id template assignment-id reason priority stage-order requirement))))

(defn code-review-create-allowed?
  "At most one code-reviewer assignment and one recorded verdict per story."
  [assignments packet story-id]
  (not (or (assignment-ever-for? assignments story-id "code-reviewer")
           (field-accepted? packet "code_review")
           (field-changes-requested? packet "code_review")
           (field-present? packet "code_review_iterations"))))

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
   :command (str "squad_assign.sh create " story-id " " template " "
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
   :command (str "squad_assign.sh create-batch " template " "
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


(def artifact-assignment-rules
  {"analyst" {:kind "implementation-plan"
              :prefix ".squad/stories/"
              :suffix "plan.md"
              :packet-path-field "implementation_plan_path"}
   "gherkin-writer" {:kind "gherkin"
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
   "senior-implementer" "senior-implementer"})

(def review-assignment-rules
  {"code-reviewer" "code"
   "architect" "architecture"})

(defn packet-result-missing? [packet kind]
  (not (field-present? packet (str (gate-key kind) "_sha"))))

(defn merged-assignment? [assignment]
  (= "merged" (:state assignment)))

(defn assignment-effective-sha [assignment]
  (artifact-sha assignment))

(defn packet-iteration-mentions-assignment?
  "True when packet history lists assignment-id under the given iterations field
  (e.g. cleaner_iterations: alpha-cleaner=recorded). Used so clear-downstream
  does not get undone by re-recording the same merged assignment."
  [packet iterations-field assignment-id]
  (let [iters (str (get packet iterations-field ""))]
    (and (not (str/blank? assignment-id))
         (not (str/blank? iters))
         (str/includes? iters (str assignment-id "=")))))

(defn result-recorded-in-iterations?
  [packet kind assignment-id]
  (packet-iteration-mentions-assignment?
   packet (str (gate-key kind) "_iterations") assignment-id))

(defn packet-result-stale-for-assignment?
  "True when a merged assignment should re-record its result on the packet.
  Prevents implementer thrash: reworks never re-recorded while implementation_sha
  already existed."
  [packet kind assignment]
  (let [kind-key (gate-key kind)
        sha-field (str kind-key "_sha")
        assignment-field (str kind-key "_assignment")
        current-sha (get packet sha-field)
        current-assignment (get packet assignment-field)
        new-id (:assignment-id assignment)
        new-sha (assignment-effective-sha assignment)
        new-rank (assignment-revision-rank new-id)
        old-rank (assignment-revision-rank current-assignment)]
    (cond
      (str/blank? current-sha) true
      (str/blank? new-id) false
      (str/blank? current-assignment) true
      (< new-rank old-rank) false
      (> new-rank old-rank) true
      (not= new-id current-assignment) true
      :else (and (not (str/blank? new-sha))
                 (not= new-sha current-sha)))))

(defn should-record-merged-result?
  "Whether residual should write this merged assignment onto the packet.
  If clear-downstream removed the sha but iterations still show this
  assignment was already recorded, leave it cleared so a fresh cycle can start
  (do not re-apply superseded cleaner/hardener/etc.)."
  [packet kind assignment]
  (let [missing? (packet-result-missing? packet kind)
        already? (result-recorded-in-iterations? packet kind (:assignment-id assignment))]
    (cond
      (and missing? already?) false
      missing? true
      :else (packet-result-stale-for-assignment? packet kind assignment))))

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
               (should-record-merged-result? packet kind assignment)
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

(defn assignment-result-story-ids [assignment]
  (let [listed (split-list (:batch-stories assignment))]
    (if (seq listed)
      listed
      (when (and (:story-id assignment)
                 (not= "batch" (:story-id assignment)))
        [(:story-id assignment)]))))

(defn direct-result-record-candidates [assignments packets]
  (let [packets-by-story (packet-by-story packets)]
    (->> (for [assignment assignments
               :let [kind (get result-assignment-rules (:template assignment))]
               :when kind
               story-id (assignment-result-story-ids assignment)
               :let [packet (get packets-by-story story-id)
                     candidate (when packet
                                 (result-record-candidate packet assignment kind))]
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

(defn assignment-batch-id
  "Batch membership lives under batch_id. Replacements set batch_id to the
  original batch assignment id so merged replacements still project to members."
  [assignment]
  (or (not-empty (:batch-id assignment))
      (when (= "batch" (:story-id assignment))
        (:assignment-id assignment))
      (:assignment-id assignment)))

(defn batch-result-record-candidate [root packets-by-story assignment kind member]
  (let [story-id (:story-id member)
        packet (get packets-by-story story-id)
        sha (batch-effective-sha root assignment)
        batch-id (assignment-batch-id assignment)]
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
       :batch-id batch-id
       :reason (str "merged " kind " batch result must be recorded in story packet")
       :command (str "squad_packet.sh record " story-id " " kind " "
                     (:assignment-id assignment) " master " sha)})))

(defn inferred-batch-member-rows
  "Senior-implementer reform often has no manifest. Infer members from
  every packet waiting on SI — never by theme."
  [root assignment kind]
  (when (and (= "batch" (:story-id assignment))
             (= "senior-implementer" kind))
    (->> (packets root)
         (filter #(and (field-changes-requested? % "architecture_review")
                       (packet-result-missing? % kind)))
         (map (fn [packet]
                {:story-id (get packet "story_id" (get packet "_story_id"))
                 :stage "architecture_changes_requested"
                 :assignment-id (:assignment-id assignment)
                 :branch "master"
                 :sha nil}))
         vec)))

(defn batch-member-rows [root assignment kind]
  (let [rows (batch-manifest-rows root (assignment-batch-id assignment))]
    (if (seq rows)
      rows
      (or (inferred-batch-member-rows root assignment kind) []))))

(defn batch-result-record-candidates [root assignments packets]
  (let [packets-by-story (packet-by-story packets)]
    (->> (for [assignment assignments
               :let [kind (get result-assignment-rules (:template assignment))]
               :when (and kind (= "batch" (:story-id assignment)))
               member (batch-member-rows root assignment kind)
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
  (let [batch-id (assignment-batch-id assignment)
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
       :assignment-id (:assignment-id assignment)
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
    (fs/path root "reviews" (str (:assignment-id assignment) ".md"))
    (fs/path root ".squad" "reviews" (str (:assignment-id assignment) ".md"))]
   (map #(fs/path root %) (artifact-paths assignment "reviews/" ".md"))
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

(defn review-already-recorded-for-assignment?
  "After clear-downstream, review_* fields are gone but *_review_iterations
  still lists the assignment decision. Do not re-apply that superseded decision."
  [packet review-field assignment-id]
  (packet-iteration-mentions-assignment?
   packet (str review-field "_iterations") assignment-id))

(defn review-target-stage-missing?
  "True when the stage this review is about is intentionally absent (e.g. code
  review after implementation rework cleared cleaner_sha)."
  [kind packet]
  (case kind
    "code" (not (field-present? packet "cleaner_sha"))
    "gherkin" (not (field-present? packet "gherkin_sha"))
    "qa-procedure" (not (field-present? packet "qa_procedure_sha"))
    false))

(defn review-record-candidate-for-story [root packet assignment kind decision]
  (let [story-id (get packet "story_id" (get packet "_story_id"))
        review-field (str (gate-key kind) "_review")
        sha (assignment-effective-sha assignment)]
    (when (and (not (str/blank? sha))
               decision
               (not (packet-review-current-for-assignment? packet review-field assignment))
               (not (review-record-superseded? packet review-field))
               (not (review-already-recorded-for-assignment? packet review-field
                                                             (:assignment-id assignment)))
               (not (review-target-stage-missing? kind packet)))
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

(defn stale-changes-requested? [packet review-field]
  (and (= "changes-requested" (get packet review-field))
       (not (squad-state/review-current? packet review-field))))

(defn packet-repair-candidates [root]
  (let [assignments (assignment-records root)
        packets (packets root)]
    (vec (concat (artifact-attachment-candidates root assignments packets)
                 (direct-result-record-candidates assignments packets)
                 (batch-result-record-candidates root assignments packets)
                 (batch-complete-candidates root assignments packets)
                 (review-record-candidates root assignments packets)))))

(defn packet-by-story [packets]
  (into {}
        (map (fn [packet]
               [(get packet "story_id" (get packet "_story_id")) packet]))
        packets))

(defn requirement-satisfied? [root packet requirement]
  (if (str/blank? requirement)
    true
    (let [[kind gate] (str/split requirement #":" 2)]
      (and (= "approval" kind)
           (some? gate)
           (boolean packet)
           (approval-satisfied? root packet gate)))))

(defn assignment-file-ok? [assignment-file]
  (and (not (str/blank? assignment-file))
       (fs/regular-file? (fs/path assignment-file))))

(defn ready-assignment-requirement-ok? [root packet requires]
  (or (str/blank? requires)
      (requirement-satisfied? root packet requires)))

(defn generic-ready-assignment? [root packet agents
                                 {:keys [assignment-id template assignment-file state requires]}]
  (and (assignment-created? state)
       (assignment-file-ok? assignment-file)
       (ready-assignment-requirement-ok? root packet requires)
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
        packet-map (packet-by-story (packets root))]
    (->> (for [assignment assignments
               :let [packet (get packet-map (:story-id assignment))]
               :when (generic-ready-assignment? root packet agents assignment)]
           (generic-ready-candidate assignment))
         (sort-by (juxt :priority :theme-id :story-id :created-at :assignment-id))
         vec)))

(def story-transition-table
  [{:id :analyst-plan-assignment
    :priority 60
    :stage-order 5
    :candidate (fn [ctx packet]
                 (when (awaiting-implementation-plan? packet)
                   (assignment-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
                                         "analyst" "analysis"
                                         "started story needs an implementation plan" 60 5 nil)))}
   {:id :implementation-plan-approval
    :priority 30
    :stage-order 8
    :candidate (fn [ctx packet]
                 (when (field-present? packet "implementation_plan_path")
                   (approval-candidate (:root ctx) packet "implementation-plan"
                                       "Approve_implementation_plan"
                                       "implementation-plan-ready" 30 8)))}
   {:id :gherkin-assignment
    :priority 60
    :stage-order 20
    :candidate (fn [ctx packet]
                 (when (and (field-present? packet "implementation_plan_path")
                            (approval-satisfied? (:root ctx) packet "implementation-plan")
                            (not (field-present? packet "gherkin_path")))
                   (assignment-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
                                         "gherkin-writer" "gherkin"
                                         "approved story needs Gherkin" 60 20 nil)))}
   {:id :qa-procedure-assignment
    :priority 60
    :stage-order 30
    :candidate (fn [ctx packet]
                 (when (and (field-present? packet "implementation_plan_path")
                            (approval-satisfied? (:root ctx) packet "implementation-plan")
                            (not (field-present? packet "qa_procedure_path")))
                   (assignment-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
                                         "qa-procedure-writer" "qa-procedure"
                                         "approved story needs QA procedure" 60 30 nil)))}
   {:id :gherkin-approval
    :priority 30
    :stage-order 60
    :candidate (fn [ctx packet]
                 (when (field-present? packet "gherkin_path")
                   (approval-candidate (:root ctx) packet "gherkin" "Approve_Gherkin" "gherkin-written" 30 60)))}
   {:id :qa-procedure-approval
    :priority 30
    :stage-order 70
    :candidate (fn [ctx packet]
                 (when (field-present? packet "qa_procedure_path")
                   (approval-candidate (:root ctx) packet "qa-procedure" "Approve_QA_procedure" "qa-procedure-written" 30 70)))}
   {:id :implementation-assignment
    :priority 60
    :stage-order 90
    :candidate (fn [ctx packet]
                 (when (and (approval-satisfied? (:root ctx) packet "implementation-plan")
                            (approval-satisfied? (:root ctx) packet "gherkin")
                            (approval-satisfied? (:root ctx) packet "qa-procedure")
                            (field-present? packet "gherkin_path")
                            (not (field-present? packet "implementation_sha")))
                   (assignment-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
                                         "implementer" "implementation"
                                         "story is approved for implementation" 60 90
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
   {:id :hardener-assignment
    :priority 60
    :stage-order 130
    :candidate (fn [ctx packet]
                 (when (and (hardener-member-ready? (:root ctx) packet)
                            (not (field-present? packet "hardener_batch")))
                   (batch-candidate (:root ctx) (:assignments ctx) packet "hardener" "hardener"
                                    "code_reviewed"
                                    "code-reviewed story is ready for hardener batch"
                                    60 125
                                    (if (field-present? packet "code_review_sha")
                                      "code_review"
                                      "cleaner"))))}
   {:id :qa-assignment
    :priority 60
    :stage-order 150
    :candidate (fn [ctx packet]
                 (when (and (qa-member-ready? (:root ctx) packet)
                            (not (field-present? packet "qa_batch")))
                   (batch-candidate (:root ctx) (:assignments ctx) packet "qa" "qa"
                                    "hardening_approved"
                                    "hardened story is ready for QA batch"
                                    60 145 "hardener")))}
   {:id :architect-assignment
    :priority 60
    :stage-order 170
    :candidate (fn [ctx packet]
                 (when (and (architecture-member-ready? (:root ctx) packet)
                            (not (field-present? packet "architecture_batch")))
                   (batch-candidate (:root ctx) (:assignments ctx) packet "architecture" "architecture"
                                    "qa_approved"
                                    "QA-verified story is ready for architecture batch"
                                    60 165 "qa")))}
   {:id :senior-implementer-batch
    :priority 60
    :stage-order 166
    :candidate (fn [ctx packet]
                 (when (and (field-changes-requested? packet "architecture_review")
                            (not (field-present? packet "senior_implementer_sha"))
                            (not (field-present? packet "architecture_fix_batch")))
                   (batch-candidate (:root ctx) (:assignments ctx) packet "architecture-fix" "architecture-fix"
                                    "architecture_changes_requested"
                                    "architecture critique needs senior implementation"
                                    60 166 "architecture")))}])

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

(defn hardener-member-ready? [root packet]
  (and (not (field-present? packet "hardener_sha"))
       (field-present? packet "code_review_sha")
       (or (field-accepted? packet "code_review")
           (field-changes-requested? packet "code_review"))))

(defn hardener-stage-clear? [root packet]
  (or (field-present? packet "hardener_sha")
      (field-present? packet "hardener_batch")
      (hardener-member-ready? root packet)))

(defn qa-member-ready? [root packet]
  (and (field-present? packet "hardener_sha")
       (not (field-present? packet "qa_sha"))))

(defn qa-stage-clear? [root packet]
  (or (field-present? packet "qa_sha")
      (field-present? packet "qa_batch")
      (qa-member-ready? root packet)))

(defn architecture-member-ready? [root packet]
  (and (field-present? packet "qa_sha")
       (not (field-present? packet "senior_implementer_sha"))
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
                       (not (field-present? % "senior_implementer_sha")))
                 packets)))

(defn unbatched-hardener-member-ready? [root packet]
  (and (hardener-member-ready? root packet)
       (not (field-present? packet "hardener_batch"))))

(defn unbatched-qa-member-ready? [root packet]
  (and (qa-member-ready? root packet)
       (not (field-present? packet "qa_batch"))))

(defn unbatched-architecture-member-ready? [root packet]
  (and (architecture-member-ready? root packet)
       (not (field-present? packet "architecture_batch"))))

(defn si-member-ready? [root packet]
  (and (field-changes-requested? packet "architecture_review")
       (not (field-present? packet "senior_implementer_sha"))))

(defn unbatched-si-member-ready? [root packet]
  (and (si-member-ready? root packet)
       (not (field-present? packet "architecture_fix_batch"))))

(defn any-unbatched-hardener-member-ready? [root packets]
  (boolean (some #(unbatched-hardener-member-ready? root %) packets)))

(defn any-unbatched-qa-member-ready? [root packets]
  (boolean (some #(unbatched-qa-member-ready? root %) packets)))

(defn any-unbatched-architecture-member-ready? [root packets]
  (boolean (some #(unbatched-architecture-member-ready? root %) packets)))

(defn any-unbatched-si-member-ready? [root packets]
  (boolean (some #(unbatched-si-member-ready? root %) packets)))

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

(defn next-batch-id [root assignments _theme-id requested-kind suffix]
  (let [base suffix
        batches (batch-records root)
        batch-ids (set (map :batch-id batches))]
    (or (reusable-batch-id batches assignments requested-kind base)
        (unique-batch-id assignments batch-ids base))))

(def batch-action-rules
  [{:ready? :hardener-ready?
    :template "hardener"
    :suffix "hardener"
    :reason "hardener batch is ready"
    :stage-order 130}
   {:ready? :qa-ready?
    :template "qa"
    :suffix "qa"
    :reason "QA batch is ready"
    :stage-order 150}
   {:ready? :senior-ready?
    :template "senior-implementer"
    :suffix "architecture-fix"
    :reason "architecture critique needs senior implementation"
    :stage-order 166}
   {:ready? :architecture-ready?
    :template "architect"
    :suffix "architecture"
    :reason "architecture batch is ready after QA"
    :stage-order 170}])

(defn batch-readiness [root packets]
  (let [batches (batch-records root)]
    {:hardener-ready? (and (seq packets)
                           (or (batch-id-needing-result packets "hardener_batch" "hardener_sha")
                               (any-unbatched-hardener-member-ready? root packets)
                               (open-batch-with-members batches "hardener" "hardener")))
     :qa-ready? (and (seq packets)
                     (or (batch-id-needing-result packets "qa_batch" "qa_sha")
                         (any-unbatched-qa-member-ready? root packets)
                         (open-batch-with-members batches "qa" "qa")))
     :architecture-ready? (and (seq packets)
                               (or (architecture-batch-needing-review packets)
                                   (any-unbatched-architecture-member-ready? root packets)
                                   (open-batch-with-members batches "architecture" "architecture")))
     :senior-ready? (and (seq packets)
                         (or (batch-id-needing-result packets "architecture_fix_batch" "senior_implementer_sha")
                             (any-unbatched-si-member-ready? root packets)
                             (open-batch-with-members batches "architecture-fix" "architecture-fix")
                             (any-architecture-needs-senior? packets)))}))

(defn batch-candidate-for-rule [root assignments agents readiness
                                {:keys [ready? template suffix reason stage-order]}]
  (when-let [ready-value (get readiness ready?)]
    (let [assignment-base (if (string? ready-value) ready-value suffix)]
      (batch-assignment-candidate root assignments agents nil
                                  template assignment-base
                                  reason 60 stage-order))))

(defn batch-candidates [root rows]
  (let [all-packets (packets root)
        assignments (assignment-records root)
        agents (agent-records root rows)
        readiness (batch-readiness root all-packets)]
    (->> (keep #(batch-candidate-for-rule root assignments agents readiness %)
               batch-action-rules)
         (sort-by (juxt :priority :theme-id :story-id :stage-order :assignment-id))
         vec)))

(defn accept-merge-handoff-step [_root assignment-id reason]
  {:action "accept_merge"
   :reason reason
   :command (str "squad_assign.sh accept-merge " assignment-id)})

(def story-candidate-fields
  [["NEXT_ACTION" :next-action true]
   ["OP" :op false]
   ["AUTHORITY" :authority false]
   ["THEME" :theme-id false]
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
  (let [candidate (actions/ensure-typed candidate)]
    (doseq [field story-candidate-fields]
      (print-candidate-field! candidate field))
    (println "CANDIDATES:" total)
    (println "AUTHORITY:" (:authority candidate))
    (println "COMMAND:" (actions/shell-command candidate))))

(def concurrent-action-fields
  [["CONCURRENT_ACTION_NAME" :next-action true]
   ["CONCURRENT_OP" :op false]
   ["CONCURRENT_AUTHORITY" :authority false]
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
  (let [candidate (actions/ensure-typed candidate)]
    (println "CONCURRENT_ACTION:" index)
    (doseq [field concurrent-action-fields]
      (print-candidate-field! candidate field))))

(defn print-concurrent-actions! [action-list]
  (let [typed (mapv actions/ensure-typed action-list)]
    (println "CONCURRENT_ACTIONS:" (count typed))
    (println "CONCURRENT_ACTION_ORDER:"
             (if (some #(= "retire_agent" (actions/op-of %)) typed)
               "retire_agent commands share the registry lock and must run one at a time; other independent commands may run concurrently"
               "execute listed order when capacity changes depend on prior actions; otherwise independent commands may run concurrently"))
    (doseq [[index action] (map-indexed vector typed)]
      (print-concurrent-action! (inc index) action))))

;; Bookkeeping-only actions: safe to apply all ready instances without capacity scheduling.
(def bookkeeping-actions
  #{"attach_story_artifact"
    "record_merged_result"
    "record_merged_batch_result"
    "complete_batch"
    "record_review_result"
    "record_auto_approval"
    "record_batch_membership"
    "declare_merge_blocker"
    "record_frame_sha"
    "start_snapshot_item"})

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

(defn apply-candidate!
  "Apply via executor under :daemon authority by default."
  [root candidate]
  (executor/apply-candidate! root candidate :daemon))

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

(defn apply-record-frame-sha! [root candidate]
  (product/record-frame-sha! root (:sha candidate))
  (assoc candidate :exit 0 :out "" :err ""))

(defn apply-start-snapshot-item! [root candidate]
  (let [result (web/start-backlog! root (:item-id candidate))]
    (assoc candidate
           :exit (if (:ok result) 0 1)
           :out (str result)
           :err (or (:error result) ""))))

(defn apply-bookkeeping-candidate! [root candidate]
  (case (:next-action candidate)
    "record_frame_sha" (apply-record-frame-sha! root candidate)
    "start_snapshot_item" (apply-start-snapshot-item! root candidate)
    (apply-candidate! root candidate)))

(defn apply-bookkeeping-ready-actions! [root rows]
  (loop [applied []
         remaining 100]
    (let [actions (ready-actions root rows)
          bookkeeping (filter bookkeeping-action? actions)]
      (if (or (zero? remaining) (empty? bookkeeping))
        applied
        (let [results (mapv #(apply-bookkeeping-candidate! root %) bookkeeping)
              failed (some #(when-not (zero? (:exit %)) %) results)
              applied (into applied results)]
          (if failed
            applied
            (recur applied (dec remaining))))))))

(defn apply-mechanical-ready-actions!
  "Backward-compatible name: applies bookkeeping mechanical actions only.
  Daemon-ready actions (create/spawn/approval request) use capacity scheduling."
  [root rows]
  (apply-bookkeeping-ready-actions! root rows))

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

(def persistent-role-names
  "Static roles that are not transient workers. Must not occupy active-transient
  wait capacity or retirement/recovery as if they were spawn fleet."
  #{"squad-leader" "troubleshooter"})

(defn transient-row? [row]
  (not (contains? persistent-role-names (first row))))

(defn agent-state [root agent]
  (get (file-map (fs/path root ".squad" "agents" agent "status")) "state" "unknown"))

(defn completed-handoff-records [root]
  (->> (files-with-extension (fs/path root ".swarmforge" "handoffs" "inbox" "completed") ".handoff")
       (map (fn [file]
              {:agent (handoff-sender file)
               :assignment-id (handoff-task file)}))
       (remove #(= "unknown" (:agent %)))))

(defn assignment-result-recorded? [root assignment-id]
  (fs/regular-file? (fs/path root ".squad" "assignments" assignment-id "result")))

(defn assignment-status-state [root assignment-id]
  (get (file-map (fs/path root ".squad" "assignments" assignment-id "status")) "state" "unknown"))

(defn assignment-dir-exists? [root assignment-id]
  (fs/directory? (fs/path root ".squad" "assignments" assignment-id)))

(def resolved-handoff-assignment-states
  #{"merged" "rejected" "blocked" "replacement_created" "superseded"
    "review_accepted" "review_changes_requested" "cancelled" "abandoned"})

(defn completed-handoff-retirable? [root {:keys [agent assignment-id]}]
  "Retire only when the assignment handoff is terminal."
  (and (not= "unknown" agent)
       (if (assignment-dir-exists? root assignment-id)
         (contains? resolved-handoff-assignment-states
                    (assignment-status-state root assignment-id))
         true)))

(defn assignment-accepted-merge? [root assignment-id]
  (= "merged"
     (get (file-map (fs/path root ".squad" "assignments" assignment-id "accepted-merge"))
          "state")))

(defn assignment-merge-file-state [root assignment-id]
  (get (file-map (fs/path root ".squad" "assignments" assignment-id "merge"))
       "state"))

(defn in-process-git-handoff-command [root file]
  (let [assignment-id (handoff-task file)
        state (assignment-status-state root assignment-id)
        merge-state (assignment-merge-file-state root assignment-id)
        already-merged? (or (= "merged" state)
                            (assignment-accepted-merge? root assignment-id))]
    (when (and (= "git_handoff" (handoff-type file))
               (assignment-dir-exists? root assignment-id))
      (cond
        already-merged?
        {:action "finish_in_process_handoff"
         :reason "assignment already merged; complete the claimed handoff"
         :command (str "SWARMFORGE_ROLE=squad-leader done_with_current.sh "
                       (pr-str (str file)))}

        ;; Status can lag merge file when result was re-recorded after merge-ready.
        (and (= "result_received" state)
             (= "merge_ready" merge-state))
        (accept-merge-handoff-step
         root assignment-id
         "merge readiness already recorded; accept merge before handoff completion")

        :else
        (case state
          ("created" "assignment_created" "in_progress" "handoff_sent" "unknown")
          {:action "record_assignment_result"
           :reason "claimed git handoff must be recorded before completion"
           :command (str "squad_assign.sh result " assignment-id " " file)}

          "result_received"
          (accept-merge-handoff-step
           root assignment-id
           "recorded result is merged by the squad leader")

          "merge_ready"
          (accept-merge-handoff-step
           root assignment-id
           "merge-ready result must be accepted before handoff completion")

          nil)))))

(defn in-process-needs-action?
  "True when an in-process handoff is claimed and must be advanced or finished."
  [{:keys [in-process]}]
  (boolean in-process))

(defn print-in-process-handoff-action! [root file]
  (if-let [{:keys [action reason command]} (in-process-git-handoff-command root file)]
    (print-handoff-action! action file reason command)
    (print-handoff-action! "finish_in_process_handoff"
                           file
                           "handoff is already claimed and must be completed before new mail"
                           (str "SWARMFORGE_ROLE=squad-leader done_with_current.sh " file))))

(def daemon-handoff-step-actions
  #{"record_assignment_result"
    "finish_in_process_handoff"})

(defn visible-handoff-agents [root]
  (->> ["new" "in_process" "completed"]
       (mapcat #(files-with-extension (fs/path root ".swarmforge" "handoffs" "inbox" %) ".handoff"))
       (map handoff-sender)
       (remove #{"unknown"})
       set))

(defn agent-task-id [root agent]
  (get (file-map (fs/path root ".squad" "agents" agent "metadata")) "task_id"))

(defn agent-assignment-retirable? [root agent]
  (completed-handoff-retirable?
   root
   {:agent agent
    :assignment-id (or (agent-task-id root agent) "unknown")}))

(defn retirement-candidates [root rows]
  (let [completed (->> (completed-handoff-records root)
                       (filter #(completed-handoff-retirable? root %))
                       (map :agent)
                       set)]
    (->> rows
         (keep (fn [row]
                 (let [agent (first row)
                       state (agent-state root agent)
                       retirable? (or (contains? completed agent)
                                      (and (= "retired" state)
                                           (agent-assignment-retirable? root agent)))]
                   (when (and (transient-row? row) retirable?)
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

(defn terminal-assignment-states-for-repair []
  #{"merged" "rejected" "blocked" "replacement_created" "superseded"
    "review_accepted" "review_changes_requested" "cancelled" "abandoned"})

(defn assignment-open-for-repair? [root task-id]
  (when (and task-id (not (str/blank? task-id)) (not= "unknown" task-id))
    (let [state (get (file-map (fs/path root ".squad" "assignments" task-id "status")) "state")]
      (boolean (and state (not (contains? (terminal-assignment-states-for-repair) state)))))))

(defn agent-session-live? [root agent]
  (let [meta (file-map (fs/path root ".squad" "agents" agent "metadata"))
        session (get meta "session")
        socket-file (fs/path root ".swarmforge" "tmux-socket")
        socket (when (fs/regular-file? socket-file)
                 (str/trim (slurp (str socket-file))))]
    (and (not (str/blank? session))
         (not (str/blank? socket))
         (zero? (:exit (process/sh {:continue true}
                                   "tmux" "-S" socket "has-session" "-t" session))))))

(defn agent-worktree-dirty? [root agent]
  (let [worktree (get (file-map (fs/path root ".squad" "agents" agent "metadata")) "worktree")]
    (when (and worktree (fs/directory? worktree))
      (let [out (:out (process/sh {:continue true}
                                  "git" "-C" (str worktree)
                                  "status" "--porcelain=v1" "--untracked-files=all"))]
        (boolean (seq (remove str/blank? (str/split-lines out))))))))

(defn session-dead-repair-candidate?
  "Quiet agent, session gone, open assignment → repair residual (not vague recover)."
  [root {:keys [agent task-id] :as record}]
  (and (active-agent? record)
       (assignment-open-for-repair? root task-id)
       (not (agent-session-live? root agent))))

(defn recovery-candidate-for-agent [root now threshold retry-threshold
                                    {:keys [agent task-id state last-activity-at activity-source] :as record}]
  (when (active-agent? record)
    (let [quiet-for (recovery-quiet-for last-activity-at now)]
      (when (recovery-agent-due? root now threshold retry-threshold agent quiet-for)
        (let [base (recovery-candidate-record threshold retry-threshold quiet-for record)
              repair? (session-dead-repair-candidate? root record)
              dirty? (boolean (agent-worktree-dirty? root agent))]
          (cond-> base
            repair? (assoc :repair? true
                           :repair-owner (if dirty? "troubleshooter" "squad-leader")
                           :dirty? dirty?)))))))

(defn recovery-candidate [root rows]
  (let [now (now-instant)
        threshold (cfg/squad-recovery-quiet-seconds root)
        retry-threshold (cfg/squad-recovery-retry-seconds root)]
    (some #(recovery-candidate-for-agent root now threshold retry-threshold %)
          (agent-records root rows))))

(defn print-recovery-action! [{:keys [agent task-id state last-activity-at activity-source quiet-for threshold retry-threshold
                                      repair? repair-owner dirty?]}]
  (if repair?
    (do
      (println "NEXT_ACTION: repair_dead_agent")
      (println "OP: repair_dead_agent")
      (println "AUTHORITY:" (if (= "troubleshooter" repair-owner) ":troubleshooter" ":sl-residual"))
      (println "REPAIR_OWNER:" repair-owner)
      (println "AGENT:" agent)
      (println "TASK_ID:" (or task-id "unknown"))
      (println "STATE:" state)
      (println "DIRTY_WORKTREE:" (if dirty? "true" "false"))
      (println "LAST_ACTIVITY_AT:" (or last-activity-at "none"))
      (println "ACTIVITY_SOURCE:" activity-source)
      (println "QUIET_FOR_SECONDS:" quiet-for)
      (println "REASON: session dead with open assignment — remove agent, clear death blockers, requeue same task")
      (println "REPAIR_PLAN: remove_dead_agent; clear_death_blockers; requeue_assignment")
      (println "COMMAND:" (str "squad_recover.sh repair " agent))
      (println "CLASSIFY_FIRST:" (str "squad_recover.sh " agent))
      (when (= "troubleshooter" repair-owner)
        (println "NOTE: Dirty worktree — Troubleshooter/operator should run repair (archives worktree then requeues)."))
      (when (= "squad-leader" repair-owner)
        (println "NOTE: Clean dead session — Squad Leader residual may run repair to free slot and requeue task.")))
    (do
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
      (println "COMMAND:" (str "squad_recover.sh " agent)))))

(defn active-transients [root rows]
  (let [now (now-instant)]
    (->> (agent-records root rows)
         (map (fn [agent]
                (assoc agent :quiet-for (seconds-between (:last-activity-at agent) now))))
         (filter active-agent?)
         vec)))

(defn print-wait-action!
  ([active] (print-wait-action! nil active))
  ([root active]
   (let [reason (if (seq active)
                  "active agents are still working or awaiting handoff delivery"
                  "no handoffs, pending approvals, active transient agents, or stale locks")]
     (println "NEXT_ACTION: wait")
     (println "REASON:" reason)
     (doseq [{:keys [agent task-id state quiet-for activity-source]} active]
       (println "ACTIVE:" agent task-id state
                (str "quiet_for=" (or quiet-for "unknown"))
                (str "activity_source=" activity-source)))
     (println "CHECK_AFTER_SECONDS: 30")
     (println "COMMAND: sleep 30 && squad_next.sh"))))

(defn ready-actions [root rows]
  (sort-by (juxt :priority :theme-id :stage-order :story-id :assignment-id)
           (concat (product-frame-candidates root rows)
                   (packet-repair-candidates root)
                   (story-candidates root rows)
                   (batch-candidates root rows)
                   (generic-ready-assignment-candidates root rows))))

(defn rows-without-agents [rows agents]
  (remove #(contains? agents (first %)) rows))

(defn capacity-used [root agents]
  (count (filter #(capacity-counted-agent? root %) agents)))

(defn active-singleton-templates [root agents]
  (->> (cfg/singleton-templates root)
       (filter #(capacity-active-template? root agents %))
       set))

(defn spawn-action? [action]
  (= "request_spawn" (:next-action action)))

(defn queues-spawn? [action]
  "True when applying this action will create a spawn request (capacity-relevant)."
  (or (spawn-action? action)
      (and (= "create_assignment" (:next-action action))
           (str/includes? (str (:command action)) "--queue-spawn"))))

(defn singleton-spawn-blocked? [root active-singletons action]
  (and (contains? (cfg/singleton-templates root) (:template action))
       (contains? active-singletons (:template action))))

(defn spawn-fits? [root used max-agents active-singletons action]
  (and (not (singleton-spawn-blocked? root active-singletons action))
       (< used max-agents)))

(defn account-spawn [root used active-singletons action]
  (let [singletons (cond-> active-singletons
                     (contains? (cfg/singleton-templates root) (:template action))
                     (conj (:template action)))]
    [(inc used) singletons]))

(defn action-dependency-keys [{:keys [next-action story-id assignment-id batch-id agent gate template batch-kind]}]
  (set
   (concat
    (when (and story-id
               (not= "batch" story-id)
               (contains? #{"attach_story_artifact"
                            "record_merged_result"
                            "record_merged_batch_result"
                            "record_review_result"
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
    (if-not (queues-spawn? action)
      (-> state
          (update :actions conj action)
          (account-dependencies action))
      (let [{:keys [root used max-agents active-singletons]} state]
        (if-not (spawn-fits? root used max-agents active-singletons action)
          state
          (let [[used active-singletons] (account-spawn root used active-singletons action)]
            (-> state
                (assoc :used used :active-singletons active-singletons)
                (update :actions conj action)
                (account-dependencies action))))))))

(defn schedule-concurrent-actions [root rows retire-actions ready-actions]
  (let [retired-agents (set (keep :agent retire-actions))
        adjusted-rows (rows-without-agents rows retired-agents)
        agents (agent-records root adjusted-rows)
        ;; Retirements share the registry lock — schedule at most one, then ready work.
        initial {:root root
                 :used (capacity-used root agents)
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
      :pending-dashboard-request (oldest-pending-dashboard-request root)
      :durable-blocker (oldest-durable-blocker root)
      :pending-approval-file (pending-approval root)}
     concurrent)))

(def action-rule-predicates
  "Predicates for residual classes. Order comes from plane/residual-class-order."
  {:finish-in-process in-process-needs-action?
   :process-handoff :new-handoff
   :stale-lock :stale-lock-info
   :pending-spawn :pending-spawn-file
   ;; Operator dashboard requests beat story FSM residual work and approval framing.
   :dashboard-request :pending-dashboard-request
   :retire :retire-candidate
   :recover :recover-candidate
   ;; Durable blockers outrank ordinary story ready-actions so SL cannot claim "no blocker"
   ;; while .squad/blockers/ still has open rejection/assignment blockers.
   :durable-blocker :durable-blocker
   :ready-action #(seq (:ready-actions %))
   :pending-approval :pending-approval-file})

(def action-rules
  "Residual ranking is plane/residual-class-order, not ad-hoc list order here."
  (mapv (fn [class]
          [class (get action-rule-predicates class (constantly false))])
        (remove #{:wait} plane/residual-class-order)))

(defn action-rule-matches? [ctx [_ predicate]]
  (if (keyword? predicate)
    (get ctx predicate)
    (predicate ctx)))

(defn action-printer [ctx]
  "Select residual class via control-plane policy."
  (let [presence {:in-process-needs-action? (in-process-needs-action? ctx)
                  :new-handoff (:new-handoff ctx)
                  :stale-lock-info (:stale-lock-info ctx)
                  :pending-spawn-file (:pending-spawn-file ctx)
                  :pending-dashboard-request (:pending-dashboard-request ctx)
                  :retire-candidate (:retire-candidate ctx)
                  :recover-candidate (:recover-candidate ctx)
                  :durable-blocker (:durable-blocker ctx)
                  :ready-actions (:ready-actions ctx)
                  :pending-approval-file (:pending-approval-file ctx)}
        class (plane/select-residual-class presence)]
    class))

(def action-print-handlers
  {:finish-in-process
   (fn [{:keys [root in-process]}]
     (print-in-process-handoff-action! root in-process))
   :process-handoff
   (fn [{:keys [new-handoff]}]
     (print-handoff-action! "process_handoff" new-handoff "new handoff mail is waiting" "ready_for_next.sh"))
   :stale-lock (fn [{:keys [stale-lock-info]}] (print-stale-lock-action! stale-lock-info))
   :pending-spawn (fn [{:keys [pending-spawn-file]}] (print-spawn-wait-action! pending-spawn-file))
   :dashboard-request (fn [{:keys [pending-dashboard-request]}]
                        (print-dashboard-request-action! pending-dashboard-request))
   :retire (fn [{:keys [retire-candidate concurrent-actions]}]
             (print-retirement-action! retire-candidate)
             (print-concurrent-actions! concurrent-actions))
   :recover (fn [{:keys [recover-candidate]}] (print-recovery-action! recover-candidate))
   :durable-blocker (fn [{:keys [durable-blocker]}]
                      (print-durable-blocker-action! durable-blocker))
   :ready-action (fn [{:keys [ready-actions concurrent-actions]}]
                   (print-story-candidate! (first ready-actions) (count ready-actions))
                   (print-concurrent-actions! concurrent-actions))
   :pending-approval (fn [{:keys [pending-approval-file]}] (print-approval-action! pending-approval-file))
   :wait (fn [{:keys [root rows]}] (print-wait-action! root (active-transients root rows)))})

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

(defn handoff-inbox-dir [root]
  (fs/path root ".swarmforge" "handoffs" "inbox"))

(defn apply-in-process-handoff-step!
  "Apply at most one deterministic in-process handoff step."
  [root]
  (let [file (first (files-with-extension
                     (fs/path (handoff-inbox-dir root) "in_process")
                     ".handoff"))]
    (when file
      (if-let [{:keys [action command]} (in-process-git-handoff-command root file)]
        (when (contains? daemon-handoff-step-actions action)
          [(apply-candidate! root {:next-action action
                                   :assignment-id (handoff-task file)
                                   :command command})])
        (when (in-process-needs-action? {:root root :in-process file})
          [(apply-candidate! root {:next-action "finish_in_process_handoff"
                                   :assignment-id (handoff-task file)
                                   :command (str "done_with_current.sh " file)})])))))

(defn apply-process-new-handoff-step!
  "Claim the next new handoff into in_process when the inbox is free."
  [root]
  (let [inbox (handoff-inbox-dir root)
        in-process (first (files-with-extension (fs/path inbox "in_process") ".handoff"))
        new-handoff (first (files-with-extension (fs/path inbox "new") ".handoff"))]
    (when (and new-handoff (nil? in-process))
      [(apply-candidate! root {:next-action "process_handoff"
                               :command "SWARMFORGE_ROLE=squad-leader ready_for_next.sh"})])))

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

(defn residual-only!
  "Squad-leader residual, including merge of the handed SHA."
  []
  (print-selected-action! (next-action-context)))

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
          (recur (into applied batch) (dec remaining)))))))

(defn -main [& args]
  (case (count args)
    0 (next-action!)
    1 (case (first args)
        "--apply-mechanical" (apply-mechanical-and-print-next!)
        "--residual-only" (residual-only!)
        (exit! 1 usage-text))
    (exit! 1 usage-text)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
