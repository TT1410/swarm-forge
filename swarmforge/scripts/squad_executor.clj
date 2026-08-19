(ns squad-executor
  "Apply control-plane actions with authority checks.

  The executor is the only path that runs shell for daemon mechanical work.
  It refuses ops not on the authority allow-list."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-actions :as actions]
            [squad-control-plane :as plane]
            [clojure.string :as str]))

(def script-dir
  (fs/parent *file*))

(defn shell-command! [root command]
  (process/sh {:dir (str root) :continue true}
              "bash" "-c" (str "PATH=" script-dir ":$PATH; " command)))

(defn apply-candidate!
  "Execute one candidate under authority (default :daemon)."
  ([root candidate]
   (apply-candidate! root candidate :daemon))
  ([root candidate authority]
   (let [typed (actions/ensure-typed candidate)
         op (actions/op-of typed)]
     (plane/assert-op-allowed! authority op)
     (let [result (shell-command! root (actions/shell-command typed))]
       (assoc typed
              :exit (:exit result)
              :out (:out result)
              :err (:err result)
              :applied-authority (keyword authority))))))

(defn apply-candidates!
  "Apply candidates sequentially under authority; stop after first non-zero exit."
  ([root candidates]
   (apply-candidates! root candidates :daemon))
  ([root candidates authority]
   (loop [remaining candidates
          applied []]
     (if (empty? remaining)
       applied
       (let [result (apply-candidate! root (first remaining) authority)
             applied (conj applied result)]
         (if (zero? (:exit result))
           (recur (rest remaining) applied)
           applied))))))
