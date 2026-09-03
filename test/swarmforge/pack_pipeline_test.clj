(ns swarmforge.pack-pipeline-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [swarmforge.pack-test-support :refer :all]))

(use-fixtures :once once-fixture)

(deftest handoffd-keeps-delivery-success-when-session-wakeup-fails
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]]
    (setup-pack! root roles)
    (create-task root "HTW" "coder")
    (queue-handoff! root {:from "coder" :to "cleaner" :task "HTW"})
    (write-file (fs/path root ".swarmforge/tmux-socket") "/tmp/missing-swarmforge.sock\n")
    (let [result (handoffd-once root)]
      (is (zero? (:exit result)))
      (is (= "cleaner" (task-lane root "HTW")))
      (is (= 1 (count (inbox-names root roles "cleaner"))))
      (is (= 1 (count (handoff-names (fs/path root ".swarmforge/handoffs/sent")))))
      (is (empty? (handoff-names (fs/path root ".swarmforge/handoffs/failed"))))
      (is (seq (fs/glob (fs/path root ".swarmforge/daemon/wakeups") "*.edn"))))))

(deftest handoffd-retries-a-temporarily-unavailable-recipient
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        recipient (fs/path root ".worktrees/cleaner")]
    (setup-pack! root roles)
    (create-task root "HTW" "coder")
    (queue-handoff! root {:from "coder" :to "cleaner" :task "HTW"})
    (write-file (fs/path root ".swarmforge/tmux-socket") "/tmp/missing-swarmforge.sock\n")
    (fs/delete-tree recipient)
    (let [outbox (first (handoff-names (fs/path root ".swarmforge/handoffs/outbox")))
          source (fs/path root ".swarmforge/handoffs/outbox" outbox)
          retry (fs/path (str source ".retry.edn"))]
      (handoffd-once root)
      (is (fs/regular-file? source))
      (is (fs/regular-file? retry))
      (is (= "coder" (task-lane root "HTW")))
      (fs/create-dirs (fs/path recipient ".swarmforge/handoffs/inbox/new"))
      (write-file retry (str (pr-str {:attempt 1 :next-at 0 :error "test"}) "\n"))
      (handoffd-once root)
      (is (not (fs/exists? source)))
      (is (not (fs/exists? retry)))
      (is (= "cleaner" (task-lane root "HTW")))
      (is (= [outbox] (inbox-names root roles "cleaner"))))))

(deftest handoffd-shows-repeated-transient-failures-in-attention
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        recipient (fs/path root ".worktrees/cleaner")]
    (setup-pack! root roles)
    (create-task root "HTW" "coder")
    (queue-handoff! root {:from "coder" :to "cleaner" :task "HTW"})
    (write-file (fs/path root ".swarmforge/tmux-socket") "/tmp/missing-swarmforge.sock\n")
    (fs/delete-tree recipient)
    (let [source (first (fs/glob (fs/path root ".swarmforge/handoffs/outbox") "*.handoff"))
          retry (fs/path (str source ".retry.edn"))]
      (dotimes [attempt 3]
        (when (pos? attempt)
          (write-file retry (str (pr-str {:attempt attempt :next-at 0 :error "test"}) "\n")))
        (handoffd-once root))
      (let [failures (:delivery_failures (web-state root))]
        (is (= 1 (count failures)))
        (is (= 3 (:attempt (first failures))))
        (is (= "HTW" (:task (first failures)))))
      (is (fs/regular-file? source))
      (is (= "coder" (task-lane root "HTW"))))))

(deftest handoffd-moves-the-task-card-to-the-recipient
  ;; Given card htw-console-app in coder
  ;; When a git_handoff coder→cleaner for that task is delivered
  ;; Then the card lane is cleaner
  (let [root (tmp-dir)
        roles ["specifier" "coder" "cleaner"]
        sock (do (setup-pack! root roles)
                 (create-task root "htw-console-app" "coder")
                 (increment-audit! root (:id (task-card root "htw-console-app")))
                 (queue-handoff! root {:from "coder" :to "cleaner" :task "htw-console-app"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "cleaner" (task-lane root "htw-console-app")))
      (is (= 1 (:audit_count (task-card root "htw-console-app"))))
      (finally
        (stop-tmux! sock)))))
(deftest handoffd-marks-the-task-card-done-for-terminal-handoff
  ;; Given six-pack, card in QA (not master)
  ;; When QA queues git_handoff to every other role
  ;; Then the card lane is done
  (let [root (tmp-dir)
        to "specifier,coder,cleaner,architect,hardender"
        sock (do (setup-pack! root six-pack-roles)
                 (create-task root "htw-console-app" "QA")
                 (queue-handoff! root {:from "QA" :to to :task "htw-console-app"})
                 (start-tmux! root six-pack-roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "htw-console-app")))
      (finally
        (stop-tmux! sock)))))
