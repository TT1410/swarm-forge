#!/usr/bin/env bb

(ns squad-tool-table
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def script-dir
  (or (some-> *file* fs/parent)
      (fs/path (System/getProperty "user.dir") "swarmforge" "scripts")))

(defn table-file [root]
  (let [project-file (fs/path root "swarmforge" "tool-table.edn")
        repo-file (fs/path (fs/parent script-dir) "tool-table.edn")]
    (if (fs/regular-file? project-file)
      project-file
      repo-file)))

(defn table [root]
  (edn/read-string (slurp (str (table-file root)))))

(defn tool-record [root tool-name]
  (get-in (table root) [:tools tool-name]))

(defn role-tool-names [root role kind]
  (get-in (table root) [:roles role kind] []))

(defn tool-spec [root tool-name]
  (when-let [{:keys [source version purpose]} (tool-record root tool-name)]
    {:name tool-name
     :source source
     :version version
     :purpose purpose}))

(defn role-tools [root role kind]
  (vec (keep #(tool-spec root %) (role-tool-names root role kind))))

(defn required-tools [root role]
  (role-tools root role :required-tools))

(defn optional-tools [root role]
  (role-tools root role :optional-tools))

(defn required-evidence [root role]
  (get-in (table root) [:roles role :required-evidence] []))

(defn require-command [{:keys [name source version]}]
  (str "squad_tool.sh require " name " " source " " version))

(defn startup-instructions [tools]
  (when (seq tools)
    (str "## Tool Startup\n\n"
         "- At startup, before artifact work, run each required tool check listed below.\n"
         "- If a required tool is missing and the assignment includes an install command, run `squad_tool.sh ensure` with that exact command.\n"
         "- If a required tool is missing and no install command is authorized, record `blocked` and hand the blocker back to `squad-leader`.\n\n"
         (apply str
                (for [tool tools]
                  (str "- `" (require-command tool) "`\n")))
         "\n")))

(defn evidence-instructions [evidence]
  (when (seq evidence)
    (str "## Required Tool Evidence\n\n"
         "Include these result-handoff headers before the blank line:\n"
         (apply str
                (for [{:keys [header description]} evidence]
                  (str "- `" header ": <artifact-path-or-summary>` - " description "\n")))
         "\n")))

(defn canonical-mismatch [root tool source version]
  (when-let [expected (tool-record root tool)]
    (cond
      (not= (:source expected) source)
      {:field "source" :expected (:source expected) :actual source}

      (not= (:version expected) version)
      {:field "version" :expected (:version expected) :actual version})))

(defn describe-tool [{:keys [name purpose]}]
  (str name (when-not (str/blank? purpose) (str " (" purpose ")"))))
