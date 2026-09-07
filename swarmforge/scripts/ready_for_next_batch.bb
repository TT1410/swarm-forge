#!/usr/bin/env bb

(ns ready-for-next-batch
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def script-dir (fs/parent *file*))
(try
  (require 'ready-for-next-guard)
  (catch Exception _
    (load-file (str (fs/path script-dir "ready_for_next_guard.bb")))))

(defn inbox-dir []
  (fs/path (System/getProperty "user.dir") ".swarmforge" "handoffs" "inbox"))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn id-timestamp []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'")
           (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)))

(defn current-head []
  (str/trim (:out (sh/sh "git" "rev-parse" "--short=10" "HEAD"))))

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

(defn set-header! [file field value]
  (let [lines (str/split-lines (slurp (str file)))
        prefix (str field ": ")
        tmp (fs/create-temp-file {:dir (fs/parent file) :prefix ".headers."})
        result (loop [remaining lines
                      out []
                      inserted? false
                      replaced? false]
                 (if-let [line (first remaining)]
                   (cond
                     (and (not inserted?) (str/blank? line))
                     (recur (next remaining)
                            (conj (cond-> out (not replaced?) (conj (str prefix value))) line)
                            true
                            replaced?)

                     (and (not inserted?) (str/starts-with? line prefix))
                     (recur (next remaining) (conj out (str prefix value)) inserted? true)

                     :else
                     (recur (next remaining) (conj out line) inserted? replaced?))
                   (cond-> out
                     (and (not inserted?) (not replaced?)) (conj (str prefix value)))))]
    (spit (str tmp) (str (str/join "\n" result) "\n"))
    (fs/move tmp file {:replace-existing true})))

(defn print-task [file]
  (let [task-name (header-field file "task")
        task-id (header-field file "task_id")]
    (println "TASK:" (str file))
    (println "FROM:" (header-value file "from" "unknown"))
    (println "TYPE:" (header-value file "type" "unknown"))
    (println "PRIORITY:" (header-value file "priority" "50"))
    (when task-name
      (println "TASK_NAME:" task-name))
    (when task-id
      (println "TASK_ID:" task-id))
    (ready-for-next-guard/print-card-briefing! file)
    (println "PAYLOAD:")
    (print (body file))))

(def manifest-filename "batch_manifest.edn")

(defn manifest-path [batch-dir]
  (fs/path batch-dir manifest-filename))

(defn read-batch-manifest [batch-dir]
  (let [path (manifest-path batch-dir)]
    (when (fs/regular-file? path)
      (try
        (edn/read-string (slurp (str path)))
        (catch Exception _ ::invalid)))))

(defn short-commit [commit]
  (when-not (str/blank? commit)
    (let [result (sh/sh "git" "rev-parse" "--short=10" commit)]
      (if (zero? (:exit result))
        (str/trim (:out result))
        commit))))

(defn print-batch [batch-dir manifest]
  (let [files (handoff-files batch-dir)]
    (when (empty? files)
      (binding [*out* *err*]
        (println "AMBIGUOUS_TASK_STATE: batch contains no tasks:" (str batch-dir)))
      (System/exit 2))
    (println "BATCH:" (str batch-dir))
    (println "BATCH_ID:" (:batch-id manifest))
    (println "COUNT:" (count files))
    (println "SELECTED_COMMIT:" (or (short-commit (:selected-commit manifest)) "none"))
    (println "ATOMIC_WORK_UNIT: Complete every member as one unit and send one combined handoff.")
    (doseq [[index member] (map-indexed vector (:members manifest))]
      (println "BATCH_MEMBER:" (inc index)
               (:task-id member)
               (or (short-commit (:commit member)) "no-commit")))
    (when-let [name (header-field (first files) "task")]
      (println "TASK_NAME:" name))
    (println "PRIORITY:" (header-value (first files) "priority" "50"))
    (ready-for-next-guard/print-card-briefing! (first files))
    (doseq [[index file] (map-indexed vector files)]
      (println)
      (println "BATCH_ITEM:" (inc index))
      (print-task file))))

