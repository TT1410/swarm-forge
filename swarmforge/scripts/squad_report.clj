#!/usr/bin/env bb

(ns squad-report
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def usage-text
  "Usage: squad_report.sh <theme-id>")

(def valid-id #"[A-Za-z0-9][A-Za-z0-9._-]*")

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

(defn validate-id! [kind value]
  (when-not (re-matches valid-id value)
    (exit! 2 (str kind " must use letters, digits, dots, underscores, and hyphens.")))
  (when (or (str/includes? value "/") (str/includes? value "\\"))
    (exit! 2 (str kind " may not contain path separators."))))

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(defn ids-in [dir]
  (if (fs/exists? dir)
    (->> (fs/list-dir dir)
         (filter fs/regular-file?)
         (map fs/file-name)
         (map #(str/replace % #"\.(md|ref)$" ""))
         sort
         vec)
    []))

(defn approval-rows [theme-dir]
  (let [file (fs/path theme-dir "approvals.tsv")]
    (if (fs/exists? file)
      (->> (str/split-lines (slurp (str file)))
           (remove str/blank?)
           (map #(let [[time gate detail] (str/split % #"\t" 3)]
                   {:time time :gate gate :detail detail}))
           vec)
      [])))

(defn assignment-dirs [root theme-id]
  (let [dir (fs/path root ".squad" "assignments")]
    (if (fs/exists? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (filter #(= theme-id (read-value (fs/path % "metadata") "theme_id")))
           (sort-by fs/file-name)
           vec)
      [])))

(defn assignment-row [dir]
  (let [metadata (fs/path dir "metadata")
        status (fs/path dir "status")]
    {:id (or (read-value metadata "assignment_id") (fs/file-name dir))
     :story (or (read-value metadata "story_id") "unknown")
     :template (or (read-value metadata "template") "unknown")
     :state (or (read-value status "state") "unknown")
     :result (fs/exists? (fs/path dir "result"))
     :merge (read-value (fs/path dir "merge") "state")
     :accepted-merge (read-value (fs/path dir "accepted-merge") "state")
     :review (read-value (fs/path dir "review") "state")
     :rejection (read-value (fs/path dir "rejection") "state")
     :replacement (read-value (fs/path dir "replacement") "replacement")
     :replaces (read-value (fs/path dir "replaces") "replaces")}))

(defn print-list [label values]
  (println (str "- " label ": " (if (seq values) (str/join ", " values) "none"))))

(defn print-report! [theme-id]
  (validate-id! "Theme id" theme-id)
  (let [root (fs/absolutize (project-root))
        theme-dir (fs/path root ".squad" "themes" theme-id)
        status (fs/path theme-dir "status")]
    (when-not (fs/directory? theme-dir)
      (exit! 1 (str "Unknown theme: " theme-id)))
    (println (str "# Squad Report: " theme-id))
    (println)
    (println "## Theme")
    (println (str "- State: " (or (read-value status "state") "unknown")))
    (println (str "- Detail: " (or (read-value status "detail") "")))
    (println (str "- Updated: " (or (read-value status "updated_at") "unknown")))
    (print-list "Stories" (ids-in (fs/path theme-dir "stories")))
    (print-list "Acceptance" (ids-in (fs/path theme-dir "acceptance")))
    (println)
    (println "## Approvals")
    (if-let [approvals (seq (approval-rows theme-dir))]
      (doseq [{:keys [gate detail time]} approvals]
        (println (str "- " gate ": " detail " (" time ")")))
      (println "- none"))
    (println)
    (println "## Assignments")
    (if-let [assignments (seq (map assignment-row (assignment-dirs root theme-id)))]
      (doseq [{:keys [id story template state result merge accepted-merge review rejection replacement replaces]} assignments]
        (println (str "- " id
                      " [" template "]"
                      " story=" story
                      " state=" state
                      " result=" (if result "yes" "no")
                      " merge=" (or merge "none")
                      " accepted_merge=" (or accepted-merge "none")
                      " review=" (or review "none")
                      " rejection=" (or rejection "none")
                      " replacement=" (or replacement "none")
                      " replaces=" (or replaces "none"))))
      (println "- none"))))

(defn -main [& args]
  (if (= 1 (count args))
    (print-report! (first args))
    (exit! 1 usage-text)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
