# Bugs

Prioritized open issues. Priority is **impact on swarm correctness, operator unblock, and recurring defect classes** — not chronological discovery. Architecture debt and product/workflow defects share one list.

**How to read priority**
- **P1 — Fix before the next serious multi-story swarm.** (Currently clear for the 2026-08-12 stuck-swarm class.)
- **P2 — Important soon.** Operator UX, theme gates, remaining control-plane structure.
- **P3 — When capacity allows.** Polish, deep architecture, nice-to-have dashboard IA.

| Pri | ID | Title | Kind | Area |
|-----|-----|--------|------|------|
| **P2** | B29 | When the swarm is stalled, the dashboard should explain why (usually stalled agents) | UX / design | Dashboard |
| **P2** | B10 | Dashboard answers truncate to first line of multiline response | Reliability | Dashboard IO |
| **P2** | B11 | Zombie tmux sessions after agent retire | Hygiene | Lifecycle |
| **P2** | B12 | Hardener edits root tooling (`bb.edn`) against role rules | Policy | Role enforcement |
| **P2** | B25 | Implementation order and dependency-checker config must be user-approved | Workflow | Theme / analysis gates |
| **P2** | B23 | Theme close / finalize is undefined (need approval that still allows more stories) | Workflow | Theme lifecycle |
| **P2** | B18 | `squad_next` mixes planning, policy, presentation, and execution | Architecture | Control plane |
| **P2** | B16 | Control-plane ownership is implicit (env/prompt), not modeled | Architecture | Authority |
| **P2** | B19 | Scheduling priority is emergent across many code paths | Architecture | Planner policy |
| **P3** | B13 | Analyst dependency-checker policy missing or coarse | Product quality | Analysis |
| **P3** | B14 | Theme package page missing `dependency-checker.edn` card | UX | Dashboard |
| **P3** | B15 | Grok agent terminal window does not fill / scroll correctly | UX | Terminal / Grok |
| **P3** | B24 | Dashboard display needs better information architecture / organization | UX / design | Dashboard |
| **P3** | B20 | Shared lease primitive missing (locks are one-off) | Architecture | Concurrency |
| **P3** | B21 | FSM transitions have hidden multi-file side effects | Architecture | Persistence |
| **P3** | B22 | Durable record formats are informal across categories | Architecture | State schema |

**Fixed (removed):**
- P0 B01–B04 (rework thrash, held handoff, impl-order gate, spawn HOL)
- prior P1 B05–B08 (APS acceptance pipeline + templates; coverage; acceptance suite; safe `file-map`)
- **P1 B26–B28, B30, B32, B33** (dashboard terminal deny-list; batch replace `batch_id`; merger slot; six-pack APS; mutator worker wiring)
- **P2 first set B31, B09, B17** (hardener quality bar must-meet-or-block; Troubleshooter role + quiet dashboard chat/`...`/open-window; typed actions foundation with `:op`/`:authority`)

**Suggested fix order**

1. **P2 remaining operator/hygiene:** **B29** → **B10** → **B11** → **B12**.
2. **P2 workflow:** **B25** → **B23**.
3. **P2 architecture (next):** **B18** / **B16** / **B19** (build on typed actions from B17).
4. **P3:** **B13**–**B15**, **B24**, **B20**–**B22**.

**Related clusters**

| Cluster | Bugs | Note |
|---------|------|------|
| Dashboard / operator | **B29**, B10, B14, B24 | Stall reason, multiline answers, IA (Troubleshooter landed B09) |
| Theme gates | B25, B23, B13, B14 | Approve order/checker; finalize; analyst policy; theme card |
| Control plane | B16, B18, B19, B20, B21 | Ownership, planner split, priority (typed actions B17 done) |
| Hygiene / policy | B11, B12 | Zombie tmux; root tooling thrash |
| Informal state | B10, B22 | Multiline + durable formats |

Source notes for B16–B22: `architecture-improvements.md` (review findings folded in and re-prioritized).

