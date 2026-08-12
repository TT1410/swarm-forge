;; APS gherkin-mutator --runner-worker: NDJSON jobs on stdin, NDJSON results on stdout.
;; Do NOT use bare `bb acceptance` as --runner-worker (that runs the human suite).
;;
;; Usage:
;;   gherkin-mutator --feature features/….feature \
;;     --runner-worker "bb acceptance-worker"
;;
(require '[babashka.fs :as fs])

(when-not (fs/exists? "acceptance/runner.clj")
  (binding [*out* *err*]
    (println "ACCEPTANCE_WORKER_BLOCKER: acceptance/runner.clj missing"))
  (System/exit 2))

(binding [*command-line-args* ["--worker"]]
  (load-file "acceptance/runner.clj"))
