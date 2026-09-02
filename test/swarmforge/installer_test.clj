(ns swarmforge.installer-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [swarmforge.script-test-support :refer :all]))

(deftest get-swarm-forge-project-manager-installs-named-packs
  (let [host (tmp-dir)
        base (tmp-dir)
        packs (tmp-dir)]
    (try
      (write-file (fs/path host "README.md") "host-readme\n")
      (write-file (fs/path host "bb.edn") "{:paths [\"test\"]}\n")
      (write-file (fs/path host "test/keep.clj") "keep\n")
      (seed-installer-host! base)
      (doseq [pack-name ["two-pack" "four-pack" "six-pack"]]
        (seed-installer-pack! (fs/path packs pack-name)))
      (let [result (run {:dir host
                         :env {"SWARMFORGE_BASE_DIR" (str base)
                               "SWARMFORGE_PACKS_DIR" (str packs)}}
                        (str (fs/path repo-root "get-swarm-forge"))
                        "project-manager")]
        (is (zero? (:exit result)) (:err result))
        (is (str/includes? (:out result) "from project-manager"))
        (assert-installer-host host)
        (is (= "PACK-PROJECT\n" (slurp (str (fs/path host "packs/two-pack/swarmforge/constitution/articles/project.prompt")))))
        (is (= "PACK-LOCAL-WORKFLOW\n" (slurp (str (fs/path host "packs/four-pack/swarmforge/constitution/articles/local-workflow.prompt")))))
        (is (fs/exists? (fs/path host "packs/six-pack/swarmforge/swarmforge.conf")))
        (is (not (fs/exists? (fs/path host ".swarmforge/project-pack/swarmforge/swarmforge.conf")))))
      (finally
        (fs/delete-tree host)
        (fs/delete-tree base)
        (fs/delete-tree packs)))))
(deftest get-swarm-forge-six-pack-composes-into-dot
  (let [host (tmp-dir)
        base (tmp-dir)
        packs (tmp-dir)]
    (try
      (write-file (fs/path host "README.md") "host-readme\n")
      (write-file (fs/path host "bb.edn") "{:paths [\"test\"]}\n")
      (write-file (fs/path host "test/keep.clj") "keep\n")
      (seed-installer-host! base)
      (seed-installer-pack! (fs/path packs "six-pack"))
      (let [result (run {:dir host
                         :env {"SWARMFORGE_BASE_DIR" (str base)
                               "SWARMFORGE_PACKS_DIR" (str packs)
                               "SWARMFORGE_BASE_BRANCH" "main"}}
                        (str (fs/path repo-root "get-swarm-forge"))
                        "six-pack")]
        (is (zero? (:exit result)) (:err result))
        (is (str/includes? (:out result) "pack installed from six-pack"))
        (is (= "host-readme\n" (slurp (str (fs/path host "README.md")))))
        (is (= "{:paths [\"test\"]}\n" (slurp (str (fs/path host "bb.edn")))))
        (is (= "keep\n" (slurp (str (fs/path host "test/keep.clj")))))
        (is (= "MAIN-ENGINEERING\n" (slurp (str (fs/path host "swarmforge/constitution/articles/engineering.prompt")))))
        (is (= "PACK-CONSTITUTION\n" (slurp (str (fs/path host "swarmforge/constitution.prompt")))))
        (is (= "PACK-PROJECT\n" (slurp (str (fs/path host "swarmforge/constitution/articles/project.prompt")))))
        (is (= "#!/bin/sh\necho pack-swarm\n" (slurp (str (fs/path host "swarm")))))
        (is (str/includes? (slurp (str (fs/path host "swarmforge/swarmforge.conf"))) "window specifier"))
        (is (fs/exists? (fs/path host "swarmforge/roles/specifier.prompt")))
        (is (not (fs/exists? (fs/path host "swarmforge/roles/lieutenant.prompt"))))
        (is (not (fs/directory? (fs/path host "projects"))))
        (is (not (fs/exists? (fs/path host "packs"))))
        (is (not (fs/exists? (fs/path host ".swarmforge/project-pack/swarmforge/swarmforge.conf")))))
      (finally
        (fs/delete-tree host)
        (fs/delete-tree base)
        (fs/delete-tree packs)))))
