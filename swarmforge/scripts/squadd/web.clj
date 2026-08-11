#!/usr/bin/env bb

(ns squadd.web
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [squad-dashboard-request :as dashreq]
            [squad-state :as squad-state])
  (:import [java.net InetAddress ServerSocket URLDecoder]))

(def approval-wake-message
  "A web approval changed state. If idle, run squad_next.sh --apply-mechanical and handle only residual judgment or user-facing work. Deterministic workflow steps are daemon-applied.")

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
    button:active, button.pressed { background: #202124; color: #fff; transform: translateY(1px); }
    button + button { margin-left: 6px; }
    .muted { color: #68726c; }
    .pill { display: inline-block; padding: 2px 6px; border-radius: 999px; background: #e8eee9; font-size: 11px; }
    .pill-pending { background: #fff3cd; }
    .pill-answered { background: #d4edda; }
    .pill-rejected { background: #f8d7da; }
    .error { color: #9b1c1c; }
    .req-history { max-height: 280px; overflow-y: auto; background: white; border: 1px solid #d9d9d2; padding: 10px 12px; display: grid; gap: 10px; }
    .req-you { border-left: 3px solid #6b7c72; padding-left: 8px; }
    .req-sl { color: #4a4f4c; padding-left: 16px; }
    .req-sl.short { font-style: italic; }
    .req-meta { font-size: 11px; color: #68726c; margin-bottom: 2px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .composer-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
  </style>
</head>
<body>
  <header><h1>SwarmForge Squad</h1><span id=\"meta\" class=\"muted\"></span></header>
  <main>
    <p id=\"error\" class=\"error\"></p>
    <section><h2>Blockers</h2><div id=\"blockers\"></div></section>
    <section><h2>Pending Approvals</h2><div id=\"approvals\"></div></section>
    <section>
      <h2 id=\"sl-requests-title\">Squad Leader Requests</h2>
      <div id=\"sl-requests\" class=\"req-history\"></div>
      <div class=\"composer-row\">
        <button type=\"button\" onclick=\"sendRequest()\">Submit</button>
      </div>
      <textarea id=\"sl-message\" placeholder=\"Message for the squad leader…\"></textarea>
    </section>
    <section><h2>Stories</h2><div id=\"stories\"></div></section>
    <section><h2>Agents</h2><div id=\"agents\"></div></section>
    <section><h2>Assignments</h2><div id=\"assignments\"></div></section>
  </main>
  <script>
    const meta = document.getElementById('meta');
    const error = document.getElementById('error');
    const blockersPanel = document.getElementById('blockers');
    const approvalsPanel = document.getElementById('approvals');
    const storiesPanel = document.getElementById('stories');
    const agentsPanel = document.getElementById('agents');
    const assignmentsPanel = document.getElementById('assignments');
    const requestsPanel = document.getElementById('sl-requests');
    const requestsTitle = document.getElementById('sl-requests-title');
    const slDraftKey = 'swarmforge.slMessageDraft';
    let slDraft = localStorage.getItem(slDraftKey) || '';
    let pressedApproval = null;
    const esc = value => String(value ?? '').replace(/[&<>\"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;','\\'':'&#39;'}[c] || c));
    async function post(path, body = null, contentType = null, refresh = true) {
      const options = { method: 'POST' };
      if (body !== null) options.body = body;
      if (contentType) options.headers = { 'Content-Type': contentType };
      const response = await fetch(path, options);
      if (!response.ok) throw new Error(await response.text());
      if (refresh) await render();
      return response;
    }
    function row(cells) { return '<tr>' + cells.map(c => '<td>' + c + '</td>').join('') + '</tr>'; }
    function table(headers, rows) {
      return '<table><thead><tr>' + headers.map(h => '<th>' + esc(h) + '</th>').join('') + '</tr></thead><tbody>' + rows.join('') + '</tbody></table>';
    }
    function artifactKindForApproval(a) {
      const gate = String(a.gate || '').replaceAll('_', '-');
      if (gate === 'theme') return 'theme';
      if (gate === 'story' || gate === 'implementation' || gate === 'final') return 'story';
      if (gate === 'gherkin') return 'gherkin';
      if (gate === 'qa-procedure' || gate === 'qa') return 'qa-procedure';
      if (gate === 'code-review' || gate === 'architecture') return 'review';
      if (gate === 'hardening') return 'story';
      return String(a.target_kind || 'story').replaceAll('_', '-');
    }
    function artifactLink(kind, id, label) {
      return `<a target=\"_blank\" href=\"/artifact/${encodeURIComponent(kind)}/${encodeURIComponent(id)}\">${esc(label)}</a>`;
    }
    function approvalButton(id, action, label) {
      const pressed = pressedApproval && pressedApproval.id === id && pressedApproval.action === action;
      const cls = pressed ? ' class=\"pressed\"' : '';
      return `<button type=\"button\" data-approval-id=\"${esc(id)}\" data-approval-action=\"${action}\"${cls}` +
        ` onpointerdown=\"window.__sfPressApproval(event,'${esc(id)}','${action}')\"` +
        ` onpointerup=\"window.__sfReleaseApproval(event,'${esc(id)}','${action}')\"` +
        ` onpointerleave=\"window.__sfCancelApproval(event,'${esc(id)}','${action}')\"` +
        ` onpointercancel=\"window.__sfCancelApproval(event,'${esc(id)}','${action}')\">${label}</button>`;
    }
    window.__sfPressApproval = (event, id, action) => {
      event.preventDefault();
      pressedApproval = {id, action};
      const btn = event.currentTarget;
      if (btn) btn.classList.add('pressed');
    };
    window.__sfCancelApproval = (event, id, action) => {
      if (pressedApproval && pressedApproval.id === id && pressedApproval.action === action) {
        pressedApproval = null;
      }
      const btn = event.currentTarget;
      if (btn) btn.classList.remove('pressed');
    };
    window.__sfReleaseApproval = async (event, id, action) => {
      const match = pressedApproval && pressedApproval.id === id && pressedApproval.action === action;
      pressedApproval = null;
      const btn = event.currentTarget;
      if (btn) btn.classList.remove('pressed');
      if (!match) return;
      try {
        await post('/api/approvals/' + encodeURIComponent(id) + '/' + action);
      } catch (err) {
        error.textContent = err.message;
      }
    };
    function approvals(items) {
      if (!items.length) return '<p class=\"muted\">No pending approvals.</p>';
      return table(['Approval', 'Target', 'Gate', 'Reason', 'Actions'], items.map(a => row([
        esc(a.approval_id),
        artifactLink(artifactKindForApproval(a), a.target_id, a.target_kind + ' ' + a.target_id),
        esc(a.gate), esc(a.reason),
        approvalButton(a.approval_id, 'approve', 'Approve') +
        approvalButton(a.approval_id, 'reject', 'Reject')
      ])));
    }
    function blockers(items) {
      if (!items.length) return '<p class=\"muted\">No blockers.</p>';
      // No one-click Resolve: operator + SL clear via squad_approval.sh resolve-rejection after recovery.
      return table(['Id','Kind','Detail'], items.map(b => {
        const id = b.assignment_id || b.blocker_id || b.approval_id || '';
        return row([
          artifactLink('blocker', id, id),
          esc(b.kind || 'blocked'),
          esc(b.detail || '')
        ]);
      }));
    }
    function statusPill(status) {
      const s = String(status || 'pending');
      return '<span class=\"pill pill-' + esc(s) + '\">' + esc(s) + '</span>';
    }
    function renderRequests(items) {
      if (!items || !items.length) return '<p class=\"muted\">No squad leader requests yet.</p>';
      return items.map(r => {
        const cancel = r.status === 'pending'
          ? ' <button type=\"button\" onclick=\"cancelRequest(\\'' + esc(r.id) + '\\')\">Cancel</button>'
          : '';
        const response = r.response
          ? '<div class=\"req-sl' + (String(r.response).length < 40 ? ' short' : '') + '\">' + esc(r.response) + '</div>'
          : (r.status === 'rejected' && r.detail
             ? '<div class=\"req-sl short\">' + esc(r.detail) + '</div>'
             : '');
        return '<div class=\"req-item\">' +
          '<div class=\"req-meta\"><strong>You</strong>' + statusPill(r.status) +
          '<span class=\"muted\">' + esc(r.id) + '</span>' + cancel + '</div>' +
          '<div class=\"req-you\">' + esc(r.body || '') + '</div>' +
          response +
          '</div>';
      }).join('');
    }
    async function cancelRequest(id) {
      try {
        await post('/api/sl-requests/' + encodeURIComponent(id) + '/cancel', null, null, true);
      } catch (err) {
        error.textContent = err.message;
      }
    }
    const messageInput = document.getElementById('sl-message');
    messageInput.value = slDraft;
    messageInput.addEventListener('input', () => {
      slDraft = messageInput.value;
      localStorage.setItem(slDraftKey, slDraft);
    });
    messageInput.addEventListener('keydown', event => {
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        sendRequest();
      }
    });
    async function sendRequest() {
      const text = messageInput.value.trim();
      if (!text) return;
      const payload = JSON.stringify({ body: text });
      await post('/api/sl-requests', payload, 'application/json; charset=utf-8', false);
      slDraft = '';
      localStorage.removeItem(slDraftKey);
      messageInput.value = '';
      await render();
    }
    function selectionInside(el) {
      const sel = window.getSelection();
      if (!sel || sel.isCollapsed || sel.rangeCount === 0) return false;
      const node = sel.anchorNode;
      if (!node) return false;
      const element = node.nodeType === 3 ? node.parentNode : node;
      return !!(element && el.contains(element));
    }
    function updateRequestsPanel(items) {
      const html = renderRequests(items);
      // Skip rebuild while the operator has a text selection (copy/paste).
      if (selectionInside(requestsPanel)) return;
      if (requestsPanel.dataset.lastHtml === html) return;
      const nearBottom = requestsPanel.scrollHeight - requestsPanel.scrollTop - requestsPanel.clientHeight <= 24;
      requestsPanel.innerHTML = html;
      requestsPanel.dataset.lastHtml = html;
      if (nearBottom) requestsPanel.scrollTop = requestsPanel.scrollHeight;
    }
    async function render() {
      try {
        const data = await (await fetch('/api/state', { cache: 'no-store' })).json();
        meta.textContent = data.project_root + ' | ' + data.generated_at;
        error.textContent = '';
        blockersPanel.innerHTML = blockers(data.blockers || []);
        approvalsPanel.innerHTML = approvals((data.approvals && data.approvals.pending) || []);
        const slQueue = (data.sl_queue_depth != null) ? data.sl_queue_depth : 0;
        requestsTitle.textContent = 'Squad Leader Requests (queue: ' + slQueue + ')';
        updateRequestsPanel(data.sl_requests || []);
        storiesPanel.innerHTML = table(['Story','State','Substate'], (data.stories || []).map(s => row([
          artifactLink('story', s.story_id, s.story_number ? ('#' + s.story_number + ' ' + s.story_id) : s.story_id),
          '<span class=\"pill\">' + esc(s.stage_label || s.state) + '</span>', esc(s.stage_detail || s.final_state)
        ])));
        agentsPanel.innerHTML = table(['Agent','Template','Task','State','Detail'], (data.agents || []).map(a => row([
          `<a target=\"_blank\" href=\"/agent/${encodeURIComponent(a.agent_id)}\">${esc(a.agent_id)}</a>`, esc(a.template), esc(a.task_id), esc(a.state), esc(a.detail)
        ])));
        assignmentsPanel.innerHTML = table(['Assignment','Template','Story','State'], (data.assignments || []).map(a => row([
          artifactLink('assignment', a.assignment_id, a.assignment_id), esc(a.template), esc(a.story_id), esc(a.state)
        ])));
      } catch (err) {
        error.textContent = err.message;
      }
    }
    render();
    setInterval(render, 2000);
  </script>
</body>
</html>")

(def web-active-assignment-states
  #{"created" "assignment_created" "in_progress" "handoff_sent" "result_received" "merge_ready" "blocked"})

(defn dashboard-agent-visible?
  "Agents appear from spawn until retirement. Temporary states such as
  failed must remain visible while the agent is still registered."
  [agent]
  (not= "retired" (get agent "state")))
(def status-reasons
  {200 "OK"
   404 "Not Found"
   405 "Method Not Allowed"
   409 "Conflict"
   500 "Internal Server Error"})

(def script-dir-path
  (or (some-> (io/resource "squadd/web.clj") .toURI fs/path fs/parent fs/parent)
      (some-> *file* fs/parent fs/parent)
      (fs/path (System/getProperty "user.dir") "swarmforge" "scripts")))

(defn script-dir []
  script-dir-path)

(defn now []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn env-long [name default-value]
  (if-let [value (System/getenv name)]
    (if (re-matches #"[0-9]+" value)
      (Long/parseLong value)
      default-value)
    default-value))

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

(defn with-log-dir-lock! [lock-dir f]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (cond
        (try-mkdir-lock! lock-dir)
        (try
          (f)
          (finally
            (try (fs/delete-tree lock-dir) (catch Exception _))))

        (> (System/currentTimeMillis) deadline)
        (f)

        :else
        (do
          (Thread/sleep 5)
          (recur))))))

(defn log! [root & parts]
  (let [log-file (fs/path (daemon-dir root) "squadd.log")
        path (str log-file)
        lock-dir (fs/path (str path ".dlock"))
        line (str (now) " " (str/join " " parts) "\n")]
    (fs/create-dirs (fs/parent log-file))
    (locking log-lock
      (with-log-dir-lock! lock-dir
        (fn []
          (spit path line :append true))))))

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

(defn write-atomic! [file content]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                  :prefix (str "." (fs/file-name file) ".")})]
    (spit (str tmp) content)
    (fs/move tmp file {:replace-existing true})))

(defn sh-continue [& args]
  (apply process/sh (concat [{:continue true}] args)))

(defn tmux-notify! [socket session message]
  (let [send-text (sh "tmux" "-S" socket "send-keys" "-t" session "-l" message)
        _ (Thread/sleep 100)
        send-return (sh "tmux" "-S" socket "send-keys" "-t" session "C-m")
        _ (Thread/sleep 100)
        send-second-return (sh "tmux" "-S" socket "send-keys" "-t" session "C-m")]
    (and (zero? (:exit send-text))
         (zero? (:exit send-return))
         (zero? (:exit send-second-return)))))

(defn codex-input-line? [line]
  (str/starts-with? line "› "))

(defn grok-footer-line? [line]
  (or (re-find #"│\s*❯" line)
      (re-find #"^\s*❯\s" line)
      (str/starts-with? (str/trim line) "Grok ")
      (str/includes? line "Shift+Tab:mode")
      (str/includes? line "Ctrl+x:shortcuts")
      (re-find #"^\s*┌─" line)
      (re-find #"^\s*└─" line)
      (re-find #"^\s*│" line)))

(defn strip-codex-input-region [text]
  (let [lines (vec (str/split-lines (or text "")))
        input-index (last (keep-indexed (fn [idx line]
                                          (when (codex-input-line? line) idx))
                                        lines))
        kept (if input-index (subvec lines 0 input-index) lines)]
    (str (str/join "\n" kept) (when (seq kept) "\n"))))

(defn strip-grok-input-region [text]
  (let [lines (vec (str/split-lines (or text "")))
        ;; Drop trailing footer/prompt chrome; stop at first non-footer from the end.
        cut (loop [i (dec (count lines))]
              (cond
                (neg? i) 0
                (or (str/blank? (nth lines i))
                    (grok-footer-line? (nth lines i)))
                (recur (dec i))
                :else (inc i)))
        kept (subvec lines 0 cut)]
    (str (str/join "\n" kept) (when (seq kept) "\n"))))

(defn strip-input-region
  ([text] (strip-input-region text nil))
  ([text backend]
   (case (some-> backend str/lower-case)
     ("codex" "chatgpt") (strip-codex-input-region text)
     ("grok" "xai") (strip-grok-input-region text)
     ;; Unknown: try codex then grok markers conservatively.
     (let [codex (strip-codex-input-region text)]
       (if (not= codex text)
         codex
         (strip-grok-input-region text))))))

(defn capture-pane-tail
  ([socket session] (capture-pane-tail socket session nil))
  ([socket session backend]
   (strip-input-region
    (:out (sh-continue "tmux" "-S" socket "capture-pane" "-p" "-t" session "-S" "-200"))
    backend)))

(defn parse-kv-file [file]
  (into {}
        (for [line (or (read-lines file) [])
              :let [[k v] (str/split line #": " 2)]
              :when (and k v)]
          [k v])))

(declare to-json)

(defn json-escape [value]
  (-> (str value)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\b" "\\b")
      (str/replace "\f" "\\f")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")
      (str/replace "\t" "\\t")))

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

(def stage-labels
  {"story_recorded" "specified"
   "story_approved" "specified"
   "specification_in_progress" "specified"
   "implementation_approval_ready" "qa approved"
   "implementation_approved" "qa approved"
   "implemented" "implemented"
   "cleaned" "cleaned"
   "code_reviewed" "code reviewed"
   "code_review_approved" "code reviewed"
   "hardener_returned" "hardened"
   "hardening_approved" "hardened"
   "qa_returned" "hardened"
   "qa_approved" "hardened"
   "architecture_returned" "hardened"
   "architecture_revision_returned" "hardened"
   "architecture_reviewed" "architect approved"
   "architecture_approved" "architect approved"
   "final_approved" "done"})

(defn stage-label [state]
  (get stage-labels state state))

(def stage-detail-rules
  [["final_approved" (fn [_] "final approved")]
   ["architecture_approved" (fn [_] "architecture approved")]
   ["architecture_reviewed" (fn [p] (str "architecture review " (get p "architecture_review" "accepted")))]
   ["architecture_revision_returned" (fn [_] "senior implementer complete")]
   ["architecture_returned" (fn [_] "architecture returned")]
   ["qa_approved" (fn [_] "QA approved")]
   ["qa_returned" (fn [_] "QA complete")]
   ["hardening_approved" (fn [_] "hardening approved")]
   ["hardener_returned" (fn [_] "hardener complete")]
   ["code_review_approved" (fn [_] "code review approved")]
   ["code_reviewed" (fn [p] (str "code review " (get p "code_review" "accepted")))]
   ["cleaned" (fn [_] "cleaner complete")]
   ["implemented" (fn [_] "implementation complete")]
   ["implementation_approved" (fn [_] "implementation approved")]
   ["implementation_approval_ready" (fn [_] "Gherkin and QA procedure approved")]
   ["specification_in_progress" (fn [p]
                                  (str "Gherkin " (get p "gherkin_review_state" "pending")
                                       "; QA procedure " (get p "qa_procedure_review_state" "pending")))]
   ["story_approved" (fn [_] "story approved")]
   ["story_recorded" (fn [_] "story recorded")]])

(defn stage-detail [packet state]
  (if-let [f (some (fn [[match f]]
                    (when (= match state) f))
                  stage-detail-rules)]
    (f packet)
    state))

(defn canonical-story-row [story-id packet-file]
  (let [packet (assoc (squad-state/read-kv-file packet-file) "story_id" story-id)
        state (squad-state/recompute-state packet)]
    (merge packet
           (squad-state/derived-stage-fields packet state)
           {"state" state
            "stage_label" (stage-label state)
            "stage_detail" (stage-detail packet state)})))

(defn story-number-sort-key [row]
  (let [n (get row "story_number")]
    (if (and n (re-matches #"[0-9]+" (str n)))
      (Long/parseLong (str n))
      Long/MAX_VALUE)))

(defn story-state [root]
  (let [dir (fs/path root ".squad" "stories")]
    (if (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (map (fn [story-dir]
                  (canonical-story-row (fs/file-name story-dir)
                                       (fs/path story-dir "packet"))))
           (sort-by (juxt story-number-sort-key #(get % "story_id" "")))
           vec)
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
           (filter dashboard-agent-visible?)
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

(def blocking-agent-states
  #{"blocked" "failed"})

(defn assignment-blocker-from-dir [assignment-dir]
  (let [assignment-id (fs/file-name assignment-dir)
        status (parse-kv-file (fs/path assignment-dir "status"))
        state (get status "state")
        blocker (fs/path assignment-dir "blocker")
        rejection (fs/path assignment-dir "rejection")]
    (cond
      (and (fs/regular-file? blocker)
           (= "blocked" state))
      (merge {"assignment_id" assignment-id
              "detail" (get status "detail" "")
              "kind" "assignment-block"}
             (parse-kv-file blocker))

      (and (fs/regular-file? rejection)
           (= "rejected" state))
      (merge {"assignment_id" assignment-id
              "state" "blocked"
              "kind" "assignment-rejection"
              "detail" (get status "detail" "rejected by squad leader")
              "reason_file" (str (fs/path assignment-dir "rejection.md"))
              "updated_at" (get status "updated_at" "")}
             (parse-kv-file rejection)
             (when (fs/regular-file? blocker)
               (parse-kv-file blocker)))

      :else nil)))

(defn assignment-blocker-state [root]
  (let [dir (fs/path root ".squad" "assignments")]
    (if (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (keep assignment-blocker-from-dir)
           (sort-by descending-value #(compare %2 %1))
           vec)
      [])))

(defn global-blocker-state [root]
  (let [dir (fs/path root ".squad" "blockers")]
    (if (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter #(and (fs/regular-file? %)
                         (not (str/ends-with? (fs/file-name %) ".md"))))
           (map (fn [file]
                  (merge {"blocker_id" (fs/file-name file)
                          "state" "blocked"}
                         (parse-kv-file file))))
           (filter #(= "blocked" (get % "state")))
           (sort-by descending-value #(compare %2 %1))
           vec)
      [])))

(defn agent-by-id [agents]
  (into {} (map (fn [agent] [(get agent "agent_id") agent]) agents)))

(defn agent-assignment-blocker [agents-by-id assignment]
  (let [agent-id (get assignment "agent_id")
        agent (get agents-by-id agent-id)
        state (get agent "state")]
    (when (and (contains? #{"in_progress" "handoff_sent"} (get assignment "state"))
               (contains? blocking-agent-states state))
      {"assignment_id" (get assignment "assignment_id")
       "kind" (str "agent-" state)
       "state" "blocked"
       "agent_id" agent-id
       "template" (get assignment "template")
       "detail" (get agent "detail" "")
       "updated_at" (or (get agent "updated_at") (get assignment "updated_at") "")})))

(defn agent-blocker-state [assignments agents]
  (let [agents-by-id (agent-by-id agents)]
    (->> assignments
         (keep #(agent-assignment-blocker agents-by-id %))
         (sort-by descending-value #(compare %2 %1))
         vec)))

(defn blocker-state [root assignments agents]
  (vec (concat (assignment-blocker-state root)
               (global-blocker-state root)
               (agent-blocker-state assignments agents))))

(defn handoff-inbox-count [root bucket]
  (let [dir (fs/path root ".swarmforge" "handoffs" "inbox" bucket)]
    (if (fs/directory? dir)
      (count (filter #(and (fs/regular-file? %)
                           (str/ends-with? (fs/file-name %) ".handoff"))
                     (fs/list-dir dir)))
      0)))

(defn pending-dashboard-request-count [root]
  (count (dashreq/pending-requests root)))

(defn sl-queue-depth
  "Work waiting on the squad leader: pending dashboard requests plus claimed and
  unclaimed handoff mail."
  [root]
  (+ (pending-dashboard-request-count root)
     (handoff-inbox-count root "new")
     (handoff-inbox-count root "in_process")))

(defn web-state [root]
  (let [assignments (assignment-state root)
        agents (agent-state root)
        sl-requests (dashreq/list-all-requests root)]
    {"generated_at" (now)
     "project_root" (str root)
     "stories" (story-state root)
     "assignments" assignments
     "agents" agents
     "batches" (batch-state root)
     "blockers" (blocker-state root assignments agents)
     "approvals" {"pending" (approval-state-for root "pending")}
     "sl_requests" sl-requests
     "sl_queue_depth" (sl-queue-depth root)}))

(defn response [status content-type body]
  {:status status :content-type content-type :body body})

(defn url-decode [value]
  (URLDecoder/decode value "UTF-8"))

(defn html-escape [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn artifact-page [title content]
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<title>" (html-escape title) "</title>"
       "<style>:root{font-family:ui-sans-serif,system-ui,sans-serif;color-scheme:light dark}"
       "body{margin:0;background:#f7f7f4;color:#202124}"
       "header{padding:14px 18px;border-bottom:1px solid #d9d9d2}"
       "h1{font-size:18px;margin:0}main{padding:18px}"
       "pre{white-space:pre-wrap;background:white;border:1px solid #d9d9d2;padding:14px;overflow:auto}</style>"
       "</head><body><header><h1>" (html-escape title) "</h1></header><main><pre>"
       (html-escape content)
       "</pre></main></body></html>"))

(defn root-child-file [root relative]
  (when-not (str/blank? relative)
    (let [root-path (.normalize (.toAbsolutePath (fs/path root)))
          file-path (.normalize (.toAbsolutePath (fs/path root relative)))]
      (when (and (.startsWith file-path root-path)
                 (fs/regular-file? file-path))
        file-path))))

(defn packet-file-for [root story-id]
  (fs/path root ".squad" "stories" story-id "packet"))

(defn packet-for [root story-id]
  (squad-state/read-kv-file (packet-file-for root story-id)))

(defn artifact-project-content [root relative]
  (when-let [file (root-child-file root relative)]
    (slurp (str file))))

(defn assignment-artifact-content [root assignment-id file-name]
  (let [file (fs/path root ".squad" "assignments" assignment-id file-name)]
    (when (fs/regular-file? file)
      (slurp (str file)))))

(defn review-content [root id]
  (or (let [file (fs/path root ".squad" "reviews" (str id ".md"))]
        (when (fs/regular-file? file)
          (slurp (str file))))
      (assignment-artifact-content root id "review.md")
      (assignment-artifact-content root id "review")
      (let [packet (packet-for root id)]
        (str/join "\n\n"
                  (keep (fn [[_ field]]
                          (when-let [assignment (get packet field)]
                            (review-content root assignment)))
                        [["Gherkin Review" "gherkin_review_assignment"]
                         ["QA Procedure Review" "qa_procedure_review_assignment"]
                         ["Code Review" "code_review_assignment"]
                         ["Architecture Review" "architecture_review_assignment"]])))))

(defn section [title content]
  (when-not (str/blank? content)
    (str "## " title "\n\n" content "\n")))

(defn theme-content [root theme-id]
  (let [theme (slurp-if-exists (fs/path root ".squad" "themes" theme-id "theme.md"))
        module-map (slurp-if-exists (fs/path root ".squad" "themes" theme-id "module-map.md"))]
    (str (section "Theme" theme)
         (section "Module Map" module-map))))

(defn packet-review-sections [root packet]
  (apply str
         (keep (fn [[title field]]
                 (when-let [assignment (get packet field)]
                   (section title (review-content root assignment))))
               [["Gherkin Review" "gherkin_review_assignment"]
                ["QA Procedure Review" "qa_procedure_review_assignment"]
                ["Code Review" "code_review_assignment"]
                ["Architecture Review" "architecture_review_assignment"]])))

(defn story-content [root story-id]
  (let [packet (packet-for root story-id)
        story (artifact-project-content root (get packet "story_path"))
        gherkin (artifact-project-content root (get packet "gherkin_path"))
        qa-procedure (artifact-project-content root (get packet "qa_procedure_path"))
        packet-text (slurp-if-exists (packet-file-for root story-id))]
    (str (section "Story" story)
         (section "Story Packet" packet-text)
         (section "Gherkin" gherkin)
         (section "QA Procedure" qa-procedure)
         (packet-review-sections root packet))))

(defn assignment-document-content [root assignment-id]
  (assignment-artifact-content root assignment-id "assignment.md"))

(def artifact-readers
  {"theme" theme-content
   "story" story-content
   "gherkin" (fn [root id]
               (or (artifact-project-content root (get (packet-for root id) "gherkin_path"))
                   (slurp-if-exists (packet-file-for root id))))
   "qa-procedure" (fn [root id]
                    (or (artifact-project-content root (get (packet-for root id) "qa_procedure_path"))
                        (slurp-if-exists (packet-file-for root id))))
   "review" review-content
   "assignment" assignment-document-content
   "blocker" (fn [root id]
               (or (assignment-artifact-content root id "blocker.md")
                   (assignment-artifact-content root id "blocker")
                   (assignment-artifact-content root id "rejection.md")
                   (assignment-artifact-content root id "rejection")
                   (let [global-md (fs/path root ".squad" "blockers" (str id ".md"))
                         global (fs/path root ".squad" "blockers" id)]
                     (cond
                       (fs/regular-file? global-md) (slurp (str global-md))
                       (fs/regular-file? global) (slurp (str global))
                       :else nil))))})

(defn artifact-content [root kind id]
  (when-let [reader (get artifact-readers kind)]
    (reader root id)))

(defn artifact-response [root path]
  (let [[_ kind encoded-id] (re-matches #"/artifact/([^/]+)/([^/]+)" path)
        id (url-decode encoded-id)
        title (str (str/capitalize (str/replace kind "-" " ")) " " id)]
    (if-let [content (not-empty (or (artifact-content root kind id) ""))]
      (response 200 "text/html; charset=utf-8" (artifact-page title content))
      (response 404 "text/plain; charset=utf-8" "Artifact not found\n"))))

(defn tail-section [file]
  (when (fs/regular-file? file)
    (let [[_ tail] (str/split (slurp (str file)) #"(?m)^last_10_lines:\s*\n" 2)]
      tail)))

(defn socket-value [root]
  (let [socket-file (fs/path root ".swarmforge" "tmux-socket")]
    (when (fs/regular-file? socket-file)
      (str/trim (slurp (str socket-file))))))

(defn agent-pane-content [root agent-id]
  (let [metadata (fs/path root ".squad" "agents" agent-id "metadata")
        session (read-value metadata "session")
        backend (or (read-value metadata "backend")
                    (read-value metadata "agent"))
        socket (socket-value root)]
    (or (not-empty (when (and socket (not (str/blank? session)))
                     (capture-pane-tail socket session backend)))
        (tail-section (fs/path root ".squad" "agents" agent-id "liveness"))
        "")))

(defn pane-page [agent-id]
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<title>Agent " (html-escape agent-id) "</title>"
       "<style>:root{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;color-scheme:light dark}"
       "body{margin:0;background:#111;color:#f4f4f4}header{padding:10px 12px;border-bottom:1px solid #333}"
       "h1{font:inherit;margin:0}pre{margin:0;padding:12px;white-space:pre-wrap;min-height:calc(100vh - 42px);overflow:auto}"
       "#new-output{position:fixed;right:12px;bottom:12px;background:#2f6f4e;color:white;border:0;border-radius:6px;padding:6px 10px;display:none}</style>"
       "</head><body><header><h1>" (html-escape agent-id) "</h1></header><pre id=\"pane\"></pre>"
       "<button id=\"new-output\" onclick=\"pane.scrollTop=pane.scrollHeight;this.style.display='none'\">New output</button>"
       "<script>const pane=document.getElementById('pane');let stickBottom=true;"
       "pane.addEventListener('scroll',()=>{const dist=pane.scrollHeight-pane.scrollTop-pane.clientHeight;"
       "stickBottom=dist<=24;});"
       "async function refresh(){"
       "const marker=document.getElementById('new-output');"
       "const prevHeight=pane.scrollHeight||0;"
       "const prevTop=pane.scrollTop||0;"
       "const distFromBottom=Math.max(0,prevHeight-prevTop-pane.clientHeight);"
       "const r=await fetch('/api/agents/" (html-escape agent-id) "/pane',{cache:'no-store'});"
       "const text=await r.text();if(text.length>0&&text!==pane.textContent){"
       "pane.textContent=text;"
       "requestAnimationFrame(()=>{"
       "if(stickBottom){pane.scrollTop=pane.scrollHeight;marker.style.display='none'}"
       "else{pane.scrollTop=Math.max(0,pane.scrollHeight-pane.clientHeight-distFromBottom);"
       "marker.style.display='block'}});}}"
       "refresh();setInterval(refresh,1000);</script></body></html>"))
(defn agent-pane-response [root path]
  (let [[_ encoded-id] (re-matches #"/api/agents/([^/]+)/pane" path)
        agent-id (url-decode encoded-id)]
    (response 200 "text/plain; charset=utf-8" (agent-pane-content root agent-id))))

(defn agent-page-response [_ path]
  (let [[_ encoded-id] (re-matches #"/agent/([^/]+)" path)
        agent-id (url-decode encoded-id)]
    (response 200 "text/html; charset=utf-8" (pane-page agent-id))))

(defn approval-web-action! [root approval-id action]
  (let [detail (if (= action "approve") "approved-by-web" "rejected-by-web")
        result (process/sh {:continue true :dir (str root)}
                           (str (fs/path (script-dir) "squad_approval.sh"))
                           action
                           approval-id
                           detail)]
    (if (zero? (:exit result))
      (do
        (when-let [socket (socket-value root)]
          (tmux-notify! socket "swarmforge-squad-leader" approval-wake-message))
        {:ok true :output (:out result)})
      {:ok false :status 409 :error (str (:err result) (:out result))})))

(defn web-error [message]
  {:ok false :status 409 :error message})

(defn json-unescape [s]
  (-> (or s "")
      (str/replace #"\\n" "\n")
      (str/replace #"\\t" "\t")
      (str/replace #"\\\"" "\"")
      (str/replace #"\\\\" "\\")))

(defn extract-json-string [json key]
  (when-let [[_ raw] (re-find (re-pattern (str "\"" key "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""))
                              (or json ""))]
    (json-unescape raw)))

(defn parse-sl-request-body [body]
  "Accept JSON {body} (optional legacy kind ignored) or plain text body."
  (let [text (str/trim (or body ""))]
    (if (str/starts-with? text "{")
      {:kind "request"
       :body (or (extract-json-string text "body") "")}
      {:kind "request"
       :body text})))

(defn dashboard-request-wake-message [request]
  (let [id (get request "id")
        kind (get request "kind" "command")
        body (get request "body" "")]
    (str "Dashboard request pending. Run squad_next.sh --apply-mechanical and answer it.\n"
         "REQUEST_ID: " id "\n"
         "KIND: " kind "\n"
         "BODY: " body "\n"
         "COMMAND: squad_dashboard_request.sh answer " id " <answer-file>\n"
         "A request is not complete until the helper succeeds.")))

(defn wake-sl-for-request! [root request]
  (if-let [socket (socket-value root)]
    (if (tmux-notify! socket "swarmforge-squad-leader" (dashboard-request-wake-message request))
      (do
        (log! root "web-sl-request-created" (get request "id"))
        true)
      false)
    false))

(defn create-sl-request-action! [root body]
  (let [{:keys [kind body]} (parse-sl-request-body body)
        result (dashreq/create-request root {:kind kind :body body})]
    (if-not (:ok result)
      (web-error (str (:error result) "\n"))
      (let [request (:request result)
            woke? (wake-sl-for-request! root request)]
        (if woke?
          {:ok true :request request}
          ;; Durable create still succeeds if wake fails — operator can retry / SL sees next.
          (do
            (log! root "web-sl-request-wake-failed" (get request "id"))
            {:ok true :request request :wake_failed true}))))))

(defn cancel-sl-request-action! [root request-id]
  (let [result (dashreq/cancel-request root request-id)]
    (if (:ok result)
      (do
        (log! root "web-sl-request-cancelled" request-id)
        {:ok true :request (:request result)})
      (web-error (str (:error result) "\n")))))

(defn state-response [root]
  (response 200 "application/json; charset=utf-8" (to-json (web-state root))))

(defn approval-response [root path]
  (let [[_ encoded-id action] (re-matches #"/api/approvals/([^/]+)/(approve|reject)" path)
        result (approval-web-action! root (url-decode encoded-id) action)]
    (if (:ok result)
      (response 200 "application/json; charset=utf-8" (to-json {"ok" true}))
      (response (:status result) "text/plain; charset=utf-8" (:error result)))))

(defn sl-requests-list-response [root]
  (response 200 "application/json; charset=utf-8"
            (to-json {"requests" (dashreq/list-all-requests root)})))

(defn sl-request-create-response [root body]
  (let [result (create-sl-request-action! root body)]
    (if (:ok result)
      (response 200 "application/json; charset=utf-8"
                (to-json {"ok" true
                          "id" (get-in result [:request "id"])
                          "request" (:request result)}))
      (response (:status result 409) "text/plain; charset=utf-8" (:error result)))))

(defn sl-request-cancel-response [root path]
  (let [[_ encoded-id] (re-matches #"/api/sl-requests/([^/]+)/cancel" path)
        result (cancel-sl-request-action! root (url-decode encoded-id))]
    (if (:ok result)
      (response 200 "application/json; charset=utf-8" (to-json {"ok" true}))
      (response (:status result 409) "text/plain; charset=utf-8" (:error result)))))

(defn sl-message-response [root body]
  "Compatibility wrapper: plain-text message becomes a command request."
  (sl-request-create-response root body))

(defn blocker-resolve-response [_ path]
  "Dashboard one-click resolve removed: clearing approval-rejection blockers is
  an SL CLI path (squad_approval.sh resolve-rejection) after operator recovery."
  (response 405 "text/plain; charset=utf-8"
            (str "Dashboard Resolve is disabled. Clear durable blockers with: "
                 "squad_approval.sh resolve-rejection <approval-id> <detail> "
                 "after operator and squad-leader recovery work. Path=" path "\n")))

(def web-routes
  [{:method "GET"
    :path "/"
    :handler (fn [_ _ _] (response 200 "text/html; charset=utf-8" dashboard-html))}
   {:method "GET"
    :path "/api/state"
    :handler (fn [root _ _] (state-response root))}
   {:method "GET"
    :path "/api/sl-requests"
    :handler (fn [root _ _] (sl-requests-list-response root))}
   {:method "GET"
    :pattern #"/artifact/[^/]+/[^/]+"
    :handler (fn [root path _] (artifact-response root path))}
   {:method "GET"
    :pattern #"/agent/[^/]+"
    :handler (fn [root path _] (agent-page-response root path))}
   {:method "GET"
    :pattern #"/api/agents/[^/]+/pane"
    :handler (fn [root path _] (agent-pane-response root path))}
   {:method "POST"
    :pattern #"/api/approvals/[^/]+/(approve|reject)"
    :handler (fn [root path _] (approval-response root path))}
   {:method "POST"
    :pattern #"/api/blockers/[^/]+/resolve"
    :handler (fn [root path _] (blocker-resolve-response root path))}
   {:method "POST"
    :path "/api/sl-requests"
    :handler (fn [root _ body] (sl-request-create-response root body))}
   {:method "POST"
    :pattern #"/api/sl-requests/[^/]+/cancel"
    :handler (fn [root path _] (sl-request-cancel-response root path))}
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
