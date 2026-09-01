#!/usr/bin/env bb

(ns merge-and-process
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def usage-text
  "Usage: merge_and_process.sh <sender> <commit>")

(defn usage []
  (binding [*out* *err*]
    (println usage-text)))

(defn exit! [status message]
  (binding [*out* *err*]
    (when message
      (println message)))
  (System/exit status))

(defn command [& args]
  (apply sh/sh args))

(defn already-merged? [sha]
  (zero? (:exit (command "git" "merge-base" "--is-ancestor" sha "HEAD"))))

(defn task-md? [path]
  (boolean (re-matches #"tasks/[^/]+\.md" (str/replace (or path "") #"\\+" "/"))))

(defn inbound-has? [sha path]
  (zero? (:exit (command "git" "cat-file" "-e" (str sha ":" path)))))

(defn git-path [name]
  (str/trim (:out (command "git" "rev-parse" "--git-path" name))))

(defn merge-in-progress? []
  (fs/exists? (git-path "MERGE_HEAD")))

(defn unmerged-paths []
  (->> (str/split-lines (:out (command "git" "diff" "--name-only" "--diff-filter=U")))
       (remove str/blank?)))

(defn overwritten-untracked [text]
  (->> (str/split-lines (or text ""))
       (drop-while #(not (re-find #"would be overwritten by merge" %)))
       (drop 1)
       (take-while #(not (str/blank? (str/trim %))))
       (map str/trim)
       (remove #(or (str/blank? %)
                    (re-find #"Please move or remove" %)
                    (re-find #"Aborting" %)))
       vec))

(defn take-inbound! [sha path]
  (let [result (command "git" "checkout" sha "--" path)]
    (when-not (zero? (:exit result))
      (exit! 1 (str/trim (str (:err result) "\n" (:out result)))))
    (command "git" "add" "--" path)))

(defn only-inbound-task-md? [sha paths]
  (and (seq paths)
       (every? task-md? paths)
       (every? #(inbound-has? sha %) paths)))

(defn finish-merge! [sender sha]
  (if (merge-in-progress?)
    (let [result (command "git" "commit" "--no-edit"
                          "-m" (str "Merge " sender " " sha))]
      (when-not (zero? (:exit result))
        (exit! 1 (str/trim (str (:err result) "\n" (:out result))))))
    (let [result (command "git" "merge" "--no-edit"
                          "-m" (str "Merge " sender " " sha) sha)]
      (when-not (zero? (:exit result))
        (exit! 1 (str/trim (str (:err result) "\n" (:out result))))))))

(defn recover-task-md! [sender sha result]
  (let [text (str (:err result) "\n" (:out result))
        overwritten (overwritten-untracked text)
        unmerged (unmerged-paths)
        recoverable (distinct (concat overwritten unmerged))]
    (if (only-inbound-task-md? sha recoverable)
      (do
        (doseq [path recoverable]
          (take-inbound! sha path))
        (finish-merge! sender sha))
      (exit! 1 (str/trim text)))))

(defn merge-commit! [sender sha]
  (when-not (already-merged? sha)
    (let [result (command "git" "merge" "--no-edit"
                          "-m" (str "Merge " sender " " sha) sha)]
      (when-not (zero? (:exit result))
        (recover-task-md! sender sha result))))
  (println "MERGED:" sender sha))

(defn -main [& args]
  (when (some #{"--help" "-h"} args)
    (usage)
    (System/exit 0))
  (when (not= 2 (count args))
    (usage)
    (System/exit 1))
  (merge-commit! (first args) (second args)))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
