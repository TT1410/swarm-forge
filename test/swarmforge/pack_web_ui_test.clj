(ns swarmforge.pack-web-ui-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [swarmforge.pack-test-support :refer :all]))

(use-fixtures :once once-fixture)

(deftest pack-web-waiting-lane-lists-waiting-cards
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)
        _ (pack-board root true
                      "create" "--root" (str root)
                      "--name" "UiShim" "--type" "component" "--waiting")
        state (web-state root)
        card (first (filter #(= "UiShim" (:name %)) (:tasks state)))]
    (is (= "waiting" (first (:lanes state))))
    (is (= "done" (last (:lanes state))))
    (is (= "waiting" (:lane card)))
    (is (= "Waiting to start" (:status card)))
    (is (not (contains? card :activity)))))
(deftest pack-web-task-window-includes-audits-and-directory
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))
        _ (increment-audit! root task-id)
        page (:out (pack-web root false "--test-task" (str root) "HTW"))
        tree (json/parse-string
              (:out (pack-web root false "--test-tree" (str root) "HTW"))
              true)]
    (is (str/includes? page "HTW"))
    (is (str/includes? page "Audit 1"))
    (is (str/includes? page "Directory"))
    (is (some #(= "tasks" (:name %)) (:entries tree)))))
(deftest pack-web-exposes-dashboard-state-from-conf-and-board
  ;; Given a six-pack with specifier as master and a board card
  ;; When pack_web --test-state
  ;; Then JSON includes lanes from conf, the master display name, and the card
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)
        _ (create-task root "htw-console-app" "specifier")
        listed (:out (list-tasks root))
        updated (nth (str/split (or (task-row listed "htw-console-app") "") #"\t") 3 nil)
        result (pack-web root true "--test-state" (str root))
        state (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (= "specifier" (:master_role state)))
    (is (= "Specifier" (:master_display state)))
    (is (= (vec (concat ["waiting"] six-pack-roles ["done"])) (:lanes state)))
    (let [card (first (:tasks state))]
      (is (= "htw-console-app" (:name card)))
      (is (str/starts-with? (:id card) "20"))
      (is (= "specifier" (:lane card)))
      (is (= updated (:updated_at card)))
      (is (= 0 (:audit_count card)))
      (is (= "" (:status card))))
    (is (= [] (:approvals state)))
    (is (= six-pack-roles (mapv :role (:work_in_flight state))))))
(deftest pack-web-post-task-creates-a-card-in-the-master-lane
  ;; Given a six-role pack
  ;; When POST /api/tasks records name and text with no type
  ;; Then the card is component in waiting with no start note
  (let [root (tmp-dir)
        text "Integrate HTW stories"]
    (setup-pack! root six-pack-roles)
    (let [result (pack-web root true "--test-post-task" (str root) "htw-console-app" text)
          body (slurp (str (fs/path root ".swarmforge/board/htw-console-app.txt")))
          notes (handoff-names (fs/path root ".swarmforge/handoffs/outbox"))]
      (is (zero? (:exit result)))
      (is (= "waiting" (task-lane root "htw-console-app")))
      (is (= "component" (:type (task-card root "htw-console-app"))))
      (is (= "Waiting to start" (:status (task-card root "htw-console-app"))))
      (is (= text body))
      (is (empty? (filter #(str/includes? % "New_Task") notes)))
      (is (seq (fs/glob (fs/path root ".swarmforge/notify") "*.notify"))))))
(deftest pack-web-post-task-creates-a-card-when-tmux-is-missing
  ;; Given no tmux socket or live session
  ;; When POST /api/tasks via --test-post-task
  ;; Then inject failure is ignored and the card is still created
  (let [root (tmp-dir)
        text example-task-text]
    (setup-pack! root six-pack-roles)
    (let [result (pack-web root false "--test-post-task" (str root) "htw-console-app" text)
          body (slurp (str (fs/path root ".swarmforge/board/htw-console-app.txt")))]
      (is (zero? (:exit result)))
      (is (= "waiting" (task-lane root "htw-console-app")))
      (is (= text body))
      (is (str/includes? (slurp (str (fs/path root "tasks/htw-console-app.md")))
                         text)))))
(deftest pack-web-inject-failure-logs-the-role
  (let [root (tmp-dir)]
    (setup-pack! root ["coder"])
    (let [result (pack-web root false "--test-post-chat" (str root) "hello master")]
      (is (zero? (:exit result)))
      (is (str/includes? (str (:err result)) "inject failed"))
      (is (str/includes? (str (:err result)) "coder")))))
(deftest pack-web-post-task-queues-a-note-for-master
  ;; Given a specifier pack and a tmux argv stub
  ;; When POST /api/tasks records name and text
  ;; Then the card is waiting, no start note is queued, and the lieutenant is notified
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))
        text example-task-text]
    (setup-pack! root)
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (let [result (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                               "--test-post-task" (str root) "htw-console-app" text)
          queued (handoff-names (fs/path root ".swarmforge/handoffs/outbox"))
          argv (read-argv argv-file)]
      (is (zero? (:exit result)))
      (is (= "waiting" (task-lane root "htw-console-app")))
      (is (empty? queued))
      (is (seq (fs/glob (fs/path root ".swarmforge/notify") "*.notify")))
      (is (seq (submitted-texts argv))))))
(deftest pack-web-post-chat-injects-text-as-is
  ;; Given a tmux argv stub
  ;; When POST /api/chat {text}
  ;; Then inject-master! send-keys that text, not a Task payload
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))
        text "Please add a --help flag"]
    (setup-pack! root)
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (let [result (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                               "--test-post-chat" (str root) text)
          argv (read-argv argv-file)]
      (is (zero? (:exit result)))
      (let [submitted (submitted-texts argv)]
        (is (some #(str/includes? % text) submitted))
        (is (some #(re-find #"\[req-" %) submitted))
        (is (not (some #(str/starts-with? % "Task:") submitted)))))))
(deftest pack-web-lists-every-role-in-the-work-queue
  ;; Given a six-pack with no in_process mail
  ;; When pack_web --test-state
  ;; Then work_in_flight has one row per conf role
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)
        wif (:work_in_flight (web-state root))]
    (is (= six-pack-roles (mapv :role wif)))
    (is (every? #(= "no_session" (:state %)) wif))
    (is (every? #(= 0 (:activity %)) wif))))
(deftest pack-web-lists-in-process-work-in-flight
  ;; Given in_process handoff for coder task cave-walk
  ;; When pack_web --test-state
  ;; Then work_in_flight includes task cave-walk role coder
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (put-in-process! root roles "coder" {:from "specifier" :task "cave-walk"})
    (let [wif (:work_in_flight (web-state root))
          row (some #(when (= "coder" (:role %)) %) wif)]
      (is (= roles (mapv :role wif)))
      (is (= "cave-walk" (:task row)))
      (is (= "coder" (:role row)))
      (is (re-matches #"\d{4}-\d{2}-\d{2}T.*Z" (or (:updated_at row) ""))))))
(deftest pack-web-marks-in-process-roles-live-when-session-exists
  ;; Given coder in_process and live tmux sessions
  ;; When pack_web --test-state
  ;; Then coder is live with that task and specifier is idle
  (let [root (tmp-dir)
        roles ["specifier" "coder"]
        sock (do (setup-pack! root roles)
                 (put-in-process! root roles "coder" {:from "specifier" :task "cave-walk"})
                 (start-tmux! root roles))]
    (try
      (let [wif (:work_in_flight (web-state root))
            by-role (into {} (map (juxt :role identity) wif))]
        (is (= "idle" (:state (get by-role "specifier"))))
        (is (= "live" (:state (get by-role "coder"))))
        (is (= "cave-walk" (:task (get by-role "coder"))))
        (is (= "" (:task (get by-role "specifier")))))
      (finally
        (stop-tmux! sock)))))
(deftest pack-web-lists-batch-in-process-in-work-in-flight
  ;; Given a batch dir in coder in_process for task cave-walk
  ;; When pack_web --test-state
  ;; Then work_in_flight includes task cave-walk role coder
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (put-in-process! root roles "coder"
                     {:from "specifier"
                      :task "cave-walk"
                      :filename "batch_20260615T000001Z_000001/50_from_specifier_to_coder.handoff"})
    (let [wif (:work_in_flight (web-state root))]
      (is (some #(and (= "cave-walk" (:task %)) (= "coder" (:role %))) wif)))))
(deftest pack-web-test-pane-prints-recorded-pane
  ;; Given a recorded pane.txt for coder task cave-walk
  ;; When pack_web --test-pane
  ;; Then it prints that text
  (let [root (tmp-dir)
        text "coder pane snapshot\n"]
    (setup-pack! root ["specifier" "coder"])
    (write-file (fs/path root ".swarmforge/sessions/coder/pane.txt") text)
    (let [result (pack-web root false "--test-pane" (str root) "coder")]
      (is (zero? (:exit result)))
      (is (str/includes? (:out result) "coder pane snapshot")))))
(deftest pack-web-pane-capture-of-missing-session-is-quiet
  (let [root (tmp-dir)]
    (setup-pack! root ["coder"])
    (let [result (pack-web root false "--test-pane" (str root) "coder")]
      (is (zero? (:exit result)))
      (is (str/blank? (str/trim (str (:err result))))))))
(deftest pack-web-teardown-throw-is-not-a-clean-success
  (let [root (tmp-dir)]
    (setup-pack! root)
    (let [result (pack-web root false "--test-teardown-throw" (str root))]
      (is (not (zero? (:exit result))))
      (is (str/includes? (str (:err result)) "teardown failed"))
      (is (str/includes? (str (:err result)) (str root))))))
(deftest pack-web-serve-writes-dashboard-url-and-binds-localhost
  ;; Given a pack root
  ;; When pack_web --serve <root>
  ;; Then dashboard-url is a localhost URL and GET / serves the dashboard
  (let [root (tmp-dir)
        url-file (fs/path root ".swarmforge/dashboard-url")
        pb (doto (java.lang.ProcessBuilder. [(script "pack_web.sh") "--serve" (str root)])
             (.directory (java.io.File. (str root))))
        _ (doto (.environment pb)
            (.put "PATH" (System/getenv "PATH"))
            (.put "GIT_CONFIG_NOSYSTEM" "1"))
        proc (.start pb)]
    (try
      (is (wait-file url-file 5000) "dashboard-url was written")
      (let [pid-file (fs/path root ".swarmforge/pack_web.pid")]
        (is (wait-file pid-file 5000) "pack_web.pid was written")
        (is (= (str (.pid proc)) (str/trim (slurp (str pid-file))))))
      (when (fs/exists? url-file)
        (let [url (str/trim (slurp (str url-file)))
              html (slurp url)]
          (is (re-find #"^http://127\.0\.0\.1:\d+$" url))
          (is (str/includes? html "New Task"))))
      (finally
        (.destroyForcibly proc)
        (.waitFor proc)))))
(deftest pack-web-teardown-requires-confirm
  ;; Given a pack root
  ;; When POST /api/teardown without confirm
  ;; Then it is rejected
  (let [root (tmp-dir)
        result (pack-web root false "--test-teardown" (str root))]
    (is (= 2 (:exit result)))
    (is (str/includes? (str (:err result) (:out result)) "TEARDOWN"))))
(deftest pack-web-teardown-kills-sessions-and-handoffd
  ;; Given a live tmux session and a fake handoffd pid
  ;; When teardown is confirmed
  ;; Then the tmux server is dead and the daemon pid is gone
  (let [root (tmp-dir)
        _ (setup-pack! root ["coder" "cleaner"])
        sock (start-tmux! root ["coder" "cleaner"])
        daemon (.start (java.lang.ProcessBuilder. ["sleep" "120"]))
        pid (str (.pid daemon))
        pack-web-proc (.start (java.lang.ProcessBuilder. ["sleep" "120"]))
        pack-web-pid (str (.pid pack-web-proc))]
    (try
      (write-file (fs/path root ".swarmforge/daemon/handoffd.pid") (str pid "\n"))
      (write-file (fs/path root ".swarmforge/pack_web.pid") (str pack-web-pid "\n"))
      (let [result (pack-web root false "--test-teardown" (str root) "TEARDOWN")]
        (is (zero? (:exit result)))
        (is (str/includes? (:out result) "teardown_started"))
        (is (not= 0 (:exit (run {:dir root :ok? false} "tmux" "-S" sock "list-sessions"))))
        (is (false? (.isAlive daemon)))
        (is (false? (.isAlive pack-web-proc)))
        (is (not (fs/exists? (fs/path root ".swarmforge/daemon/handoffd.pid"))))
        (is (not (fs/exists? (fs/path root ".swarmforge/pack_web.pid")))))
      (finally
        (when (.isAlive daemon)
          (.destroyForcibly daemon))
        (when (.isAlive pack-web-proc)
          (.destroyForcibly pack-web-proc))
        (stop-tmux! sock)))))
(deftest pack-web-shows-board-card-as-live-work
  ;; Given card HTW in specifier and a live specifier session
  ;; When pack_web --test-state
  ;; Then specifier row is live with task HTW
  (let [root (tmp-dir)
        sock (do (setup-pack! root)
                 (create-task root "HTW" "specifier")
                 (start-tmux! root ["specifier"]))]
    (try
      (let [row (some #(when (= "specifier" (:role %)) %)
                      (:work_in_flight (web-state root)))]
        (is (= "HTW" (:task row)))
        (is (= "live" (:state row))))
      (finally
        (stop-tmux! sock)))))
(deftest pack-web-chat-persists-and-answers
  ;; Given a pack root
  ;; When POST /api/chat then pack_dashboard_request answer
  ;; Then /api/state chat has the body and response
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))
        answer (fs/path root "tmp" "answer.txt")]
    (setup-pack! root)
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (write-file answer "the spec is ready\nwith two documents\n")
    (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                  "--test-post-chat" (str root) "status?")
    (let [listed (run {:dir root}
                      (script "pack_dashboard_request.sh")
                      "list" "--root" (str root))
          id (first (str/split (str/trim (:out listed)) #"\t"))]
      (is (str/starts-with? id "req-"))
      (run {:dir root} (script "pack_dashboard_request.sh") "answer" id (str answer))
      (let [chat (:chat (web-state root))
            row (first chat)
            stored (slurp (str (first (fs/list-dir
                                       (fs/path root ".swarmforge/dashboard/requests/done")))))]
        (is (= "status?" (str/trim (:body row))))
        (is (= "the spec is ready\nwith two documents" (:response row)))
        (is (str/includes? stored "response: the spec is ready\\nwith two documents\n"))
        (is (= "done" (:status row)))))))
(deftest pack-web-state-groups-in-process-batch-cards
  ;; Given two-pack and two cleaner in-process handoffs in one batch dir
  ;; When --test-state
  ;; Then those tasks share a batch id
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        _ (setup-pack! root roles)
        _ (create-task root "Command syntax" "cleaner")
        _ (create-task root "validation" "cleaner")
        batch "batch_20260824T150500Z_000001"
        dir (fs/path (in-process-dir root roles "cleaner") batch)]
    (write-file (fs/path dir "50_command.handoff")
                "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: Command syntax\n\npayload\n")
    (write-file (fs/path dir "50_validation.handoff")
                "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: validation\n\npayload\n")
    (let [by-name (into {} (map (juxt :name identity) (:tasks (web-state root))))]
      (is (= (get-in by-name ["Command syntax" :batch])
             (get-in by-name ["validation" :batch])))
      (is (some? (get-in by-name ["Command syntax" :batch]))))))
(deftest pack-web-non-codex-status-still-uses-ill
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (set-backend! root "grok")
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             "I'll write the cave stories.\n")
        card (first (:tasks (json/parse-string (:out result) true)))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status card)) "I'll write the cave stories"))))
(deftest pack-web-shows-yellow-merging-card-for-handback
  ;; Given htw in architect and jump in coder, plus a reverse htw copy in coder in_process
  ;; When --test-status-pane
  ;; Then a merging card for htw is in coder with Merging refactorer, jump waits, real htw stays architect
  (let [root (tmp-dir)
        roles four-pack-roles
        _ (setup-pack! root roles)
        _ (create-task root "htw" "architect")
        _ (create-task root "jump" "coder")
        _ (write-file
           (fs/path (in-process-dir root roles "coder")
                    "00_from_refactorer_to_coder.handoff")
           (str "from: refactorer\nto: coder\npriority: 00\ntype: git_handoff\n"
                "task: htw\nnon-forwarding: true\n\nmerge\n"))
        result (pack-web-env root {} "--test-status-pane" (str root)
                             "• The reverse handoff is structurally reconciled.\n")
        state (json/parse-string (:out result) true)
        cards (:tasks state)
        merging (filterv :merging cards)
        jump-card (first (filter #(= "jump" (:name %)) cards))
        htw-card (first (filter #(and (= "htw" (:name %)) (= "architect" (:lane %))) cards))]
    (is (zero? (:exit result)))
    (is (= ["htw" "htw" "jump"] (mapv :name cards)))
    (is (= 1 (count merging)))
    (is (= "coder" (:lane (first merging))))
    (is (= "htw" (:name (first merging))))
    (is (= "Merging refactorer" (:status (first merging))))
    (is (= "waiting in queue" (:status jump-card)))
    (is (= "architect" (:lane htw-card))))
  (let [root (tmp-dir)
        roles four-pack-roles
        _ (setup-pack! root roles)
        _ (create-task root "htw" "architect")
        _ (create-task root "jump" "coder")
        state (web-state root)
        merging (filterv :merging (:tasks state))]
    (is (= [] merging))
    (is (= "architect" (task-lane root "htw")))))
(deftest pack-web-pending-approval-card-says-waiting-for-approval
  ;; Given HTW in specifier and a pending specifier→coder git_handoff for HTW
  ;; When --test-state
  ;; Then HTW status is Waiting for approval
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)
        _ (create-task root "HTW" "specifier")
        _ (create-task root "Command Syntax" "specifier")]
    (write-file
     (fs/path root ".swarmforge/handoffs/pending_approval/50_from_specifier_to_coder.handoff")
     "from: specifier\nto: coder\npriority: 50\ntype: git_handoff\ntask: HTW\n\npayload\n")
    (let [state (web-state root)
          by-name (into {} (map (juxt :name identity) (:tasks state)))]
      (is (= "Waiting for approval" (:status (get by-name "HTW"))))
      (is (= "waiting in queue" (:status (get by-name "Command Syntax")))))))
(deftest pack-web-rejected-card-says-rejected
  ;; Given HTW is rejected
  ;; When --test-state
  ;; Then HTW status is REJECTED
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")]
    (write-file (fs/path root ".swarmforge/notify/reject-HTW") "rejected\n")
    (let [card (first (:tasks (web-state root)))]
      (is (= "REJECTED" (:status card))))))
(deftest pack-web-delete-removes-a-rejected-card
  ;; Given a rejected HTW card
  ;; When POST /api/tasks/delete
  ;; Then the card is gone from the board
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        old-id (:id (task-card root "HTW"))
        _ (increment-audit! root old-id)
        _ (write-file (fs/path root ".swarmforge/notify/reject-HTW") "rejected\n")
        result (pack-web root false "--test-delete-task" (str root) "HTW")]
    (is (zero? (:exit result)))
    (is (nil? (task-lane root "HTW")))
    (is (not (fs/exists? (fs/path root ".swarmforge/board/HTW.txt"))))
    (is (fs/exists? (fs/path root "tasks/HTW.md")))
    (create-task root "HTW" "specifier")
    (let [replacement (task-card root "HTW")]
      (is (not= old-id (:id replacement)))
      (is (= 0 (:audit_count replacement))))))
(deftest pack-web-delete-rejected-purges-handoffs-into-rejected-tasks
  ;; Given a rejected HTW card with a pending git_handoff
  ;; When POST /api/tasks/delete
  ;; Then the card, notify, and handoff are gone and rejected-tasks keeps the set
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        _ (increment-audit! root (:id (task-card root "HTW")))
        _ (write-file (fs/path root ".swarmforge/notify/reject-HTW") "rejected\n")
        pending (fs/path root ".swarmforge/handoffs/pending_approval/50_from_specifier_to_coder.handoff")
        _ (write-file pending
                      "from: specifier\nto: coder\ntype: git_handoff\ntask: HTW\n\npayload\n")
        _ (write-pending-audit! root "HTW")
        _ (write-pending-audit! root "unrelated-id")
        result (pack-web root false "--test-delete-task" (str root) "HTW")]
    (is (zero? (:exit result)))
    (is (nil? (task-lane root "HTW")))
    (is (not (fs/exists? pending)))
    (is (= #{"unrelated-id"} (pending-audit-task-ids root)))
    (is (not (fs/exists? (fs/path root ".swarmforge/notify/reject-HTW"))))
    (is (fs/exists? (fs/path root ".swarmforge/rejected-tasks")))))
(deftest pack-web-retry-moves-a-completed-retry-note-back-to-in-process
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))
        retry-name (str "50_retry_" (str/replace task-id #"[^A-Za-z0-9]+" "_") ".handoff")
        completed (fs/path root ".swarmforge/handoffs/inbox/completed" retry-name)
        in-process (fs/path root ".swarmforge/handoffs/inbox/in_process" retry-name)]
    (write-file completed
                (str "from: (Retry)\n"
                     "to: specifier\n"
                     "priority: 50\n"
                     "type: note\n"
                     "task_id: " task-id "\n"
                     "task: HTW\n"
                     "completed_at: 2026-08-26T22:45:36.178441Z\n"
                     "\n"
                     "Retry audit.\n"))
    (write-file (fs/path root ".swarmforge/handoffs/pending_approval/50_hello.handoff")
                (str "from: specifier\nto: coder\ntype: git_handoff\n"
                     "task_id: " task-id "\ntask: HTW\n\npayload\n"))
    (let [result (pack-web root false "--test-retry-task" (str root) "50_hello" "use an RNG")]
      (is (zero? (:exit result)))
      (is (fs/exists? in-process))
      (is (not (fs/exists? completed)))
      (is (str/includes? (slurp (str in-process)) (str "task_id: " task-id))))))
(deftest pack-web-second-retry-does-not-leave-copies-in-both-inboxes
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))
        retry-name (str "50_retry_" (str/replace task-id #"[^A-Za-z0-9]+" "_") ".handoff")
        completed (fs/path root ".swarmforge/handoffs/inbox/completed" retry-name)
        in-process (fs/path root ".swarmforge/handoffs/inbox/in_process" retry-name)]
    (write-file completed
                (str "from: (Retry)\n"
                     "to: specifier\n"
                     "priority: 50\n"
                     "type: note\n"
                     "task_id: " task-id "\n"
                     "task: HTW\n"
                     "\n"
                     "Retry audit.\n"))
    (write-file (fs/path root ".swarmforge/handoffs/pending_approval/50_first.handoff")
                (str "from: specifier\nto: coder\ntype: git_handoff\n"
                     "task_id: " task-id "\ntask: HTW\n\npayload\n"))
    (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                "50_first" "first"))))
    (is (zero? (:exit (run {:dir root :env {"SWARMFORGE_ROLE" "specifier"}}
                           (script "done_with_current.sh")))))
    (is (fs/exists? completed))
    (is (not (fs/exists? in-process)))
    (write-file (fs/path root ".swarmforge/handoffs/pending_approval/50_second.handoff")
                (str "from: specifier\nto: coder\ntype: git_handoff\n"
                     "task_id: " task-id "\ntask: HTW\n\npayload\n"))
    (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                "50_second" "second"))))
    (is (fs/exists? in-process))
    (is (not (fs/exists? completed)))))
(deftest pack-web-retry-rejected-queues-a-master-note
  ;; Given a pending git_handoff
  ;; When POST /api/tasks/retry with comments
  ;; Then the card stays, original body is unchanged, audit_count increases, and no New Task note is queued
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))
        _ (increment-audit! root task-id)
        original (slurp (str (fs/path root ".swarmforge/board/HTW.txt")))
        pending (fs/path root ".swarmforge/handoffs/pending_approval/50_hello.handoff")
        _ (write-file pending
                      (str "from: specifier\nto: coder\ntype: git_handoff\n"
                           "task_id: " task-id "\ntask: HTW\n\nold\n"))
        _ (write-pending-audit! root task-id)
        _ (write-pending-audit! root "unrelated-id")
        result (pack-web root false "--test-retry-task" (str root)
                         "50_hello" "use an RNG")
        card (first (:tasks (web-state root)))
        notes (if (fs/directory? (fs/path root ".swarmforge/handoffs/outbox"))
                (fs/list-dir (fs/path root ".swarmforge/handoffs/outbox"))
                [])]
    (is (zero? (:exit result)))
    (is (= "specifier" (:lane card)))
    (is (= 2 (:audit_count card)))
    (is (not= "REJECTED" (:status card)))
    (is (= original (slurp (str (fs/path root ".swarmforge/board/HTW.txt")))))
    (is (not (fs/exists? pending)))
    (is (= #{"unrelated-id"} (pending-audit-task-ids root)))
    (is (empty? (filter #(str/includes? (fs/file-name %) "New_Task") notes)))))
(deftest pack-web-retry-snapshots-rejected-branches-without-reset
  (let [root (tmp-dir)]
    (run {:dir root} "git" "init" "-q")
    (run {:dir root} "git" "config" "user.email" "test@example.com")
    (run {:dir root} "git" "config" "user.name" "Test User")
    (setup-pack! root)
    (write-file (fs/path root "story.md") "base\n")
    (run {:dir root} "git" "add" "story.md")
    (run {:dir root} "git" "commit" "-q" "-m" "Base")
    (create-task root "HTW" "specifier")
    (let [task-id (:id (task-card root "HTW"))
          base (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
      (write-file (fs/path root "story.md") "offer-1\n")
      (run {:dir root} "git" "add" "story.md")
      (run {:dir root} "git" "commit" "-q" "-m" "Offer 1")
      (let [first-sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
            pending (fs/path root ".swarmforge/handoffs/pending_approval/50_first.handoff")]
        (write-file pending
                    (str "from: specifier\nto: coder\ntype: git_handoff\n"
                         "task_id: " task-id "\ntask: HTW\n"
                         "commit: " first-sha "\n"
                         "task_base_commit: " base "\n\n"
                         "payload\n"))
        (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                    "50_first" "first comments"))))
        (let [head (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
              branches (:out (run {:dir root} "git" "branch" "--format=%(refname:short)"))]
          (is (= first-sha head))
          (is (not= base head))
          (is (str/includes? branches (str "rejected/" task-id "/1")))
          (is (str/includes? branches (str "rejected/" task-id "/latest"))))
        (write-file (fs/path root "story.md") "offer-2\n")
        (run {:dir root} "git" "add" "story.md")
        (run {:dir root} "git" "commit" "-q" "-m" "Offer 2")
        (let [second-sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
              pending2 (fs/path root ".swarmforge/handoffs/pending_approval/50_second.handoff")]
          (write-file pending2
                      (str "from: specifier\nto: coder\ntype: git_handoff\n"
                           "task_id: " task-id "\ntask: HTW\n"
                           "commit: " second-sha "\n"
                           "task_base_commit: " base "\n\n"
                           "payload\n"))
          (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                      "50_second" "second comments"))))
          (let [head (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
                branches (:out (run {:dir root} "git" "branch" "--format=%(refname:short)"))]
            (is (= second-sha head))
            (is (str/includes? branches (str "rejected/" task-id "/1")))
            (is (str/includes? branches (str "rejected/" task-id "/2")))
            (is (str/includes? branches (str "rejected/" task-id "/latest")))
            (is (= 2 (:audit_count (task-card root "HTW"))))))))))
(deftest pack-web-retry-restores-wandered-head
  (let [root (tmp-dir)]
    (run {:dir root} "git" "init" "-q")
    (run {:dir root} "git" "config" "user.email" "test@example.com")
    (run {:dir root} "git" "config" "user.name" "Test User")
    (setup-pack! root)
    (write-file (fs/path root "story.md") "base\n")
    (run {:dir root} "git" "add" "story.md")
    (run {:dir root} "git" "commit" "-q" "-m" "Base")
    (create-task root "HTW" "specifier")
    (let [task-id (:id (task-card root "HTW"))]
      (write-file (fs/path root "story.md") "offer\n")
      (run {:dir root} "git" "add" "story.md")
      (run {:dir root} "git" "commit" "-q" "-m" "Offer")
      (let [offer (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
            pending (fs/path root ".swarmforge/handoffs/pending_approval/50_offer.handoff")]
        (write-file pending
                    (str "from: specifier\nto: coder\ntype: git_handoff\n"
                         "task_id: " task-id "\ntask: HTW\n"
                         "commit: " offer "\n\npayload\n"))
        (write-file (fs/path root "story.md") "wander\n")
        (run {:dir root} "git" "add" "story.md")
        (run {:dir root} "git" "commit" "-q" "-m" "Wander")
        (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                    "50_offer" "stay on the offer"))))
        (is (= offer (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))))))))
(deftest pack-web-retry-keeps-task-base-for-the-next-git-handoff
  (let [root (tmp-dir)]
    (run {:dir root} "git" "init" "-q")
    (run {:dir root} "git" "config" "user.email" "test@example.com")
    (run {:dir root} "git" "config" "user.name" "Test User")
    (write-file (fs/path root "README.md") "initial\n")
    (run {:dir root} "git" "add" "README.md")
    (run {:dir root} "git" "commit" "-q" "-m" "Initial")
    (setup-pack! root ["specifier" "coder"])
    (create-task root "HTW" "specifier")
    (let [task-id (:id (task-card root "HTW"))
          base (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
      (write-file (fs/path root "tasks/HTW.md") "# HTW\n\nImplement the stories.\n")
      (write-file (fs/path root "extra.md") "first offer\n")
      (run {:dir root} "git" "add" "tasks/HTW.md" "extra.md")
      (run {:dir root} "git" "commit" "-q" "-m" "Offer")
      (let [offer (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
            pending (fs/path root ".swarmforge/handoffs/pending_approval/50_offer.handoff")]
        (write-file pending
                    (str "from: specifier\nto: coder\ntype: git_handoff\n"
                         "task_id: " task-id "\ntask: HTW\n"
                         "commit: " offer "\n"
                         "task_base_commit: " base "\n\n"
                         "payload\n"))
        (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                    "50_offer" "use an RNG"))))
        (write-file (fs/path root "more.md") "stacked\n")
        (run {:dir root} "git" "add" "more.md")
        (run {:dir root} "git" "commit" "-q" "-m" "Stacked")
        (write-file (fs/path root "tmp/retry.handoff")
                    "type: git_handoff\nto: coder\npriority: 50\ntask: HTW\n")
        (let [opts {:dir root :env {"SWARMFORGE_ROLE" "specifier"} :ok? false}
              first-call (run opts (script "swarm_handoff.sh")
                              (str (fs/path root "tmp/retry.handoff")))]
          (is (zero? (:exit first-call)))
          (is (str/includes? (:out first-call) "AUDIT_REQUIRED"))
          (let [queued (run (assoc opts :ok? true) (script "swarm_handoff.sh")
                            (str (fs/path root "tmp/retry.handoff")))
                outbox (fs/glob (fs/path root ".swarmforge/handoffs/outbox") "*.handoff")
                content (slurp (str (first outbox)))]
            (is (zero? (:exit queued)))
            (is (str/includes? content "artifacts:"))
            (is (str/includes? content "extra.md"))
            (is (str/includes? content "more.md"))
            (is (str/includes? content "tasks/HTW.md"))))))))
(deftest pack-web-serves-a-document
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        result (pack-web root false "--test-doc" (str root) "tasks/HTW.md")]
    (is (zero? (:exit result)))
    (is (str/includes? (:out result) "HTW"))
    (is (str/includes? (:out result) "Integrate HTW stories"))))
