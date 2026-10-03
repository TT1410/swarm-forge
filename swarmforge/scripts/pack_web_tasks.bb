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

(defn drop-task-handoffs!
  "Drop the handoffs that carry only this card. A batch that carries the card
  next to other cards belongs to the batch and stays."
  [root task-id & aliases]
  (let [wanted (set (remove str/blank? (cons task-id aliases)))]
    (doseq [path (apply task-handoffs root task-id aliases)
            :when (every? wanted (handoff-task-ids path))]
      (fs/delete-if-exists path))))

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

(def max-task-name-length 80)

(defn bad-request! [message]
  (throw (ex-info message {:http-status 400})))

(defn require-task-name! [name]
  (cond
    (str/blank? name) (bad-request! "Missing task name")
    (> (count name) max-task-name-length)
    (bad-request! (str "Task name must be no longer than " max-task-name-length
                       " characters (got " (count name) ")."))
    :else (safe-paths/require-task-name! name)))

(defn normalize-level [level]
  (let [text (some-> level str str/trim str/lower-case not-empty)]
    (cond
      (nil? text) nil
      (handoff-lib/level-priority text) text
      :else (bad-request! (str "Level must be critical, high, normal or low; got '" level "'.")))))

(defn resolve-card-ids
  "Card names or ids -> task ids of cards on the board."
  [root refs]
  (let [board (board-tasks root)]
    (->> refs
         (map #(some-> % str str/trim))
         (remove str/blank?)
         (mapv (fn [ref]
                 (or (some #(when (or (= ref (:id %)) (= ref (:name %))) (:id %)) board)
                     (bad-request! (str "Unknown card: " ref)))))
         distinct
         vec)))

(defn blocker-graph [root]
  (into {}
        (for [task (board-tasks root)]
          [(:id task) (vec (:blocked_by (handoff-lib/read-card-meta root (:id task))))])))

(defn reaches? [graph from to]
  (loop [todo [from] seen #{}]
    (when-let [id (first todo)]
      (cond
        (= id to) true
        (contains? seen id) (recur (rest todo) seen)
        :else (recur (concat (rest todo) (get graph id)) (conj seen id))))))

(defn check-blockers! [root task-id blockers]
  (when (some #{task-id} blockers)
    (bad-request! "A card cannot block itself."))
  (let [graph (assoc (blocker-graph root) task-id blockers)
        names (into {} (map (juxt :id :name) (board-tasks root)))]
    (doseq [b blockers]
      (when (reaches? graph b task-id)
        (bad-request! (str "That would make a cycle: " (get names b b)
                           " already waits for " (get names task-id task-id) "."))))))

(defn set-card-links!
  "Blockers hold a waiting card: the lieutenant cannot start it until they
  are done. Related cards are only shown. Both are kept by task id."
  [root task-id {:keys [blocked_by related]}]
  (let [blockers (resolve-card-ids root blocked_by)
        related (vec (remove #{task-id} (resolve-card-ids root related)))]
    (check-blockers! root task-id blockers)
    (handoff-lib/update-card-meta!
     root task-id
     (fn [meta]
       (cond-> (dissoc meta :blocked_by :related)
         (seq blockers) (assoc :blocked_by blockers)
         (seq related) (assoc :related related))))))

(defn create-task!
  ([root name text card-type] (create-task! root name text card-type {}))
  ([root name text card-type {:keys [level blocked_by related]}]
   (require-task-name! name)
   (let [card-type (if (str/blank? card-type) (card-type/default-type root) card-type)
         level (normalize-level level)
         blockers (resolve-card-ids root blocked_by)
         related (resolve-card-ids root related)]
     (when-not (card-type/known? root card-type)
       (throw (ex-info (str "Unknown type: " card-type) {:http-status 400})))
     (let [task-id (new-task-id name)]
       ;; The meta comes first, so the card never shows without its level.
       (handoff-lib/write-card-meta!
        root task-id
        (cond-> {}
          level (assoc :level level)
          (seq blockers) (assoc :blocked_by blockers)
          (seq related) (assoc :related related)))
       (try
         (pack-board root "create"
                     "--name" name
                     "--type" card-type
                     "--waiting"
                     "--task-id" task-id
                     "--text" (or text ""))
         (catch Exception e
           (handoff-lib/delete-card-meta! root task-id)
           (throw e)))
       task-id))))

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
  (let [{:keys [name text project type] :as opts} (json/parse-string (or body "{}") true)
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
          (create-task! dest name text type
                        (select-keys opts [:level :blocked_by :related]))
          (notify-new-task! root dest name)))
      (json-ok)
      (catch Exception e
        (http-error (or (:http-status (ex-data e)) 400) (.getMessage e))))))

(defn normalize-priority [priority]
  (let [text (str/trim (str (or priority "")))]
    (cond
      (str/blank? text) nil
      (re-matches #"[0-9]{1,2}" text) (format "%02d" (Long/parseLong text))
      :else (bad-request! (str "Priority must be a number from 00 to 99; got '" text "'.")))))

(defn project-dest [root project]
  (if (forge/forge? root)
    (if (str/blank? project)
      (bad-request! "Missing project")
      (str (forge/project-dir root project)))
    root))

(defn json-action [f]
  (try
    (f)
    (json-ok)
    (catch Exception e
      (http-error (or (:http-status (ex-data e)) 400) (.getMessage e)))))

(defn rename-task! [root name to]
  (when (str/blank? name)
    (bad-request! "Missing task name"))
  (require-task-name! (some-> to str/trim))
  (when-not (task-by-name root name)
    (throw (ex-info (str "Unknown task name: " name) {:http-status 404})))
  (pack-board root "rename" "--name" name "--to" (str/trim to)))

(defn post-rename-task [root body]
  (let [{:keys [name to project]} (json/parse-string (or body "{}") true)]
    (json-action #(rename-task! (project-dest root project) name to))))

(defn handoff-state [path]
  (let [p (str/replace (str path) "\\" "/")]
    (cond
      (str/includes? p "/inbox/in_process/") :in-process
      (str/includes? p "/pending_approval/") :pending
      (str/includes? p "/inbox/new/") :queued
      (= "outbox" (str (fs/file-name (fs/parent path)))) :queued
      :else :history)))

(defn handoff-recipient [path]
  (let [headers (:headers (parse-message path))]
    (or (get headers "recipient") (get headers "to"))))

(defn conflict! [message]
  (throw (ex-info message {:http-status 409})))

(defn outbox-mail? [path]
  (= "outbox" (str (fs/file-name (fs/parent path)))))

(defn held-handoff?
  "A git handoff the paused daemon keeps in its outbox. It stays there until
  Resume, so it can be changed like mail in an inbox."
  [root path]
  (and (outbox-mail? path)
       (ready-for-next-guard/paused-at? root)
       (= "git_handoff" (get-in (parse-message path) [:headers "type"]))))

(defn queued-card-handoffs
  "The card's handoffs still waiting in an inbox/new or outbox.
  Refuses with 409 when any handoff of the card is in process or waits for approval."
  [root name]
  (when (str/blank? name)
    (bad-request! "Missing task name"))
  (let [task (or (task-by-name root name)
                 (throw (ex-info (str "Unknown task name: " name) {:http-status 404})))
        task-id (:id task)
        by-state (group-by handoff-state (task-handoffs root task-id name))]
    (when (= "done" (:lane task))
      (conflict! (str "Card is done: " name)))
    (when-let [busy (first (:in-process by-state))]
      (conflict! (str "Card is in progress at " (handoff-recipient busy) "; it can no longer be changed in the queue: " name)))
    (when (seq (:pending by-state))
      (conflict! (str "Card is waiting for approval; use Attention: " name)))
    (when (some #(> (count (handoff-task-ids %)) 1) (:queued by-state))
      (conflict! (str "Card travels in a batch that carries other cards too: " name)))
    (when (some #(and (outbox-mail? %) (not (held-handoff? root %))) (:queued by-state))
      (conflict! (str "Card is being delivered; try again in a moment: " name)))
    {:task task :queued (vec (:queued by-state))}))

(defn move-or-conflict! [from to name]
  (try
    (fs/move from to {:atomic-move true})
    (catch java.nio.file.NoSuchFileException _
      (conflict! (str "Card was just picked up by its role: " name)))))

(defn dequeue-task!
  "Remove a card that waits in a queue: its queued handoffs are moved to
  .swarmforge/removed-tasks/<task-id>/ with its body, then the card leaves the board."
  [root name]
  (let [{:keys [task queued]} (queued-card-handoffs root name)
        task-id (:id task)
        dir (safe-paths/state-key-path! (fs/path root ".swarmforge" "removed-tasks") task-id "")
        moved (atom [])]
    (fs/create-dirs dir)
    (try
      (doseq [[i path] (map-indexed vector queued)
              :let [dest (fs/path dir (str i "_" (fs/file-name path)))]]
        (move-or-conflict! path dest name)
        (swap! moved conj [dest path]))
      (catch Exception e
        (doseq [[dest path] @moved]
          (fs/move dest path))
        (throw e)))
    (copy-into dir (safe-paths/task-path! (fs/path root ".swarmforge" "board") name ".txt"))
    (pack-board root "delete" "--name" name)
    (fs/delete-if-exists (reject-notify root name))))

(defn with-priority-header [content priority]
  (let [[header body] (str/split content #"\n\n" 2)
        lines (str/split-lines header)
        lines (if (some #(str/starts-with? % "priority: ") lines)
                (mapv #(if (str/starts-with? % "priority: ") (str "priority: " priority) %) lines)
                (conj (vec lines) (str "priority: " priority)))]
    (str (str/join "\n" lines) "\n\n" body)))

(defn prioritized-name [filename priority]
  (if (re-find #"^[0-9]{2}_" filename)
    (str priority (subs filename 2))
    (str priority "_" filename)))

(defn claim-handoff!
  "Take a queued handoff away from its role before rewriting it: an atomic
  rename to a hidden, non-.handoff name in the same inbox. 409 when the role
  picked it up first."
  [path name]
  (let [claim (fs/path (fs/parent path) (str ".claim_" (fs/file-name path) ".part"))]
    (move-or-conflict! path claim name)
    claim))

(defn reprioritize-handoff! [path priority name]
  (let [dest (fs/path (fs/parent path) (prioritized-name (str (fs/file-name path)) priority))]
    (when (and (not= (str dest) (str path)) (fs/exists? dest))
      (conflict! (str "A queued handoff named " (fs/file-name dest) " already exists.")))
    (let [claim (claim-handoff! path name)]
      (spit (str claim) (with-priority-header (slurp (str claim)) priority))
      (try
        (fs/move claim dest {:atomic-move true})
        (catch java.nio.file.FileAlreadyExistsException _
          (fs/move claim path {:atomic-move true})
          (conflict! (str "A queued handoff named " (fs/file-name dest) " already exists.")))))))

(defn reprioritize-task!
  "Change the priority of a queued card: the priority header and the
  NN_ filename prefix that orders inbox/new."
  [root name priority]
  (let [priority (or (normalize-priority priority) (bad-request! "Missing priority"))
        {:keys [queued]} (queued-card-handoffs root name)]
    (when (empty? queued)
      (conflict! (str "Card has no queued handoff to reorder: " name)))
    (doseq [path queued
            ;; merge-only copies keep 00 so every role merges before new work
            :when (not= "true" (get-in (parse-message path) [:headers "non-forwarding"]))]
      (reprioritize-handoff! path priority name))))

(defn set-task-level!
  "Set a card's level. Its forward mail still waiting in an inbox (or held
  in an outbox while paused) is reordered now; a card in progress gets the
  level from its next handoff. A batch that carries other cards keeps its
  priority."
  [root name level]
  (let [level (or (normalize-level level) (bad-request! "Missing level"))
        task (or (task-by-name root name)
                 (throw (ex-info (str "Unknown task name: " name) {:http-status 404})))
        priority (handoff-lib/level-priority level)]
    (when (= "done" (:lane task))
      (conflict! (str "Card is done: " name)))
    (handoff-lib/update-card-meta! root (:id task) #(assoc % :level level))
    (doseq [path (task-handoffs root (:id task) name)
            :let [headers (:headers (parse-message path))]
            :when (and (= :queued (handoff-state path))
                       (or (not (outbox-mail? path)) (held-handoff? root path))
                       (not= "true" (get headers "non-forwarding"))
                       (= 1 (count (handoff-task-ids path))))]
      (reprioritize-handoff! path priority name))))

(defn post-task-links [root body]
  (let [{:keys [name project] :as opts} (json/parse-string (or body "{}") true)]
    (json-action #(let [dest (project-dest root project)
                        task (or (task-by-name dest name)
                                 (throw (ex-info (str "Unknown task name: " name) {:http-status 404})))]
                    (set-card-links! dest (:id task) opts)))))

(defn swarm-command!
  "Run a swarmforge.bb subcommand (drain, resume, daemon) for root, the same
  way ./swarm does."
  [root command what]
  (let [result (sh "bb" (str (fs/path script-dir "swarmforge.bb")) command (str root))]
    (when-not (zero? (:exit result))
      (throw (ex-info (str/trim (str "Could not " what ": " (:err result) (:out result)))
                      {:http-status 500})))))

(def restart-lock (Object.))

(defn restart-daemon! [root]
  ;; Two overlapping restarts could stop, stop, start, start and leave two
  ;; daemons delivering the same outboxes.
  (locking restart-lock
    (swarm-command! root "daemon" "restart the handoff daemon")))

(defn post-restart-daemon [root body]
  (let [{:keys [project]} (json/parse-string (or body "{}") true)]
    (json-action #(restart-daemon! (project-dest root project)))))

(defn post-pause [root body]
  (let [{:keys [project]} (json/parse-string (or body "{}") true)]
    (json-action #(swarm-command! (project-dest root project) "drain" "pause the swarm"))))

(defn post-resume [root body]
  (let [{:keys [project]} (json/parse-string (or body "{}") true)]
    (json-action #(swarm-command! (project-dest root project) "resume" "resume the swarm"))))

(defn post-dequeue-task [root body]
  (let [{:keys [name project]} (json/parse-string (or body "{}") true)]
    (json-action #(dequeue-task! (project-dest root project) name))))

(defn post-task-priority [root body]
  (let [{:keys [name priority level project]} (json/parse-string (or body "{}") true)]
    (json-action #(if (str/blank? level)
                    (reprioritize-task! (project-dest root project) name priority)
                    (set-task-level! (project-dest root project) name level)))))
