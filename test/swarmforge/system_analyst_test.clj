(ns swarmforge.system-analyst-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [squad-assign :as assign]
            [squad-next :as squad-next]
            [squad-product :as product]
            [squadd.web :as web]
            [swarmforge.test-support :refer :all]))

(deftest product-record-round-trips-frame-fields
  ;; Given a product map
  ;; When it is written and read
  ;; Then frame_sha, paths, assignment_id, and open_item_ids come back
  (let [root (tmp-dir)]
    (try
      (product/write-product! root {"state" "frame_pending"
                                    "assignment_id" "system-analysis"
                                    "open_item_ids" "bl-1,bl-2"})
      (let [p (product/read-product root)]
        (is (= "frame_pending" (get p "state")))
        (is (= "system-analysis" (get p "assignment_id")))
        (is (= ["bl-1" "bl-2"] (product/open-item-ids p)))
        (is (nil? (product/frame-sha p))))
      (finally (fs/delete-tree root)))))

(deftest missing-product-file-reads-as-empty-map
  ;; Given no .squad/product file
  ;; When it is read
  ;; Then the result is {}
  (let [root (tmp-dir)]
    (try
      (is (= {} (product/read-product root)))
      (finally (fs/delete-tree root)))))

(deftest frame-ready-when-frame-sha-is-non-blank
  ;; Given a product map
  ;; When frame_sha is missing, blank, or set
  ;; Then frame-ready? is true only for a non-blank sha
  (is (false? (product/frame-ready? {})))
  (is (false? (product/frame-ready? {"frame_sha" ""})))
  (is (true? (product/frame-ready? {"frame_sha" "abc1234"}))))

(deftest open-item-ids-trim-and-drop-blanks
  ;; Given open_item_ids with spaces and empty segments
  ;; When parsed
  ;; Then blanks are dropped
  (is (= [] (product/open-item-ids {})))
  (is (= [] (product/open-item-ids {"open_item_ids" ""})))
  (is (= ["bl-1" "bl-2"] (product/open-item-ids {"open_item_ids" " bl-1 , ,bl-2 "}))))

