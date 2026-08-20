(ns squad-actions
  "Typed workflow actions.

  Candidates should carry :op (keyword or string) and :authority.
  :command remains the shell rendering for the current executor; shell is an
  outer boundary, not the action identity."
  (:require [clojure.string :as str]))

(def authorities
  #{:daemon :sl-residual :troubleshooter :operator})

(def op-authority
  "Default authority for known ops. Unknown ops default to :sl-residual.
  accept_merge is the squad leader's. merge_ready stays daemon-only if used."
  {"wait" :sl-residual
   "wait_for_spawn" :sl-residual
   "request_user_approval" :sl-residual
   ;; Residual only after Troubleshooter route-to-sl (product). Repair chat is TS wake.
   "answer_dashboard_request" :sl-residual
   "handle_durable_blocker" :sl-residual
   "recover_agent" :sl-residual
   ;; Session-dead repair — owner is SL (clean) or Troubleshooter (dirty) via REPAIR_OWNER.
   "repair_dead_agent" :sl-residual
   "retire_agent" :daemon
   "request_spawn" :daemon
   ;; Daemon owns mechanical create/spawn/approval-request; SL residual may still
   ;; surface them when the advisor leaves COMMAND for judgment (allow-list has both).
   "create_assignment" :daemon
   "create_approval_request" :daemon
   "record_auto_approval" :daemon
   "record_merged_result" :daemon
   "record_merged_batch_result" :daemon
   "complete_batch" :daemon
   "record_batch_membership" :daemon
   "record_review_result" :daemon
   "attach_story_artifact" :daemon
   "record_frame_sha" :daemon
   "start_snapshot_item" :daemon
   "declare_merge_blocker" :daemon
   "merge_ready" :daemon
   "accept_merge" :sl-residual
   "check_merge_readiness" :daemon
   "record_assignment_result" :daemon
   "claim_handoff" :daemon
   "process_handoff" :daemon
   "finish_held_handoff" :daemon
   "finish_in_process_handoff" :daemon
   "clear_stale_lock" :daemon})

(defn normalize-op
  "Canonical string op name from keyword or string."
  [op]
  (cond
    (keyword? op) (name op)
    (string? op) op
    :else (str op)))

(defn authority-for
  ([op] (authority-for op nil))
  ([op explicit]
   (or explicit
       (get op-authority (normalize-op op) :sl-residual))))

(defn action
  "Build a structured action map.

  Required: :op (keyword or string).
  Common optional: :command, :reason, :theme-id, :story-id, :assignment-id,
  :template, :authority, plus any planner fields."
  [op & {:as fields}]
  (let [op-name (normalize-op op)
        authority (authority-for op-name (:authority fields))]
    (when-not (contains? authorities authority)
      (throw (ex-info (str "Unknown action authority: " authority)
                      {:op op-name :authority authority})))
    (-> fields
        (dissoc :authority)
        (assoc :op op-name
               :op-kw (keyword op-name)
               :authority authority
               ;; Backward compatible with existing residual/print paths
               :next-action (or (:next-action fields) op-name)))))

(defn ensure-typed
  "Upgrade a legacy candidate map that only has :next-action into a typed action."
  [candidate]
  (if (and (map? candidate) (:op candidate))
    (let [op (normalize-op (:op candidate))]
      (assoc candidate
             :op op
             :op-kw (keyword op)
             :authority (authority-for op (:authority candidate))
             :next-action (or (:next-action candidate) op)))
    (let [op (normalize-op (or (:next-action candidate) (:op candidate) "unknown"))]
      (action op (dissoc (or candidate {}) :op :next-action :authority)))))

(defn op-of [candidate]
  (normalize-op (or (:op candidate) (:next-action candidate))))

(defn shell-command
  "Outer shell rendering for an action (CLI boundary only)."
  [candidate]
  (or (:command candidate) ""))

(defn authority-of [candidate]
  (:authority (ensure-typed candidate)))
