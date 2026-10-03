#!/usr/bin/env bb

(ns done-with-current
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def script-dir (fs/parent *file*))
(try
  (require 'handoff-lib)
  (catch Exception _
    (load-file (str (fs/path script-dir "handoff_lib.bb")))))

(defn exit! [status message]
  (binding [*out* *err*]
    (println message))
  (System/exit status))

(defn run-helper! [script]
  (process/exec (str (fs/path script-dir script))))

(defn drop-arg? [args]
  (boolean (some #{"--drop"} args)))

(defn open-cards-message [labels]
  (str/join "\n"
            (concat ["OPEN_CARDS: current work still has cards without an outgoing git_handoff:"]
                    (map #(str "- " %) labels)
                    ["Send a git_handoff for each card (task: <card>; list more cards of the same commit in with_tasks:),"
                     "or run done_with_current.sh --drop to finish and leave them in this lane."])))

(defn check-open-cards!
  "With a board, refuse while forwarded cards still sit in this lane. Without
  one there is no lane to strand them in, so only name them."
  [role-name drop?]
  (let [labels (mapv handoff-lib/card-label (handoff-lib/open-card-ids role-name))]
    (when (seq labels)
      (cond
        drop? (doseq [label labels]
                (println "DROPPED:" label))
        (handoff-lib/board-present?) (exit! 3 (open-cards-message labels))
        :else (doseq [label labels]
                (println "NOT_HANDED_OFF:" label))))))

(defn -main [& args]
  (try
    (let [role-name (handoff-lib/role)
          mode (handoff-lib/role-receive-mode role-name)]
      (check-open-cards! role-name (drop-arg? args))
      (case mode
        "batch" (run-helper! "done_with_current_batch.sh")
        "task" (run-helper! "done_with_current_task.sh")
        (exit! 2 (str "INVALID_RECEIVE_MODE: " mode " for role " role-name))))
    (catch clojure.lang.ExceptionInfo e
      (exit! (or (:exit (ex-data e)) 1) (ex-message e)))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
