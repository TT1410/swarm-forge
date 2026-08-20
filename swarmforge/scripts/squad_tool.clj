#!/usr/bin/env bb

(ns squad-tool
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-config :as cfg]
            [squad-tool-table :as tools]
            [clojure.string :as str]))

(def usage-text
  (str "Usage:\n"
       "  squad_tool.sh init\n"
       "  squad_tool.sh register <tool-name> <source> <version> <executable-file>\n"
       "  squad_tool.sh ensure <tool-name> <source> <version> -- <install-command...>\n"
       "  squad_tool.sh require <tool-name> <source> <version>\n"
       "  squad_tool.sh materialize <tool-name> <source> <version> [worktree]\n"
       "  squad_tool.sh status [tool-name]"))

(def valid-tool #"[A-Za-z0-9][A-Za-z0-9._-]*")

(defn exit! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn sh-continue [& args]
  (apply process/sh (concat [{:continue true}] args)))

(defn project-root []
  (or (cfg/project-root)
      (exit! 1 "Cannot find SwarmForge project root")))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn validate-tool! [tool]
  (when-not (re-matches valid-tool tool)
    (exit! 2 "Tool names must use letters, digits, dots, underscores, and hyphens."))
  (when (or (str/includes? tool "/") (str/includes? tool "\\"))
    (exit! 2 "Tool names may not contain path separators.")))

(defn cache-dir []
  (if-let [configured (not-empty (System/getenv "SWARMFORGE_TOOL_CACHE_DIR"))]
    (fs/path configured)
    (fs/path (project-root) ".swarmforge" "tools")))

(defn cache-paths [root]
  {:root root
   :bin (fs/path root "bin")
   :src (fs/path root "src")
   :cache (fs/path root "cache")
   :manifests (fs/path root "manifests")
   :locks (fs/path root "locks")})

(defn ensure-cache! []
  (let [paths (cache-paths (cache-dir))]
    (doseq [dir (vals paths)]
      (fs/create-dirs dir))
    paths))

(defn write-atomic! [file content]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                  :prefix (str "." (fs/file-name file) ".")})]
    (spit (str tmp) content)
    (fs/move tmp file {:replace-existing true})))

(defn source-file! [path]
  (let [file (fs/path path)
        file (if (fs/absolute? file) file (fs/path (fs/cwd) file))]
    (when-not (fs/regular-file? file)
      (exit! 1 (str "Executable file not found: " file)))
    file))

(defn lock-timeout? [deadline]
  (> (System/currentTimeMillis) deadline))

(def stale-owner-grace-ms 1000)

(defn current-pid []
  (.pid (java.lang.ProcessHandle/current)))

(defn lock-owner-file [lock-dir]
  (fs/path lock-dir "owner.edn"))

(defn write-lock-owner! [lock-dir]
  (spit (str (lock-owner-file lock-dir))
        (pr-str {:pid (current-pid)
                 :created_at (timestamp)})))

(defn try-create-lock! [lock-dir]
  (try
    (fs/create-dir lock-dir)
    (write-lock-owner! lock-dir)
    true
    (catch java.nio.file.FileAlreadyExistsException _
      false)))

(defn maybe-parse-long [value]
  (try
    (Long/parseLong (str value))
    (catch Exception _
      nil)))

(defn read-lock-owner [lock-dir]
  (let [file (lock-owner-file lock-dir)]
    (when (fs/regular-file? file)
      (try
        (read-string (slurp (str file)))
        (catch Exception _
          nil)))))

(defn owner-pid [owner]
  (maybe-parse-long (:pid owner)))

(defn pid-alive? [pid]
  (when pid
    (let [handle (java.lang.ProcessHandle/of pid)]
      (and (.isPresent handle)
           (.isAlive (.get handle))))))

(defn path-age-ms [path]
  (try
    (- (System/currentTimeMillis)
       (.toMillis (java.nio.file.Files/getLastModifiedTime (.toPath (fs/file path)))))
    (catch java.nio.file.NoSuchFileException _
      0)))

(defn old-enough-no-owner-lock? [lock-dir]
  (> (path-age-ms lock-dir) stale-owner-grace-ms))

(defn stale-lock? [lock-dir]
  (let [owner (read-lock-owner lock-dir)
        pid (owner-pid owner)]
    (cond
      (nil? owner) (old-enough-no-owner-lock? lock-dir)
      (nil? pid) true
      :else (not (pid-alive? pid)))))

(defn clear-stale-lock! [lock-dir]
  (try
    (when (and (fs/directory? lock-dir)
               (stale-lock? lock-dir))
      (fs/delete-tree lock-dir)
      true)
    (catch java.nio.file.NoSuchFileException _
      false)))

(defn wait-for-lock-retry! []
  (Thread/sleep 50))

