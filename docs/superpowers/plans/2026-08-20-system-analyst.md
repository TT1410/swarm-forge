# System-Analyst and Start the Backlog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Operator Starts the backlog; a `system-analyst` ships one executable frame; residual then Starts every item that was open; later roles extend that frame.

**Architecture:** Product state lives in `.squad/product` (`frame_sha`, snapshot of open item ids). Residual will not create story analysts until `frame_sha` exists. `system-analyst` is a normal transient assignment (WIF yes, board card no). Approval gate `frame` is product-scoped, not a story packet field.

**Tech Stack:** Babashka Clojure, existing `squad_assign` / `squad_next` / `squadd.web` / dashboard HTML, `clojure.test` via `bb -e` requiring the test ns.

**Spec:** `docs/superpowers/specs/2026-08-20-system-analyst-design.md`

Do not build a story DAG. Do not add theme records.

**Tests:** `bb -e '(require (quote clojure.test) (quote swarmforge.system-analyst-test)) (let [c (ref {:test 0 :pass 0 :fail 0 :error 0})] (binding [clojure.test/*report-counters* c] (doseq [v (->> (ns-publics (quote swarmforge.system-analyst-test)) vals (filter #(:test (meta %))))] (clojure.test/test-var v))) (prn @c))'`

Add `'swarmforge.system-analyst-test` to `script-test-namespaces` in `test/swarmforge/test_runner.clj` in Task 1.

---

### Task 1: Product record `.squad/product`

**Files:**
- Create: `swarmforge/scripts/squad_product.clj`
- Create: `test/swarmforge/system_analyst_test.clj`
- Modify: `test/swarmforge/test_runner.clj` (add ns)

- [ ] **Step 1: Write the failing test**

```clojure
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
```

- [ ] **Step 2: Run test to verify it fails**

Expected: unable to resolve `squad-product`.

- [ ] **Step 3: Write minimal implementation**

`squad_product.clj`: kv file at `.squad/product` (same `field: value` style as packets). Helpers: `read-product`, `write-product!`, `frame-sha`, `open-item-ids` (split comma), `frame-ready?` (non-blank `frame_sha`).

- [ ] **Step 4: Run test to verify it passes**

- [ ] **Step 5: Commit**

```bash
git add swarmforge/scripts/squad_product.clj test/swarmforge/system_analyst_test.clj test/swarmforge/test_runner.clj
git commit -m "Add .squad/product record for the executable frame."
```

---

### Task 2: `system-analyst` prompt and contract

**Files:**
- Create: `swarmforge/role-templates/system-analyst.prompt`
- Create: `swarmforge/role-templates/system-analyst.contract.edn`
- Modify: `test/swarmforge/system_analyst_test.clj`
- Modify: `test/swarmforge/role_contract_test.clj` if it enumerates templates

- [ ] **Step 1: Write the failing test**

```clojure
(deftest system-analyst-prompt-owns-the-frame-not-hunt-rules
  (let [p (slurp (str (fs/path repo-root "swarmforge/role-templates/system-analyst.prompt")))]
    (is (str/includes? p ".squad/backlog"))
    (is (str/includes? p "frame.md"))
    (is (str/includes? p "qa/product.md"))
    (is (re-find #"(?i)placeholder" p))
    (is (re-find #"(?i)do not implement" p))
    (is (re-find #"(?i)one executable|one process" p))
    (is (not (re-find #"(?i)write features/" p)))))
```

- [ ] **Step 2: Run test to verify it fails** (file missing)

- [ ] **Step 3: Write prompt + contract**

Contract:

```clojure
{:role "system-analyst"
 :handoff-targets ["squad-leader"]
 :may-web-search true
 :may-fetch-tools false
 :may-spawn false
 :may-talk-to-user false
 :writes ["frame" "qa-procedure" "entrypoint"]
 :artifact-roots ["frame.md" "qa/" "src/"]
 :allowed-root-files ["bb.edn" "deps.edn"]
 :forbidden-artifact-roots ["features/" "stories/" ".squad/stories/"]
 :self-contained-output true
 :requires-dependency-checker false
 :requires-implementation-order false}
```

Prompt must: read every `.squad/backlog/*.item`; one `-main`; named stub sockets; `frame.md`; `qa/product.md` with `<!-- <backlog-id> -->` placeholders; no hunt rules; no `features/`; no-arg `swarm_handoff.sh`.

