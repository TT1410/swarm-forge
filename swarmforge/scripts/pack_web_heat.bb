;; Role heat and work-in-flight. Loaded into pack-web.

(defn listed [dir pred]
  (if (fs/directory? dir)
    (->> (fs/list-dir dir)
         (filter pred)
         (sort-by #(fs/file-name %))
         vec)
    []))

(defn handoff-files [dir]
  (listed dir #(and (fs/regular-file? %)
                    (str/ends-with? (fs/file-name %) ".handoff"))))

(defn batch-dirs [dir]
  (listed dir #(and (fs/directory? %)
                    (str/starts-with? (fs/file-name %) "batch_"))))

(defn in-process-files [dir]
  (into (handoff-files dir)
        (mapcat handoff-files (batch-dirs dir))))

(defn iso-mtime [path]
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (.toInstant (fs/last-modified-time path))))

(defn work-entry [role path]
  (let [headers (:headers (parse-message path))]
    {:task (get headers "task")
     :role role
     :updated_at (or (not-empty (get headers "dequeued_at"))
                     (iso-mtime path))}))

(defn in-process-dir [worktree]
  (fs/path worktree ".swarmforge" "handoffs" "inbox" "in_process"))

(defn session-alive? [socket session]
  (boolean
   (when (and socket session)
     (zero? (:exit (sh "tmux" "-S" socket "has-session" "-t" session))))))

(defn role-queue-state [alive? busy?]
  (cond
    (not alive?) "no_session"
    busy? "live"
    :else "idle"))

(defn in-process-for-row [row]
  (let [worktree (nth row 2 nil)]
    (if (str/blank? worktree)
      []
      (in-process-files (in-process-dir worktree)))))

(defn session-name [row]
  (let [role (first row)
        session (nth row 3 nil)]
    (if (str/blank? session)
      (str "swarmforge-" role)
      session)))

(defn pane-target [row]
  (let [session (session-name row)
        window (nth row 4 nil)]
    (if (str/blank? window)
      session
      (str session ":" window ".0"))))

(defn backend-name [row]
  (str/lower-case (or (nth row 5 nil) "")))

(defn drop-mail-lines [text]
  (->> (str/split-lines (or text ""))
       (remove mail-banner?)
       (str/join "\n")))

(defn pane-sample [text _backend]
  (drop-mail-lines text))

(defn bag-diff [a b]
  (let [ks (set (concat (keys a) (keys b)))]
    (reduce (fn [n k]
              (+ n (Math/abs (- (long (get a k 0)) (long (get b k 0))))))
            0
            ks)))

(defn heat-from-count [n]
  (min 6 (long n)))

(defn spinner-line? [line]
  (let [n (str/lower-case (fold-apostrophe (str/trim (or line ""))))]
    (boolean
     (or (re-find #"waiting for response" n)
         (re-find #"\bworking\b.*\besc to interrupt\b" n)
         (re-find #"\besc to interrupt\b.*\d+s\b" n)))))

(defn without-spinner-lines [bag]
  (into {} (remove (fn [[line _]] (spinner-line? line)) bag)))

(defn record-heat! [key text backend]
  (let [tail (last-n-lines (pane-sample text backend) 20)
        bag (frequencies tail)
        prev (get @pane-heat key)
        n (if (:bag prev) (bag-diff (:bag prev) bag) 0)
        work-n (if (:bag prev)
                 (bag-diff (without-spinner-lines (:bag prev))
                           (without-spinner-lines bag))
                 0)
        heat (cond
               (pos? work-n) (heat-from-count work-n)
               (pos? n) 1
               :else 0)]
    (swap! pane-heat assoc key {:bag bag :heat heat})
    heat))

(defn role-heat [root role alive? text backend]
  (if alive?
    (record-heat! (pane-cache-key root role) text backend)
    0))

(defn cards-in-lane [all-tasks lane]
  (filterv #(= lane (:lane %)) all-tasks))

(defn queue-row [role names batch-names busy? alive? activity updated]
  {:task (or (first names) "")
   :tasks (vec names)
   :batch_tasks (vec batch-names)
   :role role
   :state (role-queue-state alive? busy?)
   :updated_at (or updated "")
   :activity activity})

(defn in-process-task-names [files]
  (->> files
       (map #(get-in (parse-message %) [:headers "task"]))
       (remove str/blank?)
       distinct
       vec))

(defn work-task-names [files cards]
  (let [from-files (in-process-task-names files)
        from-cards (mapv :name cards)]
    (vec (if (seq from-files) from-files from-cards))))

(defn in-process-batch-task-names [row]
  (let [worktree (nth row 2 nil)
        dir (when-not (str/blank? worktree) (in-process-dir worktree))
        batches (when dir (batch-dirs dir))]
    (if (= 1 (count batches))
      (in-process-task-names (handoff-files (first batches)))
      [])))

(defn role-heats [root]
  (let [socket (tmux-socket root)]
    (into {}
          (for [row (role-rows root)
                :let [role (first row)
                      alive? (or (session-alive? socket (session-name row))
                                 (some? *pane-text*))
                      text (live-pane-text root role)]]
            [role (role-heat root role alive? text (backend-name row))]))))

(defn work-row-for-role [root socket row all-tasks heats]
  (let [role (first row)
        files (in-process-for-row row)
        path (first files)
        from-file (when path (work-entry role path))
        cards (cards-in-lane all-tasks role)
        card (first cards)
        busy? (boolean (or path card))
        alive? (session-alive? socket (session-name row))
        names (work-task-names files cards)
        batch-names (in-process-batch-task-names row)]
    (queue-row role names batch-names busy? alive?
               (get heats role 0)
               (or (:updated_at from-file) (:updated_at card) ""))))

(defn work-in-flight
  ([root] (work-in-flight root (role-heats root)))
  ([root heats]
   (let [socket (tmux-socket root)
         all-tasks (tasks root)]
     (mapv #(work-row-for-role root socket % all-tasks heats) (role-rows root)))))

(defn executing-task? [root task]
  (let [role (:lane task)]
    (boolean
     (and (not (#{"waiting" "done"} role))
          (when-let [row (role-row root role)]
            (contains? (set (in-process-task-names (in-process-for-row row)))
                       (:name task)))))))

(defn task-with-heat [root heats task]
  (if (executing-task? root task)
    (assoc task :activity (get heats (:lane task) 0))
    task))

(defn tasks-with-heat [root heats]
  (mapv #(task-with-heat root heats %) (tasks root)))
