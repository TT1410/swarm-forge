(ns swarmforge.pack-forge-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [swarmforge.pack-test-support :refer :all]))

(use-fixtures :once once-fixture)

(deftest forge-infers-github-project-name
  (let [result (pack-web (tmp-dir) true "--test-inferred-name" "unclebob/swarm-forge" "github")]
    (is (zero? (:exit result)))
    (is (= "swarm-forge" (str/trim (:out result))))))
(deftest forge-new-project-writes-mission-and-conf
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (let [result (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                               "--test-new-project" (str root) "cave" "four-pack" "Hunt the wumpus")
          dest (fs/path root "projects/cave")]
      (is (zero? (:exit result)) (:err result))
      (is (fs/directory? dest))
      (is (= "Hunt the wumpus\n" (slurp (str (fs/path dest "mission.md")))))
      (is (fs/exists? (fs/path dest "swarmforge/swarmforge.conf")))
      (is (fs/exists? (fs/path dest "swarmforge/roles/specifier.prompt")))
      (is (str/includes? (slurp (str (fs/path dest ".swarmforge/pack"))) "lieutenant"))
      (is (str/includes? (slurp (str (fs/path root ".swarmforge/open-projects"))) "cave")))))
(deftest forge-new-project-rejects-existing-name
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "one")
    (let [result (run {:dir root :env {"SWARMFORGE_SKIP_START" "1"} :ok? false}
                      (script "pack_web.sh")
                      "--test-new-project" (str root) "cave" "two-pack" "two")]
      (is (not (zero? (:exit result))))
      (is (str/includes? (str (:err result) (:out result)) "already exists")))))
(deftest forge-open-already-open-alerts
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "m")
    (let [result (run {:dir root :env {"SWARMFORGE_SKIP_START" "1"} :ok? false}
                      (script "pack_web.sh")
                      "--test-open-project" (str root) "cave")]
      (is (not (zero? (:exit result))))
      (is (str/includes? (str (:err result) (:out result)) "already open")))))
(deftest forge-close-leaves-the-directory
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "m")
    (let [result (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                               "--test-close-project" (str root) "cave")]
      (is (zero? (:exit result)) (:err result))
      (is (fs/directory? (fs/path root "projects/cave")))
      (is (fs/exists? (fs/path root "projects/cave/mission.md")))
      (is (not (str/includes? (slurp (str (fs/path root ".swarmforge/open-projects"))) "cave"))))))
(deftest forge-open-without-pack-file-is-rejected
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (fs/create-dirs (fs/path root "projects/orphan"))
    (let [result (run {:dir root :env {"SWARMFORGE_SKIP_START" "1"} :ok? false}
                      (script "pack_web.sh")
                      "--test-open-project" (str root) "orphan")]
      (is (not (zero? (:exit result))))
      (is (str/includes? (str (:err result) (:out result)) "No pack recorded")))))
(deftest forge-refresh-keeps-mission-and-conf
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "four-pack" "keep me")
    (let [conf (fs/path root "projects/cave/swarmforge/swarmforge.conf")]
      (spit (str conf) "window specifier grok master extra-flag\n")
      (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                    "--test-close-project" (str root) "cave")
      (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                    "--test-open-project" (str root) "cave")
      (is (= "keep me\n" (slurp (str (fs/path root "projects/cave/mission.md")))))
      (is (str/includes? (slurp (str conf)) "extra-flag")))))
(deftest forge-state-tags-attention-with-project
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "m")
    (write-file (fs/path root "projects/cave/.swarmforge/roles.tsv")
                (format "specifier\tmaster\t%s\tspecifier\tSpecifier\tcodex\ttask\n"
                        (fs/path root "projects/cave")))
    (fs/create-dirs (fs/path root "projects/cave/.swarmforge/board"))
    (write-file (fs/path root "projects/cave/.swarmforge/handoffs/pending_approval/50_hello.handoff")
                "from: specifier\nto: coder\ntype: git_handoff\ntask: HTW\n\npayload\n")
    (let [state (json/parse-string
                 (:out (pack-web root true "--test-state" (str root)))
                 true)
          approval (first (:approvals state))]
      (is (true? (:forge state)))
      (is (= "cave" (:project approval)))
      (is (= ["cave"] (:open_projects state)))
      (is (= "cave" (:name (first (:projects state))))))))
