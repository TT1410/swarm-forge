(ns swarmforge.system-analyst-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [squad-assign :as assign]
            [squad-product :as product]
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
