# Redo implementation plan

**Spec:** `redo.md`  
**Base SHA:** `b73c972` (Close B95, B93, B92, and B102)  
**Sprint work:** left on `sprint-module-squad`. Do not merge it back.

**Goal:** Replace the theme/sprint/merger machine with a single-story pipeline: user-approved plan, user-approved Gherkin/QA, implementer six-pack (units + Gherkin), CR then cleaner, then hardener/QA/architect/SI, story done. Squad Leader owns merges. No merger, no dry-run, no implementation-order gates, no sprints, no operator-facing project/theme.

**Architecture:** Keep packets, `squad_next` residuals vs mechanical, squadd spawn/retire, dashboard cockpit, Troubleshooter chat. Change **who merges**, **role order after implementer**, and **what the analyst emits**. Internally a swarm may still have one hidden container id so `squad_theme.sh story` / packets keep working; operators never see theme or project.

**How to work:** TDD. Given/When/Then in `test/swarmforge/`. See each scenario fail. `bb test` green before every commit. One slice at a time.

**Out of scope:** Rewriting `squad_simulator.clj`. B89 project directories. Converting a mid-flight sprint swarm. Keeping B96 story-pair implementer batches.

---

## Files

| File | Role |
|------|------|
| `redo.md` | Product spec. Do not contradict it. |
| `swarmforge/scripts/squad_next.clj` | Residuals and mechanical applies. Drop merger/dry-run/impl-order/B96/reviewer dispatch. |
| `swarmforge/scripts/squad_assign.clj` | SL may `merge-ready` / `accept-merge`. No dry-run gate. |
| `swarmforge/scripts/squadd.clj` | Stop treating merger as the main-git actor. SL merge residual is allowed (or daemon still applies merge when residual names it). |
| `swarmforge/roles/squad-leader.prompt` | SL merges. No theme map ceremony. No impl-order. Analyst = plan, not theme-to-stories. |
| `swarmforge/role-templates/analyst.prompt` | Implementation plan per story; architecture and deps in the plan. |
| `swarmforge/role-templates/implementer.prompt` | TDD units **and** Gherkin passing (six-pack APS). |
| `swarmforge/role-templates/code-reviewer.prompt` | Recommendations **and** property tests. |
| `swarmforge/role-templates/cleaner.prompt` | Implements CR recommendations and cleans. |
| `swarmforge/role-templates/{gherkin,qa-procedure}-writer.prompt` | No reviewer step; user approves the artifact. |
| `swarmforge/constitution/articles/local-workflow.prompt` | Match redo pipeline. |
| `swarmforge/scripts/squadd/dashboard.html`, `web.clj` | Story board; Attention for plan / Gherkin / QA / story bless. Drop project/theme as the product unit. |
| `test/swarmforge/redo_next_test.clj` | **New** FSM scenarios. |
| `test/swarmforge/redo_prompt_test.clj` | **New** prompt contracts. |

Keep `squad_theme.sh` / `.squad/themes/` as storage until a later rename (B104-class). Do not add sprints.

---

## Current pipeline at `b73c972` (what you are leaving)

Theme map → theme approval → analyst writes many stories → story approval → Gherkin writer → **Gherkin reviewer** → Gherkin approve → QA writer → **QA reviewer** → QA approve → **impl-order + B96 pairs** → implementer → **cleaner** → **CR** → hardener → QA → architect → SI → story/theme finalize.

Merger + dry-run own main-git. Squadd, not SL, runs `merge-ready` / `accept-merge`.

---

## Target pipeline (one story)

```text
story on disk
  → analyst: implementation plan (user approves)
  → gherkin-writer (user approves feature)
  → qa-procedure-writer (user approves procedure)
  → implementer: TDD units + Gherkin passing (six-pack)
  → SL merges
  → code-reviewer: recs + property tests
  → SL merges
  → cleaner: implement recs + clean
  → SL merges
  → hardener (six-pack) → QA → architect recs → SI
  → story complete
```

Gherkin and QA have **no reviewer role**. User approval of the artifact is the gate.

---

## Slice 1 — SL merges; delete merger and dry-run

**Given** an assignment is merge-ready  
**When** residual/mechanical merge runs  
**Then** Squad Leader (or daemon on SL’s behalf) runs `accept-merge` with **no** merger spawn and **no** dry-run preflight.

**Given** `squad_next` would have created a merger  
**Then** it does not.

- Test: `test/swarmforge/redo_next_test.clj` — no `TEMPLATE: merger`; no `check-merge-ready` / dry-run residual; merge command is `squad_assign.sh accept-merge` (or current accept-merge helper) without a merger assignment.
- Update `assign_merge_test.clj` / `issues_b94_b99_b101_test.clj` that require merger or dry-run pause.
- Implement: `squad_next.clj` merger candidates gone; `squad_assign.clj` skip dry-run evaluation; SL prompt: you **do** run merge-ready/accept-merge (or residual says wait only if daemon applies them — pick **one** owner and test it). Recommendation: **daemon still applies** `merge-ready`/`accept-merge` as mechanical, but the **actor is SL’s main git**, not a merger worktree. Residual never says `create_assignment` merger.
- Remove merger from singleton list / spawn table if nothing else needs it. Leave the template file on disk until Slice 5 so old tests can be deleted in this slice, not left half-migrated.
- `bb test` green. Commit: SL merges, no merger, no dry-run.

---

## Slice 2 — One story, no impl-order, no B96, no theme ceremony

**Given** a registered story with approved plan + Gherkin + QA  
**When** residual creates an implementer  
**Then** it is **one story**, not a pair, and it does **not** wait on `implementation-order.md` provider SHAs.

