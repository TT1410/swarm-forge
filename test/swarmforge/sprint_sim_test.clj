(ns swarmforge.sprint-sim-test
  "Sprint-model mockup simulator: project, backlog, named sprints, ticks."
  (:require [clojure.test :refer [deftest is]]
            [squad-sprint-sim :as sim]))

(deftest seed-demo-assembles-htw
  ;; Given an empty world
  ;; When the operator seeds the Hunt the Wumpus demo
  ;; Then a project, backlog, and named draft sprints exist
  (let [w (sim/apply-action (sim/world) {:op "seed-demo"})
        dash (sim/dashboard w)]
    (is (= "htw" (get-in dash [:project :id])))
    (is (seq (:backlog dash)))
    (is (some #(= "Sprint 0" (:name %)) (:sprints dash)))
    (is (some #(= "Cave" (:name %)) (:sprints dash)))))

(deftest empty-world-has-no-project
  (let [w (sim/world)]
    (is (nil? (:project w)))
    (is (empty? (:stories w)))
    (is (empty? (:sprints w)))))

(deftest create-project-owns-one-project
  ;; Given an empty world
  ;; When the operator creates project htw
  ;; Then that project is active, Sprint 0 exists, and a second create is rejected
  (let [w (sim/create-project (sim/world) {:id "htw" :name "Hunt the Wumpus"})]
    (is (= "htw" (get-in w [:project :id])))
    (is (= "Hunt the Wumpus" (get-in w [:project :name])))
    (is (= "Sprint 0" (:name (sim/sprint w "s0"))))
    (is (= "sprint-0" (:kind (sim/sprint w "s0"))))
    (is (= "draft" (:state (sim/sprint w "s0"))))
    (is (thrown? Exception (sim/create-project w {:id "other" :name "Nope"})))))

(deftest add-stories-have-name-and-description
  ;; Given a project
  ;; When a story is added with a name and description
  ;; Then both are stored and the story is in the backlog
  (let [w (-> (sim/world)
              (sim/create-project {:id "htw" :name "HTW"})
              (sim/add-story {:id "move" :name "Move" :body "Walk between rooms."}))
        s (first (:stories w))
        dash (sim/dashboard w)]
    (is (= "Move" (:title s)))
    (is (= "Walk between rooms." (:body s)))
    (is (= "Walk between rooms." (:body (first (:backlog dash)))))))

(deftest planner-list-omits-completed-sprints
  ;; Given Sprint 0 is done and Cave is a draft
  ;; When the dashboard is built
  ;; Then the planner list has Cave and not Sprint 0
  (let [w (-> (sim/world)
              (sim/create-project {:id "htw" :name "HTW"})
              (sim/schedule-sprint "s0")
              (#(sim/approve (sim/tick (sim/tick %))
                             (:id (first (:approvals (sim/tick (sim/tick %)))))))
              (sim/create-sprint {:id "cave" :name "Cave"}))
        dash (sim/dashboard w)]
    (is (= ["cave"] (map :id (:open-sprints dash))))
    (is (some #(= "s0" (:id %)) (:sprints dash)))
    (is (some #(= "s0" (:id %)) (:done dash)))))

(deftest named-sprints-hold-moved-stories
  ;; Given backlog stories
  ;; When the operator names sprints and moves stories
  ;; Then each story is in at most one sprint and the rest stay in the backlog
  (let [w (-> (sim/world)
              (sim/create-project {:id "htw" :name "HTW"})
              (sim/add-story {:id "move" :title "move"})
              (sim/add-story {:id "shoot" :title "shoot"})
              (sim/add-story {:id "smell" :title "smell"})
              (sim/create-sprint {:id "cave" :name "Cave"})
              (sim/create-sprint {:id "hunt" :name "Hunt"})
              (sim/move-story "move" "cave")
              (sim/move-story "shoot" "cave"))
        dash (sim/dashboard w)]
    (is (= ["smell"] (map :id (:backlog dash))))
    (is (= ["move" "shoot"] (->> (:sprints dash)
                                 (filter #(= "cave" (:id %)))
                                 first :stories
                                 (map :id))))
    (let [re (sim/move-story w "move" "hunt")]
      (is (= ["shoot"] (map :id (:stories (sim/sprint re "cave")))))
      (is (= ["move"] (map :id (:stories (sim/sprint re "hunt"))))))))

(deftest impl-sprint-waits-for-sprint-0
  ;; Given Sprint 0 is still draft
  ;; When the operator schedules Cave
  ;; Then scheduling fails
  (let [w (-> (sim/world)
              (sim/create-project {:id "htw" :name "HTW"})
              (sim/create-sprint {:id "cave" :name "Cave"}))]
    (is (thrown? Exception (sim/schedule-sprint w "cave")))
    (is (= "draft" (:state (sim/sprint w "s0"))))))

(deftest only-one-sprint-can-be-scheduled
  ;; Given two named drafts
  ;; When one is scheduled
  ;; Then the other cannot be scheduled until the first completes or is cancelled
  (let [w (-> (sim/world)
              (sim/create-project {:id "htw" :name "HTW"})
              (sim/add-story {:id "move" :title "move"})
              (sim/create-sprint {:id "cave" :name "Cave"})
              (sim/move-story "move" "cave")
              (sim/schedule-sprint "s0"))]
    (is (= "s0" (:scheduled-id w)))
    (is (= "scheduled" (:state (sim/sprint w "s0"))))
    (is (thrown? Exception (sim/schedule-sprint w "cave")))))

(deftest drafts-can-be-assembled-while-one-is-in-flight
  (let [w (-> (sim/world)
              (sim/create-project {:id "htw" :name "HTW"})
              (sim/add-story {:id "move" :title "move"})
              (sim/add-story {:id "shoot" :title "shoot"})
              (sim/schedule-sprint "s0")
              (sim/create-sprint {:id "cave" :name "Cave"})
              (sim/move-story "move" "cave"))]
    (is (= "draft" (:state (sim/sprint w "cave"))))
    (is (= ["move"] (map :id (:stories (sim/sprint w "cave")))))))

(defn- approve-next [w]
  (let [w (sim/tick (sim/tick w))
        a (first (:approvals w))]
    (sim/approve w (:id a))))

(deftest cancel-keeps-stories-and-tags-the-run
  ;; Given a scheduled implementation sprint
  ;; When it is cancelled
  ;; Then the sprint stays assembled; only the run is tagged abandoned
  (let [w (-> (sim/world)
              (sim/create-project {:id "htw" :name "HTW"})
              (sim/add-story {:id "move" :title "move"})
              (sim/schedule-sprint "s0")
              approve-next
              (sim/create-sprint {:id "cave" :name "Cave"})
              (sim/move-story "move" "cave")
              (sim/schedule-sprint "cave")
              (sim/cancel-sprint "cave"))
        dash (sim/dashboard w)]
    (is (= "abandoned" (:state (sim/sprint w "cave"))))
    (is (some? (:branch (sim/sprint w "cave"))))
    (is (some? (:tag (sim/sprint w "cave"))))
    (is (= ["move"] (map :id (:stories (sim/sprint w "cave")))))
    (is (empty? (:backlog dash)))
    (is (nil? (:scheduled-id w)))))

(deftest abandoned-sprint-is-scheduled-without-ceremony
  ;; Given a cancelled sprint that still holds its stories
  ;; When the operator schedules it again
  ;; Then it is in flight with those same stories — no reopen, no re-add
  (let [w (-> (sim/world)
              (sim/create-project {:id "htw" :name "HTW"})
              (sim/add-story {:id "move" :title "move"})
              (sim/schedule-sprint "s0")
              approve-next
              (sim/create-sprint {:id "cave" :name "Cave"})
              (sim/move-story "move" "cave")
              (sim/schedule-sprint "cave")
              (sim/cancel-sprint "cave")
              (sim/schedule-sprint "cave"))]
    (is (= "scheduled" (:state (sim/sprint w "cave"))))
    (is (= "cave" (:scheduled-id w)))
    (is (= ["move"] (map :id (:stories (sim/sprint w "cave")))))))

(deftest sprint-0-tick-asks-for-map-approval-then-completes
  ;; Given sprint 0 scheduled
  ;; When the simulator ticks
  ;; Then SL map/order approval appears; approving completes sprint 0 with a tag
  (let [started (-> (sim/world)
                    (sim/create-project {:id "htw" :name "HTW"})
                    (sim/add-story {:id "move" :title "move"})
                    (sim/schedule-sprint "s0"))
        ticked (sim/tick (sim/tick started))
        approval (first (:approvals ticked))
        done (sim/approve ticked (:id approval))]
    (is (some? approval))
    (is (= "sprint-0-maps" (:kind approval)))
    (is (= "done" (:state (sim/sprint done "s0"))))
    (is (some? (:tag (sim/sprint done "s0"))))
    (is (nil? (:scheduled-id done)))))

(deftest implementation-sprint-plan-then-two-tracks
  ;; Given sprint 0 is done and Cave is scheduled
  ;; When SL/analyst ticks complete and the plan is approved
  ;; Then module tasks appear in Coding and stories go to test specification
  (let [w (-> (sim/world)
              (sim/create-project {:id "htw" :name "HTW"})
              (sim/add-story {:id "move" :title "move"})
              (sim/add-story {:id "shoot" :title "shoot"})
              (sim/schedule-sprint "s0"))
        w (approve-next w)
        w (-> w
              (sim/create-sprint {:id "cave" :name "Cave"})
              (sim/move-story "move" "cave")
              (sim/move-story "shoot" "cave")
              (sim/schedule-sprint "cave"))
        planned (sim/tick (sim/tick (sim/tick w)))
        approval (first (filter #(= "sprint-plan" (:kind %)) (:approvals planned)))
        live (sim/approve planned (:id approval))
        dash (sim/dashboard live)]
    (is (some? approval))
    (is (seq (:tasks dash)))
    (is (every? #(= "task" (:kind %)) (:tasks dash)))
    (is (every? #(= "story" (:kind %)) (:specifying dash)))
    (is (seq (:work dash)))))

(deftest tick-advances-work-toward-sprint-completion
  ;; Given an approved Cave plan
  ;; When the simulator ticks enough
  ;; Then modules and features finish, the sprint is finalized and tagged
  (let [seed (-> (sim/world)
                 (sim/create-project {:id "htw" :name "HTW"})
                 (sim/add-story {:id "move" :title "move"})
                 (sim/schedule-sprint "s0"))
        after-s0 (approve-next seed)
        live (-> after-s0
                 (sim/create-sprint {:id "cave" :name "Cave"})
                 (sim/move-story "move" "cave")
                 (sim/schedule-sprint "cave"))
        planned (sim/tick (sim/tick (sim/tick live)))
        running (sim/approve planned (:id (first (:approvals planned))))
        done (loop [w running n 0]
               (cond
                 (= "done" (:state (sim/sprint w "cave"))) w
                 (> n 80) w
                 (seq (:approvals w)) (recur (sim/approve w (:id (first (:approvals w)))) (inc n))
                 :else (recur (sim/tick w) (inc n))))]
    (is (= "done" (:state (sim/sprint done "cave"))))
    (is (some? (:tag (sim/sprint done "cave"))))
    (is (nil? (:scheduled-id done)))))
