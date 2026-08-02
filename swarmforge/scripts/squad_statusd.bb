#!/usr/bin/env bb

(ns squad-statusd
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def usage-text
  "Usage: squad_statusd.sh [--once] [--no-notify] [project-root]")

(def poll-ms 5000)
(def wake-message
  "Squad status needs attention. If idle, run squad_status.sh.")
(def stopping? (atom false))
(def last-status-notification (atom {:alerts #{} :notified-at nil}))
(def terminal-agent-states
  #{"complete" "review_complete" "handoff_ready" "handed_off" "handing_off"})

(defn exit! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn sh-continue [& args]
  (apply process/sh (concat [{:continue true}] args)))

(defn now []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn instant-now []
  (java.time.Instant/now))

(defn parse-instant [value]
  (try
    (when-not (str/blank? value)
      (java.time.Instant/parse value))
    (catch Exception _ nil)))

(defn env-long [name default-value]
  (if-let [value (System/getenv name)]
    (if (re-matches #"[0-9]+" value)
      (Long/parseLong value)
      default-value)
    default-value))

(defn notify-cooldown-seconds []
  (env-long "SWARMFORGE_SQUAD_STATUS_NOTIFY_COOLDOWN_SECONDS" 300))

(defn project-root []
  (let [configured (not-empty (System/getenv "SWARMFORGE_PROJECT_ROOT"))
        configured-roles (when configured (fs/path configured ".swarmforge" "roles.tsv"))
        cwd (fs/cwd)
        direct (fs/path cwd ".swarmforge" "roles.tsv")]
    (if (and configured (fs/exists? configured-roles))
      (fs/path configured)
      (if (fs/exists? direct)
      cwd
      (let [git-root (str/trim (:out (sh-continue "git" "rev-parse" "--show-toplevel")))]
        (if (and (not (str/blank? git-root))
                 (fs/exists? (fs/path git-root ".swarmforge" "roles.tsv")))
          (fs/path git-root)
          (exit! 1 "Cannot find SwarmForge project root")))))))

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(defn read-lines [file]
  (when (fs/exists? file)
    (str/split-lines (slurp (str file)))))

(defn load-roles [root]
  (into {}
        (for [line (read-lines (fs/path root ".swarmforge" "roles.tsv"))
              :when (not (str/blank? line))
              :let [[role worktree-name worktree-path session display agent receive-mode]
                    (str/split line #"\t" -1)]]
          [role {:role role
                 :worktree-name worktree-name
                 :worktree-path worktree-path
                 :session session
                 :display display
                 :agent agent
                 :receive-mode receive-mode}])))

(defn agent-dirs [root]
  (let [agents-dir (fs/path root ".squad" "agents")]
    (if (fs/exists? agents-dir)
      (->> (fs/list-dir agents-dir)
           (filter fs/directory?)
           (sort-by fs/file-name)
           vec)
      [])))

(defn heartbeat-age-seconds [heartbeat now-instant]
  (when-let [updated (parse-instant (read-value heartbeat "updated_at"))]
    (.getSeconds (java.time.Duration/between updated now-instant))))

(defn tmux-session-exists? [socket session]
  (and (not (str/blank? socket))
       (zero? (:exit (sh-continue "tmux" "-S" socket "has-session" "-t" session)))))

(defn pane-dead? [socket session]
  (let [result (sh-continue "tmux" "-S" socket "list-panes" "-t" session "-F" "#{pane_dead}")]
    (and (zero? (:exit result))
         (some #{"1"} (str/split-lines (:out result))))))

(defn maybe-tmux-alert [socket skip-tmux? agent session]
  (cond
    skip-tmux? nil
    (str/blank? session) (str "agent " agent " has no tmux session metadata")
    (not (tmux-session-exists? socket session)) (str "agent " agent " tmux session missing: " session)
    (pane-dead? socket session) (str "agent " agent " tmux pane is dead: " session)
    :else nil))

(defn terminal-state? [state]
  (contains? terminal-agent-states state))

(defn alerts-for-agent [root roles socket skip-tmux? stale-seconds now-instant dir]
  (let [agent (fs/file-name dir)
        metadata (fs/path dir "metadata")
        status (fs/path dir "status")
        heartbeat (fs/path dir "heartbeat")
        state (read-value status "state")
        session (or (read-value metadata "session")
                    (get-in roles [agent :session]))
        age (heartbeat-age-seconds heartbeat now-instant)]
    (cond
      (= "retired" state) []
      (terminal-state? state) []
      (nil? (get roles agent)) [(str "agent " agent " is not registered in roles.tsv")]
      (not (fs/exists? heartbeat)) [(str "agent " agent " has no heartbeat")]
      (nil? age) [(str "agent " agent " heartbeat timestamp is invalid")]
      (> age stale-seconds) [(str "agent " agent " heartbeat stale for " age " seconds")]
      :else (if-let [alert (maybe-tmux-alert socket skip-tmux? agent session)]
              [alert]
              []))))

(defn log! [root & parts]
  (let [log-file (fs/path root ".swarmforge" "daemon" "squad-statusd.log")]
    (fs/create-dirs (fs/parent log-file))
    (spit (str log-file)
          (str (now) " " (str/join " " parts) "\n")
          :append true)))

(defn notify! [socket session]
  (let [send-text (sh-continue "tmux" "-S" socket "send-keys" "-t" session "-l" wake-message)
        _ (Thread/sleep 100)
        send-return (sh-continue "tmux" "-S" socket "send-keys" "-t" session "C-m")]
    (and (zero? (:exit send-text))
         (zero? (:exit send-return)))))

(defn poll-once! [{:keys [root no-notify? skip-tmux?]}]
  (let [root (fs/absolutize root)
        roles (load-roles root)
        socket-file (fs/path root ".swarmforge" "tmux-socket")
        socket (when (fs/exists? socket-file) (str/trim (slurp (str socket-file))))
        stale-seconds (env-long "SWARMFORGE_SQUAD_STALE_SECONDS" 300)
        alerts (mapcat #(alerts-for-agent root roles socket skip-tmux? stale-seconds (instant-now) %)
                       (agent-dirs root))
        alert-set (set alerts)
        now-instant (instant-now)]
    (doseq [alert alerts]
      (println "SQUAD_STATUS_ALERT:" alert)
      (log! root "alert" alert))
    (when (seq alerts)
      (if no-notify?
        (log! root "notify-skipped" (count alerts))
        (let [{previous-alerts :alerts notified-at :notified-at} @last-status-notification
              cooldown (notify-cooldown-seconds)
              due? (or (nil? notified-at)
                       (not= alert-set previous-alerts)
                       (>= (.getSeconds (java.time.Duration/between notified-at now-instant))
                           cooldown))]
          (if-not due?
            (log! root "notify-throttled" (count alerts))
            (if (notify! socket "swarmforge-squad-leader")
              (do
                (reset! last-status-notification {:alerts alert-set :notified-at now-instant})
                (log! root "notified" "squad-leader" (count alerts)))
              (log! root "notify-failed" "squad-leader" (count alerts)))))))
    (when (empty? alerts)
      (reset! last-status-notification {:alerts #{} :notified-at nil})
      (println "SQUAD_STATUS_OK")
      (log! root "ok"))
    alerts))

(defn parse-args [args]
  (loop [remaining args
         opts {:once? false :no-notify? false :root nil}]
    (if-let [arg (first remaining)]
      (case arg
        "--once" (recur (rest remaining) (assoc opts :once? true))
        "--no-notify" (recur (rest remaining) (assoc opts :no-notify? true))
        (if (:root opts)
          (exit! 1 usage-text)
          (do
            (when (str/starts-with? arg "--")
              (exit! 1 usage-text))
            (recur (rest remaining) (assoc opts :root arg)))))
      (update opts :root #(or % (project-root))))))

(defn stop-file [root]
  (fs/path root ".swarmforge" "daemon" "squad-statusd.stop"))

(defn pid-file [root]
  (fs/path root ".swarmforge" "daemon" "squad-statusd.pid"))

(defn should-stop? [root]
  (or @stopping? (fs/exists? (stop-file root))))

(defn sleep-poll! [root ms]
  (loop [remaining ms]
    (when (and (pos? remaining) (not (should-stop? root)))
      (let [step (min remaining 100)]
        (Thread/sleep step)
        (recur (- remaining step))))))

(defn -main [& args]
  (let [{:keys [once? no-notify? root]} (parse-args args)
        root (fs/absolutize root)
        skip-tmux? (= "1" (System/getenv "SWARMFORGE_SQUAD_STATUSD_SKIP_TMUX"))]
    (if once?
      (poll-once! {:root root :no-notify? no-notify? :skip-tmux? skip-tmux?})
      (do
        (fs/create-dirs (fs/parent (pid-file root)))
        (fs/delete-if-exists (stop-file root))
        (spit (str (pid-file root)) (str (.pid (java.lang.ProcessHandle/current)) "\n"))
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. #(do
                                      (reset! stopping? true)
                                      (fs/delete-if-exists (pid-file root))
                                      (log! root "stopped"))))
        (log! root "started")
        (try
          (while (not (should-stop? root))
            (poll-once! {:root root :no-notify? no-notify? :skip-tmux? skip-tmux?})
            (sleep-poll! root poll-ms))
          (finally
            (fs/delete-if-exists (pid-file root))
            (log! root "stopped")))))))

(apply -main *command-line-args*)
