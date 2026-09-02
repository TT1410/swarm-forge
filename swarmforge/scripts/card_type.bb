(ns card-type
  (:require [clojure.string :as str]))

(def types #{"utility" "component" "QA" "review"})
(def default-type "component")
(def missing-type "QA")

(def start-lane
  {"utility" "coder"
   "component" "specifier"
   "QA" "specifier"
   "review" "cleaner"})

(def chains
  {"utility" ["coder" "cleaner"]
   "component" ["specifier" "coder" "cleaner" "architect" "hardender"]
   "QA" ["specifier" "coder" "cleaner" "architect" "hardender" "QA"]
   "review" ["cleaner" "architect" "hardender" "QA"]})

(defn known? [card-type]
  (contains? types card-type))

(defn normalize [card-type]
  (if (str/blank? card-type)
    missing-type
    card-type))

(defn starting-lane [card-type]
  (get start-lane (normalize card-type)))

(defn chain [card-type]
  (get chains (normalize card-type) (get chains missing-type)))

(defn last-role [card-type]
  (last (chain card-type)))

(defn next-role [card-type sender]
  (let [ch (chain card-type)
        idx (.indexOf ch sender)]
    (when (and (>= idx 0) (< (inc idx) (count ch)))
      (nth ch (inc idx)))))

(defn earlier-roles [card-type sender]
  (let [ch (chain card-type)
        idx (.indexOf ch sender)]
    (if (neg? idx)
      []
      (vec (take idx ch)))))

(defn last-on-card? [card-type sender]
  (= sender (last-role card-type)))

(defn terminal-upstream [pack-roles card-type]
  (let [last (last-role card-type)
        names (vec pack-roles)
        idx (.indexOf names last)]
    (if (neg? idx)
      []
      (vec (take idx names)))))

(defn on-chain? [card-type role]
  (boolean (some #{role} (chain card-type))))

(defn parse-count [value]
  (if (and value (re-matches #"[0-9]+" value))
    (Long/parseLong value)
    0))

(defn parse-row [line]
  (let [[name lane created updated task-id audit-count card-type]
        (str/split (or line "") #"\t" -1)]
    {:name name
     :lane lane
     :created created
     :updated updated
     :id (or (not-empty task-id) name)
     :audit-count (parse-count audit-count)
     :type (normalize card-type)}))

(defn format-row [{:keys [name lane created updated id audit-count type]}]
  (str/join "\t" [name
                  lane
                  created
                  updated
                  id
                  (str (or audit-count 0))
                  (normalize type)]))