(defn fail! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn canonical-commit! [file commit]
  (let [result (sh/sh "git" "rev-parse" "--verify" (str commit "^{commit}"))]
    (if (zero? (:exit result))
      (str/trim (:out result))
      (fail! 2
             (str "INVALID_BATCH_COMMIT: " commit " from " file)
             (str/trim (str (:err result) "\n" (:out result)))))))

(defn commit-ancestor? [ancestor descendant]
  (zero? (:exit (sh/sh "git" "merge-base" "--is-ancestor" ancestor descendant))))

(defn parsed-task-ids [file]
  (let [value (header-field file "batch_task_ids")]
    (when-not (str/blank? value)
      (try
        (let [parsed (edn/read-string value)]
          (when (and (vector? parsed) (every? string? parsed)) parsed))
        (catch Exception _ nil)))))

(defn file-task-ids [file]
  (->> (cons (or (not-empty (header-field file "task_id"))
                 (header-field file "task"))
             (or (parsed-task-ids file) []))
       (remove str/blank?)
       distinct
       vec))

(defn merge-source [file]
  (if (= "git_handoff" (header-field file "type"))
    (let [commit (header-field file "commit")]
      (when (str/blank? commit)
        (fail! 2 (str "INVALID_BATCH_COMMIT: missing commit in " file)))
      {:from (or (not-empty (header-field file "from")) "unknown")
       :commit (canonical-commit! file commit)})
    (when-let [role (ready-for-next-guard/merge-from-role (header-field file "task"))]
      (when-let [commit (ready-for-next-guard/role-head role)]
        {:from role :commit (canonical-commit! file commit)}))))

(defn batch-member [file]
  (let [source (merge-source file)
        task-ids (file-task-ids file)]
    {:file (fs/file-name file)
     :handoff-id (header-field file "id")
     :task (header-field file "task")
     :task-id (first task-ids)
     :task-ids task-ids
     :from (header-field file "from")
     :type (header-field file "type")
     :commit (:commit source)
     :merge-from (:from source)}))

