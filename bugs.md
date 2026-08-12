# Bugs

Prioritized open issues. Priority is **impact on swarm correctness, operator unblock, and recurring defect classes** — not chronological discovery. Architecture debt and product/workflow defects share one list.

| Pri | ID | Title | Kind | Area |
|-----|-----|--------|------|------|
| **P2** | B09 | Operator unblock needs Troubleshooter (not SL) | Operator + arch | Roles / dashboard |
| **P2** | B10 | Dashboard answers truncate to first line of multiline response | Reliability | Dashboard IO |
| **P2** | B11 | Zombie tmux sessions after agent retire | Hygiene | Lifecycle |
| **P2** | B12 | Hardener edits root tooling (`bb.edn`) against role rules | Policy | Role enforcement |
| **P2** | B16 | Control-plane ownership is implicit (env/prompt), not modeled | Architecture | Authority |
| **P2** | B17 | Actions are shell strings, not structured ops | Architecture | Integration boundary |
| **P2** | B18 | `squad_next` mixes planning, policy, presentation, and execution | Architecture | Control plane |
| **P2** | B19 | Scheduling priority is emergent across many code paths | Architecture | Planner policy |
| **P3** | B13 | Analyst dependency-checker policy missing or coarse | Product quality | Analysis |
| **P3** | B14 | Theme package page missing `dependency-checker.edn` card | UX | Dashboard |
| **P3** | B15 | Grok agent terminal window does not fill / scroll correctly | UX | Terminal / Grok |
| **P3** | B20 | Shared lease primitive missing (locks are one-off) | Architecture | Concurrency |
| **P3** | B21 | FSM transitions have hidden multi-file side effects | Architecture | Persistence |
| **P3** | B22 | Durable record formats are informal across categories | Architecture | State schema |

**Fixed (removed):**
- P0 B01–B04 (rework thrash, held handoff, impl-order gate, spawn HOL)
- P1 B05–B08 (APS acceptance pipeline + templates; coverage before CRAP/mutate; full acceptance suite before late-role handoff; safe `file-map` TOCTOU)

**Suggested fix order:** P2 operator + concrete IO (B10, B09, B11–B12), then architecture foundation B17 → B18 → B16 → B19, then P3 depth (B20–B22) and polish (B13–B15).

**Related clusters**

| Cluster | Bugs | Note |
|---------|------|------|
| Informal file state | B10, B22 | Multiline truncate; B22 systemic (safe `file-map` landed with P1) |
| Control plane structure | B16, B17, B18, B19 | Ownership, typed ops, planner split, priority policy |
| Concurrency / multi-write | B20, B21 | Leases + explicit transition persistence |
| Operator path | B09, B16 | Troubleshooter + unreachable unsafe ops from SL |
| Dependency-checker | B13, B14 | Analyst policy + theme UI |

Source notes for B16–B22: `architecture-improvements.md` (review findings folded in and re-prioritized).

---

## P2 — Operator path, concrete hygiene, architecture foundation

### B09 — Operator unblock and dashboard requests need a Troubleshooter (not the squad leader)

**Architecture note:** Same finding as control-plane role split — SL must not be both residual-only workflow participant and free-form operator (see B16).

**Symptom / gap:** When the workflow is stuck, an **operator** can force packet fields, block thrash, retire agents, move held handoffs, etc. The **squad leader cannot** under residual-only rules. **Dashboard / SL requests** still wake and route to the **SL**.

**Why the SL is too constrained:**  
- Residual is the sole workflow driver; SL must not invent transitions.  
- Stuck states often residual as `wait` / `recover_agent` / capacity waits.  
- Well-behaved SL waits or reports — does not perform out-of-band surgery.

**Expected:** A **Troubleshooter** (persistent or on-demand), **outside the product FSM**:

| Property | Troubleshooter | Squad leader |
|----------|----------------|--------------|
| Part of story/pipeline FSM | **No** | Yes |
| Bound to residual-only for all work | **No** | Yes |
| May invent escape hatches / fix durable state | **Yes** when needed | No |
| May retire/block/force packet/theme fixes | **Yes** | Only if residual directs |
| Dashboard / debug requests | **Primary** | Product orchestration framing |
| Collaborates with SL | When legitimate workflow action is needed | Receives handoffs when appropriate |

**Solution direction:**  
1. Role `troubleshooter` (prompt + contract): repair swarm state; no product authoring workers own.  
2. Dashboard / SL-request wake → troubleshooter by default.  
3. Optional stuck-pattern alerts to troubleshooter.  
4. Keep SL residual-only for normal orchestration.  
5. Code fixes still land in product; troubleshooter is in-swarm operator, not a substitute for B16–B19.

**Where:** `squadd.clj` / `squadd/web.clj` request wake → SL; SL residual-only rules; no troubleshooter role.

**Repro:** Stuck thrash / held-finish — residual never offered the escape hatch; operator had to force packet/review state. SL following rules could not.

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

### B17 — Actions are shell strings, not structured ops

**From architecture review.** Actions are strings like `squad_assign.sh accept-merge …`, then `bash -c`. Quoting, env, and authority leak everywhere (held-path finish, paths with spaces, role env).

**Expected:** Actions are structured data, e.g. `{:op :accept-merge :assignment-id … :authority :daemon}`. Shell is rendered only at the outermost CLI boundary (or not at all for in-process executor).

**Solution direction:**  
1. Define a closed set of ops with required keys + authority.  
2. Planner emits ops; executor dispatches; renderer pretty-prints for humans.  
3. Migrate hot paths first (handoff steps, packet record, spawn request) without rewriting every script at once.

**Enables:** B16, B18, safer held/finish class fixes.  
**Where:** `squad_next.clj` candidates / `apply-candidate!`; handoff mechanical steps.

**Priority rationale (P2):** Foundational architecture. Land incrementally; do not block product delivery on a full rewrite.

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

## P3 — Product polish and deeper architecture

### B13 — Analyst dependency-checker policy is missing or pitifully coarse

**Symptom:** Product `dependency-checker.edn` is a minimal two-component sketch (`:main` / `:process`). Green checker while internal process graph is unconstrained.

**Who owns it:** Analyst should author a real policy from the theme module map.

**Expected:** Real components and allowed edges (UI → process → pure domain); analysis incomplete without non-trivial policy (or waiver).

**Where:** `analyst.prompt` / contract; `theme-module-map.md`; product `dependency-checker.edn`.

**Related:** B14.

---

### B14 — Theme package page should include dependency-checker.edn card

**Symptom:** Theme package view omits `dependency-checker.edn`.

**Expected:** Card next to module map / implementation order; clear missing state.

**Where:** `squadd/web.clj` theme package sections.  
**Related:** B13.

---

### B15 — Grok agent terminal window does not fill / scroll correctly

**Symptom:** Grok-backed agent windows show ~25 lines pinned at top; rest empty; scroll unusable. Codex panes do not show this.

**Expected:** Full geometry, usable scroll — or documented operator path if upstream TUI cannot.

**Where:** Grok launch / terminal adapters / tmux size; `swarmforge/docs/grok-agent-window-scroll.md`.

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

## Architecture north star (not a freeze)

Keep the **single-writer** direction. Prefer **typed actions + planner/executor**, then **durable readers/leases**, over more prompt text or one-off retries. Use architecture work (B16–B22) to stop the next class of control-plane defects.
