#!/usr/bin/env bb

(ns squad-batch
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def usage-text
  (str "Usage:\n"
       "  squad_batch.sh create <batch-kind> <batch-id>\n"
       "  squad_batch.sh add <batch-id> <story-id> <stage> <assignment-id> <branch> <sha>\n"
       "  squad_batch.sh result <batch-id> <assignment-id> <branch> <sha>\n"
       "  squad_batch.sh status <batch-id>\n"
       "  squad_batch.sh ready <batch-kind>"))

(def valid-id #"[A-Za-z0-9][A-Za-z0-9._-]*")
(def valid-kinds #{"hardener" "qa" "architecture"})

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
    (cond
      (and configured (fs/exists? configured-roles)) (fs/path configured)
      (fs/exists? direct) cwd
      :else (let [git-root (str/trim (:out (sh-continue "git" "rev-parse" "--show-toplevel")))]
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

(defn validate-kind! [kind]
  (when-not (contains? valid-kinds kind)
    (exit! 2 "Batch kind must be hardener, qa, or architecture.")))

(defn validate-sha! [sha]
  (when-not (re-matches #"[0-9a-fA-F]{7,40}" sha)
    (exit! 2 "SHA must be a git commit abbreviation or full SHA.")))

(defn write-atomic! [file content]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                  :prefix (str "." (fs/file-name file) ".")})]
    (spit (str tmp) content)
    (fs/move tmp file {:replace-existing true})))

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
    (write-atomic! (fs/path dir "state")
                   (str "batch_id: " batch-id "\n"
                        "kind: " kind "\n"
                        "state: open\n"
                        "updated_at: " now "\n"))
    (write-atomic! (fs/path dir "manifest.tsv")
                   "story_id\tstage\tassignment_id\tbranch\tsha\tadded_at\n")
    (println "SQUAD_BATCH:" batch-id)
    (println "KIND:" kind)
    (println "STATE: open")
    (println "MANIFEST:" (str (fs/path dir "manifest.tsv")))))

(defn active-batch-file [root story-id kind]
  (fs/path root ".squad" "stories" story-id "active-batches" kind))

(defn add-story! [batch-id story-id stage assignment-id branch sha]
  (doseq [[kind value] [["Batch id" batch-id]
                        ["Story id" story-id]
                        ["Stage" stage]
                        ["Assignment id" assignment-id]]]
    (validate-id! kind value))
  (when (str/blank? branch)
    (exit! 2 "Branch may not be blank."))
  (validate-sha! sha)
  (let [root (fs/absolutize (project-root))
        dir (batch-dir root batch-id)
        metadata (fs/path dir "metadata")
        kind (read-value metadata "kind")
        now (timestamp)]
    (ensure-batch! dir batch-id)
    (validate-kind! kind)
    (let [active (active-batch-file root story-id kind)]
      (when (and (fs/exists? active)
                 (not= batch-id (str/trim (slurp (str active)))))
        (exit! 3
               (str "Story " story-id " is already in active " kind " batch "
                    (str/trim (slurp (str active)))))))
    (append-line! (fs/path dir "manifest.tsv")
                  (str/join "\t" [story-id stage assignment-id branch sha now]))
    (append-line! (fs/path dir "events.log")
                  (str now "\tstory_added\t" story-id "\t" stage "\t" assignment-id "\t" branch "\t" sha))
    (let [sdir (story-dir root story-id)]
      (fs/create-dirs (fs/path sdir "active-batches"))
      (write-atomic! (active-batch-file root story-id kind) batch-id)
      (append-line! (fs/path sdir "batches.tsv")
                    (str/join "\t" [now kind batch-id stage assignment-id branch sha])))
    (println "SQUAD_BATCH:" batch-id)
    (println "KIND:" kind)
    (println "STORY:" story-id)
    (println "STATE: story_added")))

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
    (write-atomic! (fs/path dir "state")
                   (str "batch_id: " batch-id "\n"
                        "kind: " kind "\n"
                        "state: result_received\n"
                        "updated_at: " now "\n"))
    (append-line! (fs/path dir "events.log")
                  (str now "\tresult_received\t" assignment-id "\t" branch "\t" sha))
    (println "SQUAD_BATCH:" batch-id)
    (println "KIND:" kind)
    (println "STATE: result_received")
    (println "ASSIGNMENT:" assignment-id)
    (println "BRANCH:" branch)
    (println "SHA:" sha)))

(defn status! [batch-id]
  (validate-id! "Batch id" batch-id)
  (let [root (fs/absolutize (project-root))
        dir (batch-dir root batch-id)
        metadata (fs/path dir "metadata")
        state-file (fs/path dir "state")
        manifest (fs/path dir "manifest.tsv")]
    (ensure-batch! dir batch-id)
    (println "BATCH:" batch-id)
    (println "KIND:" (or (read-value metadata "kind") "unknown"))
    (println "STATE:" (or (read-value state-file "state") "unknown"))
    (println "STORIES:" (max 0 (dec (count (str/split-lines (slurp (str manifest)))))))
    (println "MANIFEST:" (str manifest))
    (println "RESULT:" (if (fs/exists? (fs/path dir "result")) (str (fs/path dir "result")) "none"))))

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

(defn -main [& args]
  (case (first args)
    "create" (if (= 3 (count args))
               (create-batch! (second args) (nth args 2))
               (exit! 1 usage-text))
    "add" (if (= 7 (count args))
            (add-story! (second args) (nth args 2) (nth args 3) (nth args 4) (nth args 5) (nth args 6))
            (exit! 1 usage-text))
    "result" (if (= 5 (count args))
               (result! (second args) (nth args 2) (nth args 3) (nth args 4))
               (exit! 1 usage-text))
    "status" (if (= 2 (count args))
               (status! (second args))
               (exit! 1 usage-text))
    "ready" (if (= 2 (count args))
              (ready! (second args))
              (exit! 1 usage-text))
    (exit! 1 usage-text)))

(apply -main *command-line-args*)
