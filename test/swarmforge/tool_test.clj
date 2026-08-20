(ns swarmforge.tool-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [squad-tool :as tool]
            [swarmforge.test-support :refer :all]))

(deftest squad-tool-registers-executables-in-shared-cache
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "fake-tool")
                  "#!/usr/bin/env sh\nprintf 'fake-tool\\n'\n")
      (run {:dir root} "chmod" "+x" "fake-tool")
      (let [init (run {:dir root} (script "squad_tool.sh") "init")
            register (run {:dir root}
                          (script "squad_tool.sh")
                          "register"
                          "fake-tool"
                          "github.com/example/fake-tool"
                          "abcdef1234"
                          "fake-tool")
            status (run {:dir root} (script "squad_tool.sh") "status" "fake-tool")
            all-status (run {:dir root} (script "squad_tool.sh") "status")
            require (run {:dir root}
                         (script "squad_tool.sh")
                         "require"
                         "fake-tool"
                         "github.com/example/fake-tool"
                         "abcdef1234")
            mismatch (run {:dir root :ok? false}
                          (script "squad_tool.sh")
                          "require"
                          "fake-tool"
                          "github.com/example/fake-tool"
                          "ffffffffff")
            missing (run {:dir root :ok? false}
                         (script "squad_tool.sh")
                         "require"
                         "missing-tool"
                         "github.com/example/missing-tool"
                         "abcdef1234")
            aps-mismatch (run {:dir root :ok? false}
                              (script "squad_tool.sh")
                              "require"
                              "gherkin-parser"
                              "github.com/unclebob/gherkin-parser"
                              "latest")
            cached-tool (fs/path root ".swarmforge/tools/bin/fake-tool")
            manifest (fs/path root ".swarmforge/tools/manifests/fake-tool.manifest")
            run-cached (run {:dir root} (str cached-tool))]
        (is (str/includes? (:out init) "TOOL_CACHE:"))
        (is (str/includes? (:out register) "STATE: registered"))
        (is (str/includes? (:out register) "SQUAD_TOOL: fake-tool"))
        (is (str/includes? (:out status) "STATE: registered"))
        (is (str/includes? (:out status) "SOURCE: github.com/example/fake-tool"))
        (is (str/includes? (:out status) "VERSION: abcdef1234"))
        (is (str/includes? (:out all-status) "TOOLS: fake-tool"))
        (is (str/includes? (:out require) "STATE: available"))
        (is (str/includes? (:out require) "EXECUTABLE:"))
        (is (= 4 (:exit mismatch)))
        (is (str/includes? (:err mismatch) "SQUAD_TOOL_MISMATCH: fake-tool"))
        (is (str/includes? (:err mismatch) "FIELD: version"))
        (is (= 3 (:exit missing)))
        (is (str/includes? (:err missing) "SQUAD_TOOL_MISSING: missing-tool"))
        (is (= 4 (:exit aps-mismatch)))
        (is (str/includes? (:err aps-mismatch) "SQUAD_TOOL_MISMATCH: gherkin-parser"))
        (is (str/includes? (:err aps-mismatch) "EXPECTED: github.com/unclebob/Acceptance-Pipeline-Specification"))
        (is (= "fake-tool" (str/trim (:out run-cached))))
        (is (str/includes? (slurp (str manifest)) "tool: fake-tool"))
        (is (fs/exists? (fs/path root ".swarmforge/tools/src")))
        (is (fs/exists? (fs/path root ".swarmforge/tools/cache")))
        (is (fs/exists? (fs/path root ".swarmforge/tools/locks"))))
      (finally
        (fs/delete-tree root)))))

