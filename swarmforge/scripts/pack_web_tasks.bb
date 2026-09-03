;; Task create, delete, and retry. Loaded into pack-web.

(defn slug [s]
  (str/replace (or s "") #"[^A-Za-z0-9]+" "_"))

(defn id-timestamp []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmssSSSSSS'Z'")
           (.atZone (java.time.Instant/now) java.time.ZoneOffset/UTC)))

(defn id-slug [s]
  (let [slugged (-> (or s "")
                    str/lower-case
                    (str/replace #"[^a-z0-9]+" "-")
                    (str/replace #"(^-+|-+$)" ""))]
    (if (str/blank? slugged) "task" slugged)))

(defn new-task-id [name]
  (str (id-timestamp) "-" (id-slug name)))

(defn json-ok []
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string {:ok true})})

(defn http-error [status message]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string {:error message})})

(defn handoff-dirs [root]
  (->> (role-rows root)
       (map #(nth % 2 nil))
       (remove str/blank?)
       (cons (str root))
       distinct
       (mapv #(fs/path % ".swarmforge" "handoffs"))))

(defn glob-handoffs [dir]
  (if (fs/directory? dir)
    (->> (concat (fs/glob dir "*.handoff")
                 (fs/glob dir "**/*.handoff"))
         (filter fs/regular-file?)
         distinct
         vec)
    []))

(defn handoff-task-ids [path]
  (let [headers (:headers (parse-message path))]
    (vec (distinct (remove str/blank?
                           (let [batch (header-batch-task-ids headers)]
                             (if (seq batch)
                               batch
                               [(or (not-empty (get headers "task_id"))
                                    (get headers "task"))])))))))

(defn handoff-task-id [path]
  (first (handoff-task-ids path)))

(defn task-handoffs [root task-id & aliases]
  (let [wanted (set (remove str/blank? (cons task-id aliases)))]
    (->> (handoff-dirs root)
         (mapcat glob-handoffs)
         (filter #(some wanted (handoff-task-ids %)))
         vec)))

(defn copy-into [dir path]
  (when (fs/regular-file? path)
    (fs/copy path (fs/path dir (fs/file-name path)) {:replace-existing true})))

(defn archive-rejected! [root task-id name]
  (safe-paths/require-task-name! name)
  (let [dir (safe-paths/state-key-path! (fs/path root ".swarmforge" "rejected-tasks")
                                        task-id "")]
    (fs/create-dirs dir)
    (copy-into dir (safe-paths/task-path! (fs/path root ".swarmforge" "board") name ".txt"))
    (copy-into dir (fs/path root ".swarmforge" "notify" (str "reject-" name)))
    (doseq [path (task-handoffs root task-id name)]
      (copy-into dir path))))

(defn drop-task-handoffs! [root task-id & aliases]
  (doseq [path (apply task-handoffs root task-id aliases)]
    (fs/delete-if-exists path)))

(defn audit-task-id [path]
  (try
    (get-in (edn/read-string (slurp (str path))) [:candidate :task-id])
    (catch Exception _ nil)))

(defn task-audits [root task-id & aliases]
  (let [wanted (set (remove str/blank? (cons task-id aliases)))
        dir (fs/path root ".swarmforge" "handoffs" "audit_pending")]
    (if (fs/directory? dir)
      (->> (fs/glob dir "**/*.edn")
           (filter #(contains? wanted (audit-task-id %)))
           vec)
      [])))

(defn drop-task-audits! [root task-id & aliases]
  (doseq [path (apply task-audits root task-id aliases)]
    (fs/delete-if-exists path)))

(defn reject-notify [root name]
  (safe-paths/require-task-name! name)
  (safe-paths/task-path! (fs/path root ".swarmforge" "notify")
                         (str "reject-" name) ""))

(defn task-by-name [root name]
  (some #(when (= name (:name %)) %) (board-tasks root)))

(defn task-id-for-name [root name]
  (or (:id (task-by-name root name)) name))

(defn delete-task! [root name]
  (when (str/blank? name)
    (throw (ex-info "Missing task name" {:http-status 400})))
  (safe-paths/require-task-name! name)
  (when-not (rejected-task? root name)
    (throw (ex-info (str "Not rejected: " name) {:http-status 400})))
  (let [task-id (task-id-for-name root name)]
    (archive-rejected! root task-id name)
    (drop-task-handoffs! root task-id name)
    (drop-task-audits! root task-id name)
    (drop-task-reviews! root task-id))
  (pack-board root "delete" "--name" name)
  (fs/delete-if-exists (reject-notify root name)))

(defn retry-task! [root name text]
  (throw (ex-info "Retry requires a pending approval id" {:http-status 400})))

(defn post-delete-task [root body]
  (let [{:keys [name id]} (json/parse-string (or body "{}") true)]
    (try
      (if (not-empty id)
        (delete-approval! root id)
        (delete-task! root name))
      (json-ok)
      (catch Exception e
        (http-error (or (:http-status (ex-data e)) 400) (.getMessage e))))))

(defn post-retry-task [root body]
  (let [{:keys [id comments]} (json/parse-string (or body "{}") true)]
    (try
      (when (str/blank? id)
        (throw (ex-info "Missing approval id" {:http-status 400})))
      (retry-approval! root id comments)
      (json-ok)
      (catch Exception e
        (http-error (or (:http-status (ex-data e)) 400) (.getMessage e))))))

(defn create-task! [root name text card-type]
  (when (str/blank? name)
    (throw (ex-info "Missing task name" {:http-status 400})))
  (safe-paths/require-task-name! name)
  (let [card-type (if (str/blank? card-type) (card-type/default-type root) card-type)]
    (when-not (card-type/known? root card-type)
      (throw (ex-info (str "Unknown type: " card-type) {:http-status 400})))
    (let [task-id (new-task-id name)]
      (pack-board root "create"
                  "--name" name
                  "--type" card-type
                  "--waiting"
                  "--task-id" task-id
                  "--text" (or text ""))
      task-id)))

(defn lt-task-type? [card-type]
  (contains? #{"LT" "lt"} (or card-type "")))

(defn notify-lt-task! [forge dest name text]
  (safe-paths/require-task-name! name)
  (let [project (if (forge/forge? forge) (fs/file-name dest) "")]
    (notify-lieutenant! (or (forge-of forge dest) forge) "new-task"
                        [["event" "new-task"]
                         ["project" project]
                         ["task" name]
                         ["type" "LT"]
                         ["text" (or text "")]]
                        (str "Notify: new-task LT " project "/" name "\n" (or text "")))))

(defn post-tasks [root body]
  (let [{:keys [name text project type]} (json/parse-string (or body "{}") true)
        dest (if (and (forge/forge? root) (not (str/blank? project)))
               (str (forge/project-dir root project))
               root)]
    (try
      (when (and (forge/forge? root) (str/blank? project))
        (throw (ex-info "Missing project" {:http-status 400})))
      (if (lt-task-type? type)
        (when (forge/forge? root)
          (notify-lt-task! root dest name text))
        (do
          (create-task! dest name text type)
          (notify-new-task! root dest name)))
      (json-ok)
      (catch Exception e
        (http-error (or (:http-status (ex-data e)) 400) (.getMessage e))))))
