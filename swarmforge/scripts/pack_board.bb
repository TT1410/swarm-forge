#!/usr/bin/env bb

(ns pack-board
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str])
  (:import [java.nio.channels FileChannel]
           [java.nio.file OpenOption StandardOpenOption]))

(def usage-text
  (str "Usage:\n"
       "  pack_board.sh create --name <name> --type <utility|component|QA|review> [--waiting] [--merge-from <role>] [--root <dir>] [--text <text>]\n"
       "  pack_board.sh create <name> --type <utility|component|QA|review> [--waiting]\n"
       "  pack_board.sh move --name <name> --lane <lane> [--merge-from <role>] [--root <dir>]\n"
       "  pack_board.sh move <name> <lane>\n"
       "  pack_board.sh done --name <name> [--root <dir>]\n"
       "  pack_board.sh done <name>\n"
       "  pack_board.sh list [--root <dir>]\n"
       "  pack_board.sh lanes [--root <dir>]\n"
       "  pack_board.sh master-lane [--root <dir>]\n"
       "  pack_board.sh archive --role <role> [--root <dir>]\n"
       "  pack_board.sh archive <role>\n"
       "  pack_board.sh archive-all [--root <dir>]\n"
       "  pack_board.sh increment-audit --task-id <task-id> --caller <handoffd|lieutenant> [--root <dir>]\n"
       "  pack_board.sh request-allow --name <name> --act <move|done|increment-audit> [--root <dir>]\n"
       "  pack_board.sh allow --name <name> --act <move|done|increment-audit> [--root <dir>]\n"
       "  pack_board.sh delete --name <name> [--root <dir>]\n"
       "  pack_board.sh delete <name>\n"
       "  pack_board.sh stop --name <name> [--root <dir>]"))

(def flags {"--root" :root "--name" :name "--lane" :lane "--text" :text
            "--role" :role "--task-id" :task-id "--type" :type
            "--caller" :caller "--archive" :archive "--act" :act
            "--merge-from" :merge-from "--waiting" :waiting})
