# Sprint-form implementation plan

**Spec:** `sprints.md`  
**UI target:** `sprint-mockup.html` (`bb swarmforge/scripts/sprint_mockup.clj`)  
**Oracle:** `swarmforge/scripts/squad_sprint_sim.clj` and `test/swarmforge/sprint_sim_test.clj`

**Goal:** Replace the story-shaped squad machine with the sprint form: one project, named sprints, Sprint 0 for maps, implementation sprints as module tasks plus a Gherkin/QA track, sprint-level finalize and git tag.

**Architecture:** Keep implementer → cleaner → CR, Gherkin/QA write/review/approve, hardener/QA/architect/SI, squadd as main-git owner, and residual vs mechanical. Change the **unit of work**. Give the sprint its own durable records and FSM. Stories become backlog items, then sprint members, then test-spec children. Implementation walks **tasks** (one module, many stories), not story packets. Internally keep `theme_id` until Slice 4; operator language is already “project.”

**Out of scope:** B89 project directories. Rewriting `squad_simulator.clj`. Converting a mid-flight HTW swarm. Story-pair implementer batches (B96) — the task *is* the batch.

**Migration:** New swarms only. A live swarm in specifying/coding is not a sprint.

**How to work:** TDD. Given/When/Then in `test/swarmforge/`. See each scenario fail. `bb test` green before every commit. One slice at a time. Do not start Slice N+1 until Slice N is playable.

---

## Files

| File | Role |
|------|------|
| `sprints.md` | Product spec. Do not contradict it. |
| `sprint-mockup.html`, `squad_sprint_sim.clj` | Behavior oracle and UI target. |
| `swarmforge/scripts/squad_sprint.clj` + `squad_sprint.sh` | **New** durable sprint tool (Slice 1). |
| `swarmforge/scripts/squad_theme.clj` | Create project → also create Sprint 0. |
| `swarmforge/scripts/squad_assign.clj` | Task-scoped assignments (`--task`, module id). |
| `swarmforge/scripts/squad_packet.clj` | Story packets stay for Gherkin/QA only. |
| `swarmforge/scripts/squad_next.clj` | Sprint FSM (Slice 2). Do not extend `story-transition-table` to pretend a story is a sprint. |
| `swarmforge/scripts/squadd/web.clj`, `dashboard.html` | Slice 4: lift the mockup. |
| `swarmforge/roles/squad-leader.prompt` | Sprint 0 maps, sprint spec, plan presentation, task dispatch. |
| `swarmforge/role-templates/analyst.prompt` | Elaborate stories; emit tasks + interfaces. |
| `swarmforge/role-templates/implementer.prompt` | Task TDD; no Gherkin. |
| `swarmforge/role-templates/hardener.prompt` | First job: Gherkin via APA. |
| `test/swarmforge/sprint_control_test.clj` | **New** Slice 1 tests. |
| `test/swarmforge/sprint_next_test.clj` | **New** Slice 2 tests. |
| `test/swarmforge/sprint_prompt_test.clj` | **New** Slice 3 string/contract tests. |
| `test/swarmforge/sprint_ui_test.clj` | **New** Slice 4 dashboard HTML/API tests. |

Durable layout (Slice 1):

```text
.squad/project                    # id, name — alias of current theme until rename
.squad/sprints/<id>/sprint        # name, kind (sprint-0|impl), state, phase, tag, branch
.squad/sprints/<id>/stories       # story ids in this sprint
.squad/sprints/<id>/tasks/<mod>   # module, story ids, stage, assignment
.squad/sprints/<id>/interfaces.md
.squad/sprints/<id>/spec.md
.squad/sprints/completed.tsv      # id, tag, sha
.squad/sprints/abandoned.tsv      # id, tag, branch, sha
```

Story files stay under `stories/`. Backlog items that are stories keep `.squad/backlog` until moved into a sprint. Control plane stays under `.squad/`.

Sprint states: `draft` | `scheduled` | `abandoned` | `done`.  
Abandoned is in good standing: stories stay; Schedule again with no ceremony.

---

## Slice 1 — Data model and tools

