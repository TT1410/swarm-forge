#!/usr/bin/env bb

(ns ready-for-next-task
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn state-dir []
  (fs/path (System/getProperty "user.dir") ".swarmforge" "handoffs"))

(defn inbox-dir []
  (fs/path (state-dir) "inbox"))

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

(defn header-field [file field]
  (let [prefix (str field ": ")]
    (some (fn [line]
            (when (str/starts-with? line prefix)
              (subs line (count prefix))))
          (take-while (complement str/blank?) (str/split-lines (slurp (str file)))))))

(defn header-value [file field default]
  (or (header-field file field) default))

(defn body [file]
  (let [[_ body] (str/split (slurp (str file)) #"\n\n" 2)]
    (or body "")))

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

(defn print-task [file]
  (let [task-name (header-field file "task")]
    (println "TASK:" (str file))
    (println "FROM:" (header-value file "from" "unknown"))
    (println "TYPE:" (header-value file "type" "unknown"))
    (println "PRIORITY:" (header-value file "priority" "50"))
    (when task-name
      (println "TASK_NAME:" task-name))
    (println "PAYLOAD:")
    (print (body file))))

(defn fail! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn ensure-task-dirs! [dirs]
  (doseq [dir dirs]
    (fs/create-dirs dir)))

(defn ensure-no-batch-in-process! [in-process-batches]
  (when (seq in-process-batches)
    (fail! 2
           "TASK_IN_PROCESS_IS_BATCH: use ready_for_next.sh or done_with_current.sh."
           (str/join "\n" (map #(str "- " %) in-process-batches)))))

(defn ensure-unambiguous-task! [in-process-files]
  (when (> (count in-process-files) 1)
    (fail! 2
           "AMBIGUOUS_TASK_STATE: multiple tasks are already in process."
           (str/join "\n" (map #(str "- " %) in-process-files)))))

(defn move-new-task! [source-file in-process-dir]
  (let [target-file (fs/path in-process-dir (fs/file-name source-file))]
    (when (fs/exists? target-file)
      (fail! 2 (str "AMBIGUOUS_TASK_STATE: target in-process file already exists: " target-file)))
    (fs/move source-file target-file)
    (set-header! target-file "dequeued_at" (timestamp))
    target-file))

(defn next-task! [new-dir in-process-dir in-process-files]
  (if (= 1 (count in-process-files))
    (first in-process-files)
    (when-let [source-file (first (handoff-files new-dir))]
      (move-new-task! source-file in-process-dir))))

(defn -main []
  (let [inbox (inbox-dir)
        new-dir (fs/path inbox "new")
        in-process-dir (fs/path inbox "in_process")
        completed-dir (fs/path inbox "completed")]
    (ensure-task-dirs! [new-dir in-process-dir completed-dir])
    (let [in-process-batches (batch-dirs in-process-dir)
          in-process-files (handoff-files in-process-dir)]
      (ensure-no-batch-in-process! in-process-batches)
      (ensure-unambiguous-task! in-process-files)
      (if-let [task-file (next-task! new-dir in-process-dir in-process-files)]
        (print-task task-file)
        (println "NO_TASK")))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
