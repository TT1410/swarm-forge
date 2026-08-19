(ns squad-records
  "Shared durable record formats.

  Categories:
  - kv: flat `key: value` lines (status, metadata, lifecycle)
  - headers+body: key/value headers until a blank line, then free body (handoffs, requests)
  - edn: structured state maps
  - events: append-only tab or freeform lines

  Atomic writes use temp-file + move. Prefer these helpers over ad-hoc slurp/split."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn write-atomic!
  "Write content atomically under parent dir."
  [file content]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                  :prefix (str "." (fs/file-name file) ".")})]
    (spit (str tmp) content)
    (fs/move tmp file {:replace-existing true})))

(defn parse-kv-line
  "Parse one `key: value` line. Returns [k v] or nil."
  [line]
  (let [[k v] (str/split line #": " 2)]
    (when (and k v (not (str/blank? k)))
      [k v])))

(defn parse-kv-text
  "Parse all key: value lines (no blank-line body split)."
  [text]
  (into {}
        (keep parse-kv-line)
        (str/split-lines (or text ""))))

(defn read-kv-file
  "Read a flat key:value file into a map. Missing file → {}."
  [file]
  (if (fs/regular-file? file)
    (parse-kv-text (slurp (str file)))
    {}))

(defn kv-get
  "Read one field from a kv file."
  [file field]
  (get (read-kv-file file) field))

(defn format-kv
  "Render ordered key/value map as durable text (stable key order)."
  ([m] (format-kv m (sort (keys m))))
  ([m key-order]
   (str (->> key-order
             (keep (fn [k]
                     (when-let [v (get m k)]
                       (when-not (str/blank? (str v))
                         (str k ": " v)))))
             (str/join "\n"))
        "\n")))

(defn write-kv-file!
  "Atomic write of a flat kv record."
  ([file m] (write-kv-file! file m (sort (keys m))))
  ([file m key-order]
   (write-atomic! file (format-kv m key-order))))

(defn parse-headers-body
  "Split text into headers map + body string after first blank line."
  [text]
  (let [lines (str/split-lines (or text ""))
        [header-lines body-lines]
        (split-with (fn [line] (not (str/blank? line))) lines)
        body (->> body-lines
                  (drop-while str/blank?)
                  (str/join "\n"))]
    {:headers (into {} (keep parse-kv-line) header-lines)
     :body body}))

(defn read-headers-body-file
  "Read headers+body record. Missing → empty headers/body."
  [file]
  (if (fs/regular-file? file)
    (parse-headers-body (slurp (str file)))
    {:headers {} :body ""}))

(defn format-headers-body
  "Render headers map + optional body."
  ([headers] (format-headers-body headers nil))
  ([headers body]
   (str (format-kv headers)
        (when-not (str/blank? body)
          (str "\n" body
               (when-not (str/ends-with? body "\n") "\n"))))))

(defn write-headers-body-file!
  "Atomic write of headers+body record."
  [file headers body]
  (write-atomic! file (format-headers-body headers body)))

(defn append-event!
  "Append one event line (history). Creates parent dirs."
  [file line]
  (fs/create-dirs (fs/parent file))
  (spit (str file) (str line "\n") :append true))

(defn read-edn-file
  "Read EDN structured state. Missing or bad → nil."
  [file]
  (when (fs/regular-file? file)
    (try
      (edn/read-string (slurp (str file)))
      (catch Exception _ nil))))

(defn write-edn-file!
  "Atomic write of EDN value (pr-str)."
  [file value]
  (write-atomic! file (str (pr-str value) "\n")))
