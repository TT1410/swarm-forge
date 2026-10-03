;; Approval write, git rollback, and retry. Loaded into pack-web.

(defn pending-file [root id]
  (safe-paths/id-path! (pending-dir root) id ".handoff"))

(defn require-pending! [root id]
  (safe-paths/require-internal-id! id)
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
    (safe-paths/require-task-name! task)
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
  (let [dir (safe-paths/state-key-path! (fs/path root ".swarmforge" "board" "audits")
                                        task-id "")]
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
      (let [file (safe-paths/id-path!
                  (safe-paths/state-key-path! (fs/path root ".swarmforge" "board" "audits")
                                              task-id "")
                  (str n) ".md")]
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
         (filter #(some wanted (handoff-task-ids %)))
         vec)))

(defn strip-header! [path field]
  (let [prefix (str field ": ")
        [head body] (str/split (slurp (str path)) #"\n\n" 2)
        kept (remove #(str/starts-with? % prefix) (str/split-lines head))]
    (spit (str path) (str (str/join "\n" kept) "\n\n" body))))

(defn header-card-ids
  "Every card a handoff carries: its batch_task_ids, or else its task."
  [headers]
  (let [batch (header-batch-task-ids headers)]
    (vec (distinct (remove str/blank?
                           (if (seq batch)
                             batch
                             [(or (not-empty (get headers "task_id")) (get headers "task"))]))))))

(defn later-git-handoff-in?
  "Another git handoff from the sender, for other cards, among paths whose
  commit contains the held commit."
  [wt sender commit wanted paths]
  (some (fn [path]
          (let [headers (:headers (parse-message path))
                other (get headers "commit")]
            (and (= "git_handoff" (get headers "type"))
                 (= sender (get headers "from"))
                 (not-any? wanted (handoff-task-ids path))
                 (not (str/blank? other))
                 (git-ok? wt "merge-base" "--is-ancestor" commit other))))
        paths))

(defn later-handoff-on-top?
  "Another git handoff from the sender, still held or queued, whose commit
  contains the held commit."
  [root wt sender commit wanted]
  (boolean
   (and commit (git-repo? wt)
        (later-git-handoff-in?
         wt sender commit wanted
         (concat (glob-handoffs (fs/path root ".swarmforge" "handoffs" "pending_approval"))
                 (glob-handoffs (fs/path wt ".swarmforge" "handoffs" "outbox"))
                 (glob-handoffs (fs/path root ".swarmforge" "handoffs" "outbox")))))))

(defn head-built-on?
  "The worktree HEAD is a later commit that contains the held commit."
  [wt commit]
  (boolean
   (and commit (git-repo? wt)
        (git-ok? wt "rev-parse" "--verify" commit)
        (not= (git! wt "rev-parse" "HEAD") (git! wt "rev-parse" commit))
        (git-ok? wt "merge-base" "--is-ancestor" commit "HEAD"))))

(defn later-delivery-on-top?
  "HEAD moved past the held commit and the sender already delivered a git
  handoff built on it: that later card is out, so HEAD must stay."
  [wt sender commit wanted]
  (boolean
   (and (head-built-on? wt commit)
        (later-git-handoff-in? wt sender commit wanted
                               (glob-handoffs (fs/path wt ".swarmforge" "handoffs" "sent"))))))

(defn sender-moved-on?
  "True when the sender went on to another card after the held handoff: it
  holds in-process work for another card, or another of its handoffs, held,
  queued or already delivered, is built on the held commit. Then rewinding
  the worktree would throw that work away."
  [root wt sender commit headers]
  (let [in-proc (glob-handoffs (fs/path wt ".swarmforge" "handoffs" "inbox" "in_process"))
        wanted (set (header-card-ids headers))]
    (boolean (or (some #(not-any? wanted (handoff-task-ids %)) in-proc)
                 (later-handoff-on-top? root wt sender commit wanted)
                 (later-delivery-on-top? wt sender commit wanted)))))

(defn requeue-retry!
  "Queue the retry at the head of the sender's inbox instead of rewinding
  its worktree, which already holds later work."
  [wt headers]
  (let [task-id (or (not-empty (get headers "task_id")) (get headers "task"))
        task (or (get headers "task") task-id)
        ids (header-card-ids headers)
        new-dir (fs/path wt ".swarmforge" "handoffs" "inbox" "new")
        file (fs/path new-dir (str "00_retry_" (str/replace (or task-id "task") #"[^A-Za-z0-9]+" "_") ".handoff"))]
    (doseq [path (distinct (mapcat #(task-inbox-files wt "completed" % nil) ids))]
      (strip-header! path "handed_task_ids"))
    (fs/create-dirs new-dir)
    (spit (str file)
          (str "from: (Retry)\n"
               "to: " (get headers "from") "\n"
               "priority: 00\n"
               "type: note\n"
               "task_id: " task-id "\n"
               "task: " task "\n"
               (when (next ids) (str "batch_task_ids: " (pr-str ids) "\n"))
               "\n"
               "Retry audit. Redo this card on top of the current tree.\n"))))

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

(defn restore-task-base!
  "Put the mail of every card the held handoff carried back in process, so
  the sender redoes them."
  [root headers]
  (let [task-id (or (not-empty (get headers "task_id")) (get headers "task"))
        task (get headers "task")
        ids (header-card-ids headers)
        wt (worktree-for root (get headers "from"))
        in-proc (task-inbox-files wt "in_process" task-id task)
        done (->> ids
                  (mapcat #(task-inbox-files wt "completed" % nil))
                  distinct)]
    (cond
      (seq in-proc) nil
      (seq done)
      (let [dest-dir (fs/path wt ".swarmforge" "handoffs" "inbox" "in_process")]
        (fs/create-dirs dest-dir)
        (doseq [src done
                :let [dest (fs/path dest-dir (fs/file-name src))]]
          (fs/move src dest {:replace-existing true})
          (strip-header! dest "handed_task_ids")))
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
        reviews (read-reviews root id)
        moved-on? (sender-moved-on? root wt (get headers "from") commit headers)]
    (doseq [path (approval-doc-paths headers reviews)]
      (append-task-review! root task-id path comments))
    (when commit
      (snapshot-rejected! root task-id commit n)
      (when-not moved-on?
        (restore-commit! wt commit)))
    (fs/delete-if-exists src)
    (drop-reviews! root id)
    (drop-task-audits! root task-id task)
    (if moved-on?
      (requeue-retry! wt headers)
      (restore-task-base! root headers))
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
    (when (sender-moved-on? root wt (get headers "from") commit headers)
      (conflict! (str "The " (get headers "from") " has moved on past this card; Delete would discard later work. Use Retry: " task)))
    (when (next (header-card-ids headers))
      (conflict! (str "This handoff carries several cards; use Retry: " task)))
    (when (some #(and (not= :history (handoff-state %)) (next (handoff-task-ids %)))
                (task-handoffs root task-id task))
      (conflict! (str "Live mail carries this card together with other cards; use Retry: " task)))
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
      {:id (safe-paths/require-internal-id!
            (java.net.URLDecoder/decode id "UTF-8"))
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
