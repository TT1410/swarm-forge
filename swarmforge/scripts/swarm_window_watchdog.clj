#!/usr/bin/env bb

(ns swarm-window-watchdog
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def missing-threshold 3)

(defn sq [value]
  (str "'" (str/replace (str value) #"'" "'\"'\"'") "'"))

(defn rows [window-state-file]
  (when (fs/exists? window-state-file)
    (->> (str/split-lines (slurp (str window-state-file)))
         (remove str/blank?)
         (map #(zipmap [:index :window-id :session :title]
                       (str/split % #"\t" -1)))
         vec)))

(defn write-rows! [window-state-file window-ids-file rows]
  (spit (str window-state-file)
        (apply str
               (for [{:keys [index window-id session title]} rows]
                 (format "%s\t%s\t%s\t%s\n" index window-id session title))))
  (spit (str window-ids-file)
        (apply str (for [{:keys [window-id]} rows] (str window-id "\n")))))

(defn rewrite-window-id! [window-state-file window-ids-file target-index replacement-id]
  (write-rows! window-state-file
               window-ids-file
               (mapv #(if (= (:index %) target-index)
                        (assoc % :window-id replacement-id)
                        %)
                     (rows window-state-file))))

(defn adapter-script [script-dir working-dir tmux-socket backend command & args]
  (let [script (str "SCRIPT_DIR=" (sq (str script-dir)) "\n"
                    "WORKING_DIR=" (sq (str working-dir)) "\n"
                    "TMUX_SOCKET=" (sq tmux-socket) "\n"
                    "source " (sq (str (fs/path script-dir "swarm-terminal-adapter.sh")))
                    " && load_terminal_backend " (sq backend)
                    " && " command
                    (apply str (map #(str " " (sq %)) args)))]
    ["zsh" "-c" script]))

(defn terminal-ok? [script-dir working-dir tmux-socket backend command & args]
  (zero? (:exit (apply process/sh (concat [{:continue true}]
                                          (apply adapter-script script-dir working-dir tmux-socket backend command args))))))

(defn terminal-out [script-dir working-dir tmux-socket backend command & args]
  (str/trim (:out (apply process/sh (apply adapter-script script-dir working-dir tmux-socket backend command args)))))

(defn tmux-session? [tmux-socket session]
  (zero? (:exit (process/sh {:continue true} "tmux" "-S" tmux-socket "has-session" "-t" session))))

(defn kill-session! [tmux-socket session]
  (process/sh {:continue true} "tmux" "-S" tmux-socket "kill-session" "-t" session))

(defn role-sessions [working-dir]
  (let [roles-file (fs/path working-dir ".swarmforge" "roles.tsv")]
    (when (fs/exists? roles-file)
      (->> (str/split-lines (slurp (str roles-file)))
           (remove str/blank?)
           (map #(nth (str/split % #"\t" -1) 3 nil))
           (remove str/blank?)
           vec))))

(defn tmux-sessions [tmux-socket]
  (let [result (process/sh {:continue true}
                           "tmux" "-S" tmux-socket "list-sessions" "-F" "#{session_name}")]
    (when (zero? (:exit result))
      (->> (str/split-lines (:out result))
           (remove str/blank?)
           vec))))

(defn stop-handoff-daemon! [script-dir working-dir]
  (process/sh {:continue true}
              "bb" (str (fs/path script-dir "stop_handoff_daemon.clj"))
              (str working-dir)))

(defn stop-squadd! [script-dir working-dir]
  (let [script (fs/path script-dir "stop_squadd.clj")]
    (when (fs/exists? script)
      (process/sh {:continue true}
                  "bb" (str script)
                  (str working-dir)))))

(defn kill-all-sessions! [script-dir window-state-file working-dir tmux-socket backend]
  (stop-squadd! script-dir working-dir)
  (stop-handoff-daemon! script-dir working-dir)
  (doseq [session (distinct (concat (map :session (rows window-state-file))
                                    (role-sessions working-dir)
                                    (tmux-sessions tmux-socket)))]
    (when-not (str/blank? (str session))
      (kill-session! tmux-socket session)))
  (process/sh {:continue true} "tmux" "-S" tmux-socket "kill-server")
  (doseq [{:keys [window-id]} (rows window-state-file)]
    (when-not (str/blank? window-id)
      (terminal-ok? script-dir working-dir tmux-socket backend "terminal_close_window" window-id))))

(defn rewrite-command? [args]
  (= "--rewrite-window-id" (first args)))

(defn run-rewrite-command! [args]
  (let [[_ state ids target replacement] args]
    (rewrite-window-id! (fs/path state) (fs/path ids) target replacement)
    (System/exit 0)))

(defn cleanup-window-present? [script-dir working-dir tmux-socket backend cleanup-window-id]
  (terminal-ok? script-dir working-dir tmux-socket backend "terminal_window_exists" cleanup-window-id))

(defn watch-row? [tmux-socket cleanup-owner-index {:keys [index session]}]
  (and (not= index cleanup-owner-index)
       (tmux-session? tmux-socket session)))

(defn row-window-present? [script-dir working-dir tmux-socket backend window-id]
  (terminal-ok? script-dir working-dir tmux-socket backend "terminal_window_exists" window-id))

(defn reopen-row-window! [script-dir window-state-file window-ids-file working-dir tmux-socket backend cleanup-window-id counts
                          {:keys [index session title]}]
  (let [new-window-id (terminal-out script-dir working-dir tmux-socket backend
                                    "terminal_open_session" session title cleanup-window-id)]
    (when-not (str/blank? new-window-id)
      (rewrite-window-id! window-state-file window-ids-file index new-window-id))
    (assoc counts index 0)))

(defn record-missing-window! [script-dir window-state-file window-ids-file working-dir tmux-socket backend cleanup-window-id counts row]
  (let [count (inc (get counts (:index row) 0))]
    (if (< count missing-threshold)
      (assoc counts (:index row) count)
      (reopen-row-window! script-dir window-state-file window-ids-file working-dir tmux-socket backend cleanup-window-id counts row))))

(defn maybe-reopen-window! [script-dir window-state-file window-ids-file working-dir tmux-socket backend cleanup-owner-index cleanup-window-id counts
                            {:keys [index window-id] :as row}]
  (cond
    (not (watch-row? tmux-socket cleanup-owner-index row))
    counts

    (row-window-present? script-dir working-dir tmux-socket backend window-id)
    (assoc counts index 0)

    :else
    (record-missing-window! script-dir window-state-file window-ids-file working-dir tmux-socket backend cleanup-window-id counts row)))

(defn reconcile-agent-windows! [script-dir window-state-file window-ids-file working-dir tmux-socket backend cleanup-owner-index cleanup-window-id counts current-rows]
  (reduce
   (fn [counts row]
     (if (= (:index row) cleanup-owner-index)
       counts
       (maybe-reopen-window! script-dir window-state-file window-ids-file working-dir tmux-socket backend
                             cleanup-owner-index cleanup-window-id counts row)))
   (assoc counts cleanup-owner-index 0)
   current-rows))

(defn cleanup-row [cleanup-owner-index current-rows]
  (some #(when (= cleanup-owner-index (:index %)) %) current-rows))

(defn cleanup-session-live? [tmux-socket cleanup-row]
  (and cleanup-row
       (tmux-session? tmux-socket (:session cleanup-row))))

(defn handle-missing-cleanup-window! [script-dir window-state-file working-dir tmux-socket backend cleanup-owner-index counts]
  (let [count (inc (get counts cleanup-owner-index 0))]
    (if (>= count missing-threshold)
      (kill-all-sessions! script-dir window-state-file working-dir tmux-socket backend)
      (assoc counts cleanup-owner-index count))))

(defn next-missing-counts [script-dir window-state-file window-ids-file working-dir tmux-socket backend cleanup-owner-index counts]
  (let [current-rows (rows window-state-file)
        cleanup-row (cleanup-row cleanup-owner-index current-rows)]
    (when (cleanup-session-live? tmux-socket cleanup-row)
      (let [cleanup-window-id (:window-id cleanup-row)]
        (if (cleanup-window-present? script-dir working-dir tmux-socket backend cleanup-window-id)
          (reconcile-agent-windows! script-dir window-state-file window-ids-file working-dir tmux-socket backend
                                    cleanup-owner-index cleanup-window-id counts current-rows)
          (handle-missing-cleanup-window! script-dir window-state-file working-dir tmux-socket backend
                                          cleanup-owner-index counts))))))

(defn watch-loop! [script-dir window-state-file window-ids-file working-dir tmux-socket backend cleanup-owner-index]
  (loop [missing-counts {}]
    (when (fs/exists? window-state-file)
      (when-let [next-counts (next-missing-counts script-dir window-state-file window-ids-file working-dir tmux-socket backend
                                                  cleanup-owner-index missing-counts)]
        (Thread/sleep 2000)
        (recur next-counts)))))

(defn -main [& args]
  (let [[window-state-file window-ids-file cleanup-owner-index tmux-socket working-dir backend] args
        window-state-file (fs/path window-state-file)
        window-ids-file (fs/path window-ids-file)
        backend (or backend "terminal-app")
        script-dir (fs/parent *file*)]
    (when (rewrite-command? args)
      (run-rewrite-command! args))
    (watch-loop! script-dir window-state-file window-ids-file working-dir tmux-socket backend cleanup-owner-index)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