---

## P2 — Remaining operator, workflow, architecture

### B29 — When the swarm is stalled, the dashboard should explain why (usually stalled agents)

**Symptom / gap:** When residual is effectively stuck (`wait` with agents “active” for hours, merge_blocked, held handoffs, capacity deadlock), the **dashboard does not tell the operator why**. Status looks like ordinary busyness: agents in `handoff_sent`, empty or incomplete Assignments (B28), no prominent “stalled” signal. Operators must dig into disk, merge-error files, held inbox, and `squad_next` by hand. Live example: multi-hour wait while hardener/merger/cleaner were merge_blocked; UI never surfaced “merge conflict on acceptance/runner.clj” or “singleton merger slot held by handoff_sent.”

**Design need (open — prefer clarity over a specific chrome):**

Most stalls are **one or more agents not making progress**. The dashboard should make that obvious and attach a **human-readable reason**.

**Candidate UX (not mandatory):**
- Mark stalled agents (e.g. **red** / warning pill) when quiet beyond recovery threshold, assignment is `merge_blocked`, handoff held, dirty checkout blocked merge, etc.
- **Hover or click** shows a short reason (from assignment `detail`, `merge-error` summary, held-handoff note, recovery classification).
- Optional top **“Swarm health”** strip: “Stalled: 3 agents” with the same reasons listed.

**Better ideas welcome** if red+hover is wrong for density (e.g. dedicated Stall panel, sticky blocker list, residual-reason mirrored on the page). Goal is: **open dashboard → know why nothing moves**, without shell archaeology.

**Expected:**  
1. Define “stalled” operationally (quiet time, merge_blocked, held handoff, failed merge, no ready residual progress, etc.).  
2. Surface stalled agents and/or assignments with **state + reason** derived from existing status/merge-error/held data.  
3. Do not rely only on agent `handoff_sent` (looks healthy).  
4. Align with B28 (show merge_blocked rows) and B24 (IA so “needs you / stalled” outranks noise).

**Solution direction:**  
- Extend status API with `stalled?` / `stall_reason` per agent/assignment.  
- UI treatment TBD (color, hover, panel).  
- Prefer reusing assignment `detail` and first lines of merge-error over inventing new files when possible.

**Priority rationale (P2):** Same stuck swarm was undiagnosable from the dashboard; visibility is part of unblocking. Demoted from P1: diagnosis UX; fix B28 first for assignment visibility.

**Where:** `squadd/web.clj` status JSON + agents/assignments UI; agent quiet/recovery signals; assignment status + merge-error; optional residual “wait” reason export.

**Related:** B28 (show merge_blocked), B27, B26, B30, B24, B09 (troubleshooter).

---

---

---

---

---

---

### B10 — Dashboard answers truncate to first line of multiline response

**Architecture note:** Concrete instance of informal `key: value` records (B22). Fix the request/answer path now; formalize formats later.

**Symptom:** Operator asks via dashboard. Full multiline answer is written and `squad_dashboard_request.sh answer` succeeds. **UI only shows the first line** (e.g. intro without `bb run wumpus`).

**Cause:** Line-oriented `key: value` parse; only the first `response: …` line is kept. Also no `white-space: pre-wrap` on render.

**Expected:** Full multiline answers round-trip write → read → dashboard with preserved breaks.

**Solution direction:**  
1. Headers + body (or escape/base64/`response: |` block) for multiline fields.  
2. Or sibling `….answer` file referenced from the kv header.  
3. Dashboard: `pre-wrap` / `<pre>` on request body.  
4. Test: blank lines and commands survive API and UI.

**Where:** `squad_dashboard_request.clj`; `squadd/web.clj` request rendering.

**Repro (live):** `dashboard-20260811T221429Z-001` — full answer in file; dashboard showed only first line.

---

---

---

---

---

---

### B11 — Zombie tmux sessions after agent retire

