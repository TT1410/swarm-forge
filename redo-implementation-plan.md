# Redo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the theme/merger machine at `b73c972` with a single-story pipeline: backlog → start → analyst plan (user approves) → Gherkin/QA (user approves, no reviewers) → implementer (units + Gherkin) → cleaner (property tests + clean) → code-reviewer (recs) → hardener (apply recs + harden) → QA → architect → SI → story done. Squad Leader merges worker SHAs. No merger, no dry-run, no `merge_blocked`, no theme, no impl-order gates, no sprints, no project.

**Architecture:** Keep packets, `squad_next` residuals vs mechanical apply, squadd spawn/retire, dashboard cockpit, Troubleshooter chat, and `.squad/backlog`. A packet does not need a theme. SL is the handoff target and merges the SHA (six-pack style). Daemon does not merge. Hardener/QA/architect/SI may batch several ready stories. Module map and dependencies are architect + SI ongoing work.

**Tech Stack:** Babashka Clojure (`swarmforge/scripts/*.clj`), `bb test` via `swarmforge.test-runner/run-non-simulation!`, `bb coverage` / `bb crap` (crap4clj `e6e0312`), dashboard `squadd/web.clj` + `squadd/dashboard.html`.

**Specs:** `redo.md`, `redo-ui.md`. Do not contradict them.

**Base:** `squad` at `092d75a` (docs) on top of `b73c972`. Sprint work stays on `sprint-module-squad`. Do not merge it back.

**How to work:** TDD. Given/When/Then comments. Run the production script (`squad_next.sh`, `squad_assign.sh`, web helpers) — no no-op steps. See each new scenario fail before implementing. `bb test` green before every commit. One task at a time.

**Delete with each task.** When a path dies, delete its functions, templates, config lines, and tests in that same commit. Prefer deleting an old `deftest` over inverting it into “does not mention merger.” `squad_next.clj` should get shorter every task.

**No dead functions.** Do not leave `(defn foo [_ _] [])` or a helper that is only called by a deleted path. After the behavior change, grep the old name. If the only remaining refs are the `defn` and tests, delete both in this commit. A function that is never called from live residual/spawn/assign is dead even if tests still construct it.

Examples of stubs that are **not** done: `merger-candidates` that always returns `[]`; `theme-candidates` that always returns `[]`; `daemon-only-main-git-op?` that always returns `false` if nothing should call it.

**CRAP ≤ 6 on leftovers.** After each task, every **remaining** `defn` in the files that task touched has CRAP ≤ 6. Meet it by deleting doomed functions first; cover or split only what stays. Do not write tests whose only job is to paint coverage on merger/theme/order/reviewer code. Do not invent types to dodge a flat `cond`/`case`. This is **not** a whole-repo gate on day one (hundreds of today’s failures are on paths you will delete).

Before each task commit:

```bash
bb test
bb crap
```

`bb crap` runs `bb coverage` then scores `swarmforge/scripts`. Filter to the module you touched (`bb crap squad_next`). The cap still applies to every leftover function in that module. After Task 7, a full `bb crap` is a check that shipped scripts are already at 6 — not a new project.

**Out of scope:** Rewriting `squad_simulator.clj`. B89 project directories. Converting a mid-flight sprint swarm. Keeping B96 story-pair implementer batches.

---

## Files

| File | Responsibility |
|------|----------------|
| `test/swarmforge/redo_next_test.clj` | **New.** FSM scenarios for the redo pipeline. Source of truth for residuals. |
| `test/swarmforge/redo_prompt_test.clj` | **New.** Prompt/constitution contracts. |
| `test/swarmforge/redo_ui_test.clj` | **New.** Dashboard HTML/API: no project rail, Start not classify, stage pills. |
| `test/swarmforge/test_runner.clj` | Register the three new namespaces in `script-test-namespaces`. |
| `test/swarmforge/test_support.clj` | Shared fixtures only if a helper is used by more than one redo test. Prefer locals first. |
| `swarmforge/scripts/squad_next.clj` | Residuals. Drop merger, `merge_blocked`, impl-order gates, B96 implementer pairs, Gherkin/QA reviewers, theme-first map, final bless. Analyst-per-started-story. Keep hardener/QA/architect/SI batches of ready stories. |
| `swarmforge/scripts/squad_assign.clj` | Daemon does not `merge-ready` / `accept-merge`. SL merges the handed SHA. Delete dry-run. |
| `swarmforge/scripts/squad_config.clj` | Default approvals: `implementation-plan`, `gherkin`, `qa-procedure` only. Not theme/order/checker/final. |
| `swarmforge/scripts/squadd/web.clj` | `approve-backlog!` **starts** a story (packet + story file, no theme). No SL classify request. Board columns/pills. Drop project from `/api/state`. |
| `swarmforge/scripts/squadd/dashboard.html` | `redo-ui.md`: no Projects rail, Add Story + Start, Attention View document, Work Queue story·role. |
| `swarmforge/roles/squad-leader.prompt` | You merge the worker SHA (six-pack target). Handle conflicts. No merger. No theme map. Analyst writes a plan. |
| `swarmforge/role-templates/analyst.prompt` + `analyst.contract.edn` | One implementation plan per started story. Architecture and deps **in the plan**. Not a pile of stories. |
| `swarmforge/role-templates/implementer.prompt` | TDD units **and** Gherkin passing (already APS at this SHA — keep). |
| `swarmforge/role-templates/cleaner.prompt` | Property tests and clean. |
| `swarmforge/role-templates/code-reviewer.prompt` | Recommendations only. Recs go to the hardener. |
| `swarmforge/role-templates/hardener.prompt` | Apply CR recs, then harden as in six-pack. |
| `swarmforge/role-templates/{gherkin,qa-procedure}-writer.prompt` | No reviewer; user approves the file. |
| `swarmforge/constitution/articles/local-workflow.prompt` | Target pipeline only. |
| Existing tests listed per task | Invert or delete assertions that encode merger, dry-run, B96, reviewers, theme-first, impl-order gate. |

