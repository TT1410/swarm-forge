(ns squad-lease
  "Shared lease/lock primitive.

  Directory leases under `.swarmforge/squad/<name>.lock` with owner pid file.
  Stale reclaim when owner pid is dead. Not one mega-lock — call per resource
  (spawn, main-git, future handoff claims)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def default-timeout-ms 10000)
(def default-poll-ms 50)

(defn lease-dir
  "Path to lease directory for resource name (e.g. spawn, main-git)."
  [root name]
  (fs/path root ".swarmforge" "squad" (str name ".lock")))

(defn owner-file [lease-dir]
  (fs/path lease-dir "owner"))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn owner-pid
  "Read pid from lease owner file, or nil."
  [lease-dir]
  (let [owner (owner-file lease-dir)]
    (when (fs/exists? owner)
      (some->> (str/split-lines (slurp (str owner)))
               (some #(second (re-find #"^pid:\s*([0-9]+)" %)))
               parse-long))))

(defn pid-alive? [pid]
  (when pid
    (let [handle (java.lang.ProcessHandle/of pid)]
      (and (.isPresent handle)
           (.isAlive (.get handle))))))

(defn cleanup-empty!
  "Remove empty lease dir (no owner file)."
  [lease-dir]
  (when (and (fs/directory? lease-dir)
             (not (fs/exists? (owner-file lease-dir)))
             (empty? (fs/list-dir lease-dir)))
    (fs/delete-tree lease-dir)
    true))

(defn reclaim-stale!
  "If lease held by dead/missing pid, delete lease dir. Returns true if reclaimed."
  [lease-dir]
  (let [pid (owner-pid lease-dir)]
    (when (and (fs/directory? lease-dir)
               (or (nil? pid) (not (pid-alive? pid))))
      (fs/delete-tree lease-dir)
      true)))

(defn try-acquire!
  "Atomic create of lease dir + owner file. Returns lease-dir on success, nil otherwise."
  [root name]
  (let [dir (lease-dir root name)]
    (try
      (fs/create-dirs (fs/parent dir))
      (fs/create-dir dir)
      (spit (str (owner-file dir))
            (str "pid: " (.pid (java.lang.ProcessHandle/current)) "\n"
                 "acquired_at: " (timestamp) "\n"
                 "resource: " name "\n"))
      dir
      (catch java.nio.file.FileAlreadyExistsException _
        (reclaim-stale! dir)
        (cleanup-empty! dir)
        nil))))

(defn acquire!
  "Block until lease acquired or timeout. Throws ex-info on timeout.
  opts: :timeout-ms :poll-ms :timeout-message"
  ([root name] (acquire! root name {}))
  ([root name {:keys [timeout-ms poll-ms timeout-message]
               :or {timeout-ms default-timeout-ms
                    poll-ms default-poll-ms}}]
   (let [dir (lease-dir root name)
         deadline (+ (System/currentTimeMillis) timeout-ms)
         msg (or timeout-message
                 (str "Timed out waiting for lease: " dir))]
     (loop []
       (if-let [held (try-acquire! root name)]
         held
         (if (> (System/currentTimeMillis) deadline)
           (throw (ex-info msg {:lease (str dir) :resource name}))
           (do
             (reclaim-stale! dir)
             (Thread/sleep poll-ms)
             (recur))))))))

(defn release!
  "Release lease directory if present."
  [lease-dir]
  (when (and lease-dir (fs/directory? lease-dir))
    (fs/delete-tree lease-dir)
    true))

(defn with-lease
  "Acquire lease, run f, always release. Returns f's value.
  On timeout throws (same as acquire!)."
  ([root name f] (with-lease root name {} f))
  ([root name opts f]
   (let [held (acquire! root name opts)]
     (try
       (f)
       (finally
         (release! held))))))

(defn held?
  "True if lease dir exists (may be stale)."
  [root name]
  (fs/directory? (lease-dir root name)))