**Symptom:** Agent **retired**, worktree gone, not in `roles.tsv` — but **tmux sessions still exist**. Retire detail may claim session was not running while `tmux ls` lists it.

**Cause:** Retire kill/detect mismatch (wrong socket, race, or liveness check vs swarm server). Distinct from held-handoff finish (fixed P0 B02): this is **session leak**.

**Expected:** After successful `squad_retire.sh`, agent tmux session is gone; status matches reality.

**Solution direction:**  
1. Retire always uses project tmux socket; verify `has-session` after kill.  
2. Force kill if still present; log hard failure if alive.  
3. Periodic squadd reconcile: `swarmforge-*` sessions with no live agent → kill or alert.

**Where:** `squad_retire.clj`; squadd role reconciliation.

**Repro (live):** Only SL in `roles.tsv`, residual idle, `tmux ls` still shows retired worker sessions.

---

---

---

---

---

---

### B12 — Hardener edits root tooling (`bb.edn`) against role rules

**Symptom:** Hardener commits change root `bb.edn` (merge conflicts). Prompt says do **not** edit root tooling unless assignment requires it.

**Cause:** Soft prompt only; concurrent hardeners + mergers fight over `bb.edn`.

**Expected:** Hardener does not touch denylisted root tooling unless assignment says so; violations rejected or blocked.

**Solution direction:**  
1. Strengthen prompt + Leader Instructions.  
2. Pre-handoff check: hardener result must not modify denylist.  
3. Prefer product layout that does not require hardener `bb.edn` edits (tasks under `bb/tasks/`).

**Where:** `hardener.prompt` / contract; live hardener commits including `bb.edn`.

---

---

---

---

---

---

### B25 — Implementation order and dependency-checker config must be user-approved

**Symptom / gap:** After analysis (or early tooling), two architecture-control artifacts can land and start driving the swarm **without an explicit user approval gate**:

1. **Module / implementation ordering** — makefile-style `implementation-order.md` (durable under `.squad/themes/<id>/implementation-order.md`). It hard-gates implementers (providers must have `implementation_sha`). Recording can be mechanical from a root draft; the operator never has to accept the order.  
2. **Dependency-checker configuration** — product `dependency-checker.edn` (allowed component graph). Implementers and hardeners enforce whatever is on disk; a pitiful or wrong policy (B13) can still go green without the user ever reviewing it.

Theme + module map already require approval before analysis. Ordering and dependency policy are equally consequential for **build sequence** and **Clean Architecture boundaries**, but they skip that class of gate.

**Expected:**  
1. User-facing **approval** (dashboard) for **implementation order** once a non-empty durable order is proposed (or when the order is first recorded / materially revised).  
2. User-facing **approval** for **dependency-checker.edn** once a non-trivial policy is proposed (or on material revision).  
3. Implementer / hardener gates respect “approved” state: do not treat unapproved order or checker config as final for hard enforcement (or residual blocks until approved).  
4. Re-approval (or a clear “still valid” path) when analyst/architecture work changes either artifact.  
5. Theme package UI shows approval status for both (pairs with B14 for checker display).

**Solution direction:**  
- New approval gates (e.g. `implementation-order`, `dependency-checker`) via `squad_approval.sh` / packet-or-theme state fields.  
- Residual after analysis merge: request these approvals when drafts exist and are unapproved.  
- Mechanical record of order (P0 B03) remains; **approval** is the operator step after record.  
- Align with B13 (real checker content) and B23 (theme lifecycle)—order/checker approve sits between theme start and story implementation thrash.

**Priority rationale (P2):** Prevents silent wrong build order and fake architecture gates; same “user must own the architecture control plane” idea as theme approve. Content quality of the checker remains B13.

**Where:** `squad_approval` gates; `squad_theme` / durable order + product `dependency-checker.edn`; `squad_next` residual after analysis / before implementers; dashboard theme package + approval UI; analyst/architect handoffs that produce these files.

**Related:** B13 (policy content), B14 (display), fixed P0 B03 (durable order must exist).

