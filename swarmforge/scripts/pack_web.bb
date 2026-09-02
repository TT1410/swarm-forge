#!/usr/bin/env bb

(ns pack-web
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [org.httpkit.server :as http]))

(def script-dir (fs/parent *file*))
(load-file (str (fs/path script-dir "forge.bb")))
(load-file (str (fs/path script-dir "card_type.bb")))

(def usage-text
  (str "Usage:\n"
       "  pack_web.sh --serve <root> [port]\n"
       "  pack_web.sh --test-state <root>\n"
       "  pack_web.sh --test-html\n"
       "  pack_web.sh --test-post-task <root> <name> <text>\n"
       "  pack_web.sh --test-post-chat <root> <text>\n"
       "  pack_web.sh --test-inject-payload [name text]\n"
       "  pack_web.sh --test-inject-argv <root> <file> <text>\n"
       "  pack_web.sh --test-approve <root> <id>\n"
       "  pack_web.sh --test-reject <root> <id>\n"
       "  pack_web.sh --test-pane <root> <role>\n"
       "  pack_web.sh --test-agent-page [role]\n"
       "  pack_web.sh --test-heat <root>\n"
       "  pack_web.sh --test-card-heat <root>\n"
       "  pack_web.sh --test-heat-isolation <root-a> <root-b>\n"
       "  pack_web.sh --test-heat-codex <root>\n"
       "  pack_web.sh --test-heat-reorder <root>\n"
       "  pack_web.sh --test-heat-head <root>\n"
       "  pack_web.sh --test-heat-mail <root>\n"
       "  pack_web.sh --test-heat-grok <root>\n"
       "  pack_web.sh --test-heat-collapse <root>\n"
       "  pack_web.sh --test-status-pane <root> <text>\n"
       "  pack_web.sh --test-status-persist <root> <first> <second>\n"
       "  pack_web.sh --test-answer-clarification <root> <id> <text>\n"
       "  pack_web.sh --test-task <root> <name>\n"
       "  pack_web.sh --test-tree <root> <name> [path]\n"
       "  pack_web.sh --test-file <root> <name> <path>\n"
       "  pack_web.sh --test-delete-task <root> <name>\n"
       "  pack_web.sh --test-delete-approval <root> <id>\n"
       "  pack_web.sh --test-retry-task <root> <id> <comments>\n"
       "  pack_web.sh --test-save-comments <root> <id> <path> <comments>\n"
       "  pack_web.sh --test-doc <root> <path>\n"
       "  pack_web.sh --test-teardown <root> [TEARDOWN]\n"
       "  pack_web.sh --test-new-project <root> <name> <pack> [mission]\n"
       "  pack_web.sh --test-open-project <root> <name>\n"
       "  pack_web.sh --test-close-project <root> <name>\n"
       "  pack_web.sh --test-inferred-name <input> [github]\n"
       "  pack_web.sh --test-mission <root> [project]\n"
       "  pack_web.sh --test-allow <root> <name> <act> [project]\n"
       "  pack_web.sh --test-lieutenant-heat <root>\n"
       "  pack_web.sh --test-pane-merge <history-file> <visible-file>"))

(def example-task-name "htw-console-app")
(def example-task-text
  "Integrate the stories in ~/junk/htw-stories into one console application.")

(def ^:dynamic *tmux-stub* nil)
(def ^:dynamic *pane-text* nil)
(def ^:dynamic *sync-teardown?* false)
(def teardown-delay-ms 250)
(def pane-capture-lines 2000)
(def pane-heat (atom {}))
(def pane-status (atom {}))
(def pane-status-lines (atom {}))

(declare session-name pane-target live-pane-text role-row pane-sample backend-name
         in-process-for-row in-process-task-names approvals
         handoff-files batch-dirs in-process-dir allowed-doc?
         delete-approval! retry-approval! parse-message pane-status-for role-rows
         recorded-pane html-escape worktree-for-role project-query master-role
         session-alive? work-entry)

(defn usage []
  (binding [*out* *err*]
    (println usage-text)))

(defn exit! [status message]
  (binding [*out* *err*]
    (when message
      (println message)))
  (System/exit status))

