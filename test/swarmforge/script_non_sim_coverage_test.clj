(ns swarmforge.script-non-sim-coverage-test
  (:require [clojure.test :as test :refer [deftest]]
            [swarmforge.artifact-workflow-test]
            [swarmforge.assign-merge-test]
            [swarmforge.crap-coverage-test]
            [swarmforge.handoff-lib-test]
            [swarmforge.launcher-test]
            [swarmforge.recover-test]
            [swarmforge.redo-next-test]
            [swarmforge.open-issues-test]
            [swarmforge.role-contract-test]
            [swarmforge.spawn-test]
            [swarmforge.squad-next-test]
            [swarmforge.squadd-test]
            [swarmforge.squadd-web-test]
            [swarmforge.tool-test]
            [swarmforge.window-cleanup-test]))

(def script-test-namespaces
  '[swarmforge.artifact-workflow-test
    swarmforge.assign-merge-test
    swarmforge.crap-coverage-test
    swarmforge.handoff-lib-test
    swarmforge.launcher-test
    swarmforge.recover-test
    swarmforge.redo-next-test
    swarmforge.open-issues-test
    swarmforge.role-contract-test
    swarmforge.spawn-test
    swarmforge.squad-next-test
    swarmforge.squadd-test
    swarmforge.squadd-web-test
    swarmforge.tool-test
    swarmforge.window-cleanup-test])

(defn non-simulation-vars []
  (->> script-test-namespaces
       (mapcat (comp vals ns-publics))
       (filter (fn [v] (-> v meta :test)))
       (remove (fn [v] (:simulation (meta v))))
       vec))

(deftest run-script-non-simulation-tests
  (doseq [v (non-simulation-vars)]
    (test/test-var v)))