(deftest pack-web-saves-remedial-comments-on-the-pending-approval
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        _ (write-file (fs/path root "features/console.feature") "Feature: cave\n")
        _ (write-pending-approval! root {:id "50_hello" :task "HTW"
                                         :artifacts "features/console.feature"})
        saved (pack-web root false "--test-save-comments" (str root)
                        "50_hello" "features/console.feature" "use an RNG")
        reviews (get (first (get (raw-state root) "approvals")) "reviews")]
    (is (zero? (:exit saved)))
    (is (= "use an RNG" (get reviews "features/console.feature")))
    (let [blanked (pack-web root false "--test-save-comments" (str root)
                            "50_hello" "features/console.feature" "  \n")
          after (get (first (get (raw-state root) "approvals")) "reviews")]
      (is (zero? (:exit blanked)))
      (is (= "" (get after "features/console.feature")))
      (is (contains? after "features/console.feature")))))
(deftest pack-web-document-api-keeps-comment-history-and-last-diff
  (let [root (tmp-dir)
        _ (run {:dir root} "git" "init" "-q")
        _ (run {:dir root} "git" "config" "user.email" "test@example.com")
        _ (run {:dir root} "git" "config" "user.name" "Test User")
        _ (setup-pack! root)
        _ (write-file (fs/path root "features/console.feature") "Feature: cave\n")
        _ (run {:dir root} "git" "add" "features/console.feature")
        _ (run {:dir root} "git" "commit" "-q" "-m" "base")
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))
        first-sha (do (write-file (fs/path root "features/console.feature")
                                  "Feature: cave\n---\n  Scenario: one\n")
                      (run {:dir root} "git" "add" "features/console.feature")
                      (run {:dir root} "git" "commit" "-q" "-m" "offer-1")
                      (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD"))))
        _ (write-file
           (fs/path root ".swarmforge/handoffs/pending_approval/50_first.handoff")
           (str "from: specifier\nto: coder\ntype: git_handoff\n"
                "task_id: " task-id "\ntask: HTW\n"
                "commit: " first-sha "\n"
                "artifacts: features/console.feature\n\npayload\n"))
        first-doc (json/parse-string
                   (:out (pack-web root true "--test-api-doc" (str root)
                                   "features/console.feature" "50_first"))
                   true)]
    (is (false? (:has_diff first-doc)))
    (is (= [] (:history first-doc)))
    (is (str/includes? (:text first-doc) "Feature: cave"))
    (pack-web root true "--test-save-comments" (str root)
              "50_first" "features/console.feature" "needs an RNG")
    (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                "50_first" "retry the spec"))))
    (write-file (fs/path root "features/console.feature") "Feature: cave\n  Scenario: two\n")
    (run {:dir root} "git" "add" "features/console.feature")
    (run {:dir root} "git" "commit" "-q" "-m" "offer-2")
    (let [second-sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
      (write-file
       (fs/path root ".swarmforge/handoffs/pending_approval/50_second.handoff")
       (str "from: specifier\nto: coder\ntype: git_handoff\n"
            "task_id: " task-id "\ntask: HTW\n"
            "commit: " second-sha "\n"
            "artifacts: features/console.feature\n\npayload\n"))
      (let [doc (json/parse-string
                 (:out (pack-web root true "--test-api-doc" (str root)
                                 "features/console.feature" "50_second"))
                 true)
            hist (vec (:history doc))]
        (is (true? (:has_diff doc)))
        (is (= 2 (count hist)))
        (is (= "needs an RNG" (:text (first hist))))
        (is (= "retry the spec" (:text (second hist))))
        (is (not (str/blank? (:at (first hist)))))
        (is (not (str/blank? (:at (second hist)))))
        (is (some #(and (= "del" (:type %)) (str/includes? (str (:text %)) "one"))
                  (:lines doc)))
        (is (some #(and (= "del" (:type %)) (= "---" (:text %)))
                  (:lines doc)))
        (is (some #(and (= "add" (:type %)) (str/includes? (str (:text %)) "two"))
                  (:lines doc)))
        (is (some #(and (= "same" (:type %)) (str/includes? (str (:text %)) "Feature: cave"))
                  (:lines doc)))))
    (pack-web root true "--test-approve" (str root) "50_second")
    (is (not (fs/exists? (fs/path root ".swarmforge/rejected-tasks" task-id "reviews.json"))))))
