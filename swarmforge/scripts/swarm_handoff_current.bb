;; In-process current work and reverse-lane fill. Loaded into swarm-handoff.

(declare commit-descends-from?)

(defn in-process-dir []
  (fs/path (System/getProperty "user.dir") ".swarmforge" "handoffs" "inbox" "in_process"))

(defn handoff-files [dir]
  (if (fs/exists? dir)
    (->> (fs/list-dir dir)
         (filter #(and (fs/regular-file? %) (str/ends-with? (fs/file-name %) ".handoff")))
         (sort-by #(fs/file-name %))
         vec)
    []))

(defn batch-dirs [dir]
  (if (fs/exists? dir)
    (->> (fs/list-dir dir)
         (filter #(and (fs/directory? %) (str/starts-with? (fs/file-name %) "batch_")))
         (sort-by #(fs/file-name %))
         vec)
    []))

(def batch-manifest-filename "batch_manifest.edn")

(defn current-batch-dir []
  (let [batches (batch-dirs (in-process-dir))]
    (when (= 1 (count batches))
      (first batches))))

(defn batch-manifest [batch-dir]
  (let [path (fs/path batch-dir batch-manifest-filename)]
    (when (fs/regular-file? path)
      (try
        (edn/read-string (slurp (str path)))
        (catch Exception _ ::invalid)))))

(defn valid-current-batch-manifest? [batch-dir manifest]
  (let [files (mapv fs/file-name (handoff-files batch-dir))
        members (:members manifest)
        commits (->> members (keep :commit) distinct vec)
        task-ids (->> members (mapcat :task-ids) distinct vec)
        selected (:selected-commit manifest)]
    (and (map? manifest)
         (= 1 (:version manifest))
         (= (fs/file-name batch-dir) (:batch-id manifest))
         (vector? members)
         (every? map? members)
         (= files (mapv :file members))
         (vector? (:task-ids manifest))
         (= task-ids (:task-ids manifest))
         (every? #(and (string? %) (not (str/blank? %))) (:task-ids manifest))
         (or (and (empty? commits) (nil? selected))
             (and (not (str/blank? selected))
                  (contains? (set commits) selected)
                  (every? #(commit-descends-from? % selected) commits))))))

(defn header-field [file field]
  (let [prefix (str field ": ")]
    (some (fn [line]
            (when (str/starts-with? line prefix)
              (subs line (count prefix))))
          (take-while (complement str/blank?) (str/split-lines (slurp (str file)))))))

(defn handoff-task-id [file]
  (or (not-empty (header-field file "task_id"))
      (header-field file "task")))

(defn parsed-task-id-list [value]
  (when-not (str/blank? value)
    (try
      (let [parsed (edn/read-string value)]
        (when (and (vector? parsed)
                   (every? #(and (string? %) (not (str/blank? %))) parsed))
          parsed))
      (catch Exception _ nil))))

(defn handoff-task-ids [file]
  (->> (cons (handoff-task-id file)
             (or (parsed-task-id-list (header-field file "batch_task_ids")) []))
       (remove str/blank?)
       distinct
       vec))

(defn top-batch-task []
  (let [batches (batch-dirs (in-process-dir))]
    (when (= 1 (count batches))
      (when-let [file (first (handoff-files (first batches)))]
        (header-field file "task")))))

(defn top-batch-task-id []
  (let [batches (batch-dirs (in-process-dir))]
    (when (= 1 (count batches))
      (when-let [file (first (handoff-files (first batches)))]
        (handoff-task-id file)))))

(defn in-process-task-files []
  (into (handoff-files (in-process-dir))
        (mapcat handoff-files (batch-dirs (in-process-dir)))))

(defn current-work-task-ids []
  (let [dir (current-batch-dir)
        manifest (some-> dir batch-manifest)]
    (if (and dir (valid-current-batch-manifest? dir manifest))
      (:task-ids manifest)
      (->> (in-process-task-files)
           (mapcat handoff-task-ids)
           distinct
           vec))))

(defn current-work-batch-id []
  (if-let [dir (current-batch-dir)]
    (let [manifest (batch-manifest dir)]
      (if (valid-current-batch-manifest? dir manifest)
        (:batch-id manifest)
        (fs/file-name dir)))
    (some #(not-empty (header-field % "batch_id"))
          (in-process-task-files))))

(defn current-batch-member-commits []
  (when-let [dir (current-batch-dir)]
    (let [manifest (batch-manifest dir)]
      (if (valid-current-batch-manifest? dir manifest)
        (->> (:members manifest) (keep :commit) distinct vec)
        (->> (handoff-files dir)
             (filter #(= "git_handoff" (header-field % "type")))
             (keep #(not-empty (header-field % "commit")))
             distinct
             vec)))))

(defn current-batch-ancestry-errors [canonical-commit]
  (if (or (str/blank? canonical-commit) (nil? (current-batch-dir)))
    []
    (let [missing (remove #(commit-descends-from? % canonical-commit)
                          (current-batch-member-commits))]
      (if (seq missing)
        [(str "Result commit " canonical-commit
              " is incomplete for batch " (current-work-batch-id)
              "; it does not contain incoming member commit(s): "
              (str/join ", " missing) ".")]
        []))))

(defn current-in-process-task-id []
  (when-let [file (first (in-process-task-files))]
    (handoff-task-id file)))

(defn current-in-process-task []
  (when-let [file (first (in-process-task-files))]
    (header-field file "task")))

(defn current-task-base []
  (when-let [file (first (in-process-task-files))]
    (header-field file "task_base_commit")))

(defn current-work-present? []
  (seq (in-process-task-files)))

(defn current-work-state-errors [headers]
  (if-not (= "git_handoff" (get headers "type"))
    []
    (let [files (handoff-files (in-process-dir))
          batches (batch-dirs (in-process-dir))
          empty-batches (filter #(empty? (handoff-files %)) batches)
          invalid-manifests (filter (fn [batch]
                                      (let [manifest (batch-manifest batch)]
                                        (and (some? manifest)
                                             (not (valid-current-batch-manifest?
                                                   batch manifest)))))
                                    batches)]
      (cond-> []
        (and (seq files) (seq batches))
        (conj "Ambiguous current work: both task and batch work are in process.")
        (> (count files) 1)
        (conj "Ambiguous current work: multiple tasks are in process.")
        (> (count batches) 1)
        (conj "Ambiguous current work: multiple batches are in process.")
        (seq empty-batches)
        (conj (str "Ambiguous current work: empty in-process batch "
                   (first empty-batches) "."))
        (seq invalid-manifests)
        (conj (str "Invalid batch manifest: "
                   (fs/path (first invalid-manifests) batch-manifest-filename) "."))))))

(defn complete-current-after-git-handoff! [headers]
  (when (and (= "git_handoff" (get headers "type"))
             (current-work-present?))
    (let [result (sh (str (fs/path script-dir "done_with_current.sh")))]
      (print (:out result))
      (binding [*out* *err*]
        (print (:err result)))
      (when-not (zero? (:exit result))
        (exit! (:exit result) "CURRENT COMPLETION FAILED after handoff queued.")))))

(defn with-lane-task [headers sender]
  (let [cards (board-cards-in-lane sender)
        drafted-id (get headers "task_id")
        drafted (get headers "task")]
    (cond
      (some #(= drafted-id (:id %)) cards) headers
      (not (str/blank? drafted-id)) headers
      (some #(= drafted (:name %)) cards)
      (let [card (first (filter #(= drafted (:name %)) cards))]
        (assoc headers "task_id" (:id card) "task" (:name card)))
      (= 1 (count cards))
      (let [card (first cards)]
        (assoc headers "task_id" (:id card) "task" (:name card)))
      :else headers)))

(defn with-in-process-task [headers]
  (let [id (not-empty (current-in-process-task-id))
        name (not-empty (current-in-process-task))]
    (cond-> headers
      id (assoc "task_id" id)
      name (assoc "task" name))))

(defn with-batch-task-ids [headers]
  (if-not (= "git_handoff" (get headers "type"))
    headers
    (let [ids (current-work-task-ids)
          batch-id (current-work-batch-id)]
      (cond-> headers
        (or batch-id (next ids)) (assoc "batch_task_ids" (pr-str ids))
        batch-id (assoc "batch_id" batch-id)))))

(defn with-board-task [headers sender]
  (if-not (= "git_handoff" (get headers "type"))
    headers
    (cond
      (not (str/blank? (get headers "task_id"))) headers
      (not-empty (current-in-process-task-id)) (with-in-process-task headers)
      :else (let [card (board-card-named (get headers "task"))
                  filled (if card
                           (assoc headers "task_id" (:id card) "task" (:name card))
                           (with-lane-task headers sender))]
              (if (and (str/blank? (get filled "task_id"))
                       (not (str/blank? (get filled "task"))))
                (assoc filled "task_id" (get filled "task"))
                filled)))))

(defn pack-role-names []
  (->> (str/split-lines (slurp (str (roles-file))))
       (remove str/blank?)
       (map #(first (str/split % #"\t")))
       vec))

(defn last-pack-role? [role]
  (= role (last (pack-role-names))))

(defn reverse-roles [sender task]
  (let [card (or (board-card-named task) (first (board-cards-in-lane sender)))
        earlier (if card
                  (card-type/earlier-roles (project-root) (:type card) sender)
                  (let [roles (pack-role-names)
                        idx (.indexOf roles sender)]
                    (if (neg? idx) [] (vec (take idx roles)))))]
    (case (handoff-lib/role-propagation sender)
      "back-one" (vec (take-last 1 earlier))
      "back-all" (vec earlier)
      [])))

(defn terminal-sender? [headers sender]
  (and (= "git_handoff" (get headers "type"))
       (let [card (or (board-card-named (get headers "task"))
                      (first (board-cards-in-lane sender)))]
         (if card
           (card-type/last-on-card? (project-root) (:type card) sender)
           (last-pack-role? sender)))))

(defn with-delivery-kind [headers sender]
  (if (= "git_handoff" (get headers "type"))
    (assoc headers "delivery_kind" (if (terminal-sender? headers sender)
                                      "terminal"
                                      "forward"))
    headers))

(defn with-non-forwarding [headers sender]
  (if (terminal-sender? headers sender)
    (assoc headers "non-forwarding" "true")
    headers))

(defn inbound-handoffs []
  (in-process-task-files))

(defn inbound-non-forwarding? []
  (boolean (some #(= "true" (header-field % "non-forwarding"))
                 (inbound-handoffs))))
