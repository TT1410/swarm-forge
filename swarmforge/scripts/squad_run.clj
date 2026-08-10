#!/usr/bin/env bb

(ns squad-run
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def usage-text
  "Usage: squad_run.sh [--expect-failure] <phase> <detail> -- <command...>")

(def script-dir (-> *file* fs/path fs/parent))

(defn exit! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn event! [state detail]
  (let [result (process/sh {:continue true}
                           (str (babashka.fs/path script-dir "squad_event.sh"))
                           state
                           detail)]
    (when-not (zero? (:exit result))
      (exit! (:exit result) (str/trim (str (:err result)))))))

(defn split-args [args]
  (let [[flags args] (split-with #(str/starts-with? % "--") args)
        expected-failure? (contains? (set flags) "--expect-failure")]
    (when (some #(not= "--expect-failure" %) flags)
      (exit! 1 usage-text))
    (let [[before after] (split-with #(not= "--" %) args)]
    (when (or (empty? before) (< (count before) 2) (empty? after) (empty? (rest after)))
      (exit! 1 usage-text))
    {:phase (first before)
     :detail (str/join " " (rest before))
     :expected-failure? expected-failure?
     :command (vec (rest after))})))

(defn -main [& args]
  (let [{:keys [phase detail command expected-failure?]} (split-args args)
        event-detail (str phase ": " detail)]
    (event! "running" event-detail)
    (let [result (apply process/sh
                        (concat [{:continue true
                                  :out :inherit
                                  :err :inherit}]
                                command))]
      (if (zero? (:exit result))
        (event! "running" (str phase " passed: " detail))
        ;; Keep capacity-counted lifecycle: tool failures are progress detail, not
        ;; a slot-freeing terminal failure. Use blocked only for durable stops.
        (event! (if expected-failure? "running" "running")
                (str phase
                     (if expected-failure? " expected failure: " " failed: ")
                     detail
                     " exit "
                     (:exit result))))
      (System/exit (:exit result)))))
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
