(ns swarmforge.test-runner
  (:require [clojure.test :as test]
            [swarmforge.handoff-test]
            [swarmforge.script-test]))

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
                     (test-vars 'swarmforge.script-test
                                (fn [v] (not (:simulation (meta v))))))))

(defn run-simulation! []
  (run-vars! "simulation"
             (test-vars 'swarmforge.script-test
                        (fn [v] (:simulation (meta v))))))