Do not add sprints. **Delete `squad_theme.sh` and `squad_theme.clj`** in the no-theme task. Packets are per story only: drop `theme_id` from `squad_packet.sh create` and from packet files. Module map is a product file, not a theme record.

**Caps:** Delete the hardcoded `singleton-templates` set in `squad_next.clj`. A role is a singleton when `max_active_template <role> 1` in `swarmforge/squad.conf`. Spawn and residual read only that file (plus `max_transient_agents`). `squad.conf` already lists the redo caps. Drop merger and reviewer cap lines. Tests that assert `next/singleton-templates` contains `analyst` or `merger` invert or delete.

**Backends:** `squad.conf` already has write=Codex (inherit SL), judge=Grok (`code-reviewer`, `architect`). No reviewer or merger `transient_agent` lines. Do not hardcode backends in spawn.

---

## Current pipeline at `b73c972` (leaving)

Theme map → theme approval → analyst writes many stories → story approval → Gherkin writer → **Gherkin reviewer** → Gherkin approve → QA writer → **QA reviewer** → QA approve → **impl-order + B96 pairs** → implementer → **cleaner** → **CR** → hardener → QA → architect (after **all** theme stories QA) → SI → story/theme finalize.

Merger + dry-run own main-git. Residual `--residual-only` defers `accept-merge` to the daemon. That split **goes away**: SL merges.

---

## Target pipeline (one started story)

```text
backlog item (Add)                — residual: wait
  → Start                         — story file + packet; residual: analyst
  → analyst: implementation plan  — user approves gate implementation-plan
  → gherkin-writer                — user approves feature
  → implementer                   — TDD units + Gherkin passing (does not wait for QA procedure)
  → SL merges the SHA
  → qa-procedure-writer           — may run beside Gherkin / implementer; user approves procedure
  → cleaner                       — property tests + clean
  → SL merges the SHA
  → code-reviewer                 — recommendations
  → SL merges the SHA
  → hardener                      — apply recs, then harden (may batch ready stories)
  → SL merges
  → QA → architect recs → senior implementer
  → story complete
```

Gherkin and QA have **no reviewer role**. User approval of the artifact is the gate. No final bless.

**Merge owner (locked):** The worker’s git handoff carries a SHA to SL. SL merges it, as in six-pack. Residual for a result handoff is **SL merge**, not `wait_for_daemon_main_git`, not `check-merge-ready`, not `accept-merge` by the daemon. No `merge_blocked` state. Conflicts are SL’s problem while merging. `ready-actions` never returns `TEMPLATE: merger` or `create-merger`.

**No theme:** `Start` writes `stories/<id>.md` and `.squad/stories/<id>/packet` via packet-create (no theme arg). Delete `squad_theme.sh` / `squad_theme.clj` and `.squad/themes/` usage. Delete `theme-candidates` entirely (not an empty stub). Module map / dependency-checker are not residuals. Architect and SI update those files as they work.

---

## Test conventions

New tests live in `test/swarmforge/redo_next_test.clj` unless a task says otherwise.

```clojure
(ns swarmforge.redo-next-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [swarmforge.test-support :refer :all]))

(defn- write-roles! [root]
  (write-file (fs/path root ".swarmforge/roles.tsv")
              (str "squad-leader\tmaster\t" root
                   "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n")))
```

Register the ns before the first `bb test` that should see it:

```clojure
;; test/swarmforge/test_runner.clj — add to script-test-namespaces
swarmforge.redo-next-test
```

Run one file after it is registered:

```bash
bb test
```

`bb test` runs **all** non-simulation namespaces. After each implementation step, fix any inverted old tests in the **same** commit so green means green.

Do not rewrite `squad_simulator.clj`. Simulator tests are **not** in `bb test`. Leave `simulation-test` failing until someone opts in; do not block slices on it.

---

### Task 1: SL merges the handed SHA; no merger; no dry-run; no merge_blocked

**Files:**
- Create: `test/swarmforge/redo_next_test.clj`
- Modify: `test/swarmforge/test_runner.clj` (register ns)
- Modify: `swarmforge/scripts/squad_next.clj` (`ready-actions`, `merger-candidates`, handoff/merge residual)
- Modify: `swarmforge/scripts/squad_assign.clj` (stop daemon `merge-ready` / dry-run path)
- Modify: `test/swarmforge/squad_next_test.clj` (merger-routing and residual-only-defer tests)
- Modify: `test/swarmforge/issues_b94_b99_b101_test.clj` (B94 depth-2 pause, B99 merger stamps)
- Modify: `swarmforge/scripts/squad_next.clj` — delete hardcoded `singleton-templates`; treat `max_active_template` 1 as singleton

- [ ] **Step 1: Write the failing tests**

```clojure
(deftest worker-handoff-tells-sl-to-merge
  ;; Given an implementer handed a SHA to SL
  ;; When squad_next runs
  ;; Then residual is SL merge of that SHA — not merger, not daemon accept-merge
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "squad-leader\tmaster\t" root
                       "\tswarmforge-squad-leader\tSquad Leader\tcodex\ttask\n"
                       "impl-001\timpl-001\t" root "/.worktrees/impl-001"
                       "\tswarmforge-impl-001\tImplementer 001\tcodex\ttask\n"))
      (write-agent-status! root "impl-001" "handoff_sent")
      (write-file (fs/path root ".squad/assignments/cave-impl/metadata")
                  (str "assignment_id: cave-impl\n"
                       "story_id: cave-graph\n"
                       "template: implementer\n"))
      (write-file (fs/path root ".squad/assignments/cave-impl/status")
                  "assignment_id: cave-impl\nstate: result_received\n")
      (write-file (fs/path root ".swarmforge/handoffs/inbox/in_process/50_from_impl-001.handoff")
                  (str "type: git_handoff\nto: squad-leader\nfrom: impl-001\n"
                       "commit: abcdef1234\nassignment: cave-impl\n"
                       "template: implementer\n\nmerge abcdef1234\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))
            residual (:out (run {:dir root} (script "squad_next.sh") "--residual-only"))]
        (is (not (str/includes? out "TEMPLATE: merger")))
        (is (not (str/includes? out "create-merger")))
        (is (not (str/includes? out "check-merge-ready")))
        (is (not (str/includes? residual "wait_for_daemon_main_git")))
        (is (or (str/includes? out "merge")
                (str/includes? residual "merge"))))
      (finally
        (fs/delete-tree root)))))

(deftest merge-blocked-is-gone
  ;; Given leftover merge_blocked status from the old machine
  ;; When squad_next runs
  ;; Then it does not create a merger and does not treat merge_blocked as a live state
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/assignments/cave-impl/metadata")
                  (str "assignment_id: cave-impl\nstory_id: cave-graph\n"
                       "template: implementer\n"))
      (write-file (fs/path root ".squad/assignments/cave-impl/status")
                  "assignment_id: cave-impl\nstate: merge_blocked\n")
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (not (str/includes? out "TEMPLATE: merger")))
        (is (not (str/includes? out "create-merger"))))
      (finally
        (fs/delete-tree root)))))
```