- [ ] **Step 4: Tests pass**

- [ ] **Step 5: Commit**

```bash
git add swarmforge/role-templates/system-analyst.prompt swarmforge/role-templates/system-analyst.contract.edn test/swarmforge/system_analyst_test.clj
git commit -m "Add system-analyst role prompt and contract."
```

---

### Task 3: Assign `create-product` for `system-analyst`

**Files:**
- Modify: `swarmforge/scripts/squad_assign.clj` (usage, `-main`, `create-assignment!` skip story file)
- Modify: `test/swarmforge/system_analyst_test.clj`

- [ ] **Step 1: Failing test**

```clojure
(deftest create-product-assignment-has-no-story-card
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root
                       "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"))
      (write-file (fs/path root "swarmforge/role-templates/system-analyst.prompt")
                  (slurp (str (fs/path repo-root "swarmforge/role-templates/system-analyst.prompt"))))
      (write-file (fs/path root "swarmforge/role-templates/system-analyst.contract.edn")
                  (slurp (str (fs/path repo-root "swarmforge/role-templates/system-analyst.contract.edn"))))
      (write-file (fs/path root ".squad/backlog/bl-1.item")
                  "id: bl-1\ntitle: Walk\nstatus: open\ncreated_at: t\nupdated_at: t\nbody: |\n  w\n")
      (let [r (run {:dir root} (script "squad_assign.sh")
                   "create-product" "system-analyst" "system-analysis"
                   "--auto-instructions")
            md (slurp (str (fs/path root ".squad/assignments/system-analysis/assignment.md")))
            meta (slurp (str (fs/path root ".squad/assignments/system-analysis/metadata")))]
        (is (zero? (:exit r)))
        (is (str/includes? (:out r) "SQUAD_ASSIGNMENT: system-analysis"))
        (is (str/includes? md "scope: product"))
        (is (not (re-find #"(?m)^story_id:" md)))
        (is (not (re-find #"(?m)^story_id:" meta)))
        (is (str/includes? md "swarm_handoff.sh"))
        (is (str/includes? md "Walk"))
        (is (not (str/includes? md "provided theme"))))
      (finally (fs/delete-tree root)))))
```

- [ ] **Step 2: Fail** (unknown subcommand)

- [ ] **Step 3: Implement**

- Usage line: `squad_assign.sh create-product <template> <assignment-id> [--auto-instructions] [--queue-spawn]`
- `assignment-scope` returns `"product"` when template is `system-analyst`
- `story-file-required?` false for product scope
- `validate-create-ids!` skips story-id when blank and scope is product
- Render: no “write plan.md”; include backlog titles as sockets/non-goals; protocol is no-arg `swarm_handoff.sh`

- [ ] **Step 4: Pass**

- [ ] **Step 5: Commit**

```bash
git commit -m "Add create-product assignments for system-analyst."
```

---

### Task 4: Reject system-analyst handoff missing frame artifacts

**Files:**
- Modify: `swarmforge/scripts/squad_assign.clj` (`validate-result-handoff!`)
- Modify: `test/swarmforge/system_analyst_test.clj`

- [ ] **Step 1: Failing test**

Given assignment template `system-analyst` and a git_handoff whose `artifacts` omit `frame.md` or `qa/product.md`, `squad_assign.sh result` exits 2 with a message naming the missing path.

- [ ] **Step 2: Fail** (currently only requires artifacts non-blank)

- [ ] **Step 3: Implement**

When template is `system-analyst`, split artifacts on comma; require `frame.md` and `qa/product.md` as path entries (not `none`).

- [ ] **Step 4: Pass**

- [ ] **Step 5: Commit**

```bash
git commit -m "Require frame.md and qa/product.md on system-analyst handoff."
```

---

### Task 5: Approval gate `frame`

**Files:**
- Modify: `swarmforge/squad.conf` — `approval_required frame true`
- Modify: `swarmforge/scripts/squad_next.clj` — product-scoped approval candidate
- Modify: `swarmforge/scripts/squad_approval.clj` if target_kind is hardcoded to `story`
- Modify: `test/swarmforge/system_analyst_test.clj`

- [ ] **Step 1: Failing test**

After a merged system-analyst assignment with `frame.md` and `qa/product.md` in the commit listing, residual `NEXT_ACTION` is `request_user_approval` / `GATE: frame` / `TARGET_KIND: product` until approved. After `squad_approval.sh approve frame__product …`, residual is not still asking for `frame`.

