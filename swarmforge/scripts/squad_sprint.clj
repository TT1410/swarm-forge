#!/usr/bin/env bb

(ns squad-sprint
  "Durable sprint records: membership, schedule, cancel, tasks, complete."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [squad-config :as cfg]))

(def usage-text
  (str "Usage:\n"
       "  squad_sprint.sh create <id> <name>\n"
       "  squad_sprint.sh move <story-id> <sprint-id|backlog>\n"
       "  squad_sprint.sh schedule <id>\n"
       "  squad_sprint.sh cancel <id>\n"
       "  squad_sprint.sh stories <sprint-id|backlog>\n"
       "  squad_sprint.sh task <sprint-id> <module> <story,story>\n"
       "  squad_sprint.sh interfaces <sprint-id> <file>\n"
       "  squad_sprint.sh complete <id> <tag> <sha>\n"
       "  squad_sprint.sh list\n"
       "  squad_sprint.sh status [id]"))

(def valid-id #"[A-Za-z0-9][A-Za-z0-9._-]*")

(defn exit! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn project-root []
  (or (cfg/project-root)
      (exit! 1 "Cannot find SwarmForge project root")))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn validate-id! [kind value]
  (when-not (re-matches valid-id value)
    (exit! 2 (str kind " must use letters, digits, dots, underscores, and hyphens."))))

(defn write-atomic! [file content]
  (fs/create-dirs (fs/parent file))
  (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                  :prefix (str "." (fs/file-name file) ".")})]
    (spit (str tmp) content)
    (fs/move tmp file {:replace-existing true})))

(defn sprint-dir [root id]
  (fs/path root ".squad" "sprints" id))

(defn sprint-file [root id]
  (fs/path (sprint-dir root id) "sprint"))

(defn stories-file [root id]
  (fs/path (sprint-dir root id) "stories"))

(defn read-field [file field]
  (when (fs/regular-file? file)
    (let [prefix (str field ": ")]
      (some (fn [line]
              (when (str/starts-with? line prefix)
                (subs line (count prefix))))
            (str/split-lines (slurp (str file)))))))

(defn sprint-ids [root]
  (let [dir (fs/path root ".squad" "sprints")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (map fs/file-name)
           sort
           vec)
      [])))

(defn read-members [root id]
  (let [f (stories-file root id)]
    (if (fs/regular-file? f)
      (->> (str/split-lines (slurp (str f)))
           (remove str/blank?)
           vec)
      [])))

(defn write-members! [root id ids]
  (write-atomic! (stories-file root id) (str (str/join "\n" ids) (when (seq ids) "\n"))))

(defn write-sprint! [root {:keys [id name kind state phase tag branch]}]
  (write-atomic! (sprint-file root id)
                 (str "sprint_id: " id "\n"
                      "name: " name "\n"
                      "kind: " kind "\n"
                      "state: " state "\n"
                      "phase: " (or phase "") "\n"
                      "tag: " (or tag "") "\n"
                      "branch: " (or branch "") "\n"
                      "updated_at: " (timestamp) "\n")))

(defn load-sprint [root id]
  (let [f (sprint-file root id)]
    (when (fs/regular-file? f)
      {:id id
       :name (read-field f "name")
       :kind (read-field f "kind")
       :state (read-field f "state")
       :phase (read-field f "phase")
       :tag (read-field f "tag")
       :branch (read-field f "branch")})))

(defn require-sprint! [root id]
  (or (load-sprint root id)
      (exit! 1 (str "Unknown sprint: " id))))

(defn scheduled-id [root]
  (first (filter #(= "scheduled" (:state (load-sprint root %)))
                 (sprint-ids root))))

(defn locked? [sp]
  (= "scheduled" (:state sp)))