(deftest handoffd-writes-forge-notify-on-coder-handoff
  (let [forge (tmp-dir)
        project (fs/path forge "projects" "cave")
        roles ["coder" "cleaner"]
        lt-sock (tmp-tmux-socket)]
    (fs/create-dirs project)
    (setup-pack! project roles)
    (create-task project "HTW" "coder")
    (queue-handoff! project {:from "coder" :to "cleaner" :task "HTW"})
    (write-file (fs/path forge ".swarmforge/tmux-socket") (str lt-sock "\n"))
    (run {:dir forge} "tmux" "-S" lt-sock "new-session" "-d" "-s" "swarmforge-lieutenant" "sleep" "120")
    (let [sock (start-tmux! project roles (tmp-tmux-socket))]
      (try
        (handoffd-once project)
        (let [notes (vec (fs/glob (fs/path forge ".swarmforge/notify") "*.notify"))]
          (is (seq notes))
          (let [text (slurp (str (first notes)))
                pane (:out (run {:dir forge}
                                "tmux" "-S" lt-sock "capture-pane" "-p" "-t" "swarmforge-lieutenant"))]
            (is (str/includes? (fs/file-name (first notes)) "coder-handoff"))
            (is (str/includes? text "event: coder-handoff\n"))
            (is (str/includes? pane "Notify: coder-handoff"))))
        (finally
          (stop-tmux! sock)
          (stop-tmux! lt-sock))))))
(deftest handoffd-writes-forge-notify-on-specifier-handoff
  (let [forge (tmp-dir)
        project (fs/path forge "projects" "cave")
        roles six-pack-roles
        lt-sock (tmp-tmux-socket)]
    (fs/create-dirs project)
    (setup-pack! project roles)
    (create-task project "HTW" "specifier")
    (queue-handoff! project {:from "specifier" :to "coder" :task "HTW"})
    (write-file (fs/path forge ".swarmforge/tmux-socket") (str lt-sock "\n"))
    (run {:dir forge} "tmux" "-S" lt-sock "new-session" "-d" "-s" "swarmforge-lieutenant" "sleep" "120")
    (let [sock (start-tmux! project roles (tmp-tmux-socket))]
      (try
        (handoffd-once project)
        (let [notes (vec (fs/glob (fs/path forge ".swarmforge/notify") "*.notify"))
              log (fs/path project ".swarmforge/daemon/handoffd.log")]
          (is (seq notes) (when (fs/exists? log) (slurp (str log))))
          (let [text (slurp (str (first notes)))
                pane (:out (run {:dir forge}
                                "tmux" "-S" lt-sock "capture-pane" "-p" "-t" "swarmforge-lieutenant"))]
            (is (= 1 (count notes)))
            (is (str/includes? (fs/file-name (first notes)) "specifier-handoff"))
            (is (str/includes? text "project: cave\n"))
            (is (str/includes? text "event: specifier-handoff\n"))
            (is (str/includes? text "task: HTW\n"))
            (is (str/includes? pane "Notify: specifier-handoff"))
            (is (= "specifier" (task-lane project "HTW")))))
        (finally
          (stop-tmux! sock)
          (stop-tmux! lt-sock))))))
(deftest handoffd-writes-forge-notify-on-card-done
  (let [forge (tmp-dir)
        project (fs/path forge "projects" "cave")
        roles ["coder" "cleaner"]
        lt-sock (tmp-tmux-socket)]
    (fs/create-dirs project)
    (setup-pack! project roles)
    (create-task project "HTW" "cleaner")
    (queue-handoff! project {:from "cleaner" :to "coder" :task "HTW"})
    (write-file (fs/path forge ".swarmforge/tmux-socket") (str lt-sock "\n"))
    (run {:dir forge} "tmux" "-S" lt-sock "new-session" "-d" "-s" "swarmforge-lieutenant" "sleep" "120")
    (let [sock (start-tmux! project roles (tmp-tmux-socket))]
      (try
        (handoffd-once project)
        (let [notes (vec (fs/glob (fs/path forge ".swarmforge/notify") "*.notify"))
              log (fs/path project ".swarmforge/daemon/handoffd.log")]
          (is (seq notes) (when (fs/exists? log) (slurp (str log))))
          (let [text (slurp (str (first notes)))
                pane (:out (run {:dir forge}
                                "tmux" "-S" lt-sock "capture-pane" "-p" "-t" "swarmforge-lieutenant"))]
            (is (= 1 (count notes)))
            (is (str/includes? (fs/file-name (first notes)) "card-done"))
            (is (str/includes? text "event: card-done\n"))
            (is (str/includes? pane "Notify: card-done"))
            (is (= "done" (task-lane project "HTW")))))
        (finally
          (stop-tmux! sock)
          (stop-tmux! lt-sock))))))
