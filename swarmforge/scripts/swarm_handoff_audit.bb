;; Audit gate. Loaded into swarm-handoff.

(defn audit-pending-dir []
  (fs/path (state-dir) "audit_pending"))

(defn sender-audit-dir [sender]
  (fs/path (audit-pending-dir) (sha256 sender)))

(defn audit-task-id [headers]
  (or (not-empty (get headers "task_id"))
      (get headers "task")))

(defn audit-file [sender task-id]
  (fs/path (sender-audit-dir sender)
           (str (sha256 task-id) ".edn")))

(defn sender-audit-files [sender]
  (let [dir (sender-audit-dir sender)]
    (if (fs/directory? dir)
      (->> (fs/glob dir "*.edn")
           (filter fs/regular-file?)
           vec)
      [])))

(defn read-audit [path]
  (when (fs/regular-file? path)
    (try
      (edn/read-string (slurp (str path)))
      (catch Exception _ nil))))

(defn write-audit! [path candidate]
  (fs/create-dirs (fs/parent path))
  (let [tmp (fs/create-temp-file {:dir (fs/parent path) :prefix ".audit."})]
    (spit (str tmp) (str (pr-str {:candidate candidate :created-at (timestamp)}) "\n"))
    (fs/move tmp path {:replace-existing true})))

(defn with-audit-lock [f]
  (let [dir (audit-pending-dir)
        path (fs/path dir ".lock")
        options (into-array OpenOption [StandardOpenOption/CREATE
                                        StandardOpenOption/WRITE])]
    (fs/create-dirs dir)
    (with-open [channel (FileChannel/open path options)]
      (.lock channel)
      (f))))

(defn sender-audit-dir-empty? [dir]
  (and (fs/directory? dir)
       (empty? (filter fs/regular-file? (fs/list-dir dir)))))

(defn remove-empty-sender-audit-dir! [sender]
  (let [dir (sender-audit-dir sender)]
    (when (sender-audit-dir-empty? dir)
      (fs/delete-if-exists dir))))

(defn delete-sender-audits! [sender]
  (doseq [path (sender-audit-files sender)]
    (fs/delete-if-exists path))
  (remove-empty-sender-audit-dir! sender))

(defn invocation-fingerprint [draft sender headers]
  {:sender sender
   :task-id (audit-task-id headers)
   :type (get headers "type")
   :recipients (vec (str/split (or (get headers "to") "") #"," -1))
   :priority (get headers "priority")
   :task (get headers "task")
   :commit (get headers "commit")
   :task-base-commit (or (current-task-base) "")
   :non-forwarding (= "true" (get headers "non-forwarding"))
   :draft-fingerprint (sha256 (slurp (str draft)))})

(defn invalidate-changed-invocation-audits! [sender invocation]
  (with-audit-lock
    (fn []
      (doseq [path (sender-audit-files sender)
              :let [candidate (:candidate (read-audit path))]
              :when (not= invocation (select-keys candidate (keys invocation)))]
        (fs/delete-if-exists path))
      (remove-empty-sender-audit-dir! sender))))

(defn audit-candidate [draft sender headers recipients canonical-commit artifacts]
  {:version 1
   :sender sender
   :task-id (audit-task-id headers)
   :type (get headers "type")
   :recipients (vec recipients)
   :priority (get headers "priority")
   :task (get headers "task")
   :commit canonical-commit
   :artifacts (vec artifacts)
   :task-base-commit (or (current-task-base) "")
   :non-forwarding (= "true" (get headers "non-forwarding"))
   :draft-fingerprint (sha256 (slurp (str draft)))})

(defn print-audit-required! [candidate]
  (println "AUDIT_REQUIRED")
  (println "HANDOFF_NOT_QUEUED")
  (println "TASK_ID:" (:task-id candidate))
  (println "COMMIT:" (:commit candidate))
  (println)
  (println "Re-read the complete inbound task payload and every source it references.")
  (println "Compare the completed work product against every requirement and constraint,")
  (println "including interactions, boundaries, failure cases, and negative requirements.")
  (println "Establish requirement-to-evidence traceability appropriate to your role:")
  (println "every requirement must be covered by the work, supported by relevant verification,")
  (println "or identified as a gap.")
  (println "Review the complete committed diff, tests and checks, generated artifacts,")
  (println "and unrelated working-tree changes. Passing tools or clean formatting alone do")
  (println "not establish that the task is complete.")
  (println "Fix every finding, commit the corrections, rerun applicable checks, and repeat")
  (println "this audit against the revised candidate before running the handoff command again."))

(defn increment-audit-count! [task-id]
  (let [script (str (fs/path script-dir "pack_board.sh"))
        result (command (project-root) script "increment-audit"
                        "--root" (str (project-root))
                        "--caller" "handoffd"
                        "--task-id" task-id)]
    (when-not (zero? (:exit result))
      (exit! 1 (str/trim (str (:err result) "\n" (:out result)))))))

(defn latest-durable-audit-n [task-id]
  (let [dir (safe-paths/state-key-path! (fs/path (project-root) ".swarmforge" "board" "audits")
                                        task-id "")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (map fs/file-name)
           (keep #(when-let [[_ n] (re-matches #"([0-9]+)\.md" %)]
                    (Long/parseLong n)))
           (cons 0)
           (apply max))
      0)))

(defn tmp-audit-notes []
  (let [dir (fs/path (or (git-root) ".") "tmp")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter fs/regular-file?)
           (filter #(re-find #"(?i)audit" (fs/file-name %)))
           (sort-by str)
           (map #(str "## " (fs/file-name %) "\n\n" (slurp (str %))
                      (when-not (str/ends-with? (slurp (str %)) "\n") "\n")))
           (str/join "\n"))
      "")))

(defn persist-durable-audit! [candidate]
  (let [task-id (:task-id candidate)
        n (latest-durable-audit-n task-id)
        file (safe-paths/id-path!
              (safe-paths/state-key-path! (fs/path (project-root) ".swarmforge" "board" "audits")
                                          task-id "")
              (str n) ".md")
        notes (tmp-audit-notes)]
    (when (pos? n)
      (fs/create-dirs (fs/parent file))
      (spit (str file)
            (str "# Audit " n "\n\n"
                 "Refused at: " (timestamp) "\n"
                 "Sender: " (:sender candidate) "\n"
                 "Commit: " (:commit candidate) "\n"
                 "Task: " (:task candidate) "\n"
                 "Type: " (:type candidate) "\n"
                 (when (seq (:recipients candidate))
                   (str "Recipients: " (str/join ", " (:recipients candidate)) "\n"))
                 "\n"
                 (when-not (str/blank? notes)
                   (str "## Notes\n\n" notes)))))))

(defn submit-after-audit! [candidate submit!]
  (with-audit-lock
    (fn []
      (let [path (audit-file (:sender candidate) (:task-id candidate))
            previous (:candidate (read-audit path))]
        (if (= candidate previous)
          (let [result (submit!)]
            (delete-sender-audits! (:sender candidate))
            result)
          (do
            (delete-sender-audits! (:sender candidate))
            (write-audit! path candidate)
            (increment-audit-count! (:task-id candidate))
            (persist-durable-audit! candidate)
            (print-audit-required! candidate)
            nil))))))