---

---

---

---

---

---

### B23 — Theme close / finalize is undefined (approval that still allows more stories)

**Symptom / gap:** Story packets have a path through implementation → final approval, and `squad_report.sh` can emit a theme report, but there is **no clear product/workflow notion of closing or finalizing a theme**. Operators and residual cannot tell:

- When a theme is “done enough to ship” vs still open-ended analysis/implementation  
- Whether the swarm should wind down (retire transients, stop spawning implementers for that theme) vs keep waiting for more stories  
- How a user marks “this release/theme slice is accepted” without permanently forbidding **new** stories later  

Themes today are effectively never finished: analysis can add stories, packets advance independently, and nothing transitions the theme into a durable terminal or “shipped but extensible” state.

**Expected (design intent — needs product decision, then FSM/UI):**

1. **Theme finalize / ship approval** — a user-facing approval (dashboard) that means: current theme scope is accepted as complete for this slice (all relevant stories final-approved, or an explicit operator override).  
2. **Still allow more stories** — finalize must **not** lock the theme forever. After finalize (or on a new “reopen / extend” path), analysts and user-directed stories can still add work. Prefer states like:
   - `open` — analysis/implementation in progress  
   - `shipped` / `finalized` — user accepted current slice; residual may idle or report rather than thrash  
   - return to `open` (or `extended`) when new stories are registered  
3. **Residual / SL behavior** when finalized: clear next action (e.g. theme report, wait for user, or idle) instead of endless capacity waits with no goal.  
4. **Dashboard** shows theme lifecycle state and a control to request finalize (and to add stories after ship).

**Solution direction (open design):**  
- Add theme-level approval gate (e.g. `theme-final` / `theme-ship`) distinct from early `theme` approve (theme + module map to start work).  
- Record durable state on `.squad/themes/<id>/` (status + timestamp + detail).  
- `squad_next`: when all stories terminal **or** user finalizes with exceptions, offer finalize residual; after finalize, stop creating work unless new stories appear.  
- New stories after finalize: either auto-reopen theme or require a light “extend theme” path — prefer the lighter path so incremental stories stay easy.  
- Document in SL / constitution: finalize is **acceptance of current slice**, not “no further product development.”

**Priority rationale (P2):** Blocks a coherent end-to-end product loop and operator understanding of “are we done?” without being a crash. Design before heavy FSM; can start as approval + status only.

**Where:** `squad_theme.clj` / theme status; `squad_approval` gates; `squad_next` theme candidates / residual when no story work remains; dashboard theme package; SL prompt theme lifecycle; `squad_report.sh`.

---

---

---

---

---

---

### B18 — `squad_next` mixes planning, policy, presentation, and execution

**From architecture review.** One module computes candidates, applies them, prints user-facing commands, encodes daemon priority, and hides commands for SL residual mode. Every workflow change is high-risk (thrash, held finish, order gates all lived here).

**Expected:** Three layers:  
1. **Planner** — pure: state → typed actions (B17).  
2. **Executor** — applies allowed action sets for the current authority.  
3. **Renderer** — formats daemon / SL residual / operator views.

**Solution direction:**  
1. Extract pure candidate → action map first (no I/O).  
2. Move `apply-candidate!` / mechanical pass into executor with allow-lists.  
3. Residual print becomes renderer over the same actions (no second policy path).

**Depends on:** B17 (structured actions). Pairs with B19 (priority in planner only).  
**Where:** `swarmforge/scripts/squad_next.clj` (majority of control-plane complexity).

**Priority rationale (P2):** Highest long-term leverage against recurring FSM bugs. Slice after structured actions start.

---

---

---

---

---

---

### B16 — Control-plane ownership is implicit (env/prompt), not modeled

**From architecture review.** `squadd` owns mechanical FSM work and main Git writes, but ownership is enforced through env vars (`SWARMFORGE_MAIN_GIT`, `SWARMFORGE_ROLE`) and prompt discipline. That is weak: unsafe ops remain reachable from the wrong entrypoint if env is wrong or a role invents a command.