Playable without the live dashboard: `squad_sprint.sh` plus the existing mockup server later pointed at real files (optional). No `squad_next` changes except what compile requires.

### 1.1 Project create makes Sprint 0

**Given** no project  
**When** `squad_theme.sh create htw theme.md` (or the project-create path)  
**Then** Sprint 0 exists as `draft`, kind `sprint-0`, implements no stories.

- Test: `test/swarmforge/sprint_control_test.clj`
- Implement: `squad_theme.clj` create; new `squad_sprint.clj` `ensure-sprint-0`
- Do not create a second Sprint 0 if one exists

### 1.2 Stories: name + body; backlog is unscheduled

**Given** a project  
**When** a story is added with name and description  
**Then** it is in the backlog (no sprint id) and both fields persist.

- Reuse backlog CRUD. Drop any requirement that add implies approve-for-analysis.
- `squad_sprint.sh stories <sprint-id>` lists members; unassigned = backlog.

### 1.3 Named sprints, membership, one scheduled

**Given** backlog stories  
**When** operator creates sprint `cave`, moves `move` and `shoot` into it  
**Then** those stories are only in `cave`; others remain backlog.

**Given** Sprint 0 is `scheduled`  
**When** operator schedules `cave`  
**Then** the command fails.

**Given** `cave` is `draft` or `abandoned` and nothing is scheduled  
**When** operator schedules `cave`  
**Then** `cave` is `scheduled` and locked (no membership edits).

Commands (sketch):

```text
squad_sprint.sh create <id> <name>
squad_sprint.sh move <story-id> <sprint-id|backlog>
squad_sprint.sh schedule <id>
squad_sprint.sh cancel <id>
squad_sprint.sh status [id]
squad_sprint.sh list
```

`create` is always an implementation sprint. Sprint 0 is only created with the project.

### 1.4 Cancel abandons the run, not the sprint

**Given** `cave` is scheduled with stories  
**When** `cancel`  
**Then** state is `abandoned`; stories still belong to `cave`; a branch + tag are recorded in `abandoned.tsv`; `schedule cave` works again with no move/reopen.

### 1.5 Tasks and interfaces are durable after the plan exists

**Given** a sprint plan (stories, tasks, interfaces)  
**When** it is recorded  
**Then** `.squad/sprints/<id>/tasks/<module>` exists per module, each listing story ids; `interfaces.md` is stored; implementation order is the module order already on the project (updated if the plan says so).

Do not invent implementer batches of stories. One task = one module = one implementer assignment later.

### 1.6 Completion registry

**Given** a sprint is marked done with tag `v-cave` and sha  
**When** `squad_sprint.sh complete cave v-cave <sha>`  
**Then** `completed.tsv` has the row; state is `done`; the sprint no longer appears on the open list.

### Slice 1 acceptance

- `bb test` includes `sprint_control_test` and stays green
- `squad_sprint.sh` can create/list/move/schedule/cancel/complete against a temp repo
- `squad_sprint_sim` rules still match: auto Sprint 0, abandoned reschedulable, open list omits done
- Commit: durable sprint tool

---

## Slice 2 — Workflow (`squad_next`)

New sprint transition table. **Do not** add sprint phases as story packet states. Story packets are used only on the test-spec track after plan approval.

### 2.1 Stop story-shaped implementer dispatch for sprint-form swarms

Detect a sprint-form project by presence of `.squad/sprints/`. If present:

- Do not create implementer assignments from story `implementation_approval`
- Do not form B96 story-pair batches
- Hardener/QA/architect wait on **sprint** readiness, not `all-theme-stories-qa-complete?` alone

If `.squad/sprints/` is absent, keep the old table so existing tests and old swarms still run.

### 2.2 Sprint 0 residual

**Given** Sprint 0 is `scheduled` and maps are missing  
**When** residual runs  
**Then** `NEXT_ACTION: write_sprint0_maps` (SL authors module map + implementation order; existing `squad_theme.sh module-map` / `implementation-order`).

**Given** maps exist and are unapproved  
**Then** `create_approval_request` for gate `sprint-0-maps` covering both documents.

