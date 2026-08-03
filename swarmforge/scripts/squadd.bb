#!/usr/bin/env bb

(ns squadd
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str])
  (:import [java.net InetAddress ServerSocket URLDecoder]))

(def usage-text
  "Usage: squadd.sh [--once] [--no-notify] [project-root]")

(def poll-ms 1000)
(def status-poll-ms 5000)
(def handoff-wake-message
  "You have new handoff mail. If idle, run ready_for_next.sh.")
(def status-wake-message
  "Squad status needs attention. If idle, run squad_status.sh.")
(def dashboard-html
  "<!doctype html>
<html lang=\"en\">
<head>
  <meta charset=\"utf-8\">
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">
  <title>SwarmForge Squad</title>
  <style>
    :root { color-scheme: light dark; font-family: ui-sans-serif, system-ui, sans-serif; }
    body { margin: 0; background: #f7f7f4; color: #202124; }
    header { padding: 14px 18px; border-bottom: 1px solid #d9d9d2; display: flex; gap: 16px; align-items: baseline; }
    h1 { font-size: 18px; margin: 0; }
    main { padding: 16px 18px 32px; display: grid; gap: 18px; }
    section { display: grid; gap: 8px; }
    h2 { font-size: 14px; margin: 0; text-transform: uppercase; color: #59615b; }
    table { width: 100%; border-collapse: collapse; background: white; border: 1px solid #d9d9d2; }
    th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #ecece6; font-size: 13px; vertical-align: top; }
    th { background: #f0f0ea; color: #3b413d; }
    button { border: 1px solid #9aa59e; background: #fff; color: #202124; padding: 5px 9px; border-radius: 6px; cursor: pointer; }
    button + button { margin-left: 6px; }
    .muted { color: #68726c; }
    .pill { display: inline-block; padding: 2px 6px; border-radius: 999px; background: #e8eee9; }
    .error { color: #9b1c1c; }
  </style>
</head>
<body>
  <header><h1>SwarmForge Squad</h1><span id=\"meta\" class=\"muted\"></span></header>
  <main id=\"app\"></main>
  <script>
    const app = document.getElementById('app');
    const meta = document.getElementById('meta');
    const esc = value => String(value ?? '').replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
    async function post(path) {
      const response = await fetch(path, { method: 'POST' });
      if (!response.ok) throw new Error(await response.text());
      await render();
    }
    function row(cells) { return '<tr>' + cells.map(c => '<td>' + c + '</td>').join('') + '</tr>'; }
    function table(headers, rows) {
      return '<table><thead><tr>' + headers.map(h => '<th>' + esc(h) + '</th>').join('') + '</tr></thead><tbody>' + rows.join('') + '</tbody></table>';
    }
    function approvals(items) {
      if (!items.length) return '<p class=\"muted\">No pending approvals.</p>';
      return table(['Approval', 'Target', 'Gate', 'Reason', 'Actions'], items.map(a => row([
        esc(a.approval_id), esc(a.target_kind + ' ' + a.target_id), esc(a.gate), esc(a.reason),
        `<button onclick=\"post('/api/approvals/${encodeURIComponent(a.approval_id)}/approve')\">Approve</button>` +
        `<button onclick=\"post('/api/approvals/${encodeURIComponent(a.approval_id)}/reject')\">Reject</button>`
      ])));
    }
    async function render() {
      try {
        const data = await (await fetch('/api/state', { cache: 'no-store' })).json();
        meta.textContent = data.project_root + ' | ' + data.generated_at;
        app.innerHTML =
          `<section><h2>Approvals</h2>${approvals(data.approvals.pending)}</section>` +
          `<section><h2>Stories</h2>${table(['Story','State','Gherkin','QA Procedure','Implementation','Final'], data.stories.map(s => row([
            esc(s.story_id), '<span class=\"pill\">' + esc(s.state) + '</span>', esc(s.gherkin_review_state), esc(s.qa_procedure_review_state), esc(s.implementation_assignment_state), esc(s.final_state)
          ])))}</section>` +
          `<section><h2>Agents</h2>${table(['Agent','Template','Task','State','Detail'], data.agents.map(a => row([
            esc(a.agent_id), esc(a.template), esc(a.task_id), esc(a.state), esc(a.detail)
          ])))}</section>` +
          `<section><h2>Assignments</h2>${table(['Assignment','Template','Story','State'], data.assignments.map(a => row([
            esc(a.assignment_id), esc(a.template), esc(a.story_id), esc(a.state)
          ])))}</section>`;
      } catch (err) {
        app.innerHTML = '<p class=\"error\">' + esc(err.message) + '</p>';
      }
    }
    render();
    setInterval(render, 2000);
  </script>
</body>
</html>")
(def script-dir (fs/parent *file*))
(load-file (str (fs/path script-dir "squad_config.bb")))
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

(defn active-state? [state]
  (contains? active-agent-states state))

(defn active-transient-role-count [root]
  (count
   (for [[role _] (load-roles root)
         :when (and (not= "squad-leader" role)
                    (active-state? (read-value (fs/path root ".squad" "agents" role "status") "state")))]
     role)))

(defn active-role? [root role]
  (and (not= "squad-leader" role)
       (active-state? (read-value (fs/path root ".squad" "agents" role "status") "state"))))

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
               "pane_hash: " (sha256 tail) "\n"
               "last_10_lines:\n"
               (or tail "")))))

(defn missing-session-file [root agent]
  (fs/path root ".squad" "agents" agent "missing-session"))

(defn clear-missing-session! [root agent]
  (fs/delete-if-exists (missing-session-file root agent)))

(defn missing-session-grace-seconds []
  (env-long "SWARMFORGE_SQUAD_MISSING_SESSION_GRACE_SECONDS" 30))

(defn missing-session-alert [root agent session]
  (let [file (missing-session-file root agent)
        first-seen (read-value file "first_seen")
        recorded-session (read-value file "session")
        now-instant (instant-now)
        same-session? (= session recorded-session)
        baseline (if same-session? first-seen (now))
        first-instant (when same-session?
                        (parse-instant first-seen))
        age (when first-instant
              (.getSeconds (java.time.Duration/between first-instant now-instant)))
        grace (missing-session-grace-seconds)]
    (fs/create-dirs (fs/parent file))
    (spit (str file)
          (str "session: " session "\n"
               "first_seen: " baseline "\n"
               "observed_at: " (now) "\n"))
    (when (and age (>= age grace))
      (str "agent " agent " tmux session missing for " age " seconds: " session))))

(defn pane-liveness-alert [root socket agent session age]
  (cond
    (str/blank? session) (str "agent " agent " has no tmux session metadata")
    (not (tmux-session-exists? socket session)) (missing-session-alert root agent session)
    (pane-dead? socket session) (str "agent " agent " tmux pane is dead: " session)
    :else (let [liveness (fs/path root ".squad" "agents" agent "liveness")
                previous-hash (read-value liveness "pane_hash")
                tail (or (capture-pane-tail socket session) "")
                current-hash (sha256 tail)
                changed? (not= previous-hash current-hash)
                state (if changed? "running_pane_active" "running_pane_idle")]
            (clear-missing-session! root agent)
            (write-liveness! root agent state changed? tail)
            (when-not changed?
              (str "agent " agent " heartbeat stale for " age " seconds; tmux pane alive but unchanged")))))

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
      (log! root "role-retired-reconciled" agent)
      (append-compat-log! root "squad-statusd.log" "role-retired-reconciled" agent))))

(defn transient-branch [agent]
  (str "swarmforge-" agent))

(defn managed-worktree? [root agent worktree]
  (let [managed-root (fs/absolutize (fs/path root ".worktrees"))
        expected (fs/absolutize (fs/path managed-root agent))
        actual (fs/absolutize worktree)]
    (= (str expected) (str actual))))

(defn cleanup-transient-git! [root agent worktree]
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
        (log! root "git-worktree-removed" agent (str worktree)))))
  (let [branch (transient-branch agent)
        branch-result (git-continue root "branch" "-D" branch)]
    (if (zero? (:exit branch-result))
      (log! root "git-branch-deleted" agent branch)
      (log! root "git-branch-absent" agent branch))))

