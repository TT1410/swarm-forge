(ns swarmforge.role-contract-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [squad-tool-table :as tools]
            [clojure.test :refer [deftest is]]
            [swarmforge.test-support :refer :all]))

(def current-squad-templates
  ["analyst"
   "gherkin-writer"
   "qa-procedure-writer"
   "implementer"
   "cleaner"
   "code-reviewer"
   "hardener"
   "qa"
   "architect"
   "senior-implementer"
   "system-analyst"])

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

(deftest squad-role-contracts-encode-worker-boundaries
  (doseq [c (contracts)]
    (is (= ["squad-leader"] (:handoff-targets c)) (:role c))
    (is (false? (:may-spawn c)) (:role c))
    (is (false? (:may-talk-to-user c)) (:role c))
    (is (false? (:may-fetch-tools c)) (:role c)))
  (doseq [c (contracts)]
    (cond
      (= "analyst" (:role c))
      (do
        (is (true? (:may-web-search c)))
        (is (true? (:self-contained-output c))))

      (= "system-analyst" (:role c))
      (do
        (is (false? (:may-web-search c)))
        (is (true? (:self-contained-output c))))

      (= "cleaner" (:role c))
      (do
        (is (true? (:may-web-search c)))
        (is (= "property-testing-framework-discovery" (:web-search-scope c))))

      :else
      (is (false? (:may-web-search c)) (:role c)))))

