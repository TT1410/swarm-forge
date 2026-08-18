(ns swarmforge.sprint-prompt-test
  "Slice 3: prompts match the sprint form."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [swarmforge.test-support :refer [repo-root]]))

(defn- slurp-role [rel]
  (slurp (str (fs/path repo-root rel))))

(deftest sl-prompt-owns-sprint-form-not-backlog-classification
  (let [p (slurp-role "swarmforge/roles/squad-leader.prompt")]
    (is (str/includes? p "Sprint 0"))
    (is (str/includes? p "sprint spec"))
    (is (str/includes? p "plan approval"))
    (is (str/includes? p "squad_sprint.sh"))
    (is (not (str/includes? (str/lower-case p) "approve this backlog item for analysis")))
    (is (not (str/includes? p "theme-scoped or story-scoped as appropriate")))))

(deftest analyst-prompt-emits-module-tasks-and-interfaces
  (let [p (slurp-role "swarmforge/role-templates/analyst.prompt")]
    (is (str/includes? p "sprint spec"))
    (is (str/includes? p "task"))
    (is (str/includes? p "interface"))
    (is (not (str/includes? p "Implementer batches (B96)")))))

(deftest implementer-prompt-tdd-without-gherkin
  (let [p (slurp-role "swarmforge/role-templates/implementer.prompt")]
    (is (str/includes? p "TDD"))
    (is (str/includes? p "task"))
    (is (str/includes? p "not concerned with Gherkin"))
    (is (not (str/includes? p "accepted Gherkin and QA procedure")))))

(deftest hardener-prompt-gets-gherkin-passing-first
  (let [p (slurp-role "swarmforge/role-templates/hardener.prompt")]
    (is (str/includes? p "Gherkin passing"))
    (is (str/includes? p "github.com/unclebob/Acceptance-Pipeline-Specification"))
    (is (str/includes? p "parser-spec.md"))
    (is (str/includes? p "acceptance-generator.md"))))

(deftest gherkin-is-integration-testing
  (let [p (slurp-role "swarmforge/role-templates/gherkin-writer.prompt")]
    (is (str/includes? p "integration testing"))))

(deftest architect-and-si-bless-the-sprint
  (let [arch (slurp-role "swarmforge/role-templates/architect.prompt")
        si (slurp-role "swarmforge/role-templates/senior-implementer.prompt")]
    (is (str/includes? arch "sprint"))
    (is (str/includes? si "sprint"))))
