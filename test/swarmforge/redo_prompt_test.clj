(ns swarmforge.redo-prompt-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [swarmforge.test-support :refer :all]))

(deftest sl-merges-and-does-not-run-theme-ceremony
  ;; Given the squad-leader prompt
  ;; Then SL merges worker SHAs and does not run theme or merger ceremony
  (let [p (slurp (str (fs/path repo-root "swarmforge/roles/squad-leader.prompt")))]
    (is (re-find #"(?i)merge" p))
    (is (not (str/includes? p "merger")))
    (is (not (str/includes? p "dry-run")))
    (is (not (str/includes? p "module map")))))

(deftest analyst-writes-a-plan-not-story-cuts
  ;; Given the analyst prompt
  ;; Then it writes one implementation plan and does not cut stories or an order file
  (let [p (slurp (str (fs/path repo-root "swarmforge/role-templates/analyst.prompt")))]
    (is (str/includes? p "implementation plan"))
    (is (not (str/includes? p "implementation-order.md")))
    (is (not (str/includes? p "at most two")))))

(deftest local-workflow-is-the-redo-pipeline
  ;; Given the local-workflow constitution
  ;; Then it describes backlog → plan → cleaner → CR → hardener, with no reviewers or dry-run
  (let [p (slurp (str (fs/path repo-root "swarmforge/constitution/articles/local-workflow.prompt")))]
    (is (str/includes? p "backlog"))
    (is (str/includes? p "implementation plan"))
    (is (str/includes? p "cleaner"))
    (is (str/includes? p "code-reviewer"))
    (is (str/includes? p "hardener"))
    (is (not (str/includes? p "gherkin-reviewer")))
    (is (not (str/includes? p "dry-run")))))

(deftest troubleshooter-may-add-backlog-stories
  ;; Given the troubleshooter prompt
  ;; Then TS may add a backlog story and does not classify theme vs story
  (let [p (slurp (str (fs/path repo-root "swarmforge/roles/troubleshooter.prompt")))]
    (is (re-find #"(?i)backlog|add (a )?story" p))
    (is (not (str/includes? p "classify")))))

(deftest cleaner-owns-property-tests-not-cr-recs
  (let [p (slurp (str (fs/path repo-root "swarmforge/role-templates/cleaner.prompt")))]
    (is (re-find #"(?i)property" p))
    (is (not (re-find #"(?i)code.review rec" p)))))

(deftest code-reviewer-recs-go-to-hardener
  (let [p (slurp (str (fs/path repo-root "swarmforge/role-templates/code-reviewer.prompt")))]
    (is (re-find #"(?i)recommend" p))
    (is (re-find #"(?i)hardener" p))))

(deftest hardener-applies-recs-then-hardens
  (let [p (slurp (str (fs/path repo-root "swarmforge/role-templates/hardener.prompt")))]
    (is (re-find #"(?i)apply" p))
    (is (re-find #"(?i)recommend" p))
    (is (re-find #"(?i)harden" p))))
