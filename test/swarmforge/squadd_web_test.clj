(ns swarmforge.squadd-web-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [swarmforge.test-support :refer :all]))

(deftest squadd-serves-web-status-and-registers-approvals
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
	                       "    count_file=\"$FAKE_TMUX_STATE/returns\"\n"
	                       "    count=0\n"
	                       "    test -f \"$count_file\" && read count < \"$count_file\"\n"
	                       "    case \"$*\" in\n"
	                       "      *\"web approval changed state\"*|*\"run squad_next.sh\"*) touch \"$FAKE_TMUX_STATE/web-approval-notify\" ;;\n"
	                       "      *\"User message from dashboard\"*) touch \"$FAKE_TMUX_STATE/sl-message\" ;;\n"
	                       "      *\" C-m\") count=$((count + 1)); echo \"$count\" > \"$count_file\" ;;\n"
	                       "    esac\n"
	                       "    exit 0\n"
                       "    ;;\n"
                       "  capture-pane)\n"
                       "    printf '%s\\n' 'active pane line 1' 'active pane line 2'\n"
                       "    exit 0\n"
                       "    ;;\n"
                       "  *) exit 0 ;;\n"
                       "esac\n"))
      (run {:dir root} "chmod" "+x" (str fake-tmux))
      (fs/create-dirs (fs/path root ".swarmforge/daemon"))
      (write-file (fs/path root ".swarmforge/tmux-socket")
                  "/tmp/swarmforge-test.sock\n")
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (write-file (fs/path root "stories/cave-topology.md")
                  "Story: cave topology and setup.\n")
      (write-file (fs/path root "features/cave-topology.feature")
                  "Feature: Cave topology\n")
      (write-file (fs/path root "qa/cave-topology.md")
                  "# QA Procedure\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root} (script "squad_theme.sh") "story" "wumpus" "cave-topology" "stories/cave-topology.md")
      (run {:dir root} "git" "add" "stories" "features" "qa")
      (run {:dir root} "git" "commit" "-q" "-m" "Prepare story for dashboard")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
        (run {:dir root}
             (script "squad_packet.sh")
             "create"
             "wumpus"
             "cave-topology"
             "wumpus-analysis"
	             "swarmforge-analyst-001"
	             sha))
	      (spit (str (fs/path root ".squad/stories/cave-topology/packet"))
	            "state: specification_in_progress\nimplementation_sha: implementation-sha\n"
	            :append true)
	      (spit (str (fs/path root ".squad/stories/cave-topology/packet"))
	            "gherkin_path: features/cave-topology.feature\nqa_procedure_path: qa/cave-topology.md\ngherkin_review_assignment: active-assignment\n"
	            :append true)
      (run {:dir root}
           (script "squad_approval.sh")
           "request"
           "story__cave-topology"
           "story"
           "cave-topology"
           "story"
           "Approve story"
           "story is ready")
      (write-file (fs/path root ".squad/agents/active-001/metadata")
                  "agent_id: active-001\ntemplate: implementer\ntask_id: active-task\nsession: active-session\n")
      (write-file (fs/path root ".squad/agents/active-001/status")
                  "state: running\ndetail: active\nupdated_at: 2026-08-03T00:00:00Z\n")
      (write-file (fs/path root ".squad/agents/blocked-001/metadata")
                  "agent_id: blocked-001\ntemplate: gherkin-writer\ntask_id: blocked-assignment\nsession: blocked-session\n")
      (write-file (fs/path root ".squad/agents/blocked-001/status")
                  "state: blocked\ndetail: missing gherkin-parser\nupdated_at: 2026-08-05T00:00:00Z\n")
      (write-file (fs/path root ".squad/agents/retired-001/metadata")
                  "agent_id: retired-001\ntemplate: analyst\ntask_id: retired-task\n")
      (write-file (fs/path root ".squad/agents/retired-001/status")
                  "state: retired\ndetail: done\nupdated_at: 2026-08-03T00:00:00Z\n")
      (write-file (fs/path root ".squad/assignments/active-assignment/metadata")
                  "assignment_id: active-assignment\ntemplate: implementer\nstory_id: cave-topology\n")
	      (write-file (fs/path root ".squad/assignments/active-assignment/status")
	                  "state: created\ndetail: active\nupdated_at: 2026-08-03T00:00:00Z\n")
	      (write-file (fs/path root ".squad/assignments/blocked-assignment/metadata")
	                  "assignment_id: blocked-assignment\ntemplate: gherkin-writer\nstory_id: cave-topology\n")
	      (write-file (fs/path root ".squad/assignments/blocked-assignment/status")
	                  "state: in_progress\nagent_id: blocked-001\ndetail: assigned\nupdated_at: 2026-08-05T00:00:00Z\n")
	      (write-file (fs/path root ".squad/assignments/newer-assignment/metadata")
	                  "assignment_id: newer-assignment\ntemplate: cleaner\nstory_id: cave-topology\n")
	      (write-file (fs/path root ".squad/assignments/newer-assignment/status")
	                  "state: blocked\ndetail: active\nupdated_at: 2026-08-04T00:00:00Z\n")
	      (write-file (fs/path root ".squad/assignments/newer-assignment/blocker")
	                  "assignment_id: newer-assignment\nstate: blocked\nkind: required-tool-evidence\nupdated_at: 2026-08-04T00:00:00Z\n")
	      (write-file (fs/path root ".squad/assignments/newer-assignment/blocker.md")
	                  "Cleaner could not load crap4clj.\n")
	      (write-file (fs/path root ".squad/assignments/resolved-assignment/metadata")
	                  "assignment_id: resolved-assignment\ntemplate: gherkin-writer\nstory_id: cave-topology\n")
	      (write-file (fs/path root ".squad/assignments/resolved-assignment/status")
	                  "state: merged\ndetail: done\nupdated_at: 2026-08-04T00:00:00Z\n")
	      (write-file (fs/path root ".squad/assignments/resolved-assignment/blocker")
	                  "assignment_id: resolved-assignment\nstate: blocked\nkind: stale\nupdated_at: 2026-08-04T00:00:00Z\n")
	      (write-file (fs/path root ".squad/reviews/active-assignment.md")
	                  "accepted\n")
      (write-file (fs/path root ".squad/assignments/merged-assignment/metadata")
                  "assignment_id: merged-assignment\ntemplate: analyst\nstory_id: cave-topology\n")
      (write-file (fs/path root ".squad/assignments/merged-assignment/status")
                  "state: merged\ndetail: done\nupdated_at: 2026-08-03T00:00:00Z\n")
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
	              state (slurp (str base-url "api/state"))
	              theme-page (slurp (str base-url "artifact/theme/wumpus"))
	              story-page (slurp (str base-url "artifact/story/cave-topology"))
	              gherkin-page (slurp (str base-url "artifact/gherkin/cave-topology"))
	              qa-page (slurp (str base-url "artifact/qa-procedure/cave-topology"))
	              review-page (slurp (str base-url "artifact/review/active-assignment"))
	              blocker-page (slurp (str base-url "artifact/blocker/newer-assignment"))
	              agent-page (slurp (str base-url "agent/active-001"))
	              pane-tail (slurp (str base-url "api/agents/active-001/pane"))
	              approve (http-post (str base-url "api/approvals/story__cave-topology/approve"))
	              returns-before-message (Long/parseLong
	                                      (str/trim
	                                       (slurp (str (fs/path fake-state "returns")))))
	              message (http-post (str base-url "api/sl-message") "Duplicate hazard messages should not appear.")
	              returns-after-message (Long/parseLong
	                                     (str/trim
	                                      (slurp (str (fs/path fake-state "returns")))))
	              approved (slurp (str base-url "api/state"))]
	          (is (str/includes? state "\"approval_id\":\"story__cave-topology\""))
	          (is (str/includes? state "\"story_id\":\"cave-topology\""))
	          (is (str/includes? state "\"state\":\"implemented\""))
	          (is (str/includes? state "\"stage_label\":\"implemented\""))
	          (is (not (str/includes? state "\"state\":\"specification_in_progress\"")))
	          (is (str/includes? state "\"agent_id\":\"active-001\""))
	          (is (not (str/includes? state "\"agent_id\":\"retired-001\"")))
	          (is (str/includes? state "\"assignment_id\":\"active-assignment\""))
	          (is (str/includes? state "\"assignment_id\":\"newer-assignment\""))
	          (is (str/includes? state "\"blockers\""))
	          (is (str/includes? state "\"kind\":\"required-tool-evidence\""))
	          (is (str/includes? state "\"assignment_id\":\"blocked-assignment\""))
	          (is (str/includes? state "\"kind\":\"agent-blocked\""))
	          (is (str/includes? state "\"detail\":\"missing gherkin-parser\""))
	          (is (not (str/includes? state "\"assignment_id\":\"resolved-assignment\"")))
	          (is (< (str/index-of state "\"assignment_id\":\"newer-assignment\"")
	                 (str/index-of state "\"assignment_id\":\"active-assignment\"")))
	          (is (not (str/includes? state "\"assignment_id\":\"merged-assignment\"")))
	          (is (str/includes? page "localStorage.getItem(slDraftKey)"))
	          (is (str/includes? page "messageInput.addEventListener('input'"))
	          (is (str/includes? page "messageInput.addEventListener('keydown'"))
	          (is (str/includes? page "event.key === 'Enter' && !event.shiftKey"))
	          (is (str/includes? page "localStorage.removeItem(slDraftKey)"))
	          (is (str/includes? page "const messageInput = document.getElementById('sl-message')"))
	          (is (not (str/includes? page "app.innerHTML")))
	          (is (str/includes? page "/artifact/${encodeURIComponent(kind)}/${encodeURIComponent(id)}"))
	          (is (str/includes? page "/agent/${encodeURIComponent(a.agent_id)}"))
	          (is (str/includes? theme-page "faithful Hunt the Wumpus"))
	          (is (str/includes? story-page "cave topology and setup"))
	          (is (str/includes? gherkin-page "Feature: Cave topology"))
	          (is (str/includes? qa-page "QA Procedure"))
	          (is (str/includes? review-page "accepted"))
	          (is (str/includes? blocker-page "crap4clj"))
	          (is (str/includes? agent-page "/api/agents/active-001/pane"))
	          (is (str/includes? agent-page "text!==pane.textContent"))
	          (is (str/includes? agent-page "nearBottom"))
	          (is (str/includes? agent-page "marker.style.display='block'"))
	          (is (str/includes? agent-page "New output"))
	          (is (not (str/includes? agent-page "› Improve documentation")))
	          (is (str/includes? pane-tail "active pane line 2"))
	          (is (= 200 (:status approve)))
	          (is (= 200 (:status message)))
	          (is (str/includes? approved "\"approved\""))
	          (is (fs/exists? (fs/path fake-state "web-approval-notify")))
	          (is (fs/exists? (fs/path fake-state "sl-message")))
	          ;; The web daemon can emit adjacent wakeups into the same fake tmux
	          ;; stream. This assertion only needs to prove the dashboard message
	          ;; sent the required double-return wakeup.
	          (is (<= 2 (- returns-after-message returns-before-message)))
          (is (fs/exists? (fs/path root ".squad/approvals/approved/story__cave-topology.approval")))
          (is (not (fs/exists? (fs/path root ".squad/approvals/pending/story__cave-topology.approval"))))))
      (finally
        (run {:dir root :ok? false} (script "stop_squadd.clj") (str root))
        (fs/delete-tree root)))))

