;; Board state, chat, tasks, and approvals. Loaded into pack-web.

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
  (fs/path (pending-dir root) (str id ".reviews.json")))

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
  (fs/path root ".swarmforge" "rejected-tasks" task-id "reviews.json"))

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

(defn record-heat! [key text backend]
  (let [tail (last-n-lines (pane-sample text backend) 20)
        bag (frequencies tail)
        prev (get @pane-heat key)
        n (if (:bag prev) (bag-diff (:bag prev) bag) 0)
        heat (heat-from-count n)]
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

(defn chat-pending-dir [root]
  (fs/path root ".swarmforge" "dashboard" "requests" "pending"))

(defn chat-done-dir [root]
  (fs/path root ".swarmforge" "dashboard" "requests" "done"))

(defn chat-files [dir]
  (if (fs/directory? dir)
    (->> (fs/list-dir dir)
         (filter #(str/ends-with? (fs/file-name %) ".request"))
         (sort-by str)
         vec)
    []))

(defn parse-chat [path]
  (let [raw (slurp (str path))
        [header body] (str/split raw #"\n\n" 2)
        headers (into {}
                      (for [line (str/split-lines header)
                            :let [[k v] (str/split line #": " 2)]
                            :when (and k v)]
                        [k v]))]
    {:id (get headers "id")
     :status (get headers "status")
     :body (or body "")
     :response (str/replace (get headers "response" "") #"\\n" "\n")
     :created_at (get headers "created_at")}))

(defn list-chat [root]
  (vec (concat (map parse-chat (chat-files (chat-pending-dir root)))
               (map parse-chat (chat-files (chat-done-dir root))))))

(defn clar-pending-dir [root]
  (fs/path root ".swarmforge" "dashboard" "clarifications" "pending"))

(defn clar-done-dir [root]
  (fs/path root ".swarmforge" "dashboard" "clarifications" "done"))

(defn parse-clarification [path]
  (let [raw (slurp (str path))
        [header body] (str/split raw #"\n\n" 2)
        headers (into {}
                      (for [line (str/split-lines header)
                            :let [[k v] (str/split line #": " 2)]
                            :when (and k v)]
                        [k v]))]
    {:id (get headers "id")
     :status (get headers "status")
     :role (get headers "role")
     :body (or body "")
     :response (str/replace (get headers "response" "") #"\\n" "\n")
     :created_at (get headers "created_at")}))

(defn list-clarifications [root]
  (vec (concat (map parse-clarification (chat-files (clar-pending-dir root)))
               (map parse-clarification (chat-files (clar-done-dir root))))))

(defn chat-id []
  (str "req-" (str/replace (str (java.time.Instant/now)) #"[^0-9A-Za-z]" "")))

(defn chat-wake [id text]
  (if (str/includes? (or text "") "\n")
    (str "[" id "]\n" text)
    (str "[" id "] " text)))

(defn clar-wake [id role question answer]
  (str "[" id "]\n"
       "Clarification requested from: " role "\n"
       "Question:\n" (str/trimr (or question "")) "\n"
       "Answer:\n" (str/trimr (or answer ""))))

(defn write-chat-request! [root text]
  (let [id (chat-id)
        file (fs/path (chat-pending-dir root) (str id ".request"))]
    (fs/create-dirs (fs/parent file))
    (spit (str file)
          (str "id: " id "\n"
               "status: pending\n"
               "created_at: " (.format java.time.format.DateTimeFormatter/ISO_INSTANT
                                       (java.time.Instant/now)) "\n"
               "\n"
               text
               (when-not (str/ends-with? text "\n") "\n")))
    id))

(defn dashboard-state [root]
  (let [master (master-role root)
        heats (role-heats root)]
    {:master_role master
     :master_display (display-name-for-role master)
     :lanes (display-lanes root)
     :tasks (tasks root)
     :role_heats heats
     :approvals (approvals root)
     :board_allows (board-allows root)
     :work_in_flight (work-in-flight root heats)
     :chat (list-chat root)
     :clarifications (list-clarifications root)}))

(defn tagged [project items]
  (mapv #(assoc % :project project) items))

(defn open-project-root [forge name]
  (str (forge/project-dir forge name)))

(defn project-slice [forge name]
  (let [root (open-project-root forge name)]
    (try
      (let [heats (role-heats root)]
        {:name name
         :open true
         :lanes (display-lanes root)
         :tasks (tagged name (tasks root))
         :role_heats heats
         :work_in_flight (tagged name (work-in-flight root heats))})
      (catch Exception _
        {:name name
         :open true
         :lanes []
         :tasks []
         :work_in_flight []}))))

(defn forge-dashboard-state [root]
  (let [open (forge/read-open-projects root)
        projects (mapv #(project-slice root %) open)]
    {:forge true
     :master_role "lieutenant"
     :master_display "Lieutenant"
     :packs (mapv (fn [p] {:name p :conf (or (forge/pack-conf root p) "")})
                  (forge/list-pack-names root))
     :all_projects (forge/list-project-names root)
     :open_projects open
     :projects projects
     :approvals (vec (mapcat (fn [name]
                               (try
                                 (tagged name (approvals (open-project-root root name)))
                                 (catch Exception _ [])))
                             open))
     :board_allows (vec (mapcat (fn [name]
                                  (try
                                    (tagged name (board-allows (open-project-root root name)))
                                    (catch Exception _ [])))
                                open))
     :clarifications (vec (concat
                           (mapv #(assoc % :source "lieutenant")
                                 (list-clarifications root))
                           (mapcat (fn [name]
                                     (try
                                       (tagged name (list-clarifications (open-project-root root name)))
                                       (catch Exception _ [])))
                                   open)))
     :chat (list-chat root)
     :lieutenant_status (pane-status-lines-for root "lieutenant")
     :lieutenant_activity (let [heats (role-heats root)]
                            (get heats "lieutenant" 0))
     :lanes []
     :tasks []
     :work_in_flight (vec (mapcat :work_in_flight projects))}))

(defn api-state [root]
  (if (forge/forge? root)
    (forge-dashboard-state root)
    (dashboard-state root)))

(defn require-root! [root]
  (when (str/blank? root)
    (exit! 1 "Missing project root"))
  root)

(defn dashboard-page []
  (let [dir (fs/path script-dir "pack")
        html (slurp (str (fs/path dir "dashboard.html")))
        css (str/trim (slurp (str (fs/path dir "dashboard.css"))))
        js (str/join "\n"
                     [(slurp (str (fs/path dir "dashboard_board.js")))
                      (slurp (str (fs/path dir "dashboard_attention.js")))
                      (slurp (str (fs/path dir "dashboard_ui.js")))])]
    (-> html
        (str/replace "/*DASHBOARD_CSS*/" css)
        (str/replace "/*DASHBOARD_JS*/" js))))

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

(defn handoff-task-id [path]
  (let [headers (:headers (parse-message path))]
    (or (not-empty (get headers "task_id"))
        (get headers "task"))))

(defn task-handoffs [root task-id & aliases]
  (->> (handoff-dirs root)
       (mapcat glob-handoffs)
       (filter #(contains? (set (remove str/blank? (cons task-id aliases)))
                           (handoff-task-id %)))
       vec))

(defn copy-into [dir path]
  (when (fs/regular-file? path)
    (fs/copy path (fs/path dir (fs/file-name path)) {:replace-existing true})))

(defn archive-rejected! [root task-id name]
  (let [dir (fs/path root ".swarmforge" "rejected-tasks" task-id)]
    (fs/create-dirs dir)
    (copy-into dir (fs/path root ".swarmforge" "board" (str name ".txt")))
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
  (fs/path root ".swarmforge" "notify" (str "reject-" name)))

(defn task-by-name [root name]
  (some #(when (= name (:name %)) %) (board-tasks root)))

(defn task-id-for-name [root name]
  (or (:id (task-by-name root name)) name))

(defn delete-task! [root name]
  (when (str/blank? name)
    (throw (ex-info "Missing task name" {:http-status 400})))
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
  (let [card-type (if (str/blank? card-type) card-type/default-type card-type)]
    (when-not (card-type/known? card-type)
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

(defn post-chat [root body]
  (let [{:keys [text]} (json/parse-string (or body "{}") true)
        text (or text "")]
    (when-not (str/blank? text)
      (let [id (write-chat-request! root text)]
        (inject-master! root (chat-wake id text))))
    (json-ok)))

(defn clar-pending-file [root id]
  (fs/path (clar-pending-dir root) (str id ".request")))

(defn render-clarification [{:keys [id status role body response created_at]}]
  (str "id: " id "\n"
       "status: " status "\n"
       (when-not (str/blank? role) (str "role: " role "\n"))
       "created_at: " created_at "\n"
       (when-not (str/blank? response)
         (str "response: " (str/replace response #"\n" (constantly "\\n")) "\n"))
       "\n"
       (or body "")
       (when-not (str/ends-with? (or body "") "\n") "\n")))

(defn answer-clarification! [root id text]
  (let [src (clar-pending-file root id)]
    (when-not (fs/regular-file? src)
      (throw (ex-info (str "Unknown clarification: " id) {:http-status 404})))
    (let [entry (parse-clarification src)
          dest (fs/path (clar-done-dir root) (str id ".request"))
          role (:role entry)]
      (fs/create-dirs (fs/parent dest))
      (spit (str dest) (render-clarification (assoc entry
                                                   :status "done"
                                                   :response text)))
      (fs/delete-if-exists src)
      (inject-role! root role (clar-wake id role (:body entry) text)))))

(defn clarification-route [uri]
  (let [path (first (str/split (or uri "") #"\?"))]
    (when-let [[_ id] (re-matches #"/api/clarifications/([^/]+)/answer" path)]
      (java.net.URLDecoder/decode id "UTF-8"))))

(defn post-clarification [root uri body]
  (if-let [id (clarification-route uri)]
    (let [text (or (:text (json/parse-string (or body "{}") true)) "")]
      (answer-clarification! root id text)
      (json-ok))
    {:status 404 :body "Not found"}))

(defn pending-file [root id]
  (fs/path (pending-dir root) (str id ".handoff")))

(defn require-pending! [root id]
  (let [path (pending-file root id)]
    (when-not (fs/regular-file? path)
      (throw (ex-info (str "Unknown approval: " id) {:http-status 404})))
    path))

(defn with-approved [content]
  (if (re-find #"(?m)^approved: " content)
    content
    (str/replace-first content #"\n\n" "\napproved: true\n\n")))

(defn approve! [root id]
  (let [src (require-pending! root id)
        headers (:headers (parse-message src))
        dest (fs/path root ".swarmforge" "handoffs" "outbox" (fs/file-name src))]
    (fs/create-dirs (fs/parent dest))
    (spit (str dest) (with-approved (slurp (str src))))
    (fs/delete-if-exists src)
    (drop-reviews! root id)
    (drop-task-reviews! root (or (not-empty (get headers "task_id")) (get headers "task")))))

(defn save-review! [root id path comments]
  (when (str/blank? path)
    (throw (ex-info "Missing path" {:http-status 400})))
  (let [src (require-pending! root id)
        headers (:headers (parse-message src))
        task-id (or (not-empty (get headers "task_id")) (get headers "task"))
        text (str/trim (or comments ""))]
    (write-reviews! root id (assoc (read-reviews root id) path text))
    (append-task-review! root task-id path text)))

(defn write-reject-notify! [root task]
  (when-not (str/blank? task)
    (let [path (fs/path root ".swarmforge" "notify" (str "reject-" task))]
      (fs/create-dirs (fs/parent path))
      (spit (str path) "rejected\n"))))

(defn git! [root & args]
  (let [result (apply sh (concat ["git" "-C" (str root)] args))]
    (when-not (zero? (:exit result))
      (throw (ex-info (str/trim (str (:err result) "\n" (:out result)))
                      {:http-status 500})))
    (str/trim (:out result))))

(defn git-ok? [root & args]
  (zero? (:exit (apply sh (concat ["git" "-C" (str root)] args)))))

(defn git-repo? [root]
  (git-ok? root "rev-parse" "--is-inside-work-tree"))

(defn worktree-for [root role]
  (if-let [row (role-row root role)]
    (or (not-empty (nth row 2 nil)) (str root))
    (str root)))

(defn rejected-ref [task-id n]
  (str "rejected/" task-id "/" n))

(defn rejected-latest [task-id]
  (str "rejected/" task-id "/latest"))

(defn git-ref-exists? [root ref]
  (git-ok? root "show-ref" "--verify" "--quiet" (str "refs/heads/" ref)))

(defn next-rejected-n [root task-id]
  (loop [n 1]
    (if (git-ref-exists? root (rejected-ref task-id n))
      (recur (inc n))
      n)))

(defn snapshot-rejected! [root task-id commit n]
  (when (and (git-repo? root) (not (str/blank? task-id)) (not (str/blank? commit)))
    (git! root "branch" "-f" (rejected-ref task-id n) commit)
    (git! root "branch" "-f" (rejected-latest task-id) commit)))

(defn restore-commit! [worktree commit]
  (when (and (git-repo? worktree) (not (str/blank? commit))
             (git-ok? worktree "rev-parse" "--verify" commit))
    (let [head (git! worktree "rev-parse" "HEAD")
          want (git! worktree "rev-parse" commit)]
      (when (not= head want)
        (git! worktree "reset" "--hard" commit)))))

(defn commit-parent [root commit]
  (when (and (git-repo? root) (not (str/blank? commit)))
    (not-empty (git! root "rev-parse" "--short=10" (str commit "^")))))

(defn rollback-target [root headers]
  (or (not-empty (get headers "task_base_commit"))
      (when-let [commit (not-empty (get headers "commit"))]
        (commit-parent root commit))))

(defn rollback-to-base! [root headers]
  (when-let [target (rollback-target root headers)]
    (when (git-repo? root)
      (git! root "reset" "--hard" target))))

(defn increment-audit-count! [root task-id]
  (when-not (str/blank? task-id)
    (pack-board root "increment-audit" "--task-id" task-id "--caller" "handoffd")))

(defn latest-audit-n [root task-id]
  (let [dir (fs/path root ".swarmforge" "board" "audits" task-id)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (map fs/file-name)
           (keep #(when-let [[_ n] (re-matches #"([0-9]+)\.md" %)]
                    (Long/parseLong n)))
           (cons 0)
           (apply max))
      0)))

(defn review-findings [reviews]
  (->> reviews
       (filter (fn [[_ text]] (not (str/blank? (str/trim (str text))))))
       (map (fn [[path text]] (str path ":\n" (str/trim (str text)))))
       (str/join "\n\n")))

(defn write-latest-audit-findings! [root task-id reviews comments]
  (let [n (latest-audit-n root task-id)
        extra (str/trim (or comments ""))
        findings (review-findings reviews)]
    (when (pos? n)
      (let [file (fs/path root ".swarmforge" "board" "audits" task-id (str n ".md"))]
        (fs/create-dirs (fs/parent file))
        (spit (str file)
              (str "# Audit " n "\n\n"
                   (when-not (str/blank? extra) (str extra "\n\n"))
                   (when-not (str/blank? findings) (str findings "\n"))))))))

(defn retry-message [task comments reviews]
  (let [extra (str/trim (or comments ""))
        findings (review-findings reviews)]
    (str "Retry audit for " task
         ". Re-read tasks/" task ".md as operator intent."
         " Read the remedial comments as audit findings."
         (when-not (str/blank? extra) (str "\n\n" extra))
         (when-not (str/blank? findings) (str "\n\n" findings)))))

(defn task-inbox-files [worktree state task-id task]
  (let [wanted (set (remove str/blank? [task-id task]))]
    (->> (glob-handoffs (fs/path worktree ".swarmforge" "handoffs" "inbox" state))
         (filter #(contains? wanted (handoff-task-id %)))
         vec)))

(defn write-retry-in-process! [worktree headers]
  (let [task-id (or (not-empty (get headers "task_id")) (get headers "task"))
        task (or (get headers "task") task-id)
        base (not-empty (get headers "task_base_commit"))
        from (or (get headers "from") "")
        dir (fs/path worktree ".swarmforge" "handoffs" "inbox" "in_process")
        file (fs/path dir (str "50_retry_" (str/replace (or task-id "task") #"[^A-Za-z0-9]+" "_") ".handoff"))]
    (when-not (str/blank? task-id)
      (fs/create-dirs dir)
      (spit (str file)
            (str "from: (Retry)\n"
                 "to: " from "\n"
                 "priority: 50\n"
                 "type: note\n"
                 "task_id: " task-id "\n"
                 "task: " task "\n"
                 (when base (str "task_base_commit: " base "\n"))
                 "\n"
                 "Retry audit.\n")))))

(defn restore-task-base! [root headers]
  (let [task-id (or (not-empty (get headers "task_id")) (get headers "task"))
        task (get headers "task")
        wt (worktree-for root (get headers "from"))
        in-proc (task-inbox-files wt "in_process" task-id task)
        done (task-inbox-files wt "completed" task-id task)]
    (cond
      (seq in-proc) nil
      (seq done)
      (let [src (first done)
            dest-dir (fs/path wt ".swarmforge" "handoffs" "inbox" "in_process")]
        (fs/create-dirs dest-dir)
        (fs/move src (fs/path dest-dir (fs/file-name src)) {:replace-existing true}))
      :else (write-retry-in-process! wt headers))))

(defn approval-doc-paths [headers reviews]
  (vec (distinct (concat (comma-list (get headers "artifacts"))
                         (keys reviews)))))

(defn retry-approval! [root id comments]
  (let [src (require-pending! root id)
        headers (:headers (parse-message src))
        task (get headers "task")
        task-id (or (not-empty (get headers "task_id")) task)
        commit (not-empty (get headers "commit"))
        n (if (git-repo? root) (next-rejected-n root task-id) 1)
        wt (worktree-for root (get headers "from"))
        reviews (read-reviews root id)]
    (doseq [path (approval-doc-paths headers reviews)]
      (append-task-review! root task-id path comments))
    (when commit
      (snapshot-rejected! root task-id commit n)
      (restore-commit! wt commit))
    (fs/delete-if-exists src)
    (drop-reviews! root id)
    (drop-task-audits! root task-id task)
    (restore-task-base! root headers)
    (increment-audit-count! root task-id)
    (write-latest-audit-findings! root task-id reviews comments)
    (when-not (str/blank? task)
      (inject-master! root (retry-message task comments reviews)))))

(defn delete-approval! [root id]
  (let [src (require-pending! root id)
        headers (:headers (parse-message src))
        task (get headers "task")
        task-id (or (not-empty (get headers "task_id")) task)
        commit (not-empty (get headers "commit"))
        n (if (git-repo? root) (next-rejected-n root task-id) 1)
        wt (worktree-for root (get headers "from"))]
    (when commit
      (snapshot-rejected! root task-id commit n))
    (rollback-to-base! wt headers)
    (archive-rejected! root task-id task)
    (drop-task-handoffs! root task-id task)
    (drop-task-audits! root task-id task)
    (pack-board root "delete" "--name" task)
    (fs/delete-if-exists (reject-notify root task))
    (drop-reviews! root id)
    (drop-task-reviews! root task-id)))

(defn approval-route [uri]
  (let [path (first (str/split (or uri "") #"\?"))]
    (when-let [[_ id action] (re-matches #"/api/approvals/([^/]+)/(approve|reject|comments)" path)]
      {:id (java.net.URLDecoder/decode id "UTF-8")
       :action action})))

(defn post-approval [root uri body]
  (if-let [{:keys [id action]} (approval-route uri)]
    (case action
      "approve" (do (approve! root id)
                    (json-ok))
      "comments" (let [{:keys [path comments]} (json/parse-string (or body "{}") true)]
                   (save-review! root id path comments)
                   (json-ok))
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error "Reject opens the dialog; use Retry, Delete, or Accept."})})
    {:status 404 :body "Not found"}))

