#!/usr/bin/env bb

(ns squadd
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [squad-config :as cfg]
            [squad-state :as squad-state])
  (:import [java.net InetAddress ServerSocket URLDecoder]))

(def usage-text
  "Usage: squadd.sh [--once] [--no-notify] [project-root]")

(def poll-ms 1000)
(def status-poll-ms 5000)
(def handoff-wake-message
  "You have new handoff mail. If idle, run squad_next.sh, execute its COMMAND, then repeat until waiting, blocked, or user-gated.")
(def status-wake-message
  "Squad status needs attention. If idle, run squad_next.sh, execute its COMMAND, then repeat until waiting, blocked, or user-gated.")
(def approval-wake-message
  "A web approval changed state. If idle, run squad_next.sh, execute its COMMAND, then repeat until waiting, blocked, or user-gated.")
(def sl-watchdog-message
  "Run squad_next.sh, execute its COMMAND, then repeat until waiting, blocked, or user-gated.")
(def sl-message-prefix
  "User message from dashboard:")
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
    textarea { width: 100%; min-height: 90px; resize: vertical; box-sizing: border-box; border: 1px solid #c6cbc5; padding: 8px; font: inherit; }
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
    async function post(path, body = null, contentType = null) {
      const options = { method: 'POST' };
      if (body !== null) options.body = body;
      if (contentType) options.headers = { 'Content-Type': contentType };
      const response = await fetch(path, options);
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
    async function sendMessage() {
      const input = document.getElementById('sl-message');
      const text = input.value.trim();
      if (!text) return;
      await post('/api/sl-message', text, 'text/plain; charset=utf-8');
      input.value = '';
    }
    async function render() {
      try {
        const data = await (await fetch('/api/state', { cache: 'no-store' })).json();
        meta.textContent = data.project_root + ' | ' + data.generated_at;
        app.innerHTML =
          `<section><h2>Blockers</h2>${table(['Assignment','Kind','Detail'], data.blockers.map(b => row([
            esc(b.assignment_id), esc(b.kind || 'blocked'), esc(b.detail || '')
          ])))}</section>` +
          `<section><h2>Pending Approvals</h2>${approvals(data.approvals.pending)}</section>` +
          `<section><h2>Message Squad Leader</h2><textarea id=\"sl-message\"></textarea><div><button onclick=\"sendMessage()\">Submit</button></div></section>` +
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
(def stopping? (atom false))
(def last-status-poll (atom 0))
(def last-status-notification (atom {:alerts #{} :notified-at nil}))
(def last-status-log-state (atom nil))
(def active-agent-states
  #{"starting" "running"})
(def web-active-agent-states
  #{"starting" "running" "blocked" "handoff_ready" "handoff_sent"})
(def web-active-assignment-states
  #{"created" "assignment_created" "in_progress" "handoff_sent" "result_received" "merge_ready" "review_changes_requested" "blocked"})

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

(declare agent-dirs log! parse-kv-file tmux-session-exists? idle-prompt-tail?)

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

(defn capacity-counted-role? [root socket role role-data]
  (let [state (read-value (fs/path root ".squad" "agents" role "status") "state")
        session (:session role-data)]
    (and (not= "squad-leader" role)
         (not= "merger" (role-template root role))
         (not (contains? #{"retired" "failed"} state))
         (if (skip-tmux-env?)
           (active-state? state)
           (tmux-session-exists? socket session))
         (not (and (= "handoff_sent" state)
                   (contains? (visible-handoff-agents root) role))))))

(defn active-transient-role-count [root]
  (count
   (let [socket (tmux-socket root)]
     (for [[role role-data] (load-roles root)
           :when (capacity-counted-role? root socket role role-data)]
       role))))

(defn active-role? [root role]
  (let [roles (load-roles root)
        socket (tmux-socket root)]
    (capacity-counted-role? root socket role (get roles role))))

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

(defn total-capacity-full? [root]
  (>= (active-transient-role-count root) (cfg/squad-max-transient-agents root)))

(defn template-capacity-full? [root template]
  (when-let [limit (cfg/squad-template-limit root template)]
    (>= (active-template-count root template) limit)))

(defn group-capacity-blocker [root {:keys [group limit templates]}]
  (when (>= (active-group-count root templates) limit)
    (str "group-capacity-full:" group)))

(defn spawn-capacity-blocker [root template]
  (when-not (= "merger" template)
    (cond
      (total-capacity-full? root)
      "capacity-full"

      (template-capacity-full? root template)
      (str "template-capacity-full:" template)

      :else
      (some #(group-capacity-blocker root %)
            (cfg/squad-template-group-limits root template)))))

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

(defn log! [root & parts]
  (let [log-file (fs/path (daemon-dir root) "squadd.log")]
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

(defn retired-agent-alert! [root roles socket skip-tmux? dir {:keys [agent]}]
  (reconcile-retired-agent! root
                            (when-not skip-tmux? socket)
                            roles
                            agent
                            dir)
  nil)

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

(defn log-sl-watchdog-notify! [root socket session idle-for]
  (if (tmux-notify! socket session sl-watchdog-message)
    (log! root "sl-watchdog-notified" (str idle-for))
    (log! root "sl-watchdog-notify-failed" (str idle-for))))

(def sl-watchdog-log-handlers
  {:active (fn [root _ _ _] (log! root "sl-watchdog-active"))
   :not-idle (fn [root _ _ _] (log! root "sl-watchdog-not-idle-prompt"))
   :below-threshold (fn [_ _ _ _] nil)
   :throttled (fn [root _ _ idle-for] (log! root "sl-watchdog-throttled" (str idle-for)))
   :notify log-sl-watchdog-notify!})

(defn log-sl-watchdog-state! [root socket session idle-for state]
  ((sl-watchdog-log-handlers state) root socket session idle-for))

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

(def json-kind-rules
  [[nil? :nil]
   [string? :string]
   [keyword? :keyword]
   [number? :number]
   [true? :true]
   [false? :false]
   [map? :map]
   [sequential? :sequential]])

(defn json-kind [value]
  (or (some (fn [[predicate kind]]
              (when (predicate value)
                kind))
            json-kind-rules)
      :other))

(defn quoted-json [value]
  (str "\"" (json-escape value) "\""))

(def json-renderers
  {:nil (fn [_] "null")
   :string quoted-json
   :keyword (fn [value] (quoted-json (name value)))
   :number str
   :true (fn [_] "true")
   :false (fn [_] "false")
   :map (fn [value] (str "{" (str/join "," (map map-entry-json value)) "}"))
   :sequential (fn [value] (str "[" (str/join "," (map to-json value)) "]"))
   :other quoted-json})

(defn to-json [value]
  ((json-renderers (json-kind value)) value))

(defn state-files [dir name]
  (if (fs/exists? dir)
    (->> (fs/list-dir dir)
         (filter #(and (fs/regular-file? %) (str/ends-with? (fs/file-name %) name)))
         (sort-by fs/file-name)
         vec)
    []))

(defn map-with-id [id-key id file]
  (assoc (parse-kv-file file) id-key id))

(defn canonical-story-row [story-id packet-file]
  (let [packet (assoc (squad-state/read-kv-file packet-file) "story_id" story-id)
        state (squad-state/recompute-state packet)]
    (merge packet
           (squad-state/derived-stage-fields packet state)
           {"state" state})))

(defn story-state [root]
  (let [dir (fs/path root ".squad" "stories")]
    (if (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (sort-by fs/file-name)
           (mapv (fn [story-dir]
                   (canonical-story-row (fs/file-name story-dir)
                                        (fs/path story-dir "packet")))))
      [])))

(defn descending-value [row]
  (or (get row "updated_at")
      (get row "created_at")
      (get row "assignment_id")
      ""))

(defn assignment-state [root]
  (let [dir (fs/path root ".squad" "assignments")]
    (if (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (map (fn [assignment-dir]
                  (merge (map-with-id "assignment_id" (fs/file-name assignment-dir)
                                      (fs/path assignment-dir "metadata"))
                         (parse-kv-file (fs/path assignment-dir "status")))))
           (filter #(contains? web-active-assignment-states (get % "state")))
           (sort-by descending-value #(compare %2 %1))
           vec)
      [])))

(defn agent-state [root]
  (let [dir (fs/path root ".squad" "agents")]
    (if (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (sort-by fs/file-name)
           (map (fn [agent-dir]
                  (merge (map-with-id "agent_id" (fs/file-name agent-dir)
                                      (fs/path agent-dir "metadata"))
                         (parse-kv-file (fs/path agent-dir "status"))
                         {"heartbeat_at" (or (read-value (fs/path agent-dir "heartbeat") "updated_at") "none")})))
           (filter #(contains? web-active-agent-states (get % "state")))
           vec)
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

(defn blocker-state [root]
  (let [dir (fs/path root ".squad" "assignments")]
    (if (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (keep (fn [assignment-dir]
                   (let [blocker (fs/path assignment-dir "blocker")
                         status (parse-kv-file (fs/path assignment-dir "status"))]
                     (when (fs/regular-file? blocker)
                       (merge {"assignment_id" (fs/file-name assignment-dir)
                               "detail" (get status "detail" "")}
                              (parse-kv-file blocker))))))
           (sort-by descending-value #(compare %2 %1))
           vec)
      [])))

(defn web-state [root]
  {"generated_at" (now)
   "project_root" (str root)
   "stories" (story-state root)
   "assignments" (assignment-state root)
   "agents" (agent-state root)
   "batches" (batch-state root)
   "blockers" (blocker-state root)
   "approvals" {"pending" (approval-state-for root "pending")}})

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
      (do
        (let [socket-file (fs/path root ".swarmforge" "tmux-socket")]
          (when (fs/regular-file? socket-file)
            (tmux-notify! (str/trim (slurp (str socket-file)))
                          "swarmforge-squad-leader"
                          approval-wake-message)))
        {:ok true :output (:out result)})
      {:ok false :status 409 :error (str (:err result) (:out result))})))

(defn web-error [message]
  {:ok false :status 409 :error message})

(defn socket-value [root]
  (let [socket-file (fs/path root ".swarmforge" "tmux-socket")]
    (when (fs/regular-file? socket-file)
      (str/trim (slurp (str socket-file))))))

(defn sl-dashboard-message [message]
  (str sl-message-prefix "\n\n" message))

(defn send-sl-dashboard-message! [socket message]
  (tmux-notify! socket "swarmforge-squad-leader" (sl-dashboard-message message)))

(defn sl-message-result! [root socket message]
  (if (send-sl-dashboard-message! socket message)
    (do
      (log! root "web-sl-message-sent")
      {:ok true})
    (web-error "Could not send message to squad leader\n")))

(defn sl-message-web-action! [root text]
  (let [message (str/trim (or text ""))]
    (cond
      (str/blank? message) (web-error "Message is empty\n")
      (nil? (socket-value root)) (web-error "Missing tmux socket\n")
      :else (sl-message-result! root (socket-value root) message))))

(defn state-response [root]
  (response 200 "application/json; charset=utf-8" (to-json (web-state root))))

(defn approval-response [root path]
  (let [[_ encoded-id action] (re-matches #"/api/approvals/([^/]+)/(approve|reject)" path)
        result (approval-web-action! root (url-decode encoded-id) action)]
    (if (:ok result)
      (response 200 "application/json; charset=utf-8" (to-json {"ok" true}))
      (response (:status result) "text/plain; charset=utf-8" (:error result)))))

(defn sl-message-response [root body]
  (let [result (sl-message-web-action! root body)]
    (if (:ok result)
      (response 200 "application/json; charset=utf-8" (to-json {"ok" true}))
      (response (:status result) "text/plain; charset=utf-8" (:error result)))))

(def web-routes
  [{:method "GET"
    :path "/"
    :handler (fn [_ _ _] (response 200 "text/html; charset=utf-8" dashboard-html))}
   {:method "GET"
    :path "/api/state"
    :handler (fn [root _ _] (state-response root))}
   {:method "POST"
    :pattern #"/api/approvals/[^/]+/(approve|reject)"
    :handler (fn [root path _] (approval-response root path))}
   {:method "POST"
    :path "/api/sl-message"
    :handler (fn [root _ body] (sl-message-response root body))}])

(defn route-matches? [{:keys [method path pattern]} request-method request-path]
  (and (= method request-method)
       (or (= path request-path)
           (and pattern (re-matches pattern request-path)))))

(defn route-response [root method path body]
  (some (fn [{:keys [handler] :as route}]
          (when (route-matches? route method path)
            (handler root path body)))
        web-routes))

(defn route-web-request [root method path body]
  (or (route-response root method path body)
      (if (contains? #{"GET" "POST"} method)
        (response 404 "text/plain; charset=utf-8" "Not found\n")
        (response 405 "text/plain; charset=utf-8" "Method not allowed\n"))))

(defn handle-web-request [root method path body]
  (try
    (route-web-request root method path body)
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

(defn header-entry [line]
  (let [[k v] (str/split line #":\s*" 2)]
    (when (and k v)
      [(str/lower-case k) v])))

(defn read-header-line [reader]
  (let [line (.readLine reader)]
    (when-not (or (nil? line) (str/blank? line))
      line)))

(defn read-headers [reader]
  (loop [headers {}]
    (if-let [line (read-header-line reader)]
      (recur (if-let [[k v] (header-entry line)]
               (assoc headers k v)
               headers))
      headers)))

(defn content-length [headers]
  (try
    (Long/parseLong (get headers "content-length" "0"))
    (catch Exception _ 0)))

(defn read-body [reader length]
  (if (pos? length)
    (let [buffer (char-array length)
          read-count (.read reader buffer 0 length)]
      (String. buffer 0 (max 0 read-count)))
    ""))

(defn parse-request-line [request-line]
  (when request-line
    (let [[_ method target] (re-matches #"([A-Z]+)\s+(\S+)\s+HTTP/.*" request-line)]
      {:method method :target target})))

(defn target-path [target]
  (first (str/split target #"\?" 2)))

(defn request-response [root {:keys [method target]} body]
  (if (and method target)
    (handle-web-request root method (target-path target) body)
    (response 400 "text/plain; charset=utf-8" "Bad request\n")))

(defn handle-client! [root socket]
  (with-open [socket socket
              reader (java.io.BufferedReader.
                      (java.io.InputStreamReader. (.getInputStream socket) "UTF-8"))]
    (let [request (parse-request-line (.readLine reader))
          headers (read-headers reader)
          body (read-body reader (content-length headers))]
      (send-socket-response! socket (request-response root request body)))))

(defn web-enabled? []
  (not= "0" (System/getenv "SWARMFORGE_SQUADD_WEB")))

(defn web-open-command []
  (cond
    (System/getenv "SWARMFORGE_SQUADD_WEB_OPEN_COMMAND")
    (str/split (System/getenv "SWARMFORGE_SQUADD_WEB_OPEN_COMMAND") #"\s+")
    (= "Mac OS X" (System/getProperty "os.name"))
    ["open"]
    :else
    ["xdg-open"]))

(defn should-open-web? []
  (and (not= "0" (System/getenv "SWARMFORGE_SQUADD_WEB_OPEN"))
       (not= "1" (System/getenv "SWARMFORGE_SQUADD_SKIP_TMUX"))))

(defn maybe-open-web! [root url]
  (when (should-open-web?)
    (let [result (apply sh-continue (concat (web-open-command) [url]))]
      (if (zero? (:exit result))
        (log! root "web-opened" url)
        (log! root "web-open-failed" url (str "exit " (:exit result)))))))

(defn web-accept-loop! [root server-socket]
  (try
    (while (not (.isClosed server-socket))
      (try
        (handle-client! root (.accept server-socket))
        (catch java.net.SocketException _ nil)
        (catch Exception e
          (log! root "web-error" (.getMessage e)))))
    (catch java.net.SocketException _ nil)))

(defn start-web-thread! [root server-socket]
  (let [thread (Thread. #(web-accept-loop! root server-socket))]
    (.setDaemon thread true)
    (.start thread)
    thread))

(defn start-web-server! [root]
  (when (web-enabled?)
    (let [port (env-long "SWARMFORGE_SQUADD_WEB_PORT" 0)
          server-socket (ServerSocket. port 50 (InetAddress/getByName "127.0.0.1"))
          actual-port (.getLocalPort server-socket)
          url (str "http://127.0.0.1:" actual-port "/")
          thread (start-web-thread! root server-socket)]
      (write-atomic! (fs/path (daemon-dir root) "squad-web-url") (str url "\n"))
      (log! root "web-started" url)
      (maybe-open-web! root url)
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

(defn process-spawn-request! [root request]
  (let [{:keys [in-process completed failed]} (spawn-request-dirs root)
        base (fs/file-name request)
        active (fs/path in-process base)
        request-data (parse-kv-file request)
        template (get request-data "template")
        blocker (when-not (str/blank? template)
                  (spawn-capacity-blocker root template))]
    (if blocker
      (log! root "spawn-request-deferred" (str request) blocker)
      (do
        (fs/create-dirs in-process)
        (fs/move request active {:replace-existing false})
        (handle-active-spawn-request! root active base completed failed request-data)))))

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
    (poll-status! opts)
    (poll-sl-watchdog! opts)))

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
        (let [web-server (start-web-server! root)]
          (try
            (while (not (should-stop? root))
              (poll-loop-once! opts)
              (sleep-poll! root poll-ms))
            (finally
              (stop-web-server! web-server)
              (shutdown! root))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
