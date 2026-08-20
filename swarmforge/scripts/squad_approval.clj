#!/usr/bin/env bb

(ns squad-approval
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-config :as cfg]
            [clojure.string :as str]))

(def script-dir (fs/parent *file*))

(def usage-text
  (str "Usage:\n"
       "  squad_approval.sh required <gate>\n"
       "  squad_approval.sh request <approval-id> <theme|story|product> <target-id> <gate> <title> <reason...>\n"
       "  squad_approval.sh request-bulk <theme|story|product> <gate> <title> <reason> <approval-id>:<target-id>...\n"
       "  squad_approval.sh approve <approval-id> <detail...>\n"
       "  squad_approval.sh reject <approval-id> <reason...>\n"
       "  squad_approval.sh resolve-rejection <approval-id> [detail...]\n"
       "  squad_approval.sh status [approval-id]\n"))

(def valid-id #"[A-Za-z0-9][A-Za-z0-9._-]*")
(def valid-target-kinds #{"theme" "story" "product"})
(def valid-gates #{"theme" "story" "implementation-plan" "implementation_plan"
                   "gherkin" "qa_procedure" "qa-procedure" "implementation"
                   "code_review" "code-review" "hardening" "qa" "architecture" "final"
                   "implementation-order" "implementation_order"
                   "dependency-checker" "dependency_checker"
                   "finalize" "theme_finalize" "theme-finalize"
                   "frame"})

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

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn validate-id! [kind value]
  (when-not (re-matches valid-id value)
    (exit! 2 (str kind " must use letters, digits, dots, underscores, and hyphens.")))
  (when (or (str/includes? value "/") (str/includes? value "\\"))
    (exit! 2 (str kind " may not contain path separators."))))

(defn validate-gate! [gate]
  (validate-id! "Approval gate" gate)
  (when-not (contains? valid-gates gate)
    (exit! 2 (str "Approval gate must be theme, story, implementation-plan, gherkin, qa-procedure, implementation, "
                  "code-review, hardening, qa, architecture, final, implementation-order, "
                  "dependency-checker, finalize, or frame."))))

(defn write-atomic! [file content]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                  :prefix (str "." (fs/file-name file) ".")})]
    (spit (str tmp) content)
    (fs/move tmp file {:replace-existing true})))

(defn approvals-dir [root state]
  (fs/path root ".squad" "approvals" state))

(defn pending-file [root approval-id]
  (fs/path (approvals-dir root "pending") (str approval-id ".approval")))

(defn approved-file [root approval-id]
  (fs/path (approvals-dir root "approved") (str approval-id ".approval")))

(defn rejected-file [root approval-id]
  (fs/path (approvals-dir root "rejected") (str approval-id ".approval")))

(defn cleared-file [root approval-id]
  (fs/path (approvals-dir root "cleared") (str approval-id ".approval")))

(defn blocker-file [root blocker-id]
  (fs/path root ".squad" "blockers" blocker-id))