- [ ] **Step 2: Register the ns. Run `bb test`. Confirm they fail.**

Expected: today’s residual is `create-merger` or `wait_for_daemon_main_git` / `accept_merge` for the daemon.

- [ ] **Step 3: Minimal implementation**

Handoff residual: SL merges the commit on the handoff (six-pack target). `--residual-only` must **not** rewrite that to `wait_for_daemon_main_git`.

Stop calling `dry-run-merge` / daemon `merge-ready`. There is no `merge_blocked` state to enter.

**Delete, do not stub**, every merger / dry-run / daemon-merge helper that nothing live calls. In `squad_next.clj` that includes (names as of this SHA):

- `merger-candidates`, `merger-candidate`, `merger-create-candidate`, `merger-spawn-candidate`, `merger-limit-blocker-candidate`
- `existing-merger-assignment`, `open-merger-assignments`
- `pause-product-accept-merge?`, `pause-product-accept-merge-at-root?`
- `merger-spawn-action?`, `merger-holds-capacity-slot?` (and the copies in `squadd.clj` / `squad_spawn.clj` if only merger uses them)
- `lineage-max-depth-exhausted?` / `merge-lineage-root` / `assignment-in-merge-lineage?` **in next** if only the merger path used them
- `park-paused-product-accept-handoffs!`, `restore-held-accept-handoffs-after-depth-2!`, `open-depth-2-merger-ids` if they exist only for B94 depth-2 pause
- `print-daemon-owned-main-git-wait!` if residual no longer defers merge

Drop merger from `ready-actions`. Singleton list comes from `squad.conf` (`max_active_template` 1), not a hardcoded set that still names `merger`.

In `squad_assign.clj`: delete `dry-run-merge` and the `merge_blocked` dry-run writers if `check-merge-ready!` is no longer a live residual. Leave `create-merger` CLI only if something still invokes it; otherwise delete that command too. Leave `merger.prompt` on disk until Task 6 **only if** no code still dispatches the template.

- [ ] **Step 3b: Grep**

```bash
rg -n "merger-candidates|create-merger|dry-run-merge|wait_for_daemon_main_git|pause-product-accept" swarmforge/scripts test
```

No live callers. Tests that only existed to lock those functions are deleted, not inverted into no-ops.

- [ ] **Step 4: Invert old tests**

Delete or invert every test that requires merger, dry-run, `merge_blocked`, depth-2 pause (B94), merger stamps (B99), or “residual defers accept-merge to daemon.” Those last two (`residual-only-defers-accept-merge-to-daemon`, `residual-only-defers-merge-ready-to-daemon`) **invert**: SL residual is the merge.

- [ ] **Step 5: Run `bb test`. Then `bb crap` (or `bb crap squad_next` / `squad_assign`). Confirm green and leftover functions in touched files have CRAP ≤ 6.**

- [ ] **Step 6: Commit**

```bash
git commit -m "$(cat <<'EOF'
SL merges worker SHAs; drop merger, dry-run, and merge_blocked.
EOF
)"
```

---

### Task 2: Empty swarm waits; one story; no impl-order; no B96

**Files:**
- Modify: `test/swarmforge/redo_next_test.clj`
- Modify: `swarmforge/scripts/squad_next.clj` (`theme-candidates`, `implementation-order-record-candidate`, `implementer-batch-candidates` / `derive-implementer-batches` call sites, `implementation-assignment` in `story-transition-table`)
- Modify: `test/swarmforge/issues_b96_test.clj`
- Modify: `test/swarmforge/squad_next_test.clj` (`squad-next-hard-gates-implementer-on-implementation-order`, `p0-missing-durable-implementation-order-blocks-all-implementers`, `squad-next-reports-highest-priority-workflow-action` theme-map assertion)

- [ ] **Step 1: Write the failing tests**

```clojure
(deftest empty-swarm-waits
  ;; Given a new repo with only SL registered
  ;; When residual runs
  ;; Then wait — not write_theme_module_map
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "NEXT_ACTION: wait"))
        (is (not (str/includes? out "write_theme_module_map")))
        (is (not (str/includes? out "create_approval_request"))))
      (finally
        (fs/delete-tree root)))))

(deftest implementer-is-one-story-without-order-file
  ;; Given two implementer-ready stories and no implementation-order.md
  ;; When residual runs
  ;; Then each story may get its own implementer; no batch of two
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "swarmforge/squad.conf") implementer-gate-conf)
      (doseq [story ["alpha" "beta"]]
        (write-file (fs/path root ".squad/stories" story "packet")
                    (str "story_id: " story "\n"
                         "theme_id: swarm\n"
                         "implementation_plan_approval: approved\n"
                         "gherkin_path: features/" story ".feature\n"
                         "gherkin_approval: approved\n"
                         "qa_procedure_path: qa/" story ".md\n"
                         "qa_procedure_approval: approved\n"))
        (write-file (fs/path root "stories" (str story ".md")) (str "Story " story ".\n")))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "TEMPLATE: implementer"))
        (is (not (str/includes? out "--batch-stories")))
        (is (not (str/includes? out "record_implementation_order"))))
      (finally
        (fs/delete-tree root)))))
```

