(ns swarmforge.sprint-control-test
  "Slice 1: durable sprint tool — project, membership, schedule, cancel, tasks, complete."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [swarmforge.test-support :refer :all]))

(defn- field [file k]
  (when (fs/regular-file? file)
    (let [prefix (str k ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(defn- sprint-file [root id]
  (fs/path root ".squad" "sprints" id "sprint"))

(defn- sprint-stories-file [root id]
  (fs/path root ".squad" "sprints" id "stories"))

(defn- setup-project! [root]
  (init-repo! root)
  (write-file (fs/path root ".swarmforge/roles.tsv")
              (str "squad-leader\tmaster\t" root
                   "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
  (write-file (fs/path root "theme.md") "Hunt the Wumpus.\n")
  (run {:dir root} (script "squad_theme.sh") "create" "htw" "theme.md"))

(defn- add-story-file! [root id title body]
  (write-file (fs/path root "stories" (str id ".md"))
              (str "# " title "\n\n" body "\n"))
  (run {:dir root} (script "squad_theme.sh") "story" "htw" id (str "stories/" id ".md")))

(deftest theme-create-makes-draft-sprint-0
  ;; Given no project
  ;; When the operator creates theme htw
  ;; Then Sprint 0 exists as draft, kind sprint-0, with no stories
  (let [root (tmp-dir)]
    (try
      (setup-project! root)
      (let [sf (sprint-file root "s0")]
        (is (fs/regular-file? sf))
        (is (= "Sprint 0" (field sf "name")))
        (is (= "sprint-0" (field sf "kind")))
        (is (= "draft" (field sf "state")))
        (is (or (not (fs/exists? (sprint-stories-file root "s0")))
                (str/blank? (str/trim (slurp (str (sprint-stories-file root "s0"))))))))
      (finally (fs/delete-tree root)))))

(deftest registered-story-is-unscheduled-backlog
  ;; Given a project
  ;; When a named story with a description is registered
  ;; Then it is listed as backlog and the file still has name and body
  (let [root (tmp-dir)]
    (try
      (setup-project! root)
      (add-story-file! root "move" "Move" "Walk between rooms.")
      (let [listed (:out (run {:dir root} (script "squad_sprint.sh") "stories" "backlog"))
            text (slurp (str (fs/path root "stories/move.md")))]
        (is (str/includes? listed "move"))
        (is (str/includes? text "Move"))
        (is (str/includes? text "Walk between rooms.")))
      (finally (fs/delete-tree root)))))

(deftest create-and-move-stories-into-named-sprint
  ;; Given backlog stories
  ;; When the operator creates cave and moves move and shoot
  ;; Then those are cave members and smell stays in the backlog
  (let [root (tmp-dir)]
    (try
      (setup-project! root)
      (doseq [[id title] [["move" "Move"] ["shoot" "Shoot"] ["smell" "Smell"]]]
        (add-story-file! root id title (str title " body.")))
      (run {:dir root} (script "squad_sprint.sh") "create" "cave" "Cave")
      (run {:dir root} (script "squad_sprint.sh") "move" "move" "cave")
      (run {:dir root} (script "squad_sprint.sh") "move" "shoot" "cave")
      (let [cave (:out (run {:dir root} (script "squad_sprint.sh") "stories" "cave"))
            back (:out (run {:dir root} (script "squad_sprint.sh") "stories" "backlog"))
            sf (sprint-file root "cave")]
        (is (= "impl" (field sf "kind")))
        (is (= "draft" (field sf "state")))
        (is (str/includes? cave "move"))
        (is (str/includes? cave "shoot"))
        (is (not (str/includes? cave "smell")))
        (is (str/includes? back "smell"))
        (is (not (str/includes? back "move"))))
      (finally (fs/delete-tree root)))))

(deftest impl-sprint-cannot-schedule-before-sprint-0-done
  ;; Given Sprint 0 is still draft
  ;; When the operator schedules an implementation sprint
  ;; Then the command fails and the impl sprint stays draft
  (let [root (tmp-dir)]
    (try
      (setup-project! root)
      (run {:dir root} (script "squad_sprint.sh") "create" "cave" "Cave")
      (let [fail (run {:dir root :ok? false} (script "squad_sprint.sh") "schedule" "cave")]
        (is (not (zero? (:exit fail))))
        (is (str/includes? (str (:err fail) (:out fail)) "Sprint 0"))
        (is (= "draft" (field (sprint-file root "cave") "state")))
        (is (= "draft" (field (sprint-file root "s0") "state"))))
      (finally (fs/delete-tree root)))))

(deftest only-one-sprint-may-be-scheduled
  (let [root (tmp-dir)]
    (try
      (setup-project! root)
      (add-story-file! root "move" "Move" "Walk.")
      (run {:dir root} (script "squad_sprint.sh") "create" "cave" "Cave")
      (run {:dir root} (script "squad_sprint.sh") "move" "move" "cave")
      (run {:dir root} (script "squad_sprint.sh") "schedule" "s0")
      (is (= "scheduled" (field (sprint-file root "s0") "state")))
      (let [fail (run {:dir root :ok? false} (script "squad_sprint.sh") "schedule" "cave")]
        (is (not (zero? (:exit fail)))))
      (finally (fs/delete-tree root)))))

(deftest scheduled-sprint-locks-membership
  (let [root (tmp-dir)]
    (try
      (setup-project! root)
      (add-story-file! root "move" "Move" "Walk.")
      (add-story-file! root "shoot" "Shoot" "Fire.")
      (run {:dir root} (script "squad_sprint.sh") "create" "cave" "Cave")
      (run {:dir root} (script "squad_sprint.sh") "move" "move" "cave")
      (run {:dir root} (script "squad_sprint.sh") "complete" "s0" "sprint-0" "aaa")
      (run {:dir root} (script "squad_sprint.sh") "schedule" "cave")
      (is (= "scheduled" (field (sprint-file root "cave") "state")))
      (let [fail (run {:dir root :ok? false} (script "squad_sprint.sh") "move" "shoot" "cave")]
        (is (not (zero? (:exit fail)))))
      (finally (fs/delete-tree root)))))

(deftest cancel-keeps-stories-and-records-abandoned-run
  ;; Given a scheduled sprint
  ;; When it is cancelled
  ;; Then it is abandoned, stories stay, abandoned.tsv is written, and it can be scheduled again
  (let [root (tmp-dir)]
    (try
      (setup-project! root)
      (add-story-file! root "move" "Move" "Walk.")
      (run {:dir root} (script "squad_sprint.sh") "create" "cave" "Cave")
      (run {:dir root} (script "squad_sprint.sh") "move" "move" "cave")
      (run {:dir root} (script "squad_sprint.sh") "complete" "s0" "sprint-0" "aaa")
      (run {:dir root} (script "squad_sprint.sh") "schedule" "cave")
      (run {:dir root} (script "squad_sprint.sh") "cancel" "cave")
      (let [sf (sprint-file root "cave")
            members (:out (run {:dir root} (script "squad_sprint.sh") "stories" "cave"))
            abandoned (slurp (str (fs/path root ".squad/sprints/abandoned.tsv")))]
        (is (= "abandoned" (field sf "state")))
        (is (str/includes? members "move"))
        (is (str/includes? abandoned "cave"))
        (is (str/includes? abandoned "abandoned/cave")))
      (run {:dir root} (script "squad_sprint.sh") "schedule" "cave")
      (is (= "scheduled" (field (sprint-file root "cave") "state")))
      (finally (fs/delete-tree root)))))

(deftest record-plan-writes-tasks-and-interfaces
  ;; Given a sprint
  ;; When the plan is recorded
  ;; Then each module task lists its stories and interfaces.md is stored
  (let [root (tmp-dir)]
    (try
      (setup-project! root)
      (add-story-file! root "move" "Move" "Walk.")
      (add-story-file! root "shoot" "Shoot" "Fire.")
      (run {:dir root} (script "squad_sprint.sh") "create" "cave" "Cave")
      (run {:dir root} (script "squad_sprint.sh") "move" "move" "cave")
      (run {:dir root} (script "squad_sprint.sh") "move" "shoot" "cave")
      (write-file (fs/path root "interfaces.md") "World exposes Room.\n")
      (run {:dir root} (script "squad_sprint.sh") "task" "cave" "world" "move,shoot")
      (run {:dir root} (script "squad_sprint.sh") "task" "cave" "command" "move,shoot")
      (run {:dir root} (script "squad_sprint.sh") "interfaces" "cave" "interfaces.md")
      (let [world (slurp (str (fs/path root ".squad/sprints/cave/tasks/world")))
            ifaces (slurp (str (fs/path root ".squad/sprints/cave/interfaces.md")))]
        (is (str/includes? world "move"))
        (is (str/includes? world "shoot"))
        (is (str/includes? world "module: world"))
        (is (str/includes? ifaces "World exposes Room.")))
      (finally (fs/delete-tree root)))))

(deftest complete-registers-tag-and-drops-from-open-list
  ;; Given a sprint
  ;; When it is completed with a tag and sha
  ;; Then completed.tsv has the row, state is done, and list omits it
  (let [root (tmp-dir)]
    (try
      (setup-project! root)
      (run {:dir root} (script "squad_sprint.sh") "create" "cave" "Cave")
      (run {:dir root} (script "squad_sprint.sh") "complete" "cave" "v-cave" "abc123")
      (let [sf (sprint-file root "cave")
            done (slurp (str (fs/path root ".squad/sprints/completed.tsv")))
            listed (:out (run {:dir root} (script "squad_sprint.sh") "list"))]
        (is (= "done" (field sf "state")))
        (is (str/includes? done "cave"))
        (is (str/includes? done "v-cave"))
        (is (str/includes? done "abc123"))
        (is (not (str/includes? listed "cave")))
        (is (str/includes? listed "s0")))
      (finally (fs/delete-tree root)))))