(defn blocker-md-file [root blocker-id]
  (fs/path root ".squad" "blockers" (str blocker-id ".md")))

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(defn file-map [file]
  (into {}
        (keep (fn [line]
                (let [[k v] (str/split line #": " 2)]
                  (when (and k v) [k v]))))
        (str/split-lines (slurp (str file)))))

(defn active-approval-file [root approval-id]
  "Pending, approved, or rejected — not cleared (cleared gates may be re-requested)."
  (some (fn [file]
          (when (fs/exists? file) file))
        [(pending-file root approval-id)
         (approved-file root approval-id)
         (rejected-file root approval-id)]))

(defn approval-file [root approval-id]
  (or (active-approval-file root approval-id)
      (let [cleared (cleared-file root approval-id)]
        (when (fs/exists? cleared) cleared))))

(defn approval-files
  "Active approval records (not cleared). Used to block duplicate gate requests."
  [root]
  (for [state ["pending" "approved" "rejected"]
        :let [dir (approvals-dir root state)]
        :when (fs/exists? dir)
        file (fs/list-dir dir)
        :when (fs/regular-file? file)]
    file))

(defn equivalent-approval-file [root target-kind target-id gate]
  (some (fn [file]
          (let [approval (file-map file)]
            (when (and (= target-kind (get approval "target_kind"))
                       (= target-id (get approval "target_id"))
                       (= gate (get approval "gate")))
              file)))
        (approval-files root)))

(defn print-request-result! [root file]
  (let [approval (file-map file)
        approval-id (get approval "approval_id" (str/replace (fs/file-name file) #"\.approval$" ""))
        gate (get approval "gate" "unknown")]
    (println "SQUAD_APPROVAL:" approval-id)
    (println "STATE:" (get approval "state" "unknown"))
    (println "REQUIRED:" (cfg/squad-approval-required? root gate))
    (println "FILE:" (str file))))

(defn normalized-detail [parts default-value]
  (let [detail (str/replace (str/join " " parts) #"\R+" " ")]
    (if (str/blank? detail) default-value detail)))

(defn packet-file [root story-id]
  (fs/path root ".squad" "stories" story-id "packet"))

(defn target-exists? [root target-kind target-id]
  (case target-kind
    "story" (fs/exists? (packet-file root target-id))
    "product" (and (= "product" target-id)
                   (fs/regular-file? (fs/path root ".squad" "product")))
    false))

(defn packet-gate [gate]
  (str/replace gate "_" "-"))

(defn command-for [target-kind target-id gate detail]
  (case target-kind
    "story" [(str (fs/path script-dir "squad_packet.sh")) "approve" target-id (packet-gate gate) detail]
    []))

(defn ensure-target-exists! [root target-kind target-id]
  (when-not (target-exists? root target-kind target-id)
    (exit! 1 (str "Approval target not found: " target-kind " " target-id))))

(defn request-content [approval-id target-kind target-id gate title reason now]
  (str "approval_id: " approval-id "\n"
       "target_kind: " target-kind "\n"
       "target_id: " target-id "\n"
       "gate: " gate "\n"
       "state: pending\n"
       "title: " title "\n"
       "reason: " reason "\n"
       "created_at: " now "\n"
       "approve_command: " (str/join " " (command-for target-kind target-id gate "approved-by-user")) "\n"))

(defn write-request! [file approval-id target-kind target-id gate title reason now]
  (write-atomic! file (request-content approval-id target-kind target-id gate title reason now)))

(defn required! [gate]
  (validate-gate! gate)
  (let [root (fs/absolutize (project-root))
        required? (cfg/squad-approval-required? root gate)]
    (println "APPROVAL_GATE:" gate)
    (println "REQUIRED:" required?)))

(defn request! [approval-id target-kind target-id gate title reason-parts]
  (validate-id! "Approval id" approval-id)
  (when-not (contains? valid-target-kinds target-kind)
    (exit! 2 "Approval target kind must be theme, story, or product."))
  (validate-id! "Target id" target-id)
  (validate-gate! gate)
  (let [root (fs/absolutize (project-root))
        file (pending-file root approval-id)
        reason (normalized-detail reason-parts "approval requested")
        now (timestamp)]
    (ensure-target-exists! root target-kind target-id)
    (if-let [existing (or (active-approval-file root approval-id)
                          (equivalent-approval-file root target-kind target-id gate))]
      (print-request-result! root existing)
      (do
        (write-request! file approval-id target-kind target-id gate title reason now)
        (print-request-result! root file)))))

(defn parse-bulk-pair! [pair]
  (let [[approval-id target-id extra] (str/split pair #":" 3)]
    (when (or extra (str/blank? approval-id) (str/blank? target-id))
      (exit! 2 "Bulk approval pairs must use <approval-id>:<target-id>."))
    (validate-id! "Approval id" approval-id)
    (validate-id! "Target id" target-id)
    {:approval-id approval-id
     :target-id target-id}))

(defn request-bulk! [target-kind gate title reason pairs]
  (when-not (contains? valid-target-kinds target-kind)
    (exit! 2 "Approval target kind must be theme, story, or product."))
  (validate-gate! gate)
  (when-not (seq pairs)
    (exit! 1 usage-text))
  (doseq [{:keys [approval-id target-id]} (map parse-bulk-pair! pairs)]
    (request! approval-id target-kind target-id gate title [reason])))

(defn move-with-state! [source target state detail]
  (let [content (slurp (str source))
        content (-> content
                    (str/replace #"(?m)^state: .*$" (str "state: " state))
                    (str "resolved_at: " (timestamp) "\n"
                         "resolution_detail: " detail "\n"))]
    (write-atomic! target content)
    (fs/delete-if-exists source)))

(defn approve! [approval-id detail-parts]
  (validate-id! "Approval id" approval-id)
  (let [root (fs/absolutize (project-root))
        source (pending-file root approval-id)]
    (when-not (fs/regular-file? source)
      (exit! 1 (str "Pending approval not found: " approval-id)))
    (let [approval (file-map source)
          target-kind (get approval "target_kind")
          target-id (get approval "target_id")
          gate (get approval "gate")
          detail (normalized-detail detail-parts "approved-by-user")
          command (command-for target-kind target-id gate detail)]
      (when (seq command)
        (let [result (apply process/sh (concat [{:dir (str root) :continue true}] command))]
          (when-not (zero? (:exit result))
            (exit! (:exit result)
                   (str "Approval transition failed: " approval-id)
                   (:err result)))))
      (move-with-state! source (approved-file root approval-id) "approved" detail)
      (println "SQUAD_APPROVAL:" approval-id)
      (println "STATE: approved")
      (println "TARGET:" target-id)
      (println "GATE:" gate))))

(defn write-approval-blocker! [root approval-id approval reason now]
  (let [blocker-id approval-id
        dir (fs/path root ".squad" "blockers")
        target-kind (get approval "target_kind" "unknown")
        target-id (get approval "target_id" "unknown")
        gate (get approval "gate" "unknown")
        title (get approval "title" "")
        body (str "Approval " approval-id " rejected for " target-kind " " target-id
                  " gate " gate ".\nReason: " reason "\n")]
    (write-atomic! (fs/path dir blocker-id)
                   (str "blocker_id: " blocker-id "\n"
                        "kind: approval-rejection\n"
                        "state: blocked\n"
                        "approval_id: " approval-id "\n"
                        "target_kind: " target-kind "\n"
                        "target_id: " target-id "\n"
                        "gate: " gate "\n"
                        "title: " title "\n"
                        "detail: " reason "\n"
                        "updated_at: " now "\n"))
    (write-atomic! (fs/path dir (str blocker-id ".md")) body)
    blocker-id))

(defn write-packet-map! [file packet]
  (let [now (timestamp)
        packet (assoc packet "updated_at" now)
        lines (for [k (sort (keys packet))
                    :let [v (get packet k)]
                    :when (not (str/blank? (str v)))]
                (str k ": " v))]
    (write-atomic! file (str (str/join "\n" lines) "\n"))))

(defn update-story-packet-on-approval-reject! [root story-id gate reason]
  (let [file (packet-file root story-id)]
    (when (fs/regular-file? file)
      (let [packet (file-map file)
            gate-key (str/replace (packet-gate gate) "-" "_")
            approval-field (str gate-key "_approval")
            detail-field (str gate-key "_approval_detail")]
        (write-packet-map! file
                           (assoc packet
                                  approval-field "rejected"
                                  detail-field reason))))))

(defn clear-story-packet-rejection! [root story-id gate]
  "Remove rejected gate approval fields so the workflow can re-request the gate."
  (let [file (packet-file root story-id)]
    (when (fs/regular-file? file)
      (let [packet (file-map file)
            gate-key (str/replace (packet-gate gate) "-" "_")
            drop-keys #{(str gate-key "_approval")
                        (str gate-key "_approval_detail")
                        (str gate-key "_approval_state")}]
        (write-packet-map! file (apply dissoc packet drop-keys))))))

(defn remove-approval-blocker! [root blocker-id]
  (fs/delete-if-exists (blocker-file root blocker-id))
  (fs/delete-if-exists (blocker-md-file root blocker-id)))

(defn spec-gate? [gate]
  (contains? #{"story" "implementation-plan" "implementation_plan"
               "gherkin" "qa-procedure" "qa_procedure"} gate))

(defn backlog-item-files [root]
  (let [dir (fs/path root ".squad" "backlog")]
    (if (fs/directory? dir)
      (filterv #(and (fs/regular-file? %)
                     (str/ends-with? (str (fs/file-name %)) ".item"))
               (fs/list-dir dir))
      [])))

(defn backlog-file-for-story [root story-id]
  (first (filter #(= story-id (read-value % "story_id"))
                 (backlog-item-files root))))

(defn reopen-backlog-item! [file]
  (let [now (timestamp)
        updated (-> (slurp (str file))
                    (str/replace-first #"(?m)^status: .*$" "status: open")
                    (str/replace-first #"(?m)^updated_at: .*$" (str "updated_at: " now)))]
    (write-atomic! file updated)
    (or (read-value file "id") (str (fs/file-name file)))))

(defn rejected-story-body [root story-id reason]
  (let [story-path (or (read-value (fs/path root ".squad" "stories" story-id "packet")
                                   "story_path")
                       (str "stories/" story-id ".md"))]
    (if (fs/regular-file? (fs/path root story-path))
      (slurp (str (fs/path root story-path)))
      (str "Rejected story " story-id " — restore text and re-approve.\nReason: " reason "\n"))))

(defn write-rejected-backlog-item! [root story-id reason now]
  (let [body (rejected-story-body root story-id reason)
        bl-dir (fs/path root ".squad" "backlog")
        bl-id (str "bl-reject-" story-id)]
    (fs/create-dirs bl-dir)
    (spit (str (fs/path bl-dir (str bl-id ".item")))
          (str "id: " bl-id "\n"
               "title: " story-id " (rejected story)\n"
               "status: open\n"
               "created_at: " now "\n"
               "updated_at: " now "\n"
               "source_story: " story-id "\n"
               "rejection_reason: " reason "\n"
               "body: |\n"
               (->> (str/split-lines body)
                    (map #(str "  " %))
                    (str/join "\n"))
               "\n"))
    bl-id))

(defn return-story-to-backlog! [root story-id reason now]
  (if-let [file (backlog-file-for-story root story-id)]
    (println "BACKLOG:" (reopen-backlog-item! file))
    (println "BACKLOG:" (write-rejected-backlog-item! root story-id reason now))))

(defn reject! [approval-id reason-parts]
  (validate-id! "Approval id" approval-id)
  (let [root (fs/absolutize (project-root))
        source (pending-file root approval-id)
        reason (normalized-detail reason-parts "rejected-by-user")
        now (timestamp)]
    (when-not (fs/regular-file? source)
      (exit! 1 (str "Pending approval not found: " approval-id)))
    (let [approval (file-map source)
          target-kind (get approval "target_kind")
          target-id (get approval "target_id")
          gate (get approval "gate")]
      (move-with-state! source (rejected-file root approval-id) "rejected" reason)
      (write-approval-blocker! root approval-id approval reason now)
      (when (= "story" target-kind)
        (update-story-packet-on-approval-reject! root target-id gate reason)
        (when (spec-gate? gate)
          (return-story-to-backlog! root target-id reason now)))
      (println "SQUAD_APPROVAL:" approval-id)
      (println "STATE: rejected")
      (println "TARGET:" (or target-id "unknown"))
      (println "GATE:" (or gate "unknown"))
      (println "BLOCKER:" (str (fs/path root ".squad" "blockers" approval-id))))))
(defn resolve-rejection!
  "Clear an approval-rejection durable blocker and reopen the gate for re-request.
  Does not auto-approve; workflow may request a new pending approval afterward."
  [approval-id detail-parts]
  (validate-id! "Approval id" approval-id)
  (let [root (fs/absolutize (project-root))
        rejected (rejected-file root approval-id)
        detail (normalized-detail detail-parts "rejection-cleared-for-reentry")
        now (timestamp)]
    (when-not (fs/regular-file? rejected)
      (exit! 1 (str "Rejected approval not found: " approval-id
                    " (resolve-rejection only clears rejected gates)")))
    (let [approval (file-map rejected)
          target-kind (get approval "target_kind")
          target-id (get approval "target_id")
          gate (get approval "gate")
          blocker-id (or (get approval "approval_id") approval-id)]
      (move-with-state! rejected (cleared-file root approval-id) "cleared" detail)
      (remove-approval-blocker! root blocker-id)
      (when (and (= "story" target-kind) (not (str/blank? target-id)) (not (str/blank? gate)))
        (clear-story-packet-rejection! root target-id gate))
      (println "SQUAD_APPROVAL:" approval-id)
      (println "STATE: cleared")
      (println "TARGET:" (or target-id "unknown"))
      (println "GATE:" (or gate "unknown"))
      (println "DETAIL:" detail)
      (println "BLOCKER_REMOVED:" (str (blocker-file root blocker-id)))
      (println "NOTE: gate may be re-requested; not approved"))))

(defn print-one-status! [root approval-id]
  (validate-id! "Approval id" approval-id)
  (if-let [file (approval-file root approval-id)]
    (let [approval (file-map file)]
      (println "APPROVAL:" approval-id)
      (println "STATE:" (get approval "state" "unknown"))
      (println "TARGET_KIND:" (get approval "target_kind" "unknown"))
      (println "TARGET_ID:" (get approval "target_id" "unknown"))
      (println "GATE:" (get approval "gate" "unknown"))
      (println "TITLE:" (get approval "title" ""))
      (println "FILE:" (str file)))
    (exit! 1 (str "Approval not found: " approval-id))))

(defn print-all-status! [root]
  (doseq [state ["pending" "approved" "rejected"]]
    (let [dir (approvals-dir root state)
          files (if (fs/exists? dir)
                  (sort (filter fs/regular-file? (fs/list-dir dir)))
                  [])]
      (doseq [file files]
        (let [approval-id (str/replace (fs/file-name file) #"\.approval$" "")]
          (print-one-status! root approval-id))))))

(defn status! [maybe-approval-id]
  (let [root (fs/absolutize (project-root))]
    (if maybe-approval-id
      (print-one-status! root maybe-approval-id)
      (print-all-status! root))))

(defn exact-count! [args n]
  (when-not (= n (count args))
    (exit! 1 usage-text)))

(defn minimum-count! [args n]
  (when-not (>= (count args) n)
    (exit! 1 usage-text)))

(def approval-commands
  {"required" (fn [args]
                (exact-count! args 2)
                (required! (second args)))
   "request" (fn [args]
               (minimum-count! args 7)
               (request! (second args) (nth args 2) (nth args 3) (nth args 4) (nth args 5) (drop 6 args)))
   "request-bulk" (fn [args]
                    (minimum-count! args 6)
                    (request-bulk! (second args) (nth args 2) (nth args 3) (nth args 4) (drop 5 args)))
   "approve" (fn [args]
               (minimum-count! args 2)
               (approve! (second args) (drop 2 args)))
   "reject" (fn [args]
              (minimum-count! args 2)
              (reject! (second args) (drop 2 args)))
   "resolve-rejection" (fn [args]
                         (minimum-count! args 2)
                         (resolve-rejection! (second args) (drop 2 args)))
   "status" (fn [args]
              (when-not (<= 1 (count args) 2)
                (exit! 1 usage-text))
              (status! (second args)))})

(defn -main [& args]
  (if-let [command (approval-commands (first args))]
    (command args)
    (exit! 1 usage-text)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