(deftest forge-state-lists-board-allows-and-allow-writes-file
  (let [root (tmp-dir)
        project (fs/path root "projects/cave")]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "m")
    (create-task project "HTW" "specifier")
    (pack-board project true "request-allow" "--root" (str project)
                "--name" "HTW" "--act" "move")
    (let [state (json/parse-string
                 (:out (pack-web root true "--test-state" (str root)))
                 true)
          item (first (:board_allows state))]
      (is (= "cave" (:project item)))
      (is (= "HTW" (:task item)))
      (is (= "move" (:act item))))
    (pack-web root true "--test-allow" (str root) "HTW" "move" "cave")
    (is (fs/exists? (fs/path project ".swarmforge/board/lt-allow/HTW-move")))
    (is (not (fs/exists? (fs/path project ".swarmforge/board/lt-allow-pending/HTW-move"))))
    (let [state (json/parse-string
                 (:out (pack-web root true "--test-state" (str root)))
                 true)]
      (is (empty? (:board_allows state))))))
(deftest forge-pane-capture-uses-project-root
  ;; Given a forge with an open project and a recorded specifier pane
  ;; When GET /api/agents/specifier/pane?project=cave
  ;; Then it prints that pane, not the host miss
  (let [root (tmp-dir)
        dest (fs/path root "projects/cave")]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "m")
    (write-file (fs/path dest ".swarmforge/roles.tsv")
                (format "specifier\tmaster\t%s\tswarmforge-specifier\tSpecifier\tcodex\ttask\n"
                        dest))
    (write-file (fs/path dest ".swarmforge/sessions/specifier/pane.txt")
                "cave specifier pane\n")
    (let [host (pack-web root true "--test-pane" (str root) "specifier")
          project (pack-web root true "--test-pane" (str root) "specifier" "cave")]
      (is (str/includes? (:out host) "(no pane capture for specifier)"))
      (is (str/includes? (:out project) "cave specifier pane")))))
(deftest forge-mission-reads-project-mission-md
  ;; Given an open forge project with mission.md
  ;; When GET /api/mission?project=cave
  ;; Then it prints that mission
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "four-pack" "Hunt the wumpus")
    (let [result (pack-web root true "--test-mission" (str root) "cave")]
      (is (zero? (:exit result)))
      (is (str/includes? (:out result) "Hunt the wumpus")))))
(deftest forge-card-status-is-per-project
  ;; Given two open projects each with a specifier card and its own pane
  ;; When --test-state
  ;; Then each card keeps the status from its own project, not the other
  (let [root (tmp-dir)
        cave (fs/path root "projects/cave")
        dice (fs/path root "projects/dice")]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "four-pack" "m")
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "dice" "four-pack" "m")
    (setup-pack! cave ["specifier"])
    (setup-pack! dice ["specifier"])
    (create-task cave "htw" "specifier")
    (create-task dice "begin" "specifier")
    (write-file (fs/path cave ".swarmforge/sessions/specifier/pane.txt")
                "I'm specifying Hunt the Wumpus.\n")
    (write-file (fs/path dice ".swarmforge/sessions/specifier/pane.txt")
                "I'll implement begin.\n")
    (let [state (json/parse-string
                 (:out (pack-web root true "--test-state" (str root)))
                 true)
          by (into {} (map (juxt :name identity) (:projects state)))
          cave-status (:status (first (:tasks (get by "cave"))))
          dice-status (:status (first (:tasks (get by "dice"))))]
      (is (str/includes? (str cave-status) "Hunt the Wumpus"))
      (is (str/includes? (str dice-status) "I'll implement begin"))
      (is (not (str/includes? (str cave-status) "begin")))
      (is (not (str/includes? (str dice-status) "Hunt the Wumpus"))))))
