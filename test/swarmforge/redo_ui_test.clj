(ns swarmforge.redo-ui-test
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [squadd.web :as web]
            [swarmforge.test-support :refer :all]))

(def html
  (slurp (str (fs/path repo-root "swarmforge/scripts/squadd/dashboard.html"))))

(deftest cockpit-drops-project-and-sprint
  ;; Given the cockpit HTML
  ;; Then backlog + Add Story stay; project pill, Projects rail, and sprint are gone
  (is (str/includes? html "id=\"backlog-deck\""))
  (is (not (str/includes? html "id=\"theme-pill\"")))
  (is (not (str/includes? html ">Projects<")))
  (is (not (str/includes? html "sprint"))
      "no sprint chip or planner")
  (is (str/includes? html "Add Story")))

(deftest start-label-not-classify
  ;; Given the backlog editor
  ;; Then Start is the pipeline button; no classify copy
  (is (re-find #"Start" html))
  (is (not (str/includes? html "SL classifies project vs story"))))

(deftest attention-has-view-document
  (is (str/includes? html "View document")))

(deftest short-stage-pills-exist
  (doseq [pill ["plan" "gherkin" "qa-proc" "implement" "clean" "review"
                "harden" "qa" "architect" "si" "done"]]
    (is (str/includes? html pill))))

(deftest web-state-has-backlog-and-started-story-without-theme
  ;; Given an open backlog item and a started story
  ;; When web-state is built
  ;; Then open items stay in backlog; started story is Specifying with pill plan
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (let [created (web/create-backlog! root {:title "Fog cues" :body "Hints."})
            open-id (get-in created [:item "id"])
            _ (write-frame-ready! root)
            started (web/start-backlog! root open-id)
            story-id (get-in started [:item "story_id"])
            state (web/web-state root)
            stories (get state "stories")
            card (first (filter #(= story-id (get % "story_id")) stories))]
        (is (seq (get state "backlog")))
        (is (some? card))
        (is (= "specifying" (get card "board_column")))
        (is (= "plan" (get card "stage_label")))
        (is (nil? (get state "current_theme_id"))))
      (finally
        (fs/delete-tree root)))))

(deftest attention-only-lists-operator-gates
  ;; Given leftover theme and final approvals plus a plan gate
  ;; When pending approvals are listed
  ;; Then only implementation-plan / gherkin / qa-procedure appear
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".squad/approvals/pending/plan.approval")
                  "approval_id: plan\ntarget_kind: story\ntarget_id: cave\ngate: implementation-plan\n")
      (write-file (fs/path root ".squad/approvals/pending/theme.approval")
                  "approval_id: theme\ntarget_kind: theme\ntarget_id: wumpus\ngate: theme\n")
      (write-file (fs/path root ".squad/approvals/pending/final.approval")
                  "approval_id: final\ntarget_kind: story\ntarget_id: cave\ngate: final\n")
      (let [pending (web/approval-state-for root "pending")
            gates (set (map #(get % "gate") pending))]
        (is (contains? gates "implementation-plan"))
        (is (not (contains? gates "theme")))
        (is (not (contains? gates "final"))))
      (finally
        (fs/delete-tree root)))))

(deftest work-queue-omits-merger
  (let [rows (web/work-in-flight-rows
              [{"assignment_id" "cave-impl" "story_id" "cave" "template" "implementer"
                "state" "in_progress"}
               {"assignment_id" "merge-1" "story_id" "cave" "template" "merger"
                "state" "in_progress"}]
              [])]
    (is (some #(= "implementer" (get % "role")) rows))
    (is (not (some #(= "merger" (get % "role")) rows)))))

(defn- dashboard-script []
  (or (second (re-find #"(?s)<script>(.*)</script>" html)) ""))

(deftest dashboard-script-is-valid-javascript
  ;; Given the cockpit page script
  ;; Then it parses — a syntax error would kill Add Story, TS Enter, and swimlanes
  (let [script (dashboard-script)
        tmp (fs/create-temp-file {:prefix "squad-dashboard." :suffix ".js"})]
    (try
      (spit (str tmp) script)
      (let [checked (sh/sh "node" "--check" (str tmp))]
        (is (zero? (:exit checked)) (:err checked)))
      (finally
        (fs/delete-if-exists tmp)))))

(deftest add-story-is-wired-at-page-load
  ;; Given the cockpit HTML
  ;; Then Add Story is bound at load, not only after renderBoard
  (is (re-find #"getElementById\('btn-add-item'\)\.onclick" html)))

(deftest board-html-has-four-swimlanes
  ;; Given the cockpit HTML before JS runs
  ;; Then the four board lanes are already in the markup
  (doseq [lane ["Specifying" "Coding" "Finalizing" "Done"]]
    (is (re-find (re-pattern (str "<h3>" lane "</h3>")) html))))

(deftest ts-enter-sends-without-shift
  ;; Given the Troubleshooter composer
  ;; Then Enter sends and Shift+Enter stays a newline
  (is (re-find #"sl-message'\)\.addEventListener\('keydown'" html))
  (is (re-find #"e\.key==='Enter'&&!e\.shiftKey" html))
  (is (str/includes? html "sendTsChat()")))

(deftest story-edit-float-is-large-and-resizable
  ;; Given the backlog/story edit float
  ;; Then it opens larger than a card and the operator can grow it
  (is (str/includes? html "function openFloat({id,title,bodyHtml,onMount,left,top,className"))
  (is (re-find #"(?s)function openBacklogEditor.*?className:\s*'editor'" html))
  (is (re-find #"\.float\.editor\{[^}]*resize:\s*both" html))
  (is (re-find #"\.float\.editor\{[^}]*min-width:\s*min\(720px" html))
  (is (not (str/includes? html "max-width:440px"))
      "story editor must not be capped at 440px")
  (is (re-find #"\.float\.editor textarea\{[^}]*min-height:\s*240px" html)))