(deftest get-swarm-forge-pack-fetch-does-not-need-packs-dir-when-base-dir-is-set
  (let [host (tmp-dir)
        base (tmp-dir)
        git-dir (tmp-dir)]
    (try
      (write-file (fs/path host "README.md") "host-readme\n")
      (seed-installer-host! base)
      (init-repo! git-dir)
      (seed-installer-pack! git-dir)
      (run {:dir git-dir} "git" "checkout" "-q" "-B" "six-pack")
      (run {:dir git-dir} "git" "add" "-A")
      (run {:dir git-dir} "git" "commit" "-q" "-m" "pack")
      (let [result (run {:dir host
                         :env {"SWARMFORGE_BASE_DIR" (str base)
                               "SWARMFORGE_REPO_URL" "https://example.invalid/swarm-forge"
                               "SWARMFORGE_GIT_DIR" (str git-dir)}}
                        (str (fs/path repo-root "get-swarm-forge"))
                        "six-pack")]
        (is (zero? (:exit result)) (:err result))
        (is (= "PACK-CONSTITUTION\n" (slurp (str (fs/path host "swarmforge/constitution.prompt")))))
        (is (fs/exists? (fs/path host "swarmforge/roles/specifier.prompt")))
        (is (= "MAIN-ENGINEERING\n" (slurp (str (fs/path host "swarmforge/constitution/articles/engineering.prompt")))))
        (is (not (fs/exists? (fs/path host "swarmforge/roles/lieutenant.prompt")))))
      (finally
        (fs/delete-tree host)
        (fs/delete-tree base)
        (fs/delete-tree git-dir)))))
(deftest get-swarm-forge-project-manager-fails-when-that-branch-is-missing
  (let [host (tmp-dir)
        git-dir (tmp-dir)]
    (try
      (init-repo! git-dir)
      (seed-installer-host! git-dir)
      (run {:dir git-dir} "git" "checkout" "-q" "-B" "main")
      (run {:dir git-dir} "git" "add" "-A")
      (run {:dir git-dir} "git" "commit" "-q" "-m" "main host")
      (let [result (run {:dir host :ok? false
                         :env {"SWARMFORGE_REPO_URL" "https://example.invalid/swarm-forge"
                               "SWARMFORGE_GIT_DIR" (str git-dir)}}
                        (str (fs/path repo-root "get-swarm-forge"))
                        "project-manager")]
        (is (pos? (:exit result)))
        (is (str/includes? (:err result) "branch 'project-manager' was not found")))
      (finally
        (fs/delete-tree host)
        (fs/delete-tree git-dir)))))
(deftest get-swarm-forge-lieutenant-installs-project-pack
  (let [host (tmp-dir)
        base (tmp-dir)]
    (try
      (write-file (fs/path host "README.md") "host-readme\n")
      (write-file (fs/path host "bb.edn") "{:paths [\"test\"]}\n")
      (write-file (fs/path host "test/keep.clj") "keep\n")
      (seed-installer-host! base)
      (seed-installer-pack! (fs/path base ".swarmforge/project-pack"))
      (let [result (run {:dir host
                         :env {"SWARMFORGE_BASE_DIR" (str base)}}
                        (str (fs/path repo-root "get-swarm-forge"))
                        "lieutenant")]
        (is (zero? (:exit result)) (:err result))
        (is (str/includes? (:out result) "from lieutenant"))
        (assert-installer-host host)
        (is (not (fs/exists? (fs/path host "packs"))))
        (is (= "PACK-PROJECT\n" (slurp (str (fs/path host ".swarmforge/project-pack/swarmforge/constitution/articles/project.prompt")))))
        (is (= "PACK-LOCAL-WORKFLOW\n" (slurp (str (fs/path host ".swarmforge/project-pack/swarmforge/constitution/articles/local-workflow.prompt")))))
        (is (fs/exists? (fs/path host ".swarmforge/project-pack/swarmforge/swarmforge.conf")))
        (is (not (fs/exists? (fs/path host ".swarmforge/project-pack/swarmforge/constitution/articles/engineering.prompt")))))
      (finally
        (fs/delete-tree host)
        (fs/delete-tree base)))))
