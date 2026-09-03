;; Chat and clarifications. Loaded into pack-web.

(declare json-ok)

(defn chat-pending-dir [root]
  (fs/path root ".swarmforge" "dashboard" "requests" "pending"))

(defn chat-done-dir [root]
  (fs/path root ".swarmforge" "dashboard" "requests" "done"))

(defn chat-files [dir]
  (if (fs/directory? dir)
    (->> (fs/list-dir dir)
         (filter #(str/ends-with? (fs/file-name %) ".request"))
         (sort-by str)
         vec)
    []))

(defn parse-chat [path]
  (let [raw (slurp (str path))
        [header body] (str/split raw #"\n\n" 2)
        headers (into {}
                      (for [line (str/split-lines header)
                            :let [[k v] (str/split line #": " 2)]
                            :when (and k v)]
                        [k v]))]
    {:id (get headers "id")
     :status (get headers "status")
     :body (or body "")
     :response (str/replace (get headers "response" "") #"\\n" "\n")
     :created_at (get headers "created_at")}))

(defn list-chat [root]
  (vec (concat (map parse-chat (chat-files (chat-pending-dir root)))
               (map parse-chat (chat-files (chat-done-dir root))))))

(defn clar-pending-dir [root]
  (fs/path root ".swarmforge" "dashboard" "clarifications" "pending"))

(defn clar-done-dir [root]
  (fs/path root ".swarmforge" "dashboard" "clarifications" "done"))

(defn parse-clarification [path]
  (let [raw (slurp (str path))
        [header body] (str/split raw #"\n\n" 2)
        headers (into {}
                      (for [line (str/split-lines header)
                            :let [[k v] (str/split line #": " 2)]
                            :when (and k v)]
                        [k v]))]
    {:id (get headers "id")
     :status (get headers "status")
     :role (get headers "role")
     :body (or body "")
     :response (str/replace (get headers "response" "") #"\\n" "\n")
     :created_at (get headers "created_at")}))

(defn list-clarifications [root]
  (vec (concat (map parse-clarification (chat-files (clar-pending-dir root)))
               (map parse-clarification (chat-files (clar-done-dir root))))))

(defn chat-id []
  (str "req-" (str/replace (str (java.time.Instant/now)) #"[^0-9A-Za-z]" "")))

(defn chat-wake [id text]
  (if (str/includes? (or text "") "\n")
    (str "[" id "]\n" text)
    (str "[" id "] " text)))

(defn clar-wake [id role question answer]
  (str "[" id "]\n"
       "Clarification requested from: " role "\n"
       "Question:\n" (str/trimr (or question "")) "\n"
       "Answer:\n" (str/trimr (or answer ""))))

(defn write-chat-request! [root text]
  (let [id (chat-id)
        file (fs/path (chat-pending-dir root) (str id ".request"))]
    (fs/create-dirs (fs/parent file))
    (spit (str file)
          (str "id: " id "\n"
               "status: pending\n"
               "created_at: " (.format java.time.format.DateTimeFormatter/ISO_INSTANT
                                       (java.time.Instant/now)) "\n"
               "\n"
               text
               (when-not (str/ends-with? text "\n") "\n")))
    id))

(defn post-chat [root body]
  (let [{:keys [text]} (json/parse-string (or body "{}") true)
        text (or text "")]
    (when-not (str/blank? text)
      (let [id (write-chat-request! root text)]
        (inject-master! root (chat-wake id text))))
    (json-ok)))

(defn clar-pending-file [root id]
  (safe-paths/id-path! (clar-pending-dir root) id ".request"))

(defn render-clarification [{:keys [id status role body response created_at]}]
  (str "id: " id "\n"
       "status: " status "\n"
       (when-not (str/blank? role) (str "role: " role "\n"))
       "created_at: " created_at "\n"
       (when-not (str/blank? response)
         (str "response: " (str/replace response #"\n" (constantly "\\n")) "\n"))
       "\n"
       (or body "")
       (when-not (str/ends-with? (or body "") "\n") "\n")))

(defn answer-clarification! [root id text]
  (safe-paths/require-internal-id! id)
  (let [src (clar-pending-file root id)]
    (when-not (fs/regular-file? src)
      (throw (ex-info (str "Unknown clarification: " id) {:http-status 404})))
    (let [entry (parse-clarification src)
          dest (safe-paths/id-path! (clar-done-dir root) id ".request")
          role (:role entry)]
      (fs/create-dirs (fs/parent dest))
      (spit (str dest) (render-clarification (assoc entry
                                                   :status "done"
                                                   :response text)))
      (fs/delete-if-exists src)
      (inject-role! root role (clar-wake id role (:body entry) text)))))

(defn clarification-route [uri]
  (let [path (first (str/split (or uri "") #"\?"))]
    (when-let [[_ id] (re-matches #"/api/clarifications/([^/]+)/answer" path)]
      (safe-paths/require-internal-id!
       (java.net.URLDecoder/decode id "UTF-8")))))

(defn post-clarification [root uri body]
  (if-let [id (clarification-route uri)]
    (let [text (or (:text (json/parse-string (or body "{}") true)) "")]
      (answer-clarification! root id text)
      (json-ok))
    {:status 404 :body "Not found"}))
