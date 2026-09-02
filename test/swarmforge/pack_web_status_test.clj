(ns swarmforge.pack-web-status-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [swarmforge.pack-test-support :refer :all]))

(use-fixtures :once once-fixture)

(deftest pack-web-in-process-card-has-heat
  (let [root (tmp-dir)
        roles ["specifier" "coder"]
        _ (setup-pack! root roles)
        _ (create-task root "HTW" "coder")
        _ (create-task root "Grenade" "coder")
        _ (put-in-process! root roles "coder" {:from "specifier" :task "HTW"})
        state (web-state root)
        by-name (into {} (map (juxt :name identity) (:tasks state)))]
    (is (not (contains? (get by-name "HTW") :activity)))
    (is (contains? (:role_heats state) :coder))
    (is (not (contains? (get by-name "Grenade") :activity)))))
(deftest pack-web-thermometer-heat-is-per-agent
  ;; Given two pack roots each with a specifier
  ;; When one pane changes and the other stays put
  ;; Then only the changed agent's thermometer rises
  (let [root-a (tmp-dir)
        root-b (tmp-dir)
        _ (setup-pack! root-a ["specifier"])
        _ (setup-pack! root-b ["specifier"])
        result (pack-web root-a false "--test-heat-isolation" (str root-a) (str root-b))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (pos? (:changed body)))
    (is (zero? (:stable body)))))
(deftest pack-web-thermometer-heat-rises-when-pane-changes
  ;; Given a specifier row
  ;; When --test-heat samples two different pane texts
  ;; Then activity after is greater than activity before
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        result (pack-web root false "--test-heat" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (< (:before body) (:after body)))
    (is (<= 0 (:before body)))
    (is (<= (:after body) 6))))
(deftest pack-web-thermometer-heats-on-codex-working-timer
  ;; Given a Codex specifier pane whose only change is the working timer
  ;; When --test-heat-codex samples both
  ;; Then activity rises
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        result (pack-web root false "--test-heat-codex" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (< (:before body) (:after body)))))
(deftest pack-web-thermometer-ignores-reordered-tail
  ;; Given a pane whose last 20 lines are the same bag in a new order
  ;; When --test-heat-reorder samples both
  ;; Then activity does not rise
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        result (pack-web root false "--test-heat-reorder" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (= (:before body) (:after body)))))
(deftest pack-web-thermometer-uses-last-twenty-line-bag
  ;; Given a 25-line pane whose first five lines then change
  ;; When --test-heat-head samples both
  ;; Then activity stays at the baseline (tail bag unchanged)
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        result (pack-web root false "--test-heat-head" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (= (:before body) (:after body)))))
(deftest pack-web-card-status-is-last-im-sentence
  ;; Given a specifier card and a pane tail with an I'm sentence
  ;; When --test-state
  ;; Then that task's status is that sentence
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")]
    (let [result (pack-web-env root {} "--test-status-pane" (str root)
                               "Working on HTW.\nI'm idle, so I'm running ready_for_next.sh.\nesc to interrupt · 3s\n")
          state (json/parse-string (:out result) true)
          card (first (:tasks state))]
      (is (zero? (:exit result)))
      (is (= "HTW" (:name card)))
      (is (str/includes? (str (:status card)) "I'm idle, so I'm running ready_for_next.sh")))))
(deftest pack-web-card-status-includes-codex-summaries
  (doseq [sentence ["Received task extras from the board."
                    "The HHG rules are now settled."
                    "The operator resolved the throw messages."
                    "Completed extras and queued the coder handoff."
                    "The exact-commit audit found no remaining gaps."
                    "The instruction graph is loaded."
                    "The first draft is complete."
                    "All six final feature files parse successfully."
                    "The audit corrections are committed."
                    "I'm tightening those final cases."]]
    (let [root (tmp-dir)
          _ (setup-pack! root)
          _ (create-task root "HTW" "specifier")
          result (pack-web-env root {} "--test-status-pane" (str root)
                               (str sentence "\nesc to interrupt · 3s\n"))
          card (first (:tasks (json/parse-string (:out result) true)))]
      (is (zero? (:exit result)))
      (is (str/includes? (str (:status card)) sentence)))))
(deftest pack-web-codex-status-keeps-work-bullet-after-edit-dump
  ;; Given a Codex work bullet, then a long Edited dump and Working
  ;; When --test-status-pane
  ;; Then status is still that work bullet
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        dump (apply str (repeat 30 "    12 +| Pit, bat, and Wumpus room resolution\n"))
        result (pack-web-env root {} "--test-status-pane" (str root)
                             (str "• The audit corrections are committed. I'm repeating the audit.\n"
                                  "• Edited tmp/behavior-audit.md (+4 -4)\n"
                                  dump
                                  "• Working (31s • esc to interrupt)\n"))
        card (first (:tasks (json/parse-string (:out result) true)))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status card)) "audit corrections are committed"))
    (is (not (str/includes? (str (:status card)) "Working")))
    (is (not (str/includes? (str (:status card)) "Edited")))))
