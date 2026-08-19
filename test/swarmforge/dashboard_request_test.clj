(ns swarmforge.dashboard-request-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [squad-dashboard-request :as dashreq]
            [squadd.web :as web]
            [swarmforge.test-support :refer :all]))

(deftest create-answer-and-reject-dashboard-requests
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (let [created (dashreq/create-request root {:body "Summarize status"})
            id (get-in created [:request "id"])]
        (is (:ok created))
        (is (= "request" (get-in created [:request "kind"])))
        (is (fs/exists? (fs/path root ".swarmforge/dashboard/requests/pending" (str id ".request"))))
        (is (= 1 (count (dashreq/pending-requests root))))
        (write-file (fs/path root "answer.txt") "All clear.\n")
        (let [answered (run {:dir root} (script "squad_dashboard_request.sh") "answer" id "answer.txt")]
          (is (str/includes? (:out answered) "STATE: answered"))
          (is (fs/exists? (fs/path root ".swarmforge/dashboard/requests/answered" (str id ".request"))))
          (is (not (fs/exists? (fs/path root ".swarmforge/dashboard/requests/pending" (str id ".request")))))))
      (let [q (dashreq/create-request root {:kind "question" :body "What is blocked?"})
            qid (get-in q [:request "id"])]
        (is (= "request" (get-in q [:request "kind"]))
            "legacy kind is normalized to request")
        (write-file (fs/path root "empty.txt") "")
        (let [ack (run {:dir root} (script "squad_dashboard_request.sh") "answer" qid "empty.txt")]
          (is (str/includes? (:out ack) "STATE: answered"))
          (is (str/includes? (:out ack) "RESPONSE: Done")))
        (let [q2 (dashreq/create-request root {:body "Another?"})
              qid2 (get-in q2 [:request "id"])]
          (write-file (fs/path root "reason.txt") "not now")
          (let [rej (run {:dir root} (script "squad_dashboard_request.sh") "reject" qid2 "reason.txt")]
            (is (str/includes? (:out rej) "STATE: rejected")))))
      (finally
        (fs/delete-tree root)))))

(deftest empty-body-is-rejected
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (is (not (:ok (dashreq/create-request root {:kind "command" :body "   "}))))
      (is (not (:ok (dashreq/create-request root {:kind "command" :body ""}))))
      (finally
        (fs/delete-tree root)))))

(deftest answer-refuses-false-empty-body-claim
  ;; Cannot answer "body was empty" when durable body is present
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (let [created (dashreq/create-request
                     root
                     {:body "Theme: Hunt the Wumpus. Be faithful."
                      :owner "squad-leader"})
            id (get-in created [:request "id"])
            bad (dashreq/answer-request
                 root id
                 "I could not route product work because the routed request body was empty.")]
        (is (:ok created))
        (is (false? (:ok bad)))
        (is (str/includes? (str (:error bad)) "non-empty"))
        (let [good (dashreq/answer-request root id "Creating theme htw.")]
          (is (:ok good))
          (is (= "answered" (get-in good [:request "status"])))))
      (finally
        (fs/delete-tree root)))))

(deftest multiline-body-survives-embedded-key-value-lines
  ;; Free-text lines that look like `key: value` must not truncate body
  (let [root (tmp-dir)
        body (str "PRODUCT BACKLOG APPROVED FOR ANALYSIS\n"
                  "backlog_id: bl-20260817-001\n"
                  "title: command syntax.\n\n"
                  "The move command should be M n.\n")]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (let [created (dashreq/create-request root {:body body :owner "squad-leader"})
            id (get-in created [:request "id"])
            pending (first (dashreq/pending-requests root))
            re-read (dashreq/file-map
                     (fs/path root ".swarmforge/dashboard/requests/pending" (str id ".request")))]
        (is (:ok created))
        (is (str/includes? (get pending "body") "backlog_id: bl-20260817-001"))
        (is (str/includes? (get pending "body") "The move command should be M n."))
        (is (str/includes? (get re-read "body") "The move command should be M n."))
        (is (not= "PRODUCT BACKLOG APPROVED FOR ANALYSIS" (get re-read "body"))))
      (finally
        (fs/delete-tree root)))))

