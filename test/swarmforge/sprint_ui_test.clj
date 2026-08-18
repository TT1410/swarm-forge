(ns swarmforge.sprint-ui-test
  "Slice 4: dashboard HTML and /api/state for the sprint form."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [squadd.web :as web]
            [swarmforge.test-support :refer :all]))

(defn- setup-project! [root]
  (init-repo! root)
  (write-file (fs/path root ".swarmforge/roles.tsv")
              (str "squad-leader\tmaster\t" root
                   "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
  (write-file (fs/path root "theme.md") "HTW\n")
  (run {:dir root} (script "squad_theme.sh") "create" "htw" "theme.md"))

(deftest dashboard-html-has-sprint-controls
  (let [html web/dashboard-html]
    (is (str/includes? html "Add Story"))
    (is (str/includes? html "id=\"btn-new-project\""))
    (is (str/includes? html "id=\"sprint-chip\""))
    (is (str/includes? html "id=\"planner-ok\""))
    (is (str/includes? html "ico-card"))
    (is (str/includes? html "clipboard"))
    (is (str/includes? html "id=\"story-name\""))
    (is (str/includes? html "id=\"story-body\""))
    (is (not (str/includes? html "Add New Item")))
    (is (not (str/includes? html "SL classifies project vs story")))
    (is (not (str/includes? html "sprint-kind")))))

(deftest web-state-exposes-sprint-board
  ;; Given a project with Sprint 0 done and Cave draft
  ;; When /api/state is built
  ;; Then open-sprints omits done; done lists Sprint 0
  (let [root (tmp-dir)]
    (try
      (setup-project! root)
      (run {:dir root} (script "squad_sprint.sh") "create" "cave" "Cave")
      (run {:dir root} (script "squad_sprint.sh") "complete" "s0" "sprint-0" "aaa")
      (let [state (web/web-state root)
            open (get state "open-sprints")
            done (get state "done")]
        (is (map? (get state "project")))
        (is (= "htw" (get-in state ["project" "id"])))
        (is (some #(= "cave" (get % "id")) open))
        (is (not (some #(= "s0" (get % "id")) open)))
        (is (some #(= "s0" (get % "id")) done)))
      (finally (fs/delete-tree root)))))

(deftest add-story-via-api-is-unscheduled
  ;; Given a project
  ;; When the operator adds a named story
  ;; Then it is in the unscheduled backlog, not in a sprint
  (let [root (tmp-dir)]
    (try
      (setup-project! root)
      (let [res (web/route-web-request
                 root "POST" "/api/stories"
                 "{\"name\":\"Move\",\"body\":\"Walk between rooms.\"}")
            state (web/web-state root)
            bl (get state "backlog")]
        (is (= 200 (:status res)))
        (is (some #(= "move" (get % "id")) bl))
        (is (not (some (fn [sp]
                         (some #(= "move" (get % "id")) (get sp "stories")))
                       (get state "open-sprints")))))
      (finally (fs/delete-tree root)))))

(deftest schedule-sprint-via-api
  ;; Given a draft implementation sprint
  ;; When the operator schedules it through the dashboard API
  ;; Then /api/state shows it scheduled
  (let [root (tmp-dir)]
    (try
      (setup-project! root)
      (is (= 200 (:status (web/route-web-request
                           root "POST" "/api/sprints"
                           "{\"name\":\"Cave\"}"))))
      (let [sched (web/route-web-request root "POST" "/api/sprints/cave/schedule" "")
            state (web/web-state root)
            cave (first (filter #(= "cave" (get % "id"))
                                (get state "open-sprints")))]
        (is (= 200 (:status sched)))
        (is (= "scheduled" (get cave "state")))
        (is (= "cave" (get-in state ["scheduled" "id"]))))
      (finally (fs/delete-tree root)))))