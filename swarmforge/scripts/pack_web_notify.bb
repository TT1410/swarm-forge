;; Lieutenant inject and notify. Loaded into pack-web.

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
