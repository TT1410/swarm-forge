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
   "senior-implementer"])

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
  (let [prompt (slurp (str (fs/path repo-root "swarmforge/worker-common.prompt")))]
    (is (str/includes? prompt "Use the assigned worktree for all file inspection, edits, staging, commits, and local verification."))
    (is (str/includes? prompt "Do not create or edit project-root files directly"))))

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

      (= "cleaner" (:role c))
      (do
        (is (true? (:may-web-search c)))
        (is (= "property-testing-framework-discovery" (:web-search-scope c))))

      :else
      (is (false? (:may-web-search c)) (:role c)))))

(deftest squad-role-contracts-separate-artifact-ownership
  (let [by-role (into {} (map (juxt :role identity) (contracts)))]
    (is (= ["stories/"] (:artifact-roots (by-role "analyst"))))
    (is (true? (:requires-dependency-checker (by-role "analyst"))))
    (is (some #{"dependency-checker"} (:writes (by-role "analyst"))))
    (is (some #{"dependency-checker.edn"} (:allowed-root-files (by-role "analyst"))))
    (is (= ["features/"] (:artifact-roots (by-role "gherkin-writer"))))
    (is (= ["qa/"] (:artifact-roots (by-role "qa-procedure-writer"))))
    (doseq [artifact-role ["gherkin-writer" "qa-procedure-writer" "gherkin-reviewer" "qa-procedure-reviewer"]]
      (is (false? (:may-run-broad-tests (by-role artifact-role))) artifact-role))
    (doseq [review-role ["gherkin-reviewer" "qa-procedure-reviewer" "code-reviewer" "architect"]]
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
    (is (= ["gherkin-parser" "ir-dry-checker"] (:required-tool-ids (by-role "gherkin-reviewer"))))
    (is (= #{"dependency-checker"} (required-tool-names "implementer")))
    (is (= #{"crap4clj" "dry4clj" "dependency-checker"} (required-tool-names "cleaner")))
    (is (= #{"clj-mutate" "crap4clj" "dry4clj" "gherkin-parser" "gherkin-mutator" "dependency-checker"}
           (required-tool-names "hardener")))
    (is (= #{"dependency-checker"} (required-tool-names "architect")))
    (is (= #{"dependency-checker"} (required-tool-names "code-reviewer")))
    (is (= #{"dependency-checker"} (required-tool-names "senior-implementer")))
    (is (= #{"crap4clj" "dry4clj"} (required-tool-names "qa")))
    (is (= #{"gherkin-parser" "ir-dry-checker"} (required-tool-names "gherkin-writer")))
    (is (= #{"gherkin-parser" "ir-dry-checker"} (required-tool-names "gherkin-reviewer")))))

(deftest squad-role-prompts-include-valid-helper-examples
  (let [prompt (slurp (str (fs/path repo-root "swarmforge/worker-common.prompt")))]
    (is (str/includes? prompt "squad_event.sh running"))
    (is (str/includes? prompt "squad_event.sh blocked"))
    (is (not (re-find #"squad_event\.sh\s+[a-z][a-z0-9-]*-\d{3}\s+" prompt)))
    (is (not (re-find #"squad_tool\.sh require [A-Za-z0-9._-]+(?:`|\n)" prompt))))
  (let [cleaner (slurp (str (fs/path repo-root "swarmforge/role-templates/cleaner.prompt")))
        hardener (slurp (str (fs/path repo-root "swarmforge/role-templates/hardener.prompt")))
        gherkin (slurp (str (fs/path repo-root "swarmforge/role-templates/gherkin-writer.prompt")))
        qa (slurp (str (fs/path repo-root "swarmforge/role-templates/qa.prompt")))]
    (doseq [prompt [cleaner qa hardener]]
      (is (str/includes? prompt "Tool Startup") prompt)
      (is (str/includes? prompt "swarmforge/tool-table.edn"))
      (is (not (str/includes? prompt "record `blocked`"))))
    (is (str/includes? hardener "Verification Prerequisites"))
    (is (str/includes? gherkin "generated assignment `Tool Startup` section"))
    (is (str/includes? gherkin "Acceptance Pipeline Specification"))))

(deftest squad-reviewer-prompts-use-deterministic-review-helper
  (doseq [template ["gherkin-reviewer" "qa-procedure-reviewer" "code-reviewer" "architect"]]
    (let [prompt (slurp (str (fs/path repo-root "swarmforge/role-templates" (str template ".prompt"))))]
      (is (str/includes? prompt "squad_review.sh <assignment-id> <accepted|changes-requested> <review-file>")
          template))))

(deftest squad-analyst-prompt-includes-invest-story-guidance
  (let [prompt (slurp (str (fs/path repo-root "swarmforge/role-templates/analyst.prompt")))]
    (is (str/includes? prompt "I.N.V.E.S.T."))
    (doseq [word ["independent" "negotiable" "valuable" "estimable" "small" "testable"]]
      (is (str/includes? prompt word)))))

(deftest analyst-must-author-dependency-checker-config
  ;; Given analysis is the place Clean Architecture components are cut into stories
  ;; When the analyst role is specified
  ;; Then dependency-checker.edn is required product policy at handoff
  (let [prompt (slurp (str (fs/path repo-root "swarmforge/role-templates/analyst.prompt")))
        template (slurp (str (fs/path repo-root "swarmforge/templates/dependency-checker.edn")))
        c (contract "analyst")]
    (is (str/includes? prompt "dependency-checker.edn"))
    (is (str/includes? prompt ":allowed-dependencies"))
    (is (str/includes? prompt "Analysis is incomplete without this file"))
    (is (str/includes? template ":allowed-dependencies"))
    (is (true? (:requires-dependency-checker c)))))

(deftest analyst-must-author-implementation-order
  ;; Given analysis cuts stories that may have implementer dependencies
  ;; When the analyst role is specified
  ;; Then implementation-order.md is always required (edges or comment-only)
  (let [prompt (slurp (str (fs/path repo-root "swarmforge/role-templates/analyst.prompt")))
        template (slurp (str (fs/path repo-root "swarmforge/templates/theme-implementation-order.md")))
        c (contract "analyst")]
    (is (str/includes? prompt "Always** commit root **`implementation-order.md`")
        "order is always required, not only when deps exist")
    (is (str/includes? prompt "comment header")
        "single-story / no-gate themes still get an explicit file")
    (is (str/includes? template "always** commits this file"))
    (is (true? (:requires-implementation-order c)))
    (is (some #{"implementation-order"} (:writes c)))))

(deftest squad-architect-prompt-frames-principles-as-review-advice
  (let [prompt (slurp (str (fs/path repo-root "swarmforge/role-templates/architect.prompt")))]
    (is (str/includes? prompt "Make recommendations; do not directly rewrite the system."))
    (is (str/includes? prompt "Low level is close to IO"))
    (is (str/includes? prompt "high level is far from IO"))
    (is (str/includes? prompt "Dependencies should point from lower-level functions and modules"))
    (is (str/includes? prompt "Large modules with many responsibilities"))
    (is (str/includes? prompt "well-named modules with single responsibilities"))))

(deftest squad-senior-implementer-runs-full-verification-before-handoff
  (let [prompt (slurp (str (fs/path repo-root "swarmforge/role-templates/senior-implementer.prompt")))
        contract (contract "senior-implementer")]
    (is (str/includes? prompt "full acceptance suite"))
    (is (str/includes? prompt "bb acceptance"))
    (is (not (str/includes? prompt "Run relevant verification before handoff.")))
    (is (true? (:may-run-broad-tests contract)))))

(deftest required-tool-startup-instructions-come-from-tool-table
  (let [helper (str (fs/path repo-root "swarmforge/scripts/install_bb_tool.sh"))
        startup (tools/startup-instructions (tools/required-tools repo-root "gherkin-writer"))]
    (is (str/includes? startup "## Tool Startup"))
    (is (str/includes? startup "squad_tool.sh require gherkin-parser github.com/unclebob/Acceptance-Pipeline-Specification latest"))
    (is (str/includes? startup (str "squad_tool.sh ensure gherkin-parser github.com/unclebob/Acceptance-Pipeline-Specification latest -- 'bash' '" helper "' '/Users/unclebob/projects/Acceptance-Pipeline-Specification' 'gherkin-parser'")))
    (is (str/includes? startup "squad_tool.sh require ir-dry-checker github.com/unclebob/Acceptance-Pipeline-Specification latest"))
    (is (str/includes? startup "record `blocked`"))))

(deftest hardener-forbids-root-tooling-files
  ;; Hardener must not thrash root bb.edn/deps.edn
  (let [c (contract "hardener")
        prompt (slurp (str (fs/path repo-root "swarmforge/role-templates/hardener.prompt")))]
    (is (some #{"bb.edn"} (:forbidden-root-files c)))
    (is (some #{"deps.edn"} (:forbidden-root-files c)))
    (is (str/includes? prompt "Root tooling denylist"))
    (is (str/includes? prompt "swarm_handoff"))))

(deftest hardener-tool-startup-includes-coverage-and-acceptance-prerequisites
  (let [startup (tools/startup-instructions
                 (tools/required-tools repo-root "hardener")
                 (tools/verification-prerequisites repo-root "hardener"))
        hardener (slurp (str (fs/path repo-root "swarmforge/role-templates/hardener.prompt")))]
    (is (str/includes? startup "## Tool Startup"))
    (is (str/includes? startup "## Verification Prerequisites"))
    (is (str/includes? startup "bb coverage"))
    (is (str/includes? startup "bb acceptance"))
    (is (str/includes? startup "bb acceptance-worker"))
    (is (str/includes? startup "gherkin-mutator"))
    (is (str/includes? startup "lcov"))
    (is (not (str/includes? startup "gherkin-mutator --runner-worker \"bb acceptance\"")))
    (is (str/includes? hardener "bb acceptance-worker"))
    (is (not (str/includes? hardener "--runner-worker \"bb acceptance\"")))
    (is (seq (tools/required-evidence repo-root "hardener")))))

(deftest implementer-prompt-requires-six-pack-aps-model
  (let [prompt (slurp (str (fs/path repo-root "swarmforge/role-templates/implementer.prompt")))]
    (is (str/includes? prompt "six-pack"))
    (is (str/includes? prompt "entrypoint generator"))
    (is (str/includes? (str/lower-case prompt) "step handlers"))
    (is (str/includes? prompt "bb acceptance-worker"))
    (is (str/includes? prompt "acceptance/steps/"))))

(deftest hardener-prompt-requires-quality-bar
  (let [prompt (slurp (str (fs/path repo-root "swarmforge/role-templates/hardener.prompt")))
        lower (str/lower-case prompt)
        prereqs (tools/verification-prerequisites repo-root "hardener")
        evidence (map :header (tools/required-evidence repo-root "hardener"))]
    (is (str/includes? prompt "CRAP ≤ 6") prompt)
    (is (str/includes? lower "all mutants are killed") prompt)
    (is (str/includes? lower "reduce duplication") prompt)
    (is (str/includes? lower "hand back a blocker") prompt)
    (is (some #(str/includes? % "CRAP ≤ 6") prereqs))
    (is (some #(= "dry" %) evidence))))

(deftest troubleshooter-role-prompt-is-short-and-operator-focused
  (let [prompt (slurp (str (fs/path repo-root "swarmforge/roles/troubleshooter.prompt")))
        contract (edn/read-string (slurp (str (fs/path repo-root "swarmforge/roles/troubleshooter.contract.edn"))))]
    (is (< (count prompt) 2500) "prompt stays short")
    (is (str/includes? (str/lower-case prompt) "look around"))
    (is (str/includes? prompt "Squad Leader"))
    (is (true? (:persistent contract)))
    (is (true? (:idle-until-called contract)))
    (is (true? (:elevated-ops contract)))))

(deftest implementer-prompt-owns-acceptance-pipeline
  (let [prompt (slurp (str (fs/path repo-root "swarmforge/role-templates/implementer.prompt")))]
    (is (str/includes? prompt "Acceptance Pipeline"))
    (is (str/includes? prompt "bb acceptance"))
    (is (str/includes? prompt "ACCEPTANCE_BLOCKER"))))

(deftest late-roles-require-full-acceptance-suite-before-handoff
  (doseq [[template needle] [["hardener" "full acceptance suite"]
                             ["qa" "full acceptance suite"]
                             ["architect" "full acceptance suite"]
                             ["senior-implementer" "full acceptance suite"]]]
    (let [prompt (slurp (str (fs/path repo-root "swarmforge/role-templates" (str template ".prompt"))))]
      (is (str/includes? prompt needle) template)
      (is (str/includes? prompt "bb acceptance") template))))

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
    (is (true? (:theme-module-map-before-theme-approval c)))
    (is (true? (:theme-approval-before-analyst c)))
    (is (true? (:story-packet-source-of-truth c)))
    (is (= "squad_next.sh --residual-only" (:implementation-readiness-source c)))
    (is (= "squad_next.sh --residual-only" (:concurrent-action-source c)))
    (is (true? (:applied-transitions-informational c)))
    (is (str/includes? prompt "CONCURRENT_ACTIONS"))
    (is (str/includes? prompt "CONCURRENT_COMMAND"))
    (is (str/includes? prompt "APPLIED_TRANSITIONS"))
    (is (str/includes? prompt "informational history"))
    (is (str/includes? prompt "Theme Module Map"))
    (is (str/includes? prompt "squad_theme.sh module-map"))
    (is (str/includes? prompt "theme-module-map.md"))
    (is (= ["hardener" "qa" "architect" "senior-implementer"] (:singleton-roles c)))
    (is (some #{"stories"} (:forbidden-writes c)))
    (is (some #{"production-code"} (:forbidden-writes c)))
    (is (some #{"theme-module-maps"} (:writes c)))))

(deftest analyst-implementer-architect-prompts-reference-module-map
  (let [analyst (slurp (str (fs/path repo-root "swarmforge/role-templates/analyst.prompt")))
        implementer (slurp (str (fs/path repo-root "swarmforge/role-templates/implementer.prompt")))
        architect (slurp (str (fs/path repo-root "swarmforge/role-templates/architect.prompt")))
        outline (slurp (str (fs/path repo-root "swarmforge/templates/theme-module-map.md")))]
    (is (str/includes? analyst "Theme Module Map"))
    (is (str/includes? analyst "Separate **process**, **UI**, and **IO**"))
    (is (str/includes? analyst "implementation-order"))
    (is (str/includes? analyst "dependency-checker.edn"))
    (is (str/includes? outline "**analyst** authors"))
    (is (str/includes? implementer "Theme Module Map"))
    (is (str/includes? implementer "root tooling files"))
    (is (str/includes? implementer "deps.edn"))
    (is (str/includes? architect "Theme Module Map"))
    (is (str/includes? architect "module map"))
    (is (str/includes? architect "Module Map Recommendations"))
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
        deps (slurp (str (fs/path repo-root "swarmforge/templates/product-deps.edn")))
        engineering (slurp (str (fs/path repo-root "swarmforge/constitution/articles/engineering.prompt")))
        implementer (slurp (str (fs/path repo-root "swarmforge/role-templates/implementer.prompt")))
        cleaner (slurp (str (fs/path repo-root "swarmforge/role-templates/cleaner.prompt")))
        hardener (slurp (str (fs/path repo-root "swarmforge/role-templates/hardener.prompt")))]
    (is (str/includes? bb "local/root"))
    (is (str/includes? bb "bb/tasks/test.clj"))
    (is (not (str/includes? bb ":paths")))
    (is (str/includes? deps ":paths"))
    (is (str/includes? engineering "Project Tooling Layout"))
    (is (str/includes? engineering "Keep root `bb.edn` thin"))
    (doseq [prompt [implementer cleaner hardener]]
      (is (str/includes? prompt "bb.edn"))
      (is (str/includes? prompt "deps.edn")))))

(deftest runtime-constitution-does-not-require-development-design-doc
  (let [project-article (slurp (str (fs/path repo-root "swarmforge/constitution/articles/project.prompt")))]
    (is (not (str/includes? project-article "squad-design.md")))))
