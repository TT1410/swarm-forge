(ns swarmforge.coverage-in-process-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [commit-msg-hook]
            [handoff-lib]
            [handoffd]
            [merge-and-process]
            [pack-board]
            [pack-dashboard-request]
            [pack-web]
            [ready-for-next-guard]
            [stop-handoff-daemon]
            [swarm-handoff]
            [swarm-tool]
            [swarm-window-watchdog]
            [swarmforge]))

(defn- tmp-dir []
  (fs/create-temp-dir {:prefix "swarmforge-in-process-test."}))

(deftest commit-msg-hook-builds-bylines
  (is (= "By specifier." (commit-msg-hook/byline "specifier")))
  (is (= "hello\n\nBy coder.\n" (commit-msg-hook/append-byline "hello" "coder"))))

(deftest watchdog-rewrites-window-state-in-process
  (let [root (tmp-dir)
        state-file (fs/path root "windows.tsv")
        ids-file (fs/path root "window-ids")]
    (try
      (spit (str state-file)
            (str "1\told-a\tswarmforge-coder\tSwarmForge Coder\n"
                 "2\told-b\tswarmforge-cleaner\tSwarmForge Cleaner\n"))
      (spit (str ids-file) "old-a\nold-b\n")
      (swarm-window-watchdog/rewrite-window-id! state-file ids-file "2" "new-b")
      (is (re-find #"2\tnew-b\tswarmforge-cleaner\tSwarmForge Cleaner" (slurp (str state-file))))
      (is (= "old-a\nnew-b\n" (slurp (str ids-file))))
      (finally
        (fs/delete-tree root)))))

(deftest handoff-lib-validates-priority-and-headers
  (is (handoff-lib/valid-priority? "10"))
  (is (not (handoff-lib/valid-priority? "5")))
  (let [root (tmp-dir)
        file (fs/path root "task.handoff")]
    (try
      (spit (str file) "task: alpha\nfrom: coder\n\nbody\n")
      (is (= "alpha" (handoff-lib/header-field file "task")))
      (is (= "body\n" (handoff-lib/body file)))
      (finally
        (fs/delete-tree root)))))

(deftest swarm-handoff-parses-note-draft
  (let [root (tmp-dir)
        draft (fs/path root "note.handoff")]
    (try
      (spit (str draft) "type: note\nto: cleaner\npriority: 50\nmessage: hello\n")
      (let [{:keys [headers errors]} (swarm-handoff/parse-draft draft)]
        (is (empty? errors))
        (is (= "note" (get headers "type")))
        (is (= "cleaner" (get headers "to")))
        (is (= "hello" (get headers "message"))))
      (finally
        (fs/delete-tree root)))))

(deftest pack-web-reads-query-values
  (is (= "HTW" (pack-web/query-value "/api/task?name=HTW" "name")))
  (is (nil? (pack-web/query-value "/api/task" "name"))))

(deftest pack-board-parses-flags
  (is (= {:positional ["list"] :root "/tmp/root"}
         (pack-board/parse-args ["list" "--root" "/tmp/root"]))))

(deftest swarmforge-classifies-worktrees-and-windows
  (is (swarmforge/special-worktree? "master"))
  (is (swarmforge/special-worktree? "none"))
  (is (not (swarmforge/special-worktree? "coder")))
  (is (true? (swarmforge/visible-window? "window" 1)))
  (is (false? (swarmforge/visible-window? "window-invisible" 2))))

(deftest handoffd-parses-recipients-and-messages
  (is (= ["coder" "cleaner"] (handoffd/recipient-list {"to" "coder, cleaner"})))
  (is (true? (handoffd/non-forwarding? {"non-forwarding" "true"})))
  (is (true? (handoffd/phantom-sender? "(New Task)")))
  (is (false? (handoffd/phantom-sender? "coder")))
  (let [root (tmp-dir)
        file (fs/path root "mail.handoff")]
    (try
      (spit (str file) "from: coder\nto: cleaner\ntype: note\n\npayload\n")
      (let [message (handoffd/parse-message file)]
        (is (= "coder" (get-in message [:headers "from"])))
        (is (= "payload\n" (:body message)))
        (is (re-find #"from: coder" (handoffd/render-message (:headers message) (:body message)))))
      (finally
        (fs/delete-tree root)))))

(deftest merge-and-process-usage-text-names-the-script
  (is (re-find #"merge_and_process" merge-and-process/usage-text)))

(deftest ready-for-next-guard-formats-wait-message
  (let [lines (ready-for-next-guard/wait-message ["/tmp/a.handoff"])]
    (is (re-find #"WAITING_FOR_APPROVAL" (first lines)))
    (is (re-find #"/tmp/a.handoff" (second lines)))))

(deftest handoff-lib-rewrites-headers
  (is (= ["task: beta" "" "body"]
         (vec (handoff-lib/set-header-lines ["task: alpha" "" "body"] "task" "beta"))))
  (is (= ["task: alpha" "from: coder"]
         (vec (handoff-lib/append-header ["task: alpha"] "from: " "coder"))))
  (let [root (tmp-dir)
        file (fs/path root "item.handoff")]
    (try
      (spit (str file) "task: alpha\n\nbody\n")
      (handoff-lib/set-header! file "task" "beta")
      (is (re-find #"task: beta" (slurp (str file))))
      (handoff-lib/print-task file)
      (is (not (handoff-lib/roles-at? nil)))
      (is (handoff-lib/same-path? "/tmp" "/tmp"))
      (finally
        (fs/delete-tree root)))))

(defn- git-command [disambiguate-out object-type short-out]
  (fn [_dir & args]
    (cond
      (some #(str/starts-with? % "--disambiguate=") args)
      {:exit 0 :out disambiguate-out}
      (= ["git" "cat-file" "-t"] (take 3 args))
      {:exit 0 :out object-type}
      (some #(= "--short=10" %) args)
      {:exit 0 :out short-out}
      :else {:exit 1 :out "" :err "unexpected git"})))

(defn- with-validate-mocks [{:keys [known? command]} f]
  (with-redefs [swarm-handoff/git-cwd (constantly ".")
                swarm-handoff/role-known? (or known? (constantly true))
                swarm-handoff/command (or command (git-command "abcdef1234\n" "commit\n" "abcdef1234\n"))]
    (f)))

(defn- has-error? [result re]
  (boolean (some #(re-find re %) (:errors result))))

(deftest swarm-handoff-validates-headers
  (is (= [[] []] (swarm-handoff/validate-recipients "")))
  (is (= [] (swarm-handoff/current-work-state-errors {"type" "note"})))
  (is (= [] (swarm-handoff/task-state-errors {"type" "note"} "coder")))
  (let [root (tmp-dir)
        draft (fs/path root "bad.handoff")]
    (try
      (spit (str draft) "type: note\ntype: note\npriority: 50\nto: x\nmessage: hi\n")
      (is (seq (:errors (swarm-handoff/parse-draft draft))))
      (finally
        (fs/delete-tree root))))
  (with-validate-mocks {}
    (fn []
      (let [ok (swarm-handoff/validate
                {"type" "note" "to" "receiver" "priority" "50" "message" "hello"}
                ["type" "to" "priority" "message"])]
        (is (empty? (:errors ok)))
        (is (= ["receiver"] (:recipients ok)))
        (is (nil? (:canonical-commit ok))))
      (let [missing (swarm-handoff/validate {} [])]
        (is (has-error? missing #"Missing required header 'type'"))
        (is (has-error? missing #"Missing required header 'to'"))
        (is (has-error? missing #"Missing required header 'priority'")))
      (let [bad-type (swarm-handoff/validate
                      {"type" "fax" "to" "receiver" "priority" "50"}
                      ["type" "to" "priority"])]
        (is (has-error? bad-type #"must be one of git_handoff or note")))
      (let [bad-priority (swarm-handoff/validate
                          {"type" "note" "to" "receiver" "priority" "zz" "message" "hi"}
                          ["type" "to" "priority" "message"])]
        (is (has-error? bad-priority #"two digits from 00 to 99")))
      (let [illegal (swarm-handoff/validate
                     {"type" "note" "to" "receiver" "priority" "50" "message" "hi" "commit" "abcdef1234" "task" "nope"}
                     ["type" "to" "priority" "message" "commit" "task"])]
        (is (has-error? illegal #"Header 'commit' is not allowed for type 'note'"))
        (is (has-error? illegal #"Header 'task' is not allowed for type 'note'"))
        (is (has-error? illegal #"Header 'commit' is only allowed for git_handoff"))
        (is (has-error? illegal #"Header 'task' is only allowed for git_handoff")))
      (let [note-msg (swarm-handoff/validate
                      {"type" "note" "to" "receiver" "priority" "50"}
                      ["type" "to" "priority"])]
        (is (has-error? note-msg #"Missing required header 'message'")))
      (let [long-note (swarm-handoff/validate
                       {"type" "note" "to" "receiver" "priority" "50"
                        "message" (apply str (repeat 81 "x"))}
                       ["type" "to" "priority" "message"])]
        (is (has-error? long-note #"Header 'message' must be no longer than 80")))
      (let [git-msg (swarm-handoff/validate
                     {"type" "git_handoff" "to" "receiver" "priority" "50"
                      "task_id" "t1" "task" "t1" "commit" "abcdef1234" "message" "nope"}
                     ["type" "to" "priority" "task_id" "task" "commit" "message"])]
        (is (has-error? git-msg #"Header 'message' is not allowed for type 'git_handoff'"))
        (is (has-error? git-msg #"Header 'message' is only allowed for note")))
      (let [git-missing (swarm-handoff/validate
                         {"type" "git_handoff" "to" "receiver" "priority" "50"}
                         ["type" "to" "priority"])]
        (is (has-error? git-missing #"Missing required header 'commit'"))
        (is (has-error? git-missing #"Missing required header 'task_id'"))
        (is (has-error? git-missing #"Missing required header 'task'")))
      (let [bad-sha (swarm-handoff/validate
                     {"type" "git_handoff" "to" "receiver" "priority" "50"
                      "task_id" "t1" "task" "t1" "commit" "not-a-sha!"}
                     ["type" "to" "priority" "task_id" "task" "commit"])]
        (is (has-error? bad-sha #"exactly 10 hexadecimal characters")))
      (let [long-task (swarm-handoff/validate
                       {"type" "git_handoff" "to" "receiver" "priority" "50"
                        "task_id" "t1" "task" (apply str (repeat 81 "t")) "commit" "abcdef1234"}
                       ["type" "to" "priority" "task_id" "task" "commit"])]
        (is (has-error? long-task #"Header 'task' must be no longer than 80")))
      (let [ok-git (swarm-handoff/validate
                    {"type" "git_handoff" "to" "receiver" "priority" "50"
                     "task_id" "t1" "task" "t1" "commit" "abcdef1234"}
                    ["type" "to" "priority" "task_id" "task" "commit"])]
        (is (empty? (:errors ok-git)))
        (is (= "abcdef1234" (:canonical-commit ok-git))))))
  (with-validate-mocks {:command (git-command "aaa\nbbb\n" "commit\n" "aaa\n")}
    (fn []
      (let [result (swarm-handoff/validate
                    {"type" "git_handoff" "to" "receiver" "priority" "50"
                     "task_id" "t1" "task" "t1" "commit" "abcdef1234"}
                    ["type" "to" "priority" "task_id" "task" "commit"])]
        (is (has-error? result #"must resolve to exactly one Git object")))))
  (with-validate-mocks {:command (git-command "abcdef1234\n" "blob\n" "abcdef1234\n")}
    (fn []
      (let [result (swarm-handoff/validate
                    {"type" "git_handoff" "to" "receiver" "priority" "50"
                     "task_id" "t1" "task" "t1" "commit" "abcdef1234"}
                    ["type" "to" "priority" "task_id" "task" "commit"])]
        (is (has-error? result #"must resolve to a commit")))))
  (with-validate-mocks {:known? (constantly false)}
    (fn []
      (let [result (swarm-handoff/validate
                    {"type" "note" "to" "ghost" "priority" "50" "message" "hi"}
                    ["type" "to" "priority" "message"])]
        (is (has-error? result #"Unknown recipient role 'ghost'")))))
  (let [[_ errors] (with-validate-mocks {:known? (constantly true)}
                     (fn [] (swarm-handoff/validate-recipients "receiver,,receiver,bad_role")))]
    (is (some #(re-find #"empty recipient" %) errors))
    (is (some #(re-find #"underscores" %) errors))
    (is (some #(re-find #"Duplicate recipient 'receiver'" %) errors))))

(deftest pack-web-routes-and-parsing
  (is (= 404 (:status (pack-web/handle-get nil "/missing"))))
  (is (= 404 (:status (pack-web/handle-post nil "/nope" ""))))
  (is (seq (pack-web/codex-bullets "• one\n  continued\n• two\n")))
  (is (true? (pack-web/confirm-teardown? "TEARDOWN")))
  (is (false? (pack-web/confirm-teardown? "no")))
  (is (= "&lt;x&gt;" (pack-web/html-escape "<x>")))
  (let [entry (pack-web/task-entry "HTW\tspecifier\tnow\tnow\tid1\t2")]
    (is (= "HTW" (:name entry)))
    (is (= 2 (:audit_count entry))))
  (is (seq (pack-web/parse-unified-diff "--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new\n")))
  (let [missing (pack-web/handle-request nil {:method "HEAD" :uri "/missing" :body nil})]
    (is (= 404 (:status missing)))))

(deftest swarmforge-launch-helpers
  (is (= "iterm2" (swarmforge/normalize-terminal-backend "iTerm")))
  (is (= "terminal-app" (swarmforge/normalize-terminal-backend "terminal")))
  (is (= "windows-terminal" (swarmforge/normalize-terminal-backend "wt")))
  (is (= "none" (swarmforge/normalize-terminal-backend "none")))
  (is (= "ghostty" (swarmforge/normalize-terminal-backend "ghostty")))
  (is (= "--yolo " (swarmforge/yolo-flag "codex" {:extra-args ""})))
  (is (= "" (swarmforge/yolo-flag "codex" {:extra-args "--yolo"})))
  (is (= "--permission-mode bypassPermissions "
         (swarmforge/yolo-flag "claude" {:extra-args ""})))
  (is (= "" (swarmforge/yolo-flag "unknown" {:extra-args ""})))
  (is (swarmforge/skip-config-line? "# hi"))
  (is (swarmforge/skip-config-line? ""))
  (is (not (swarmforge/special-worktree? "coder")))
  (is (some? (swarmforge/sleep-inhibitor-prefix))))

(deftest pack-board-helpers
  (is (= "hello" (pack-board/slug "Hello!")))
  (is (= 3 (pack-board/parse-count "3")))
  (is (= 0 (pack-board/parse-count "x")))
  (is (re-find #"\tlane2\t" (pack-board/rewrite-lane "n\tlane1\tc\tu\tid\t0" "n" "lane2"))))

(deftest stop-daemon-with-no-pid
  (let [root (tmp-dir)]
    (try
      (stop-handoff-daemon/stop! (str root) :timeout-ms 10)
      (is (not (fs/exists? (fs/path root ".swarmforge/daemon/handoffd.pid"))))
      (finally
        (fs/delete-tree root)))))

(deftest handoffd-skips-board-update-without-board
  (is (nil? (handoffd/update-board! {} {"type" "note"})))
  (is (false? (handoffd/non-forwarding? {})))
  (is (nil? (handoffd/recipient-list {}))))

(deftest dashboard-request-helpers
  (is (string? pack-dashboard-request/usage-text)))

(deftest swarm-tool-usage
  (is (fn? swarm-tool/-main)))

(deftest handoffd-renotifies-idle-roles-with-waiting-mail
  ;; SF-03, SF-17: an idle role with mail is reminded; a busy role is not
  (let [root (tmp-dir)
        idle (fs/path root "idle")
        busy (fs/path root "busy")
        notified (atom [])]
    (try
      (doseq [wt [idle busy]]
        (fs/create-dirs (fs/path wt ".swarmforge/handoffs/inbox/new"))
        (spit (str (fs/path wt ".swarmforge/handoffs/inbox/new/50_x.handoff")) "from: a\n\nbody\n"))
      (fs/create-dirs (fs/path busy ".swarmforge/handoffs/inbox/in_process"))
      (spit (str (fs/path busy ".swarmforge/handoffs/inbox/in_process/50_y.handoff")) "from: a\n\nbody\n")
      (handoffd/configure! [(str root)])
      (reset! handoffd/last-notified {})
      (reset! handoffd/renotify-counts {})
      (reset! handoffd/started-at-ms (- (System/currentTimeMillis) (* 10 handoffd/renotify-ms)))
      (with-redefs [handoffd/notify! (fn [_ session] (swap! notified conj session))
                    handoffd/log! (fn [& _])]
        (let [roles {"idle" {:role "idle" :worktree-path (str idle) :session "idle-s"}
                     "busy" {:role "busy" :worktree-path (str busy) :session "busy-s"}}]
          (handoffd/renotify-idle-roles! roles "sock")
          (is (= ["idle-s"] @notified))
          (handoffd/renotify-idle-roles! roles "sock")
          (is (= ["idle-s"] @notified) "waits for the interval before reminding again")
          (swap! handoffd/last-notified assoc "idle" (- (System/currentTimeMillis) (inc handoffd/renotify-ms)))
          (handoffd/renotify-idle-roles! roles "sock")
          (is (= ["idle-s"] @notified) "backs off after a reminder that changed nothing")
          (swap! handoffd/last-notified assoc "idle" (- (System/currentTimeMillis) (inc (* 2 handoffd/renotify-ms))))
          (handoffd/renotify-idle-roles! roles "sock")
          (is (= ["idle-s" "idle-s"] @notified))))
      (finally
        (fs/delete-tree root)))))

(deftest pack-web-notify-keeps-seen-keys-of-a-project-that-failed-to-read
  ;; A project that cannot be read this poll keeps its notified keys, so its
  ;; items are not announced again on the next poll
  (let [root (tmp-dir)
        notified (fs/path root ".swarmforge/dashboard/notified")]
    (try
      (fs/create-dirs (fs/parent notified))
      (spit (str notified) "p1/approval/x\np2/approval/y\n")
      (with-redefs [pack-web/conf-setting (fn [_ _] "true")
                    pack-web/attention-scan (fn [_] {:events [] :failed #{"p1"}})]
        (pack-web/notify-new-attention! (str root)))
      (is (= "p1/approval/x\n" (slurp (str notified))))
      (finally
        (fs/delete-tree root)))))

(deftest handoffd-skips-a-carried-card-that-left-the-board
  ;; A carried card that cannot move must not fail the handoff of the others
  (let [root (tmp-dir)
        moved (atom [])]
    (try
      (fs/create-dirs (fs/path root ".swarmforge/board"))
      (spit (str (fs/path root ".swarmforge/board/tasks.tsv"))
            "A\tcoder\tt\tt\tid-a\nB\tcoder\tt\tt\tid-b\n")
      (spit (str (fs/path root ".swarmforge/roles.tsv")) "")
      (handoffd/configure! [(str root)])
      (with-redefs [handoffd/pack-board! (fn [& args]
                                           (when (some #{"B"} args)
                                             (throw (ex-info "Unknown task name: B" {})))
                                           (swap! moved conj args))
                    handoffd/log! (fn [& _])]
        (handoffd/update-board! {} {"type" "git_handoff" "to" "cleaner" "from" "coder"
                                    "task_id" "id-a" "task" "A" "with_task_ids" "id-b"}))
      (is (= [["move" "--name" "A" "--lane" "cleaner"]] @moved))
      (finally
        (fs/delete-tree root)))))

(deftest handoffd-delivery-restarts-the-senders-idle-clock
  ;; Right after its mail goes out the sender may still be in its turn, so it
  ;; is not reminded at once
  (let [root (tmp-dir)
        coder (fs/path root "coder")
        cleaner (fs/path root "cleaner")
        notified (atom [])]
    (try
      (doseq [wt [coder cleaner]]
        (fs/create-dirs (fs/path wt ".swarmforge/handoffs/inbox/new"))
        (fs/create-dirs (fs/path wt ".swarmforge/handoffs/outbox")))
      (spit (str (fs/path coder ".swarmforge/handoffs/inbox/new/50_x.handoff")) "from: a\n\nbody\n")
      (spit (str (fs/path coder ".swarmforge/handoffs/outbox/50_n.handoff"))
            "from: coder\nto: cleaner\npriority: 50\ntype: note\n\nhi\n")
      (handoffd/configure! [(str root)])
      (reset! handoffd/last-notified {})
      (reset! handoffd/renotify-counts {"coder" 3})
      (reset! handoffd/started-at-ms (- (System/currentTimeMillis) (* 10 handoffd/renotify-ms)))
      (with-redefs [handoffd/notify! (fn [_ session] (swap! notified conj session))
                    handoffd/pack-board! (fn [& _])
                    handoffd/log! (fn [& _])]
        (let [roles {"coder" {:role "coder" :worktree-path (str coder) :session "coder-s"}
                     "cleaner" {:role "cleaner" :worktree-path (str cleaner) :session "cleaner-s"}}]
          (handoffd/deliver! roles "sock" "coder" (fs/path coder ".swarmforge/handoffs/outbox/50_n.handoff"))
          (is (= ["cleaner-s"] @notified))
          (is (nil? (get @handoffd/renotify-counts "coder")))
          (handoffd/renotify-idle-roles! roles "sock")
          (is (= ["cleaner-s"] @notified))))
      (finally
        (fs/delete-tree root)))))

(deftest handoffd-renotify-survives-a-role-that-cannot-be-read
  ;; A role whose inbox changes mid-read is skipped this poll; the daemon keeps
  ;; running and still reminds the other roles
  (let [root (tmp-dir)
        idle (fs/path root "idle")
        notified (atom [])]
    (try
      (fs/create-dirs (fs/path idle ".swarmforge/handoffs/inbox/new"))
      (spit (str (fs/path idle ".swarmforge/handoffs/inbox/new/50_x.handoff")) "from: a\n\nbody\n")
      (handoffd/configure! [(str root)])
      (reset! handoffd/last-notified {})
      (reset! handoffd/renotify-counts {})
      (reset! handoffd/started-at-ms (- (System/currentTimeMillis) (* 10 handoffd/renotify-ms)))
      (let [real handoffd/idle-with-mail?]
        (with-redefs [handoffd/notify! (fn [_ session] (swap! notified conj session))
                      handoffd/log! (fn [& _])
                      handoffd/idle-with-mail? (fn [roles role-info]
                                                 (if (= "broken" (:role role-info))
                                                   (throw (java.nio.file.NoSuchFileException. "gone"))
                                                   (real roles role-info)))]
          (handoffd/renotify-idle-roles! {"broken" {:role "broken" :worktree-path (str idle) :session "b-s"}
                                          "idle" {:role "idle" :worktree-path (str idle) :session "idle-s"}}
                                         "sock")
          (is (= ["idle-s"] @notified))))
      (finally
        (fs/delete-tree root)))))

(deftest handoffd-mail-card-keys-tolerates-vanished-mail
  (is (= [] (handoffd/mail-card-keys "/nonexistent/swarmforge/50_x.handoff"))))

(deftest handoffd-poll-survives-a-failing-reminder
  ;; An error while reminding idle roles is logged, not thrown, so the daemon
  ;; loop keeps delivering handoffs
  (let [root (tmp-dir)
        logged (atom [])]
    (try
      (fs/create-dirs (fs/path root ".swarmforge"))
      (spit (str (fs/path root ".swarmforge/tmux-socket")) "sock\n")
      (spit (str (fs/path root ".swarmforge/roles.tsv")) "")
      (handoffd/configure! [(str root)])
      (with-redefs [handoffd/renotify-idle-roles! (fn [& _] (throw (java.nio.file.NoSuchFileException. "gone")))
                    handoffd/log! (fn [& parts] (swap! logged conj (first parts)))]
        (handoffd/poll-once!)
        (is (= ["renotify-error"] @logged)))
      (finally
        (fs/delete-tree root)))))

(deftest handoffd-stops-when-its-pid-file-is-gone-or-taken
  ;; A deleted project or a newer daemon must end this one, so two daemons
  ;; never deliver the same outboxes
  (let [root (tmp-dir)]
    (try
      (fs/create-dirs (fs/path root ".swarmforge/daemon"))
      (handoffd/configure! [(str root)])
      (reset! handoffd/daemon-mode? true)
      (spit (str handoffd/pid-file) (str handoffd/own-pid "\n"))
      (is (false? (handoffd/should-stop?)))
      (spit (str handoffd/pid-file) "1\n")
      (is (true? (handoffd/should-stop?)))
      (handoffd/delete-own-pid-file!)
      (is (fs/exists? handoffd/pid-file) "another daemon's pid file is left alone")
      (fs/delete handoffd/pid-file)
      (is (true? (handoffd/should-stop?)))
      (finally
        (reset! handoffd/daemon-mode? false)
        (fs/delete-tree root)))))

(deftest swarm-handoff-audit-ignores-a-priority-taken-from-the-level
  ;; The level replaced the typed 50, so a level change between the audit
  ;; and the resubmit must not count as a change the role made
  (let [root (tmp-dir)]
    (try
      (fs/create-dirs (fs/path root ".swarmforge/board/meta"))
      (with-redefs [swarm-handoff/project-root (constantly (str root))]
        (let [draft {"type" "git_handoff" "priority" "50" "task_id" "t1"}
              at (fn [level]
                   (spit (str (fs/path root ".swarmforge/board/meta/t1.edn")) (pr-str {:level level}))
                   (swarm-handoff/fill-priority draft))]
          (is (= "30" (get (at "high") "priority")))
          (is (= (swarm-handoff/audited-priority (at "high"))
                 (swarm-handoff/audited-priority (at "critical"))))
          (let [no-level (do (fs/delete (fs/path root ".swarmforge/board/meta/t1.edn"))
                             (swarm-handoff/fill-priority draft))]
            (is (= "50" (get no-level "priority")))
            (is (= (swarm-handoff/audited-priority no-level)
                   (swarm-handoff/audited-priority (at "low")))
                "setting a level after the audit keeps it"))
          (is (= "00" (swarm-handoff/audited-priority
                       (swarm-handoff/fill-priority (assoc draft "priority" "00")))))))
      (finally
        (fs/delete-tree root)))))
