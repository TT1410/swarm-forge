(ns swarmforge.pack-board-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [swarmforge.pack-test-support :refer :all]))

(use-fixtures :once once-fixture)

(deftest pack-board-creates-a-task-in-the-master-lane
  ;; Given a pack with specifier on master
  ;; When New Task records name htw-console-app
  ;; Then the card sits in lane specifier
  (let [root (tmp-dir)
        _ (setup-pack! root)
        created (create-task root "htw-console-app" "specifier")
        listed (:out (list-tasks root))
        on-disk (slurp (str (fs/path root ".swarmforge/board/tasks.tsv")))
        cols (str/split (or (task-row listed "htw-console-app") "") #"\t")]
    (is (zero? (:exit created)))
    (is (= listed on-disk))
    (is (= "htw-console-app" (nth cols 0 nil)))
    (is (= "specifier" (nth cols 1 nil)))
    (is (re-matches #"\d{4}-\d{2}-\d{2}T.*Z" (nth cols 2 "")))
    (is (= (nth cols 2 nil) (nth cols 3 nil)))
    (is (= "0" (nth cols 5 nil)))
    (is (= "component" (nth cols 6 nil)))))
(deftest new-task-writes-the-card-and-body
  ;; Given specifier is master
  ;; When create name=htw-console-app text="Integrate HTW stories…"
  ;; Then lane is specifier AND board/htw-console-app.txt has the text
  (let [root (tmp-dir)
        text "Integrate HTW stories…"]
    (write-file
     (fs/path root ".swarmforge/roles.tsv")
     (str "specifier\tmaster\t" root "\tsession\tSpecifier\tcodex\ttask\n"))
    (let [created (pack-board root true
                              "create"
                              "--root" (str root)
                              "--name" "htw-console-app"
                              "--type" "component"
                              "--text" text)
          body (slurp (str (fs/path root ".swarmforge/board/htw-console-app.txt")))]
      (is (zero? (:exit created)))
      (is (= "specifier" (task-lane root "htw-console-app")))
      (is (= text body))
      (is (= (str "# htw-console-app\n\nType: component\n\n" text "\n")
             (slurp (str (fs/path root "tasks/htw-console-app.md"))))))))
(deftest pack-board-waiting-create-skips-start-note
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)
        created (pack-board root true
                            "create" "--root" (str root)
                            "--name" "UiShim" "--type" "component"
                            "--waiting" "--merge-from" "coder"
                            "--text" "Shim the cave UI")
        notes (handoff-names (fs/path root ".swarmforge/handoffs/outbox"))
        doc (slurp (str (fs/path root "tasks/UiShim.md")))]
    (is (zero? (:exit created)))
    (is (= "waiting" (task-lane root "UiShim")))
    (is (empty? notes))
    (is (str/includes? doc "Merge-from: coder"))
    (pack-board root true
                "move" "--root" (str root)
                "--name" "UiShim" "--lane" "specifier"
                "--caller" "handoffd")
    (is (= "specifier" (task-lane root "UiShim")))
    (is (seq (filter #(str/includes? % "New_Task")
                     (handoff-names (fs/path root ".swarmforge/handoffs/outbox")))))))
(deftest pack-board-increment-audit-writes-durable-files
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))]
    (increment-audit! root task-id)
    (increment-audit! root task-id)
    (let [dir (fs/path root ".swarmforge/board/audits" task-id)
          files (mapv fs/file-name (fs/list-dir dir))]
      (is (= ["1.md" "2.md"] (vec (sort files))))
      (is (str/includes? (slurp (str (fs/path dir "1.md"))) "Audit 1")))))
