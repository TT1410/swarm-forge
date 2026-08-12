# Issues

Prioritized open issues. Priority is **impact on swarm correctness, operator unblock, and recurring defect classes** — not chronological discovery.

**How to read priority**
- **P1 — Fix before the next serious multi-story swarm.** (Clear for the 2026-08-12 stuck-swarm class.)
- **P2 — Important soon.** Live operator friction, hygiene, theme architecture gates.
- **P3 — When capacity allows.** Polish, deep architecture, large IA redesign.

---

## Index (open only)

| Pri | ID | Title | Kind | Area |
|-----|-----|--------|------|------|
| **P2** | B34 | Troubleshooter chat laggy — raw id-prefixed tmux inject | UX | Operator chat |
| **P2** | B10 | Dashboard answers truncate to first line | Reliability | Dashboard IO |
| **P2** | B29 | Stalled swarm not explained on dashboard | UX / design | Dashboard |
| **P2** | B11 | Zombie tmux sessions after agent retire | Hygiene | Lifecycle |
| **P2** | B12 | Hardener edits root tooling (`bb.edn`) | Policy | Role enforcement |
| **P2** | B25 | Order + dependency-checker need user approval | Workflow | Theme gates |
| **P2** | B14 | Theme package missing `dependency-checker.edn` card | UX | Dashboard |
| **P2** | B13 | Checker policy quality (beyond mere presence) | Product quality | Analysis |
| **P2** | B23 | Theme close / finalize undefined | Workflow | Theme lifecycle |
| **P2** | B18 | `squad_next` mixes plan / policy / present / execute | Architecture | Control plane |
| **P2** | B16 | Control-plane ownership not modeled | Architecture | Authority |
| **P2** | B19 | Scheduling priority is emergent | Architecture | Planner |
| **P3** | B35 | Squad product backlog registry (list / select / dispatch) | Product / UX | Operator intake |
| **P3** | B24 | Dashboard information architecture | UX / design | Dashboard |
| **P3** | B15 | Grok terminal window geometry / scroll | UX | Terminal |
| **P3** | B20 | Shared lease primitive missing | Architecture | Concurrency |
| **P3** | B21 | FSM transitions have hidden multi-file side effects | Architecture | Persistence |
| **P3** | B22 | Durable record formats informal | Architecture | State schema |

---

## Suggested fix order

1. **Operator chat + IO:** **B34** → **B10**  
   Snappy, correct Troubleshooter conversation (delivery + multiline answers).

2. **Operator visibility + hygiene:** **B29** → **B11** → **B12**  
   Know why the swarm is stuck; no session leaks; hardener doesn’t thrash root tooling.

3. **Theme architecture control:** **B25** → **B14** → **B13** → **B23**  
   Approve order/checker (now that analysis must produce them), show checker on theme package, raise policy quality, then theme finalize/ship.

4. **Control plane (after typed actions B17):** **B18** → **B16** → **B19**  
   Planner / executor / renderer; authority; single priority policy.

5. **P3 product intake / polish / deep arch:** **B35** (with B24), **B15**, then **B20** → **B21** → **B22**.

---

## Clusters

| Cluster | Bugs | Note |
|---------|------|------|
| Operator chat / dashboard IO | **B34**, **B10**, B29, B14, B24 | Latency, multiline, stall reason, theme cards, later IA |
| Product intake | **B35** | Durable backlog → dispatch as theme/story to SL or TS |
| Lifecycle hygiene | **B11**, B12 | Zombie sessions; hardener root-tooling |
| Theme / architecture gates | **B25**, **B14**, **B13**, B23 | Approve + display + quality + finalize |
| Control plane | **B18**, **B16**, **B19**, B20–B22 | Split `squad_next`; ownership; priority; leases |

Source notes for B16–B22: `architecture-improvements.md` (review findings folded in).

---

## Fixed (removed from open list)

| Set | IDs | Summary |
|-----|-----|---------|
| P0 | B01–B04 | Rework thrash, held handoff, impl-order gate, spawn HOL |
| prior P1 | B05–B08 | APS pipeline/templates, coverage, acceptance suite, safe `file-map` |
| P1 stuck-swarm | B26–B28, B30, B32, B33 | Terminal assignment deny-list, batch replace `batch_id`, merger slot, six-pack APS, mutator wiring |
| P2 first set | B31, B09, B17 | Hardener quality bar; Troubleshooter role + dashboard chat; typed actions |

