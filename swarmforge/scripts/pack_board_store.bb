;; Board rows, task docs, create, increment-audit, and delete. Loaded into pack-board.

(declare require-caller! consume-allow!)

(defn board-dir [root]
  (fs/path root ".swarmforge" "board"))

(defn tasks-file [root]
  (fs/path (board-dir root) "tasks.tsv"))

(defn with-board-lock [root f]
  (let [dir (board-dir root)
        path (fs/path dir "tasks.lock")
        options (into-array OpenOption [StandardOpenOption/CREATE
                                        StandardOpenOption/WRITE])]
    (fs/create-dirs dir)
    (with-open [channel (FileChannel/open path options)]
      (.lock channel)
      (f))))

(defn task-body-file [root name]
  (fs/path (board-dir root) (str name ".txt")))

(defn task-doc-file [root name]
  (fs/path root "tasks" (str name ".md")))

(defn write-body! [root name text]
  (when (some? text)
    (let [file (task-body-file root name)]
      (fs/create-dirs (fs/parent file))
      (spit (str file) text))))

(defn write-task-doc! [root name text card-type merge-from]
  (when (some? name)
    (let [file (task-doc-file root name)
          body (or text "")]
      (fs/create-dirs (fs/parent file))
      (spit (str file)
            (str "# " name "\n\n"
                 "Type: " (card-type/normalize card-type) "\n"
                 (when-not (str/blank? merge-from)
                   (str "Merge-from: " merge-from "\n"))
                 "\n"
                 body
                 (when-not (str/ends-with? body "\n") "\n"))))))

(defn task-text [root name]
  (let [body (task-body-file root name)]
    (if (fs/regular-file? body)
      (slurp (str body))
      "")))

(defn set-merge-from-line [text role]
  (let [lines (str/split-lines (or text ""))
        prefix "Merge-from: "
        without (remove #(str/starts-with? % prefix) lines)
        insert-at (or (first (keep-indexed
                              (fn [i line]
                                (when (str/starts-with? line "Type: ") (inc i)))
                              without))
                      (count without))
        [before after] (split-at insert-at without)
        next (concat before
                     (when-not (str/blank? role) [(str prefix role)])
                     after)]
    (str (str/join "\n" next)
         (when-not (str/ends-with? (or text "") "\n") "\n")
         (when (and (str/ends-with? (or text "") "\n")
                    (not (str/ends-with? (str/join "\n" next) "\n")))
           "\n"))))

(defn update-task-doc-merge-from! [root name role]
  (let [file (task-doc-file root name)]
    (when (fs/regular-file? file)
      (spit (str file) (set-merge-from-line (slurp (str file)) role)))))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn durable-audit-file [root task-id n]
  (fs/path root ".swarmforge" "board" "audits" task-id (str n ".md")))

(defn write-durable-audit! [root task-id n text]
  (when (and (not (str/blank? task-id)) (pos? n))
    (let [file (durable-audit-file root task-id n)]
      (fs/create-dirs (fs/parent file))
      (spit (str file)
            (or text
                (str "# Audit " n "\n\n"
                     "Refused at: " (timestamp) "\n"
                     "Task-id: " task-id "\n"))))))

(defn id-timestamp []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmssSSSSSS'Z'")
           (.atZone (java.time.Instant/now) java.time.ZoneOffset/UTC)))

(defn slug [s]
  (let [slugged (-> (or s "")
                    str/lower-case
                    (str/replace #"[^a-z0-9]+" "-")
                    (str/replace #"(^-+|-+$)" ""))]
    (if (str/blank? slugged) "task" slugged)))

(defn new-task-id [name]
  (str (id-timestamp) "-" (slug name)))

(defn read-rows [file]
  (if (fs/exists? file)
    (->> (str/split-lines (slurp (str file)))
         (remove str/blank?)
         vec)
    []))

(defn write-rows [file rows]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file) :prefix ".tasks."})]
    (spit (str tmp)
          (if (seq rows)
            (str (str/join "\n" rows) "\n")
            ""))
    (fs/move tmp file {:replace-existing true :atomic-move true})))

