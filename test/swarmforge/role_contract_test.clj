(ns swarmforge.role-contract-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [squad-tool-table :as tools]
            [clojure.test :refer [deftest is testing]]
            [swarmforge.test-support :refer :all]))

(def current-squad-templates
  ["analyst"
   "gherkin-writer"
   "qa-procedure-writer"
   "gherkin-reviewer"
   "qa-procedure-reviewer"
   "implementer"
   "cleaner"
   "code-reviewer"
   "hardener"
   "qa"
   "architect"
   "senior-implementor"
   "merger"])

(defn contract-path [template]
  (fs/path repo-root "swarmforge" "role-templates" (str template ".contract.edn")))

(defn contract [template]
  (edn/read-string (slurp (str (contract-path template)))))

(defn contracts []
  (map contract current-squad-templates))

(defn required-tool-names [role]
  (set (map :name (tools/required-tools repo-root role))))

(deftest squad-role-templates-exist
  (doseq [template current-squad-templates]
    (is (fs/exists? (fs/path repo-root "swarmforge/role-templates" (str template ".prompt"))))
    (is (fs/exists? (contract-path template)))))

(deftest squad-role-prompts-reference-contracts
  (doseq [template current-squad-templates]
    (let [prompt (slurp (str (fs/path repo-root "swarmforge/role-templates" (str template ".prompt"))))]
      (is (str/includes? prompt (str template ".contract.edn")) template))))

(deftest squad-role-prompts-confine-artifacts-to-worktrees
  (doseq [template current-squad-templates]
    (let [prompt (slurp (str (fs/path repo-root "swarmforge/role-templates" (str template ".prompt"))))]
      (is (str/includes? prompt "Create, edit, stage, commit, and inspect assigned artifacts only inside the assigned worktree.")
          template))))

(deftest squad-role-contracts-encode-worker-boundaries
  (doseq [c (contracts)]
    (is (= ["squad-leader"] (:handoff-targets c)) (:role c))
    (is (false? (:may-spawn c)) (:role c))
    (is (false? (:may-talk-to-user c)) (:role c))
    (is (false? (:may-fetch-tools c)) (:role c)))
  (doseq [c (contracts)]
    (if (= "analyst" (:role c))
      (do
        (is (true? (:may-web-search c)))
        (is (true? (:self-contained-output c))))
      (is (false? (:may-web-search c)) (:role c)))))

(deftest squad-role-contracts-separate-artifact-ownership
  (let [by-role (into {} (map (juxt :role identity) (contracts)))]
    (is (= ["stories/"] (:artifact-roots (by-role "analyst"))))
    (is (= ["features/"] (:artifact-roots (by-role "gherkin-writer"))))
    (is (= ["qa/"] (:artifact-roots (by-role "qa-procedure-writer"))))
    (doseq [artifact-role ["gherkin-writer" "qa-procedure-writer" "gherkin-reviewer" "qa-procedure-reviewer"]]
      (is (false? (:may-run-broad-tests (by-role artifact-role))) artifact-role))
    (doseq [review-role ["gherkin-reviewer" "qa-procedure-reviewer" "code-reviewer" "architect"]]
      (is (= [".squad/reviews/"] (:artifact-roots (by-role review-role))) review-role))
    (is (= ["src/" "test/" "features/" "qa/"] (:artifact-roots (by-role "implementer"))))
    (is (= ["src/" "test/" "features/" "qa/" ".squad/"] (:artifact-roots (by-role "merger"))))
    (is (= "squad_next.sh" (:workflow-readiness-source (by-role "implementer"))))
    (doseq [singleton-role ["hardener" "qa" "architect"]]
      (is (true? (:singleton (by-role singleton-role))) singleton-role))
    (is (= "hardener" (:batch-kind (by-role "hardener"))))
    (is (= "qa" (:batch-kind (by-role "qa"))))
    (is (= "architecture" (:batch-kind (by-role "architect"))))))

