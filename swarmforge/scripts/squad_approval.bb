#!/usr/bin/env bb

(ns squad-approval
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def script-dir (fs/parent *file*))
(load-file (str (fs/path script-dir "squad_config.bb")))

(def usage-text
  (str "Usage:\n"
       "  squad_approval.sh required <gate>\n"
       "  squad_approval.sh request <approval-id> <theme|story> <target-id> <gate> <title> <reason...>\n"
       "  squad_approval.sh approve <approval-id> <detail...>\n"
       "  squad_approval.sh reject <approval-id> <reason...>\n"
       "  squad_approval.sh status [approval-id]\n"))

(def valid-id #"[A-Za-z0-9][A-Za-z0-9._-]*")
(def valid-target-kinds #{"theme" "story"})
(def valid-gates #{"theme" "story" "gherkin" "qa_procedure" "qa-procedure" "implementation"
                   "code_review" "code-review" "hardening" "qa" "architecture" "final"})

(defn exit! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn sh-continue [& args]
  (apply process/sh (concat [{:continue true}] args)))

(defn project-root []
  (let [configured (not-empty (System/getenv "SWARMFORGE_PROJECT_ROOT"))
        configured-roles (when configured (fs/path configured ".swarmforge" "roles.tsv"))
        cwd (fs/cwd)
        direct (fs/path cwd ".swarmforge" "roles.tsv")]
    (cond
      (and configured (fs/exists? configured-roles)) (fs/path configured)
      (fs/exists? direct) cwd
      :else (let [git-root (str/trim (:out (sh-continue "git" "rev-parse" "--show-toplevel")))]
              (if (and (not (str/blank? git-root))
                       (fs/exists? (fs/path git-root ".swarmforge" "roles.tsv")))
                (fs/path git-root)
                (exit! 1 "Cannot find SwarmForge project root"))))))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn validate-id! [kind value]
  (when-not (re-matches valid-id value)
    (exit! 2 (str kind " must use letters, digits, dots, underscores, and hyphens.")))
  (when (or (str/includes? value "/") (str/includes? value "\\"))
    (exit! 2 (str kind " may not contain path separators."))))

(defn validate-gate! [gate]
  (validate-id! "Approval gate" gate)
  (when-not (contains? valid-gates gate)
    (exit! 2 "Approval gate must be theme, story, gherkin, qa-procedure, implementation, code-review, hardening, qa, architecture, or final.")))

(defn write-atomic! [file content]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                  :prefix (str "." (fs/file-name file) ".")})]
    (spit (str tmp) content)
    (fs/move tmp file {:replace-existing true})))

(defn approvals-dir [root state]
  (fs/path root ".squad" "approvals" state))

(defn pending-file [root approval-id]
  (fs/path (approvals-dir root "pending") (str approval-id ".approval")))

(defn approved-file [root approval-id]
  (fs/path (approvals-dir root "approved") (str approval-id ".approval")))