(deftest handoffd-refactorer-back-one-does-not-done-or-hold
  ;; Given four-pack, card in refactorer, reverse copy to coder and forward to architect
  ;; When handoffd delivers
  ;; Then coder gets the 00 reverse file, lane is architect, Attention is empty, card is not Done
  (let [root (tmp-dir)
        roles four-pack-roles
        sock (do (setup-pack! root roles {"refactorer" "back-one" "architect" "back-all"})
                 (create-task root "HTW" "refactorer")
                 (write-file
                  (fs/path (pack-worktree root roles "coder")
                           ".swarmforge/handoffs/inbox/new/50_next_card.handoff")
                  (str "from: specifier\nto: coder\npriority: 50\ntype: note\n"
                       "message: next card\n\nnote\n"))
                 (queue-handoff! root {:from "refactorer" :to "architect" :task "HTW"
                                       :priority "50"})
                 (queue-handoff! root {:from "refactorer" :to "coder" :task "HTW"
                                       :priority "00" :non-forwarding true
                                       :body reverse-structure-body})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (let [coder-mail (sort (inbox-names root roles "coder"))
            delivered (slurp (str (fs/path (pack-worktree root roles "coder")
                                           ".swarmforge/handoffs/inbox/new"
                                           (first coder-mail))))]
        (is (str/starts-with? (first coder-mail) "00_"))
        (is (str/starts-with? (second coder-mail) "50_"))
        (is (str/includes? delivered "merge_and_process.sh refactorer"))
        (is (str/includes? delivered "inbound tree is the structure"))
        (is (str/includes? delivered "non-forwarding: true")))
      (is (seq (inbox-names root roles "architect")))
      (is (= [] (pending-names root)))
      (is (= "architect" (task-lane root "HTW")))
      (is (not= "done" (task-lane root "HTW")))
      (finally
        (stop-tmux! sock)))))
(deftest handoffd-six-pack-hardender-dones-because-last-on-component
  (let [root (tmp-dir)
        roles six-pack-roles
        sock (do (setup-pack! root roles {"cleaner" "back-one"
                                          "architect" "back-all"
                                          "QA" "back-all"})
                 (create-task root "HTW" "hardender")
                 (queue-handoff! root {:from "hardender" :to "specifier" :task "HTW"
                                       :priority "50" :non-forwarding true})
                 (doseq [role ["specifier" "coder" "cleaner" "architect"]]
                   (queue-handoff! root {:from "hardender" :to role :task "HTW"
                                         :priority "00" :non-forwarding true
                                         :body reverse-structure-body}))
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (doseq [role ["specifier" "coder" "cleaner" "architect"]]
        (is (seq (inbox-names root roles role)) role))
      (is (= "done" (task-lane root "HTW")))
      (finally
        (stop-tmux! sock)))))
(deftest handoffd-six-pack-architect-back-all-moves-to-hardender
  (let [root (tmp-dir)
        roles six-pack-roles
        sock (do (setup-pack! root roles {"cleaner" "back-one"
                                          "architect" "back-all"
                                          "QA" "back-all"})
                 (create-task root "HTW" "architect")
                 (queue-handoff! root {:from "architect" :to "hardender" :task "HTW"
                                       :priority "50"})
                 (doseq [role ["specifier" "coder" "cleaner"]]
                   (queue-handoff! root {:from "architect" :to role :task "HTW"
                                         :priority "00" :non-forwarding true}))
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (doseq [role ["specifier" "coder" "cleaner"]]
        (is (seq (inbox-names root roles role)) role))
      (is (seq (inbox-names root roles "hardender")))
      (is (= [] (inbox-names root roles "QA")))
      (is (= "hardender" (task-lane root "HTW")))
      (is (not= "done" (task-lane root "HTW")))
      (finally
        (stop-tmux! sock)))))
(deftest handoffd-six-pack-qa-back-all-dones-because-last
  (let [root (tmp-dir)
        roles six-pack-roles
        sock (do (setup-pack! root roles {"cleaner" "back-one"
                                          "architect" "back-all"
                                          "QA" "back-all"})
                 (create-task root "HTW" "QA")
                 (queue-handoff! root {:from "QA" :to "specifier" :task "HTW"
                                       :priority "50" :non-forwarding true})
                 (doseq [role ["specifier" "coder" "cleaner" "architect" "hardender"]]
                   (queue-handoff! root {:from "QA" :to role :task "HTW"
                                         :priority "00" :non-forwarding true}))
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (doseq [role ["specifier" "coder" "cleaner" "architect" "hardender"]]
        (is (seq (inbox-names root roles role)) role))
      (is (= "done" (task-lane root "HTW")))
      (finally
        (stop-tmux! sock)))))
(deftest handoffd-two-pack-cleaner-back-one-dones-because-last
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        sock (do (setup-pack! root roles {"cleaner" "back-one"})
                 (create-task root "HTW" "cleaner")
                 (queue-handoff! root {:from "cleaner" :to "coder" :task "HTW"
                                       :priority "50" :non-forwarding true})
                 (queue-handoff! root {:from "cleaner" :to "coder" :task "HTW"
                                       :priority "00" :non-forwarding true})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (seq (inbox-names root roles "coder")))
      (is (= "done" (task-lane root "HTW")))
      (finally
        (stop-tmux! sock)))))
(deftest handoffd-delivers-new-task-note-without-moving-the-card
  ;; Given a waiting New Task card
  ;; When handoffd --once
  ;; Then no start note is delivered; the card stays in waiting
  (let [root (tmp-dir)
        roles six-pack-roles
        sock (do (setup-pack! root roles)
                 (pack-web root true "--test-post-task" (str root) "HTW" "Print hello")
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "waiting" (task-lane root "HTW")))
      (is (= [] (pending-names root)))
      (is (empty? (inbox-names root roles "specifier")))
      (finally
        (stop-tmux! sock)))))
(deftest specifier-git-handoff-waits-for-attention
  ;; Given six-pack-shaped roles + card in specifier
  ;; When specifier→coder is queued and handoffd --once
  ;; Then file is in pending_approval, coder inbox empty, pack_web --test-state approvals has the task
  (let [root (tmp-dir)
        artifacts "features/console.feature,qa/console.md"
        sock (do (setup-pack! root six-pack-roles)
                 (create-task root "htw-console-app" "specifier")
                 (increment-audit! root (:id (task-card root "htw-console-app")))
                 (queue-handoff! root {:from "specifier" :to "coder" :task "htw-console-app"
                                       :artifacts artifacts})
                 (start-tmux! root six-pack-roles))]
    (try
      (handoffd-once root)
      (let [state (web-state root)]
        (is (= ["50_from_specifier_to_coder.handoff"] (pending-names root)))
        (is (= [] (inbox-names root six-pack-roles "coder")))
        (is (= "specifier" (task-lane root "htw-console-app")))
        (is (= 1 (:audit_count (first (:tasks state)))))
        (is (= [{:id "50_from_specifier_to_coder"
                 :gate "spec → coder"
                 :task_id "htw-console-app"
                 :task "htw-console-app"
                 :artifacts []
                 :reviews {}}]
               (:approvals state))))
      (finally
        (stop-tmux! sock)))))
(deftest two-pack-git-handoff-does-not-wait
  ;; Given coder master, cleaner next, no specifier
  ;; When coder→cleaner queued + --once
  ;; Then delivered to cleaner; approvals empty
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        sock (do (setup-pack! root roles)
                 (create-task root "htw-console-app" "coder")
                 (queue-handoff! root {:from "coder" :to "cleaner" :task "htw-console-app"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (seq (inbox-names root roles "cleaner")))
      (is (= [] (pending-names root)))
      (is (= "cleaner" (task-lane root "htw-console-app")))
      (is (= [] (:approvals (web-state root))))
      (finally
        (stop-tmux! sock)))))
(deftest two-pack-end-broadcast-marks-the-card-done
  ;; Given two-pack, card in cleaner
  ;; When cleaner queues git_handoff to coder (every other role)
  ;; Then the card is done and coder inbox has the file
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        sock (do (setup-pack! root roles)
                 (create-task root "htw-console-app" "cleaner")
                 (queue-handoff! root {:from "cleaner" :to "coder" :task "htw-console-app"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "htw-console-app")))
      (is (seq (inbox-names root roles "coder")))
      (is (= [] (pending-names root)))
      (finally
        (stop-tmux! sock)))))