**Given** that approval  
**Then** Sprint 0 → `done`, record tag (lightweight: `sprint-0` + HEAD sha). Residual `assemble_sprint`.

Stories added after schedule are ignored for this map pass (already in spec).

### 2.3 Implementation sprint: SL spec then analyst

**Given** an impl sprint is `scheduled`  
**Then** residual `write_sprint_spec` — SL writes `.squad/sprints/<id>/spec.md` (story list + current map + order; adjust map/order if needed and request reapproval first).

**Given** spec recorded  
**Then** create **one** analyst assignment scoped to the sprint (not one per story). Instructions point at the spec, all member stories, map, and order.

**Given** analyst merged  
**Then** expect recorded: elaborated story files, `tasks/*`, `interfaces.md`. Residual `request_sprint_plan_approval` — **one** approval for the plan.

### 2.4 After plan approval: two tracks

**Given** sprint-plan approved  
**Then** residual may emit **concurrent** work:

1. Tasks in implementation order: create implementer assignment per task (`--task <module>` or assignment story-id = module, metadata `task_id` / `sprint_id` / `story_ids`). One implementer per module; do not start module N+1 until order says so; different modules may be concurrent.
2. Each elaborated story: Gherkin writer → reviewer → approve → QA proc writer → reviewer → approve, **as usual**.

Implementer assignments must not require Gherkin/QA on the packet. Test-spec assignments must not wait on implementation.

### 2.5 Task pipeline

As usual: implementer → cleaner → CR, merge via squadd.

Task stage on `.squad/sprints/<id>/tasks/<mod>`: `implement` → `clean` → `review` → `ready`.

### 2.6 Finalize the sprint, not each story

**Given** every task is `ready` **and** every sprint story has approved feature + QA procedure  
**Then** create the hardener assignment for the **sprint** (not a story batch of the old kind).

Hardener first: Gherkin passing via APA harness. Then usual hardening.

Then QA → Architect → optional SI, as usual, sprint-scoped.

**Given** QA + Architect bless (or SI after handoff)  
**Then** `git tag`, `squad_sprint.sh complete`, residual idle / `assemble_sprint`.

### 2.7 Cancel from residual/tools

`squad_sprint.sh cancel` while scheduled: abandon branch from current worktrees/HEAD as the run; clear in-flight assignments for that sprint; sprint stays `abandoned` with members. Next `schedule` starts a new run.

### Slice 2 acceptance

- New `sprint_next_test.clj`: Sprint 0 maps → approve → done; impl sprint spec → analyst → plan approve → implementer on module + gherkin on story; harden only after both tracks
- Existing non-sprint `squad_next` tests still pass (no `.squad/sprints/`)
- `bb test` green
- Commit: sprint FSM

---

## Slice 3 — Prompts

Do this after Slice 2 emits the right `NEXT_ACTION` / assignment templates. Tests are prompt/contract string assertions plus any contract.edn writes/requires.

### 3.1 Squad Leader

**Files:** `swarmforge/roles/squad-leader.prompt`

- Owns one project. Does not classify backlog items as “theme vs story.”
- Sprint 0: author module map + implementation order; request one approval; do not implement stories.
- Impl sprint: adjust maps if needed (reapprove); write sprint spec; assign analyst; present **one** plan approval (stories + tasks + interfaces).
- After approval: dispatch tasks in order; do not send Gherkin to implementers.
- Cancel/reschedule: no ceremony.

Remove or rewrite “User-Directed Stories → analyst theme vs story” so it matches backlog add + sprint membership.

### 3.2 Analyst

**Files:** `swarmforge/role-templates/analyst.prompt`, `analyst.contract.edn`

- Input: sprint spec, not “infer the project from loosely related items.”
- Elaborate member stories for consistency.
- Emit **tasks by module** (one task, many stories) and **intermodule interfaces**.
- Do not emit story-shaped implementer batches.
- Hand stories + tasks + interfaces back; SL requests the plan approval.

### 3.3 Implementer

**Files:** `implementer.prompt`, `implementer.contract.edn`

