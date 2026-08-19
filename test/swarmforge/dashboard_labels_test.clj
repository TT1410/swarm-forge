(ns swarmforge.dashboard-labels-test
  "Regression coverage for  (Work Queue),  (theme→project),  (WIF labels)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [squadd.web :as web]
            [swarmforge.test-support :refer :all]))

(deftest dashboard-says-work-queue
  (is (str/includes? web/dashboard-html "Work Queue"))
  (is (not (str/includes? web/dashboard-html "Work in flight"))))

(deftest dashboard-drops-project-rail
  (is (not (str/includes? web/dashboard-html ">Projects<")))
  (is (not (str/includes? web/dashboard-html "id=\"theme-pill\"")))
  (is (not (str/includes? web/dashboard-html ">Themes<"))))

(deftest theme-package-uses-project-copy
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".squad/themes/htw/theme.md") "# Theme: HTW\n")
      (let [parts (web/theme-package-parts root "htw")
            titles (set (map :title parts))
            page (web/theme-package-page "htw" parts)]
        (is (contains? titles "Project Lifecycle"))
        (is (not (contains? titles "Theme Lifecycle")))
        (is (str/includes? page "Project package"))
        (is (not (str/includes? page "Theme package"))))
      (finally
        (fs/delete-tree root)))))

(deftest wif-strips-theme-prefix-and-shows-project-story
  ;; Given a mid-project analyst whose metadata still says story_id: theme
  ;; When WIF builds the story label
  ;; Then the label is project:story and never starts with Theme:
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".squad/themes/htw/theme.md")
                  "# Theme: HTW\n\nHunt the Wumpus.\n")
      (let [a {"assignment_id" "analyst-htw-holy-hand-grenade"
               "story_id" "theme"
               "scope" "theme"
               "theme_id" "htw"
               "template" "analyst"
               "state" "in_progress"}
            rows (web/work-in-flight-rows root [a] [])
            label (get (first rows) "story")]
        (is (str/includes? (str/lower-case label) "htw"))
        (is (str/includes? (str/lower-case label) "holy-hand-grenade"))
        (is (not (str/starts-with? label "Theme:"))
            "Theme.md H1 Theme: prefix is stripped"))
      (finally
        (fs/delete-tree root)))))

(deftest wif-project-wide-analysis-shows-project-only
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".squad/themes/htw/theme.md")
                  "# Theme: HTW\n")
      (let [a {"assignment_id" "htw-analysis"
               "story_id" "theme"
               "scope" "theme"
               "theme_id" "htw"
               "template" "analyst"
               "state" "in_progress"}
            label (get (first (web/work-in-flight-rows root [a] [])) "story")]
        (is (= "HTW" label)))
      (finally
        (fs/delete-tree root)))))
