;; Generate target/coverage/lcov.info for crap4clj and clj-mutate.
;; Requires deps.edn :cov alias (Cloverage) from the product template.
;; Agents that run CRAP or mutation must succeed here first (or hand back a blocker).

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

(def lcov-path "target/coverage/lcov.info")

(defn coverage-ok? []
  (and (fs/exists? lcov-path)
       (pos? (fs/size lcov-path))))

(when-not (fs/exists? "deps.edn")
  (binding [*out* *err*]
    (println "COVERAGE_BLOCKER: deps.edn missing; cannot run Cloverage :cov alias."))
  (System/exit 2))

(println "COVERAGE: running clj -M:cov …")
(let [result (p/shell {:continue true :out :string :err :string}
                      "clj" "-M:cov")]
  (when-not (str/blank? (:out result))
    (print (:out result)))
  (when-not (str/blank? (:err result))
    (binding [*out* *err*]
      (print (:err result))))
  (cond
    (and (zero? (:exit result)) (coverage-ok?))
    (do
      (println "COVERAGE_OK:" lcov-path)
      (System/exit 0))

    (coverage-ok?)
    (do
      (println "COVERAGE_OK:" lcov-path "(existing LCOV kept; clj exit" (:exit result) ")")
      (System/exit 0))

    :else
    (do
      (binding [*out* *err*]
        (println "COVERAGE_BLOCKER: failed to produce" lcov-path)
        (println "Configure :cov in deps.edn (see swarmforge/templates/product-deps.edn) and ensure tests run under Cloverage.")
        (println "Do not run crap4clj or clj-mutate until coverage succeeds.")
        (println "clj exit:" (:exit result)))
      (System/exit (if (pos? (:exit result)) (:exit result) 2)))))
