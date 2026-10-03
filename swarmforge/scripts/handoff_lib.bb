#!/usr/bin/env bb

(ns handoff-lib
  (:require [babashka.fs :as fs]
            [babashka.process]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn same-path? [a b]
  (try
    (= (str (fs/canonicalize a)) (str (fs/canonicalize b)))
    (catch Exception _
      (= (str a) (str b)))))

(defn git-toplevel []
  (let [out (:out (babashka.process/sh {:continue true} "git" "rev-parse" "--show-toplevel"))]
    (when-not (str/blank? out)
      (str/trim out))))

(defn state-dir []
  (fs/path (System/getProperty "user.dir") ".swarmforge" "handoffs"))

(defn inbox-dir []
  (fs/path (state-dir) "inbox"))

(defn roles-at? [root]
  (and root (fs/exists? (fs/path root ".swarmforge" "roles.tsv"))))

(defn git-common-dir []
  (let [out (:out (babashka.process/sh {:continue true} "git" "rev-parse" "--git-common-dir"))]
    (when-not (str/blank? out)
      (let [path (fs/path (str/trim out))]
        (str (if (fs/absolute? path) path (fs/absolutize path)))))))

(defn project-root []
  (or (let [parent (some-> (git-common-dir) fs/parent str)]
        (when (roles-at? parent) parent))
      (when (roles-at? (git-toplevel)) (git-toplevel))
      (when (roles-at? (fs/cwd)) (fs/cwd))
      (throw (ex-info "Cannot find SwarmForge project root" {:exit 1}))))

(defn roles-file []
  (fs/path (project-root) ".swarmforge" "roles.tsv"))

(defn role-rows []
  (->> (str/split-lines (slurp (str (roles-file))))
       (map #(str/split % #"\t" -1))))

(defn infer-role-from-worktree []
  (let [here (or (git-toplevel) (str (fs/cwd)))]
    (some (fn [cols]
            (let [role-name (first cols)
                  wt (when (>= (count cols) 3) (nth cols 2))]
              (when (and (not-empty role-name) (not-empty wt) (same-path? wt here))
                role-name)))
          (role-rows))))

(defn role []
  (or (not-empty (System/getenv "SWARMFORGE_ROLE"))
      (infer-role-from-worktree)
      (throw (ex-info "Set SWARMFORGE_ROLE." {:exit 1}))))

(defn role-row [role-name]
  (or (some #(when (= role-name (first %)) %) (role-rows))
      (throw (ex-info (str "Unknown role: " role-name) {:exit 1}))))

(defn role-known? [role-name]
  (boolean (some #(= role-name (first %)) (role-rows))))

(defn role-worktree-name [role-name]
  (second (role-row role-name)))

(defn role-receive-mode [role-name]
  (let [mode (nth (role-row role-name) 6 "")]
    (if (str/blank? mode) "task" mode)))

(defn role-propagation [role-name]
  (let [mode (nth (role-row role-name) 7 "")]
    (if (str/blank? mode) "forward-only" mode)))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn id-timestamp []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'")
           (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)))

(defn valid-priority? [value]
  (boolean (re-matches #"[0-9][0-9]" value)))

(defn header-field [file field]
  (let [prefix (str field ": ")]
    (some (fn [line]
            (when (str/starts-with? line prefix)
              (subs line (count prefix))))
          (take-while (complement str/blank?)
                      (str/split-lines (slurp (str file)))))))

(defn body [file]
  (let [[_ body] (str/split (slurp (str file)) #"\n\n" 2)]
    (or body "")))

(defn rewrite-header-line [prefix value line]
  (if (str/starts-with? line prefix) (str prefix value) line))

(defn has-header? [prefix lines]
  (boolean (some #(str/starts-with? % prefix) lines)))

(defn append-header [headers prefix value]
  (if (has-header? prefix headers)
    headers
    (conj (vec headers) (str prefix value))))

(defn set-header-lines [lines field value]
  (let [prefix (str field ": ")
        headers (vec (take-while (complement str/blank?) lines))
        rest-lines (drop-while (complement str/blank?) lines)
        rewritten (mapv #(rewrite-header-line prefix value %) headers)]
    (concat (append-header rewritten prefix value) rest-lines)))

(defn set-header! [file field value]
  (let [file (fs/path file)
        tmp (fs/create-temp-file {:dir (fs/parent file) :prefix ".headers."})]
    (spit (str tmp) (str (str/join "\n" (set-header-lines (str/split-lines (slurp (str file))) field value)) "\n"))
    (fs/move tmp file {:replace-existing true})))

(defn print-task [file]
  (let [task-name (header-field file "task")
        task-id (header-field file "task_id")]
    (println "TASK:" (str file))
    (println "FROM:" (or (header-field file "from") "unknown"))
    (println "TYPE:" (or (header-field file "type") "unknown"))
    (println "PRIORITY:" (or (header-field file "priority") "50"))
    (when task-name
      (println "TASK_NAME:" task-name))
    (when task-id
      (println "TASK_ID:" task-id))
    (println "PAYLOAD:")
    (print (body file))))

(defn handoff-files [dir]
  (if (fs/exists? dir)
    (->> (fs/list-dir dir)
         (filter #(and (fs/regular-file? %) (str/ends-with? (fs/file-name %) ".handoff")))
         (sort-by #(fs/file-name %))
         vec)
    []))

(defn split-list [value]
  (->> (str/split (or value "") #",")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn batch-dirs [dir]
  (if (fs/exists? dir)
    (->> (fs/list-dir dir)
         (filter #(and (fs/directory? %) (str/starts-with? (fs/file-name %) "batch_")))
         (sort-by #(fs/file-name %))
         vec)
    []))

(defn in-process-files []
  (let [dir (fs/path (inbox-dir) "in_process")]
    (into (handoff-files dir)
          (mapcat handoff-files (batch-dirs dir)))))

(defn mail-task-id [file]
  (or (not-empty (header-field file "task_id"))
      (not-empty (header-field file "task"))))

(declare board-cards find-card)

(defn batch-task-id-list
  "The batch_task_ids header: an EDN vector of task ids, or [] when absent or malformed."
  [value]
  (if (str/blank? value)
    []
    (try
      (let [parsed (edn/read-string value)]
        (if (and (vector? parsed) (every? string? parsed)) parsed []))
      (catch Exception _ []))))

(defn mail-card-ids
  "Cards a mail carries (its task plus any batch_task_ids), as board task ids
  when the board knows the card."
  [file]
  (let [cards (board-cards)]
    (->> (cons (mail-task-id file) (batch-task-id-list (header-field file "batch_task_ids")))
         (remove str/blank?)
         (map #(or (:id (find-card cards %)) %))
         distinct
         vec)))

(defn handed-card-ids [file]
  (set (split-list (header-field file "handed_task_ids"))))

(defn card-mail?
  "Mail that carries a card the receiving role must hand off: forwarding
  git handoffs and notes that name a board task (new or retried cards)."
  [file]
  (boolean (and (not= "true" (header-field file "non-forwarding"))
                (not (#{"reverse" "terminal"} (header-field file "delivery_kind")))
                (or (= "git_handoff" (header-field file "type"))
                    (not-empty (header-field file "task_id")))
                (seq (mail-card-ids file)))))

(defn board-file []
  (fs/path (project-root) ".swarmforge" "board" "tasks.tsv"))

(defn board-present? []
  (fs/exists? (board-file)))

(defn board-cards []
  (if (board-present?)
    (->> (str/split-lines (slurp (str (board-file))))
         (remove str/blank?)
         (mapv #(let [[name lane _created _updated task-id] (str/split % #"\t" -1)]
                  {:name name :lane lane :id (or (not-empty task-id) name)})))
    []))

(defn find-card [cards key]
  (or (some #(when (= key (:id %)) %) cards)
      (some #(when (= key (:name %)) %) cards)))

(defn recursive-handoff-files [dir]
  (if (fs/directory? dir)
    (->> (fs/glob dir "**.handoff")
         (filter fs/regular-file?)
         vec)
    []))

(defn outbound-card-ids
  "Cards named by git handoffs from role-name that wait for approval or delivery."
  [role-name]
  (let [root (project-root)
        dirs (cons (fs/path root ".swarmforge" "handoffs" "pending_approval")
                   (cons (fs/path root ".swarmforge" "handoffs" "outbox")
                         (for [cols (role-rows)
                               :let [wt (nth cols 2 nil)]
                               :when (not (str/blank? wt))]
                           (fs/path wt ".swarmforge" "handoffs" "outbox"))))]
    (->> dirs
         (mapcat recursive-handoff-files)
         (filter #(and (= "git_handoff" (header-field % "type"))
                       (= role-name (header-field % "from"))))
         (mapcat mail-card-ids)
         set)))

(defn open-card-ids
  "Cards in current work that still need an outgoing handoff from role-name.
  With a board, only cards still in the role's lane count."
  ([role-name] (open-card-ids role-name (in-process-files)))
  ([role-name files]
   (let [board? (board-present?)
         cards (board-cards)
         outbound (outbound-card-ids role-name)]
     (->> files
          (filter card-mail?)
          (mapcat (fn [file] (remove (handed-card-ids file) (mail-card-ids file))))
          distinct
          (remove outbound)
          (filter (fn [id]
                    (if board?
                      (= role-name (:lane (find-card cards id)))
                      true)))
          vec))))

(defn card-label [id]
  (let [card (find-card (board-cards) id)]
    (if (and card (not= id (:name card)))
      (str (:name card) " (" id ")")
      id)))

(defn print-batch [batch-dir]
  (let [files (handoff-files batch-dir)]
    (when (empty? files)
      (throw (ex-info (str "AMBIGUOUS_TASK_STATE: batch contains no tasks: " batch-dir) {:exit 2})))
    (println "BATCH:" (str batch-dir))
    (println "COUNT:" (count files))
    (when-let [name (header-field (first files) "task")]
      (println "TASK_NAME:" name))
    (println "PRIORITY:" (or (header-field (first files) "priority") "50"))
    (doseq [[index file] (map-indexed vector files)]
      (println)
      (println "BATCH_ITEM:" (inc index))
      (print-task file))))

(defn archive-current-role! []
  (let [script (str (fs/path (fs/parent *file*) "pack_board.sh"))
        result (babashka.process/sh {:continue true}
                                    script "archive" "--role" (role)
                                    "--root" (str (project-root)))]
    (when-not (zero? (:exit result))
      (binding [*out* *err*]
        (print (str (:err result) (:out result)))))))

(defn announce-follow-up! []
  (if (seq (handoff-files (fs/path (inbox-dir) "new")))
    (println "MAIL_WAITING")
    (println "NO_TASK")))

(defn finish-done! []
  (try
    (archive-current-role!)
    (catch Exception e
      (binding [*out* *err*]
        (println (str "archive failed role=" (try (role) (catch Exception _ "?"))
                      " root=" (try (str (project-root)) (catch Exception _ "?"))
                      " error=" (.getMessage e)))
        (flush))))
  (announce-follow-up!))

(defn next-sequence []
  (let [dir (state-dir)
        seq-file (fs/path dir "sequence")
        lock-dir (fs/path dir "sequence.lock")]
    (fs/create-dirs dir)
    (loop []
      (when-not (try (fs/create-dir lock-dir) true (catch Exception _ false))
        (Thread/sleep 50)
        (recur)))
    (try
      (let [last-value (if (fs/exists? seq-file)
                         (str/trim (slurp (str seq-file)))
                         "0")
            last-number (if (re-matches #"[0-9]+" last-value)
                          (Long/parseLong last-value)
                          0)
            next-number (inc last-number)]
        (spit (str seq-file) (format "%06d\n" next-number))
        (format "%06d" next-number))
      (finally
        (fs/delete-tree lock-dir)))))

(defn print-header-or-exit [value]
  (if value (println value) (System/exit 1)))

(defn unknown-lib-command [_]
  (binding [*out* *err*]
    (println "Usage: handoff_lib.bb <command> [args...]"))
  (System/exit 2))

;; Card meta: per-card settings the board row has no column for (level,
;; how a TODO card starts, blockers). Keyed by task id, which survives a
;; rename, in .swarmforge/board/meta/<task-id>.edn.

(def level-priorities
  "Task level -> handoff priority. Lower runs first; the gaps keep equal-level
  cards batching together."
  {"critical" "10" "high" "30" "normal" "50" "low" "70"})

(defn level-priority [level]
  (get level-priorities (some-> level str/lower-case str/trim)))

(defn card-meta-file [root task-id]
  (fs/path root ".swarmforge" "board" "meta"
           (str (str/replace (str task-id) #"[^A-Za-z0-9._-]" "_") ".edn")))

(defn read-card-meta [root task-id]
  (let [file (when-not (str/blank? (str task-id)) (card-meta-file root task-id))]
    (or (when (and file (fs/regular-file? file))
          (try
            (let [value (clojure.edn/read-string (slurp (str file)))]
              (when (map? value) value))
            (catch Exception _ nil)))
        {})))

(defn write-card-meta! [root task-id meta]
  (let [file (card-meta-file root task-id)
        dir (fs/parent file)]
    (fs/create-dirs dir)
    (if (empty? meta)
      (fs/delete-if-exists file)
      (let [tmp (fs/create-temp-file {:dir dir :prefix ".meta."})]
        (spit (str tmp) (str (pr-str meta) "\n"))
        (fs/move tmp file {:replace-existing true :atomic-move true})))))

(def card-meta-lock
  "The dashboard changes level, links and start role on separate request
  threads; one lock keeps a read-modify-write from losing another's edit."
  (Object.))

(defn update-card-meta! [root task-id f & args]
  (locking card-meta-lock
    (let [meta (apply f (read-card-meta root task-id) args)]
      (write-card-meta! root task-id meta)
      meta)))

(defn delete-card-meta! [root task-id]
  (when-not (str/blank? (str task-id))
    (fs/delete-if-exists (card-meta-file root task-id))))

(def lib-commands
  {"role" (fn [_] (println (role)))
   "state-dir" (fn [_] (println (state-dir)))
   "inbox-dir" (fn [_] (println (inbox-dir)))
   "project-root" (fn [_] (println (project-root)))
   "role-known" (fn [args] (System/exit (if (role-known? (second args)) 0 1)))
   "role-worktree-name" (fn [args] (println (role-worktree-name (second args))))
   "role-receive-mode" (fn [args] (println (role-receive-mode (second args))))
   "role-propagation" (fn [args] (println (role-propagation (second args))))
   "timestamp" (fn [_] (println (timestamp)))
   "id-timestamp" (fn [_] (println (id-timestamp)))
   "valid-priority" (fn [args] (System/exit (if (valid-priority? (second args)) 0 1)))
   "header-field" (fn [args] (print-header-or-exit (header-field (second args) (nth args 2))))
   "body" (fn [args] (print (body (second args))))
   "set-header" (fn [args] (set-header! (second args) (nth args 2) (nth args 3)))
   "print-task" (fn [args] (print-task (second args)))
   "print-batch" (fn [args] (print-batch (second args)))
   "next-sequence" (fn [_] (println (next-sequence)))
   "open-cards" (fn [_] (doseq [id (open-card-ids (role))] (println (card-label id))))
   "finish-done" (fn [_] (finish-done!))})

(defn -main [& args]
  (try
    ((get lib-commands (first args) unknown-lib-command) args)
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println (ex-message e)))
      (System/exit (or (:exit (ex-data e)) 1)))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
