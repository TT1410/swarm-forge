# System-Analyst and Start the Backlog

Date: 2026-08-20  
Status: approved in conversation; not implemented  
Replaces: story DAG / implementer-waits-on-pred SHA (remains on hold; do not build)

## Problem

Independent per-story assignments produce a series of little applications. This swarm started **replay** first; the stub grew a private cave map and probe CLI instead of one Hunt the Wumpus. A mission paragraph is not enough. The vision must be a **running skeleton** that later roles **extend from the inside**.

## Goals

- Operator **Starts the backlog** (one product), not a single card.
- A new template **`system-analyst`** reads every open backlog item and ships the frame, not the game.
- Later roles keep their assignments and fill named sockets on that one executable and one UI.
- No story DAG. No theme. No fake “frame” story on the board.

## Non-goals (v1)

- Dependency graph / ordered implementers
- Frame revision when a new socket appears (operator exception later, not automatic)
- Auto-start of items added after Backlog Start
- Gherkin, cleaner, CR, hardener, or story implementer on the empty loop
- Second System-Analyst on every new card

## Flow

1. Operator adds items (files / Troubleshooter / dashboard). Stories stay **open**. A **mission** (see `2026-08-20-backlog-mission-design.md`) is not open.
2. Operator **Starts the backlog** (requires a mission and at least one open story). Residual creates assignment `*-system-analysis` and spawns `system-analyst-001` only. No story packets yet. Per-card Start is disabled.
3. Agent reads the Mission and Stories on the assignment (do not search the worktree for `.squad/backlog` or `stories/`). Conceives a vision, ships one executable consistent with those stories, and writes that vision as a comment in the framework and in `qa/product.md`. Do not create plug points. One worktree, one commit:
   - One process / one `-main` / product entrypoint: the form the Mission names (empty turn loop, named stub sockets, dummy state). No hunt rules, messages, or wins.
   - `frame.md`: socket list (from **open stories**), how to run, what is not implemented.
   - `qa/product.md`: one QA procedure through that UI, with a labeled placeholder per open story.
4. Handoff: `swarm_handoff.sh` with no file. Missing `frame.md` or `qa/product.md` is an invalid git_handoff.
5. SL `accept-merge` of the frame commit (`frame.md`, `qa/product.md`, executable). Session is captured on retire.
6. Operator Attention gate **`frame`**: package is those merged files. Approve or reject (same UX as a plan). Residual records **`frame_sha`** only after approve. Stories do not start until then.
7. Residual then Starts **every item that was open at Backlog Start**: `stories/<id>.md`, packet, analyst — today’s Start, for each.
8. Story pipeline is unchanged except prompts: the frame is already real; extend it; QA-proc writer edits `qa/product.md` in place for this story’s placeholder (implementer notes as today).
9. Items **added after** Backlog Start stay open until the operator **Starts that card**. Individual Start is allowed only when `frame_sha` is present. No second System-Analyst.

## Role: `system-analyst`

New template (not a reuse of `analyst`). Own prompt and contract.

**Owns:** the executable frame, `frame.md`, `qa/product.md`.

**Must not:** implement story rules; add `features/*.feature` for hunt behavior; add a second entrypoint; fill a socket with a sidecar app or a private topology.

**Pipeline:** short path. One assignment, operator `frame` gate, merge. Residual does not spawn Gherkin-writer, QA-proc writer, implementer, cleaner, or CR for the frame.

**WIP:** the assignment has an `agent_id`. It **does** appear in the Work Queue (state icon, thermometer, Open agent). It does **not** get a board story card.

**Caps:** one `system-analyst` at a time. Same spawn lock as other transients.

## Files

| Path | Meaning |
|---|---|
| Product entrypoint (`bb run` or existing `-main`) | Empty loop + stub sockets |
| `frame.md` | Sockets, how to run, non-goals for the frame |
| `qa/product.md` | One QA script; placeholders `<!-- <story-id or backlog-id> -->` for later writers |

Required artifacts on the git_handoff: `frame.md`, `qa/product.md`, and the entrypoint path listed in `frame.md`.

## Gate and residual

- New approval kind: `frame`. Attention document is the package above.
- Reject: not merged; stories stay open; operator may **replace** the assignment (new `system-analyst`), same as today’s replace. Durable blocker if the agent cannot proceed.
- Product record holds `frame_sha` (and paths). Story packets do not store the frame.
- Residual when `frame_sha` missing and open items exist: only `system-analyst`. Per-card Start is rejected.
- Residual after merge: Start each item that was open at Backlog Start (snapshot the ids at Start so later adds are not swallowed).
- After `frame_sha`: per-card Start starts **that** item only.

## Cockpit

- Backlog deck: **Start backlog** enabled when there is a mission, at least one open story, no `frame_sha`, and no in-flight `system-analyst`. Mission item: visible, labeled, editable until Start; never a story card. Details: `2026-08-20-backlog-mission-design.md`.
- Per-card Start hidden or disabled until `frame_sha`.
- Attention: `frame` gate with View package.
- Toolbar status: `Frame: none | pending | in review | on master`.
- Work Queue: system-analyst row while assigned.

## Later roles (extend, do not bolt on)

Every story agent (analyst, Gherkin, QA-proc, implementer, cleaner, CR, hardener, QA, architect, SI):

- Treat the merged frame as already real.
- Fill a named socket; keep the one executable and one UI.
- Do not attach a sidecar (second `-main`, story-specific probe, private cave map).
- QA-proc writer: edit `qa/product.md` in the same commit as implementer notes; fill this story’s placeholder. QA runs that one procedure through the frame UI.
- Two QA-proc writers on `qa/product.md`: last merge wins; no DAG to serialize them.

## Failures

- Blocked system-analyst: durable blocker; do not start stories.
- Invalid handoff without required frame artifacts: reject, do not merge.
- Empty hunt behavior in the frame commit: out of contract; operator should reject the `frame` gate (prompt-enforced; no extra role).

## Tests (acceptance)

- Backlog Start with two open items: only `system-analyst` assignment/spawn; WIF has that row; board has no story cards; per-card Start disabled.
- After simulated frame merge (`frame.md`, `qa/product.md`, `frame_sha`): residual Starts both items; two analyst assignments.
- Per-card Start before `frame_sha` rejected.
- Per-card Start after `frame_sha` starts only that item.
- Item added after Backlog Start stays open until per-card Start.
- `system-analyst` handoff missing `frame.md` or `qa/product.md` is invalid.
- Later story assignment names the frame (run command, extend `qa/product.md`); no “provided theme.”
- Prompts: system-analyst owns sockets not hunt rules; story QA-proc edits `qa/product.md` placeholders.

## Implementation notes

- New files: `swarmforge/role-templates/system-analyst.prompt`, `system-analyst.contract.edn`; residual branches in `squad_next.clj`; approval kind `frame` in existing approval/web paths; dashboard Start-backlog control, frame toolbar status, WIF via normal assignment rows.
- Product record: `.squad/product` (not `.squad/stories/…`). Holds `frame_sha`, `frame_path`, `qa_path`, `assignment_id`. Assignment template `system-analyst`, scope `product`, `story_id` absent. It is not a board card.
- Do not invent `implementation-order.md` or theme records.
- Reuse no-arg `swarm_handoff.sh` and existing spawn/retire.