(deftest squadd-omits-approval-history
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (fs/create-dirs (fs/path root ".swarmforge/daemon"))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "theme.md")
                  "Implement a faithful Hunt the Wumpus.\n")
      (run {:dir root} (script "squad_theme.sh") "create" "wumpus" "theme.md")
      (run {:dir root}
           (script "squad_approval.sh")
           "request"
           "theme__wumpus"
           "theme"
           "wumpus"
           "theme"
           "Approve theme"
           "theme is ready")
      (run {:dir root}
           (script "squad_approval.sh")
           "approve"
           "theme__wumpus"
           "approved by test")
      (run {:dir root :ok? false}
           "sh" "-c"
           (str "SWARMFORGE_SQUADD_SKIP_TMUX=1 SWARMFORGE_SQUADD_WEB_PORT=0 bb "
                (script "squadd.clj") " " root " >/dev/null 2>&1 &"))
	      (let [url-file (fs/path root ".swarmforge/daemon/squad-web-url")]
	        (is (wait-for-file url-file 3000))
	        (let [base-url (str/trim (slurp (str url-file)))
	              page (slurp base-url)
	              state (slurp (str base-url "api/state"))]
	          (is (not (str/includes? page "Approval History")))
	          (is (not (str/includes? state "\"approved\"")))
	          (is (not (str/includes? state "\"approval_id\":\"theme__wumpus\"")))
	          (is (not (str/includes? state "\"resolution_detail\":\"approved by test\"")))))
      (finally
        (run {:dir root :ok? false} (script "stop_squadd.clj") (str root))
        (fs/delete-tree root)))))