For this task, isolate **order/batch** only. After later tasks, tighten the packet to redo fields. Do not add `theme_id` to new packets.

- [ ] **Step 2: Run `bb test`. Confirm the new tests fail.**

Expected: `empty-swarm-waits` is already close to true on a bare repo; `squad-next-reports-highest-priority-workflow-action` still expects `write_theme_module_map` after `squad_theme.sh create`. Invert that old test to `wait`. Do **not** add a hidden-theme fixture.

`implementer-is-one-story-without-order-file` fails because `implementation-order-record-candidate` emits `record_implementation_order` or B96 batches two stories.

- [ ] **Step 3: Minimal implementation**

`theme-candidates`: return `[]`. No theme ceremony. (Analyst-per-story is Task 3.)

`implementation-order-record-candidate` / `implementation-order-record-candidates`: return `nil` / `[]`. Stop calling them from `ready-actions` if they are concatenated there (they currently feed implementer readiness, not `ready-actions` directly — grep and cut every call that **blocks** implementers).

`implementer-dependencies-satisfied?`: always true (or delete the gate in `packet-ready-for-implementer?` / implementer-batch path).

`implementer-batch-candidates`: return `[]`. In `:implementation-assignment`, drop `implementer-batch-for-story` / “skip if batch size > 1”. Always one implementer per story id.

- [ ] **Step 4: Invert old tests**

| Test | New expectation |
|------|-----------------|
| `squad-next-reports-highest-priority-workflow-action` (lines that `create` theme then expect `write_theme_module_map`) | `wait`; do not require theme create |
| `squad-next-hard-gates-implementer-on-implementation-order` | both stories may schedule; no provider SHA wait |
| `p0-missing-durable-implementation-order-blocks-all-implementers` | implementer **is** created |
| `missing-order-without-draft-offers-seed-record-not-silent-block` | delete |
| `p0-mechanical-records-root-implementation-order-draft` | delete |
| `b25-*` order/checker user approval | delete or skip; redo has no those gates |
| `b13-hollow-or-missing-checker-is-incomplete-analysis-residual` | delete or invert (analyst no longer must emit checker) |
| `issues_b96_test.clj` entire file | invert: two ready stories are **not** one `--batch-stories` assignment; `derive-implementer-batches` may stay as dead code or be deleted |

- [ ] **Step 5: `bb test` green. `bb crap` on touched modules: leftover functions CRAP ≤ 6.**

- [ ] **Step 6: Commit**

```bash
git commit -m "$(cat <<'EOF'
One story at a time; no theme-first residual, order gate, or B96 pairs.
EOF
)"
```

---

### Task 3: Backlog Start → analyst plan → user approval

**Files:**
- Modify: `test/swarmforge/redo_next_test.clj`
- Modify: `swarmforge/scripts/squadd/web.clj` (`approve-backlog!`)
- Modify: `test/swarmforge/squadd_web_test.clj` (`backlog-crud-and-approve-for-analysis`)
- Modify: `swarmforge/scripts/squad_next.clj` (analyst candidate on started story; plan approval)
- Modify: `swarmforge/scripts/squad_config.clj` (`implementation-plan` / `implementation_plan` default true)
- Modify: `swarmforge/scripts/squad_approval.clj` if gates are a closed set
- Modify: `swarmforge/role-templates/analyst.prompt` and `analyst.contract.edn`

**Start semantics (locked):** Keep `.squad/backlog` Add as today. The existing approve endpoint **starts** the item:

1. Slug a story id from the title (stable, filesystem-safe).
2. Write `stories/<id>.md` from title + body if missing.
3. Create `.squad/stories/<id>/packet` (no theme). If `squad_packet.sh create` still requires a theme argument, change it so a story packet does not.
4. Mark backlog item `status: started` (not `dispatched`).
5. **Do not** create a dashboard request for SL to classify theme vs story. Do not call `squad_theme.sh create`.

Residual after Start: `create_assignment` analyst scoped to **that story id** (not `story_id: theme`).

After analyst merge + plan file on disk (`.squad/stories/<id>/plan.md` **or** `stories/<id>-plan.md` recorded on the packet as `implementation_plan_path`): `create_approval_request` gate `implementation-plan`.

- [ ] **Step 1: Write the failing tests**

```clojure
(deftest backlog-add-does-not-start-analyst
  ;; Given an open backlog item
  ;; When residual runs
  ;; Then wait — no analyst
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (require 'squadd.web)
      ((resolve 'squadd.web/create-backlog!) root {:title "Cave graph" :body "Rooms and tunnels."})
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "NEXT_ACTION: wait"))
        (is (not (str/includes? out "TEMPLATE: analyst"))))
      (finally
        (fs/delete-tree root)))))

(deftest start-backlog-creates-analyst-for-that-story
  ;; Given a backlog item
  ;; When the operator starts it
  ;; Then a story packet exists and residual is create_assignment analyst for that story
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (require 'squadd.web)
      (let [web (find-ns 'squadd.web)
            created ((ns-resolve web 'create-backlog!) root {:title "Cave graph" :body "Rooms and tunnels."})
            id (get-in created [:item "id"])
            started ((ns-resolve web 'approve-backlog!) root id)
            story-id (or (get-in started [:item "story_id"]) "cave-graph")
            out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (fs/regular-file? (fs/path root "stories" (str story-id ".md"))))
        (is (fs/regular-file? (fs/path root ".squad/stories" story-id "packet")))
        (is (str/includes? out "TEMPLATE: analyst"))
        (is (str/includes? out story-id))
        (is (not (str/includes? out "NEW THEME")))
        (is (not (str/includes? (get-in started [:request "body"] "") "classify"))))
      (finally
        (fs/delete-tree root)))))

(deftest analyst-plan-requests-implementation-plan-approval
  ;; Given a started story whose analyst assignment is merged with a plan file
  ;; When residual runs
  ;; Then create_approval_request gate implementation-plan
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root "stories/cave-graph.md") "Rooms and tunnels.\n")
      (write-file (fs/path root ".squad/stories/cave-graph/plan.md")
                  "# Implementation plan\n\n1. Graph.\n")
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str "story_id: cave-graph\n"
                       "theme_id: swarm\n"
                       "implementation_plan_path: .squad/stories/cave-graph/plan.md\n"
                       "implementation_plan_sha: abcdef1234\n"))
      (write-file (fs/path root ".squad/assignments/cave-graph-analysis/metadata")
                  (str "assignment_id: cave-graph-analysis\n"
                       "theme_id: swarm\n"
                       "story_id: cave-graph\n"
                       "template: analyst\n"))
      (write-file (fs/path root ".squad/assignments/cave-graph-analysis/status")
                  "state: merged\n")
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "NEXT_ACTION: create_approval_request"))
        (is (str/includes? out "implementation-plan")))
      (finally
        (fs/delete-tree root)))))
```