(deftest component-hardender-end-broadcast-marks-the-card-done
  ;; Given six-pack, component card in hardender
  ;; When hardender queues git_handoff to every other role
  ;; Then the card is done
  (let [root (tmp-dir)
        roles six-pack-roles
        sock (do (setup-pack! root roles)
                 (create-task root "htw-console-app" "hardender")
                 (queue-handoff! root {:from "hardender"
                                       :to "specifier,coder,cleaner,architect,QA"
                                       :task "htw-console-app"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "htw-console-app")))
      (is (seq (inbox-names root roles "specifier")))
      (is (seq (inbox-names root roles "coder")))
      (is (seq (inbox-names root roles "cleaner")))
      (is (= [] (pending-names root)))
      (finally
        (stop-tmux! sock)))))
(deftest component-hardender-last-role-git-handoff-is-done
  ;; Given six-pack, component card in hardender
  ;; When hardender queues git_handoff to specifier,coder (not every other role)
  ;; Then the card is done because hardender is last on this card
  (let [root (tmp-dir)
        roles six-pack-roles
        sock (do (setup-pack! root roles)
                 (create-task root "htw-console-app" "hardender")
                 (queue-handoff! root {:from "hardender"
                                       :to "specifier,coder"
                                       :task "htw-console-app"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "htw-console-app")))
      (finally
        (stop-tmux! sock)))))