**Given** a new swarm  
**When** residual runs with no story  
**Then** it is `wait` (or “add a story”). Not `write_theme_module_map`. Not theme approval.

- Test: `redo_next_test.clj` — create theme storage if the helper still requires an id, register one story, skip order file, expect implementer on that story id only; assert **not** `write_theme_module_map` after create.
- Delete or stop calling `derive-implementer-batches` / `implementer-batch-plan` from `ready-actions`.
- Stop `implementation-order-record-candidate` as a hard gate. Analyst may mention deps **in the plan**; do not record a makefile order that blocks implementers.
- Prompts: stories are **end-to-end functional use cases**. Drop “split process/UI/IO into separate stories” as a requirement. Drop theme-map-before-analyst.
- `issues_b96_test.clj`: invert or delete pair-batch assertions.
- `bb test` green. Commit: one story, no order gate, no theme-first residual.

---

## Slice 3 — Analyst = implementation plan; user approves

**Given** a story file exists  
**When** residual runs  
**Then** `create_assignment` analyst scoped to **that story**, instructions: write an implementation plan aware of architecture and dependencies.

**Given** analyst merged a plan  
**Then** one user approval gate `implementation-plan` (name it that; do not reuse theme approval).

Durable: `.squad/stories/<id>/plan.md` (or packet field `implementation_plan`). Not a sprint spec. Not a theme package.

- Test: after story register, residual is analyst; after fake merge + plan file, residual is `create_approval_request` gate `implementation-plan`.
- `analyst.prompt` / `analyst.contract.edn`: emit plan, not a pile of new stories (user may still add stories separately via dashboard / Troubleshooter).
- Approval config: add `implementation-plan` as required by default (`squad_config.clj`).
- Dashboard Attention: plan approval, not “theme + module map.”
- `bb test` green. Commit: analyst plan gate.

---

## Slice 4 — Gherkin and QA: write, user approves, no reviewer

**Given** plan approved  
**Then** residual may create `gherkin-writer` then, after user approves the feature, `qa-procedure-writer` then user approves the procedure.

**Given** writer merged  
**Then** do **not** create `gherkin-reviewer` or `qa-procedure-reviewer`. Residual is user approval of the artifact.

- Test: no reviewer `create_assignment` after writer merge; `NEXT_ACTION: create_approval_request` for `gherkin` / `qa-procedure`.
- Remove those transitions from `story-transition-table` (or skip them).
- Writer prompts: you have no reviewer; the user approves the file.
- Update role_contract / B-tests that require reviewer assignments.
- `bb test` green. Commit: no Gherkin/QA reviewers.

---

## Slice 5 — Implementer six-pack; CR then cleaner

**Given** Gherkin and QA approved  
**Then** implementer assignment: TDD unit tests **and** get Gherkin passing (APS six-pack already in `implementer.prompt` at this SHA).

**Given** implementer merged  
**Then** **code-reviewer** next (not cleaner). CR writes recommendations **and** property tests.

**Given** CR merged  
**Then** **cleaner** implements the recommendations and cleans.

**Given** cleaner merged  
**Then** hardener (six-pack) → QA → architect recommendations → senior implementer → story complete (`final` / packet done). Not theme finalize as the unit of done.

- Test: sequence of residuals/assignments `implementer` → `code-reviewer` → `cleaner` → `hardener` (after code review approved / cleaner recorded — match packet fields you already have).
- Flip `story-transition-table` order: today cleaner precedes CR.
- `code-reviewer.prompt`: property tests + recs.
- `cleaner.prompt`: apply CR recs, then clean.
- Hardener/QA/architect/SI prompts: “as usual” for **this story**, not a sprint.
- Delete merger template from dispatch tests; optional delete `merger.prompt` in this slice if nothing references it.
- `bb test` green. Commit: new post-impl order.

---

## Slice 6 — Dashboard and constitution

Follow `redo-ui.md`.

- Board columns stay Specifying / Coding / Finalizing / Done; cards are **stories** (index cards, short stage pills). No sprint chip, no Sprint 0, no Project control, no Projects rail. Unstarted stories stay in the backlog deck.
- Attention: implementation plan, feature, QA procedure, story bless. View document + Approve. No theme map. No reviewer gates.
- Add Story: name + body; Enter adds. Work Queue: story · role. No merger.
- `local-workflow.prompt` rewritten to the target pipeline. Drop merger, reviewers, impl-order, theme-first.
- Troubleshooter: when asked, may add stories (exception). Do not route that to SL for theme classification.
- `bb test` green. Commit: cockpit + constitution.

---

## Spec coverage

| redo.md | Slice |
|---------|-------|
| 1–2 SL merges; no merger; no dry-run | 1 |
| 3 Stories are E2E use cases | 2, 3 |
| 4 Module impl order does not matter | 2 |
| 5 No sprints, no project | 2, 6 |
| 6 Simplify workflow | all |
| 7 Analyst → plan, user approves | 3 |
| 8 Gherkin/QA no review; user approves | 4 |
| 9 Implementer units + Gherkin | 5 |
| 10 CR recs + property tests | 5 |
| 11 Cleaner implements recs + cleans | 5 |
| 12–15 Hardener, QA, architect, SI | 5 |
| 16 Story complete | 5, 6 |

---

## Suggested first commit

Write `redo_next_test.clj`: after implementer merge-ready, **no** merger assignment and **no** dry-run residual. Watch it fail (current SHA still creates merger / dry-run). Delete merger candidates; skip dry-run. `bb test`. Commit Slice 1. Then Slice 2.