If `approve-backlog!` is renamed to `start-backlog!`, update the test and keep the HTTP path `/api/backlog/:id/approve` as an alias for Start so the current dashboard button works until Task 7.

- [ ] **Step 2: Run `bb test`. Confirm fail.**

Expected: Start still writes an SL request whose body contains `NEW THEME` / classify (`backlog-crud-and-approve-for-analysis`). Residual after a story packet without theme approval is Gherkin or theme map, not story-scoped analyst. No `implementation-plan` gate.

- [ ] **Step 3: Minimal implementation**

Rewrite `approve-backlog!` in `squadd/web.clj`: stop `dashreq/create-request`. Write story file and packet. No theme. Set `status` to `started` and `story_id`. Return `{:ok true :item updated}` with no `:request` classify body.

Add a story-transition (or a new candidate ahead of `story-candidates`) :

```clojure
{:id :analyst-plan-assignment
 :priority 60
 :stage-order 5
 :candidate (fn [ctx packet]
              (when (and (not (field-present? packet "implementation_plan_path"))
                         (not (field-present? packet "implementation_plan_sha")))
                (assignment-candidate (:root ctx) (:assignments ctx) (:agents ctx) packet
                                      "analyst" "analysis"
                                      "started story needs an implementation plan" 60 5 nil)))}
```

Put it **first** in `story-transition-table`. Remove or stop using `:story-approval` as the intake gate (the backlog story **is** the story; the user gate is the plan).

Add:

```clojure
{:id :implementation-plan-approval
 :priority 30
 :stage-order 8
 :candidate (fn [ctx packet]
              (when (field-present? packet "implementation_plan_path")
                (approval-candidate (:root ctx) packet "implementation-plan"
                                    "Approve_implementation_plan"
                                    "implementation-plan-ready" 30 8)))}
```

Gherkin writer must wait until `approval-satisfied?` for `implementation-plan` (change the `:gherkin-assignment` `when` from `story` to `implementation-plan`). Same for QA writer.

`squad_config.clj`:

```clojure
"implementation-plan" true
"implementation_plan" true
```

Set `"theme" false`, `"implementation_order" false`, `"dependency_checker" false` as defaults (or leave unused because those residuals are gone).

`analyst.prompt`: replace “turn theme into stories” with: write `.squad/stories/<id>/plan.md` for **this** story. Conscious of architecture and dependencies. Do not invent sibling stories. Do not require `implementation-order.md` or `dependency-checker.edn`.

Mechanical record of the plan: when the analyst assignment merges, attach `implementation_plan_path` / sha the same way other artifacts attach (`attach_story_artifact` or a dedicated record). If today’s attach only knows `stories/` / `features/` / `qa/`, add `plan.md` as an allowed artifact path.

- [ ] **Step 4: Invert `backlog-crud-and-approve-for-analysis`**

Assert: after approve/start, item status is `started`, a `stories/*.md` exists, **no** request body containing `NEW THEME`. Keep B53 body-preservation on the **story file**, not the SL request.

B72 rejected-story-returns-to-backlog stays: reject of `implementation-plan`, Gherkin, or QA procedure reopens the backlog item. No final bless.

- [ ] **Step 5: `bb test` green. `bb crap` on touched modules: leftover functions CRAP ≤ 6.**

- [ ] **Step 6: Commit**

```bash
git commit -m "$(cat <<'EOF'
Start a backlog story to spawn a per-story analyst plan gate.
EOF
)"
```

---

### Task 4: Gherkin and QA — write, user approves, no reviewer

**Files:**
- Modify: `test/swarmforge/redo_next_test.clj`
- Modify: `swarmforge/scripts/squad_next.clj` (`story-transition-table` `:gherkin-review-assignment`, `:qa-procedure-review-assignment`, `:gherkin-approval`, `:qa-procedure-approval`)
- Modify: `swarmforge/role-templates/gherkin-writer.prompt`, `qa-procedure-writer.prompt`
- Modify: `test/swarmforge/squad_next_test.clj` (auto-accept after reviewer, second-reviewer tests)
- Modify: `test/swarmforge/role_contract_test.clj` (reviewer required-tools / dispatch)

- [ ] **Step 1: Write the failing tests**

