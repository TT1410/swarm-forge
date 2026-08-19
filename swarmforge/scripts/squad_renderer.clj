(ns squad-renderer
  "Present control-plane plan views. Pure printing — no state mutation."
  (:require [squad-actions :as actions]
            [squad-control-plane :as plane]
            [clojure.string :as str]))

(defn print-policy-summary!
  "Optional diagnostics: residual ranking and ready bands."
  []
  (println "POLICY_RESIDUAL_ORDER:" (str/join " " (map name plane/residual-class-order)))
  (println "POLICY_READY_PRIORITY:"
           (str/join " "
                     (map (fn [[k v]] (str (name k) "=" v))
                          (plane/ready-priority-bands-ordered)))))

(defn print-authority!
  "Print AUTHORITY for a typed action."
  [candidate]
  (let [typed (actions/ensure-typed candidate)]
    (println "AUTHORITY:" (:authority typed))))