- [ ] **Step 2: Fail**

- [ ] **Step 3: Implement**

- `approval-id` for this gate: `frame__product`
- `target_kind` `product`, `target_id` `product`
- Attention document: `frame.md` + `qa/product.md` (reuse story-package HTML pattern or a `package-page` for product)
- Config: `approval_required frame true`

- [ ] **Step 4: Pass**

- [ ] **Step 5: Commit**

```bash
git commit -m "Add product frame approval gate."
```

---

### Task 6: Start the backlog snapshots open ids and does not Start stories

**Files:**
- Modify: `swarmforge/scripts/squadd/web.clj` — `start-backlog-all!`, HTTP `POST /api/backlog/start`
- Modify: `swarmforge/scripts/squadd/dashboard.html` — Start backlog button
- Modify: `test/swarmforge/system_analyst_test.clj`
- Modify: `test/swarmforge/squadd_web_test.clj` if Start-all HTTP is covered there

- [ ] **Step 1: Failing test**

```clojure
(deftest start-backlog-does-not-create-story-files
  ;; Given two open items and no frame_sha
  ;; When POST /api/backlog/start
  ;; Then .squad/product lists both ids, items stay open, no stories/*.md
  ...)
```

Per-card `POST /api/backlog/:id/approve` while no `frame_sha` returns 409 with a message that the frame is required.

- [ ] **Step 2: Fail**

- [ ] **Step 3: Implement**

`start-backlog-all!`:
- Error if no open items
- Error if `frame_sha` already set
- Write product `{state: frame_pending, open_item_ids: <csv of current open ids>}`
- Do **not** call `write-started-story!`

Per-card `start-backlog!`: if `(not (product/frame-ready? (product/read-product root)))` → `{:ok false :status 409 :error "Start the backlog first (frame required)."}`

Dashboard: button `Start backlog` next to Add Story, `POST /api/backlog/start`. Per-card Start hidden unless `state.frame_sha` (or `state.frame.state === 'on master'`).

- [ ] **Step 4: Pass**

- [ ] **Step 5: Commit**

```bash
git commit -m "Start the backlog snapshots open items without creating stories."
```

---

### Task 7: Residual assigns `system-analyst` then Starts snapshotted items after merge

**Files:**
- Modify: `swarmforge/scripts/squad_next.clj`
- Modify: `swarmforge/squad.conf` — `max_active_template system-analyst 1`
- Modify: `test/swarmforge/system_analyst_test.clj`

- [ ] **Step 1: Failing tests**

1. Product `frame_pending`, no assignment: `squad_next.sh` prints `NEXT_ACTION: create_assignment` and `COMMAND: squad_assign.sh create-product system-analyst system-analysis --auto-instructions --queue-spawn`.
2. After assignment merged, `frame` approved, commit contains `frame.md` and `qa/product.md`: residual writes `frame_sha` onto `.squad/product` and Starts each `open_item_ids` (stories exist, items `status: started`). Next actions include analyst `create_assignment` for those stories (capacity permitting).
3. WIF rows include `system-analysis` / template `system-analyst` while in progress (`work-in-flight-rows`).
4. Board story list empty until step 2.

- [ ] **Step 2: Fail**

- [ ] **Step 3: Implement**

Residual order (before story analysts):

1. If product missing/pending and open items (or snapshot ids) and no live system-analyst assignment → create-product.
2. If system-analyst result received → SL accept-merge (existing).
3. If merged and `frame` approval required and not approved → create_approval_request `frame`.
4. If approved and no `frame_sha` → record `frame_sha` from assignment commit; set `frame_path` / `qa_path`.
5. If `frame_sha` and snapshot ids not yet started → call `start-backlog!` for each id in snapshot (now allowed).
6. Existing story pipeline.

Do not spawn Gherkin/implementer/cleaner/CR for the system-analyst assignment. Skip `artifact-assignment-rules` for that template.

- [ ] **Step 4: Pass**

- [ ] **Step 5: Commit**

```bash
git commit -m "Residual runs system-analyst then Starts snapshotted backlog items."
```

---

### Task 8: Per-card Start after frame; late items stay open

**Files:**
- Modify: `swarmforge/scripts/squadd/web.clj` (`start-backlog!` 409 vs ok)
- Modify: `test/swarmforge/system_analyst_test.clj`