(defn acquire-lock! [locks-dir tool]
  (let [lock-dir (fs/path locks-dir (str tool ".lock"))
        deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (when (lock-timeout? deadline)
        (exit! 2 (str "Timed out waiting for tool cache lock: " lock-dir)))
      (if (try-create-lock! lock-dir)
        lock-dir
        (do
          (clear-stale-lock! lock-dir)
          (wait-for-lock-retry!)
          (recur))))))

(defn manifest-file [paths tool]
  (fs/path (:manifests paths) (str tool ".manifest")))

(defn executable-target [paths tool]
  (fs/path (:bin paths) tool))

(defn local-tool-paths [worktree]
  (let [root (fs/path worktree ".swarmforge" "tools")]
    {:root root
     :bin (fs/path root "bin")
     :manifests (fs/path root "manifests")}))

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(defn missing-tool-state [manifest executable]
  (cond
    (not (fs/exists? manifest)) {:state :missing :reason "missing manifest"}
    (not (fs/exists? executable)) {:state :missing :reason "missing executable"}))

(defn mismatch-state [manifest field expected]
  (let [actual (read-value manifest field)]
    (when-not (= expected actual)
      {:state :mismatch
       :field field
       :expected expected
       :actual (or actual "unknown")})))

(defn available-tool-state [executable]
  {:state :available :executable executable})

(defn tool-state [paths tool source version]
  (let [manifest (manifest-file paths tool)
        executable (executable-target paths tool)]
    (or (missing-tool-state manifest executable)
        (mismatch-state manifest "source" source)
        (mismatch-state manifest "version" version)
        (available-tool-state executable))))

(defn write-manifest! [manifest tool source version executable now]
  (write-atomic! manifest
                 (str "tool: " tool "\n"
                      "source: " source "\n"
                      "version: " version "\n"
                      "executable: " executable "\n"
                      "registered_at: " now "\n")))

(defn write-local-manifest! [manifest tool source version executable cached mode now]
  (write-atomic! manifest
                 (str "tool: " tool "\n"
                      "source: " source "\n"
                      "version: " version "\n"
                      "executable: " executable "\n"
                      "cached_executable: " cached "\n"
                      "mode: " mode "\n"
                      "materialized_at: " now "\n")))

(defn hardlink! [source target]
  (java.nio.file.Files/createLink
   (.toPath (fs/file target))
   (.toPath (fs/file source))))

(defn ensure-worktree! [maybe-worktree]
  (let [worktree (or maybe-worktree (not-empty (System/getenv "SWARMFORGE_WORKTREE")))]
    (when (str/blank? worktree)
      (exit! 1 "No worktree supplied and SWARMFORGE_WORKTREE is not set."))
    worktree))

(defn ensure-tool-state! [tool state]
  (case (:state state)
    :missing (exit! 3
                    (str "SQUAD_TOOL_MISSING: " tool)
                    (str "REASON: " (:reason state)))
    :mismatch (exit! 4
                     (str "SQUAD_TOOL_MISMATCH: " tool)
                     (str "FIELD: " (:field state))
                     (str "EXPECTED: " (:expected state))
                     (str "ACTUAL: " (:actual state)))
    :available state))

(defn ensure-canonical-tool! [root tool source version]
  (when-let [{:keys [field expected actual]} (tools/canonical-mismatch root tool source version)]
    (exit! 4
           (str "SQUAD_TOOL_MISMATCH: " tool)
           (str "FIELD: " field)
           (str "EXPECTED: " expected)
           (str "ACTUAL: " actual))))

(defn materialize-link! [cached target]
  (try
    (hardlink! cached target)
    "hardlink"
    (catch Exception _
      (fs/copy cached target {:replace-existing true})
      "copy")))

(defn write-materialized-tool! [paths worktree tool source version state]
  (let [local (local-tool-paths worktree)
        target (fs/path (:bin local) tool)
        manifest (fs/path (:manifests local) (str tool ".manifest"))
        cached (:executable state)
        lock-dir (acquire-lock! (:locks paths) (str tool ".materialize"))]
    (try
      (fs/create-dirs (:bin local))
      (fs/create-dirs (:manifests local))
      (fs/delete-if-exists target)
      (let [mode (materialize-link! cached target)]
        (fs/set-posix-file-permissions target "r-xr-xr-x")
        (write-local-manifest! manifest tool source version target cached mode (timestamp))
        {:tool tool
         :state :materialized
         :executable target
         :manifest manifest
         :mode mode})
      (finally
        (fs/delete-tree lock-dir)))))

(defn materialize-tool! [tool source version maybe-worktree]
  (validate-tool! tool)
  (let [worktree (ensure-worktree! maybe-worktree)
        paths (ensure-cache!)
        state (ensure-tool-state! tool (tool-state paths tool source version))]
    (write-materialized-tool! paths worktree tool source version state)))

