#!/usr/bin/env bb

(ns squad-simulator
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def script-dir (fs/parent *file*))
(def usage-text "Usage: squad_simulator.sh htw [--keep]")

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
  (let [result (apply process/sh (concat [{:dir (path-str dir)
                                           :continue true
                                           :extra-env {"SWARMFORGE_PROJECT_ROOT" (path-str dir)}}]
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
                      "approval_required implementation false\n"))
    (write-file! (fs/path root "theme.md") "Implement a faithful Hunt the Wumpus.\n")
    (run-script! root "squad_theme.sh" "create" "hunt-the-wumpus" "theme.md")
    root))

(defn assignment-file [root assignment-id]
  (fs/path root ".squad" "assignments" assignment-id "assignment.md"))

(defn create-assignment! [root {:strs [theme story template assignment]}]
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
                      "state: assignment_created\n"
                      "detail: simulated\n"
                      "updated_at: simulated\n"))))

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

(defn spawn-agent! [root counters scheduled tick {:strs [template assignment]}]
  (let [agent (next-agent-id counters template)
        session (str "swarmforge-" agent)
        rows (role-rows root)
        worktree (path-str (fs/path root ".worktrees" agent))
        due (+ tick 3)
        agent-dir (fs/path root ".squad" "agents" agent)]
    (write-roles! root (conj rows [agent agent worktree session (str/replace agent "-" " ") "codex" "task"]))
    (write-file! (fs/path agent-dir "metadata")
                 (str "agent: " agent "\n"
                      "template: " template "\n"
                      "task_id: " assignment "\n"
                      "session: " session "\n"))
    (write-file! (fs/path agent-dir "status")
                 (str "state: running\n"
                      "detail: simulated\n"
                      "updated_at: simulated\n"))
    (swap! scheduled conj {:due due :agent agent :assignment assignment})
    due))

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
                      "commit: " sha "\n\n"
                      "simulated result\n"))))

