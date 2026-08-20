#!/usr/bin/env bb

(ns squad-packet
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-config :as cfg]
            [squad-state :as squad-state]
            [clojure.string :as str]))

(def script-dir (fs/parent *file*))

(def usage-text
  (str "Usage:\n"
       "  squad_packet.sh create <story-id> <story-assignment-id> <branch> <sha>\n"
       "  squad_packet.sh attach <story-id> <gherkin|qa-procedure|implementation-plan> <assignment-id> <branch> <sha> <artifact-file>\n"
       "  squad_packet.sh review <story-id> <code|architecture> <accepted|changes-requested> <assignment-id> <branch> <sha>\n"
       "  squad_packet.sh approve <story-id> <implementation-plan|gherkin|qa-procedure|implementation|code-review|hardening|qa|architecture> <detail...>\n"
       "  squad_packet.sh record <story-id> <implementation|cleaner|hardener|qa|architecture|senior-implementer> <assignment-id> <branch> <sha>\n"
       "  squad_packet.sh batch <story-id> <hardener|qa|architecture|architecture-fix> <batch-id> <stage> <assignment-id> <branch> <sha>\n"
       "  squad_packet.sh status <story-id>\n"
       "  squad_packet.sh validate <story-id>"))

(def valid-id #"[A-Za-z0-9][A-Za-z0-9._-]*")
(def artifact-kinds #{"gherkin" "qa-procedure" "implementation-plan"})
(def review-kinds #{"code" "architecture"})
(def result-kinds #{"implementation" "cleaner" "hardener" "qa" "architecture" "senior-implementer"})
(def batch-kinds #{"hardener" "qa" "architecture" "architecture-fix"})
(def approval-gates #{"implementation-plan" "gherkin" "qa-procedure" "implementation"
                      "code-review" "hardening" "qa" "architecture"})

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

(defn validate-sha! [sha]
  (when-not (re-matches #"[0-9a-fA-F]{7,40}" sha)
    (exit! 2 "SHA must be a git commit abbreviation or full SHA.")))

(defn write-atomic! [file content]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                  :prefix (str "." (fs/file-name file) ".")})]
    (spit (str tmp) content)
    (fs/move tmp file {:replace-existing true})))

(defn append-line! [file line]
  (fs/create-dirs (fs/parent file))
  (spit (str file) (str line "\n") :append true))

(defn story-dir [root story-id]
  (fs/path root ".squad" "stories" story-id))

(defn packet-file [root story-id]
  (fs/path (story-dir root story-id) "packet"))

(defn ensure-packet! [root story-id]
  (let [file (packet-file root story-id)]
    (when-not (fs/regular-file? file)
      (exit! 1 (str "Story packet not found: " story-id)))
    file))

(defn source-file! [path]
  (let [file (fs/path path)
        file (if (fs/absolute? file) file (fs/path (fs/cwd) file))]
    (when-not (fs/regular-file? file)
      (exit! 1 (str "Source file not found: " file)))
    file))

(defn relative-project-file! [root file allowed-prefixes message]
  (let [root-path (.normalize (.toAbsolutePath (fs/path root)))
        file-path (.normalize (.toAbsolutePath (fs/path file)))]
    (when-not (.startsWith file-path root-path)
      (exit! 2 message))
    (let [relative (str/replace (str (.relativize root-path file-path)) "\\" "/")]
      (when-not (some #(str/starts-with? relative %) allowed-prefixes)
        (exit! 2 message))
      relative)))

(defn packet-map [root story-id]
  (let [file (packet-file root story-id)]
    (if-not (fs/exists? file)
      {}
      (into {}
            (keep (fn [line]
                    (let [[k v] (str/split line #": " 2)]
                      (when (and k v)
                        [k v]))))
            (str/split-lines (slurp (str file)))))))

(defn accepted? [packet field]
  (= "accepted" (get packet field)))

(defn approved? [packet field]
  (= "approved" (get packet field)))

(defn value-or [value fallback]
  (if (str/blank? value) fallback value))

(defn append-iteration [packet stage assignment-id state]
  (let [field (str stage "_iterations")
        entry (str assignment-id "=" state)
        existing (get packet field)]
    (assoc packet field
           (if (str/blank? existing)
             entry
             (str existing "," entry)))))

(defn ordered-packet-lines [packet]
  (let [ordered ["story_id" "story_number" "theme_id" "state" "final_state"
                 "story_path" "story_assignment" "story_branch" "story_sha"
                 "story_approval" "story_approval_state" "story_approval_detail"
                 "story_iterations"
                 "gherkin_path" "gherkin_assignment" "gherkin_branch" "gherkin_sha"
                 "gherkin_assignment_state" "gherkin_iterations"
                 "gherkin_review" "gherkin_review_state" "gherkin_review_assignment"
                 "gherkin_review_branch" "gherkin_review_sha" "gherkin_review_target_sha"
                 "gherkin_review_iterations"
                 "gherkin_approval" "gherkin_approval_state" "gherkin_approval_detail"
                 "gherkin_approval_iterations"
                 "qa_procedure_path" "qa_implementer_notes_path"
                 "qa_procedure_assignment" "qa_procedure_branch" "qa_procedure_sha"
                 "qa_procedure_assignment_state" "qa_procedure_iterations"
                 "qa_procedure_review" "qa_procedure_review_state" "qa_procedure_review_assignment"
                 "qa_procedure_review_branch" "qa_procedure_review_sha" "qa_procedure_review_target_sha"
                 "qa_procedure_review_iterations"
                 "qa_procedure_approval" "qa_procedure_approval_state" "qa_procedure_approval_detail"
                 "qa_procedure_approval_iterations"
                 "implementation_approval" "implementation_approval_state" "implementation_approval_detail"
                 "implementation_approval_iterations"
                 "implementation_assignment" "implementation_branch" "implementation_sha"
                 "implementation_assignment_state" "implementation_iterations"
                 "cleaner_assignment" "cleaner_branch" "cleaner_sha"
                 "cleaner_review_state" "cleaner_iterations"
                 "code_review" "code_review_assignment" "code_review_branch" "code_review_sha"
                 "code_review_target_sha" "code_review_iterations" "code_review_approval" "code_review_approval_detail"
                 "code_review_approval_iterations"
                 "hardener_batch" "hardener_batch_stage" "hardener_batch_assignment"
                 "hardener_batch_branch" "hardener_batch_sha" "hardener_batch_iterations"
                 "hardener_assignment" "hardener_branch" "hardener_sha"
                 "hardener_review_state" "hardener_iterations"
                 "hardening_approval" "hardening_approval_detail" "hardening_approval_iterations"
                 "qa_batch" "qa_batch_stage" "qa_batch_assignment" "qa_batch_branch" "qa_batch_sha"
                 "qa_batch_iterations" "qa_assignment" "qa_branch" "qa_sha"
                 "qa_result_state" "qa_iterations" "qa_approval" "qa_approval_detail"
                 "qa_approval_iterations"
                 "architecture_batch" "architecture_batch_stage" "architecture_batch_assignment"
                 "architecture_batch_branch" "architecture_batch_sha" "architecture_batch_iterations"
                 "architecture_assignment" "architecture_branch" "architecture_sha"
                 "architecture_result_state" "architecture_iterations"
                 "architecture_review" "architecture_review_assignment"
                 "architecture_review_branch" "architecture_review_sha" "architecture_review_target_sha"
                 "architecture_review_iterations"
                 "architecture_approval" "architecture_approval_detail" "architecture_approval_iterations"
                 "architecture_fix_batch" "architecture_fix_batch_stage"
                 "architecture_fix_batch_assignment" "architecture_fix_batch_branch"
                 "architecture_fix_batch_sha" "architecture_fix_batch_iterations"
                 "senior_implementer_assignment" "senior_implementer_branch"
                 "senior_implementer_sha" "senior_implementer_iterations"
                 "final_approval" "final_approval_detail" "final_approval_iterations"
                 "updated_at"]
        emitted (set ordered)]
    (concat
     (keep (fn [k]
             (when-let [v (get packet k)]
               (str k ": " v)))
           ordered)
     (for [k (sort (remove emitted (keys packet)))]
       (str k ": " (get packet k))))))

(defn write-packet! [root story-id packet]
  (let [now (timestamp)
        state (squad-state/recompute-state packet)
        packet (merge packet (squad-state/derived-stage-fields packet state))
        packet (assoc packet
                      "state" state
                      "updated_at" now)]
    (write-atomic! (packet-file root story-id)
                   (str (str/join "\n" (ordered-packet-lines packet)) "\n"))
    packet))

(defn event! [root story-id state & fields]
  (append-line! (fs/path (story-dir root story-id) "events.log")
                (str/join "\t" (concat [(timestamp) state] fields))))

(defn story-source-for-create [root story-id]
  (let [rel (str "stories/" story-id ".md")]
    (when-not (fs/regular-file? (fs/path root rel))
      (exit! 1 (str "Story file not found: " rel)))
    {:story-path rel}))

(defn print-created-packet! [root story-id packet story-path]
  (println "SQUAD_PACKET:" story-id)
  (println "STATE:" (get packet "state"))
  (println "STORY:" story-path)
  (println "PACKET:" (str (packet-file root story-id))))

(defn create-packet! [story-id assignment-id branch sha]
  (doseq [[kind value] [["Story id" story-id]
                        ["Assignment id" assignment-id]
                        ["Branch" branch]]]
    (validate-id! kind value))
  (validate-sha! sha)
  (let [root (fs/absolutize (project-root))
        source (story-source-for-create root story-id)
        dir (story-dir root story-id)]
    (when (fs/exists? (packet-file root story-id))
      (exit! 2 (str "Story packet already exists: " story-id)))
    (fs/create-dirs dir)
    (let [base {"story_id" story-id
                "story_path" (:story-path source)
                "story_assignment" assignment-id
                "story_branch" branch
                "story_sha" sha}
          packet (write-packet! root story-id
                                (append-iteration base "story" assignment-id "recorded"))]
      (event! root story-id "story_recorded" assignment-id branch sha)
      (print-created-packet! root story-id packet (:story-path source)))))

(def artifact-path-rules
  {"gherkin" {:prefixes ["features/"]
              :message "Gherkin artifacts must live under features/."}
   "qa-procedure" {:prefixes ["qa/"]
                   :message "QA procedure artifacts must live under qa/."}
   "implementation-plan" {:prefixes [".squad/stories/" "stories/"]
                          :message "Implementation plans must live under .squad/stories/ or stories/."}})

(defn validate-artifact-kind! [kind]
  (when-not (contains? artifact-kinds kind)
    (exit! 2 "Artifact kind must be gherkin, qa-procedure, or implementation-plan.")))

(defn relative-artifact-file! [root kind file]
  (let [{:keys [prefixes message]} (artifact-path-rules kind)]
    (relative-project-file! root file prefixes message)))

(defn artifact-review-fields [prefix]
  [(str prefix "_review")
   (str prefix "_review_assignment")
   (str prefix "_review_branch")
   (str prefix "_review_sha")
   (str prefix "_review_target_sha")])

(defn artifact-approval-fields [prefix]
  [(str prefix "_approval")
   (str prefix "_approval_detail")])

(defn packet-with-artifact [packet prefix relative assignment-id branch sha]
  (let [reset-fields (concat (artifact-approval-fields prefix)
                             (artifact-review-fields prefix))]
    (append-iteration
     (assoc (apply dissoc packet reset-fields)
            (str prefix "_path") relative
            (str prefix "_assignment") assignment-id
            (str prefix "_branch") branch
            (str prefix "_sha") sha)
     prefix assignment-id "attached")))

(defn print-artifact-attached! [story-id packet kind relative packet-file]
  (println "SQUAD_PACKET:" story-id)
  (println "STATE:" (get packet "state"))
  (println "ARTIFACT:" kind)
  (println "PATH:" relative)
  (println "PACKET:" (str packet-file)))

(defn implementer-notes-relative [procedure-relative]
  (when (and procedure-relative
             (str/ends-with? procedure-relative ".md")
             (not (str/includes? procedure-relative "implementer-notes")))
    (str (subs procedure-relative 0 (- (count procedure-relative) 3))
         "-implementer-notes.md")))

(defn attach-artifact! [story-id kind assignment-id branch sha artifact-path]
  (validate-artifact-kind! kind)
  (doseq [[label value] [["Story id" story-id]
                         ["Assignment id" assignment-id]
                         ["Branch" branch]]]
    (validate-id! label value))
  (validate-sha! sha)
  (let [root (fs/absolutize (project-root))
        file (source-file! artifact-path)
        relative (relative-artifact-file! root kind file)
        packet-file (ensure-packet! root story-id)
        packet (packet-map root story-id)
        prefix (str/replace kind "-" "_")
        packet (packet-with-artifact packet prefix relative assignment-id branch sha)
        notes (when (= "qa-procedure" kind)
                (implementer-notes-relative relative))
        packet (cond-> packet
                 (and notes (fs/regular-file? (fs/path root notes)))
                 (assoc "qa_implementer_notes_path" notes))
        packet (write-packet! root story-id packet)]
    (event! root story-id (str prefix "_attached") assignment-id branch sha relative)
    (print-artifact-attached! story-id packet kind relative packet-file)))

(defn review-artifact! [story-id kind decision assignment-id branch sha]
  (when-not (contains? review-kinds kind)
    (exit! 2 "Review kind must be code or architecture."))
  (when-not (#{"accepted" "changes-requested"} decision)
    (exit! 2 "Review decision must be accepted or changes-requested."))
  (doseq [[label value] [["Story id" story-id]
                         ["Assignment id" assignment-id]
                         ["Branch" branch]]]
    (validate-id! label value))
  (validate-sha! sha)
  (let [root (fs/absolutize (project-root))
        packet (packet-map root story-id)
        _ (ensure-packet! root story-id)
        prefix (str/replace kind "-" "_")
        packet (write-packet! root story-id
                              (append-iteration
                               (squad-state/with-review-target
                                (assoc packet
                                       (str prefix "_review") decision
                                       (str prefix "_review_assignment") assignment-id
                                       (str prefix "_review_branch") branch
                                       (str prefix "_review_sha") sha)
                                (str prefix "_review"))
                               (str prefix "_review") assignment-id decision))]
    (event! root story-id (str prefix "_review_" decision) assignment-id branch sha)
    (println "SQUAD_PACKET:" story-id)
    (println "STATE:" (get packet "state"))
    (println "REVIEW:" kind)
    (println "DECISION:" decision)))

(defn approve! [story-id gate detail-parts]
  (when-not (contains? approval-gates gate)
    (exit! 2 "Approval gate must be implementation-plan, gherkin, qa-procedure, implementation, code-review, hardening, qa, or architecture."))
  (validate-id! "Story id" story-id)
  (let [root (fs/absolutize (project-root))
        _ (ensure-packet! root story-id)
        packet (packet-map root story-id)
        gate-key (str/replace gate "-" "_")
        detail (str/replace (str/join " " detail-parts) #"\R+" " ")
        detail (if (str/blank? detail) "approved" detail)
        packet (write-packet! root story-id
                              (append-iteration
                               (assoc packet
                                      (str gate-key "_approval") "approved"
                                      (str gate-key "_approval_detail") detail)
                               (str gate-key "_approval") gate-key "approved"))]
    (event! root story-id (str gate-key "_approved") detail)
    (println "SQUAD_PACKET:" story-id)
    (println "STATE:" (get packet "state"))
    (println "APPROVAL:" gate)))

(defn git-commit-subject [root sha]
  (str/trim (:out (process/sh {:continue true :dir (str root)}
                              "git" "log" "-1" "--pretty=%s" sha))))

(defn qa-commit-failed?
  "Detect explicit batch QA failure in commit subject (handoff artifact)."
  [root sha]
  (boolean (re-find #"(?i)qa failure|failed final batch qa|final_qa_fail|htw_final_qa_fail"
                    (or (git-commit-subject root sha) ""))))

(defn record-result! [story-id kind assignment-id branch sha]
  (when-not (contains? result-kinds kind)
    (exit! 2 "Result kind must be implementation, cleaner, hardener, qa, architecture, or senior-implementer."))
  (doseq [[label value] [["Story id" story-id]
                         ["Assignment id" assignment-id]
                         ["Branch" branch]]]
    (validate-id! label value))
  (validate-sha! sha)
  (let [root (fs/absolutize (project-root))
        _ (ensure-packet! root story-id)
        packet (packet-map root story-id)
        prefix (str/replace kind "-" "_")
        qa-failed? (and (= "qa" kind) (qa-commit-failed? root sha))
        packet (squad-state/clear-downstream packet kind)
        packet (write-packet! root story-id
                              (append-iteration
                               (cond-> (assoc packet
                                              (str prefix "_assignment") assignment-id
                                              (str prefix "_branch") branch
                                              (str prefix "_sha") sha)
                                 qa-failed?
                                 (assoc "qa_verdict" "failed"
                                        "qa_result_state" "failed"))
                               prefix assignment-id (if qa-failed? "failed" "recorded")))]
    (event! root story-id (str prefix (if qa-failed? "_failed" "_recorded"))
            assignment-id branch sha)
    (when qa-failed?
      ;; Durable Attention signal — not auto-approved
      (let [blocker-dir (fs/path root ".squad" "blockers")
            bid (str "qa-failed__" story-id)]
        (fs/create-dirs blocker-dir)
        (spit (str (fs/path blocker-dir bid))
              (str "blocker_id: " bid "\n"
                   "kind: qa-failed\n"
                   "target_kind: story\n"
                   "target_id: " story-id "\n"
                   "assignment_id: " assignment-id "\n"
                   "sha: " sha "\n"
                   "detail: batch QA reported failure; do not auto-approve\n"
                   "updated_at: " (java.time.Instant/now) "\n"))))
    (println "SQUAD_PACKET:" story-id)
    (println "STATE:" (get packet "state"))
    (println "RESULT:" kind)
    (when qa-failed?
      (println "QA_VERDICT: failed"))
    (println "ASSIGNMENT:" assignment-id)
    (println "BRANCH:" branch)
    (println "SHA:" sha)))
(defn batch-story! [story-id kind batch-id stage assignment-id branch sha]
  (when-not (contains? batch-kinds kind)
    (exit! 2 "Batch kind must be hardener, qa, architecture, or architecture-fix."))
  (doseq [[label value] [["Story id" story-id]
                         ["Batch id" batch-id]
                         ["Stage" stage]
                         ["Assignment id" assignment-id]
                         ["Branch" branch]]]
    (validate-id! label value))
  (validate-sha! sha)
  (let [root (fs/absolutize (project-root))
        prefix (str/replace kind "-" "_")
        _ (ensure-packet! root story-id)
        packet (packet-map root story-id)
        packet (write-packet! root story-id
                              (append-iteration
                               (assoc packet
                                      (str prefix "_batch") batch-id
                                      (str prefix "_batch_stage") stage
                                      (str prefix "_batch_assignment") assignment-id
                                      (str prefix "_batch_branch") branch
                                      (str prefix "_batch_sha") sha)
                               (str prefix "_batch") batch-id "member"))]
    (event! root story-id (str prefix "_batch_added") batch-id stage assignment-id branch sha)
    (println "SQUAD_PACKET:" story-id)
    (println "STATE:" (get packet "state"))
    (println "BATCH:" batch-id)
    (println "KIND:" kind)))

(defn print-status! [story-id]
  (validate-id! "Story id" story-id)
  (let [root (fs/absolutize (project-root))
        file (ensure-packet! root story-id)
        packet (packet-map root story-id)
        issues (squad-state/consistency-issues root packet)]
    (println "STORY:" story-id)
    (println "THEME:" (get packet "theme_id" "unknown"))
    (println "STATE:" (get packet "state" "unknown"))
    (println "FINAL_STATE:" (get packet "final_state" "unknown"))
    (println "STORY_PATH:" (get packet "story_path" "none"))
    (println "STORY_APPROVAL:" (get packet "story_approval" "none"))
    (println "STORY_APPROVAL_STATE:" (get packet "story_approval_state" "none"))
    (println "GHERKIN:" (get packet "gherkin_path" "none"))
    (println "GHERKIN_ASSIGNMENT_STATE:" (get packet "gherkin_assignment_state" "none"))
    (println "GHERKIN_REVIEW:" (get packet "gherkin_review" "none"))
    (println "GHERKIN_REVIEW_STATE:" (get packet "gherkin_review_state" "none"))
    (println "GHERKIN_APPROVAL:" (get packet "gherkin_approval" "none"))
    (println "GHERKIN_APPROVAL_STATE:" (get packet "gherkin_approval_state" "none"))
    (println "QA_PROCEDURE:" (get packet "qa_procedure_path" "none"))
    (println "QA_PROCEDURE_ASSIGNMENT_STATE:" (get packet "qa_procedure_assignment_state" "none"))
    (println "QA_PROCEDURE_REVIEW:" (get packet "qa_procedure_review" "none"))
    (println "QA_PROCEDURE_REVIEW_STATE:" (get packet "qa_procedure_review_state" "none"))
    (println "QA_PROCEDURE_APPROVAL:" (get packet "qa_procedure_approval" "none"))
    (println "QA_PROCEDURE_APPROVAL_STATE:" (get packet "qa_procedure_approval_state" "none"))
    (println "IMPLEMENTATION_APPROVAL:" (get packet "implementation_approval" "none"))
    (println "IMPLEMENTATION_APPROVAL_STATE:" (get packet "implementation_approval_state" "none"))
    (println "IMPLEMENTATION:" (get packet "implementation_sha" "none"))
    (println "IMPLEMENTATION_ASSIGNMENT_STATE:" (get packet "implementation_assignment_state" "none"))
    (println "CLEANER:" (get packet "cleaner_sha" "none"))
    (println "CLEANER_REVIEW_STATE:" (get packet "cleaner_review_state" "none"))
    (println "CODE_REVIEW:" (get packet "code_review" "none"))
    (println "HARDENER_BATCH:" (get packet "hardener_batch" "none"))
    (println "HARDENER:" (get packet "hardener_sha" "none"))
    (println "HARDENER_REVIEW_STATE:" (get packet "hardener_review_state" "none"))
    (println "QA_BATCH:" (get packet "qa_batch" "none"))
	    (println "QA:" (get packet "qa_sha" "none"))
	    (println "QA_RESULT_STATE:" (get packet "qa_result_state" "none"))
	    (println "ARCHITECTURE_BATCH:" (get packet "architecture_batch" "none"))
	    (println "ARCHITECTURE:" (get packet "architecture_sha" "none"))
	    (println "ARCHITECTURE_REVIEW:" (get packet "architecture_review" "none"))
	    (println "ARCHITECTURE_RESULT_STATE:" (get packet "architecture_result_state" "none"))
	    (println "SENIOR_IMPLEMENTER:" (get packet "senior_implementer_sha" "none"))
	    (println "FINAL_APPROVAL:" (get packet "final_approval" "none"))
	    (println "CONSISTENCY:" (if (seq issues) "issues" "ok"))
	    (println "PACKET:" (str file))))

(defn validate-packet! [story-id]
  (validate-id! "Story id" story-id)
  (let [root (fs/absolutize (project-root))
        _ (ensure-packet! root story-id)
        packet (packet-map root story-id)
        issues (squad-state/consistency-issues root packet)]
    (println "STORY:" story-id)
    (if (seq issues)
      (do
        (println "CONSISTENCY: issues")
        (doseq [{:keys [code field detail]} issues]
          (println "ISSUE:" code)
          (println "FIELD:" field)
          (println "DETAIL:" detail))
        (System/exit 3))
      (println "CONSISTENCY: ok"))))

(defn exact-count! [args expected]
  (when-not (= expected (count args))
    (exit! 1 usage-text)))

(defn minimum-count! [args expected]
  (when-not (>= (count args) expected)
    (exit! 1 usage-text)))

(def packet-commands
  {"create" (fn [args] (exact-count! args 5) (apply create-packet! (rest args)))
   "attach" (fn [args] (exact-count! args 7) (apply attach-artifact! (rest args)))
   "review" (fn [args] (exact-count! args 7) (apply review-artifact! (rest args)))
   "approve" (fn [args] (minimum-count! args 4) (approve! (second args) (nth args 2) (drop 3 args)))
   "record" (fn [args] (exact-count! args 6) (apply record-result! (rest args)))
   "batch" (fn [args] (exact-count! args 8) (apply batch-story! (rest args)))
   "status" (fn [args] (exact-count! args 2) (print-status! (second args)))
   "validate" (fn [args] (exact-count! args 2) (validate-packet! (second args)))})

(defn -main [& args]
  (if-let [command (packet-commands (first args))]
    (command args)
    (exit! 1 usage-text)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
