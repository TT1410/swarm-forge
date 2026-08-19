(ns swarmforge.deep-arch-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [squad-lease :as lease]
            [squad-records :as rec]
            [squad-transition :as transition]
            [swarmforge.test-support :refer :all]))

(deftest kv-and-headers-body-roundtrip
  (let [root (tmp-dir)
        kv (fs/path root "status")
        msg (fs/path root "note.request")]
    (try
      (rec/write-kv-file! kv {"state" "open" "detail" "hello"} ["state" "detail"])
      (is (= "open" (rec/kv-get kv "state")))
      (is (= "hello" (get (rec/read-kv-file kv) "detail")))
      (rec/write-headers-body-file! msg {"id" "r1" "kind" "command"} "line1\nline2\n")
      (let [{:keys [headers body]} (rec/read-headers-body-file msg)]
        (is (= "r1" (get headers "id")))
        (is (str/includes? body "line1"))
        (is (str/includes? body "line2")))
      (rec/append-event! (fs/path root "events.log") "t1\tevent\tok")
      (is (str/includes? (slurp (str (fs/path root "events.log"))) "event"))
      (rec/write-edn-file! (fs/path root "state.edn") {:a 1 :b [2]})
      (is (= 1 (:a (rec/read-edn-file (fs/path root "state.edn")))))
      (finally
        (fs/delete-tree root)))))

(deftest lease-acquire-release-and-exclusion
  (let [root (tmp-dir)]
    (try
      (fs/create-dirs (fs/path root ".swarmforge" "squad"))
      (let [held (lease/acquire! root "spawn")]
        (is (lease/held? root "spawn"))
        (is (nil? (lease/try-acquire! root "spawn"))
            "second acquire fails while held")
        (lease/release! held)
        (is (not (lease/held? root "spawn")))
        (is (some? (lease/try-acquire! root "spawn"))))
      (finally
        (fs/delete-tree root)))))

(deftest with-lease-always-releases
  (let [root (tmp-dir)]
    (try
      (fs/create-dirs (fs/path root ".swarmforge" "squad"))
      (is (= :ok (lease/with-lease root "main-git" (fn [] :ok))))
      (is (not (lease/held? root "main-git")))
      (finally
        (fs/delete-tree root)))))

(deftest accept-merge-transition-writes-full-after-state
  ;; Given assignment merge_ready artifacts
  ;; When apply-transition! accept-merge
  ;; Then status, accepted-merge, and events all show merged
  (let [root (tmp-dir)
        dir (fs/path root ".squad/assignments/cave-impl")]
    (try
      (write-file (fs/path dir "metadata")
                  "assignment_id: cave-impl\ntheme_id: cave\nstory_id: topology\ntemplate: implementer\n")
      (write-file (fs/path dir "status")
                  "assignment_id: cave-impl\nstate: merge_ready\n")
      (write-file (fs/path dir "merge")
                  "assignment_id: cave-impl\nstate: merge_ready\ncommit: abcdef1234\n")
      (fs/create-dirs (fs/path root ".squad/themes/cave"))
      (let [before (transition/snapshot-assignment root "cave-impl")
            result (transition/apply-transition!
                    root :accept-merge
                    {:assignment-id "cave-impl"
                     :commit "abcdef1234"
                     :merge-commit "fedcba9876"
                     :detail "merged result commit"
                     :now "2026-08-15T12:00:00Z"
                     :theme-id "cave"
                     :story-id "topology"})
            after (:after result)]
        (is (= "merge_ready" (get-in before [:status "state"])))
        (is (= "merged" (get-in after [:status "state"])))
        (is (= "merged" (get-in after [:accepted-merge "state"])))
        (is (= "abcdef1234" (get-in after [:accepted-merge "commit"])))
        (is (= "fedcba9876" (get-in after [:accepted-merge "merge_commit"])))
        (is (str/includes? (slurp (str (fs/path dir "events.log"))) "merged"))
        (is (str/includes? (slurp (str (fs/path root ".squad/themes/cave/events.log")))
                           "assignment_merged")))
      (finally
        (fs/delete-tree root)))))