**Expected:** Ownership is first-class in the command/action API: **daemon/internal** vs **SL-facing** vs **operator/troubleshooter**. Unsafe actions (accept-merge, packet surgery, etc.) are unreachable from the SL residual entrypoint.

**Solution direction:**  
1. Tag structured actions with `:authority` (see B17).  
2. SL residual renderer/executor only accepts residual-safe ops.  
3. Daemon executor accepts mechanical + main-git set.  
4. Troubleshooter (B09) gets an explicit elevated operator set — not “SL with fewer rules.”

**Depends on / enables:** B17, B18, B09.  
**Where:** `squad_next.clj`, `squadd.clj`, assign/merge scripts, residual-only path.

**Priority rationale (P2):** Single-writer already works in practice; modeling ownership prevents regressions and makes B09 clean.

---

---

---

---

---

---

### B19 — Scheduling priority is emergent across many code paths

**From architecture review.** Bookkeeping, daemon-ready actions, spawn requests, handoffs, SL/dashboard requests, status, watchdogs, and approvals each have their own ordering. Policy like “main Git before retire” or “operator request outranks FSM task” is emergent, not testable in one place.

**Expected:** One planner policy function (or ordered policy table) decides priority. Tests assert policy directly.

**Solution direction:**  
1. After B18, all ready work enters the planner as typed actions.  
2. Single sort key / priority table for daemon drain vs residual vs operator.  
3. Remove ad-hoc “first in this loop wins” except where explicitly policy.

**Depends on:** B17–B18.  
**Where:** mechanical pass ordering; spawn poll vs workflow; dashboard wake vs residual.

**Priority rationale (P2):** Same foundation cluster as B18; do not invent a third priority scheme before structured planner exists.

---

---

---

---

---

---

## P3 — Product polish and deeper architecture

### B13 — Analyst dependency-checker policy is missing or pitifully coarse

**Symptom:** Product `dependency-checker.edn` is a minimal two-component sketch (`:main` / `:process`). Green checker while internal process graph is unconstrained.

**Who owns it:** Analyst should author a real policy from the theme module map.

**Expected:** Real components and allowed edges (UI → process → pure domain); analysis incomplete without non-trivial policy (or waiver). Policy should then be **user-approved** (B25), not only present on disk.

**Where:** `analyst.prompt` / contract; `theme-module-map.md`; product `dependency-checker.edn`.

**Related:** B14, B25.

---

---

---

---

---

---

### B14 — Theme package page should include dependency-checker.edn card

**Symptom:** Theme package view omits `dependency-checker.edn`.

**Expected:** Card next to module map / implementation order; clear missing state; ideally approval status for checker and order (B25).

**Where:** `squadd/web.clj` theme package sections.  
**Related:** B13, B25.

---

---

---

---

---

---

### B15 — Grok agent terminal window does not fill / scroll correctly

**Symptom:** Grok-backed agent windows show ~25 lines pinned at top; rest empty; scroll unusable. Codex panes do not show this.

**Expected:** Full geometry, usable scroll — or documented operator path if upstream TUI cannot.

**Where:** Grok launch / terminal adapters / tmux size; `swarmforge/docs/grok-agent-window-scroll.md`.

---

---

---

---

---

---

### B24 — Dashboard display needs better information architecture / organization

**Symptom / gap:** The operator dashboard (`squadd` web UI) works as a flat status surface—agents, assignments, approvals, requests, theme package, story packets—but **layout and hierarchy are hard to scan**. As swarms grow, the important signal (what needs me, what’s blocked, theme progress) competes with noise (every agent row, every intermediate state). Related defects (B10 multiline truncate, B14 missing theme cards, B09 request routing) sit on top of a display that was never designed as a coherent operator console.

**Expected (design — needs deliberate IA, not only more panels):**