(deftest squad-tool-ensure-installs-once-and-reuses-matching-cache
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (let [install (run {:dir root}
                         (script "squad_tool.sh")
                         "ensure"
                         "built-tool"
                         "github.com/example/built-tool"
                         "1111111111"
                         "--"
                         "sh"
                         "-c"
                         "printf '#!/usr/bin/env sh\nprintf \"built-tool\\\\n\"\n' > \"$SWARMFORGE_TOOL_TARGET\"")
            reuse (run {:dir root}
                       (script "squad_tool.sh")
                       "ensure"
                       "built-tool"
                       "github.com/example/built-tool"
                       "1111111111"
                       "--"
                       "sh"
                       "-c"
                       "exit 99")
            cached-tool (fs/path root ".swarmforge/tools/bin/built-tool")
            run-cached (run {:dir root} (str cached-tool))]
        (is (str/includes? (:out install) "STATE: installed"))
        (is (str/includes? (:out reuse) "STATE: available"))
        (is (= "built-tool" (str/trim (:out run-cached))))
        (is (str/includes? (slurp (str (fs/path root ".swarmforge/tools/manifests/built-tool.manifest")))
                           "source: github.com/example/built-tool")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-tool-materializes-worktree-local-executables
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "fake-tool")
                  "#!/usr/bin/env sh\nprintf 'fake-tool:%s\\n' \"$1\"\n")
      (run {:dir root} "chmod" "+x" "fake-tool")
      (let [worktree (fs/path root ".worktrees/agent-001")
            _ (fs/create-dirs worktree)
            _ (run {:dir root}
                   (script "squad_tool.sh")
                   "register"
                   "fake-tool"
                   "github.com/example/fake-tool"
                   "abcdef1234"
                   "fake-tool")
            materialize (run {:dir root}
                             (script "squad_tool.sh")
                             "materialize"
                             "fake-tool"
                             "github.com/example/fake-tool"
                             "abcdef1234"
                             (str worktree))
            local-tool (fs/path worktree ".swarmforge/tools/bin/fake-tool")
            local-manifest (fs/path worktree ".swarmforge/tools/manifests/fake-tool.manifest")
            run-local (run {:dir worktree} (str local-tool) "ok")
            require-with-worktree (run {:dir root
                                        :env {"SWARMFORGE_WORKTREE" (str worktree)}}
                                       (script "squad_tool.sh")
                                       "require"
                                       "fake-tool"
                                       "github.com/example/fake-tool"
                                       "abcdef1234")]
        (is (str/includes? (:out materialize) "STATE: materialized"))
        (is (str/includes? (:out materialize) "MODE:"))
        (is (fs/exists? local-tool))
        (is (fs/exists? local-manifest))
        (is (= #{"OWNER_READ" "OWNER_EXECUTE"
                 "GROUP_READ" "GROUP_EXECUTE"
                 "OTHERS_READ" "OTHERS_EXECUTE"}
               (set (map str (fs/posix-file-permissions local-tool)))))
        (is (= "fake-tool:ok" (str/trim (:out run-local))))
        (is (str/includes? (:out require-with-worktree) "STATE: available"))
        (is (str/includes? (:out require-with-worktree) "LOCAL_EXECUTABLE:"))
        (is (str/includes? (slurp (str local-manifest)) "cached_executable:"))
        (is (or (str/includes? (slurp (str local-manifest)) "mode: hardlink")
                (str/includes? (slurp (str local-manifest)) "mode: copy"))))
      (finally
        (fs/delete-tree root)))))

(deftest tool-table-installs-from-github-not-a-local-checkout
  ;; Given the canonical tool table
  ;; Then every tool source is GitHub and no install command points at a local working copy
  (let [table (edn/read-string
               (slurp (str (fs/path repo-root "swarmforge/tool-table.edn"))))]
    (doseq [[name spec] (:tools table)]
      (is (str/starts-with? (str (:source spec)) "github.com/")
          (str name " source must be a GitHub repo"))
      (is (not (re-find #"/Users/|/home/" (str (:install-command spec))))
          (str name " must not install from a local working copy"))
      (is (= "latest" (:version spec))
          (str name " version is latest from GitHub")))))

(deftest github-https-url-is-the-clone-source
  (is (= "https://github.com/unclebob/Acceptance-Pipeline-Specification.git"
         (tool/github-https-url "github.com/unclebob/Acceptance-Pipeline-Specification")))
  (is (nil? (tool/github-https-url "/Users/unclebob/projects/Acceptance-Pipeline-Specification"))))

(deftest latest-ensure-refreshes-instead-of-reusing-stale-cache
  ;; Given a tool already cached at version latest
  ;; When ensure latest runs again
  ;; Then the install command runs again (GitHub refresh), not a cache hit
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root
                       "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (let [install (run {:dir root}
                         (script "squad_tool.sh")
                         "ensure" "built-tool" "github.com/example/built-tool" "latest"
                         "--" "sh" "-c"
                         "printf '#!/usr/bin/env sh\nprintf \"v1\\\\n\"\n' > \"$SWARMFORGE_TOOL_TARGET\"")
            refresh (run {:dir root}
                         (script "squad_tool.sh")
                         "ensure" "built-tool" "github.com/example/built-tool" "latest"
                         "--" "sh" "-c"
                         "printf '#!/usr/bin/env sh\nprintf \"v2\\\\n\"\n' > \"$SWARMFORGE_TOOL_TARGET\"")
            cached (fs/path root ".swarmforge/tools/bin/built-tool")]
        (is (str/includes? (:out install) "STATE: installed"))
        (is (str/includes? (:out refresh) "STATE: installed"))
        (is (= "v2" (str/trim (:out (run {:dir root} (str cached)))))))
      (finally
        (fs/delete-tree root)))))

(deftest git-source-sync-clones-and-updates-from-remote
  ;; Given a GitHub-shaped git remote
  ;; When the tool source is synced at latest
  ;; Then the cache copy matches the remote HEAD, including a later update
  (let [upstream (tmp-dir)
        dest (tmp-dir)]
    (try
      (init-repo! upstream)
      (write-file (fs/path upstream "bb.edn") "{:tasks {ping {:task (println :ok)}}}\n")
      (write-file (fs/path upstream "marker") "one\n")
      (run {:dir upstream} "git" "add" "bb.edn" "marker")
      (run {:dir upstream} "git" "commit" "-q" "-m" "one")
      (fs/delete-tree dest)
      (tool/sync-git-source! dest (str upstream) "latest")
      (is (= "one\n" (slurp (str (fs/path dest "marker")))))
      (write-file (fs/path upstream "marker") "two\n")
      (run {:dir upstream} "git" "add" "marker")
      (run {:dir upstream} "git" "commit" "-q" "-m" "two")
      (tool/sync-git-source! dest (str upstream) "latest")
      (is (= "two\n" (slurp (str (fs/path dest "marker")))))
      (finally
        (fs/delete-tree upstream)
        (when (fs/exists? dest) (fs/delete-tree dest))))))