(defn reconcile-retired-agent! [root socket roles agent dir]
  (let [metadata (fs/path dir "metadata")
        session (or (read-value metadata "session")
                    (get-in roles [agent :session]))
        worktree (or (read-value metadata "worktree")
                     (get-in roles [agent :worktree-path]))]
    (when (kill-tmux-session! socket session)
      (log! root "retired-session-killed" agent session)
      (append-compat-log! root "squad-statusd.log" "retired-session-killed" agent session))
    (cleanup-transient-git! root agent worktree)
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
                            (reconcile-retired-agent! root
                                                      (when-not skip-tmux? socket)
                                                      roles
                                                      agent
                                                      dir)
                            [])
      (and state (not (active-state? state))) []
      (nil? (get roles agent)) [(str "agent " agent " is not registered in roles.tsv")]
      (not (fs/exists? heartbeat)) [(str "agent " agent " has no heartbeat")]
      (nil? age) [(str "agent " agent " heartbeat timestamp is invalid")]
      (> age stale-seconds) (if skip-tmux?
                              [(str "agent " agent " heartbeat stale for " age " seconds")]
                              (if-let [alert (pane-liveness-alert root socket agent session age)]
                                [alert]
                                []))
      :else (if-let [alert (maybe-tmux-alert root socket skip-tmux? agent session)]
              [alert]
              []))))