**Partial progress (still open under related IDs):**
- Analysis must author `dependency-checker.edn` + `implementation-order.md` (prompt/contract); seed order when implementer-ready (B13/B25 remaining: quality + user approval).
- Theme package always shows Implementation Order (missing state); checker card still B14.
- Troubleshooter short wake + faster pending poll; B34 is the real delivery redesign.
- Troubleshooter not counted as active transient; product chat `route-to-sl` to SL.

---

## P2 — Operator, hygiene, theme, control plane

### B34 — Troubleshooter chat laggy; prefer raw id-prefixed tmux inject

**Symptom:** Operator ↔ Troubleshooter chat ~7–14s even for “hi”. Easy to misread as handoff lag. Path is durable request file + tmux wake + `squad_dashboard_request.sh answer`. Latency is mostly a **full coding-agent turn** after paste (long instructional wakes made it worse). Short wake + 400ms pending poll only trim margins.

**Not the problem:** Handoff outbox/inbox for operator chat. Do **not** put chat on the handoff protocol.

**Design (agreed, not implemented):**
1. Create durable pending request first (id, busy, cancel, history).
2. **Raw tmux inject** into `swarmforge-troubleshooter`: body prefixed with dashboard id (e.g. `[dashboard-…-001] hi`).
3. **Answer path unchanged:** `answer` / `route-to-sl` via helper; pane text alone does not complete.
4. Prompt: id-prefixed line ⇒ that request’s body; answer/route same id.

**Safety:** No inject without durable id; stable prefix; single-flight if mid-turn; product still `route-to-sl`; missing session falls back/queues.

**Expected:** Less ceremony and fewer tool steps. Not sub-second “hi” while Codex-in-tmux is the backend.

**Priority (P2):** Primary operator surface (B09); every message pays multi-second tax.

**Where:** `squadd/web.clj` wake/inject; Troubleshooter prompt; `squad_dashboard_request.clj`.

**Related:** B09, B10, B29, `route-to-sl`.

---

### B10 — Dashboard answers truncate to first line

**Architecture note:** Concrete instance of informal `key: value` records (B22). Fix the path now; formalize formats later.

**Symptom:** Multiline answer written and helper succeeds; **UI shows only the first line**.

**Cause:** Line-oriented parse keeps only first `response: …` line; no `pre-wrap` on render.

**Expected:** Full multiline round-trip write → read → UI.

**Direction:** Headers + body (or block scalar / sibling answer file); `pre-wrap` in UI; tests with blank lines and shell commands.

**Where:** `squad_dashboard_request.clj`; `squadd/web.clj`.

**Repro:** `dashboard-20260811T221429Z-001`.

---

### B29 — When the swarm is stalled, the dashboard should explain why

**Symptom:** Residual stuck for hours (merge_blocked, held handoffs, capacity deadlock) looks like ordinary busyness. Operators dig disk/logs by hand.

**Expected:** Operational “stalled” definition; agents/assignments with **state + short reason** (detail, merge-error, held note); not only `handoff_sent`. Align with B24 (needs-you outranks noise).

**Direction:** Status API `stalled?` / `stall_reason`; UI TBD (pill, hover, health strip). Prefer existing files over new ones.

**Priority (P2):** Visibility is part of unblocking; demoted from P1 after B28 (merge_blocked visible).

**Where:** `squadd/web.clj`; assignment/agent status; optional residual wait reason.

**Related:** B28 (fixed), B11, B24, B09.

---

### B11 — Zombie tmux sessions after agent retire

**Symptom:** Agent retired, worktree gone — **tmux session still listed**. Retire may claim session was not running.

**Cause:** Kill/detect mismatch (socket, race, liveness). Session leak, not held-handoff finish (B02 fixed).

**Expected:** Successful retire ⇒ session gone; status matches reality; optional squadd reconcile of orphan `swarmforge-*` sessions.

**Where:** `squad_retire.clj`; squadd reconciliation.

---

