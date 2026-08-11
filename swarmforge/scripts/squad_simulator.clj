#!/usr/bin/env bb

(ns squad-simulator
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def script-dir (fs/parent *file*))
(def usage-text
  (str "Usage: squad_simulator.sh htw [--keep] [--seed <n>] [--runs <n>]\n"
       "       [--stories <n|min..max>] [--handoff-ticks <n|min..max>]\n"
       "       [--approval-ticks <n|min..max>] [--stall-percent <0-100>]\n"
       "       [--stall-mode dark|active-then-handoff|active-then-dark|mixed]\n"
       "       [--stall-active-ticks <n|min..max>] [--merge-failure-template <template[,template...]>]\n"
       "       [--max-ticks <n>]"))

(defn path-str [path]
  (let [path (fs/path path)]
    (try
      (.toString (.toRealPath path (make-array java.nio.file.LinkOption 0)))
      (catch Exception _
        (.toString (.normalize (.toAbsolutePath path)))))))

(defn exit! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn sh! [dir & args]
  (let [env (cond-> {"SWARMFORGE_PROJECT_ROOT" (path-str dir)
                     ;; Simulator owns mechanical apply like squadd (main-git owner).
                     "SWARMFORGE_MAIN_GIT" "1"
                     "SWARMFORGE_MAIN_GIT_OWNER" "daemon"
                     "SWARMFORGE_ROLE" "squadd"}
              (System/getProperty "swarmforge.sim.now")
              (assoc "SWARMFORGE_NOW" (System/getProperty "swarmforge.sim.now")
                     "SWARMFORGE_SQUAD_RECOVERY_GRACE_SECONDS" "5"))
        result (apply process/sh (concat [{:dir (path-str dir)
                                           :continue true
                                           :extra-env env}]
                                         args))]
    (when-not (zero? (:exit result))
      (exit! (:exit result)
             (str "SIM_COMMAND_FAILED: " (str/join " " args))
             (:err result)))
    result))

