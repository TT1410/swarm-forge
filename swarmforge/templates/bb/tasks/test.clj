;; Example task body — keep logic out of root bb.edn to reduce merge conflicts.
(require '[clojure.test :as test])

;; Replace with the product test namespace(s).
(let [result (test/run-all-tests #"^.*-test$")]
  (when (pos? (+ (:fail result 0) (:error result 0)))
    (System/exit 1)))
