(ns squad-config
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def squad-default-max-transient-agents 10)
(def squad-default-recovery-quiet-seconds 300)
(def squad-default-recovery-retry-seconds 60)
(def squad-default-approval-required
  {"theme" false
   "story" false
   "frame" true
   "implementation-plan" true
   "implementation_plan" true
   "gherkin" true
   "qa_procedure" true
   "qa-procedure" true
   "implementation" false
   "code_review" false
   "code-review" false
   "hardening" false
   "qa" false
   "architecture" false
   "final" false
   "implementation_order" false
   "implementation-order" false
   "dependency_checker" false
   "dependency-checker" false
   "finalize" false
   "theme_finalize" false
   "theme-finalize" false})

(defn squad-env-long [name default-value]
  (if-let [value (System/getenv name)]
    (if (re-matches #"[0-9]+" value)
      (Long/parseLong value)
      default-value)
    default-value))

(defn squad-config-value [root key]
  (let [config-file (fs/path root "swarmforge" "squad.conf")]
    (when (fs/exists? config-file)
      (some (fn [line]
              (let [line (str/trim (first (str/split line #"#" 2)))
                    [k v] (str/split line #"\s+" 2)]
                (when (= key k)
                  v)))
            (str/split-lines (slurp (str config-file)))))))

(defn squad-config-entries [root key]
  (let [config-file (fs/path root "swarmforge" "squad.conf")]
    (if (fs/exists? config-file)
      (->> (str/split-lines (slurp (str config-file)))
           (map #(str/trim (first (str/split % #"#" 2))))
           (remove str/blank?)
           (map #(str/split % #"\s+"))
           (filter #(= key (first %)))
           (map rest)
           vec)
      [])))

(defn configured-project-root []
  (when-let [configured (not-empty (System/getenv "SWARMFORGE_PROJECT_ROOT"))]
    (when (fs/exists? (fs/path configured ".swarmforge" "roles.tsv"))
      (fs/path configured))))

(defn cwd-project-root []
  (let [cwd (fs/cwd)]
    (when (fs/exists? (fs/path cwd ".swarmforge" "roles.tsv"))
      cwd)))

(defn git-project-root []
  (let [result (shell/sh "git" "rev-parse" "--show-toplevel")]
    (when (zero? (:exit result))
      (let [root (str/trim (:out result))]
        (when (fs/exists? (fs/path root ".swarmforge" "roles.tsv"))
          (fs/path root))))))

(defn git-common-project-root []
  (let [result (shell/sh "git" "rev-parse" "--git-common-dir")]
    (when (zero? (:exit result))
      (let [common (fs/path (str/trim (:out result)))
            common (if (fs/absolute? common) common (fs/absolutize common))
            root (fs/parent common)]
        (when (fs/exists? (fs/path root ".swarmforge" "roles.tsv"))
          root)))))

(defn project-root []
  (or (configured-project-root)
      (cwd-project-root)
      (git-project-root)
      (git-common-project-root)))

(defn squad-config-long [root key default-value]
  (let [value (squad-config-value root key)]
    (if (and value (re-matches #"[0-9]+" value))
      (Long/parseLong value)
      default-value)))

(defn squad-config-bool [root key default-value]
  (let [value (some-> (squad-config-value root key) str/lower-case str/trim)]
    (cond
      (#{"true" "yes" "1" "on" "required"} value) true
      (#{"false" "no" "0" "off" "not-required"} value) false
      :else default-value)))

(defn parse-config-bool [value default-value]
  (let [value (some-> value str/lower-case str/trim)]
    (cond
      (#{"true" "yes" "1" "on" "required"} value) true
      (#{"false" "no" "0" "off" "not-required"} value) false
      :else default-value)))

(defn approval-required-config [root gate-key]
  (some (fn [[configured-gate value]]
          (when (= (str/replace configured-gate "-" "_") gate-key)
            value))
        (squad-config-entries root "approval_required")))

(defn default-approval-required [gate gate-key]
  (or (get squad-default-approval-required gate)
      (get squad-default-approval-required gate-key)
      false))

(defn squad-approval-required? [root gate]
  (let [gate-key (str/replace gate "-" "_")
        default (default-approval-required gate gate-key)]
    (parse-config-bool (approval-required-config root gate-key) default)))

(def valid-transient-agent-backends
  #{"codex" "claude" "copilot" "grok" "squad-leader" "leader"})

(defn- transient-agent-lines [root]
  (squad-config-entries root "transient_agent"))

(defn squad-transient-agent-default
  "Global default backend from a one-token `transient_agent <backend>` line."
  [root]
  (some (fn [parts]
          (when (= 1 (count parts))
            (first parts)))
        (transient-agent-lines root)))

(defn squad-transient-agent-for-template
  "Per-template override from `transient_agent <template> <backend>`, else default."
  [root template]
  (or (when-not (str/blank? template)
        (some (fn [parts]
                (when (and (= 2 (count parts))
                           (= template (first parts)))
                  (second parts)))
              (transient-agent-lines root)))
      (squad-transient-agent-default root)))

(defn squad-transient-agent-config
  "Backward-compatible global default (first bare `transient_agent <backend>`)."
  [root]
  (squad-transient-agent-default root))

(defn squad-max-transient-agents [root]
  (if (System/getenv "SWARMFORGE_SQUAD_MAX_AGENTS")
    (squad-env-long "SWARMFORGE_SQUAD_MAX_AGENTS"
                    (squad-config-long root "max_transient_agents" squad-default-max-transient-agents))
    (squad-config-long root "max_transient_agents" squad-default-max-transient-agents)))

(defn squad-recovery-quiet-seconds [root]
  (if (System/getenv "SWARMFORGE_SQUAD_RECOVERY_QUIET_SECONDS")
    (squad-env-long "SWARMFORGE_SQUAD_RECOVERY_QUIET_SECONDS"
                    (squad-config-long root "recovery_quiet_seconds" squad-default-recovery-quiet-seconds))
    (squad-config-long root "recovery_quiet_seconds" squad-default-recovery-quiet-seconds)))

(defn squad-recovery-retry-seconds [root]
  (if (System/getenv "SWARMFORGE_SQUAD_RECOVERY_RETRY_SECONDS")
    (squad-env-long "SWARMFORGE_SQUAD_RECOVERY_RETRY_SECONDS"
                    (squad-config-long root "recovery_retry_seconds" squad-default-recovery-retry-seconds))
    (squad-config-long root "recovery_retry_seconds" squad-default-recovery-retry-seconds)))

(defn singleton-templates
  "Templates with max_active_template 1 in squad.conf. Empty if unconfigured."
  [root]
  (->> (squad-config-entries root "max_active_template")
       (keep (fn [[template limit]]
               (when (= "1" limit) template)))
       set))

(defn squad-template-limit [root template]
  (some (fn [[configured-template limit]]
          (when (and (= configured-template template)
                     limit
                     (re-matches #"[0-9]+" limit))
            (Long/parseLong limit)))
        (squad-config-entries root "max_active_template")))

(defn squad-template-group-limits [root template]
  (->> (squad-config-entries root "max_active_group")
       (keep (fn [[group-name limit & templates]]
               (when (and group-name
                          limit
                          (re-matches #"[0-9]+" limit)
                          (some #{template} templates))
                 {:group group-name
                  :limit (Long/parseLong limit)
                  :templates (set templates)})))))
