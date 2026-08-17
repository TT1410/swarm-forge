#!/usr/bin/env bb

(ns squad-next
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-actions :as actions]
            [squad-config :as cfg]
            [squad-control-plane :as plane]
            [squad-executor :as executor]
            [squad-state :as squad-state]
            [clojure.edn :as edn]
            [clojure.set]
            [clojure.string :as str]))

(def usage-text
  "Usage: squad_next.sh [--apply-mechanical | --residual-only]")

(def script-dir
  (fs/parent *file*))

(def ^:dynamic *sl-facing-residual?*
  "When true, main-git merge-ready/accept residual becomes wait_for_daemon_main_git
  so the squad leader does not race squadd."
  false)

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
  (TOCTOU during agent retire) return {} — never throw (P1 B08)."
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
  "First line of body, truncated for unmissable residual display (B55)."
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
    ;; B55: put intent where collapsed tool UIs still show it
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
  "Agents count as active until retired. Transient failed/blocked states still
  occupy capacity so a recovering agent cannot free a slot for a new spawn."
  (not= "retired" (:state agent)))
(defn active-assignment? [agents assignment-id]
  (boolean (some #(and (= assignment-id (:task-id %)) (active-agent? %)) agents)))

(defn active-template? [agents template]
  (boolean (some #(and (= template (:template %)) (active-agent? %)) agents)))

(def singleton-templates #{"hardener" "qa" "architect" "merger"})

(defn handoff-visible-agent? [root agent]
  (contains? (visible-handoff-agents root) agent))

(defn capacity-counted-agent? [root agent]
  "Agents that consume max_transient_agents slots. Merger is singleton-gated
  separately and does not consume the general transient budget."
  (and (active-agent? agent)
       (not= "merger" (:template agent))
       (not (and (= "handoff_sent" (:state agent))
                 (handoff-visible-agent? root (:agent agent))))))

(defn merger-holds-capacity-slot?
  "Merger agents that have only handed off while their assignment is merge_blocked
  no longer monopolize the singleton merger slot (B27)."
  [root agent]
  (and (active-agent? agent)
       (= "merger" (:template agent))
       (not (and (= "handoff_sent" (:state agent))
                 (= "merge_blocked"
                    (get (file-map (fs/path root ".squad" "assignments" (:task-id agent) "status"))
                         "state"))))))

(defn capacity-active-template? [root agents template]
  "True when a live agent already holds this template for capacity/singleton purposes.
  Merger ignores handoff_sent agents whose assignment is merge_blocked so recovery
  can start a second merger."
  (boolean
   (some (fn [agent]
           (and (= template (:template agent))
                (if (= "merger" template)
                  (merger-holds-capacity-slot? root agent)
                  (capacity-counted-agent? root agent))))
         agents)))

(defn spawn-capacity? [root agents template]
  (let [active (filter #(capacity-counted-agent? root %) agents)
        max-agents (cfg/squad-max-transient-agents root)]
    (and (or (= "merger" template)
             (< (count active) max-agents))
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

(defn theme-module-map-path [theme-dir]
  (fs/path theme-dir "module-map.md"))

(defn theme-module-map-present? [theme-dir]
  (fs/regular-file? (theme-module-map-path theme-dir)))

(defn theme-lifecycle
  "B23: open (default) or finalized. Stored in lifecycle file or status."
  [theme-dir]
  (let [life (fs/path theme-dir "lifecycle")
        status (fs/path theme-dir "status")]
    (or (when (fs/regular-file? life)
          (get (file-map life) "lifecycle"))
        (when (fs/regular-file? status)
          (or (get (file-map status) "lifecycle")
              (when (= "finalized" (get (file-map status) "state"))
                "finalized")))
        "open")))

(defn theme-finalized? [theme-dir]
  (= "finalized" (theme-lifecycle theme-dir)))

(defn theme-records [root]
  (->> (theme-dirs root)
       (map (fn [dir]
              (let [theme-id (fs/file-name dir)]
                {:theme-id theme-id
                 :theme-dir dir
                 :module-map-present? (theme-module-map-present? dir)
                 :approved-theme? (theme-approved? dir "theme")
                 :lifecycle (theme-lifecycle dir)
                 :finalized? (theme-finalized? dir)})))
       vec))

(defn packet-story-done?
  "Story finished its product pipeline (final approved)."
  [packet]
  (or (= "final_approved" (get packet "state"))
      (= "final_approved" (get packet "final_state"))
      (field-approved? packet "final_approval")))

(defn theme-packets [root theme-id]
  (filterv #(= theme-id (get % "theme_id")) (packets root)))

(defn theme-slice-complete?
  "True when theme has at least one packet and every packet is final-approved."
  [root theme-id]
  (let [ps (theme-packets root theme-id)]
    (and (seq ps) (every? packet-story-done? ps))))

(defn theme-has-open-assignment?
  [root theme-id]
  (boolean
   (some (fn [a]
           (and (= theme-id (:theme-id a))
                (not (contains? #{"merged" "rejected" "blocked" "cancelled" "abandoned"
                                  "replacement_created" "superseded"}
                                (:state a)))))
         (assignment-records root))))

(defn theme-finalize-candidate
  "B23: when slice is done and theme not finalized, request finalize approval."
  [root theme]
  (when (and (not (:finalized? theme))
             (theme-slice-complete? root (:theme-id theme))
             (not (theme-has-open-assignment? root (:theme-id theme)))
             (not (approval-record-exists-for? root "theme" (:theme-id theme) "finalize")))
    (let [theme-id (:theme-id theme)
          approval-id (str "finalize__" theme-id)]
      (if (cfg/squad-approval-required? root "finalize")
        {:priority (plane/ready-priority-of :theme-finalize)
         :stage-order 1
         :next-action "create_approval_request"
         :theme-id theme-id
         :story-id "theme"
         :gate "finalize"
         :reason "theme slice complete; user finalize/ship approval"
         :command (str "squad_approval.sh request " approval-id
                       " theme " theme-id
                       " finalize Approve_theme_finalize "
                       "theme-slice-ready-to-finalize")}
        {:priority (plane/ready-priority-of :theme-finalize)
         :stage-order 1
         :next-action "record_auto_approval"
         :theme-id theme-id
         :story-id "theme"
         :gate "finalize"
         :reason "finalize approval not required by configuration"
         :command (str "squad_theme.sh finalize " theme-id " auto-approved-by-config")}))))

(defn theme-finalize-candidates [root]
  (->> (theme-records root)
       (keep #(theme-finalize-candidate root %))
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

(defn implementation-order-path [root theme-id]
  (fs/path root ".squad" "themes" theme-id "implementation-order.md"))

(defn parse-implementation-order-edges
  "Parse makefile-style `dependent: provider [provider...]` lines into map dependent -> providers.
  Lines using the word `after` are ignored (invalid; record step rejects them)."
  [text]
  (reduce (fn [m raw]
            (let [line (str/trim (first (str/split raw #"#" 2)))]
              (if-let [[_ dep providers]
                       (re-matches #"([A-Za-z0-9][A-Za-z0-9._-]*)\s*:\s*(.+)" line)]
                (let [ps (->> (str/split providers #"\s+")
                              (remove str/blank?)
                              vec)]
                  (if (seq ps)
                    (update m dep (fnil into []) ps)
                    m))
                m)))
          {}
          (str/split-lines (or text ""))))

(defn implementation-order-recorded?
  "True when durable theme implementation-order.md exists (even if empty)."
  [root theme-id]
  (and (not (str/blank? theme-id))
       (fs/regular-file? (implementation-order-path root theme-id))))

(defn load-implementation-order [root theme-id]
  (let [path (implementation-order-path root theme-id)]
    (if (fs/regular-file? path)
      (parse-implementation-order-edges (slurp (str path)))
      {})))

(defn story-implementation-complete? [root story-id]
  "True when the story packet has recorded a merged implementation_sha."
  (let [packet-file (fs/path root ".squad" "stories" story-id "packet")]
    (and (fs/regular-file? packet-file)
         (not (str/blank? (get (file-map packet-file) "implementation_sha"))))))

(declare implementer-dependencies-satisfied? implementer-dependency-block-reason
         dependency-checker-nontrivial? theme-architecture-gate-satisfied?
         dependency-checker-quality-at)

(defn root-implementation-order-draft-path [root]
  (fs/path root "implementation-order.md"))

(defn default-implementation-order-seed
  "Comment-only order: valid to record, means no multi-story implementer gates."
  []
  (str "# No multi-story implementer dependencies declared for this theme.\n"
       "# Stories may implement when story/spec gates allow.\n"))

(defn packet-ready-for-implementer?
  "True when a story would seek implementer work if order allowed it."
  [root packet]
  (and (approval-satisfied? root packet "implementation")
       (squad-state/implementation-ready? packet)
       (not (field-present? packet "implementation_sha"))))

(defn theme-has-implementer-ready-story? [root theme-id]
  (boolean
   (some (fn [packet]
           (and (= theme-id (get packet "theme_id"))
                (packet-ready-for-implementer? root packet)))
         (packets root))))

(defn implementation-order-record-candidate
  "Durable theme order must exist before implementers (P0 B03).
  - Root draft present → record it (even mid-pipeline).
  - No draft → seed comment-only order only when some story is implementer-ready,
    so missing analyst order does not permanently block; early pipeline stages
    are not pre-empted by seed."
  [root theme-id]
  (when (and (not (str/blank? theme-id))
             (not (implementation-order-recorded? root theme-id)))
    (let [draft (root-implementation-order-draft-path root)
          has-draft? (fs/regular-file? draft)
          needs-impl? (theme-has-implementer-ready-story? root theme-id)]
      (when (or has-draft? needs-impl?)
        (let [seed-cmd (str "cat > implementation-order.md <<'SF_IMPL_ORDER_EOF'\n"
                            (default-implementation-order-seed)
                            "SF_IMPL_ORDER_EOF\n"
                            "squad_theme.sh implementation-order " theme-id " implementation-order.md")
              record-cmd (str "squad_theme.sh implementation-order " theme-id " implementation-order.md")]
          {:priority 26
           :stage-order 1
           :next-action "record_implementation_order"
           :theme-id theme-id
           :story-id "theme"
           :reason (if has-draft?
                     "root implementation-order.md must be recorded into durable theme path before implementers"
                     "durable implementation order missing; seed comment-only order so implementers are not stuck")
           :command (if has-draft? record-cmd seed-cmd)})))))

(defn implementation-order-record-candidates [root]
  (->> (packets root)
       (map #(get % "theme_id"))
       (remove str/blank?)
       distinct
       (keep #(implementation-order-record-candidate root %))
       vec))

;;; --- B13 checker quality + B25 theme architecture approval gates ---

(defn dependency-checker-path [root]
  (fs/path root "dependency-checker.edn"))

(defn parse-dependency-checker-edn [text]
  (try
    (edn/read-string {:readers *data-readers*} text)
    (catch Exception _ nil)))

(defn dependency-checker-quality
  "Classify product dependency-checker policy text.
  :missing — blank/absent content
  :hollow  — unparseable, not a map, or empty :allowed-dependencies
  :ok      — at least one component under :allowed-dependencies"
  [text]
  (if (str/blank? text)
    :missing
    (let [data (parse-dependency-checker-edn text)]
      (if-not (map? data)
        :hollow
        (let [deps (get data :allowed-dependencies)]
          (if (and (map? deps) (seq deps))
            :ok
            :hollow))))))

(defn dependency-checker-quality-at [root]
  (let [path (dependency-checker-path root)]
    (if (fs/regular-file? path)
      (dependency-checker-quality (slurp (str path)))
      :missing)))

(defn dependency-checker-nontrivial? [root]
  (= :ok (dependency-checker-quality-at root)))

(defn implementation-order-nonempty?
  "True when durable order has at least one makefile edge (B25 non-empty order)."
  [root theme-id]
  (boolean (seq (load-implementation-order root theme-id))))

(defn content-sha [text]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        digest (.digest md (.getBytes (str text) "UTF-8"))]
    (.toString (BigInteger. 1 digest) 16)))

(defn theme-gate-content-path [root theme-id gate]
  (case (str/replace gate "_" "-")
    "implementation-order" (implementation-order-path root theme-id)
    "dependency-checker" (dependency-checker-path root)
    nil))

(defn theme-gate-fingerprint-path [root theme-id gate]
  (fs/path root ".squad" "themes" theme-id "approval-fingerprints"
           (str (str/replace gate "_" "-") ".sha")))

(defn theme-content-gate-approved?
  "Theme gate approved in approvals.tsv and content fingerprint still matches (B25)."
  [root theme-id gate]
  (let [theme-dir (fs/path root ".squad" "themes" theme-id)
        gate-norm (str/replace gate "_" "-")
        approved? (or (theme-approved? theme-dir gate)
                      (theme-approved? theme-dir gate-norm)
                      (theme-approved? theme-dir (str/replace gate "-" "_")))
        content-path (theme-gate-content-path root theme-id gate)
        fp-path (theme-gate-fingerprint-path root theme-id gate)]
    (and approved?
         (fs/regular-file? content-path)
         (fs/regular-file? fp-path)
         (= (str/trim (slurp (str fp-path)))
            (content-sha (slurp (str content-path)))))))

(defn theme-architecture-gate-satisfied?
  "Order/checker gate satisfied: not required, no material to approve, or approved with fingerprint."
  [root theme-id gate]
  (let [gate-norm (str/replace gate "_" "-")
        required? (cfg/squad-approval-required? root gate-norm)
        needs-material?
        (case gate-norm
          "implementation-order" (implementation-order-nonempty? root theme-id)
          "dependency-checker" (dependency-checker-nontrivial? root)
          false)]
    (cond
      (not required?) true
      (not needs-material?) true
      :else (theme-content-gate-approved? root theme-id gate-norm))))

(defn pending-theme-gate-approval? [root theme-id gate]
  (boolean
   (some #(and (= "theme" (get % "target_kind"))
               (= theme-id (get % "target_id"))
               (or (= gate (get % "gate"))
                   (= (str/replace gate "_" "-") (str/replace (get % "gate" "") "_" "-")))
               (= "pending" (:state %)))
         (approval-records root))))

(defn approved-theme-gate-record [root theme-id gate]
  (some #(when (and (= "theme" (get % "target_kind"))
                    (= theme-id (get % "target_id"))
                    (or (= gate (get % "gate"))
                        (= (str/replace gate "_" "-") (str/replace (get % "gate" "") "_" "-")))
                    (= "approved" (:state %)))
           %)
        (approval-records root)))

(defn theme-ids-with-packets [root]
  (->> (packets root)
       (map #(get % "theme_id"))
       (remove str/blank?)
       distinct
       vec))

(defn incomplete-dependency-checker-candidate
  "B13: when implementers would run but checker is missing/hollow, surface residual
  (implementer hard-gate alone is silent). Earlier pipeline stages may proceed."
  [root theme-id]
  (when (and (not (str/blank? theme-id))
             (not (dependency-checker-nontrivial? root))
             ;; Only surface when implementers would otherwise schedule — earlier
             ;; stages (story approval, Gherkin) may still proceed.
             (theme-has-implementer-ready-story? root theme-id))
    (let [q (dependency-checker-quality-at root)
          reason (case q
                   :missing "dependency-checker.edn is missing; analysis incomplete without product policy"
                   :hollow "dependency-checker.edn is hollow (empty or unparseable :allowed-dependencies); author a real component graph from the module map"
                   "dependency-checker.edn is not a non-trivial product policy")]
      {:priority 27
       :stage-order 1
       :next-action "complete_dependency_checker"
       :theme-id theme-id
       :story-id "theme"
       :gate "dependency-checker"
       :reason reason
       :command (str "echo 'Author non-trivial root dependency-checker.edn from the theme module map "
                     "(see swarmforge/templates/dependency-checker.edn); reject hollow two-node stubs. "
                     "Then user-approve via dashboard (B25).'")})))

(defn incomplete-dependency-checker-candidates [root]
  (->> (theme-ids-with-packets root)
       (keep #(incomplete-dependency-checker-candidate root %))
       vec))

(defn theme-architecture-approval-candidate
  "B25: request (or auto-record) approval for non-empty order / non-trivial checker."
  [root theme-id gate title reason]
  (let [gate-norm (str/replace gate "_" "-")
        needs-material?
        (case gate-norm
          "implementation-order"
          (and (implementation-order-recorded? root theme-id)
               (implementation-order-nonempty? root theme-id))
          "dependency-checker" (dependency-checker-nontrivial? root)
          false)
        satisfied? (theme-architecture-gate-satisfied? root theme-id gate-norm)
        approval-id (str gate-norm "__" theme-id)
        pending? (pending-theme-gate-approval? root theme-id gate-norm)
        stale-approved (when (and needs-material? (not satisfied?))
                         (approved-theme-gate-record root theme-id gate-norm))]
    (when (and needs-material? (not satisfied?) (not pending?))
      (if (cfg/squad-approval-required? root gate-norm)
        (let [clear-cmd (when stale-approved
                          (str "mkdir -p .squad/approvals/cleared && "
                               "mv -f " (pr-str (:file stale-approved))
                               " .squad/approvals/cleared/ 2>/dev/null; "))
              request-cmd (str "squad_approval.sh request " approval-id
                               " theme " theme-id " " gate-norm " "
                               title " " reason)]
          {:priority 28
           :stage-order (if (= gate-norm "implementation-order") 1 2)
           :next-action "create_approval_request"
           :theme-id theme-id
           :story-id "theme"
           :gate gate-norm
           :reason (if stale-approved
                     (str reason " (content revised since prior approval)")
                     reason)
           :command (str clear-cmd request-cmd)})
        {:priority 28
         :stage-order (if (= gate-norm "implementation-order") 1 2)
         :next-action "record_auto_approval"
         :theme-id theme-id
         :story-id "theme"
         :gate gate-norm
         :reason (str gate-norm " approval is not required by configuration")
         :command (str "squad_theme.sh approve " theme-id " " gate-norm
                       " auto-approved-by-config")}))))

(defn theme-architecture-approval-candidates [root]
  (vec
   (mapcat
    (fn [theme-id]
      (keep identity
            [(theme-architecture-approval-candidate
              root theme-id "implementation-order"
              "Approve_implementation_order"
              "non-empty-implementation-order-ready-for-approval")
             (theme-architecture-approval-candidate
              root theme-id "dependency-checker"
              "Approve_dependency_checker"
              "non-trivial-dependency-checker-ready-for-approval")]))
    (theme-ids-with-packets root))))

(defn implementer-dependencies-satisfied?
  "Hard gate (P0 B03 + B13 + B25):
  - durable implementation order recorded
  - non-trivial dependency-checker present (B13 quality)
  - non-empty order / non-trivial checker user-approved when required (B25)
  - providers listed for story-id each have implementation_sha"
  [root theme-id story-id]
  (and (implementation-order-recorded? root theme-id)
       (dependency-checker-nontrivial? root)
       (theme-architecture-gate-satisfied? root theme-id "implementation-order")
       (theme-architecture-gate-satisfied? root theme-id "dependency-checker")
       (let [providers (get (load-implementation-order root theme-id) story-id)]
         (or (empty? providers)
             (every? #(story-implementation-complete? root %) providers)))))

(defn implementer-dependency-block-reason [root theme-id story-id]
  (cond
    (not (implementation-order-recorded? root theme-id))
    "implementation order not recorded for theme"

    (not (dependency-checker-nontrivial? root))
    (case (dependency-checker-quality-at root)
      :missing "dependency-checker.edn missing (analysis incomplete)"
      :hollow "dependency-checker.edn hollow (analysis incomplete)"
      "dependency-checker.edn not ready")

    (not (theme-architecture-gate-satisfied? root theme-id "implementation-order"))
    "implementation-order awaiting user approval"

    (not (theme-architecture-gate-satisfied? root theme-id "dependency-checker"))
    "dependency-checker awaiting user approval"

    :else
    (let [providers (get (load-implementation-order root theme-id) story-id)
          pending (remove #(story-implementation-complete? root %) providers)]
      (when (seq pending)
        (str "implementation order: waiting on " (str/join ", " pending))))))

(defn assignment-candidate [root assignments agents packet template assignment-suffix reason priority stage-order requirement]
  (let [story-id (get packet "story_id" (get packet "_story_id"))
        theme-id (get packet "theme_id")
        assignment-id (next-assignment-id assignments story-id assignment-suffix)
        assignment (assignment-for assignments theme-id story-id template)
        blocked-by-order? (and (= "implementer" template)
                               (not (implementer-dependencies-satisfied? root theme-id story-id)))]
    (when-not blocked-by-order?
      (if assignment
        (when (spawnable-assignment? root agents template assignment)
          (assignment-spawn-candidate assignment theme-id story-id template reason priority stage-order))
        (assignment-create-candidate theme-id story-id template assignment-id reason priority stage-order requirement)))))

(defn implementer-rework-already-created?
  "True when an implementer assignment exists beyond the packet's recorded
  implementation_assignment — the one allowed rework for a current code_review
  changes-requested cycle (P0 B01 thrash stop)."
  [assignments packet story-id]
  (let [recorded (get packet "implementation_assignment")]
    (boolean
     (some (fn [a]
             (and (= story-id (:story-id a))
                  (= "implementer" (:template a))
                  (not= recorded (:assignment-id a))))
           assignments))))

(defn implementation-revision-candidate
  "At most one implementer rework while code_review is currently changes-requested.
  After that rework merges and is re-recorded, clear-downstream drops CR and a
  fresh code-review cycle is required."
  [root assignments agents packet reason priority stage-order]
  (let [story-id (get packet "story_id" (get packet "_story_id"))
        theme-id (get packet "theme_id")]
    (when (and (field-changes-requested? packet "code_review")
               (not (stale-changes-requested? packet "code_review"))
               (approval-satisfied? root packet "story")
               (approval-satisfied? root packet "gherkin")
               (approval-satisfied? root packet "qa-procedure")
               (approval-satisfied? root packet "implementation")
               (implementer-dependencies-satisfied? root theme-id story-id))
      (if-let [assignment (assignment-for assignments theme-id story-id "implementer")]
        (when (spawnable-assignment? root agents "implementer" assignment)
          (assignment-spawn-candidate assignment theme-id story-id "implementer" reason priority stage-order))
        (when-not (implementer-rework-already-created? assignments packet story-id)
          (assignment-create-candidate theme-id story-id "implementer"
                                       (next-assignment-id assignments story-id "implementation")
                                       reason priority stage-order nil))))))

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

(defn theme-module-map-candidate [theme]
  {:priority (plane/ready-priority-of :theme-module-map)
   :stage-order 1
   :next-action "write_theme_module_map"
   :theme-id (:theme-id theme)
   :story-id "theme"
   :gate "theme"
   :reason (str "theme needs a Clean Architecture module map before theme approval; "
                "fill swarmforge/templates/theme-module-map.md for this theme, then "
                "record it with squad_theme.sh module-map")
   :command (str "squad_theme.sh module-map " (:theme-id theme)
                 " <filled-module-map.md>")})

(defn theme-candidates [root rows]
  (let [assignments (assignment-records root)
        agents (agent-records root rows)
        packet-themes (set (map #(get % "theme_id") (packets root)))]
    (->> (for [theme (theme-records root)
               :when (not (or (contains? packet-themes (:theme-id theme))
                              (theme-analysis-complete? assignments (:theme-id theme))))
               :let [approval-id (str "theme__" (:theme-id theme))
                     write-map (when (and (not (:module-map-present? theme))
                                          (not (:approved-theme? theme)))
                                 (theme-module-map-candidate theme))
                     approval (when (and (:module-map-present? theme)
                                         (not (:approved-theme? theme))
                                         (not (approval-record-exists-for? root "theme" (:theme-id theme) "theme")))
                                {:priority (plane/ready-priority-of :theme-approval)
                                 :stage-order 1
                                 :next-action "create_approval_request"
                                 :theme-id (:theme-id theme)
                                 :story-id "theme"
                                 :gate "theme"
                                 :reason "theme and module map ready for user approval"
                                 :command (str "squad_approval.sh request " approval-id
                                               " theme " (:theme-id theme)
                                               " theme Approve_theme_and_module_map "
                                               "theme-and-module-map-ready")})
                     analyst (when (:approved-theme? theme)
                               (theme-assignment-candidate root assignments agents theme
                                                           "analyst" "analysis"
                                                           "approved theme needs story analysis"
                                                           60 5 "theme"))
                     candidate (cond
                                 (:approved-theme? theme) analyst
                                 write-map write-map
                                 :else approval)]
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
   "senior-implementer" "senior-implementer"})

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

(defn packet-iteration-mentions-assignment?
  "True when packet history lists assignment-id under the given iterations field
  (e.g. cleaner_iterations: alpha-cleaner=recorded). Used so clear-downstream
  does not get undone by re-recording the same merged assignment (B39)."
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
  already existed (P0 B01)."
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
  B39: if clear-downstream removed the sha but iterations still show this
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
               (= story-id (:story-id assignment))
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

(defn assignment-batch-id
  "Batch membership lives under batch_id (B32). Replacements set batch_id to the
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

(defn batch-result-record-candidates [root assignments packets]
  (let [packets-by-story (packet-by-story packets)]
    (->> (for [assignment assignments
               :let [kind (get result-assignment-rules (:template assignment))]
               :when (and kind (= "batch" (:story-id assignment)))
               member (batch-manifest-rows root (assignment-batch-id assignment))
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
  "B39: after clear-downstream, review_* fields are gone but *_review_iterations
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
    (vec (concat (implementation-order-record-candidates root)
                 (incomplete-dependency-checker-candidates root)
                 (theme-architecture-approval-candidates root)
                 (analyst-story-registration-candidates root assignments)
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
                 (implementation-revision-candidate
                  (:root ctx) (:assignments ctx) (:agents ctx) packet
                  "code review requested implementation changes" 60 95))}
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
             :agents (agent-records root rows)}
        finalized (->> (theme-records root)
                       (filter :finalized?)
                       (map :theme-id)
                       set)]
    (->> (for [packet (packets root)
               :when (not (contains? finalized (get packet "theme_id")))
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

(defn architecture-complete? [packet]
  (or (field-accepted? packet "architecture_review")
      (and (field-changes-requested? packet "architecture_review")
           (field-present? packet "senior_implementer_sha"))))

(defn architecture-gate-satisfied-for-final? [root packet]
  (or (and (field-accepted? packet "architecture_review")
           (approval-satisfied? root packet "architecture"))
      (and (field-changes-requested? packet "architecture_review")
           (field-present? packet "senior_implementer_sha"))))

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
    :template "senior-implementer"
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

(defn merge-lineage-root
  "Strip trailing -merge segments to get the product/rework assignment root."
  [assignment-id]
  (str/replace (str assignment-id) #"(?:-merge)+$" ""))

(defn assignment-in-merge-lineage? [assignment-id lineage-root]
  (or (= assignment-id lineage-root)
      (str/starts-with? (str assignment-id) (str lineage-root "-merge"))))

(defn assignment-has-blocker? [root assignment-id]
  (or (= "blocked" (get (file-map (fs/path root ".squad" "assignments" assignment-id "status")) "state"))
      (fs/regular-file? (fs/path root ".squad" "assignments" assignment-id "blocker"))
      (fs/regular-file? (fs/path root ".squad" "assignments" assignment-id "blocker.md"))))

(defn lineage-max-depth-exhausted?
  "True when any assignment in this merge lineage is already at max_merger_depth
  and terminal-blocked. Stops re-creating mergers after max-depth hard stop."
  [root assignments lineage-root max-depth]
  (boolean
   (some (fn [{:keys [assignment-id state]}]
           (and (assignment-in-merge-lineage? assignment-id lineage-root)
                (>= (merge-suffix-depth assignment-id) max-depth)
                (or (= "blocked" state)
                    (assignment-has-blocker? root assignment-id))))
         assignments)))

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
                    "Manual resolution required for assignment " assignment-id ". "
                    "Do not reject/replace this lineage; resolve the blocker.\n")]
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
        max-depth (cfg/squad-max-merger-depth root)
        lineage-root (merge-lineage-root assignment-id)
        exhausted? (lineage-max-depth-exhausted? root assignments lineage-root max-depth)]
    (cond
      ;; At or past max depth: only durable block — never create another -merge or rework.
      (>= depth max-depth)
      (when-not (assignment-has-blocker? root assignment-id)
        (merger-limit-blocker-candidate root assignment max-depth))

      ;; Lineage already hard-stopped at max depth: do not restart with a new merger.
      exhausted?
      nil

      :else
      (let [base (str assignment-id "-merge")
            existing (existing-merger-assignment assignments base)]
        (if existing
          (when-not (active-assignment? agents (:assignment-id existing))
            (merger-spawn-candidate root agents existing))
          (merger-create-candidate theme-id assignment-id story-id
                                   (next-id-with-base assignments base)))))))

(def open-merger-states
  #{"created" "assignment_created" "in_progress" "handoff_sent"
    "result_received" "merge_ready" "merge_blocked"})

(defn open-merger-assignments [assignments]
  (filter #(and (= "merger" (:template %))
                (contains? open-merger-states (:state %)))
          assignments))

(defn merger-candidates [root rows]
  "At most one open merger lineage at a time (singleton). Prefer progressing an
  existing open merger (spawn or nested merge-merge); otherwise create for the
  highest-priority merge_blocked product assignment.
  A merger that is only handoff_sent with merge_blocked assignment does not block
  creating the next merger (B27)."
  (let [assignments (assignment-records root)
        agents (agent-records root rows)
        sort-key (juxt :priority :theme-id :story-id :stage-order :assignment-id)]
    (if (capacity-active-template? root agents "merger")
      []
      (let [open (vec (open-merger-assignments assignments))]
        (if (seq open)
          (->> open
               (keep (fn [assignment]
                       (if (= "merge_blocked" (:state assignment))
                         (merger-candidate root assignments agents assignment)
                         (when (assignment-created? (:state assignment))
                           (merger-spawn-candidate root agents assignment)))))
               (remove nil?)
               (sort-by sort-key)
               (take 1)
               vec)
          (->> assignments
               (filter #(= "merge_blocked" (:state %)))
               (keep #(merger-candidate root assignments agents %))
               (remove nil?)
               (sort-by sort-key)
               (take 1)
               vec))))))

(def story-candidate-fields
  [["NEXT_ACTION" :next-action true]
   ["OP" :op false]
   ["AUTHORITY" :authority false]
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
    "declare_merge_blocker"
    "record_implementation_order"})

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
  "B16/B18: apply via executor under :daemon authority by default."
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
    "review_accepted" "review_changes_requested" "cancelled" "abandoned"})

(defn completed-handoff-retirable? [root {:keys [agent assignment-id]}]
  "Retire only when the assignment handoff is terminal. merge_blocked agents
  keep their worktree for merger recovery until the assignment is merged (or
  otherwise resolved) — not merely when a downstream merger recorded a result."
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
         ;; Resync status via merge-ready, then finish. Daemon has no SWARMFORGE_ROLE.
         :command (str "squad_assign.sh merge-ready " assignment-id
                       " && SWARMFORGE_ROLE=squad-leader done_with_current.sh "
                       (pr-str (str file)))}

        ;; Status can lag merge file when result was re-recorded after merge-ready.
        (and (= "result_received" state)
             (= "merge_ready" merge-state))
        {:action "accept_merge"
         :reason "merge readiness already recorded; accept merge before handoff completion"
         :command (str "squad_assign.sh accept-merge " assignment-id)}

        (and (= "result_received" state)
             (= "merge_blocked" merge-state))
        nil

        :else
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
          nil)))))

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

(defn print-daemon-owned-main-git-wait! [action assignment-id]
  (println "NEXT_ACTION: wait_for_daemon_main_git")
  (println "ASSIGNMENT:" (or assignment-id "unknown"))
  (println "DEFERRED_ACTION:" action)
  (println "REASON: main git merge-ready/accept is owned by squadd; wait for the next daemon poll")
  (println "COMMAND: sleep 5 && squad_next.sh --residual-only"))

(defn print-in-process-handoff-action! [root file]
  (if-let [{:keys [action reason command]} (in-process-git-handoff-command root file)]
    (if (and *sl-facing-residual?*
             (plane/daemon-only-main-git-op? action))
      (print-daemon-owned-main-git-wait! action (handoff-task file))
      (print-handoff-action! action file reason command))
    (if (in-process-merge-blocked? root file)
      (print-handoff-action! "hold_merge_blocked_handoff"
                             file
                             "merge-blocked assignment must be resolved before handoff completion"
                             (str "true  # hold in_process until merge recovery; do not run done_with_current.sh"))
      (print-handoff-action! "finish_in_process_handoff"
                             file
                             "handoff is already claimed and must be completed before new mail"
                             (str "SWARMFORGE_ROLE=squad-leader done_with_current.sh " file)))))

(def daemon-handoff-step-actions
  #{"record_assignment_result" "check_merge_readiness" "accept_merge"
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

(defn held-for-merge-recovery? [root assignment-id]
  "True when the agent's assignment is merge_blocked: worktree is held for merger
  recovery. Do not recover_agent — residual should drive merger instead."
  (and assignment-id
       (not= "unknown" assignment-id)
       (assignment-merge-blocked? root assignment-id)))

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
  "B38: quiet agent, session gone, open assignment → repair residual (not vague recover)."
  [root {:keys [agent task-id] :as record}]
  (and (active-agent? record)
       (not (held-for-merge-recovery? root task-id))
       (assignment-open-for-repair? root task-id)
       (not (agent-session-live? root agent))))

(defn recovery-candidate-for-agent [root now threshold retry-threshold
                                    {:keys [agent task-id state last-activity-at activity-source] :as record}]
  (when (and (active-agent? record)
             (not (held-for-merge-recovery? root task-id)))
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

(defn finalized-theme-ids [root]
  (->> (theme-records root)
       (filter :finalized?)
       (map :theme-id)
       sort
       vec))

(defn print-wait-action!
  ([active] (print-wait-action! nil active))
  ([root active]
   (let [root (or root (fs/absolutize (project-root)))
         finalized (try (finalized-theme-ids root) (catch Exception _ []))
         reason (cond
                  (seq active)
                  "active agents are still working or awaiting handoff delivery"
                  (seq finalized)
                  (str "theme(s) finalized (" (str/join ", " finalized)
                       "); no open product work — idle until reopen or new stories (B23)")
                  :else
                  "no handoffs, pending approvals, active transient agents, or stale locks")]
     (println "NEXT_ACTION: wait")
     (println "REASON:" reason)
     (when (seq finalized)
       (println "THEME_LIFECYCLE: finalized")
       (doseq [id finalized]
         (println "FINALIZED_THEME:" id)))
     (doseq [{:keys [agent task-id state quiet-for activity-source]} active]
       (println "ACTIVE:" agent task-id state
                (str "quiet_for=" (or quiet-for "unknown"))
                (str "activity_source=" activity-source)))
     (println "CHECK_AFTER_SECONDS: 30")
     (println "COMMAND: sleep 30 && squad_next.sh"))))

(defn ready-actions [root rows]
  (sort-by (juxt :priority :theme-id :stage-order :story-id :assignment-id)
           (concat (packet-repair-candidates root)
                   (theme-candidates root rows)
                   (story-candidates root rows)
                   (batch-candidates root rows)
                   (merger-candidates root rows)
                   (generic-ready-assignment-candidates root rows)
                   (theme-finalize-candidates root))))

(defn rows-without-agents [rows agents]
  (remove #(contains? agents (first %)) rows))

(defn capacity-used [root agents]
  (count (filter #(capacity-counted-agent? root %) agents)))

(defn active-singleton-templates [root agents]
  (->> singleton-templates
       (filter #(capacity-active-template? root agents %))
       set))

(defn spawn-action? [action]
  (= "request_spawn" (:next-action action)))

(defn merger-spawn-action? [action]
  (and (spawn-action? action)
       (= "merger" (:template action))))

(defn queues-spawn? [action]
  "True when applying this action will create a spawn request (capacity-relevant)."
  (or (spawn-action? action)
      (and (= "create_assignment" (:next-action action))
           (str/includes? (str (:command action)) "--queue-spawn"))))

(defn singleton-spawn-blocked? [active-singletons action]
  (and (contains? singleton-templates (:template action))
       (contains? active-singletons (:template action))))

(defn spawn-fits? [used max-agents active-singletons action]
  "Mergers skip the general transient budget but still honor singleton caps."
  (and (not (singleton-spawn-blocked? active-singletons action))
       (or (merger-spawn-action? action)
           (< used max-agents))))

(defn account-spawn [used active-singletons action]
  (let [singletons (cond-> active-singletons
                     (contains? singleton-templates (:template action))
                     (conj (:template action)))]
    (if (merger-spawn-action? action)
      [used singletons]
      [(inc used) singletons])))

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
    (if-not (queues-spawn? action)
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
      :pending-dashboard-request (oldest-pending-dashboard-request root)
      :durable-blocker (oldest-durable-blocker root)
      :pending-approval-file (pending-approval root)}
     concurrent)))

(def action-rule-predicates
  "Predicates for residual classes. Order comes from plane/residual-class-order (B19)."
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
  "B19: residual ranking is plane/residual-class-order, not ad-hoc list order here."
  (mapv (fn [class]
          [class (get action-rule-predicates class (constantly false))])
        (remove #{:wait} plane/residual-class-order)))

(defn action-rule-matches? [ctx [_ predicate]]
  (if (keyword? predicate)
    (get ctx predicate)
    (predicate ctx)))

(defn action-printer [ctx]
  "B18/B19: select residual class via control-plane policy."
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

(defn park-merge-blocked-in-process-handoffs!
  "Free the single in_process slot: park merge_blocked handoffs under inbox/held/
  so later workers' mail can be claimed. Merger residual still sees the assignment."
  [root]
  (let [in-process-dir (fs/path (handoff-inbox-dir root) "in_process")
        held-dir (fs/path (handoff-inbox-dir root) "held")
        parked (atom [])]
    (doseq [file (files-with-extension in-process-dir ".handoff")]
      (when (in-process-merge-blocked? root file)
        (fs/create-dirs held-dir)
        (let [dest (fs/path held-dir (fs/file-name file))]
          (fs/move file dest {:replace-existing true})
          (swap! parked conj
                 {:next-action "park_merge_blocked_handoff"
                  :assignment-id (handoff-task dest)
                  :exit 0
                  :out ""
                  :err ""
                  :command (str "park " (fs/file-name dest))}))))
    @parked))

(defn held-handoff-files [root]
  (files-with-extension (fs/path (handoff-inbox-dir root) "held") ".handoff"))

(defn apply-held-handoff-finish-step!
  "When a parked merge_blocked assignment later merges, finish the held handoff.
  done_with_current only accepts in_process paths — move held → in_process first
  when the active tray is free (P0 B02)."
  [root]
  (let [in-process-dir (fs/path (handoff-inbox-dir root) "in_process")
        existing (first (files-with-extension in-process-dir ".handoff"))]
    (when (nil? existing)
      (some (fn [file]
              (let [assignment-id (handoff-task file)
                    state (assignment-status-state root assignment-id)]
                (when (or (= "merged" state)
                          (assignment-accepted-merge? root assignment-id)
                          (contains? resolved-handoff-assignment-states state))
                  (fs/create-dirs in-process-dir)
                  (let [dest (fs/path in-process-dir (fs/file-name file))]
                    (fs/move file dest {:replace-existing true})
                    [(apply-candidate! root
                                       {:next-action "finish_held_handoff"
                                        :assignment-id assignment-id
                                        :command (str "SWARMFORGE_ROLE=squad-leader done_with_current.sh "
                                                      (pr-str (str dest)))})]))))
            (held-handoff-files root)))))

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
        (when (and (not (in-process-merge-blocked? root file))
                   (in-process-needs-action? {:root root :in-process file}))
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
        ;; Free in_process before claim so merge_blocked does not starve new mail.
        parked (park-merge-blocked-in-process-handoffs! root)
        held-finish (or (apply-held-handoff-finish-step! root) [])
        claim (or (apply-process-new-handoff-step! root) [])
        handoff (or (apply-in-process-handoff-step! root) [])]
    (into [] (concat bookkeeping retires daemon-ready stale parked held-finish claim handoff))))

(defn print-sl-facing-residual! []
  (binding [*sl-facing-residual?* true]
    (print-selected-action! (next-action-context))))

(defn residual-only!
  "Squad-leader residual: judgment/recovery only; never hand main-git accept to SL."
  []
  (print-sl-facing-residual!))

(defn apply-mechanical-and-print-next! []
  (let [root (fs/absolutize (project-root))]
    (loop [applied []
           remaining 100]
      (let [batch (apply-one-mechanical-pass! root)]
        (cond
          (zero? remaining)
          (do (print-applied-transitions! applied)
              (print-sl-facing-residual!))

          (empty? batch)
          (do (print-applied-transitions! applied)
              (print-sl-facing-residual!))

          (some #(and (contains? % :exit) (not (zero? (:exit %)))) batch)
          (do (print-applied-transitions! (into applied batch))
              (print-sl-facing-residual!))

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