(defn print-materialized! [{:keys [tool executable manifest mode]}]
  (println "SQUAD_TOOL:" tool)
  (println "STATE: materialized")
  (println "MODE:" mode)
  (println "EXECUTABLE:" (str executable))
  (println "MANIFEST:" (str manifest)))

(defn print-tool-available! [tool state materialized]
  (println "SQUAD_TOOL:" tool)
  (println "STATE: available")
  (println "EXECUTABLE:" (str (:executable state)))
  (when materialized
    (println "LOCAL_EXECUTABLE:" (str (:executable materialized)))
    (println "LOCAL_MODE:" (:mode materialized))))

(defn register-tool! [tool source version executable]
  (validate-tool! tool)
  (let [paths (ensure-cache!)
        executable (source-file! executable)
        lock-dir (acquire-lock! (:locks paths) tool)]
    (try
      (let [target (executable-target paths tool)
            manifest (manifest-file paths tool)
            now (timestamp)]
        (fs/copy executable target {:replace-existing true})
        (fs/set-posix-file-permissions target "rwxr-xr-x")
        (write-manifest! manifest tool source version target now)
        (println "SQUAD_TOOL:" tool)
        (println "STATE: registered")
        (println "EXECUTABLE:" (str target))
        (println "MANIFEST:" (str manifest)))
      (finally
        (fs/delete-tree lock-dir)))))

(defn require-tool! [tool source version]
  (validate-tool! tool)
  (let [root (project-root)
        _ (ensure-canonical-tool! root tool source version)
        paths (ensure-cache!)
        state (ensure-tool-state! tool (tool-state paths tool source version))
        materialized (when (not-empty (System/getenv "SWARMFORGE_WORKTREE"))
                       (materialize-tool! tool source version nil))]
    (print-tool-available! tool state materialized)))

