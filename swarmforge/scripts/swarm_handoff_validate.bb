;; Draft parse and validation. Loaded into swarm-handoff.

(defn parse-draft [draft]
  (loop [lines (str/split-lines (slurp (str draft)))
         line-no 0
         body-seen? false
         headers {}
         ordered []
         errors []]
    (if-let [line (first lines)]
      (let [line-no (inc line-no)]
        (cond
          (or body-seen? (str/blank? line) (not (str/includes? line ": ")))
          (recur (next lines) line-no true headers ordered errors)

          :else
          (let [[field value] (str/split line #": " 2)]
            (cond
              (or (str/blank? field) (str/blank? value))
              (recur (next lines) line-no body-seen? headers ordered
                     (conj errors (format "Line %d: field and value must both be non-empty." line-no)))

              (reserved-fields field)
              (recur (next lines) line-no body-seen? headers ordered
                     (conj errors (format "Line %d: header '%s' is reserved and must not be written by agents." line-no field)))

              (not (allowed-fields field))
              (recur (next lines) line-no body-seen? headers ordered
                     (conj errors (format "Line %d: unknown header '%s'." line-no field)))

              (contains? headers field)
              (recur (next lines) line-no body-seen? headers ordered
                     (conj errors (format "Line %d: duplicate header '%s'." line-no field)))

              :else
              (recur (next lines) line-no body-seen? (assoc headers field value) (conj ordered field) errors)))))
      {:headers headers :ordered ordered :errors errors})))

(defn pack-role-names []
  (mapv first (handoff-lib/role-rows)))

(defn chain-recipient-errors [headers recipients]
  (let [card-type (get headers "card_type")
        sender (sender-role)]
    (if (or (not= "git_handoff" (get headers "type"))
            (str/blank? card-type)
            (not (card-type/on-chain? (project-root) card-type sender)))
      []
      (let [next (card-type/next-role (project-root) card-type sender)
            last? (card-type/last-on-card? (project-root) card-type sender)]
        (cond
          last?
          (let [want (set (card-type/terminal-upstream (project-root) (pack-role-names) card-type))
                got (set recipients)
                extra (set/difference got want)
                missing (set/difference want got)]
            (cond-> []
              (seq extra)
              (conj (format "Recipient '%s' is not upstream of last on this card."
                            (str/join "," (sort extra))))
              (seq missing)
              (conj (format "Terminal to: must include all upstream roles (%s)."
                            (str/join "," (card-type/terminal-upstream
                                           (project-root) (pack-role-names) card-type))))))
          :else
          (cond-> []
            (some #(not= % next) recipients)
            (conj (format "Recipient must be next on this card (%s); got %s."
                          next (str/join "," recipients)))
            (some #(not (card-type/on-chain? (project-root) card-type %)) recipients)
            (conj "Recipient is not on this card's chain.")))))))

(defn validate-recipients [to]
  (if (str/blank? to)
    [[] []]
    (let [recipients (str/split to #"," -1)]
      [recipients
       (loop [remaining recipients seen #{} errors []]
         (if-let [recipient (first remaining)]
           (let [errors (cond-> errors
                          (str/blank? recipient)
                          (conj "Header 'to' contains an empty recipient.")
                          (str/includes? recipient "_")
                          (conj (format "Recipient role '%s' is invalid; role names may not contain underscores." recipient))
                          (contains? seen recipient)
                          (conj (format "Duplicate recipient '%s'." recipient))
                          (and (not (str/blank? recipient)) (not (role-known? recipient)))
                          (conj (format "Unknown recipient role '%s'." recipient)))]
             (recur (next remaining) (conj seen recipient) errors))
           errors))])))

(defn canonical-commit [commit]
  (let [dir (git-cwd)
        matches (-> (command dir "git" "rev-parse" (str "--disambiguate=" commit))
                    :out
                    str/split-lines
                    vec)]
    (cond
      (not= 1 (count matches))
      [nil (format "Header 'commit' must resolve to exactly one Git object; '%s' matched %d." commit (count matches))]

      :else
      (let [object (first matches)
            object-type (str/trim (:out (command dir "git" "cat-file" "-t" object)))]
        (if (= "commit" object-type)
          [(str/trim (:out (command dir "git" "rev-parse" "--short=10" object))) nil]
          [nil (format "Header 'commit' must resolve to a commit; '%s' resolves to '%s'." commit object-type)])))))

(def allowed-fields-by-type
  {"git_handoff" #{"type" "to" "priority" "task_id" "task" "commit"}
   "note" #{"type" "to" "priority" "message"}})

(defn field-allowed? [type field]
  (contains? (get allowed-fields-by-type type) field))

(defn field-errors [type ordered]
  (if type
    (vec (for [field ordered
               :when (not (field-allowed? type field))]
           (format "Header '%s' is not allowed for type '%s'." field type)))
    []))

(defn base-errors [headers]
  (let [type (get headers "type")
        to (get headers "to")
        priority (get headers "priority")]
    (cond-> []
      (str/blank? type) (conj "Missing required header 'type'.")
      (str/blank? to) (conj "Missing required header 'to'.")
      (str/blank? priority) (conj "Missing required header 'priority'.")
      (and (not (str/blank? type)) (not (allowed-types type)))
      (conj (format "Header 'type' must be one of git_handoff or note; got '%s'." type))
      (and (not (str/blank? priority)) (not (valid-priority? priority)))
      (conj (format "Header 'priority' must be two digits from 00 to 99; got '%s'." priority)))))

(defn commit-check [type commit]
  (if (= "git_handoff" type)
    (cond
      (str/blank? commit) [nil "Missing required header 'commit' for git_handoff."]
      (not (re-matches #"[0-9a-fA-F]{10}" commit))
      [nil (format "Header 'commit' must be exactly 10 hexadecimal characters; got '%s'." commit)]
      :else (canonical-commit commit))
    [nil nil]))

(defn git-required-errors [headers]
  (let [task-name (get headers "task")]
    (cond-> []
      (str/blank? (get headers "task_id"))
      (conj "Missing required header 'task_id' for git_handoff.")
      (str/blank? task-name)
      (conj "Missing required header 'task' for git_handoff.")
      (> (count (or task-name "")) 80)
      (conj (format "Header 'task' must be no longer than 80 characters; got %d." (count task-name))))))

(defn git-header-errors [type headers]
  (let [task-name (get headers "task")
        commit (get headers "commit")]
    (cond-> []
      (= "git_handoff" type)
      (into (git-required-errors headers))
      (and (not= "git_handoff" type) (not (str/blank? commit)))
      (conj "Header 'commit' is only allowed for git_handoff.")
      (and (not= "git_handoff" type) (not (str/blank? task-name)))
      (conj "Header 'task' is only allowed for git_handoff."))))

(defn note-errors [type note-message]
  (cond-> []
    (= "note" type)
    (into (cond-> []
            (str/blank? note-message)
            (conj "Missing required header 'message' for note.")
            (> (count (or note-message "")) 80)
            (conj (format "Header 'message' must be no longer than 80 characters; got %d." (count note-message)))))
    (and (not= "note" type) (not (str/blank? note-message)))
    (conj "Header 'message' is only allowed for note.")))

(defn validate [headers ordered]
  (let [type (get headers "type")
        [recipients recipient-errors] (validate-recipients (get headers "to"))
        [canonical commit-error] (commit-check type (get headers "commit"))]
    {:recipients recipients
     :canonical-commit canonical
     :errors (vec (concat (base-errors headers)
                          recipient-errors
                          (chain-recipient-errors headers recipients)
                          (field-errors type ordered)
                          (git-header-errors type headers)
                          (if commit-error [commit-error] [])
                          (note-errors type (get headers "message"))))}))