(deftest pack-web-document-api-hides-diff-when-git-fails
  (let [root (tmp-dir)
        _ (run {:dir root} "git" "init" "-q")
        _ (run {:dir root} "git" "config" "user.email" "test@example.com")
        _ (run {:dir root} "git" "config" "user.name" "Test User")
        _ (setup-pack! root)
        _ (write-file (fs/path root "features/console.feature") "Feature: cave\n")
        _ (run {:dir root} "git" "add" "features/console.feature")
        _ (run {:dir root} "git" "commit" "-q" "-m" "base")
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))
        sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
    (run {:dir root} "git" "branch" "-f" (str "rejected/" task-id "/latest") sha)
    (write-file
     (fs/path root ".swarmforge/handoffs/pending_approval/50_hello.handoff")
     (str "from: specifier\nto: coder\ntype: git_handoff\n"
          "task_id: " task-id "\ntask: HTW\n"
          "commit: notacommit\n"
          "artifacts: features/console.feature\n\npayload\n"))
    (let [doc (json/parse-string
               (:out (pack-web root true "--test-api-doc" (str root)
                               "features/console.feature" "50_hello"))
               true)]
      (is (false? (:has_diff doc)))
      (is (= [] (:lines doc))))))
(deftest pack-web-approve-discards-remedial-comments
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        _ (write-pending-approval! root {:id "50_hello" :task "HTW"})
        _ (pack-web root false "--test-save-comments" (str root)
                    "50_hello" "features/console.feature" "use an RNG")
        result (pack-web root false "--test-approve" (str root) "50_hello")]
    (is (zero? (:exit result)))
    (is (= [] (pending-names root)))
    (is (not (fs/exists? (fs/path root ".swarmforge/handoffs/pending_approval/50_hello.reviews.json"))))))
