(ns swarmforge.script-misc-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [swarmforge.script-test-support :refer :all]))

(deftest handoff-lib-parses-and-prints-handoff-files
  (let [root (tmp-dir)
        handoff-file (fs/path root "task.handoff")]
    (try
      (write-file handoff-file
                  (str "id: 1\n"
                       "from: coder\n"
                       "to: cleaner\n"
                       "priority: 10\n"
                       "type: git_handoff\n"
                       "task: task-alpha\n"
                       "\n"
                       "merge_and_process coder abcdef1234\n"))
      (let [header (run {:dir root} (script "handoff_lib.bb") "header-field" "task.handoff" "task")
            body (run {:dir root} (script "handoff_lib.bb") "body" "task.handoff")
            task (run {:dir root} (script "handoff_lib.bb") "print-task" "task.handoff")]
        (is (str/includes? (:out header) "task-alpha"))
        (is (str/includes? (:out body) "merge_and_process coder abcdef1234"))
        (is (str/includes? (:out task) "TASK: task.handoff"))
        (is (str/includes? (:out task) "FROM: coder"))
        (is (str/includes? (:out task) "TASK_NAME: task-alpha")))
      (finally
        (fs/delete-tree root)))))
(deftest handoff-lib-updates-headers-and-reads-role-state
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "coder\tmaster\t" root "\tsession\tCoder\tcodex\ttask\n"
                       "cleaner\tcleaner\t" root "/.worktrees/cleaner\tsession\tCleaner\tcodex\tbatch\n"))
      (write-file (fs/path root ".swarmforge/handoffs/inbox/new/item.handoff")
                  (str "id: 1\n"
                       "from: coder\n"
                       "to: cleaner\n"
                       "priority: 20\n"
                       "type: note\n"
                       "\n"
                       "payload\n"))
      (run {:dir root} (script "handoff_lib.bb") "role-known" "cleaner")
      (run {:dir root} (script "handoff_lib.bb") "set-header" ".swarmforge/handoffs/inbox/new/item.handoff" "dequeued_at" "2026-06-16T00:00:00Z")
      (let [mode (run {:dir root} (script "handoff_lib.bb") "role-receive-mode" "cleaner")
            worktree (run {:dir root} (script "handoff_lib.bb") "role-worktree-name" "cleaner")
            dequeued (run {:dir root} (script "handoff_lib.bb") "header-field" ".swarmforge/handoffs/inbox/new/item.handoff" "dequeued_at")
            seq-1 (run {:dir root} (script "handoff_lib.bb") "next-sequence")
            seq-2 (run {:dir root} (script "handoff_lib.bb") "next-sequence")]
        (is (str/includes? (:out mode) "batch"))
        (is (str/includes? (:out worktree) "cleaner"))
        (is (str/includes? (:out dequeued) "2026-06-16T00:00:00Z"))
        (is (str/includes? (:out seq-1) "000001"))
        (is (str/includes? (:out seq-2) "000002")))
      (finally
        (fs/delete-tree root)))))
(deftest handoff-lib-reads-role-propagation
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "coder\tmaster\t" root "\tsession\tCoder\tcodex\ttask\n"
                       "cleaner\tcleaner\t" root "\tsession\tCleaner\tcodex\tbatch\tback-one\n"
                       "architect\tarchitect\t" root "\tsession\tArchitect\tcodex\tbatch\tback-all\n"))
      (let [coder (run {:dir root} (script "handoff_lib.bb") "role-propagation" "coder")
            cleaner (run {:dir root} (script "handoff_lib.bb") "role-propagation" "cleaner")
            architect (run {:dir root} (script "handoff_lib.bb") "role-propagation" "architect")]
        (is (str/includes? (:out coder) "forward-only"))
        (is (str/includes? (:out cleaner) "back-one"))
        (is (str/includes? (:out architect) "back-all")))
      (finally
        (fs/delete-tree root)))))