(deftest multiline-body-and-response-round-trip
  ;; Given a dashboard request with multiline body and answer
  ;; When answered and re-read via list/status helpers
  ;; Then every line is preserved including blank lines and shell-like text
  (let [root (tmp-dir)
        body "Please run:\n\nbb run wumpus\n\nThen report."
        answer "Done.\n\nCommand:\nbb run wumpus\n\nExit 0."]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (let [created (dashreq/create-request root {:body body})
            id (get-in created [:request "id"])]
        (is (:ok created))
        (is (= body (get-in created [:request "body"])))
        (is (= body (get (first (dashreq/pending-requests root)) "body")))
        (write-file (fs/path root "answer.txt") answer)
        (let [out (:out (run {:dir root} (script "squad_dashboard_request.sh") "answer" id "answer.txt"))
              answered (dashreq/file-map
                        (fs/path root ".swarmforge/dashboard/requests/answered" (str id ".request")))
              listed (first (filter #(= id (get % "id")) (dashreq/list-all-requests root)))]
          (is (str/includes? out "STATE: answered"))
          (is (= answer (get answered "response")))
          (is (= body (get answered "body")))
          (is (= answer (get listed "response")))
          (is (str/includes? (get listed "response") "bb run wumpus"))
          (is (str/includes? (slurp (str (fs/path root ".swarmforge/dashboard/requests/answered"
                                                   (str id ".request"))))
                             "response: |"))))
      (finally
        (fs/delete-tree root)))))

(deftest legacy-single-line-request-files-still-parse
  (let [root (tmp-dir)
        file (fs/path root ".swarmforge/dashboard/requests/answered/legacy.request")]
    (try
      (write-file file
                  (str "id: legacy\n"
                       "kind: request\n"
                       "status: answered\n"
                       "body: one line body\n"
                       "response: one line response\n"))
      (let [m (dashreq/file-map file)]
        (is (= "one line body" (get m "body")))
        (is (= "one line response" (get m "response"))))
      (finally
        (fs/delete-tree root)))))

(deftest path-traversal-id-is-rejected
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (is (not (:ok (dashreq/answer-request root "../evil" "x"))))
      (is (not (:ok (dashreq/cancel-request root "a/b"))))
      (finally
        (fs/delete-tree root)))))

(deftest new-requests-default-to-troubleshooter-owner
  ;; Given a new dashboard request from the operator chat
  ;; When it is created
  ;; Then owner is Troubleshooter (front door), not Squad Leader residual
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (let [created (dashreq/create-request root {:body "Why is merger stuck?"})
            id (get-in created [:request "id"])]
        (is (:ok created))
        (is (= "troubleshooter" (get-in created [:request "owner"])))
        (is (= "troubleshooter" (get (first (dashreq/pending-requests root)) "owner")))
        (is (nil? (dashreq/oldest-pending-for-owner root "squad-leader"))
            "SL residual must not see Troubleshooter-owned chat yet")
        (is (= id (get (dashreq/oldest-pending-for-owner root "troubleshooter") "id"))))
      (finally
        (fs/delete-tree root)))))

(deftest route-to-sl-hands-product-work-to-squad-leader
  ;; Given a pending Troubleshooter-owned request that is product work
  ;; When Troubleshooter routes it to the Squad Leader
  ;; Then owner becomes squad-leader, request stays pending, and residual surfaces it
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (let [created (dashreq/create-request root {:body "Add story: player can leave the cave"})
            id (get-in created [:request "id"])
            routed (dashreq/route-to-sl root id)]
        (is (:ok routed))
        (is (= "squad-leader" (get-in routed [:request "owner"])))
        (is (= "pending" (get-in routed [:request "status"])))
        (is (fs/exists? (fs/path root ".swarmforge/dashboard/requests/pending" (str id ".request"))))
        (is (= "squad-leader"
               (get (dashreq/file-map
                     (fs/path root ".swarmforge/dashboard/requests/pending" (str id ".request")))
                    "owner")))
        (let [cli (run {:dir root} (script "squad_dashboard_request.sh") "status" id)]
          (is (str/includes? (:out cli) "OWNER: squad-leader")))
        (let [next (run {:dir root} (script "squad_next.sh") "--residual-only")]
          (is (str/includes? (:out next) "NEXT_ACTION: answer_dashboard_request"))
          (is (str/includes? (:out next) (str "REQUEST_ID: " id)))
          (is (str/includes? (:out next) "OWNER: squad-leader"))
          (is (str/includes? (:out next) (str "squad_dashboard_request.sh answer " id)))))
      (finally
        (fs/delete-tree root)))))