(deftest pack-board-create-type-sets-lane-and-rejects-lane-flag
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)]
    (is (zero? (:exit (pack-board root true "create" "--root" (str root)
                                  "--name" "util" "--type" "utility"))))
    (is (= "coder" (task-lane root "util")))
    (is (= "utility" (nth (str/split (or (task-row (:out (list-tasks root)) "util") "") #"\t") 6)))
    (is (str/includes? (slurp (str (fs/path root "tasks/util.md"))) "Type: utility"))
    (is (zero? (:exit (pack-board root true "create" "--root" (str root)
                                  "--name" "rev" "--type" "review"))))
    (is (= "cleaner" (task-lane root "rev")))
    (let [rejected (pack-board root false "create" "--root" (str root)
                               "--name" "bad" "--type" "utility" "--lane" "specifier")]
      (is (pos? (:exit rejected)))
      (is (str/includes? (:err rejected) "rejects --lane")))
    (let [unknown (pack-board root false "create" "--root" (str root)
                              "--name" "bad2" "--type" "four")]
      (is (pos? (:exit unknown)))
      (is (str/includes? (:err unknown) "Unknown type")))
    (let [moved (do (pack-board root true "create" "--root" (str root)
                                "--name" "stay" "--type" "utility")
                    (pack-board root true "move" "--root" (str root)
                                "--name" "stay" "--lane" "cleaner"
                                "--caller" "handoffd")
                    (str/split (or (task-row (:out (list-tasks root)) "stay") "") #"\t"))]
      (is (= "cleaner" (nth moved 1)))
      (is (= "utility" (nth moved 6))))
    (let [no-caller (pack-board root false "move" "--root" (str root)
                                "--name" "stay" "--lane" "specifier")]
      (is (pos? (:exit no-caller)))
      (is (str/includes? (:err no-caller) "requires --caller")))
    (let [lt-denied (pack-board root false "move" "--root" (str root)
                                "--name" "stay" "--lane" "specifier"
                                "--caller" "lieutenant")]
      (is (pos? (:exit lt-denied)))
      (is (str/includes? (:err lt-denied) "requires --caller")))
    (let [requested (pack-board root true "request-allow" "--root" (str root)
                                "--name" "stay" "--act" "move")
          pending (fs/path root ".swarmforge/board/lt-allow-pending/stay-move")
          allowed (do (pack-board root true "allow" "--root" (str root)
                                  "--name" "stay" "--act" "move")
                      (pack-board root true "move" "--root" (str root)
                                  "--name" "stay" "--lane" "specifier"
                                  "--caller" "lieutenant"))]
      (is (zero? (:exit requested)))
      (is (not (fs/exists? pending)))
      (is (zero? (:exit allowed)))
      (is (= "specifier" (task-lane root "stay")))
      (is (not (fs/exists? (fs/path root ".swarmforge/board/lt-allow/stay-move")))))
    (let [reuse (pack-board root false "move" "--root" (str root)
                            "--name" "stay" "--lane" "coder"
                            "--caller" "lieutenant")]
      (is (pos? (:exit reuse)))
      (is (str/includes? (:err reuse) "requires --caller")))))
(deftest pack-board-serializes-concurrent-audit-increments
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))
        increments (doall (repeatedly 8 #(future (increment-audit! root task-id))))]
    (doseq [increment increments]
      @increment)
    (is (= 8 (:audit_count (task-card root "HTW"))))))
(deftest pack-board-lists-lanes-in-role-order
  ;; Given roles specifier, coder, QA
  ;; When pack_board lanes
  ;; Then it prints those roles in conf order
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier" "coder" "QA"])
        result (pack-board root true "lanes" "--root" (str root))]
    (is (= "specifier\ncoder\nQA\n" (:out result)))))
(deftest pack-board-reports-the-master-lane
  ;; Given specifier's worktree is master
  ;; When pack_board master-lane
  ;; Then it prints specifier
  (let [root (tmp-dir)]
    (write-file
     (fs/path root ".swarmforge/roles.tsv")
     (str "specifier\tmaster\t" root "\tsession\tSpecifier\tcodex\ttask\n"
          "coder\tcoder\t" root "/.worktrees/coder\tsession\tCoder\tcodex\ttask\n"))
    (let [result (pack-board root true "master-lane" "--root" (str root))]
      (is (= "specifier\n" (:out result))))))
(deftest pack-board-rejects-a-duplicate-task-name
  ;; Given a card named htw-console-app
  ;; When New Task records the same name again
  ;; Then the create is rejected and the original card is unchanged
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "htw-console-app" "specifier")
        before (:out (list-tasks root))
        duplicate (create-task root "htw-console-app" "specifier" false)
        after (:out (list-tasks root))]
    (is (not (zero? (:exit duplicate))))
    (is (str/includes? (str (:err duplicate) (:out duplicate)) "Duplicate"))
    (is (= before after))))
