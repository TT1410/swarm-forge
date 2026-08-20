#!/usr/bin/env bb

(ns squad-backlog
  "Add open backlog items from files or composed text. Does not Start."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [squad-config :as cfg]
            [squadd.web :as web]))

(def usage-text
  (str "Usage:\n"
       "  squad_backlog.sh import <dir-or-file>\n"
       "  squad_backlog.sh add --title <title> [--body-file <file>]\n"))

(defn exit! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

(defn project-root []
  (or (cfg/project-root)
      (exit! 1 "Cannot find SwarmForge project root")))

(defn markdown-files [path]
  (let [p (fs/path path)]
    (cond
      (fs/regular-file? p)
      [p]

      (fs/directory? p)
      (->> (fs/list-dir p)
           (filter #(and (fs/regular-file? %)
                         (re-find #"(?i)\.(md|markdown|txt)$" (fs/file-name %))))
           (sort-by str)
           vec)

      :else
      (exit! 1 (str "Not a file or directory: " path)))))

(defn mission-heading-line? [line]
  (boolean (re-find #"(?i)^#\s*mission\s*$" (str line))))

(defn title-body-from-markdown [file]
  (let [text (slurp (str file))
        lines (str/split-lines text)
        mission-heading (first (filter mission-heading-line? lines))
        heading (or mission-heading
                    (first (filter #(re-find #"^#+\s+" %) lines)))]
    (cond
      mission-heading
      {:title "Mission"
       :body (str/trim
              (str/join "\n" (remove #(= % mission-heading) lines)))}

      heading
      {:title (str/trim (str/replace heading #"^#+\s*" ""))
       :body (str/trim
              (str/join "\n" (remove #(= % heading) lines)))}

      :else
      {:title (-> file fs/file-name (str/replace #"\.[^.]+$" "") (str/replace #"[-_]+" " "))
       :body (str/trim text)})))

(defn add-item! [root {:keys [title body]}]
  (let [result (web/create-backlog! root {:title title :body (or body "")})]
    (when-not (:ok result)
      (exit! (or (:status result) 1) (or (:error result) "failed to add backlog item")))
    (let [item (:item result)]
      (println "SQUAD_BACKLOG:" (get item "id"))
      (println "TITLE:" (get item "title"))
      (println "STATUS:" (get item "status"))
      item)))

(defn import-path! [root path]
  (let [files (markdown-files path)]
    (when (empty? files)
      (exit! 1 (str "No markdown files in " path)))
    (doseq [file files]
      (add-item! root (title-body-from-markdown file)))
    (println "IMPORTED:" (count files))))

(defn parse-add-options [args]
  (loop [args args
         opts {}]
    (cond
      (empty? args) opts
      (= "--title" (first args))
      (if-let [v (second args)]
        (recur (nnext args) (assoc opts :title v))
        (exit! 2 "add --title requires a value"))
      (= "--body-file" (first args))
      (if-let [v (second args)]
        (recur (nnext args) (assoc opts :body-file v))
        (exit! 2 "add --body-file requires a value"))
      :else (exit! 2 usage-text))))

(defn add-from-options! [root args]
  (let [{:keys [title body-file]} (parse-add-options args)]
    (when (str/blank? title)
      (exit! 2 "add requires --title"))
    (add-item! root {:title title
                     :body (if (and body-file (fs/regular-file? body-file))
                             (slurp (str body-file))
                             "")})))

(defn -main [& args]
  (let [root (fs/absolutize (project-root))]
    (cond
      (and (= "import" (first args)) (= 2 (count args)))
      (import-path! root (second args))

      (= "add" (first args))
      (add-from-options! root (rest args))

      :else
      (exit! 1 usage-text))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
