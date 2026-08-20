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

(defn pending-frame-approval? [root]
  (fs/regular-file?
   (fs/path root ".squad" "approvals" "pending" "frame__product.approval")))

(defn frame-view
  "Cockpit frame: none | pending | in_review | on_master."
  [root]
  (let [p (read-product root)
        sha (frame-sha p)]
    (cond
      sha {"state" "on_master" "sha" sha}
      (pending-frame-approval? root) {"state" "in_review"}
      (= "frame_pending" (get p "state")) {"state" "pending"}
      :else {"state" "none"})))

(defn record-frame-sha!
  "Stamp the merged frame onto the product record. Snapshot ids stay."
  [root sha]
  (write-product! root
                  (merge (read-product root)
                         {"state" "framed"
                          "frame_sha" sha
                          "frame_path" "frame.md"
                          "qa_path" "qa/product.md"})))
