(ns squad-sprint-next
  "Sprint-form residuals for squad_next. Active when any sprint record exists."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [squad-sprint :as sprint]))

(def test-spec-transition-ids
  #{:gherkin-assignment :gherkin-revision-assignment
    :qa-procedure-assignment :qa-procedure-revision-assignment
    :gherkin-review-assignment :qa-procedure-review-assignment
    :gherkin-approval :qa-procedure-approval})

(defn file-map [file]
  (if (fs/regular-file? file)
    (into {}
          (keep (fn [line]
                  (when-let [[_ k v] (re-matches #"([^:]+):\s*(.*)" line)]
                    [k v])))
          (str/split-lines (slurp (str file))))
    {}))

(defn theme-id [root]
  (or (sprint/read-field (fs/path root ".squad" "project") "theme_id")
      (first (sprint/sprint-ids root))
      "theme"))

(defn sprint-workflow-active? [root]
  (boolean (seq (sprint/sprint-ids root))))

(defn scheduled-sprint [root]
  (when-let [id (sprint/scheduled-id root)]
    (sprint/load-sprint root id)))

(defn theme-dir [root]
  (fs/path root ".squad" "themes" (theme-id root)))

(defn maps-present? [root]
  (let [dir (theme-dir root)]
    (and (fs/regular-file? (fs/path dir "module-map.md"))
         (fs/regular-file? (fs/path dir "implementation-order.md")))))

(defn spec-present? [root sprint-id]
  (fs/regular-file? (fs/path (sprint/sprint-dir root sprint-id) "spec.md")))

(defn interfaces-present? [root sprint-id]
  (fs/regular-file? (fs/path (sprint/sprint-dir root sprint-id) "interfaces.md")))

(defn task-ids [root sprint-id]
  (let [dir (fs/path (sprint/sprint-dir root sprint-id) "tasks")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter fs/regular-file?)
           (map fs/file-name)
           sort
           vec)
      [])))

(defn task-record [root sprint-id module]
  (let [m (file-map (fs/path (sprint/sprint-dir root sprint-id) "tasks" module))]
    {:module module
     :stories (get m "stories")
     :stage (get m "stage" "queued")}))

(defn plan-recorded? [root sprint-id]
  (and (seq (task-ids root sprint-id))
       (interfaces-present? root sprint-id)))

(defn approval-records [root]
  (for [state ["pending" "approved" "rejected"]
        file (let [dir (fs/path root ".squad" "approvals" state)]
               (if (fs/directory? dir)
                 (->> (fs/list-dir dir)
                      (filter #(str/ends-with? (str %) ".approval")))
                 []))]
    (assoc (file-map file) :state state)))

(defn approval-exists? [root gate]
  (boolean (some #(= gate (get % "gate")) (approval-records root))))

(defn approval-approved? [root gate]
  (boolean (some #(and (= gate (get % "gate"))
                       (= "approved" (:state %)))
                 (approval-records root))))

(defn assignment-records [root]
  (let [dir (fs/path root ".squad" "assignments")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (map (fn [d]
                  (let [meta (file-map (fs/path d "metadata"))
                        status (file-map (fs/path d "status"))]
                    {:assignment-id (fs/file-name d)
                     :template (get meta "template")
                     :story-id (get meta "story_id")
                     :state (get status "state" "unknown")})))
           vec)
      [])))

(defn analyst-for [assignments sprint-id]
  (first (filter #(and (= "analyst" (:template %))
                       (= sprint-id (:story-id %)))
                 assignments)))

(defn open-assignment? [a]
  (not (contains? #{"merged" "rejected" "blocked" "cancelled" "abandoned"
                    "replacement_created" "superseded"}
                  (:state a))))

(defn implementer-for [assignments module]
  (first (filter #(and (= "implementer" (:template %))
                       (= module (:story-id %))
                       (open-assignment? %))
                 assignments)))

(defn hardener-for [assignments sprint-id]
  (first (filter #(and (= "hardener" (:template %))
                       (or (= sprint-id (:story-id %))
                           (= "batch" (:story-id %)))
                       (open-assignment? %))
                 assignments)))

(defn head-sha [root]
  (let [r (process/sh {:dir (str root) :continue true}
                      "git" "rev-parse" "--short=10" "HEAD")]
    (if (zero? (:exit r))
      (str/trim (:out r))
      "HEAD")))

(defn cand [next-action theme-id story-id reason command extras]
  (merge {:priority 20
          :stage-order 1
          :next-action next-action
          :theme-id theme-id
          :story-id story-id
          :reason reason
          :command command}
         extras))