(defn due-handoffs! [root scheduled tick]
  (let [due (filter #(= tick (:due %)) @scheduled)]
    (swap! scheduled #(vec (remove (fn [event] (= tick (:due event))) %)))
    (doseq [event due]
      (create-handoff! root tick event))))

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

(defn process-analyst! [root assignment agent]
  (doseq [[story text] [["cave-topology" "Story: cave topology and hazards.\n"]
                       ["player-actions" "Story: player actions and turns.\n"]]]
    (write-file! (fs/path root "stories" (str story ".md")) text)
    (git-commit! root (str "Add story " story))
    (run-script! root "squad_theme.sh" "story" "hunt-the-wumpus" story (str "stories/" story ".md"))
    (let [sha (str/trim (:out (sh! root "git" "rev-parse" "--short=10" "HEAD")))]
      (run-script! root "squad_packet.sh" "create" "hunt-the-wumpus" story assignment agent sha))))

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

(defn base-batch-id [assignment]
  (str/replace assignment #"-r[0-9]+$" ""))

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
  (let [batch-id (base-batch-id assignment)
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
  (let [batch-id (base-batch-id assignment)
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
                    "senior-implementor\n"))
    (let [sha (git-commit! root (str "Senior implementation " assignment))]
      (doseq [story stories]
        (run-script! root "squad_packet.sh" "record" story "senior-implementor" assignment agent sha)))))

(defn process-handoff! [root review-counts {:strs [handoff task from]}]
  (let [metadata (file-map (fs/path root ".squad" "assignments" task "metadata"))
        template (get metadata "template")
        story (get metadata "story_id")]
    (println (str "AGENT_HANDOFF: " from " assignment=" task))
	    (let [result (case template
	                   "analyst" (process-analyst! root task from)
	                   "gherkin-writer" (process-writer! root task from template story)
	                   "qa-procedure-writer" (process-writer! root task from template story)
	                   "gherkin-reviewer" (process-reviewer! root review-counts task from template story)
	                   "qa-procedure-reviewer" (process-reviewer! root review-counts task from template story)
		                   "implementer" (process-implementer! root task from story)
		                   "cleaner" (process-cleaner! root task from story)
		                   "code-reviewer" (process-code-reviewer! root task from story)
		                   "hardener" (if (= "batch" story)
		                                (process-result-batch! root task from "hardener")
		                                (process-result-agent! root task from story "hardener"))
		                   "qa" (if (= "batch" story)
		                          (process-result-batch! root task from "qa")
		                          (process-result-agent! root task from story "qa"))
		                   "architect" (if (= "batch" story)
		                                 (process-architecture-batch! root review-counts task from)
		                                 (process-architect! root task from story))
		                   "senior-implementor" (process-senior-batch! root task from)
		                   nil)]
      (when (#{"accepted" "changes-requested"} result)
        (println "REVIEW_DECISION:" story template (str "decision=" result))))
    (mark-assignment! root task "merged")
    (let [target (fs/path root ".swarmforge" "handoffs" "inbox" "completed" (fs/file-name handoff))]
      (fs/create-dirs (fs/parent target))
      (fs/move handoff target {:replace-existing true}))))

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

(defn active-agents? [root]
  (boolean (seq (remove #(= "squad-leader" (first %)) (role-rows root)))))

(defn print-block! [tick text]
  (println)
  (println (format "TICK %03d" tick))
  (print text)
  (when-not (str/ends-with? text "\n")
    (println)))

(defn simulate-htw! [keep?]
  (let [root (setup-root!)
        scheduled (atom [])
        approval-due (atom {})
        counters (atom {})
        review-counts (atom {})]
    (println "SIM_START theme=hunt-the-wumpus stories=2 reviewer_policy=reject-once user_policy=approve max_transient_slots=3 handoff_latency_ticks=3 approval_latency_ticks=5")
    (println "SIM_ROOT:" (path-str root))
    (loop [tick 0]
      (when (> tick 250)
        (exit! 2 "SIM_FAILED: exceeded 250 ticks"))
      (due-handoffs! root scheduled tick)
      (let [out (:out (run-script! root "squad_next.sh"))
            m (command-map out)
            action (get m "next_action")]
        (print-block! tick out)
        (case action
          "create_approval_request"
          (let [approval-id (nth (str/split (get m "command") #"\s+") 2)]
            (run-command! root (get m "command"))
            (swap! approval-due assoc approval-id (+ tick 5))
            (println "APPROVAL_DUE_TICK:" (format "%03d" (+ tick 5))))

          "request_user_approval"
          (let [approval-id (get m "approval")]
            (if (approve-due? approval-due approval-id tick)
              (do
                (run-script! root "squad_approval.sh" "approve" approval-id "approved-by-simulator")
                (println "USER_APPROVES:" approval-id))
              (println "SIM_WAIT: user approval pending")))

	          "record_auto_approval"
	          (do
	            (run-command! root (get m "command"))
	            (println "SIM_APPLIED: auto approval recorded"))

	          "record_batch_membership"
	          (do
	            (run-command! root (get m "command"))
	            (println "SIM_APPLIED: batch membership recorded"))
	
	          "create_assignment"
          (do
            (create-assignment! root m)
            (println "SIM_APPLIED: assignment created"))

          "request_spawn"
          (let [due (spawn-agent! root counters scheduled tick m)]
            (println "DUE_TICK:" (format "%03d" due)))

          "process_handoff"
          (do
            (process-handoff! root review-counts m)
            (println "SIM_APPLIED: handoff processed"))

          "retire_agent"
          (do
            (retire-agent! root (get m "agent"))
            (println "SIM_APPLIED: agent retired"))

          "wait"
          (println "SIM_WAIT: no executable simulated event")

          "wait_for_spawn"
          (println "SIM_WAIT: spawn daemon not used by simulator")

          (exit! 2 (str "SIM_FAILED: unsupported action " action)))
	        (if (and (= "wait" action)
	                 (final-approved? root "cave-topology")
	                 (final-approved? root "player-actions")
	                 (empty? @scheduled)
	                 (not (active-agents? root)))
          (do
            (println)
            (println (format "SIM_END tick=%03d state=workflow_idle stories=2 cave_topology=%s player_actions=%s max_transient_slots=3"
                             tick
                             (story-state root "cave-topology")
                             (story-state root "player-actions")))
            (when-not keep?
              (fs/delete-tree root)))
          (recur (inc tick)))))))

(defn -main [& args]
  (let [scenario (first args)
        keep? (some #{"--keep"} args)]
    (when-not (= "htw" scenario)
      (exit! 1 usage-text))
    (simulate-htw! keep?)))

(apply -main *command-line-args*)
