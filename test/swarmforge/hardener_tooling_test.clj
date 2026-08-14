(ns swarmforge.hardener-tooling-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [swarm-handoff :as handoff]))

(deftest hardener-handoff-rejects-root-bb-edn-in-commit
  ;; B12: mechanical gate when hardener commit touches denylisted root tooling
  (with-redefs [handoff/commit-changed-paths
                (fn [_] ["src/core.clj" "bb.edn" "test/core_test.clj"])]
    (let [errs (handoff/hardener-root-tooling-errors
                {"type" "git_handoff" "template" "hardener"}
                "abc123def0")]
      (is (seq errs))
      (is (str/includes? (first errs) "bb.edn"))
      (is (str/includes? (first errs) "B12"))))
  (with-redefs [handoff/commit-changed-paths
                (fn [_] ["src/core.clj" "test/core_test.clj"])]
    (is (nil? (handoff/hardener-root-tooling-errors
               {"type" "git_handoff" "template" "hardener"}
               "abc123def0"))))
  (with-redefs [handoff/commit-changed-paths
                (fn [_] ["bb.edn"])]
    (is (nil? (handoff/hardener-root-tooling-errors
               {"type" "git_handoff" "template" "implementer"}
               "abc123def0"))
        "non-hardener templates are not blocked by this gate")))