(defn sprint-0-candidates [root theme-id]
  (let [maps? (maps-present? root)]
    (cond
      (not maps?)
      [(cand "write_sprint0_maps" theme-id "s0"
             "scheduled Sprint 0 needs module map and implementation order"
             (str "squad_theme.sh module-map " theme-id " <filled-module-map.md> && "
                  "squad_theme.sh implementation-order " theme-id " <filled-order.md>")
             {:gate "sprint-0-maps"})]

      (not (approval-exists? root "sprint-0-maps"))
      [(cand "create_approval_request" theme-id "s0"
             "Sprint 0 maps ready for approval"
             (str "squad_approval.sh request sprint-0-maps__s0 theme " theme-id
                  " sprint-0-maps Approve_sprint_0_maps maps-ready")
             {:gate "sprint-0-maps"})]

      (approval-approved? root "sprint-0-maps")
      [(cand "complete_sprint_0" theme-id "s0"
             "Sprint 0 maps approved; tag and close Sprint 0"
             (str "squad_sprint.sh complete s0 sprint-0 " (head-sha root))
             {:gate "sprint-0-maps"})]

      :else [])))

(defn impl-candidates [root theme-id sp assignments]
  (let [id (:id sp)]
    (cond
      (not (spec-present? root id))
      [(cand "write_sprint_spec" theme-id id
             "scheduled sprint needs a sprint specification"
             (str "write .squad/sprints/" id "/spec.md")
             {})]

      (nil? (analyst-for assignments id))
      [(assoc (cand "create_assignment" theme-id id
                    "sprint spec ready for analysis"
                    (str "squad_assign.sh create " theme-id " " id
                         " analyst " id "-analysis --auto-instructions --queue-spawn")
                    {:template "analyst"
                     :assignment-id (str id "-analysis")})
              :priority 60)]

      (and (= "merged" (:state (analyst-for assignments id)))
           (plan-recorded? root id)
           (not (approval-exists? root "sprint-plan")))
      [(cand "create_approval_request" theme-id id
             "sprint plan ready for approval"
             (str "squad_approval.sh request sprint-plan__" id " theme " theme-id
                  " sprint-plan Approve_sprint_plan plan-ready")
             {:gate "sprint-plan"})]

      :else [])))

(defn two-track-candidates [root theme-id sp assignments]
  (let [id (:id sp)
        modules (task-ids root id)
        impls (keep (fn [mod]
                      (when (and (not= "ready" (:stage (task-record root id mod)))
                                 (nil? (implementer-for assignments mod)))
                        (let [stories (:stories (task-record root id mod))]
                          (assoc (cand "create_assignment" theme-id mod
                                       "approved plan: implement module task"
                                       (str "squad_assign.sh create " theme-id " " mod
                                            " implementer " id "-" mod "-implementation"
                                            " --auto-instructions --queue-spawn --batch-stories "
                                            stories)
                                       {:template "implementer"
                                        :assignment-id (str id "-" mod "-implementation")})
                                 :priority 60
                                 :stage-order 90))))
                    modules)]
    (vec impls)))

(defn packet-map [root story-id]
  (file-map (fs/path root ".squad" "stories" story-id "packet")))

(defn story-test-spec-ready? [root story-id]
  (let [p (packet-map root story-id)]
    (and (not (str/blank? (get p "gherkin_path")))
         (= "approved" (get p "gherkin_approval"))
         (not (str/blank? (get p "qa_procedure_path")))
         (= "approved" (get p "qa_procedure_approval")))))

(defn all-tasks-ready? [root sprint-id]
  (let [mods (task-ids root sprint-id)]
    (and (seq mods)
         (every? #(= "ready" (:stage (task-record root sprint-id %))) mods))))

(defn all-stories-test-ready? [root sprint-id]
  (let [stories (sprint/read-members root sprint-id)]
    (and (seq stories)
         (every? #(story-test-spec-ready? root %) stories))))

(defn hardener-candidate [root theme-id sp assignments]
  (let [id (:id sp)]
    (when (and (all-tasks-ready? root id)
               (all-stories-test-ready? root id)
               (nil? (hardener-for assignments id)))
      (assoc (cand "create_assignment" theme-id id
                   "modules ready and features approved; harden the sprint"
                   (str "squad_assign.sh create " theme-id " " id
                        " hardener " id "-hardener --auto-instructions --queue-spawn")
                   {:template "hardener"
                    :assignment-id (str id "-hardener")})
             :priority 60
             :stage-order 130))))

(defn sprint-candidates [root]
  (when (sprint-workflow-active? root)
    (let [theme-id (theme-id root)
          assignments (assignment-records root)
          sp (scheduled-sprint root)]
      (cond
        (nil? sp)
        []

        (= "sprint-0" (:kind sp))
        (sprint-0-candidates root theme-id)

        (approval-approved? root "sprint-plan")
        (let [tracks (two-track-candidates root theme-id sp assignments)
              hard (hardener-candidate root theme-id sp assignments)]
          (vec (concat tracks (when hard [hard]))))

        :else
        (impl-candidates root theme-id sp assignments)))))