### B12 — Hardener edits root tooling (`bb.edn`) against role rules

**Symptom:** Hardener commits thrash root `bb.edn` (merge conflicts). Prompt says not to unless assignment requires it.

**Expected:** Denylisted root tooling untouched unless assignment says so; pre-handoff check or reject.

**Direction:** Stronger prompt/contract; check result diff; product layout with tasks under `bb/tasks/`.

**Where:** `hardener.prompt` / contract; merge path.

---

### B25 — Implementation order and dependency-checker must be user-approved

**Symptom:** Order and `dependency-checker.edn` can drive the swarm **without user approval**. Theme + module map already require approve; order/checker do not.

**Partial progress:** Analyst must author both; durable order required before implementers (seed if missing when implementer-ready). Still **no user gate**.

**Expected:** Dashboard approvals for non-empty order and non-trivial checker; enforce only when approved (or residual blocks); re-approve on material revision; theme package shows status (with B14).

**Where:** `squad_approval`; `squad_theme`; `squad_next`; theme package UI.

**Related:** B13, B14, B03 (fixed — durable order exists).

---

### B14 — Theme package should include dependency-checker.edn card

**Symptom:** Theme package shows scheme, module map, implementation order — **not** checker config.

**Expected:** Card next to map/order; clear missing state; approval badge when B25 lands.

**Where:** `squadd/web.clj` `theme-package-parts`.

**Related:** B13, B25.

---

### B13 — Dependency-checker policy quality (beyond presence)

**Symptom:** Checker often **absent** or a hollow two-node stub; green while process graph unconstrained.

**Partial progress:** Analyst **must** commit root `dependency-checker.edn` (template + SL review checklist). Still missing: residual gate if absent, quality bar beyond presence, user approval (B25).

**Expected:** Real components/edges from module map; incomplete analysis without non-trivial policy (or waiver); then B25 approve.

**Where:** `analyst.prompt` / contract; template; optional `squad_next` incomplete-analysis residual.

**Related:** B14, B25.

---

### B23 — Theme close / finalize is undefined

**Symptom:** No durable “theme shipped / slice accepted” while still allowing later stories. Residual may thrash with no goal when packets are done.

**Expected:** Finalize/ship approval for current slice; states `open` / `finalized` / re-open on new stories; residual idle or report when finalized; dashboard control.

**Where:** `squad_theme`; `squad_approval`; `squad_next`; dashboard; SL prompt.

---

### B18 — `squad_next` mixes planning, policy, presentation, and execution

**From architecture review.** One module candidates, applies, prints, and encodes daemon vs residual policy.

**Expected:** Planner (state → typed actions) · Executor (authority allow-lists) · Renderer (views).

**Depends on:** B17 (done). Pairs with B19.

**Where:** `squad_next.clj`.

---

### B16 — Control-plane ownership is implicit, not modeled

**From architecture review.** Daemon vs SL vs Troubleshooter ownership is env/prompt, not API.

**Expected:** Actions tagged with authority; residual cannot reach accept-merge etc.; Troubleshooter elevated set explicit (B09).

**Depends on:** B17 (done), B18.

**Where:** `squad_next.clj`, `squadd.clj`, assign/merge scripts.

---

### B19 — Scheduling priority is emergent

**From architecture review.** Bookkeeping, spawn, handoffs, dashboard, watchdogs each order themselves.

**Expected:** One planner priority policy; tests assert it.

**Depends on:** B17–B18.

**Where:** mechanical pass; spawn poll; residual vs operator.

---

## P3 — Product intake, polish, and deep architecture

### B35 — Squad product backlog registry (list / select / dispatch)

**Idea:** A **product backlog** owned by the swarm (not the meta SwarmForge defect list): durable items the operator can list, scroll, select, and **send as a theme or story** to the Squad Leader or the Troubleshooter.

**Why it fits:** Today product intent enters via free-form Troubleshooter chat (and `route-to-sl`). That works for ad hoc commands but does not give a stable “what’s next to build” surface, multi-item planning, or deliberate dispatch (theme vs story, SL vs TS). A registry is the **intake control plane**; theme packets and story FSM remain the **execution** plane.