(deftest system-analyst-prompt-owns-the-frame-not-hunt-rules
  (let [p (slurp (str (fs/path repo-root "swarmforge/role-templates/system-analyst.prompt")))]
    (is (str/includes? p ".squad/backlog"))
    (is (str/includes? p "frame.md"))
    (is (str/includes? p "qa/product.md"))
    (is (re-find #"(?i)placeholder" p))
    (is (re-find #"(?i)do not implement" p))
    (is (re-find #"(?i)one executable|one process" p))
    (is (not (re-find #"(?i)write features/" p)))))

(deftest story-prompts-extend-the-frame
  ;; Given later-role story prompts after the frame is on master
  ;; Then each prompt names frame.md, extends the one executable, and
  ;; forbids a second -main, probe app, or sidecar
  (doseq [name ["analyst.prompt" "gherkin-writer.prompt" "qa-procedure-writer.prompt"
                "implementer.prompt" "qa.prompt"]]
    (let [p (slurp (str (fs/path repo-root "swarmforge/role-templates" name)))]
      (is (re-find #"(?i)frame\.md" p) name)
      (is (re-find #"(?i)extend" p) name)
      (is (re-find #"(?i)do not (add|create) a second|-main|probe app|sidecar" p) name)))
  (let [qa (slurp (str (fs/path repo-root "swarmforge/role-templates/qa-procedure-writer.prompt")))]
    (is (str/includes? qa "qa/product.md")))
  (let [w (slurp (str (fs/path repo-root "swarmforge/worker-common.prompt")))]
    (is (re-find #"(?i)frame" w))))

(deftest create-product-assignment-has-no-story-card
  ;; Given a product with a backlog item and the system-analyst template
  ;; When create-product assigns system-analyst
  ;; Then the assignment is product-scoped, has no story_id, lists backlog titles, and uses swarm_handoff.sh
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root
                       "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "swarmforge/role-templates/system-analyst.prompt")
                  (slurp (str (fs/path repo-root "swarmforge/role-templates/system-analyst.prompt"))))
      (write-file (fs/path root "swarmforge/role-templates/system-analyst.contract.edn")
                  (slurp (str (fs/path repo-root "swarmforge/role-templates/system-analyst.contract.edn"))))
      (write-file (fs/path root ".squad/backlog/bl-1.item")
                  "id: bl-1\ntitle: Walk\nstatus: open\ncreated_at: t\nupdated_at: t\nbody: |\n  w\n")
      (let [r (run {:dir root} (script "squad_assign.sh")
                   "create-product" "system-analyst" "system-analysis"
                   "--auto-instructions")
            md (slurp (str (fs/path root ".squad/assignments/system-analysis/assignment.md")))
            meta (slurp (str (fs/path root ".squad/assignments/system-analysis/metadata")))]
        (is (zero? (:exit r)))
        (is (str/includes? (:out r) "SQUAD_ASSIGNMENT: system-analysis"))
        (is (str/includes? md "scope: product"))
        (is (not (re-find #"(?m)^story_id:" md)))
        (is (not (re-find #"(?m)^story_id:" meta)))
        (is (str/includes? md "swarm_handoff.sh"))
        (is (str/includes? md "Walk"))
        (is (not (str/includes? md "provided theme"))))
      (finally (fs/delete-tree root)))))

(deftest system-analyst-result-requires-frame-and-qa-product
  ;; Given a system-analyst git_handoff result
  ;; When artifacts omit frame.md or qa/product.md
  ;; Then validation fails naming the missing path
  (let [ok {"assignment" "system-analysis" "agent" "system-analyst-001"
            "template" "system-analyst" "artifacts" "frame.md,qa/product.md"}
        missing-qa (assoc ok "artifacts" "frame.md")
        missing-frame (assoc ok "artifacts" "qa/product.md")
        none (assoc ok "artifacts" "none")]
    (is (= ok (assign/validate-result-manifest! "system-analysis" "system-analyst"
                                                "system-analyst-001" ok)))
    (doseq [[bad missing] [[missing-qa #"qa/product.md"]
                           [missing-frame #"frame.md"]
                           [none #"qa/product.md"]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo missing
            (with-redefs [assign/exit! (fn [status & lines]
                                         (throw (ex-info (str/join " " lines) {:status status})))]
              (assign/validate-result-manifest! "system-analysis" "system-analyst"
                                                "system-analyst-001" bad)))))))

(defn- write-roles! [root]
  (write-file (fs/path root ".swarmforge/roles.tsv")
              (str "squad-leader\tmaster\t" root
                   "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n")))

(defn- write-item! [root id title status]
  (write-file (fs/path root ".squad/backlog" (str id ".item"))
              (str "id: " id "\n"
                   "title: " title "\n"
                   "status: " status "\n"
                   "created_at: t\n"
                   "updated_at: t\n"
                   "body: |\n"
                   "  " title "\n")))

(defn- write-open-item! [root id title]
  (write-item! root id title "open"))

(defn- write-started-item! [root id title]
  (write-item! root id title "started"))

(defn- write-merged-system-analyst!
  ([root] (write-merged-system-analyst! root nil))
  ([root sha]
   (write-file (fs/path root ".squad/assignments/system-analysis/metadata")
               (str "assignment_id: system-analysis\n"
                    "template: system-analyst\n"
                    "scope: product\n"))
   (write-file (fs/path root ".squad/assignments/system-analysis/status")
               "assignment_id: system-analysis\nstate: merged\n")
   (when-not (str/blank? sha)
     (write-file (fs/path root ".squad/assignments/system-analysis/result-manifest")
                 (str "assignment: system-analysis\n"
                      "template: system-analyst\n"
                      "commit: " sha "\n"
                      "artifacts: frame.md,qa/product.md\n")))))

(deftest frame-approval-request-is-product-scoped
  ;; Given a product record
  ;; When frame approval is requested then approved
  ;; Then the pending file is product-scoped and approve relocates it
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root
                       "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (product/write-product! root {"state" "frame_pending"
                                    "assignment_id" "system-analysis"})
      (let [request (run {:dir root}
                         (script "squad_approval.sh")
                         "request"
                         "frame__product"
                         "product"
                         "product"
                         "frame"
                         "Approve_frame"
                         "frame-ready")
            pending (slurp (str (fs/path root ".squad/approvals/pending/frame__product.approval")))]
        (is (zero? (:exit request)))
        (is (str/includes? pending "target_kind: product"))
        (is (str/includes? pending "gate: frame"))
        (let [approve (run {:dir root}
                           (script "squad_approval.sh")
                           "approve"
                           "frame__product"
                           "approved-by-user")]
          (is (zero? (:exit approve)))
          (is (fs/exists? (fs/path root ".squad/approvals/approved/frame__product.approval")))
          (is (not (fs/exists? (fs/path root ".squad/approvals/pending/frame__product.approval"))))))
      (finally (fs/delete-tree root)))))

(deftest frame-approval-request-requires-product-record
  ;; Given no .squad/product file
  ;; When frame approval is requested
  ;; Then the command fails because the target is missing
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root
                       "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (let [request (run {:dir root :ok? false}
                         (script "squad_approval.sh")
                         "request"
                         "frame__product"
                         "product"
                         "product"
                         "frame"
                         "Approve_frame"
                         "frame-ready")]
        (is (not (zero? (:exit request))))
        (is (str/includes? (str (:err request) (:out request)) "Approval target not found")))
      (finally (fs/delete-tree root)))))

(deftest frame-approval-candidate-when-merged-system-analyst-and-no-frame-sha
  ;; Given a product without frame_sha, a merged system-analyst, and no frame approval
  ;; When the frame approval candidate is computed
  ;; Then it requests frame__product
  ;; And it is nil once frame_sha is set
  (let [root (tmp-dir)]
    (try
      (product/write-product! root {"state" "frame_pending"
                                    "assignment_id" "system-analysis"})
      (write-merged-system-analyst! root)
      (let [candidate (squad-next/frame-approval-candidate root)]
        (is (= "create_approval_request" (:next-action candidate)))
        (is (= "frame" (:gate candidate)))
        (is (str/includes? (:command candidate)
                           "squad_approval.sh request frame__product product product frame")))
      (product/write-product! root {"state" "framed"
                                    "frame_sha" "abc1234"
                                    "assignment_id" "system-analysis"})
      (is (nil? (squad-next/frame-approval-candidate root)))
      (finally (fs/delete-tree root)))))

(defn- story-md-files [root]
  (let [dir (fs/path root "stories")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(str/ends-with? (fs/file-name %) ".md"))
           vec)
      [])))

(deftest start-backlog-does-not-create-story-files
  ;; Given two open items and no frame_sha
  ;; When POST /api/backlog/start
  ;; Then product is frame_pending with both ids, items stay open, no stories/*.md
  (let [root (tmp-dir)]
    (try
      (let [a (web/create-backlog! root {:title "Walk" :body "Move."})
            b (web/create-backlog! root {:title "Shoot" :body "Arrow."})
            id-a (get-in a [:item "id"])
            id-b (get-in b [:item "id"])
            resp (web/handle-web-request root "POST" "/api/backlog/start" "{}")
            p (product/read-product root)]
        (is (= 200 (:status resp)))
        (is (str/includes? (:body resp) "\"ok\":true"))
        (is (str/includes? (:body resp) id-a))
        (is (str/includes? (:body resp) id-b))
        (is (= "frame_pending" (get p "state")))
        (is (= #{id-a id-b} (set (product/open-item-ids p))))
        (is (= "open" (get (web/get-backlog root id-a) "status")))
        (is (= "open" (get (web/get-backlog root id-b) "status")))
        (is (empty? (story-md-files root))))
      (finally (fs/delete-tree root)))))

(deftest per-card-start-requires-frame
  ;; Given two open items and no frame_sha
  ;; When POST /api/backlog/:id/approve
  ;; Then 409 and the body mentions the frame
  (let [root (tmp-dir)]
    (try
      (let [created (web/create-backlog! root {:title "Walk" :body "Move."})
            id (get-in created [:item "id"])
            resp (web/handle-web-request root "POST" (str "/api/backlog/" id "/approve") "{}")]
        (is (= 409 (:status resp)))
        (is (re-find #"(?i)frame" (str (:body resp)))))
      (finally (fs/delete-tree root)))))

(deftest start-backlog-requires-open-items
  ;; Given no open backlog items
  ;; When POST /api/backlog/start
  ;; Then 400
  (let [root (tmp-dir)]
    (try
      (let [resp (web/handle-web-request root "POST" "/api/backlog/start" "{}")]
        (is (= 400 (:status resp))))
      (finally (fs/delete-tree root)))))

(deftest start-backlog-does-not-resnapshot-when-frame-exists
  ;; Given open items and a product that already has frame_sha
  ;; When POST /api/backlog/start
  ;; Then 409 and the snapshot is unchanged
  (let [root (tmp-dir)]
    (try
      (web/create-backlog! root {:title "Walk" :body "Move."})
      (product/write-product! root {"state" "framed"
                                    "frame_sha" "abc1234"
                                    "open_item_ids" "old-id"})
      (let [resp (web/handle-web-request root "POST" "/api/backlog/start" "{}")
            p (product/read-product root)]
        (is (= 409 (:status resp)))
        (is (= "framed" (get p "state")))
        (is (= "abc1234" (product/frame-sha p)))
        (is (= ["old-id"] (product/open-item-ids p))))
      (finally (fs/delete-tree root)))))

(deftest dashboard-has-start-backlog-button
  ;; Given the cockpit HTML
  ;; Then Start backlog sits beside Add Story and posts /api/backlog/start
  (let [html web/dashboard-html]
    (is (str/includes? html "id=\"btn-start-backlog\""))
    (is (str/includes? html "Start backlog"))
    (is (re-find #"id=\"btn-start-backlog\"" html))
    (is (re-find #"getElementById\('btn-start-backlog'\)\.onclick" html))
    (is (str/includes? html "/api/backlog/start"))))

(deftest squad-conf-caps-system-analyst-at-one
  (let [conf (slurp (str (fs/path repo-root "swarmforge/squad.conf")))]
    (is (re-find #"(?m)^max_active_template system-analyst 1$" conf))))

(deftest system-analyst-is-not-an-artifact-assignment
  (is (not (contains? squad-next/artifact-assignment-rules "system-analyst"))))

(deftest pending-product-prints-create-product-assignment
  ;; Given a pending product snapshot with two open items and no assignment
  ;; When squad_next.sh runs
  ;; Then residual assigns system-analyst via create-product
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "swarmforge/squad.conf")
                  (str "max_active_template system-analyst 1\n"
                       "approval_required frame true\n"))
      (write-open-item! root "bl-1" "Walk")
      (write-open-item! root "bl-2" "Shoot")
      (product/write-product! root {"state" "frame_pending"
                                    "open_item_ids" "bl-1,bl-2"})
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "NEXT_ACTION: create_assignment"))
        (is (str/includes? out "squad_assign.sh create-product system-analyst system-analysis"))
        (is (str/includes? out "--queue-spawn"))
        (is (empty? (story-md-files root))))
      (finally (fs/delete-tree root)))))

(deftest product-frame-candidates-offer-create-product
  ;; Given a pending product and no system-analyst assignment
  ;; When product-frame-candidates are computed
  ;; Then create-product is offered at high priority
  (let [root (tmp-dir)]
    (try
      (product/write-product! root {"state" "frame_pending"
                                    "open_item_ids" "bl-1,bl-2"})
      (let [cands (squad-next/product-frame-candidates root [])
            create (first (filter #(= "create_assignment" (:next-action %)) cands))]
        (is (some? create))
        (is (= "system-analyst" (:template create)))
        (is (= "system-analysis" (:assignment-id create)))
        (is (= 20 (:priority create)))
        (is (str/includes? (:command create)
                           "squad_assign.sh create-product system-analyst system-analysis"))
        (is (str/includes? (:command create) "--queue-spawn")))
      (finally (fs/delete-tree root)))))

(deftest product-frame-candidates-skip-create-when-analyst-live
  ;; Given a pending product and an in-progress system-analyst
  ;; When product-frame-candidates are computed
  ;; Then create-product is not offered
  (let [root (tmp-dir)]
    (try
      (product/write-product! root {"state" "frame_pending"
                                    "assignment_id" "system-analysis"
                                    "open_item_ids" "bl-1"})
      (write-file (fs/path root ".squad/assignments/system-analysis/metadata")
                  "assignment_id: system-analysis\ntemplate: system-analyst\nscope: product\n")
      (write-file (fs/path root ".squad/assignments/system-analysis/status")
                  "state: in_progress\n")
      (let [cands (squad-next/product-frame-candidates root [])]
        (is (not (some #(= "create_assignment" (:next-action %)) cands))))
      (finally (fs/delete-tree root)))))

(deftest product-frame-candidates-include-frame-approval
  ;; Given a merged system-analyst and no frame sha or approval
  ;; When product-frame-candidates are computed
  ;; Then the frame approval request is included and create-product is not
  (let [root (tmp-dir)]
    (try
      (product/write-product! root {"state" "frame_pending"
                                    "assignment_id" "system-analysis"})
      (write-merged-system-analyst! root)
      (write-file (fs/path root "swarmforge/squad.conf")
                  "approval_required frame true\n")
      (let [cands (squad-next/product-frame-candidates root [])]
        (is (some #(= "create_approval_request" (:next-action %)) cands))
        (is (not (some #(str/includes? (str (:command %)) "create-product") cands))))
      (finally (fs/delete-tree root)))))

(deftest record-frame-sha-stamps-paths
  ;; Given a pending product
  ;; When the frame sha is recorded
  ;; Then paths and snapshot ids are preserved
  (let [root (tmp-dir)]
    (try
      (product/write-product! root {"state" "frame_pending"
                                    "assignment_id" "system-analysis"
                                    "open_item_ids" "bl-1"})
      (product/record-frame-sha! root "abc1234")
      (let [p (product/read-product root)]
        (is (= "abc1234" (product/frame-sha p)))
        (is (= "frame.md" (get p "frame_path")))
        (is (= "qa/product.md" (get p "qa_path")))
        (is (= ["bl-1"] (product/open-item-ids p)))
        (is (true? (product/frame-ready? p))))
      (finally (fs/delete-tree root)))))

(deftest residual-records-frame-and-starts-snapshot-items
  ;; Given merged system-analyst, approved frame, and a snapshot of two open items
  ;; When bookkeeping residual applies
  ;; Then frame_sha is recorded, snapshot items start, and a late item stays open
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "swarmforge/squad.conf")
                  (str "max_active_template system-analyst 1\n"
                       "approval_required frame true\n"))
      (write-open-item! root "bl-1" "Walk")
      (write-open-item! root "bl-2" "Shoot")
      (write-open-item! root "bl-late" "Late")
      (write-file (fs/path root "frame.md") "# Frame\n\nrun: bb run\n")
      (write-file (fs/path root "qa/product.md") "<!-- bl-1 -->\n<!-- bl-2 -->\n")
      (run {:dir root} "git" "add" "frame.md" "qa/product.md")
      (run {:dir root} "git" "commit" "-q" "-m" "frame")
      (let [sha (str/trim (:out (run {:dir root} "git" "rev-parse" "HEAD")))]
        (product/write-product! root {"state" "frame_pending"
                                      "assignment_id" "system-analysis"
                                      "open_item_ids" "bl-1,bl-2"})
        (write-merged-system-analyst! root sha)
        (write-file (fs/path root ".squad/approvals/approved/frame__product.approval")
                    "target_kind: product\ntarget_id: product\ngate: frame\nstate: approved\n")
        (let [ready (squad-next/ready-actions root [])
              bookkeeping (filter squad-next/bookkeeping-action? ready)]
          (is (some #(= "record_frame_sha" (:next-action %)) bookkeeping)))
        (squad-next/apply-bookkeeping-ready-actions! root (squad-next/role-rows root))
        (let [p (product/read-product root)]
          (is (= sha (product/frame-sha p)))
          (is (= "frame.md" (get p "frame_path")))
          (is (= "qa/product.md" (get p "qa_path"))))
        (is (= "started" (get (web/get-backlog root "bl-1") "status")))
        (is (= "started" (get (web/get-backlog root "bl-2") "status")))
        (is (= "open" (get (web/get-backlog root "bl-late") "status")))
        (is (= 2 (count (story-md-files root))))
        (let [out (:out (run {:dir root} (script "squad_next.sh")))]
          (is (str/includes? out "NEXT_ACTION: create_assignment"))
          (is (re-find #"TEMPLATE: analyst|template analyst" out))
          (is (not (str/includes? out "create-product")))))
      (finally (fs/delete-tree root)))))

(deftest residual-leaves-late-open-items-unstarted
  ;; Given a framed product whose snapshot is already-started bl-1 and a late open item bl-new
  ;; When squad_next.sh applies residual
  ;; Then no stories/ file is created for bl-new and bl-new stays open
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "swarmforge/squad.conf")
                  (str "max_active_template system-analyst 1\n"
                       "approval_required frame true\n"))
      (write-started-item! root "bl-1" "Walk")
      (write-open-item! root "bl-new" "New")
      (product/write-product! root {"state" "framed"
                                    "frame_sha" "abc1234"
                                    "open_item_ids" "bl-1"})
      (run {:dir root} (script "squad_next.sh") "--apply-mechanical")
      (is (= "started" (get (web/get-backlog root "bl-1") "status")))
      (is (= "open" (get (web/get-backlog root "bl-new") "status")))
      (is (empty? (story-md-files root)))
      (is (not (fs/regular-file? (fs/path root "stories/new.md"))))
      (let [cands (squad-next/product-frame-candidates root [])]
        (is (not (some #(and (= "start_snapshot_item" (:next-action %))
                             (= "bl-new" (:item-id %)))
                       cands))))
      (finally (fs/delete-tree root)))))

(deftest per-card-start-after-frame-starts-only-that-item
  ;; Given a framed product and a late open item bl-new
  ;; When POST /api/backlog/bl-new/approve
  ;; Then 200 and stories exist only for that item
  (let [root (tmp-dir)]
    (try
      (write-open-item! root "bl-new" "New")
      (write-open-item! root "bl-other" "Other")
      (product/write-product! root {"state" "framed"
                                    "frame_sha" "abc1234"
                                    "open_item_ids" "bl-1"})
      (let [resp (web/handle-web-request root "POST" "/api/backlog/bl-new/approve" "{}")]
        (is (= 200 (:status resp)))
        (is (str/includes? (:body resp) "\"ok\":true"))
        (is (= "started" (get (web/get-backlog root "bl-new") "status")))
        (is (= "open" (get (web/get-backlog root "bl-other") "status")))
        (is (fs/regular-file? (fs/path root "stories/new.md")))
        (is (not (fs/regular-file? (fs/path root "stories/other.md"))))
        (is (= 1 (count (story-md-files root)))))
      (finally (fs/delete-tree root)))))

(deftest wif-includes-in-progress-system-analyst
  ;; Given an in-progress product-scoped system-analyst assignment
  ;; When WIF rows are built
  ;; Then the assignment_id is present even with a blank story_id
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".squad/assignments/system-analysis/metadata")
                  (str "assignment_id: system-analysis\n"
                       "template: system-analyst\n"
                       "scope: product\n"))
      (write-file (fs/path root ".squad/assignments/system-analysis/status")
                  "state: in_progress\nupdated_at: 2026-08-20T00:00:00Z\n")
      (let [rows (web/work-in-flight-rows root (web/assignment-state root) [])
            row (first (filter #(= "system-analysis" (get % "assignment_id")) rows))]
        (is (some? row))
        (is (= "system-analyst" (get row "role")))
        (is (str/blank? (get row "story_id"))))
      (finally (fs/delete-tree root)))))

(deftest dashboard-wif-does-not-require-story-card-for-system-analyst
  ;; Given the Work Queue renderer
  ;; Then a row can label from assignment_id when story_id is blank
  (let [html web/dashboard-html]
    (is (re-find #"w\.story\|\|w\.story_id\|\|w\.assignment_id" html))))

(deftest web-state-frame-is-none-without-product
  ;; Given no product file
  ;; When web-state is built
  ;; Then frame state is none and sha is absent
  (let [root (tmp-dir)]
    (try
      (let [frame (get (web/web-state root) "frame")]
        (is (= "none" (get frame "state")))
        (is (str/blank? (str (get frame "sha")))))
      (finally (fs/delete-tree root)))))

(deftest web-state-frame-is-none-when-product-empty
  ;; Given an empty product file
  ;; When web-state is built
  ;; Then frame state is none
  (let [root (tmp-dir)]
    (try
      (product/write-product! root {})
      (is (= "none" (get-in (web/web-state root) ["frame" "state"])))
      (finally (fs/delete-tree root)))))

(deftest web-state-frame-is-pending-when-snapshot-has-no-sha
  ;; Given product state frame_pending, no frame_sha, no pending frame approval
  ;; When web-state is built
  ;; Then frame state is pending
  (let [root (tmp-dir)]
    (try
      (product/write-product! root {"state" "frame_pending"
                                    "open_item_ids" "bl-1"})
      (let [frame (get (web/web-state root) "frame")]
        (is (= "pending" (get frame "state")))
        (is (str/blank? (str (get frame "sha")))))
      (finally (fs/delete-tree root)))))

(deftest web-state-frame-is-in-review-when-frame-approval-pending
  ;; Given a pending frame__product approval
  ;; When web-state is built
  ;; Then frame state is in_review
  (let [root (tmp-dir)]
    (try
      (product/write-product! root {"state" "frame_pending"
                                    "assignment_id" "system-analysis"})
      (write-file (fs/path root ".squad/approvals/pending/frame__product.approval")
                  "target_kind: product\ntarget_id: product\ngate: frame\nstate: pending\n")
      (let [frame (get (web/web-state root) "frame")]
        (is (= "in_review" (get frame "state")))
        (is (str/blank? (str (get frame "sha")))))
      (finally (fs/delete-tree root)))))

(deftest web-state-frame-is-on-master-when-sha-set
  ;; Given frame_sha is set
  ;; When web-state is built
  ;; Then frame state is on_master and sha is the value
  (let [root (tmp-dir)]
    (try
      (product/write-product! root {"state" "framed"
                                    "frame_sha" "abc1234"})
      (write-file (fs/path root ".squad/approvals/pending/frame__product.approval")
                  "target_kind: product\ntarget_id: product\ngate: frame\n")
      (let [frame (get (web/web-state root) "frame")]
        (is (= "on_master" (get frame "state")))
        (is (= "abc1234" (get frame "sha"))))
      (finally (fs/delete-tree root)))))

(deftest api-state-json-includes-frame
  ;; Given a framed product
  ;; When GET /api/state
  ;; Then the JSON body includes the frame object
  (let [root (tmp-dir)]
    (try
      (product/write-product! root {"state" "framed" "frame_sha" "deadbeef"})
      (let [resp (web/handle-web-request root "GET" "/api/state" "")]
        (is (= 200 (:status resp)))
        (is (str/includes? (:body resp) "\"frame\""))
        (is (str/includes? (:body resp) "\"on_master\""))
        (is (str/includes? (:body resp) "\"deadbeef\"")))
      (finally (fs/delete-tree root)))))

(deftest dashboard-has-frame-status
  ;; Given the cockpit HTML
  ;; Then the toolbar shows frame-status near Start backlog
  (let [html web/dashboard-html]
    (is (str/includes? html "id=\"frame-status\""))
    (is (re-find #"(?s)id=\"frame-status\".*id=\"btn-start-backlog\"|id=\"btn-start-backlog\".*id=\"frame-status\"" html))))

(deftest dashboard-updates-frame-status-from-state
  ;; Given the cockpit script
  ;; Then refresh sets #frame-status from data.frame as Frame: none etc.
  (let [html web/dashboard-html]
    (is (re-find #"getElementById\('frame-status'\)" html))
    (is (str/includes? html "Frame: "))
    (is (re-find #"data\.frame" html))
    (is (str/includes? html "in review"))
    (is (str/includes? html "on master"))))

(deftest dashboard-start-backlog-enabled-only-when-open-and-idle
  ;; Given the cockpit script
  ;; Then Start backlog is disabled without open items, with a frame sha, or with an in-flight system-analyst
  (let [html web/dashboard-html]
    (is (re-find #"getElementById\('btn-start-backlog'\).*disabled" html))
    (is (re-find #"system-analyst" html))
    (is (re-find #"(?s)btn-start-backlog.*merged" html))
    (is (re-find #"(?s)frame\.sha|frame\]\.sha|frame && .*sha" html))))
