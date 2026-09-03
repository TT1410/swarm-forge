;; File and task HTML views. Loaded into pack-web.

(defn pretty-json [text]
  (try (json/generate-string (json/parse-string text) {:pretty true})
       (catch Exception _ text)))

(defn pretty-edn [text]
  (try (let [sw (java.io.StringWriter.)]
         (pprint/pprint (edn/read-string text) sw)
         (str sw))
       (catch Exception _ text)))

(defn code-escape [s]
  (-> (or s "")
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn apply-style [text pattern class-name]
  (let [matcher (.matcher pattern text)]
    (loop [last 0 out ""]
      (if (.find matcher)
        (let [start (.start matcher)
              end (.end matcher)
              piece (.group matcher)]
          (recur end (str out (subs text last start)
                          "<span class='" class-name "'>"
                          piece "</span>")))
        (str out (subs text last))))))

(defn colorize-code [source]
  (let [split-comment (fn [line]
                        (loop [idx 0 in-string? false escaped? false]
                          (if (>= idx (count line))
                            [line nil]
                            (let [ch (.charAt line idx)]
                              (cond
                                (and (not in-string?) (= ch \;))
                                [(subs line 0 idx) (subs line idx)]
                                escaped? (recur (inc idx) in-string? false)
                                (and in-string? (= ch \\)) (recur (inc idx) in-string? true)
                                (= ch \") (recur (inc idx) (not in-string?) false)
                                :else (recur (inc idx) in-string? false))))))
        [code-part comment-part] (split-comment (or source ""))
        escaped (code-escape code-part)
        string-pattern (java.util.regex.Pattern/compile "\"([^\"\\\\]|\\\\.)*\"")
        keyword-pattern (java.util.regex.Pattern/compile ":[a-zA-Z0-9\\-\\?!_\\./]+")]
    (str (-> escaped
             (apply-style string-pattern "str")
             (apply-style keyword-pattern "kw"))
         (when comment-part
           (str "<span class='cmt'>" (code-escape comment-part) "</span>")))))

(def gherkin-keywords
  ["Scenario Outline" "Feature" "Background" "Rule" "Scenario" "Examples"
   "Given" "When" "Then" "And" "But"])

(def gherkin-string-pattern
  (java.util.regex.Pattern/compile "\"([^\"\\\\]|\\\\.)*\""))

(def gherkin-placeholder-pattern
  (java.util.regex.Pattern/compile "&lt;[^&]+&gt;"))

(defn colorize-gherkin-rest [s]
  (-> (code-escape s)
      (apply-style gherkin-string-pattern "str")
      (apply-style gherkin-placeholder-pattern "ph")))

(defn gherkin-keyword-at [trimmed]
  (or (when (or (= trimmed "*") (str/starts-with? trimmed "* ")) "*")
      (some (fn [kw]
              (when (or (= trimmed kw)
                        (str/starts-with? trimmed (str kw " "))
                        (str/starts-with? trimmed (str kw ":")))
                kw))
            gherkin-keywords)))

(defn colorize-gherkin-line [source]
  (let [line (str/replace (or source "") "\t" "  ")
        trimmed (str/triml line)
        indent (subs line 0 (- (count line) (count trimmed)))]
    (cond
      (str/blank? trimmed) ""
      (str/starts-with? trimmed "#")
      (str (code-escape indent) "<span class='cmt'>" (code-escape trimmed) "</span>")
      (or (str/starts-with? trimmed "\"\"\"") (str/starts-with? trimmed "'''"))
      (str (code-escape indent) "<span class='str'>" (code-escape trimmed) "</span>")
      (str/starts-with? trimmed "@")
      (str (code-escape indent)
           (->> (str/split trimmed #"\s+")
                (map #(str "<span class='tag'>" (code-escape %) "</span>"))
                (str/join " ")))
      (str/starts-with? trimmed "|")
      (str (code-escape indent)
           (-> (code-escape trimmed)
               (apply-style gherkin-string-pattern "str")
               (str/replace "|" "<span class='tbl'>|</span>")))
      :else
      (if-let [kw (gherkin-keyword-at trimmed)]
        (str (code-escape indent)
             "<span class='kw'>" (code-escape kw) "</span>"
             (colorize-gherkin-rest (subs trimmed (count kw))))
        (colorize-gherkin-rest line)))))

(defn source-lines-html
  ([source] (source-lines-html source colorize-code))
  ([source colorize]
   (let [lines (str/split (or source "") #"\r?\n" -1)]
     (->> lines
          (map-indexed
           (fn [idx line]
             (let [line-html (colorize (str/replace (or line "") "\t" "  "))
                   visible (if (str/blank? line-html) "&nbsp;" line-html)]
               (str "<tr><td class='ln'>" (inc idx)
                    "</td><td class='code'><pre>" visible "</pre></td></tr>"))))
          (apply str)))))

(defn code-html [source]
  (str "<table class='src'>" (source-lines-html source) "</table>"))

(defn gherkin-html [source]
  (str "<table class='src'>" (source-lines-html source colorize-gherkin-line) "</table>"))

(defn printable-byte? [b]
  (let [n (bit-and (int b) 0xff)]
    (or (= n 9) (= n 10) (= n 13) (and (>= n 32) (<= n 126)))))

(defn binary-bytes? [bytes]
  (boolean (some (fn [b]
                   (let [n (bit-and (int b) 0xff)]
                     (or (zero? n) (and (< n 32) (not (#{9 10 13} n))))))
                 bytes)))

(defn hex-dump [bytes]
  (let [len (count bytes)]
    (str/join
     "\n"
     (map (fn [off]
            (let [chunk (subvec (vec bytes) off (min len (+ off 16)))
                  hex (str/join " " (map #(format "%02x" (bit-and (int %) 0xff)) chunk))
                  pad (apply str (repeat (* 3 (- 16 (count chunk))) " "))
                  chars (apply str (map (fn [b]
                                          (let [n (bit-and (int b) 0xff)]
                                            (if (and (>= n 32) (<= n 126))
                                              (char n)
                                              \.)))
                                        chunk))]
              (format "%08x  %s%s |%s|" off hex pad chars)))
          (range 0 len 16)))))

(defn read-file-bytes [path n]
  (with-open [in (io/input-stream (str path))]
    (let [buf (byte-array n)
          got (.read in buf)]
      (if (neg? got) (byte-array 0) (byte-array (take got buf))))))

(defn read-all-bytes [path]
  (with-open [in (io/input-stream (str path))]
    (let [out (java.io.ByteArrayOutputStream.)]
      (io/copy in out)
      (.toByteArray out))))

(def code-exts #{".clj" ".cljs" ".cljc" ".bb" ".edn" ".json"})

(defn file-view [path file]
  (let [name (str/lower-case (or path ""))]
    (cond
      (or (str/ends-with? name ".json") (str/ends-with? name ".edn")
          (str/ends-with? name ".clj") (str/ends-with? name ".cljs")
          (str/ends-with? name ".cljc") (str/ends-with? name ".bb"))
      (let [raw (slurp (str file))
            body (cond
                   (str/ends-with? name ".json") (pretty-json raw)
                   (str/ends-with? name ".edn") (pretty-edn raw)
                   :else raw)]
        {:kind "code" :html (code-html body)})

      (str/ends-with? name ".feature")
      {:kind "code" :html (gherkin-html (slurp (str file)))}

      (or (str/ends-with? name ".md") (str/ends-with? name ".txt"))
      {:kind "text" :text (slurp (str file))}

      :else
      (let [head (read-file-bytes file 8192)]
        (if (binary-bytes? head)
          {:kind "binary" :text (hex-dump (vec (read-all-bytes file)))}
          {:kind "text" :text (try (slurp (str file))
                                   (catch Exception _ (hex-dump (vec (read-all-bytes file)))))})))))

(defn task-page [name text audits project]
  (let [name-html (html-escape name)
        qs (str "name=" (java.net.URLEncoder/encode (str name) "UTF-8")
                (when-not (str/blank? project)
                  (str "&project=" (java.net.URLEncoder/encode (str project) "UTF-8"))))
        audit-html (if (seq audits)
                     (str/join
                      (map (fn [item]
                             (str "<section class=\"audit\"><h2>"
                                  (html-escape (:label item))
                                  "</h2><pre>"
                                  (html-escape (:text item))
                                  "</pre></section>"))
                           audits))
                     "<p class=\"note\">No audits yet.</p>")]
    (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
         "<title>" name-html "</title>"
         "<style>html,body{height:100%;margin:0;display:flex;flex-direction:column;"
         "background:#f8f8f5;color:#1e221f;font-family:ui-sans-serif,system-ui,sans-serif}"
         "header{display:flex;align-items:center;gap:8px;padding:8px 10px;"
         "background:linear-gradient(180deg,#eceee8,#e0e3dc);border-bottom:1px solid #d5d9d2}"
         "h1{margin:0;font-size:14px;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
         "h2{margin:0 0 6px;font-size:11px;text-transform:uppercase;letter-spacing:.04em;color:#68726c}"
         "button{border:1px solid #9aa59e;background:#fff;padding:5px 10px;border-radius:7px;"
         "font-size:12px;cursor:pointer}"
         "main{flex:1;min-height:0;overflow:auto;padding:12px;display:flex;flex-direction:column;gap:12px}"
         "pre{margin:0;padding:10px;background:#fffef9;border:1px solid #d5d9d2;border-radius:8px;"
         "white-space:pre-wrap;overflow:auto;font:12px/1.4 ui-monospace,Menlo,monospace}"
         ".note{color:#68726c;font-size:12px}"
         "#tree{font:12px/1.4 ui-sans-serif,system-ui,sans-serif}"
         ".dir-row{display:flex;align-items:center;gap:6px;padding:2px 0}"
         ".dir-row button.toggle{padding:0 6px;min-width:1.6em}"
         ".dir-row button.leaf{border:0;background:none;text-decoration:underline;color:#3d5a45;padding:0}"
         ".children{margin-left:1.2rem}"
         "#file-view{display:none}"
         "#file-view.open{display:block}"
         "#file-body{margin:0;padding:10px;overflow:auto;white-space:pre-wrap;"
         "font:12px/1.4 ui-monospace,Menlo,monospace;background:#fffef9;border:1px solid #d5d9d2;border-radius:8px}"
         ".src{border-collapse:collapse;width:100%;font:12px/1.35 ui-monospace,Menlo,monospace}"
         ".ln{width:52px;padding:0 8px;background:#eef2f7;color:#6b7280;text-align:right;vertical-align:top;border-right:1px solid #d1d5db;user-select:none}"
         ".code{padding:0 10px;vertical-align:top}"
         ".code pre{margin:0;white-space:pre}"
         ".cmt{color:#6b7280}.str{color:#b45309}.kw{color:#1d4ed8}"
         ".tag{color:#7c3aed}.tbl{color:#0f766e}.ph{color:#0e7490}"
         "</style></head><body>"
         "<header><h1>" name-html "</h1></header>"
         "<main>"
         "<section><h2>Task</h2><pre id=\"task-body\">" (html-escape text) "</pre></section>"
         "<section><h2>Audits</h2>" audit-html "</section>"
         "<section id=\"dir-panel\"><h2>Directory</h2>"
         "<div id=\"tree\"></div>"
         "<div id=\"file-view\"><h2 id=\"file-name\"></h2>"
         "<div id=\"file-body\"></div></div></section>"
         "</main>"
         "<script>"
         "const qs=" (json/generate-string qs) ";"
         "function el(tag, attrs, kids){"
         "const n=document.createElement(tag);"
         "Object.keys(attrs||{}).forEach(k=>n.setAttribute(k, attrs[k]));"
         "(kids||[]).forEach(c=>n.appendChild(typeof c===\"string\"?document.createTextNode(c):c));"
         "return n;}"
         "async function openFile(path){"
         "const f=await fetch(\"/api/file?\"+qs+\"&path=\"+encodeURIComponent(path));"
         "const body=f.ok?await f.json():{text:\"Not found\",path:path};"
         "const w=window.open(\"about:blank\",\"file-\"+encodeURIComponent(path)+\"-\"+Date.now(),"
         "\"resizable=yes,scrollbars=yes,width=780,height=640\");"
         "if(!w)return;"
         "w.document.open();"
         "w.document.write(\"<html><head><title></title><style>"
         "html,body{height:100%;margin:0;display:flex;flex-direction:column;background:#f8f8f5;color:#1e221f;"
         "font-family:ui-sans-serif,system-ui,sans-serif}"
         "header{flex:0 0 auto;padding:8px 10px;background:#eceee8;border-bottom:1px solid #d5d9d2;font-weight:600;font-size:13px}"
         "#file-body{flex:1;margin:0;padding:12px;overflow:auto;white-space:pre-wrap;"
         "font:12px/1.4 ui-monospace,Menlo,monospace;background:#fffef9}"
         ".src{border-collapse:collapse;width:100%;font:12px/1.35 ui-monospace,Menlo,monospace}"
         ".ln{width:52px;padding:0 8px;background:#eef2f7;color:#6b7280;text-align:right;vertical-align:top}"
         ".code{padding:0 10px;vertical-align:top}.code pre{margin:0;white-space:pre}"
         ".cmt{color:#6b7280}.str{color:#b45309}.kw{color:#1d4ed8}.tag{color:#7c3aed}.ph{color:#0f766e}.tbl{color:#334155}"
         "</style></head><body><header></header><div id=file-body></div></body></html>\");"
         "w.document.close();"
         "const title=body.path||path;"
         "w.document.title=title;"
         "w.document.querySelector(\"header\").textContent=title;"
         "const box=w.document.getElementById(\"file-body\");"
         "if(body.html){box.innerHTML=body.html;}else{box.textContent=body.text||\"\";}"
         "}"
         "async function loadTree(path, host){"
         "const r=await fetch(\"/api/tree?\"+qs+\"&path=\"+encodeURIComponent(path||\"\"));"
         "const data=r.ok?await r.json():{entries:[]};"
         "host.replaceChildren();"
         "(data.entries||[]).forEach(item=>{"
         "const row=el(\"div\",{class:\"dir-row\"});"
         "if(item.dir){"
         "const btn=el(\"button\",{type:\"button\",class:\"toggle\"},[\"+\"]);"
         "const kids=el(\"div\",{class:\"children\"});"
         "kids.hidden=true;"
         "btn.onclick=async()=>{"
         "if(kids.hidden){btn.textContent=\"−\";kids.hidden=false;"
         "if(!kids.dataset.loaded){await loadTree(item.path, kids);kids.dataset.loaded=\"1\";}}"
         "else{btn.textContent=\"+\";kids.hidden=true;}};"
         "row.append(btn, document.createTextNode(item.name));"
         "host.append(row, kids);"
         "}else{"
         "const btn=el(\"button\",{type:\"button\",class:\"leaf\"},[item.name]);"
         "btn.onclick=()=>openFile(item.path);"
         "row.appendChild(btn);host.appendChild(row);}"
         "});}"
         "loadTree(\"\", document.getElementById(\"tree\"));"
         "</script></body></html>")))

(defn get-task [root uri]
  (let [name (task-query-name uri)
        project (query-value uri "project")
        task (when (safe-paths/task-name? name)
               (or (board-task-named root name)
                   (when (or (fs/regular-file? (safe-paths/task-path! (fs/path root "tasks") name ".md"))
                             (fs/regular-file? (safe-paths/task-path! (fs/path root ".swarmforge" "board") name ".txt")))
                     {:name name :id name :lane "waiting"})))]
    (if task
      (let [text (task-document root name)
            audits (list-card-audits root (or (:id task) name))]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (task-page name (if (str/blank? text) (str name "\n") text) audits project)})
      {:status 404 :body "Not found"})))

(defn get-api-tree [root uri]
  (let [name (query-value uri "name")
        rel (or (query-value uri "path") "")
        task (when (safe-paths/task-name? name) (board-task-named root name))
        wt (when task (card-worktree root task))
        dir (when wt (resolve-under wt rel))]
    (if (and dir (fs/directory? dir))
      {:status 200
       :headers {"Content-Type" "application/json; charset=utf-8"}
       :body (json/generate-string
              {:path rel
               :entries (mapv (fn [item]
                                (assoc item :path
                                       (str/replace
                                        (str (fs/path (or rel "") (:name item)))
                                        #"^/" "")))
                              (tree-entries dir))})}
      {:status 404 :body "Not found"})))

(defn get-api-file [root uri]
  (let [name (query-value uri "name")
        rel (query-value uri "path")
        task (when (safe-paths/task-name? name) (board-task-named root name))
        wt (when task (card-worktree root task))
        file (when (and wt (not (str/blank? rel))) (resolve-under wt rel))]
    (if (and file (fs/regular-file? file))
      {:status 200
       :headers {"Content-Type" "application/json; charset=utf-8"}
       :body (json/generate-string (merge {:path rel} (file-view rel file)))}
      {:status 404 :body "Not found"})))

(defn html-escape [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn get-file-page [root uri]
  (let [name (query-value uri "name")
        rel (query-value uri "path")
        task (when (safe-paths/task-name? name) (board-task-named root name))
        wt (when task (card-worktree root task))
        file (when (and wt (not (str/blank? rel))) (resolve-under wt rel))
        payload (if (and file (fs/regular-file? file))
                  (file-view rel file)
                  {:kind "text" :text "Not found"})
        title (html-escape (or rel "file"))
        inner (if (:html payload)
                (:html payload)
                (str "<pre>" (html-escape (or (:text payload) "")) "</pre>"))]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                "<title>" title "</title>"
                "<style>html,body{height:100%;margin:0;display:flex;flex-direction:column;"
                "background:#f8f8f5;color:#1e221f;font-family:ui-sans-serif,system-ui,sans-serif}"
                "header{flex:0 0 auto;padding:8px 10px;background:#eceee8;border-bottom:1px solid #d5d9d2;"
                "font-weight:600;font-size:13px}"
                "#file-body{flex:1;margin:0;padding:12px;overflow:auto;white-space:pre-wrap;"
                "font:12px/1.4 ui-monospace,Menlo,monospace;background:#fffef9}"
                ".src{border-collapse:collapse;width:100%;font:12px/1.35 ui-monospace,Menlo,monospace}"
                ".ln{width:52px;padding:0 8px;background:#eef2f7;color:#6b7280;text-align:right;vertical-align:top}"
                ".code{padding:0 10px;vertical-align:top}.code pre{margin:0;white-space:pre}"
                ".cmt{color:#6b7280}.str{color:#b45309}.kw{color:#1d4ed8}</style></head><body>"
                "<header>" title "</header>"
                "<div id=\"file-body\">" inner "</div></body></html>")}))
