;; Allow, move, and done. Loaded into pack-board.

(defn rewrite-lane [line name lane]
  (let [row (card-type/parse-row line)]
    (if (= (str/lower-case (or name "")) (str/lower-case (or (:name row) "")))
      (card-type/format-row (assoc row :lane lane :updated (timestamp)))
      line)))

(def allow-acts #{"move" "done" "increment-audit" "stop"})

(defn lt-allow-file [root name act]
  (fs/path root ".swarmforge" "board" "lt-allow" (str name "-" act)))

(defn lt-pending-file [root name act]
  (fs/path root ".swarmforge" "board" "lt-allow-pending" (str name "-" act)))

(defn require-act! [act]
  (require-value! act "act")
  (when-not (contains? allow-acts act)
    (exit! 1 (str "Unknown act: " act))))

(defn caller-task-name [opts]
  (or (not-empty (task-name opts))
      (when-let [id (not-empty (:task-id opts))]
        (some (fn [line]
                (let [row (card-type/parse-row line)]
                  (when (or (= id (:id row)) (= id (:name row)))
                    (:name row))))
              (read-rows (tasks-file (resolve-root opts)))))))

(defn request-allow! [opts]
  (let [name (task-name opts)
        act (:act opts)
        root (resolve-root opts)
        file (lt-pending-file root name act)]
    (require-value! name "task name")
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
    (require-act! act)
    (fs/create-dirs (fs/parent allow))
    (spit (str allow) (str "name: " name "\nact: " act "\n"))
    (fs/delete-if-exists pending)))

(defn waiting-start? [opts]
  (let [root (resolve-root opts)
        name (task-name opts)
        lane (task-lane opts)
        line (when (and name lane) (find-task (read-rows (tasks-file root)) name))
        row (when line (card-type/parse-row line))]
    (and row
         (= "waiting" (:lane row))
         (= lane (card-type/starting-lane (:type row)))
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

(defn set-lane! [opts lane]
  (let [act (if (= "done" lane) "done" "move")]
    (require-caller! opts act)
    (let [name (task-name opts)
          root (resolve-root opts)
          file (tasks-file root)]
      (require-value! name "task name")
      (require-value! lane "lane")
      (let [before (atom nil)]
        (with-board-lock
          root
          (fn []
            (let [rows (read-rows file)
                  line (find-task rows name)]
              (when-not line
                (exit! 1 (str "Unknown task name: " name)))
              (reset! before (card-type/parse-row line))
              (write-rows file (mapv #(rewrite-lane % name lane) rows)))))
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