(defn display-name-for-role [role]
  (->> (str/split (str/replace role #"[-_]" " ") #"\s+")
       (remove str/blank?)
       (map str/capitalize)
       (str/join " ")))

(defn task-payload
  ([] (task-payload example-task-name example-task-text))
  ([name text] (str "Task: " name "\n\n" (or text ""))))

(defn reject-message [task]
  (str "Rejected: " task))

(defn tmux-stub []
  (or *tmux-stub* (System/getenv "SWARMFORGE_TMUX_STUB")))

(defn record-argv! [file argv]
  (when-let [dir (fs/parent file)]
    (fs/create-dirs dir))
  (spit (str file) (str (pr-str (vec argv)) "\n") :append true))

(defn send-keys! [socket session & keys]
  (let [argv (into ["tmux" "-S" socket "send-keys" "-t" session] keys)]
    (if-let [stub (tmux-stub)]
      (record-argv! stub argv)
      (let [result (apply sh argv)]
        (when-not (zero? (:exit result))
          (throw (ex-info "tmux send-keys failed" result)))))))

(defn role-rows [root]
  (let [file (fs/path root ".swarmforge" "roles.tsv")]
    (if (fs/exists? file)
      (->> (str/split-lines (slurp (str file)))
           (remove str/blank?)
           (mapv #(str/split % #"\t" -1)))
      [])))

(defn master-row [root]
  (some #(when (= "master" (nth % 1 nil)) %) (role-rows root)))

(defn master-session [root]
  (when-let [row (master-row root)]
    (session-name row)))

(defn tmux-socket [root]
  (let [file (fs/path root ".swarmforge" "tmux-socket")]
    (when (fs/exists? file)
      (not-empty (str/trim (slurp (str file)))))))

(defn inject-target! [socket target text]
  (when (and socket target (not (str/blank? text)))
    (send-keys! socket target "-l" text)
    (when-not (tmux-stub)
      (Thread/sleep 150))
    (send-keys! socket target "C-m")
    (when-not (tmux-stub)
      (Thread/sleep 50))
    (send-keys! socket target "C-j")))

(defn inject-role! [root role text]
  (try
    (let [socket (tmux-socket root)
          target (when-let [row (role-row root role)]
                   (pane-target row))]
      (when-not (and socket target)
        (throw (ex-info "missing tmux target" {:role role :socket socket})))
      (inject-target! socket target text))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "inject failed role=" role
                      " socket=" (tmux-socket root)
                      " error=" (.getMessage e)))
        (flush)))))

(defn inject-master! [root text]
  (when-let [row (master-row root)]
    (inject-role! root (first row) text)))

(defn write-notify-file! [dir event kvs]
  (let [stamp (str/replace (str (java.time.Instant/now)) #"[^0-9A-Za-z]" "")
        file (fs/path dir (str stamp "-" event ".notify"))]
    (fs/create-dirs dir)
    (spit (str file)
          (str (str/join "\n" (for [[k v] kvs] (str k ": " v)))
               "\n"))
    file))

(defn forge-of [forge-or-pack project-root]
  (if (forge/forge? forge-or-pack)
    forge-or-pack
    (let [parent (fs/parent project-root)
          grand (when parent (fs/parent parent))]
      (when (and parent grand
                 (= "projects" (fs/file-name parent))
                 (fs/directory? (fs/path grand "projects")))
        (str grand)))))

(defn notify-lieutenant! [forge event kvs inject-text]
  (when forge
    (write-notify-file! (fs/path forge ".swarmforge" "notify") event kvs)
    (inject-role! forge "lieutenant" inject-text)))

(defn notify-new-task! [forge-or-pack project-root name]
  (let [forge (forge-of forge-or-pack project-root)
        project (if forge (fs/file-name project-root) "")]
    (if forge
      (notify-lieutenant! forge "new-task"
                          [["event" "new-task"]
                           ["project" project]
                           ["task" name]]
                          (str "Notify: new-task " project "/" name))
      (do
        (write-notify-file! (fs/path project-root ".swarmforge" "notify") "new-task"
                            [["event" "new-task"]
                             ["task" name]])
        (inject-master! project-root (str "Notify: new-task " name))))))

(defn notify-new-project! [forge name]
  (notify-lieutenant! forge "new-project"
                      [["event" "new-project"]
                       ["project" name]]
                      (str "Notify: new-project " name)))

(defn notify-allow! [forge-or-pack project-root name act]
  (let [forge (forge-of forge-or-pack project-root)
        project (if forge (fs/file-name project-root) "")]
    (if forge
      (notify-lieutenant! forge "allow"
                          [["event" "allow"]
                           ["project" project]
                           ["name" name]
                           ["act" act]]
                          (str "Notify: allow " project "/" name " " act))
      (do
        (write-notify-file! (fs/path project-root ".swarmforge" "notify") "allow"
                            [["event" "allow"]
                             ["name" name]
                             ["act" act]])
        (inject-master! project-root (str "Notify: allow " name " " act))))))

(defn pack-board-result [root & args]
  (let [script (str (fs/path script-dir "pack_board.sh"))]
    (apply sh (concat [script] args ["--root" (str root)]))))

(defn pack-board [root & args]
  (let [result (apply pack-board-result root args)]
    (when-not (zero? (:exit result))
      (let [msg (str/trim (str (:err result) "\n" (:out result)))]
        (throw (ex-info msg {:exit (:exit result)
                             :http-status (if (str/includes? msg "Duplicate") 409 400)}))))
    (:out result)))

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
  (slurp (str (fs/path script-dir "pack" "dashboard.html"))))

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

(defn query-value [uri key]
  (when-let [q (second (str/split (or uri "") #"\?" 2))]
    (some (fn [pair]
            (let [[k v] (str/split pair #"=" 2)]
              (when (= k key)
                (java.net.URLDecoder/decode (or v "") "UTF-8"))))
          (str/split q #"&"))))

(defn existing-path [root rel]
  (let [path (fs/path root rel)]
    (when (fs/exists? path)
      (fs/canonicalize path))))

(defn under-dir? [file dir]
  (and file dir (fs/starts-with? file dir)))

(defn allowed-doc? [root rel]
  (when-not (str/blank? rel)
    (let [file (existing-path root rel)]
      (and (some? file)
           (fs/regular-file? file)
           (or (under-dir? file (existing-path root "features"))
               (under-dir? file (existing-path root "qa"))
               (under-dir? file (existing-path root "tasks")))))))

(defn get-mission [root]
  (let [file (fs/path root "mission.md")]
    {:status 200
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body (if (fs/regular-file? file) (slurp (str file)) "")}))

(defn get-doc [root uri]
  (let [rel (query-value uri "path")]
    (if (allowed-doc? root rel)
      {:status 200
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body (slurp (str (existing-path root rel)))}
      {:status 404 :body "Not found"})))

(defn parse-unified-diff [text]
  (->> (str/split-lines (or text ""))
       (drop-while #(not (str/starts-with? % "@@")))
       rest
       (keep (fn [line]
               (cond
                 (str/starts-with? line "+") {:type "add" :text (subs line 1)}
                 (str/starts-with? line "-") {:type "del" :text (subs line 1)}
                 (str/starts-with? line "\\") nil
                 (str/starts-with? line " ") {:type "same" :text (subs line 1)}
                 (str/blank? line) {:type "same" :text ""}
                 :else {:type "same" :text line})))
       vec))

(defn file-diff-lines [root prior commit rel]
  (let [result (apply sh ["git" "-C" (str root) "diff" "--no-color" "-U999999"
                          prior commit "--" rel])]
    (cond
      (not (zero? (:exit result))) nil
      (str/blank? (:out result))
      (mapv (fn [line] {:type "same" :text line})
            (str/split-lines (slurp (str (existing-path root rel)))))
      :else (parse-unified-diff (:out result)))))

(defn pending-headers [root id]
  (let [path (when-not (str/blank? id) (pending-file root id))]
    (when (and path (fs/regular-file? path))
      (:headers (parse-message path)))))

(defn get-api-doc [root uri]
  (let [rel (query-value uri "path")
        id (query-value uri "id")]
    (if-not (allowed-doc? root rel)
      {:status 404 :body "Not found"}
      (let [text (slurp (str (existing-path root rel)))
            headers (pending-headers root id)
            task-id (or (not-empty (get headers "task_id")) (get headers "task"))
            commit (not-empty (get headers "commit"))
            prior (when (and task-id (git-ref-exists? root (rejected-latest task-id)))
                    (rejected-latest task-id))
            lines (when (and prior commit)
                    (file-diff-lines root prior commit rel))
            has-diff (some? lines)
            history (mapv (fn [item]
                            {:at (or (get item "at") (:at item))
                             :text (or (get item "text") (:text item))})
                          (path-review-history root task-id rel))]
        {:status 200
         :headers {"Content-Type" "application/json; charset=utf-8"}
         :body (json/generate-string {:path rel
                                      :text text
                                      :has_diff has-diff
                                      :lines (or lines [])
                                      :history history})}))))

(defn task-query-name [uri]
  (when (str/starts-with? (or uri "") "/task")
    (query-value uri "name")))

(defn board-task-named [root name]
  (some #(when (= name (:name %)) %) (board-tasks root)))

(defn task-document [root name]
  (let [md (fs/path root "tasks" (str name ".md"))
        txt (fs/path root ".swarmforge" "board" (str name ".txt"))]
    (cond
      (fs/regular-file? md) (slurp (str md))
      (fs/regular-file? txt) (slurp (str txt))
      :else "")))

(defn list-card-audits [root task-id]
  (let [dir (fs/path root ".swarmforge" "board" "audits" task-id)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(re-matches #"[0-9]+\.md" (fs/file-name %)))
           (sort-by #(Long/parseLong (str/replace (fs/file-name %) #"\.md$" "")))
           (mapv (fn [file]
                   (let [n (Long/parseLong (str/replace (fs/file-name file) #"\.md$" ""))]
                     {:n n
                      :label (str "Audit " n)
                      :text (slurp (str file))}))))
      [])))

(defn card-worktree [root task]
  (let [lane (:lane task)
        master (master-role root)]
    (if (#{"waiting" "done" nil} lane)
      (or (worktree-for-role root master) (str root))
      (or (worktree-for-role root lane) (str root)))))

(defn resolve-under [root rel]
  (let [base (try (fs/canonicalize root) (catch Exception _ (fs/absolutize root)))
        target (try
                 (fs/canonicalize (if (str/blank? rel) root (fs/path root rel)))
                 (catch Exception _ nil))]
    (when (and target (fs/starts-with? target base))
      target)))

(defn tree-entries [dir]
  (if (fs/directory? dir)
    (->> (fs/list-dir dir)
         (remove #(= ".git" (fs/file-name %)))
         (sort-by (juxt #(if (fs/directory? %) 0 1) #(str/lower-case (fs/file-name %))))
         (mapv (fn [p]
                 {:name (fs/file-name p)
                  :dir (boolean (fs/directory? p))})))
    []))

(defn pretty-json [text]
  (try (json/generate-string (json/parse-string text) {:pretty true})
       (catch Exception _ text)))

(defn pretty-edn [text]
  (try (let [sw (java.io.StringWriter.)]
         (pprint/pprint (edn/read-string text) sw)
         (str sw))
       (catch Exception _ text)))

(defn code-escape [s]
  (-> (or s "")
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn apply-style [text pattern class-name]
  (let [matcher (.matcher pattern text)]
    (loop [last 0 out ""]
      (if (.find matcher)
        (let [start (.start matcher)
              end (.end matcher)
              piece (.group matcher)]
          (recur end (str out (subs text last start)
                          "<span class='" class-name "'>"
                          piece "</span>")))
        (str out (subs text last))))))

(defn colorize-code [source]
  (let [split-comment (fn [line]
                        (loop [idx 0 in-string? false escaped? false]
                          (if (>= idx (count line))
                            [line nil]
                            (let [ch (.charAt line idx)]
                              (cond
                                (and (not in-string?) (= ch \;))
                                [(subs line 0 idx) (subs line idx)]
                                escaped? (recur (inc idx) in-string? false)
                                (and in-string? (= ch \\)) (recur (inc idx) in-string? true)
                                (= ch \") (recur (inc idx) (not in-string?) false)
                                :else (recur (inc idx) in-string? false))))))
        [code-part comment-part] (split-comment (or source ""))
        escaped (code-escape code-part)
        string-pattern (java.util.regex.Pattern/compile "\"([^\"\\\\]|\\\\.)*\"")
        keyword-pattern (java.util.regex.Pattern/compile ":[a-zA-Z0-9\\-\\?!_\\./]+")]
    (str (-> escaped
             (apply-style string-pattern "str")
             (apply-style keyword-pattern "kw"))
         (when comment-part
           (str "<span class='cmt'>" (code-escape comment-part) "</span>")))))

(def gherkin-keywords
  ["Scenario Outline" "Feature" "Background" "Rule" "Scenario" "Examples"
   "Given" "When" "Then" "And" "But"])

(def gherkin-string-pattern
  (java.util.regex.Pattern/compile "\"([^\"\\\\]|\\\\.)*\""))

(def gherkin-placeholder-pattern
  (java.util.regex.Pattern/compile "&lt;[^&]+&gt;"))

(defn colorize-gherkin-rest [s]
  (-> (code-escape s)
      (apply-style gherkin-string-pattern "str")
      (apply-style gherkin-placeholder-pattern "ph")))

(defn gherkin-keyword-at [trimmed]
  (or (when (or (= trimmed "*") (str/starts-with? trimmed "* ")) "*")
      (some (fn [kw]
              (when (or (= trimmed kw)
                        (str/starts-with? trimmed (str kw " "))
                        (str/starts-with? trimmed (str kw ":")))
                kw))
            gherkin-keywords)))

(defn colorize-gherkin-line [source]
  (let [line (str/replace (or source "") "\t" "  ")
        trimmed (str/triml line)
        indent (subs line 0 (- (count line) (count trimmed)))]
    (cond
      (str/blank? trimmed) ""
      (str/starts-with? trimmed "#")
      (str (code-escape indent) "<span class='cmt'>" (code-escape trimmed) "</span>")
      (or (str/starts-with? trimmed "\"\"\"") (str/starts-with? trimmed "'''"))
      (str (code-escape indent) "<span class='str'>" (code-escape trimmed) "</span>")
      (str/starts-with? trimmed "@")
      (str (code-escape indent)
           (->> (str/split trimmed #"\s+")
                (map #(str "<span class='tag'>" (code-escape %) "</span>"))
                (str/join " ")))
      (str/starts-with? trimmed "|")
      (str (code-escape indent)
           (-> (code-escape trimmed)
               (apply-style gherkin-string-pattern "str")
               (str/replace "|" "<span class='tbl'>|</span>")))
      :else
      (if-let [kw (gherkin-keyword-at trimmed)]
        (str (code-escape indent)
             "<span class='kw'>" (code-escape kw) "</span>"
             (colorize-gherkin-rest (subs trimmed (count kw))))
        (colorize-gherkin-rest line)))))

(defn source-lines-html
  ([source] (source-lines-html source colorize-code))
  ([source colorize]
   (let [lines (str/split (or source "") #"\r?\n" -1)]
     (->> lines
          (map-indexed
           (fn [idx line]
             (let [line-html (colorize (str/replace (or line "") "\t" "  "))
                   visible (if (str/blank? line-html) "&nbsp;" line-html)]
               (str "<tr><td class='ln'>" (inc idx)
                    "</td><td class='code'><pre>" visible "</pre></td></tr>"))))
          (apply str)))))

(defn code-html [source]
  (str "<table class='src'>" (source-lines-html source) "</table>"))

(defn gherkin-html [source]
  (str "<table class='src'>" (source-lines-html source colorize-gherkin-line) "</table>"))

(defn printable-byte? [b]
  (let [n (bit-and (int b) 0xff)]
    (or (= n 9) (= n 10) (= n 13) (and (>= n 32) (<= n 126)))))

(defn binary-bytes? [bytes]
  (boolean (some (fn [b]
                   (let [n (bit-and (int b) 0xff)]
                     (or (zero? n) (and (< n 32) (not (#{9 10 13} n))))))
                 bytes)))

(defn hex-dump [bytes]
  (let [len (count bytes)]
    (str/join
     "\n"
     (map (fn [off]
            (let [chunk (subvec (vec bytes) off (min len (+ off 16)))
                  hex (str/join " " (map #(format "%02x" (bit-and (int %) 0xff)) chunk))
                  pad (apply str (repeat (* 3 (- 16 (count chunk))) " "))
                  chars (apply str (map (fn [b]
                                          (let [n (bit-and (int b) 0xff)]
                                            (if (and (>= n 32) (<= n 126))
                                              (char n)
                                              \.)))
                                        chunk))]
              (format "%08x  %s%s |%s|" off hex pad chars)))
          (range 0 len 16)))))

(defn read-file-bytes [path n]
  (with-open [in (io/input-stream (str path))]
    (let [buf (byte-array n)
          got (.read in buf)]
      (if (neg? got) (byte-array 0) (byte-array (take got buf))))))

(defn read-all-bytes [path]
  (with-open [in (io/input-stream (str path))]
    (let [out (java.io.ByteArrayOutputStream.)]
      (io/copy in out)
      (.toByteArray out))))

(def code-exts #{".clj" ".cljs" ".cljc" ".bb" ".edn" ".json"})

(defn file-view [path file]
  (let [name (str/lower-case (or path ""))]
    (cond
      (or (str/ends-with? name ".json") (str/ends-with? name ".edn")
          (str/ends-with? name ".clj") (str/ends-with? name ".cljs")
          (str/ends-with? name ".cljc") (str/ends-with? name ".bb"))
      (let [raw (slurp (str file))
            body (cond
                   (str/ends-with? name ".json") (pretty-json raw)
                   (str/ends-with? name ".edn") (pretty-edn raw)
                   :else raw)]
        {:kind "code" :html (code-html body)})

      (str/ends-with? name ".feature")
      {:kind "code" :html (gherkin-html (slurp (str file)))}

      (or (str/ends-with? name ".md") (str/ends-with? name ".txt"))
      {:kind "text" :text (slurp (str file))}

      :else
      (let [head (read-file-bytes file 8192)]
        (if (binary-bytes? head)
          {:kind "binary" :text (hex-dump (vec (read-all-bytes file)))}
          {:kind "text" :text (try (slurp (str file))
                                   (catch Exception _ (hex-dump (vec (read-all-bytes file)))))})))))

(defn task-page [name text audits project]
  (let [name-html (html-escape name)
        qs (str "name=" (java.net.URLEncoder/encode (str name) "UTF-8")
                (when-not (str/blank? project)
                  (str "&project=" (java.net.URLEncoder/encode (str project) "UTF-8"))))
        audit-html (if (seq audits)
                     (str/join
                      (map (fn [item]
                             (str "<section class=\"audit\"><h2>"
                                  (html-escape (:label item))
                                  "</h2><pre>"
                                  (html-escape (:text item))
                                  "</pre></section>"))
                           audits))
                     "<p class=\"note\">No audits yet.</p>")]
    (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
         "<title>" name-html "</title>"
         "<style>html,body{height:100%;margin:0;display:flex;flex-direction:column;"
         "background:#f8f8f5;color:#1e221f;font-family:ui-sans-serif,system-ui,sans-serif}"
         "header{display:flex;align-items:center;gap:8px;padding:8px 10px;"
         "background:linear-gradient(180deg,#eceee8,#e0e3dc);border-bottom:1px solid #d5d9d2}"
         "h1{margin:0;font-size:14px;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
         "h2{margin:0 0 6px;font-size:11px;text-transform:uppercase;letter-spacing:.04em;color:#68726c}"
         "button{border:1px solid #9aa59e;background:#fff;padding:5px 10px;border-radius:7px;"
         "font-size:12px;cursor:pointer}"
         "main{flex:1;min-height:0;overflow:auto;padding:12px;display:flex;flex-direction:column;gap:12px}"
         "pre{margin:0;padding:10px;background:#fffef9;border:1px solid #d5d9d2;border-radius:8px;"
         "white-space:pre-wrap;overflow:auto;font:12px/1.4 ui-monospace,Menlo,monospace}"
         ".note{color:#68726c;font-size:12px}"
         "#tree{font:12px/1.4 ui-sans-serif,system-ui,sans-serif}"
         ".dir-row{display:flex;align-items:center;gap:6px;padding:2px 0}"
         ".dir-row button.toggle{padding:0 6px;min-width:1.6em}"
         ".dir-row button.leaf{border:0;background:none;text-decoration:underline;color:#3d5a45;padding:0}"
         ".children{margin-left:1.2rem}"
         "#file-view{display:none}"
         "#file-view.open{display:block}"
         "#file-body{margin:0;padding:10px;overflow:auto;white-space:pre-wrap;"
         "font:12px/1.4 ui-monospace,Menlo,monospace;background:#fffef9;border:1px solid #d5d9d2;border-radius:8px}"
         ".src{border-collapse:collapse;width:100%;font:12px/1.35 ui-monospace,Menlo,monospace}"
         ".ln{width:52px;padding:0 8px;background:#eef2f7;color:#6b7280;text-align:right;vertical-align:top;border-right:1px solid #d1d5db;user-select:none}"
         ".code{padding:0 10px;vertical-align:top}"
         ".code pre{margin:0;white-space:pre}"
         ".cmt{color:#6b7280}.str{color:#b45309}.kw{color:#1d4ed8}"
         ".tag{color:#7c3aed}.tbl{color:#0f766e}.ph{color:#0e7490}"
         "</style></head><body>"
         "<header><h1>" name-html "</h1>"
         "<button type=\"button\" id=\"dir-btn\">Directory</button></header>"
         "<main>"
         "<section><h2>Task</h2><pre id=\"task-body\">" (html-escape text) "</pre></section>"
         "<section><h2>Audits</h2>" audit-html "</section>"
         "<section id=\"dir-panel\" hidden><h2>Directory</h2>"
         "<div id=\"tree\"></div>"
         "<div id=\"file-view\"><h2 id=\"file-name\"></h2>"
         "<div id=\"file-body\"></div></div></section>"
         "</main>"
         "<script>"
         "const qs=" (json/generate-string qs) ";"
         "function el(tag, attrs, kids){"
         "const n=document.createElement(tag);"
         "Object.keys(attrs||{}).forEach(k=>n.setAttribute(k, attrs[k]));"
         "(kids||[]).forEach(c=>n.appendChild(typeof c===\"string\"?document.createTextNode(c):c));"
         "return n;}"
         "async function openFile(path){"
         "const f=await fetch(\"/api/file?\"+qs+\"&path=\"+encodeURIComponent(path));"
         "const body=f.ok?await f.json():{text:\"Not found\",path:path};"
         "const w=window.open(\"about:blank\",\"file-\"+encodeURIComponent(path)+\"-\"+Date.now(),"
         "\"resizable=yes,scrollbars=yes,width=780,height=640\");"
         "if(!w)return;"
         "w.document.open();"
         "w.document.write(\"<html><head><title></title><style>"
         "html,body{height:100%;margin:0;display:flex;flex-direction:column;background:#f8f8f5;color:#1e221f;"
         "font-family:ui-sans-serif,system-ui,sans-serif}"
         "header{flex:0 0 auto;padding:8px 10px;background:#eceee8;border-bottom:1px solid #d5d9d2;font-weight:600;font-size:13px}"
         "#file-body{flex:1;margin:0;padding:12px;overflow:auto;white-space:pre-wrap;"
         "font:12px/1.4 ui-monospace,Menlo,monospace;background:#fffef9}"
         ".src{border-collapse:collapse;width:100%;font:12px/1.35 ui-monospace,Menlo,monospace}"
         ".ln{width:52px;padding:0 8px;background:#eef2f7;color:#6b7280;text-align:right;vertical-align:top}"
         ".code{padding:0 10px;vertical-align:top}.code pre{margin:0;white-space:pre}"
         ".cmt{color:#6b7280}.str{color:#b45309}.kw{color:#1d4ed8}.tag{color:#7c3aed}.ph{color:#0f766e}.tbl{color:#334155}"
         "</style></head><body><header></header><div id=file-body></div></body></html>\");"
         "w.document.close();"
         "const title=body.path||path;"
         "w.document.title=title;"
         "w.document.querySelector(\"header\").textContent=title;"
         "const box=w.document.getElementById(\"file-body\");"
         "if(body.html){box.innerHTML=body.html;}else{box.textContent=body.text||\"\";}"
         "}"
         "async function loadTree(path, host){"
         "const r=await fetch(\"/api/tree?\"+qs+\"&path=\"+encodeURIComponent(path||\"\"));"
         "const data=r.ok?await r.json():{entries:[]};"
         "host.replaceChildren();"
         "(data.entries||[]).forEach(item=>{"
         "const row=el(\"div\",{class:\"dir-row\"});"
         "if(item.dir){"
         "const btn=el(\"button\",{type:\"button\",class:\"toggle\"},[\"+\"]);"
         "const kids=el(\"div\",{class:\"children\"});"
         "kids.hidden=true;"
         "btn.onclick=async()=>{"
         "if(kids.hidden){btn.textContent=\"−\";kids.hidden=false;"
         "if(!kids.dataset.loaded){await loadTree(item.path, kids);kids.dataset.loaded=\"1\";}}"
         "else{btn.textContent=\"+\";kids.hidden=true;}};"
         "row.append(btn, document.createTextNode(item.name));"
         "host.append(row, kids);"
         "}else{"
         "const btn=el(\"button\",{type:\"button\",class:\"leaf\"},[item.name]);"
         "btn.onclick=()=>openFile(item.path);"
         "row.appendChild(btn);host.appendChild(row);}"
         "});}"
         "document.getElementById(\"dir-btn\").onclick=async()=>{"
         "const panel=document.getElementById(\"dir-panel\");"
         "panel.hidden=!panel.hidden;"
         "if(!panel.hidden && !panel.dataset.loaded){"
         "await loadTree(\"\", document.getElementById(\"tree\"));"
         "panel.dataset.loaded=\"1\";}"
         "};"
         "</script></body></html>")))

(defn get-task [root uri]
  (let [name (task-query-name uri)
        project (query-value uri "project")
        task (when (and (not (str/blank? name))
                        (not (str/includes? name "/"))
                        (not (str/includes? name "..")))
               (or (board-task-named root name)
                   (when (or (fs/regular-file? (fs/path root "tasks" (str name ".md")))
                             (fs/regular-file? (fs/path root ".swarmforge" "board" (str name ".txt"))))
                     {:name name :id name :lane "waiting"})))]
    (if task
      (let [text (task-document root name)
            audits (list-card-audits root (or (:id task) name))]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (task-page name (if (str/blank? text) (str name "\n") text) audits project)})
      {:status 404 :body "Not found"})))

(defn get-api-tree [root uri]
  (let [name (query-value uri "name")
        rel (or (query-value uri "path") "")
        task (board-task-named root name)
        wt (when task (card-worktree root task))
        dir (when wt (resolve-under wt rel))]
    (if (and dir (fs/directory? dir))
      {:status 200
       :headers {"Content-Type" "application/json; charset=utf-8"}
       :body (json/generate-string
              {:path rel
               :entries (mapv (fn [item]
                                (assoc item :path
                                       (str/replace
                                        (str (fs/path (or rel "") (:name item)))
                                        #"^/" "")))
                              (tree-entries dir))})}
      {:status 404 :body "Not found"})))

(defn get-api-file [root uri]
  (let [name (query-value uri "name")
        rel (query-value uri "path")
        task (board-task-named root name)
        wt (when task (card-worktree root task))
        file (when (and wt (not (str/blank? rel))) (resolve-under wt rel))]
    (if (and file (fs/regular-file? file))
      {:status 200
       :headers {"Content-Type" "application/json; charset=utf-8"}
       :body (json/generate-string (merge {:path rel} (file-view rel file)))}
      {:status 404 :body "Not found"})))

(defn html-escape [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn get-file-page [root uri]
  (let [name (query-value uri "name")
        rel (query-value uri "path")
        task (board-task-named root name)
        wt (when task (card-worktree root task))
        file (when (and wt (not (str/blank? rel))) (resolve-under wt rel))
        payload (if (and file (fs/regular-file? file))
                  (file-view rel file)
                  {:kind "text" :text "Not found"})
        title (html-escape (or rel "file"))
        inner (if (:html payload)
                (:html payload)
                (str "<pre>" (html-escape (or (:text payload) "")) "</pre>"))]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                "<title>" title "</title>"
                "<style>html,body{height:100%;margin:0;display:flex;flex-direction:column;"
                "background:#f8f8f5;color:#1e221f;font-family:ui-sans-serif,system-ui,sans-serif}"
                "header{flex:0 0 auto;padding:8px 10px;background:#eceee8;border-bottom:1px solid #d5d9d2;"
                "font-weight:600;font-size:13px}"
                "#file-body{flex:1;margin:0;padding:12px;overflow:auto;white-space:pre-wrap;"
                "font:12px/1.4 ui-monospace,Menlo,monospace;background:#fffef9}"
                ".src{border-collapse:collapse;width:100%;font:12px/1.35 ui-monospace,Menlo,monospace}"
                ".ln{width:52px;padding:0 8px;background:#eef2f7;color:#6b7280;text-align:right;vertical-align:top}"
                ".code{padding:0 10px;vertical-align:top}.code pre{margin:0;white-space:pre}"
                ".cmt{color:#6b7280}.str{color:#b45309}.kw{color:#1d4ed8}</style></head><body>"
                "<header>" title "</header>"
                "<div id=\"file-body\">" inner "</div></body></html>")}))

(defn role-row [root role]
  (some #(when (= role (first %)) %) (role-rows root)))

(defn session-for-role [root role]
  (when-let [row (role-row root role)]
    (session-name row)))

(defn worktree-for-role [root role]
  (when-let [row (role-row root role)]
    (nth row 2 nil)))

(defn merge-pane-capture [hist vis]
  (let [h (or hist "")
        v (or vis "")]
    (cond
      (str/blank? v) (not-empty h)
      (str/blank? h) v
      (str/includes? h v) h
      (str/starts-with? v h) v
      :else
      (let [h-trim (str/replace h #"\n+\z" "")
            v-lines (str/split-lines v)
            overlap (loop [n (count v-lines)]
                      (cond
                        (zero? n) nil
                        (str/ends-with? h-trim (str/join "\n" (take n v-lines))) n
                        :else (recur (dec n))))]
        (if overlap
          (let [rest (str/join "\n" (drop overlap v-lines))]
            (if (str/blank? rest)
              h
              (str h-trim "\n" rest (when (str/ends-with? v "\n") "\n"))))
          (str h-trim "\n" v))))))

(defn tmux-capture [socket target]
  (try
    (let [history (sh "tmux" "-S" socket "capture-pane" "-p" "-J" "-t" target
                      "-S" (str "-" pane-capture-lines))
          visible (sh "tmux" "-S" socket "capture-pane" "-p" "-J" "-t" target)
          hist (when (zero? (:exit history)) (:out history))
          vis (when (zero? (:exit visible)) (:out visible))]
      (merge-pane-capture hist vis))
    (catch Exception _)))

(defn capture-pane [root role]
  (when-let [row (role-row root role)]
    (let [socket (tmux-socket root)]
      (when socket
        (or (tmux-capture socket (pane-target row))
            (tmux-capture socket (session-name row)))))))

(defn live-pane-text [root role]
  (or *pane-text*
      (capture-pane root role)
      (recorded-pane root role)))

(defn pane-files [root role]
  (let [dir (fs/path root ".swarmforge" "sessions" role)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (map #(fs/path % "pane.txt"))
           (filter fs/regular-file?)
           vec)
      [])))

(defn in-process-task [root role]
  (when-let [worktree (worktree-for-role root role)]
    (some #(get-in (parse-message %) [:headers "task"])
          (in-process-files (in-process-dir worktree)))))

(defn pane-for-task [files task]
  (when task
    (some #(when (= task (fs/file-name (fs/parent %))) %) files)))

(defn recorded-pane [root role]
  (let [direct (fs/path root ".swarmforge" "sessions" role "pane.txt")]
    (if (fs/regular-file? direct)
      (slurp (str direct))
      (let [files (pane-files root role)
            chosen (or (pane-for-task files (in-process-task root role))
                       (last (sort-by str files)))]
        (when chosen
          (slurp (str chosen)))))))

(defn pane-content [root role]
  (or (not-empty (capture-pane root role))
      (not-empty (recorded-pane root role))
      (str "(no pane capture for " role ")\n")))

(defn project-query [project]
  (when (not-empty project)
    (str "?project=" (java.net.URLEncoder/encode (str project) "UTF-8"))))

(defn pane-page [role snapshot & [project]]
  (let [role-html (html-escape role)
        pane-url (str "/api/agents/" role-html "/pane" (or (project-query project) ""))]
    (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
         "<title>Agent " role-html "</title>"
         "<style>html,body{height:100%;margin:0;overflow:hidden;background:#111;color:#f4f4f4;"
         "font-family:ui-monospace,Menlo,monospace}"
         "body{display:flex;flex-direction:column}"
         "header{height:42px;box-sizing:border-box;padding:10px 12px;border-bottom:1px solid #333;flex:0 0 auto}"
         "h1{font:inherit;margin:0;font-size:14px}"
         "#pane{flex:1 1 auto;margin:0;padding:12px;white-space:pre-wrap;overflow:auto;"
         "min-height:0;height:calc(100vh - 42px);max-height:calc(100vh - 42px)}</style></head>"
         "<body><header><h1>" role-html "</h1></header>"
         "<pre id=\"pane\">" (html-escape snapshot) "</pre>"
         "<script>(function(){"
         "const pane=document.getElementById('pane');"
         "let stickBottom=true;"
         "let firstPaint=true;"
         "function nearBottom(){"
         "return (pane.scrollHeight-pane.scrollTop-pane.clientHeight)<=64;}"
         "function toEnd(){pane.scrollTop=pane.scrollHeight;stickBottom=true;}"
         "function toEndSoon(){"
         "toEnd();requestAnimationFrame(toEnd);"
         "setTimeout(toEnd,0);setTimeout(toEnd,50);setTimeout(toEnd,200);}"
         "pane.addEventListener('scroll',function(){stickBottom=nearBottom();},{passive:true});"
         "async function refresh(){"
         "const r=await fetch('" pane-url "',{cache:'no-store'});"
         "const text=await r.text();"
         "const changed=text!==pane.textContent;"
         "if(changed){pane.textContent=text;}"
         "if(firstPaint||stickBottom){toEndSoon();firstPaint=false;}"
         "}"
         "refresh();setInterval(refresh,1000);"
         "window.addEventListener('load',toEndSoon);"
         "window.addEventListener('pageshow',toEndSoon);"
         "})();</script></body></html>")))

(defn agent-role [uri]
  (when-let [[_ role] (re-matches #"/agent/([^/]+)"
                                 (first (str/split (or uri "") #"\?")))]
    (java.net.URLDecoder/decode role "UTF-8")))

(defn agent-pane-role [uri]
  (when-let [[_ role] (re-matches #"/api/agents/([^/]+)/pane"
                                 (first (str/split (or uri "") #"\?")))]
    (java.net.URLDecoder/decode role "UTF-8")))

(defn get-agent [root uri]
  (if-let [role (agent-role uri)]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (pane-page role (pane-content root role) (query-value uri "project"))}
    {:status 404 :body "Not found"}))

(defn get-agent-pane [root uri]
  (if-let [role (agent-pane-role uri)]
    {:status 200
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body (pane-content root role)}
    {:status 404 :body "Not found"}))

(defn request-project-root [forge uri]
  (if-not (forge/forge? forge)
    forge
    (if-let [name (not-empty (query-value uri "project"))]
      (str (forge/project-dir forge name))
      forge)))

(defn body-map [body]
  (try
    (json/parse-string (or body "{}") true)
    (catch Exception _ {})))

(defn json-ok-data [m]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string (merge {:ok true} m))})

(defn find-approval-root [forge id]
  (or (some (fn [name]
              (let [proot (str (forge/project-dir forge name))]
                (when (fs/regular-file? (pending-file proot id))
                  proot)))
            (forge/read-open-projects forge))
      (throw (ex-info (str "Unknown approval: " id) {:http-status 404}))))

(defn find-clar-root [forge id]
  (or (when (fs/regular-file? (clar-pending-file forge id))
        forge)
      (some (fn [name]
              (let [proot (str (forge/project-dir forge name))]
                (when (fs/regular-file? (clar-pending-file proot id))
                  proot)))
            (forge/read-open-projects forge))
      (throw (ex-info (str "Unknown clarification: " id) {:http-status 404}))))

(defn handle-get [root uri]
  (cond
    (= "/" uri)
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (dashboard-page)}

    (= "/api/state" uri)
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string (api-state root))}

    (agent-pane-role uri)
    (get-agent-pane (request-project-root root uri) uri)

    (agent-role uri)
    (get-agent (request-project-root root uri) uri)

    (task-query-name uri)
    (get-task (request-project-root root uri) uri)

    (str/starts-with? (first (str/split (or uri "") #"\?")) "/api/tree")
    (get-api-tree (request-project-root root uri) uri)

    (str/starts-with? (first (str/split (or uri "") #"\?")) "/api/file")
    (get-api-file (request-project-root root uri) uri)

    (str/starts-with? (first (str/split (or uri "") #"\?")) "/file")
    (get-file-page (request-project-root root uri) uri)

    (str/starts-with? (first (str/split (or uri "") #"\?")) "/api/doc")
    (get-api-doc (request-project-root root uri) uri)

    (str/starts-with? (or uri "") "/doc")
    (get-doc (request-project-root root uri) uri)

    (= "/api/mission" (first (str/split (or uri "") #"\?")))
    (get-mission (request-project-root root uri))

    :else {:status 404 :body "Not found"}))

(defn confirm-teardown? [body]
  (let [text (str/trim (or body ""))]
    (or (= "TEARDOWN" text)
        (try
          (= "TEARDOWN" (:confirm (json/parse-string text true)))
          (catch Exception _ false)))))

(defn current-pid []
  (str (.pid (java.lang.ProcessHandle/current))))

(defn pack-web-pid-file [root]
  (fs/path root ".swarmforge" "pack_web.pid"))

(defn write-pack-web-pid! [root]
  (let [file (pack-web-pid-file root)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) (str (current-pid) "\n"))))

(defn stop-pack-web! [root]
  (let [file (pack-web-pid-file root)
        pid (when (fs/exists? file)
              (not-empty (str/trim (slurp (str file)))))]
    (when (and pid (not= pid (current-pid)))
      (sh "kill" "-TERM" pid))
    (fs/delete-if-exists file)))

(defn list-tmux-sessions [socket]
  (if (str/blank? socket)
    []
    (let [result (sh "tmux" "-S" socket "list-sessions" "-F" "#{session_name}")]
      (if (zero? (:exit result))
        (->> (str/split-lines (:out result))
             (remove str/blank?)
             vec)
        []))))

(defn kill-session! [socket session]
  (sh "tmux" "-S" socket "kill-session" "-t" (str "=" session))
  (sh "tmux" "-S" socket "kill-session" "-t" session))

(defn kill-all-sessions-on-socket! [socket]
  (when-not (str/blank? socket)
    (doseq [session (list-tmux-sessions socket)]
      (kill-session! socket session))
    (sh "tmux" "-S" socket "kill-server")))

(defn stop-handoffd! [root]
  (sh "bb" (str (fs/path script-dir "stop_handoff_daemon.bb")) (str root)))

(defn swarm-cleanup! [root socket]
  (let [script (str (fs/path script-dir "swarm-cleanup.sh"))
        ids (str (fs/path root ".swarmforge" "window-ids"))]
    (apply sh (into [script (or socket "none") ids]
                    (list-tmux-sessions socket)))))

(defn close-swarm-bin []
  (let [path (fs/path (fs/parent (fs/parent script-dir)) "close-swarm")]
    (when (fs/exists? path)
      (str path))))

(defn close-swarm! [root]
  (if-let [bin (close-swarm-bin)]
    (sh bin (str root))
    (swarm-cleanup! root (tmux-socket root))))

(defn run-teardown! [root]
  (when (forge/forge? root)
    (forge/close-all-projects! root))
  (close-swarm! root)
  (stop-handoffd! root)
  (kill-all-sessions-on-socket! (tmux-socket root))
  (stop-pack-web! root)
  true)

(defn log-teardown-failure! [root e]
  (binding [*out* *err*]
    (println (str "teardown failed root=" root
                  " error=" (.getMessage e)))
    (flush)))

(defn schedule-teardown! [root]
  (if *sync-teardown?*
    (try
      (run-teardown! root)
      (catch Exception e
        (log-teardown-failure! root e)
        (exit! 1 nil)))
    (future
      (Thread/sleep teardown-delay-ms)
      (try
        (run-teardown! root)
        (System/exit 0)
        (catch Exception e
          (log-teardown-failure! root e)
          (System/exit 1)))))
  true)

(defn teardown-response [root body]
  (if (confirm-teardown? body)
    (do
      (schedule-teardown! root)
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:ok true :status "teardown_started"})})
    {:status 400
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body "Teardown requires confirm=TEARDOWN (JSON {\"confirm\":\"TEARDOWN\"}).\n"}))

(defn post-new-project [root body]
  (let [parsed (body-map body)
        created (forge/instantiate! root parsed)]
    (forge/open-project! root (:name created))
    (notify-new-project! root (:name created))
    (json-ok-data created)))

(defn post-open-project [root body]
  (json-ok-data (forge/open-project! root (:name (body-map body)))))

(defn post-close-project [root body]
  (json-ok-data (forge/close-project! root (:name (body-map body)))))

(defn post-board-allow [root body]
  (let [m (body-map body)
        name (:name m)
        act (:act m)
        dest (if (forge/forge? root)
               (let [project (:project m)]
                 (when (str/blank? project)
                   (throw (ex-info "Missing project" {:http-status 400})))
                 (str (forge/project-dir root project)))
               root)]
    (when (or (str/blank? name) (str/blank? act))
      (throw (ex-info "Missing name or act" {:http-status 400})))
    (pack-board dest "allow" "--name" name "--act" act)
    (notify-allow! root dest name act)
    (json-ok)))

(defn scoped-approval-root [root uri body]
  (if-not (forge/forge? root)
    root
    (let [m (body-map body)
          id (or (:id (approval-route uri)) (:id m))
          project (:project m)]
      (cond
        (not (str/blank? project)) (str (forge/project-dir root project))
        (not (str/blank? id)) (find-approval-root root id)
        :else (throw (ex-info "Missing project" {:http-status 400}))))))

(defn scoped-clar-root [root uri]
  (if-not (forge/forge? root)
    root
    (find-clar-root root (clarification-route uri))))

(defn handle-post [root uri body]
  (cond
    (= "/api/projects" uri) (post-new-project root body)
    (= "/api/projects/open" uri) (post-open-project root body)
    (= "/api/projects/close" uri) (post-close-project root body)
    (= "/api/tasks" uri) (post-tasks root body)
    (= "/api/tasks/delete" uri)
    (post-delete-task (scoped-approval-root root uri body) body)
    (= "/api/tasks/retry" uri)
    (post-retry-task (scoped-approval-root root uri body) body)
    (= "/api/chat" uri) (post-chat root body)
    (= "/api/teardown" uri) (teardown-response root body)
    (= "/api/board/allow" uri) (post-board-allow root body)
    (str/starts-with? (or uri "") "/api/approvals/")
    (post-approval (scoped-approval-root root uri body) uri body)
    (str/starts-with? (or uri "") "/api/clarifications/")
    (post-clarification (scoped-clar-root root uri) uri body)
    :else {:status 404 :body "Not found"}))

(defn handle-request [root {:keys [method uri body]}]
  (try
    (case method
      "GET" (handle-get root uri)
      "POST" (handle-post root uri body)
      {:status 404 :body "Not found"})
    (catch Exception e
      (http-error (or (:http-status (ex-data e)) 500) (.getMessage e)))))

(defn test-state! [root]
  (println (:body (handle-request (require-root! root) {:method "GET" :uri "/api/state"}))))

(defn test-html! []
  (print (:body (handle-request nil {:method "GET" :uri "/"})))
  (flush))

(defn test-post-task! [root name text & [project type]]
  (let [resp (handle-request (require-root! root)
                             {:method "POST"
                              :uri "/api/tasks"
                              :body (json/generate-string
                                     (cond-> {:name name :text (or text "")}
                                       (not (str/blank? project)) (assoc :project project)
                                       (not (str/blank? type)) (assoc :type type)))})]
    (print (:body resp))
    (flush)
    (when-not (= 200 (:status resp))
      (binding [*out* *err*]
        (println (:body resp)))
      (System/exit 1))))

(defn test-delete-task! [root name]
  (let [resp (handle-request (require-root! root)
                             {:method "POST"
                              :uri "/api/tasks/delete"
                              :body (json/generate-string {:name name})})]
    (print (:body resp))
    (flush)
    (when-not (= 200 (:status resp))
      (binding [*out* *err*]
        (println (:body resp)))
      (System/exit 1))))

(defn test-delete-approval! [root id]
  (let [resp (handle-request (require-root! root)
                             {:method "POST"
                              :uri "/api/tasks/delete"
                              :body (json/generate-string {:id id})})]
    (print (:body resp))
    (flush)
    (when-not (= 200 (:status resp))
      (binding [*out* *err*]
        (println (:body resp)))
      (System/exit 1))))

(defn test-retry-task! [root id comments]
  (let [resp (handle-request (require-root! root)
                             {:method "POST"
                              :uri "/api/tasks/retry"
                              :body (json/generate-string {:id id :comments (or comments "")})})]
    (print (:body resp))
    (flush)
    (when-not (= 200 (:status resp))
      (binding [*out* *err*]
        (println (:body resp)))
      (System/exit 1))))

(defn test-post-chat! [root text]
  (handle-request (require-root! root)
                  {:method "POST"
                   :uri "/api/chat"
                   :body (json/generate-string {:text (or text "")})}))

(defn test-inject-payload! [name text]
  (println (if (and name text)
             (task-payload name text)
             (task-payload))))

(defn test-inject-argv! [root file text]
  (when (str/blank? file)
    (exit! 1 "Missing argv file"))
  (binding [*tmux-stub* file]
    (inject-master! (require-root! root) text)))

(defn test-http! [resp]
  (print (:body resp))
  (flush)
  (when-not (= 200 (:status resp))
    (binding [*out* *err*]
      (println (:body resp)))
    (System/exit 1)))

(defn test-approval! [root id action]
  (when (str/blank? id)
    (exit! 1 "Missing approval id"))
  (test-http! (handle-request (require-root! root)
                              {:method "POST"
                               :uri (str "/api/approvals/" id "/" action)})))

(defn test-allow! [root name act & [project]]
  (when (or (str/blank? name) (str/blank? act))
    (exit! 1 "Missing name or act"))
  (test-http! (handle-request (require-root! root)
                              {:method "POST"
                               :uri "/api/board/allow"
                               :body (json/generate-string
                                      (cond-> {:name name :act act}
                                        (not (str/blank? project))
                                        (assoc :project project)))})))

(defn test-save-comments! [root id path comments]
  (when (str/blank? id)
    (exit! 1 "Missing approval id"))
  (test-http! (handle-request (require-root! root)
                              {:method "POST"
                               :uri (str "/api/approvals/" id "/comments")
                               :body (json/generate-string {:path path :comments (or comments "")})})))

(defn test-pane! [root role & [project]]
  (when (str/blank? role)
    (exit! 1 "Missing role"))
  (print (:body (handle-request (require-root! root)
                                {:method "GET"
                                 :uri (str "/api/agents/" role "/pane"
                                          (or (project-query project) ""))})))
  (flush))

(defn test-pane-merge! [history-file visible-file]
  (when (or (str/blank? history-file) (str/blank? visible-file))
    (exit! 1 "Missing history or visible file"))
  (print (merge-pane-capture (slurp history-file) (slurp visible-file)))
  (flush))

(defn test-agent-page! [role & [project]]
  (print (pane-page (or role "specifier") "" project))
  (flush))

(defn print-heat-pair! [root before-text after-text]
  (require-root! root)
  (reset! pane-heat {})
  (binding [*pane-text* before-text]
    (let [before (:activity (first (work-in-flight root)))]
      (binding [*pane-text* after-text]
        (let [after (:activity (first (work-in-flight root)))]
          (println (json/generate-string {:before before :after after})))))))

(defn test-heat! [root]
  (print-heat-pair! root "alpha\nline two\n" "beta\nline two\nchanged output\n"))

(defn test-card-heat! [root]
  (require-root! root)
  (reset! pane-heat {})
  (let [before-text "alpha\nline two\n"
        after-text "beta\nline two\nchanged output\n"]
    (binding [*pane-text* before-text]
      (let [h1 (:role_heats (dashboard-state root))]
        (binding [*pane-text* after-text]
          (let [h2 (:role_heats (dashboard-state root))
                role (or (ffirst h2) "specifier")]
            (println (json/generate-string
                      {:before (get h1 role 0)
                       :after (get h2 role 0)
                       :other {:Grenade false}}))))))))

(defn test-lieutenant-heat! [root]
  (require-root! root)
  (reset! pane-heat {})
  (let [before-text "alpha\nline two\n"
        after-text "beta\nline two\nchanged output\n"]
    (binding [*pane-text* before-text]
      (let [before (or (:lieutenant_activity (api-state root)) 0)]
        (binding [*pane-text* after-text]
          (let [after (or (:lieutenant_activity (api-state root)) 0)]
            (println (json/generate-string {:before before :after after}))))))))

(defn print-heat-isolation! [root-a root-b]
  (reset! pane-heat {})
  (binding [*pane-text* "stable-a\n"]
    (work-in-flight root-a))
  (binding [*pane-text* "stable-b\n"]
    (work-in-flight root-b))
  (let [changed (binding [*pane-text* "changed-a\nmore\n"]
                  (:activity (first (work-in-flight root-a))))
        stable (binding [*pane-text* "stable-b\n"]
                 (:activity (first (work-in-flight root-b))))]
    (println (json/generate-string {:changed changed :stable stable}))))

(defn test-heat-isolation! [root-a root-b]
  (require-root! root-a)
  (require-root! root-b)
  (print-heat-isolation! root-a root-b))

(defn test-heat-codex! [root]
  (print-heat-pair! root
                    "I'll load the SwarmForge instructions.\n\nesc to interrupt · 3s\n"
                    "I'll load the SwarmForge instructions.\n\nesc to interrupt · 4s\n"))

(defn test-heat-reorder! [root]
  (let [lines (mapv #(str "line-" %) (range 20))]
    (print-heat-pair! root
                      (str (str/join "\n" lines) "\n")
                      (str (str/join "\n" (reverse lines)) "\n"))))

(defn test-heat-head! [root]
  (let [tail (mapv #(str "tail-" %) (range 20))
        before (str (str/join "\n" (concat ["a" "b" "c" "d" "e"] tail)) "\n")
        after (str (str/join "\n" (concat ["v" "w" "x" "y" "z"] tail)) "\n")]
    (print-heat-pair! root before after)))

(defn test-heat-mail! [root]
  (print-heat-pair! root
                    (str "stable\n"
                         "› You have new handoff mail. If idle, run ready_for_next.sh.\n"
                         "work line A\n"
                         "• Working (1s • esc to interrupt)\n"
                         "›\n")
                    (str "stable\n"
                         "› You have new handoff mail. If idle, run ready_for_next.sh.\n"
                         "work line B\n"
                         "• Working (2s • esc to interrupt)\n"
                         "›\n")))

(defn test-heat-grok! [root]
  (print-heat-pair! root
                    (str "I'll write the cave stories.\n"
                         "You have new handoff mail. If idle, run ready_for_next.sh.\n"
                         "always-approve  shift+tab\n"
                         "Waiting for response 1s\n"
                         "enter:send  Esc:cancel\n")
                    (str "I'll write the cave stories.\n"
                         "You have new handoff mail. If idle, run ready_for_next.sh.\n"
                         "always-approve  shift+tab\n"
                         "Waiting for response 2s\n"
                         "enter:send  Esc:cancel\n")))

(defn test-heat-collapse! [root]
  (print-heat-pair! root
                    (str "… +28 lines (ctrl + t to view transcript)\n"
                         "• Working (1s • esc to interrupt)\n")
                    (str "… +29 lines (ctrl + t to view transcript)\n"
                         "• Working (2s • esc to interrupt)\n")))

(defn test-status-pane! [root text]
  (require-root! root)
  (binding [*pane-text* (or text "")]
    (println (:body (handle-request root {:method "GET" :uri "/api/state"})))))

(defn test-status-persist! [root first-text second-text]
  (require-root! root)
  (reset! pane-status {})
  (reset! pane-status-lines {})
  (binding [*pane-text* (or first-text "")]
    (let [first-status (:status (first (:tasks (json/parse-string
                                                (:body (handle-request root {:method "GET" :uri "/api/state"}))
                                                true))))]
      (binding [*pane-text* (or second-text "")]
        (let [second-status (:status (first (:tasks (json/parse-string
                                                     (:body (handle-request root {:method "GET" :uri "/api/state"}))
                                                     true))))]
          (println (json/generate-string {:first first-status :second second-status})))))))

(defn test-answer-clarification! [root id text]
  (when (str/blank? id)
    (exit! 1 "Missing clarification id"))
  (test-http! (handle-request (require-root! root)
                              {:method "POST"
                               :uri (str "/api/clarifications/" id "/answer")
                               :body (json/generate-string {:text (or text "")})})))

(defn test-task! [root name]
  (when (str/blank? name)
    (exit! 1 "Missing task name"))
  (print (:body (handle-request (require-root! root)
                                {:method "GET"
                                 :uri (str "/task?name=" name)})))
  (flush))

(defn test-tree! [root name path]
  (when (str/blank? name)
    (exit! 1 "Missing task name"))
  (print (:body (handle-request (require-root! root)
                                {:method "GET"
                                 :uri (str "/api/tree?name=" name
                                           (when-not (str/blank? path)
                                             (str "&path=" path)))})))
  (flush))

(defn test-file! [root name path]
  (when (or (str/blank? name) (str/blank? path))
    (exit! 1 "Missing task name or path"))
  (print (:body (handle-request (require-root! root)
                                {:method "GET"
                                 :uri (str "/api/file?name=" name "&path=" path)})))
  (flush))

(defn test-mission! [root & [project]]
  (print (:body (handle-request (require-root! root)
                                {:method "GET"
                                 :uri (str "/api/mission"
                                           (when-not (str/blank? project)
                                             (str "?project=" project)))})))
  (flush))

(defn test-doc! [root path]
  (when (str/blank? path)
    (exit! 1 "Missing path"))
  (print (:body (handle-request (require-root! root)
                                {:method "GET"
                                 :uri (str "/doc?path=" path)})))
  (flush))

(defn test-api-doc! [root path id]
  (when (str/blank? path)
    (exit! 1 "Missing path"))
  (print (:body (handle-request (require-root! root)
                                {:method "GET"
                                 :uri (str "/api/doc?path=" path
                                           (when-not (str/blank? id)
                                             (str "&id=" id)))})))
  (flush))

(defn test-project-http! [resp]
  (print (:body resp))
  (flush)
  (when-not (= 200 (:status resp))
    (binding [*out* *err*]
      (println (:body resp)))
    (System/exit 1)))

(defn test-new-project! [root name pack mission]
  (test-project-http!
   (handle-request (require-root! root)
                   {:method "POST"
                    :uri "/api/projects"
                    :body (json/generate-string {:name name
                                                 :pack pack
                                                 :mission (or mission "")})})))

(defn test-open-project! [root name]
  (test-project-http!
   (handle-request (require-root! root)
                   {:method "POST"
                    :uri "/api/projects/open"
                    :body (json/generate-string {:name name})})))

(defn test-close-project! [root name]
  (test-project-http!
   (handle-request (require-root! root)
                   {:method "POST"
                    :uri "/api/projects/close"
                    :body (json/generate-string {:name name})})))

(defn test-inferred-name! [input github]
  (println (forge/inferred-name input (= "github" github))))

(defn test-teardown! [root confirm]
  (binding [*sync-teardown?* true]
    (let [resp (handle-request (require-root! root)
                               {:method "POST"
                                :uri "/api/teardown"
                                :body (when confirm
                                        (json/generate-string {:confirm confirm}))})]
      (when-not (= 200 (:status resp))
        (exit! 2 (:body resp)))
      (print (:body resp))
      (flush))))

(defn request-body [req]
  (when-let [body (:body req)]
    (if (string? body) body (slurp body))))

(defn request-uri [req]
  (let [uri (:uri req)
        qs (:query-string req)]
    (if (str/blank? qs) uri (str uri "?" qs))))

(defn http-handler [root]
  (fn [req]
    (handle-request root {:method (str/upper-case (name (:request-method req)))
                          :uri (request-uri req)
                          :body (request-body req)})))

(defn write-dashboard-url! [root url]
  (let [file (fs/path root ".swarmforge" "dashboard-url")]
    (fs/create-dirs (fs/parent file))
    (spit (str file) (str url "\n"))))

(defn parse-port [port-str]
  (if (str/blank? port-str) 0 (Long/parseLong port-str)))

(defn serve! [root port-str]
  (let [root (require-root! root)
        server (http/run-server (http-handler root)
                                {:ip "127.0.0.1"
                                 :port (parse-port port-str)
                                 :worker-count 8
                                 :legacy-return-value? false})
        url (str "http://127.0.0.1:" (http/server-port server))]
    (write-pack-web-pid! root)
    (write-dashboard-url! root url)
    (println url)
    (flush)
    @(promise)))

(defn -main [& args]
  (case (first args)
    "--serve" (serve! (second args) (nth args 2 nil))
    (do (usage)
        (exit! 1 nil)))
  (System/exit 0))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