(deftest pack-web-retry-delivers-remedial-comments-to-master
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))]
    (setup-pack! root)
    (create-task root "HTW" "specifier")
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (let [task-id (:id (task-card root "HTW"))]
      (write-pending-approval! root {:id "50_hello" :task "HTW" :task-id task-id})
      (pack-web root false "--test-save-comments" (str root)
                "50_hello" "features/console.feature" "use an RNG")
      (let [result (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                                 "--test-retry-task" (str root) "50_hello" "")
            argv (read-argv argv-file)
            injected (str (last (first argv)))]
        (is (zero? (:exit result)))
        (is (str/includes? injected "features/console.feature"))
        (is (str/includes? injected "use an RNG"))
        (is (not (str/includes? injected "New Task")))
        (is (not (fs/exists? (fs/path root ".swarmforge/handoffs/pending_approval/50_hello.reviews.json"))))))))
(deftest pack-web-post-task-duplicate-keeps-the-server
  ;; Given a card named HTW
  ;; When POST /api/tasks uses HTW again
  ;; Then it reports Duplicate and does not create a second card
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (pack-web root true "--test-post-task" (str root) "HTW" "first")
        duplicate (pack-web root false "--test-post-task" (str root) "HTW" "second")
        listed (:out (list-tasks root))
        htw-rows (filter #(str/starts-with? % "HTW\t") (str/split-lines listed))]
    (is (not (zero? (:exit duplicate))))
    (is (str/includes? (str (:err duplicate) (:out duplicate)) "Duplicate"))
    (is (= 1 (count htw-rows)))))
