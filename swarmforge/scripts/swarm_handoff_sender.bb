;; Sender role and board card lookup. Loaded into swarm-handoff.

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
      (lib-fail e))))

(defn roles-file []
  (handoff-lib/roles-file))

(defn role-known? [role]
  (try
    (handoff-lib/role-known? role)
    (catch clojure.lang.ExceptionInfo e
      (lib-fail e))))

(defn same-path? [a b]
  (handoff-lib/same-path? a b))

(defn infer-role-from-worktree []
  (handoff-lib/infer-role-from-worktree))

(defn sender-role []
  (try
    (handoff-lib/role)
    (catch clojure.lang.ExceptionInfo e
      (lib-fail e))))

(defn board-cards []
  (let [file (fs/path (project-root) ".swarmforge" "board" "tasks.tsv")]
    (if (fs/exists? file)
      (into []
            (keep (fn [line]
                    (let [row (card-type/parse-row line)]
                      (when (not (str/blank? (:name row)))
                        row))))
            (str/split-lines (slurp (str file))))
      [])))

(defn board-cards-in-lane [lane]
  (filterv #(= lane (:lane %)) (board-cards)))

(defn board-card-named [name]
  (some #(when (= name (:name %)) %) (board-cards)))
