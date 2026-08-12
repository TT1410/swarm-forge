;; Full Gherkin acceptance suite — canonical `bb acceptance` entrypoint (human output).
;; Not a gherkin-mutator worker — use `bb acceptance-worker` for --runner-worker.
;;
;; Six-pack / APS project components (implementer owns wiring when features exist):
;;   acceptance/generator.clj   — JSON IR → thin generated entrypoints
;;   acceptance/runtime.clj     — scenario expansion + step dispatch
;;   acceptance/steps/*.clj     — project step handlers (regex captures by default)
;;   acceptance/runner.clj      — thin shell: suite vs --worker
;;   acceptance/generated/      — generated tests (separate from unit tests)
;;
;; Normal run: features → gherkin-parser → generator → runtime + steps.
;; When features/ exists without a runner, exit 2 (blocker).

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
        (println "Implement APS six-pack components: generator, runtime, step handlers, thin runner.")
        (println "Scaffold: swarmforge/templates/acceptance/ and bb/tasks/acceptance*.clj")
        (println "Suite: bb acceptance | Mutator worker: bb acceptance-worker")
        (println "See constitution Acceptance Pipeline and github.com/unclebob/Acceptance-Pipeline-Specification.")
        (println "Features found:")
        (doseq [f features] (println " " f)))
      (System/exit 2))

    :else
    (do
      (println "ACCEPTANCE: loading acceptance/runner.clj for" (count features) "feature(s)")
      (load-file "acceptance/runner.clj"))))