(deftest component-hardender-one-recipient-non-forwarding-is-done
  ;; Given six-pack, component card in hardender
  ;; When hardender queues a non-forwarding git_handoff to specifier only
  ;; Then the card is done, not moved to specifier
  (let [root (tmp-dir)
        roles six-pack-roles
        sock (do (setup-pack! root roles)
                 (create-task root "HTW" "hardender")
                 (queue-handoff! root {:from "hardender"
                                       :to "specifier"
                                       :task "HTW"
                                       :non-forwarding true})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "HTW")))
      (is (seq (inbox-names root roles "specifier")))
      (finally
        (stop-tmux! sock)))))
(deftest terminal-handoff-dones-only-the-named-card
  ;; Given two-pack, Command syntax and validation in cleaner, those names in a
  ;; completed cleaner batch, HTW still in cleaner
  ;; When cleaner queues a terminal git_handoff named HTW
  ;; Then only HTW is done
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        batch (fs/path (pack-worktree root roles "cleaner")
                       ".swarmforge/handoffs/inbox/completed"
                       "batch_20260824T150500Z_000001")
        sock (do (setup-pack! root roles)
                 (create-task root "HTW" "cleaner")
                 (create-task root "Command syntax" "cleaner")
                 (create-task root "validation" "cleaner")
                 (write-file (fs/path batch "50_command.handoff")
                             "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: Command syntax\n\npayload\n")
                 (write-file (fs/path batch "50_validation.handoff")
                             "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: validation\n\npayload\n")
                 (queue-handoff! root {:from "cleaner" :to "coder" :task "HTW"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "HTW")))
      (is (= "cleaner" (task-lane root "Command syntax")))
      (is (= "cleaner" (task-lane root "validation")))
      (finally
        (stop-tmux! sock)))))
(deftest terminal-handoff-leaves-unfinished-lane-cards
  ;; Given two-pack, HTW finished in a completed batch, Command syntax only in the lane
  ;; When cleaner terminals with task HTW
  ;; Then HTW is done and Command syntax stays in cleaner
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        done (fs/path (pack-worktree root roles "cleaner")
                      ".swarmforge/handoffs/inbox/completed")
        sock (do (setup-pack! root roles)
                 (create-task root "HTW" "cleaner")
                 (create-task root "Command syntax" "cleaner")
                 (write-file (fs/path done "50_htw.handoff")
                             "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: HTW\n\npayload\n")
                 (queue-handoff! root {:from "cleaner" :to "coder" :task "HTW"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "HTW")))
      (is (= "cleaner" (task-lane root "Command syntax")))
      (finally
        (stop-tmux! sock)))))
(deftest terminal-handoff-dones-every-named-batch-card
  ;; Given two-pack, one liners/validate/HHG in an in-process cleaner batch,
  ;; and Command syntax in cleaner but not in that batch
  ;; When cleaner terminals with task one liners before done_with_current
  ;; Then every named batch card is done and Command syntax is untouched
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        batch (fs/path (in-process-dir root roles "cleaner")
                       "batch_20260824T202830Z_000001")
        sock (do (setup-pack! root roles)
                 (create-task root "one liners" "cleaner")
                 (create-task root "validate" "cleaner")
                 (create-task root "Holy Hand Grenade" "cleaner")
                 (create-task root "Command syntax" "cleaner")
                 (write-file (fs/path batch "50_oneliners.handoff")
                             "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: one liners\n\npayload\n")
                 (write-file (fs/path batch "50_validate.handoff")
                             "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: validate\n\npayload\n")
                 (write-file (fs/path batch "50_hhg.handoff")
                             "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: Holy Hand Grenade\n\npayload\n")
                 (let [ids (mapv #(:id (task-card root %))
                                 ["one liners" "validate" "Holy Hand Grenade"])]
                   (queue-handoff! root {:from "cleaner" :to "coder"
                                         :task "one liners"
                                         :task-id (first ids)
                                         :batch-task-ids ids}))
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "one liners")))
      (is (= "done" (task-lane root "validate")))
      (is (= "done" (task-lane root "Holy Hand Grenade")))
      (is (= "cleaner" (task-lane root "Command syntax")))
      (finally
        (stop-tmux! sock)))))

