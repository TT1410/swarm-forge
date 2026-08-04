#!/usr/bin/env bb

(ns squad-report
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-config :as cfg]
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
  (or (cfg/project-root)
      (exit! 1 "Cannot find SwarmForge project root")))

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

(defn value-or-unknown [value]
  (or value "unknown"))

(defn assignment-row [dir]
  (let [metadata (fs/path dir "metadata")
        status (fs/path dir "status")]
    {:id (or (read-value metadata "assignment_id") (fs/file-name dir))
     :story (value-or-unknown (read-value metadata "story_id"))
     :template (value-or-unknown (read-value metadata "template"))
     :state (value-or-unknown (read-value status "state"))
     :result (fs/exists? (fs/path dir "result"))
     :merge (read-value (fs/path dir "merge") "state")
     :accepted-merge (read-value (fs/path dir "accepted-merge") "state")
     :review (read-value (fs/path dir "review") "state")
     :rejection (read-value (fs/path dir "rejection") "state")
     :replacement (read-value (fs/path dir "replacement") "replacement")
     :replaces (read-value (fs/path dir "replaces") "replaces")}))

(defn print-list [label values]
  (println (str "- " label ": " (if (seq values) (str/join ", " values) "none"))))

(defn print-theme-section! [theme-dir status]
  (println "## Theme")
  (println (str "- State: " (or (read-value status "state") "unknown")))
  (println (str "- Detail: " (or (read-value status "detail") "")))
  (println (str "- Updated: " (or (read-value status "updated_at") "unknown")))
  (print-list "Stories" (ids-in (fs/path theme-dir "stories")))
  (print-list "Acceptance" (ids-in (fs/path theme-dir "acceptance"))))

(defn print-approvals-section! [theme-dir]
  (println "## Approvals")
  (if-let [approvals (seq (approval-rows theme-dir))]
    (doseq [{:keys [gate detail time]} approvals]
      (println (str "- " gate ": " detail " (" time ")")))
    (println "- none")))

(defn yes-no [value]
  (if value "yes" "no"))

(defn value-or-none [value]
  (or value "none"))

(defn assignment-summary-fields [{:keys [story state result merge accepted-merge review rejection replacement replaces]}]
  [["story" story]
   ["state" state]
   ["result" (yes-no result)]
   ["merge" (value-or-none merge)]
   ["accepted_merge" (value-or-none accepted-merge)]
   ["review" (value-or-none review)]
   ["rejection" (value-or-none rejection)]
   ["replacement" (value-or-none replacement)]
   ["replaces" (value-or-none replaces)]])

(defn assignment-summary [{:keys [id template] :as assignment}]
  (str "- " id
       " [" template "] "
       (str/join " "
                 (map (fn [[label value]]
                        (str label "=" value))
                      (assignment-summary-fields assignment)))))

(defn print-assignments-section! [root theme-id]
  (println "## Assignments")
  (if-let [assignments (seq (map assignment-row (assignment-dirs root theme-id)))]
    (doseq [assignment assignments]
      (println (assignment-summary assignment)))
    (println "- none")))

(defn print-report! [theme-id]
  (validate-id! "Theme id" theme-id)
  (let [root (fs/absolutize (project-root))
        theme-dir (fs/path root ".squad" "themes" theme-id)
        status (fs/path theme-dir "status")]
    (when-not (fs/directory? theme-dir)
      (exit! 1 (str "Unknown theme: " theme-id)))
    (println (str "# Squad Report: " theme-id))
    (println)
    (print-theme-section! theme-dir status)
    (println)
    (print-approvals-section! theme-dir)
    (println)
    (print-assignments-section! root theme-id)))

(defn -main [& args]
  (if (= 1 (count args))
    (print-report! (first args))
    (exit! 1 usage-text)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
