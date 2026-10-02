#!/usr/bin/env bb

(ns ready-for-next-guard
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(defn command [& args]
  (apply sh/sh args))

(defn git-root []
  (let [result (command "git" "rev-parse" "--show-toplevel")]
    (when (zero? (:exit result))
      (str/trim (:out result)))))

(defn git-common-dir []
  (let [result (command "git" "rev-parse" "--git-common-dir")]
    (when (zero? (:exit result))
      (let [path (str/trim (:out result))]
        (if (fs/absolute? path)
          path
          (str (fs/absolutize path)))))))

(defn roles-at? [root]
  (and root (fs/exists? (fs/path root ".swarmforge" "roles.tsv"))))

(defn project-root []
  (or (let [parent (some-> (git-common-dir) fs/parent str)]
        (when (roles-at? parent) parent))
      (when (roles-at? (git-root)) (git-root))
      (when (roles-at? (fs/cwd)) (str (fs/cwd)))))

(defn same-path? [a b]
  (try
    (= (str (fs/canonicalize a)) (str (fs/canonicalize b)))
    (catch Exception _
      (= (str a) (str b)))))

(defn roles-file []
  (when-let [root (project-root)]
    (fs/path root ".swarmforge" "roles.tsv")))

(defn role-rows []
  (if-let [file (roles-file)]
    (if (fs/exists? file)
      (->> (str/split-lines (slurp (str file)))
           (remove str/blank?)
           (map #(str/split % #"\t" -1))
           vec)
      [])
    []))

(defn infer-role-from-worktree []
  (let [here (or (git-root) (str (fs/absolutize ".")))]
    (some (fn [cols]
            (let [role-name (first cols)
                  wt (nth cols 2 nil)]
              (when (and (not-empty role-name) (not-empty wt) (same-path? wt here))
                role-name)))
          (role-rows))))

(defn current-role []
  (or (not-empty (System/getenv "SWARMFORGE_ROLE"))
      (infer-role-from-worktree)))

(defn header-map [file]
  (into {}
        (for [line (take-while (complement str/blank?) (str/split-lines (slurp (str file))))
              :let [[k v] (str/split line #": " 2)]
              :when (and k v)]
          [k v])))

(defn recursive-handoff-files [dir]
  (if (fs/directory? dir)
    (->> (concat (fs/glob dir "*.handoff")
                 (fs/glob dir "**/*.handoff"))
         (filter fs/regular-file?)
         distinct
         vec)
    []))

(defn pending-approval-dir []
  (when-let [root (project-root)]
    (fs/path root ".swarmforge" "handoffs" "pending_approval")))

(defn active-outbox-dirs []
  (concat
   (when-let [root (project-root)]
     [(fs/path root ".swarmforge" "handoffs" "outbox")])
   (for [cols (role-rows)
         :let [wt (nth cols 2 nil)]
         :when (not (str/blank? wt))]
     (fs/path wt ".swarmforge" "handoffs" "outbox"))))

(defn outbound-git-from-role? [role file]
  (let [headers (header-map file)]
    (and (= "git_handoff" (get headers "type"))
         (= role (get headers "from")))))

(defn active-outbound-git-files [role]
  (if (str/blank? role)
    []
    (let [pending (if-let [dir (pending-approval-dir)]
                    (recursive-handoff-files dir)
                    [])
          outbox-active (mapcat recursive-handoff-files (active-outbox-dirs))]
      (->> (concat pending outbox-active)
           (filter #(outbound-git-from-role? role %))
           distinct
           vec))))

(defn outbox-git-files
  "Git handoffs from role that the daemon has not delivered or held yet."
  [role]
  (if (str/blank? role)
    []
    (->> (mapcat recursive-handoff-files (active-outbox-dirs))
         (filter #(outbound-git-from-role? role %))
         distinct
         vec)))

(defn pending-approval-git-files [role]
  (if (str/blank? role)
    []
    (->> (if-let [dir (pending-approval-dir)]
           (recursive-handoff-files dir)
           [])
         (filter #(outbound-git-from-role? role %))
         vec)))

(defn card-keys [file]
  (let [headers (header-map file)]
    (->> (cons (or (not-empty (get headers "task_id")) (get headers "task"))
               (str/split (or (get headers "with_task_ids") "") #","))
         (map #(some-> % str/trim))
         (remove str/blank?)
         set)))

(defn held-card-keys [role]
  (into #{} (mapcat card-keys) (pending-approval-git-files role)))

(defn startable-files
  "New mail the role may start. A handoff waiting for approval holds back only
  mail for the same card, not the whole queue."
  [role files]
  (let [held (held-card-keys role)]
    (vec (remove #(some held (card-keys %)) files))))

(defn blocking-files
  "Outbound files that keep role from taking new work, or nil."
  [role new-files]
  (or (seq (outbox-git-files role))
      (when (and (seq new-files) (empty? (startable-files role new-files)))
        (seq (pending-approval-git-files role)))))

(defn wait-message [active]
  ["WAITING_FOR_APPROVAL: current git handoff is still active"
   (str/join "\n" (map #(str "- " %) active))])