(deftest route-to-sl-cli-and-reject-double-route
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "troubleshooter\tmaster\t" root "\tswarmforge-troubleshooter\tTroubleshooter\tcodex\ttask\n"))
      (let [created (dashreq/create-request root {:body "New theme: Hunt the Wumpus"})
            id (get-in created [:request "id"])
            cli (run {:dir root} (script "squad_dashboard_request.sh") "route-to-sl" id)]
        (is (str/includes? (:out cli) "STATE: pending"))
        (is (str/includes? (:out cli) "OWNER: squad-leader"))
        (is (str/includes? (:out cli) "ROUTED: squad-leader"))
        (let [again (dashreq/route-to-sl root id)]
          (is (:ok again) "idempotent re-route is ok")
          (is (= "squad-leader" (get-in again [:request "owner"]))))
        (write-file (fs/path root "answer.txt") "Routed story registered.\n")
        (let [answered (run {:dir root} (script "squad_dashboard_request.sh") "answer" id "answer.txt")]
          (is (str/includes? (:out answered) "STATE: answered")))
        (is (not (:ok (dashreq/route-to-sl root id)))
            "cannot route an already-answered request"))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-residual-ignores-troubleshooter-owned-requests
  ;; Given only Troubleshooter-owned pending chat
  ;; When SL runs residual
  ;; Then it does not claim answer_dashboard_request (TS owns the front door)
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (let [created (dashreq/create-request root {:kind "question" :body "Status?"})
            id (get-in created [:request "id"])
            next (run {:dir root} (script "squad_next.sh") "--residual-only")]
        (is (some? id))
        (is (not (str/includes? (:out next) "NEXT_ACTION: answer_dashboard_request")))
        (is (not (str/includes? (:out next) (str "REQUEST_ID: " id)))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-surfaces-sl-owned-pending-dashboard-request
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (let [created (dashreq/create-request root {:kind "question" :body "Add story about bats"})
            id (get-in created [:request "id"])]
        (is (:ok (dashreq/route-to-sl root id)))
        (let [next (run {:dir root} (script "squad_next.sh"))]
          (is (str/includes? (:out next) "NEXT_ACTION: answer_dashboard_request"))
          (is (str/includes? (:out next) (str "REQUEST_ID: " id)))
          (is (str/includes? (:out next) (str "squad_dashboard_request.sh answer " id)))
          (is (not (str/includes? (:out next) "NEXT_ACTION: wait")))))
      (finally
        (fs/delete-tree root)))))

(deftest pane-text-does-not-complete-request
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (let [created (dashreq/create-request root {:kind "command" :body "Do the thing"})
            id (get-in created [:request "id"])]
        (is (:ok (dashreq/route-to-sl root id)))
        ;; Simulate SL "replying" only in chat — no helper call.
        (is (fs/exists? (fs/path root ".swarmforge/dashboard/requests/pending" (str id ".request"))))
        (is (= "pending" (get (first (dashreq/pending-requests root)) "status")))
        (let [next (run {:dir root} (script "squad_next.sh"))]
          (is (str/includes? (:out next) "answer_dashboard_request"))))
      (finally
        (fs/delete-tree root)))))

