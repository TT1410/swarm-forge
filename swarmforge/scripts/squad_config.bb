(require '[babashka.fs :as fs]
         '[clojure.string :as str])

(def squad-default-max-transient-agents 5)
(def squad-default-approval-required
  {"theme" true
   "story" true
   "gherkin" true
   "qa_procedure" true
   "qa-procedure" true
   "implementation" false
   "code_review" false
   "code-review" false
   "hardening" false
   "qa" false
   "architecture" false
   "final" false})

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

(defn squad-approval-required? [root gate]
  (let [gate-key (str/replace gate "-" "_")
        configured (some (fn [[configured-gate value]]
                           (when (= (str/replace configured-gate "-" "_") gate-key)
                             value))
                         (squad-config-entries root "approval_required"))
        default (or (get squad-default-approval-required gate)
                    (get squad-default-approval-required gate-key)
                    false)]
    (if configured
      (let [value (str/lower-case (str/trim configured))]
        (cond
          (#{"true" "yes" "1" "on" "required"} value) true
          (#{"false" "no" "0" "off" "not-required"} value) false
          :else default))
      default)))

(defn squad-transient-agent-config [root]
  (squad-config-value root "transient_agent"))

(defn squad-max-transient-agents [root]
  (if (System/getenv "SWARMFORGE_SQUAD_MAX_AGENTS")
    (squad-env-long "SWARMFORGE_SQUAD_MAX_AGENTS"
                    (squad-config-long root "max_transient_agents" squad-default-max-transient-agents))
    (squad-config-long root "max_transient_agents" squad-default-max-transient-agents)))

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