(deftest forge-state-includes-lieutenant-status-lines
  ;; Given a forge lieutenant pane with two status sentences
  ;; When --test-state
  ;; Then lieutenant_status is those two lines
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (fs/create-dirs (fs/path root "projects"))
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tgrok\ttask\tforward-only\n"
                        root))
    (write-file (fs/path root ".swarmforge/sessions/lieutenant/pane.txt")
                (str "I'm listing the open projects.\n"
                     "I'll summarize HTW next.\n"))
    (let [state (json/parse-string
                 (:out (pack-web root true "--test-state" (str root)))
                 true)]
      (is (true? (:forge state)))
      (is (= ["I'm listing the open projects." "I'll summarize HTW next."]
             (:lieutenant_status state))))))
(deftest forge-state-includes-lieutenant-clarifications
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "m")
    (fs/create-dirs (fs/path root ".swarmforge/dashboard/clarifications/pending"))
    (write-file (fs/path root ".swarmforge/dashboard/clarifications/pending/clar-lt.request")
                (str "id: clar-lt\nstatus: pending\nrole: lieutenant\n"
                     "created_at: 2026-01-01T00:00:00Z\n\n"
                     "coder is stalled on cave\n"))
    (let [state (json/parse-string
                 (:out (pack-web root true "--test-state" (str root)))
                 true)
          item (first (filter #(= "clar-lt" (:id %)) (:clarifications state)))]
      (is (= "lieutenant" (:source item)))
      (is (str/includes? (:body item) "stalled")))))
(deftest forge-new-project-notifies-lieutenant
  (let [root (tmp-dir)
        argv (str (fs/path root "tmux.argv"))]
    (seed-mini-forge! root)
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tgrok\ttask\tforward-only\n"
                        root))
    (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "tmux.sock") "\n"))
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1" "SWARMFORGE_TMUX_STUB" argv}
                  "--test-new-project" (str root) "cave" "two-pack" "a mission")
    (let [notes (vec (fs/glob (fs/path root ".swarmforge/notify") "*new-project*.notify"))]
      (is (seq notes))
      (is (str/includes? (slurp (str (first notes))) "event: new-project"))
      (is (str/includes? (slurp (str (first notes))) "project: cave")))
    (is (seq (submitted-texts (read-argv argv))))))
(deftest forge-lt-task-does-not-create-a-card
  (let [root (tmp-dir)
        argv (str (fs/path root "tmux.argv"))]
    (seed-mini-forge! root)
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tgrok\ttask\tforward-only\n"
                        root))
    (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "tmux.sock") "\n"))
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "m")
    (let [resp (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv}
                             "--test-post-task" (str root) "Shim" "fit this" "cave" "LT")
          state (json/parse-string
                 (:out (pack-web root true "--test-state" (str root)))
                 true)
          cave (first (filter #(= "cave" (:name %)) (:projects state)))
          notes (vec (fs/glob (fs/path root ".swarmforge/notify") "*new-task*.notify"))]
      (is (zero? (:exit resp)))
      (is (empty? (filter #(= "Shim" (:name %)) (or (:tasks cave) []))))
      (is (seq notes))
      (is (str/includes? (slurp (str (first notes))) "type: LT"))
      (is (str/includes? (slurp (str (first notes))) "fit this"))
      (is (seq (submitted-texts (read-argv argv)))))))
(deftest forge-allow-notifies-lieutenant
  (let [root (tmp-dir)
        project (fs/path root "projects/cave")
        argv (str (fs/path root "tmux.argv"))]
    (seed-mini-forge! root)
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tgrok\ttask\tforward-only\n"
                        root))
    (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "tmux.sock") "\n"))
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "m")
    (create-task project "HTW" "specifier")
    (pack-board project true "request-allow" "--root" (str project)
                "--name" "HTW" "--act" "stop")
    (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv}
                  "--test-allow" (str root) "HTW" "stop" "cave")
    (let [notes (vec (fs/glob (fs/path root ".swarmforge/notify") "*allow*.notify"))]
      (is (seq notes))
      (is (str/includes? (slurp (str (first notes))) "event: allow"))
      (is (str/includes? (slurp (str (first notes))) "act: stop")))
    (is (seq (submitted-texts (read-argv argv))))))