(defn row-name [line]
  (first (str/split line #"\t")))

(defn find-task [rows name]
  (let [want (str/lower-case (or name ""))]
    (some #(when (= want (str/lower-case (or (row-name %) ""))) %) rows)))

(defn task-row
  ([name lane now]
   (task-row name lane now (new-task-id name) card-type/default-type))
  ([name lane now task-id]
   (task-row name lane now task-id card-type/default-type))
  ([name lane now task-id card-type]
   (card-type/format-row {:name name
                          :lane lane
                          :created now
                          :updated now
                          :id task-id
                          :audit-count 0
                          :type card-type})))

(defn task-name [opts]
  (or (:name opts) (second (:positional opts))))

(defn task-lane [opts]
  (or (:lane opts) (nth (:positional opts) 2 nil)))

(defn require-value! [value label]
  (when (str/blank? value)
    (exit! 1 (str "Missing " label))))

(defn pack-role-names [root]
  (mapv first (role-rows root)))

(defn require-merge-from! [root role]
  (when-not (str/blank? role)
    (when-not (some #{role} (pack-role-names root))
      (exit! 1 (str "Unknown merge-from role: " role)))))

(defn slug-role [s]
  (str/replace (or s "") #"[^A-Za-z0-9]+" "_"))

(defn queue-start-note! [root name lane task-id text]
  (when-not (or (str/blank? lane) (#{"waiting" "done"} lane))
    (let [now (timestamp)
          stamp (str/replace now #"[^0-9A-Za-z]" "")
          body (or text "")
          filename (str "50_" stamp "_from_New_Task_to_" (slug-role lane) ".handoff")
          outbox (fs/path root ".swarmforge" "handoffs" "outbox")
          file (fs/path outbox filename)]
      (fs/create-dirs outbox)
      (spit (str file)
            (str "id: " stamp "_from_New_Task\n"
                 "from: (New Task)\n"
                 "to: " lane "\n"
                 "priority: 50\n"
                 "type: note\n"
                 "task_id: " task-id "\n"
                 "task: " name "\n"
                 "created_at: " now "\n"
                 "\n"
                 body
                 (when-not (str/ends-with? body "\n") "\n"))))))

(defn create! [opts]
  (when (contains? opts :lane)
    (exit! 1 "create rejects --lane; lane is computed from --type"))
  (let [name (task-name opts)
        card-type (:type opts)
        root (resolve-root opts)
        file (tasks-file root)
        merge-from (:merge-from opts)]
    (require-value! name "task name")
    (require-value! card-type "type")
    (when-not (card-type/known? card-type)
      (exit! 1 (str "Unknown type: " card-type)))
    (require-merge-from! root merge-from)
    (let [lane (if (:waiting opts) "waiting" (card-type/starting-lane card-type))
          task-id (or (:task-id opts) (new-task-id name))]
      (with-board-lock
        root
        (fn []
          (let [rows (read-rows file)]
            (when (find-task rows name)
              (exit! 1 (str "Duplicate task name: " name)))
            (write-rows file (conj rows (task-row name lane (timestamp)
                                                 task-id
                                                 card-type)))
            (write-body! root name (:text opts))
            (write-task-doc! root name (:text opts) card-type merge-from)
            (when-not (:waiting opts)
              (queue-start-note! root name lane task-id (:text opts)))))))))

(defn parse-count [value]
  (if (and value (re-matches #"[0-9]+" value))
    (Long/parseLong value)
    0))

(defn rewrite-audit-count [line task-id]
  (let [row (card-type/parse-row line)
        row-key (or (not-empty (:id row)) (:name row))]
    (if (= task-id row-key)
      (card-type/format-row (assoc row :audit-count (inc (:audit-count row))))
      line)))

(defn increment-audit! [opts]
  (require-caller! opts "increment-audit")
  (let [task-id (:task-id opts)
        root (resolve-root opts)
        file (tasks-file root)]
    (require-value! task-id "task ID")
    (with-board-lock
      root
      (fn []
        (when (fs/exists? file)
          (let [rows (read-rows file)
                present? (some #(let [[name _lane _created _updated row-task-id]
                                      (str/split % #"\t" -1)]
                                  (= task-id (or (not-empty row-task-id) name)))
                               rows)]
            (when-not present?
              (exit! 1 (str "Unknown task ID: " task-id)))
            (write-rows file (mapv #(rewrite-audit-count % task-id) rows))
            (let [n (some (fn [line]
                            (let [row (card-type/parse-row line)
                                  row-key (or (not-empty (:id row)) (:name row))]
                              (when (= task-id row-key)
                                (:audit-count row))))
                          (read-rows file))]
              (when n
                (write-durable-audit! root task-id n nil))))))))
  (consume-allow! opts "increment-audit"))

(defn delete! [opts]
  (let [name (task-name opts)
        root (resolve-root opts)
        file (tasks-file root)]
    (require-value! name "task name")
    (with-board-lock
      root
      (fn []
        (let [rows (read-rows file)]
          (when-not (find-task rows name)
            (exit! 1 (str "Unknown task name: " name)))
          (write-rows file (filterv #(not= (str/lower-case name)
                                           (str/lower-case (or (row-name %) "")))
                                    rows))
          (fs/delete-if-exists (task-body-file root name)))))))
