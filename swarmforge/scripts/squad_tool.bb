#!/usr/bin/env bb

(ns squad-tool
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def usage-text
  (str "Usage:\n"
       "  squad_tool.sh init\n"
       "  squad_tool.sh register <tool-name> <source> <version> <executable-file>\n"
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
  (let [cwd (fs/cwd)
        direct (fs/path cwd ".swarmforge" "roles.tsv")]
    (if (fs/exists? direct)
      cwd
      (let [git-root (str/trim (:out (sh-continue "git" "rev-parse" "--show-toplevel")))]
        (if (and (not (str/blank? git-root))
                 (fs/exists? (fs/path git-root ".swarmforge" "roles.tsv")))
          (fs/path git-root)
          (exit! 1 "Cannot find SwarmForge project root"))))))

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
  (fs/path (:manifests paths) (str tool ".manifest"))
)

(defn executable-target [paths tool]
  (fs/path (:bin paths) tool))

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

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
        (write-atomic! manifest
                       (str "tool: " tool "\n"
                            "source: " source "\n"
                            "version: " version "\n"
                            "executable: " target "\n"
                            "registered_at: " now "\n"))
        (println "SQUAD_TOOL:" tool)
        (println "STATE: registered")
        (println "EXECUTABLE:" (str target))
        (println "MANIFEST:" (str manifest)))
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
    "status" (if (<= 1 (count args) 2)
               (apply status! (rest args))
               (exit! 1 usage-text))
    (exit! 1 usage-text)))

(apply -main *command-line-args*)