1. **Clear visual priority:** “Needs operator” (approvals, dashboard requests, stuck/blocked) above “in flight” above “history / detail.”  
2. **Theme-centric navigation:** theme → stories → pipeline stage, not only a bag of agents and assignment ids. Align with theme lifecycle (B23) when that exists.  
3. **Scannable density:** progressive disclosure (summaries, expand for detail) rather than dumping full packet/request text into the main scroll.  
4. **Consistent grouping** of related surfaces (approvals vs SL/troubleshooter requests vs agent fleet vs theme package artifacts).  
5. **Stable chrome** so operators always know where to look for the next action.

**Solution direction (open design):**  
- Sketch wireframes / IA before more one-off cards.  
- Inventory current sections in `squadd/web.clj` (`dashboard-html`, `render`, API payloads) and regroup by operator job-to-be-done.  
- Prefer filters/tabs (Active / Needs you / Theme / Agents) over a single infinite list.  
- Coordinate with B10 (readable multiline), B14 (complete theme package), B09 (who owns requests), B23 (theme state badge).

**Priority rationale (P3):** Operator pain and cognitive load, not a functional blocker. Do after or alongside concrete dashboard bugs (B10, B14); avoid large redesign mid-P0/P1 firefighting.

**Where:** `swarmforge/scripts/squadd/web.clj` (HTML/CSS/JS + JSON renderers); dashboard request UI; theme package view; any status APIs the SPA polls.

---

---

---

---

---

---

### B20 — Shared lease primitive missing (locks are one-off)

**From architecture review.** Main-git lock protects merge-ready/accept-merge, but spawn lock, handoff in-process/held, status files, and request queues each invent their own concurrency story.

**Expected:** A **small common lease module**: token ownership, stale/TTL policy, atomic acquire/release, consistent logging. **Per-resource use** (merge lease, spawn lease, handoff claim) — **not** one mega-lock over the swarm.

**Solution direction:**  
1. Extract merge-lock patterns into `lease.clj` (or similar).  
2. Adopt for spawn and handoff claim next.  
3. Document which resources need leases vs single-writer daemon ownership (B16).

**Priority rationale (P3):** Valuable after hot paths stabilize; premature unification can hide different lifetimes. Prefer after B17–B18 slices exist.

**Where:** main-git lock; spawn lock; handoff in_process claim.

---

---

---

---

---

---

### B21 — FSM transitions have hidden multi-file side effects

**From architecture review.** e.g. `accept-merge` updates assignment status, merge state, accepted-merge, merger lineage, events, theme events. Hard to reason about as one transition.

**Expected:** Transition functions with explicit before → after state; **one persistence layer** writes all affected records for that transition.

**Solution direction:**  
1. Document each high-risk transition’s write set.  
2. Implement transition + persist pairs for accept-merge, record-result, retire.  
3. Tests assert full after-state, not a single file.

**Depends on:** B17–B18 help; B22 helps record shape.  
**Priority rationale (P3):** Large; reduce risk after typed actions exist.

---

---

---

---

---

---

### B22 — Durable record formats are informal across categories

**From architecture review.** Many files are `key: value` until blank line. Easy to debug, but multiline values (B10) and partial lifecycle writes recur. (Safe missing-file readers for agent telemetry landed with P1.)

**Expected:** Standardize by category:  
- **Headers + body** for messages/requests/answers  
- **EDN** (or similar) for structured state  
- **Append-only events** for history  

Shared safe readers (missing file → empty; no exists/slurp race).

**Solution direction:**  
1. Land B10 as targeted multiline fix.  
2. Introduce format helpers and migrate one record type at a time (dashboard requests, then assignment status, then packets if needed).  
3. Avoid big-bang rewrite of every file on disk.

**Priority rationale (P3) as a program:** Systemic. Concrete remaining pain is P2 B10.

**Where:** `file-map` / `parse-kv` call sites; dashboard requests; agent status/heartbeat; packets.

---