(deftest pack-board-archives-live-role-panes
  ;; Given a two-pack with a live card in coder and a done card
  ;; When pack_board archive-all with SWARMFORGE_PANE_STUB
  ;; Then coder's pane.txt exists and the done card is skipped
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]]
    (setup-pack! root roles)
    (create-task root "htw-console-app" "coder")
    (create-task root "already-done" "done")
    (let [result (run {:dir root :env {"SWARMFORGE_PANE_STUB" "pane\n"}}
                      (script "pack_board.sh")
                      "archive-all" "--root" (str root))]
      (is (zero? (:exit result)))
      (is (= "pane\n" (slurp (str (role-pane-path root "coder")))))
      (is (not (fs/exists? (role-pane-path root "done"))))
      (is (not (fs/exists? (pane-path root "coder" "htw-console-app")))))))
(deftest close-swarm-archives-live-role-panes
  ;; Given a two-pack with a live card in coder
  ;; When close-swarm
  ;; Then coder's pane.txt is archived
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]]
    (setup-pack! root roles)
    (create-task root "htw-console-app" "coder")
    (write-file (fs/path root ".swarmforge/tmux-socket")
                (str (fs/path root "tmux.sock") "\n"))
    (write-file (fs/path root ".swarmforge/window-ids") "")
    (let [result (run {:dir root
                       :env {"SWARMFORGE_TERMINAL_BACKEND" "none"
                             "SWARMFORGE_PANE_STUB" "pane\n"}}
                      (str (fs/path repo-root "close-swarm"))
                      (str root))]
      (is (zero? (:exit result)))
      (is (= "pane\n" (slurp (str (role-pane-path root "coder"))))))))
(deftest pack-board-move-matches-task-name-ignoring-case
  ;; Given board card HTW
  ;; When pack_board move --name htw --lane coder
  ;; Then the card HTW is in coder
  (let [root (tmp-dir)]
    (setup-pack! root)
    (create-task root "HTW" "specifier")
    (pack-board root true "move" "--root" (str root) "--name" "htw" "--lane" "coder"
                "--caller" "handoffd")
    (is (= "coder" (task-lane root "HTW")))))
(deftest pack-board-stop-returns-card-to-waiting-and-resets
  ;; Given a live card with in_process, start mail, and Attention files
  ;; When pack_board stop
  ;; Then the card is waiting, the tree is reset, and that card's mail is gone
  (let [root (tmp-dir)
        roles ["specifier"]
        planted (plant-live-card-for-halt! root roles)]
    (is (fs/exists? (fs/path root "extra.md")))
    (pack-board root true "stop" "--root" (str root) "--name" "HTW" "--caller" "handoffd")
    (assert-card-halted! root roles planted)))
(deftest pack-board-move-to-waiting-is-stop
  ;; Given a live card with in_process, start mail, and Attention files
  ;; When pack_board move --lane waiting
  ;; Then the halt is the same as stop
  (let [root (tmp-dir)
        roles ["specifier"]
        planted (plant-live-card-for-halt! root roles)]
    (pack-board root true "move" "--root" (str root) "--name" "HTW"
                "--lane" "waiting" "--caller" "handoffd")
    (assert-card-halted! root roles planted)))
(deftest pack-board-stop-reports-failed-reset-to-lieutenant
  ;; Given a live card whose task_base_commit will not reset
  ;; When pack_board stop
  ;; Then the card is waiting, the tree is left, and the lieutenant is told
  (let [forge (tmp-dir)
        project (fs/path forge "projects" "cave")
        roles ["specifier"]
        argv (str (fs/path forge "tmux.argv"))]
    (fs/create-dirs project)
    (write-file (fs/path forge ".swarmforge/roles.tsv")
                (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tgrok\ttask\tforward-only\n"
                        forge))
    (write-file (fs/path forge ".swarmforge/tmux-socket")
                (str (fs/path forge "tmux.sock") "\n"))
    (let [planted (plant-live-card-for-halt! project roles)]
      (write-file (:in-process planted)
                  (str "from: (New Task)\nto: specifier\npriority: 50\ntype: note\n"
                       "task: HTW\ntask_id: " (:task-id planted) "\n"
                       "task_base_commit: deadbeefdead\n\nGo\n"))
      (run {:dir project :env {"SWARMFORGE_TMUX_STUB" argv}}
           (script "pack_board.sh")
           "stop" "--root" (str project) "--name" "HTW" "--caller" "handoffd")
      (is (= "waiting" (task-lane project "HTW")))
      (is (fs/exists? (fs/path project "extra.md")))
      (is (not (fs/exists? (:in-process planted))))
      (let [notes (vec (fs/glob (fs/path forge ".swarmforge/notify") "*reset-failed*.notify"))]
        (is (seq notes))
        (is (str/includes? (slurp (str (first notes))) "event: reset-failed"))
        (is (str/includes? (slurp (str (first notes))) "task: HTW")))
      (let [argv-text (slurp argv)]
        (is (str/includes? argv-text "swarmforge-lieutenant"))
        (is (str/includes? argv-text "git reset failed for HTW"))))))
