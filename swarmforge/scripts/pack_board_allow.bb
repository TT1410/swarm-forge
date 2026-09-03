;; Allow, move, and done. Loaded into pack-board.

(defn rewrite-lane [root line name lane]
  (let [row (card-type/parse-row root line)]
    (if (= (str/lower-case (or name "")) (str/lower-case (or (:name row) "")))
      (card-type/format-row (assoc row :lane lane :updated (timestamp)))
      line)))

(def allow-acts #{"move" "done" "increment-audit" "stop" "create"})

(defn lt-allow-file [root name act]
  (safe-paths/task-path! (fs/path root ".swarmforge" "board" "lt-allow")
                         name (str "-" act)))

(defn lt-pending-file [root name act]
  (safe-paths/task-path! (fs/path root ".swarmforge" "board" "lt-allow-pending")
                         name (str "-" act)))

(defn require-act! [act]
  (require-value! act "act")
  (when-not (contains? allow-acts act)
    (exit! 1 (str "Unknown act: " act))))

(defn caller-task-name [opts]
  (let [root (resolve-root opts)]
    (or (not-empty (task-name opts))
        (when-let [id (not-empty (:task-id opts))]
          (some (fn [line]
                  (let [row (card-type/parse-row root line)]
                    (when (or (= id (:id row)) (= id (:name row)))
                      (:name row))))
                (read-rows (tasks-file root)))))))

(defn request-allow! [opts]
  (let [name (task-name opts)
        act (:act opts)
        root (resolve-root opts)
        file (lt-pending-file root name act)]
    (require-value! name "task name")
    (safe-paths/require-task-name! name)
    (require-act! act)
    (fs/create-dirs (fs/parent file))
    (spit (str file) (str "name: " name "\nact: " act "\n"))))

(defn allow! [opts]
  (let [name (task-name opts)
        act (:act opts)
        root (resolve-root opts)
        pending (lt-pending-file root name act)
        allow (lt-allow-file root name act)]
    (require-value! name "task name")
    (safe-paths/require-task-name! name)
    (require-act! act)
    (fs/create-dirs (fs/parent allow))
    (spit (str allow) (str "name: " name "\nact: " act "\n"))
    (fs/delete-if-exists pending)))

(defn waiting-start? [opts]
  (let [root (resolve-root opts)
        name (task-name opts)
        lane (task-lane opts)
        line (when (and name lane) (find-task (read-rows (tasks-file root)) name))
        row (when line (card-type/parse-row root line))]
    (and row
         (= "waiting" (:lane row))
         (= lane (card-type/starting-lane root (:type row)))
         (not (#{"waiting" "done"} lane)))))

(defn caller-allowed? [opts act]
  (let [caller (:caller opts)
        root (resolve-root opts)
        name (caller-task-name opts)]
    (cond
      (= "handoffd" caller) true
      (and (= "lieutenant" caller)
           (= "move" act)
           (waiting-start? opts))
      true
      (and (= "lieutenant" caller)
           (not (str/blank? name))
           (fs/regular-file? (lt-allow-file root name act)))
      true
      :else false)))

(defn require-caller! [opts act]
  (when-not (caller-allowed? opts act)
    (exit! 1 (str act " requires --caller handoffd or --caller lieutenant with Attention"))))

(defn consume-allow! [opts act]
  (when (= "lieutenant" (:caller opts))
    (when-let [name (not-empty (caller-task-name opts))]
      (fs/delete-if-exists (lt-allow-file (resolve-root opts) name act)))))

(defn pending-pack-clarify? [root]
  (let [dir (fs/path root ".swarmforge" "dashboard" "clarifications" "pending")]
    (boolean
     (and (fs/directory? dir)
          (seq (filter #(and (fs/regular-file? %)
                             (str/ends-with? (fs/file-name %) ".request"))
                       (fs/list-dir dir)))))))

(defn handoff-files-under [dir]
  (if (fs/directory? dir)
    (->> (fs/list-dir dir)
         (mapcat (fn [entry]
                   (cond
                     (and (fs/regular-file? entry)
                          (str/ends-with? (fs/file-name entry) ".handoff"))
                     [entry]
                     (fs/directory? entry)
                     (handoff-files-under entry)
                     :else [])))
         vec)
    []))

(defn header-map [file]
  (into {}
        (for [line (take-while (complement str/blank?)
                               (str/split-lines (slurp (str file))))
              :let [[k v] (str/split line #": " 2)]
              :when (and k v)]
          [k v])))

(defn in-process-dirs [root]
  (into [(fs/path root ".swarmforge" "handoffs" "inbox" "in_process")]
        (keep (fn [cols]
                (when-let [wt (not-empty (nth cols 2 nil))]
                  (fs/path wt ".swarmforge" "handoffs" "inbox" "in_process")))
              (role-rows root))))

(defn in-flight-reverse? [root]
  (boolean
   (some (fn [file]
           (let [h (header-map file)]
             (and (= "git_handoff" (get h "type"))
                  (= "true" (get h "non-forwarding")))))
         (mapcat handoff-files-under (in-process-dirs root)))))

(defn stuck-create-reason [root]
  (cond
    (pending-pack-clarify? root) "pending clarification"
    (in-flight-reverse? root) "in-flight reverse merge"
    :else nil))

(defn recut-of? [root name]
  (= (str/lower-case (or (recut-name root) ""))
     (str/lower-case (or name ""))))

(defn require-create-when-stuck! [opts]
  (let [root (resolve-root opts)
        name (task-name opts)
        reason (stuck-create-reason root)]
    (when reason
      (cond
        (recut-of? root name)
        (consume-recut! root name)

        (and (= "lieutenant" (:caller opts))
             (not (str/blank? name))
             (fs/regular-file? (lt-allow-file root name "create")))
        nil

        :else
        (exit! 1 (str "create refused: " reason))))))

(defn set-lane! [opts lane]
  (let [act (if (= "done" lane) "done" "move")]
    (require-caller! opts act)
    (let [name (task-name opts)
          root (resolve-root opts)
          file (tasks-file root)]
      (require-value! name "task name")
      (safe-paths/require-task-name! name)
      (require-value! lane "lane")
      (when-not (or (#{"waiting" "done"} lane)
                    (some #{lane} (pack-role-names root)))
        (exit! 1 (str "Unknown lane: " lane)))
      (let [before (atom nil)]
        (with-board-lock
          root
          (fn []
            (let [rows (read-rows file)
                  line (find-task rows name)]
              (when-not line
                (exit! 1 (str "Unknown task name: " name)))
              (reset! before (card-type/parse-row root line))
              (write-rows file (mapv #(rewrite-lane root % name lane) rows)))))
        (consume-allow! opts act)
        @before))))

(defn move! [opts]
  (let [root (resolve-root opts)
        name (task-name opts)
        lane (task-lane opts)
        merge-from (:merge-from opts)
        before (set-lane! opts lane)]
    (require-merge-from! root merge-from)
    (when-not (str/blank? merge-from)
      (update-task-doc-merge-from! root name merge-from))
    (when (and before
               (= "waiting" (:lane before))
               (not (#{"waiting" "done"} lane)))
      (queue-start-note! root name lane (:id before) (task-text root name)))
    (when (and before
               (= "waiting" lane)
               (not (#{"waiting" "done"} (:lane before))))
      (halt-live-card! root before))))

(defn done! [opts]
  (set-lane! opts "done"))

(defn parse-batch-task-ids! [value]
  (let [parsed (try
                 (edn/read-string (or value ""))
                 (catch Exception _ nil))]
    (when-not (and (vector? parsed)
                   (next parsed)
                   (every? #(and (string? %) (safe-paths/state-key? %)) parsed)
                   (= parsed (vec (distinct parsed))))
      (exit! 1 "--task-ids must be an EDN vector of at least two distinct task IDs"))
    parsed))

(defn row-key [root line]
  (let [row (card-type/parse-row root line)]
    (or (not-empty (:id row)) (:name row))))

(defn transition-batch! [opts]
  (let [lane (:lane opts)
        act (if (= "done" lane) "done" "move")
        root (resolve-root opts)
        file (tasks-file root)
        ids (parse-batch-task-ids! (:task-ids opts))
        wanted (set ids)]
    (require-caller! opts act)
    (require-value! lane "lane")
    (when-not (or (#{"waiting" "done"} lane)
                  (some #{lane} (pack-role-names root)))
      (exit! 1 (str "Unknown lane: " lane)))
    (with-board-lock
      root
      (fn []
        (let [rows (read-rows file)
              present (set (map #(row-key root %) rows))
              missing (remove present ids)]
          (when (seq missing)
            (exit! 1 (str "Unknown task IDs: " (pr-str (vec missing)))))
          (write-rows file
                      (mapv (fn [line]
                              (if (contains? wanted (row-key root line))
                                (let [row (card-type/parse-row root line)]
                                  (card-type/format-row
                                   (assoc row :lane lane :updated (timestamp))))
                                line))
                            rows)))))))
