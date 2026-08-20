(ns swarmforge.test-support
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def repo-root (fs/cwd))

(def scripts-dir (fs/path repo-root "swarmforge" "scripts"))

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn run
  [{:keys [dir env ok?]} & args]
  (let [result (apply sh/sh (concat args [:dir (str dir)
                                          :env (merge {"PATH" (System/getenv "PATH")
                                                       "GIT_CONFIG_NOSYSTEM" "1"
                                                       ;; Tests act as the main-git owner (daemon).
                                                       "SWARMFORGE_MAIN_GIT" "1"
                                                       "SWARMFORGE_MAIN_GIT_OWNER" "daemon"}
                                                      env)]))]
    (when (and (not (false? ok?)) (not= 0 (:exit result)))
      (throw (ex-info (str "Command failed: " (str/join " " args))
                      (assoc result :args args))))
    result))

(defn init-repo! [root]
  (run {:dir root} "git" "init" "-q")
  (run {:dir root} "git" "config" "user.email" "test@example.com")
  (run {:dir root} "git" "config" "user.name" "Test User")
  (write-file (fs/path root "README.md") "initial\n")
  (run {:dir root} "git" "add" "README.md")
  (run {:dir root} "git" "commit" "-q" "-m" "Initial commit"))

(defn git-branch-exists? [root branch]
  (not (str/blank? (:out (run {:dir root}
                              "git" "branch" "--list" branch)))))

(defn git-worktree-registered? [root worktree]
  (str/includes? (:out (run {:dir root} "git" "worktree" "list"))
                 (str worktree)))

(defn tmp-dir []
  (fs/create-temp-dir {:prefix "swarmforge-script-test."}))

(defn write-frame-ready!
  "Mark the product frame as present so per-card Start is allowed."
  [root]
  (write-file (fs/path root ".squad" "product") "frame_sha: abc1234\n"))

(defn script [name]
  (str (fs/path scripts-dir name)))

(def minimal-module-map
  (str "# Theme Module Map\n\n"
       "## Purpose\n\nSample purpose.\n\n"
       "## Dependency Rule\n\nDependencies point inward.\n\n"
       "## Use Cases (Business / Process Rules)\n\n"
       "### Use case: sample\n\n- **Intent:** sample.\n\n"
       "## UI (Interface Adapters)\n\nCLI prompts.\n\n"
       "## IO (Interface Adapters / Drivers)\n\nStdin/stdout.\n\n"
       "## Out of Scope\n\nDetailed APIs.\n"))

(def minimal-dependency-checker
  "Non-trivial product policy for tests."
  (str "{:allowed-dependencies {:greeting []\n"
       "                        :ui [:greeting]}\n"
       " :fail-on-cycles true\n"
       " :fail-on-violations true}\n"))

(def hollow-dependency-checker
  "{:allowed-dependencies {}\n :fail-on-cycles true\n :fail-on-violations true}\n")

(defn write-nontrivial-checker!
  "Install non-trivial root dependency-checker so implementer gates pass."
  [root]
  (write-file (fs/path root "dependency-checker.edn") minimal-dependency-checker))

(def implementer-gate-conf
  "Disable order/checker/plan user approval in fixture tests that only exercise implementer FSM."
  (str "max_transient_agents 10\n"
       "approval_required implementation-plan false\n"
       "approval_required implementation false\n"
       "approval_required implementation_order false\n"
       "approval_required dependency_checker false\n"))

(defn mark-implementation-plan-approved!
  "Skip the analyst/plan gate so later-stage fixtures can start at Gherkin."
  [root story-id]
  (spit (str (fs/path root ".squad/stories" story-id "packet"))
        (str "implementation_plan_path: .squad/stories/" story-id "/plan.md\n"
             "implementation_plan_sha: abcdef1234\n"
             "implementation_plan_approval: approved\n")
        :append true))

(defn wait-for-file [path timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (fs/exists? path) true
        (> (System/currentTimeMillis) deadline) false
        :else (do
                (Thread/sleep 50)
                (recur))))))

(defn http-post
  ([url] (http-post url ""))
  ([url body]
  (let [[_ host port path] (re-matches #"http://([^:/]+):([0-9]+)(/.*)" url)
        socket (java.net.Socket. host (Long/parseLong port))]
    (with-open [socket socket
                reader (java.io.BufferedReader.
                        (java.io.InputStreamReader. (.getInputStream socket) "UTF-8"))]
      (let [body-bytes (.getBytes body "UTF-8")
            request (str "POST " path " HTTP/1.1\r\n"
                         "Host: " host "\r\n"
                         "Content-Type: text/plain; charset=utf-8\r\n"
                         "Content-Length: " (alength body-bytes) "\r\n"
                         "Connection: close\r\n\r\n")]
        (.write (.getOutputStream socket) (.getBytes request "UTF-8"))
        (.write (.getOutputStream socket) body-bytes)
        (.flush (.getOutputStream socket))
        (let [status-line (.readLine reader)
              status (parse-long (second (re-find #"HTTP/[0-9.]+\s+([0-9]+)" status-line)))
              lines (line-seq reader)
              body (str/join "\n" (drop 1 (drop-while #(not= "" %) lines)))]
          {:status status :body body}))))))

(defn write-agent-status!
  ([root agent-id state]
   (write-agent-status! root agent-id state "2099-01-01T00:00:00Z"))
  ([root agent-id state updated-at]
   (write-file (fs/path root ".squad/agents" agent-id "status")
               (str "state: " state "\n"
                    "detail: test\n"
                    "updated_at: " updated-at "\n"))
   (write-file (fs/path root ".squad/agents" agent-id "heartbeat")
               (str "agent: " agent-id "\n"
                    "task_id: " agent-id "-task\n"
                    "state: " state "\n"
                    "detail: test\n"
                    "updated_at: " updated-at "\n"))))

(defn prepare-implementation-packet! [root _theme-id story-id]
  (write-file (fs/path root "features" (str story-id ".feature"))
              (str "Feature: " story-id "\n"))
  (write-file (fs/path root "qa" (str story-id ".md"))
              (str "# QA Procedure: " story-id "\n"))
  (run {:dir root} "git" "add" "stories" "features" "qa")
  (run {:dir root} "git" "commit" "-q" "-m" (str "Prepare packet artifacts for " story-id))
  (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
    (run {:dir root}
         (script "squad_packet.sh")
         "create"
         story-id
         (str story-id "-analysis")
         "master"
         sha)
    (run {:dir root}
         (script "squad_packet.sh")
         "approve"
         story-id
         "implementation-plan"
         "user approved plan")
    (run {:dir root}
         (script "squad_packet.sh")
         "attach"
         story-id
         "gherkin"
         (str story-id "-gherkin")
         "swarmforge-gherkin-writer-001"
         sha
         (str "features/" story-id ".feature"))
    (run {:dir root}
         (script "squad_packet.sh")
         "approve"
         story-id
         "gherkin"
         "user approved gherkin")
    (run {:dir root}
         (script "squad_packet.sh")
         "attach"
         story-id
         "qa-procedure"
         (str story-id "-qa-procedure")
         "swarmforge-qa-procedure-writer-001"
         sha
         (str "qa/" story-id ".md"))
    (run {:dir root}
         (script "squad_packet.sh")
         "approve"
         story-id
         "qa-procedure"
         "user approved qa procedure")
    (run {:dir root}
         (script "squad_packet.sh")
         "approve"
         story-id
         "implementation"
         "user approved implementation")
    sha))