```clojure
(defn- plan-approved-packet [story]
  (str "story_id: " story "\n"
       "theme_id: swarm\n"
       "implementation_plan_path: .squad/stories/" story "/plan.md\n"
       "implementation_plan_approval: approved\n"))

(deftest gherkin-writer-after-plan-approval
  ;; Given implementation-plan approved
  ;; When residual runs
  ;; Then create_assignment gherkin-writer — not gherkin-reviewer
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (plan-approved-packet "cave-graph"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "TEMPLATE: gherkin-writer"))
        (is (not (str/includes? out "gherkin-reviewer"))))
      (finally
        (fs/delete-tree root)))))

(deftest gherkin-merge-requests-user-approval-not-reviewer
  ;; Given gherkin_path recorded
  ;; When residual runs
  ;; Then create_approval_request gherkin — not gherkin-reviewer
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str (plan-approved-packet "cave-graph")
                       "gherkin_path: features/cave-graph.feature\n"
                       "gherkin_sha: abcdef1234\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "NEXT_ACTION: create_approval_request"))
        (is (str/includes? out "gherkin"))
        (is (not (str/includes? out "TEMPLATE: gherkin-reviewer"))))
      (finally
        (fs/delete-tree root)))))

(deftest qa-writer-then-user-approval-not-reviewer
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str (plan-approved-packet "cave-graph")
                       "gherkin_path: features/cave-graph.feature\n"
                       "gherkin_approval: approved\n"
                       "qa_procedure_path: qa/cave-graph.md\n"
                       "qa_procedure_sha: abcdef1234\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "NEXT_ACTION: create_approval_request"))
        (is (or (str/includes? out "qa-procedure")
                (str/includes? out "qa_procedure")))
        (is (not (str/includes? out "qa-procedure-reviewer"))))
      (finally
        (fs/delete-tree root)))))
```

- [ ] **Step 2: Run. Confirm fail** (today Gherkin path without `gherkin_review: accepted` creates `gherkin-reviewer`; approval waits on review accepted).

- [ ] **Step 3: Minimal implementation**

Remove `:gherkin-review-assignment`, `:qa-procedure-review-assignment`, `:gherkin-revision-assignment`, `:qa-procedure-revision-assignment` from `story-transition-table`.

Change `:gherkin-approval` to fire when `gherkin_path` is present (not `field-accepted?` review):

```clojure
{:id :gherkin-approval
 :candidate (fn [ctx packet]
              (when (field-present? packet "gherkin_path")
                (approval-candidate (:root ctx) packet "gherkin"
                                    "Approve_Gherkin" "gherkin-written" 30 60)))}
```

Same for QA procedure with `qa_procedure_path`.

`:implementation-assignment` requires plan + Gherkin **user** approvals only. It does **not** wait for the QA procedure. Drop `gherkin_review` / `qa_procedure_review` predicates and the extra `:implementation-approval` row.

Writer prompts: one sentence each — there is no reviewer; the operator approves the file on the dashboard.

- [ ] **Step 4: Invert old tests**

| Test | New expectation |
|------|-----------------|
| `squad-next-auto-accepts-revised-gherkin-after-one-review-cycle` | delete |
| `squad-next-does-not-replay-stale-review-after-post-revision-acceptance` | delete |
| `squad-next-auto-accepts-after-revised-artifact-is-attached` | delete |
| `squad-next-does-not-create-second-reviewer-when-review-history-exists` | delete |
| `squad-next-spawns-existing-rereview-before-requesting-another-revision` | delete |
| Any `TEMPLATE: gherkin-reviewer` / `qa-procedure-reviewer` assertion | no such template |
| `role_contract_test` lists of reviewer roles | drop gherkin-reviewer and qa-procedure-reviewer from required dispatch |

- [ ] **Step 5: `bb test` green. `bb crap` on touched modules: leftover functions CRAP ≤ 6.**

- [ ] **Step 6: Commit**

```bash
git commit -m "$(cat <<'EOF'
Gherkin and QA go to the user; drop reviewer roles.
EOF
)"
```

---

### Task 5: Implementer → cleaner → CR → hardener; batches stay; no final bless

**Files:**
- Modify: `test/swarmforge/redo_next_test.clj`
- Modify: `swarmforge/scripts/squad_next.clj` (`:cleaner-assignment` / `:code-review-assignment` order and predicates; `hardener-member-ready?`; `all-theme-stories-qa-complete?` for architect)
- Modify: `swarmforge/role-templates/implementer.prompt` (keep units + Gherkin)
- Modify: `swarmforge/role-templates/code-reviewer.prompt`
- Modify: `swarmforge/role-templates/cleaner.prompt`
- Modify: `test/swarmforge/squad_next_test.clj` (`squad-next-creates-first-code-reviewer-for-cleaned-story` and cleaner-before-CR tests)
- Modify: `test/swarmforge/issues_b97_b98_b100_test.clj` (architect after **all** stories QA)
- Modify: `test/swarmforge/issues_b94_b99_b101_test.clj` (B101: recs go to hardener, not a rework implementer)
- Modify: `test/swarmforge/role_contract_test.clj` (drop merger from singleton required list if still there)
- Optional delete: `swarmforge/role-templates/merger.prompt` only if nothing references it

- [ ] **Step 1: Write the failing tests**