**Suggested shape (design, not prescribed UI):**
1. **Durable store** under the project (e.g. `.squad/backlog/` or `.swarmforge/product-backlog/`) — id, title, body, kind intent (`theme` | `story` | `unspecified`), status (`open` | `dispatched` | `done` | `cancelled`), timestamps, optional links to `theme_id` / `story_id` / dashboard request id after send.
2. **Dashboard panel:** list + scroll + select; create/edit/archive; no requirement that items be formal Gherkin.
3. **Dispatch actions:**
   - **→ SL as theme / story:** create durable product request owned by SL (`route-to-sl` / product-owned dashboard request or a dedicated helper) with body = backlog item; SL residual orchestrates as today.
   - **→ Troubleshooter:** open chat request (B34 inject path) with body = item (repair/clarify/split) **or** “prepare this for SL.”
4. **One-way by default:** dispatch does not delete the item; mark `dispatched` and link outcomes so the registry stays the operator’s memory.

**Boundaries (keep sharp):**
- Not a second story FSM and not a replacement for `.squad/stories` packets.
- Not SwarmForge’s own engineering issues list (`issues.md` in the SwarmForge repo).
- Prefer reusing dashboard-request + owner + `route-to-sl` over inventing a third mailbox.
- Multiline bodies need B10 (or headers+body) so long specs survive.

**Depends on / pairs with:** B34 (snappy TS delivery), B10 (multiline), B09/`route-to-sl` (product ownership), B24 (IA — backlog is a first-class “Needs operator / Plan” region), B23 (theme lifecycle once items become themes).

**Priority rationale (P3):** High product value for multi-item / multi-theme work, but not required to unstick a single live swarm. Land after chat reliability (B34/B10) so dispatch feels usable; can start as files + thin UI before fancy prioritization.

**Where:** new backlog helper + durable dir; `squadd/web.clj` panel; dispatch via existing dashboard request / SL residual paths.

**Related:** B34, B10, B24, B09, B23.

---

### B24 — Dashboard information architecture

**Symptom:** Flat status dump; hard to scan “what needs me” vs noise.

**Expected:** Needs-operator first; theme-centric navigation; progressive disclosure; stable chrome. Coordinate B10, B14, B29, B23.

**Priority (P3):** Cognitive load, not a hard blocker. Avoid big redesign mid firefighting.

**Where:** `squadd/web.clj`.

---

### B15 — Grok agent terminal window geometry / scroll

**Symptom:** Grok panes ~25 lines pinned; scroll unusable. Codex OK.

**Expected:** Full geometry or documented operator path.

**Where:** Grok launch / tmux size; `swarmforge/docs/grok-agent-window-scroll.md`.

---

### B20 — Shared lease primitive missing

**From architecture review.** Merge, spawn, handoff each invent concurrency.

**Expected:** Small common lease module (token, TTL, acquire/release); per-resource use — not one mega-lock.

**Priority (P3):** After hot paths stabilize; after B17–B18 slices.

---

### B21 — FSM transitions have hidden multi-file side effects

**From architecture review.** e.g. accept-merge writes many records without one transition API.

**Expected:** Explicit before→after; one persistence layer per transition; tests assert full after-state.

**Depends on:** B17–B18; B22 helps shape.

---

### B22 — Durable record formats are informal

**From architecture review.** `key: value` until blank line; multiline (B10) and partial writes recur.

**Expected:** Headers+body for messages; EDN for structured state; append-only events; shared safe readers.

**Priority (P3) as a program:** Systemic. Concrete pain is P2 **B10**.

---

## Architecture north star

Keep the **single-writer** direction. Prefer **typed actions + planner/executor**, then **durable readers/leases**, over more prompt text or one-off retries.

**Status:** P0/P1 stuck-swarm class and first P2 set (B31, B09, B17) are done.  
**Next:** Operator chat/IO (**B34**, **B10**), visibility/hygiene (**B29**, **B11**, **B12**), theme gates (**B25**, **B14**, **B13**, **B23**), then control-plane split (**B18** / **B16** / **B19**).  
Theme finalize (**B23**) is acceptance of a slice, not permanent lockout of new stories. Dashboard IA (**B24**) and product backlog registry (**B35**) follow operator jobs (plan → dispatch → execute), not an uncurated status dump.
