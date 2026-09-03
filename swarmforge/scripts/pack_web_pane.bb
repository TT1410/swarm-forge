;; Pane capture and agent session pages. Loaded into pack-web.

(defn merge-pane-capture [hist vis]
  (let [h (or hist "")
        v (or vis "")]
    (cond
      (str/blank? v) (not-empty h)
      (str/blank? h) v
      (str/includes? h v) h
      (str/starts-with? v h) v
      :else
      (let [h-trim (str/replace h #"\n+\z" "")
            v-lines (str/split-lines v)
            overlap (loop [n (count v-lines)]
                      (cond
                        (zero? n) nil
                        (str/ends-with? h-trim (str/join "\n" (take n v-lines))) n
                        :else (recur (dec n))))]
        (if overlap
          (let [rest (str/join "\n" (drop overlap v-lines))]
            (if (str/blank? rest)
              h
              (str h-trim "\n" rest (when (str/ends-with? v "\n") "\n"))))
          (str h-trim "\n" v))))))

(defn tmux-capture [socket target]
  (try
    (let [history (sh "tmux" "-S" socket "capture-pane" "-p" "-J" "-t" target
                      "-S" (str "-" pane-capture-lines))
          visible (sh "tmux" "-S" socket "capture-pane" "-p" "-J" "-t" target)
          hist (when (zero? (:exit history)) (:out history))
          vis (when (zero? (:exit visible)) (:out visible))]
      (merge-pane-capture hist vis))
    (catch Exception _)))

(defn capture-pane [root role]
  (when-let [row (role-row root role)]
    (let [socket (tmux-socket root)]
      (when socket
        (or (tmux-capture socket (pane-target row))
            (tmux-capture socket (session-name row)))))))

(defn live-pane-text [root role]
  (or *pane-text*
      (capture-pane root role)
      (recorded-pane root role)))

(defn pane-files [root role]
  (let [dir (safe-paths/id-path! (fs/path root ".swarmforge" "sessions") role "")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (map #(fs/path % "pane.txt"))
           (filter fs/regular-file?)
           vec)
      [])))

(defn in-process-task [root role]
  (when-let [worktree (worktree-for-role root role)]
    (some #(get-in (parse-message %) [:headers "task"])
          (in-process-files (in-process-dir worktree)))))

(defn pane-for-task [files task]
  (when task
    (some #(when (= task (fs/file-name (fs/parent %))) %) files)))

(defn recorded-pane [root role]
  (let [direct (fs/path (safe-paths/id-path! (fs/path root ".swarmforge" "sessions") role "")
                        "pane.txt")]
    (if (fs/regular-file? direct)
      (slurp (str direct))
      (let [files (pane-files root role)
            chosen (or (pane-for-task files (in-process-task root role))
                       (last (sort-by str files)))]
        (when chosen
          (slurp (str chosen)))))))

(defn pane-content [root role]
  (or (not-empty (capture-pane root role))
      (not-empty (recorded-pane root role))
      (str "(no pane capture for " role ")\n")))

(defn project-query [project]
  (when (not-empty project)
    (str "?project=" (java.net.URLEncoder/encode (str project) "UTF-8"))))

(defn pane-page [role snapshot & [project]]
  (let [role-html (html-escape role)
        pane-url (str "/api/agents/" role-html "/pane" (or (project-query project) ""))]
    (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
         "<title>Agent " role-html "</title>"
         "<style>html,body{height:100%;margin:0;overflow:hidden;background:#111;color:#f4f4f4;"
         "font-family:ui-monospace,Menlo,monospace}"
         "body{display:flex;flex-direction:column}"
         "header{height:42px;box-sizing:border-box;padding:10px 12px;border-bottom:1px solid #333;flex:0 0 auto}"
         "h1{font:inherit;margin:0;font-size:14px}"
         "#pane{flex:1 1 auto;margin:0;padding:12px;white-space:pre-wrap;overflow:auto;"
         "min-height:0;height:calc(100vh - 42px);max-height:calc(100vh - 42px)}</style></head>"
         "<body><header><h1>" role-html "</h1></header>"
         "<pre id=\"pane\">" (html-escape snapshot) "</pre>"
         "<script>(function(){"
         "const pane=document.getElementById('pane');"
         "let stickBottom=true;"
         "let firstPaint=true;"
         "function nearBottom(){"
         "return (pane.scrollHeight-pane.scrollTop-pane.clientHeight)<=64;}"
         "function toEnd(){pane.scrollTop=pane.scrollHeight;stickBottom=true;}"
         "function toEndSoon(){"
         "toEnd();requestAnimationFrame(toEnd);"
         "setTimeout(toEnd,0);setTimeout(toEnd,50);setTimeout(toEnd,200);}"
         "pane.addEventListener('scroll',function(){stickBottom=nearBottom();},{passive:true});"
         "async function refresh(){"
         "const r=await fetch('" pane-url "',{cache:'no-store'});"
         "const text=await r.text();"
         "const changed=text!==pane.textContent;"
         "if(changed){pane.textContent=text;}"
         "if(firstPaint||stickBottom){toEndSoon();firstPaint=false;}"
         "}"
         "refresh();setInterval(refresh,1000);"
         "window.addEventListener('load',toEndSoon);"
         "window.addEventListener('pageshow',toEndSoon);"
         "})();</script></body></html>")))

(defn agent-role [uri]
  (when-let [[_ role] (re-matches #"/agent/([^/]+)"
                                 (first (str/split (or uri "") #"\?")))]
    (java.net.URLDecoder/decode role "UTF-8")))

(defn agent-pane-role [uri]
  (when-let [[_ role] (re-matches #"/api/agents/([^/]+)/pane"
                                 (first (str/split (or uri "") #"\?")))]
    (java.net.URLDecoder/decode role "UTF-8")))

(defn get-agent [root uri]
  (if-let [role (some-> (agent-role uri)
                        (#(when (safe-paths/internal-id? %) %)))]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (pane-page role (pane-content root role) (query-value uri "project"))}
    {:status 404 :body "Not found"}))

(defn get-agent-pane [root uri]
  (if-let [role (some-> (agent-pane-role uri)
                        (#(when (safe-paths/internal-id? %) %)))]
    {:status 200
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body (pane-content root role)}
    {:status 404 :body "Not found"}))