(deftest pack-web-unknown-approval-keeps-the-server
  ;; Given a pack with no pending approval
  ;; When POST /api/approvals/missing/approve
  ;; Then it reports Unknown approval and the next request still works
  (let [root (tmp-dir)
        _ (setup-pack! root)
        result (pack-web root false "--test-approve" (str root) "no-such-id")]
    (is (not (zero? (:exit result))))
    (is (str/includes? (:out result) "error"))
    (is (str/includes? (str (:err result) (:out result)) "Unknown approval"))
    (is (zero? (:exit (pack-web root false "--test-state" (str root)))))))
(deftest pack-web-unknown-clarification-keeps-the-server
  ;; Given a pack with no pending clarification
  ;; When POST /api/clarifications/missing/answer
  ;; Then it reports Unknown clarification and the next request still works
  (let [root (tmp-dir)
        _ (setup-pack! root)
        result (pack-web root false "--test-answer-clarification"
                         (str root) "no-such-id" "nope")]
    (is (not (zero? (:exit result))))
    (is (str/includes? (:out result) "error"))
    (is (str/includes? (str (:err result) (:out result)) "Unknown clarification"))
    (is (zero? (:exit (pack-web root false "--test-state" (str root)))))))
(deftest pack-web-clarification-posts-to-attention-and-answers-into-the-role
  ;; Given QA posts a clarification question
  ;; When the operator answers
  ;; Then /api/state listed it and the answer is injected into QA with the durable id
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        question (fs/path root "tmp" "question.txt")]
    (setup-pack! root ["QA"])
    (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "tmux.sock") "\n"))
    (write-file question "Does the bat drop to any of 20 rooms?\n")
    (let [created (run {:dir root :env {"SWARMFORGE_ROLE" "QA"}}
                       (script "pack_dashboard_request.sh")
                       "clarify" (str question))
          id (str/trim (:out created))
          pending (web-state root)
          item (first (:clarifications pending))]
      (is (zero? (:exit created)))
      (is (str/starts-with? id "clar-"))
      (is (= "QA" (:role item)))
      (is (str/includes? (:body item) "Does the bat drop to any of 20 rooms?"))
      (is (= "pending" (:status item)))
      (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                    "--test-answer-clarification" (str root) id "Yes, 1 to 20.\nUse all rooms.")
      (let [argv (slurp argv-file)
            done (first (:clarifications (web-state root)))
            stored (slurp (str (first (fs/list-dir
                                       (fs/path root ".swarmforge/dashboard/clarifications/done")))))]
        (is (str/includes? argv id))
        (is (str/includes? argv "Yes, 1 to 20."))
        (is (str/includes? argv "Use all rooms."))
        (is (= "done" (:status done)))
        (is (= "Yes, 1 to 20.\nUse all rooms." (:response done)))
        (is (str/includes? stored "response: Yes, 1 to 20.\\nUse all rooms.\n"))))))
