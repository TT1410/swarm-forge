#!/usr/bin/env bb

(ns commit-msg-hook
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(defn git-toplevel []
  (let [result (sh/sh "git" "rev-parse" "--show-toplevel")]
    (when (zero? (:exit result))
      (str/trim (:out result)))))

(defn same-path? [a b]
  (try
    (= (str (fs/canonicalize a)) (str (fs/canonicalize b)))
    (catch Exception _
      (= (str a) (str b)))))

(defn roles-file []
  (when-let [root (git-toplevel)]
    (let [direct (fs/path root ".swarmforge" "roles.tsv")]
      (if (fs/exists? direct)
        direct
        (let [common (sh/sh "git" "rev-parse" "--git-common-dir")
              common-path (when (zero? (:exit common))
                            (let [path (fs/path (str/trim (:out common)))]
                              (if (fs/absolute? path) path (fs/absolutize path))))
              candidate (some-> common-path fs/parent (fs/path ".swarmforge" "roles.tsv"))]
          (when (and candidate (fs/exists? candidate))
            candidate))))))

(defn infer-role []
  (when-let [file (roles-file)]
    (let [here (or (git-toplevel) (str (fs/cwd)))]
      (some (fn [line]
              (let [cols (str/split line #"\t")
                    role (first cols)
                    wt (when (>= (count cols) 3) (nth cols 2))]
                (when (and (not-empty role) (not-empty wt) (same-path? wt here))
                  role)))
            (str/split-lines (slurp (str file)))))))

(defn role []
  (or (not-empty (System/getenv "SWARMFORGE_ROLE"))
      (infer-role)))

(defn byline [role-name]
  (str "By " role-name "."))

(defn append-byline [text role-name]
  (str (str/trimr text) "\n\n" (byline role-name) "\n"))

(defn merging? []
  (zero? (:exit (sh/sh "git" "rev-parse" "-q" "--verify" "MERGE_HEAD"))))

(defn config-file? [path]
  (boolean (re-find #"(?i)\.(ya?ml|json)$" path)))

(defn staged-config-files []
  (let [result (sh/sh "git" "diff" "--cached" "--name-only" "--diff-filter=ACMR" "HEAD")]
    (if (zero? (:exit result))
      (filterv config-file? (remove str/blank? (str/split-lines (:out result))))
      [])))

(defn parse-config-text! [path text]
  (if (re-find #"(?i)\.json$" path)
    (json/parse-string text)
    (dorun (yaml/parse-string text :load-all true :unknown-tag-fn :value))))

(defn config-error [path]
  (let [result (sh/sh "git" "show" (str ":" path))]
    (when (zero? (:exit result))
      (try
        (parse-config-text! path (:out result))
        nil
        (catch Exception e
          {:path path
           :message (str/trim (str (or (ex-message e) e)))})))))

(defn conf-merge-checks []
  (when-let [file (roles-file)]
    (let [conf (fs/path (fs/parent (fs/parent file)) "swarmforge" "swarmforge.conf")]
      (when (fs/regular-file? conf)
        (->> (str/split-lines (slurp (str conf)))
             (map str/trim)
             (keep #(second (re-matches #"merge-check\s+(.+)" %)))
             vec)))))

(defn check-error [command]
  (let [result (sh/sh "sh" "-c" command :dir (or (git-toplevel) "."))]
    (when-not (zero? (:exit result))
      {:command command
       :output (str/trim (str (:out result) (:err result)))})))

(defn refuse-merge! [lines]
  (binding [*out* *err*]
    (println "MERGE_CHECK_FAILED: the merge commit was refused.")
    (doseq [line lines]
      (println line))
    (println "Compare the merge result with each parent:")
    (println "  git diff --cached HEAD -- <file>")
    (println "  git diff --cached MERGE_HEAD -- <file>")
    (println "Fix the file, git add it, and commit again. Then review with: git show --cc HEAD"))
  (System/exit 1))

(defn check-merge!
  "Merge commits only: staged YAML/JSON must still parse, and every
  `merge-check <command>` line in swarmforge.conf must pass."
  []
  (when (merging?)
    (let [parse-errors (keep config-error (staged-config-files))
          check-errors (keep check-error (conf-merge-checks))]
      (when (or (seq parse-errors) (seq check-errors))
        (refuse-merge!
         (concat
          (for [{:keys [path message]} parse-errors]
            (str "- " path " no longer parses:\n"
                 (str/join "\n" (map #(str "    " %) (str/split-lines message)))))
          (for [{:keys [command output]} check-errors]
            (str "- merge-check failed: " command
                 (when-not (str/blank? output) (str "\n" output))))))))))

(defn -main [& args]
  (when-not (= 1 (count args))
    (System/exit 0))
  (check-merge!)
  (when-let [role-name (role)]
    (let [msg-file (first args)
          text (slurp msg-file)]
      (when-not (str/includes? text (byline role-name))
        (spit msg-file (append-byline text role-name)))))
  (System/exit 0))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
