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
  ;; When POST /api/tasks records name, text, and a configured type
  ;; Then the card is component in waiting with no start note
  (let [root (tmp-dir)
        text "Integrate HTW stories"]
    (setup-pack! root six-pack-roles)
    (let [result (pack-web root true "--test-post-task" (str root) "htw-console-app" text "" "component")
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

(deftest pack-web-state-groups-a-propagated-batch-in-task-mode
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        _ (setup-pack! root roles)
        _ (create-task root "pits" "cleaner")
        _ (create-task root "bats" "cleaner")
        ids (mapv #(:id (task-card root %)) ["pits" "bats"])
        _ (put-in-process! root roles "cleaner"
                           {:from "coder" :task "pits" :task-id (first ids)
                            :batch-task-ids ids})
        state (web-state root)
        by-name (into {} (map (juxt :name identity) (:tasks state)))
        row (some #(when (= "cleaner" (:role %)) %) (:work_in_flight state))]
    (is (= (get-in by-name ["pits" :batch])
           (get-in by-name ["bats" :batch])))
    (is (some? (get-in by-name ["pits" :batch])))
    (is (= ["pits" "bats"] (:tasks row)))
    (is (= ["pits" "bats"] (:batch_tasks row)))))
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
        _ (queue-inbox-mail! root roles "coder" {:from "specifier" :task "jump"})
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
        _ (create-task root "Command Syntax" "specifier")
        _ (queue-inbox-mail! root six-pack-roles "specifier" {:from "(New Task)" :task "Command Syntax"})]
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
                           (script "done_with_current.sh") "--drop"))))
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
    (is (= "code" (:kind first-doc)))
    (is (str/includes? (str (:html first-doc)) "class='kw'"))
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

(def long-name (apply str (repeat 81 "x")))

(defn api-post [root uri body]
  (json/parse-string
   (:out (pack-web root true "--test-post" (str root) uri (json/generate-string body)))
   true))