(defn registered-story-ids [root]
  (let [themes (fs/path root ".squad" "themes")]
    (if (fs/directory? themes)
      (->> (fs/list-dir themes)
           (filter fs/directory?)
           (mapcat (fn [theme]
                     (let [stories (fs/path theme "stories")]
                       (when (fs/directory? stories)
                         (->> (fs/list-dir stories)
                              (filter fs/regular-file?)
                              (map fs/file-name)
                              (filter #(str/ends-with? % ".ref"))
                              (map #(str/replace % #"\.ref$" "")))))))
           sort
           vec)
      [])))

(defn assigned-story-ids [root]
  (->> (sprint-ids root)
       (mapcat #(read-members root %))
       set))

(defn write-project! [root theme-id]
  (write-atomic! (fs/path root ".squad" "project")
                 (str "id: " theme-id "\n"
                      "name: " theme-id "\n"
                      "theme_id: " theme-id "\n")))

(defn ensure-sprint-0! [root]
  (when-not (load-sprint root "s0")
    (write-sprint! root {:id "s0" :name "Sprint 0" :kind "sprint-0" :state "draft"})
    (write-members! root "s0" [])))

(defn create-sprint! [id name]
  (validate-id! "Sprint id" id)
  (let [root (project-root)]
    (when (load-sprint root id)
      (exit! 2 (str "Sprint already exists: " id)))
    (write-sprint! root {:id id :name (or name id) :kind "impl" :state "draft"})
    (write-members! root id [])
    (println "SPRINT:" id)
    (println "STATE: draft")
    (println "KIND: impl")))

(defn remove-from-all! [root story-id]
  (doseq [sid (sprint-ids root)]
    (let [members (read-members root sid)]
      (when (some #{story-id} members)
        (when (locked? (load-sprint root sid))
          (exit! 2 (str "Story is locked in scheduled sprint " sid)))
        (write-members! root sid (vec (remove #{story-id} members)))))))

(defn move-story! [story-id dest]
  (validate-id! "Story id" story-id)
  (let [root (project-root)]
    (if (= "backlog" dest)
      (do (remove-from-all! root story-id)
          (println "STORY:" story-id)
          (println "SPRINT: backlog"))
      (do (validate-id! "Sprint id" dest)
          (let [sp (require-sprint! root dest)]
            (when (locked? sp)
              (exit! 2 "Cannot add a story to a locked sprint"))
            (remove-from-all! root story-id)
            (write-members! root dest (conj (read-members root dest) story-id))
            (println "STORY:" story-id)
            (println "SPRINT:" dest))))))

(defn schedule! [id]
  (validate-id! "Sprint id" id)
  (let [root (project-root)
        sp (require-sprint! root id)]
    (when (scheduled-id root)
      (exit! 2 "A sprint is already scheduled"))
    (when-not (contains? #{"draft" "abandoned"} (:state sp))
      (exit! 2 "Only a draft or abandoned sprint can be scheduled"))
    (write-sprint! root (assoc sp :state "scheduled" :phase "scheduled"))
    (println "SPRINT:" id)
    (println "STATE: scheduled")))

(defn git [root & args]
  (apply process/sh (concat [{:dir (str root) :continue true}] args)))

(defn cancel! [id]
  (validate-id! "Sprint id" id)
  (let [root (project-root)
        sp (require-sprint! root id)]
    (when (not= "scheduled" (:state sp))
      (exit! 2 "Only a scheduled sprint can be cancelled"))
    (let [branch (str "abandoned/" id)
          tag (str "abandoned-" id)
          sha (str/trim (:out (git root "git" "rev-parse" "--short=10" "HEAD")))]
      (git root "git" "branch" "-f" branch)
      (write-sprint! root (assoc sp :state "abandoned" :phase "" :tag tag :branch branch))
      (let [log (fs/path root ".squad" "sprints" "abandoned.tsv")]
        (fs/create-dirs (fs/parent log))
        (spit (str log) (str id "\t" tag "\t" branch "\t" sha "\n") :append true))
      (println "SPRINT:" id)
      (println "STATE: abandoned")
      (println "BRANCH:" branch)
      (println "TAG:" tag))))

(defn print-stories! [target]
  (let [root (project-root)
        ids (if (= "backlog" target)
              (let [taken (assigned-story-ids root)]
                (filterv #(not (contains? taken %)) (registered-story-ids root)))
              (do (require-sprint! root target)
                  (read-members root target)))]
    (if (seq ids)
      (doseq [id ids] (println "STORY:" id))
      (println "STORY:"))))

(defn record-task! [sprint-id module stories]
  (validate-id! "Sprint id" sprint-id)
  (validate-id! "Module" module)
  (let [root (project-root)]
    (require-sprint! root sprint-id)
    (write-atomic! (fs/path (sprint-dir root sprint-id) "tasks" module)
                   (str "module: " module "\n"
                        "stories: " stories "\n"
                        "stage: queued\n"))
    (println "SPRINT:" sprint-id)
    (println "TASK:" module)
    (println "STORIES:" stories)))

(defn record-interfaces! [sprint-id file]
  (validate-id! "Sprint id" sprint-id)
  (let [root (project-root)
        src (fs/path file)
        src (if (fs/absolute? src) src (fs/path (fs/cwd) src))]
    (require-sprint! root sprint-id)
    (when-not (fs/regular-file? src)
      (exit! 1 (str "Interfaces file not found: " src)))
    (write-atomic! (fs/path (sprint-dir root sprint-id) "interfaces.md")
                   (slurp (str src)))
    (println "SPRINT:" sprint-id)
    (println "INTERFACES:" (str (fs/path (sprint-dir root sprint-id) "interfaces.md")))))

(defn complete! [id tag sha]
  (validate-id! "Sprint id" id)
  (let [root (project-root)
        sp (require-sprint! root id)]
    (when (= "done" (:state sp))
      (exit! 2 (str "Sprint already complete: " id)))
    (write-sprint! root (assoc sp :state "done" :phase "" :tag tag))
    (let [log (fs/path root ".squad" "sprints" "completed.tsv")]
      (fs/create-dirs (fs/parent log))
      (spit (str log) (str id "\t" tag "\t" sha "\n") :append true))
    (println "SPRINT:" id)
    (println "STATE: done")
    (println "TAG:" tag)
    (println "SHA:" sha)))

(defn print-one! [sp]
  (println "SPRINT:" (:id sp))
  (println "NAME:" (:name sp))
  (println "KIND:" (:kind sp))
  (println "STATE:" (:state sp)))

(defn list-open! []
  (let [root (project-root)
        open (filter #(not= "done" (:state (load-sprint root %)))
                     (sprint-ids root))]
    (doseq [id open]
      (print-one! (load-sprint root id)))))

(defn status! [id]
  (let [root (project-root)]
    (if id
      (print-one! (require-sprint! root id))
      (list-open!))))

(defn exact! [args n]
  (when-not (= n (count args))
    (exit! 1 usage-text)))

(defn min! [args n]
  (when-not (>= (count args) n)
    (exit! 1 usage-text)))

(def commands
  {"create" (fn [args] (exact! args 3) (create-sprint! (second args) (nth args 2)))
   "move" (fn [args] (exact! args 3) (move-story! (second args) (nth args 2)))
   "schedule" (fn [args] (exact! args 2) (schedule! (second args)))
   "cancel" (fn [args] (exact! args 2) (cancel! (second args)))
   "stories" (fn [args] (exact! args 2) (print-stories! (second args)))
   "task" (fn [args] (exact! args 4) (record-task! (second args) (nth args 2) (nth args 3)))
   "interfaces" (fn [args] (exact! args 3) (record-interfaces! (second args) (nth args 2)))
   "complete" (fn [args] (exact! args 4) (complete! (second args) (nth args 2) (nth args 3)))
   "list" (fn [args] (exact! args 1) (list-open!))
   "status" (fn [args]
              (when-not (#{1 2} (count args))
                (exit! 1 usage-text))
              (status! (second args)))})

(defn -main [& args]
  (if-let [command (commands (first args))]
    (command args)
    (exit! 1 usage-text)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