(deftest pack-board-move-to-other-lane-does-not-halt
  ;; Given a live card on specifier
  ;; When pack_board move --lane coder
  ;; Then the tree and Attention files stay
  (let [root (tmp-dir)
        roles ["specifier" "coder"]
        planted (plant-live-card-for-halt! root roles)]
    (pack-board root true "move" "--root" (str root) "--name" "HTW"
                "--lane" "coder" "--caller" "handoffd")
    (is (= "coder" (task-lane root "HTW")))
    (is (fs/exists? (fs/path root "extra.md")))
    (is (fs/exists? (:in-process planted)))
    (is (fs/exists? (:pending planted)))
    (is (fs/exists? (:outbox planted)))))
(deftest pack-board-lieutenant-starts-waiting-without-allow
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)]
    (pack-board root true "create" "--root" (str root)
                "--name" "place-hunter" "--type" "component" "--waiting")
    (let [moved (pack-board root true "move" "--root" (str root)
                            "--name" "place-hunter" "--lane" "specifier"
                            "--caller" "lieutenant")]
      (is (zero? (:exit moved)))
      (is (= "specifier" (task-lane root "place-hunter")))
      (is (not (fs/exists? (fs/path root ".swarmforge/board/lt-allow-pending/place-hunter-move"))))
      (is (not (str/includes? (slurp (str (fs/path root "tasks/place-hunter.md")))
                              "Merge-from:")))
      (let [state (json/parse-string
                   (:out (pack-web root true "--test-state" (str root)))
                   true)]
        (is (empty? (:board_allows state)))))))
(deftest pack-board-lieutenant-live-move-needs-allow
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)]
    (create-task root "HTW" "specifier")
    (let [moved (pack-board root false "move" "--root" (str root)
                            "--name" "HTW" "--lane" "coder"
                            "--caller" "lieutenant")]
      (is (not (zero? (:exit moved))))
      (is (= "specifier" (task-lane root "HTW"))))))
(deftest pack-board-lieutenant-waiting-wrong-lane-needs-allow
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)]
    (pack-board root true "create" "--root" (str root)
                "--name" "place-hunter" "--type" "component" "--waiting")
    (let [moved (pack-board root false "move" "--root" (str root)
                            "--name" "place-hunter" "--lane" "coder"
                            "--caller" "lieutenant")]
      (is (not (zero? (:exit moved))))
      (is (= "waiting" (task-lane root "place-hunter"))))))
(deftest pack-board-create-requires-type
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)
        result (pack-board root false "create" "--root" (str root)
                           "--name" "no-type")]
    (is (pos? (:exit result)))
    (is (str/includes? (:err result) "type"))
    (is (nil? (task-lane root "no-type")))))
(deftest pack-board-usage-lists-archive-flag
  (let [root (tmp-dir)
        result (pack-board root false)]
    (is (pos? (:exit result)))
    (is (str/includes? (:err result) "--archive"))))
