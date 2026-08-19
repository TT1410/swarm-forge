(ns squad-transition
  "Explicit FSM transition persistence.

  A transition is a pure description of before → after durable writes.
  apply-transition! is the single persistence entry for that change.

  First vertical: accept-merge success records (status, accepted-merge, events)."
  (:require [babashka.fs :as fs]
            [squad-records :as rec]
            [clojure.string :as str]))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn assignment-dir [root assignment-id]
  (fs/path root ".squad" "assignments" assignment-id))

(defn snapshot-assignment
  "Before-state for tests and transition planning."
  [root assignment-id]
  (let [dir (assignment-dir root assignment-id)]
    {:assignment-id assignment-id
     :status (rec/read-kv-file (fs/path dir "status"))
     :merge (rec/read-kv-file (fs/path dir "merge"))
     :accepted-merge (rec/read-kv-file (fs/path dir "accepted-merge"))
     :metadata (rec/read-kv-file (fs/path dir "metadata"))
     :has-blocker? (fs/regular-file? (fs/path dir "blocker"))}))

(defn- write-kv! [file m key-order]
  (rec/write-kv-file! file m key-order))

(defn apply-writes!
  "Apply a vector of write specs:
   {:type :kv :path p :map m :keys ks}
   {:type :event :path p :line l}
   {:type :delete :path p}"
  [writes]
  (doseq [w writes]
    (case (:type w)
      :kv (write-kv! (:path w) (:map w) (or (:keys w) (sort (keys (:map w)))))
      :event (rec/append-event! (:path w) (:line w))
      :delete (fs/delete-if-exists (:path w))
      :edn (rec/write-edn-file! (:path w) (:value w))
      nil)))

(defn accept-merge-writes
  "Build write list for successful accept-merge on assignment-id.
  Does not run git — only durable record side effects after merge succeeded."
  [root assignment-id {:keys [commit merge-commit detail now theme-id story-id]}]
  (let [dir (assignment-dir root assignment-id)
        now (or now (timestamp))
        detail (or detail "merged")
        writes
        [{:type :kv
          :path (fs/path dir "accepted-merge")
          :keys ["assignment_id" "state" "commit" "merge_commit" "detail" "updated_at"]
          :map {"assignment_id" assignment-id
                "state" "merged"
                "commit" commit
                "merge_commit" merge-commit
                "detail" detail
                "updated_at" now}}
         {:type :kv
          :path (fs/path dir "status")
          :keys ["assignment_id" "state" "detail" "updated_at"]
          :map {"assignment_id" assignment-id
                "state" "merged"
                "detail" detail
                "updated_at" now}}
         {:type :event
          :path (fs/path dir "events.log")
          :line (str now "\tmerged\t" commit "\t" merge-commit "\t" detail)}]]
    (cond-> writes
      theme-id
      (conj {:type :event
             :path (fs/path root ".squad" "themes" theme-id "events.log")
             :line (str now "\tassignment_merged\t" assignment-id "\t" commit "\t"
                        (or story-id "unknown"))}))))

(defn apply-transition!
  "Apply one named transition. Returns {:op :before :after :writes-count}.

  Currently supported:
  :accept-merge — durable records after git merge succeeded
    args: :assignment-id :commit :merge-commit :detail :now
          :theme-id :story-id"
  [root op args]
  (let [op (keyword op)
        assignment-id (:assignment-id args)
        before (when assignment-id (snapshot-assignment root assignment-id))
        writes (case op
                 :accept-merge (accept-merge-writes root assignment-id args)
                 (throw (ex-info (str "Unknown transition op: " op) {:op op})))]
    (apply-writes! writes)
    {:op op
     :before before
     :after (when assignment-id (snapshot-assignment root assignment-id))
     :writes-count (count writes)}))
