(ns squad-product
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [squad-records :as rec]))

(defn product-file [root]
  (fs/path root ".squad" "product"))

(defn read-product
  "Missing file → {} — never throw."
  [root]
  (rec/read-kv-file (product-file root)))

(defn write-product! [root m]
  (rec/write-kv-file! (product-file root) m))

(defn frame-sha [p]
  (let [sha (get p "frame_sha")]
    (when-not (str/blank? sha) sha)))

(defn open-item-ids [p]
  (->> (str/split (str (get p "open_item_ids")) #",")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn frame-ready? [p]
  (boolean (frame-sha p)))