(deftest web-create-cancel-and-state-include-requests
  (let [root (tmp-dir)
        bin (fs/path root "bin")
        fake-tmux (fs/path bin "tmux")
        fake-state (fs/path root "fake-tmux-state")]
    (try
      (init-repo! root)
      (fs/create-dirs bin)
      (write-file fake-tmux
                  (str "#!/usr/bin/env sh\n"
                       "mkdir -p \"$FAKE_TMUX_STATE\"\n"
                       "cmd=\"$3\"\n"
                       "case \"$cmd\" in\n"
                       "  send-keys)\n"
                       "    case \"$*\" in\n"
                       "      *dashboard-*|*Dashboard*request*|*squad_dashboard_request*|*REQUEST_ID*) touch \"$FAKE_TMUX_STATE/sl-request\" ;;\n"
                       "      *\"User message from dashboard\"*) touch \"$FAKE_TMUX_STATE/sl-message\" ;;\n"
                       "    esac\n"
                       "    exit 0\n"
                       "    ;;\n"
                       "  *) exit 0 ;;\n"
                       "esac\n"))
      (run {:dir root} "chmod" "+x" (str fake-tmux))
      (fs/create-dirs (fs/path root ".swarmforge/daemon"))
      (write-file (fs/path root ".swarmforge/tmux-socket") "/tmp/swarmforge-test.sock\n")
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (run {:dir root :ok? false}
           "sh" "-c"
           (str "FAKE_TMUX_STATE=" fake-state
                " PATH=" bin ":$PATH"
                " SWARMFORGE_SQUADD_SKIP_TMUX=1 SWARMFORGE_SQUADD_WEB_PORT=0 bb "
                (script "squadd.clj") " " root " >/dev/null 2>&1 &"))
      (let [url-file (fs/path root ".swarmforge/daemon/squad-web-url")]
        (is (wait-for-file url-file 3000))
        (let [base-url (str/trim (slurp (str url-file)))
              page (slurp base-url)
              create (http-post (str base-url "api/sl-requests")
                                "{\"kind\":\"command\",\"body\":\"Ship it\"}")
              state (slurp (str base-url "api/state"))
              list (slurp (str base-url "api/sl-requests"))]
          (is (str/includes? page "Troubleshooter"))
          (is (not (str/includes? page "setKind(")))
          (is (not (str/includes? page "kind-command")))
          (is (str/includes? page "renderChat"))
          (is (str/includes? page "sl-requests"))
          (is (str/includes? page "bubble-you"))
          (is (str/includes? page "bubble-ts"))
          (is (= 200 (:status create)))
          (is (str/includes? state "\"sl_requests\""))
          (is (str/includes? state "Ship it"))
          (is (str/includes? list "Ship it"))
          (is (wait-for-file (fs/path fake-state "sl-request") 2000))
          (let [id (second (re-find #"\"id\":\"([^\"]+)\"" (:body create)))
                cancel (http-post (str base-url "api/sl-requests/" id "/cancel"))]
            (is (some? id))
            (is (= 200 (:status cancel)))
            (is (fs/exists? (fs/path root ".swarmforge/dashboard/requests/rejected" (str id ".request")))))))
      (finally
        (run {:dir root :ok? false} (script "stop_squadd.clj") (str root))
        (fs/delete-tree root)))))

(deftest sl-message-wrapper-creates-command-request
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root ".swarmforge/tmux-socket") "/tmp/x.sock\n")
      (with-redefs [web/tmux-notify! (constantly true)
                    web/socket-value (constantly "/tmp/x.sock")]
        (let [result (web/create-sl-request-action! root "legacy plain message")]
          (is (:ok result))
          (is (= "request" (get-in result [:request "kind"])))
          (is (= "legacy plain message" (get-in result [:request "body"])))
          (is (seq (dashreq/pending-requests root)))))
      (finally
        (fs/delete-tree root)))))

