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
(def active-agent-states
  #{"starting" "running"})

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

(defn alert-key [alert]
  (-> alert
      (str/replace #" heartbeat stale for [0-9]+ seconds; tmux pane alive but unchanged$"
                   " heartbeat stale; tmux pane alive but unchanged")
      (str/replace #" tmux session missing for [0-9]+ seconds:"
                   " tmux session missing:")
      (str/replace #" heartbeat stale for [0-9]+ seconds$"
                   " heartbeat stale")))

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
       (or (zero? (:exit (sh-continue "tmux" "-S" socket "has-session" "-t" session)))
           (let [result (sh-continue "tmux" "-S" socket "list-sessions" "-F" "#S")]
             (and (zero? (:exit result))
                  (contains? (set (str/split-lines (:out result))) session))))))

(defn pane-dead? [socket session]
  (let [result (sh-continue "tmux" "-S" socket "list-panes" "-t" session "-F" "#{pane_dead}")]
    (and (zero? (:exit result))
         (some #{"1"} (str/split-lines (:out result))))))

(defn sha256 [value]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes (or value "") "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn capture-pane-tail [socket session]
  (let [result (sh-continue "tmux" "-S" socket "capture-pane" "-p" "-t" session "-S" "-10")]
    (when (zero? (:exit result))
      (:out result))))

(defn write-liveness! [root agent state changed? tail]
  (let [file (fs/path root ".squad" "agents" agent "liveness")]
    (fs/create-dirs (fs/parent file))
    (spit (str file)
          (str "state: " state "\n"
               "observed_at: " (now) "\n"
               "pane_changed: " (if changed? "true" "false") "\n"
               "pane_hash: " (sha256 tail) "\n"
               "last_10_lines:\n"
               (or tail "")))))

(defn missing-session-file [root agent]
  (fs/path root ".squad" "agents" agent "missing-session"))

(defn clear-missing-session! [root agent]
  (fs/delete-if-exists (missing-session-file root agent)))

(defn missing-session-grace-seconds []
  (env-long "SWARMFORGE_SQUAD_MISSING_SESSION_GRACE_SECONDS" 30))

(defn missing-session-alert [root agent session]
  (let [file (missing-session-file root agent)
        first-seen (read-value file "first_seen")
        recorded-session (read-value file "session")
        now-instant (instant-now)
        same-session? (= session recorded-session)
        baseline (if same-session? first-seen (now))
        first-instant (when same-session?
                        (parse-instant first-seen))
        age (when first-instant
              (.getSeconds (java.time.Duration/between first-instant now-instant)))
        grace (missing-session-grace-seconds)]
    (fs/create-dirs (fs/parent file))
    (spit (str file)
          (str "session: " session "\n"
               "first_seen: " baseline "\n"
               "observed_at: " (now) "\n"))
    (when (and age (>= age grace))
      (str "agent " agent " tmux session missing for " age " seconds: " session))))

(defn pane-liveness-alert [root socket agent session age]
  (cond
    (str/blank? session) (str "agent " agent " has no tmux session metadata")
    (not (tmux-session-exists? socket session)) (missing-session-alert root agent session)
    (pane-dead? socket session) (str "agent " agent " tmux pane is dead: " session)
    :else (let [liveness (fs/path root ".squad" "agents" agent "liveness")
                previous-hash (read-value liveness "pane_hash")
                tail (or (capture-pane-tail socket session) "")
                current-hash (sha256 tail)
                changed? (not= previous-hash current-hash)
                state (if changed? "running_pane_active" "running_pane_idle")]
            (clear-missing-session! root agent)
            (write-liveness! root agent state changed? tail)
            (when-not changed?
              (str "agent " agent " heartbeat stale for " age " seconds; tmux pane alive but unchanged")))))

(defn maybe-tmux-alert [root socket skip-tmux? agent session]
  (cond
    skip-tmux? nil
    (str/blank? session) (str "agent " agent " has no tmux session metadata")
    (not (tmux-session-exists? socket session)) nil
    (pane-dead? socket session) (str "agent " agent " tmux pane is dead: " session)
    :else (do
            (clear-missing-session! root agent)
            nil)))

(defn active-state? [state]
  (contains? active-agent-states state))

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
      (and state (not (active-state? state))) []
      (nil? (get roles agent)) [(str "agent " agent " is not registered in roles.tsv")]
      (not (fs/exists? heartbeat)) [(str "agent " agent " has no heartbeat")]
      (nil? age) [(str "agent " agent " heartbeat timestamp is invalid")]
      (> age stale-seconds) (if skip-tmux?
                              [(str "agent " agent " heartbeat stale for " age " seconds")]
                              (if-let [alert (pane-liveness-alert root socket agent session age)]
                                [alert]
                                []))
      :else (if-let [alert (maybe-tmux-alert root socket skip-tmux? agent session)]
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
        alert-key-set (set (map alert-key alerts))
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
                       (not= alert-key-set previous-alerts)
                       (>= (.getSeconds (java.time.Duration/between notified-at now-instant))
                           cooldown))]
          (if-not due?
            (log! root "notify-throttled" (count alerts))
            (if (notify! socket "swarmforge-squad-leader")
              (do
                (reset! last-status-notification {:alerts alert-key-set :notified-at now-instant})
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
