#!/usr/bin/env bb

(ns squad-batch-story
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-config :as cfg]
            [clojure.string :as str]))

(def usage-text
  "Usage: squad_batch_story.sh add <story-id> <hardener|qa|architecture|architecture-fix> <batch-id> <stage> <assignment-id> <branch> <sha>")

(def script-dir (fs/parent *file*))

(defn exit! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn sh! [dir & args]
  (let [result (apply process/sh (concat [{:dir (str dir) :continue true}] args))]
    (when-not (zero? (:exit result))
      (exit! (:exit result) (:err result)))
    result))

(defn sh-continue [& args]
  (apply process/sh (concat [{:continue true}] args)))

(defn project-root []
  (or (cfg/project-root)
      (exit! 1 "Cannot find SwarmForge project root")))

(defn script [name]
  (str (fs/path script-dir name)))

(defn batch-exists? [root batch-id]
  (fs/directory? (fs/path root ".squad" "batches" batch-id)))

(defn add-story! [story-id kind batch-id stage assignment-id branch sha]
  (let [root (fs/absolutize (project-root))]
    (when-not (batch-exists? root batch-id)
      (sh! root (script "squad_batch.sh") "create" kind batch-id))
    (sh! root (script "squad_batch.sh") "add" batch-id story-id stage assignment-id branch sha)
    (sh! root (script "squad_packet.sh") "batch" story-id kind batch-id stage assignment-id branch sha)
    (println "SQUAD_BATCH_STORY:" story-id)
    (println "KIND:" kind)
    (println "BATCH:" batch-id)
    (println "STATE: recorded")))

(defn -main [& args]
  (if (and (= "add" (first args)) (= 8 (count args)))
    (apply add-story! (rest args))
    (exit! 1 usage-text)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