(deftest pack-web-serves-the-task-body
  ;; Given New Task HTW with body
  ;; When pack_web --test-task HTW
  ;; Then it prints the name and body
  (let [root (tmp-dir)
        text "Find the stories in ~/junk/htw-stories and implement them."]
    (setup-pack! root)
    (pack-board root true
                "create" "--root" (str root)
                "--name" "HTW" "--type" "component" "--text" text)
    (let [result (pack-web root false "--test-task" (str root) "HTW")]
      (is (zero? (:exit result)))
      (is (str/includes? (:out result) "HTW"))
      (is (str/includes? (:out result) text)))))
(deftest pack-web-work-queue-lists-every-in-process-task
  ;; Given two in-process handoffs on architect
  ;; When --test-state
  ;; Then the row's task is the first name and tasks lists both
  (let [root (tmp-dir)
        roles ["specifier" "architect"]]
    (setup-pack! root roles)
    (put-in-process! root roles "architect"
                     {:from "cleaner" :task "HTW"
                      :filename "10_from_cleaner_htw.handoff"})
    (put-in-process! root roles "architect"
                     {:from "cleaner" :task "Command Syntax"
                      :filename "11_from_cleaner_cs.handoff"})
    (let [row (some #(when (= "architect" (:role %)) %)
                    (:work_in_flight (web-state root)))]
      (is (= "HTW" (:task row)))
      (is (= ["HTW" "Command Syntax"] (:tasks row))))))
