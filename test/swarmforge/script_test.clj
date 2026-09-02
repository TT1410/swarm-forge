(ns swarmforge.script-test
  (:require [clojure.test :refer [run-tests]]
            swarmforge.script-misc-test
            swarmforge.swarmforge-script-test
            swarmforge.installer-test))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'swarmforge.script-misc-test
                                        'swarmforge.swarmforge-script-test
                                        'swarmforge.installer-test)]
    (System/exit (+ fail error))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
