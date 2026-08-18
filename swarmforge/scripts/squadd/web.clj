#!/usr/bin/env bb

(ns squadd.web
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [squad-dashboard-request :as dashreq]
            [squad-records :as rec]
            [squad-sprint :as sprint]
            [squad-state :as squad-state]
            [stop-squadd :as stop-squadd])
  (:import [java.net InetAddress ServerSocket URLDecoder]))

(def approval-wake-message
  "A web approval changed state. If idle, run squad_next.sh --residual-only and handle only residual judgment or user-facing work. The daemon owns merge-ready/accept-merge and other mechanical applies.")

(defn dashboard-html-path []
  "Prefer dashboard.html beside this script (squadd/dashboard.html)."
  (let [candidates (cond-> []
                     *file* (conj (fs/path (fs/parent *file*) "dashboard.html"))
                     true (conj (fs/path (System/getProperty "user.dir")
                                         "swarmforge" "scripts" "squadd" "dashboard.html")))]
    (first (filter #(and % (fs/regular-file? %)) candidates))))

(def dashboard-html
  "Live combined cockpit (B24/B35). Source: squadd/dashboard.html — see ui-design.md
  and dashboard-mockup.html for behavior."
  (if-let [p (dashboard-html-path)]
    (slurp (str p))
    (str "<!doctype html><html><body><h1>Missing squadd/dashboard.html</h1>"
         "<p>Install swarmforge/scripts/squadd/dashboard.html next to web.clj.</p>"
         "</body></html>")))

(def web-terminal-assignment-states
  "Assignments in these states are finished for dashboard purposes and hidden
  from the default active list. Everything else (including merge_blocked) stays visible."
  #{"merged" "rejected" "superseded" "cancelled" "abandoned" "replacement_created"
    "retired" "review_accepted" "review_changes_requested"})

(defn web-active-assignment?
  "True when the assignment should appear on the active dashboard list."
  [state]
  (let [s (or state "unknown")]
    (not (contains? web-terminal-assignment-states s))))

(def dashboard-hidden-agent-ids
  "Persistent operator roles shown elsewhere (Troubleshooter chat), not in fleet list."
  #{"troubleshooter"})

(defn dashboard-agent-visible?
  "Transient fleet agents appear from spawn until retirement. Temporary states
  such as failed must remain visible while registered. Persistent operator
  roles (Troubleshooter) are omitted — they are not product workers."
  [agent]
  (let [id (or (get agent "agent_id") "")
        template (or (get agent "template") "")]
    (and (not= "retired" (get agent "state"))
         (not (contains? dashboard-hidden-agent-ids id))
         (not (contains? dashboard-hidden-agent-ids template)))))
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

(defn tmux-notify!
  "Inject a short wake line into an agent pane. Keep messages tiny: long pastes
  force a slow full model turn for every operator chat (measured ~8s for 'hi')."
  [socket session message]
  (let [send-text (sh "tmux" "-S" socket "send-keys" "-t" session "-l" message)
        _ (Thread/sleep 30)
        send-return (sh "tmux" "-S" socket "send-keys" "-t" session "C-m")
        _ (Thread/sleep 30)
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

(def pane-capture-lines
  "How many history lines to mirror in the dashboard agent pane (B15)."
  2000)

(defn capture-pane-tail
  ([socket session] (capture-pane-tail socket session nil))
  ([socket session backend]
   (strip-input-region
    (:out (sh-continue "tmux" "-S" socket "capture-pane" "-p" "-t" session
                       "-S" (str "-" pane-capture-lines)))
    backend)))
(defn parse-kv-file
  "Flat key:value status/metadata files via squad-records (B40)."
  [file]
  (rec/read-kv-file file))

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
  ;; B67: early Specifying pills are distinct (written / approved / in-process)
  {"story_recorded" "written"
   "story_approved" "approved"
   "specification_in_progress" "in-process"
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
           (filter #(web-active-assignment? (get % "state")))
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

(defn- normalize-gate [gate]
  (-> (or gate "")
      str/trim
      str/lower-case
      (str/replace "_" "-")))

(defn approval-document-ref
  "Map an approval record to the dashboard artifact the operator should read
  before Approve/Reject. Always returns url+label when target_id is present."
  [{:strs [target_kind target_id gate story_id theme_id] :as _approval}]
  (let [kind (str/lower-case (str/trim (or target_kind "")))
        tid (or (not-empty target_id)
                (not-empty story_id)
                (not-empty theme_id)
                "")
        gate-n (normalize-gate gate)
        enc (fn [s] (java.net.URLEncoder/encode (str s) "UTF-8"))
        story-url (fn [path-kind id]
                    (str "/artifact/" path-kind "/" (enc id)))
        theme-url (fn [id & [hash]]
                    (str "/artifact/theme/" (enc id)
                         (when hash (str "#" hash))))]
    (cond
      (str/blank? tid)
      {"document_url" ""
       "document_label" "View document"}

      (= kind "theme")
      (case gate-n
        "implementation-order"
        {"document_url" (theme-url tid "implementation-order")
         "document_label" "View implementation order"}
        "dependency-checker"
        {"document_url" (theme-url tid "dependency-checker")
         "document_label" "View dependency checker"}
        "module-map"
        {"document_url" (theme-url tid "module-map")
         "document_label" "View module map"}
        ("finalize" "theme-finalize")
        {"document_url" (theme-url tid)
         "document_label" "View theme package"}
        ;; theme gate and any other theme-scoped gate
        {"document_url" (theme-url tid)
         "document_label" "View theme package"})

      ;; story target (default) and unknown kinds fall through to story package
      :else
      (case gate-n
        "gherkin"
        {"document_url" (story-url "gherkin" tid)
         "document_label" "View gherkin"}
        ("qa-procedure")
        {"document_url" (story-url "qa-procedure" tid)
         "document_label" "View QA procedure"}
        ("code-review")
        {"document_url" (story-url "review" tid)
         "document_label" "View code review"}
        "architecture"
        {"document_url" (story-url "review" tid)
         "document_label" "View architecture review"}
        "story"
        {"document_url" (story-url "story" tid)
         "document_label" "View story"}
        ;; implementation, hardening, qa, final, …
        {"document_url" (story-url "story" tid)
         "document_label" "View story package"}))))

(defn enrich-approval-document
  "Attach document_url / document_label for Attention strip links."
  [approval]
  (merge approval (approval-document-ref approval)))

(defn approval-state-for [root state]
  (->> (state-files (fs/path root ".squad" "approvals" state) ".approval")
       (mapv (comp enrich-approval-document parse-kv-file))))

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

(defn current-theme-id [root]
  "Prefer the most recently updated theme directory under .squad/themes."
  (let [dir (fs/path root ".squad" "themes")]
    (when (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (sort-by (fn [d]
                      (or (when-let [st (fs/path d "status")]
                            (when (fs/regular-file? st)
                              (fs/last-modified-time st)))
                          (fs/last-modified-time d)))
                    #(compare %2 %1))
           first
           fs/file-name))))

(defn troubleshooter-session-name []
  "swarmforge-troubleshooter")

(defn troubleshooter-agent-working?
  "True when Troubleshooter agent status/heartbeat/liveness say it is busy.
  Persistent Troubleshooter sessions often omit these files; callers should
  also treat pending dashboard requests as working."
  [root]
  (let [status (parse-kv-file (fs/path root ".squad" "agents" "troubleshooter" "status"))
        heartbeat (parse-kv-file (fs/path root ".squad" "agents" "troubleshooter" "heartbeat"))
        liveness (parse-kv-file (fs/path root ".squad" "agents" "troubleshooter" "liveness"))
        state (or (get status "state") (get heartbeat "state") "")
        pane-idle (get liveness "pane_idle_prompt")]
    (and (not (str/blank? state))
         (not (#{"retired" "idle" "handoff_sent"} state))
         (not= "true" pane-idle))))

(defn troubleshooter-working?
  "True when the dashboard busy indicator should show.
  Primary signal: pending operator request (chat wait). Secondary: agent
  status/liveness when present (daemon-style workers)."
  [root]
  (or (pos? (pending-dashboard-request-count root))
      (troubleshooter-agent-working? root)))

(defn merge-error-snippet [root assignment-id]
  (let [file (fs/path root ".squad" "assignments" assignment-id "merge-error")]
    (when (fs/regular-file? file)
      (->> (str/split-lines (slurp (str file)))
           (remove str/blank?)
           (take 2)
           (str/join " | ")
           not-empty))))

(defn assignment-by-id [assignments]
  (into {} (map (fn [a] [(get a "assignment_id") a]) assignments)))

(defn recoverable-merge-block?
  "B63: merge_blocked is auto-recovered by merger path — not a TS stall."
  [assignment]
  (= "merge_blocked" (get assignment "state")))

(defn assignment-stall-item [root assignment]
  "B63: only stalls that need TS/operator intervention.
  Recoverable merge_blocked is excluded (merger pipeline handles it)."
  (let [state (get assignment "state")
        id (get assignment "assignment_id")
        detail (str/trim (or (get assignment "detail") ""))]
    (cond
      ;; B63: do not Attention-stall auto-recoverable merges
      (recoverable-merge-block? assignment)
      nil

      (= "blocked" state)
      {"kind" "assignment"
       "id" id
       "state" state
       "reason" (or (not-empty detail) "assignment blocked")}

      ;; Max-depth / hard merge stops often use detail language; surface as stall
      (and (str/includes? (str/lower-case detail) "max_merger_depth")
           (not (str/blank? detail)))
      {"kind" "assignment"
       "id" id
       "state" (or state "blocked")
       "reason" detail}

      :else nil)))

(defn agent-stall-item [agent]
  (let [state (get agent "state")
        id (get agent "agent_id")
        detail (str/trim (or (get agent "detail") ""))]
    (when (contains? #{"blocked" "failed"} state)
      {"kind" "agent"
       "id" id
       "state" state
       "reason" (or (not-empty detail) (str "agent " state))})))

(defn held-handoff-stall-items
  "B63: held handoffs for recoverable merge_blocked tasks are not stalls."
  [root assignments]
  (let [by-id (assignment-by-id assignments)
        dir (fs/path root ".swarmforge" "handoffs" "inbox" "held")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".handoff")))
           (keep (fn [file]
                   (let [m (parse-kv-file file)
                         from (get m "from" "unknown")
                         task (get m "task" (get m "assignment" ""))
                         a (get by-id task)]
                     (when-not (and a (recoverable-merge-block? a))
                       {"kind" "held_handoff"
                        "id" (fs/file-name file)
                        "state" "held"
                        "reason" (str "held handoff from " from
                                      (when-not (str/blank? task)
                                        (str " task=" task)))}))))
           vec)
      [])))

(defn stall-report
  "B29/B63: operator-facing stalls = needs TS/operator, not auto-merger recovery."
  [root assignments agents]
  (let [items (vec
               (concat (keep #(assignment-stall-item root %) assignments)
                       (keep agent-stall-item agents)
                       (held-handoff-stall-items root assignments)))
        summary (if (seq items)
                  (str (count items) " stalled — "
                       (->> items
                            (map #(str (get % "kind") " " (get % "id")
                                       ": " (get % "reason")))
                            (take 3)
                            (str/join "; ")
                            (#(if (> (count items) 3)
                                (str % " …")
                                %))))
                  "")]
    {"stalled" (boolean (seq items))
     "count" (count items)
     "items" items
     "summary" summary}))

;;; --- Board columns + backlog (B24 / B35) ---

(def board-column-by-state
  "Map packet state → board column (B47 Finalizing, B62 Specifying/Coding).
  No Ready column: implementation_approved is Coding; implementation_approval_ready is Specifying.
  code_review_approved is Finalizing."
  {"final_approved" "done"
   ;; B100: story Done after QA; architecture/senior-impl are project-level
   "architecture_approved" "done"
   "architecture_reviewed" "done"
   "architecture_revision_returned" "done"
   "architecture_returned" "done"
   "qa_approved" "done"
   "final_approval_ready" "done"
   "senior_implementer_returned" "done"
   ;; Finalizing: post-CR through QA result, before QA success
   "qa_returned" "finalizing"
   "hardening_approved" "finalizing"
   "hardener_returned" "finalizing"
   "code_review_approved" "finalizing"
   ;; Coding: approved to implement through code review result
   "code_reviewed" "coding"
   "cleaned" "coding"
   "implemented" "coding"
   "implementation_approved" "coding"
   ;; Specifying (was Specified + ready gate)
   "implementation_approval_ready" "specifying"
   "specification_in_progress" "specifying"
   "story_approved" "specifying"
   "story_recorded" "specifying"
   ;; legacy aliases if old clients send them
   "specified" "specifying"
   "ready" "coding"})

(defn board-column [state]
  (get board-column-by-state state "specifying"))

(def pipeline-stage-rank
  "Higher = later progress (B46/B48). Used for WIP and in-column card sort."
  {"story_recorded" 10
   "story_approved" 20
   "specification_in_progress" 30
   "implementation_approval_ready" 40
   "implementation_approved" 50
   "implemented" 60
   "cleaned" 70
   "code_reviewed" 80
   "code_review_approved" 90
   "hardener_returned" 95
   "hardening_approved" 100
   "qa_returned" 105
   "qa_approved" 110
   "architecture_returned" 115
   "architecture_revision_returned" 118
   "architecture_reviewed" 120
   "architecture_approved" 130
   "senior_implementer_returned" 135
   "final_approval_ready" 140
   "final_approved" 200})

(def wif-role-rank
  "Later pipeline roles rank higher within WIF (B46)."
  {"analyst" 10
   "gherkin-writer" 20
   "gherkin-reviewer" 25
   "qa-procedure-writer" 30
   "qa-procedure-reviewer" 35
   "implementer" 50
   "cleaner" 55
   "code-reviewer" 60
   "hardener" 70
   "qa" 80
   "architect" 90
   "senior-implementer" 100
   "merger" 110})

(def wif-assignment-state-rank
  "Later assignment lifecycle ranks higher (B46). Prevents created above in_progress
  when role ranks match and created has a newer updated_at."
  {"created" 10
   "starting" 15
   "assigned" 20
   "queued" 22
   "blocked" 25
   "failed" 28
   "in_progress" 40
   "running" 42
   "merge_blocked" 45
   "handoff_ready" 50
   "handoff_sent" 55
   "merge_ready" 60
   "merging" 65
   "result_received" 70})

(defn pipeline-rank
  "Numeric progress rank for sort (higher = later). Unknowns sort low."
  [state-or-role]
  (let [s (str (or state-or-role ""))]
    (or (get pipeline-stage-rank s)
        (get wif-role-rank s)
        (get wif-role-rank (str/replace s "_" "-"))
        (get wif-assignment-state-rank s)
        0)))

(defn assignment-progress-rank
  "WIF sort key component: assignment lifecycle (higher = later progress)."
  [state]
  (get wif-assignment-state-rank (str state) 0))

(defn story-board-row [story]
  (let [state (get story "state" "story_recorded")]
    (assoc story
           "board_column" (board-column state)
           "pipeline_rank" (pipeline-rank state)
           "created_at" (or (get story "created_at") (get story "story_recorded_at") "")
           "updated_at" (or (get story "updated_at") ""))))

(defn enrich-story-holds
  "B60: temporary hold reason for soft-red card outline + hover."
  [stories assignments]
  (let [coding-impl-states #{"created" "starting" "in_progress" "running"
                             "handoff_ready" "handoff_sent" "merge_ready"
                             "merge_blocked" "merging"}
        active-impl (set (for [a assignments
                               :when (and (= "implementer" (get a "template" ""))
                                          (contains? coding-impl-states (get a "state" "")))]
                           (get a "story_id")))
        merge-blocked (into {}
                            (for [a assignments
                                  :when (= "merge_blocked" (get a "state"))]
                              [(get a "story_id")
                               (or (not-empty (get a "detail"))
                                   "dry-run or accept-merge failed")]))]
    (mapv (fn [s]
            (let [id (get s "story_id")
                  st (get s "state")]
              (cond
                (get merge-blocked id)
                (assoc s "hold" true
                       "hold_reason" (str "Merge blocked: " (get merge-blocked id)))

                (and (= "implementation_approved" st)
                     (not (contains? active-impl id)))
                (assoc s "hold" true
                       "hold_reason"
                       "Approved to implement but not started — may be waiting on implementation-order or capacity")

                :else s)))
          stories)))

(defn pane-sample-for-hash
  "B95: drop the last non-empty line (timer/status widgets) before hashing."
  [text]
  (let [trimmed (str/replace (str text) #"\s+\z" "")
        lines (str/split-lines trimmed)]
    (if (<= (count lines) 1)
      ""
      (str/join "\n" (pop (vec lines))))))

(def sl-activity-atom
  "B56: last SL pane sample for heat decay across polls."
  (atom {:hash nil :heat 0}))

(def agent-activity-atom
  "B66: last pane hash/heat per agent_id for WIF thermometers."
  (atom {}))

(declare sl-activity socket-value agent-session-name)

(defn agent-pane-heat
  "B66/B84: heat 0–6 for one agent pane (observe only; six-bar WIF therm)."
  [root agent-id]
  (when-not (str/blank? agent-id)
    (let [socket (socket-value root)
          session (agent-session-name root agent-id)
          live? (and socket session
                     (zero? (:exit (sh-continue "tmux" "-S" socket "has-session" "-t" session))))
          text (when live?
                 (:out (sh-continue "tmux" "-S" socket "capture-pane" "-p" "-t" session "-S" "-20")))
          h (when text (str (hash (pane-sample-for-hash text))))
          prev (get @agent-activity-atom agent-id)
          heat (cond
                 (not live?) 0
                 (nil? h) 0
                 (nil? (:hash prev)) 1
                 (not= h (:hash prev)) (min 6 (inc (long (or (:heat prev) 0))))
                 :else (max 0 (dec (long (or (:heat prev) 0)))))
          level (case (long heat)
                  0 "idle"
                  1 "quiet"
                  2 "warm"
                  3 "busy"
                  4 "brisk"
                  5 "hot"
                  "max")]
      (swap! agent-activity-atom assoc agent-id {:hash h :heat heat})
      {"level" level "heat" heat "session_live" (boolean live?)})))

(defn batch-manifest-members [root batch-id]
  (let [manifest (fs/path root ".squad" "batches" batch-id "manifest.tsv")]
    (if (fs/regular-file? manifest)
      (->> (str/split-lines (slurp (str manifest)))
           rest
           (map #(first (str/split % #"\t" -1)))
           (remove str/blank?)
           vec)
      [])))

(defn batches-enriched [root]
  (->> (batch-state root)
       (mapv (fn [b]
               (let [id (get b "batch_id" "")
                     members (batch-manifest-members root id)
                     kind (or (get b "template") (get b "kind") "batch")]
                 (assoc b
                        "members" members
                        "member_count" (count members)
                        "batch_kind" kind))))))

(defn parent-batch-id
  "B80: map rework batch ids (e.g. htw-architecture-fix) to parent batch."
  [batch-id]
  (when-not (str/blank? batch-id)
    (or (second (re-matches #"(.+)-fix$" batch-id))
        (second (re-matches #"(.+)-r\d+$" batch-id)))))

(defn resolve-wif-batch
  "B80: find batch record for assignment, including parent of -fix / -rN ids."
  [batch-by-id a]
  (let [id (get a "assignment_id" "")
        bid (or (get a "batch_id") "")]
    (or (get batch-by-id id)
        (get batch-by-id bid)
        (when-let [parent (parent-batch-id bid)]
          (get batch-by-id parent))
        (when-let [parent (parent-batch-id id)]
          (get batch-by-id parent)))))

(defn strip-theme-project-prefix
  "B91: theme.md H1 is often '# Theme: HTW' — never show that prefix."
  [s]
  (str/trim (str/replace (str s) #"(?i)^(theme|project)\s*:\s*" "")))

(defn theme-display-name
  "B83/B88/B91: human project label from theme.md title or theme_id."
  [root theme-id]
  (when-not (str/blank? theme-id)
    (let [theme-md (fs/path root ".squad" "themes" theme-id "theme.md")
          from-file (when (fs/regular-file? theme-md)
                      (when-let [line (first (filter #(re-find #"(?i)^#\s+" %)
                                                     (str/split-lines (slurp (str theme-md)))))]
                        (strip-theme-project-prefix
                         (str/replace line #"(?i)^#\s+" ""))))]
      (or (not-empty from-file) theme-id))))

(defn inferred-story-slug
  "B91: recover a story token from assignment id when story_id is still 'theme'."
  [assignment-id theme-id]
  (let [id (str assignment-id)
        tid (str theme-id)
        stripped (-> id
                     (str/replace #"(?i)^(analyst|specifier)-" "")
                     (str/replace (re-pattern (str "(?i)^" (java.util.regex.Pattern/quote tid) "-")) "")
                     (str/replace #"(?i)-analysis(-.*)?$" "")
                     (str/replace #"(?i)^analysis$" ""))]
    (when (and (not (str/blank? stripped))
               (not= (str/lower-case stripped) (str/lower-case tid)))
      stripped)))

(defn wif-story-label
  "B83/B91: project:story when a story is known; project name only for whole-project work."
  [root a members batch-kind]
  (let [id (get a "assignment_id" "")
        story (get a "story_id" "")
        theme-id (get a "theme_id" "")
        scope (get a "scope" "")
        project (or (theme-display-name root theme-id)
                    (not-empty theme-id))]
    (cond
      (seq members)
      (str (or batch-kind "batch") " ×" (count members))

      (or (= "theme" story)
          (= "theme" scope)
          (= "Theme" story)
          (= "project" story))
      (if-let [inferred (inferred-story-slug id theme-id)]
        (str (or theme-id project) ":" inferred)
        (or project id))

      (or (str/blank? story) (= "batch" story))
      id

      (and (not (str/blank? theme-id))
           (not= story theme-id))
      (str theme-id ":" story)

      :else
      story)))

(defn work-in-flight-rows
  "Active assignments as WIF table rows; batch assignments include members.
  B46: later progress on top —
  1) assignment lifecycle (in_progress > created, …)
  2) pipeline role (implementer > gherkin-writer, …)
  3) updated_at newest first.
  B83: pass root for theme display names."
  ([assignments batches] (work-in-flight-rows nil assignments batches))
  ([root assignments batches]
   (let [batch-by-id (into {} (map (fn [b] [(get b "batch_id") b]) batches))]
     (->> assignments
          (map (fn [a]
                 (let [id (get a "assignment_id" "")
                       story (get a "story_id" "")
                       template (get a "template" "")
                       state (get a "state" "")
                       b (resolve-wif-batch batch-by-id a)
                       members (or (get b "members") [])
                       label (wif-story-label root a members (get b "batch_kind"))
                       ;; Never highlight the literal story_id "batch" / "theme"
                       story-ids (if (seq members)
                                   members
                                   (if (or (str/blank? story)
                                           (= "batch" story)
                                           (= "theme" story))
                                     []
                                     [story]))
                       agent (or (get a "agent_id") "")]
                   {"assignment_id" id
                    "story" label
                    "story_id" story
                    "story_ids" story-ids
                    "is_batch" (boolean (seq members))
                    "batch_id" (or (get b "batch_id") (when (seq members) id) "")
                    "members" members
                    "role" template
                    "state" state
                    "updated_at" (get a "updated_at" "")
                    "agent_id" agent
                    "state_rank" (assignment-progress-rank state)
                    "pipeline_rank" (pipeline-rank template)})))
          (sort-by (fn [row]
                     [(- (long (or (get row "state_rank") 0)))
                      (- (long (or (get row "pipeline_rank") 0)))
                      (str (get row "updated_at" ""))])
                   (fn [[sa ra ua] [sb rb ub]]
                     (let [c (compare sa sb)]
                       (if-not (zero? c)
                         c
                         (let [c2 (compare ra rb)]
                           (if-not (zero? c2) c2 (compare ub ua)))))))
          vec))))

(defn product-pending-label
  "B71: short label for pending product/SL dashboard request."
  [sl-requests]
  (when-let [req (some (fn [r]
                         (when (and (= "pending" (get r "status"))
                                    (let [o (get r "owner" "")]
                                      (or (str/blank? o)
                                          (= "squad-leader" o)
                                          (= "product" o))))
                           r))
                       sl-requests)]
    (or (not-empty (get req "title"))
        (not-empty (get req "id"))
        "pending")))

(defn residual-snapshot
  "B71: prefer daemon-written residual file; else nil."
  [root]
  (let [f (fs/path root ".swarmforge" "daemon" "residual-next")]
    (when (fs/regular-file? f)
      (not-empty (str/trim (slurp (str f)))))))

(defn dashboard-next-action
  "Cheap FSM-ish label for header (B51) — no full residual scan.
  B71: when residual snapshot exists, prefer it over product-request heuristic alone."
  [{:strs [approvals stalls sl_requests agents] :as state}]
  (let [snap (residual-snapshot (get state "project_root" "."))
        pending-appr (seq (get approvals "pending"))
        stalled (get stalls "stalled")
        pending-req (some #(= "pending" (get % "status")) sl_requests)
        active (some (fn [a]
                       (let [s (get a "state" "")]
                         (and (not= s "retired")
                              (not= s "idle")
                              (not (str/blank? s)))))
                     agents)]
    (or snap
        (cond
          pending-req "answer_dashboard_request"
          pending-appr "user_approval"
          stalled "investigate_stall"
          active "wait_active_agents"
          :else "idle"))))
(defn backlog-dir [root]
  (fs/path root ".squad" "backlog"))

(defn backlog-file [root id]
  (fs/path (backlog-dir root) (str id ".item")))

(defn backlog-valid-id? [id]
  (and (string? id)
       (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" id)
       (not (str/includes? id ".."))))

(defn next-backlog-id [root]
  (let [base (str "bl-" (.format java.time.format.DateTimeFormatter/BASIC_ISO_DATE
                                  (java.time.LocalDate/now)))]
    (loop [n 1]
      (let [id (format "%s-%03d" base n)]
        (if (fs/regular-file? (backlog-file root id))
          (recur (inc n))
          id)))))

(defn write-backlog-item! [root item]
  (let [id (get item "id")
        body (get item "body" "")
        ;; body as multiline block
        content (str "id: " id "\n"
                     "title: " (str/replace (str (get item "title" "")) #"\R+" " ") "\n"
                     "status: " (get item "status" "open") "\n"
                     "created_at: " (get item "created_at") "\n"
                     "updated_at: " (get item "updated_at") "\n"
                     (when-let [req (not-empty (get item "request_id"))]
                       (str "request_id: " req "\n"))
                     (when-let [th (not-empty (get item "theme_id"))]
                       (str "theme_id: " th "\n"))
                     (when-let [st (not-empty (get item "story_id"))]
                       (str "story_id: " st "\n"))
                     "body: |\n"
                     (->> (str/split-lines (str body))
                          (map #(str "  " %))
                          (str/join "\n"))
                     "\n")]
    (fs/create-dirs (backlog-dir root))
    (write-atomic! (backlog-file root id) content)
    item))

(defn parse-backlog-item [file]
  (let [text (slurp (str file))
        lines (str/split-lines text)
        headers (atom {})
        body-lines (atom [])
        in-body? (atom false)]
    (doseq [line lines]
      (cond
        @in-body?
        (swap! body-lines conj (if (str/starts-with? line "  ") (subs line 2) line))

        (str/starts-with? line "body: |")
        (reset! in-body? true)

        :else
        (when-let [[_ k v] (re-matches #"^([A-Za-z0-9_]+): (.*)$" line)]
          (swap! headers assoc k v))))
    (assoc @headers "body" (str/join "\n" @body-lines))))

(defn list-backlog [root]
  (let [dir (backlog-dir root)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".item")))
           (map parse-backlog-item)
           (sort-by #(get % "updated_at" "") #(compare %2 %1))
           vec)
      [])))

(defn get-backlog [root id]
  (when (backlog-valid-id? id)
    (let [f (backlog-file root id)]
      (when (fs/regular-file? f)
        (parse-backlog-item f)))))

(defn create-backlog! [root {:keys [title body]}]
  (let [title (str/trim (or title ""))
        body (or body "")]
    (cond
      (str/blank? title) {:ok false :error "title required" :status 400}
      :else
      (let [now (now)
            id (next-backlog-id root)
            item {"id" id
                  "title" title
                  "body" body
                  "status" "open"
                  "created_at" now
                  "updated_at" now}]
        (write-backlog-item! root item)
        {:ok true :item item}))))

(defn update-backlog! [root id {:keys [title body status]}]
  (if-let [item (get-backlog root id)]
    (let [updated (cond-> (assoc item "updated_at" (now))
                    (some? title) (assoc "title" (str/trim title))
                    (some? body) (assoc "body" body)
                    (some? status) (assoc "status" status))]
      (if (str/blank? (get updated "title"))
        {:ok false :error "title required" :status 400}
        (do (write-backlog-item! root updated)
            {:ok true :item updated})))
    {:ok false :error "not found" :status 404}))

(defn delete-backlog! [root id]
  (if-let [item (get-backlog root id)]
    (do (write-backlog-item! root (assoc item
                                         "status" "cancelled"
                                         "updated_at" (now)))
        (fs/delete-if-exists (backlog-file root id))
        {:ok true})
    {:ok false :error "not found" :status 404}))

(defn approve-backlog!
  "Mark item dispatched and open a product request owned by squad-leader.
  SL decides theme vs story (ui-design.md).
  B53: do not embed bare `key: value` header-shaped lines in the body text;
  free-text labels keep the full product description through B10 re-parse."
  [root id]
  (if-let [item (get-backlog root id)]
    (let [title (get item "title" id)
          body (get item "body" "")
          msg (str "PRODUCT BACKLOG APPROVED FOR ANALYSIS\n"
                   "Backlog id = " id "\n"
                   "Title = " title "\n\n"
                   body "\n\n"
                   "Operator approved this backlog item for analysis.\n"
                   "Squad Leader: classify as a NEW THEME or a STORY on an existing theme, "
                   "then drive analyst/theme workflow. Do not ask the operator to re-classify.")
          created (dashreq/create-request root {:kind "request"
                                                :body msg
                                                :owner dashreq/product-owner})]
      (if-not (:ok created)
        {:ok false :error (:error created) :status 400}
        (let [req-id (get-in created [:request "id"])
              updated (assoc item
                             "status" "dispatched"
                             "request_id" req-id
                             "updated_at" (now))]
          (write-backlog-item! root updated)
          (log! root "backlog-approved" id req-id)
          {:ok true :item updated :request (:request created)})))
    {:ok false :error "not found" :status 404}))

(defn extract-json-field [body key]
  (or (when-let [m (re-find (re-pattern (str "(?s)\"" key "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")) body)]
        (-> (second m)
            (str/replace #"\\n" "\n")
            (str/replace #"\\\"" "\"")
            (str/replace #"\\\\" "\\")))
      (when-let [m (re-find (re-pattern (str "(?s)\"" key "\"\\s*:\\s*\"([^\"]*)\"")) body)]
        (second m))))

(defn slug-id [s]
  (let [x (-> (str/lower-case (str (or s "")))
              (str/replace #"[^a-z0-9]+" "-")
              (str/replace #"^-|-$" ""))]
    (when (re-matches sprint/valid-id x) x)))

(defn story-title [root story-id]
  (let [f (fs/path root "stories" (str story-id ".md"))]
    (if (fs/regular-file? f)
      (or (some (fn [line]
                  (when (str/starts-with? line "# ")
                    (str/trim (subs line 2))))
                (str/split-lines (slurp (str f))))
          story-id)
      story-id)))

(defn project-map [root]
  (let [f (fs/path root ".squad" "project")]
    (if (fs/regular-file? f)
      {"id" (or (sprint/read-field f "id") "")
       "name" (or (sprint/read-field f "name") "")
       "theme_id" (or (sprint/read-field f "theme_id") "")}
      (when-let [tid (current-theme-id root)]
        {"id" tid "name" tid "theme_id" tid}))))

(defn sprint-json [root id]
  (when-let [sp (sprint/load-sprint root id)]
    {"id" id
     "name" (or (:name sp) id)
     "kind" (or (:kind sp) "impl")
     "state" (or (:state sp) "draft")
     "phase" (or (:phase sp) "")
     "tag" (or (:tag sp) "")
     "branch" (or (:branch sp) "")
     "stories" (mapv (fn [sid]
                       {"id" sid
                        "title" (story-title root sid)
                        "kind" "story"})
                     (sprint/read-members root id))}))

(defn all-sprint-json [root]
  (->> (sprint/sprint-ids root)
       (keep #(sprint-json root %))
       vec))

(defn done-sprint-json [root]
  (mapv (fn [s] (assoc s "kind" "sprint" "title" (get s "name")))
        (filter #(= "done" (get % "state")) (all-sprint-json root))))

(defn sprint-tasks [root sprint-id]
  (let [dir (fs/path root ".squad" "sprints" sprint-id "tasks")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter fs/regular-file?)
           (sort-by fs/file-name)
           (mapv (fn [f]
                   (let [mod (fs/file-name f)
                         raw (or (sprint/read-field f "stories") "")]
                     {"id" mod
                      "title" mod
                      "kind" "task"
                      "module" mod
                      "stage" (or (sprint/read-field f "stage") "queued")
                      "stories" (vec (remove str/blank?
                                             (str/split raw #",")))}))))
      [])))

(defn unscheduled-story-json [root]
  (let [taken (sprint/assigned-story-ids root)]
    (->> (sprint/registered-story-ids root)
         (remove taken)
         (mapv (fn [sid]
                 {"id" sid
                  "title" (story-title root sid)
                  "kind" "story"
                  "status" "open"})))))

(defn merged-backlog [root]
  (let [items (list-backlog root)
        seen (set (map #(get % "id") items))]
    (into items (remove #(contains? seen (get % "id"))
                        (unscheduled-story-json root)))))

(defn story-card-json [root story]
  {"id" (get story "story_id")
   "title" (story-title root (get story "story_id"))
   "kind" "story"
   "stage" (or (get story "stage_label") (get story "state"))})

(defn specifying-json [root stories scheduled]
  (if-not scheduled
    (->> stories
         (filter #(= "specifying" (get % "board_column")))
         (mapv #(story-card-json root %)))
    (let [by-id (into {} (map (fn [s] [(get s "story_id") s]) stories))]
      (mapv (fn [member]
              (let [id (get member "id")]
                (if-let [s (get by-id id)]
                  (story-card-json root s)
                  {"id" id
                   "title" (or (get member "title") (story-title root id))
                   "kind" "story"
                   "stage" (or (get scheduled "phase") "specifying")})))
            (or (get scheduled "stories") [])))))

(defn coding-json [root stories scheduled]
  (if scheduled
    (sprint-tasks root (get scheduled "id"))
    (->> stories
         (filter #(= "coding" (get % "board_column")))
         (mapv #(story-card-json root %)))))

(defn finalize-phase? [phase]
  (boolean (re-find #"harden|qa|arch|senior|final" (str phase))))

(defn finalizing-json [root stories scheduled]
  (cond
    (and scheduled (= "sprint-0" (get scheduled "kind")))
    [{"id" (get scheduled "id")
      "title" (get scheduled "name")
      "kind" "sprint"
      "stage" (or (get scheduled "phase") "maps")}]

    (and scheduled (finalize-phase? (get scheduled "phase")))
    [(assoc scheduled "kind" "sprint" "title" (get scheduled "name"))]

    :else
    (->> stories
         (filter #(= "finalizing" (get % "board_column")))
         (mapv #(story-card-json root %)))))

(defn run-squad-script [root script & args]
  (apply process/sh (concat [{:continue true :dir (str root)}]
                            [(str (fs/path (script-dir) script))]
                            args)))

(defn script-ok? [result]
  (zero? (:exit result)))

(defn script-error [result]
  (str/trim (str (:err result) (:out result))))

(defn create-project! [root {:keys [id name]}]
  (let [id (or (slug-id id) "")
        name (let [n (str/trim (or name ""))]
               (if (str/blank? n) id n))]
    (cond
      (str/blank? id) {:ok false :error "id required" :status 400}
      (project-map root) {:ok false :error "project exists" :status 409}
      :else
      (do
        (spit (str (fs/path root "theme.md")) (str "# " name "\n\n" name "\n"))
        (let [r (run-squad-script root "squad_theme.sh" "create" id "theme.md")]
          (if (script-ok? r)
            {:ok true :project (project-map root)}
            {:ok false :error (script-error r) :status 409}))))))

(defn register-story! [root {:keys [id name body]}]
  (let [title (str/trim (or name id ""))
        id (or (slug-id id) (slug-id title))
        theme (or (get (project-map root) "theme_id")
                  (get (project-map root) "id"))]
    (cond
      (str/blank? theme) {:ok false :error "no project" :status 409}
      (str/blank? id) {:ok false :error "name required" :status 400}
      :else
      (do
        (fs/create-dirs (fs/path root "stories"))
        (spit (str (fs/path root "stories" (str id ".md")))
              (str "# " title "\n\n" (or body "") "\n"))
        (let [r (run-squad-script root "squad_theme.sh" "story" theme id
                                  (str "stories/" id ".md"))]
          (if (script-ok? r)
            {:ok true :story {"id" id "title" title "body" (or body "")}}
            {:ok false :error (script-error r) :status 409}))))))

(defn create-sprint-web! [root {:keys [id name]}]
  (let [title (str/trim (or name id ""))
        id (or (slug-id id) (slug-id title))]
    (cond
      (str/blank? id) {:ok false :error "name required" :status 400}
      (sprint/load-sprint root id)
      {:ok false :error (str "Sprint already exists: " id) :status 409}
      :else
      (do
        (sprint/write-sprint! root {:id id :name (if (str/blank? title) id title)
                                    :kind "impl" :state "draft"})
        (sprint/write-members! root id [])
        {:ok true :sprint (sprint-json root id)}))))

(defn schedule-sprint-web! [root id]
  (let [sp (sprint/load-sprint root id)]
    (cond
      (nil? sp) {:ok false :error (str "Unknown sprint: " id) :status 404}
      (sprint/scheduled-id root)
      {:ok false :error "A sprint is already scheduled" :status 409}
      (not (contains? #{"draft" "abandoned"} (:state sp)))
      {:ok false :error "Only a draft or abandoned sprint can be scheduled" :status 409}
      :else
      (do
        (sprint/write-sprint! root (assoc sp :state "scheduled" :phase "scheduled"))
        {:ok true :sprint (sprint-json root id)}))))

(defn cancel-sprint-web! [root id]
  (let [r (run-squad-script root "squad_sprint.sh" "cancel" id)]
    (if (script-ok? r)
      {:ok true :sprint (sprint-json root id)}
      {:ok false :error (script-error r) :status 409})))

(defn move-story-web! [root story-id dest]
  (let [dest (or dest "backlog")
        r (run-squad-script root "squad_sprint.sh" "move" story-id dest)]
    (if (script-ok? r)
      {:ok true :story_id story-id :sprint dest}
      {:ok false :error (script-error r) :status 409})))

(defn web-state [root]
  (let [assignments (assignment-state root)
        agents (agent-state root)
        sl-requests (dashreq/list-all-requests root)
        theme-id (current-theme-id root)
        stalls (stall-report root assignments agents)
        stories (->> (story-state root)
                     (mapv story-board-row)
                     (#(enrich-story-holds % assignments))
                     ;; B48: later progress first within column (client also sorts)
                     (sort-by (fn [s]
                                [(- (long (or (get s "pipeline_rank") 0)))
                                 (str (get s "updated_at" ""))])
                              (fn [[ra ua] [rb ub]]
                                (let [c (compare ra rb)]
                                  (if (zero? c) (compare ub ua) c))))
                     vec)
        batches (batches-enriched root)
        backlog (merged-backlog root)
        approvals {"pending" (approval-state-for root "pending")}
        wif (->> (work-in-flight-rows root assignments batches)
                 (mapv (fn [row]
                         (if-let [heat (agent-pane-heat root (get row "agent_id"))]
                           (assoc row "activity" heat)
                           row))))
        project (project-map root)
        sprints (all-sprint-json root)
        open-sprints (filterv #(not= "done" (get % "state")) sprints)
        scheduled (first (filter #(= "scheduled" (get % "state")) open-sprints))
        base {"generated_at" (now)
              "project_root" (str root)
              "stories" stories
              "assignments" assignments
              "agents" agents
              "batches" batches
              "work_in_flight" wif
              "backlog" backlog
              "blockers" (blocker-state root assignments agents)
              "stalls" stalls
              "approvals" approvals
              "sl_requests" sl-requests
              "sl_queue_depth" (sl-queue-depth root)
              "sl_activity" (sl-activity root)
              "troubleshooter" {"working" (troubleshooter-working? root)
                                "session" (troubleshooter-session-name)}
              "project" project
              "sprints" sprints
              "open-sprints" open-sprints
              "scheduled" scheduled
              "specifying" (specifying-json root stories scheduled)
              "coding" (coding-json root stories scheduled)
              "finalizing" (finalizing-json root stories scheduled)
              "done" (done-sprint-json root)}
        with-theme (cond-> base theme-id (assoc "current_theme_id" theme-id))
        next-a (dashboard-next-action with-theme)
        product (product-pending-label sl-requests)]
    (cond-> (assoc with-theme
                   "next_action" next-a
                   "residual" next-a)
      product (assoc "product_pending" product))))
(defn response [status content-type body]
  {:status status :content-type content-type :body body})

(defn url-decode [value]
  (URLDecoder/decode value "UTF-8"))

(defn json-ok [payload]
  (response 200 "application/json; charset=utf-8" (to-json (assoc payload "ok" true))))

(defn action-response [result]
  (if (:ok result)
    (json-ok (dissoc result :ok :status))
    (response (:status result 400) "text/plain; charset=utf-8"
              (str (:error result) "\n"))))

(defn project-create-response [root body]
  (action-response (create-project! root {:id (extract-json-field body "id")
                                          :name (extract-json-field body "name")})))

(defn story-create-response [root body]
  (action-response (register-story! root {:id (extract-json-field body "id")
                                          :name (extract-json-field body "name")
                                          :body (or (extract-json-field body "body") "")})))

(defn sprint-create-response [root body]
  (action-response (create-sprint-web! root {:id (extract-json-field body "id")
                                             :name (extract-json-field body "name")})))

(defn sprint-action-response [root path]
  (let [[_ id action] (re-matches #"/api/sprints/([^/]+)/(schedule|cancel)" path)
        id (when id (url-decode id))]
    (cond
      (nil? id) (response 400 "text/plain; charset=utf-8" "bad sprint path\n")
      (= action "schedule") (action-response (schedule-sprint-web! root id))
      :else (action-response (cancel-sprint-web! root id)))))

(defn story-move-response [root path body]
  (let [[_ id] (re-matches #"/api/stories/([^/]+)/move" path)
        id (when id (url-decode id))
        dest (or (extract-json-field body "sprint_id")
                 (extract-json-field body "dest")
                 "backlog")]
    (if id
      (action-response (move-story-web! root id dest))
      (response 400 "text/plain; charset=utf-8" "bad story path\n"))))

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

(defn- theme-gate-approved-line? [theme-dir gate]
  (let [file (fs/path theme-dir "approvals.tsv")
        gate-norm (str/replace gate "_" "-")]
    (and (fs/exists? file)
         (some (fn [line]
                 (let [[_ recorded] (str/split line #"\t" 3)]
                   (or (= gate recorded)
                       (= gate-norm recorded)
                       (= (str/replace gate "-" "_") recorded))))
               (str/split-lines (slurp (str file)))))))

(defn- content-sha-web [text]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        digest (.digest md (.getBytes (str text) "UTF-8"))]
    (.toString (BigInteger. 1 digest) 16)))

(defn- theme-content-gate-status
  "Status badge for B25 content gates: missing | hollow | unapproved | approved | n/a."
  [root theme-id gate content]
  (let [theme-dir (fs/path root ".squad" "themes" theme-id)
        gate-norm (str/replace gate "_" "-")
        fp (fs/path theme-dir "approval-fingerprints" (str gate-norm ".sha"))
        quality (if (= gate-norm "dependency-checker")
                  (cond
                    (str/blank? content) :missing
                    :else
                    (try
                      (let [data (edn/read-string content)
                            deps (when (map? data) (get data :allowed-dependencies))]
                        (if (and (map? deps) (seq deps)) :ok :hollow))
                      (catch Exception _ :hollow)))
                  (cond
                    (str/blank? content) :missing
                    :else :ok))
        nonempty-order?
        (and (= gate-norm "implementation-order")
             (not (str/blank? content))
             (some #(re-matches #"([A-Za-z0-9][A-Za-z0-9._-]*)\s*:\s*(.+)"
                                (str/trim (first (str/split % #"#" 2))))
                   (str/split-lines content)))
        needs-approval? (case gate-norm
                          "dependency-checker" (= quality :ok)
                          "implementation-order" (boolean nonempty-order?)
                          false)
        approved? (and (theme-gate-approved-line? theme-dir gate-norm)
                       (fs/regular-file? fp)
                       (not (str/blank? content))
                       (= (str/trim (slurp (str fp))) (content-sha-web content)))]
    (cond
      (= quality :missing) "missing"
      (= quality :hollow) "hollow — needs non-trivial policy"
      (not needs-approval?) "present (no approval required — empty/comment-only)"
      approved? "approved"
      :else "awaiting user approval")))

(defn theme-lifecycle-status [root theme-id]
  "B23: open (default) or finalized."
  (let [life (slurp-if-exists (fs/path root ".squad" "themes" theme-id "lifecycle"))
        status (slurp-if-exists (fs/path root ".squad" "themes" theme-id "status"))]
    (or (when (not (str/blank? life))
          (second (re-find #"(?m)^lifecycle:\s*(.+)$" life)))
        (when (not (str/blank? status))
          (or (second (re-find #"(?m)^lifecycle:\s*(.+)$" status))
              (when (re-find #"(?m)^state:\s*finalized\s*$" status) "finalized")))
        "open")))

(defn theme-package-parts [root theme-id]
  "Ordered package sections. Implementation order and dependency-checker always
  appear (explicit missing markers) so operators notice incomplete analysis.
  B25: status line for approval of non-empty order / non-trivial checker.
  B23: lifecycle open/finalized."
  (let [theme (slurp-if-exists (fs/path root ".squad" "themes" theme-id "theme.md"))
        module-map (slurp-if-exists (fs/path root ".squad" "themes" theme-id "module-map.md"))
        durable-order (slurp-if-exists (fs/path root ".squad" "themes" theme-id "implementation-order.md"))
        draft-order (slurp-if-exists (fs/path root "implementation-order.md"))
        checker (slurp-if-exists (fs/path root "dependency-checker.edn"))
        lifecycle (theme-lifecycle-status root theme-id)
        lifecycle-body (str "_Lifecycle: **" lifecycle "**_\n\n"
                            (if (= "finalized" lifecycle)
                              (str "Project slice is finalized (shipped/accepted). "
                                   "New stories re-open automatically, or run "
                                   "`squad_theme.sh reopen " theme-id " <detail>`.")
                              (str "Project slice is open. When every story has finished QA and "
                                   "architecture is accepted (or senior-implementer has closed "
                                   "changes-requested), the project is done. Then request "
                                   "finalize approval, or run "
                                   "`squad_theme.sh finalize " theme-id " <detail>`.")))
        order-status (theme-content-gate-status
                      root theme-id "implementation-order"
                      (if (not (str/blank? durable-order)) durable-order ""))
        checker-status (theme-content-gate-status root theme-id "dependency-checker" checker)
        status-line (fn [status]
                      (str "_Status: " status "_\n\n"))
        ;; "" is truthy in Clojure — never use (or durable draft) for optional text.
        impl-order (cond
                     (not (str/blank? durable-order))
                     (str (status-line order-status) durable-order)
                     (not (str/blank? draft-order))
                     (str (status-line "draft — not yet recorded")
                          "_(Not yet recorded under .squad/themes/" theme-id
                          "/ — run `squad_theme.sh implementation-order "
                          theme-id " implementation-order.md`.)_\n\n"
                          draft-order)
                     :else
                     (str (status-line "missing")
                          "_(Missing.)_ Analyst must commit root `implementation-order.md` "
                          "(edges or comment-only “no multi-story gates”), then record with "
                          "`squad_theme.sh implementation-order " theme-id
                          " implementation-order.md`."))
        checker-body (if (not (str/blank? checker))
                       (str (status-line checker-status) checker)
                       (str (status-line "missing")
                            "_(Missing.)_ Analyst must commit root `dependency-checker.edn` "
                            "from the module map (real components/edges, not a hollow stub). "
                            "See `swarmforge/templates/dependency-checker.edn`. "
                            "Non-trivial policy requires user approval (B25)."))]
    (cond-> []
      true
      (conj {:id "lifecycle" :title "Project Lifecycle" :body lifecycle-body})
      (not (str/blank? theme))
      (conj {:id "theme" :title "Project" :body theme})
      (not (str/blank? module-map))
      (conj {:id "module-map" :title "Module Map" :body module-map})
      true
      (conj {:id "implementation-order" :title "Implementation Order" :body impl-order})
      true
      (conj {:id "dependency-checker" :title "Dependency Checker" :body checker-body}))))

(defn theme-content [root theme-id]
  "Plain-text package body (tests / simple readers). Prefer theme-package-page for HTML."
  (->> (theme-package-parts root theme-id)
       (map (fn [{:keys [title body]}] (section title body)))
       (apply str)))

(defn theme-package-page [theme-id parts]
  "HTML with clear section chrome and jump links so module map / order are not lost under theme prose."
  (let [nav (when (seq parts)
              (str "<nav class=\"toc\">"
                   (str/join " · "
                             (map (fn [{:keys [id title]}]
                                    (str "<a href=\"#" id "\">" (html-escape title) "</a>"))
                                  parts))
                   "</nav>"))
        body (str/join
              ""
              (map (fn [{:keys [id title body]}]
                     (str "<section class=\"pkg\" id=\"" id "\">"
                          "<h2>" (html-escape title) "</h2>"
                          "<pre>" (html-escape body) "</pre>"
                          "</section>"))
                   parts))]
    (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
         "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
         "<title>Project " (html-escape theme-id) "</title>"
         "<style>:root{font-family:ui-sans-serif,system-ui,sans-serif;color-scheme:light dark}"
         "body{margin:0;background:#f7f7f4;color:#202124}"
         "header{padding:14px 18px;border-bottom:1px solid #d9d9d2}"
         "h1{font-size:18px;margin:0}h2{font-size:16px;margin:0 0 10px;padding:0}"
         "main{padding:18px;display:grid;gap:18px}"
         "nav.toc{padding:10px 18px;background:#ecece6;border-bottom:1px solid #d9d9d2;font-size:14px}"
         "nav.toc a{color:#1a5f4a;margin-right:4px}"
         "section.pkg{background:white;border:1px solid #d9d9d2;border-radius:8px;padding:14px}"
         "section.pkg pre{white-space:pre-wrap;margin:0;overflow:auto;font-size:13px;line-height:1.45}"
         "</style></head><body>"
         "<header><h1>Project package: " (html-escape theme-id) "</h1></header>"
         (or nav "")
         "<main>" body "</main></body></html>")))

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
    (if (= "theme" kind)
      (let [parts (theme-package-parts root id)]
        (if (seq parts)
          (response 200 "text/html; charset=utf-8" (theme-package-page id parts))
          (response 404 "text/plain; charset=utf-8" "Project package not found\n")))
      (if-let [content (not-empty (or (artifact-content root kind id) ""))]
        (response 200 "text/html; charset=utf-8" (artifact-page title content))
        (response 404 "text/plain; charset=utf-8" "Artifact not found\n")))))

(defn tail-section [file]
  (when (fs/regular-file? file)
    (let [[_ tail] (str/split (slurp (str file)) #"(?m)^last_10_lines:\s*\n" 2)]
      tail)))

(defn socket-value [root]
  (let [socket-file (fs/path root ".swarmforge" "tmux-socket")]
    (when (fs/regular-file? socket-file)
      (str/trim (slurp (str socket-file))))))

(defn sl-activity
  "B56/B65: idle…max from SL pane change rate (observe only). Heat 0–6 for six bars."
  [root]
  (let [socket (socket-value root)
        session "swarmforge-squad-leader"
        live? (and socket
                   (zero? (:exit (sh-continue "tmux" "-S" socket "has-session" "-t" session))))
        text (when live?
               (:out (sh-continue "tmux" "-S" socket "capture-pane" "-p" "-t" session "-S" "-40")))
        h (when text (str (hash (pane-sample-for-hash text))))
        prev @sl-activity-atom
        heat (cond
               (not live?) 0
               (nil? h) 0
               (nil? (:hash prev)) 1
               (not= h (:hash prev)) (min 6 (inc (long (or (:heat prev) 0))))
               :else (max 0 (dec (long (or (:heat prev) 0)))))
        level (case (long heat)
                0 "idle"
                1 "quiet"
                2 "warm"
                3 "busy"
                4 "brisk"
                5 "hot"
                "max")]
    (reset! sl-activity-atom {:hash h :heat heat})
    {"level" level
     "heat" heat
     "session_live" (boolean live?)}))

(defn session-from-roles-tsv [root agent-id]
  "roles.tsv: role worktree path session display backend receive-mode"
  (let [roles (fs/path root ".swarmforge" "roles.tsv")]
    (when (fs/regular-file? roles)
      (some (fn [line]
              (let [cols (str/split line #"\t")]
                (when (and (>= (count cols) 4)
                           (= agent-id (nth cols 0)))
                  (nth cols 3))))
            (or (read-lines roles) [])))))

(defn session-from-sessions-tsv [root agent-id]
  "sessions.tsv: index role session display backend"
  (let [sessions (fs/path root ".swarmforge" "sessions.tsv")]
    (when (fs/regular-file? sessions)
      (some (fn [line]
              (let [cols (str/split line #"\t")]
                (when (and (>= (count cols) 3)
                           (= agent-id (nth cols 1)))
                  (nth cols 2))))
            (or (read-lines sessions) [])))))

(defn agent-session-name
  "Resolve tmux session for dashboard pane view.
  Transient workers store session in agent metadata; persistent roles (SL,
  Troubleshooter) only appear in roles.tsv / sessions.tsv."
  [root agent-id]
  (let [metadata (fs/path root ".squad" "agents" agent-id "metadata")]
    (or (not-empty (read-value metadata "session"))
        (session-from-roles-tsv root agent-id)
        (session-from-sessions-tsv root agent-id)
        (when-not (str/blank? agent-id)
          (str "swarmforge-" agent-id)))))

(defn agent-backend-name [root agent-id]
  (let [metadata (fs/path root ".squad" "agents" agent-id "metadata")]
    (or (not-empty (read-value metadata "backend"))
        (not-empty (read-value metadata "agent"))
        (let [roles (fs/path root ".swarmforge" "roles.tsv")]
          (when (fs/regular-file? roles)
            (some (fn [line]
                    (let [cols (str/split line #"\t")]
                      (when (and (>= (count cols) 6)
                                 (= agent-id (nth cols 0)))
                        (nth cols 5))))
                  (or (read-lines roles) [])))))))

(defn agent-pane-content [root agent-id]
  (let [session (agent-session-name root agent-id)
        backend (agent-backend-name root agent-id)
        socket (socket-value root)]
    (or (not-empty (when (and socket (not (str/blank? session)))
                     (capture-pane-tail socket session backend)))
        (tail-section (fs/path root ".squad" "agents" agent-id "liveness"))
        (when (and socket (not (str/blank? session)))
          (str "(no pane capture yet for session " session ")\n"))
        "")))

(defn pane-page [agent-id]
  "B86/B69: session window (agent/SL/TS) — scroll container is the pane, open at end.
  Root cause of failed open-at-bottom: pre used min-height only so it grew with
  content and the *window* scrolled; pre.scrollTop was a no-op."
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<title>Agent " (html-escape agent-id) "</title>"
       "<style>"
       "html,body{height:100%;margin:0;overflow:hidden;background:#111;color:#f4f4f4;"
       "font-family:ui-monospace,SFMono-Regular,Menlo,monospace;color-scheme:light dark}"
       "header{height:42px;box-sizing:border-box;padding:10px 12px;border-bottom:1px solid #333;flex:0 0 auto}"
       "h1{font:inherit;margin:0;font-size:14px}"
       "body{display:flex;flex-direction:column}"
       ;; Fixed-height scrollport so overflow is on #pane, not the document
       "#pane{flex:1 1 auto;margin:0;padding:12px;white-space:pre-wrap;overflow:auto;"
       "min-height:0;height:calc(100vh - 42px);max-height:calc(100vh - 42px)}"
       "#new-output{position:fixed;right:12px;bottom:12px;background:#2f6f4e;color:white;"
       "border:0;border-radius:6px;padding:6px 10px;display:none;z-index:2}"
       "</style>"
       "</head><body><header><h1>" (html-escape agent-id) "</h1></header>"
       "<pre id=\"pane\"></pre>"
       "<button id=\"new-output\" type=\"button\">New output</button>"
       "<script>"
       "(function(){"
       "const pane=document.getElementById('pane');"
       "const marker=document.getElementById('new-output');"
       "let stickBottom=true;"
       "let firstPaint=true;"
       "function nearBottom(){"
       "return (pane.scrollHeight-pane.scrollTop-pane.clientHeight)<=64;}"
       "function toEnd(){"
       "pane.scrollTop=pane.scrollHeight;"
       "stickBottom=true;}"
       "function toEndSoon(){"
       "toEnd();"
       "requestAnimationFrame(toEnd);"
       "setTimeout(toEnd,0);"
       "setTimeout(toEnd,50);"
       "setTimeout(toEnd,200);}"
       "pane.addEventListener('scroll',function(){stickBottom=nearBottom();}, {passive:true});"
       "marker.addEventListener('click',function(){toEnd();marker.style.display='none';});"
       "async function refresh(){"
       "const prevHeight=pane.scrollHeight||0;"
       "const prevTop=pane.scrollTop||0;"
       "const distFromBottom=Math.max(0,prevHeight-prevTop-pane.clientHeight);"
       "const r=await fetch('/api/agents/" (html-escape agent-id) "/pane',{cache:'no-store'});"
       "const text=await r.text();"
       "const changed=text!==pane.textContent;"
       "if(changed){pane.textContent=text;}"
       "if(firstPaint||stickBottom||distFromBottom<=64){"
       "toEndSoon();marker.style.display='none';firstPaint=false;}"
       "else if(changed){"
       "pane.scrollTop=Math.max(0,pane.scrollHeight-pane.clientHeight-distFromBottom);"
       "marker.style.display='block';}"
       "}"
       "refresh();setInterval(refresh,1000);"
       "window.addEventListener('load',toEndSoon);"
       "window.addEventListener('pageshow',toEndSoon);"
       "window.addEventListener('focus',function(){if(firstPaint||stickBottom)toEndSoon();});"
       "})();"
       "</script></body></html>"))
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

(defn dashboard-request-wake-message
  "B34: raw tmux inject of id-prefixed operator text. Durable request already
  exists; answer still via squad_dashboard_request.sh answer (pane alone does not
  complete). Single-line: [id] body. Multiline body: [id] then body on next lines."
  [request]
  (let [id (get request "id")
        body (str (get request "body" ""))]
    (if (str/includes? body "\n")
      (str "[" id "]\n" body)
      (str "[" id "] " body))))

(defn wake-troubleshooter-for-request! [root request]
  (if-let [socket (socket-value root)]
    (if (tmux-notify! socket (troubleshooter-session-name) (dashboard-request-wake-message request))
      (do
        (log! root "web-troubleshooter-request-created" (get request "id"))
        true)
      ;; Fall back to SL if Troubleshooter session is not up yet
      (if (tmux-notify! socket "swarmforge-squad-leader"
                        (str "Troubleshooter session missing. Operator request "
                             (get request "id") " — spawn troubleshooter or handle residual.\n"
                             (dashboard-request-wake-message request)))
        (do (log! root "web-sl-request-fallback" (get request "id")) true)
        false))
    false))

(defn wake-sl-for-request! [root request]
  "Legacy name: operator dashboard requests wake the Troubleshooter (B09)."
  (wake-troubleshooter-for-request! root request))

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

(defn kill-all-sessions-on-socket! [socket]
  (when-not (str/blank? socket)
    (let [listed (sh-continue "tmux" "-S" socket "list-sessions" "-F" "#{session_name}")]
      (when (zero? (:exit listed))
        (doseq [session (->> (str/split-lines (:out listed))
                             (remove str/blank?))]
          (sh-continue "tmux" "-S" socket "kill-session" "-t" (str "=" session))
          (sh-continue "tmux" "-S" socket "kill-session" "-t" session))))))

(defn run-teardown! [root]
  "Stop squadd (full teardown reconcile) and kill remaining tmux sessions on the
  project socket. Same intent as ./close-swarm for process lifecycle."
  (let [socket (socket-value root)]
    (kill-all-sessions-on-socket! socket)
    (stop-squadd/stop! (str (fs/absolutize root)) :full-teardown? true)
    (kill-all-sessions-on-socket! socket)
    (log! root "web-teardown-complete")
    true))

(defn schedule-teardown! [root]
  "Respond to the operator first; run teardown shortly after so HTTP can flush
  before this process is killed."
  (future
    (try
      (Thread/sleep 250)
      (run-teardown! root)
      (catch Exception e
        (try (log! root "web-teardown-failed" (.getMessage e))
             (catch Exception _ nil)))))
  true)

(defn teardown-confirm-ok? [body]
  (let [text (str/trim (or body ""))]
    (or (= "TEARDOWN" text)
        (= "TEARDOWN" (extract-json-string text "confirm"))
        (and (str/includes? text "TEARDOWN")
             (re-find #"(?i)\"confirm\"\\s*:\\s*\"TEARDOWN\"" text)))))

(defn teardown-response [root body]
  (if-not (teardown-confirm-ok? body)
    (response 400 "text/plain; charset=utf-8"
              "Teardown requires confirm=TEARDOWN (JSON {\"confirm\":\"TEARDOWN\"}).\n")
    (do
      (log! root "web-teardown-requested")
      (schedule-teardown! root)
      (response 200 "application/json; charset=utf-8"
                (to-json {"ok" true
                          "status" "teardown_started"
                          "detail" "Swarm teardown started; dashboard will go offline."})))))

(defn backlog-list-response [root]
  (response 200 "application/json; charset=utf-8"
            (to-json {"items" (list-backlog root)})))

(defn backlog-create-response [root body]
  (let [title (or (extract-json-field body "title") "")
        b (or (extract-json-field body "body") body "")
        result (create-backlog! root {:title title :body b})]
    (if (:ok result)
      (response 200 "application/json; charset=utf-8" (to-json {"ok" true "item" (:item result)}))
      (response (:status result 400) "text/plain; charset=utf-8" (str (:error result) "\n")))))

(defn backlog-item-response [root path body]
  (let [[_ id action] (re-matches #"/api/backlog/([^/]+)(?:/(approve|delete))?" path)
        id (when id (url-decode id))]
    (cond
      (nil? id)
      (response 400 "text/plain; charset=utf-8" "bad backlog path\n")

      (= action "approve")
      (let [r (approve-backlog! root id)]
        (if (:ok r)
          (response 200 "application/json; charset=utf-8"
                    (to-json {"ok" true "item" (:item r) "request" (:request r)}))
          (response (:status r 400) "text/plain; charset=utf-8" (str (:error r) "\n"))))

      (= action "delete")
      (let [r (delete-backlog! root id)]
        (if (:ok r)
          (response 200 "application/json; charset=utf-8" (to-json {"ok" true}))
          (response (:status r 404) "text/plain; charset=utf-8" (str (:error r) "\n"))))

      :else
      (let [title (extract-json-field body "title")
            b (extract-json-field body "body")
            r (update-backlog! root id {:title title :body b})]
        (if (:ok r)
          (response 200 "application/json; charset=utf-8" (to-json {"ok" true "item" (:item r)}))
          (response (:status r 400) "text/plain; charset=utf-8" (str (:error r) "\n")))))))


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
    :handler (fn [root _ body] (sl-message-response root body))}
   {:method "POST"
    :path "/api/teardown"
    :handler (fn [root _ body] (teardown-response root body))}
   {:method "GET"
    :path "/api/backlog"
    :handler (fn [root _ _] (backlog-list-response root))}
   {:method "POST"
    :path "/api/backlog"
    :handler (fn [root _ body] (backlog-create-response root body))}
   {:method "POST"
    :pattern #"/api/backlog/[^/]+(?:/(approve|delete))?"
    :handler (fn [root path body] (backlog-item-response root path body))}
   {:method "POST"
    :path "/api/project"
    :handler (fn [root _ body] (project-create-response root body))}
   {:method "POST"
    :path "/api/stories"
    :handler (fn [root _ body] (story-create-response root body))}
   {:method "POST"
    :path "/api/sprints"
    :handler (fn [root _ body] (sprint-create-response root body))}
   {:method "POST"
    :pattern #"/api/sprints/[^/]+/(schedule|cancel)"
    :handler (fn [root path _] (sprint-action-response root path))}
   {:method "POST"
    :pattern #"/api/stories/[^/]+/move"
    :handler (fn [root path body] (story-move-response root path body))}])

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