(deftest pack-web-card-status-ignores-tool-trace-lines
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             (str "I'll commit the spec.\n"
                                  "• Ran 7 commands\n"
                                  "• Edited features/003.feature\n"
                                  "• Added tmp/htw-handoff.txt\n"))
        card (first (:tasks (json/parse-string (:out result) true)))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status card)) "I'll commit the spec"))
    (is (not (str/includes? (str (:status card)) "Ran 7")))))
(deftest pack-web-card-status-includes-continue-sentences
  ;; Given a specifier card and a pane tail whose last matching sentence uses continue
  ;; When --test-status-pane
  ;; Then that task's status is that sentence
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")]
    (let [result (pack-web-env root {} "--test-status-pane" (str root)
                               "Working on HTW.\nI'll continue with the cave map.\nesc to interrupt · 3s\n")
          state (json/parse-string (:out result) true)
          card (first (:tasks state))]
      (is (zero? (:exit result)))
      (is (str/includes? (str (:status card)) "I'll continue with the cave map.")))))
(deftest pack-web-card-status-joins-wrapped-pane-lines
  ;; Given an I'll sentence split across two pane lines
  ;; When --test-status-pane
  ;; Then status is the full sentence with a space at the wrap
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")]
    (let [result (pack-web-env root {} "--test-status-pane" (str root)
                               "I'll continue with the\ncave map for HTW.\n")
          card (first (:tasks (json/parse-string (:out result) true)))]
      (is (zero? (:exit result)))
      (is (str/includes? (str (:status card)) "I'll continue with the cave map for HTW.")))))
(deftest pack-web-card-status-ignores-transcript-and-helper-chrome
  ;; Given an I'll sentence then a collapsed transcript line and helper audit copy
  ;; When --test-status-pane
  ;; Then status is the I'll sentence, not the chrome
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             (str "I'll write the cave stories.\n"
                                  "… +15 lines (ctrl + t to view transcript)\n"
                                  "Fix every finding, commit the corrections, rerun applicable checks, and repeat "
                                  "this audit against the revised candidate before running the handoff command again.\n"))
        card (first (:tasks (json/parse-string (:out result) true)))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status card)) "I'll write the cave stories"))
    (is (not (str/includes? (str (:status card)) "view transcript")))
    (is (not (str/includes? (str (:status card)) "running the handoff command again")))))
(deftest pack-web-codex-status-is-last-work-bullet
  ;; Given a Codex pane of throwaway bullets then a work bullet with no I'll
  ;; When --test-status-pane
  ;; Then status is that work bullet
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             (str "• You have 1 usage limit reset available. Run /usage to use one.\n"
                                  "• Ran 4 commands · ctrl + t to view transcript\n"
                                  "• Edited features/htw.feature\n"
                                  "• Added tmp/htw_handoff.txt\n"
                                  "• Searching the web\n"
                                  "• Searched the web for yob\n"
                                  "• Working (3s • esc to interrupt)\n"
                                  "• The specification now makes every random domain explicit.\n"))
        card (first (:tasks (json/parse-string (:out result) true)))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status card)) "specification now makes every random domain explicit"))
    (is (not (str/includes? (str (:status card)) "Ran 4")))
    (is (not (str/includes? (str (:status card)) "Working")))))
(deftest pack-web-card-status-ignores-handoff-mail-banner
  ;; Given an I'll sentence and a later If idle, run ready_for_next.sh banner
  ;; When --test-status-pane
  ;; Then status is the I'll sentence, not the mail line
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             (str "I'll commit the spec and queue the coder handoff.\n"
                                  "You have new handoff mail. If idle, run ready_for_next.sh.\n"
                                  "esc to interrupt · 1s\n"))
        state (json/parse-string (:out result) true)
        card (first (:tasks state))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status card)) "I'll commit the spec"))
    (is (not (str/includes? (str (:status card)) "ready_for_next")))))
(deftest pack-web-grok-card-status-uses-work-not-chrome
  ;; Given a Grok pane with an I'll sentence under mail and chrome
  ;; When --test-status-pane
  ;; Then status is the I'll sentence
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (set-backend! root "grok")
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             (str "I'll commit the spec and queue the coder handoff.\n"
                                  "You have new handoff mail. If idle, run ready_for_next.sh.\n"
                                  "always-approve  shift+tab\n"
                                  "Waiting for response...\n"
                                  "enter:send  Esc:cancel\n"))
        state (json/parse-string (:out result) true)
        card (first (:tasks state))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status card)) "I'll commit the spec"))
    (is (not (str/includes? (str (:status card)) "ready_for_next")))
    (is (not (str/includes? (str (:status card)) "Waiting for response")))))
