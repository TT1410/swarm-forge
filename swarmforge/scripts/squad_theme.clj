#!/usr/bin/env bb

(ns squad-theme
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-config :as cfg]
            [clojure.string :as str]))

(def script-dir (fs/parent *file*))

(def usage-text
  (str "Usage:\n"
       "  squad_theme.sh create <theme-id> <theme-file>\n"
       "  squad_theme.sh module-map <theme-id> <module-map-file>\n"
       "  squad_theme.sh implementation-order <theme-id> <order-file>\n"
       "  squad_theme.sh story <theme-id> <story-id> <story-file>\n"
       "  squad_theme.sh stories <theme-id> <story-id>:<story-file>...\n"
       "  squad_theme.sh approved-story <theme-id> <story-id> <story-file> <assignment-id> <branch> <sha> <detail...>\n"
       "  squad_theme.sh acceptance <theme-id> <artifact-id> <acceptance-file>\n"
       "  squad_theme.sh approve <theme-id> <gate> <detail...>\n"
       "  squad_theme.sh status <theme-id>"))

(def module-map-required-headings
  ["## Purpose"
   "## Dependency Rule"
   "## Use Cases (Business / Process Rules)"
   "## UI (Interface Adapters)"
   "## IO (Interface Adapters / Drivers)"])

(def valid-id #"[A-Za-z0-9][A-Za-z0-9._-]*")

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

(defn validate-id! [kind value]
  (when-not (re-matches valid-id value)
    (exit! 2 (str kind " must use letters, digits, dots, underscores, and hyphens.")))
  (when (or (str/includes? value "/") (str/includes? value "\\"))
    (exit! 2 (str kind " may not contain path separators."))))

(defn source-file! [path]
  (let [file (fs/path path)
        file (if (fs/absolute? file) file (fs/path (fs/cwd) file))]
    (when-not (fs/regular-file? file)
      (exit! 1 (str "Source file not found: " file)))
    file))

(defn relative-project-artifact! [root file]
  (let [root-path (.normalize (.toAbsolutePath (fs/path root)))
        file-path (.normalize (.toAbsolutePath (fs/path file)))]
    (when-not (.startsWith file-path root-path)
      (exit! 2 "Acceptance artifacts must be project-local files."))
    (let [relative (str/replace (str (.relativize root-path file-path)) "\\" "/")]
      (when-not (or (str/starts-with? relative "features/")
                    (str/starts-with? relative "qa/"))
        (exit! 2 "Acceptance artifacts must live under features/ or qa/."))
      relative)))

(defn relative-story-artifact! [root file]
  (let [root-path (.normalize (.toAbsolutePath (fs/path root)))
        file-path (.normalize (.toAbsolutePath (fs/path file)))]
    (when-not (.startsWith file-path root-path)
      (exit! 2 "Story artifacts must be project-local files."))
    (let [relative (str/replace (str (.relativize root-path file-path)) "\\" "/")]
      (when-not (str/starts-with? relative "stories/")
        (exit! 2 "Story artifacts must live under stories/."))
      relative)))

(defn write-atomic! [file content]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                  :prefix (str "." (fs/file-name file) ".")})]
    (spit (str tmp) content)
    (fs/move tmp file {:replace-existing true})))

(defn append-line! [file line]
  (fs/create-dirs (fs/parent file))
  (spit (str file) (str line "\n") :append true))

(defn theme-dir [root theme-id]
  (fs/path root ".squad" "themes" theme-id))

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(defn create-theme! [theme-id theme-file]
  (validate-id! "Theme id" theme-id)
  (let [root (fs/absolutize (project-root))
        source (source-file! theme-file)
        dir (theme-dir root theme-id)
        now (timestamp)]
    (when (fs/exists? dir)
      (exit! 2 (str "Theme already exists: " theme-id)))
    (fs/create-dirs (fs/path dir "stories"))
    (fs/copy source (fs/path dir "theme.md"))
    (write-atomic! (fs/path dir "status")
                   (str "theme_id: " theme-id "\n"
                        "state: theme_created\n"
                        "detail: theme record created\n"
                        "updated_at: " now "\n"))
    (append-line! (fs/path dir "events.log")
                  (str now "\ttheme_created\t" theme-id))
    (println "SQUAD_THEME:" theme-id)
    (println "STATE: theme_created")
    (println "PATH:" (str dir))))

(defn ensure-theme! [dir theme-id]
  (when-not (fs/directory? dir)
    (exit! 1 (str "Unknown theme: " theme-id))))

(defn module-map-path [dir]
  (fs/path dir "module-map.md"))

(defn module-map-exists? [dir]
  (fs/regular-file? (module-map-path dir)))