(deftest pack-web-work-queue-marks-only-real-batches
  ;; Given a real in-process batch on architect
  ;; When --test-state
  ;; Then the batch task names are exposed for the dashboard + indicator
  (let [root (tmp-dir)
        roles ["specifier" "architect"]]
    (setup-pack! root roles)
    (put-in-process! root roles "architect"
                     {:from "cleaner" :task "HTW"
                      :filename "batch_20260615T000001Z_000001/10_from_cleaner_htw.handoff"})
    (put-in-process! root roles "architect"
                     {:from "cleaner" :task "Command Syntax"
                      :filename "batch_20260615T000001Z_000001/11_from_cleaner_cs.handoff"})
    (let [row (some #(when (= "architect" (:role %)) %)
                    (:work_in_flight (web-state root)))]
      (is (= "HTW" (:task row)))
      (is (= ["HTW" "Command Syntax"] (:tasks row)))
      (is (= ["HTW" "Command Syntax"] (:batch_tasks row))))))
(deftest pack-web-clarification-answer-echoes-the-question
  ;; Given QA asked a clarification
  ;; When the operator answers
  ;; Then the injected pane text includes the question and Clarification requested from
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        question (fs/path root "tmp" "question.txt")]
    (setup-pack! root ["QA"])
    (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "tmux.sock") "\n"))
    (write-file question "Does the bat drop to any of 20 rooms?\n")
    (let [id (str/trim (:out (run {:dir root :env {"SWARMFORGE_ROLE" "QA"}}
                                  (script "pack_dashboard_request.sh")
                                  "clarify" (str question))))]
      (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                    "--test-answer-clarification" (str root) id "Yes, 1 to 20.")
      (let [argv (slurp argv-file)]
        (is (str/includes? argv "Clarification requested from: QA"))
        (is (str/includes? argv "Does the bat drop to any of 20 rooms?"))
        (is (str/includes? argv "Yes, 1 to 20."))))))
