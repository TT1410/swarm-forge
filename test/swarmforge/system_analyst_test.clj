(ns swarmforge.system-analyst-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [squad-product :as product]
            [swarmforge.test-support :refer :all]))

(deftest product-record-round-trips-frame-fields
  ;; Given a product map
  ;; When it is written and read
  ;; Then frame_sha, paths, assignment_id, and open_item_ids come back
  (let [root (tmp-dir)]
    (try
      (product/write-product! root {"state" "frame_pending"
                                    "assignment_id" "system-analysis"
                                    "open_item_ids" "bl-1,bl-2"})
      (let [p (product/read-product root)]
        (is (= "frame_pending" (get p "state")))
        (is (= "system-analysis" (get p "assignment_id")))
        (is (= ["bl-1" "bl-2"] (product/open-item-ids p)))
        (is (nil? (product/frame-sha p))))
      (finally (fs/delete-tree root)))))

(deftest missing-product-file-reads-as-empty-map
  ;; Given no .squad/product file
  ;; When it is read
  ;; Then the result is {}
  (let [root (tmp-dir)]
    (try
      (is (= {} (product/read-product root)))
      (finally (fs/delete-tree root)))))

(deftest frame-ready-when-frame-sha-is-non-blank
  ;; Given a product map
  ;; When frame_sha is missing, blank, or set
  ;; Then frame-ready? is true only for a non-blank sha
  (is (false? (product/frame-ready? {})))
  (is (false? (product/frame-ready? {"frame_sha" ""})))
  (is (true? (product/frame-ready? {"frame_sha" "abc1234"}))))

(deftest open-item-ids-trim-and-drop-blanks
  ;; Given open_item_ids with spaces and empty segments
  ;; When parsed
  ;; Then blanks are dropped
  (is (= [] (product/open-item-ids {})))
  (is (= [] (product/open-item-ids {"open_item_ids" ""})))
  (is (= ["bl-1" "bl-2"] (product/open-item-ids {"open_item_ids" " bl-1 , ,bl-2 "}))))
