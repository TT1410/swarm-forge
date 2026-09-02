(ns swarmforge.handoff-test
  (:require [clojure.test :refer [run-tests]]
            swarmforge.swarm-handoff-test
            swarmforge.ready-handoff-test))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'swarmforge.swarm-handoff-test
                                        'swarmforge.ready-handoff-test)]
    (System/exit (+ fail error))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