(defn validate-module-map-content! [text]
  (when (str/blank? (str/trim text))
    (exit! 2 "Module map file is empty."))
  (doseq [heading module-map-required-headings]
    (when-not (str/includes? text heading)
      (exit! 2 (str "Module map missing required heading: " heading
                    " (see swarmforge/templates/theme-module-map.md)")))))

(defn record-module-map! [theme-id module-map-file]
  (validate-id! "Theme id" theme-id)
  (let [root (fs/absolutize (project-root))
        source (source-file! module-map-file)
        dir (theme-dir root theme-id)
        dest (module-map-path dir)
        content (slurp (str source))
        now (timestamp)]
    (ensure-theme! dir theme-id)
    (validate-module-map-content! content)
    (write-atomic! dest content)
    (write-atomic! (fs/path dir "status")
                   (str "theme_id: " theme-id "\n"
                        "state: module_map_recorded\n"
                        "detail: module-map.md\n"
                        "updated_at: " now "\n"))
    (append-line! (fs/path dir "events.log")
                  (str now "\tmodule_map_recorded\tmodule-map.md"))
    (println "SQUAD_THEME:" theme-id)
    (println "MODULE_MAP:" (str dest))
    (println "STATE: module_map_recorded")))

(defn implementation-order-path [dir]
  (fs/path dir "implementation-order.md"))

(defn implementation-order-exists? [dir]
  (fs/regular-file? (implementation-order-path dir)))

(defn implementation-order-edge-line? [line]
  "Makefile-style: dependent: provider [provider ...]"
  (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*\s*:\s*[A-Za-z0-9][A-Za-z0-9._-]*(?:\s+[A-Za-z0-9][A-Za-z0-9._-]*)*"
              line))

