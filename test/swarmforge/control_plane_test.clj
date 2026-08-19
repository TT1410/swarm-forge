(ns swarmforge.control-plane-test
  (:require [clojure.test :refer [deftest is testing]]
            [squad-actions :as actions]
            [squad-control-plane :as plane]
            [squad-executor :as executor]
            [babashka.fs :as fs]
            [swarmforge.test-support :refer :all]))

(deftest residual-class-order-is-single-policy
  ;; Given the control-plane residual ranking
  ;; When comparing classes
  ;; Then handoffs outrank dashboard, dashboard outranks ready-actions, etc.
  (is (plane/residual-class-before? :finish-in-process :process-handoff))
  (is (plane/residual-class-before? :process-handoff :dashboard-request))
  (is (plane/residual-class-before? :dashboard-request :retire))
  (is (plane/residual-class-before? :retire :recover))
  (is (plane/residual-class-before? :recover :durable-blocker))
  (is (plane/residual-class-before? :durable-blocker :ready-action))
  (is (plane/residual-class-before? :ready-action :pending-approval))
  (is (plane/residual-class-before? :pending-approval :wait))
  (is (= 0 (plane/residual-class-rank :finish-in-process)))
  (is (= (dec (count plane/residual-class-order))
         (plane/residual-class-rank :wait))))

(deftest select-residual-class-uses-policy-order
  (is (= :dashboard-request
         (plane/select-residual-class
          {:pending-dashboard-request {:id "1"}
           :ready-actions [{:next-action "create_assignment"}]
           :pending-approval-file "/x"})))
  (is (= :ready-action
         (plane/select-residual-class
          {:ready-actions [{:next-action "create_assignment"}]
           :pending-approval-file "/x"})))
  (is (= :wait (plane/select-residual-class {}))))

(deftest ready-priority-bands-are-ordered
  (is (< (plane/ready-priority-of :existing-spawn)
         (plane/ready-priority-of :theme-module-map)))
  (is (< (plane/ready-priority-of :bookkeeping-register)
         (plane/ready-priority-of :user-approval)))
  (is (< (plane/ready-priority-of :user-approval)
         (plane/ready-priority-of :spawn-worker)))
  (is (= 60 (plane/ready-priority-of :spawn-worker)))
  (is (= 30 (plane/ready-priority-of :user-approval))))

(deftest sl-owns-accept-merge
  (is (plane/op-allowed? :sl-residual :accept_merge))
  (is (not (plane/op-allowed? :daemon :accept_merge)))
  (is (not (plane/op-allowed? :sl-residual :check_merge_readiness)))
  (is (not (plane/op-allowed? :daemon :merge_ready))))

(deftest sl-residual-may-repair-and-approve
  (is (plane/op-allowed? :sl-residual :repair_dead_agent))
  (is (plane/op-allowed? :sl-residual :recover_agent))
  (is (plane/op-allowed? :sl-residual :request_user_approval))
  (is (plane/op-allowed? :sl-residual :answer_dashboard_request))
  (is (plane/op-allowed? :troubleshooter :repair_dead_agent)))

(deftest filter-allowed-strips-daemon-only-for-sl
  (let [cands [(actions/action :accept_merge :command "x")
               (actions/action :repair_dead_agent :command "y")
               (actions/action :wait :command "z")]
        filtered (plane/filter-allowed :sl-residual cands)]
    (is (= ["accept_merge" "repair_dead_agent" "wait"] (mapv actions/op-of filtered)))))

(deftest executor-refuses-disallowed-op
  (let [root (tmp-dir)]
    (try
      (is (thrown-with-msg? Exception #"may not execute"
                            (executor/apply-candidate!
                             root
                             {:next-action "merge_ready"
                              :command "echo should-not-run"}
                             :sl-residual)))
      (finally
        (fs/delete-tree root)))))

(deftest plan-view-shape
  (let [plan (plane/plan-view {:residual-class :ready-action
                               :ready-actions [{:next-action "create_assignment"}]
                               :concurrent-actions []
                               :primary {:next-action "create_assignment"}})]
    (is (= :ready-action (:residual-class plan)))
    (is (map? (:policy plan)))
    (is (= plane/residual-class-order (get-in plan [:policy :residual-class-order])))))