(deftest swarm-tool-knows-constitution-tool-names
  ;; Given a pack project
  ;; When require runs for clj-mutate
  ;; Then it is a known tool (missing until ensure), not Unknown tool
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (let [missing (run {:dir root :ok? false}
                         (script "swarm_tool.sh") "require" "clj-mutate")
            help (run {:dir root :ok? false}
                      (script "swarm_tool.sh") "--help")]
        (is (not= 0 (:exit missing)))
        (is (str/includes? (:err missing) "MISSING: clj-mutate"))
        (is (not (str/includes? (:err missing) "Unknown tool")))
        (is (str/includes? (str (:err help) (:out help)) "clj-mutate"))
        (is (str/includes? (str (:err help) (:out help)) "crap4clj"))
        (is (str/includes? (str (:err help) (:out help)) "dry4clj"))
        (is (str/includes? (str (:err help) (:out help)) "cloverage"))
        (is (str/includes? (str (:err help) (:out help)) "speclj"))
        (is (str/includes? (str (:err help) (:out help)) "speclj-structure-check")))
      (finally
        (fs/delete-tree root)))))
(deftest swarm-tool-ensure-cloverage-invokes-cloverage
  ;; Given a pack project
  ;; When swarm_tool.sh ensure cloverage
  ;; Then the wrapper launches cloverage.coverage, not crap4clj
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (run {:dir root} (script "swarm_tool.sh") "ensure" "cloverage")
      (let [wrapper (slurp (str (fs/path root ".swarmforge/bin/cloverage")))]
        (is (str/includes? wrapper "cloverage.coverage"))
        (is (str/includes? wrapper "cloverage/cloverage"))
        (is (str/includes? wrapper "\"src\""))
        (is (str/includes? wrapper "\"spec\""))
        (is (str/includes? wrapper "\"test\""))
        (is (str/includes? wrapper "-s spec"))
        (is (str/includes? wrapper "-r speclj"))
        (is (str/includes? wrapper "speclj/speclj"))
        (is (not (str/includes? wrapper "crap4clj")))
        (is (zero? (:exit (run {:dir root} (script "swarm_tool.sh") "require" "cloverage"))))
        (is (zero? (:exit (run {:dir root} (script "swarm_tool.sh") "require" "Cloverage")))))
      (finally
        (fs/delete-tree root)))))
(deftest swarm-tool-ensure-speclj-uses-speclj-main
  ;; Given a pack project
  ;; When swarm_tool.sh ensure speclj
  ;; Then the wrapper runs speclj.main -c spec, not speclj.cli
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (run {:dir root} (script "swarm_tool.sh") "ensure" "speclj")
      (let [wrapper (slurp (str (fs/path root ".swarmforge/bin/speclj")))]
        (is (str/includes? wrapper "speclj.main"))
        (is (str/includes? wrapper "-c spec"))
        (is (str/includes? wrapper "3.13.0"))
        (is (not (str/includes? wrapper "speclj.cli"))))
      (finally
        (fs/delete-tree root)))))
(deftest swarm-tool-ensure-crap4clj-also-installs-cloverage
  ;; Given a pack project with local crap4clj source
  ;; When swarm_tool.sh ensure crap4clj
  ;; Then both crap4clj and cloverage wrappers are installed
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (write-file (fs/path root ".swarmforge/tools/crap4clj/bb.edn")
                  "{:tasks {crap4clj identity}}\n")
      (run {:dir root} (script "swarm_tool.sh") "ensure" "crap4clj")
      (is (fs/executable? (fs/path root ".swarmforge/bin/crap4clj")))
      (is (fs/executable? (fs/path root ".swarmforge/bin/cloverage")))
      (is (str/includes? (slurp (str (fs/path root ".swarmforge/bin/cloverage")))
                         "cloverage.coverage"))
      (finally
        (fs/delete-tree root)))))
(deftest swarm-tool-ensure-clj-mutate-also-installs-cloverage
  ;; Given a pack project with local clj-mutate source
  ;; When swarm_tool.sh ensure clj-mutate
  ;; Then both clj-mutate and cloverage wrappers are installed
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (write-file (fs/path root ".swarmforge/tools/clj-mutate/bb.edn")
                  "{:tasks {clj-mutate identity}}\n")
      (run {:dir root} (script "swarm_tool.sh") "ensure" "clj-mutate")
      (is (fs/executable? (fs/path root ".swarmforge/bin/clj-mutate")))
      (is (fs/executable? (fs/path root ".swarmforge/bin/cloverage")))
      (is (str/includes? (slurp (str (fs/path root ".swarmforge/bin/cloverage")))
                         "cloverage.coverage"))
      (finally
        (fs/delete-tree root)))))
