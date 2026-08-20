#!/usr/bin/env bb

(ns squad-batch
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-config :as cfg]
            [squad-state :as squad-state]
            [clojure.string :as str]))

(def script-dir (fs/parent *file*))

(def usage-text
  (str "Usage:\n"
       "  squad_batch.sh create <batch-kind> <batch-id>\n"
       "  squad_batch.sh add <batch-id> <story-id> <stage> <assignment-id> <branch> <sha>\n"
       "  squad_batch.sh result <batch-id> <assignment-id> <branch> <sha>\n"
       "  squad_batch.sh close <batch-id>\n"
       "  squad_batch.sh complete <batch-id>\n"
       "  squad_batch.sh status <batch-id>\n"
       "  squad_batch.sh ready <batch-kind>"))

(def valid-id #"[A-Za-z0-9][A-Za-z0-9._-]*")
(def valid-kinds #{"hardener" "qa" "architecture" "architecture-fix"})

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

(defn validate-kind! [kind]
  (when-not (contains? valid-kinds kind)
    (exit! 2 "Batch kind must be hardener, qa, architecture, or architecture-fix.")))

(defn validate-sha! [sha]
  (when-not (re-matches #"[0-9a-fA-F]{7,40}" sha)
    (exit! 2 "SHA must be a git commit abbreviation or full SHA.")))

(defn write-atomic! [file content]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                  :prefix (str "." (fs/file-name file) ".")})]
    (spit (str tmp) content)
    (fs/move tmp file {:replace-existing true})))

(defn write-batch-status! [dir batch-id kind state]
  (let [content (str "batch_id: " batch-id "\n"
                     "kind: " kind "\n"
                     "state: " state "\n"
                     "updated_at: " (timestamp) "\n")]
    (write-atomic! (fs/path dir "state") content)
    (write-atomic! (fs/path dir "status") content)))

(defn append-line! [file line]
  (fs/create-dirs (fs/parent file))
  (spit (str file) (str line "\n") :append true))

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(defn batch-dir [root batch-id]
  (fs/path root ".squad" "batches" batch-id))

(defn story-dir [root story-id]
  (fs/path root ".squad" "stories" story-id))

(defn ensure-batch! [dir batch-id]
  (when-not (fs/directory? dir)
    (exit! 1 (str "Unknown batch: " batch-id))))

(defn create-batch! [kind batch-id]
  (validate-kind! kind)
  (validate-id! "Batch id" batch-id)
  (let [root (fs/absolutize (project-root))
        dir (batch-dir root batch-id)
        now (timestamp)]
    (when (fs/exists? dir)
      (exit! 2 (str "Batch already exists: " batch-id)))
    (fs/create-dirs dir)
    (write-atomic! (fs/path dir "metadata")
                   (str "batch_id: " batch-id "\n"
                        "kind: " kind "\n"
                        "created_at: " now "\n"))
    (write-batch-status! dir batch-id kind "open")
    (write-atomic! (fs/path dir "manifest.tsv")
                   "story_id\tstage\tassignment_id\tbranch\tsha\tadded_at\n")
    (write-atomic! (fs/path dir "manifest")
                   "story_id\tstage\tassignment_id\tbranch\tsha\tadded_at\n")
    (println "SQUAD_BATCH:" batch-id)
    (println "KIND:" kind)
    (println "STATE: open")
    (println "MANIFEST:" (str (fs/path dir "manifest.tsv")))))

(defn active-batch-file [root story-id kind]
  (fs/path root ".squad" "stories" story-id "active-batches" kind))

(defn validate-add-story-args! [batch-id story-id stage assignment-id branch sha]
  (doseq [[kind value] [["Batch id" batch-id]
                        ["Story id" story-id]
                        ["Stage" stage]
                        ["Assignment id" assignment-id]]]
    (validate-id! kind value))
  (when (str/blank? branch)
    (exit! 2 "Branch may not be blank."))
  (validate-sha! sha))

(defn active-batch-id [active]
  (when (fs/exists? active)
    (str/trim (slurp (str active)))))

