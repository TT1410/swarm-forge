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

(defn task-entry [root line]
  (let [row (card-type/parse-row root line)]
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

(defn grok-thought-line? [line]
  (boolean (re-find #"^\s*┃" (or line ""))))

(defn grok-tool-start-line? [line]
  (boolean (re-find #"^\s*(?:◆|\$)\s" (or line ""))))

(defn strip-terminal-controls [text]
  (-> (or text "")
      (str/replace #"\u001b\][^\u0007\u001b]*(?:\u0007|\u001b\\)" "")
      (str/replace #"\u001b\[[0-?]*[ -/]*[@-~]" "")
      (str/replace #"[\u0000-\u0008\u000b\u000c\u000e-\u001a\u001c-\u001f\u007f]" "")))

(defn grok-plan-line? [line]
  (boolean (re-find #"^\s*(?:▶|□|☐|☑|✓|✔)\s+" (or line ""))))

(defn grok-timer-line? [line]
  (let [n (str/lower-case (fold-apostrophe (str/trim (or line ""))))]
    (boolean
     (or (re-find #"^(?:[\u2800-\u28ff]\s*)?(?:thinking|waiting for response|working)\b" n)
         (re-find #"^(?:[\u2800-\u28ff]\s*)?[0-9]+(?:\.[0-9]+)?s(?:\s+.*)?$" n)
         (and (re-find #"\b[0-9]+(?:\.[0-9]+)?s\b" n)
              (or (str/includes? n "⇣")
                  (str/includes? n "tokens")
                  (str/includes? n "esc to interrupt")))))))

(defn grok-chrome-line? [line]
  (let [n (str/lower-case (fold-apostrophe (str/trim (or line ""))))]
    (boolean
     (or (str/blank? n)
         (grok-plan-line? line)
         (grok-timer-line? line)
         (mail-banner? n)
         (pane-chrome? n)
         (re-find #"^notify:" n)
         (re-find #"^\[req-[^]]+\]" n)
         (re-find #"^worked for\b" n)
         (re-find #"^grok\s" n)
         (re-find #"^❯" n)
         (re-find #"waiting for response" n)
         (re-find #"^always-approve\b" n)
         (re-find #"^enter:send\b" n)
         (re-find #"·\s*/help\b" n)
         (re-find #"^…\s*\+\d+\s+lines\b" n)))))

(defn grok-prose-text [text]
  (:text
   (reduce
    (fn [{:keys [in-tool? text]} line]
      (cond
        (grok-thought-line? line)
        {:in-tool? false :text (conj text "")}

        (grok-tool-start-line? line)
        {:in-tool? true :text (conj text "")}

        in-tool?
        {:in-tool? true :text text}

        (grok-chrome-line? line)
        {:in-tool? false :text (conj text "")}

        :else
        {:in-tool? false :text (conj text (str/trim line))}))
    {:in-tool? false :text []}
    (str/split-lines (strip-terminal-controls text)))))

(defn complete-status-sentence? [sentence]
  (boolean
   (and (not (str/blank? sentence))
        (not (grok-chrome-line? sentence))
        (re-find #"[.!?…][\"'’”`)\]]*$" (str/trim sentence)))))

(defn grok-prose-sentences [text]
  (-> (str/join "\n" (grok-prose-text text))
      (str/replace #"-[ \t]*\n[ \t]*([a-z0-9])" "-$1")
      pane-sentences
      (->> (filterv complete-status-sentence?))))

(def grok-log-initial-bytes (* 8 1024 1024))
(def grok-message-history 8)

(defn grok-home []
  (fs/path (or (not-empty (System/getenv "SWARMFORGE_GROK_HOME"))
               (not-empty (System/getenv "GROK_HOME"))
               (str (fs/path (System/getProperty "user.home") ".grok")))))

(defn grok-path-identity [path]
  (when path
    (str (try
           (fs/canonicalize path)
           (catch Exception _
             (fs/absolutize path))))))

(defn grok-file-stamp [path]
  (when (fs/regular-file? path)
    [(fs/size path) (str (fs/last-modified-time path))]))

(defn active-grok-sessions []
  (let [file (fs/path (grok-home) "active_sessions.json")
        key (str file)
        stamp (grok-file-stamp file)
        cached (get @grok-active-sessions-cache key)]
    (if (= stamp (:stamp cached))
      (:sessions cached)
      (let [sessions (if stamp
                       (try
                         (let [parsed (json/parse-string (slurp (str file)) true)]
                           (if (sequential? parsed) (vec parsed) []))
                         (catch Exception _ []))
                       [])]
        (swap! grok-active-sessions-cache assoc key {:stamp stamp :sessions sessions})
        sessions))))

(defn active-grok-session [cwd]
  (let [wanted (grok-path-identity cwd)]
    (->> (active-grok-sessions)
         (filter #(= wanted (grok-path-identity (:cwd %))))
         (sort-by #(or (:opened_at %) ""))
         last)))

(defn encoded-grok-cwd [cwd]
  (-> (java.net.URLEncoder/encode (str cwd) "UTF-8")
      (str/replace "+" "%20")))

(defn grok-updates-path [cwd session-id]
  (when (and (not (str/blank? cwd))
             (safe-paths/internal-id? (str session-id)))
    (let [path (fs/path (grok-home) "sessions" (encoded-grok-cwd cwd)
                        (str session-id) "updates.jsonl")]
      (when (fs/regular-file? path) path))))

(defn last-newline-index [bytes]
  (loop [i (dec (alength bytes))]
    (cond
      (neg? i) nil
      (= 10 (bit-and 0xff (aget bytes i))) i
      :else (recur (dec i)))))

(defn first-newline-index [bytes end]
  (loop [i 0]
    (cond
      (>= i end) nil
      (= 10 (bit-and 0xff (aget bytes i))) i
      :else (recur (inc i)))))

(defn read-grok-log-chunk [path offset]
  (with-open [file (java.io.RandomAccessFile. (str path) "r")]
    (let [length (.length file)
          continuing? (and (some? offset) (<= (long offset) length))
          start (if continuing?
                  (long offset)
                  (max 0 (- length grok-log-initial-bytes)))
          byte-count (int (- length start))
          bytes (byte-array byte-count)]
      (.seek file start)
      (.readFully file bytes)
      (if-let [end (last-newline-index bytes)]
        (let [begin (if (or continuing? (zero? start))
                      0
                      (inc (or (first-newline-index bytes end) end)))
              text (if (< begin (inc end))
                     (String. bytes begin (- (inc end) begin)
                              java.nio.charset.StandardCharsets/UTF_8)
                     "")]
          {:offset (+ start end 1)
           :lines (str/split-lines text)
           :reset? (not continuing?)})
        {:offset start :lines [] :reset? (not continuing?)}))))

(defn blank-grok-log-state []
  {:offset nil :sequence 0 :messages [] :plan nil :active? false})

(defn grok-message-key [event]
  (let [meta (get-in event [:params :_meta])]
    [(or (:promptId meta) "")
     (or (:streamStartMs meta) (:eventId meta) (:timestamp event))]))

(defn append-grok-message [messages key text sequence]
  (let [last-message (peek messages)
        updated (if (= key (:key last-message))
                  (conj (pop messages)
                        (assoc last-message
                               :text (str (:text last-message) text)
                               :sequence sequence))
                  (conj messages {:key key :text text :sequence sequence}))]
    (vec (take-last grok-message-history updated))))

(def active-grok-update-types
  #{"user_message_chunk" "agent_message_chunk" "agent_thought_chunk"
    "tool_call" "tool_call_update" "plan"})

(defn apply-grok-update [state event]
  (let [event-update (get-in event [:params :update])
        kind (:sessionUpdate event-update)
        sequence (inc (:sequence state))
        state (assoc state :sequence sequence)
        state (cond
                (= "turn_completed" kind) (assoc state :active? false)
                (contains? active-grok-update-types kind) (assoc state :active? true)
                :else state)]
    (cond
      (= "agent_message_chunk" kind)
      (let [text (get-in event-update [:content :text])]
        (if (string? text)
          (update state :messages append-grok-message
                  (grok-message-key event) text sequence)
          state))

      (= "plan" kind)
      (assoc state :plan {:entries (vec (:entries event-update)) :sequence sequence})

      :else state)))

(defn parse-grok-update [line]
  (try
    (json/parse-string line true)
    (catch Exception _ nil)))

(defn refresh-grok-log [path]
  (let [key (str path)]
    (locking grok-log-status-cache
      (let [cached (get @grok-log-status-cache key (blank-grok-log-state))
            chunk (read-grok-log-chunk path (:offset cached))
            base (if (:reset? chunk) (blank-grok-log-state) cached)
            refreshed (reduce apply-grok-update
                              (assoc base :offset (:offset chunk))
                              (keep parse-grok-update (:lines chunk)))]
        (swap! grok-log-status-cache assoc key refreshed)
        refreshed))))

(defn markdown-status-paragraph [paragraph]
  (let [joined (->> (str/split-lines (or paragraph ""))
                    (map str/trim)
                    (remove #(or (str/blank? %)
                                 (re-find #"^```" %)
                                 (re-find #"^#{1,6}\s" %)))
                    (map #(str/replace % #"^(?:[-*+]\s+|\d+[.)]\s+)" ""))
                    (str/join " "))
        text (-> joined
                 (str/replace #"[*_`]" "")
                 (str/replace #"\s+" " ")
                 str/trim)
        sentences (filterv complete-status-sentence? (pane-sentences text))]
    (when (seq sentences)
      (str/join " " (take 2 sentences)))))

(defn grok-message-status [text]
  (some markdown-status-paragraph (str/split (or text "") #"\n\s*\n")))

(defn grok-plan-status [{:keys [entries]}]
  (when-let [entry (some #(when (= "in_progress" (:status %)) %) entries)]
    (let [text (-> (or (:content entry) "")
                   (str/replace #"\s+" " ")
                   str/trim)]
      (when-not (str/blank? text)
        (if (complete-status-sentence? text) text (str text "."))))))

(defn grok-log-lines [{:keys [messages plan]}]
  (let [message-items (keep (fn [{:keys [text sequence]}]
                              (when-let [status (grok-message-status text)]
                                {:text status :sequence sequence}))
                            messages)
        plan-item (when-let [status (grok-plan-status plan)]
                    {:text status :sequence (:sequence plan)})]
    (->> (cond-> (vec message-items) plan-item (conj plan-item))
         (sort-by :sequence)
         (map :text)
         distinct
         (take-last 2)
         vec)))

(defn grok-status-for-row [row]
  (when-let [session (active-grok-session (nth row 2 nil))]
    (when-let [path (grok-updates-path (:cwd session) (:session_id session))]
      (let [state (refresh-grok-log path)]
        {:lines (grok-log-lines state)
         :active? (:active? state)
         :session-id (:session_id session)}))))

(defn pane-cache-key [root role]
  [(str root) (str role)])

(defn matching-status-sentences [text backend]
  (let [sample (pane-sample text backend)]
    (if (= "grok" backend)
      (grok-prose-sentences sample)
      (let [tail (last-n-lines sample 20)
            joined-tail (str/join "\n" tail)
            from-sentences (filterv status-sentence? (pane-sentences joined-tail))]
        (if (= "codex" backend)
          (let [bullets (->> (codex-bullets sample)
                             (remove codex-throwaway-bullet?)
                             vec)]
            (if (seq bullets) bullets from-sentences))
          from-sentences)))))

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

(defn read-board-tasks [root]
  (mapv #(task-entry root %) (lines (pack-board root "list"))))

(defn board-tasks [root]
  (if-not *board-tasks-cache*
    (read-board-tasks root)
    (let [key (str (fs/absolutize root))]
      (if-let [cached (find @*board-tasks-cache* key)]
        (val cached)
        (let [loaded (read-board-tasks root)]
          (swap! *board-tasks-cache* assoc key loaded)
          loaded)))))

(defn pane-status-lines-for [root role]
  (let [row (role-row root role)
        backend (when row (backend-name row))
        grok-status (when (and row (= "grok" backend) (nil? *pane-text*))
                      (grok-status-for-row row))
        transcript-lines (:lines grok-status)]
    (if row
      (if (seq transcript-lines)
        (im-status-lines (pane-cache-key root role)
                         (str/join "\n" transcript-lines)
                         "grok")
        (im-status-lines (pane-cache-key root role)
                         (live-pane-text root role)
                         backend))
      [])))

(defn pane-status-for [root role]
  (or (last (pane-status-lines-for root role)) ""))

(defn active-card-names [root role]
  (let [row (role-row root role)
        names (when row (in-process-task-names root (in-process-for-row row)))
        cards (filter #(= role (:lane %)) (board-tasks root))]
    (if (seq names)
      (set names)
      (if (= 1 (count cards))
        #{(:name (first cards))}
        #{}))))

(defn rejected-task? [root name]
  (and (safe-paths/task-name? name)
       (fs/exists? (safe-paths/task-path! (fs/path root ".swarmforge" "notify")
                                          (str "reject-" name) ""))))

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
        task-id (:id task)
        rejected? (rejected-task? root name)
        approval? (or (contains? (pending-approval-ids root) task-id)
                      (contains? (pending-approval-names root) name))
        active? (contains? (active-card-names root role) name)
        socket (tmux-socket root)
        row (role-row root role)
        session-down? (and active? socket row
                           (not (session-alive? socket (session-name row))))
        [phase status] (cond
                         (= "done" role) ["done" ""]
                         rejected? ["rejected" "REJECTED"]
                         approval? ["awaiting approval" "Waiting for approval"]
                         (= "waiting" role) ["waiting" "Waiting to start"]
                         active? [(if session-down? "no session" "working")
                                  (pane-status-for root role)]
                         :else ["queued" "waiting in queue"])]
    (assoc task :status status :status_phase phase)))

(defn batch-task-names [root dir]
  (in-process-task-names root (handoff-files dir)))

(defn multi-batches [root dir]
  (for [b (batch-dirs dir)
        :let [names (batch-task-names root b)]
        :when (next names)]
    [(fs/file-name b) names]))

(defn propagated-batches [root dir]
  (for [path (handoff-files dir)
        :let [names (in-process-task-names root [path])]
        :when (next names)]
    [(str "handoff-" (fs/file-name path)) names]))

(defn indexed-batches [root dir]
  (concat (multi-batches root dir)
          (propagated-batches root dir)))

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
                               (concat (indexed-batches root (fs/path wt ".swarmforge" "handoffs" "inbox" "completed"))
                                       (indexed-batches root (in-process-dir wt)))))))
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
         :status_phase "merging"
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
