;; Pane-status sentences and board task status. Loaded into pack-web.

(defn lines [text]
  (->> (str/split-lines (or text ""))
       (remove str/blank?)
       vec))

(defn lanes [root]
  (lines (pack-board root "lanes")))

(defn display-lanes [root]
  (vec (concat ["waiting"] (lanes root) ["done"])))

(defn master-role [root]
  (str/trim (pack-board root "master-lane")))

(defn task-entry [line]
  (let [row (card-type/parse-row line)]
    {:name (:name row)
     :id (:id row)
     :lane (:lane row)
     :updated_at (:updated row)
     :audit_count (:audit-count row)
     :type (:type row)}))

(defn last-n-lines [text n]
  (vec (take-last n (str/split-lines (or text "")))))

(defn pane-sentences [text]
  (->> (str/split-lines (or text ""))
       (map str/trim)
       (remove str/blank?)
       (str/join " ")
       (#(str/split % #"(?<=[.!?…])\s+"))
       (map str/trim)
       (remove str/blank?)
       vec))

(defn fold-apostrophe [s]
  (str/replace (or s "") "\u2019" "'"))

(defn i-status? [sentence]
  (boolean (re-find #"\bI(?:'(?:ll|m|ve))?\b" (fold-apostrophe sentence))))

(defn other-status? [sentence]
  (let [n (str/lower-case (fold-apostrophe sentence))]
    (boolean (or (re-find #"\blet me\b" n)
                 (re-find #"hand off" n)
                 (re-find #"handing off" n)
                 (re-find #"handoff" n)
                 (re-find #"continue" n)
                 (re-find #"\breceived\b" n)
                 (re-find #"\breceiving\b" n)
                 (re-find #"\bsettled\b" n)
                 (re-find #"\bresolved\b" n)
                 (re-find #"\bcompleted\b" n)
                 (re-find #"\bcomplete\b" n)
                 (re-find #"\bcommitted\b" n)
                 (re-find #"\bloaded\b" n)
                 (re-find #"\bprepared\b" n)
                 (re-find #"\bconfirming\b" n)
                 (re-find #"\brepeating\b" n)
                 (re-find #"\btightening\b" n)
                 (re-find #"\buncovered\b" n)
                 (re-find #"\bcorrections\b" n)
                 (re-find #"\bparse(?:s|d)?\b" n)
                 (re-find #"\breview(?:ing|ed)?\b" n)
                 (re-find #"\bwriting\b" n)
                 (re-find #"\bdefining\b" n)
                 (re-find #"\bspecifying\b" n)
                 (re-find #"\bchecking\b" n)
                 (re-find #"\breading\b" n)
                 (re-find #"\bfound\b" n)))))

(defn tool-trace? [sentence]
  (boolean (re-find #"(?i)^(?:•\s*)?(?:Ran|Edited|Added)\b"
                    (fold-apostrophe sentence))))

(defn mail-banner? [sentence]
  (let [n (str/lower-case (fold-apostrophe sentence))]
    (boolean (or (re-find #"you have new handoff mail" n)
                 (re-find #"you have a reverse merge" n)
                 (re-find #"if idle, run ready_for_next" n)
                 (re-find #"rejected:" n)))))

(defn pane-chrome? [sentence]
  (let [n (str/lower-case (fold-apostrophe sentence))]
    (boolean (or (re-find #"to view transcript" n)
                 (re-find #"running the handoff command again" n)))))

(defn status-sentence? [sentence]
  (and (not (mail-banner? sentence))
       (not (tool-trace? sentence))
       (not (pane-chrome? sentence))
       (or (i-status? sentence) (other-status? sentence))))

(defn strip-bullet [sentence]
  (str/replace (or sentence "") #"^[•*]\s*" ""))

(defn codex-throwaway-bullet? [sentence]
  (let [n (str/lower-case (fold-apostrophe (strip-bullet sentence)))]
    (boolean (or (re-find #"^(?:working|ran|edited|added|searching|searched)\b" n)
                 (re-find #"you have \d+ usage limit reset available" n)
                 (mail-banner? sentence)
                 (pane-chrome? sentence)))))

(defn codex-bullets [text]
  (loop [lines (mapv str/trim (str/split-lines (or text "")))
         current nil
         out []]
    (if-let [line (first lines)]
      (cond
        (str/blank? line)
        (recur (next lines) current out)

        (re-find #"^[•*]\s*" line)
        (recur (next lines) line (cond-> out current (conj current)))

        current
        (recur (next lines) (str current " " line) out)

        :else
        (recur (next lines) current out))
      (cond-> out current (conj current)))))

(defn pane-cache-key [root role]
  [(str root) (str role)])

(defn matching-status-sentences [text backend]
  (let [sample (pane-sample text backend)
        tail (last-n-lines sample 20)
        joined-tail (str/join "\n" tail)
        from-sentences (filterv status-sentence? (pane-sentences joined-tail))]
    (if (= "codex" backend)
      (let [bullets (->> (codex-bullets sample)
                         (remove codex-throwaway-bullet?)
                         vec)]
        (if (seq bullets) bullets from-sentences))
      from-sentences)))

(defn im-status-lines [role text backend]
  (let [found (vec (take-last 2 (matching-status-sentences text backend)))]
    (if (seq found)
      (do (swap! pane-status-lines assoc role found)
          (swap! pane-status assoc role (last found))
          found)
      (or (not-empty (get @pane-status-lines role))
          (let [one (get @pane-status role "")]
            (if (str/blank? one) [] [one]))))))

(defn im-status [role text backend]
  (or (last (im-status-lines role text backend)) ""))

(defn board-tasks [root]
  (mapv task-entry (lines (pack-board root "list"))))

(defn pane-status-lines-for [root role]
  (let [row (role-row root role)
        text (when row (live-pane-text root role))
        backend (when row (backend-name row))]
    (if row
      (im-status-lines (pane-cache-key root role) text backend)
      [])))

(defn pane-status-for [root role]
  (or (last (pane-status-lines-for root role)) ""))

(defn active-card-names [root role]
  (let [row (role-row root role)
        names (when row (in-process-task-names (in-process-for-row row)))
        cards (filter #(= role (:lane %)) (board-tasks root))]
    (if (seq names)
      (set names)
      (if (= 1 (count cards))
        #{(:name (first cards))}
        #{}))))

(defn rejected-task? [root name]
  (fs/exists? (fs/path root ".swarmforge" "notify" (str "reject-" name))))

(defn pending-approval-ids [root]
  (->> (approvals root)
       (map :task_id)
       (remove str/blank?)
       set))

(defn pending-approval-names [root]
  (->> (approvals root)
       (map :task)
       (remove str/blank?)
       set))

(defn task-with-status [root task]
  (let [role (:lane task)
        name (:name task)
        task-id (:id task)]
    (assoc task :status
           (cond
             (= "done" role) ""
             (rejected-task? root name) "REJECTED"
             (or (contains? (pending-approval-ids root) task-id)
                 (contains? (pending-approval-names root) name)) "Waiting for approval"
             (= "waiting" role) "Waiting to start"
             (contains? (active-card-names root role) name)
             (pane-status-for root role)
             :else "waiting in queue"))))

(defn batch-task-names [dir]
  (in-process-task-names (handoff-files dir)))

(defn multi-batches [dir]
  (for [b (batch-dirs dir)
        :let [names (batch-task-names b)]
        :when (next names)]
    [(fs/file-name b) names]))

(defn index-batches [idx pairs]
  (reduce (fn [m [id names]]
            (reduce #(assoc %1 %2 id) m names))
          idx
          pairs))

(defn batch-index [root]
  (reduce (fn [idx row]
            (let [wt (nth row 2)]
              (if (str/blank? wt)
                idx
                (index-batches idx
                               (concat (multi-batches (fs/path wt ".swarmforge" "handoffs" "inbox" "completed"))
                                       (multi-batches (in-process-dir wt)))))))
          {}
          (role-rows root)))

(defn reverse-handoff? [path]
  (let [h (:headers (parse-message path))]
    (and (= "git_handoff" (get h "type"))
         (= "true" (get h "non-forwarding")))))

(defn merging-card [root row]
  (when-let [file (first (filter reverse-handoff? (in-process-for-row row)))]
    (let [h (:headers (parse-message file))
          name (or (get h "task") (get h "task_id"))
          role (first row)
          sender (str/trim (or (get h "from") ""))]
      (when-not (str/blank? name)
        {:name name
         :id (str "merging-" (or (get h "task_id") name))
         :lane role
         :updated_at (or (not-empty (get h "dequeued_at")) "")
         :audit_count 0
         :merging true
         :status (str "Merging " sender)}))))

(defn merging-cards [root]
  (vec (keep #(merging-card root %) (role-rows root))))

(defn tasks [root]
  (let [idx (batch-index root)
        board (mapv (fn [task]
                      (if-let [batch (get idx (:name task))]
                        (assoc (task-with-status root task) :batch batch)
                        (task-with-status root task)))
                    (board-tasks root))]
    (into (merging-cards root) board)))

(defn parse-message [path]
  (let [content (slurp (str path))
        [header body] (str/split content #"\n\n" 2)
        headers (into {}
                      (for [line (str/split-lines header)
                            :let [[k v] (str/split line #": " 2)]
                            :when (and k v)]
                        [k v]))]
    {:headers headers :body (or body "")}))

(defn comma-list [text]
  (->> (str/split (or text "") #",")
       (map str/trim)
       (remove str/blank?)
       vec))