(deftest swarm-tool-require-and-ensure-install-aps-wrappers
  ;; Given a project without APS tools
  ;; When require runs, it reports missing
  ;; When ensure runs against a local APS source, wrappers land in .swarmforge/bin
  (let [root (tmp-dir)
        aps (fs/path root "aps-src")]
    (try
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (write-file (fs/path aps "bb.edn") "{:tasks {gherkin-parser identity\n  gherkin-ir-dry-checker identity}}\n")
      (let [missing (run {:dir root :ok? false}
                         (script "swarm_tool.sh") "require" "gherkin-parser")]
        (is (not= 0 (:exit missing)))
        (is (str/includes? (:err missing) "MISSING: gherkin-parser")))
      (run {:dir root
            :env {"SWARMFORGE_TOOL_SRC" (str aps)
                  "PATH" (System/getenv "PATH")
                  "GIT_CONFIG_NOSYSTEM" "1"}}
           (script "swarm_tool.sh") "ensure" "gherkin-parser")
      (run {:dir root
            :env {"SWARMFORGE_TOOL_SRC" (str aps)
                  "PATH" (System/getenv "PATH")
                  "GIT_CONFIG_NOSYSTEM" "1"}}
           (script "swarm_tool.sh") "ensure" "ir-dry-checker")
      (let [parser (fs/path root ".swarmforge/bin/gherkin-parser")
            dry (fs/path root ".swarmforge/bin/ir-dry-checker")]
        (is (fs/executable? parser))
        (is (fs/executable? dry))
        (is (zero? (:exit (run {:dir root} (script "swarm_tool.sh") "require" "gherkin-parser"))))
        (is (zero? (:exit (run {:dir root} (script "swarm_tool.sh") "require" "ir-dry-checker")))))
      (finally
        (fs/delete-tree root)))))
