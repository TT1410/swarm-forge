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
      (let [created (dashreq/create-request root {:kind "command" :body "Summarize status"})
            id (get-in created [:request "id"])]
        (is (:ok created))
        (is (fs/exists? (fs/path root ".swarmforge/dashboard/requests/pending" (str id ".request"))))
        (is (= 1 (count (dashreq/pending-requests root))))
        (write-file (fs/path root "answer.txt") "All clear.\n")
        (let [answered (run {:dir root} (script "squad_dashboard_request.sh") "answer" id "answer.txt")]
          (is (str/includes? (:out answered) "STATE: answered"))
          (is (fs/exists? (fs/path root ".swarmforge/dashboard/requests/answered" (str id ".request"))))
          (is (not (fs/exists? (fs/path root ".swarmforge/dashboard/requests/pending" (str id ".request")))))))
      (let [q (dashreq/create-request root {:kind "question" :body "What is blocked?"})
            qid (get-in q [:request "id"])]
        (write-file (fs/path root "empty.txt") "")
        (let [bad (run {:dir root :ok? false}
                       (script "squad_dashboard_request.sh") "answer" qid "empty.txt")]
          (is (= 2 (:exit bad)))
          (is (str/includes? (:err bad) "non-empty")))
        (write-file (fs/path root "reason.txt") "not now")
        (let [rej (run {:dir root} (script "squad_dashboard_request.sh") "reject" qid "reason.txt")]
          (is (str/includes? (:out rej) "STATE: rejected"))))
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

(deftest squad-next-surfaces-pending-dashboard-request
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (let [created (dashreq/create-request root {:kind "question" :body "Status?"})
            id (get-in created [:request "id"])
            next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "NEXT_ACTION: answer_dashboard_request"))
        (is (str/includes? (:out next) (str "REQUEST_ID: " id)))
        (is (str/includes? (:out next) (str "squad_dashboard_request.sh answer " id)))
        (is (not (str/includes? (:out next) "NEXT_ACTION: wait"))))
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
                       "      *Dashboard*request*|*squad_dashboard_request*|*REQUEST_ID*) touch \"$FAKE_TMUX_STATE/sl-request\" ;;\n"
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
          (is (str/includes? page "Squad Leader Requests"))
          (is (str/includes? page "setKind('command')"))
          (is (str/includes? page "req-you"))
          (is (str/includes? page "req-sl"))
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
          (is (= "command" (get-in result [:request "kind"])))
          (is (= "legacy plain message" (get-in result [:request "body"])))
          (is (seq (dashreq/pending-requests root)))))
      (finally
        (fs/delete-tree root)))))
