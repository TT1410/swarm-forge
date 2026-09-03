;; Pending approvals and board-allow reads. Loaded into pack-web.

(defn pending-dir [root]
  (fs/path root ".swarmforge" "handoffs" "pending_approval"))

(defn pending-files [root]
  (let [dir (pending-dir root)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".handoff")))
           (sort-by #(fs/file-name %)))
      [])))

(defn approval-id [path]
  (str/replace (fs/file-name path) #"\.handoff$" ""))

(defn reviews-file [root id]
  (safe-paths/id-path! (pending-dir root) id ".reviews.json"))

(defn read-reviews [root id]
  (let [file (reviews-file root id)]
    (if (fs/regular-file? file)
      (try
        (let [parsed (json/parse-string (slurp (str file)))]
          (if (map? parsed) parsed {}))
        (catch Exception _ {}))
      {})))

(defn write-reviews! [root id reviews]
  (let [file (reviews-file root id)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) (json/generate-string reviews))))

(defn drop-reviews! [root id]
  (fs/delete-if-exists (reviews-file root id)))

(defn task-reviews-file [root task-id]
  (fs/path (safe-paths/state-key-path! (fs/path root ".swarmforge" "rejected-tasks")
                                       task-id "")
           "reviews.json"))

(defn read-task-reviews [root task-id]
  (let [file (task-reviews-file root task-id)]
    (if (and (not (str/blank? task-id)) (fs/regular-file? file))
      (try
        (let [parsed (json/parse-string (slurp (str file)))]
          (if (map? parsed) parsed {}))
        (catch Exception _ {}))
      {})))

(defn write-task-reviews! [root task-id store]
  (when-not (str/blank? task-id)
    (let [file (task-reviews-file root task-id)]
      (fs/create-dirs (fs/parent file))
      (spit (str file) (json/generate-string store)))))

(defn drop-task-reviews! [root task-id]
  (when-not (str/blank? task-id)
    (fs/delete-if-exists (task-reviews-file root task-id))))

(defn iso-now []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT (java.time.Instant/now)))

(defn append-task-review! [root task-id path comments]
  (let [text (str/trim (or comments ""))]
    (when (and (not (str/blank? task-id)) (not (str/blank? path)) (not (str/blank? text)))
      (let [store (read-task-reviews root task-id)
            entry {"at" (iso-now) "text" text}
            history (conj (vec (get store path [])) entry)]
        (write-task-reviews! root task-id (assoc store path history))))))

(defn path-review-history [root task-id path]
  (vec (get (read-task-reviews root task-id) path [])))

(defn approval-entry [root path]
  (let [headers (:headers (parse-message path))
        to (first (comma-list (get headers "to")))
        id (approval-id path)]
    {:id id
     :gate (str "spec → " to)
     :task_id (or (not-empty (get headers "task_id")) (get headers "task"))
     :task (get headers "task")
     :artifacts (filterv #(allowed-doc? root %)
                          (comma-list (get headers "artifacts")))
     :reviews (read-reviews root id)}))

(defn approvals [root]
  (mapv #(approval-entry root %) (pending-files root)))

(defn delivery-attention-dir [root]
  (fs/path root ".swarmforge" "handoffs" "delivery_attention"))

(defn delivery-failures [root]
  (let [dir (delivery-attention-dir root)]
    (if-not (fs/directory? dir)
      []
      (->> (fs/list-dir dir)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".edn")))
           (sort-by #(fs/file-name %))
           (keep (fn [path]
                   (try
                     (let [entry (edn/read-string (slurp (str path)))]
                       (when (map? entry)
                         (assoc entry :attention_id (fs/file-name path))))
                     (catch Exception _ nil))))
           vec))))

(defn board-allow-pending-dir [root]
  (fs/path root ".swarmforge" "board" "lt-allow-pending"))

(defn parse-allow-field [text field]
  (second (re-find (re-pattern (str "(?m)^" field ": (.*)$")) (or text ""))))

(defn board-allows [root]
  (let [dir (board-allow-pending-dir root)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter fs/regular-file?)
           (sort-by #(fs/file-name %))
           (keep (fn [path]
                   (let [text (slurp (str path))
                         name (parse-allow-field text "name")
                         act (parse-allow-field text "act")]
                     (when (and (not (str/blank? name)) (not (str/blank? act)))
                       {:id (fs/file-name path)
                        :task name
                        :act act}))))
           vec)
      [])))