```clojure
(defn- spec-approved-packet [story]
  (str "story_id: " story "\n"
       "theme_id: swarm\n"
       "implementation_plan_approval: approved\n"
       "gherkin_path: features/" story ".feature\n"
       "gherkin_approval: approved\n"
       "qa_procedure_path: qa/" story ".md\n"
       "qa_procedure_approval: approved\n"))

(deftest implementer-after-plan-and-gherkin
  ;; Given plan and Gherkin approved; no QA procedure yet
  ;; When residual runs
  ;; Then implementer
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str "story_id: cave-graph\n"
                       "implementation_plan_approval: approved\n"
                       "gherkin_path: features/cave-graph.feature\n"
                       "gherkin_approval: approved\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "TEMPLATE: implementer"))
        (is (str/includes? out "cave-graph")))
      (finally
        (fs/delete-tree root)))))

(deftest cleaner-after-implementer
  ;; Given implementation_sha, no cleaner
  ;; When residual runs
  ;; Then cleaner — not code-reviewer
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str (spec-approved-packet "cave-graph")
                       "implementation_sha: abcdef1111\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "TEMPLATE: cleaner"))
        (is (not (str/includes? out "TEMPLATE: code-reviewer"))))
      (finally
        (fs/delete-tree root)))))

(deftest code-reviewer-after-cleaner
  ;; Given implementation_sha and cleaner_sha
  ;; When residual runs
  ;; Then code-reviewer
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str (spec-approved-packet "cave-graph")
                       "implementation_sha: abcdef1111\n"
                       "cleaner_sha: abcdef2222\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "TEMPLATE: code-reviewer")))
      (finally
        (fs/delete-tree root)))))

(deftest hardener-after-code-review
  ;; Given CR recorded
  ;; When residual runs
  ;; Then hardener (may be a batch of ready stories)
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str (spec-approved-packet "cave-graph")
                       "implementation_sha: abcdef1111\n"
                       "cleaner_sha: abcdef2222\n"
                       "code_review: accepted\n"
                       "code_review_sha: abcdef3333\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (or (str/includes? out "TEMPLATE: hardener")
                (str/includes? out "hardener"))))
      (finally
        (fs/delete-tree root)))))

(deftest ready-stories-may-share-a-hardener-batch
  ;; Given two stories both CR-complete
  ;; When residual runs
  ;; Then one hardener may cover both (batch stays)
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (doseq [story ["alpha" "beta"]]
        (write-file (fs/path root ".squad/stories" story "packet")
                    (str (spec-approved-packet story)
                         "implementation_sha: a\ncleaner_sha: b\n"
                         "code_review: accepted\ncode_review_sha: c\n")))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (str/includes? out "hardener")))
      (finally
        (fs/delete-tree root)))))

(deftest no-final-bless
  ;; Given architect accepted with no recs (or SI merged)
  ;; When residual runs
  ;; Then story is done — not create_approval_request final
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-roles! root)
      (write-file (fs/path root ".squad/stories/cave-graph/packet")
                  (str (spec-approved-packet "cave-graph")
                       "implementation_sha: a\ncleaner_sha: b\n"
                       "code_review: accepted\nhardener_sha: c\nqa_sha: d\n"
                       "architecture_review: accepted\n"))
      (let [out (:out (run {:dir root} (script "squad_next.sh")))]
        (is (not (str/includes? out "Approve_final")))
        (is (not (str/includes? out "gate final"))))
      (finally
        (fs/delete-tree root)))))
```

- [ ] **Step 2: Run. Confirm fail** where today’s table or B97/B100 disagree.

- [ ] **Step 3: Minimal implementation**

Keep `:cleaner-assignment` after `implementation_sha` (already true today). Keep `:code-review-assignment` after cleaner. Change hardener to require CR recorded (recs in hand), then harden. Recs do **not** spawn a rework implementer.

Keep `batch-candidate` for hardener, QA, architect, SI: several **ready** stories may share one assignment. Drop `all-theme-stories-qa-complete?` — do not wait for stories that are not ready. Batch only the ones that are.

Drop `:final-approval` and `theme-finalize-candidates` (`[]`). Story is done when SI has merged, or architect accepted with no recs.

`cleaner.prompt`: property tests and clean. Not CR recs.

`code-reviewer.prompt`: recommendations only. Recs are for the hardener.

`hardener.prompt`: apply CR recs, then harden as in six-pack.

`implementer.prompt`: keep APS units + Gherkin.

- [ ] **Step 4: Invert old tests**

| Test | New expectation |
|------|-----------------|
| `squad-next-creates-first-code-reviewer-for-cleaned-story` | keep if it already is cleaner then CR |
| `squad-next-routes-code-review-rejection-back-to-implementer` | invert: recs go to hardener, not a new implementer |
| B97 “arch after all QA” | invert: batch the ready stories; do not wait on unready siblings |
| B100 Done after QA | Done only after SI / architect-no-recs. Architect/SI stay Finalizing. |
| `role_contract_test` `analyst-must-author-implementation-order` | invert: plan, not order file |
| merger in `singleton-roles` | remove |

- [ ] **Step 5: `bb test` green. `bb crap` on touched modules: leftover functions CRAP ≤ 6.**

- [ ] **Step 6: Commit**

```bash
git commit -m "$(cat <<'EOF'
Implementer, cleaner, CR, then hardener applies recs. Batches stay. No final bless.
EOF
)"
```

---

### Task 6: Prompts and constitution

**Files:**
- Create: `test/swarmforge/redo_prompt_test.clj`
- Modify: `test/swarmforge/test_runner.clj`
- Modify: `swarmforge/roles/squad-leader.prompt`
- Modify: `swarmforge/constitution/articles/local-workflow.prompt`
- Modify: writer/CR/cleaner/analyst prompts if any Task 3–5 sentence is still missing
- Modify: `swarmforge/roles/troubleshooter.prompt` — may add a story to the backlog when asked; do not route that to SL for theme classification

- [ ] **Step 1: Write the failing tests**

```clojure
(ns swarmforge.redo-prompt-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [swarmforge.test-support :refer :all]))

(deftest sl-merges-and-does-not-run-theme-ceremony
  (let [p (slurp (str (fs/path repo-root "swarmforge/roles/squad-leader.prompt")))]
    (is (re-find #"(?i)merge" p))
    (is (not (str/includes? p "merger")))
    (is (not (str/includes? p "dry-run")))
    (is (not (str/includes? p "module map")))))

(deftest analyst-writes-a-plan-not-story-cuts
  (let [p (slurp (str (fs/path repo-root "swarmforge/role-templates/analyst.prompt")))]
    (is (str/includes? p "implementation plan"))
    (is (not (str/includes? p "implementation-order.md")))
    (is (not (str/includes? p "at most two")))))

(deftest local-workflow-is-the-redo-pipeline
  (let [p (slurp (str (fs/path repo-root "swarmforge/constitution/articles/local-workflow.prompt")))]
    (is (str/includes? p "backlog"))
    (is (str/includes? p "implementation plan"))
    (is (str/includes? p "cleaner"))
    (is (str/includes? p "code-reviewer"))
    (is (str/includes? p "hardener"))
    (is (not (str/includes? p "gherkin-reviewer")))
    (is (not (str/includes? p "dry-run")))))

(deftest troubleshooter-may-add-backlog-stories
  (let [p (slurp (str (fs/path repo-root "swarmforge/roles/troubleshooter.prompt")))]
    (is (re-find #"(?i)backlog|add (a )?story" p))
    (is (not (str/includes? p "classify")))))
```

