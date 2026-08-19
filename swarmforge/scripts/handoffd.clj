#!/usr/bin/env bb

(ns handoffd
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(def poll-ms 1000)
(def wake-message
  "You have new handoff mail. If idle, run squad_next.sh --residual-only. Residual accept-merge is yours; the daemon does not merge.")
(defn usage []
  (binding [*out* *err*]
    (println "Usage: handoffd.clj <project-root>"))
  (System/exit 1))

(def project-root
  (or (first *command-line-args*) "."))

(def state-dir (fs/path project-root ".swarmforge"))
(def daemon-dir (fs/path state-dir "daemon"))
(def roles-file (fs/path state-dir "roles.tsv"))
(def socket-file (fs/path state-dir "tmux-socket"))
(def pid-file (fs/path daemon-dir "handoffd.pid"))
(def stop-file (fs/path daemon-dir "stop"))
(def log-file (fs/path daemon-dir "handoffd.log"))
(def stopping-flag (atom false))

(defn now []
  (.format (java.time.format.DateTimeFormatter/ISO_INSTANT)
           (java.time.Instant/now)))

(defn log! [& parts]
  (fs/create-dirs daemon-dir)
  (spit (str log-file)
        (str (now) " " (str/join " " parts) "\n")
        :append true))

(defn read-lines [path]
  (when (fs/exists? path)
    (str/split-lines (slurp (str path)))))

(defn load-roles []
  (into {}
        (for [line (read-lines roles-file)
              :when (not (str/blank? line))
              :let [[role worktree-name worktree-path session display agent receive-mode]
                    (str/split line #"\t")]]
          [role {:role role
                 :worktree-name worktree-name
                 :worktree-path worktree-path
                 :session session
                 :display display
                 :agent agent
                 :receive-mode (or receive-mode "task")}])))

(defn parse-message [path]
  (let [content (slurp (str path))
        [header body] (str/split content #"\n\n" 2)
        headers (into {}
                      (for [line (str/split-lines header)
                            :let [[k v] (str/split line #": " 2)]
                            :when (and k v)]
                        [k v]))]
    {:headers headers
     :body (or body "")
     :content content}))

(defn render-message [headers body]
  (let [preferred ["id" "from" "to" "recipient" "priority" "type" "role" "commit"
                   "message" "created_at" "enqueued_at" "dequeued_at" "completed_at"]
        remaining (->> (keys headers)
                       (remove (set preferred))
                       sort)
        ordered (concat preferred remaining)]
    (str (str/join "\n"
                   (for [k ordered
                         :let [v (get headers k)]
                         :when v]
                     (str k ": " v)))
         "\n\n"
         body)))

(defn add-delivery-headers [message recipient]
  (-> message
      (assoc-in [:headers "recipient"] recipient)
      (assoc-in [:headers "enqueued_at"] (now))))

(defn target-path [role-info filename]
  (fs/path (:worktree-path role-info)
           ".swarmforge" "handoffs" "inbox" "new" filename))

(defn notify! [socket session]
  (let [send-text (sh "tmux" "-S" socket "send-keys" "-t" session "-l" wake-message)
        _ (Thread/sleep 150)
        send-carriage-return (sh "tmux" "-S" socket "send-keys" "-t" session "C-m")
        _ (Thread/sleep 50)
        send-line-feed (sh "tmux" "-S" socket "send-keys" "-t" session "C-j")]
    (when-not (zero? (:exit send-text))
      (throw (ex-info "tmux send text failed" send-text)))
    (when-not (zero? (:exit send-carriage-return))
      (throw (ex-info "tmux send carriage return failed" send-carriage-return)))
    (when-not (zero? (:exit send-line-feed))
      (throw (ex-info "tmux send line feed failed" send-line-feed)))))

(defn move-with-collision [source target-dir]
  (fs/create-dirs target-dir)
  (let [base (fs/file-name source)
        target (fs/path target-dir base)]
    (if (fs/exists? target)
      (fs/move source
               (fs/path target-dir (str (now) "_" base))
               {:replace-existing false})
      (fs/move source target {:replace-existing false}))))

(defn fail! [path reason]
  (let [failed-dir (fs/path (fs/parent (fs/parent path)) "failed")]
    (log! "failed" (str path) reason)
    (spit (str path ".error") (str reason "\n"))
    (move-with-collision path failed-dir)))

(defn message-recipients [message]
  (some-> (get-in message [:headers "to"]) (str/split #",") seq))

(defn ensure-role-info! [roles recipient]
  (or (get roles recipient)
      (throw (ex-info (str "unknown recipient " recipient) {:recipient recipient}))))

(defn delivered-message [message recipient]
  (add-delivery-headers message recipient))

(defn write-delivered-message! [role-info filename message]
  (let [target (target-path role-info filename)]
    (fs/create-dirs (fs/parent target))
    (when-not (fs/exists? target)
      (spit (str target) (render-message (:headers message) (:body message))))))

(defn deliver-recipient! [roles socket filename message recipient]
  (let [role-info (ensure-role-info! roles recipient)]
    (write-delivered-message! role-info filename (delivered-message message recipient))
    (notify! socket (:session role-info))))

(defn sent-dir [roles sender-role]
  (fs/path (get-in roles [sender-role :worktree-path])
           ".swarmforge" "handoffs" "sent"))

(defn deliver! [roles socket sender-role path]
  (let [filename (fs/file-name path)
        message (parse-message path)
        recipients (message-recipients message)]
    (if-not recipients
      (fail! path "missing to header")
      (do
        (doseq [recipient recipients]
          (deliver-recipient! roles socket filename message recipient))
        (move-with-collision path (sent-dir roles sender-role))
        (log! "delivered" (str path))))))

(defn outbox-files [role-info]
  (let [outbox (fs/path (:worktree-path role-info) ".swarmforge" "handoffs" "outbox")]
    (when (fs/exists? outbox)
      (->> (fs/list-dir outbox)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".handoff")))
           (sort-by #(fs/file-name %))))))

(defn should-stop? []
  (or @stopping-flag (fs/exists? stop-file)))

(defn sleep-poll! [ms]
  (loop [remaining ms]
    (when (and (pos? remaining) (not (should-stop?)))
      (let [step (min remaining 100)]
        (Thread/sleep step)
        (recur (- remaining step))))))

(defn archive-failed! [path error]
  (try
    (fail! path (.getMessage error))
    (catch Exception nested
      (log! "failed-to-archive" (str path) (.getMessage nested)))))

(defn deliver-outbox-file! [roles socket role path]
  (try
    (deliver! roles socket role path)
    (catch Exception e
      (log! "error" (str path) (.getMessage e))
      (archive-failed! path e))))

(defn poll-role! [roles socket role role-info]
  (doseq [path (or (outbox-files role-info) [])
          :while (not (should-stop?))]
    (deliver-outbox-file! roles socket role path)))

(defn poll-once! []
  (when-not (should-stop?)
    (let [roles (load-roles)
          socket (str/trim (slurp (str socket-file)))]
      (doseq [[role role-info] roles
              :while (not (should-stop?))]
        (poll-role! roles socket role role-info)))))

(defn shutdown! []
  (reset! stopping-flag true)
  (try
    (fs/delete-if-exists pid-file)
    (log! "stopped")
    (catch Exception _ nil)))

(defn -main []
  (when-not (first *command-line-args*)
    (usage))
  (fs/create-dirs daemon-dir)
  (fs/delete-if-exists stop-file)
  (spit (str pid-file) (str (.pid (java.lang.ProcessHandle/current)) "\n"))
  (.addShutdownHook (Runtime/getRuntime) (Thread. shutdown!))
  (log! "started")
  (try
    (while (not (should-stop?))
      (poll-once!)
      (sleep-poll! poll-ms))
    (finally
      (fs/delete-if-exists pid-file)
      (log! "stopped"))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