Keep the **single-writer** direction. Prefer **typed actions + planner/executor**, then **durable readers/leases**, over more prompt text or one-off retries. **Fix P1 B28, B29, B32, B33, B26, B30, B31, B27 before large control-plane rewrites** — dashboard stall visibility, batch replace ↔ result projection, implementer APS = six-pack coder model, modular acceptance layout, reliable Gherkin mutation facilities, hardener quality bar (CRAP/mutants/DRY), free merger slot. Theme finalize (B23) should stay a deliberate product approval, not an irreversible lockout of new stories. Dashboard IA (B24) should follow operator jobs, not grow as an uncurated status dump.

---

Keep the **single-writer** direction. Prefer **typed actions + planner/executor**, then **durable readers/leases**, over more prompt text or one-off retries.

**Before large control-plane rewrites**, clear **P1**: **B28**, **B32**, **B27**, **B26**, **B30** (visibility, batch-result projection, merger slot, acceptance layout, mutator worker wiring). Then **P2 APS strategy** **B33**/**B31** so hardeners and implementers share one correct model. Theme finalize (**B23**) stays a deliberate product approval, not an irreversible lockout of new stories. Dashboard IA (**B24**) should follow operator jobs, not grow as an uncurated status dump.

---

Keep the **single-writer** direction. Prefer **typed actions + planner/executor**, then **durable readers/leases**, over more prompt text or one-off retries.

**Before large control-plane rewrites**, clear **P1**: **B28**, **B32**, **B27**, **B26**, **B30** (visibility, batch-result projection, merger slot, acceptance layout, mutator worker wiring). Then **P2 APS strategy** **B33**/**B31** so hardeners and implementers share one correct model. Theme finalize (**B23**) stays a deliberate product approval, not an irreversible lockout of new stories. Dashboard IA (**B24**) should follow operator jobs, not grow as an uncurated status dump.

---

Keep the **single-writer** direction. Prefer **typed actions + planner/executor**, then **durable readers/leases**, over more prompt text or one-off retries.

**Before large control-plane rewrites**, clear **P1**: **B28**, **B32**, **B27**, **B26**, **B30** (visibility, batch-result projection, merger slot, acceptance layout, mutator worker wiring). Then **P2 APS strategy** **B33**/**B31** so hardeners and implementers share one correct model. Theme finalize (**B23**) stays a deliberate product approval, not an irreversible lockout of new stories. Dashboard IA (**B24**) should follow operator jobs, not grow as an uncurated status dump.

---

Keep the **single-writer** direction. Prefer **typed actions + planner/executor**, then **durable readers/leases**, over more prompt text or one-off retries.

**Before the next multi-story swarm**, clear **all P1**: **B28**, **B32**, **B27**, **B26**, **B30**, **B33**. Then **P2** hardener bar (**B31**) and operator UX (**B29**, **B10**, **B09**). Theme finalize (**B23**) stays a deliberate product approval. Dashboard IA (**B24**) should follow operator jobs, not grow as an uncurated status dump.

---

Keep the **single-writer** direction. Prefer **typed actions + planner/executor**, then **durable readers/leases**, over more prompt text or one-off retries.

**P1 complete** for the 2026-08-12 stuck-swarm class (visibility, batch replace projection, merger slot, APS six-pack model, mutator worker wiring). Next: **P2** hardener bar (**B31**) and operator UX (**B29**, **B10**, **B09**). Theme finalize (**B23**) stays a deliberate product approval. Dashboard IA (**B24**) should follow operator jobs, not grow as an uncurated status dump.

---

## Architecture north star (not a freeze)

Keep the **single-writer** direction. Prefer **typed actions + planner/executor**, then **durable readers/leases**, over more prompt text or one-off retries.

**P1 complete.** First **P2 set complete** (B31 hardener bar, B09 Troubleshooter, B17 typed actions). Next: remaining operator UX (**B29**, **B10**), hygiene (**B11**, **B12**), theme gates (**B25**, **B23**), then control-plane split (**B18**/ **B16**/ **B19**).