- [ ] **Step 2: Run. Confirm fail** on constitution / SL still describing theme map and merger.

- [ ] **Step 3: Rewrite those prompts to the target pipeline in `redo.md`.** Short. SL merges the SHA. No merger, no dry-run, no impl-order, no reviewers, no project, no theme. Cleaner: property tests + clean. CR: recs. Hardener: apply recs then harden. Architect + SI own the module map.

- [ ] **Step 4: `bb test` green. `bb crap` on touched modules: leftover functions CRAP ≤ 6.**

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
Rewrite SL, analyst, constitution, and TS prompts for the redo pipeline.
EOF
)"
```

---

### Task 7: Dashboard (`redo-ui.md`)

**Files:**
- Create: `test/swarmforge/redo_ui_test.clj`
- Modify: `test/swarmforge/test_runner.clj`
- Modify: `swarmforge/scripts/squadd/dashboard.html`
- Modify: `swarmforge/scripts/squadd/web.clj` (`web-state`, `board-column-by-state`, Attention payload, Work Queue)
- Modify: `test/swarmforge/issues_p3_test.clj` (B102 backlog button stays)
- Modify: `test/swarmforge/squadd_web_test.clj` (theme package / project copy)

Follow `redo-ui.md` exactly.

- [ ] **Step 1: Write the failing tests**

```clojure
(ns swarmforge.redo-ui-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [swarmforge.test-support :refer :all]))

(def html
  (slurp (str (fs/path repo-root "swarmforge/scripts/squadd/dashboard.html"))))

(deftest cockpit-drops-project-and-sprint
  (is (str/includes? html "id=\"backlog-deck\""))
  (is (not (str/includes? html "id=\"theme-pill\"")))
  (is (not (str/includes? html ">Projects<")))
  (is (not (str/includes? html "sprint"))
      "no sprint chip or planner")
  (is (str/includes? html "Add Story")))

(deftest start-label-not-classify
  (is (re-find #"Start" html))
  (is (not (str/includes? html "SL classifies project vs story"))))

(deftest attention-has-view-document
  (is (str/includes? html "View document")))

(deftest short-stage-pills-exist
  (doseq [pill ["plan" "gherkin" "qa-proc" "implement" "clean" "review"
                "harden" "qa" "architect" "si" "done"]]
    (is (str/includes? html pill))))
```

Also a `web-state` test: backlog open items in `backlog`; started stories in `stories` with column Specifying and pill `plan`; no `current_theme_id` required by the page.

- [ ] **Step 2: Run. Confirm fail.**

- [ ] **Step 3: Implement the cockpit**

- Header: keep title, `next action:` (no `residual:` label), SL thermometer, Open SL, Open TS, Teardown. Drop project pill.
- Attention: only user approvals (implementation-plan, gherkin, qa-procedure). Each row: gate · story id · **View document** · Approve · Reject. No theme map. No reviewer gates. No final bless.
- Board: Specifying / Coding / Finalizing / Done. Unstarted = backlog deck only.
- Pills: `plan`, `gherkin`, `qa-proc`, `implement`, `clean`, `review`, `harden`, `qa`, `architect`, `si`, `done`.
- Toolbar: **Add Story** (name + body; Enter adds; Shift+Enter newline). Deck lists open backlog. Item editor: Add vs **Start**.
- Rail: delete Projects section. Work Queue: story · role. No merger rows.
- Troubleshooter chat unchanged (Enter send, hold scroll).

`board-column-by-state`: plan / gherkin / qa-proc → Specifying; implement / clean / review → Coding; harden / qa / architect / si → Finalizing; done → Done. Architect is **not** Done (overrides B100).

- [ ] **Step 4: `bb test` green. `bb crap` on touched modules: leftover functions CRAP ≤ 6. Verify in the browser if squadd is up; otherwise the HTML/API tests are the stand-in.

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
Cockpit matches redo-ui: backlog Start, story board, no project rail.
EOF
)"
```

---

## Spec coverage

| redo.md | Task |
|---------|------|
| 1–2 SL merges worker SHA; no merger; no dry-run; no merge_blocked | 1 |
| 3 Stories are E2E use cases | 3, 6 (analyst prompt) |
| 4 Module impl order does not matter | 2 |
| 5 No sprints, no project, no theme | 2, 3, 7 |
| 6 Simplify workflow | all |
| 7 Analyst → plan, user approves | 3, 6 |
| 8 Gherkin/QA no review; user approves | 4 |
| 9 Implementer units + Gherkin | 5 |
| 10 Cleaner: property tests + clean | 5, 6 |
| 11 CR recs only | 5, 6 |
| 12 Hardener applies recs then hardens | 5, 6 |
| 13–15 QA, architect recs, SI; module map is arch+SI | 5, 6 |
| 16 Story complete; no final bless | 5, 7 |
| 17 Backlog; Start; reject returns | 3, 7 |
| Hardener/QA/arch/SI batches of ready stories | 5 |

| redo-ui.md | Task |
|------------|------|
| Drop project / sprint / Projects rail | 7 |
| Keep backlog deck, Add Story, WIF, TS | 7 |
| Columns + pills | 7 |
| Attention View document | 7 |
| Add vs Start | 3, 7 |
| Work Queue story · role | 7 |

---

## Suggested first commit

Task 1 Step 1–2 only: `redo_next_test.clj` + runner registration. Watch merger tests fail for the right reason. Then implement Task 1.

Do not start Task 2 until `bb test` is green on Task 1 and leftover functions in Task 1’s files are CRAP ≤ 6.