(deftest commit-msg-hook-adds-missing-role-byline
  ;; Given a specifier commit whose message has no byline
  ;; When the commit-msg hook runs
  ;; Then it appends `By specifier.` and does not duplicate an existing byline
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (run {:dir root}
           (script "swarmforge.bb")
           "--test-install-hooks"
           (str root))
      (write-file (fs/path root "spec.md") "hunt\n")
      (run {:dir root} "git" "add" "spec.md")
      (run {:dir root :env {"SWARMFORGE_ROLE" "specifier"
                            "PATH" (System/getenv "PATH")
                            "GIT_CONFIG_NOSYSTEM" "1"}}
           "git" "commit" "-q" "-m" "Specify Hunt the Wumpus console app")
      (let [body (commit-body root)]
        (is (str/includes? body "Specify Hunt the Wumpus console app"))
        (is (str/includes? body "By specifier."))
        (is (= 1 (count (re-seq #"By specifier\." body)))))
      (write-file (fs/path root "spec.md") "hunt two\n")
      (run {:dir root} "git" "add" "spec.md")
      (run {:dir root :env {"SWARMFORGE_ROLE" "specifier"
                            "PATH" (System/getenv "PATH")
                            "GIT_CONFIG_NOSYSTEM" "1"}}
           "git" "commit" "-q" "-m" "Add a scenario\n\nBy specifier.")
      (is (= 1 (count (re-seq #"By specifier\." (commit-body root)))))
      (finally
        (fs/delete-tree root)))))
(deftest commit-msg-hook-infers-role-from-worktree
  ;; Given SWARMFORGE_ROLE is unset and roles.tsv maps this worktree to specifier
  ;; When a commit is made
  ;; Then the hook still adds `By specifier.`
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (run {:dir root}
           (script "swarmforge.bb")
           "--test-install-hooks"
           (str root))
      (write-file (fs/path root "spec.md") "hunt\n")
      (run {:dir root} "git" "add" "spec.md")
      (run {:dir root} "git" "commit" "-q" "-m" "Specify Hunt the Wumpus console app")
      (is (str/includes? (commit-body root) "By specifier."))
      (finally
        (fs/delete-tree root)))))
(deftest commit-msg-hook-composes-idempotently-and-restores-existing-hook
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (let [hook (fs/path root ".git/hooks/commit-msg")
            saved (fs/path root ".git/hooks/commit-msg.before-swarmforge")
            original (str "#!/bin/sh\n"
                          "grep -q 'By specifier\\.' \"$1\" || exit 42\n"
                          "printf 'called\\n' > .git/original-hook.called\n")]
        (write-file hook original)
        (run {:dir root} "chmod" "+x" (str hook))
        (run {:dir root} (script "swarmforge.bb") "--test-install-hooks" (str root))
        (run {:dir root} (script "swarmforge.bb") "--test-install-hooks" (str root))
        (is (= original (slurp (str saved))))
        (is (str/includes? (slurp (str hook)) "SWARMFORGE COMBINED COMMIT-MSG HOOK"))
        (write-file (fs/path root "work.txt") "work\n")
        (run {:dir root} "git" "add" "work.txt")
        (let [commit (run {:dir root :env {"SWARMFORGE_ROLE" "specifier"}}
                          "git" "commit" "-q" "-m" "Compose hooks")]
          (is (zero? (:exit commit))))
        (is (= "called\n" (slurp (str (fs/path root ".git/original-hook.called")))))
        (is (str/includes? (commit-body root) "By specifier."))
        (run {:dir root} (script "swarmforge.bb") "--remove-hooks" (str root))
        (is (= original (slurp (str hook))))
        (is (not (fs/exists? saved))))
      (finally
        (fs/delete-tree root)))))
(deftest commit-msg-hook-restores-an-existing-symbolic-link
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (let [hooks (fs/path root ".git/hooks")
            target (fs/path hooks "project-commit-msg")
            hook (fs/path hooks "commit-msg")
            saved (fs/path hooks "commit-msg.before-swarmforge")]
        (write-file target "#!/bin/sh\nexit 0\n")
        (run {:dir root} "chmod" "+x" (str target))
        (run {:dir hooks} "ln" "-s" "project-commit-msg" "commit-msg")
        (run {:dir root} (script "swarmforge.bb") "--test-install-hooks" (str root))
        (is (fs/sym-link? saved))
        (run {:dir root} (script "swarmforge.bb") "--remove-hooks" (str root))
        (is (fs/sym-link? hook))
        (is (not (fs/exists? saved))))
      (finally
        (fs/delete-tree root)))))
(deftest window-watchdog-rewrites-window-state-and-id-list
  (let [root (tmp-dir)
        state-file (fs/path root "windows.tsv")
        ids-file (fs/path root "window-ids")]
    (try
      (write-file state-file
                  (str "1\told-a\tswarmforge-coder\tSwarmForge Coder\n"
                       "2\told-b\tswarmforge-cleaner\tSwarmForge Cleaner\n"))
      (write-file ids-file "old-a\nold-b\n")
      (run {:dir root} (script "swarm_window_watchdog.bb") "--rewrite-window-id" "windows.tsv" "window-ids" "2" "new-b")
      (let [state (slurp (str state-file))
            ids (slurp (str ids-file))]
        (is (str/includes? state "1\told-a\tswarmforge-coder\tSwarmForge Coder"))
        (is (str/includes? state "2\tnew-b\tswarmforge-cleaner\tSwarmForge Cleaner"))
        (is (= "old-a\nnew-b\n" ids)))
      (finally
        (fs/delete-tree root)))))
(deftest swarm-cleanup-tolerates-missing-runtime-state
  (let [root (tmp-dir)
        ids-file (fs/path root ".swarmforge/window-ids")]
    (try
      (write-file ids-file "window-a\nwindow-b\n")
      (let [result (run {:dir root
                         :env {"SWARMFORGE_TERMINAL_BACKEND" "none"}}
                        (str (fs/path scripts-dir "swarm-cleanup.sh"))
                        "/tmp/nonexistent.sock"
                        (str ids-file))]
        (is (= 0 (:exit result)))
        (is (= "" (:err result))))
      (finally
        (fs/delete-tree root)))))
(deftest close-swarm-reports-when-no-swarm-state
  (let [root (tmp-dir)]
    (try
      (let [result (run {:dir root :ok? false
                         :env {"SWARMFORGE_TERMINAL_BACKEND" "none"}}
                        (close-swarm)
                        (str root))]
        (is (not= 0 (:exit result)))
        (is (str/includes? (str (:err result) (:out result)) "No SwarmForge swarm")))
      (finally
        (fs/delete-tree root)))))
(deftest close-swarm-kills-tmux-sessions-and-stops-daemon
  (let [root (tmp-dir)
        sock (tmp-tmux-socket)
        pid-file (fs/path root ".swarmforge/daemon/handoffd.pid")
        daemon (.start (java.lang.ProcessBuilder. ["sleep" "120"]))
        pid (str (.pid daemon))]
    (try
      (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
      (write-file (fs/path root ".swarmforge/sessions.tsv")
                  (str "1\tcoder\tswarmforge-coder\tCoder\tcodex\n"
                       "2\tcleaner\tswarmforge-cleaner\tCleaner\tcodex\n"))
      (write-file (fs/path root ".swarmforge/window-ids") "win-a\nwin-b\n")
      (write-file pid-file (str pid "\n"))
      (run {:dir root} "tmux" "-S" sock "new-session" "-d" "-s" "swarmforge-coder" "sleep" "120")
      (run {:dir root} "tmux" "-S" sock "new-session" "-d" "-s" "swarmforge-cleaner" "sleep" "120")
      (let [result (run {:dir root
                         :env {"SWARMFORGE_TERMINAL_BACKEND" "none"}}
                        (close-swarm)
                        (str root))]
        (is (= 0 (:exit result)))
        (is (not= 0 (:exit (run {:dir root :ok? false}
                                "tmux" "-S" sock "has-session" "-t" "swarmforge-coder"))))
        (is (not= 0 (:exit (run {:dir root :ok? false}
                                "tmux" "-S" sock "has-session" "-t" "swarmforge-cleaner"))))
        (is (not (fs/exists? pid-file)))
        (is (false? (.isAlive daemon))))
      (finally
        (when (.isAlive daemon)
          (.destroyForcibly daemon))
        (run {:dir root :ok? false} "tmux" "-S" sock "kill-server")
        (fs/delete-tree root)))))
(deftest clj-mutate-wrapper-is-differential-with-four-workers
  ;; Given an installed clj-mutate wrapper
  ;; When it is invoked with --mutate-all
  ;; Then --mutate-all is dropped and --max-workers 4 is used
  (let [root (tmp-dir)]
    (try
      (write-echo-tool! root "clj-mutate")
      (run {:dir root} (script "swarm_tool.sh") "ensure" "clj-mutate")
      (let [out (:out (run {:dir root}
                           (str (fs/path root ".swarmforge/bin/clj-mutate"))
                           "src/htw/game.clj" "--reuse-lcov" "--mutate-all"
                           "--test-command" "bb test"))]
        (is (str/includes? out "--max-workers 4"))
        (is (not (str/includes? out "--mutate-all"))))
      (finally
        (fs/delete-tree root)))))
(deftest clj-mutate-scan-does-not-inject-max-workers
  ;; Given an installed clj-mutate wrapper
  ;; When it is invoked with --scan
  ;; Then it does not add --max-workers
  (let [root (tmp-dir)]
    (try
      (write-echo-tool! root "clj-mutate")
      (run {:dir root} (script "swarm_tool.sh") "ensure" "clj-mutate")
      (let [out (:out (run {:dir root}
                           (str (fs/path root ".swarmforge/bin/clj-mutate"))
                           "src/htw/game.clj" "--scan"))]
        (is (not (str/includes? out "--max-workers"))))
      (finally
        (fs/delete-tree root)))))
(deftest gherkin-mutator-wrapper-is-differential-with-four-workers
  ;; Given an installed gherkin-mutator wrapper
  ;; When it is invoked with --level full
  ;; Then the level is hard and --workers 4 is used
  (let [root (tmp-dir)
        aps (fs/path root "aps-src")]
    (try
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (write-file (fs/path aps "bb.edn")
                  "{:tasks {gherkin-mutator (apply println *command-line-args*)}}\n")
      (run {:dir root :env {"SWARMFORGE_TOOL_SRC" (str aps)
                            "PATH" (System/getenv "PATH")
                            "GIT_CONFIG_NOSYSTEM" "1"}}
           (script "swarm_tool.sh") "ensure" "gherkin-mutator")
      (let [out (:out (run {:dir root}
                           (str (fs/path root ".swarmforge/bin/gherkin-mutator"))
                           "--feature" "features/a.feature" "--level" "full"
                           "--runner-worker" "true"))]
        (is (str/includes? out "--level hard"))
        (is (str/includes? out "--workers 4"))
        (is (not (str/includes? out "--level full"))))
      (finally
        (fs/delete-tree root)))))
(deftest constitution-tool-wrappers-do-not-use-a-lock-file
  ;; Given an installed constitution tool wrapper
  ;; When it is written
  ;; Then it does not take a project lock directory
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (write-file (fs/path root ".swarmforge/tools/crap4clj/bb.edn")
                  "{:tasks {crap4clj identity}}\n")
      (run {:dir root} (script "swarm_tool.sh") "ensure" "crap4clj")
      (let [wrapper (slurp (str (fs/path root ".swarmforge/bin/crap4clj")))]
        (is (not (str/includes? wrapper "constitution-tools.lock")))
        (is (not (str/includes? wrapper "SWARMFORGE_TOOL_HELD"))))
      (finally
        (fs/delete-tree root)))))
