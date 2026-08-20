#!/usr/bin/env bb

(ns squad-run
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def usage-text
  (str "Usage:\n"
       "  squad_run.sh [--expect-failure] <command...>\n"
       "  squad_run.sh [--expect-failure] <phase> <detail> -- <command...>\n"
       "  squad_run.sh --help"))

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

(defn parse-run-args [args]
  (let [args (vec args)]
    (cond
      (or (empty? args)
          (#{"--help" "-h"} (first args)))
      {:help? true}

      :else
      (loop [i 0
             expected-failure? false
             phase nil
             detail nil]
        (let [a (get args i)]
          (cond
            (nil? a)
            {:error true}

            (= a "--expect-failure")
            (recur (inc i) true phase detail)

            (= a "--phase")
            (if-let [v (get args (inc i))]
              (recur (+ i 2) expected-failure? v detail)
              {:error true})

            (= a "--detail")
            (if-let [v (get args (inc i))]
              (recur (+ i 2) expected-failure? phase v)
              {:error true})

            (= a "--")
            (let [cmd (subvec args (inc i))]
              (if (empty? cmd)
                {:error true}
                {:phase (or phase "run")
                 :detail (or detail (str/join " " cmd))
                 :expected-failure? expected-failure?
                 :command (vec cmd)}))

            :else
            (let [remaining (subvec args i)
                  dash (.indexOf remaining "--")]
              (if (>= dash 0)
                (let [before (subvec remaining 0 dash)
                      cmd (subvec remaining (inc dash))]
                  (if (or (empty? before) (empty? cmd))
                    {:error true}
                    {:phase (or phase (first before))
                     :detail (or detail (str/join " " (next before)))
                     :expected-failure? expected-failure?
                     :command (vec cmd)}))
                (if (empty? remaining)
                  {:error true}
                  {:phase (or phase "run")
                   :detail (or detail (str/join " " remaining))
                   :expected-failure? expected-failure?
                   :command (vec remaining)})))))))))

(defn child-stdin
  "TTY stdin is closed so interactive programs cannot wait on the agent pane.
  A redirected pipe is inherited so `printf Y | squad_run.sh …` still works."
  [kind]
  (if (= kind :tty) :closed :inherit))

(defn tty-stdin? []
  (zero? (:exit (process/sh {:continue true} "test" "-t" "0"))))

(defn process-in []
  (if (= :inherit (child-stdin (if (tty-stdin?) :tty :pipe)))
    :inherit
    ""))

(defn split-args [args]
  (let [parsed (parse-run-args args)]
    (cond
      (:help? parsed)
      (do (println usage-text) (System/exit 0))
      (:error parsed)
      (exit! 1 usage-text)
      :else parsed)))

(defn -main [& args]
  (let [{:keys [phase detail command expected-failure?]} (split-args args)
        event-detail (str phase ": " detail)]
    (event! "running" event-detail)
    (let [result (apply process/sh
                        (concat [{:continue true
                                  :out :inherit
                                  :err :inherit
                                  :in (process-in)}]
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