(defn outbox-handoffs [root]
  (let [dir (fs/path root ".swarmforge/handoffs/outbox")]
    (mapv #(fs/path dir %) (sort (handoff-names dir)))))

(defn inbox-new-path [root roles role filename]
  (fs/path (pack-worktree root roles role) ".swarmforge/handoffs/inbox/new" filename))

(defn put-queued! [root roles role {:keys [task task-id priority from]}]
  (let [priority (or priority "50")
        file (inbox-new-path root roles role
                             (str priority "_20260924T000000Z_000001_from_" (or from "specifier")
                                  "_to_" role "_" (str/replace task #"\W+" "") ".handoff"))]
    (write-file file
                (str "from: " (or from "specifier") "\n"
                     "to: " role "\n"
                     "recipient: " role "\n"
                     "priority: " priority "\n"
                     "type: git_handoff\n"
                     (when task-id (str "task_id: " task-id "\n"))
                     "task: " task "\n"
                     "\n"
                     "payload\n"))
    file))

(deftest pack-web-post-task-rejects-a-name-over-eighty-characters
  ;; Given a pack
  ;; When POST /api/tasks gets an 81-character name
  ;; Then it answers 400 with the limit and creates no card
  (let [root (tmp-dir)
        _ (setup-pack! root)
        resp (api-post root "/api/tasks" {:name long-name :text "too long"})]
    (is (= 400 (:status resp)))
    (is (str/includes? (get-in resp [:body :error]) "no longer than 80 characters"))
    (is (nil? (task-lane root long-name)))
    (is (empty? (outbox-handoffs root)))))

(deftest pack-web-rename-task-keeps-the-card-id
  ;; Given card HTW created from the dashboard
  ;; When POST /api/tasks/rename to Hunt
  ;; Then the card carries Hunt with the same id in the same lane, and long names are refused
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (api-post root "/api/tasks" {:name "HTW" :text "x"})
        id (:id (task-card root "HTW"))
        resp (api-post root "/api/tasks/rename" {:name "HTW" :to "Hunt"})
        long (api-post root "/api/tasks/rename" {:name "Hunt" :to long-name})
        missing (api-post root "/api/tasks/rename" {:name "Nope" :to "Yes"})]
    (is (= 200 (:status resp)))
    (is (= id (:id (task-card root "Hunt"))))
    (is (= "waiting" (task-lane root "Hunt")))
    (is (nil? (task-lane root "HTW")))
    (is (= 400 (:status long)))
    (is (= 404 (:status missing)))))

(deftest pack-web-single-queued-card-shows-queued-not-pane-status
  ;; Given coder has one card whose mail waits in inbox/new and nothing in process
  ;; When the dashboard state is read with a busy pane
  ;; Then the card says waiting in queue, is marked queued, and carries the queue priority
  (let [root (tmp-dir)
        roles ["specifier" "coder"]
        _ (setup-pack! root roles)
        _ (create-task root "C11" "coder")
        _ (put-queued! root roles "coder" {:task "C11" :task-id (:id (task-card root "C11")) :priority "30"})
        result (pack-web-env root {} "--test-status-pane" (str root)
                             "I'm hardening something else.\nesc to interrupt · 1s\n")
        card (some #(when (= "C11" (:name %)) %) (:tasks (json/parse-string (:out result) true)))]
    (is (= "waiting in queue" (:status card)))
    (is (true? (:queued card)))
    (is (= "coder" (:queue_role card)))
    (is (= "30" (:queue_priority card)))))

(deftest pack-web-dequeue-removes-a-queued-card
  ;; Given cards C20 and C21 queued in coder inbox/new
  ;; When POST /api/tasks/dequeue C20
  ;; Then C20 is gone from the inbox and the board, archived under removed-tasks, and C21 stays
  (let [root (tmp-dir)
        roles ["specifier" "coder"]
        _ (setup-pack! root roles)
        _ (create-task root "C20" "coder")
        _ (create-task root "C21" "coder")
        id (:id (task-card root "C20"))
        _ (put-queued! root roles "coder" {:task "C20" :task-id id})
        _ (put-queued! root roles "coder" {:task "C21" :task-id (:id (task-card root "C21"))})
        resp (api-post root "/api/tasks/dequeue" {:name "C20"})
        left (inbox-names root roles "coder")]
    (is (= 200 (:status resp)))
    (is (nil? (task-lane root "C20")))
    (is (= "coder" (task-lane root "C21")))
    (is (= 1 (count left)))
    (is (str/includes? (slurp (str (inbox-new-path root roles "coder" (first left)))) "task: C21\n"))
    (is (= 1 (count (handoff-names (fs/path root ".swarmforge/removed-tasks" id)))))
    (is (fs/exists? (fs/path root ".swarmforge/removed-tasks" id "C20.txt")))))

(deftest pack-web-dequeue-and-priority-refuse-a-card-in-progress
  ;; Given card C30 in coder in_process
  ;; When POST /api/tasks/dequeue or /api/tasks/priority
  ;; Then both answer 409 and the card and mail stay
  (let [root (tmp-dir)
        roles ["specifier" "coder"]
        _ (setup-pack! root roles)
        _ (create-task root "C30" "coder")
        _ (put-in-process! root roles "coder" {:from "specifier" :task "C30"})
        dq (api-post root "/api/tasks/dequeue" {:name "C30"})
        pr (api-post root "/api/tasks/priority" {:name "C30" :priority "10"})]
    (is (= 409 (:status dq)))
    (is (str/includes? (get-in dq [:body :error]) "in progress"))
    (is (= 409 (:status pr)))
    (is (= "coder" (task-lane root "C30")))
    (is (= 1 (count (handoff-names (in-process-dir root roles "coder")))))))

(deftest pack-web-priority-reorders-a-queued-card
  ;; Given C10b and C22 queued in coder at priority 50
  ;; When POST /api/tasks/priority C10b to 10
  ;; Then C10b's file starts with 10_, its header says priority 10, and it sorts first
  (let [root (tmp-dir)
        roles ["specifier" "coder"]
        _ (setup-pack! root roles)
        _ (create-task root "C22" "coder")
        _ (create-task root "C10b" "coder")
        _ (put-queued! root roles "coder" {:task "C22" :task-id (:id (task-card root "C22")) :from "a"})
        _ (put-queued! root roles "coder" {:task "C10b" :task-id (:id (task-card root "C10b")) :from "b"})
        resp (api-post root "/api/tasks/priority" {:name "C10b" :priority 10})
        names (sort (inbox-names root roles "coder"))
        first-file (slurp (str (inbox-new-path root roles "coder" (first names))))
        bad (api-post root "/api/tasks/priority" {:name "C22" :priority "100"})]
    (is (= 200 (:status resp)))
    (is (str/starts-with? (first names) "10_"))
    (is (str/includes? first-file "task: C10b\n"))
    (is (str/includes? first-file "priority: 10\n"))
    (is (not (str/includes? first-file "priority: 50\n")))
    (is (= 400 (:status bad)))
    (is (= "30" (:queue_priority
                 (do (api-post root "/api/tasks/priority" {:name "C22" :priority "30"})
                     (task-card root "C22")))))))

(deftest pack-web-dequeue-refuses-a-card-sharing-its-batch
  (let [root (tmp-dir)
        roles ["specifier" "coder"]
        _ (setup-pack! root roles)
        _ (create-task root "C40" "coder")
        _ (create-task root "C41" "coder")
        c40 (:id (task-card root "C40"))
        c41 (:id (task-card root "C41"))]
    (write-file (inbox-new-path root roles "coder" "50_both.handoff")
                (str "from: specifier\nto: coder\npriority: 50\ntype: git_handoff\n"
                     "task_id: " c40 "\ntask: C40\nbatch_id: b1\n"
                     "batch_task_ids: " (pr-str [c40 c41]) "\n\npayload\n"))
    (let [resp (api-post root "/api/tasks/dequeue" {:name "C41"})]
      (is (= 409 (:status resp)))
      (is (= "coder" (task-lane root "C41")))
      (is (= ["50_both.handoff"] (inbox-names root roles "coder"))))))

(deftest pack-web-dequeue-and-priority-refuse-mail-still-in-the-outbox
  ;; The daemon may be delivering outbox mail, so only inbox mail is changed
  (let [root (tmp-dir)
        roles ["specifier" "coder"]
        _ (setup-pack! root roles)
        _ (create-task root "C50" "coder")
        id (:id (task-card root "C50"))
        _ (queue-handoff! root {:from "specifier" :to "coder" :task "C50" :task-id id})
        dq (api-post root "/api/tasks/dequeue" {:name "C50"})
        pr (api-post root "/api/tasks/priority" {:name "C50" :priority "10"})]
    (is (= 409 (:status dq)))
    (is (= 409 (:status pr)))
    (is (= "coder" (task-lane root "C50")))
    (is (= 1 (count (outbox-handoffs root))))
    (fs/move (first (outbox-handoffs root))
             (inbox-new-path root roles "coder" "50_c50.handoff"))
    (is (= 200 (:status (api-post root "/api/tasks/priority" {:name "C50" :priority "10"}))))
    (is (= ["10_c50.handoff"] (inbox-names root roles "coder")))))

(defn ask-clarification! [root role text]
  (let [question (fs/path root "tmp" (str role "-question.txt"))]
    (write-file question text)
    (str/trim (:out (run {:dir root :env {"SWARMFORGE_ROLE" role}}
                         (script "pack_dashboard_request.sh")
                         "clarify" (str question))))))

(deftest pack-web-clarification-answer-also-reaches-extra-roles
  ;; Given coder asks about a scenario that specifier owns
  ;; When the operator answers with also = specifier
  ;; Then both coder and specifier panes get the answer and the record lists specifier
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))]
    (setup-pack! root ["specifier" "coder"])
    (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "tmux.sock") "\n"))
    (let [id (ask-clarification! root "coder" "Is VAC-3 still valid after C15?\n")
          resp (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                             "--test-post" (str root)
                             (str "/api/clarifications/" id "/answer")
                             (json/generate-string {:text "No, drop VAC-3." :also ["specifier" "coder"]}))
          argv (read-argv argv-file)
          answered (filter #(and (inject-literal %) (str/includes? (inject-literal %) "No, drop VAC-3.")) argv)
          done (first (:clarifications (web-state root)))]
      (is (= 200 (:status (json/parse-string (:out resp) true))))
      (is (= 2 (count answered)))
      (is (= 2 (count (set (map inject-target answered)))))
      (is (= "done" (:status done)))
      (is (= ["specifier"] (:also done))))))

(deftest pack-web-clarification-answer-refuses-an-unknown-extra-role
  ;; Given QA asks a question
  ;; When the operator answers with also = nobody
  ;; Then the answer is refused with 400 and the question stays pending
  (let [root (tmp-dir)]
    (setup-pack! root ["QA"])
    (let [id (ask-clarification! root "QA" "Which rooms?\n")
          resp (api-post root (str "/api/clarifications/" id "/answer") {:text "All." :also "nobody"})]
      (is (= 400 (:status resp)))
      (is (= "pending" (:status (first (:clarifications (web-state root)))))))))

(defn notify-log-lines [file]
  (if (fs/exists? file)
    (vec (remove str/blank? (str/split-lines (slurp (str file)))))
    []))

(deftest pack-web-notify-cmd-runs-once-per-new-question-and-approval
  ;; Given swarmforge.conf names a notify-cmd, QA asks a question and an approval is pending
  ;; When the dashboard notifier scans twice
  ;; Then notify-cmd ran once per item with event, id, role, and summary
  (let [root (tmp-dir)
        log (fs/path root "notify.log")
        hook (fs/path root "notify.sh")]
    (setup-pack! root ["specifier" "QA"])
    (write-file hook (str "#!/bin/sh\n"
                          "echo \"$1|$2|$3|$4|$SWARMFORGE_NOTIFY_EVENT|$SWARMFORGE_NOTIFY_TASK\" >> " log "\n"))
    (fs/set-posix-file-permissions hook "rwxr-xr-x")
    (write-file (fs/path root "swarmforge/swarmforge.conf")
                (str "window specifier codex master\n"
                     "window QA codex QA\n"
                     "notify-cmd " hook "\n"))
    (let [id (ask-clarification! root "QA" "Does the bat drop to any of 20 rooms?\n")]
      (write-file (fs/path root ".swarmforge/handoffs/pending_approval/50_from_specifier_to_QA.handoff")
                  "from: specifier\nto: QA\ntype: git_handoff\ntask: HTW\n\npayload\n")
      (pack-web root true "--test-notify-scan" (str root))
      (pack-web root true "--test-notify-scan" (str root))
      (let [lines (notify-log-lines log)]
        (is (= 2 (count lines)))
        (is (some #(str/starts-with? % (str "clarification|" id "|QA|Clarification from QA: Does the bat drop")) lines))
        (is (some #(str/starts-with? % "approval|50_from_specifier_to_QA|specifier|Approval needed: HTW") lines))
        (is (some #(str/ends-with? % "|approval|HTW") lines)))
      (ask-clarification! root "specifier" "Second question?\n")
      (pack-web root true "--test-notify-scan" (str root))
      (is (= 3 (count (notify-log-lines log)))))))

(deftest pack-web-without-notify-cmd-runs-nothing
  ;; Given no notify-cmd in swarmforge.conf
  ;; When the notifier scans with a pending question
  ;; Then nothing is recorded as notified
  (let [root (tmp-dir)]
    (setup-pack! root ["QA"])
    (write-file (fs/path root "swarmforge/swarmforge.conf") "window QA codex master\n")
    (ask-clarification! root "QA" "Which rooms?\n")
    (pack-web root true "--test-notify-scan" (str root))
    (is (not (fs/exists? (fs/path root ".swarmforge/dashboard/notified"))))))

(deftest pack-web-serve-records-its-port-and-falls-back-when-busy
  ;; Given a port held by another listener
  ;; When pack_web --serve asks for it
  ;; Then it serves on another port and records that port for the next start
  (with-open [s (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))]
    (let [root (tmp-dir)
          busy (.getLocalPort s)
          url-file (fs/path root ".swarmforge/dashboard-url")
          pb (doto (java.lang.ProcessBuilder. [(script "pack_web.sh") "--serve" (str root) (str busy)])
               (.directory (java.io.File. (str root))))
          _ (doto (.environment pb)
              (.put "PATH" (System/getenv "PATH")))
          proc (.start pb)]
      (try
        (is (wait-file url-file 10000))
        (let [url (str/trim (slurp (str url-file)))
              port (str/trim (slurp (str (fs/path root ".swarmforge/dashboard-port"))))]
          (is (= url (str "http://127.0.0.1:" port)))
          (is (not= (str busy) port)))
        (finally
          (.destroyForcibly proc)
          (.waitFor proc))))))

(deftest pack-web-state-reports-drain
  ;; Given a pack being drained while coder still has in-process work
  ;; When pack_web --test-state
  ;; Then drain is paused and not drained, and becomes drained once work clears
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (is (= false (:paused (:drain (web-state root)))))
    (write-file (fs/path root ".swarmforge/paused") "now\n")
    (put-in-process! root roles "coder" {:from "specifier" :task "cave-walk"})
    (let [drain (:drain (web-state root))]
      (is (= true (:paused drain)))
      (is (= false (:drained drain)))
      (is (= ["coder"] (mapv :role (:busy drain)))))
    (fs/delete-tree (in-process-dir root roles "coder"))
    (is (= true (:drained (:drain (web-state root)))))))

(defn git-init! [root]
  (run {:dir root} "git" "init" "-q")
  (run {:dir root} "git" "config" "user.email" "test@example.com")
  (run {:dir root} "git" "config" "user.name" "Test User"))

(defn commit-file! [root text]
  (write-file (fs/path root "story.md") text)
  (run {:dir root} "git" "add" "story.md")
  (run {:dir root} "git" "commit" "-q" "-m" text)
  (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD"))))

(defn held-handoff! [root task-id task commit]
  (write-file (fs/path root ".swarmforge/handoffs/pending_approval/50_offer.handoff")
              (str "from: specifier\nto: coder\ntype: git_handoff\n"
                   "task_id: " task-id "\ntask: " task "\n"
                   "commit: " commit "\n\npayload\n")))

(defn head-commit [root]
  (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD"))))

(defn retry-note-name? [name]
  (boolean (re-find #"^00_.*_retry_" name)))

(deftest pack-web-retry-keeps-later-work-when-the-specifier-moved-on
  ;; Given card A held for approval while the specifier already works on card B
  ;; When the operator retries A
  ;; Then HEAD and B stay, and A is queued again at the front of the specifier inbox
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (git-init! root)
    (setup-pack! root roles)
    (commit-file! root "base")
    (create-task root "A" "specifier")
    (create-task root "B" "specifier")
    (let [a-id (:id (task-card root "A"))
          b-id (:id (task-card root "B"))
          offer (commit-file! root "offer A")
          _ (held-handoff! root a-id "A" offer)
          later (commit-file! root "work on B")]
      (write-file (fs/path (in-process-dir root roles "specifier") "50_b.handoff")
                  (str "from: (New Task)\nto: specifier\npriority: 50\ntype: note\n"
                       "task_id: " b-id "\ntask: B\n\nB\n"))
      (is (zero? (:exit (pack-web root false "--test-retry-task" (str root) "50_offer" "fix A"))))
      (is (= later (head-commit root)))
      (is (= ["50_b.handoff"] (handoff-names (in-process-dir root roles "specifier"))))
      (is (some retry-note-name? (inbox-names root roles "specifier")))
      (is (= [] (pending-names root))))))

(deftest pack-web-delete-refuses-when-the-specifier-moved-on
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (git-init! root)
    (setup-pack! root roles)
    (commit-file! root "base")
    (create-task root "A" "specifier")
    (create-task root "B" "specifier")
    (let [a-id (:id (task-card root "A"))
          b-id (:id (task-card root "B"))
          offer (commit-file! root "offer A")
          _ (held-handoff! root a-id "A" offer)
          later (commit-file! root "work on B")]
      (write-file (fs/path (in-process-dir root roles "specifier") "50_b.handoff")
                  (str "from: (New Task)\nto: specifier\npriority: 50\ntype: note\n"
                       "task_id: " b-id "\ntask: B\n\nB\n"))
      (let [result (pack-web root false "--test-delete-approval" (str root) "50_offer")]
        (is (not (zero? (:exit result))))
        (is (= later (head-commit root)))
        (is (= "specifier" (task-lane root "A")))
        (is (= ["50_offer.handoff"] (pending-names root)))))))

(deftest pack-web-retry-clears-handed-marks-on-restored-mail
  ;; A retried card must be handed off again, so the restored mail forgets it was handed
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (git-init! root)
    (setup-pack! root roles)
    (commit-file! root "base")
    (create-task root "A" "specifier")
    (let [a-id (:id (task-card root "A"))
          offer (commit-file! root "offer A")
          done (fs/path root ".swarmforge/handoffs/inbox/completed/50_a.handoff")]
      (held-handoff! root a-id "A" offer)
      (write-file done (str "from: (New Task)\nto: specifier\npriority: 50\ntype: note\n"
                            "task_id: " a-id "\ntask: A\nhanded_task_ids: " a-id "\n\nA\n"))
      (is (zero? (:exit (pack-web root false "--test-retry-task" (str root) "50_offer" "again"))))
      (let [restored (fs/path (in-process-dir root roles "specifier") "50_a.handoff")]
        (is (fs/exists? restored))
        (is (not (str/includes? (slurp (str restored)) "handed_task_ids")))))))

(deftest pack-web-retry-and-delete-keep-a-later-card-already-delivered
  ;; Given card A held while the specifier already delivered card B on top of it and went idle
  ;; When the operator retries or deletes A
  ;; Then Delete refuses, Retry keeps HEAD and queues A again
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (git-init! root)
    (setup-pack! root roles)
    (commit-file! root "base")
    (create-task root "A" "specifier")
    (create-task root "B" "specifier")
    (let [a-id (:id (task-card root "A"))
          b-id (:id (task-card root "B"))
          offer (commit-file! root "offer A")
          _ (held-handoff! root a-id "A" offer)
          later (commit-file! root "offer B")]
      (write-file (fs/path root ".swarmforge/handoffs/sent/50_b.handoff")
                  (str "from: specifier\nto: coder\ntype: git_handoff\n"
                       "task_id: " b-id "\ntask: B\ncommit: " later "\n\npayload\n"))
      (is (not (zero? (:exit (pack-web root false "--test-delete-approval" (str root) "50_offer")))))
      (is (= later (head-commit root)))
      (is (zero? (:exit (pack-web root false "--test-retry-task" (str root) "50_offer" "fix A"))))
      (is (= later (head-commit root)))
      (is (some retry-note-name? (inbox-names root roles "specifier"))))))

(deftest pack-web-retry-requeue-carries-every-card-of-the-batch
  ;; Given batch A+B held while the specifier works on C
  ;; When the operator retries
  ;; Then the retry note carries the whole batch and B's mail forgets it was handed
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (doseq [n ["A" "B" "C"]] (create-task root n "specifier"))
    (let [[a b c] (map #(:id (task-card root %)) ["A" "B" "C"])
          b-done (fs/path root ".swarmforge/handoffs/inbox/completed/50_b.handoff")]
      (write-file (fs/path root ".swarmforge/handoffs/pending_approval/50_offer.handoff")
                  (str "from: specifier\nto: coder\ntype: git_handoff\n"
                       "task_id: " a "\ntask: A\nbatch_id: b1\n"
                       "batch_task_ids: " (pr-str [a b]) "\n\npayload\n"))
      (write-file b-done (str "from: (New Task)\nto: specifier\ntype: note\n"
                              "task_id: " b "\ntask: B\nhanded_task_ids: " b "\n\nB\n"))
      (write-file (fs/path (in-process-dir root roles "specifier") "50_c.handoff")
                  (str "from: (New Task)\nto: specifier\ntype: note\ntask_id: " c "\ntask: C\n\nC\n"))
      (is (zero? (:exit (pack-web root false "--test-retry-task" (str root) "50_offer" "again"))))
      (let [note (first (filter retry-note-name? (inbox-names root roles "specifier")))]
        (is (str/includes? (slurp (str (inbox-new-path root roles "specifier" note)))
                           (str "batch_task_ids: " (pr-str [a b]) "\n")))
        (is (not (str/includes? (slurp (str b-done)) "handed_task_ids")))))))

(deftest pack-web-delete-approval-keeps-other-cards-mail
  ;; Deleting A refuses while live mail carries A in a batch with B,
  ;; and once that mail is history, keeps it and drops only A
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (create-task root "A" "specifier")
    (create-task root "B" "specifier")
    (let [a (:id (task-card root "A"))
          b (:id (task-card root "B"))
          b-sent (fs/path root ".swarmforge/handoffs/sent/50_b.handoff")
          b-live (inbox-new-path root roles "coder" "50_b.handoff")
          b-mail (str "from: specifier\nto: coder\ntype: git_handoff\n"
                      "task_id: " b "\ntask: B\nbatch_id: b1\n"
                      "batch_task_ids: " (pr-str [b a]) "\n\npayload\n")]
      (write-file (fs/path root ".swarmforge/handoffs/pending_approval/50_offer.handoff")
                  (str "from: specifier\nto: coder\ntype: git_handoff\n"
                       "task_id: " a "\ntask: A\n\npayload\n"))
      (write-file b-live b-mail)
      (is (not (zero? (:exit (pack-web root false "--test-delete-approval" (str root) "50_offer")))))
      (is (= "specifier" (task-lane root "A")))
      (fs/move b-live b-sent)
      (is (zero? (:exit (pack-web root false "--test-delete-approval" (str root) "50_offer"))))
      (is (nil? (task-lane root "A")))
      (is (fs/exists? b-sent)))))

(defn- in-process-entries [root roles role]
  (let [dir (in-process-dir root roles role)]
    (if (fs/exists? dir)
      (sort (map #(str (fs/file-name %)) (fs/list-dir dir)))
      [])))

(defn- held-batch! [root a b]
  (write-file (fs/path root ".swarmforge/handoffs/pending_approval/50_offer.handoff")
              (str "from: specifier\nto: coder\ntype: git_handoff\n"
                   "task_id: " a "\ntask: A\nbatch_id: b1\n"
                   "batch_task_ids: " (pr-str [a b]) "\n\npayload\n")))

(defn- completed-mail! [root dir name id task completed-at]
  (write-file (fs/path root ".swarmforge/handoffs/inbox/completed" dir name)
              (str "from: (New Task)\nto: specifier\npriority: 50\ntype: note\n"
                   "task_id: " id "\ntask: " task "\nhanded_task_ids: " id
                   "\ncompleted_at: " completed-at "\n\n" task "\n")))

(defn- batch-mode! [root]
  (let [file (fs/path root ".swarmforge/roles.tsv")]
    (spit (str file) (str/replace-first (slurp (str file)) "\ttask\t" "\tbatch\t"))))

(deftest pack-web-retry-restores-a-batch-senders-cards-as-one-batch
  ;; Given a batch-mode specifier whose batch of A and B is held
  ;; When the operator retries
  ;; Then A's and B's mail come back in one batch folder that ready_for_next accepts
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (batch-mode! root)
    (doseq [n ["A" "B"]] (create-task root n "specifier"))
    (let [[a b] (map #(:id (task-card root %)) ["A" "B"])
          old-batch "batch_20261001T100000Z_000001"]
      (held-batch! root a b)
      (completed-mail! root old-batch "50_a.handoff" a "A" "2026-10-01T10:00:00Z")
      (completed-mail! root old-batch "50_b.handoff" b "B" "2026-10-01T10:00:00Z")
      (is (zero? (:exit (pack-web root false "--test-retry-task" (str root) "50_offer" "again"))))
      (let [[batch & more] (in-process-entries root roles "specifier")
            batch-dir (fs/path (in-process-dir root roles "specifier") batch)]
        (is (str/starts-with? batch "batch_"))
        (is (empty? more))
        (is (= ["50_a.handoff" "50_b.handoff"] (sort (handoff-names batch-dir))))
        (is (not-any? #(str/includes? (slurp (str %)) "handed_task_ids")
                      (fs/glob batch-dir "*.handoff"))))
      (let [result (run {:dir root :env {"SWARMFORGE_ROLE" "specifier"} :ok? false}
                        (script "ready_for_next.sh"))]
        (is (zero? (:exit result)) (:err result))
        (is (str/includes? (:out result) "COUNT: 2"))))))

(deftest pack-web-retry-gives-a-task-sender-one-mail-for-several-cards
  ;; Given a task-mode specifier whose batch of A and B is held, with an older round of A
  ;; When the operator retries
  ;; Then one retry note naming both cards is in process and every mail stays completed
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (doseq [n ["A" "B"]] (create-task root n "specifier"))
    (let [[a b] (map #(:id (task-card root %)) ["A" "B"])]
      (held-batch! root a b)
      (completed-mail! root "" "50_a_old.handoff" a "A" "2026-09-30T10:00:00Z")
      (completed-mail! root "" "50_a.handoff" a "A" "2026-10-01T10:00:00Z")
      (completed-mail! root "" "50_b.handoff" b "B" "2026-10-01T10:00:00Z")
      (is (zero? (:exit (pack-web root false "--test-retry-task" (str root) "50_offer" "again"))))
      (let [entries (in-process-entries root roles "specifier")
            note (slurp (str (fs/path (in-process-dir root roles "specifier") (first entries))))]
        (is (= 1 (count entries)))
        (is (str/includes? (first entries) "_retry_"))
        (is (str/includes? note (str "batch_task_ids: " (pr-str [a b]) "\n")))
        (is (str/includes? note "card_type: component\n")))
      (is (= 3 (count (handoff-names (fs/path root ".swarmforge/handoffs/inbox/completed")))))
      (let [result (run {:dir root :env {"SWARMFORGE_ROLE" "specifier"} :ok? false}
                        (script "ready_for_next.sh"))]
        (is (zero? (:exit result)) (:err result))))))

(deftest pack-web-retry-restores-only-the-newest-round-of-a-card
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (create-task root "A" "specifier")
    (let [a (:id (task-card root "A"))]
      (write-file (fs/path root ".swarmforge/handoffs/pending_approval/50_offer.handoff")
                  (str "from: specifier\nto: coder\ntype: git_handoff\n"
                       "task_id: " a "\ntask: A\n\npayload\n"))
      (completed-mail! root "" "50_a_old.handoff" a "A" "2026-09-30T10:00:00Z")
      (completed-mail! root "" "50_a_new.handoff" a "A" "2026-10-01T10:00:00Z")
      (is (zero? (:exit (pack-web root false "--test-retry-task" (str root) "50_offer" "again"))))
      (is (= ["50_a_new.handoff"] (in-process-entries root roles "specifier")))
      (is (fs/exists? (fs/path root ".swarmforge/handoffs/inbox/completed/50_a_old.handoff"))))))

(deftest pack-web-requeued-retry-carries-the-card-type-and-comes-first
  ;; Given card A held while the specifier works on B, and a merge-only copy waits
  ;; When the operator retries A
  ;; Then the queued retry names A's card type and sorts ahead of the merge-only copy
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (create-task root "A" "specifier")
    (create-task root "B" "specifier")
    (let [a (:id (task-card root "A"))
          b (:id (task-card root "B"))]
      (write-file (fs/path root ".swarmforge/handoffs/pending_approval/50_offer.handoff")
                  (str "from: specifier\nto: coder\ntype: git_handoff\n"
                       "task_id: " a "\ntask: A\n\npayload\n"))
      (write-file (fs/path (in-process-dir root roles "specifier") "50_b.handoff")
                  (str "from: (New Task)\nto: specifier\npriority: 50\ntype: note\n"
                       "task_id: " b "\ntask: B\n\nB\n"))
      (write-file (inbox-new-path root roles "specifier"
                                  "00_20261001T100000Z_000001_from_coder_to_specifier.handoff")
                  (str "from: coder\nto: specifier\npriority: 00\ntype: git_handoff\n"
                       "non-forwarding: true\ntask_id: " b "\ntask: B\n\nmerge\n"))
      (is (zero? (:exit (pack-web root false "--test-retry-task" (str root) "50_offer" "again"))))
      (let [first-name (first (sort (inbox-names root roles "specifier")))]
        (is (retry-note-name? first-name))
        (is (str/includes? (slurp (str (inbox-new-path root roles "specifier" first-name)))
                           "card_type: component\n"))))))

(defn card-meta [root name]
  (let [id (:id (task-card root name))
        file (fs/path root ".swarmforge/board/meta" (str id ".edn"))]
    (when (fs/exists? file) (clojure.edn/read-string (slurp (str file))))))

(defn lieutenant-start! [root name lane ok?]
  (pack-board root ok? "move" "--root" (str root) "--name" name "--lane" lane "--caller" "lieutenant"))

(deftest pack-web-state-reports-whether-the-handoff-daemon-runs
  ;; Given no daemon pid, then a live pid, then a dead one
  ;; Then the state says the daemon is down, running, down
  (let [root (tmp-dir)]
    (setup-pack! root ["specifier" "coder"])
    (is (= {:running false} (:daemon (web-state root))))
    (write-file (fs/path root ".swarmforge/daemon/handoffd.pid")
                (str (.pid (java.lang.ProcessHandle/current)) "\n"))
    (is (= {:running true} (:daemon (web-state root))))
    (write-file (fs/path root ".swarmforge/daemon/handoffd.pid") "999999999\n")
    (is (= {:running false} (:daemon (web-state root))))))

(deftest pack-web-card-without-mail-says-stuck-not-queued
  ;; Given two cards in coder: one with queued mail, one whose mail is gone
  ;; Then the first is queued and the second says no mail, flagged stuck
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (create-task root "HTW" "coder")
    (create-task root "Orphan" "coder")
    (queue-inbox-mail! root roles "coder" {:from "specifier" :task "HTW"})
    (let [by-name (into {} (map (juxt :name identity) (:tasks (web-state root))))]
      (is (= true (:queued (get by-name "HTW"))))
      (is (nil? (:stuck (get by-name "HTW"))))
      (is (= "no_mail" (:stuck (get by-name "Orphan"))))
      (is (= "stuck" (:status_phase (get by-name "Orphan"))))
      (is (nil? (:queued (get by-name "Orphan"))))
      (is (str/starts-with? (:status (get by-name "Orphan")) "No mail")))))

(deftest pack-web-lone-card-without-mail-is-not-shown-as-worked-on
  ;; Given the only card in coder has no mail anywhere but one completed
  ;; Then it is flagged stuck instead of borrowing coder's pane status
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (create-task root "HTW" "coder")
    (write-file (fs/path (pack-worktree root roles "coder")
                         ".swarmforge/handoffs/inbox/completed/50_htw.handoff")
                "from: specifier\nto: coder\npriority: 50\ntype: git_handoff\ntask: HTW\n\npayload\n")
    (is (= "no_mail" (:stuck (task-card root "HTW"))))))

(deftest pack-web-waiting-and-done-cards-are-never-stuck
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (api-post root "/api/tasks" {:name "Later" :text "x" :type "utility"})
    (create-task root "Old" "coder")
    (pack-board root true "done" "--root" (str root) "--name" "Old" "--caller" "handoffd")
    (is (nil? (:stuck (task-card root "Later"))))
    (is (= "Waiting to start" (:status (task-card root "Later"))))
    (is (nil? (:stuck (task-card root "Old"))))))

(deftest pack-web-card-whose-delivery-failed-shows-the-error
  ;; Given HTW's handoff landed in failed/ with an error
  ;; Then the card says delivery failed with that error
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (create-task root "HTW" "specifier")
    (write-file (fs/path root ".swarmforge/handoffs/failed/50_htw.handoff")
                "id: 1_from_specifier\nfrom: specifier\nto: nobody\npriority: 50\ntype: git_handoff\ntask: HTW\n\npayload\n")
    (write-file (fs/path root ".swarmforge/handoffs/failed/50_htw.handoff.error") "unknown recipient nobody\n")
    (let [card (task-card root "HTW")]
      (is (= "failed" (:stuck card)))
      (is (= "Delivery failed: unknown recipient nobody" (:status card))))))

(deftest pack-web-card-handing-off-or-held-is-not-stuck
  ;; Given HTW's handoff waits in the outbox
  ;; Then the card says Handing off, and Handoff held until Resume while paused
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (create-task root "HTW" "specifier")
    (queue-handoff! root {:from "specifier" :to "coder" :task "HTW"})
    (is (= "Handing off" (:status (task-card root "HTW"))))
    (write-file (fs/path root ".swarmforge/paused") "now\n")
    (is (= "Handoff held until Resume" (:status (task-card root "HTW"))))
    (is (= "held" (:status_phase (task-card root "HTW"))))))

(deftest pack-web-marks-a-card-returned-for-rework
  ;; Given QA returned HTW to coder once before, and now again
  ;; Then the card shows who returned it and how many returns it had;
  ;; once coder forwards it again the badge goes but the count stays
  (let [root (tmp-dir)
        roles ["specifier" "coder" "QA"]
        coder-wt (pack-worktree root roles "coder")
        qa-wt (pack-worktree root roles "QA")
        mail (fn [id from to ret]
               (str "id: " id "_from_" from "\nfrom: " from "\nto: " to "\npriority: 50\n"
                    "type: git_handoff\ntask: HTW\n" (when ret "return: true\n") "\npayload\n"))]
    (setup-pack! root roles)
    (create-task root "HTW" "coder")
    (write-file (fs/path coder-wt ".swarmforge/handoffs/inbox/completed/50_a.handoff")
                (mail "20260101T000001000000Z_1" "QA" "coder" true))
    (write-file (fs/path qa-wt ".swarmforge/handoffs/sent/50_a.handoff")
                (mail "20260101T000001000000Z_1" "QA" "coder" true))
    (write-file (fs/path coder-wt ".swarmforge/handoffs/inbox/new/50_b.handoff")
                (mail "20260101T000002000000Z_1" "QA" "coder" true))
    (let [card (task-card root "HTW")]
      (is (= "QA" (:returned_from card)))
      (is (= 2 (:return_count card))))
    (fs/move (fs/path coder-wt ".swarmforge/handoffs/inbox/new/50_b.handoff")
             (fs/path coder-wt ".swarmforge/handoffs/inbox/completed/50_b.handoff"))
    (write-file (fs/path qa-wt ".swarmforge/handoffs/inbox/new/50_c.handoff")
                (mail "20260101T000003000000Z_1" "coder" "QA" false))
    (pack-board root true "move" "--root" (str root) "--name" "HTW" "--lane" "QA" "--caller" "handoffd")
    (let [card (task-card root "HTW")]
      (is (nil? (:returned_from card)))
      (is (= 2 (:return_count card))))))

(deftest new-task-level-orders-the-start-note
  ;; Given New Task makes a utility card at level high
  ;; Then the card waits with its level and no mail
  ;; When the lieutenant starts it
  ;; Then its start note goes out with priority 30
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (is (= 200 (:status (api-post root "/api/tasks" {:name "HTW" :text "Hunt" :type "utility" :level "high"}))))
    (let [card (task-card root "HTW")]
      (is (= "waiting" (:lane card)))
      (is (= "high" (:level card)))
      (is (nil? (:stuck card))))
    (is (= {:level "high"} (card-meta root "HTW")))
    (is (= [] (outbox-handoffs root)))
    (is (zero? (:exit (lieutenant-start! root "HTW" "coder" true))))
    (is (= "coder" (task-lane root "HTW")))
    (let [[file] (outbox-handoffs root)
          text (slurp (str file))]
      (is (str/starts-with? (str (fs/file-name file)) "30_"))
      (is (str/includes? text "priority: 30"))
      (is (str/includes? text "Hunt")))
    (is (= "Handing off" (:status (task-card root "HTW"))))))

(deftest new-task-refuses-an-unknown-level
  (let [root (tmp-dir)]
    (setup-pack! root ["specifier" "coder"])
    (let [resp (api-post root "/api/tasks" {:name "HTW" :type "utility" :level "urgent"})]
      (is (= 400 (:status resp)))
      (is (str/includes? (get-in resp [:body :error]) "Level must be")))
    (is (nil? (task-lane root "HTW")))))

(deftest task-level-change-reorders-queued-mail-and-skips-merge-copies
  ;; Given a queued coder card and a 00 merge-only copy of it
  ;; When its level becomes critical
  ;; Then its forward mail moves to 10 and the merge copy stays 00
  (let [root (tmp-dir)
        roles ["specifier" "coder"]
        new-dir (fs/path (pack-worktree root roles "coder") ".swarmforge/handoffs/inbox/new")]
    (setup-pack! root roles)
    (create-task root "HTW" "coder")
    (put-queued! root roles "coder" {:task "HTW" :task-id (:id (task-card root "HTW"))})
    (write-file (fs/path new-dir "00_merge_from_QA_to_coder.handoff")
                (str "from: QA\nto: coder\npriority: 00\ntype: git_handoff\ntask_id: "
                     (:id (task-card root "HTW")) "\ntask: HTW\nnon-forwarding: true\n\nmerge\n"))
    (is (= 200 (:status (api-post root "/api/tasks/priority" {:name "HTW" :level "critical"}))))
    (let [names (set (handoff-names new-dir))]
      (is (contains? names "00_merge_from_QA_to_coder.handoff"))
      (is (= 2 (count names)))
      (is (some #(str/starts-with? % "10_") names)))
    (is (= "critical" (:level (task-card root "HTW"))))))

(deftest task-level-change-reorders-held-outbox-mail-while-paused
  ;; Given HTW's handoff is held in the outbox while paused
  ;; When its level becomes low
  ;; Then the held handoff takes priority 70; unpaused outbox mail is refused
  (let [root (tmp-dir)
        roles ["specifier" "coder"]
        outbox (fs/path root ".swarmforge/handoffs/outbox")]
    (setup-pack! root roles)
    (create-task root "HTW" "specifier")
    (queue-handoff! root {:from "specifier" :to "coder" :task "HTW" :task-id (:id (task-card root "HTW"))})
    (is (= 409 (:status (api-post root "/api/tasks/priority" {:name "HTW" :priority "20"}))))
    (write-file (fs/path root ".swarmforge/paused") "now\n")
    (is (= 200 (:status (api-post root "/api/tasks/priority" {:name "HTW" :level "low"}))))
    (is (every? #(str/starts-with? % "70_") (handoff-names outbox)))
    (is (str/includes? (slurp (str (first (fs/glob outbox "*.handoff")))) "priority: 70"))))

(deftest blockers-hold-a-waiting-card-until-they-are-done
  ;; Given A in coder and B waiting, blocked by A and related to A
  ;; Then B shows its blocker, A shows it blocks B, and the lieutenant
  ;; cannot start B; a cycle or self link is refused
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (create-task root "A" "coder")
    (is (= 200 (:status (api-post root "/api/tasks" {:name "B" :type "utility" :blocked_by ["A"] :related ["A"]}))))
    (let [b (task-card root "B")
          a (task-card root "A")]
      (is (= [{:name "A" :done false}] (:blockers b)))
      (is (= true (:blocked b)))
      (is (= ["A"] (:related b)))
      (is (= ["B"] (:blocks a))))
    (let [result (lieutenant-start! root "B" "coder" false)]
      (is (not (zero? (:exit result))))
      (is (str/includes? (:err result) "blocked by A")))
    (is (= "waiting" (task-lane root "B")))
    (is (= 400 (:status (api-post root "/api/tasks/links" {:name "A" :blocked_by ["B"]}))))
    (is (= 400 (:status (api-post root "/api/tasks/links" {:name "B" :blocked_by ["B"]}))))
    (is (= 400 (:status (api-post root "/api/tasks/links" {:name "B" :blocked_by ["Nope"]}))))
    (pack-board root true "done" "--root" (str root) "--name" "A" "--caller" "handoffd")
    (is (nil? (:blocked (task-card root "B"))))
    (is (= [{:name "A" :done true}] (:blockers (task-card root "B"))))
    (is (zero? (:exit (lieutenant-start! root "B" "coder" true))))
    (is (= "coder" (task-lane root "B")))))

(deftest links-can-be-cleared-and-a-deleted-blocker-no-longer-holds-a-card
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (create-task root "A" "coder")
    (create-task root "C" "coder")
    (api-post root "/api/tasks" {:name "B" :type "utility"})
    (is (= 200 (:status (api-post root "/api/tasks/links" {:name "B" :blocked_by ["A" "C"]}))))
    (is (= true (:blocked (task-card root "B"))))
    (pack-board root true "delete" "--root" (str root) "--name" "A")
    (is (= [{:name "C" :done false}] (:blockers (task-card root "B"))))
    (is (= 200 (:status (api-post root "/api/tasks/links" {:name "B" :blocked_by []}))))
    (is (nil? (:blockers (task-card root "B"))))
    (is (nil? (card-meta root "B")) "empty meta leaves no file")
    (is (zero? (:exit (lieutenant-start! root "B" "coder" true))))))

(deftest deleting-a-card-deletes-its-meta
  (let [root (tmp-dir)]
    (setup-pack! root ["specifier" "coder"])
    (api-post root "/api/tasks" {:name "B" :type "utility" :level "low"})
    (let [id (:id (task-card root "B"))
          file (fs/path root ".swarmforge/board/meta" (str id ".edn"))]
      (is (fs/exists? file))
      (pack-board root true "delete" "--root" (str root) "--name" "B")
      (is (not (fs/exists? file))))))

(deftest pause-and-resume-endpoints-drive-the-swarm-pause-file
  ;; Given a swarm
  ;; When the dashboard posts /api/pause, then /api/resume
  ;; Then the pause file appears and goes, and Retry is refused while paused
  (let [root (tmp-dir)
        pause (fs/path root ".swarmforge/paused")]
    (setup-pack! root ["specifier" "coder"])
    (is (= 200 (:status (api-post root "/api/pause" {}))))
    (is (fs/exists? pause))
    (write-file (fs/path root ".swarmforge/handoffs/pending_approval/50_hold.handoff")
                "from: specifier\nto: coder\npriority: 50\ntype: git_handoff\ntask: HTW\n\npayload\n")
    (let [resp (api-post root "/api/tasks/retry" {:id "50_hold" :comments "again"})]
      (is (= 409 (:status resp)))
      (is (str/includes? (get-in resp [:body :error]) "paused")))
    (is (fs/exists? (fs/path root ".swarmforge/handoffs/pending_approval/50_hold.handoff")))
    (is (= 200 (:status (api-post root "/api/resume" {}))))
    (is (not (fs/exists? pause)))))

(deftest waiting-todo-and-done-are-not-role-names
  (doseq [role ["waiting" "todo" "done"]]
    (let [root (tmp-dir)]
      (write-file (fs/path root "swarmforge/swarmforge.conf") (str "window " role " codex master\n"))
      (write-file (fs/path root (str "swarmforge/roles/" role ".prompt")) "x\n")
      (write-file (fs/path root "swarmforge/constitution.prompt") "x\n")
      (let [result (run {:dir root :ok? false} "bb" (script "swarmforge.bb") "--test-parse" (str root))]
        (is (not (zero? (:exit result))) role)
        (is (str/includes? (str (:out result) (:err result)) "board columns") role)))))