(deftest ready-for-next-treats-blank-receive-mode-as-task
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "sender\tmaster\t%s\tsession\tSender\tcodex\t\n" root))
      (doseq [dir [".swarmforge/handoffs/outbox/tmp"
                   ".swarmforge/handoffs/sent"
                   ".swarmforge/handoffs/failed"
                   ".swarmforge/handoffs/inbox/new"
                   ".swarmforge/handoffs/inbox/in_process"
                   ".swarmforge/handoffs/inbox/completed"]]
        (fs/create-dirs (fs/path root dir)))
      (let [mode (run {:dir root :env {"SWARMFORGE_ROLE" "sender"}}
                      (script "handoff_lib.bb") "role-receive-mode" "sender")
            ready (run {:dir root :env {"SWARMFORGE_ROLE" "sender"} :ok? false}
                       (script "ready_for_next.sh"))]
        (is (str/includes? (:out mode) "task"))
        (is (zero? (:exit ready)))
        (is (str/includes? (:out ready) "NO_TASK")))
      (write-file (fs/path root ".swarmforge/handoffs/inbox/in_process/50_item.handoff")
                  (str "id: 1\n"
                       "from: sender\n"
                       "to: sender\n"
                       "priority: 50\n"
                       "type: note\n"
                       "task: HTW\n"
                       "\n"
                       "body\n"))
      (let [done (run {:dir root :env {"SWARMFORGE_ROLE" "sender"} :ok? false}
                      (script "done_with_current.sh"))]
        (is (zero? (:exit done)))
        (is (str/includes? (:out done) "COMPLETED:"))
        (is (re-find #"MAIL_WAITING|NO_TASK" (:out done))))
      (finally
        (fs/delete-tree root)))))