(deftest pack-board-archive-accepts-archive-flag-and-aliases
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]]
    (setup-pack! root roles)
    (create-task root "htw-console-app" "coder")
    (let [flagged (run {:dir root :env {"SWARMFORGE_PANE_STUB" "flag\n"}}
                       (script "pack_board.sh")
                       "archive" "--archive" "coder" "--root" (str root))]
      (is (zero? (:exit flagged)))
      (is (= "flag\n" (slurp (str (role-pane-path root "coder"))))))
    (let [aliased (run {:dir root :env {"SWARMFORGE_PANE_STUB" "role\n"}}
                       (script "pack_board.sh")
                       "archive" "--role" "coder" "--root" (str root))]
      (is (zero? (:exit aliased)))
      (is (= "role\n" (slurp (str (role-pane-path root "coder"))))))
    (let [positional (run {:dir root :env {"SWARMFORGE_PANE_STUB" "pos\n"}}
                          (script "pack_board.sh")
                          "archive" "coder" "--root" (str root))]
      (is (zero? (:exit positional)))
      (is (= "pos\n" (slurp (str (role-pane-path root "coder"))))))))
(deftest pack-board-create-refuses-pending-clarify
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)
        pending (fs/path root ".swarmforge/dashboard/clarifications/pending/clar-1.request")]
    (write-file pending "id: clar-1\nstatus: pending\nrole: specifier\n\nstuck\n")
    (let [blocked (pack-board root false "create" "--root" (str root)
                              "--name" "next" "--type" "component")]
      (is (pos? (:exit blocked)))
      (is (str/includes? (:err blocked) "pending clarification"))
      (is (nil? (task-lane root "next"))))
    (fs/delete-if-exists pending)
    (let [ok (pack-board root true "create" "--root" (str root)
                         "--name" "next" "--type" "component")]
      (is (zero? (:exit ok)))
      (is (= "specifier" (task-lane root "next"))))))
(deftest pack-board-create-ignores-forge-lieutenant-clarify
  (let [forge (tmp-dir)
        project (fs/path forge "projects" "cave")]
    (fs/create-dirs project)
    (setup-pack! project six-pack-roles)
    (write-file (fs/path forge ".swarmforge/dashboard/clarifications/pending/lt.request")
                "id: lt\nstatus: pending\nrole: lieutenant\n\nstall\n")
    (let [ok (pack-board project true "create" "--root" (str project)
                         "--name" "next" "--type" "component")]
      (is (zero? (:exit ok)))
      (is (= "specifier" (task-lane project "next"))))))
(deftest pack-board-create-recut-allowed-while-stuck
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)]
    (pack-board root true "create" "--root" (str root)
                "--name" "stuck" "--type" "component")
    (write-file (fs/path root ".swarmforge/dashboard/clarifications/pending/clar.request")
                "id: clar\nstatus: pending\nrole: specifier\n\nstuck\n")
    (pack-board root true "delete" "--root" (str root) "--name" "stuck")
    (let [recut (pack-board root true "create" "--root" (str root)
                            "--name" "stuck" "--type" "utility")]
      (is (zero? (:exit recut)))
      (is (= "coder" (task-lane root "stuck"))))
    (let [other (pack-board root false "create" "--root" (str root)
                            "--name" "other" "--type" "component")]
      (is (pos? (:exit other)))
      (is (nil? (task-lane root "other"))))))
(deftest pack-board-create-allow-overrides-stuck
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)]
    (write-file (fs/path root ".swarmforge/dashboard/clarifications/pending/clar.request")
                "id: clar\nstatus: pending\nrole: specifier\n\nstuck\n")
    (pack-board root true "request-allow" "--root" (str root)
                "--name" "override" "--act" "create")
    (pack-board root true "allow" "--root" (str root)
                "--name" "override" "--act" "create")
    (let [ok (pack-board root true "create" "--root" (str root)
                         "--name" "override" "--type" "component"
                         "--caller" "lieutenant")]
      (is (zero? (:exit ok)))
      (is (= "specifier" (task-lane root "override"))))))
(deftest pack-board-create-refuses-in-flight-reverse
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)
        in-process (fs/path root ".swarmforge/handoffs/inbox/in_process/00_rev.handoff")]
    (write-file in-process
                (str "from: architect\nto: specifier\npriority: 00\n"
                     "type: git_handoff\ntask: stuck\nnon-forwarding: true\n\nmerge\n"))
    (let [blocked (pack-board root false "create" "--root" (str root)
                              "--name" "next" "--type" "component")]
      (is (pos? (:exit blocked)))
      (is (str/includes? (:err blocked) "in-flight reverse merge"))
      (is (nil? (task-lane root "next"))))))