(deftest pack-web-waiting-cards-say-waiting-in-queue
  ;; Given two specifier cards and a pane I'm sentence
  ;; When --test-status-pane
  ;; Then both cards say waiting in queue and the work row is not marked as a batch
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        _ (create-task root "Holy Hand Grenade" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             "I'm specifying HTW.\nesc to interrupt · 1s\n")
        state (json/parse-string (:out result) true)
        by-name (into {} (map (juxt :name identity) (:tasks state)))
        specifier-row (some #(when (= "specifier" (:role %)) %)
                            (:work_in_flight state))]
    (is (zero? (:exit result)))
    (is (= "waiting in queue" (:status (get by-name "HTW"))))
    (is (= "waiting in queue" (:status (get by-name "Holy Hand Grenade"))))
    (is (= ["HTW" "Holy Hand Grenade"] (:tasks specifier-row)))
    (is (= [] (:batch_tasks specifier-row)))))
(deftest pack-web-in-process-card-gets-pane-status
  ;; Given two coder cards and in-process mail for Holy Hand Grenade
  ;; When --test-status-pane
  ;; Then Holy Hand Grenade has the pane sentence and HTW says waiting in queue
  (let [root (tmp-dir)
        roles ["specifier" "coder"]
        _ (setup-pack! root roles)
        _ (create-task root "HTW" "coder")
        _ (create-task root "Holy Hand Grenade" "coder")
        _ (put-in-process! root roles "coder"
                           {:from "specifier" :task "Holy Hand Grenade"})
        result (pack-web-env root {} "--test-status-pane" (str root)
                             "I'm merging the grenade.\nesc to interrupt · 1s\n")
        state (json/parse-string (:out result) true)
        by-name (into {} (map (juxt :name identity) (:tasks state)))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status (get by-name "Holy Hand Grenade")))
                       "I'm merging the grenade"))
    (is (= "waiting in queue" (:status (get by-name "HTW"))))))
(deftest pack-web-card-status-matches-unicode-im-and-i-keywords
  ;; Given a pane with Unicode I’m and let me
  ;; When --test-status-pane
  ;; Then those sentences can be card status
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        im (pack-web-env root {} "--test-status-pane" (str root)
                         "I’m merging the QA handoff.\nesc to interrupt · 1s\n")
        let-me (pack-web-env root {} "--test-status-pane" (str root)
                             "Let me inspect the conflicts.\nesc to interrupt · 1s\n")
        handoff (pack-web-env root {} "--test-status-pane" (str root)
                              "HANDOFF queued to cleaner.\nesc to interrupt · 1s\n")]
    (is (str/includes? (:out im) "merging the QA handoff"))
    (is (str/includes? (:out let-me) "Let me inspect the conflicts"))
    (is (str/includes? (:out handoff) "HANDOFF queued to cleaner"))))
(deftest pack-web-card-status-stays-until-replaced
  ;; Given a status sentence then a pane with no status keywords
  ;; When --test-status-persist
  ;; Then the first sentence remains
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-persist" (str root)
                             "I'm working on HTW.\n"
                             "esc to interrupt · 9s\n")
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:first body)) "I'm working on HTW"))
    (is (= (:first body) (:second body)))))
(deftest pack-web-thermometer-heats-on-work-after-handoff-mail
  ;; Given a Codex pane whose only cut-point used to be an old › mail line
  ;; When later transcript lines change
  ;; Then heat rises
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        result (pack-web root false "--test-heat-mail" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (< (:before body) (:after body)))))
(deftest pack-web-grok-thermometer-heats-on-waiting-timer
  ;; Given a Grok pane whose only change is Waiting for response Ns
  ;; When --test-heat-grok
  ;; Then activity rises
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        _ (set-backend! root "grok")
        result (pack-web root false "--test-heat-grok" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (< (:before body) (:after body)))))
(deftest pack-web-thermometer-heats-on-collapsed-transcript-counts
  ;; Given Codex collapsed output whose +N line changes
  ;; When --test-heat-collapse
  ;; Then heat rises
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        result (pack-web root false "--test-heat-collapse" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (< (:before body) (:after body)))))
(deftest pack-web-in-process-card-heat-rises
  (let [root (tmp-dir)
        roles ["specifier"]
        _ (setup-pack! root roles)
        _ (create-task root "HTW" "specifier")
        _ (create-task root "Grenade" "specifier")
        _ (put-in-process! root roles "specifier" {:from "(New Task)" :task "HTW"})
        body (json/parse-string
              (:out (pack-web root false "--test-card-heat" (str root)))
              true)]
    (is (< (:before body) (:after body)))
    (is (false? (get-in body [:other :Grenade])))))