(defn poll-status! [{:keys [root no-notify? skip-tmux?]}]
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
    (when (seq alerts)
      (let [state-key [:alerts alert-key-set]]
        (when (not= state-key @last-status-log-state)
          (reset! last-status-log-state state-key)
          (doseq [alert alerts]
            (log! root "status-alert" alert)
            (append-compat-log! root "squad-statusd.log" "alert" alert)))))
    (when (seq alerts)
      (if no-notify?
        (append-compat-log! root "squad-statusd.log" "notify-skipped" (count alerts))
        (let [{previous-alerts :alerts notified-at :notified-at} @last-status-notification
              cooldown (notify-cooldown-seconds)
              due? (or (nil? notified-at)
                       (not= alert-key-set previous-alerts)
                       (>= (.getSeconds (java.time.Duration/between notified-at now-instant))
                           cooldown))]
          (if-not due?
            (append-compat-log! root "squad-statusd.log" "notify-throttled" (count alerts))
            (if (tmux-notify! socket "swarmforge-squad-leader" status-wake-message)
              (do
                (reset! last-status-notification {:alerts alert-key-set :notified-at now-instant})
                (append-compat-log! root "squad-statusd.log" "notified" "squad-leader" (count alerts)))
              (append-compat-log! root "squad-statusd.log" "notify-failed" "squad-leader" (count alerts)))))))
    (when (empty? alerts)
      (reset! last-status-notification {:alerts #{} :notified-at nil})
      (println "SQUAD_STATUS_OK")
      (when (not= :ok @last-status-log-state)
        (reset! last-status-log-state :ok)
        (log! root "status-ok")
        (append-compat-log! root "squad-statusd.log" "ok")))
    alerts))

(defn parse-kv-file [file]
  (into {}
        (for [line (or (read-lines file) [])
              :let [[k v] (str/split line #": " 2)]
              :when (and k v)]
          [k v])))

(defn json-escape [value]
  (-> (str value)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\b" "\\b")
      (str/replace "\f" "\\f")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")
      (str/replace "\t" "\\t")))

(declare to-json)

(defn map-entry-json [[k v]]
  (str "\"" (json-escape (name k)) "\":" (to-json v)))

(defn to-json [value]
  (cond
    (nil? value) "null"
    (string? value) (str "\"" (json-escape value) "\"")
    (keyword? value) (str "\"" (json-escape (name value)) "\"")
    (number? value) (str value)
    (true? value) "true"
    (false? value) "false"
    (map? value) (str "{" (str/join "," (map map-entry-json value)) "}")
    (sequential? value) (str "[" (str/join "," (map to-json value)) "]")
    :else (str "\"" (json-escape value) "\"")))

(defn state-files [dir name]
  (if (fs/exists? dir)
    (->> (fs/list-dir dir)
         (filter #(and (fs/regular-file? %) (str/ends-with? (fs/file-name %) name)))
         (sort-by fs/file-name)
         vec)
    []))

(defn map-with-id [id-key id file]
  (assoc (parse-kv-file file) id-key id))

(defn story-state [root]
  (let [dir (fs/path root ".squad" "stories")]
    (if (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (sort-by fs/file-name)
           (mapv (fn [story-dir]
                   (map-with-id "story_id" (fs/file-name story-dir) (fs/path story-dir "packet")))))
      [])))

(defn assignment-state [root]
  (let [dir (fs/path root ".squad" "assignments")]
    (if (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (sort-by fs/file-name)
           (mapv (fn [assignment-dir]
                   (merge (map-with-id "assignment_id" (fs/file-name assignment-dir)
                                       (fs/path assignment-dir "metadata"))
                          (parse-kv-file (fs/path assignment-dir "status"))))))
      [])))

(defn agent-state [root]
  (let [dir (fs/path root ".squad" "agents")]
    (if (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (sort-by fs/file-name)
           (mapv (fn [agent-dir]
                   (merge (map-with-id "agent_id" (fs/file-name agent-dir)
                                       (fs/path agent-dir "metadata"))
                          (parse-kv-file (fs/path agent-dir "status"))
                          {"heartbeat_at" (or (read-value (fs/path agent-dir "heartbeat") "updated_at") "none")}))))
      [])))

(defn approval-state-for [root state]
  (->> (state-files (fs/path root ".squad" "approvals" state) ".approval")
       (mapv #(parse-kv-file %))))

(defn batch-state [root]
  (let [dir (fs/path root ".squad" "batches")]
    (if (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (sort-by fs/file-name)
           (mapv (fn [batch-dir]
                   (merge (map-with-id "batch_id" (fs/file-name batch-dir)
                                       (fs/path batch-dir "metadata"))
                          (parse-kv-file (if (fs/exists? (fs/path batch-dir "status"))
                                           (fs/path batch-dir "status")
                                           (fs/path batch-dir "state")))))))
      [])))

(defn web-state [root]
  {"generated_at" (now)
   "project_root" (str root)
   "stories" (story-state root)
   "assignments" (assignment-state root)
   "agents" (agent-state root)
   "batches" (batch-state root)
   "approvals" {"pending" (approval-state-for root "pending")
                "approved" (approval-state-for root "approved")
                "rejected" (approval-state-for root "rejected")}})

(def status-reasons
  {200 "OK"
   404 "Not Found"
   405 "Method Not Allowed"
   409 "Conflict"
   500 "Internal Server Error"})

(defn response [status content-type body]
  {:status status :content-type content-type :body body})

(defn url-decode [value]
  (URLDecoder/decode value "UTF-8"))

(defn approval-web-action! [root approval-id action]
  (let [detail (if (= action "approve") "approved-by-web" "rejected-by-web")
        result (process/sh {:continue true :dir (str root)}
                           (str (fs/path script-dir "squad_approval.sh"))
                           action
                           approval-id
                           detail)]
    (if (zero? (:exit result))
      {:ok true :output (:out result)}
      {:ok false :status 409 :error (str (:err result) (:out result))})))

(defn handle-web-request [root method path]
  (try
    (cond
      (and (= method "GET") (= path "/"))
      (response 200 "text/html; charset=utf-8" dashboard-html)

      (and (= method "GET") (= path "/api/state"))
      (response 200 "application/json; charset=utf-8" (to-json (web-state root)))

      (and (= method "POST") (re-matches #"/api/approvals/[^/]+/(approve|reject)" path))
      (let [[_ encoded-id action] (re-matches #"/api/approvals/([^/]+)/(approve|reject)" path)
            result (approval-web-action! root (url-decode encoded-id) action)]
        (if (:ok result)
          (response 200 "application/json; charset=utf-8" (to-json {"ok" true}))
          (response (:status result) "text/plain; charset=utf-8" (:error result))))

      (contains? #{"GET" "POST"} method)
      (response 404 "text/plain; charset=utf-8" "Not found\n")

      :else
      (response 405 "text/plain; charset=utf-8" "Method not allowed\n"))
    (catch Exception e
      (response 500 "text/plain; charset=utf-8" (str (.getMessage e) "\n")))))

(defn send-socket-response! [socket {:keys [status content-type body]}]
  (let [body (or body "")
        bytes (.getBytes body "UTF-8")
        reason (get status-reasons status "OK")
        header (str "HTTP/1.1 " status " " reason "\r\n"
                    "Content-Type: " content-type "\r\n"
                    "Cache-Control: no-store\r\n"
                    "Content-Length: " (alength bytes) "\r\n"
                    "Connection: close\r\n"
                    "\r\n")
        out (.getOutputStream socket)]
    (.write out (.getBytes header "UTF-8"))
    (.write out bytes)
    (.flush out)))

(defn handle-client! [root socket]
  (with-open [socket socket
              reader (java.io.BufferedReader.
                      (java.io.InputStreamReader. (.getInputStream socket) "UTF-8"))]
    (let [request-line (.readLine reader)
          [_ method target] (when request-line
                              (re-matches #"([A-Z]+)\s+(\S+)\s+HTTP/.*" request-line))]
      (loop []
        (let [line (.readLine reader)]
          (when (and line (not (str/blank? line)))
            (recur))))
      (send-socket-response! socket
                             (if (and method target)
                               (handle-web-request root method (first (str/split target #"\?" 2)))
                               (response 400 "text/plain; charset=utf-8" "Bad request\n"))))))

(defn start-web-server! [root]
  (when-not (= "0" (System/getenv "SWARMFORGE_SQUADD_WEB"))
    (let [port (env-long "SWARMFORGE_SQUADD_WEB_PORT" 0)
          server-socket (ServerSocket. port 50 (InetAddress/getByName "127.0.0.1"))
          actual-port (.getLocalPort server-socket)
          url (str "http://127.0.0.1:" actual-port "/")
          thread (Thread.
                  (fn []
                    (try
                      (while (not (.isClosed server-socket))
                        (try
                          (handle-client! root (.accept server-socket))
                          (catch java.net.SocketException _ nil)
                          (catch Exception e
                            (log! root "web-error" (.getMessage e)))))
                      (catch java.net.SocketException _ nil))))]
      (.setDaemon thread true)
      (.start thread)
      (write-atomic! (fs/path (daemon-dir root) "squad-web-url") (str url "\n"))
      (log! root "web-started" url)
      {:socket server-socket :thread thread})))

(defn stop-web-server! [web-server]
  (when-let [socket (:socket web-server)]
    (try
      (.close socket)
      (catch Exception _ nil))))

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
        (let [web-server (start-web-server! root)]
          (try
            (while (not (should-stop? root))
              (poll-loop-once! opts)
              (sleep-poll! root poll-ms))
            (finally
              (stop-web-server! web-server)
              (shutdown! root))))))))

(apply -main *command-line-args*)
