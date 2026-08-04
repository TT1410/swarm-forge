#!/usr/bin/env bb

(ns done-with-current-task
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def script-dir (fs/parent *file*))

(defn inbox-dir []
  (fs/path (System/getProperty "user.dir") ".swarmforge" "handoffs" "inbox"))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn handoff-files [dir]
  (if (fs/exists? dir)
    (->> (fs/list-dir dir)
         (filter #(and (fs/regular-file? %) (str/ends-with? (fs/file-name %) ".handoff")))
         (sort-by #(fs/file-name %))
         vec)
    []))

(defn batch-dirs [dir]
  (if (fs/exists? dir)
    (->> (fs/list-dir dir)
         (filter #(and (fs/directory? %) (str/starts-with? (fs/file-name %) "batch_")))
         (sort-by #(fs/file-name %))
         vec)
    []))

(defn finish-header-lines [out inserted? replaced? header-line]
  (cond-> out
    (and (not inserted?) (not replaced?)) (conj header-line)))

(defn insert-header? [inserted? line]
  (and (not inserted?) (str/blank? line)))

(defn replace-header? [inserted? prefix line]
  (and (not inserted?) (str/starts-with? line prefix)))

(defn header-line-action [inserted? prefix line]
  (cond
    (insert-header? inserted? line) :insert
    (replace-header? inserted? prefix line) :replace
    :else :copy))

(defn insert-header-line [header-line {:keys [out replaced?]} line]
  {:out (conj (cond-> out (not replaced?) (conj header-line)) line)
   :inserted? true
   :replaced? replaced?})

(defn replace-header-line [header-line {:keys [out inserted?]}]
  {:out (conj out header-line)
   :inserted? inserted?
   :replaced? true})

(defn copy-header-line [{:keys [out inserted? replaced?]} line]
  {:out (conj out line)
   :inserted? inserted?
   :replaced? replaced?})

(defn update-header-line [prefix header-line {:keys [out inserted? replaced?]} line]
  (case (header-line-action inserted? prefix line)
    :insert (insert-header-line header-line {:out out :inserted? inserted? :replaced? replaced?} line)
    :replace (replace-header-line header-line {:out out :inserted? inserted? :replaced? replaced?})
    :copy (copy-header-line {:out out :inserted? inserted? :replaced? replaced?} line)))

(defn header-lines-with [lines field value]
  (let [prefix (str field ": ")
        header-line (str prefix value)
        state (reduce (partial update-header-line prefix header-line)
                      {:out [] :inserted? false :replaced? false}
                      lines)]
    (finish-header-lines (:out state) (:inserted? state) (:replaced? state) header-line)))

(defn set-header! [file field value]
  (let [tmp (fs/create-temp-file {:dir (fs/parent file) :prefix ".headers."})
        result (header-lines-with (str/split-lines (slurp (str file))) field value)]
    (spit (str tmp) (str (str/join "\n" result) "\n"))
    (fs/move tmp file {:replace-existing true})))

(defn fail! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn run-ready! []
  (process/exec (str (fs/path script-dir "ready_for_next_task.sh"))))

(defn ensure-current-task-state! [in-process-batches in-process-files]
  (when (seq in-process-batches)
    (fail! 2
           "CURRENT_WORK_IS_BATCH: use done_with_current.sh."
           (str/join "\n" (map #(str "- " %) in-process-batches))))
  (when (empty? in-process-files)
    (fail! 1 "NO_CURRENT_TASK"))
  (when (> (count in-process-files) 1)
    (fail! 2
           "AMBIGUOUS_TASK_STATE: multiple tasks are in process."
           (str/join "\n" (map #(str "- " %) in-process-files)))))

(defn complete-task! [source-file completed-dir]
  (let [target-file (fs/path completed-dir (fs/file-name source-file))]
    (set-header! source-file "completed_at" (timestamp))
    (when (fs/exists? target-file)
      (fail! 2 (str "AMBIGUOUS_TASK_STATE: completed file already exists: " target-file)))
    (fs/move source-file target-file)
    target-file))

(defn -main []
  (let [inbox (inbox-dir)
        in-process-dir (fs/path inbox "in_process")
        completed-dir (fs/path inbox "completed")]
    (doseq [dir [in-process-dir completed-dir]]
      (fs/create-dirs dir))
    (let [in-process-batches (batch-dirs in-process-dir)
          in-process-files (handoff-files in-process-dir)]
      (ensure-current-task-state! in-process-batches in-process-files)
      (let [target-file (complete-task! (first in-process-files) completed-dir)]
        (println "COMPLETED:" (str target-file))
        (run-ready!)))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