(deftest handoffd-moves-every-batch-card-and-leaves-unrelated-cards
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        sock (do (setup-pack! root roles)
                 (create-task root "pits" "coder")
                 (create-task root "bats" "coder")
                 (create-task root "unrelated" "coder")
                 (let [ids (mapv #(:id (task-card root %)) ["pits" "bats"])]
                   (queue-handoff! root {:from "coder" :to "cleaner"
                                         :task "pits"
                                         :task-id (first ids)
                                         :batch-task-ids ids}))
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "cleaner" (task-lane root "pits")))
      (is (= "cleaner" (task-lane root "bats")))
      (is (= "coder" (task-lane root "unrelated")))
      (finally
        (stop-tmux! sock)))))
(deftest six-pack-qa-broadcast-marks-the-card-done
  ;; Given six-pack, card in QA
  ;; When QA queues git_handoff to every other role
  ;; Then the card is done
  (let [root (tmp-dir)
        others "specifier,coder,cleaner,architect,hardender"
        sock (do (setup-pack! root six-pack-roles)
                 (create-task root "htw-console-app" "QA")
                 (queue-handoff! root {:from "QA" :to others :task "htw-console-app"})
                 (start-tmux! root six-pack-roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "htw-console-app")))
      (is (seq (inbox-names root six-pack-roles "specifier")))
      (is (seq (inbox-names root six-pack-roles "hardender")))
      (is (= [] (pending-names root)))
      (finally
        (stop-tmux! sock)))))
(deftest attention-approve-delivers-the-handoff
  ;; Given pending approval
  ;; When pack_web --test-approve <root> <id>
  ;; Then coder inbox has the file, card lane coder (handoffd --once after approve)
  (let [root (tmp-dir)
        sock (do (setup-pack! root six-pack-roles)
                 (create-task root "htw-console-app" "specifier")
                 (increment-audit! root (:id (task-card root "htw-console-app")))
                 (queue-handoff! root {:from "specifier" :to "coder" :task "htw-console-app"
                                       :artifacts "features/console.feature"})
                 (start-tmux! root six-pack-roles))]
    (try
      (handoffd-once root)
      (let [id (:id (first (:approvals (web-state root))))]
        (pack-web root true "--test-approve" (str root) id)
        (handoffd-once root)
        (is (seq (inbox-names root six-pack-roles "coder")))
        (is (= "coder" (task-lane root "htw-console-app")))
        (is (= 1 (:audit_count (task-card root "htw-console-app"))))
        (is (= [] (pending-names root)))
        (is (= [] (:approvals (web-state root)))))
      (finally
        (stop-tmux! sock)))))
(deftest attention-reject-returns-to-master
  ;; Given pending
  ;; When --test-reject
  ;; Then the pending approval is unchanged because Reject only opens the dialog
  (let [root (tmp-dir)
        sock (do (setup-pack! root six-pack-roles)
                 (create-task root "htw-console-app" "specifier")
                 (increment-audit! root (:id (task-card root "htw-console-app")))
                 (queue-handoff! root {:from "specifier" :to "coder" :task "htw-console-app"})
                 (start-tmux! root six-pack-roles))]
    (try
      (handoffd-once root)
      (let [id (:id (first (:approvals (web-state root))))
            result (pack-web root false "--test-reject" (str root) id)]
        (is (not (zero? (:exit result))))
        (is (seq (pending-names root)))
        (is (= "specifier" (task-lane root "htw-console-app")))
        (is (= 1 (:audit_count (task-card root "htw-console-app"))))
        (is (seq (:approvals (web-state root))))
        (is (not (fs/exists? (fs/path root ".swarmforge/notify/reject-htw-console-app")))))
      (finally
        (stop-tmux! sock)))))
