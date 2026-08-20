(ns squad-control-plane
  "Control-plane policy for SwarmForge.

  Single residual-class ranking and ready-action priority bands.
  Authority allow-lists: which actor may execute which ops.
  Planner selects residual class + ready actions; executor filters by
        authority; renderer is pure presentation of a plan view.

  squad_next remains the state scanner and CLI entry; this ns owns policy."
  (:require [squad-actions :as actions]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Residual class ranking (top-level next-action selection)
;;; Lower index = higher priority. First match wins.
;;; ---------------------------------------------------------------------------

(def residual-class-order
  "Ordered residual classes. Index is the rank (0 = highest priority).
  Dashboard-request outranks pending-spawn so product intake is not starved."
  [:finish-in-process
   :process-handoff
   :stale-lock
   :dashboard-request
   :pending-spawn
   :retire
   :recover
   :durable-blocker
   :ready-action
   :pending-approval
   :wait])

(defn residual-class-rank
  "Numeric rank for sorting/tests. Unknown classes sort last."
  [class]
  (let [idx (.indexOf residual-class-order (keyword class))]
    (if (neg? idx) 999 idx)))

(defn residual-class-before?
  "True when class-a outranks class-b (lower rank number wins)."
  [class-a class-b]
  (< (residual-class-rank class-a) (residual-class-rank class-b)))

;;; ---------------------------------------------------------------------------
;;; Ready-action priority bands (within :ready-action class)
;;; Lower number = higher priority (matches existing sort-by :priority).
;;; ---------------------------------------------------------------------------

(def ready-priority
  "Named priority bands for pipeline candidates. Values match historical
  squad_next numbers so behavior stays stable while policy becomes central."
  {:existing-spawn 10
   :theme-module-map 18
   :theme-approval 20
   :bookkeeping-review 24
   :bookkeeping-register 25
   :bookkeeping-order 26
   :incomplete-checker 27
   :architecture-gate-approval 28
   :user-approval 30
   :spawn-worker 60})

(defn ready-priority-of
  "Look up a named band. Unknown keys default to spawn-worker (60)."
  [band]
  (get ready-priority (keyword band) (:spawn-worker ready-priority)))

(defn ready-priority-bands-ordered
  "Bands sorted highest-priority-first (lowest number first)."
  []
  (sort-by (comp ready-priority-of first) ready-priority))

;;; ---------------------------------------------------------------------------
;;; Authority allow-lists
;;; ---------------------------------------------------------------------------

(def daemon-ops
  "Ops the daemon/mechanical pass may execute."
  #{:retire_agent
    :request_spawn
    :create_assignment
    :create_approval_request
    :record_auto_approval
    :record_merged_result
    :record_merged_batch_result
    :complete_batch
    :record_batch_membership
    :record_review_result
    :attach_story_artifact
    :record_frame_sha
    :start_snapshot_item
    :declare_merge_blocker
    :claim_handoff
    :process_handoff
    :finish_held_handoff
    :finish_in_process_handoff
    :clear_stale_lock
    :record_assignment_result})

(def sl-residual-ops
  "Ops Squad Leader residual may surface as COMMAND (judgment / product / recovery).
  SL merges the handed SHA (six-pack target)."
  #{:wait
    :accept_merge
    :wait_for_spawn
    :request_user_approval
    :answer_dashboard_request
    :handle_durable_blocker
    :recover_agent
    :repair_dead_agent
    :create_assignment
    :create_approval_request
    :record_auto_approval
    :retire_agent
    :request_spawn
    :clear_stale_lock
    :process_handoff
    :finish_in_process_handoff})

(def troubleshooter-ops
  "Troubleshooter may perform operator state surgery including dead-agent repair."
  (into sl-residual-ops
        #{:repair_dead_agent}))

(def operator-ops
  "Operator-facing approval UI actions (approve/reject via dashboard API)."
  #{:request_user_approval})

(def authority-allow-lists
  {:daemon daemon-ops
   :sl-residual sl-residual-ops
   :troubleshooter troubleshooter-ops
   :operator operator-ops})

(defn allowed-ops
  "Set of keyword ops allowed for authority (or empty)."
  [authority]
  (get authority-allow-lists (keyword authority) #{}))

(defn op-allowed?
  "True when authority may execute op."
  [authority op]
  (contains? (allowed-ops authority) (keyword (actions/normalize-op op))))

(defn assert-op-allowed!
  "Throw if authority may not run op (executor guard)."
  [authority op]
  (when-not (op-allowed? authority op)
    (throw (ex-info (str "Authority " authority " may not execute op " op)
                    {:authority authority :op op}))))

(defn filter-allowed
  "Keep only candidates allowed for authority."
  [authority candidates]
  (filterv (fn [c]
             (op-allowed? authority (actions/op-of c)))
           candidates))

;;; ---------------------------------------------------------------------------
;;; Plan view (planner output shape)
;;; ---------------------------------------------------------------------------

(defn plan-view
  "Structured plan for renderer/executor.

  :residual-class — selected top-level class (keyword)
  :ready-actions  — sorted pipeline candidates
  :concurrent     — capacity-scheduled concurrent actions
  :primary        — first ready action when residual-class is :ready-action
  :context-keys   — diagnostic keys present on ctx (optional)"
  [{:keys [residual-class ready-actions concurrent-actions primary] :as plan}]
  {:residual-class residual-class
   :ready-actions (vec (or ready-actions []))
   :concurrent-actions (vec (or concurrent-actions []))
   :primary primary
   :policy {:residual-class-order residual-class-order
            :ready-priority ready-priority}})

(defn select-residual-class
  "Pure selection of residual class from a context map of presence flags.
  ctx keys match residual-class-order (except :wait default)."
  [ctx]
  (or (some (fn [class]
              (when (case class
                      :finish-in-process (boolean (:in-process-needs-action? ctx))
                      :process-handoff (boolean (:new-handoff ctx))
                      :stale-lock (boolean (:stale-lock-info ctx))
                      :pending-spawn (boolean (:pending-spawn-file ctx))
                      :dashboard-request (boolean (:pending-dashboard-request ctx))
                      :retire (boolean (:retire-candidate ctx))
                      :recover (boolean (:recover-candidate ctx))
                      :durable-blocker (boolean (:durable-blocker ctx))
                      :ready-action (seq (:ready-actions ctx))
                      :pending-approval (boolean (:pending-approval-file ctx))
                      :wait false
                      false)
                class))
            residual-class-order)
      :wait))

(defn describe-policy
  "Human-readable policy dump for tests and diagnostics."
  []
  {:residual-class-order residual-class-order
   :ready-priority ready-priority
   :authorities (into {} (map (fn [[k v]] [k (sort (map name v))]) authority-allow-lists))})
