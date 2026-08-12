;; Full Gherkin acceptance suite — canonical `bb acceptance` entrypoint.
;; Used as gherkin-mutator --runner-worker and as late-role handoff verification.
;;
;; Project-specific APS components (see constitution Acceptance Pipeline):
;;   acceptance/runner.clj          — runner adapter (loads IR, runs steps)
;;   acceptance/entrypoint_gen.clj  — optional: feature IR → executable entrypoints
;;   acceptance/runtime.clj         — optional: scenario expansion / step dispatch
;;   acceptance/steps.clj           — optional: project step handlers
;;
;; When features/ exists without a runner, exit 2 (blocker) so agents cannot
;; pretend the suite passed.

(require '[babashka.fs :as fs]
         '[clojure.string :as str])

(defn feature-files []
  (when (fs/directory? "features")
    (->> (fs/glob "features" "**/*.feature")
         (map str)
         sort
         vec)))

(let [features (feature-files)]
  (cond
    (empty? features)
    (do
      (println "ACCEPTANCE: no features/*.feature — suite empty (ok for pre-Gherkin scaffolds)")
      (System/exit 0))

    (not (fs/exists? "acceptance/runner.clj"))
    (do
      (binding [*out* *err*]
        (println "ACCEPTANCE_BLOCKER: features exist but acceptance/runner.clj is missing.")
        (println "Implement APS project components (entrypoint generator, runtime, step handlers, runner adapter).")
        (println "Canonical command after wiring: bb acceptance")
        (println "See constitution Acceptance Pipeline and github.com/unclebob/Acceptance-Pipeline-Specification.")
        (println "Features found:")
        (doseq [f features] (println " " f)))
      (System/exit 2))

    :else
    (do
      (println "ACCEPTANCE: loading acceptance/runner.clj for" (count features) "feature(s)")
      (load-file "acceptance/runner.clj"))))