(defn split-command [args]
  (let [[before after] (split-with #(not= "--" %) args)]
    (when (or (empty? before) (empty? after) (empty? (rest after)))
      (exit! 1 usage-text))
    [before (rest after)]))

(defn print-installed-tool! [tool target]
  (println "SQUAD_TOOL:" tool)
  (println "STATE: installed")
  (println "EXECUTABLE:" (str target)))

(defn print-cached-tool! [tool state]
  (println "SQUAD_TOOL:" tool)
  (println "STATE: available")
  (println "EXECUTABLE:" (str (:executable state))))

(defn tool-install-env [paths tool source version target tool-src]
  {"SWARMFORGE_TOOL_NAME" tool
   "SWARMFORGE_TOOL_SOURCE" source
   "SWARMFORGE_TOOL_VERSION" version
   "SWARMFORGE_TOOL_CACHE_DIR" (str (:root paths))
   "SWARMFORGE_TOOL_BIN_DIR" (str (:bin paths))
   "SWARMFORGE_TOOL_SRC_DIR" (str tool-src)
   "SWARMFORGE_TOOL_TARGET" (str target)})

(defn run-install-command! [paths tool source version target tool-src install-command]
  (apply process/sh
         {:continue true
          :dir (str tool-src)
          :env (tool-install-env paths tool source version target tool-src)}
         install-command))

(defn ensure-install-success! [tool result]
  (when-not (zero? (:exit result))
    (exit! (:exit result)
           (str "SQUAD_TOOL_INSTALL_FAILED: " tool)
           (str/trim (str (:err result))))))

(defn ensure-install-target! [tool target]
  (when-not (fs/regular-file? target)
    (exit! 5
           (str "SQUAD_TOOL_INSTALL_INCOMPLETE: " tool)
           (str "REASON: missing target executable " target))))

(defn github-https-url
  "Clone URL for a github.com/org/repo source. Local paths are not a source of truth."
  [source]
  (when (and (string? source) (str/starts-with? source "github.com/"))
    (str "https://" source ".git")))

(defn latest-version? [version]
  (= "latest" (str/lower-case (str version))))

(defn git-ok! [result context]
  (when-not (zero? (:exit result))
    (exit! (or (:exit result) 1)
           (str "SQUAD_TOOL_GIT_FAILED: " context)
           (str/trim (str (:err result) "\n" (:out result)))))
  result)

(defn git-clone! [url dir]
  (when (fs/exists? dir)
    (fs/delete-tree dir))
  (when-let [parent (fs/parent dir)]
    (fs/create-dirs parent))
  (git-ok! (process/sh {:continue true} "git" "clone" "--" url (str dir))
           (str "clone " url)))

(defn git-in [dir & args]
  (git-ok! (apply process/sh {:continue true :dir (str dir)} args)
           (str/join " " args)))

(defn git-update! [dir version]
  (git-in dir "git" "fetch" "origin")
  (if (latest-version? version)
    (let [ref (str/trim (:out (git-in dir "git" "rev-parse" "--abbrev-ref" "origin/HEAD")))]
      (git-in dir "git" "reset" "--hard" ref))
    (git-in dir "git" "checkout" "--detach" (str version))))

(defn sync-git-source! [dir url version]
  (if (fs/exists? (fs/path dir ".git"))
    (git-update! dir version)
    (do
      (git-clone! url dir)
      (git-update! dir version))))

(defn install-bb-tool-command? [install-command]
  (boolean (some #(str/includes? (str %) "install_bb_tool.sh") install-command)))

(defn maybe-sync-github-source! [tool-src source version install-command]
  (when-let [url (and (install-bb-tool-command? install-command)
                      (github-https-url source))]
    (sync-git-source! tool-src url version)))

(defn install-tool! [paths tool source version install-command]
  (let [target (executable-target paths tool)
        tool-src (fs/path (:src paths) tool)]
    (fs/create-dirs tool-src)
    (maybe-sync-github-source! tool-src source version install-command)
    (fs/delete-if-exists target)
    (ensure-install-success! tool (run-install-command! paths tool source version target tool-src install-command))
    (ensure-install-target! tool target)
    (fs/set-posix-file-permissions target "rwxr-xr-x")
    (write-manifest! (manifest-file paths tool) tool source version target (timestamp))
    (print-installed-tool! tool target)))

(defn ensure-tool! [tool source version install-command]
  (validate-tool! tool)
  (let [root (project-root)
        _ (ensure-canonical-tool! root tool source version)
        paths (ensure-cache!)
        lock-dir (acquire-lock! (:locks paths) tool)]
    (try
      (let [state (tool-state paths tool source version)]
        (if (and (not (latest-version? version))
                 (= :available (:state state)))
          (print-cached-tool! tool state)
          (install-tool! paths tool source version install-command)))
      (finally
        (fs/delete-tree lock-dir)))))

(defn manifest-available? [manifest executable]
  (and (fs/exists? manifest) (fs/exists? executable)))

(defn print-registered-tool! [tool manifest executable]
  (println "TOOL:" tool)
  (println "STATE: registered")
  (println "SOURCE:" (or (read-value manifest "source") "unknown"))
  (println "VERSION:" (or (read-value manifest "version") "unknown"))
  (println "EXECUTABLE:" (str executable)))

(defn print-missing-tool! [tool]
  (println "TOOL:" tool)
  (println "STATE: missing"))

(defn print-one! [paths tool]
  (validate-tool! tool)
  (let [manifest (manifest-file paths tool)
        executable (executable-target paths tool)]
    (if (manifest-available? manifest executable)
      (print-registered-tool! tool manifest executable)
      (print-missing-tool! tool))))

(defn registered-tools [paths]
  (if (fs/exists? (:manifests paths))
    (->> (fs/list-dir (:manifests paths))
         (filter fs/regular-file?)
         (map fs/file-name)
         (keep #(second (re-matches #"(.+)\.manifest" %)))
         sort
         vec)
    []))

(defn status! [& maybe-tool]
  (let [paths (ensure-cache!)]
    (if-let [tool (first maybe-tool)]
      (print-one! paths tool)
      (let [tools (registered-tools paths)]
        (println "TOOL_CACHE:" (str (:root paths)))
        (println "TOOLS:" (if (seq tools) (str/join "," tools) "none"))))))

(defn init! []
  (let [paths (ensure-cache!)]
    (println "TOOL_CACHE:" (str (:root paths)))
    (println "BIN:" (str (:bin paths)))
    (println "MANIFESTS:" (str (:manifests paths)))))

(defn exact-count! [args expected]
  (when-not (= expected (count args))
    (exit! 1 usage-text)))

(defn between-count! [args low high]
  (when-not (<= low (count args) high)
    (exit! 1 usage-text)))

(defn ensure-command! [args]
  (let [[tool-args install-command] (split-command (rest args))]
    (exact-count! tool-args 3)
    (ensure-tool! (first tool-args) (second tool-args) (nth tool-args 2) install-command)))

(def tool-commands
  {"init" (fn [args] (exact-count! args 1) (init!))
   "register" (fn [args] (exact-count! args 5) (register-tool! (second args) (nth args 2) (nth args 3) (nth args 4)))
   "ensure" ensure-command!
   "require" (fn [args] (exact-count! args 4) (require-tool! (second args) (nth args 2) (nth args 3)))
   "materialize" (fn [args]
                   (between-count! args 4 5)
                   (print-materialized!
                    (materialize-tool! (second args) (nth args 2) (nth args 3) (nth args 4 nil))))
   "status" (fn [args] (between-count! args 1 2) (apply status! (rest args)))})

(defn -main [& args]
  (if-let [command (tool-commands (first args))]
    (command args)
    (exit! 1 usage-text)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