(deftest squadd-opens-dashboard-on-startup-when-enabled
  (let [root (tmp-dir)
        opener (fs/path root "open-dashboard")
        marker (fs/path root "opened-dashboard")]
    (try
      (init-repo! root)
      (fs/create-dirs (fs/path root ".swarmforge/daemon"))
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file opener
                  (str "#!/usr/bin/env sh\n"
                       "printf '%s\\n' \"$1\" > " marker "\n"))
      (run {:dir root} "chmod" "+x" (str opener))
      (run {:dir root :ok? false}
           "sh" "-c"
           (str "SWARMFORGE_SQUADD_WEB_PORT=0 "
                "SWARMFORGE_SQUADD_WEB_OPEN=1 "
                "SWARMFORGE_SQUADD_WEB_OPEN_COMMAND=" opener " "
                "bb " (script "squadd.clj") " " root " >/dev/null 2>&1 &"))
      (let [url-file (fs/path root ".swarmforge/daemon/squad-web-url")]
        (is (wait-for-file url-file 3000))
        (is (wait-for-file marker 3000))
        (is (= (str/trim (slurp (str url-file)))
               (str/trim (slurp (str marker))))))
      (finally
        (run {:dir root :ok? false} (script "stop_squadd.clj") (str root))
        (fs/delete-tree root)))))
