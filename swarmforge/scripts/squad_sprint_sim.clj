(ns squad-sprint-sim
  "In-memory sprint-model simulator that drives the mockup.
  Tick / approval timing follows the old squad_simulator idea without the live FSM."
  (:require [clojure.string :as str]))

(defn world []
  {:tick 0
   :project nil
   :stories []
   :sprints []
   :scheduled-id nil
   :approvals []
   :work []
   :next-action "—"
   :seq 0})

(defn- fail! [msg]
  (throw (ex-info msg {})))

(defn- next-seq [w]
  [(inc (:seq w)) (assoc w :seq (inc (:seq w)))])

(defn- story [w id]
  (first (filter #(= id (:id %)) (:stories w))))

(defn sprint [w id]
  (let [sp (first (filter #(= id (:id %)) (:sprints w)))
        stories (filterv #(= id (:sprint-id %)) (:stories w))]
    (when sp (assoc sp :stories stories))))

(defn- replace-sprint [w id f]
  (update w :sprints (fn [sps] (mapv #(if (= id (:id %)) (f %) %) sps))))

(defn- replace-story [w id f]
  (update w :stories (fn [ss] (mapv #(if (= id (:id %)) (f %) %) ss))))

(defn- locked-sprint? [sp]
  (contains? #{"scheduled" "done"} (:state sp)))

(defn create-sprint [w {:keys [id name kind]}]
  (when-not (:project w)
    (fail! "No project"))
  (when (sprint w id)
    (fail! "Sprint already exists"))
  (update w :sprints conj {:id id
                           :name (or name id)
                           :kind (or kind "impl")
                           :state "draft"
                           :phase nil
                           :tag nil
                           :branch nil
                           :tasks []}))

(defn create-project [w {:keys [id name]}]
  (when (:project w)
    (fail! "Only one project at a time"))
  (-> w
      (assoc :project {:id id :name (or name id)} :next-action "add_story")
      (create-sprint {:id "s0" :name "Sprint 0" :kind "sprint-0"})))

(defn add-story [w {:keys [id name title body description]}]
  (when-not (:project w)
    (fail! "No project"))
  (when (story w id)
    (fail! "Story already exists"))
  (update w :stories conj {:id id
                           :title (or name title id)
                           :body (or body description "")
                           :sprint-id nil
                           :stage nil}))

(defn move-story [w story-id sprint-id]
  (let [s (or (story w story-id) (fail! "Unknown story"))
        dest (when sprint-id (or (sprint w sprint-id) (fail! "Unknown sprint")))
        src (when (:sprint-id s) (sprint w (:sprint-id s)))]
    (when (and src (locked-sprint? src))
      (fail! "Story is locked in a scheduled sprint"))
    (when (and dest (locked-sprint? dest))
      (fail! "Cannot add a story to a locked sprint"))
    (replace-story w story-id #(assoc % :sprint-id sprint-id))))

(defn- add-approval [w kind sprint-id]
  (let [[n w] (next-seq w)
        id (str kind "-" n)]
    (-> w
        (update :approvals conj {:id id :kind kind :sprint-id sprint-id :target sprint-id})
        (assoc :next-action (str "approve_" kind)))))

(defn schedule-sprint [w sprint-id]
  (let [sp (or (sprint w sprint-id) (fail! "Unknown sprint"))
        s0 (sprint w "s0")]
    (when (:scheduled-id w)
      (fail! "A sprint is already scheduled"))
    (when-not (contains? #{"draft" "abandoned"} (:state sp))
      (fail! "Only a draft or abandoned sprint can be scheduled"))
    (when (and (= "impl" (:kind sp))
               s0
               (not= "done" (:state s0)))
      (fail! "Sprint 0 must be complete before an implementation sprint can be scheduled"))
    (let [phase (if (= "sprint-0" (:kind sp)) "sl-maps" "sl-spec")]
      (-> w
          (assoc :scheduled-id sprint-id :next-action phase)
          (replace-sprint sprint-id #(assoc % :state "scheduled" :phase phase
                                           :tasks []))))))

(defn cancel-sprint [w sprint-id]
  (let [sp (or (sprint w sprint-id) (fail! "Unknown sprint"))]
    (when (not= "scheduled" (:state sp))
      (fail! "Only a scheduled sprint can be cancelled"))
    (-> w
        (assoc :scheduled-id nil
               :approvals []
               :work []
               :next-action "assemble_sprint")
        (replace-sprint sprint-id
                        #(assoc % :state "abandoned"
                                :phase nil
                                :tag (str "abandoned-" sprint-id)
                                :branch (str "abandoned/" sprint-id)
                                :tasks []))
        (update :stories (fn [ss]
                           (mapv #(if (= sprint-id (:sprint-id %))
                                    (assoc % :stage nil)
                                    %)
                                 ss))))))

(def story-modules
  {"move" ["world" "command"]
   "shoot" ["world" "command"]
   "find-wumpus" ["hunt" "world"]
   "arrows" ["hunt"]
   "win" ["hunt"]
   "same-map" ["replay"]
   "score" ["replay"]
   "smell" ["world"]
   "bats" ["world"]
   "pits" ["world"]})

(defn- modules-for [story-ids]
  (reduce (fn [acc sid]
            (let [mods (get story-modules sid [(str sid "-mod")])]
              (reduce (fn [m mod]
                        (update m mod (fnil conj []) sid))
                      acc
                      mods)))
          {}
          story-ids))

(defn- start-two-track [w sprint-id]
  (let [ids (mapv :id (:stories (sprint w sprint-id)))
        tasks (mapv (fn [[mod sids]]
                      {:id mod :module mod :stories sids :kind "task" :stage "implement"})
                    (modules-for ids))]
    (-> w
        (replace-sprint sprint-id #(assoc % :phase "two-track" :tasks tasks))
        (update :stories (fn [ss]
                           (mapv #(if (= sprint-id (:sprint-id %))
                                    (assoc % :stage "gherkin")
                                    %)
                                 ss)))
        (assoc :next-action "implement_task"
               :work (concat
                      (map (fn [t] {:id (:id t) :kind "task" :label (:module t) :role "implementer"})
                           tasks)
                      (map (fn [id] {:id id :kind "story" :label id :role "gherkin-writer"})
                           ids))))))

(defn- task-role [stage]
  (case stage
    "implement" "implementer"
    "clean" "cleaner"
    "review" "code-reviewer"
    "ready" "—"))

(defn- story-role [stage]
  (case stage
    "gherkin" "gherkin-writer"
    "gherkin-review" "gherkin-reviewer"
    "qa" "qa-proc-writer"
    "qa-review" "qa-proc-reviewer"
    "specified" "—"))

(defn- next-task-stage [stage]
  (case stage
    "implement" "clean"
    "clean" "review"
    "review" "ready"
    "ready" "ready"))

(defn- next-story-stage [stage]
  (case stage
    "gherkin" "gherkin-review"
    "gherkin-review" "qa"
    "qa" "qa-review"
    "qa-review" "specified"
    "specified" "specified"))

(defn- refresh-work [w sprint-id]
  (let [sp (sprint w sprint-id)
        tasks (remove #(= "ready" (:stage %)) (:tasks sp))
        stories (remove #(= "specified" (:stage %))
                        (filter #(= sprint-id (:sprint-id %)) (:stories w)))]
    (assoc w :work
           (concat
            (map (fn [t] {:id (:id t) :kind "task" :label (:module t) :role (task-role (:stage t))})
                 tasks)
            (map (fn [s] {:id (:id s) :kind "story" :label (:id s) :role (story-role (:stage s))})
                 stories)))))

(defn- advance-two-track [w sprint-id]
  (let [w (-> w
              (replace-sprint sprint-id
                              (fn [sp]
                                (update sp :tasks
                                        (fn [ts]
                                          (mapv #(update % :stage next-task-stage) ts)))))
              (update :stories
                      (fn [ss]
                        (mapv #(if (= sprint-id (:sprint-id %))
                                 (update % :stage next-story-stage)
                                 %)
                              ss))))
        sp (sprint w sprint-id)
        tasks-ready? (every? #(= "ready" (:stage %)) (:tasks sp))
        stories-ready? (every? #(= "specified" (:stage %)) (:stories sp))]
    (if (and tasks-ready? stories-ready?)
      (-> w
          (replace-sprint sprint-id #(assoc % :phase "finalizing"))
          (assoc :work [{:id sprint-id :kind "sprint" :label (:name sp) :role "hardener"}]
                 :next-action "harden_sprint"))
      (refresh-work w sprint-id))))

(defn- advance-finalizing [w sprint-id]
  (let [role (or (:role (first (:work w))) "hardener")]
    (case role
      "hardener" (assoc w :work [{:id sprint-id :kind "sprint" :label sprint-id :role "qa"}]
                        :next-action "qa_sprint")
      "qa" (assoc w :work [{:id sprint-id :kind "sprint" :label sprint-id :role "architect"}]
                  :next-action "architect_sprint")
      "architect" (-> w
                      (replace-sprint sprint-id
                                      #(assoc % :state "done" :phase nil
                                              :tag (str "v" sprint-id)))
                      (assoc :scheduled-id nil :work [] :approvals []
                             :next-action "assemble_sprint"))
      w)))

(defn- tick-scheduled [w]
  (let [id (:scheduled-id w)
        sp (sprint w id)
        phase (:phase sp)]
    (case phase
      "sl-maps" (-> w
                    (replace-sprint id #(assoc % :phase "awaiting-maps"))
                    (add-approval "sprint-0-maps" id))
      "sl-spec" (-> w
                    (replace-sprint id #(assoc % :phase "analysis"))
                    (assoc :next-action "analyze_sprint"))
      "analysis" (-> w
                     (replace-sprint id #(assoc % :phase "awaiting-plan"))
                     (add-approval "sprint-plan" id))
      "two-track" (advance-two-track w id)
      "finalizing" (advance-finalizing w id)
      w)))

(defn tick [w]
  (let [w (update w :tick inc)]
    (if (:scheduled-id w)
      (tick-scheduled w)
      w)))

(defn approve [w approval-id]
  (let [a (or (first (filter #(= approval-id (:id %)) (:approvals w)))
              (fail! "Unknown approval"))
        w (update w :approvals (fn [as] (vec (remove #(= approval-id (:id %)) as))))
        id (:sprint-id a)]
    (case (:kind a)
      "sprint-0-maps" (-> w
                          (replace-sprint id #(assoc % :state "done" :phase nil :tag "v0"))
                          (assoc :scheduled-id nil :work [] :next-action "assemble_sprint"))
      "sprint-plan" (start-two-track w id)
      w)))

(defn- story-card [s]
  {:id (:id s)
   :title (:title s)
   :body (:body s)
   :kind "story"
   :stage (:stage s)
   :sprint-id (:sprint-id s)})

(defn- task-card [t]
  {:id (:id t)
   :title (:module t)
   :kind "task"
   :stage (:stage t)
   :stories (:stories t)})

(defn dashboard [w]
  (let [backlog (filterv (comp nil? :sprint-id) (:stories w))
        sprints (mapv (fn [sp]
                        (assoc (sprint w (:id sp))
                               :stories (mapv story-card (:stories (sprint w (:id sp))))))
                      (:sprints w))
        live (when-let [id (:scheduled-id w)] (sprint w id))
        specifying (when live
                     (if (= "two-track" (:phase live))
                       (mapv story-card (:stories live))
                       []))
        coding (when live
                 (if (= "two-track" (:phase live))
                   (mapv task-card (:tasks live))
                   []))
        finalizing (when (and live (= "finalizing" (:phase live)))
                     [{:id (:id live) :title (:name live) :kind "sprint" :stage "finalizing"}])
        done (filterv #(= "done" (:state %)) sprints)
        busy (count (:work w))]
    {:project (:project w)
     :backlog (mapv story-card backlog)
     :sprints sprints
     :open-sprints (filterv #(not= "done" (:state %)) sprints)
     :scheduled live
     :specifying (or specifying [])
     :coding (or coding [])
     :tasks (or coding [])
     :finalizing (or finalizing [])
     :done (mapv (fn [sp] {:id (:id sp) :title (:name sp) :kind "sprint"
                           :tag (:tag sp) :stage "done"})
                 done)
     :work (:work w)
     :approvals (:approvals w)
     :next-action (:next-action w)
     :tick (:tick w)
     :busy busy
     :meta (format "slots · %d busy · tick %d · next action: %s"
                   busy (:tick w) (:next-action w))}))

(defn seed-demo [_w]
  (-> (world)
      (create-project {:id "htw" :name "Hunt the Wumpus"})
      (add-story {:id "move" :name "Move" :body "Walk between rooms."})
      (add-story {:id "shoot" :name "Shoot" :body "Fire an arrow down a tunnel."})
      (add-story {:id "smell" :name "Smell" :body "Sense the wumpus in an adjacent room."})
      (add-story {:id "bats" :name "Bats" :body "Super bats carry the hunter away."})
      (add-story {:id "pits" :name "Pits" :body "Bottomless pits end the hunt."})
      (add-story {:id "find-wumpus" :name "Find Wumpus" :body "Locate the wumpus."})
      (add-story {:id "arrows" :name "Arrows" :body "Limited arrows."})
      (add-story {:id "win" :name "Win" :body "Kill the wumpus to win."})
      (add-story {:id "same-map" :name "Same map" :body "Replay on the same cave."})
      (add-story {:id "score" :name "Score" :body "Keep a score across hunts."})
      (create-sprint {:id "cave" :name "Cave"})
      (create-sprint {:id "hunt" :name "Hunt"})
      (create-sprint {:id "replay" :name "Replay"})
      (move-story "move" "cave")
      (move-story "shoot" "cave")
      (move-story "find-wumpus" "hunt")
      (move-story "arrows" "hunt")
      (move-story "win" "hunt")
      (move-story "same-map" "replay")
      (move-story "score" "replay")))

(defn- field [cmd k]
  (or (get cmd k)
      (get cmd (keyword (str/replace (name k) "-" "_")))))

(defn apply-action [w cmd]
  (let [op (str (or (field cmd :op) ""))]
    (case op
      "create-project" (create-project w cmd)
      "add-story" (add-story w cmd)
      "create-sprint" (create-sprint w cmd)
      "move-story" (move-story w (field cmd :story-id) (field cmd :sprint-id))
      "schedule-sprint" (schedule-sprint w (field cmd :sprint-id))
      "cancel-sprint" (cancel-sprint w (field cmd :sprint-id))
      "approve" (approve w (field cmd :approval-id))
      "tick" (tick w)
      "seed-demo" (seed-demo w)
      (fail! (str "Unknown op " op)))))
