(ns swarmforge.issues-b87-b88-b91-test
  "Regression coverage for B87 (Work Queue), B88 (theme→project), B91 (WIF labels)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [squadd.web :as web]
            [swarmforge.test-support :refer :all]))

(deftest b87-dashboard-says-work-queue
  (is (str/includes? web/dashboard-html "Work Queue"))
  (is (not (str/includes? web/dashboard-html "Work in flight"))))

(deftest b88-dashboard-says-project-not-theme
  (is (str/includes? web/dashboard-html ">Projects<"))
  (is (str/includes? web/dashboard-html "project: —"))
  (is (not (str/includes? web/dashboard-html ">Themes<")))
  (is (not (str/includes? web/dashboard-html "theme: —"))))

(deftest b88-theme-package-uses-project-copy
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

(deftest b91-wif-strips-theme-prefix-and-shows-project-story
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
            "B91: theme.md H1 Theme: prefix is stripped"))
      (finally
        (fs/delete-tree root)))))

(deftest b91-wif-project-wide-analysis-shows-project-only
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