- Input: one task (module, story needs, interfaces).
- TDD. No Gherkin, no QA procedures.
- Cleaner/CR unchanged.

### 3.4 Hardener

**Files:** `hardener.prompt`

- Waits for all sprint modules `ready` and all sprint features/QA approved (FSM already waits).
- **First task:** get Gherkin passing by implementing the test harness per APA.
- Then usual hardening.

### 3.5 Gherkin / QA / Architect / SI

Keep “as usual” with two wording fixes:

- Gherkin is integration testing, not the implementer spec.
- Architect/SI bless the **sprint**, not a leftover story pile.

### Slice 3 acceptance

- `sprint_prompt_test.clj` asserts the required phrases and forbids leftover “approve backlog for analysis” / “implementer reads Gherkin first”
- `bb test` green
- Commit: sprint prompts

---

## Slice 4 — UI

Lift `sprint-mockup.html` into `swarmforge/scripts/squadd/dashboard.html` and `web.clj`. Wire buttons to Slice 1 APIs and `/api/state` from real sprint records + `squad_next` residual.

### 4.1 Kill backlog Approve-for-analysis

Add Story: **name** + **description** textarea. Enter adds; Shift+Enter newline. No Approve that throws the item at SL for classification.

### 4.2 Controls together

Toolbar group: **Project** | **Add Story** | **Sprint** chip. Backlog deck stays a count + list.

### 4.3 Sprint dialog

- Lists **open** sprints only (not `done`)
- Sprint 0 already exists; New sprint is always impl; no kind menu
- New sprint is selected so stories can be moved immediately
- Schedule dismisses the dialog; **OK** dismisses
- Abandoned looks like a normal sprint; Schedule works

### 4.4 Board

| Column | Cards |
|--------|--------|
| Specifying | Index cards: stories in analysis or Gherkin/QA |
| Coding | Clipboards: module tasks |
| Finalizing | Sprint card when hardening/QA/arch/SI |
| Done | Completed **sprints** (tag + SHA), not stories |

Sprint 0: map + order in Finalizing until approved; no story/module cards.

### 4.5 Work Queue and Attention

- Work first column is the assignment: module, story, or sprint
- Attention: sprint-0-maps, map-delta, **sprint plan**, per-story feature/QA, sprint bless

### 4.6 State API

`/api/state` grows: `project`, `open-sprints`, `backlog` (unscheduled stories), `specifying`, `coding` (tasks), `finalizing`, `done` (sprints), existing `work` / `approvals` / `residual`.

Keep old keys long enough that leftover tests are updated in the same slice, not left half-migrated.

### Slice 4 acceptance

- `sprint_ui_test.clj` + updated `squadd_web_test.clj`: no `residual:`; no backlog column; no Approve-for-analysis copy; `open-sprints` omits done; story cards vs task clipboards in HTML
- Click-through against a temp swarm: create project → Sprint 0 present → add stories → new sprint selected → schedule → Attention shows sprint-0 or plan gate
- `bb test` green
- Commit: sprint dashboard

---

## Spec coverage

| Spec section | Slice |
|--------------|-------|
| One project, many stories | 1, 3, 4 |
| Backlog = unscheduled | 1, 4 |
| Named sprints, one scheduled, lock | 1, 2, 4 |
| Cancel keeps sprint; abandon run; reschedule | 1, 2, 4 |
| Sprint 0 auto; maps + order; late stories ignored | 1, 2, 3 |
| Impl: SL spec → analyst → one plan approval | 2, 3 |
| Tasks by module + interfaces | 1, 2, 3 |
| Two tracks; Gherkin is integration | 2, 3, 4 |
| Implementer TDD, no Gherkin | 2, 3 |
| Finalize waits modules + features; APA first | 2, 3 |
| Tag + completed list | 1, 2, 4 |
| Mockup behavior | 4 |

---

## Suggested first commit of Slice 1

Write `sprint_control_test.clj` for 1.1–1.3 only. Watch it fail. Add `squad_sprint.clj` / `squad_sprint.sh`. Hook Sprint 0 into theme create. `bb test`. Commit. Then 1.4–1.6.
