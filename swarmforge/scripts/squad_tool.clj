#!/usr/bin/env bb

(ns squad-tool
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
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
  (let [configured (not-empty (System/getenv "SWARMFORGE_PROJECT_ROOT"))
        configured-roles (when configured (fs/path configured ".swarmforge" "roles.tsv"))
        cwd (fs/cwd)
        direct (fs/path cwd ".swarmforge" "roles.tsv")]
    (if (and configured (fs/exists? configured-roles))
      (fs/path configured)
      (if (fs/exists? direct)
      cwd
      (let [git-root (str/trim (:out (sh-continue "git" "rev-parse" "--show-toplevel")))]
        (if (and (not (str/blank? git-root))
                 (fs/exists? (fs/path git-root ".swarmforge" "roles.tsv")))
          (fs/path git-root)
          (exit! 1 "Cannot find SwarmForge project root")))))))

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

(defn acquire-lock! [locks-dir tool]
  (let [lock-dir (fs/path locks-dir (str tool ".lock"))
        deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (when (> (System/currentTimeMillis) deadline)
        (exit! 2 (str "Timed out waiting for tool cache lock: " lock-dir)))
      (if (try
            (fs/create-dir lock-dir)
            true
            (catch java.nio.file.FileAlreadyExistsException _
              false))
        lock-dir
        (do
          (Thread/sleep 50)
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

(defn tool-state [paths tool source version]
  (let [manifest (manifest-file paths tool)
        executable (executable-target paths tool)]
    (cond
      (not (fs/exists? manifest))
      {:state :missing :reason "missing manifest"}

      (not (fs/exists? executable))
      {:state :missing :reason "missing executable"}

      (not= source (read-value manifest "source"))
      {:state :mismatch
       :field "source"
       :expected source
       :actual (or (read-value manifest "source") "unknown")}

      (not= version (read-value manifest "version"))
      {:state :mismatch
       :field "version"
       :expected version
       :actual (or (read-value manifest "version") "unknown")}

      :else
      {:state :available :executable executable})))

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

(defn materialize-tool! [tool source version maybe-worktree]
  (validate-tool! tool)
  (let [worktree (or maybe-worktree (not-empty (System/getenv "SWARMFORGE_WORKTREE")))]
    (when (str/blank? worktree)
      (exit! 1 "No worktree supplied and SWARMFORGE_WORKTREE is not set."))
    (let [paths (ensure-cache!)
          state (tool-state paths tool source version)]
      (case (:state state)
        :missing
        (exit! 3
               (str "SQUAD_TOOL_MISSING: " tool)
               (str "REASON: " (:reason state)))

        :mismatch
        (exit! 4
               (str "SQUAD_TOOL_MISMATCH: " tool)
               (str "FIELD: " (:field state))
               (str "EXPECTED: " (:expected state))
               (str "ACTUAL: " (:actual state)))

        :available
        (let [local (local-tool-paths worktree)
              target (fs/path (:bin local) tool)
              manifest (fs/path (:manifests local) (str tool ".manifest"))
              cached (:executable state)
              lock-dir (acquire-lock! (:locks paths) (str tool ".materialize"))]
          (try
            (fs/create-dirs (:bin local))
            (fs/create-dirs (:manifests local))
            (fs/delete-if-exists target)
            (let [mode (try
                         (hardlink! cached target)
                         "hardlink"
                         (catch Exception _
                           (fs/copy cached target {:replace-existing true})
                           "copy"))]
              (fs/set-posix-file-permissions target "r-xr-xr-x")
              (write-local-manifest! manifest tool source version target cached mode (timestamp))
              {:tool tool
               :state :materialized
               :executable target
               :manifest manifest
               :mode mode})
            (finally
              (fs/delete-tree lock-dir))))))))

(defn print-materialized! [{:keys [tool executable manifest mode]}]
  (println "SQUAD_TOOL:" tool)
  (println "STATE: materialized")
  (println "MODE:" mode)
  (println "EXECUTABLE:" (str executable))
  (println "MANIFEST:" (str manifest)))

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
  (let [paths (ensure-cache!)
        state (tool-state paths tool source version)]
    (case (:state state)
      :missing
      (exit! 3
             (str "SQUAD_TOOL_MISSING: " tool)
             (str "REASON: " (:reason state)))

      :mismatch
      (exit! 4
             (str "SQUAD_TOOL_MISMATCH: " tool)
             (str "FIELD: " (:field state))
             (str "EXPECTED: " (:expected state))
             (str "ACTUAL: " (:actual state)))

      :available
      (let [materialized (when (not-empty (System/getenv "SWARMFORGE_WORKTREE"))
                           (materialize-tool! tool source version nil))]
        (println "SQUAD_TOOL:" tool)
        (println "STATE: available")
        (println "EXECUTABLE:" (str (:executable state)))
        (when materialized
          (println "LOCAL_EXECUTABLE:" (str (:executable materialized)))
          (println "LOCAL_MODE:" (:mode materialized)))))))

(defn split-command [args]
  (let [[before after] (split-with #(not= "--" %) args)]
    (when (or (empty? before) (empty? after) (empty? (rest after)))
      (exit! 1 usage-text))
    [before (rest after)]))

(defn ensure-tool! [tool source version install-command]
  (validate-tool! tool)
  (let [paths (ensure-cache!)
        lock-dir (acquire-lock! (:locks paths) tool)]
    (try
      (let [state (tool-state paths tool source version)]
        (if (= :available (:state state))
          (do
            (println "SQUAD_TOOL:" tool)
            (println "STATE: available")
            (println "EXECUTABLE:" (str (:executable state))))
          (let [target (executable-target paths tool)
                tool-src (fs/path (:src paths) tool)]
            (fs/create-dirs tool-src)
            (fs/delete-if-exists target)
            (let [result (apply process/sh
                                {:continue true
                                 :dir (str tool-src)
                                 :env {"SWARMFORGE_TOOL_NAME" tool
                                       "SWARMFORGE_TOOL_SOURCE" source
                                       "SWARMFORGE_TOOL_VERSION" version
                                       "SWARMFORGE_TOOL_CACHE_DIR" (str (:root paths))
                                       "SWARMFORGE_TOOL_BIN_DIR" (str (:bin paths))
                                       "SWARMFORGE_TOOL_SRC_DIR" (str tool-src)
                                       "SWARMFORGE_TOOL_TARGET" (str target)}}
                                install-command)]
              (when-not (zero? (:exit result))
                (exit! (:exit result)
                       (str "SQUAD_TOOL_INSTALL_FAILED: " tool)
                       (str/trim (str (:err result)))))
              (when-not (fs/regular-file? target)
                (exit! 5
                       (str "SQUAD_TOOL_INSTALL_INCOMPLETE: " tool)
                       (str "REASON: missing target executable " target)))
              (fs/set-posix-file-permissions target "rwxr-xr-x")
              (write-manifest! (manifest-file paths tool) tool source version target (timestamp))
              (println "SQUAD_TOOL:" tool)
              (println "STATE: installed")
              (println "EXECUTABLE:" (str target))))))
      (finally
        (fs/delete-tree lock-dir)))))

(defn print-one! [paths tool]
  (validate-tool! tool)
  (let [manifest (manifest-file paths tool)
        executable (executable-target paths tool)]
    (if (and (fs/exists? manifest) (fs/exists? executable))
      (do
        (println "TOOL:" tool)
        (println "STATE: registered")
        (println "SOURCE:" (or (read-value manifest "source") "unknown"))
        (println "VERSION:" (or (read-value manifest "version") "unknown"))
        (println "EXECUTABLE:" (str executable)))
      (do
        (println "TOOL:" tool)
        (println "STATE: missing")))))

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

(defn -main [& args]
  (case (first args)
    "init" (if (= 1 (count args))
             (init!)
             (exit! 1 usage-text))
    "register" (if (= 5 (count args))
                 (register-tool! (second args) (nth args 2) (nth args 3) (nth args 4))
                 (exit! 1 usage-text))
    "ensure" (let [[tool-args install-command] (split-command (rest args))]
               (if (= 3 (count tool-args))
                 (ensure-tool! (first tool-args) (second tool-args) (nth tool-args 2) install-command)
                 (exit! 1 usage-text)))
    "require" (if (= 4 (count args))
                (require-tool! (second args) (nth args 2) (nth args 3))
                (exit! 1 usage-text))
    "materialize" (if (<= 4 (count args) 5)
                    (print-materialized!
                     (materialize-tool! (second args)
                                        (nth args 2)
                                        (nth args 3)
                                        (nth args 4 nil)))
                    (exit! 1 usage-text))
    "status" (if (<= 1 (count args) 2)
               (apply status! (rest args))
               (exit! 1 usage-text))
    (exit! 1 usage-text)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