(deftest attention-reject-preserves-branch-and-rolls-back-head
  ;; Given a pending approval with task identity and a task base commit
  ;; When it is rejected
  ;; Then rejected work is preserved off the active branch and pending state is cleared
  (let [root (tmp-dir)]
    (run {:dir root} "git" "init" "-q")
    (run {:dir root} "git" "config" "user.email" "test@example.com")
    (run {:dir root} "git" "config" "user.name" "Test User")
    (setup-pack! root six-pack-roles)
    (write-file (fs/path root "story.md") "base\n")
    (run {:dir root} "git" "add" "story.md")
    (run {:dir root} "git" "commit" "-q" "-m" "Base")
    (create-task root "htw-console-app" "specifier")
    (let [task-id (:id (first (:tasks (web-state root))))
          base (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
      (write-pending-audit! root task-id)
      (write-pending-audit! root "unrelated-id")
      (write-file (fs/path root "story.md") "rejected\n")
      (run {:dir root} "git" "add" "story.md")
      (run {:dir root} "git" "commit" "-q" "-m" "Rejected work")
      (let [rejected (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
            pending (fs/path root ".swarmforge/handoffs/pending_approval/50_from_specifier_to_coder.handoff")]
        (write-file pending
                    (str "from: specifier\n"
                         "to: coder\n"
                         "priority: 50\n"
                         "type: git_handoff\n"
                         "task_id: " task-id "\n"
                         "task: htw-console-app\n"
                         "commit: " rejected "\n"
                         "task_base_commit: " base "\n"
                         "\n"
                         "payload\n"))
        (let [result (pack-web root false "--test-delete-approval" (str root) "50_from_specifier_to_coder")
              head (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
              branches (:out (run {:dir root} "git" "branch" "--format=%(refname:short)"))]
          (is (zero? (:exit result)))
          (is (= base head))
          (is (str/includes? branches (str "rejected/" task-id "/latest")))
          (is (str/includes? branches (str "rejected/" task-id "/1")))
          (is (not (fs/exists? pending)))
          (is (= #{"unrelated-id"} (pending-audit-task-ids root)))
          (is (fs/exists? (fs/path root ".swarmforge/rejected-tasks" task-id)))
          (is (nil? (task-lane root "htw-console-app"))))))))
(deftest inject-payload-formats-task-name-and-body
  ;; Given the New Task example name and body
  ;; When pack_web --test-inject-payload
  ;; Then it prints Task: name, a blank line, and the body
  (let [result (pack-web (tmp-dir) false "--test-inject-payload")]
    (is (zero? (:exit result)))
    (is (= (str example-task-payload "\n") (:out result)))))
(deftest inject-master-records-send-keys-argv
  ;; Given master session swarmforge-specifier in roles.tsv
  ;; When --test-inject-argv records the would-be tmux argv
  ;; Then the text is submitted as a turn to that pane
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))
        text "hello from operator"]
    (write-file
     (fs/path root ".swarmforge/roles.tsv")
     (str "specifier\tmaster\t" root "\tswarmforge-specifier\tSpecifier\tcodex\ttask\n"))
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (let [result (pack-web root false "--test-inject-argv" (str root) argv-file text)
          argv (read-argv argv-file)]
      (is (zero? (:exit result)))
      (is (= [text] (submitted-texts argv "swarmforge-specifier:Specifier.0"))))))
(deftest attention-reject-injects-a-message-to-master
  ;; Given a pending approval and a tmux argv stub
  ;; When retry with comments
  ;; Then master receives those comments and no New Task note is queued
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))]
    (setup-pack! root six-pack-roles)
    (create-task root "htw-console-app" "specifier")
    (let [task-id (:id (task-card root "htw-console-app"))]
      (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
      (write-file
       (fs/path root ".swarmforge/handoffs/pending_approval/50_from_specifier_to_coder.handoff")
       (str "from: specifier\n"
            "to: coder\n"
            "priority: 50\n"
            "type: git_handoff\n"
            "task_id: " task-id "\n"
            "task: htw-console-app\n"
            "\n"
            "payload\n"))
      (let [result (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                                 "--test-retry-task" (str root)
                                 "50_from_specifier_to_coder"
                                 "use an RNG")
            argv (read-argv argv-file)
            notes (fs/list-dir (fs/path root ".swarmforge/handoffs/outbox"))]
        (is (zero? (:exit result)))
        (is (= [] (pending-names root)))
        (is (not (fs/exists? (fs/path root ".swarmforge/notify/reject-htw-console-app"))))
        (is (some #(str/includes? % "use an RNG") (submitted-texts argv)))
        (is (empty? (filter #(str/includes? (fs/file-name %) "New_Task") notes)))))))
(deftest pack-agent-page-polls-live-pane
  ;; When serving the agent session window
  ;; Then it polls /api/agents/<role>/pane
  (let [result (pack-web (tmp-dir) false "--test-agent-page" "specifier")]
    (is (zero? (:exit result)))
    (is (str/includes? (:out result) "/api/agents/specifier/pane"))
    (is (not (str/includes? (:out result) "?project=")))
    (is (str/includes? (:out result) "setInterval(refresh"))
    (is (str/includes? (:out result) "toEndSoon"))
    (is (str/includes? (:out result) "stickBottom"))))
(deftest pack-agent-page-polls-project-pane
  ;; Given a forge project agent window
  ;; When serving the agent session page
  ;; Then it polls /api/agents/<role>/pane?project=<name>
  (let [result (pack-web (tmp-dir) false "--test-agent-page" "specifier" "HTW")]
    (is (zero? (:exit result)))
    (is (str/includes? (:out result) "/api/agents/specifier/pane?project=HTW"))))
(deftest handoffd-archives-sender-pane-when-task-moves
  ;; Given card and specifier→coder handoff (two-pack coder→cleaner to skip attention)
  ;; When delivered
  ;; Then .swarmforge/sessions/<from>/<task>/pane.txt exists
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        sock (do (setup-pack! root roles)
                 (create-task root "htw-console-app" "coder")
                 (queue-handoff! root {:from "coder" :to "cleaner" :task "htw-console-app"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root {"SWARMFORGE_PANE_STUB" "pane\n"})
      (let [pane (role-pane-path root "coder")]
        (is (fs/exists? pane))
        (is (= "pane\n" (slurp (str pane))))
        (is (not (fs/exists? (pane-path root "coder" "htw-console-app")))))
      (finally
        (stop-tmux! sock)))))
(deftest handoffd-moves-card-when-handoff-task-case-differs
  ;; Given card HTW in coder
  ;; When git_handoff coder→cleaner task htw is delivered
  ;; Then HTW is in cleaner
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        sock (do (setup-pack! root roles)
                 (create-task root "HTW" "coder")
                 (queue-handoff! root {:from "coder" :to "cleaner" :task "htw"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "cleaner" (task-lane root "HTW")))
      (finally
        (stop-tmux! sock)))))
(deftest handoffd-does-not-deliver-when-board-task-is-unknown
  ;; Given card HTW and a handoff for other-task
  ;; When delivered
  ;; Then coder inbox stays empty and HTW stays in specifier
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        sock (do (setup-pack! root roles)
                 (create-task root "HTW" "coder")
                 (queue-handoff! root {:from "coder" :to "cleaner" :task "other-task"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "coder" (task-lane root "HTW")))
      (is (= [] (inbox-names root roles "cleaner")))
      (finally
        (stop-tmux! sock)))))
(deftest pack-dashboard-request-accepts-an-already-answered-clarification
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        question (fs/path root "tmp" "question.txt")
        ack (fs/path root "tmp" "answer.txt")]
    (setup-pack! root ["QA"])
    (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "tmux.sock") "\n"))
    (write-file question "Does the bat drop to any of 20 rooms?\n")
    (write-file ack "ignored local ack\n")
    (let [created (run {:dir root :env {"SWARMFORGE_ROLE" "QA"}}
                       (script "pack_dashboard_request.sh")
                       "clarify" (str question))
          id (str/trim (:out created))]
      (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                    "--test-answer-clarification" (str root) id "Yes, 1 to 20.")
      (let [acked (run {:dir root :env {"SWARMFORGE_ROLE" "QA"}}
                       (script "pack_dashboard_request.sh")
                       "answer" id (str ack))
            done (first (:clarifications (web-state root)))
            pending-requests (fs/path root ".swarmforge/dashboard/requests/pending")
            done-file (first (fs/list-dir
                              (fs/path root ".swarmforge/dashboard/clarifications/done")))]
        (is (zero? (:exit acked)))
        (is (str/includes? (:out acked) (str "ANSWERED: " id)))
        (is (= "done" (:status done)))
        (is (= "Yes, 1 to 20." (:response done)))
        (is (str/includes? (slurp (str done-file)) "response: Yes, 1 to 20.\n"))
        (is (or (not (fs/directory? pending-requests))
                (empty? (fs/list-dir pending-requests)))))
      (let [unknown (run {:dir root :env {"SWARMFORGE_ROLE" "QA"} :ok? false}
                         (script "pack_dashboard_request.sh")
                         "answer" "clar-missing" (str ack))]
        (is (not (zero? (:exit unknown))))
        (is (str/includes? (str (:err unknown) (:out unknown))
                           "Unknown pending request"))))))
