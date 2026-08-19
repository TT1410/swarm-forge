#!/usr/bin/env bb

(ns squadd
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [squad-config :as cfg]
            [squad-records :as rec]
            [squadd.web :as web]))

(def usage-text
  "Usage: squadd.sh [--once] [--no-notify] [project-root]")

(def poll-ms 1000)
(def poll-ms-max 15000)
(def status-poll-ms 5000)
;; Adaptive poll delay when spawn queue is capacity-full (bug #4).
(def current-poll-ms (atom poll-ms))
;; Log each deferred spawn request at most once until it leaves `new/` (bug #4).
(def deferred-spawn-log-keys (atom #{}))
(def handoff-wake-message
  "You have new handoff mail. If idle, run squad_next.sh --residual-only. Residual accept-merge is yours; the daemon does not merge.")
(def status-wake-message
  "Squad status needs attention. If idle, run squad_next.sh --residual-only. Residual accept-merge is yours.")
(def sl-watchdog-message
  "Run squad_next.sh --residual-only. Follow residual COMMAND, including accept-merge. Do not run --apply-mechanical (daemon owns mechanical apply).")
(def sl-judgment-actions
  #{"request_user_approval"
    "answer_dashboard_request"
    "handle_durable_blocker"
    "recover_agent"
    "accept_merge"})
(def script-dir (fs/parent *file*))
(def stopping? (atom false))
(def last-status-poll (atom 0))
(def last-status-notification (atom {:alerts #{} :notified-at nil}))
(def last-status-log-state (atom nil))
(def active-agent-states
  #{"starting" "running"})

(defn exit! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn sh-continue [& args]
  (apply process/sh (concat [{:continue true}] args)))

(defn git-continue [root & args]
  (apply sh-continue (concat ["git" "-C" (str root)] args)))

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

(defn alert-key [alert]
  (-> alert
      (str/replace #" heartbeat stale for [0-9]+ seconds; tmux pane alive but unchanged$"
                   " heartbeat stale; tmux pane alive but unchanged")
      (str/replace #" tmux session missing for [0-9]+ seconds:"
                   " tmux session missing:")
      (str/replace #" heartbeat stale for [0-9]+ seconds$"
                   " heartbeat stale")))

(defn parse-instant [value]
  (try
    (when-not (str/blank? value)
      (java.time.Instant/parse value))
    (catch Exception _ nil)))

(defn project-root []
  (or (cfg/project-root)
      (exit! 1 "Cannot find SwarmForge project root")))

(defn read-lines [path]
  (when (fs/exists? path)
    (str/split-lines (slurp (str path)))))

(defn slurp-if-exists [path]
  (if (fs/regular-file? path)
    (slurp (str path))
    ""))

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(defn parse-kv-file
  "Flat key:value status/metadata via squad-records."
  [file]
  (rec/read-kv-file file))

(declare agent-dirs log! tmux-session-exists? idle-prompt-tail?)

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

(defn write-atomic!
  "Atomic write via squad-records."
  [file content]
  (rec/write-atomic! file content))

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

(defn active-state? [state]
  (contains? active-agent-states state))

(defn skip-tmux-env? []
  (= "1" (System/getenv "SWARMFORGE_SQUADD_SKIP_TMUX")))

(defn tmux-socket [root]
  (let [socket-file (fs/path root ".swarmforge" "tmux-socket")]
    (when (fs/regular-file? socket-file)
      (str/trim (slurp (str socket-file))))))

(defn visible-handoff-agents [root]
  (->> ["new" "in_process" "completed"]
       (mapcat (fn [state]
                 (let [dir (fs/path root ".swarmforge" "handoffs" "inbox" state)]
                   (when (fs/exists? dir)
                     (->> (fs/list-dir dir)
                          (filter #(and (fs/regular-file? %)
                                        (str/ends-with? (fs/file-name %) ".handoff")))
                          (map #(read-value % "from"))
                          (remove str/blank?))))))
       set))

(declare role-template)

(defn template-active-role? [root socket role role-data]
  "True when the role is a live transient that should count toward template caps."
  (let [state (read-value (fs/path root ".squad" "agents" role "status") "state")
        session (:session role-data)]
    (and (not= "squad-leader" role)
         (not (contains? #{"retired" "failed"} state))
         (if (skip-tmux-env?)
           (active-state? state)
           (tmux-session-exists? socket session))
         (not (and (= "handoff_sent" state)
                   (contains? (visible-handoff-agents root) role))))))

(defn capacity-counted-role? [root socket role role-data]
  "True when the role consumes max_transient_agents."
  (template-active-role? root socket role role-data))

(defn active-transient-role-count [root]
  (count
   (let [socket (tmux-socket root)]
     (for [[role role-data] (load-roles root)
           :when (capacity-counted-role? root socket role role-data)]
       role))))

(defn active-role? [root role]
  (let [roles (load-roles root)
        socket (tmux-socket root)]
    (template-active-role? root socket role (get roles role))))

(defn template-from-role [role]
  (str/replace role #"-\d{3}$" ""))

(defn role-template [root role]
  (or (read-value (fs/path root ".squad" "agents" role "metadata") "template")
      (template-from-role role)))

(defn role-task-id [root role]
  (or (read-value (fs/path root ".squad" "agents" role "metadata") "task_id")
      (read-value (fs/path root ".squad" "agents" role "metadata") "task-id")))

(defn counts-toward-template-cap?
  "Whether an active role consumes max_active_template for this template."
  [root role template]
  (and (active-role? root role)
       (= template (role-template root role))))

(defn active-template-count [root template]
  (count
   (for [[role _] (load-roles root)
         :when (counts-toward-template-cap? root role template)]
     role)))

(defn active-group-count [root templates]
  (count
   (for [[role _] (load-roles root)
         :when (and (active-role? root role)
                    (contains? templates (role-template root role)))]
     role)))

(defn total-capacity-full? [root]
  (>= (active-transient-role-count root) (cfg/squad-max-transient-agents root)))

(defn template-capacity-full? [root template]
  (when-let [limit (cfg/squad-template-limit root template)]
    (>= (active-template-count root template) limit)))

(defn group-capacity-blocker [root {:keys [group limit templates]}]
  (when (>= (active-group-count root templates) limit)
    (str "group-capacity-full:" group)))

(defn spawn-capacity-blocker [root template]
  (or (when (total-capacity-full? root) "capacity-full")
      (when (template-capacity-full? root template)
        (str "template-capacity-full:" template))
      (some #(group-capacity-blocker root %)
            (cfg/squad-template-group-limits root template))))

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
          (log! root "role-recovered" agent))
        updated))
    (if (seq recovered)
      (load-roles root)
      roles)))

(defn daemon-dir [root]
  (fs/path root ".swarmforge" "daemon"))

(def log-lock (Object.))

(defn try-mkdir-lock! [lock-dir]
  (try
    (fs/create-dir lock-dir)
    true
    (catch java.nio.file.FileAlreadyExistsException _
      false)
    (catch Exception _
      false)))

(defn acquire-log-dir-lock! [lock-dir deadline-ms]
  (loop []
    (if (try-mkdir-lock! lock-dir)
      true
      (if (> (System/currentTimeMillis) deadline-ms)
        false
        (do
          (Thread/sleep 5)
          (recur))))))

(defn with-log-dir-lock! [lock-dir f]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (if (acquire-log-dir-lock! lock-dir deadline)
      (try
        (f)
        (finally
          (try (fs/delete-tree lock-dir) (catch Exception _))))
      ;; Best-effort: never drop a log line if lock acquisition times out.
      (f))))

(defn append-locked-log-line! [log-file line]
  "Append one complete line under a process-local lock and a directory lock for
  cross-thread / multi-writer serialization (babashka-safe)."
  (fs/create-dirs (fs/parent log-file))
  (let [path (str log-file)
        lock-dir (fs/path (str path ".dlock"))
        payload (if (str/ends-with? line "\n") line (str line "\n"))]
    (locking log-lock
      (with-log-dir-lock! lock-dir
        (fn []
          (spit path payload :append true))))))

(defn log! [root & parts]
  (append-locked-log-line!
   (fs/path (daemon-dir root) "squadd.log")
   (str (now) " " (str/join " " parts))))

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
        send-return (sh "tmux" "-S" socket "send-keys" "-t" session "C-m")
        _ (Thread/sleep 100)
        send-second-return (sh "tmux" "-S" socket "send-keys" "-t" session "C-m")]
    (and (zero? (:exit send-text))
         (zero? (:exit send-return))
         (zero? (:exit send-second-return)))))

(defn fail-handoff! [root path reason]
  (let [failed-dir (fs/path (fs/parent (fs/parent path)) "failed")]
    (log! root "handoff-failed" (str path) reason)
    (spit (str path ".error") (str reason "\n"))
    (move-with-collision path failed-dir)))

(defn handoff-recipients [message]
  (some-> (get-in message [:headers "to"]) (str/split #",") seq))

(defn ensure-recipient-role! [roles recipient]
  (or (get roles recipient)
      (throw (ex-info (str "unknown recipient " recipient) {:recipient recipient}))))

(defn recipient-target [role-info filename]
  (fs/path (:worktree-path role-info)
           ".swarmforge" "handoffs" "inbox" "new" filename))

(defn delivered-handoff [message recipient]
  (-> message
      (assoc-in [:headers "recipient"] recipient)
      (assoc-in [:headers "enqueued_at"] (now))))

(defn write-recipient-handoff! [role-info filename message]
  (let [target (recipient-target role-info filename)]
    (fs/create-dirs (fs/parent target))
    (when-not (fs/exists? target)
      (spit (str target) (render-message (:headers message) (:body message))))))

(defn deliver-recipient-handoff! [roles socket filename message recipient]
  (let [role-info (ensure-recipient-role! roles recipient)]
    (write-recipient-handoff! role-info filename (delivered-handoff message recipient))
    (tmux-notify! socket (:session role-info) handoff-wake-message)))

(defn sender-sent-dir [roles sender-role]
  (fs/path (get-in roles [sender-role :worktree-path])
           ".swarmforge" "handoffs" "sent"))

(defn deliver-handoff! [root roles socket sender-role path]
  (let [filename (fs/file-name path)
        message (parse-message path)
        recipients (handoff-recipients message)]
    (if-not recipients
      (fail-handoff! root path "missing to header")
      (do
        (doseq [recipient recipients]
          (deliver-recipient-handoff! roles socket filename message recipient))
        (move-with-collision path (sender-sent-dir roles sender-role))
        (log! root "handoff-delivered" (str path))))))

(defn outbox-files [role-info]
  (let [outbox (fs/path (:worktree-path role-info) ".swarmforge" "handoffs" "outbox")]
    (when (fs/exists? outbox)
      (->> (fs/list-dir outbox)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".handoff")))
           (sort-by #(fs/file-name %))))))

(defn tmux-socket [root]
  (let [socket-file (fs/path root ".swarmforge" "tmux-socket")]
    (when (fs/exists? socket-file)
      (str/trim (slurp (str socket-file))))))

(defn archive-failed-handoff! [root path error]
  (try
    (fail-handoff! root path (.getMessage error))
    (catch Exception nested
      (log! root "handoff-failed-to-archive" (str path) (.getMessage nested)))))

(defn deliver-outbox-file! [root roles socket role path]
  (try
    (deliver-handoff! root roles socket role path)
    (catch Exception e
      (log! root "handoff-error" (str path) (.getMessage e))
      (archive-failed-handoff! root path e))))

(defn poll-role-outbox! [root roles socket role role-info]
  (doseq [path (or (outbox-files role-info) [])
          :while (not @stopping?)]
    (deliver-outbox-file! root roles socket role path)))

(defn poll-handoffs! [root]
  (let [roles (reconcile-roles! root)
        socket (tmux-socket root)]
    (when-not (str/blank? socket)
      (doseq [[role role-info] roles
              :while (not @stopping?)]
        (poll-role-outbox! root roles socket role role-info)))))

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

(defn sha256 [value]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes (or value "") "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn capture-pane-tail [socket session]
  (let [result (sh-continue "tmux" "-S" socket "capture-pane" "-p" "-t" session "-S" "-10")]
    (when (zero? (:exit result))
      (:out result))))

(defn write-liveness! [root agent state changed? tail]
  (let [file (fs/path root ".squad" "agents" agent "liveness")]
    (fs/create-dirs (fs/parent file))
    (spit (str file)
          (str "state: " state "\n"
               "observed_at: " (now) "\n"
               "pane_changed: " (if changed? "true" "false") "\n"
               "pane_idle_prompt: " (if (idle-prompt-tail? tail) "true" "false") "\n"
               "pane_hash: " (sha256 tail) "\n"
               "last_10_lines:\n"
               (or tail "")))))

(defn missing-session-file [root agent]
  (fs/path root ".squad" "agents" agent "missing-session"))

(defn clear-missing-session! [root agent]
  (fs/delete-if-exists (missing-session-file root agent)))

(defn missing-session-grace-seconds []
  (env-long "SWARMFORGE_SQUAD_MISSING_SESSION_GRACE_SECONDS" 30))

(defn missing-session-context [root agent session]
  (let [file (missing-session-file root agent)
        first-seen (read-value file "first_seen")
        recorded-session (read-value file "session")
        same-session? (= session recorded-session)
        baseline (if same-session? first-seen (now))
        first-instant (when same-session? (parse-instant first-seen))]
    {:file file
     :agent agent
     :session session
     :baseline baseline
     :first-instant first-instant}))

(defn missing-session-age [first-instant]
  (when first-instant
    (.getSeconds (java.time.Duration/between first-instant (instant-now)))))

(defn write-missing-session! [{:keys [file session baseline]}]
  (fs/create-dirs (fs/parent file))
  (spit (str file)
        (str "session: " session "\n"
             "first_seen: " baseline "\n"
             "observed_at: " (now) "\n")))

(defn missing-session-message [agent session age grace]
  (when (and age (>= age grace))
    (str "agent " agent " tmux session missing for " age " seconds: " session)))

(defn missing-session-alert [root agent session]
  (let [{:keys [first-instant] :as context} (missing-session-context root agent session)
        age (missing-session-age first-instant)
        grace (missing-session-grace-seconds)]
    (write-missing-session! context)
    (missing-session-message agent session age grace)))

(defn observe-pane-liveness! [root socket agent session]
  (let [liveness (fs/path root ".squad" "agents" agent "liveness")
        previous-hash (read-value liveness "pane_hash")
        tail (or (capture-pane-tail socket session) "")
        current-hash (sha256 tail)
        changed? (not= previous-hash current-hash)
        state (if changed? "running_pane_active" "running_pane_idle")]
    (clear-missing-session! root agent)
    (write-liveness! root agent state changed? tail)
    changed?))

(defn live-pane-alert [root socket agent session age]
  (when-not (observe-pane-liveness! root socket agent session)
    (str "agent " agent " heartbeat stale for " age " seconds; tmux pane alive but unchanged")))

(defn pane-liveness-kind [socket session]
  (cond
    (str/blank? session) :missing-session-metadata
    (not (tmux-session-exists? socket session)) :missing-session
    (pane-dead? socket session) :dead-pane
    :else :live-pane))

(defn pane-liveness-message [root socket agent session age kind]
  (case kind
    :missing-session-metadata (str "agent " agent " has no tmux session metadata")
    :missing-session (missing-session-alert root agent session)
    :dead-pane (str "agent " agent " tmux pane is dead: " session)
    :live-pane (live-pane-alert root socket agent session age)))

(defn pane-liveness-alert [root socket agent session age]
  (pane-liveness-message root socket agent session age
                         (pane-liveness-kind socket session)))

(defn killable-session? [socket session]
  (and (not (str/blank? socket))
       (not (str/blank? session))
       (tmux-session-exists? socket session)))

(defn wait-session-gone-step [socket session remaining]
  (cond
    (not (tmux-session-exists? socket session)) :gone
    (zero? remaining) :timed-out
    :else :retry))

(defn wait-session-gone [socket session]
  (loop [remaining 20]
    (case (wait-session-gone-step socket session remaining)
      :gone true
      :timed-out false
      :retry (do
               (Thread/sleep 100)
               (recur (dec remaining))))))

(defn kill-tmux-session! [socket session]
  (when (killable-session? socket session)
    (sh-continue "tmux" "-S" socket "kill-session" "-t" session)
    (wait-session-gone socket session)))

(defn maybe-tmux-alert [root socket skip-tmux? agent session]
  (cond
    skip-tmux? nil
    (str/blank? session) (str "agent " agent " has no tmux session metadata")
    (not (tmux-session-exists? socket session)) nil
    (pane-dead? socket session) (str "agent " agent " tmux pane is dead: " session)
    :else (do
            (clear-missing-session! root agent)
            nil)))

(defn retire-role-row! [root agent]
  (let [roles (load-roles root)]
    (when (contains? roles agent)
      (write-roles! root (dissoc roles agent))
      (log! root "role-retired-reconciled" agent))))

(defn transient-branch [agent]
  (str "swarmforge-" agent))

(defn managed-worktree? [root agent worktree]
  (let [managed-root (fs/absolutize (fs/path root ".worktrees"))
        expected (fs/absolutize (fs/path managed-root agent))
        actual (fs/absolutize worktree)]
    (= (str expected) (str actual))))

(defn cleanup-worktree! [root agent worktree]
  (if (or (str/blank? (str worktree))
          (not (managed-worktree? root agent worktree)))
    (log! root "git-cleanup-skipped" agent "unmanaged-worktree" (str worktree))
    (let [remove-result (git-continue root "worktree" "remove" "--force" (str worktree))]
      (when-not (zero? (:exit remove-result))
        (when (fs/exists? worktree)
          (fs/delete-tree worktree))
        (git-continue root "worktree" "prune"))
      (if (fs/exists? worktree)
        (log! root "git-worktree-remove-failed" agent (str worktree))
        (log! root "git-worktree-removed" agent (str worktree))))))

(defn cleanup-branch! [root agent]
  (let [branch (transient-branch agent)
        branch-result (git-continue root "branch" "-D" branch)]
    (if (zero? (:exit branch-result))
      (log! root "git-branch-deleted" agent branch)
      (log! root "git-branch-absent" agent branch))))

(defn cleanup-transient-git! [root agent worktree]
  (cleanup-worktree! root agent worktree)
  (cleanup-branch! root agent))

(defn retired-cleanup-marker [dir]
  (fs/path dir "retired-cleanup"))

(defn retired-cleanup-context [roles agent dir]
  (let [metadata (fs/path dir "metadata")]
    {:session (or (read-value metadata "session")
                  (get-in roles [agent :session]))
     :worktree (or (read-value metadata "worktree")
                   (get-in roles [agent :worktree-path]))}))

(defn kill-retired-session! [root socket agent session]
  (when (kill-tmux-session! socket session)
    (log! root "retired-session-killed" agent session)))

(defn mark-retired-cleanup! [marker]
  (write-atomic! marker (str "cleaned_at: " (now) "\n")))

(defn cleanup-retired-agent! [root socket agent worktree session marker]
  (kill-retired-session! root socket agent session)
  (cleanup-transient-git! root agent worktree)
  (retire-role-row! root agent)
  (mark-retired-cleanup! marker))

(defn reconcile-retired-agent! [root socket roles agent dir]
  (let [cleanup-marker (retired-cleanup-marker dir)]
    (when-not (fs/regular-file? cleanup-marker)
      (let [{:keys [worktree session]} (retired-cleanup-context roles agent dir)]
        (cleanup-retired-agent! root socket agent worktree session cleanup-marker)))))

(defn agent-alert-context [root roles now-instant dir]
  (let [agent (fs/file-name dir)
        metadata (fs/path dir "metadata")
        status (fs/path dir "status")
        heartbeat (fs/path dir "heartbeat")]
    {:agent agent
     :metadata metadata
     :status status
     :heartbeat heartbeat
     :state (read-value status "state")
     :session (or (read-value metadata "session")
                  (get-in roles [agent :session]))
     :age (heartbeat-age-seconds heartbeat now-instant)}))

(defn stale-heartbeat-alert [root socket skip-tmux? agent session age]
  (if skip-tmux?
    (str "agent " agent " heartbeat stale for " age " seconds")
    (pane-liveness-alert root socket agent session age)))

(defn current-heartbeat-alert [root socket skip-tmux? agent session]
  (maybe-tmux-alert root socket skip-tmux? agent session))

(def agent-alert-rules
  [[:retired (fn [_ {:keys [state]}] (= "retired" state))]
   [:inactive (fn [_ {:keys [state]}] (and state (not (active-state? state))))]
   [:unregistered (fn [roles {:keys [agent]}] (nil? (get roles agent)))]
   [:missing-heartbeat (fn [_ {:keys [heartbeat]}] (not (fs/exists? heartbeat)))]
   [:invalid-heartbeat (fn [_ {:keys [age]}] (nil? age))]
   [:stale-heartbeat (fn [_ {:keys [age stale-seconds]}] (> age stale-seconds))]])

(defn alert-context-with-threshold [stale-seconds context]
  (assoc context :stale-seconds stale-seconds))

(defn agent-alert-kind [roles stale-seconds context]
  (let [context (alert-context-with-threshold stale-seconds context)]
    (or (some (fn [[kind predicate]]
                (when (predicate roles context)
                  kind))
              agent-alert-rules)
        :tmux-health)))

(defn agent-task-id [root agent]
  (read-value (fs/path root ".squad" "agents" agent "metadata") "task_id"))

(def resolved-handoff-assignment-states
  #{"merged" "rejected" "blocked" "replacement_created" "superseded"
    "review_accepted" "review_changes_requested" "cancelled" "abandoned"})

(defn assignment-handoff-terminal? [root assignment-id]
  (if-not (and assignment-id
               (not= "unknown" assignment-id)
               (fs/directory? (fs/path root ".squad" "assignments" assignment-id)))
    true
    (let [state (or (read-value (fs/path root ".squad" "assignments" assignment-id "status") "state")
                    "unknown")]
      (contains? resolved-handoff-assignment-states state))))

(defn retired-agent-alert! [root roles socket skip-tmux? dir {:keys [agent]}]
  (when (contains? roles agent)
    (let [task-id (agent-task-id root agent)
          terminal? (assignment-handoff-terminal? root task-id)]
      (if terminal?
        (do
          (log! root "agent-retired-awaiting-workflow" agent)
          (str "agent " agent " reported retired; run squad_retire.sh only after workflow resolves its handoff"))
        (do
          (log! root "agent-retired-before-handoff-terminal" agent (or task-id "unknown"))
          (str "agent " agent " status is retired but assignment "
               (or task-id "unknown")
               " handoff is not terminal; do not free the role until workflow resolves"))))))

(defn unregistered-agent-alert [{:keys [agent]}]
  (str "agent " agent " is not registered in roles.tsv"))

(defn missing-heartbeat-alert [{:keys [agent]}]
  (str "agent " agent " has no heartbeat"))

(defn invalid-heartbeat-alert [{:keys [agent]}]
  (str "agent " agent " heartbeat timestamp is invalid"))

(def alert-handlers
  {:retired (fn [root roles socket skip-tmux? dir context]
              (retired-agent-alert! root roles socket skip-tmux? dir context))
   :inactive (fn [_ _ _ _ _ _] nil)
   :unregistered (fn [_ _ _ _ _ context] (unregistered-agent-alert context))
   :missing-heartbeat (fn [_ _ _ _ _ context] (missing-heartbeat-alert context))
   :invalid-heartbeat (fn [_ _ _ _ _ context] (invalid-heartbeat-alert context))
   :stale-heartbeat (fn [root _ socket skip-tmux? _ context]
                      (stale-heartbeat-alert root socket skip-tmux? (:agent context) (:session context) (:age context)))
   :tmux-health (fn [root _ socket skip-tmux? _ context]
                  (current-heartbeat-alert root socket skip-tmux? (:agent context) (:session context)))})

(defn alert-for-kind [root roles socket skip-tmux? dir context kind]
  ((alert-handlers kind) root roles socket skip-tmux? dir context))

(defn alert-for-context [root roles socket skip-tmux? stale-seconds dir
                         context]
  (alert-for-kind root roles socket skip-tmux? dir context
                  (agent-alert-kind roles stale-seconds context)))

(defn alerts-for-agent [root roles socket skip-tmux? stale-seconds now-instant dir]
  (if-let [alert (alert-for-context root roles socket skip-tmux? stale-seconds dir
                                    (agent-alert-context root roles now-instant dir))]
    [alert]
    []))

(defn log-status-alerts! [root alerts alert-key-set]
  (when (seq alerts)
    (let [state-key [:alerts alert-key-set]]
      (when (not= state-key @last-status-log-state)
        (reset! last-status-log-state state-key)
        (doseq [alert alerts]
          (log! root "status-alert" alert))))))

(defn status-notify-due? [previous-alerts notified-at alert-key-set now-instant cooldown]
  (or (nil? notified-at)
      (not= alert-key-set previous-alerts)
      (>= (.getSeconds (java.time.Duration/between notified-at now-instant))
	          cooldown)))

(defn status-notify-success! [root alert-key-set now-instant alert-count]
  (reset! last-status-notification {:alerts alert-key-set :notified-at now-instant})
  (log! root "status-notified" "squad-leader" (str alert-count)))

(defn send-status-notification! [root socket alert-key-set now-instant alert-count]
  (if (tmux-notify! socket "swarmforge-squad-leader" status-wake-message)
    (status-notify-success! root alert-key-set now-instant alert-count)
    (log! root "status-notify-failed" "squad-leader" (str alert-count))))

(defn notify-status-active! [root socket alerts alert-key-set now-instant]
  (let [{previous-alerts :alerts notified-at :notified-at} @last-status-notification
        cooldown (notify-cooldown-seconds)
        alert-count (count alerts)]
    (if-not (status-notify-due? previous-alerts notified-at alert-key-set now-instant cooldown)
      (log! root "status-notify-throttled" (str alert-count))
      (send-status-notification! root socket alert-key-set now-instant alert-count))))

(defn notify-status-alerts! [root socket no-notify? alerts alert-key-set now-instant]
  (when (seq alerts)
    (if no-notify?
      (log! root "status-notify-skipped" (str (count alerts)))
      (notify-status-active! root socket alerts alert-key-set now-instant))))

(defn log-status-ok! [root alerts]
  (when (empty? alerts)
    (reset! last-status-notification {:alerts #{} :notified-at nil})
    (println "SQUAD_STATUS_OK")
    (when (not= :ok @last-status-log-state)
      (reset! last-status-log-state :ok)
      (log! root "status-ok"))))

(defn registered-tmux-sessions
  "Sessions still claimed by roles.tsv or sessions.tsv."
  [root]
  (let [from-roles (for [line (when (fs/regular-file? (fs/path root ".swarmforge" "roles.tsv"))
                                (str/split-lines (slurp (str (fs/path root ".swarmforge" "roles.tsv")))))
                         :when (not (str/blank? line))
                         :let [cols (str/split line #"\t" -1)]
                         :when (>= (count cols) 4)
                         :let [session (nth cols 3)]
                         :when (not (str/blank? session))]
                     session)
        from-sessions (for [line (when (fs/regular-file? (fs/path root ".swarmforge" "sessions.tsv"))
                                   (str/split-lines (slurp (str (fs/path root ".swarmforge" "sessions.tsv")))))
                            :when (not (str/blank? line))
                            :let [cols (str/split line #"\t" -1)]
                            :when (>= (count cols) 3)
                            :let [session (nth cols 2)]
                            :when (not (str/blank? session))]
                        session)]
    (set (concat from-roles from-sessions))))

(defn live-tmux-sessions [socket]
  (when-not (str/blank? socket)
    (let [result (sh-continue "tmux" "-S" socket "list-sessions" "-F" "#{session_name}")]
      (when (zero? (:exit result))
        (->> (str/split-lines (:out result))
             (remove str/blank?)
             set)))))

(defn orphan-swarmforge-sessions [root socket]
  "Swarmforge-* sessions not registered in roles/sessions (retire leak)."
  (let [registered (registered-tmux-sessions root)
        live (or (live-tmux-sessions socket) #{})]
    (->> live
         (filter #(str/starts-with? % "swarmforge-"))
         (remove #(contains? registered %))
         ;; Never kill persistent operator surfaces if still registered under alt name
         (remove #(#{"swarmforge-squad-leader" "swarmforge-troubleshooter"} %))
         sort
         vec)))

(defn kill-orphan-tmux-session! [root socket session]
  (sh-continue "tmux" "-S" socket "kill-session" "-t" (str "=" session))
  (sh-continue "tmux" "-S" socket "kill-session" "-t" session)
  (if (tmux-session-exists? socket session)
    (log! root "orphan-session-kill-failed" session)
    (do
      (log! root "orphan-session-killed" session)
      (println "ORPHAN_SESSION_KILLED:" session))))

(defn reconcile-orphan-tmux-sessions!
  "After retire leaks, kill swarmforge-* sessions not in roles.tsv."
  [root skip-tmux?]
  (when-not skip-tmux?
    (when-let [socket (tmux-socket root)]
      (doseq [session (orphan-swarmforge-sessions root socket)]
        (kill-orphan-tmux-session! root socket session)))))

(defn poll-status! [{:keys [root no-notify? skip-tmux?]}]
  (reconcile-orphan-tmux-sessions! root skip-tmux?)
  (let [roles (reconcile-roles! root)
        socket-file (fs/path root ".swarmforge" "tmux-socket")
        socket (when (fs/exists? socket-file) (str/trim (slurp (str socket-file))))
        stale-seconds (env-long "SWARMFORGE_SQUAD_STALE_SECONDS" 300)
        alerts (mapcat #(alerts-for-agent root roles socket skip-tmux? stale-seconds (instant-now) %)
                       (agent-dirs root))
        alert-key-set (set (map alert-key alerts))
        now-instant (instant-now)]
    (doseq [alert alerts]
      (println "SQUAD_STATUS_ALERT:" alert))
    (log-status-alerts! root alerts alert-key-set)
    (notify-status-alerts! root socket no-notify? alerts alert-key-set now-instant)
    (log-status-ok! root alerts)
    alerts))

(defn pending-approval? [root]
  (let [dir (fs/path root ".squad" "approvals" "pending")]
    (and (fs/exists? dir)
         (boolean
          (seq
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".approval"))
                   (fs/list-dir dir)))))))

(defn sl-watchdog-file [root]
  (fs/path (daemon-dir root) "sl-watchdog"))

(defn idle-prompt-tail? [tail]
  (let [text (str/trim (or tail ""))]
    (boolean
     (or (some #(str/ends-with? text %) [">" "$" "%" "#"])
         (re-find #"(?i)(waiting for|ready for|run|enter|prompt)" text)))))

(defn seconds-since [instant]
  (when instant
    (.getSeconds (java.time.Duration/between instant (instant-now)))))

(defn sl-watchdog-enabled? [{:keys [root no-notify? skip-tmux?]}]
  (not (or skip-tmux? no-notify? (pending-approval? root))))

(defn sl-session-available? [socket session]
  (and (not (str/blank? socket))
       (tmux-session-exists? socket session)
       (not (pane-dead? socket session))))

(declare watchdog-unchanged-since seconds-since-or-zero)

(defn sl-watchdog-observation [root socket session]
  (let [state-file (sl-watchdog-file root)
        previous (parse-kv-file state-file)
        tail (or (capture-pane-tail socket session) "")
        status-text (slurp-if-exists (fs/path root ".squad" "agents" "squad-leader" "status"))
        current-hash (sha256 (str tail "\n--status--\n" status-text))
        previous-hash (get previous "pane_hash")
        changed? (not= current-hash previous-hash)
        unchanged-since (watchdog-unchanged-since previous changed?)]
    {:state-file state-file
     :tail tail
     :current-hash current-hash
     :changed? changed?
     :unchanged-since unchanged-since
     :idle-for (seconds-since-or-zero unchanged-since)
     :notified-age (seconds-since (parse-instant (get previous "notified_at")))
     :prompt? (idle-prompt-tail? tail)}))

(defn watchdog-unchanged-since [previous changed?]
  (if changed?
    (now)
    (or (get previous "unchanged_since") (now))))

(defn seconds-since-or-zero [value]
  (or (seconds-since (parse-instant value)) 0))

(defn sl-watchdog-due? [{:keys [notified-age]} cooldown]
  (or (nil? notified-age) (>= notified-age cooldown)))

(defn write-sl-watchdog-state! [{:keys [state-file current-hash unchanged-since idle-for prompt? changed? tail]} threshold due?]
  (write-atomic! state-file
                 (str "pane_hash: " current-hash "\n"
                      "observed_at: " (now) "\n"
                      "unchanged_since: " unchanged-since "\n"
                      "idle_for_seconds: " idle-for "\n"
                      "prompt: " prompt? "\n"
                      (when (and (not changed?) prompt? (>= idle-for threshold) due?)
                        (str "notified_at: " (now) "\n"))
                      "last_10_lines:\n"
                      tail)))

(defn sl-watchdog-log-state [{:keys [changed? prompt? idle-for]} threshold due?]
  (cond
    changed? :active
    (not prompt?) :not-idle
    (< idle-for threshold) :below-threshold
    (not due?) :throttled
    :else :notify))

(defn oldest-sl-owned-dashboard-request-id [root]
  "Only product requests owned by Squad Leader (after Troubleshooter route-to-sl)."
  (let [dir (fs/path root ".swarmforge" "dashboard" "requests" "pending")]
    (when (fs/directory? dir)
      (some (fn [file]
              (let [m (parse-kv-file file)
                    owner (str/lower-case (str/trim (or (get m "owner") "")))
                    id (or (get m "id")
                           (str/replace (fs/file-name file) #"\.request$" ""))]
                (when (= "squad-leader" owner)
                  id)))
            (->> (fs/list-dir dir)
                 (filter #(and (fs/regular-file? %)
                               (str/ends-with? (fs/file-name %) ".request")))
                 (sort-by fs/file-name))))))

(defn sl-watchdog-message-for [root]
  (if-let [id (oldest-sl-owned-dashboard-request-id root)]
    (str "Pending product dashboard request " id " (owner: squad-leader). "
         "Run squad_next.sh --residual-only, route product work, then "
         "squad_dashboard_request.sh answer " id " <answer-file>. "
         "The request is not complete until the helper succeeds.")
    sl-watchdog-message))

(defn log-sl-watchdog-notify! [root socket session idle-for]
  (if (tmux-notify! socket session (sl-watchdog-message-for root))
    (log! root "sl-watchdog-notified" (str idle-for))
    (log! root "sl-watchdog-notify-failed" (str idle-for))))

(def sl-watchdog-log-handlers
  ;; Do not log :active every poll — it drowned the daemon log (bug #5).
  {:active (fn [_ _ _ _] nil)
   :not-idle (fn [root _ _ _] (log! root "sl-watchdog-not-idle-prompt"))
   :below-threshold (fn [_ _ _ _] nil)
   :throttled (fn [root _ _ idle-for] (log! root "sl-watchdog-throttled" (str idle-for)))
   :notify log-sl-watchdog-notify!})

(def last-sl-watchdog-log-state (atom nil))

(defn log-sl-watchdog-state! [root socket session idle-for state]
  (when (not= state @last-sl-watchdog-log-state)
    (reset! last-sl-watchdog-log-state state)
    (when-not (= state :active)
      ((sl-watchdog-log-handlers state) root socket session idle-for))))

(defn log-sl-watchdog! [root socket session {:keys [idle-for] :as observation} threshold due?]
  (log-sl-watchdog-state! root socket session idle-for
                          (sl-watchdog-log-state observation threshold due?)))

(defn poll-sl-watchdog! [{:keys [root no-notify? skip-tmux?]}]
  (when (sl-watchdog-enabled? {:root root :no-notify? no-notify? :skip-tmux? skip-tmux?})
    (let [socket-file (fs/path root ".swarmforge" "tmux-socket")
          socket (when (fs/regular-file? socket-file) (str/trim (slurp (str socket-file))))
          session "swarmforge-squad-leader"
          threshold (env-long "SWARMFORGE_SL_IDLE_SECONDS" 60)
          cooldown (env-long "SWARMFORGE_SL_WATCHDOG_COOLDOWN_SECONDS" 300)]
      (when (sl-session-available? socket session)
        (let [observation (sl-watchdog-observation root socket session)
              due? (sl-watchdog-due? observation cooldown)]
          (write-sl-watchdog-state! observation threshold due?)
          (log-sl-watchdog! root socket session observation threshold due?))))))

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

(defn valid-spawn-request? [{:strs [template task_id assignment]}]
  (not (or (str/blank? template)
           (str/blank? task_id)
           (str/blank? assignment))))

(defn spawn-env []
  (cond-> {"PATH" (System/getenv "PATH")
           "GIT_CONFIG_NOSYSTEM" "1"}
    (= "1" (System/getenv "SWARMFORGE_SQUAD_NO_LAUNCH"))
    (assoc "SWARMFORGE_SQUAD_NO_LAUNCH" "1")
    (not (str/blank? (System/getenv "SWARMFORGE_SQUAD_AGENT")))
    (assoc "SWARMFORGE_SQUAD_AGENT" (System/getenv "SWARMFORGE_SQUAD_AGENT"))
    (not (str/blank? (System/getenv "SWARMFORGE_SQUAD_AGENT_COMMAND")))
    (assoc "SWARMFORGE_SQUAD_AGENT_COMMAND" (System/getenv "SWARMFORGE_SQUAD_AGENT_COMMAND"))))

(defn run-spawn-request! [root {:strs [template task_id assignment]}]
  (process/sh {:continue true
               :dir (str root)
               :env (spawn-env)}
              (str (fs/path script-dir "squad_spawn.sh"))
              template
              task_id
              assignment))

(defn fail-spawn-request! [root active failed message]
  (fs/create-dirs failed)
  (spit (str active ".error") message)
  (move-with-collision active failed)
  (log! root "spawn-request-failed" (str active) "invalid request"))

(defn archive-spawn-result! [root active base completed failed result]
  (let [target-dir (if (zero? (:exit result)) completed failed)]
    (fs/create-dirs target-dir)
    (spit (str (fs/path target-dir (str base ".out"))) (:out result))
    (spit (str (fs/path target-dir (str base ".err"))) (:err result))
    (when-not (zero? (:exit result))
      (spit (str (fs/path target-dir (str base ".error"))) (str "exit " (:exit result) "\n")))
    (move-with-collision active target-dir)
    (if (zero? (:exit result))
      (log! root "spawn-request-completed" (str active))
      (log! root "spawn-request-failed" (str active) (str "exit " (:exit result))))))

(defn handle-active-spawn-request! [root active base completed failed request-data]
  (if-not (valid-spawn-request? request-data)
    (fail-spawn-request! root active failed "spawn request missing template, task_id, or assignment\n")
    (archive-spawn-result! root active base completed failed
                           (run-spawn-request! root request-data))))

(defn log-spawn-deferred-once! [root request blocker]
  "Rate-limit: one log line per request basename+blocker while it sits deferred."
  (let [key (str (fs/file-name request) "|" blocker)]
    (when-not (contains? @deferred-spawn-log-keys key)
      (swap! deferred-spawn-log-keys conj key)
      (log! root "spawn-request-deferred" (str request) blocker))))

(defn clear-deferred-log-keys-for! [basename]
  (swap! deferred-spawn-log-keys
         (fn [keys]
           (into #{} (remove #(str/starts-with? % (str basename "|")) keys)))))

(defn process-spawn-request!
  "Returns :processed, :deferred, or :error."
  [root request]
  (let [{:keys [in-process completed failed]} (spawn-request-dirs root)
        base (fs/file-name request)
        active (fs/path in-process base)
        request-data (parse-kv-file request)
        template (get request-data "template")
        blocker (when-not (str/blank? template)
                  (spawn-capacity-blocker root template))]
    (if blocker
      (do
        (log-spawn-deferred-once! root request blocker)
        :deferred)
      (do
        (clear-deferred-log-keys-for! base)
        (fs/create-dirs in-process)
        (fs/move request active {:replace-existing false})
        (handle-active-spawn-request! root active base completed failed request-data)
        :processed))))

(defn total-capacity-pressure-blocker?
  "Only global max_transient_agents pressure should stop the spawn queue scan.
  Template/group capacity full must defer that request and continue so other
  templates can spawn ( head-of-line)."
  [blocker]
  (= "capacity-full" blocker))

(defn poll-spawn-requests!
  "Process new spawn requests. On total capacity-full, stop the scan early and
  return true so the daemon can back off. Template/group full only defers that
  request and continues scanning. Deferred requests log once each, not every tick."
  [root]
  (let [deferred (atom 0)
        capacity-pressure? (atom false)]
    (doseq [request (or (spawn-request-files root) [])
            :while (and (not @stopping?) (not @capacity-pressure?))]
      (try
        (case (process-spawn-request! root request)
          :deferred
          (do
            (swap! deferred inc)
            (let [blocker (spawn-capacity-blocker
                           root
                           (get (parse-kv-file request) "template"))]
              (when (total-capacity-pressure-blocker? blocker)
                (reset! capacity-pressure? true))))
          :processed nil
          nil)
        (catch Exception e
          (log! root "spawn-request-error" (str request) (.getMessage e)))))
    (when (and (pos? @deferred) @capacity-pressure?)
      (log! root "spawn-queue-waiting"
            (str @deferred " deferred this pass;")
            (str (count (or (spawn-request-files root) [])) " still queued")))
    @capacity-pressure?))

(defn note-spawn-poll-result! [capacity-pressure?]
  (if capacity-pressure?
    (swap! current-poll-ms #(min poll-ms-max (long (* % 1.5))))
    (reset! current-poll-ms poll-ms)))

(defn pid-file [root]
  (fs/path (daemon-dir root) "squadd.pid"))

(defn stop-file [root]
  (fs/path (daemon-dir root) "squadd.stop"))

(defn should-stop? [root]
  (or @stopping? (fs/exists? (stop-file root))))

(defn parse-next-action [out]
  (some #(second (re-find #"^NEXT_ACTION:\s*(\S+)" %))
        (str/split-lines (or out ""))))

(defn apply-workflow-mechanical!
  "Drain deterministic workflow steps via squad_next --apply-mechanical.
  Sets main-git owner env so leftover merge-ready stays daemon-only."
  [root]
  (let [result (process/sh {:continue true
                            :dir (str root)
                            :env {"PATH" (str script-dir ":" (or (System/getenv "PATH") ""))
                                  "GIT_CONFIG_NOSYSTEM" "1"
                                  "SWARMFORGE_ROLE" "squadd"
                                  "SWARMFORGE_MAIN_GIT" "1"
                                  "SWARMFORGE_MAIN_GIT_OWNER" "daemon"}}
                           (str (fs/path script-dir "squad_next.sh"))
                           "--apply-mechanical")
        out (str (:out result))
        action (parse-next-action out)]
    (when (str/includes? out "APPLIED_TRANSITION:")
      (log! root "workflow-mechanical-applied" (str (count (re-seq #"APPLIED_TRANSITION:" out)))))
    (when-not (zero? (:exit result))
      (log! root "workflow-mechanical-failed" (str (:exit result)) (str/trim (or (:err result) ""))))
    ;; Durable residual snapshot for dashboard header
    (when-not (str/blank? action)
      (let [dir (fs/path root ".swarmforge" "daemon")]
        (fs/create-dirs dir)
        (spit (str (fs/path dir "residual-next")) (str action "\n"))))
    {:exit (:exit result)
     :out out
     :next-action action
     :needs-sl? (contains? sl-judgment-actions action)}))

(defn poll-once! [opts]
  (let [root (:root opts)]
    (apply-workflow-mechanical! root)
    (note-spawn-poll-result! (poll-spawn-requests! root))
    (poll-handoffs! root)
    (poll-status! opts)
    (poll-sl-watchdog! opts)))

(defn due-status? []
  (let [now-ms (System/currentTimeMillis)]
    (when (>= (- now-ms @last-status-poll) status-poll-ms)
      (reset! last-status-poll now-ms)
      true)))

(defn poll-loop-once! [opts]
  (let [root (:root opts)]
    (apply-workflow-mechanical! root)
    (note-spawn-poll-result! (poll-spawn-requests! root))
    (poll-handoffs! root)
    (when (due-status?)
      (poll-status! opts)
      (poll-sl-watchdog! opts))))
(defn sleep-poll! [root ms]
  (loop [remaining ms]
    (when (and (pos? remaining) (not (should-stop? root)))
      (let [step (min remaining 100)]
        (Thread/sleep step)
        (recur (- remaining step))))))

(def arg-handlers
  {"--once" (fn [opts _] (assoc opts :once? true))
   "--no-notify" (fn [opts _] (assoc opts :no-notify? true))})

(defn apply-arg! [opts arg]
  (if-let [handler (arg-handlers arg)]
    (handler opts arg)
    (do
      (when (or (:root opts) (str/starts-with? arg "--"))
        (exit! 1 usage-text))
      (assoc opts :root arg))))

(defn parse-args [args]
  (loop [remaining args
         opts {:once? false :no-notify? false :root nil}]
    (if-let [arg (first remaining)]
      (recur (rest remaining) (apply-arg! opts arg))
      (update opts :root #(or % (project-root))))))

(defn shutdown! [root]
  (reset! stopping? true)
  (try
    (fs/delete-if-exists (pid-file root))
    (fs/delete-if-exists (fs/path (daemon-dir root) "squad-web-url"))
    (log! root "stopped")
    (catch Exception _ nil)))

(defn -main [& args]
  (let [{:keys [once? no-notify? root]} (parse-args args)
        root (fs/absolutize root)
        skip-tmux? (= "1" (System/getenv "SWARMFORGE_SQUADD_SKIP_TMUX"))
        opts {:root root :no-notify? no-notify? :skip-tmux? skip-tmux?}]
    (if once?
      (poll-once! opts)
      (do
        (fs/create-dirs (daemon-dir root))
        (fs/delete-if-exists (stop-file root))
        (spit (str (pid-file root)) (str (.pid (java.lang.ProcessHandle/current)) "\n"))
        (.addShutdownHook (Runtime/getRuntime) (Thread. #(shutdown! root)))
        (log! root "started")
        (let [web-server (web/start-web-server! root)]
          (try
            (while (not (should-stop? root))
              (poll-loop-once! opts)
              (sleep-poll! root @current-poll-ms))
            (finally
              (web/stop-web-server! web-server)
              (shutdown! root))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
