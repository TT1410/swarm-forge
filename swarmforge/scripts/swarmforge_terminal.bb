;; Terminal adapters, windows, pack_web. Loaded into swarmforge.

(defn adapter-script [ctx command & args]
  (let [script (str "SCRIPT_DIR=" (sq (str (:script-dir ctx))) "\n"
                    "WORKING_DIR=" (sq (str (:working-dir ctx))) "\n"
                    "TMUX_SOCKET=" (sq (:tmux-socket ctx)) "\n"
                    "source " (sq (str (fs/path (:script-dir ctx) "swarm-terminal-adapter.sh")))
                    " && load_terminal_backend " (sq (:terminal-backend ctx))
                    " && " command
                    (apply str (map #(str " " (sq %)) args)))]
    ["zsh" "-c" script]))

(defn terminal-call [ctx command & args]
  (apply process/sh (apply adapter-script ctx command args)))

(defn terminal-call-ok? [ctx command & args]
  (zero? (:exit (apply process/sh (concat [{:continue true}] (apply adapter-script ctx command args))))))

(defn terminal-call-out [ctx command & args]
  (str/trim (:out (apply terminal-call ctx command args))))

(defn skip-terminal? [row]
  (not (:visible? row)))

(defn record-window! [ctx index window-id row]
  (spit (str (:window-ids-file ctx)) (str window-id "\n") :append true)
  (spit (str (:window-state-file ctx))
        (format "%d\t%s\t%s\t%s\n"
                (inc index) window-id (:session row)
                (str "SwarmForge " (:display-name row)))
        :append true))

(defn open-one-session! [ctx row previous-window-id]
  (terminal-call-out ctx "terminal_open_session"
                     (:session row)
                     (str "SwarmForge " (:display-name row))
                     previous-window-id))

(defn open-role-terminal! [ctx row previous-window-id index]
  (if (skip-terminal? row)
    previous-window-id
    (let [window-id (open-one-session! ctx row previous-window-id)]
      (when (terminal-call-ok? ctx "terminal_backend_tracks_windows")
        (record-window! ctx index window-id row))
      window-id)))

(defn start-window-watchdog! [ctx]
  (process/process [(str (fs/path (:script-dir ctx) "swarm-window-watchdog.sh"))
                    (str (:window-state-file ctx))
                    (str (:window-ids-file ctx))
                    "1"
                    (:tmux-socket ctx)
                    (str (:working-dir ctx))
                    (:terminal-backend ctx)]
                   {:out (str (:window-watchdog-log ctx))
                    :err :out}))

(defn open-sessions-in-terminals! [ctx]
  (println (str "Opening separate " (terminal-call-out ctx "terminal_backend_label") " surfaces for each session..."))
  (when (terminal-call-ok? ctx "terminal_backend_tracks_windows")
    (spit (str (:window-ids-file ctx)) "")
    (spit (str (:window-state-file ctx)) ""))
  (loop [rows (:roles ctx)
         index 0
         previous-window-id ""]
    (when-let [row (first rows)]
      (let [window-id (open-role-terminal! ctx row previous-window-id index)]
        (if (terminal-call-ok? ctx "terminal_backend_tracks_windows")
          (recur (next rows) (inc index) window-id)
          (recur (next rows) (inc index) previous-window-id)))))
  (if (terminal-call-ok? ctx "terminal_backend_tracks_windows")
    (start-window-watchdog! ctx)
    (println (str yellow (terminal-call-out ctx "terminal_backend_label")
                  " surfaces are not trackable; window watchdog is disabled for this backend." reset))))

(defn attach-fallback! [ctx]
  (let [row (or (first (remove skip-terminal? (:roles ctx)))
                (first (:roles ctx)))]
    (println (str yellow "No terminal backend found; attaching current shell to '"
                  (:session row) "' instead." reset))
    (sh "tmux" "-S" (:tmux-socket ctx) "attach-session" "-t" (:session row))))

(defn clear-window-state! [ctx]
  (spit (str (:window-ids-file ctx)) "")
  (spit (str (:window-state-file ctx)) ""))

(defn open-terminal-surfaces! [ctx]
  (cond
    (every? skip-terminal? (:roles ctx))
    (do
      (clear-window-state! ctx)
      (println (str yellow "No visible Terminal surfaces; use the dashboard." reset)))

    (terminal-call-ok? ctx "terminal_backend_can_open_sessions")
    (open-sessions-in-terminals! ctx)

    :else
    (attach-fallback! ctx)))

(defn terminal-plan-line [row]
  (if (skip-terminal? row)
    (str "skip-terminal " (:role row))
    (str "open-terminal " (:role row))))

(defn launch-plan-lines [ctx]
  (cons "pack_web start" (map terminal-plan-line (:roles ctx))))

(defn wait-for-file [path timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (fs/exists? path) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 50) (recur))))))

(defn dashboard-url-file [ctx]
  (fs/path (:state-dir ctx) "dashboard-url"))

(defn pack-web-pid-file [ctx]
  (fs/path (:state-dir ctx) "pack_web.pid"))

(defn stop-existing-pack-web! [ctx]
  (let [file (pack-web-pid-file ctx)
        pid (when (fs/regular-file? file)
              (not-empty (str/trim (slurp (str file)))))]
    (when pid
      (process/sh {:continue true} "kill" "-TERM" pid))
    (fs/delete-if-exists file)
    (fs/delete-if-exists (dashboard-url-file ctx))))

(defn open-browser? []
  (not= "0" (System/getenv "SWARMFORGE_OPEN_BROWSER")))

(defn maybe-open-browser! [url]
  (when (and (open-browser?) (command-exists? "open"))
    (process/sh {:continue true} "open" url)))

(defn start-pack-web! [ctx]
  (stop-existing-pack-web! ctx)
  (let [script (str (fs/path (:script-dir ctx) "pack_web.sh"))
        log (fs/path (:state-dir ctx) "dashboard.log")]
    (process/process [script "--serve" (str (:working-dir ctx))]
                     {:out (str log) :err :out})
    (when-not (wait-for-file (dashboard-url-file ctx) 5000)
      (fail! (str red "Error:" reset " Dashboard did not start.")))
    (let [url (str/trim (slurp (str (dashboard-url-file ctx))))]
      (println (str green "Dashboard: " url reset))
      (maybe-open-browser! url)
      url)))
