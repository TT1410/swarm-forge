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


(load-file (str (fs/path script-dir "pack_web_board.bb")))

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


(load-file (str (fs/path script-dir "pack_web_view.bb")))

(defn role-row [root role]
  (some #(when (= role (first %)) %) (role-rows root)))

(defn session-for-role [root role]
  (when-let [row (role-row root role)]
    (session-name row)))

(defn worktree-for-role [root role]
  (when-let [row (role-row root role)]
    (nth row 2 nil)))


(load-file (str (fs/path script-dir "pack_web_pane.bb")))

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
