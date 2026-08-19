(ns swarmforge.durable-blocker-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [swarmforge.test-support :refer :all]))

(defn- seed-theme-story! [root]
  (write-file (fs/path root ".swarmforge/roles.tsv")
              (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
  (write-file (fs/path root "theme.md") "Theme.\n")
  (write-file (fs/path root "module-map.md") minimal-module-map)
  (write-file (fs/path root "stories/cave.md") "Story cave.\n")
  (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
    (run {:dir root} (script "squad_packet.sh") "create" "cave" "cave-story" "master" sha)
    sha))

(deftest resolve-rejection-clears-blocker-and-reopens-gate
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (seed-theme-story! root)
      (run {:dir root} (script "squad_approval.sh") "request"
           "qa-procedure__cave" "story" "cave" "qa-procedure"
           "Approve_QA" "please")
      (run {:dir root} (script "squad_approval.sh") "reject"
           "qa-procedure__cave" "needs work")
      (is (fs/exists? (fs/path root ".squad/blockers/qa-procedure__cave")))
      (is (str/includes? (slurp (str (fs/path root ".squad/stories/cave/packet")))
                         "qa_procedure_approval: rejected"))
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "NEXT_ACTION: handle_durable_blocker"))
        (is (str/includes? (:out next) "BLOCKER_ID: qa-procedure__cave"))
        (is (str/includes? (:out next) "resolve-rejection")))
      (let [cleared (run {:dir root} (script "squad_approval.sh") "resolve-rejection"
                         "qa-procedure__cave" "operator cleared")]
        (is (str/includes? (:out cleared) "STATE: cleared"))
        (is (not (fs/exists? (fs/path root ".squad/blockers/qa-procedure__cave"))))
        (is (fs/exists? (fs/path root ".squad/approvals/cleared/qa-procedure__cave.approval")))
        (is (not (str/includes? (slurp (str (fs/path root ".squad/stories/cave/packet")))
                                "qa_procedure_approval: rejected"))))
      (let [next2 (run {:dir root} (script "squad_next.sh"))]
        (is (not (str/includes? (:out next2) "handle_durable_blocker"))))
      ;; Gate can be re-requested after resolve.
      (let [req (run {:dir root} (script "squad_approval.sh") "request"
                     "qa-procedure__cave" "story" "cave" "qa-procedure"
                     "Approve_QA" "again")]
        (is (str/includes? (:out req) "STATE: pending")))
      (finally
        (fs/delete-tree root)))))

(deftest squad-next-reports-durable-blocker-over-empty-pending-approvals
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root ".squad/blockers/qa-procedure__room-reporting")
                  (str "blocker_id: qa-procedure__room-reporting\n"
                       "kind: approval-rejection\n"
                       "state: blocked\n"
                       "approval_id: qa-procedure__room-reporting\n"
                       "target_kind: story\n"
                       "target_id: room-reporting\n"
                       "gate: qa-procedure\n"
                       "detail: rejected-by-web\n"
                       "updated_at: 2026-08-11T16:22:10Z\n"))
      (let [next (run {:dir root} (script "squad_next.sh"))]
        (is (str/includes? (:out next) "NEXT_ACTION: handle_durable_blocker"))
        (is (str/includes? (:out next) "room-reporting"))
        (is (str/includes? (:out next) "rejected-by-web"))
        (is (not (str/includes? (:out next) "NEXT_ACTION: wait"))))
      (finally
        (fs/delete-tree root)))))
