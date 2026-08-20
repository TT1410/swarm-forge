(ns squad-state
  "Squad packet/assignment state helpers. Kv reads go through squad-records."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [squad-records :as rec]))

(def terminal-batch-states
  #{"result_received" "rejected" "merged" "closed" "complete" "failed"})

(def stage-target-fields
  {"gherkin_review" "gherkin_sha"
   "qa_procedure_review" "qa_procedure_sha"
   "code_review" "cleaner_sha"
   "architecture_review" "architecture_sha"})

(def downstream-fields
  {"implementation" ["cleaner_assignment" "cleaner_branch" "cleaner_sha"
                     "cleaner_review_state"
                     "code_review" "code_review_assignment" "code_review_branch"
                     "code_review_sha" "code_review_target_sha"
                     "code_review_approval" "code_review_approval_detail"
                     "hardener_batch" "hardener_batch_stage"
                     "hardener_batch_assignment" "hardener_batch_branch"
                     "hardener_batch_sha" "hardener_assignment"
                     "hardener_branch" "hardener_sha" "hardener_review_state"
                     "hardening_approval" "hardening_approval_detail"
                     "qa_batch" "qa_batch_stage" "qa_batch_assignment"
                     "qa_batch_branch" "qa_batch_sha" "qa_assignment"
                     "qa_branch" "qa_sha" "qa_result_state"
                     "qa_approval" "qa_approval_detail"
                     "architecture_batch" "architecture_batch_stage"
                     "architecture_batch_assignment" "architecture_batch_branch"
                     "architecture_batch_sha" "architecture_assignment"
                     "architecture_branch" "architecture_sha"
                     "architecture_review" "architecture_review_assignment"
                     "architecture_review_branch" "architecture_review_sha"
                     "architecture_review_target_sha"
                     "architecture_approval" "architecture_approval_detail"
                     "senior_implementer_assignment"
                     "senior_implementer_branch" "senior_implementer_sha"
                     "final_approval" "final_approval_detail"]
   "cleaner" ["code_review" "code_review_assignment" "code_review_branch"
              "code_review_sha" "code_review_target_sha"
              "code_review_approval" "code_review_approval_detail"
              "hardener_batch" "hardener_batch_stage"
              "hardener_batch_assignment" "hardener_batch_branch"
              "hardener_batch_sha" "hardener_assignment"
              "hardener_branch" "hardener_sha" "hardener_review_state"
              "hardening_approval" "hardening_approval_detail"
              "qa_batch" "qa_batch_stage" "qa_batch_assignment"
              "qa_batch_branch" "qa_batch_sha" "qa_assignment"
              "qa_branch" "qa_sha" "qa_result_state"
              "qa_approval" "qa_approval_detail"
              "architecture_batch" "architecture_batch_stage"
              "architecture_batch_assignment" "architecture_batch_branch"
              "architecture_batch_sha" "architecture_assignment"
              "architecture_branch" "architecture_sha"
              "architecture_review" "architecture_review_assignment"
              "architecture_review_branch" "architecture_review_sha"
              "architecture_review_target_sha"
              "architecture_approval" "architecture_approval_detail"
              "senior_implementer_assignment"
              "senior_implementer_branch" "senior_implementer_sha"
              "final_approval" "final_approval_detail"]
   "senior-implementer" ["final_approval" "final_approval_detail"]})

(defn parse-kv-lines
  "Parse flat key: value lines. Delegates to squad-records."
  [text]
  (rec/parse-kv-text text))

(defn read-kv-file
  "Read a flat key:value file. Delegates to squad-records."
  [file]
  (rec/read-kv-file file))

(defn read-value [file field]
  (rec/kv-get file field))

(defn accepted? [packet field]
  (= "accepted" (get packet field)))

(defn approved? [packet field]
  (= "approved" (get packet field)))

(defn present? [packet field]
  (not (str/blank? (get packet field))))

(defn target-sha [packet review-field]
  (get packet (get stage-target-fields review-field)))

(defn review-current? [packet review-field]
  (let [recorded-target (get packet (str review-field "_target_sha"))
        current-target (target-sha packet review-field)]
    (or (str/blank? recorded-target)
        (and (not (str/blank? current-target))
             (= recorded-target current-target)))))

(defn current-review [packet review-field]
  (when (review-current? packet review-field)
    (get packet review-field)))

(defn current-accepted? [packet review-field]
  (= "accepted" (current-review packet review-field)))

(defn current-changes-requested? [packet review-field]
  (= "changes-requested" (current-review packet review-field)))

(defn value-or [value fallback]
  (if (str/blank? value) fallback value))

(defn implementation-ready? [packet]
  (and (approved? packet "implementation_plan_approval")
       (approved? packet "gherkin_approval")
       (approved? packet "qa_procedure_approval")))

(defn specification-in-progress? [packet]
  (and (approved? packet "story_approval")
       (or (present? packet "gherkin_assignment")
           (present? packet "qa_procedure_assignment"))))

(def state-transitions
  [["final_approved" #(approved? % "final_approval")]
   ["architecture_approved" #(approved? % "architecture_approval")]
   ["architecture_reviewed" #(current-accepted? % "architecture_review")]
   ["architecture_revision_returned" #(present? % "senior_implementer_sha")]
   ["architecture_returned" #(present? % "architecture_sha")]
   ["qa_approved" #(approved? % "qa_approval")]
   ["qa_returned" #(present? % "qa_sha")]
   ["hardening_approved" #(approved? % "hardening_approval")]
   ["hardener_returned" #(present? % "hardener_sha")]
   ["code_review_approved" #(approved? % "code_review_approval")]
   ["code_reviewed" #(and (current-accepted? % "code_review")
                          (present? % "cleaner_sha"))]
   ["cleaned" #(present? % "cleaner_sha")]
   ["implemented" #(present? % "implementation_sha")]
   ["implementation_approved" #(approved? % "implementation_approval")]
   ["implementation_approval_ready" implementation-ready?]
   ["specification_in_progress" specification-in-progress?]
   ["story_approved" #(approved? % "story_approval")]])

(defn recompute-state [packet]
  (or (some (fn [[state matches?]]
              (when (matches? packet)
                state))
            state-transitions)
      "story_recorded"))

(defn assignment-state [packet path-field assignment-field]
  (cond
    (present? packet path-field) "complete"
    (present? packet assignment-field) "assigned"
    :else "pending"))

(defn review-state [packet review-field path-field]
  (value-or (current-review packet review-field)
            (if (present? packet path-field) "pending" "blocked")))

(defn path-approval-state [packet approval-field path-field]
  (value-or (get packet approval-field)
            (if (present? packet path-field) "pending" "blocked")))

(def implementation-assignment-rules
  [["complete" #(present? % "implementation_sha")]
   ["assigned" #(present? % "implementation_assignment")]
   ["ready" #(and (implementation-ready? %)
                  (not (present? % "implementation_sha")))]])

(defn state-from-rules [rules packet default-state]
  (or (some (fn [[state predicate]]
              (when (predicate packet)
                state))
            rules)
      default-state))

(defn implementation-assignment-state [packet]
  (state-from-rules implementation-assignment-rules packet "blocked"))

(defn cleaner-review-state [packet]
  (or (current-review packet "code_review")
      (when (present? packet "cleaner_sha") "pending")
      "blocked"))

(defn batched-result-rules [approval-field result-field batch-field]
  [["approved" #(approved? % approval-field)]
   ["pending" #(present? % result-field)]
   ["batched" #(present? % batch-field)]])

(defn batched-result-state [packet approval-field result-field batch-field]
  (state-from-rules (batched-result-rules approval-field result-field batch-field) packet "blocked"))

(defn architecture-result-state [packet]
  (or (when (approved? packet "architecture_approval") "approved")
      (current-review packet "architecture_review")
      (when (present? packet "architecture_sha") "pending_review")
      (when (present? packet "architecture_batch") "batched")
      "blocked"))

(defn derived-stage-fields [packet state]
  {"story_approval_state" (value-or (get packet "story_approval") "pending")
   "gherkin_assignment_state" (assignment-state packet "gherkin_path" "gherkin_assignment")
   "gherkin_review_state" (review-state packet "gherkin_review" "gherkin_path")
   "gherkin_approval_state" (path-approval-state packet "gherkin_approval" "gherkin_path")
   "qa_procedure_assignment_state" (assignment-state packet "qa_procedure_path" "qa_procedure_assignment")
   "qa_procedure_review_state" (review-state packet "qa_procedure_review" "qa_procedure_path")
   "qa_procedure_approval_state" (path-approval-state packet "qa_procedure_approval" "qa_procedure_path")
   "implementation_approval_state" (value-or (get packet "implementation_approval")
                                             (if (= "implementation_approval_ready" state) "pending" "blocked"))
   "implementation_assignment_state" (implementation-assignment-state packet)
   "cleaner_review_state" (cleaner-review-state packet)
   "hardener_review_state" (batched-result-state packet "hardening_approval" "hardener_sha" "hardener_batch")
   "qa_result_state" (batched-result-state packet "qa_approval" "qa_sha" "qa_batch")
   "architecture_result_state" (architecture-result-state packet)
   "final_state" state})

(defn clear-downstream [packet kind]
  (apply dissoc packet (get downstream-fields kind [])))

(defn with-review-target [packet review-field]
  (if-let [sha (target-sha packet review-field)]
    (assoc packet (str review-field "_target_sha") sha)
    packet))

(defn batch-dir [root batch-id]
  (fs/path root ".squad" "batches" batch-id))

(defn batch-state [root batch-id]
  (let [dir (batch-dir root batch-id)
        state-file (if (fs/exists? (fs/path dir "status"))
                     (fs/path dir "status")
                     (fs/path dir "state"))]
    (read-value state-file "state")))

(defn active-batch? [root batch-id]
  (and (not (str/blank? batch-id))
       (not (contains? terminal-batch-states (batch-state root batch-id)))))

(defn active-batch-index [root story-id kind]
  (let [file (fs/path root ".squad" "stories" story-id "active-batches" kind)]
    (when (fs/regular-file? file)
      (str/trim (slurp (str file))))))

(defn consistency-issues [root packet]
  (let [story-id (get packet "story_id")]
    (vec
     (concat
      (for [[review-field target-field] stage-target-fields
            :let [recorded-target (get packet (str review-field "_target_sha"))
                  current-target (get packet target-field)]
            :when (and (not (str/blank? recorded-target))
                       (not (str/blank? current-target))
                       (not= recorded-target current-target))]
        {:code "stale-review"
         :field review-field
         :detail (str review-field " reviewed " recorded-target
                      " but current " target-field " is " current-target)})
      (for [kind ["hardener" "qa" "architecture"]
            :let [packet-batch (get packet (str kind "_batch"))
                  index-batch (active-batch-index root story-id kind)]
            :when (and (not (str/blank? index-batch))
                       (or (not= packet-batch index-batch)
                           (not (active-batch? root index-batch))))]
        {:code "stale-active-batch-index"
         :field (str "active-batches/" kind)
         :detail (str "index points to " index-batch
                      ", packet points to " (or packet-batch "none")
                      ", index-active=" (active-batch? root index-batch))})))))
