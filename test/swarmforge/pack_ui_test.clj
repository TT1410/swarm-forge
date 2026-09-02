(ns swarmforge.pack-ui-test
  (:require [clojure.test :refer [run-tests]]
            swarmforge.pack-board-test
            swarmforge.pack-web-status-test
            swarmforge.pack-web-ui-test
            swarmforge.pack-pipeline-test
            swarmforge.pack-forge-test))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'swarmforge.pack-board-test
                                        'swarmforge.pack-web-status-test
                                        'swarmforge.pack-web-ui-test
                                        'swarmforge.pack-pipeline-test
                                        'swarmforge.pack-forge-test)]
    (System/exit (+ fail error))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