(defn conflicting-active-batch? [root batch-id active-batch-id]
  (and active-batch-id
       (not= batch-id active-batch-id)
       (squad-state/active-batch? root active-batch-id)))

(defn ensure-no-active-batch-conflict! [root batch-id story-id kind]
  (let [active-id (active-batch-id (active-batch-file root story-id kind))]
    (when (conflicting-active-batch? root batch-id active-id)
      (exit! 3
             (str "Story " story-id " is already in active " kind " batch " active-id)))))

(defn append-batch-story! [root dir batch-id story-id stage assignment-id branch sha kind now]
  (append-line! (fs/path dir "manifest.tsv")
                (str/join "\t" [story-id stage assignment-id branch sha now]))
  (append-line! (fs/path dir "manifest")
                (str/join "\t" [story-id stage assignment-id branch sha now]))
  (append-line! (fs/path dir "events.log")
                (str now "\tstory_added\t" story-id "\t" stage "\t" assignment-id "\t" branch "\t" sha))
  (let [sdir (story-dir root story-id)]
    (fs/create-dirs (fs/path sdir "active-batches"))
    (write-atomic! (active-batch-file root story-id kind) batch-id)
    (append-line! (fs/path sdir "batches.tsv")
                  (str/join "\t" [now kind batch-id stage assignment-id branch sha]))))

(defn print-story-added! [batch-id kind story-id]
  (println "SQUAD_BATCH:" batch-id)
  (println "KIND:" kind)
  (println "STORY:" story-id)
  (println "STATE: story_added"))

(defn batch-admission-state [dir]
  (or (read-value (fs/path dir "status") "state")
      (read-value (fs/path dir "state") "state")
      "unknown"))

(defn ensure-batch-open-for-membership! [dir batch-id]
  (let [state (batch-admission-state dir)]
    (when-not (= "open" state)
      (exit! 2 (str "Batch " batch-id " is not open for new members (state: " state ").")))))

(defn add-story! [batch-id story-id stage assignment-id branch sha]
  (validate-add-story-args! batch-id story-id stage assignment-id branch sha)
  (let [root (fs/absolutize (project-root))
        dir (batch-dir root batch-id)
        metadata (fs/path dir "metadata")
        kind (read-value metadata "kind")
        now (timestamp)]
    (ensure-batch! dir batch-id)
    (validate-kind! kind)
    (ensure-batch-open-for-membership! dir batch-id)
    (ensure-no-active-batch-conflict! root batch-id story-id kind)
    (append-batch-story! root dir batch-id story-id stage assignment-id branch sha kind now)
    (print-story-added! batch-id kind story-id)))

(defn result! [batch-id assignment-id branch sha]
  (doseq [[kind value] [["Batch id" batch-id]
                        ["Assignment id" assignment-id]]]
    (validate-id! kind value))
  (when (str/blank? branch)
    (exit! 2 "Branch may not be blank."))
  (validate-sha! sha)
  (let [root (fs/absolutize (project-root))
        dir (batch-dir root batch-id)
        kind (read-value (fs/path dir "metadata") "kind")
        now (timestamp)]
    (ensure-batch! dir batch-id)
    (write-atomic! (fs/path dir "result")
                   (str "batch_id: " batch-id "\n"
                        "kind: " kind "\n"
                        "assignment_id: " assignment-id "\n"
                        "branch: " branch "\n"
                        "sha: " sha "\n"
                        "received_at: " now "\n"))
    (write-batch-status! dir batch-id kind "result_received")
    (append-line! (fs/path dir "events.log")
                  (str now "\tresult_received\t" assignment-id "\t" branch "\t" sha))
    (println "SQUAD_BATCH:" batch-id)
    (println "KIND:" kind)
    (println "STATE: result_received")
    (println "ASSIGNMENT:" assignment-id)
    (println "BRANCH:" branch)
    (println "SHA:" sha)))