(deftest ready-for-next-unknown-role-fails
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "sender\tmaster\t%s\tsession\tSender\tcodex\ttask\n" root))
      (let [ready (run {:dir root :env {"SWARMFORGE_ROLE" "ghost"} :ok? false}
                       (script "ready_for_next.sh"))
            done (run {:dir root :env {"SWARMFORGE_ROLE" "ghost"} :ok? false}
                      (script "done_with_current.sh"))]
        (is (not (zero? (:exit ready))))
        (is (str/includes? (str (:err ready) (:out ready)) "Unknown role"))
        (is (not (zero? (:exit done))))
        (is (str/includes? (str (:err done) (:out done)) "Unknown role")))
      (finally
        (fs/delete-tree root)))))
(deftest finish-done-logs-archive-throw-and-still-announces
  (let [root (tmp-dir)
        lib (fs/path root "handoff_lib.bb")]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "sender\tmaster\t%s\tsession\tSender\tcodex\ttask\n" root))
      (fs/create-dirs (fs/path root ".swarmforge/handoffs/inbox/new"))
      (fs/copy (script "handoff_lib.bb") lib)
      (let [result (run {:dir root :env {"SWARMFORGE_ROLE" "sender"} :ok? false}
                        "bb" (str lib) "finish-done")]
        (is (zero? (:exit result)))
        (is (re-find #"MAIL_WAITING|NO_TASK" (:out result)))
        (is (str/includes? (str (:err result)) "archive failed"))
        (is (str/includes? (str (:err result)) "sender"))
        (is (str/includes? (str (:err result)) (str root))))
      (finally
        (fs/delete-tree root)))))
(deftest pack-web-production-main-does-not-run-test-flags
  (let [via-bb (run {:dir repo-root :ok? false}
                    "bb" (script "pack_web.bb") "--test-html")
        via-sh (run {:dir repo-root :ok? false}
                    (script "pack_web.sh") "--test-html")]
    (is (not (zero? (:exit via-bb))))
    (is (zero? (:exit via-sh)))
    (is (str/includes? (:out via-sh) "<!doctype html"))))
