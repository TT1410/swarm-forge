(ns swarmforge.test-runner
  (:require [clojure.test :as test]
            [swarmforge.artifact-workflow-test]
            [swarmforge.assign-merge-test]
            [swarmforge.bugs-remaining-test]
            [swarmforge.dashboard-request-test]
            [swarmforge.durable-blocker-test]
            [swarmforge.crap-coverage-test]
            [swarmforge.handoff-test]
            [swarmforge.handoff-lib-test]
            [swarmforge.residual-priority-test]
            [swarmforge.code-review-and-stamps-test]
            [swarmforge.architecture-and-done-test]
            [swarmforge.dashboard-labels-test]
            [swarmforge.dashboard-polish-test]
            [swarmforge.launcher-test]
            [swarmforge.recover-test]
            [swarmforge.redo-next-test]
            [swarmforge.redo-ui-test]
            [swarmforge.open-issues-test]
            [swarmforge.role-contract-test]
            [swarmforge.simulator-test]
            [swarmforge.spawn-test]
            [swarmforge.squad-actions-test]
            [swarmforge.squad-next-test]
            [swarmforge.squadd-test]
            [swarmforge.squadd-web-test]
            [swarmforge.system-analyst-test]
            [swarmforge.tool-test]
            [swarmforge.window-cleanup-test]))

(def script-test-namespaces
  '[swarmforge.artifact-workflow-test
    swarmforge.assign-merge-test
    swarmforge.bugs-remaining-test
    swarmforge.dashboard-request-test
    swarmforge.durable-blocker-test
    swarmforge.handoff-lib-test
    swarmforge.residual-priority-test
    swarmforge.code-review-and-stamps-test
    swarmforge.architecture-and-done-test
    swarmforge.dashboard-labels-test
    swarmforge.dashboard-polish-test
    swarmforge.launcher-test
    swarmforge.recover-test
    swarmforge.redo-next-test
    swarmforge.redo-ui-test
    swarmforge.open-issues-test
    swarmforge.role-contract-test
    swarmforge.spawn-test
    swarmforge.squad-actions-test
    swarmforge.squad-next-test
    swarmforge.squadd-test
    swarmforge.squadd-web-test
    swarmforge.system-analyst-test
    swarmforge.tool-test
    swarmforge.window-cleanup-test])

(def simulation-test-namespaces
  '[swarmforge.simulator-test])

(defn test-vars [ns-sym pred]
  (->> (ns-publics ns-sym)
       vals
       (filter (fn [v] (-> v meta :test)))
       (filter pred)))

(defn run-vars! [label vars]
  (let [vars (vec vars)
        counters (ref {:test 0 :pass 0 :fail 0 :error 0})]
    (binding [test/*report-counters* counters]
      (doseq [v vars]
        (test/test-var v)))
    (let [{:keys [fail error]} (deref counters)]
      (println)
      (println "Ran" (count vars) label "tests.")
      (System/exit (+ fail error)))))

(defn run-non-simulation! []
  (run-vars! "non-simulation"
             (concat (test-vars 'swarmforge.handoff-test
                                (fn [v] (not (:simulation (meta v)))))
                     (test-vars 'swarmforge.crap-coverage-test
                                (fn [v] (not (:simulation (meta v)))))
                     (mapcat #(test-vars %
                                          (fn [v] (not (:simulation (meta v)))))
                             script-test-namespaces))))

(defn run-simulation! []
  (run-vars! "simulation"
             (mapcat #(test-vars %
                                  (fn [v] (:simulation (meta v))))
                     simulation-test-namespaces)))