(deftest pack-web-file-viewer-kinds
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")]
    (write-file (fs/path root "src/x.clj") "(def x :k) ; c\n")
    (write-file (fs/path root "data.json") "{\"a\":1}")
    (write-file (fs/path root "tasks/Ui.md") "# Ui\n\nHello\n")
    (write-file (fs/path root "features/console.feature")
                (str "@wip\n"
                     "Feature: console\n"
                     "  # hunt\n"
                     "  Scenario: start\n"
                     "    Given a cave named \"pit\"\n"
                     "    When I go to <room>\n"
                     "    | name |\n"
                     "    | pit  |\n"))
    (write-file (fs/path root "blob.bin") (str (char 0) (char 1) "Hi"))
    (let [clj (json/parse-string
               (:out (pack-web root false "--test-file" (str root) "HTW" "src/x.clj"))
               true)
          json-body (json/parse-string
                     (:out (pack-web root false "--test-file" (str root) "HTW" "data.json"))
                     true)
          md (json/parse-string
              (:out (pack-web root false "--test-file" (str root) "HTW" "tasks/Ui.md"))
              true)
          feature (json/parse-string
                   (:out (pack-web root false "--test-file" (str root) "HTW"
                                   "features/console.feature"))
                   true)
          bin (json/parse-string
               (:out (pack-web root false "--test-file" (str root) "HTW" "blob.bin"))
               true)]
      (is (= "code" (:kind clj)))
      (is (str/includes? (str (:html clj)) "class='kw'"))
      (is (str/includes? (str (:html clj)) "class='cmt'"))
      (is (= "code" (:kind json-body)))
      (is (str/includes? (str (:html json-body)) "class='str'"))
      (is (= "text" (:kind md)))
      (is (str/includes? (:text md) "# Ui"))
      (is (nil? (:html md)))
      (is (= "code" (:kind feature)))
      (is (str/includes? (str (:html feature)) "class='kw'"))
      (is (str/includes? (str (:html feature)) "class='tag'"))
      (is (str/includes? (str (:html feature)) "class='cmt'"))
      (is (str/includes? (str (:html feature)) "class='str'"))
      (is (str/includes? (str (:html feature)) "class='ph'"))
      (is (str/includes? (str (:html feature)) "class='tbl'"))
      (is (= "binary" (:kind bin)))
      (is (str/includes? (:text bin) "00000000"))
      (is (str/includes? (:text bin) "|")))))
(deftest pack-web-pane-merge-keeps-history-and-live-tail
  (let [root (tmp-dir)
        hist (fs/path root "hist.txt")
        vis (fs/path root "vis.txt")]
    (write-file hist "old history\nvisible line\n")
    (write-file vis "visible line\n")
    (let [kept (:out (pack-web root false "--test-pane-merge" (str hist) (str vis)))]
      (is (str/includes? kept "old history"))
      (is (str/includes? kept "visible line")))
    (write-file hist "old line\n")
    (write-file vis "old line\nnew tail still on screen\n")
    (let [tail (:out (pack-web root false "--test-pane-merge" (str hist) (str vis)))]
      (is (str/includes? tail "new tail still on screen"))
      (is (= 1 (count (re-seq #"old line" tail)))))))
(deftest pack-web-retry-audit-writes-findings-not-candidate
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))]
    (write-file (fs/path root ".swarmforge/handoffs/pending_approval/50_hello.handoff")
                (str "from: specifier\nto: coder\ntype: git_handoff\n"
                     "task_id: " task-id "\ntask: HTW\n"
                     "artifacts: features/console.feature\n\npayload\n"))
    (pack-web root true "--test-save-comments" (str root)
              "50_hello" "features/console.feature" "use an RNG")
    (pack-web root true "--test-retry-task" (str root) "50_hello" "retry note")
    (let [page (:out (pack-web root false "--test-task" (str root) "HTW"))]
      (is (str/includes? page "use an RNG"))
      (is (str/includes? page "retry note"))
      (is (not (str/includes? page ":candidate"))))))
