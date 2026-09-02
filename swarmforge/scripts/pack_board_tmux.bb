;; Pane inject and session archive. Loaded into pack-board.

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
