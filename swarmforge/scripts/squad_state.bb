(ns squad-state
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

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
                     "senior_implementor_assignment"
                     "senior_implementor_branch" "senior_implementor_sha"
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
              "senior_implementor_assignment"
              "senior_implementor_branch" "senior_implementor_sha"
              "final_approval" "final_approval_detail"]
   "senior-implementor" ["architecture_review" "architecture_review_assignment"
                         "architecture_review_branch" "architecture_review_sha"
                         "architecture_review_target_sha"
                         "architecture_approval" "architecture_approval_detail"
                         "final_approval" "final_approval_detail"]})

(defn parse-kv-lines [text]
  (into {}
        (keep (fn [line]
                (let [[k v] (str/split line #": " 2)]
                  (when (and k v)
                    [k v]))))
        (str/split-lines (or text ""))))

(defn read-kv-file [file]
  (if (fs/regular-file? file)
    (parse-kv-lines (slurp (str file)))
    {}))

(defn read-value [file field]
  (get (read-kv-file file) field))

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

(defn recompute-state [packet]
  (cond
    (approved? packet "final_approval") "final_approved"
    (approved? packet "architecture_approval") "architecture_approved"
    (current-accepted? packet "architecture_review") "architecture_reviewed"
    (present? packet "senior_implementor_sha") "architecture_revision_returned"
    (present? packet "architecture_sha") "architecture_returned"
    (approved? packet "qa_approval") "qa_approved"
    (present? packet "qa_sha") "qa_returned"
    (approved? packet "hardening_approval") "hardening_approved"
    (present? packet "hardener_sha") "hardener_returned"
    (approved? packet "code_review_approval") "code_review_approved"
    (and (current-accepted? packet "code_review")
         (present? packet "cleaner_sha")) "code_reviewed"
    (present? packet "cleaner_sha") "cleaned"
    (present? packet "implementation_sha") "implemented"
    (approved? packet "implementation_approval") "implementation_approved"
    (and (approved? packet "story_approval")
         (approved? packet "gherkin_approval")
         (approved? packet "qa_procedure_approval")
         (current-accepted? packet "gherkin_review")
         (current-accepted? packet "qa_procedure_review")) "implementation_approval_ready"
    (and (approved? packet "story_approval")
         (or (present? packet "gherkin_assignment")
             (present? packet "qa_procedure_assignment"))) "specification_in_progress"
    (approved? packet "story_approval") "story_approved"
    :else "story_recorded"))

(defn derived-stage-fields [packet state]
  {"story_approval_state" (value-or (get packet "story_approval") "pending")
   "gherkin_assignment_state" (cond
                                (present? packet "gherkin_path") "complete"
                                (present? packet "gherkin_assignment") "assigned"
                                :else "pending")
   "gherkin_review_state" (value-or (current-review packet "gherkin_review")
                                    (if (present? packet "gherkin_path") "pending" "blocked"))
   "gherkin_approval_state" (value-or (get packet "gherkin_approval")
                                      (if (current-accepted? packet "gherkin_review") "pending" "blocked"))
   "qa_procedure_assignment_state" (cond
                                     (present? packet "qa_procedure_path") "complete"
                                     (present? packet "qa_procedure_assignment") "assigned"
                                     :else "pending")
   "qa_procedure_review_state" (value-or (current-review packet "qa_procedure_review")
                                         (if (present? packet "qa_procedure_path") "pending" "blocked"))
   "qa_procedure_approval_state" (value-or (get packet "qa_procedure_approval")
                                           (if (current-accepted? packet "qa_procedure_review") "pending" "blocked"))
   "implementation_approval_state" (value-or (get packet "implementation_approval")
                                             (if (= "implementation_approval_ready" state) "pending" "blocked"))
   "implementation_assignment_state" (cond
                                       (present? packet "implementation_sha") "complete"
                                       (present? packet "implementation_assignment") "assigned"
                                       (approved? packet "implementation_approval") "ready"
                                       :else "blocked")
   "cleaner_review_state" (cond
                            (current-review packet "code_review") (current-review packet "code_review")
                            (present? packet "cleaner_sha") "pending"
                            :else "blocked")
   "hardener_review_state" (cond
                             (approved? packet "hardening_approval") "approved"
                             (present? packet "hardener_sha") "pending"
                             (present? packet "hardener_batch") "batched"
                             :else "blocked")
   "qa_result_state" (cond
                       (approved? packet "qa_approval") "approved"
                       (present? packet "qa_sha") "pending"
                       (present? packet "qa_batch") "batched"
                       :else "blocked")
   "architecture_result_state" (cond
                                 (approved? packet "architecture_approval") "approved"
                                 (current-review packet "architecture_review") (current-review packet "architecture_review")
                                 (present? packet "architecture_sha") "pending_review"
                                 (present? packet "architecture_batch") "batched"
                                 :else "blocked")
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
