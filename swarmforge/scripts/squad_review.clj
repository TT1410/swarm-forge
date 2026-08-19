#!/usr/bin/env bb

(ns squad-review
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-config :as cfg]
            [clojure.string :as str]))

(def usage-text
  (str "Usage: squad_review.sh <assignment-id> <accepted|changes-requested> <review-file> [artifacts]\n\n"
       "Queues a deterministic review result handoff to squad-leader."))

(def valid-id #"[A-Za-z0-9][A-Za-z0-9._-]*")
(def valid-decisions #{"accepted" "changes-requested"})
(def reviewer-templates #{"code-reviewer" "architect"})

(defn exit! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn project-root []
  (or (cfg/project-root)
      (exit! 1 "Cannot find SwarmForge project root")))

(defn script-dir []
  (fs/parent *file*))

(defn sh [& args]
  (apply process/sh (concat [{:continue true}] args)))

(defn sh-out [& args]
  (str/trim (:out (apply sh args))))

(defn validate-id! [kind value]
  (when-not (re-matches valid-id value)
    (exit! 2 (str kind " must use letters, digits, dots, underscores, and hyphens.")))
  (when (or (str/includes? value "/") (str/includes? value "\\"))
    (exit! 2 (str kind " may not contain path separators."))))

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(defn role []
  (or (not-empty (System/getenv "SWARMFORGE_ROLE"))
      (exit! 1 "Set SWARMFORGE_ROLE.")))

(defn assignment-dir [root assignment-id]
  (fs/path root ".squad" "assignments" assignment-id))

(defn source-file! [path]
  (let [file (fs/path path)
        file (if (fs/absolute? file) file (fs/path (fs/cwd) file))]
    (when-not (fs/regular-file? file)
      (exit! 1 (str "Review file not found: " file)))
    file))

(defn relative-to-root [root file]
  (let [root-path (.normalize (.toAbsolutePath (fs/path root)))
        file-path (.normalize (.toAbsolutePath (fs/path file)))]
    (when (.startsWith file-path root-path)
      (str/replace (str (.relativize root-path file-path)) "\\" "/"))))

(defn durable-review-relative? [relative]
  (and relative
       (str/ends-with? relative ".md")
       (or (str/starts-with? relative "reviews/")
           (str/starts-with? relative ".squad/reviews/"))))

(defn ensure-review-file! [root file]
  (let [worktree (not-empty (System/getenv "SWARMFORGE_WORKTREE"))
        relative (or (when worktree
                       (let [rel (relative-to-root worktree file)]
                         (when (durable-review-relative? rel) rel)))
                     (relative-to-root root file))]
    (when-not (durable-review-relative? relative)
      (exit! 2 "Review file must be a durable markdown artifact under reviews/ (or legacy .squad/reviews/)."))
    relative))
(defn ensure-assignment! [root assignment-id sender]
  (let [dir (assignment-dir root assignment-id)
        metadata (fs/path dir "metadata")
        template (read-value metadata "template")]
    (when-not (fs/directory? dir)
      (exit! 1 (str "Unknown assignment: " assignment-id)))
    (when-not (contains? reviewer-templates template)
      (exit! 2 (str "Assignment template is not a reviewer role: " template)))
    (let [agent-metadata (fs/path root ".squad" "agents" sender "metadata")]
      (when (fs/exists? agent-metadata)
        (let [task-id (read-value agent-metadata "task_id")]
          (when-not (= assignment-id task-id)
            (exit! 2 (str "Current agent is assigned to " task-id ", not " assignment-id))))))
    template))

(defn ensure-decision! [decision]
  (when-not (contains? valid-decisions decision)
    (exit! 2 "Review decision must be accepted or changes-requested.")))

(defn head-commit! []
  (let [result (sh "git" "rev-parse" "--short=10" "HEAD")]
    (when-not (zero? (:exit result))
      (exit! 2 "Could not determine current commit; commit review artifacts before handoff."))
    (let [commit (str/trim (:out result))]
      (when-not (re-matches #"[0-9a-fA-F]{10}" commit)
        (exit! 2 "Current HEAD did not produce a 10-character commit."))
      commit)))

(defn ensure-clean-head! []
  (let [dirty (sh-out "git" "status" "--porcelain" "--untracked-files=no")]
    (when-not (str/blank? dirty)
      (exit! 2 "Commit tracked changes before review handoff."))))

(defn handoff-state-dir []
  (fs/path (System/getProperty "user.dir") ".swarmforge" "handoffs"))

(defn write-draft! [{:keys [assignment-id template decision review-path artifacts commit]}]
  (let [tmp-dir (fs/path (handoff-state-dir) "outbox" "tmp")
        draft (fs/create-temp-file {:dir (doto tmp-dir fs/create-dirs)
                                    :prefix "review-"
                                    :suffix ".handoff"})]
    (spit (str draft)
          (str "type: git_handoff\n"
               "to: squad-leader\n"
               "priority: 50\n"
               "task: " assignment-id "\n"
               "commit: " commit "\n"
               "assignment: " assignment-id "\n"
               "template: " template "\n"
               "artifacts: " (if (str/blank? artifacts) review-path artifacts) "\n"
               "review_decision: " decision "\n"))
    draft))

(defn queue-handoff! [draft]
  (let [script (fs/path (script-dir) "swarm_handoff.sh")
        result (sh (str script) (str draft))]
    (when-not (zero? (:exit result))
      (exit! (:exit result) (str/trim (:err result))))
    (print (:out result))))

(defn submit-review! [assignment-id decision review-file artifacts]
  (validate-id! "Assignment id" assignment-id)
  (ensure-decision! decision)
  (let [root (fs/absolutize (project-root))
        sender (role)
        file (source-file! review-file)
        review-path (ensure-review-file! root file)
        template (ensure-assignment! root assignment-id sender)
        commit (head-commit!)]
    (ensure-clean-head!)
    (queue-handoff! (write-draft! {:assignment-id assignment-id
                                   :template template
                                   :decision decision
                                   :review-path review-path
                                   :artifacts artifacts
                                   :commit commit}))))

(defn -main [& args]
  (when-not (<= 3 (count args) 4)
    (exit! 1 usage-text))
  (let [[assignment-id decision review-file artifacts] args]
    (submit-review! assignment-id decision review-file artifacts)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
