;; Commit/worktree facts, header fill, and state errors. Loaded into swarm-handoff.

(defn role-worktree [role]
  (some (fn [line]
          (let [cols (str/split line #"\t")]
            (when (and (= role (first cols)) (>= (count cols) 3))
              (nth cols 2))))
        (str/split-lines (slurp (str (roles-file))))))

(defn git-cwd []
  (or (not-empty (role-worktree (sender-role)))
      (git-root)
      "."))

(defn worktree-head []
  (let [result (command (git-cwd) "git" "rev-parse" "--short=10" "HEAD")]
    (when-not (zero? (:exit result))
      (exit! 1 "Cannot read HEAD commit."))
    (str/trim (:out result))))

(defn under-dir? [file dir]
  (let [file (str (fs/canonicalize file))
        dir (str (fs/canonicalize dir))]
    (str/starts-with? file (str dir "/"))))

(defn worktree-tmp []
  (let [dir (fs/path (git-cwd) "tmp")]
    (fs/create-dirs dir)
    dir))

(defn require-worktree-tmp-draft! [draft]
  (when-not (under-dir? draft (worktree-tmp))
    (exit! 1 (str "Draft must live in ./tmp/ in the assigned worktree; got " draft))))

(defn commit-on-sender-branch? [sha]
  (zero? (:exit (command (git-cwd) "git" "merge-base" "--is-ancestor" sha "HEAD"))))

(defn commit-descends-from? [base sha]
  (zero? (:exit (command (git-cwd) "git" "merge-base" "--is-ancestor" base sha))))

(defn named-files [result]
  (->> (:out result)
       str/split-lines
       (remove str/blank?)
       distinct
       vec))

(defn banned-path-errors [card-type files added]
  (if-not (contains? #{"utility" "review"} card-type)
    []
    (cond-> []
      (some #(or (str/starts-with? % "features/") (str/ends-with? % ".feature")) files)
      (conj "This card type must not add features/*.feature.")
      (some #(str/starts-with? % "qa/") added)
      (conj "This card type must not add QA procedures."))))

(defn commit-named-files [sha diff-filter]
  (if-let [base (not-empty (current-task-base))]
    (named-files (command (git-cwd) "git" "diff" "--name-only"
                          (str "--diff-filter=" diff-filter) base sha))
    (let [against-parent (command (git-cwd) "git" "diff" "--name-only"
                                  (str "--diff-filter=" diff-filter) (str sha "^") sha)]
      (if (zero? (:exit against-parent))
        (named-files against-parent)
        (named-files (command (git-cwd) "git" "diff-tree" "--root"
                              "--no-commit-id" "--name-only"
                              (str "--diff-filter=" diff-filter) "-r" sha))))))

(defn commit-artifacts [sha]
  (commit-named-files sha "ACMRT"))

(defn commit-added [sha]
  (commit-named-files sha "A"))

(defn state-dir []
  (fs/path (project-root) ".swarmforge" "handoffs"))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn id-timestamp []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'")
           (.atZone (java.time.Instant/now) java.time.ZoneOffset/UTC)))

(defn sha256 [text]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str text) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))


(load-file (str (fs/path script-dir "swarm_handoff_audit.bb")))


(defn valid-priority? [priority]
  (boolean (and priority (re-matches #"[0-9][0-9]" priority))))

(defn fill-commit [headers]
  (if (= "git_handoff" (get headers "type"))
    (assoc headers "commit" (worktree-head))
    headers))

(defn fill-priority [headers]
  (if (valid-priority? (get headers "priority"))
    headers
    (assoc headers "priority" "50")))

(defn fill-card-type [headers sender]
  (if-not (= "git_handoff" (get headers "type"))
    headers
    (let [card (or (board-card-named (get headers "task"))
                   (first (board-cards-in-lane sender)))]
      (if card
        (assoc headers "card_type" (:type card))
        headers))))

(defn prepare-headers [headers sender]
  (-> headers
      fill-commit
      (with-board-task sender)
      (fill-card-type sender)
      (with-non-forwarding sender)
      fill-priority))

(defn state-root []
  (fs/path (project-root) ".swarmforge"))

(defn board-rows []
  (let [file (fs/path (state-root) "board" "tasks.tsv")]
    (if (fs/exists? file)
      (->> (str/split-lines (slurp (str file)))
           (remove str/blank?)
           (map #(let [[name lane _created _updated task-id] (str/split % #"\t" -1)]
                   {:name name :lane lane :id (or (not-empty task-id) name)}))
           vec)
      [])))

(defn board-present? []
  (fs/exists? (fs/path (state-root) "board" "tasks.tsv")))

(defn board-task [task-id]
  (some #(when (= task-id (:id %)) %) (board-rows)))

(defn rejected-task? [task]
  (let [name (:name task)]
    (and (not (str/blank? name))
         (fs/exists? (fs/path (state-root) "notify" (str "reject-" name))))))

(defn task-state-errors [headers sender]
  (if-not (= "git_handoff" (get headers "type"))
    []
    (let [task-id (or (not-empty (get headers "task_id"))
                      (get headers "task"))
          in-process-id (current-in-process-task-id)
          task (board-task task-id)]
      (cond-> []
        (str/blank? task-id)
        (conj "Missing required header 'task_id' for git_handoff.")
        (and in-process-id (not= task-id in-process-id))
        (conj (format "Handoff task_id '%s' does not match current in-process task_id '%s'."
                      task-id in-process-id))
        (and (board-present?) (nil? in-process-id) (not task))
        (conj (format "Handoff task_id '%s' is not a current board task." task-id))
        (and task (= "done" (:lane task)))
        (conj (format "Task '%s' is done and cannot accept new handoffs." (:name task)))
        (rejected-task? task)
        (conj (format "Task '%s' is rejected and must be retried before handoff." (:name task)))))))

(def active-states
  [["pending approvals" (fn [] [(fs/path (state-dir) "pending_approval")])]
   ["sent" (fn []
             (concat [(fs/path (state-dir) "sent")]
                     (for [line (str/split-lines (slurp (str (roles-file))))
                           :let [cols (str/split line #"\t" -1)
                                 wt (nth cols 2 nil)]
                           :when (not (str/blank? wt))]
                       (fs/path wt ".swarmforge" "handoffs" "sent"))))]
   ["recipient inbox" (fn []
                        (for [line (str/split-lines (slurp (str (roles-file))))
                              :let [cols (str/split line #"\t" -1)
                                    wt (nth cols 2 nil)]
                              :when (not (str/blank? wt))
                              state ["new" "in_process"]]
                          (fs/path wt ".swarmforge" "handoffs" "inbox" state)))]] )

(defn recursive-handoff-files [dir]
  (if (fs/directory? dir)
    (->> (concat (fs/glob dir "*.handoff")
                 (fs/glob dir "**/*.handoff"))
         (filter fs/regular-file?)
         distinct
         vec)
    []))

(defn header-map [file]
  (into {}
        (for [line (take-while (complement str/blank?) (str/split-lines (slurp (str file))))
              :let [[k v] (str/split line #": " 2)]
              :when (and k v)]
          [k v])))

(defn same-active-handoff? [sender recipients headers canonical-commit path]
  (let [h (header-map path)
        task-id (or (not-empty (get headers "task_id")) (get headers "task"))
        other-id (or (not-empty (get h "task_id")) (get h "task"))]
    (and (= sender (get h "from"))
         (= (set recipients) (set (str/split (or (get h "to") "") #",")))
         (= task-id other-id)
         (= canonical-commit (get h "commit")))))

(defn duplicate-errors [sender recipients headers canonical-commit]
  (if-not (= "git_handoff" (get headers "type"))
    []
    (let [matches (for [[label dirs-fn] active-states
                        dir (dirs-fn)
                        file (recursive-handoff-files dir)
                        :when (same-active-handoff? sender recipients headers canonical-commit file)]
                    (str label ": " file))]
      (if (seq matches)
        [(str "Duplicate active handoff for same from/to/task_id/commit exists: "
              (str/join ", " matches))]
        []))))

(defn ancestry-errors [headers canonical-commit]
  (if-not (= "git_handoff" (get headers "type"))
    []
    (let [base (current-task-base)]
      (cond-> []
        (and (not (str/blank? base))
             (not (str/blank? canonical-commit))
             (not (commit-descends-from? base canonical-commit)))
        (conj (format "Result commit %s is not a descendant of task base %s."
                      canonical-commit base))))))

(defn ensure-field [ordered field]
  (if (some #{field} ordered)
    ordered
    (conj (vec ordered) field)))


(load-file (str (fs/path script-dir "swarm_handoff_validate.bb")))