(defn rejected-file [root approval-id]
  (fs/path (approvals-dir root "rejected") (str approval-id ".approval")))

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(defn file-map [file]
  (into {}
        (keep (fn [line]
                (let [[k v] (str/split line #": " 2)]
                  (when (and k v) [k v]))))
        (str/split-lines (slurp (str file)))))

(defn approval-file [root approval-id]
  (some (fn [file]
          (when (fs/exists? file) file))
        [(pending-file root approval-id)
         (approved-file root approval-id)
         (rejected-file root approval-id)]))

(defn packet-file [root story-id]
  (fs/path root ".squad" "stories" story-id "packet"))

(defn theme-status-file [root theme-id]
  (fs/path root ".squad" "themes" theme-id "status"))

(defn target-exists? [root target-kind target-id]
  (case target-kind
    "theme" (fs/exists? (theme-status-file root target-id))
    "story" (fs/exists? (packet-file root target-id))
    false))

(defn packet-gate [gate]
  (str/replace gate "_" "-"))

(defn command-for [target-kind target-id gate detail]
  (case target-kind
    "theme" [(str (fs/path script-dir "squad_theme.sh")) "approve" target-id gate detail]
    "story" [(str (fs/path script-dir "squad_packet.sh")) "approve" target-id (packet-gate gate) detail]))

(defn required! [gate]
  (validate-gate! gate)
  (let [root (fs/absolutize (project-root))
        required? (squad-approval-required? root gate)]
    (println "APPROVAL_GATE:" gate)
    (println "REQUIRED:" required?)))

(defn request! [approval-id target-kind target-id gate title reason-parts]
  (validate-id! "Approval id" approval-id)
  (when-not (contains? valid-target-kinds target-kind)
    (exit! 2 "Approval target kind must be theme or story."))
  (validate-id! "Target id" target-id)
  (validate-gate! gate)
  (let [root (fs/absolutize (project-root))
        file (pending-file root approval-id)
        reason (str/replace (str/join " " reason-parts) #"\R+" " ")
        reason (if (str/blank? reason) "approval requested" reason)
        now (timestamp)]
    (when-not (target-exists? root target-kind target-id)
      (exit! 1 (str "Approval target not found: " target-kind " " target-id)))
    (when (approval-file root approval-id)
      (exit! 2 (str "Approval already exists: " approval-id)))
    (write-atomic! file
                   (str "approval_id: " approval-id "\n"
                        "target_kind: " target-kind "\n"
                        "target_id: " target-id "\n"
                        "gate: " gate "\n"
                        "state: pending\n"
                        "title: " title "\n"
                        "reason: " reason "\n"
                        "created_at: " now "\n"
                        "approve_command: " (str/join " " (command-for target-kind target-id gate "approved-by-user")) "\n"))
    (println "SQUAD_APPROVAL:" approval-id)
    (println "STATE: pending")
    (println "REQUIRED:" (squad-approval-required? root gate))
    (println "FILE:" (str file))))

(defn move-with-state! [source target state detail]
  (let [content (slurp (str source))
        content (-> content
                    (str/replace #"(?m)^state: .*$" (str "state: " state))
                    (str "resolved_at: " (timestamp) "\n"
                         "resolution_detail: " detail "\n"))]
    (write-atomic! target content)
    (fs/delete-if-exists source)))

(defn approve! [approval-id detail-parts]
  (validate-id! "Approval id" approval-id)
  (let [root (fs/absolutize (project-root))
        source (pending-file root approval-id)]
    (when-not (fs/regular-file? source)
      (exit! 1 (str "Pending approval not found: " approval-id)))
    (let [approval (file-map source)
          target-kind (get approval "target_kind")
          target-id (get approval "target_id")
          gate (get approval "gate")
          detail (str/replace (str/join " " detail-parts) #"\R+" " ")
          detail (if (str/blank? detail) "approved-by-user" detail)
          command (command-for target-kind target-id gate detail)
          result (apply process/sh (concat [{:dir (str root) :continue true}] command))]
      (when-not (zero? (:exit result))
        (exit! (:exit result)
               (str "Approval transition failed: " approval-id)
               (:err result)))
      (move-with-state! source (approved-file root approval-id) "approved" detail)
      (println "SQUAD_APPROVAL:" approval-id)
      (println "STATE: approved")
      (println "TARGET:" target-id)
      (println "GATE:" gate))))

(defn reject! [approval-id reason-parts]
  (validate-id! "Approval id" approval-id)
  (let [root (fs/absolutize (project-root))
        source (pending-file root approval-id)
        reason (str/replace (str/join " " reason-parts) #"\R+" " ")
        reason (if (str/blank? reason) "rejected-by-user" reason)]
    (when-not (fs/regular-file? source)
      (exit! 1 (str "Pending approval not found: " approval-id)))
    (move-with-state! source (rejected-file root approval-id) "rejected" reason)
    (println "SQUAD_APPROVAL:" approval-id)
    (println "STATE: rejected")))

(defn print-one-status! [root approval-id]
  (validate-id! "Approval id" approval-id)
  (if-let [file (approval-file root approval-id)]
    (let [approval (file-map file)]
      (println "APPROVAL:" approval-id)
      (println "STATE:" (get approval "state" "unknown"))
      (println "TARGET_KIND:" (get approval "target_kind" "unknown"))
      (println "TARGET_ID:" (get approval "target_id" "unknown"))
      (println "GATE:" (get approval "gate" "unknown"))
      (println "TITLE:" (get approval "title" ""))
      (println "FILE:" (str file)))
    (exit! 1 (str "Approval not found: " approval-id))))

(defn print-all-status! [root]
  (doseq [state ["pending" "approved" "rejected"]]
    (let [dir (approvals-dir root state)
          files (if (fs/exists? dir)
                  (sort (filter fs/regular-file? (fs/list-dir dir)))
                  [])]
      (doseq [file files]
        (let [approval-id (str/replace (fs/file-name file) #"\.approval$" "")]
          (print-one-status! root approval-id))))))

(defn status! [maybe-approval-id]
  (let [root (fs/absolutize (project-root))]
    (if maybe-approval-id
      (print-one-status! root maybe-approval-id)
      (print-all-status! root))))

(defn -main [& args]
  (case (first args)
    "required" (if (= 2 (count args))
                 (required! (second args))
                 (exit! 1 usage-text))
    "request" (if (>= (count args) 7)
                (request! (second args) (nth args 2) (nth args 3) (nth args 4) (nth args 5) (drop 6 args))
                (exit! 1 usage-text))
    "approve" (if (>= (count args) 2)
                (approve! (second args) (drop 2 args))
                (exit! 1 usage-text))
    "reject" (if (>= (count args) 2)
               (reject! (second args) (drop 2 args))
               (exit! 1 usage-text))
    "status" (if (<= 1 (count args) 2)
               (status! (second args))
               (exit! 1 usage-text))
    (exit! 1 usage-text)))

(apply -main *command-line-args*)