(deftest forge-open-project-does-not-notify-new-project
  (let [root (tmp-dir)
        argv (str (fs/path root "tmux.argv"))]
    (seed-mini-forge! root)
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tgrok\ttask\tforward-only\n"
                        root))
    (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "tmux.sock") "\n"))
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1" "SWARMFORGE_TMUX_STUB" argv}
                  "--test-new-project" (str root) "cave" "two-pack" "m")
    (pack-web root true "--test-close-project" (str root) "cave")
    (doseq [file (fs/glob (fs/path root ".swarmforge/notify") "*.notify")]
      (fs/delete-if-exists file))
    (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv}
                  "--test-open-project" (str root) "cave")
    (is (empty? (vec (fs/glob (fs/path root ".swarmforge/notify") "*new-project*.notify"))))))
(deftest forge-pack-clarify-notifies-lieutenant
  (let [root (tmp-dir)
        argv (str (fs/path root "tmux.argv"))
        question (fs/path root "tmp" "question.txt")]
    (seed-mini-forge! root)
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tgrok\ttask\tforward-only\n"
                        root))
    (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "tmux.sock") "\n"))
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "m")
    (write-file question "coder is blocked on bats_nearby\n")
    (let [dest (fs/path root "projects/cave")
          _ (write-file (fs/path dest ".swarmforge/roles.tsv")
                        (format "coder\tcoder\t%s\tcoder\tCoder\tcodex\ttask\n" dest))
          created (run {:dir dest
                        :env {"SWARMFORGE_ROLE" "coder"
                              "SWARMFORGE_TMUX_STUB" argv}}
                       (script "pack_dashboard_request.sh")
                       "clarify" (str question))
          notes (vec (fs/glob (fs/path root ".swarmforge/notify") "*clarify*.notify"))]
      (is (zero? (:exit created)) (:err created))
      (is (seq notes))
      (is (str/includes? (slurp (str (first notes))) "event: clarify"))
      (is (seq (submitted-texts (read-argv argv)))))))
(deftest handoffd-wakes-lieutenant-with-submit-keys
  (let [root (tmp-dir)
        bin (fs/path root "bin")
        fake-tmux (fs/path bin "tmux")
        tmux-log (str (fs/path root "tmux.log"))
        dest (fs/path root "projects/cave")]
    (seed-mini-forge! root)
    (fs/create-dirs bin)
    (write-file fake-tmux
                (str "#!/usr/bin/env bb\n"
                     "(when-let [log (System/getenv \"TMUX_LOG\")]\n"
                     "  (spit log (str (pr-str *command-line-args*) \"\\n\") :append true))\n"))
    (run {:dir root} "chmod" "+x" (str fake-tmux))
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "m")
    (setup-pack! dest ["specifier" "coder"])
    (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "tmux.sock") "\n"))
    (write-file (fs/path dest ".swarmforge/tmux-socket") (str (fs/path root "tmux.sock") "\n"))
    (write-file (fs/path dest ".swarmforge/handoffs/outbox/50_approved.handoff")
                (str "from: specifier\nto: coder\npriority: 50\ntype: git_handoff\n"
                     "task_id: cave\ntask: cave\ncommit: 1234567890\n"
                     "approved: true\n\npayload\n"))
    (let [result (run {:dir dest
                       :env {"PATH" (str bin ":" (System/getenv "PATH"))
                             "TMUX_LOG" tmux-log}}
                      "bb" (script "handoffd.bb") "--once" (str dest))
          notes (vec (fs/glob (fs/path root ".swarmforge/notify") "*specifier-handoff*.notify"))]
      (is (zero? (:exit result)) (:err result))
      (is (seq notes))
      (is (seq (submitted-texts (read-argv tmux-log) "swarmforge-lieutenant"))))))
(deftest forge-lieutenant-heat-rises
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tgrok\ttask\tforward-only\n"
                        root))
    (let [body (json/parse-string
                (:out (pack-web root false "--test-lieutenant-heat" (str root)))
                true)]
      (is (< (:before body) (:after body))))))