(deftest squad-role-contracts-separate-artifact-ownership
  (let [by-role (into {} (map (juxt :role identity) (contracts)))]
    (is (= [".squad/stories/" "stories/"] (:artifact-roots (by-role "analyst"))))
    (is (false? (:requires-dependency-checker (by-role "analyst"))))
    (is (some #{"plan"} (:writes (by-role "analyst"))))
    (is (not (some #{"dependency-checker"} (:writes (by-role "analyst")))))
    (is (not (some #{"dependency-checker.edn"} (:allowed-root-files (by-role "analyst")))))
    (is (= ["features/"] (:artifact-roots (by-role "gherkin-writer"))))
    (is (= ["qa/"] (:artifact-roots (by-role "qa-procedure-writer"))))
    (doseq [artifact-role ["gherkin-writer" "qa-procedure-writer"]]
      (is (false? (:may-run-broad-tests (by-role artifact-role))) artifact-role))
    (doseq [review-role ["code-reviewer" "architect"]]
      (is (= ["reviews/"] (:artifact-roots (by-role review-role))) review-role))
    (is (= ["src/" "test/" "features/" "qa/" "acceptance/" "bb/"] (:artifact-roots (by-role "implementer"))))
    (is (= "squad_next.sh" (:workflow-readiness-source (by-role "implementer"))))
    (doseq [singleton-role ["hardener" "qa" "architect"]]
      (is (true? (:singleton (by-role singleton-role))) singleton-role))
    (is (= "hardener" (:batch-kind (by-role "hardener"))))
    (is (= "qa" (:batch-kind (by-role "qa"))))
    (is (= "architecture" (:batch-kind (by-role "architect"))))))

(deftest squad-role-tooling-contracts-include-required-tools
  (let [by-role (into {} (map (juxt :role identity) (contracts)))]
    (is (= ["dependency-checker"] (:required-tool-ids (by-role "implementer"))))
    (is (= ["crap4clj" "dry4clj" "dependency-checker"] (:required-tool-ids (by-role "cleaner"))))
    (is (= ["clj-mutate" "crap4clj" "dry4clj" "gherkin-parser" "gherkin-mutator" "dependency-checker"]
           (:required-tool-ids (by-role "hardener"))))
    (is (= ["dependency-checker"] (:required-tool-ids (by-role "architect"))))
    (is (= ["dependency-checker"] (:required-tool-ids (by-role "code-reviewer"))))
    (is (= ["dependency-checker"] (:required-tool-ids (by-role "senior-implementer"))))
    (is (= ["crap4clj" "dry4clj"] (:required-tool-ids (by-role "qa"))))
    (is (= ["gherkin-parser" "ir-dry-checker"] (:required-tool-ids (by-role "gherkin-writer"))))
    (is (= #{"dependency-checker"} (required-tool-names "implementer")))
    (is (= #{"crap4clj" "dry4clj" "dependency-checker"} (required-tool-names "cleaner")))
    (is (= #{"clj-mutate" "crap4clj" "dry4clj" "gherkin-parser" "gherkin-mutator" "dependency-checker"}
           (required-tool-names "hardener")))
    (is (= #{"dependency-checker"} (required-tool-names "architect")))
    (is (= #{"dependency-checker"} (required-tool-names "code-reviewer")))
    (is (= #{"dependency-checker"} (required-tool-names "senior-implementer")))
    (is (= #{"crap4clj" "dry4clj"} (required-tool-names "qa")))
    (is (= #{"gherkin-parser" "ir-dry-checker"} (required-tool-names "gherkin-writer")))))

(deftest analyst-writes-a-per-story-implementation-plan
  ;; Given Start has already created the story
  ;; When the analyst role is specified
  ;; Then the contract is a plan for that story, not theme order/checker
  (let [c (contract "analyst")]
    (is (false? (:requires-dependency-checker c)))
    (is (false? (:requires-implementation-order c)))
    (is (not (some #{"implementation-order"} (:writes c))))))

(deftest squad-senior-implementer-runs-full-verification-before-handoff
  (let [c (contract "senior-implementer")]
    (is (true? (:may-run-broad-tests c)))))

(deftest required-tool-startup-instructions-come-from-tool-table
  (let [helper (str (fs/path repo-root "swarmforge/scripts/install_bb_tool.sh"))
        startup (tools/startup-instructions (tools/required-tools repo-root "gherkin-writer"))]
    (is (str/includes? startup "## Tool Startup"))
    (is (str/includes? startup "squad_tool.sh require gherkin-parser github.com/unclebob/Acceptance-Pipeline-Specification latest"))
    (is (str/includes? startup (str "squad_tool.sh ensure gherkin-parser github.com/unclebob/Acceptance-Pipeline-Specification latest -- 'bash' '" helper "' 'gherkin-parser'")))
    (is (not (str/includes? startup "/Users/unclebob/projects/Acceptance-Pipeline-Specification")))
    (is (not (str/includes? startup "/Users/unclebob/projects/clojure/")))
    (is (str/includes? startup "squad_tool.sh require ir-dry-checker github.com/unclebob/Acceptance-Pipeline-Specification latest"))
    (is (str/includes? startup "record `blocked`"))))

(deftest hardener-forbids-root-tooling-files
  (let [c (contract "hardener")]
    (is (some #{"bb.edn"} (:forbidden-root-files c)))
    (is (some #{"deps.edn"} (:forbidden-root-files c)))))

(deftest hardener-tool-startup-includes-coverage-and-acceptance-prerequisites
  (let [startup (tools/startup-instructions
                 (tools/required-tools repo-root "hardener")
                 (tools/verification-prerequisites repo-root "hardener"))]
    (is (str/includes? startup "## Tool Startup"))
    (is (str/includes? startup "## Verification Prerequisites"))
    (is (str/includes? startup "bb coverage"))
    (is (str/includes? startup "bb acceptance"))
    (is (str/includes? startup "bb acceptance-worker"))
    (is (str/includes? startup "gherkin-mutator"))
    (is (str/includes? startup "lcov"))
    (is (not (str/includes? startup "gherkin-mutator --runner-worker \"bb acceptance\"")))
    (is (seq (tools/required-evidence repo-root "hardener")))))

(deftest hardener-verification-quality-bar
  (let [prereqs (tools/verification-prerequisites repo-root "hardener")
        evidence (map :header (tools/required-evidence repo-root "hardener"))]
    (is (some #(str/includes? % "CRAP ≤ 6") prereqs))
    (is (some #(= "dry" %) evidence))))

(deftest troubleshooter-contract-is-operator-focused
  (let [c (edn/read-string (slurp (str (fs/path repo-root "swarmforge/roles/troubleshooter.contract.edn"))))]
    (is (true? (:persistent c)))
    (is (true? (:idle-until-called c)))
    (is (true? (:elevated-ops c)))))

(deftest squad-leader-contract-encodes-orchestration-boundary
  (let [contract-file (fs/path repo-root "swarmforge/roles/squad-leader.contract.edn")
        c (edn/read-string (slurp (str contract-file)))]
    (is (fs/exists? contract-file))
    (is (true? (:persistent c)))
    (is (true? (:may-talk-to-user c)))
    (is (true? (:may-spawn c)))
    (is (not (contains? c :requires-theme-negotiation-before-analyst)))
    (is (not (contains? c :theme-module-map-before-theme-approval)))
    (is (not (contains? c :theme-approval-before-analyst)))
    (is (true? (:story-packet-source-of-truth c)))
    (is (= "squad_next.sh --residual-only" (:implementation-readiness-source c)))
    (is (= "squad_next.sh --residual-only" (:concurrent-action-source c)))
    (is (true? (:applied-transitions-informational c)))
    (is (= ["hardener" "qa" "architect" "senior-implementer"] (:singleton-roles c)))
    (is (some #{"stories"} (:forbidden-writes c)))
    (is (some #{"production-code"} (:forbidden-writes c)))
    (is (not (some #{"theme-module-maps"} (:writes c))))))

(deftest theme-module-map-outline-has-clean-architecture-sections
  (let [outline (slurp (str (fs/path repo-root "swarmforge/templates/theme-module-map.md")))]
    (is (str/includes? outline "**analyst** authors"))
    (is (str/includes? outline "## Use Cases (Business / Process Rules)"))
    (is (str/includes? outline "## Dependency Rule"))
    (is (str/includes? outline "## UI (Interface Adapters)"))
    (is (str/includes? outline "## IO (Interface Adapters / Drivers)"))
    (is (str/includes? outline "Tooling Layout"))))

(deftest root-bb-edn-has-coverage-task
  ;; Given the SwarmForge repo itself
  ;; When operators run verification
  ;; Then `bb coverage` exists and drives Cloverage into target/coverage/lcov.info
  (let [bb (slurp (str (fs/path repo-root "bb.edn")))
        task (slurp (str (fs/path repo-root "bb/tasks/coverage.clj")))]
    (is (str/includes? bb "coverage"))
    (is (str/includes? bb "bb/tasks/coverage.clj"))
    (is (str/includes? task "clj"))
    (is (str/includes? task "-M:cov"))
    (is (str/includes? task "target/coverage/lcov.info"))))

(deftest root-bb-edn-has-crap-task
  ;; Given the latest crap4clj
  ;; When operators run CRAP here
  ;; Then `bb crap` uses that lib, this repo's source root, and `bb coverage`
  (let [bb (slurp (str (fs/path repo-root "bb.edn")))]
    (is (str/includes? bb "crap4clj"))
    (is (str/includes? bb "e6e0312"))
    (is (str/includes? bb "--coverage-command"))
    (is (str/includes? bb "bb coverage"))
    (is (str/includes? bb "--source-root"))
    (is (str/includes? bb "swarmforge/scripts"))))

(deftest product-tooling-templates-keep-bb-edn-thin
  (let [bb (slurp (str (fs/path repo-root "swarmforge/templates/product-bb.edn")))
        deps (slurp (str (fs/path repo-root "swarmforge/templates/product-deps.edn")))]
    (is (str/includes? bb "local/root"))
    (is (str/includes? bb "bb/tasks/test.clj"))
    (is (not (str/includes? bb ":paths")))
    (is (str/includes? deps ":paths"))))
