;; Halt a live card: drop mail, reset worktree, stop. Loaded into pack-board.

(defn handoff-headers [file]
  (into {}
        (for [line (take-while (complement str/blank?)
                               (str/split-lines (slurp (str file))))
              :let [[k v] (str/split line #": " 2)]
              :when (and k v)]
          [k v])))

(defn handoff-for-card? [file name task-id]
  (let [h (handoff-headers file)
        task (get h "task")
        id (get h "task_id")]
    (or (= name task)
        (and (not (str/blank? task-id))
             (or (= task-id id) (= task-id task))))))

(defn drop-card-handoffs-in! [dir name task-id]
  (when (fs/directory? dir)
    (doseq [file (->> (fs/list-dir dir)
                      (filter #(and (fs/regular-file? %)
                                    (str/ends-with? (fs/file-name %) ".handoff"))))]
      (when (handoff-for-card? file name task-id)
        (fs/delete-if-exists file)))
    (doseq [batch (->> (fs/list-dir dir)
                       (filter #(and (fs/directory? %)
                                     (str/starts-with? (fs/file-name %) "batch_"))))]
      (drop-card-handoffs-in! batch name task-id)
      (when (empty? (filter #(str/ends-with? (fs/file-name %) ".handoff")
                            (if (fs/directory? batch) (fs/list-dir batch) [])))
        (fs/delete-tree batch)))))

(defn worktree-for-lane [root lane]
  (some (fn [cols]
          (when (= lane (first cols))
            (not-empty (nth cols 2 nil))))
        (role-rows root)))

(defn tell-agent-stopped! [root role]
  (inject-pane! root role "The lieutenant stopped this card. Stop executing it."))

(defn write-reset-failed-notify! [root name lane message]
  (let [dest (or (forge-root root) (str root))
        dir (fs/path dest ".swarmforge" "notify")
        stamp (str/replace (str (java.time.Instant/now)) #"[^0-9A-Za-z]" "")
        file (fs/path dir (str stamp "-reset-failed.notify"))]
    (fs/create-dirs dir)
    (spit (str file)
          (str "event: reset-failed\n"
               "task: " name "\n"
               "lane: " lane "\n"
               "error: " message "\n"))))

(defn report-reset-failure! [root name lane message]
  (write-reset-failed-notify! root name lane message)
  (let [text (str "git reset failed for " name " on " lane ": " message)]
    (if-let [forge (forge-root root)]
      (inject-pane! forge "lieutenant" text)
      (inject-pane! root (or (master-role-name root) lane) text))))

(defn drop-card-pending-approval! [root name task-id]
  (drop-card-handoffs-in! (fs/path root ".swarmforge" "handoffs" "pending_approval")
                          name task-id))

(defn audit-matches-card? [path name task-id]
  (try
    (let [cand (:candidate (edn/read-string (slurp (str path))))
          id (or (:task-id cand) (:task cand))]
      (or (= name id) (= task-id id) (= name (:task cand))))
    (catch Exception _
      false)))

(defn drop-card-audits! [root name task-id]
  (let [dir (fs/path root ".swarmforge" "handoffs" "audit_pending")]
    (when (fs/directory? dir)
      (doseq [file (->> (concat (fs/glob dir "*.edn")
                                (fs/glob dir "**/*.edn"))
                        (filter fs/regular-file?)
                        distinct)]
        (when (audit-matches-card? file name task-id)
          (fs/delete-if-exists file))))))

(defn in-process-handoffs [in-process name task-id]
  (->> (concat
        (filter #(and (fs/regular-file? %)
                      (str/ends-with? (fs/file-name %) ".handoff"))
                (fs/list-dir in-process))
        (mapcat (fn [batch]
                  (if (fs/directory? batch)
                    (filter #(str/ends-with? (fs/file-name %) ".handoff")
                            (fs/list-dir batch))
                    []))
                (filter #(and (fs/directory? %)
                              (str/starts-with? (fs/file-name %) "batch_"))
                        (fs/list-dir in-process))))
       (filter #(handoff-for-card? % name task-id))))

(defn git-reset-failure [wt base name]
  (cond
    (str/blank? base) (str "no task_base_commit for " name)
    :else
    (let [result (command wt "git" "reset" "--hard" base)]
      (when-not (zero? (:exit result))
        (str "git reset --hard " base " failed: "
             (str/trim (str (:err result) " " (:out result))))))))

(defn reset-in-process! [root lane name task-id]
  (when-let [wt (worktree-for-lane root lane)]
    (let [in-process (fs/path wt ".swarmforge" "handoffs" "inbox" "in_process")]
      (when (fs/directory? in-process)
        (let [files (in-process-handoffs in-process name task-id)
              base (some #(not-empty (get (handoff-headers %) "task_base_commit")) files)
              msg (when (seq files) (git-reset-failure wt base name))]
          (drop-card-handoffs-in! in-process name task-id)
          msg)))))

(defn halt-live-card! [root before]
  (when (and before (not (#{"waiting" "done"} (:lane before))))
    (let [name (:name before)
          lane (:lane before)
          task-id (:id before)]
      (doseq [wt (cons (str root)
                       (keep #(nth % 2 nil) (role-rows root)))]
        (drop-card-handoffs-in! (fs/path wt ".swarmforge" "handoffs" "outbox") name task-id)
        (drop-card-handoffs-in! (fs/path wt ".swarmforge" "handoffs" "inbox" "new") name task-id))
      (drop-card-pending-approval! root name task-id)
      (drop-card-audits! root name task-id)
      (when-let [msg (reset-in-process! root lane name task-id)]
        (report-reset-failure! root name lane msg))
      (tell-agent-stopped! root lane))))

(defn stop! [opts]
  (require-caller! opts "stop")
  (let [name (task-name opts)
        root (resolve-root opts)
        file (tasks-file root)]
    (require-value! name "task name")
    (let [row (atom nil)]
      (with-board-lock
        root
        (fn []
          (let [rows (read-rows file)
                line (find-task rows name)]
            (when-not line
              (exit! 1 (str "Unknown task name: " name)))
            (reset! row (card-type/parse-row line))
            (write-rows file (mapv #(rewrite-lane % name "waiting") rows)))))
      (halt-live-card! root @row)
      (consume-allow! opts "stop"))))
