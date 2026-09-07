(ns handoff-state
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn header-map [file]
  (into {}
        (for [line (take-while (complement str/blank?)
                               (str/split-lines (slurp (str file))))
              :let [[k v] (str/split line #": " 2)]
              :when (and k v)]
          [k v])))

(defn handoff-files-under [dir]
  (if (fs/directory? dir)
    (->> (fs/list-dir dir)
         (mapcat (fn [entry]
                   (cond
                     (and (fs/regular-file? entry)
                          (str/ends-with? (fs/file-name entry) ".handoff"))
                     [entry]

                     (fs/directory? entry)
                     (handoff-files-under entry)

                     :else [])))
         vec)
    []))

(defn role-worktree-roots [root]
  (let [roles-file (fs/path root ".swarmforge" "roles.tsv")]
    (if-not (fs/regular-file? roles-file)
      []
      (->> (str/split-lines (slurp (str roles-file)))
           (remove str/blank?)
           (keep (fn [line]
                   (not-empty (nth (str/split line #"\t" -1) 2 nil))))
           vec))))

(defn active-handoff-dirs [root]
  (->> (cons (str root) (role-worktree-roots root))
       distinct
       (mapcat (fn [worktree]
                 [(fs/path worktree ".swarmforge" "handoffs" "outbox")
                  (fs/path worktree ".swarmforge" "handoffs" "pending_approval")
                  (fs/path worktree ".swarmforge" "handoffs" "inbox" "new")
                  (fs/path worktree ".swarmforge" "handoffs" "inbox" "in_process")]))
       distinct
       vec))

(defn synchronization-handoff? [file]
  (let [headers (header-map file)
        kind (get headers "delivery_kind")]
    (and (= "git_handoff" (get headers "type"))
         (or (#{"reverse" "terminal"} kind)
             (= "true" (get headers "non-forwarding"))))))

(defn active-synchronization-files [root]
  (->> (active-handoff-dirs root)
       (mapcat handoff-files-under)
       (map #(fs/path %))
       distinct
       (filter synchronization-handoff?)
       vec))

(defn synchronization-active? [root]
  (boolean (seq (active-synchronization-files root))))