(defn validate-implementation-order-content! [text]
  (when (str/blank? (str/trim text))
    (exit! 2 "Implementation order file is empty."))
  (doseq [raw (str/split-lines text)]
    (let [line (str/trim (first (str/split raw #"#" 2)))]
      (when-not (str/blank? line)
        (when (re-find #"(?i)\bafter\b" line)
          (exit! 2 (str "Invalid implementation-order edge: use makefile colon form "
                        "`dependent: provider ...`, not the word after. Line: " line)))
        (when (or (str/includes? line ":")
                  (re-find #"\bafter\b" line))
          (when-not (implementation-order-edge-line? line)
            (exit! 2 (str "Invalid implementation-order edge (want: <story>: <provider>...): "
                          line))))))))

(defn record-implementation-order! [theme-id order-file]
  (validate-id! "Theme id" theme-id)
  (let [root (fs/absolutize (project-root))
        source (source-file! order-file)
        dir (theme-dir root theme-id)
        dest (implementation-order-path dir)
        content (slurp (str source))
        now (timestamp)]
    (ensure-theme! dir theme-id)
    (validate-implementation-order-content! content)
    (write-atomic! dest content)
    (write-atomic! (fs/path dir "status")
                   (str "theme_id: " theme-id "\n"
                        "state: implementation_order_recorded\n"
                        "detail: implementation-order.md\n"
                        "updated_at: " now "\n"))
    (append-line! (fs/path dir "events.log")
                  (str now "\timplementation_order_recorded\timplementation-order.md"))
    (println "SQUAD_THEME:" theme-id)
    (println "IMPLEMENTATION_ORDER:" (str dest))
    (println "STATE: implementation_order_recorded")))

(defn story-ref-files [dir]
  (let [stories-dir (fs/path dir "stories")]
    (if (fs/exists? stories-dir)
      (->> (fs/list-dir stories-dir)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".ref")))
           vec)
      [])))

(defn next-story-number [dir]
  (let [nums (keep (fn [ref-file]
                     (when-let [n (read-value ref-file "story_number")]
                       (when (re-matches #"[0-9]+" n)
                         (Long/parseLong n))))
                   (story-ref-files dir))]
    (inc (if (seq nums) (apply max nums) 0))))

(defn add-story! [theme-id story-id story-file]
  (validate-id! "Theme id" theme-id)
  (validate-id! "Story id" story-id)
  (let [root (fs/absolutize (project-root))
        source (source-file! story-file)
        relative-source (relative-story-artifact! root source)
        dir (theme-dir root theme-id)
        story-path (fs/path dir "stories" (str story-id ".ref"))
        now (timestamp)]
    (ensure-theme! dir theme-id)
    (when (fs/exists? story-path)
      (exit! 2 (str "Story already exists: " story-id)))
    (let [story-number (next-story-number dir)]
      (write-atomic! story-path
                     (str "story_id: " story-id "\n"
                          "story_number: " story-number "\n"
                          "path: " relative-source "\n"
                          "recorded_at: " now "\n"))
      (write-atomic! (fs/path dir "status")
                     (str "theme_id: " theme-id "\n"
                          "state: story_added\n"
                          "detail: " story-id " " relative-source "\n"
                          "updated_at: " now "\n"))
      (append-line! (fs/path dir "events.log")
                    (str now "\tstory_added\t" story-id "\t" story-number "\t" relative-source))
      (println "SQUAD_THEME:" theme-id)
      (println "STORY:" story-id)
      (println "STORY_NUMBER:" story-number)
      (println "PATH:" relative-source)
      (println "STATE: story_added"))))

(defn parse-story-pair! [pair]
  (let [[story-id story-file extra] (str/split pair #":" 3)]
    (when (or extra (str/blank? story-id) (str/blank? story-file))
      (exit! 2 "Story pairs must use <story-id>:<story-file>."))
    {:story-id story-id
     :story-file story-file}))

(defn add-stories! [theme-id pairs]
  (when-not (seq pairs)
    (exit! 1 usage-text))
  (doseq [{:keys [story-id story-file]} (map parse-story-pair! pairs)]
    (add-story! theme-id story-id story-file)))

(defn run-packet! [root & args]
  (let [result (apply process/sh (concat [{:dir (str root) :continue true}
                                          (str (fs/path script-dir "squad_packet.sh"))]
                                         args))]
    (when-not (zero? (:exit result))
      (exit! (:exit result) (:err result)))
    result))

(defn add-approved-story! [theme-id story-id story-file assignment-id branch sha detail-parts]
  (add-story! theme-id story-id story-file)
  (let [root (fs/absolutize (project-root))
        detail (str/replace (str/join " " detail-parts) #"\R+" " ")
        detail (if (str/blank? detail) "approved-by-user" detail)]
    (run-packet! root "create" theme-id story-id assignment-id branch sha)
    (run-packet! root "approve" story-id "story" detail)
    (println "SQUAD_THEME:" theme-id)
    (println "STORY:" story-id)
    (println "STATE: story_approved")))

(defn add-acceptance! [theme-id artifact-id acceptance-file]
  (validate-id! "Theme id" theme-id)
  (validate-id! "Acceptance artifact id" artifact-id)
  (let [root (fs/absolutize (project-root))
        source (source-file! acceptance-file)
        relative-source (relative-project-artifact! root source)
        dir (theme-dir root theme-id)
        artifact-path (fs/path dir "acceptance" (str artifact-id ".ref"))
        now (timestamp)]
    (ensure-theme! dir theme-id)
    (when (fs/exists? artifact-path)
      (exit! 2 (str "Acceptance artifact already exists: " artifact-id)))
    (fs/create-dirs (fs/parent artifact-path))
    (write-atomic! artifact-path
                   (str "artifact_id: " artifact-id "\n"
                        "path: " relative-source "\n"
                        "recorded_at: " now "\n"))
    (write-atomic! (fs/path dir "status")
                   (str "theme_id: " theme-id "\n"
                        "state: acceptance_added\n"
                        "detail: " artifact-id " " relative-source "\n"
                        "updated_at: " now "\n"))
    (append-line! (fs/path dir "events.log")
                  (str now "\tacceptance_added\t" artifact-id "\t" relative-source))
    (println "SQUAD_THEME:" theme-id)
    (println "ACCEPTANCE:" artifact-id)
    (println "PATH:" relative-source)
    (println "STATE: acceptance_added")))

(def content-gated-theme-gates
  "Theme gates whose approval is bound to a content fingerprint (B25 re-approve on revision)."
  #{"implementation-order" "implementation_order"
    "dependency-checker" "dependency_checker"})

(defn normalize-theme-gate [gate]
  (str/replace gate "_" "-"))

(defn content-sha [text]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        digest (.digest md (.getBytes (str text) "UTF-8"))]
    (.toString (BigInteger. 1 digest) 16)))

(defn gate-content-path [root theme-id gate]
  (case (normalize-theme-gate gate)
    "implementation-order" (implementation-order-path (theme-dir root theme-id))
    "dependency-checker" (fs/path root "dependency-checker.edn")
    nil))

(defn gate-fingerprint-path [theme-dir gate]
  (fs/path theme-dir "approval-fingerprints" (str (normalize-theme-gate gate) ".sha")))

(defn write-gate-fingerprint! [root theme-id gate]
  (when-let [content-path (gate-content-path root theme-id gate)]
    (when (fs/regular-file? content-path)
      (let [dir (theme-dir root theme-id)
            sha (content-sha (slurp (str content-path)))]
        (write-atomic! (gate-fingerprint-path dir gate) (str sha "\n"))))))

(defn approve! [theme-id gate detail-parts]
  (validate-id! "Theme id" theme-id)
  (validate-id! "Gate" gate)
  (let [root (fs/absolutize (project-root))
        dir (theme-dir root theme-id)
        detail (str/replace (str/join " " detail-parts) #"\R+" " ")
        detail (if (str/blank? detail) "approved" detail)
        now (timestamp)]
    (ensure-theme! dir theme-id)
    (append-line! (fs/path dir "approvals.tsv")
                  (str now "\t" gate "\t" detail))
    (when (contains? content-gated-theme-gates gate)
      (write-gate-fingerprint! root theme-id gate))
    (write-atomic! (fs/path dir "status")
                   (str "theme_id: " theme-id "\n"
                        "state: approved_" gate "\n"
                        "detail: " detail "\n"
                        "updated_at: " now "\n"))
    (append-line! (fs/path dir "events.log")
                  (str now "\tapproved_" gate "\t" detail))
    (println "SQUAD_THEME:" theme-id)
    (println "GATE:" gate)
    (println "STATE:" (str "approved_" gate))))

(defn story-ids [dir]
  (let [stories-dir (fs/path dir "stories")]
    (if (fs/exists? stories-dir)
      (->> (fs/list-dir stories-dir)
           (filter fs/regular-file?)
           (map fs/file-name)
           (map #(str/replace % #"\.ref$" ""))
           sort
           vec)
      [])))

(defn acceptance-ids [dir]
  (let [acceptance-dir (fs/path dir "acceptance")]
    (if (fs/exists? acceptance-dir)
      (->> (fs/list-dir acceptance-dir)
           (filter fs/regular-file?)
           (map fs/file-name)
           (map #(str/replace % #"\.ref$" ""))
           sort
           vec)
      [])))

(defn approval-lines [dir]
  (if (fs/exists? (fs/path dir "approvals.tsv"))
    (str/split-lines (slurp (str (fs/path dir "approvals.tsv"))))
    []))

(defn print-status! [theme-id]
  (validate-id! "Theme id" theme-id)
  (let [root (fs/absolutize (project-root))
        dir (theme-dir root theme-id)
        status (fs/path dir "status")]
    (ensure-theme! dir theme-id)
    (println "THEME:" theme-id)
    (println "STATE:" (or (read-value status "state") "unknown"))
    (println "DETAIL:" (or (read-value status "detail") ""))
    (println "UPDATED_AT:" (or (read-value status "updated_at") "unknown"))
    (println "STORIES:" (str/join "," (story-ids dir)))
    (println "ACCEPTANCE:" (str/join "," (acceptance-ids dir)))
    (println "MODULE_MAP:" (if (module-map-exists? dir) "present" "missing"))
    (println "IMPLEMENTATION_ORDER:" (if (implementation-order-exists? dir) "present" "missing"))
    (println "APPROVALS:" (count (approval-lines dir)))))

(defn exact-count! [args n]
  (when-not (= n (count args))
    (exit! 1 usage-text)))

(defn minimum-count! [args n]
  (when-not (>= (count args) n)
    (exit! 1 usage-text)))

(def theme-commands
  {"create" (fn [args]
              (exact-count! args 3)
              (create-theme! (second args) (nth args 2)))
   "module-map" (fn [args]
                  (exact-count! args 3)
                  (record-module-map! (second args) (nth args 2)))
   "implementation-order" (fn [args]
                            (exact-count! args 3)
                            (record-implementation-order! (second args) (nth args 2)))
   "story" (fn [args]
             (exact-count! args 4)
             (add-story! (second args) (nth args 2) (nth args 3)))
   "stories" (fn [args]
               (minimum-count! args 3)
               (add-stories! (second args) (drop 2 args)))
   "approved-story" (fn [args]
                      (minimum-count! args 7)
                      (add-approved-story! (second args) (nth args 2) (nth args 3)
                                           (nth args 4) (nth args 5) (nth args 6)
                                           (drop 7 args)))
   "acceptance" (fn [args]
                  (exact-count! args 4)
                  (add-acceptance! (second args) (nth args 2) (nth args 3)))
   "approve" (fn [args]
               (minimum-count! args 3)
               (approve! (second args) (nth args 2) (drop 3 args)))
   "status" (fn [args]
              (exact-count! args 2)
              (print-status! (second args)))})

(defn -main [& args]
  (if-let [command (theme-commands (first args))]
    (command args)
    (exit! 1 usage-text)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