(deftest get-swarm-forge-help-names-the-products
  (let [result (run {:dir repo-root :ok? false}
                    (str (fs/path repo-root "get-swarm-forge")) "-h")]
    (is (zero? (:exit result)) (:err result))
    (is (str/includes? (:err result) "two-pack"))
    (is (str/includes? (:err result) "project-manager"))
    (is (str/includes? (:err result) "lieutenant"))
    (is (str/includes? (:err result) "compose into the current directory"))
    (is (str/includes? (:err result) "forges"))))
(deftest get-swarm-forge-requires-a-product
  (let [result (run {:dir repo-root :ok? false}
                    (str (fs/path repo-root "get-swarm-forge")))]
    (is (pos? (:exit result)))
    (is (str/includes? (:err result) "usage: get-swarm-forge")))
  (doseq [name ["main" "master" "sf-local-only"]]
    (let [result (run {:dir repo-root :ok? false
                       :env {"SWARMFORGE_BASE_BRANCH" "main"}}
                      (str (fs/path repo-root "get-swarm-forge"))
                      name)]
      (is (pos? (:exit result)) name)
      (is (str/includes? (:err result) "unknown product")))))
(deftest get-swarm-forge-missing-branch-names-the-url
  (let [host (tmp-dir)
        result (run {:dir host :ok? false
                     :env {"SWARMFORGE_REPO_URL" "https://example.invalid/swarm-forge"
                           "SWARMFORGE_GIT_DIR" ""}}
                    (str (fs/path repo-root "get-swarm-forge"))
                    "lieutenant")]
    (is (pos? (:exit result)))
    (is (str/includes? (:err result) "branch 'lieutenant' was not found"))
    (is (str/includes? (:err result) "SWARMFORGE_GIT_DIR"))))
(deftest get-swarm-forge-falls-back-to-local-git-for-named-product
  (let [host (tmp-dir)
        git-dir (tmp-dir)]
    (try
      (write-file (fs/path host "README.md") "host-readme\n")
      (write-file (fs/path host "bb.edn") "{:paths [\"test\"]}\n")
      (write-file (fs/path host "test/keep.clj") "keep\n")
      (init-repo! git-dir)
      (seed-installer-host! git-dir)
      (seed-installer-pack! (fs/path git-dir ".swarmforge/project-pack"))
      (run {:dir git-dir} "git" "checkout" "-q" "-b" "lieutenant")
      (run {:dir git-dir} "git" "add" "-A")
      (run {:dir git-dir} "git" "commit" "-q" "-m" "Seed local branch")
      (let [result (run {:dir host
                         :env {"SWARMFORGE_REPO_URL" "https://example.invalid/swarm-forge"
                               "SWARMFORGE_GIT_DIR" (str git-dir)}}
                        (str (fs/path repo-root "get-swarm-forge"))
                        "lieutenant")]
        (is (zero? (:exit result)) (:err result))
        (is (str/includes? (:err result) (str "using " git-dir)))
        (is (str/includes? (:out result) "from lieutenant"))
        (assert-installer-host host)
        (is (fs/exists? (fs/path host ".swarmforge/project-pack/swarmforge/swarmforge.conf"))))
      (finally
        (fs/delete-tree host)
        (fs/delete-tree git-dir)))))
