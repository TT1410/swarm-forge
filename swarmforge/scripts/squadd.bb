#!/usr/bin/env bb

(ns squadd
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(def usage-text
  "Usage: squadd.sh [--once] [--no-notify] [project-root]")

(def poll-ms 1000)
(def status-poll-ms 5000)
(def handoff-wake-message
  "You have new handoff mail. If idle, run ready_for_next.sh.")
(def status-wake-message
  "Squad status needs attention. If idle, run squad_status.sh.")
(def script-dir (fs/parent *file*))
(load-file (str (fs/path script-dir "squad_config.bb")))
(def stopping? (atom false))
(def last-status-poll (atom 0))
(def last-status-notification (atom {:alerts #{} :notified-at nil}))
(def terminal-agent-states
  #{"complete" "review_complete" "handoff_ready" "handed_off" "handing_off"})

(defn exit! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn sh-continue [& args]
  (apply process/sh (concat [{:continue true}] args)))

(defn now []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn instant-now []
  (java.time.Instant/now))

(defn env-long [name default-value]
  (if-let [value (System/getenv name)]
    (if (re-matches #"[0-9]+" value)
      (Long/parseLong value)
      default-value)
    default-value))

(defn notify-cooldown-seconds []
  (env-long "SWARMFORGE_SQUAD_STATUS_NOTIFY_COOLDOWN_SECONDS" 300))

(defn parse-instant [value]
  (try
    (when-not (str/blank? value)
      (java.time.Instant/parse value))
    (catch Exception _ nil)))

(defn project-root []
  (let [configured (not-empty (System/getenv "SWARMFORGE_PROJECT_ROOT"))
        configured-roles (when configured (fs/path configured ".swarmforge" "roles.tsv"))
        cwd (fs/cwd)
        direct (fs/path cwd ".swarmforge" "roles.tsv")]
    (if (and configured (fs/exists? configured-roles))
      (fs/path configured)
      (if (fs/exists? direct)
      cwd
      (let [git-root (str/trim (:out (sh-continue "git" "rev-parse" "--show-toplevel")))]
        (if (and (not (str/blank? git-root))
                 (fs/exists? (fs/path git-root ".swarmforge" "roles.tsv")))
          (fs/path git-root)
          (exit! 1 "Cannot find SwarmForge project root")))))))

(defn read-lines [path]
  (when (fs/exists? path)
    (str/split-lines (slurp (str path)))))

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(declare agent-dirs log! append-compat-log!)

(defn load-roles [root]
  (into {}
        (for [line (read-lines (fs/path root ".swarmforge" "roles.tsv"))
              :when (not (str/blank? line))
              :let [[role worktree-name worktree-path session display agent receive-mode]
                    (str/split line #"\t" -1)]]
          [role {:role role
                 :worktree-name worktree-name
                 :worktree-path worktree-path
                 :session session
                 :display display
                 :agent agent
                 :receive-mode (or receive-mode "task")}])))

(defn write-atomic! [file content]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                  :prefix (str "." (fs/file-name file) ".")})]
    (spit (str tmp) content)
    (fs/move tmp file {:replace-existing true})))

(defn write-roles! [root roles]
  (let [roles-file (fs/path root ".swarmforge" "roles.tsv")]
    (write-atomic! roles-file
                   (apply str
                          (for [[_ role] (sort-by key roles)]
                            (str (str/join "\t" [(:role role)
                                                 (:worktree-name role)
                                                 (:worktree-path role)
                                                 (:session role)
                                                 (:display role)
                                                 (:agent role)
                                                 (:receive-mode role)])
                                 "\n"))))))

(defn metadata-role [dir]
  (let [metadata (fs/path dir "metadata")
        agent-id (read-value metadata "agent_id")
        template (read-value metadata "template")
        worktree (read-value metadata "worktree")
        session (read-value metadata "session")
        display (read-value metadata "display")
        backend (read-value metadata "backend")
        status (read-value (fs/path dir "status") "state")]
    (when (and agent-id worktree session display backend (not= "retired" status))
      [agent-id {:role agent-id
                 :worktree-name agent-id
                 :worktree-path worktree
                 :session session
                 :display display
                 :agent backend
                 :template template
                 :receive-mode "task"}])))

(defn terminal-state? [state]
  (contains? terminal-agent-states state))

(defn active-transient-role-count [root]
  (count
   (for [[role _] (load-roles root)
         :when (and (not= "squad-leader" role)
                    (not (terminal-state? (read-value (fs/path root ".squad" "agents" role "status") "state"))))]
     role)))

(defn active-role? [root role]
  (and (not= "squad-leader" role)
       (not (terminal-state? (read-value (fs/path root ".squad" "agents" role "status") "state")))))

(defn template-from-role [role]
  (str/replace role #"-\d{3}$" ""))

(defn role-template [root role]
  (or (read-value (fs/path root ".squad" "agents" role "metadata") "template")
      (template-from-role role)))

(defn active-template-count [root template]
  (count
   (for [[role _] (load-roles root)
         :when (and (active-role? root role)
                    (= template (role-template root role)))]
     role)))

(defn active-group-count [root templates]
  (count
   (for [[role _] (load-roles root)
         :when (and (active-role? root role)
                    (contains? templates (role-template root role)))]
     role)))

(defn spawn-capacity-blocker [root template]
  (cond
    (>= (active-transient-role-count root) (squad-max-transient-agents root))
    "capacity-full"

    (and (squad-template-limit root template)
         (>= (active-template-count root template) (squad-template-limit root template)))
    (str "template-capacity-full:" template)

    :else
    (some (fn [{:keys [group limit templates]}]
            (when (>= (active-group-count root templates) limit)
              (str "group-capacity-full:" group)))
          (squad-template-group-limits root template))))

(defn reconcile-roles! [root]
  (let [roles (load-roles root)
        recovered (into {}
                        (for [dir (agent-dirs root)
                              :let [entry (metadata-role dir)]
                              :when (and entry (nil? (get roles (first entry))))]
                          entry))]
    (when (seq recovered)
      (let [updated (merge roles recovered)]
        (write-roles! root updated)
        (doseq [agent (sort (keys recovered))]
          (log! root "role-recovered" agent)
          (append-compat-log! root "squad-statusd.log" "role-recovered" agent))
        updated))
    (if (seq recovered)
      (load-roles root)
      roles)))

(defn daemon-dir [root]
  (fs/path root ".swarmforge" "daemon"))

(defn log! [root & parts]
  (let [log-file (fs/path (daemon-dir root) "squadd.log")]
    (fs/create-dirs (fs/parent log-file))
    (spit (str log-file)
          (str (now) " " (str/join " " parts) "\n")
          :append true)))

(defn append-compat-log! [root log-name & parts]
  (let [log-file (fs/path (daemon-dir root) log-name)]
    (fs/create-dirs (fs/parent log-file))
    (spit (str log-file)
          (str (now) " " (str/join " " parts) "\n")
          :append true)))

(defn parse-message [path]
  (let [content (slurp (str path))
        [header body] (str/split content #"\n\n" 2)
        headers (into {}
                      (for [line (str/split-lines header)
                            :let [[k v] (str/split line #": " 2)]
                            :when (and k v)]
                        [k v]))]
    {:headers headers
     :body (or body "")
     :content content}))

(defn render-message [headers body]
  (let [preferred ["id" "from" "to" "recipient" "priority" "type" "role" "commit"
                   "message" "created_at" "enqueued_at" "dequeued_at" "completed_at"]
        remaining (->> (keys headers)
                       (remove (set preferred))
                       sort)
        ordered (concat preferred remaining)]
    (str (str/join "\n"
                   (for [k ordered
                         :let [v (get headers k)]
                         :when v]
                     (str k ": " v)))
         "\n\n"
         body)))

(defn move-with-collision [source target-dir]
  (fs/create-dirs target-dir)
  (let [base (fs/file-name source)
        target (fs/path target-dir base)]
    (if (fs/exists? target)
      (fs/move source
               (fs/path target-dir (str (now) "_" base))
               {:replace-existing false})
      (fs/move source target {:replace-existing false}))))

(defn tmux-notify! [socket session message]
  (let [send-text (sh "tmux" "-S" socket "send-keys" "-t" session "-l" message)
        _ (Thread/sleep 100)
        send-return (sh "tmux" "-S" socket "send-keys" "-t" session "C-m")]
    (and (zero? (:exit send-text))
         (zero? (:exit send-return)))))

(defn fail-handoff! [root path reason]
  (let [failed-dir (fs/path (fs/parent (fs/parent path)) "failed")]
    (log! root "handoff-failed" (str path) reason)
    (append-compat-log! root "handoffd.log" "failed" (str path) reason)
    (spit (str path ".error") (str reason "\n"))
    (move-with-collision path failed-dir)))

(defn deliver-handoff! [root roles socket sender-role path]
  (let [filename (fs/file-name path)
        message (parse-message path)
        headers (:headers message)
        recipients (some-> (get headers "to") (str/split #",") seq)]
    (if-not recipients
      (fail-handoff! root path "missing to header")
      (do
        (doseq [recipient recipients]
          (let [role-info (get roles recipient)]
            (when-not role-info
              (throw (ex-info (str "unknown recipient " recipient) {:recipient recipient})))
            (let [target (fs/path (:worktree-path role-info)
                                  ".swarmforge" "handoffs" "inbox" "new" filename)
                  delivered (assoc-in message [:headers "recipient"] recipient)
                  delivered (assoc-in delivered [:headers "enqueued_at"] (now))]
              (fs/create-dirs (fs/parent target))
              (when-not (fs/exists? target)
                (spit (str target) (render-message (:headers delivered) (:body delivered))))
              (tmux-notify! socket (:session role-info) handoff-wake-message))))
        (move-with-collision path
                             (fs/path (get-in roles [sender-role :worktree-path])
                                      ".swarmforge" "handoffs" "sent"))
        (log! root "handoff-delivered" (str path))
        (append-compat-log! root "handoffd.log" "delivered" (str path))))))

(defn outbox-files [role-info]
  (let [outbox (fs/path (:worktree-path role-info) ".swarmforge" "handoffs" "outbox")]
    (when (fs/exists? outbox)
      (->> (fs/list-dir outbox)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".handoff")))
           (sort-by #(fs/file-name %))))))

(defn poll-handoffs! [root]
  (let [roles (reconcile-roles! root)
        socket-file (fs/path root ".swarmforge" "tmux-socket")
        socket (when (fs/exists? socket-file) (str/trim (slurp (str socket-file))))]
    (when-not (str/blank? socket)
      (doseq [[role role-info] roles
              path (or (outbox-files role-info) [])
              :while (not @stopping?)]
        (try
          (deliver-handoff! root roles socket role path)
          (catch Exception e
            (log! root "handoff-error" (str path) (.getMessage e))
            (try
              (fail-handoff! root path (.getMessage e))
              (catch Exception nested
                (log! root "handoff-failed-to-archive" (str path) (.getMessage nested))))))))))

(defn agent-dirs [root]
  (let [agents-dir (fs/path root ".squad" "agents")]
    (if (fs/exists? agents-dir)
      (->> (fs/list-dir agents-dir)
           (filter fs/directory?)
           (sort-by fs/file-name)
           vec)
      [])))

(defn heartbeat-age-seconds [heartbeat now-instant]
  (when-let [updated (parse-instant (read-value heartbeat "updated_at"))]
    (.getSeconds (java.time.Duration/between updated now-instant))))

(defn tmux-session-exists? [socket session]
  (and (not (str/blank? socket))
       (or (zero? (:exit (sh-continue "tmux" "-S" socket "has-session" "-t" session)))
           (let [result (sh-continue "tmux" "-S" socket "list-sessions" "-F" "#S")]
             (and (zero? (:exit result))
                  (contains? (set (str/split-lines (:out result))) session))))))

(defn pane-dead? [socket session]
  (let [result (sh-continue "tmux" "-S" socket "list-panes" "-t" session "-F" "#{pane_dead}")]
    (and (zero? (:exit result))
         (some #{"1"} (str/split-lines (:out result))))))

(defn kill-tmux-session! [socket session]
  (when (and (not (str/blank? socket))
             (not (str/blank? session))
             (tmux-session-exists? socket session))
    (sh-continue "tmux" "-S" socket "kill-session" "-t" session)
    (loop [remaining 20]
      (cond
        (not (tmux-session-exists? socket session)) true
        (zero? remaining) false
        :else (do
                (Thread/sleep 100)
                (recur (dec remaining)))))))

(defn maybe-tmux-alert [socket skip-tmux? agent session]
  (cond
    skip-tmux? nil
    (str/blank? session) (str "agent " agent " has no tmux session metadata")
    (not (tmux-session-exists? socket session)) (str "agent " agent " tmux session missing: " session)
    (pane-dead? socket session) (str "agent " agent " tmux pane is dead: " session)
    :else nil))

(defn retire-role-row! [root agent]
  (let [roles (load-roles root)]
    (when (contains? roles agent)
      (write-roles! root (dissoc roles agent))
      (log! root "role-retired-reconciled" agent)
      (append-compat-log! root "squad-statusd.log" "role-retired-reconciled" agent))))

(defn reconcile-retired-agent! [root socket roles agent dir]
  (let [metadata (fs/path dir "metadata")
        session (or (read-value metadata "session")
                    (get-in roles [agent :session]))]
    (when (kill-tmux-session! socket session)
      (log! root "retired-session-killed" agent session)
      (append-compat-log! root "squad-statusd.log" "retired-session-killed" agent session))
    (retire-role-row! root agent)))

(defn reconcile-terminal-agent! [root socket roles agent dir state]
  (let [metadata (fs/path dir "metadata")
        session (or (read-value metadata "session")
                    (get-in roles [agent :session]))]
    (when (kill-tmux-session! socket session)
      (log! root "terminal-session-killed" agent state session)
      (append-compat-log! root "squad-statusd.log" "terminal-session-killed" agent state session))
    (retire-role-row! root agent)))

(defn alerts-for-agent [root roles socket skip-tmux? stale-seconds now-instant dir]
  (let [agent (fs/file-name dir)
        metadata (fs/path dir "metadata")
        status (fs/path dir "status")
        heartbeat (fs/path dir "heartbeat")
        state (read-value status "state")
        session (or (read-value metadata "session")
                    (get-in roles [agent :session]))
        age (heartbeat-age-seconds heartbeat now-instant)]
    (cond
      (= "retired" state) (do
                            (when-not skip-tmux?
                              (reconcile-retired-agent! root socket roles agent dir))
                            [])
      (terminal-state? state) (do
                                (when-not skip-tmux?
                                  (reconcile-terminal-agent! root socket roles agent dir state))
                                [])
      (nil? (get roles agent)) [(str "agent " agent " is not registered in roles.tsv")]
      (not (fs/exists? heartbeat)) [(str "agent " agent " has no heartbeat")]
      (nil? age) [(str "agent " agent " heartbeat timestamp is invalid")]
      (> age stale-seconds) [(str "agent " agent " heartbeat stale for " age " seconds")]
      :else (if-let [alert (maybe-tmux-alert socket skip-tmux? agent session)]
              [alert]
              []))))

(defn poll-status! [{:keys [root no-notify? skip-tmux?]}]
  (let [roles (reconcile-roles! root)
        socket-file (fs/path root ".swarmforge" "tmux-socket")
        socket (when (fs/exists? socket-file) (str/trim (slurp (str socket-file))))
        stale-seconds (env-long "SWARMFORGE_SQUAD_STALE_SECONDS" 300)
        alerts (mapcat #(alerts-for-agent root roles socket skip-tmux? stale-seconds (instant-now) %)
                       (agent-dirs root))
        alert-set (set alerts)
        now-instant (instant-now)]
    (doseq [alert alerts]
      (println "SQUAD_STATUS_ALERT:" alert)
      (log! root "status-alert" alert)
      (append-compat-log! root "squad-statusd.log" "alert" alert))
    (when (seq alerts)
      (if no-notify?
        (append-compat-log! root "squad-statusd.log" "notify-skipped" (count alerts))
        (let [{previous-alerts :alerts notified-at :notified-at} @last-status-notification
              cooldown (notify-cooldown-seconds)
              due? (or (nil? notified-at)
                       (not= alert-set previous-alerts)
                       (>= (.getSeconds (java.time.Duration/between notified-at now-instant))
                           cooldown))]
          (if-not due?
            (append-compat-log! root "squad-statusd.log" "notify-throttled" (count alerts))
            (if (tmux-notify! socket "swarmforge-squad-leader" status-wake-message)
              (do
                (reset! last-status-notification {:alerts alert-set :notified-at now-instant})
                (append-compat-log! root "squad-statusd.log" "notified" "squad-leader" (count alerts)))
              (append-compat-log! root "squad-statusd.log" "notify-failed" "squad-leader" (count alerts)))))))
    (when (empty? alerts)
      (reset! last-status-notification {:alerts #{} :notified-at nil})
      (println "SQUAD_STATUS_OK")
      (log! root "status-ok")
      (append-compat-log! root "squad-statusd.log" "ok"))
    alerts))

(defn parse-kv-file [file]
  (into {}
        (for [line (or (read-lines file) [])
              :let [[k v] (str/split line #": " 2)]
              :when (and k v)]
          [k v])))

(defn spawn-request-dirs [root]
  {:new (fs/path root ".squad" "spawn-requests" "new")
   :in-process (fs/path root ".squad" "spawn-requests" "in_process")
   :completed (fs/path root ".squad" "spawn-requests" "completed")
   :failed (fs/path root ".squad" "spawn-requests" "failed")})

(defn spawn-request-files [root]
  (let [{:keys [new]} (spawn-request-dirs root)]
    (when (fs/exists? new)
      (->> (fs/list-dir new)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".request")))
           (sort-by #(fs/file-name %))))))

(defn process-spawn-request! [root request]
  (let [{:keys [in-process completed failed]} (spawn-request-dirs root)
        base (fs/file-name request)
        active (fs/path in-process base)]
    (let [{:strs [template task_id assignment]} (parse-kv-file request)
          blocker (when-not (str/blank? template)
                    (spawn-capacity-blocker root template))]
      (if blocker
        (log! root "spawn-request-deferred" (str request) blocker)
        (do
          (fs/create-dirs in-process)
          (fs/move request active {:replace-existing false})
          (if (or (str/blank? template) (str/blank? task_id) (str/blank? assignment))
            (do
              (fs/create-dirs failed)
              (spit (str active ".error") "spawn request missing template, task_id, or assignment\n")
              (move-with-collision active failed)
              (log! root "spawn-request-failed" (str active) "invalid request"))
            (let [env (cond-> {"PATH" (System/getenv "PATH")
                               "GIT_CONFIG_NOSYSTEM" "1"}
                        (= "1" (System/getenv "SWARMFORGE_SQUAD_NO_LAUNCH"))
                        (assoc "SWARMFORGE_SQUAD_NO_LAUNCH" "1")
                        (not (str/blank? (System/getenv "SWARMFORGE_SQUAD_AGENT")))
                        (assoc "SWARMFORGE_SQUAD_AGENT" (System/getenv "SWARMFORGE_SQUAD_AGENT"))
                        (not (str/blank? (System/getenv "SWARMFORGE_SQUAD_AGENT_COMMAND")))
                        (assoc "SWARMFORGE_SQUAD_AGENT_COMMAND" (System/getenv "SWARMFORGE_SQUAD_AGENT_COMMAND")))
                  result (process/sh {:continue true
                                      :dir (str root)
                                      :env env}
                                     (str (fs/path script-dir "squad_spawn.sh"))
                                     template
                                     task_id
                                     assignment)]
              (if (zero? (:exit result))
                (do
                  (fs/create-dirs completed)
                  (spit (str (fs/path completed (str base ".out"))) (:out result))
                  (spit (str (fs/path completed (str base ".err"))) (:err result))
                  (move-with-collision active completed)
                  (log! root "spawn-request-completed" (str active)))
                (do
                  (fs/create-dirs failed)
                  (spit (str (fs/path failed (str base ".out"))) (:out result))
                  (spit (str (fs/path failed (str base ".err"))) (:err result))
                  (spit (str (fs/path failed (str base ".error"))) (str "exit " (:exit result) "\n"))
                  (move-with-collision active failed)
                  (log! root "spawn-request-failed" (str active) (str "exit " (:exit result))))))))))))

(defn poll-spawn-requests! [root]
  (doseq [request (or (spawn-request-files root) [])
          :while (not @stopping?)]
    (try
      (process-spawn-request! root request)
      (catch Exception e
        (log! root "spawn-request-error" (str request) (.getMessage e))))))

(defn pid-file [root]
  (fs/path (daemon-dir root) "squadd.pid"))

(defn stop-file [root]
  (fs/path (daemon-dir root) "squadd.stop"))

(defn should-stop? [root]
  (or @stopping? (fs/exists? (stop-file root))))

(defn poll-once! [opts]
  (let [root (:root opts)]
    (poll-spawn-requests! root)
    (poll-handoffs! root)
    (poll-status! opts)))

(defn due-status? []
  (let [now-ms (System/currentTimeMillis)]
    (when (>= (- now-ms @last-status-poll) status-poll-ms)
      (reset! last-status-poll now-ms)
      true)))

(defn poll-loop-once! [opts]
  (let [root (:root opts)]
    (poll-spawn-requests! root)
    (poll-handoffs! root)
    (when (due-status?)
      (poll-status! opts))))

(defn sleep-poll! [root ms]
  (loop [remaining ms]
    (when (and (pos? remaining) (not (should-stop? root)))
      (let [step (min remaining 100)]
        (Thread/sleep step)
        (recur (- remaining step))))))

(defn parse-args [args]
  (loop [remaining args
         opts {:once? false :no-notify? false :root nil}]
    (if-let [arg (first remaining)]
      (case arg
        "--once" (recur (rest remaining) (assoc opts :once? true))
        "--no-notify" (recur (rest remaining) (assoc opts :no-notify? true))
        (if (:root opts)
          (exit! 1 usage-text)
          (do
            (when (str/starts-with? arg "--")
              (exit! 1 usage-text))
            (recur (rest remaining) (assoc opts :root arg)))))
      (update opts :root #(or % (project-root))))))

(defn shutdown! [root]
  (reset! stopping? true)
  (try
    (fs/delete-if-exists (pid-file root))
    (log! root "stopped")
    (catch Exception _ nil)))

(defn -main [& args]
  (let [{:keys [once? no-notify? root]} (parse-args args)
        root (fs/absolutize root)
        skip-tmux? (= "1" (System/getenv "SWARMFORGE_SQUAD_STATUSD_SKIP_TMUX"))
        opts {:root root :no-notify? no-notify? :skip-tmux? skip-tmux?}]
    (if once?
      (poll-once! opts)
      (do
        (fs/create-dirs (daemon-dir root))
        (fs/delete-if-exists (stop-file root))
        (spit (str (pid-file root)) (str (.pid (java.lang.ProcessHandle/current)) "\n"))
        (.addShutdownHook (Runtime/getRuntime) (Thread. #(shutdown! root)))
        (log! root "started")
        (try
          (while (not (should-stop? root))
            (poll-loop-once! opts)
            (sleep-poll! root poll-ms))
          (finally
            (shutdown! root)))))))

(apply -main *command-line-args*)