(deftest troubleshooter-wake-is-id-prefixed-raw-body
  ;; Given a new dashboard request
  ;; When building the Troubleshooter wake paste
  ;; Then it is raw inject: [id] body — not a long instructional essay
  (let [req {"id" "dashboard-20260812T000000Z-001"
             "body" "hi there"
             "kind" "request"
             "owner" "troubleshooter"}
        msg (web/dashboard-request-wake-message req)]
    (is (= "[dashboard-20260812T000000Z-001] hi there" msg))
    (is (not (str/includes? msg "Run: squad_dashboard_request")))
    (is (not (str/includes? msg "COMMAND_REPAIR"))))
  (let [req {"id" "dashboard-m"
             "body" "line one\nline two"}
        msg (web/dashboard-request-wake-message req)]
    (is (str/starts-with? msg "[dashboard-m]\n"))
    (is (str/includes? msg "line one\nline two"))))

(deftest dashboard-html-preserves-multiline-request-text
  (is (or (str/includes? web/dashboard-html "white-space: pre-wrap")
          (str/includes? web/dashboard-html "white-space:pre-wrap"))
      "Request body/response must keep newlines in the UI")
  (is (str/includes? web/dashboard-html "bubble-you")
      "request body class present")
  (is (str/includes? web/dashboard-html "bubble-ts")
      "response class present")
  (is (str/includes? web/dashboard-html "Enter send")
      "Enter sends chat; Shift+Enter line break")
  (is (not (str/includes? web/dashboard-html "Type TEARDOWN"))
      "single-confirm teardown; no type-TEARDOWN step"))

(deftest progress-notes-keep-request-pending
  ;; Interim notes while Troubleshooter works; answer still closes
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (let [created (dashreq/create-request root {:body "Why is this stuck?"})
            id (get-in created [:request "id"])]
        (write-file (fs/path root "n1.txt") "Checking merge_blocked assignments.\n")
        (let [n1 (run {:dir root} (script "squad_dashboard_request.sh") "note" id "n1.txt")]
          (is (str/includes? (:out n1) "STATE: pending"))
          (is (str/includes? (:out n1) "NOTE:"))
          (is (str/includes? (:out n1) "Checking merge_blocked")))
        (write-file (fs/path root "n2.txt") "Merger slot held by handoff_sent.\n")
        (run {:dir root} (script "squad_dashboard_request.sh") "note" id "n2.txt")
        (let [pending (first (dashreq/pending-requests root))]
          (is (= "pending" (get pending "status")))
          (is (= 2 (count (get pending "progress"))))
          (is (str/includes? (get (first (get pending "progress")) "text") "merge_blocked")))
        (write-file (fs/path root "answer.txt") "Root cause: merger merge_blocked on cave-impl.\n")
        (run {:dir root} (script "squad_dashboard_request.sh") "answer" id "answer.txt")
        (let [listed (first (filter #(= id (get % "id")) (dashreq/list-all-requests root)))]
          (is (= "answered" (get listed "status")))
          (is (= 2 (count (get listed "progress")))
              "progress survives answer")
          (is (str/includes? (get listed "response") "Root cause"))))
      (finally
        (fs/delete-tree root)))))

(deftest note-on-answered-request-fails
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (let [created (dashreq/create-request root {:body "hi"})
            id (get-in created [:request "id"])]
        (is (:ok (dashreq/answer-request root id "hello")))
        (is (not (:ok (dashreq/append-progress-note! root id "too late")))))
      (finally
        (fs/delete-tree root)))))

(deftest sl-queue-depth-counts-requests-and-handoffs
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (is (= 0 (web/sl-queue-depth root)))
      (dashreq/create-request root {:body "one"})
      (dashreq/create-request root {:body "two"})
      (write-file (fs/path root ".swarmforge/handoffs/inbox/new/50_a.handoff") "type: git_handoff\n\n")
      (write-file (fs/path root ".swarmforge/handoffs/inbox/in_process/50_b.handoff") "type: git_handoff\n\n")
      (is (= 4 (web/sl-queue-depth root))
          "2 pending requests + 1 new + 1 in_process")
      (let [state (web/web-state root)]
        (is (= 4 (get state "sl_queue_depth")))
        (is (str/includes? web/dashboard-html "sl-requests-title"))
        (is (str/includes? web/dashboard-html "ts-busy")))
      (finally
        (fs/delete-tree root)))))