(defn write-file! [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn append-file! [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text :append true))

(defn file-map [file]
  (if (fs/exists? file)
    (into {}
          (keep (fn [line]
                  (when-let [[_ k v] (re-matches #"([^:]+):\s*(.*)" line)]
                    [k v])))
          (take-while (complement str/blank?)
                      (str/split-lines (slurp (str file)))))
    {}))

(defn command-map [text]
  (into {}
        (keep (fn [line]
                (when-let [[_ k v] (re-matches #"([A-Z_]+):\s*(.*)" line)]
                  [(str/lower-case k) v])))
	        (str/split-lines text)))

(def concurrent-field-map
  {"concurrent_action_name" "next_action"
   "concurrent_theme" "theme"
   "concurrent_story" "story"
   "concurrent_gate" "gate"
   "concurrent_template" "template"
   "concurrent_agent" "agent"
   "concurrent_assignment" "assignment"
   "concurrent_batch_kind" "batch_kind"
   "concurrent_batch" "batch"
   "concurrent_reason" "reason"
   "concurrent_command" "command"})

(defn concurrent-action-line [line]
  (when-let [[_ k v] (re-matches #"CONCURRENT_([A-Z_]+):\s*(.*)" line)]
    (let [key (str/lower-case (str "concurrent_" k))]
      (when (contains? concurrent-field-map key)
        [key v]))))

(defn finish-concurrent-action [actions current]
  (cond-> actions
    (seq current) (conj current)))

(defn normalize-concurrent-action [action]
  (into {}
        (map (fn [[k v]]
               [(get concurrent-field-map k k) v]))
        action))

(defn concurrent-actions [text]
  (->> (reduce (fn [{:keys [actions current]} line]
                 (if (re-matches #"CONCURRENT_ACTION:\s*[0-9]+" line)
                   {:actions (finish-concurrent-action actions current)
                    :current {}}
                   (if-let [[k v] (when current
                                    (concurrent-action-line line))]
                     {:actions actions
                      :current (assoc current k v)}
                     {:actions actions
                      :current current})))
               {:actions [] :current nil}
               (str/split-lines text))
       ((fn [{:keys [actions current]}]
          (finish-concurrent-action actions current)))
       (mapv normalize-concurrent-action)))

(defn parse-long! [label value]
  (try
    (Long/parseLong value)
    (catch Exception _
      (exit! 2 (str label " must be an integer.")))))

(defn range-match! [label value]
  (or (re-matches #"([0-9]+)(?:\.\.([0-9]+))?" value)
      (exit! 2 (str label " must be an integer or min..max range."))))

(defn range-end [label start b]
  (if b (parse-long! label b) start))

(defn ensure-valid-range! [label start end]
  (when (> start end)
    (exit! 2 (str label " range minimum must be <= maximum."))))

(defn parse-int-range! [label value]
  (let [[_ a b] (range-match! label value)
        start (when a (parse-long! label a))
        end (range-end label start b)]
    (ensure-valid-range! label start end)
    [start end]))

(defn choose-int [^java.util.Random rng [low high]]
  (+ low (.nextInt rng (inc (- high low)))))

(def default-options
  {:keep? false
   :seed nil
   :runs 1
   :stories-range [2 2]
   :handoff-ticks-range [3 3]
   :approval-ticks-range [5 5]
   :stall-percent 0
   :stall-mode "dark"
   :stall-active-ticks-range [5 5]
   :merge-failure-templates []
   :max-ticks 1000})

(defn positive-long! [label value]
  (let [n (parse-long! label value)]
    (when-not (pos? n)
      (exit! 2 (str label " must be greater than zero.")))
    n))

(defn positive-range! [label value]
  (let [value (parse-int-range! label value)]
    (when-not (pos? (first value))
      (exit! 2 (str label " must be greater than zero.")))
    value))

(defn stall-percent! [value]
  (let [percent (parse-long! "Stall percent" value)]
    (when-not (<= 0 percent 100)
      (exit! 2 "Stall percent must be between 0 and 100."))
    percent))

(defn stall-mode! [value]
  (when-not (#{"dark" "active-then-handoff" "active-then-dark" "mixed"} value)
    (exit! 2 "Stall mode must be dark, active-then-handoff, active-then-dark, or mixed."))
  value)

(defn template-sequence! [value]
  (->> (str/split (or value "") #",")
       (map str/trim)
       (remove str/blank?)
       vec))

(def option-parsers
  {"--seed" [:seed #(parse-long! "Seed" %)]
   "--runs" [:runs #(positive-long! "Runs" %)]
   "--stories" [:stories-range #(positive-range! "Stories" %)]
   "--handoff-ticks" [:handoff-ticks-range #(parse-int-range! "Handoff ticks" %)]
   "--approval-ticks" [:approval-ticks-range #(parse-int-range! "Approval ticks" %)]
   "--stall-percent" [:stall-percent stall-percent!]
   "--stall-mode" [:stall-mode stall-mode!]
   "--stall-active-ticks" [:stall-active-ticks-range #(parse-int-range! "Stall active ticks" %)]
   "--merge-failure-template" [:merge-failure-templates template-sequence!]
   "--max-ticks" [:max-ticks #(positive-long! "Max ticks" %)]})

(defn apply-option! [opts flag value]
  (if (= "--keep" flag)
    (assoc opts :keep? true)
    (if-let [[key parse] (option-parsers flag)]
      (assoc opts key (parse value))
      (exit! 1 usage-text))))

(defn parse-options [args]
  (loop [remaining args
         opts default-options]
    (if (empty? remaining)
      opts
      (let [[flag value & more] remaining
            rest-args (if (= "--keep" flag) (rest remaining) more)]
        (recur rest-args (apply-option! opts flag value))))))

(defn run-script! [root script & args]
  (apply sh! root (path-str (fs/path script-dir script)) args))

(defn git-commit! [root message]
  (sh! root "git" "add" ".")
  (sh! root "git" "commit" "-q" "--allow-empty" "-m" message)
  (str/trim (:out (sh! root "git" "rev-parse" "--short=10" "HEAD"))))

(defn setup-root! []
  (let [root (fs/create-temp-dir {:prefix "swarmforge-htw-sim."})]
    (sh! root "git" "init" "-q")
    (sh! root "git" "config" "user.email" "sim@example.com")
    (sh! root "git" "config" "user.name" "Swarm Simulator")
    (write-file! (fs/path root "README.md") "simulated HTW repo\n")
    (git-commit! root "Initial commit")
    (write-file! (fs/path root ".swarmforge" "roles.tsv")
                 (str "squad-leader\tmaster\t" (path-str root) "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
    (write-file! (fs/path root "swarmforge" "squad.conf")
                 (str "max_transient_agents 3\n"
                      "approval_required theme true\n"
                      "approval_required story true\n"
	                      "approval_required gherkin true\n"
	                      "approval_required qa_procedure true\n"
	                      "approval_required implementation false\n"
	                      "recovery_quiet_seconds 5\n"
	                      "recovery_retry_seconds 5\n"))
    (doseq [template ["analyst" "gherkin-writer" "qa-procedure-writer"
                      "gherkin-reviewer" "qa-procedure-reviewer"
                      "implementer" "cleaner" "code-reviewer"
                      "hardener" "qa" "architect" "senior-implementer" "merger"]]
      (write-file! (fs/path root "swarmforge" "role-templates" (str template ".prompt"))
                   (str "Simulated " template " role.\n")))
    (write-file! (fs/path root "theme.md") "Implement a faithful Hunt the Wumpus.\n")
	    (run-script! root "squad_theme.sh" "create" "hunt-the-wumpus" "theme.md")
	    root))
(def default-story-specs
  [["cave-topology" "Story: cave topology and hazards.\n"]
   ["player-actions" "Story: player actions and turns.\n"]])

(defn generated-story-spec [n]
  [(format "story-%02d" n)
   (format "Story: generated HTW behavior %02d.\n" n)])

(defn story-specs [n]
  (vec (take n (concat default-story-specs
                       (map generated-story-spec (iterate inc 3))))))

(def sim-start-instant (java.time.Instant/parse "2026-08-03T00:00:00Z"))

(defn sim-timestamp [tick]
  (str (.plusSeconds sim-start-instant tick)))

(defn set-sim-now! [tick]
  (System/setProperty "swarmforge.sim.now" (sim-timestamp tick)))

(defn write-agent-status! [root agent state detail tick]
  (let [agent-dir (fs/path root ".squad" "agents" agent)
        timestamp (sim-timestamp tick)
        task-id (or (get (file-map (fs/path agent-dir "metadata")) "task_id") agent)]
    (write-file! (fs/path agent-dir "status")
                 (str "state: " state "\n"
                      "detail: " detail "\n"
                      "updated_at: " timestamp "\n"))
    (write-file! (fs/path agent-dir "heartbeat")
                 (str "agent: " agent "\n"
                      "task_id: " task-id "\n"
                      "state: " state "\n"
                      "detail: " detail "\n"
                      "updated_at: " timestamp "\n"))))

(defn write-liveness! [root agent tick changed? detail]
  (write-file! (fs/path root ".squad" "agents" agent "liveness")
               (str "state: " (if changed? "running_pane_active" "running_pane_idle") "\n"
                    "observed_at: " (sim-timestamp tick) "\n"
                    "pane_changed: " (if changed? "true" "false") "\n"
                    "pane_hash: simulated-" tick "\n"
                    "last_10_lines:\n"
                    detail "\n")))

(defn assignment-file [root assignment-id]
  (fs/path root ".squad" "assignments" assignment-id "assignment.md"))

(declare run-command!)

(defn placeholder-instructions! [root]
  (let [file (fs/path root ".squad" "simulator" "instructions.md")]
    (write-file! file "Simulated assignment instructions.\n")
    (path-str file)))

(defn replace-instructions-placeholder [root command]
  (str/replace command "<instructions-file>" (placeholder-instructions! root)))

(defn create-merger-assignment! [root command]
  (run-command! root (replace-instructions-placeholder root command)))

(defn create-standard-assignment! [root {:strs [theme story template assignment]}]
  (let [dir (fs/path root ".squad" "assignments" assignment)
        assignment-path (assignment-file root assignment)]
    (write-file! assignment-path
                 (str "# Simulated Assignment\n\n"
                      "assignment_id: " assignment "\n"
                      "theme_id: " theme "\n"
                      "story_id: " story "\n"
                      "template: " template "\n"))
    (write-file! (fs/path dir "metadata")
                 (str "assignment_id: " assignment "\n"
                      "theme_id: " theme "\n"
                      "story_id: " story "\n"
                      "template: " template "\n"
                      "assignment_file: " (path-str assignment-path) "\n"
                      "created_at: simulated\n"))
    (write-file! (fs/path dir "status")
                 (str "assignment_id: " assignment "\n"
                      "state: created\n"
                      "detail: simulated\n"
                      "updated_at: simulated\n"))))

(defn create-assignment! [root {:strs [template command] :as command-map}]
  (if (= "merger" template)
    (create-merger-assignment! root command)
    (create-standard-assignment! root command-map)))

(defn role-rows [root]
  (let [file (fs/path root ".swarmforge" "roles.tsv")]
    (if (fs/exists? file)
      (->> (str/split-lines (slurp (str file)))
           (remove str/blank?)
           (map #(str/split % #"\t" -1))
           vec)
      [])))

(defn write-roles! [root rows]
  (write-file! (fs/path root ".swarmforge" "roles.tsv")
               (apply str (map #(str (str/join "\t" %) "\n") rows))))

(defn next-agent-id [counters template]
  (let [n (inc (get @counters template 0))]
    (swap! counters assoc template n)
    (format "%s-%03d" template n)))

(defn choose-stall-mode [options rng]
  (if (= "mixed" (:stall-mode options))
    (let [modes ["dark" "active-then-handoff" "active-then-dark"]]
      (nth modes (.nextInt rng (count modes))))
    (:stall-mode options)))

(defn schedule-liveness! [scheduled agent start-tick end-tick]
  (doseq [activity-tick (range (inc start-tick) (inc end-tick))]
    (swap! scheduled conj {:type :liveness
                           :due activity-tick
                           :agent agent})))

(defn write-sim-agent! [root rows agent template assignment session worktree tick]
  (write-roles! root (conj rows [agent agent worktree session (str/replace agent "-" " ") "codex" "task"]))
  (write-file! (fs/path root ".squad" "agents" agent "metadata")
               (str "agent: " agent "\n"
                    "template: " template "\n"
                    "task_id: " assignment "\n"
                    "session: " session "\n"))
  (write-agent-status! root agent "running" "simulated" tick))

(defn schedule-handoff! [scheduled due agent assignment]
  (swap! scheduled conj {:type :handoff :due due :agent agent :assignment assignment}))

(defn stalled-agent-result [agent stall-mode]
  {:agent agent :stalled? true :stall-mode stall-mode})

(defn active-handoff-stall! [scheduled tick latency agent assignment stall-mode active-ticks]
  (let [handoff-due (+ tick active-ticks latency)]
    (schedule-liveness! scheduled agent tick (+ tick active-ticks))
    (schedule-handoff! scheduled handoff-due agent assignment)
    (assoc (stalled-agent-result agent stall-mode) :due handoff-due)))

(defn active-dark-stall! [scheduled tick agent stall-mode active-ticks]
  (schedule-liveness! scheduled agent tick (+ tick active-ticks))
  (stalled-agent-result agent stall-mode))

(def stalled-agent-schedulers
  {"dark" (fn [_ _ _ agent _ stall-mode _]
            (stalled-agent-result agent stall-mode))
   "active-then-handoff" active-handoff-stall!
   "active-then-dark" (fn [scheduled tick _ agent _ stall-mode active-ticks]
                        (active-dark-stall! scheduled tick agent stall-mode active-ticks))})

(defn schedule-stalled-agent! [scheduled tick latency agent assignment stall-mode active-ticks]
  ((stalled-agent-schedulers stall-mode)
   scheduled tick latency agent assignment stall-mode active-ticks))

(defn schedule-normal-agent! [scheduled due agent assignment]
  (schedule-handoff! scheduled due agent assignment)
  {:agent agent :due due :stalled? false})

(defn spawn-agent! [root counters scheduled tick options rng {:strs [template assignment]}]
  (let [agent (next-agent-id counters template)
        session (str "swarmforge-" agent)
        rows (role-rows root)
        worktree (path-str (fs/path root ".worktrees" agent))
        latency (choose-int rng (:handoff-ticks-range options))
        due (+ tick latency)
        stalled? (< (.nextInt rng 100) (:stall-percent options))
        stall-mode (when stalled? (choose-stall-mode options rng))
        active-ticks (when stalled? (choose-int rng (:stall-active-ticks-range options)))]
    (write-sim-agent! root rows agent template assignment session worktree tick)
    (if stalled?
      (schedule-stalled-agent! scheduled tick latency agent assignment stall-mode active-ticks)
      (schedule-normal-agent! scheduled due agent assignment))))

(defn create-handoff! [root tick {:keys [agent assignment]}]
  (let [sha (str/trim (:out (sh! root "git" "rev-parse" "--short=10" "HEAD")))
        file (fs/path root ".swarmforge" "handoffs" "inbox" "new"
                      (format "50_%06d_000001_from_%s_to_squad-leader.handoff" tick agent))]
    (write-file! file
                 (str "type: git_handoff\n"
                      "to: squad-leader\n"
                      "from: " agent "\n"
                      "priority: 50\n"
                      "task: " assignment "\n"
                      "commit: " sha "\n"
                      "assignment: " assignment "\n"
                      "agent: " agent "\n"
                      "template: simulator\n"
                      "artifacts: none\n\n"
                      "simulated result\n"))))

(defn due-events! [root scheduled tick]
  (let [due (filter #(<= (:due %) tick) @scheduled)]
    (swap! scheduled #(vec (remove (fn [event] (<= (:due event) tick)) %)))
    (doseq [event due]
      (case (:type event)
        :handoff (create-handoff! root tick event)
        :liveness (write-liveness! root (:agent event) tick true
                                   (str (:agent event) " still active at tick " tick))))))

(defn mark-assignment! [root assignment state]
  (write-file! (fs/path root ".squad" "assignments" assignment "status")
               (str "assignment_id: " assignment "\n"
                    "state: " state "\n"
                    "detail: simulated\n"
                    "updated_at: simulated\n")))

(defn story-kind [template]
  (case template
    "gherkin-writer" "gherkin"
    "qa-procedure-writer" "qa-procedure"
    nil))

(defn review-kind [template]
  (case template
    "gherkin-reviewer" "gherkin"
    "qa-procedure-reviewer" "qa-procedure"
    nil))

(defn review-decision! [review-counts story kind]
  (let [key [story kind]
        n (inc (get @review-counts key 0))]
    (swap! review-counts assoc key n)
    (if (= 1 n) "changes-requested" "accepted")))

(defn process-analyst! [root assignment agent stories]
  (doseq [[story text] stories]
    (write-file! (fs/path root "stories" (str story ".md")) text))
  (let [sha (git-commit! root (str "Add analyst stories for " assignment))
        artifacts (str/join "," (map (fn [[story _]]
                                        (str "stories/" story ".md"))
                                      stories))
        dir (fs/path root ".squad" "assignments" assignment)]
    (write-file! (fs/path dir "result-manifest")
                 (str "assignment_id: " assignment "\n"
                      "agent: " agent "\n"
                      "template: analyst\n"
                      "commit: " sha "\n"
                      "artifacts: " artifacts "\n"))
    (write-file! (fs/path dir "accepted-merge")
                 (str "assignment_id: " assignment "\n"
                      "state: merged\n"
                      "commit: " sha "\n"
                      "merge_commit: " sha "\n"))))

(defn process-writer! [root assignment agent template story]
  (let [kind (story-kind template)
        path (case kind
               "gherkin" (fs/path root "features" (str story ".feature"))
               "qa-procedure" (fs/path root "qa" (str story ".md")))
        rel (str/replace (str (.relativize (.toPath (fs/file root)) (.toPath (fs/file path)))) "\\" "/")]
    (write-file! path (str kind " artifact for " story "\n"))
    (let [sha (git-commit! root (str "Add " kind " for " story))]
      (run-script! root "squad_packet.sh" "attach" story kind assignment agent sha rel))))

(defn process-reviewer! [root review-counts assignment agent template story]
  (let [kind (review-kind template)
        decision (review-decision! review-counts story kind)
        review-file (fs/path root ".squad" "reviews" (str assignment ".md"))]
    (write-file! review-file (str decision "\n"))
    (let [sha (git-commit! root (str "Review " kind " for " story))]
      (run-script! root "squad_packet.sh" "review" story kind decision assignment agent sha)
      decision)))

(defn process-implementer! [root assignment agent story]
  (write-file! (fs/path root "src" (str story ".txt"))
               (str "implementation for " story "\n"))
  (let [sha (git-commit! root (str "Implement " story))]
    (run-script! root "squad_packet.sh" "record" story "implementation" assignment agent sha)))

(defn process-cleaner! [root assignment agent story]
  (append-file! (fs/path root "src" (str story ".txt"))
                "cleaned\n")
  (let [sha (git-commit! root (str "Clean " story))]
    (run-script! root "squad_packet.sh" "record" story "cleaner" assignment agent sha)))

(defn process-code-reviewer! [root assignment agent story]
  (let [review-file (fs/path root ".squad" "reviews" (str assignment ".md"))]
    (write-file! review-file "accepted\n")
    (let [sha (git-commit! root (str "Code review " story))]
      (run-script! root "squad_packet.sh" "review" story "code" "accepted" assignment agent sha))))

(defn process-result-agent! [root assignment agent story kind]
  (append-file! (fs/path root "src" (str story ".txt"))
                (str kind "\n"))
  (let [sha (git-commit! root (str "Record " kind " for " story))]
    (run-script! root "squad_packet.sh" "record" story kind assignment agent sha)))

(defn process-architect! [root assignment agent story]
  (let [review-file (fs/path root ".squad" "reviews" (str assignment ".md"))]
    (write-file! review-file "accepted\n")
    (let [sha (git-commit! root (str "Architecture review " story))]
      (run-script! root "squad_packet.sh" "review" story "architecture" "accepted" assignment agent sha)
      "accepted")))

(defn batch-stories [root batch-id]
  (let [manifest (fs/path root ".squad" "batches" batch-id "manifest.tsv")]
    (if (fs/exists? manifest)
      (->> (rest (str/split-lines (slurp (str manifest))))
           (keep #(first (str/split % #"\t")))
           vec)
      [])))

(defn packet-batch-stories [root kind batch-id]
  (let [field (str (str/replace kind "-" "_") "_batch")
        stories-dir (fs/path root ".squad" "stories")]
    (if (fs/exists? stories-dir)
      (->> (fs/list-dir stories-dir)
           (filter fs/directory?)
           (keep (fn [dir]
                   (let [story (fs/file-name dir)
                         packet (file-map (fs/path dir "packet"))]
                     (when (= batch-id (get packet field))
                       story))))
           sort
           vec)
      [])))

(defn ensure-sim-batch! [root kind batch-id]
  (when-not (fs/exists? (fs/path root ".squad" "batches" batch-id))
    (run-script! root "squad_batch.sh" "create" kind batch-id)
    (doseq [story (packet-batch-stories root kind batch-id)]
      (run-script! root "squad_batch.sh" "add" batch-id story
                   "reconstructed" "simulator" "simulator" "0000000000"))))

(defn packets-with-architecture-changes [root]
  (let [stories-dir (fs/path root ".squad" "stories")]
    (if (fs/exists? stories-dir)
      (->> (fs/list-dir stories-dir)
           (filter fs/directory?)
           (keep (fn [dir]
                   (let [story (fs/file-name dir)
                         packet (file-map (fs/path dir "packet"))]
                     (when (= "changes-requested" (get packet "architecture_review"))
                       story))))
           sort
           vec)
      [])))

(defn process-result-batch! [root assignment agent kind]
  (let [batch-id assignment
        _ (ensure-sim-batch! root kind batch-id)
        stories (batch-stories root batch-id)]
    (doseq [story stories]
      (append-file! (fs/path root "src" (str story ".txt"))
                    (str kind "\n")))
    (let [sha (git-commit! root (str "Record " kind " batch " assignment))]
      (run-script! root "squad_batch.sh" "result" batch-id assignment agent sha)
      (doseq [story stories]
        (run-script! root "squad_packet.sh" "record" story kind assignment agent sha)))))

(defn architecture-decision! [review-counts assignment]
  (let [key ["batch" "architecture"]
        n (inc (get @review-counts key 0))]
    (swap! review-counts assoc key n)
    (if (= 1 n) "changes-requested" "accepted")))

(defn process-architecture-batch! [root review-counts assignment agent]
  (let [batch-id assignment
        _ (ensure-sim-batch! root "architecture" batch-id)
        stories (batch-stories root batch-id)
        decision (architecture-decision! review-counts assignment)
        review-file (fs/path root ".squad" "reviews" (str assignment ".md"))]
    (write-file! review-file (str decision "\n"))
    (let [sha (git-commit! root (str "Architecture batch review " assignment))]
      (run-script! root "squad_batch.sh" "result" batch-id assignment agent sha)
      (doseq [story stories]
        (run-script! root "squad_packet.sh" "review" story "architecture" decision assignment agent sha))
      decision)))

(defn process-senior-batch! [root assignment agent]
  (let [stories (packets-with-architecture-changes root)]
    (doseq [story stories]
      (append-file! (fs/path root "src" (str story ".txt"))
                    "senior-implementer\n"))
    (let [sha (git-commit! root (str "Senior implementation " assignment))]
      (doseq [story stories]
        (run-script! root "squad_packet.sh" "record" story "senior-implementer" assignment agent sha)))))

(defn batch-story? [story]
  (= "batch" story))

(def simple-template-processors
  {"analyst" (fn [root _ stories task from _ _]
               (process-analyst! root task from stories))
   "gherkin-writer" (fn [root _ _ task from template story]
                      (process-writer! root task from template story))
   "qa-procedure-writer" (fn [root _ _ task from template story]
                           (process-writer! root task from template story))
   "gherkin-reviewer" (fn [root review-counts _ task from template story]
                        (process-reviewer! root review-counts task from template story))
   "qa-procedure-reviewer" (fn [root review-counts _ task from template story]
                             (process-reviewer! root review-counts task from template story))
   "implementer" (fn [root _ _ task from _ story]
                   (process-implementer! root task from story))
   "cleaner" (fn [root _ _ task from _ story]
               (process-cleaner! root task from story))
   "code-reviewer" (fn [root _ _ task from _ story]
                     (process-code-reviewer! root task from story))
   "senior-implementer" (fn [root _ _ task from _ _]
                          (process-senior-batch! root task from))})

(defn process-result-template! [root task from story kind]
  (if (batch-story? story)
    (process-result-batch! root task from kind)
    (process-result-agent! root task from story kind)))

(defn process-architect-template! [root review-counts task from story]
  (if (batch-story? story)
    (process-architecture-batch! root review-counts task from)
    (process-architect! root task from story)))

(def batch-aware-template-processors
  {"hardener" (fn [root _ _ task from _ story]
                (process-result-template! root task from story "hardener"))
   "qa" (fn [root _ _ task from _ story]
          (process-result-template! root task from story "qa"))
   "architect" (fn [root review-counts _ task from _ story]
                 (process-architect-template! root review-counts task from story))})

(defn template-processor [template]
  (or (simple-template-processors template)
      (batch-aware-template-processors template)))

(defn process-template! [root review-counts stories task from template story]
  (when-let [processor (template-processor template)]
    (processor root review-counts stories task from template story)))

(defn print-review-decision! [story template result]
  (when (#{"accepted" "changes-requested"} result)
    (println "REVIEW_DECISION:" story template (str "decision=" result))))

(defn complete-handoff! [root handoff]
  (let [target (fs/path root ".swarmforge" "handoffs" "inbox" "completed" (fs/file-name handoff))]
    (fs/create-dirs (fs/parent target))
    (fs/move handoff target {:replace-existing true})))

(defn consume-forced-merge-failure? [merge-failures template]
  (let [templates (:templates @merge-failures)]
    (when (and template (= template (first templates)))
      (swap! merge-failures update :templates #(vec (rest %)))
      true)))

(defn record-merge-block! [root assignment from]
  (let [sha (str/trim (:out (sh! root "git" "rev-parse" "--short=10" "HEAD")))
        dir (fs/path root ".squad" "assignments" assignment)]
    (write-file! (fs/path dir "result")
                 (str "assignment_id: " assignment "\n"
                      "state: result_received\n"
                      "from: " from "\n"
                      "commit: " sha "\n"
                      "updated_at: simulated\n"))
    (write-file! (fs/path dir "status")
                 (str "assignment_id: " assignment "\n"
                      "state: merge_blocked\n"
                      "detail: simulated merge failure\n"
                      "updated_at: simulated\n"))
    (write-file! (fs/path dir "blocker")
                 (str "assignment_id: " assignment "\n"
                      "state: blocked\n"))
    (write-file! (fs/path dir "blocker.md")
                 "Simulated merge failure.\n")
    (println "SIM_MERGE_BLOCKED:" assignment (str "agent=" from))))

(defn resolve-merger-assignment! [root assignment]
  (let [dir (fs/path root ".squad" "assignments" assignment)
        metadata (file-map (fs/path dir "metadata"))
        original (get metadata "merge_for")
        sha (str/trim (:out (sh! root "git" "rev-parse" "--short=10" "HEAD")))]
    (when original
      (when (= "merger" (get (file-map (fs/path root ".squad" "assignments" original "metadata")) "template"))
        (resolve-merger-assignment! root original))
      (write-file! (fs/path dir "status")
                   (str "assignment_id: " assignment "\n"
                        "state: merged\n"
                        "detail: simulated merger resolved conflict\n"
                        "updated_at: simulated\n"))
      (write-file! (fs/path root ".squad" "assignments" original "accepted-merge")
                   (str "assignment_id: " original "\n"
                        "state: merged\n"
                        "commit: " (or (get metadata "conflicting_commit") sha) "\n"
                        "merge_commit: " sha "\n"
                        "resolved_by: " assignment "\n"
                        "detail: simulated merger resolved conflict\n"
                        "updated_at: simulated\n"))
      (write-file! (fs/path root ".squad" "assignments" original "status")
                   (str "assignment_id: " original "\n"
                        "state: merged\n"
                        "detail: simulated merger resolved conflict\n"
                        "updated_at: simulated\n"))
      (fs/delete-if-exists (fs/path root ".squad" "assignments" original "blocker"))
      (fs/delete-if-exists (fs/path root ".squad" "assignments" original "blocker.md"))
      (println "SIM_MERGER_RESOLVED:" original (str "merger=" assignment)))))

(defn process-handoff! [root review-counts stories merge-failures {:strs [handoff task from]}]
  (let [metadata (file-map (fs/path root ".squad" "assignments" task "metadata"))
        template (get metadata "template")
        story (get metadata "story_id")]
    (println (str "AGENT_HANDOFF: " from " assignment=" task))
    (if (consume-forced-merge-failure? merge-failures template)
      (do
        (when-not (= "merger" template)
          (print-review-decision! story template
                                  (process-template! root review-counts stories task from template story)))
        (record-merge-block! root task from))
      (if (= "merger" template)
        (resolve-merger-assignment! root task)
        (do
          (print-review-decision! story template
                                  (process-template! root review-counts stories task from template story))
          (mark-assignment! root task "merged"))))
    (complete-handoff! root handoff)))

(defn retire-agent! [root agent]
  (write-roles! root (remove #(= agent (first %)) (role-rows root)))
  (write-file! (fs/path root ".squad" "agents" agent "status")
               (str "state: retired\n"
                    "detail: simulated\n"
                    "updated_at: simulated\n")))

(defn approve-due? [approval-due approval-id tick]
  (<= (get @approval-due approval-id Long/MAX_VALUE) tick))

(defn run-command! [root command]
  (let [parts (str/split command #"\s+")
        [cmd & args] parts
        script (fs/file-name cmd)]
    (apply run-script! root script args)))

(defn story-state [root story]
  (get (file-map (fs/path root ".squad" "stories" story "packet")) "state" "unknown"))

(defn implemented? [root story]
  (contains? (file-map (fs/path root ".squad" "stories" story "packet")) "implementation_sha"))

(defn final-approved? [root story]
  (= "final_approved" (story-state root story)))

(defn unfinished-stories [root stories]
  (remove #(final-approved? root %) stories))

(defn story-summary [root stories]
  (str/join " "
            (map (fn [story]
                   (str (str/replace story "-" "_") "=" (story-state root story)))
                 stories)))

(defn active-agents? [root]
  (boolean (seq (remove #(= "squad-leader" (first %)) (role-rows root)))))

(defn print-block! [tick text]
  (println)
  (println (format "TICK %03d" tick))
  (print text)
  (when-not (str/ends-with? text "\n")
    (println)))

(defn wait-text [out]
  (str out
       (when-not (str/ends-with? out "\n") "\n")
       "SIM_WAIT: no executable simulated event\n"))

(defn record-wait! [pending-wait tick out]
  (let [text (wait-text out)]
    (swap! pending-wait
           (fn [pending]
             (if pending
               (assoc pending
                      :end tick
                      :count (inc (:count pending))
                      :text text)
               {:start tick
                :end tick
                :count 1
                :text text})))))

(defn flush-wait! [pending-wait]
  (when-let [{:keys [start end count text]} @pending-wait]
    (println)
    (if (= start end)
      (println (format "TICK %03d" start))
      (println (format "TICK %03d..%03d" start end)))
    (println "WAIT_TICKS:" count)
    (print text)
    (when-not (str/ends-with? text "\n")
      (println))
    (reset! pending-wait nil)))

(defn print-max-ticks-and-exit! [root pending-wait tick story-count story-ids scheduled keep?]
  (flush-wait! pending-wait)
  (println)
  (println (format "SIM_END tick=%03d state=max_ticks_exceeded stories=%d unfinished=%s active_agents=%s scheduled=%d %s"
                   tick
                   story-count
                   (str/join "," (unfinished-stories root story-ids))
                   (if (active-agents? root) "true" "false")
                   (count @scheduled)
                   (story-summary root story-ids)))
  (when-not keep?
    (fs/delete-tree root))
  (exit! 2 "SIM_FAILED: exceeded max ticks"))

(defn create-approval-request! [root approval-due rng options tick command]
  (let [approval-id (nth (str/split command #"\s+") 2)]
    (run-command! root command)
    (let [latency (choose-int rng (:approval-ticks-range options))
          due (+ tick latency)]
      (swap! approval-due assoc approval-id due)
      (println "APPROVAL_DUE_TICK:" (format "%03d" due)))))

(defn process-user-approval! [root approval-due approval-id tick]
  (if (approve-due? approval-due approval-id tick)
    (do
      (run-script! root "squad_approval.sh" "approve" approval-id "approved-by-simulator")
      (println "USER_APPROVES:" approval-id))
    (println "SIM_WAIT: user approval pending")))

(defn process-spawn! [root counters scheduled tick options rng command-map]
  (let [{:keys [agent due stalled? stall-mode]} (spawn-agent! root counters scheduled tick options rng command-map)]
    (println "AGENT:" agent)
    (if stalled?
      (do
        (println "SIM_STALL:" agent stall-mode)
        (when due
          (println "DUE_TICK:" (format "%03d" due))))
      (println "DUE_TICK:" (format "%03d" due)))))

(defn request-file-map [file]
  (into {}
        (for [line (str/split-lines (slurp (str file)))
              :let [[k v] (str/split line #": " 2)]
              :when (and k v)]
          [k v])))

(defn complete-spawn-request! [request]
  (let [completed (fs/path (fs/parent (fs/parent request)) "completed")]
    (fs/create-dirs completed)
    (fs/move request (fs/path completed (fs/file-name request)) {:replace-existing true})))

(defn process-queued-spawn! [root counters scheduled tick options rng command-map]
  (let [request (fs/path (get command-map "request"))
        data (request-file-map request)
        spawn-map {"template" (get data "template")
                   "assignment" (get data "task_id")}]
    (process-spawn! root counters scheduled tick options rng spawn-map)
    (complete-spawn-request! request)
    (println "SIM_APPLIED: queued spawn processed")))

(defn process-recovery! [root command]
  (let [recovery (:out (run-command! root command))]
    (println "SIM_RECOVERY_RESULT:")
    (print recovery)
    (when-not (str/ends-with? recovery "\n")
      (println))
    (let [fields (command-map recovery)
          agent (get fields "agent")
          assignment (get fields "task_id")]
      (when (and (= "failed_no_work" (get fields "recovery_state"))
                 agent
                 assignment
                 (not= "unknown" assignment))
        (mark-assignment! root assignment "created")
        (retire-agent! root agent)
        (println "SIM_APPLIED: failed assignment requeued for replacement")))))

(def sim-action-handlers
  {"create_approval_request"
   (fn [ctx tick command-map]
     (create-approval-request! (:root ctx) (:approval-due ctx) (:rng ctx) (:options ctx) tick (get command-map "command")))

   "request_user_approval"
   (fn [ctx tick command-map]
     (let [approval-id (get command-map "approval")
           approval-due (:approval-due ctx)]
       ;; Mechanical apply may create the request before the sim sees create_approval_request.
       (when (and approval-id (not (contains? @approval-due approval-id)))
         (let [latency (choose-int (:rng ctx) (:approval-ticks-range (:options ctx)))
               due (+ tick latency)]
           (swap! approval-due assoc approval-id due)
           (println "APPROVAL_DUE_TICK:" (format "%03d" due)
                    "(request already created by mechanical apply)")))
       (process-user-approval! (:root ctx) approval-due approval-id tick)))
   "record_auto_approval"
   (fn [ctx _ command-map]
     (run-command! (:root ctx) (get command-map "command"))
     (println "SIM_APPLIED: auto approval recorded"))

   "record_batch_membership"
   (fn [ctx _ command-map]
     (run-command! (:root ctx) (get command-map "command"))
     (println "SIM_APPLIED: batch membership recorded"))

   "create_assignment"
   (fn [ctx _ command-map]
     (create-assignment! (:root ctx) command-map)
     (println "SIM_APPLIED: assignment created"))

   "request_spawn"
   (fn [ctx tick command-map]
     (process-spawn! (:root ctx) (:counters ctx) (:scheduled ctx) tick (:options ctx) (:rng ctx) command-map))

   "process_handoff"
   (fn [ctx _ command-map]
     (process-handoff! (:root ctx) (:review-counts ctx) (:stories ctx) (:merge-failures ctx) command-map)
     (println "SIM_APPLIED: handoff processed"))

   "retire_agent"
   (fn [ctx _ command-map]
     (retire-agent! (:root ctx) (get command-map "agent"))
     (println "SIM_APPLIED: agent retired"))

   "recover_agent"
   (fn [ctx _ command-map]
     (process-recovery! (:root ctx) (get command-map "command")))

   "wait_for_spawn"
   (fn [ctx tick command-map]
     (process-queued-spawn! (:root ctx) (:counters ctx) (:scheduled ctx)
                            tick (:options ctx) (:rng ctx) command-map))})

(defn apply-sim-action! [ctx tick action command-map]
  (if-let [handler (sim-action-handlers action)]
    (handler ctx tick command-map)
    (exit! 2 (str "SIM_FAILED: unsupported action " action))))

(defn workflow-idle? [root scheduled story-ids action]
  (and (= "wait" action)
       (every? #(final-approved? root %) story-ids)
       (empty? @scheduled)
       (not (active-agents? root))))

(defn print-workflow-idle! [root pending-wait tick story-count story-ids keep?]
  (flush-wait! pending-wait)
  (println)
  (println (format "SIM_END tick=%03d state=workflow_idle stories=%d %s max_transient_slots=3"
                   tick story-count (story-summary root story-ids)))
  (when-not keep?
    (fs/delete-tree root)))

(defn print-sim-start! [run-index story-count options root]
  (println "SIM_START theme=hunt-the-wumpus"
           (str "run=" run-index)
           (str "stories=" story-count)
           "reviewer_policy=reject-once"
           "architect_policy=reject-once"
           "user_policy=approve"
           "max_transient_slots=3"
           (str "handoff_ticks=" (str/join ".." (:handoff-ticks-range options)))
           (str "approval_ticks=" (str/join ".." (:approval-ticks-range options)))
           (str "stall_percent=" (:stall-percent options))
           (str "stall_mode=" (:stall-mode options))
	          (str "stall_active_ticks=" (str/join ".." (:stall-active-ticks-range options)))
           (str "merge_failure_template=" (if (seq (:merge-failure-templates options))
                                            (str/join "," (:merge-failure-templates options))
                                            "none")))
  (println "SIM_ROOT:" (path-str root)))

(defn simulation-context [root stories options rng]
  {:root root
   :approval-due (atom {})
   :rng rng
   :options options
   :counters (atom {})
   :scheduled (atom [])
   :review-counts (atom {})
   :merge-failures (atom {:templates (:merge-failure-templates options)})
   :stories stories
   :pending-wait (atom nil)})

(defn print-and-apply-action! [ctx tick out action m]
  (flush-wait! (:pending-wait ctx))
  (print-block! tick out)
  (apply-sim-action! ctx tick action m))

(defn action-maps [out primary]
  (let [concurrent (concurrent-actions out)]
    (if (seq concurrent)
      concurrent
      [primary])))

(defn run-sim-tick! [ctx tick]
  (set-sim-now! tick)
  (due-events! (:root ctx) (:scheduled ctx) tick)
  (let [out (:out (run-script! (:root ctx) "squad_next.sh" "--apply-mechanical"))
        m (command-map out)
        action (get m "next_action")]
    (if (= "wait" action)
      (record-wait! (:pending-wait ctx) tick out)
      (do
        (flush-wait! (:pending-wait ctx))
        (print-block! tick out)
        (doseq [action-map (action-maps out m)]
          (apply-sim-action! ctx tick (get action-map "next_action") action-map))))
    action))

(defn ensure-sim-within-limit! [ctx tick story-count story-ids]
  (when (> tick (get-in ctx [:options :max-ticks]))
    (print-max-ticks-and-exit! (:root ctx) (:pending-wait ctx) tick story-count story-ids
                               (:scheduled ctx) (get-in ctx [:options :keep?]))))

(defn finish-or-continue-sim! [ctx tick story-count story-ids action]
  (if (workflow-idle? (:root ctx) (:scheduled ctx) story-ids action)
    (print-workflow-idle! (:root ctx) (:pending-wait ctx) tick story-count story-ids
                          (get-in ctx [:options :keep?]))
    :continue))

(defn simulate-htw-run! [run-index options rng]
  (let [root (setup-root!)
        story-count (choose-int rng (:stories-range options))
        stories (story-specs story-count)
        story-ids (mapv first stories)
        ctx (simulation-context root stories options rng)]
    (print-sim-start! run-index story-count options root)
    (loop [tick 0]
      (ensure-sim-within-limit! ctx tick story-count story-ids)
      (let [action (run-sim-tick! ctx tick)]
        (if (= :continue (finish-or-continue-sim! ctx tick story-count story-ids action))
          (recur (inc tick)))))))

(defn simulate-htw! [options]
  (let [base-seed (or (:seed options) (System/currentTimeMillis))]
    (println "SIM_SEED:" base-seed)
    (doseq [run-index (range 1 (inc (:runs options)))]
      (when (> run-index 1)
        (println))
      (simulate-htw-run! run-index options (java.util.Random. (+ base-seed run-index -1))))))

(defn -main [& args]
  (let [scenario (first args)
        options (parse-options (rest args))]
    (when-not (= "htw" scenario)
      (exit! 1 usage-text))
    (simulate-htw! options)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
