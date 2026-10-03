;; Dashboard state assembly. Loaded into pack-web.


(load-file (str (fs/path script-dir "pack_web_approvals.bb")))
(load-file (str (fs/path script-dir "pack_web_heat.bb")))
(load-file (str (fs/path script-dir "pack_web_chat.bb")))

(defn daemon-pid-file [root]
  (fs/path root ".swarmforge" "daemon" "handoffd.pid"))

(defn pid-alive? [pid]
  (boolean
   (try
     (let [handle (java.lang.ProcessHandle/of (Long/parseLong pid))]
       (and (.isPresent handle) (.isAlive (.get handle))))
     (catch Exception _ false))))

(defn daemon-state
  "Whether the handoff daemon runs. Without it no handoff leaves an outbox,
  so the whole swarm stands still."
  [root]
  (let [file (daemon-pid-file root)
        pid (when (fs/regular-file? file) (str/trim (slurp (str file))))]
    {:running (and (not (str/blank? pid)) (pid-alive? pid))}))

(defn dashboard-state [root]
  ;; The card list reads every mail header, so build it once per poll.
  (let [master (master-role root)
        heats (role-heats root)
        all-tasks (tasks root)]
    {:master_role master
     :master_display (display-name-for-role master)
     :card_types (card-type/card-types root)
     :lanes (display-lanes root)
     :tasks all-tasks
     :role_heats heats
     :approvals (approvals root)
     :delivery_failures (delivery-failures root)
     :board_allows (board-allows root)
     :work_in_flight (work-in-flight root heats all-tasks)
     :chat (list-chat root)
     :clarifications (list-clarifications root)
     :drain (ready-for-next-guard/drain-state root)
     :daemon (daemon-state root)}))

(defn tagged [project items]
  (mapv #(assoc % :project project) items))

(defn open-project-root [forge name]
  (str (forge/project-dir forge name)))

(defn project-slice [forge name entry]
  (let [root (open-project-root forge name)]
    (try
      (let [heats (role-heats root)
            all-tasks (tasks root)]
        {:name name
         :open (= "open" (:state entry))
         :state (:state entry)
         :error (:error entry)
         :card_types (card-type/card-types root)
         :lanes (display-lanes root)
         :tasks (tagged name all-tasks)
         :role_heats heats
         :drain (ready-for-next-guard/drain-state root)
         :daemon (daemon-state root)
         :work_in_flight (tagged name (work-in-flight root heats all-tasks))})
      (catch Exception _
        {:name name
         :open (= "open" (:state entry))
         :state (:state entry)
         :error (:error entry)
         :card_types []
         :lanes []
         :tasks []
         :work_in_flight []}))))

(defn forge-dashboard-state [root]
  (let [states (forge/reconcile-project-states! root)
        open (forge/read-open-projects root)
        effective-states (if (seq states)
                           states
                           (into {} (map (fn [name]
                                           [name {:state "open" :error "" :managed-runtime false}])
                                         open)))
        active (->> effective-states
                    (remove (fn [[_ entry]] (= "closed" (:state entry))))
                    (sort-by key))
        projects (mapv (fn [[name entry]] (project-slice root name entry)) active)]
    {:forge true
     :master_role "lieutenant"
     :master_display "Lieutenant"
     :packs (mapv (fn [p] {:name p :conf (or (forge/pack-conf root p) "")})
                  (forge/list-pack-names root))
     :all_projects (forge/list-project-names root)
     :open_projects open
     :project_states (mapv (fn [name]
                             (assoc (forge/project-state root name) :name name))
                           (forge/list-project-names root))
     :projects projects
     :delivery_failures (vec (mapcat (fn [[name _entry]]
                                       (try
                                         (tagged name (delivery-failures (open-project-root root name)))
                                         (catch Exception _ [])))
                                     active))
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
     :lieutenant_phase (let [row (role-row root "lieutenant")
                             socket (tmux-socket root)
                             down? (and row socket
                                        (not (session-alive? socket (session-name row))))
                             structured-status (when row
                                                 (structured-status-for-row root row))]
                         (cond
                           down? "no session"
                           (:active? structured-status) "working"
                           :else "idle"))
     :lieutenant_activity (let [heats (role-heats root)]
                            (get heats "lieutenant" 0))
     :lanes []
     :tasks []
     :work_in_flight (vec (mapcat :work_in_flight projects))}))

(defn api-state [root]
  (binding [*board-tasks-cache* (atom {})]
    (if (forge/forge? root)
      (forge-dashboard-state root)
      (dashboard-state root))))

(defn require-root! [root]
  (when (str/blank? root)
    (exit! 1 "Missing project root"))
  root)

(defn dashboard-page []
  (let [dir (fs/path script-dir "pack")
        html (slurp (str (fs/path dir "dashboard.html")))
        css (str/trim (slurp (str (fs/path dir "dashboard.css"))))
        js (str/join "\n"
                     [(slurp (str (fs/path dir "dashboard_board.js")))
                      (slurp (str (fs/path dir "dashboard_attention.js")))
                      (slurp (str (fs/path dir "dashboard_ui.js")))])]
    (-> html
        (str/replace "/*DASHBOARD_CSS*/" css)
        (str/replace "/*DASHBOARD_JS*/" js))))


(load-file (str (fs/path script-dir "pack_web_tasks.bb")))
(load-file (str (fs/path script-dir "pack_web_retry.bb")))
