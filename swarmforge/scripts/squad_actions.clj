(ns squad-actions
  "Typed workflow actions (B17).

  Candidates should carry :op (keyword or string) and :authority.
  :command remains the shell rendering for the current executor; shell is an
  outer boundary, not the action identity."
  (:require [clojure.string :as str]))

(def authorities
  #{:daemon :sl-residual :troubleshooter :operator})

(def op-authority
  "Default authority for known ops (B16). Unknown ops default to :sl-residual.
  Daemon-only main-git ops (accept_merge, merge_ready, check_merge_readiness) must
  never default to SL residual."
  {"wait" :sl-residual
   "wait_for_daemon_main_git" :sl-residual
   "wait_for_spawn" :sl-residual
   "request_user_approval" :sl-residual
   ;; Residual only after Troubleshooter route-to-sl (product). Repair chat is TS wake.
   "answer_dashboard_request" :sl-residual
   "handle_durable_blocker" :sl-residual
   "recover_agent" :sl-residual
   ;; B38: session-dead repair — owner is SL (clean) or Troubleshooter (dirty) via REPAIR_OWNER.
   "repair_dead_agent" :sl-residual
   "write_theme_module_map" :sl-residual
   "complete_dependency_checker" :sl-residual
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
   "record_post_revision_review_acceptance" :daemon
   "record_implementation_order" :daemon
   "register_story_artifact" :daemon
   "register_story_packet" :daemon
   "attach_story_artifact" :daemon
   "declare_merge_blocker" :daemon
   "merge_ready" :daemon
   "accept_merge" :daemon
   "check_merge_readiness" :daemon
   "record_assignment_result" :daemon
   "claim_handoff" :daemon
   "process_handoff" :daemon
   "finish_held_handoff" :daemon
   "finish_in_process_handoff" :daemon
   "clear_stale_lock" :daemon
   "park_merge_blocked" :daemon
   "hold_merge_blocked" :daemon})

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
