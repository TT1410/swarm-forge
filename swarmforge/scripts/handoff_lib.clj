#!/usr/bin/env bb

(ns handoff-lib
  (:require [babashka.fs :as fs]
            [babashka.process]
            [squad-config :as cfg]
            [clojure.string :as str]))

(defn role []
  (or (System/getenv "SWARMFORGE_ROLE")
      (throw (ex-info "Set SWARMFORGE_ROLE." {:exit 1}))))

(defn state-dir []
  (fs/path (System/getProperty "user.dir") ".swarmforge" "handoffs"))

(defn inbox-dir []
  (fs/path (state-dir) "inbox"))

(defn project-root []
  (or (cfg/project-root)
      (throw (ex-info "Cannot find SwarmForge project root" {:exit 1}))))

(defn roles-file []
  (fs/path (project-root) ".swarmforge" "roles.tsv"))

(defn role-rows []
  (->> (str/split-lines (slurp (str (roles-file))))
       (map #(str/split % #"\t" -1))))

(defn role-row [role-name]
  (or (some #(when (= role-name (first %)) %) (role-rows))
      (throw (ex-info (str "Unknown role: " role-name) {:exit 1}))))

(defn role-known? [role-name]
  (boolean (some #(= role-name (first %)) (role-rows))))

(defn role-worktree-name [role-name]
  (second (role-row role-name)))

(defn role-receive-mode [role-name]
  (let [mode (nth (role-row role-name) 6 "")]
    (if (str/blank? mode) "task" mode)))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn id-timestamp []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'")
           (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)))

(defn valid-priority? [value]
  (boolean (re-matches #"[0-9][0-9]" value)))

(defn header-field [file field]
  (let [prefix (str field ": ")]
    (some (fn [line]
            (when (str/starts-with? line prefix)
              (subs line (count prefix))))
          (take-while (complement str/blank?)
                      (str/split-lines (slurp (str file)))))))

(defn body [file]
  (let [[_ body] (str/split (slurp (str file)) #"\n\n" 2)]
    (or body "")))

(defn insert-before-body [out replaced? header-line blank-line]
  (conj (cond-> out (not replaced?) (conj header-line)) blank-line))

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
  {:out (insert-before-body out replaced? header-line line)
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

(defn update-header-lines [lines field value]
  (let [prefix (str field ": ")
        header-line (str prefix value)
        state (reduce (partial update-header-line prefix header-line)
                      {:out [] :inserted? false :replaced? false}
                      lines)]
    (finish-header-lines (:out state) (:inserted? state) (:replaced? state) header-line)))

(defn set-header! [file field value]
  (let [file (fs/path file)
        lines (str/split-lines (slurp (str file)))
        tmp (fs/create-temp-file {:dir (fs/parent file) :prefix ".headers."})
        result (update-header-lines lines field value)]
    (spit (str tmp) (str (str/join "\n" result) "\n"))
    (fs/move tmp file {:replace-existing true})))

(defn header-value [file field default]
  (or (header-field file field) default))

(defn task-fields [file]
  [["TASK" (str file)]
   ["FROM" (header-value file "from" "unknown")]
   ["TYPE" (header-value file "type" "unknown")]
   ["PRIORITY" (header-value file "priority" "50")]])

(defn print-task-name! [file]
  (when-let [task-name (header-field file "task")]
    (println "TASK_NAME:" task-name)))

(defn print-task [file]
  (doseq [[label value] (task-fields file)]
    (println (str label ":") value))
  (print-task-name! file)
  (println "PAYLOAD:")
  (print (body file)))

(defn handoff-files [dir]
  (if (fs/exists? dir)
    (->> (fs/list-dir dir)
         (filter #(and (fs/regular-file? %) (str/ends-with? (fs/file-name %) ".handoff")))
         (sort-by #(fs/file-name %))
         vec)
    []))

(defn print-batch [batch-dir]
  (let [files (handoff-files batch-dir)]
    (when (empty? files)
      (throw (ex-info (str "AMBIGUOUS_TASK_STATE: batch contains no tasks: " batch-dir) {:exit 2})))
    (println "BATCH:" (str batch-dir))
    (println "COUNT:" (count files))
    (println "PRIORITY:" (or (header-field (first files) "priority") "50"))
    (doseq [[index file] (map-indexed vector files)]
      (println)
      (println "BATCH_ITEM:" (inc index))
      (print-task file))))

(defn acquire-sequence-lock! [lock-dir]
  (loop []
    (when-not (try (fs/create-dir lock-dir) true (catch Exception _ false))
      (Thread/sleep 50)
      (recur))))

(defn read-sequence-value [seq-file]
  (let [last-value (if (fs/exists? seq-file)
                     (str/trim (slurp (str seq-file)))
                     "0")]
    (if (re-matches #"[0-9]+" last-value)
      (Long/parseLong last-value)
      0)))

(defn write-next-sequence! [seq-file]
  (let [next-number (inc (read-sequence-value seq-file))]
    (spit (str seq-file) (format "%06d\n" next-number))
    (format "%06d" next-number)))

(defn next-sequence []
  (let [dir (state-dir)
        seq-file (fs/path dir "sequence")
        lock-dir (fs/path dir "sequence.lock")]
    (fs/create-dirs dir)
    (acquire-sequence-lock! lock-dir)
    (try
      (write-next-sequence! seq-file)
      (finally
        (fs/delete-tree lock-dir)))))

(defn exit-for-bool! [value]
  (System/exit (if value 0 1)))

(defn print-header-field! [file field]
  (if-let [value (header-field file field)]
    (println value)
    (System/exit 1)))

(def commands
  {"role" (fn [_] (println (role)))
   "state-dir" (fn [_] (println (state-dir)))
   "inbox-dir" (fn [_] (println (inbox-dir)))
   "project-root" (fn [_] (println (project-root)))
   "role-known" #(exit-for-bool! (role-known? (first %)))
   "role-worktree-name" #(println (role-worktree-name (first %)))
   "role-receive-mode" #(println (role-receive-mode (first %)))
   "timestamp" (fn [_] (println (timestamp)))
   "id-timestamp" (fn [_] (println (id-timestamp)))
   "valid-priority" #(exit-for-bool! (valid-priority? (first %)))
   "header-field" #(print-header-field! (first %) (second %))
   "body" #(print (body (first %)))
   "set-header" #(set-header! (first %) (second %) (nth % 2))
   "print-task" #(print-task (first %))
   "print-batch" #(print-batch (first %))
   "next-sequence" (fn [_] (println (next-sequence)))})

(defn usage! []
  (binding [*out* *err*]
    (println "Usage: handoff_lib.clj <command> [args...]"))
  (System/exit 2))

(defn run-command! [args]
  (if-let [command (commands (first args))]
    (command (rest args))
    (usage!)))

(defn -main [& args]
  (try
    (run-command! args)
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println (ex-message e)))
      (System/exit (or (:exit (ex-data e)) 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