(defn close! [batch-id]
  "Close batch to new membership once its assignment is created/spawned."
  (validate-id! "Batch id" batch-id)
  (let [root (fs/absolutize (project-root))
        dir (batch-dir root batch-id)
        kind (read-value (fs/path dir "metadata") "kind")
        state (batch-admission-state dir)
        now (timestamp)]
    (ensure-batch! dir batch-id)
    (when-not (contains? #{"open" "closed"} state)
      (exit! 2 (str "Batch " batch-id " cannot be closed from state: " state)))
    (write-batch-status! dir batch-id kind "closed")
    (append-line! (fs/path dir "events.log")
                  (str now "\tclosed"))
    (println "SQUAD_BATCH:" batch-id)
    (println "KIND:" kind)
    (println "STATE: closed")))

(defn complete! [batch-id]
  (validate-id! "Batch id" batch-id)
  (let [root (fs/absolutize (project-root))
        dir (batch-dir root batch-id)
        kind (read-value (fs/path dir "metadata") "kind")
        now (timestamp)]
    (ensure-batch! dir batch-id)
    (write-batch-status! dir batch-id kind "complete")
    (append-line! (fs/path dir "events.log")
                  (str now "\tcomplete"))
    (println "SQUAD_BATCH:" batch-id)
    (println "KIND:" kind)
    (println "STATE: complete")))

(defn batch-status-file [dir]
  (let [status-file (fs/path dir "status")]
    (if (fs/exists? status-file) status-file (fs/path dir "state"))))

(defn manifest-story-count [manifest]
  (max 0 (dec (count (str/split-lines (slurp (str manifest)))))))

(defn batch-result-path [dir]
  (let [result (fs/path dir "result")]
    (if (fs/exists? result) (str result) "none")))

(defn batch-status-fields [batch-id metadata state-file manifest dir]
  [["BATCH" batch-id]
   ["KIND" (or (read-value metadata "kind") "unknown")]
   ["STATE" (or (read-value state-file "state") "unknown")]
   ["STORIES" (manifest-story-count manifest)]
   ["MANIFEST" (str manifest)]
   ["RESULT" (batch-result-path dir)]])

(defn status! [batch-id]
  (validate-id! "Batch id" batch-id)
  (let [root (fs/absolutize (project-root))
        dir (batch-dir root batch-id)
        metadata (fs/path dir "metadata")
        state-file (batch-status-file dir)
        manifest (fs/path dir "manifest.tsv")]
    (ensure-batch! dir batch-id)
    (doseq [[label value] (batch-status-fields batch-id metadata state-file manifest dir)]
      (println (str label ":") value))))

(defn ready! [kind]
  (validate-kind! kind)
  (let [root (fs/absolutize (project-root))
        batches-dir (fs/path root ".squad" "batches")]
    (doseq [dir (if (fs/exists? batches-dir) (fs/list-dir batches-dir) [])
            :let [metadata (fs/path dir "metadata")
                  state (fs/path dir "state")]
            :when (and (= kind (read-value metadata "kind"))
                       (= "open" (read-value state "state")))]
      (println "BATCH:" (fs/file-name dir))
      (println "MANIFEST:" (str (fs/path dir "manifest.tsv"))))))

(defn exact-count! [args n]
  (when-not (= n (count args))
    (exit! 1 usage-text)))

(def batch-commands
  {"create" (fn [args]
              (exact-count! args 3)
              (create-batch! (second args) (nth args 2)))
   "add" (fn [args]
           (exact-count! args 7)
           (add-story! (second args) (nth args 2) (nth args 3) (nth args 4) (nth args 5) (nth args 6)))
   "result" (fn [args]
              (exact-count! args 5)
              (result! (second args) (nth args 2) (nth args 3) (nth args 4)))
   "close" (fn [args]
             (exact-count! args 2)
             (close! (second args)))
   "complete" (fn [args]
                (exact-count! args 2)
                (complete! (second args)))
   "status" (fn [args]
              (exact-count! args 2)
              (status! (second args)))
   "ready" (fn [args]
             (exact-count! args 2)
             (ready! (second args)))})

(defn -main [& args]
  (if-let [command (batch-commands (first args))]
    (command args)
    (exit! 1 usage-text)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
