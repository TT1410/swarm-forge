(ns swarmforge.sprint-next-test
  "Slice 2: squad_next residuals for scheduled sprints."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [swarmforge.test-support :refer :all]))

(def comment-only-order
  "# No module dependencies.\n")

(defn- setup! [root]
  (init-repo! root)
  (write-file (fs/path root ".swarmforge/roles.tsv")
              (str "squad-leader\tmaster\t" root
                   "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
  (write-file (fs/path root "theme.md") "Hunt the Wumpus.\n")
  (run {:dir root} (script "squad_theme.sh") "create" "htw" "theme.md"))

(defn- next-out [root]
  (:out (run {:dir root} (script "squad_next.sh"))))

(defn- add-story! [root id]
  (write-file (fs/path root "stories" (str id ".md")) (str "# " id "\n\n" id " body.\n"))
  (run {:dir root} (script "squad_theme.sh") "story" "htw" id (str "stories/" id ".md")))

(defn- write-maps! [root]
  (write-file (fs/path root "module-map.md") minimal-module-map)
  (write-file (fs/path root "order.md") comment-only-order)
  (run {:dir root} (script "squad_theme.sh") "module-map" "htw" "module-map.md")
  (run {:dir root} (script "squad_theme.sh") "implementation-order" "htw" "order.md"))

(deftest draft-sprint-0-assembles-not-theme-map
  ;; Given a new project (draft Sprint 0 only)
  ;; When residual runs
  ;; Then assemble the next sprint; do not start the old theme-map path
  (let [root (tmp-dir)]
    (try
      (setup! root)
      (let [out (next-out root)]
        (is (str/includes? out "NEXT_ACTION: assemble_sprint"))
        (is (not (str/includes? out "write_theme_module_map"))))
      (finally (fs/delete-tree root)))))

(deftest scheduled-sprint-0-asks-for-maps
  ;; Given Sprint 0 is scheduled and maps are missing
  ;; When residual runs
  ;; Then write_sprint0_maps
  (let [root (tmp-dir)]
    (try
      (setup! root)
      (run {:dir root} (script "squad_sprint.sh") "schedule" "s0")
      (let [out (next-out root)]
        (is (str/includes? out "NEXT_ACTION: write_sprint0_maps"))
        (is (str/includes? out "squad_theme.sh module-map"))
        (is (str/includes? out "implementation-order")))
      (finally (fs/delete-tree root)))))

(deftest sprint-0-maps-request-approval-then-complete
  ;; Given Sprint 0 scheduled and both maps recorded
  ;; When residual runs
  ;; Then request sprint-0-maps approval; after approve, complete Sprint 0
  (let [root (tmp-dir)]
    (try
      (setup! root)
      (run {:dir root} (script "squad_sprint.sh") "schedule" "s0")
      (write-maps! root)
      (let [ask (next-out root)]
        (is (str/includes? ask "NEXT_ACTION: create_approval_request"))
        (is (str/includes? ask "sprint-0-maps")))
      (run {:dir root} (script "squad_approval.sh") "request"
           "sprint-0-maps__s0" "theme" "htw" "sprint-0-maps"
           "Approve_sprint_0_maps" "maps-ready")
      (run {:dir root} (script "squad_approval.sh") "approve"
           "sprint-0-maps__s0" "approved")
      (let [done (next-out root)]
        (is (str/includes? done "NEXT_ACTION: complete_sprint_0"))
        (is (str/includes? done "squad_sprint.sh complete s0")))
      (run {:dir root} (script "squad_sprint.sh") "complete" "s0" "sprint-0" "abc")
      (is (= "done"
             (let [sf (fs/path root ".squad/sprints/s0/sprint")
                   prefix "state: "]
               (some #(when (str/starts-with? % prefix) (subs % (count prefix)))
                     (str/split-lines (slurp (str sf)))))))
      (finally (fs/delete-tree root)))))

(deftest impl-sprint-asks-for-spec-then-analyst-then-plan
  ;; Given an implementation sprint is scheduled
  ;; Then residual is write_sprint_spec; after spec, create analyst; after merge+tasks, plan approval
  (let [root (tmp-dir)]
    (try
      (setup! root)
      (add-story! root "move")
      (run {:dir root} (script "squad_sprint.sh") "create" "cave" "Cave")
      (run {:dir root} (script "squad_sprint.sh") "move" "move" "cave")
      (run {:dir root} (script "squad_sprint.sh") "schedule" "s0")
      (write-maps! root)
      (run {:dir root} (script "squad_approval.sh") "request"
           "sprint-0-maps__s0" "theme" "htw" "sprint-0-maps"
           "Approve_sprint_0_maps" "maps-ready")
      (run {:dir root} (script "squad_approval.sh") "approve" "sprint-0-maps__s0" "ok")
      (run {:dir root} (script "squad_sprint.sh") "complete" "s0" "sprint-0" "abc")
      (run {:dir root} (script "squad_sprint.sh") "schedule" "cave")
      (is (str/includes? (next-out root) "NEXT_ACTION: write_sprint_spec"))
      (write-file (fs/path root ".squad/sprints/cave/spec.md") "Sprint spec.\n")
      (let [analyst (next-out root)]
        (is (str/includes? analyst "NEXT_ACTION: create_assignment"))
        (is (str/includes? analyst "TEMPLATE: analyst"))
        (is (str/includes? analyst "cave")))
      (write-file (fs/path root ".squad/assignments/cave-analysis/metadata")
                  (str "assignment_id: cave-analysis\n"
                       "theme_id: htw\n"
                       "story_id: cave\n"
                       "template: analyst\n"))
      (write-file (fs/path root ".squad/assignments/cave-analysis/status")
                  "assignment_id: cave-analysis\nstate: merged\n")
      (run {:dir root} (script "squad_sprint.sh") "task" "cave" "world" "move")
      (write-file (fs/path root "interfaces.md") "Room id.\n")
      (run {:dir root} (script "squad_sprint.sh") "interfaces" "cave" "interfaces.md")
      (let [plan (next-out root)]
        (is (str/includes? plan "NEXT_ACTION: create_approval_request"))
        (is (str/includes? plan "sprint-plan")))
      (finally (fs/delete-tree root)))))

(deftest plan-approval-opens-two-tracks-and-delays-hardener
  ;; Given plan approved, a module task, and a story
  ;; Then implementer and/or gherkin appear; hardener waits until both tracks are ready
  (let [root (tmp-dir)]
    (try
      (setup! root)
      (add-story! root "move")
      (run {:dir root} (script "squad_sprint.sh") "create" "cave" "Cave")
      (run {:dir root} (script "squad_sprint.sh") "move" "move" "cave")
      (run {:dir root} (script "squad_sprint.sh") "schedule" "cave")
      (write-file (fs/path root ".squad/sprints/cave/spec.md") "spec\n")
      (write-file (fs/path root ".squad/assignments/cave-analysis/metadata")
                  "assignment_id: cave-analysis\ntheme_id: htw\nstory_id: cave\ntemplate: analyst\n")
      (write-file (fs/path root ".squad/assignments/cave-analysis/status")
                  "assignment_id: cave-analysis\nstate: merged\n")
      (run {:dir root} (script "squad_sprint.sh") "task" "cave" "world" "move")
      (write-file (fs/path root "interfaces.md") "Room.\n")
      (run {:dir root} (script "squad_sprint.sh") "interfaces" "cave" "interfaces.md")
      (run {:dir root} (script "squad_approval.sh") "request"
           "sprint-plan__cave" "theme" "htw" "sprint-plan"
           "Approve_sprint_plan" "plan-ready")
      (run {:dir root} (script "squad_approval.sh") "approve" "sprint-plan__cave" "ok")
      (let [out (next-out root)]
        (is (or (str/includes? out "implementer")
                (str/includes? out "gherkin-writer"))
            "two-track work starts")
        (is (not (str/includes? out "hardener"))))
      (write-file (fs/path root ".squad/sprints/cave/tasks/world")
                  "module: world\nstories: move\nstage: ready\n")
      (write-file (fs/path root ".squad/stories/move/packet")
                  (str "story_id: move\n"
                       "theme_id: htw\n"
                       "gherkin_path: features/move.feature\n"
                       "gherkin_approval: approved\n"
                       "qa_procedure_path: qa/move.md\n"
                       "qa_procedure_approval: approved\n"))
      (let [hard (next-out root)]
        (is (str/includes? hard "hardener")))
      (finally (fs/delete-tree root)))))
