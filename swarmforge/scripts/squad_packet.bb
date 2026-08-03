#!/usr/bin/env bb

(ns squad-packet
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def usage-text
  (str "Usage:\n"
       "  squad_packet.sh create <theme-id> <story-id> <story-assignment-id> <branch> <sha>\n"
       "  squad_packet.sh attach <story-id> <gherkin|qa-procedure> <assignment-id> <branch> <sha> <artifact-file>\n"
       "  squad_packet.sh review <story-id> <gherkin|qa-procedure|code|architecture> <accepted|changes-requested> <assignment-id> <branch> <sha>\n"
       "  squad_packet.sh approve <story-id> <story|gherkin|qa-procedure|implementation|code-review|hardening|qa|architecture|final> <detail...>\n"
       "  squad_packet.sh record <story-id> <implementation|cleaner|hardener|qa|architecture|senior-implementor> <assignment-id> <branch> <sha>\n"
       "  squad_packet.sh batch <story-id> <hardener|qa|architecture> <batch-id> <stage> <assignment-id> <branch> <sha>\n"
       "  squad_packet.sh status <story-id>"))

(def valid-id #"[A-Za-z0-9][A-Za-z0-9._-]*")
(def artifact-kinds #{"gherkin" "qa-procedure"})
(def review-kinds #{"gherkin" "qa-procedure" "code" "architecture"})
(def result-kinds #{"implementation" "cleaner" "hardener" "qa" "architecture" "senior-implementor"})
(def batch-kinds #{"hardener" "qa" "architecture"})
(def approval-gates #{"story" "gherkin" "qa-procedure" "implementation"
                      "code-review" "hardening" "qa" "architecture" "final"})

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

(defn validate-sha! [sha]
  (when-not (re-matches #"[0-9a-fA-F]{7,40}" sha)
    (exit! 2 "SHA must be a git commit abbreviation or full SHA.")))

(defn write-atomic! [file content]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                  :prefix (str "." (fs/file-name file) ".")})]
    (spit (str tmp) content)
    (fs/move tmp file {:replace-existing true})))

(defn append-line! [file line]
  (fs/create-dirs (fs/parent file))
  (spit (str file) (str line "\n") :append true))

(defn read-value [file field]
  (when (fs/exists? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(defn theme-dir [root theme-id]
  (fs/path root ".squad" "themes" theme-id))

(defn story-dir [root story-id]
  (fs/path root ".squad" "stories" story-id))

(defn packet-file [root story-id]
  (fs/path (story-dir root story-id) "packet"))

(defn ensure-packet! [root story-id]
  (let [file (packet-file root story-id)]
    (when-not (fs/regular-file? file)
      (exit! 1 (str "Story packet not found: " story-id)))
    file))

(defn source-file! [path]
  (let [file (fs/path path)
        file (if (fs/absolute? file) file (fs/path (fs/cwd) file))]
    (when-not (fs/regular-file? file)
      (exit! 1 (str "Source file not found: " file)))
    file))

(defn relative-project-file! [root file allowed-prefixes message]
  (let [root-path (.normalize (.toAbsolutePath (fs/path root)))
        file-path (.normalize (.toAbsolutePath (fs/path file)))]
    (when-not (.startsWith file-path root-path)
      (exit! 2 message))
    (let [relative (str/replace (str (.relativize root-path file-path)) "\\" "/")]
      (when-not (some #(str/starts-with? relative %) allowed-prefixes)
        (exit! 2 message))
      relative)))

(defn referenced-project-file [root ref-file]
  (when (fs/exists? ref-file)
    (when-let [relative (read-value ref-file "path")]
      relative)))

(defn packet-map [root story-id]
  (let [file (packet-file root story-id)]
    (if-not (fs/exists? file)
      {}
      (into {}
            (keep (fn [line]
                    (let [[k v] (str/split line #": " 2)]
                      (when (and k v)
                        [k v]))))
            (str/split-lines (slurp (str file)))))))

(defn accepted? [packet field]
  (= "accepted" (get packet field)))

(defn approved? [packet field]
  (= "approved" (get packet field)))

(defn recompute-state [packet]
  (cond
    (approved? packet "final_approval") "final_approved"
    (approved? packet "architecture_approval") "architecture_approved"
    (accepted? packet "architecture_review") "architecture_reviewed"
    (contains? packet "senior_implementor_sha") "architecture_revision_returned"
    (contains? packet "architecture_sha") "architecture_returned"
    (approved? packet "qa_approval") "qa_approved"
    (contains? packet "qa_sha") "qa_returned"
    (approved? packet "hardening_approval") "hardening_approved"
    (contains? packet "hardener_sha") "hardener_returned"
    (approved? packet "code_review_approval") "code_review_approved"
    (and (= "accepted" (get packet "code_review"))
         (contains? packet "cleaner_sha")) "code_reviewed"
    (contains? packet "cleaner_sha") "cleaned"
    (contains? packet "implementation_sha") "implemented"
    (approved? packet "implementation_approval") "implementation_approved"
    (and (approved? packet "story_approval")
         (approved? packet "gherkin_approval")
         (approved? packet "qa_procedure_approval")
         (accepted? packet "gherkin_review")
         (accepted? packet "qa_procedure_review")) "implementation_approval_ready"
    (and (approved? packet "story_approval")
         (or (contains? packet "gherkin_assignment")
             (contains? packet "qa_procedure_assignment"))) "specification_in_progress"
    (approved? packet "story_approval") "story_approved"
    :else "story_recorded"))

(defn ordered-packet-lines [packet]
  (let [ordered ["story_id" "theme_id" "state" "story_path" "story_assignment"
                 "story_branch" "story_sha" "story_approval" "story_approval_detail"
                 "gherkin_path" "gherkin_assignment" "gherkin_branch" "gherkin_sha"
                 "gherkin_review" "gherkin_review_assignment" "gherkin_review_branch" "gherkin_review_sha"
                 "gherkin_approval" "gherkin_approval_detail"
                 "qa_procedure_path" "qa_procedure_assignment" "qa_procedure_branch" "qa_procedure_sha"
                 "qa_procedure_review" "qa_procedure_review_assignment" "qa_procedure_review_branch"
                 "qa_procedure_review_sha" "qa_procedure_approval" "qa_procedure_approval_detail"
                 "implementation_approval" "implementation_approval_detail"
                 "implementation_assignment" "implementation_branch" "implementation_sha"
                 "cleaner_assignment" "cleaner_branch" "cleaner_sha"
                 "code_review" "code_review_assignment" "code_review_branch" "code_review_sha"
                 "code_review_approval" "code_review_approval_detail"
                 "hardener_batch" "hardener_batch_stage" "hardener_batch_assignment"
                 "hardener_batch_branch" "hardener_batch_sha" "hardener_assignment"
                 "hardener_branch" "hardener_sha" "hardening_approval" "hardening_approval_detail"
                 "qa_batch" "qa_batch_stage"
                 "qa_batch_assignment" "qa_batch_branch" "qa_batch_sha"
                 "qa_assignment" "qa_branch" "qa_sha" "qa_approval" "qa_approval_detail"
                 "architecture_batch"
                 "architecture_batch_stage" "architecture_batch_assignment"
                 "architecture_batch_branch" "architecture_batch_sha"
                 "architecture_assignment" "architecture_branch" "architecture_sha"
                 "architecture_review" "architecture_review_assignment"
                 "architecture_review_branch" "architecture_review_sha"
                 "architecture_approval" "architecture_approval_detail"
                 "senior_implementor_assignment" "senior_implementor_branch"
                 "senior_implementor_sha"
                 "final_approval" "final_approval_detail"
                 "updated_at"]
        emitted (set ordered)]
    (concat
     (keep (fn [k]
             (when-let [v (get packet k)]
               (str k ": " v)))
           ordered)
     (for [k (sort (remove emitted (keys packet)))]
       (str k ": " (get packet k))))))

(defn write-packet! [root story-id packet]
  (let [now (timestamp)
        packet (assoc packet
                      "state" (recompute-state packet)
                      "updated_at" now)]
    (write-atomic! (packet-file root story-id)
                   (str (str/join "\n" (ordered-packet-lines packet)) "\n"))
    packet))

(defn event! [root story-id state & fields]
  (append-line! (fs/path (story-dir root story-id) "events.log")
                (str/join "\t" (concat [(timestamp) state] fields))))

(defn create-packet! [theme-id story-id assignment-id branch sha]
  (doseq [[kind value] [["Theme id" theme-id]
                        ["Story id" story-id]
                        ["Assignment id" assignment-id]
                        ["Branch" branch]]]
    (validate-id! kind value))
  (validate-sha! sha)
  (let [root (fs/absolutize (project-root))
        theme (theme-dir root theme-id)
        story-ref (fs/path theme "stories" (str story-id ".ref"))
        story-path (referenced-project-file root story-ref)
        dir (story-dir root story-id)]
    (when-not (fs/directory? theme)
      (exit! 1 (str "Unknown theme: " theme-id)))
    (when-not story-path
      (exit! 1 (str "Story reference not found: " story-id)))
    (when (fs/exists? (packet-file root story-id))
      (exit! 2 (str "Story packet already exists: " story-id)))
    (fs/create-dirs dir)
    (let [packet (write-packet! root story-id
                                {"story_id" story-id
                                 "theme_id" theme-id
                                 "story_path" story-path
                                 "story_assignment" assignment-id
                                 "story_branch" branch
                                 "story_sha" sha})]
      (event! root story-id "story_recorded" assignment-id branch sha)
      (println "SQUAD_PACKET:" story-id)
      (println "STATE:" (get packet "state"))
      (println "STORY:" story-path)
      (println "PACKET:" (str (packet-file root story-id))))))

(defn attach-artifact! [story-id kind assignment-id branch sha artifact-path]
  (when-not (contains? artifact-kinds kind)
    (exit! 2 "Artifact kind must be gherkin or qa-procedure."))
  (doseq [[label value] [["Story id" story-id]
                         ["Assignment id" assignment-id]
                         ["Branch" branch]]]
    (validate-id! label value))
  (validate-sha! sha)
  (let [root (fs/absolutize (project-root))
        file (source-file! artifact-path)
        relative (case kind
                   "gherkin" (relative-project-file! root file ["features/"]
                                                     "Gherkin artifacts must live under features/.")
                   "qa-procedure" (relative-project-file! root file ["qa/"]
                                                          "QA procedure artifacts must live under qa/."))
        packet-file (ensure-packet! root story-id)
        packet (packet-map root story-id)
        prefix (str/replace kind "-" "_")
        packet (write-packet! root story-id
                              (assoc (apply dissoc packet
                                            [(str prefix "_review")
                                             (str prefix "_review_assignment")
                                             (str prefix "_review_branch")
                                             (str prefix "_review_sha")
                                             (str prefix "_approval")
                                             (str prefix "_approval_detail")])
                                     (str prefix "_path") relative
                                     (str prefix "_assignment") assignment-id
                                     (str prefix "_branch") branch
                                     (str prefix "_sha") sha))]
    (event! root story-id (str prefix "_attached") assignment-id branch sha relative)
    (println "SQUAD_PACKET:" story-id)
    (println "STATE:" (get packet "state"))
    (println "ARTIFACT:" kind)
    (println "PATH:" relative)
    (println "PACKET:" (str packet-file))))

(defn review-artifact! [story-id kind decision assignment-id branch sha]
  (when-not (contains? review-kinds kind)
    (exit! 2 "Review kind must be gherkin, qa-procedure, code, or architecture."))
  (when-not (#{"accepted" "changes-requested"} decision)
    (exit! 2 "Review decision must be accepted or changes-requested."))
  (doseq [[label value] [["Story id" story-id]
                         ["Assignment id" assignment-id]
                         ["Branch" branch]]]
    (validate-id! label value))
  (validate-sha! sha)
  (let [root (fs/absolutize (project-root))
        packet (packet-map root story-id)
        _ (ensure-packet! root story-id)
        prefix (str/replace kind "-" "_")
        packet (write-packet! root story-id
                              (assoc packet
                                     (str prefix "_review") decision
                                     (str prefix "_review_assignment") assignment-id
                                     (str prefix "_review_branch") branch
                                     (str prefix "_review_sha") sha))]
    (event! root story-id (str prefix "_review_" decision) assignment-id branch sha)
    (println "SQUAD_PACKET:" story-id)
    (println "STATE:" (get packet "state"))
    (println "REVIEW:" kind)
    (println "DECISION:" decision)))

(defn approve! [story-id gate detail-parts]
  (when-not (contains? approval-gates gate)
    (exit! 2 "Approval gate must be story, gherkin, qa-procedure, implementation, code-review, hardening, qa, architecture, or final."))
  (validate-id! "Story id" story-id)
  (let [root (fs/absolutize (project-root))
        _ (ensure-packet! root story-id)
        packet (packet-map root story-id)
        gate-key (str/replace gate "-" "_")
        detail (str/replace (str/join " " detail-parts) #"\R+" " ")
        detail (if (str/blank? detail) "approved" detail)
        packet (write-packet! root story-id
                              (assoc packet
                                     (str gate-key "_approval") "approved"
                                     (str gate-key "_approval_detail") detail))]
    (event! root story-id (str gate-key "_approved") detail)
    (println "SQUAD_PACKET:" story-id)
    (println "STATE:" (get packet "state"))
    (println "APPROVAL:" gate)))

(defn record-result! [story-id kind assignment-id branch sha]
  (when-not (contains? result-kinds kind)
    (exit! 2 "Result kind must be implementation, cleaner, hardener, qa, architecture, or senior-implementor."))
  (doseq [[label value] [["Story id" story-id]
                         ["Assignment id" assignment-id]
                         ["Branch" branch]]]
    (validate-id! label value))
  (validate-sha! sha)
  (let [root (fs/absolutize (project-root))
        _ (ensure-packet! root story-id)
        packet (packet-map root story-id)
        prefix (str/replace kind "-" "_")
        packet (if (= "senior-implementor" kind)
                 (apply dissoc packet
                        ["architecture_review" "architecture_review_assignment"
                         "architecture_review_branch" "architecture_review_sha"
                         "architecture_approval" "architecture_approval_detail"
                         "final_approval" "final_approval_detail"])
                 packet)
        packet (write-packet! root story-id
                              (assoc packet
                                     (str prefix "_assignment") assignment-id
                                     (str prefix "_branch") branch
                                     (str prefix "_sha") sha))]
    (event! root story-id (str prefix "_recorded") assignment-id branch sha)
    (println "SQUAD_PACKET:" story-id)
    (println "STATE:" (get packet "state"))
    (println "RESULT:" kind)
    (println "ASSIGNMENT:" assignment-id)
    (println "BRANCH:" branch)
    (println "SHA:" sha)))

(defn batch-story! [story-id kind batch-id stage assignment-id branch sha]
  (when-not (contains? batch-kinds kind)
    (exit! 2 "Batch kind must be hardener, qa, or architecture."))
  (doseq [[label value] [["Story id" story-id]
                         ["Batch id" batch-id]
                         ["Stage" stage]
                         ["Assignment id" assignment-id]
                         ["Branch" branch]]]
    (validate-id! label value))
  (validate-sha! sha)
  (let [root (fs/absolutize (project-root))
        _ (ensure-packet! root story-id)
        packet (packet-map root story-id)
        packet (write-packet! root story-id
                              (assoc packet
                                     (str kind "_batch") batch-id
                                     (str kind "_batch_stage") stage
                                     (str kind "_batch_assignment") assignment-id
                                     (str kind "_batch_branch") branch
                                     (str kind "_batch_sha") sha))]
    (event! root story-id (str kind "_batch_added") batch-id stage assignment-id branch sha)
    (println "SQUAD_PACKET:" story-id)
    (println "STATE:" (get packet "state"))
    (println "BATCH:" batch-id)
    (println "KIND:" kind)))

(defn print-status! [story-id]
  (validate-id! "Story id" story-id)
  (let [root (fs/absolutize (project-root))
        file (ensure-packet! root story-id)
        packet (packet-map root story-id)]
    (println "STORY:" story-id)
    (println "THEME:" (get packet "theme_id" "unknown"))
    (println "STATE:" (get packet "state" "unknown"))
    (println "STORY_PATH:" (get packet "story_path" "none"))
    (println "STORY_APPROVAL:" (get packet "story_approval" "none"))
    (println "GHERKIN:" (get packet "gherkin_path" "none"))
    (println "GHERKIN_REVIEW:" (get packet "gherkin_review" "none"))
    (println "GHERKIN_APPROVAL:" (get packet "gherkin_approval" "none"))
    (println "QA_PROCEDURE:" (get packet "qa_procedure_path" "none"))
    (println "QA_PROCEDURE_REVIEW:" (get packet "qa_procedure_review" "none"))
    (println "QA_PROCEDURE_APPROVAL:" (get packet "qa_procedure_approval" "none"))
    (println "IMPLEMENTATION_APPROVAL:" (get packet "implementation_approval" "none"))
    (println "IMPLEMENTATION:" (get packet "implementation_sha" "none"))
    (println "CLEANER:" (get packet "cleaner_sha" "none"))
    (println "CODE_REVIEW:" (get packet "code_review" "none"))
    (println "HARDENER_BATCH:" (get packet "hardener_batch" "none"))
    (println "HARDENER:" (get packet "hardener_sha" "none"))
    (println "QA_BATCH:" (get packet "qa_batch" "none"))
	    (println "QA:" (get packet "qa_sha" "none"))
	    (println "ARCHITECTURE_BATCH:" (get packet "architecture_batch" "none"))
	    (println "ARCHITECTURE:" (get packet "architecture_sha" "none"))
	    (println "ARCHITECTURE_REVIEW:" (get packet "architecture_review" "none"))
	    (println "SENIOR_IMPLEMENTOR:" (get packet "senior_implementor_sha" "none"))
	    (println "FINAL_APPROVAL:" (get packet "final_approval" "none"))
	    (println "PACKET:" (str file))))

(defn -main [& args]
  (case (first args)
    "create" (if (= 6 (count args))
               (apply create-packet! (rest args))
               (exit! 1 usage-text))
    "attach" (if (= 7 (count args))
               (apply attach-artifact! (rest args))
               (exit! 1 usage-text))
    "review" (if (= 7 (count args))
               (apply review-artifact! (rest args))
               (exit! 1 usage-text))
    "approve" (if (>= (count args) 4)
                (approve! (second args) (nth args 2) (drop 3 args))
                (exit! 1 usage-text))
    "record" (if (= 6 (count args))
               (apply record-result! (rest args))
               (exit! 1 usage-text))
    "batch" (if (= 8 (count args))
              (apply batch-story! (rest args))
              (exit! 1 usage-text))
    "status" (if (= 2 (count args))
               (print-status! (second args))
               (exit! 1 usage-text))
    (exit! 1 usage-text)))

(apply -main *command-line-args*)