(deftest squad-role-tooling-contracts-include-required-tools
  (let [by-role (into {} (map (juxt :role identity) (contracts)))]
    (is (= ["crap4clj" "dry4clj"] (:required-tool-ids (by-role "cleaner"))))
    (is (= ["clj-mutate" "crap4clj" "dry4clj" "gherkin-parser" "gherkin-mutator"]
           (:required-tool-ids (by-role "hardener"))))
    (is (= ["crap4clj" "dry4clj"] (:required-tool-ids (by-role "qa"))))
    (is (= ["gherkin-parser" "ir-dry-checker"] (:required-tool-ids (by-role "gherkin-writer"))))
    (is (= #{"crap4clj" "dry4clj"} (required-tool-names "cleaner")))
    (is (= #{"clj-mutate" "crap4clj" "dry4clj" "gherkin-parser" "gherkin-mutator"}
           (required-tool-names "hardener")))
    (is (= #{"crap4clj" "dry4clj"} (required-tool-names "qa")))
    (is (= #{"gherkin-parser" "ir-dry-checker"} (required-tool-names "gherkin-writer")))))

(deftest squad-role-prompts-include-valid-helper-examples
  (doseq [template ["cleaner" "hardener" "gherkin-writer" "qa"]]
    (let [prompt (slurp (str (fs/path repo-root "swarmforge/role-templates" (str template ".prompt"))))]
      (is (str/includes? prompt "squad_event.sh running") template)
      (is (str/includes? prompt "squad_event.sh blocked") template)
      (is (not (re-find #"squad_event\.sh\s+[a-z][a-z0-9-]*-\d{3}\s+" prompt)) template)
      (is (not (re-find #"squad_tool\.sh require [A-Za-z0-9._-]+(?:`|\n)" prompt)) template)))
  (let [cleaner (slurp (str (fs/path repo-root "swarmforge/role-templates/cleaner.prompt")))
        hardener (slurp (str (fs/path repo-root "swarmforge/role-templates/hardener.prompt")))
        gherkin (slurp (str (fs/path repo-root "swarmforge/role-templates/gherkin-writer.prompt")))
        qa (slurp (str (fs/path repo-root "swarmforge/role-templates/qa.prompt")))]
    (doseq [prompt [cleaner qa]]
      (is (str/includes? prompt "generated assignment `Tool Startup` section"))
      (is (str/includes? prompt "swarmforge/tool-table.edn"))
      (is (str/includes? prompt "record `blocked`")))
    (is (str/includes? hardener "generated assignment `Tool Startup` section"))
    (is (str/includes? hardener "swarmforge/tool-table.edn"))
    (is (str/includes? gherkin "generated assignment `Tool Startup` section"))
    (is (str/includes? gherkin "do not block handoff on special evidence header names"))))

(deftest squad-analyst-prompt-includes-invest-story-guidance
  (let [prompt (slurp (str (fs/path repo-root "swarmforge/role-templates/analyst.prompt")))]
    (is (str/includes? prompt "I.N.V.E.S.T."))
    (doseq [word ["independent" "negotiable" "valuable" "estimable" "small" "testable"]]
      (is (str/includes? prompt word)))))

(deftest squad-architect-prompt-frames-principles-as-review-advice
  (let [prompt (slurp (str (fs/path repo-root "swarmforge/role-templates/architect.prompt")))]
    (is (str/includes? prompt "Produce architectural critique only"))
    (is (str/includes? prompt "The Dependency Rule"))
    (is (str/includes? prompt "Low level is close to IO"))
    (is (str/includes? prompt "high level is far from IO"))
    (is (str/includes? prompt "large modules with many responsibilities"))
    (is (str/includes? prompt "individual well-named modules with single responsibilities"))))

(deftest required-tool-startup-instructions-come-from-tool-table
  (let [helper (str (fs/path repo-root "swarmforge/scripts/install_bb_tool.sh"))
        startup (tools/startup-instructions (tools/required-tools repo-root "gherkin-writer"))]
    (is (str/includes? startup "## Tool Startup"))
    (is (str/includes? startup "squad_tool.sh require gherkin-parser github.com/unclebob/Acceptance-Pipeline-Specification latest"))
    (is (str/includes? startup (str "squad_tool.sh ensure gherkin-parser github.com/unclebob/Acceptance-Pipeline-Specification latest -- 'bash' '" helper "' '/Users/unclebob/projects/Acceptance-Pipeline-Specification' 'gherkin-parser'")))
    (is (str/includes? startup "squad_tool.sh require ir-dry-checker github.com/unclebob/Acceptance-Pipeline-Specification latest"))
    (is (str/includes? startup "record `blocked`"))))

(deftest squad-leader-contract-encodes-orchestration-boundary
  (let [contract-file (fs/path repo-root "swarmforge/roles/squad-leader.contract.edn")
        prompt (slurp (str (fs/path repo-root "swarmforge/roles/squad-leader.prompt")))
        c (edn/read-string (slurp (str contract-file)))]
    (is (fs/exists? contract-file))
    (is (str/includes? prompt "squad-leader.contract.edn"))
    (is (true? (:persistent c)))
    (is (true? (:may-talk-to-user c)))
    (is (true? (:may-spawn c)))
    (is (true? (:requires-theme-negotiation-before-analyst c)))
    (is (true? (:theme-approval-before-analyst c)))
    (is (true? (:story-packet-source-of-truth c)))
    (is (= "squad_next.sh" (:implementation-readiness-source c)))
    (is (= ["hardener" "qa" "architect"] (:singleton-roles c)))
    (is (some #{"stories"} (:forbidden-writes c)))
    (is (some #{"production-code"} (:forbidden-writes c)))))

(deftest runtime-constitution-does-not-require-development-design-doc
  (let [project-article (slurp (str (fs/path repo-root "swarmforge/constitution/articles/project.prompt")))]
    (is (not (str/includes? project-article "squad-design.md")))))
