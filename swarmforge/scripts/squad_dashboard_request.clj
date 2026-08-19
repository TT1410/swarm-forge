#!/usr/bin/env bb

(ns squad-dashboard-request
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [squad-config :as cfg]
            [squad-records :as rec]
            [clojure.string :as str]))

(def usage-text
  (str "Usage:\n"
       "  squad_dashboard_request.sh answer <id> <answer-file>\n"
       "  squad_dashboard_request.sh complete <id> <answer-file>\n"
       "  squad_dashboard_request.sh reject <id> <reason-file>\n"
       "  squad_dashboard_request.sh cancel <id>\n"
       "  squad_dashboard_request.sh route-to-sl <id>\n"
       "  squad_dashboard_request.sh note <id> <note-file>\n"
       "  squad_dashboard_request.sh status [id]\n"))

(def valid-id #"[A-Za-z0-9][A-Za-z0-9._-]*")
;; Single request type. Legacy "command"/"question" accepted and normalized to "request".
(def valid-kinds #{"request" "command" "question"})
;; Front-door chat is Troubleshooter; product intent is re-owned by Squad Leader.
(def valid-owners #{"troubleshooter" "squad-leader"})
(def default-owner "troubleshooter")
(def product-owner "squad-leader")
(def max-body-chars 8000)
(def history-limit 50)

(defn exit! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn project-root []
  (or (cfg/project-root)
      (exit! 1 "Cannot find SwarmForge project root")))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn valid-id? [value]
  (and (string? value)
       (re-matches valid-id value)
       (not (str/includes? value "/"))
       (not (str/includes? value "\\"))
       (not (str/includes? value ".."))))

(defn write-atomic!
  "Atomic write via squad-records. Request parse-kv keeps multiline  form."
  [file content]
  (rec/write-atomic! file content))

(defn requests-root [root]
  (fs/path root ".swarmforge" "dashboard" "requests"))

(defn state-dir [root state]
  (fs/path (requests-root root) state))

(defn request-file [root state id]
  (fs/path (state-dir root state) (str id ".request")))

(defn progress-file
  "Append-only interim status notes for a request. Sibling of .request."
  [root state id]
  (fs/path (state-dir root state) (str id ".progress")))

(def multiline-field-keys
  "Fields that may contain newlines. Written as key: | block form."
  #{"body" "response" "detail"})

(def request-field-keys
  "Top-level durable request fields. Multiline body/response/detail may contain
  free text that looks like `key: value` (e.g. backlog_id: …). Only these
  keys end a multiline block — arbitrary `foo: bar` lines stay inside the body."
  #{"id" "kind" "status" "owner" "created_at" "updated_at" "answered_at"
    "routed_at" "body" "response" "detail"})

(def header-key-line
  #"^([A-Za-z0-9_]+): (.*)$")

(defn- multiline-block-terminator?
  "True when line starts the next top-level request field (not free text)."
  [line]
  (when-let [[_ k] (re-matches header-key-line line)]
    (contains? request-field-keys k)))

(defn parse-kv
  "Parse request records. Single-line `key: value` plus multiline `key: |`
  blocks for body/response/detail. Legacy single-line files still parse.
  Multiline blocks end only at a known request-field header."
  [text]
  (loop [lines (str/split-lines (or text ""))
         acc {}]
    (if (empty? lines)
      acc
      (let [line (first lines)
            rest-lines (rest lines)]
        (if-let [[_ k v] (re-matches header-key-line line)]
          (if (and (= "|" v) (contains? multiline-field-keys k))
            (let [[content more]
                  (split-with (fn [l] (not (multiline-block-terminator? l)))
                              rest-lines)]
              (recur (vec more)
                     (assoc acc k (str/join "\n" content))))
            (recur rest-lines (assoc acc k v)))
          ;; Skip blank/malformed lines outside a block
          (recur rest-lines acc))))))

(defn file-map [file]
  (if (fs/regular-file? file)
    (parse-kv (slurp (str file)))
    {}))

(defn find-request-file [root id]
  (some (fn [state]
          (let [file (request-file root state id)]
            (when (fs/regular-file? file)
              {:state state :file file})))
        ["pending" "answered" "rejected"]))

(defn normalize-owner [owner]
  (let [o (str/lower-case (str/trim (or owner default-owner)))]
    (if (contains? valid-owners o) o default-owner)))

(defn request-owner
  "Owner for residual routing. Missing/blank defaults to Troubleshooter (front door)."
  [m]
  (normalize-owner (get m "owner")))

(defn render-field
  "Single-line `key: value` unless value has a newline — then `key: |` block."
  [k v]
  (let [s (str v)]
    (if (and (contains? multiline-field-keys k)
             (str/includes? s "\n"))
      (str k ": |\n" s)
      (str k ": " s))))

(defn render-request [m]
  (let [ordered ["id" "kind" "status" "owner" "created_at" "updated_at" "answered_at"
                 "routed_at" "body" "response" "detail"]
        emitted (set ordered)]
    (str
     (str/join "\n"
               (concat
                (keep (fn [k]
                        (when-let [v (not-empty (str (get m k "")))]
                          (render-field k v)))
                      ordered)
                (for [k (sort (remove emitted (keys m)))
                      :let [v (get m k)]
                      :when (not (str/blank? (str v)))]
                  (render-field k v))))
     "\n")))

(defn normalize-body [text]
  (str/trim (or text "")))

(defn body-error [body]
  (cond
    (str/blank? body) "Request body is empty."
    (> (count body) max-body-chars) (str "Request body exceeds " max-body-chars " characters.")
    :else nil))

(defn kind-error [kind]
  (when-not (contains? valid-kinds kind)
    "kind must be request (legacy command/question also accepted)."))

(defn normalize-kind [kind]
  (let [k (str/lower-case (str/trim (or kind "request")))]
    (if (contains? #{"command" "question" "request"} k)
      "request"
      k)))

(defn utc-stamp []
  (let [fmt (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'")
        zdt (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)]
    (.format zdt fmt)))

(defn next-request-id [root]
  (let [base (str "dashboard-" (utc-stamp))
        existing (for [state ["pending" "answered" "rejected"]
                       :let [dir (state-dir root state)]
                       :when (fs/directory? dir)
                       f (fs/list-dir dir)
                       :when (fs/regular-file? f)
                       :let [name (fs/file-name f)]
                       :when (str/starts-with? name base)]
                   name)]
    (if (empty? existing)
      (str base "-001")
      (let [nums (keep (fn [name]
                         (when-let [[_ n] (re-matches
                                           (re-pattern (str "\\Q" base "\\E-([0-9]{3})\\.request"))
                                           name)]
                           (Long/parseLong n)))
                       existing)
            n (inc (if (seq nums) (apply max nums) 0))]
        (format "%s-%03d" base n)))))

(defn create-request
  "Create a pending dashboard request owned by the Troubleshooter front door.
  Returns {:ok true :request m} or {:ok false :error msg}."
  [root {:keys [kind body owner]}]
  (let [kind (normalize-kind kind)
        body (normalize-body body)
        owner (normalize-owner (or owner default-owner))]
    (or (when-let [err (kind-error kind)]
          {:ok false :error err})
        (when-let [err (body-error body)]
          {:ok false :error err})
        (let [now (timestamp)
              id (next-request-id root)
              m {"id" id
                 "kind" kind
                 "status" "pending"
                 "owner" owner
                 "created_at" now
                 "updated_at" now
                 "body" body}
              file (request-file root "pending" id)]
          (if-not (valid-id? id)
            {:ok false :error "Generated request id is invalid."}
            (do
              (write-atomic! file (render-request m))
              {:ok true :request m}))))))

(defn list-request-files [root state]
  (let [dir (state-dir root state)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".request")))
           (sort-by fs/file-name)
           vec)
      [])))

(defn parse-progress-line [line]
  (let [line (str/trim (or line ""))]
    (when-not (str/blank? line)
      (if-let [[_ at text] (re-matches #"(\S+)\t(.*)" line)]
        {"at" at "text" text}
        {"at" "" "text" line}))))

(defn read-progress
  "Ordered interim notes for request id."
  [root state id]
  (let [file (progress-file root state id)]
    (if (fs/regular-file? file)
      (->> (str/split-lines (slurp (str file)))
           (keep parse-progress-line)
           vec)
      [])))

(defn project-root-from-request-file
  "…/project/.swarmforge/dashboard/requests/<state>/<id>.request → project"
  [file]
  (-> file fs/parent fs/parent fs/parent fs/parent fs/parent))

(defn request-summary
  ([file state] (request-summary file state (project-root-from-request-file file)))
  ([file state root]
   (let [m (file-map file)
         id (or (get m "id")
                (str/replace (fs/file-name file) #"\.request$" ""))]
     (merge m
            {"id" id
             "status" (or (get m "status") state)
             "kind" (get m "kind" "request")
             "owner" (request-owner m)
             "created_at" (get m "created_at" "")
             "updated_at" (get m "updated_at" "")
             "answered_at" (get m "answered_at" "")
             "routed_at" (get m "routed_at" "")
             "body" (get m "body" "")
             "response" (get m "response" "")
             "detail" (get m "detail" "")
             "progress" (read-progress root state id)}))))

(defn list-all-requests
  ([root] (list-all-requests root history-limit))
  ([root limit]
   (let [rows (vec
               (mapcat (fn [state]
                         (map #(request-summary % state root)
                              (list-request-files root state)))
                       ["pending" "answered" "rejected"]))
         sorted (sort-by (fn [r]
                           [(get r "created_at" "")
                            (get r "id" "")])
                         rows)]
     (if (and limit (pos? limit) (> (count sorted) limit))
       (vec (take-last limit sorted))
       (vec sorted)))))

(defn pending-requests
  ([root]
   (->> (list-request-files root "pending")
        (map #(request-summary % "pending" root))
        (sort-by (fn [r] [(get r "created_at" "") (get r "id" "")]))
        vec))
  ([root owner]
   (let [want (normalize-owner owner)]
     (vec (filter #(= want (request-owner %)) (pending-requests root))))))

(defn oldest-pending [root]
  (first (pending-requests root)))

(defn oldest-pending-for-owner [root owner]
  (first (pending-requests root owner)))

(defn rewrite-pending! [root id updates]
  (let [pending (request-file root "pending" id)]
    (cond
      (not (valid-id? id))
      {:ok false :error "Invalid request id."}

      (not (fs/regular-file? pending))
      (if-let [{:keys [state]} (find-request-file root id)]
        {:ok false :error (str "Request is not pending (status=" state ").")}
        {:ok false :error (str "Pending request not found: " id)})

      :else
      (let [now (timestamp)
            m (merge (file-map pending)
                     updates
                     {"status" "pending"
                      "updated_at" now})]
        (write-atomic! pending (render-request m))
        {:ok true :request (request-summary pending "pending" root)}))))

(defn route-to-sl
  "Re-own a pending request for Squad Leader product residual.
  Keeps status pending so the operator busy indicator stays on until SL answers."
  [root id]
  (let [pending (request-file root "pending" id)]
    (cond
      (not (valid-id? id))
      {:ok false :error "Invalid request id."}

      (not (fs/regular-file? pending))
      (if-let [{:keys [state]} (find-request-file root id)]
        {:ok false :error (str "Request is not pending (status=" state ").")}
        {:ok false :error (str "Pending request not found: " id)})

      :else
      (let [now (timestamp)
            m (file-map pending)
            already? (= product-owner (request-owner m))
            updates (cond-> {"owner" product-owner}
                      (not already?) (assoc "routed_at" now))
            result (rewrite-pending! root id updates)]
        (if (:ok result)
          (assoc result :routed (not already?) :already-sl already?)
          result)))))

(defn source-file [path]
  (let [file (fs/path path)
        file (if (fs/absolute? file) file (fs/path (fs/cwd) file))]
    (when (fs/regular-file? file)
      file)))

(defn move-progress-sidecar! [root id from-state to-state]
  (let [from (progress-file root from-state id)
        to (progress-file root to-state id)]
    (when (fs/regular-file? from)
      (fs/create-dirs (fs/parent to))
      (fs/move from to {:replace-existing true}))))

(defn move-resolved [root id from-file target-state updates]
  (let [now (timestamp)
        m (merge (file-map from-file)
                 updates
                 {"status" target-state
                  "updated_at" now})
        m (if (= "answered" target-state)
            (assoc m "answered_at" (or (get updates "answered_at") now))
            m)
        target (request-file root target-state id)]
    (write-atomic! target (render-request m))
    (move-progress-sidecar! root id "pending" target-state)
    (fs/delete-if-exists from-file)
    m))

(defn append-progress-note!
  "Interim status while request stays pending. Does not complete the request."
  [root id note-text]
  (cond
    (not (valid-id? id))
    {:ok false :error "Invalid request id."}

    :else
    (let [pending (request-file root "pending" id)
          note (str/trim (or note-text ""))]
      (cond
        (not (fs/regular-file? pending))
        (if-let [{:keys [state]} (find-request-file root id)]
          {:ok false :error (str "Request is not pending (status=" state ").")}
          {:ok false :error (str "Pending request not found: " id)})

        (str/blank? note)
        {:ok false :error "Progress note is empty."}

        (> (count note) max-body-chars)
        {:ok false :error (str "Progress note exceeds " max-body-chars " characters.")}

        :else
        (let [now (timestamp)
              pfile (progress-file root "pending" id)
              line (str now "\t" (str/replace note #"\t" " ") "\n")]
          (fs/create-dirs (fs/parent pfile))
          (spit (str pfile) line :append true)
          (rewrite-pending! root id {})
          {:ok true
           :request (request-summary pending "pending" root)
           :note {"at" now "text" note}})))))

(defn normalize-answer [_kind text]
  "Single answer rule: blank answers become Done (intent lives in request body)."
  (let [text (str/trim (or text ""))]
    (if (str/blank? text) "Done" text)))

(defn claims-empty-product-body?
  "True when answer asserts the request body was empty."
  [answer]
  (let [a (str/lower-case (str answer))]
    (boolean
     (and (re-find #"\bempty\b" a)
          (or (re-find #"\bbody\b" a)
              (re-find #"\brequest\b" a)
              (re-find #"\bproduct\b" a))))))

(defn answer-request
  "Answer a pending request. answer-text is the response body string.
  Returns {:ok true :request m} or {:ok false :error msg}."
  [root id answer-text]
  (cond
    (not (valid-id? id))
    {:ok false :error "Invalid request id."}

    :else
    (let [pending (request-file root "pending" id)]
      (cond
        (not (fs/regular-file? pending))
        (if-let [{:keys [state]} (find-request-file root id)]
          {:ok false :error (str "Request is not pending (status=" state ").")}
          {:ok false :error (str "Pending request not found: " id)})

        :else
        (let [m (file-map pending)
              kind (get m "kind" "request")
              body (str/trim (or (get m "body") ""))
              answer (normalize-answer kind answer-text)]
          (cond
            (> (count answer) max-body-chars)
            {:ok false :error (str "Answer exceeds " max-body-chars " characters.")}

            ;; Refuse false "empty body" answers when durable body is present
            (and (not (str/blank? body))
                 (claims-empty-product-body? answer))
            {:ok false
             :error (str "Request body is non-empty (" (count body)
                         " chars). Do not answer that the body is empty; "
                         "read the pending .request file or residual BODY / BODY_PREVIEW.")}

            :else
            {:ok true
             :request (move-resolved root id pending "answered"
                                     {"response" answer})}))))))

(defn reject-request
  [root id reason]
  (cond
    (not (valid-id? id))
    {:ok false :error "Invalid request id."}

    :else
    (let [pending (request-file root "pending" id)]
      (cond
        (not (fs/regular-file? pending))
        (if-let [{:keys [state]} (find-request-file root id)]
          {:ok false :error (str "Request is not pending (status=" state ").")}
          {:ok false :error (str "Pending request not found: " id)})

        :else
        (let [reason (let [r (str/trim (or reason ""))]
                       (if (str/blank? r) "rejected" r))]
          {:ok true
           :request (move-resolved root id pending "rejected"
                                   {"detail" reason
                                    "response" ""})})))))

(defn cancel-request
  [root id]
  (reject-request root id "cancelled-by-operator"))

(defn answer! [id answer-path]
  (let [root (fs/absolutize (project-root))
        file (source-file answer-path)]
    (when-not file
      (exit! 1 (str "Source file not found: " answer-path)))
    (let [result (answer-request root id (slurp (str file)))]
      (if (:ok result)
        (let [m (:request result)]
          (println "SQUAD_DASHBOARD_REQUEST:" (get m "id"))
          (println "STATE: answered")
          (println "KIND:" (get m "kind"))
          (println "RESPONSE:" (get m "response")))
        (exit! 2 (:error result))))))

(defn reject! [id reason-path]
  (let [root (fs/absolutize (project-root))
        file (source-file reason-path)]
    (when-not file
      (exit! 1 (str "Source file not found: " reason-path)))
    (let [result (reject-request root id (slurp (str file)))]
      (if (:ok result)
        (let [m (:request result)]
          (println "SQUAD_DASHBOARD_REQUEST:" (get m "id"))
          (println "STATE: rejected")
          (println "DETAIL:" (get m "detail")))
        (exit! 2 (:error result))))))

(defn cancel! [id]
  (let [root (fs/absolutize (project-root))
        result (cancel-request root id)]
    (if (:ok result)
      (let [m (:request result)]
        (println "SQUAD_DASHBOARD_REQUEST:" (get m "id"))
        (println "STATE: rejected")
        (println "DETAIL:" (get m "detail")))
      (exit! 2 (:error result)))))

(defn print-one-status! [root id]
  (when-not (valid-id? id)
    (exit! 2 "Invalid request id."))
  (if-let [{:keys [state file]} (find-request-file root id)]
    (let [m (request-summary file state root)]
      (println "REQUEST:" id)
      (println "STATE:" (get m "status" state))
      (println "KIND:" (get m "kind" "request"))
      (println "OWNER:" (request-owner m))
      (println "BODY:" (get m "body" ""))
      (doseq [n (get m "progress" [])]
        (println "NOTE:" (get n "at") (get n "text")))
      (when-not (str/blank? (get m "response" ""))
        (println "RESPONSE:" (get m "response")))
      (when-not (str/blank? (get m "detail" ""))
        (println "DETAIL:" (get m "detail")))
      (println "FILE:" (str file)))
    (exit! 1 (str "Request not found: " id))))

(defn print-all-status! [root]
  (doseq [r (list-all-requests root nil)]
    (println "REQUEST:" (get r "id"))
    (println "STATE:" (get r "status"))
    (println "KIND:" (get r "kind"))
    (println "OWNER:" (request-owner r))
    (println "BODY:" (get r "body"))
    (when-not (str/blank? (get r "response"))
      (println "RESPONSE:" (get r "response")))
    (println "---")))

(defn socket-value [root]
  (let [socket-file (fs/path root ".swarmforge" "tmux-socket")]
    (when (fs/regular-file? socket-file)
      (str/trim (slurp (str socket-file))))))

(defn wake-sl-for-product-request! [root request]
  "Best-effort: notify Squad Leader residual after route-to-sl."
  (when-let [socket (socket-value root)]
    (let [id (get request "id")
          body (get request "body" "")
          msg (str "Product request routed from Troubleshooter. Run squad_next.sh --residual-only.\n"
                   "REQUEST_ID: " id "\n"
                   "OWNER: squad-leader\n"
                   "BODY: " body "\n"
                   "COMMAND: squad_dashboard_request.sh answer " id " <answer-file>\n"
                   "Orchestrate theme/story work first; request stays pending until answer succeeds.")]
      (try
        (let [r (process/sh {:continue true}
                            "tmux" "-S" socket "send-keys" "-t" "swarmforge-squad-leader" "-l" msg)]
          (when (zero? (:exit r))
            (process/sh {:continue true}
                        "tmux" "-S" socket "send-keys" "-t" "swarmforge-squad-leader" "C-m")
            true))
        (catch Exception _ false)))))

(defn note! [id note-path]
  (let [root (fs/absolutize (project-root))
        file (source-file note-path)]
    (when-not file
      (exit! 1 (str "Source file not found: " note-path)))
    (let [result (append-progress-note! root id (slurp (str file)))]
      (if (:ok result)
        (let [m (:request result)
              n (:note result)]
          (println "SQUAD_DASHBOARD_REQUEST:" (get m "id"))
          (println "STATE: pending")
          (println "NOTE_AT:" (get n "at"))
          (println "NOTE:" (get n "text"))
          (println "PROGRESS_COUNT:" (count (get m "progress"))))
        (exit! 2 (:error result))))))

(defn route-to-sl! [id]
  (let [root (fs/absolutize (project-root))
        result (route-to-sl root id)]
    (if (:ok result)
      (let [m (:request result)
            woke? (wake-sl-for-product-request! root m)]
        (println "SQUAD_DASHBOARD_REQUEST:" (get m "id"))
        (println "STATE: pending")
        (println "OWNER:" (request-owner m))
        (println "ROUTED: squad-leader")
        (println "SL_NOTIFIED:" (if woke? "true" "false"))
        (println "NOTE: stay pending until squad_dashboard_request.sh answer; run residual as SL"))
      (exit! 2 (:error result)))))

(defn status! [maybe-id]
  (let [root (fs/absolutize (project-root))]
    (if maybe-id
      (print-one-status! root maybe-id)
      (print-all-status! root))))

(defn exact-count! [args n]
  (when-not (= n (count args))
    (exit! 1 usage-text)))

(def commands
  {"answer" (fn [args]
              (exact-count! args 3)
              (answer! (second args) (nth args 2)))
   "complete" (fn [args]
                (exact-count! args 3)
                (answer! (second args) (nth args 2)))
   "reject" (fn [args]
              (exact-count! args 3)
              (reject! (second args) (nth args 2)))
   "cancel" (fn [args]
              (exact-count! args 2)
              (cancel! (second args)))
   "route-to-sl" (fn [args]
                   (exact-count! args 2)
                   (route-to-sl! (second args)))
   "note" (fn [args]
            (exact-count! args 3)
            (note! (second args) (nth args 2)))
   "status" (fn [args]
              (when-not (<= 1 (count args) 2)
                (exit! 1 usage-text))
              (status! (second args)))})

(defn -main [& args]
  (if-let [command (commands (first args))]
    (command args)
    (exit! 1 usage-text)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
