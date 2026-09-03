(ns card-type
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn routes-file [root]
  (fs/path root ".swarmforge" "routes.tsv"))

(defn route-rows [root]
  (let [file (routes-file root)]
    (if (fs/regular-file? file)
      (->> (str/split-lines (slurp (str file)))
           (remove str/blank?)
           (keep (fn [line]
                   (let [[card-type roles] (str/split line #"\t" 2)
                         chain (vec (remove str/blank? (str/split (or roles "") #",")))]
                     (when (and (not (str/blank? card-type)) (seq chain))
                       [card-type chain]))))
           vec)
      [])))

(defn routes [root]
  (into {} (route-rows root)))

(defn card-types [root]
  (mapv first (route-rows root)))

(defn default-type [root]
  (first (card-types root)))

(defn known? [root card-type]
  (contains? (routes root) card-type))

(defn normalize [root card-type]
  (if (str/blank? card-type)
    (default-type root)
    card-type))

(defn starting-lane [root card-type]
  (first (get (routes root) (normalize root card-type))))

(defn chain [root card-type]
  (get (routes root) (normalize root card-type)))

(defn last-role [root card-type]
  (last (chain root card-type)))

(defn next-role [root card-type sender]
  (let [ch (chain root card-type)
        idx (.indexOf ch sender)]
    (when (and (>= idx 0) (< (inc idx) (count ch)))
      (nth ch (inc idx)))))

(defn earlier-roles [root card-type sender]
  (let [ch (chain root card-type)
        idx (.indexOf ch sender)]
    (if (neg? idx)
      []
      (vec (take idx ch)))))

(defn last-on-card? [root card-type sender]
  (= sender (last-role root card-type)))

(defn terminal-upstream [root pack-roles card-type]
  (let [last (last-role root card-type)
        names (vec pack-roles)
        idx (.indexOf names last)]
    (if (neg? idx)
      []
      (vec (take idx names)))))

(defn on-chain? [root card-type role]
  (boolean (some #{role} (chain root card-type))))

(defn parse-count [value]
  (if (and value (re-matches #"[0-9]+" value))
    (Long/parseLong value)
    0))

(defn parse-row [root line]
  (let [[name lane created updated task-id audit-count card-type]
        (str/split (or line "") #"\t" -1)]
    {:name name
     :lane lane
     :created created
     :updated updated
     :id (or (not-empty task-id) name)
     :audit-count (parse-count audit-count)
     :type (normalize root card-type)}))

(defn format-row [{:keys [name lane created updated id audit-count type]}]
  (str/join "\t" [name
                  lane
                  created
                  updated
                  id
                  (str (or audit-count 0))
                  (or type "")]))
