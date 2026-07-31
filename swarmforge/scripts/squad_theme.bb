#!/usr/bin/env bb

(ns squad-theme
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def usage-text
  (str "Usage:\n"
       "  squad_theme.sh create <theme-id> <theme-file>\n"
       "  squad_theme.sh story <theme-id> <story-id> <story-file>\n"
       "  squad_theme.sh acceptance <theme-id> <artifact-id> <acceptance-file>\n"
       "  squad_theme.sh approve <theme-id> <gate> <detail...>\n"
       "  squad_theme.sh status <theme-id>"))

(def valid-id #"[A-Za-z0-9][A-Za-z0-9._-]*")

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

(defn add-story! [theme-id story-id story-file]
  (validate-id! "Theme id" theme-id)
  (validate-id! "Story id" story-id)
  (let [root (fs/absolutize (project-root))
        source (source-file! story-file)
        dir (theme-dir root theme-id)
        story-path (fs/path dir "stories" (str story-id ".md"))
        now (timestamp)]
    (ensure-theme! dir theme-id)
    (when (fs/exists? story-path)
      (exit! 2 (str "Story already exists: " story-id)))
    (fs/create-dirs (fs/parent story-path))
    (fs/copy source story-path)
    (write-atomic! (fs/path dir "status")
                   (str "theme_id: " theme-id "\n"
                        "state: story_added\n"
                        "detail: " story-id "\n"
                        "updated_at: " now "\n"))
    (append-line! (fs/path dir "events.log")
                  (str now "\tstory_added\t" story-id))
    (println "SQUAD_THEME:" theme-id)
    (println "STORY:" story-id)
    (println "STATE: story_added")))

(defn add-acceptance! [theme-id artifact-id acceptance-file]
  (validate-id! "Theme id" theme-id)
  (validate-id! "Acceptance artifact id" artifact-id)
  (let [root (fs/absolutize (project-root))
        source (source-file! acceptance-file)
        dir (theme-dir root theme-id)
        artifact-path (fs/path dir "acceptance" (str artifact-id ".md"))
        now (timestamp)]
    (ensure-theme! dir theme-id)
    (when (fs/exists? artifact-path)
      (exit! 2 (str "Acceptance artifact already exists: " artifact-id)))
    (fs/create-dirs (fs/parent artifact-path))
    (fs/copy source artifact-path)
    (write-atomic! (fs/path dir "status")
                   (str "theme_id: " theme-id "\n"
                        "state: acceptance_added\n"
                        "detail: " artifact-id "\n"
                        "updated_at: " now "\n"))
    (append-line! (fs/path dir "events.log")
                  (str now "\tacceptance_added\t" artifact-id))
    (println "SQUAD_THEME:" theme-id)
    (println "ACCEPTANCE:" artifact-id)
    (println "STATE: acceptance_added")))

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
           (map #(str/replace % #"\.md$" ""))
           sort
           vec)
      [])))

(defn acceptance-ids [dir]
  (let [acceptance-dir (fs/path dir "acceptance")]
    (if (fs/exists? acceptance-dir)
      (->> (fs/list-dir acceptance-dir)
           (filter fs/regular-file?)
           (map fs/file-name)
           (map #(str/replace % #"\.md$" ""))
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
    (println "APPROVALS:" (count (approval-lines dir)))))

(defn -main [& args]
  (case (first args)
    "create" (if (= 3 (count args))
               (create-theme! (second args) (nth args 2))
               (exit! 1 usage-text))
    "story" (if (= 4 (count args))
              (add-story! (second args) (nth args 2) (nth args 3))
              (exit! 1 usage-text))
    "acceptance" (if (= 4 (count args))
                   (add-acceptance! (second args) (nth args 2) (nth args 3))
                   (exit! 1 usage-text))
    "approve" (if (>= (count args) 3)
                (approve! (second args) (nth args 2) (drop 3 args))
                (exit! 1 usage-text))
    "status" (if (= 2 (count args))
               (print-status! (second args))
               (exit! 1 usage-text))
    (exit! 1 usage-text)))

(apply -main *command-line-args*)