(defn complete-commit [members]
  (let [commits (->> members (keep :commit) distinct vec)]
    (when (seq commits)
      (let [complete (filterv (fn [candidate]
                                (every? #(commit-ancestor? % candidate) commits))
                              commits)]
        (when-not (= 1 (count complete))
          (apply fail! 2
                 "NON_LINEAR_BATCH: no single incoming commit contains every batch member."
                 (for [member members
                       :when (:commit member)]
                   (format "- %s %s" (:task-id member) (short-commit (:commit member)))))
          nil)
        (first complete)))))

(defn build-batch-manifest [batch-id files]
  (let [members (mapv batch-member files)
        selected (complete-commit members)
        selected-member (some #(when (= selected (:commit %)) %) members)]
    {:version 1
     :batch-id batch-id
     :created-at (timestamp)
     :task-ids (->> members (mapcat :task-ids) distinct vec)
     :members members
     :selected-commit selected
     :selected-from (:merge-from selected-member)}))

(defn write-batch-manifest! [batch-dir manifest]
  (let [path (manifest-path batch-dir)
        tmp (fs/create-temp-file {:dir batch-dir :prefix ".batch-manifest."})]
    (spit (str tmp) (str (pr-str manifest) "\n"))
    (fs/move tmp path {:replace-existing true :atomic-move true})))

(defn valid-manifest? [batch-dir manifest]
  (let [files (mapv fs/file-name (handoff-files batch-dir))
        members (:members manifest)
        commits (->> members (keep :commit) distinct vec)
        task-ids (->> members (mapcat :task-ids) distinct vec)
        selected (:selected-commit manifest)]
    (and (map? manifest)
         (= 1 (:version manifest))
         (= (fs/file-name batch-dir) (:batch-id manifest))
         (vector? members)
         (every? map? members)
         (= files (mapv :file members))
         (vector? (:task-ids manifest))
         (= task-ids (:task-ids manifest))
         (or (and (empty? commits) (nil? selected))
             (and (not (str/blank? selected))
                  (contains? (set commits) selected)
                  (every? #(commit-ancestor? % selected) commits))))))

(defn ensure-batch-manifest! [batch-dir]
  (let [stored (read-batch-manifest batch-dir)]
    (cond
      (= ::invalid stored)
      (fail! 2 (str "INVALID_BATCH_MANIFEST: " (manifest-path batch-dir)))

      stored
      (if (valid-manifest? batch-dir stored)
        stored
        (fail! 2 (str "INVALID_BATCH_MANIFEST: does not match batch " batch-dir)))

      :else
      (let [manifest (build-batch-manifest (fs/file-name batch-dir)
                                           (handoff-files batch-dir))]
        (write-batch-manifest! batch-dir manifest)
        manifest))))

(defn merge-selected! [manifest]
  (when-let [commit (:selected-commit manifest)]
    (let [result (sh/sh (str (fs/path script-dir "merge_and_process.sh"))
                           (or (:selected-from manifest) "unknown") commit)]
      (when-not (zero? (:exit result))
        (fail! 1 (str/trim (str (:err result) "\n" (:out result))))))))

(defn process-batch! [batch-dir manifest]
  ;; The declaration is deliberately emitted before merge so it survives and
  ;; identifies the same atomic work unit on conflict and resume.
  (print-batch batch-dir manifest)
  (flush)
  (merge-selected! manifest)
  (doseq [file (handoff-files batch-dir)]
    (ready-for-next-guard/ensure-task-document-committed! file)))

(defn new-batch-dir [in-process-dir]
  (loop [suffix 1]
    (let [dir (fs/path in-process-dir (format "batch_%s_%06d" (id-timestamp) suffix))]
      (if (fs/exists? dir)
        (recur (inc suffix))
        dir))))

(defn reverse-mail? [file]
  (or (= "true" (header-field file "non-forwarding"))
      (= "00" (header-value file "priority" "50"))))

(defn batch-card-type [file]
  (or (not-empty (header-field file "card_type")) ""))

(defn batch-key [file]
  [(header-value file "priority" "50")
   (batch-card-type file)
   (boolean (reverse-mail? file))])

(defn select-batch-files [new-files]
  (when-let [first-file (first new-files)]
    (let [key (batch-key first-file)]
      (filterv #(= key (batch-key %)) new-files))))

(defn -main []
  (let [inbox (inbox-dir)
        new-dir (fs/path inbox "new")
        in-process-dir (fs/path inbox "in_process")
        completed-dir (fs/path inbox "completed")]
    (doseq [dir [new-dir in-process-dir completed-dir]]
      (fs/create-dirs dir))
    (let [in-process-batches (batch-dirs in-process-dir)
          in-process-files (handoff-files in-process-dir)]
      (when (seq in-process-files)
        (fail! 2
               "TASK_IN_PROCESS_IS_SINGLE: use ready_for_next.sh or done_with_current.sh."
               (str/join "\n" (map #(str "- " %) in-process-files))))
      (when (> (count in-process-batches) 1)
        (fail! 2
               "AMBIGUOUS_TASK_STATE: multiple batches are already in process."
               (str/join "\n" (map #(str "- " %) in-process-batches))))
      (if (= 1 (count in-process-batches))
        (let [batch-dir (first in-process-batches)]
          (process-batch! batch-dir (ensure-batch-manifest! batch-dir)))
        (let [new-files (handoff-files new-dir)]
          (when-let [active (seq (ready-for-next-guard/active-outbound-git-files
                                  (ready-for-next-guard/current-role)))]
            (apply fail! 2 (ready-for-next-guard/wait-message active)))
          (if (empty? new-files)
            (println "NO_TASK")
            (let [selected-files (select-batch-files new-files)
                  batch-dir (new-batch-dir in-process-dir)
                  batch-id (fs/file-name batch-dir)
                  manifest (build-batch-manifest batch-id selected-files)]
              (fs/create-dir batch-dir)
              (doseq [source-file selected-files]
                (let [target-file (fs/path batch-dir (fs/file-name source-file))]
                  (when (fs/exists? target-file)
                    (fail! 2 (str "AMBIGUOUS_TASK_STATE: target batch file already exists: " target-file)))
                  (fs/move source-file target-file)
                  (set-header! target-file "dequeued_at" (timestamp))
                  (set-header! target-file "task_base_commit" (current-head))))
              (when (empty? selected-files)
                (fail! 2 "AMBIGUOUS_TASK_STATE: no tasks selected for batch."))
              (write-batch-manifest! batch-dir manifest)
              (process-batch! batch-dir manifest))))))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (-main))