- [ ] **Step 1: Tests**

- `frame_sha` set; new open item `bl-new` not in snapshot → still open; residual does not Start it.
- `POST /api/backlog/bl-new/approve` succeeds; only that story file appears.

- [ ] **Step 2: Fail** if residual blindly starts all open items

- [ ] **Step 3: Start-after-frame only for snapshot in residual; per-card Start uses `start-backlog!` for one id**

- [ ] **Step 4: Pass**

- [ ] **Step 5: Commit**

```bash
git commit -m "Allow per-card Start only after the frame; leave late items open."
```

---

### Task 9: Cockpit frame status and WIF

**Files:**
- Modify: `swarmforge/scripts/squadd/web.clj` (`state-response` includes `frame`)
- Modify: `swarmforge/scripts/squadd/dashboard.html`
- Modify: `test/swarmforge/system_analyst_test.clj` / `redo_ui_test.clj`

- [ ] **Step 1: Tests**

JSON state has `"frame": {"state": "none"|"pending"|"in_review"|"on_master", "sha": ...}`. HTML has `id="frame-status"` and `id="btn-start-backlog"`. WIF already covered in Task 7; assert dashboard does not require a story card for system-analyst.

- [ ] **Step 2: Fail**

- [ ] **Step 3: Implement toolbar `Frame: …`; Start backlog enabled only when open items, no sha, no in-flight system-analyst**

- [ ] **Step 4: Pass**

- [ ] **Step 5: Commit**

```bash
git commit -m "Show frame status and Start backlog on the cockpit."
```

---

### Task 10: Later-role prompts extend the frame

**Files:**
- Modify: `swarmforge/role-templates/analyst.prompt`
- Modify: `swarmforge/role-templates/gherkin-writer.prompt`
- Modify: `swarmforge/role-templates/qa-procedure-writer.prompt`
- Modify: `swarmforge/role-templates/implementer.prompt`
- Modify: `swarmforge/role-templates/qa.prompt`
- Modify: `swarmforge/worker-common.prompt`
- Modify: `test/swarmforge/system_analyst_test.clj` and existing prompt tests

- [ ] **Step 1: Failing tests**

Each prompt includes: the frame is already real (`frame.md`); extend the one executable; do not add a second `-main` or probe app. QA-proc writer: edit `qa/product.md` placeholder for this story in the same commit as implementer notes. Analyst: how to run is the frame’s command, not a new stub CLI.

- [ ] **Step 2: Fail**

- [ ] **Step 3: Edit prompts** (do not remove independent-story / non-goals rules; add “extend the frame”)

- [ ] **Step 4: Pass**

- [ ] **Step 5: Commit**

```bash
git commit -m "Orient story roles to extend the product frame."
```

---

### Task 11: Story assignment names the frame

**Files:**
- Modify: `swarmforge/scripts/squad_assign.clj` `render-assignment`
- Modify: `test/swarmforge/system_analyst_test.clj`

- [ ] **Step 1: Test**

With `.squad/product` `frame_sha` and `frame.md` how-to-run line, a later analyst `assignment.md` includes `frame.md`, `qa/product.md`, and “extend”; still no “provided theme.”

- [ ] **Step 2: Fail**

- [ ] **Step 3: If `product/frame-ready?`, insert a **Frame** section (run command from `frame.md` first heading or a `run:` kv in product record). Store `run` on product at record time from `frame.md` if easier than parsing.

- [ ] **Step 4: Pass**

- [ ] **Step 5: Commit**

```bash
git commit -m "Point story assignments at the merged product frame."
```

---

## Spec coverage

| Spec | Task |
|---|---|
| Start backlog, no cards | 6 |
| system-analyst template | 2, 3 |
| Executable + frame.md + qa/product.md | 2, 4 |
| Short path, no story pipeline on frame | 7 |
| frame gate | 5 |
| frame_sha then Start snapshot | 7 |
| Per-card Start after frame | 8 |
| Late items stay open | 8 |
| WIF yes, board card no | 7, 9 |
| Toolbar status | 9 |
| Extend not bolt on | 10, 11 |
| Invalid handoff | 4 |
| Caps one system-analyst | 7 (`max_active_template`) |
| No DAG / no theme | all |

## Placeholders

None. `run:` on the product record in Task 11 is a concrete kv, not TBD.