(def bool-flags #{"--waiting"})
(def script-dir (fs/parent *file*))
(try
  (require 'handoff-lib)
  (catch Exception _
    (load-file (str (fs/path script-dir "handoff_lib.bb")))))
(try
  (require 'card-type)
  (catch Exception _
    (load-file (str (fs/path script-dir "card_type.bb")))))

(declare role-rows halt-live-card!)

(defn usage []
  (binding [*out* *err*]
    (println usage-text)))

(defn exit! [status message]
  (binding [*out* *err*]
    (when message
      (println message)))
  (System/exit status))

(defn command [dir & args]
  (apply sh (concat args [:dir (str dir)])))

(defn git-root []
  (handoff-lib/git-toplevel))

(defn git-common-dir []
  (handoff-lib/git-common-dir))

(defn roles-at? [root]
  (handoff-lib/roles-at? root))

(defn project-root []
  (try
    (handoff-lib/project-root)
    (catch clojure.lang.ExceptionInfo e
      (exit! (or (:exit (ex-data e)) 1) (ex-message e)))))

(defn parse-args [args]
  (loop [args args opts {} positionals []]
    (if (empty? args)
      (assoc opts :positional positionals)
      (let [head (first args)
            flag (get flags head)]
        (cond
          (nil? flag)
          (recur (rest args) opts (conj positionals head))

          (contains? bool-flags head)
          (recur (rest args) (assoc opts flag true) positionals)

          (nil? (second args))
          (exit! 1 (str "Missing value for " head))

          :else
          (recur (drop 2 args) (assoc opts flag (second args)) positionals))))))

(defn resolve-root [opts]
  (or (:root opts) (project-root)))

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

(defn rewrite-lane [line name lane]
  (let [row (card-type/parse-row line)]
    (if (= (str/lower-case (or name "")) (str/lower-case (or (:name row) "")))
      (card-type/format-row (assoc row :lane lane :updated (timestamp)))
      line)))

(def allow-acts #{"move" "done" "increment-audit" "stop"})

(defn lt-allow-file [root name act]
  (fs/path root ".swarmforge" "board" "lt-allow" (str name "-" act)))

(defn lt-pending-file [root name act]
  (fs/path root ".swarmforge" "board" "lt-allow-pending" (str name "-" act)))

(defn require-act! [act]
  (require-value! act "act")
  (when-not (contains? allow-acts act)
    (exit! 1 (str "Unknown act: " act))))

(defn caller-task-name [opts]
  (or (not-empty (task-name opts))
      (when-let [id (not-empty (:task-id opts))]
        (some (fn [line]
                (let [row (card-type/parse-row line)]
                  (when (or (= id (:id row)) (= id (:name row)))
                    (:name row))))
              (read-rows (tasks-file (resolve-root opts)))))))

(defn request-allow! [opts]
  (let [name (task-name opts)
        act (:act opts)
        root (resolve-root opts)
        file (lt-pending-file root name act)]
    (require-value! name "task name")
    (require-act! act)
    (fs/create-dirs (fs/parent file))
    (spit (str file) (str "name: " name "\nact: " act "\n"))))

(defn allow! [opts]
  (let [name (task-name opts)
        act (:act opts)
        root (resolve-root opts)
        pending (lt-pending-file root name act)
        allow (lt-allow-file root name act)]
    (require-value! name "task name")
    (require-act! act)
    (fs/create-dirs (fs/parent allow))
    (spit (str allow) (str "name: " name "\nact: " act "\n"))
    (fs/delete-if-exists pending)))

(defn caller-allowed? [opts act]
  (let [caller (:caller opts)
        root (resolve-root opts)
        name (caller-task-name opts)]
    (cond
      (= "handoffd" caller) true
      (and (= "lieutenant" caller)
           (not (str/blank? name))
           (fs/regular-file? (lt-allow-file root name act)))
      true
      :else false)))

(defn require-caller! [opts act]
  (when-not (caller-allowed? opts act)
    (exit! 1 (str act " requires --caller handoffd or --caller lieutenant with Attention"))))

(defn consume-allow! [opts act]
  (when (= "lieutenant" (:caller opts))
    (when-let [name (not-empty (caller-task-name opts))]
      (fs/delete-if-exists (lt-allow-file (resolve-root opts) name act)))))

(defn set-lane! [opts lane]
  (let [act (if (= "done" lane) "done" "move")]
    (require-caller! opts act)
    (let [name (task-name opts)
          root (resolve-root opts)
          file (tasks-file root)]
      (require-value! name "task name")
      (require-value! lane "lane")
      (let [before (atom nil)]
        (with-board-lock
          root
          (fn []
            (let [rows (read-rows file)
                  line (find-task rows name)]
              (when-not line
                (exit! 1 (str "Unknown task name: " name)))
              (reset! before (card-type/parse-row line))
              (write-rows file (mapv #(rewrite-lane % name lane) rows)))))
        (consume-allow! opts act)
        @before))))

(defn move! [opts]
  (let [root (resolve-root opts)
        name (task-name opts)
        lane (task-lane opts)
        merge-from (:merge-from opts)
        before (set-lane! opts lane)]
    (require-merge-from! root merge-from)
    (when-not (str/blank? merge-from)
      (update-task-doc-merge-from! root name merge-from))
    (when (and before
               (= "waiting" (:lane before))
               (not (#{"waiting" "done"} lane)))
      (queue-start-note! root name lane (:id before) (task-text root name)))
    (when (and before
               (= "waiting" lane)
               (not (#{"waiting" "done"} (:lane before))))
      (halt-live-card! root before))))

(defn done! [opts]
  (set-lane! opts "done"))

(defn list! [opts]
  (let [file (tasks-file (resolve-root opts))]
    (when (fs/exists? file)
      (print (slurp (str file)))
      (flush))))

(defn roles-file [root]
  (fs/path root ".swarmforge" "roles.tsv"))

(defn role-rows [root]
  (map #(str/split % #"\t" -1) (read-rows (roles-file root))))

(defn lanes! [opts]
  (doseq [cols (role-rows (resolve-root opts))]
    (println (first cols))))

(defn master-lane! [opts]
  (let [masters (filterv #(= "master" (second %)) (role-rows (resolve-root opts)))]
    (when-not (= 1 (count masters))
      (exit! 1 "Config must name exactly one master worktree"))
    (println (ffirst masters))))

(defn tmux-socket [root]
  (let [file (fs/path root ".swarmforge" "tmux-socket")]
    (when (fs/exists? file)
      (not-empty (str/trim (slurp (str file)))))))

(defn tmux-stub []
  (System/getenv "SWARMFORGE_TMUX_STUB"))

(defn send-keys! [socket session & keys]
  (let [argv (into ["tmux" "-S" socket "send-keys" "-t" session] keys)]
    (if-let [stub (tmux-stub)]
      (do (fs/create-dirs (fs/parent stub))
          (spit (str stub) (str (pr-str (vec argv)) "\n") :append true))
      (apply sh argv))))

(defn session-for-role [root role]
  (when-let [row (some #(when (= role (first %)) %) (role-rows root))]
    (let [session (nth row 3 nil)]
      (if (str/blank? session)
        (str "swarmforge-" role)
        session))))

(defn master-role-name [root]
  (some (fn [cols]
          (when (= "master" (second cols))
            (first cols)))
        (role-rows root)))

(defn forge-root [root]
  (let [parent (fs/parent root)
        grand (when parent (fs/parent parent))]
    (when (and parent grand
               (= "projects" (fs/file-name parent))
               (fs/directory? (fs/path grand "projects")))
      (str grand))))

(defn inject-pane! [root role text]
  (when-not (or (str/blank? role) (str/blank? text))
    (when-let [socket (tmux-socket root)]
      (when-let [session (session-for-role root role)]
        (send-keys! socket session "-l" text)
        (send-keys! socket session "C-m")
        (send-keys! socket session "C-j")))))

(defn tmux-pane [root role]
  (let [socket (tmux-socket root)
        session (session-for-role root role)]
    (when (and socket session)
      (let [result (sh "tmux" "-S" socket "capture-pane" "-p" "-t" session "-S" "-")]
        (when (zero? (:exit result))
          (:out result))))))

(defn pane-text [root role]
  (or (System/getenv "SWARMFORGE_PANE_STUB")
      (tmux-pane root role)))

(defn archive-session! [root role]
  (when-not (str/blank? role)
    (when-let [text (pane-text root role)]
      (let [file (fs/path root ".swarmforge" "sessions" role "pane.txt")]
        (fs/create-dirs (fs/parent file))
        (spit (str file) text)))))

(defn archive-role [opts]
  (or (:archive opts) (:role opts) (second (:positional opts))))

(defn archive! [opts]
  (let [role (archive-role opts)]
    (require-value! role "role")
    (archive-session! (resolve-root opts) role)))

(defn live-card [line]
  (let [[name lane] (str/split line #"\t")]
    (when (and (not (str/blank? name))
               (not (str/blank? lane))
               (not= "done" lane))
      [name lane])))

(defn archive-all! [opts]
  (let [root (resolve-root opts)
        roles (->> (read-rows (tasks-file root))
                   (keep live-card)
                   (map second)
                   distinct)]
    (doseq [role roles]
      (archive-session! root role))))

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

(defn handoff-headers [file]
  (into {}
        (for [line (take-while (complement str/blank?)
                               (str/split-lines (slurp (str file))))
              :let [[k v] (str/split line #": " 2)]
              :when (and k v)]
          [k v])))

(defn handoff-for-card? [file name task-id]
  (let [h (handoff-headers file)
        task (get h "task")
        id (get h "task_id")]
    (or (= name task)
        (and (not (str/blank? task-id))
             (or (= task-id id) (= task-id task))))))

(defn drop-card-handoffs-in! [dir name task-id]
  (when (fs/directory? dir)
    (doseq [file (->> (fs/list-dir dir)
                      (filter #(and (fs/regular-file? %)
                                    (str/ends-with? (fs/file-name %) ".handoff"))))]
      (when (handoff-for-card? file name task-id)
        (fs/delete-if-exists file)))
    (doseq [batch (->> (fs/list-dir dir)
                       (filter #(and (fs/directory? %)
                                     (str/starts-with? (fs/file-name %) "batch_"))))]
      (drop-card-handoffs-in! batch name task-id)
      (when (empty? (filter #(str/ends-with? (fs/file-name %) ".handoff")
                            (if (fs/directory? batch) (fs/list-dir batch) [])))
        (fs/delete-tree batch)))))

(defn worktree-for-lane [root lane]
  (some (fn [cols]
          (when (= lane (first cols))
            (not-empty (nth cols 2 nil))))
        (role-rows root)))

(defn tell-agent-stopped! [root role]
  (inject-pane! root role "The lieutenant stopped this card. Stop executing it."))

(defn write-reset-failed-notify! [root name lane message]
  (let [dest (or (forge-root root) (str root))
        dir (fs/path dest ".swarmforge" "notify")
        stamp (str/replace (str (java.time.Instant/now)) #"[^0-9A-Za-z]" "")
        file (fs/path dir (str stamp "-reset-failed.notify"))]
    (fs/create-dirs dir)
    (spit (str file)
          (str "event: reset-failed\n"
               "task: " name "\n"
               "lane: " lane "\n"
               "error: " message "\n"))))

(defn report-reset-failure! [root name lane message]
  (write-reset-failed-notify! root name lane message)
  (let [text (str "git reset failed for " name " on " lane ": " message)]
    (if-let [forge (forge-root root)]
      (inject-pane! forge "lieutenant" text)
      (inject-pane! root (or (master-role-name root) lane) text))))

(defn drop-card-pending-approval! [root name task-id]
  (drop-card-handoffs-in! (fs/path root ".swarmforge" "handoffs" "pending_approval")
                          name task-id))

(defn audit-matches-card? [path name task-id]
  (try
    (let [cand (:candidate (edn/read-string (slurp (str path))))
          id (or (:task-id cand) (:task cand))]
      (or (= name id) (= task-id id) (= name (:task cand))))
    (catch Exception _
      false)))

(defn drop-card-audits! [root name task-id]
  (let [dir (fs/path root ".swarmforge" "handoffs" "audit_pending")]
    (when (fs/directory? dir)
      (doseq [file (->> (concat (fs/glob dir "*.edn")
                                (fs/glob dir "**/*.edn"))
                        (filter fs/regular-file?)
                        distinct)]
        (when (audit-matches-card? file name task-id)
          (fs/delete-if-exists file))))))

(defn in-process-handoffs [in-process name task-id]
  (->> (concat
        (filter #(and (fs/regular-file? %)
                      (str/ends-with? (fs/file-name %) ".handoff"))
                (fs/list-dir in-process))
        (mapcat (fn [batch]
                  (if (fs/directory? batch)
                    (filter #(str/ends-with? (fs/file-name %) ".handoff")
                            (fs/list-dir batch))
                    []))
                (filter #(and (fs/directory? %)
                              (str/starts-with? (fs/file-name %) "batch_"))
                        (fs/list-dir in-process))))
       (filter #(handoff-for-card? % name task-id))))

(defn git-reset-failure [wt base name]
  (cond
    (str/blank? base) (str "no task_base_commit for " name)
    :else
    (let [result (command wt "git" "reset" "--hard" base)]
      (when-not (zero? (:exit result))
        (str "git reset --hard " base " failed: "
             (str/trim (str (:err result) " " (:out result))))))))

(defn reset-in-process! [root lane name task-id]
  (when-let [wt (worktree-for-lane root lane)]
    (let [in-process (fs/path wt ".swarmforge" "handoffs" "inbox" "in_process")]
      (when (fs/directory? in-process)
        (let [files (in-process-handoffs in-process name task-id)
              base (some #(not-empty (get (handoff-headers %) "task_base_commit")) files)
              msg (when (seq files) (git-reset-failure wt base name))]
          (drop-card-handoffs-in! in-process name task-id)
          msg)))))

(defn halt-live-card! [root before]
  (when (and before (not (#{"waiting" "done"} (:lane before))))
    (let [name (:name before)
          lane (:lane before)
          task-id (:id before)]
      (doseq [wt (cons (str root)
                       (keep #(nth % 2 nil) (role-rows root)))]
        (drop-card-handoffs-in! (fs/path wt ".swarmforge" "handoffs" "outbox") name task-id)
        (drop-card-handoffs-in! (fs/path wt ".swarmforge" "handoffs" "inbox" "new") name task-id))
      (drop-card-pending-approval! root name task-id)
      (drop-card-audits! root name task-id)
      (when-let [msg (reset-in-process! root lane name task-id)]
        (report-reset-failure! root name lane msg))
      (tell-agent-stopped! root lane))))

(defn stop! [opts]
  (require-caller! opts "stop")
  (let [name (task-name opts)
        root (resolve-root opts)
        file (tasks-file root)]
    (require-value! name "task name")
    (let [row (atom nil)]
      (with-board-lock
        root
        (fn []
          (let [rows (read-rows file)
                line (find-task rows name)]
            (when-not line
              (exit! 1 (str "Unknown task name: " name)))
            (reset! row (card-type/parse-row line))
            (write-rows file (mapv #(rewrite-lane % name "waiting") rows)))))
      (halt-live-card! root @row)
      (consume-allow! opts "stop"))))

(def commands
  {"create" create!
   "move" move!
   "done" done!
   "stop" stop!
   "list" list!
   "lanes" lanes!
   "master-lane" master-lane!
   "archive" archive!
   "archive-all" archive-all!
   "increment-audit" increment-audit!
   "request-allow" request-allow!
   "allow" allow!
   "delete" delete!})

(defn -main [& args]
  (let [opts (parse-args args)
        command (get commands (first (:positional opts)))]
    (if command
      (command opts)
      (do (usage)
          (exit! 1 nil))))
  (System/exit 0))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
