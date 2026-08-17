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
| — | — | *(none)* | — | — |

---

## Suggested fix order

*(no open issues)*

---

## Clusters

| Cluster | Bugs | Note |
|---------|------|------|
| Packet repair / rework cycle | — | B85 analyst singleton closed |
| Operator chat / dashboard IO | — | B83/B84/B86 closed |
| Product intake | — | B85 closed |
| Control plane | — | residual order, YOLO, dirty soft-defer, QA fail gate closed |
| Lifecycle hygiene | — | B11/B12/B37/B38 done |
| Theme / architecture gates | — | B23 finalize + B25/B13/B14 done |
| Deep durable arch | — | B20–B22 + **B40** write-atomic/kv adoption |
| Terminal chrome | — | **B15**, **B41** done |

Former free-standing notes (`architecture-improvements.md`) are superseded: foundations landed as B16–B22; residual call-site migration **B40** done.

---

## Open detail

### B83 — Analyst WIF label: theme name (or story), not “Theme”

**Symptom:** Work-in-flight rows for **analyst** assignments often show the story column as **`Theme`** (or similarly unhelpful). Theme-scoped analysts store `story_id: theme` in metadata (`htw-analysis`, `htw-command-syntax-analysis`, etc.), and WIF currently surfaces that literal instead of a human label.

**Expected:**
1. If the assignment is **theme-scoped** (`story_id` / scope is `theme`): WIF story label = **theme name** — prefer display title from theme package / `theme.md` header when available, else `theme_id` (e.g. `htw` or “HTW”).
2. If the assignment is **single-story** (story-scoped analyst or story_id is a real story): WIF story label = **that story id** (or story title if cheap to resolve).
3. Never show the bare placeholder **`theme`** / **`Theme`** as the only label when `theme_id` is known.
4. Tooltip may still include assignment id; primary column should be scannable (which theme / which story).
5. Same rule for any other role that uses `story_id: theme` as a placeholder.

**Priority (P3):** Operator scanability on WIF.

**Where:** `squadd/web.clj` `work-in-flight-rows` label selection; optional theme title helper from `.squad/themes/<id>/`; `dashboard.html` if it overrides display.

**Related:** B46/B59 WIF columns; theme-scoped analyst create path; **B80** batch labels.

---

### B84 — WIF activity thermometer: six bars (not three)

**Today (B66):** each Work-in-flight row with a live agent shows a **three-bar** activity thermometer (heat 0–3 → idle / quiet / busy / hot).

**Expected:** Match SL observe resolution (**B65**): **six bars**, heat **0–6**.

1. Server `agent-pane-heat` (or equivalent) supports heat **0–6** with the same decay/inc idea as B66/B56 (unchanged pane → cool; changed → heat up; cap 6).
2. UI: six `.bar` elements on `.wif-therm`; light 1…n bars by heat; keep compact WIF sizing (bars may be slightly narrower than SL if needed).
3. Level labels align with B65 where useful (e.g. idle → quiet → warm → busy → brisk → hot → max); tooltip still includes level + agent id.
4. Observe only — no inject; only rows with a live agent/session.

**Priority (P3):** Visual parity with SL thermometer; finer glance signal on WIF.

**Where:** `squadd/web.clj` `agent-pane-heat` heat cap/level map; `squadd/dashboard.html` `.wif-therm` markup (three → six bars) + CSS nth-child rules.

**Related:** **B66** three-bar WIF therm (supersede resolution); **B65** SL six-bar therm.

---

### B85 — Analyst is a singleton template (no parallel theme analysts)

**Policy:** **`analyst` is a singleton** — at most one active analyst at a time (same class as hardener / qa / architect / merger).

**Symptom (live htw):** Two theme-scoped analysts ran in parallel (`htw-analysis` + `htw-command-syntax-analysis`). First merged `dependency-checker.edn` + `implementation-order.md`; second handoff **`merge_blocked`** with add/add conflicts on those files. Operator can misread this as “story approval blocked the other analyst,” but the root cause is **parallel analysts both owning root architecture artifacts**.

**Today:**
- `squad.conf`: `max_active_template analyst 3`
- `squad_next` `singleton-templates` = `#{hardener qa architect merger}` — **analyst omitted**
- Spawn can run multiple analysts concurrently; residual may create/spawn a second while the first is still active

**Expected:**
1. **`max_active_template analyst 1`** in `squad.conf` (and comments: singletons stay at 1).
2. **`singleton-templates` includes `"analyst"`** in residual/spawn capacity (`squad_next` and any squadd spawn gates that mirror it).
3. Pending second analyst spawn defers with `template-capacity-full:analyst` until the first retires / handoff completes and capacity frees.
4. Tests: one active analyst + pending analyst spawn → capacity full; after first retired, spawn proceeds.
5. Optional: residual does not *create* a second analyst assignment while one is open (or creates but does not spawn until free) — at minimum spawn must enforce singleton.

**Priority (P1):** Prevents false merge_blocked thrash and corrupted dual order/checker writes on multi-story intake.

**Where:** `swarmforge/squad.conf`; `squad_next.clj` `singleton-templates` / `spawn-capacity?`; `squadd.clj` template caps if separate; spawn tests.

**Related:** live dual-analyst conflict; **B70** mid-theme deps; **B73** singleton capacity parity pattern.

---

### B86 — Every session window scrolls to bottom on open (agent / SL / TS)

**Policy:** When opening **any** session window — **Open SL**, **Open TS**, WIF agent open, `/agent/<id>` — the pane mirror must **scroll to the bottom** immediately after open so the latest output is visible.

**Today:** **B69** / **B74** targeted agent-pane stick-to-bottom and open-at-end; still can land mid-history or fail for SL/TS if first paint / popup path differs. Operators expect one rule for all roles.

**Expected:**
1. **On first open / focus of a session window** (new popup or reusing existing): after first pane content is painted, **force `scrollTop = scrollHeight`** (or equivalent).
2. Applies uniformly to **squad-leader**, **troubleshooter**, and **all transient** agent sessions.
3. After open, keep **B74** behavior: stay at bottom when already near end; preserve position if the operator scrolls up.
4. Cover both dashboard `openAgentWindow(...)` paths and direct `/agent/<id>` loads.
5. Tests or manual checklist: Open SL, Open TS, open implementer from WIF — each lands at end of capture.

**Priority (P3):** Operator chrome consistency.

**Where:** `squadd/web.clj` `pane-page` first-paint scroll; `squadd/dashboard.html` `openAgentWindow` if it needs a post-load hook; any SL/TS-specific pane wrappers.

**Related:** **B69** scroll to end on open; **B74** stick when at bottom; **B41** SL/TS window-invisible; Open SL/TS controls.

---

## Fixed (removed from open list)

| Set | IDs | Summary |
|-----|-----|---------|
| P0 | B01–B04 | Rework thrash, held handoff, impl-order gate, spawn HOL |
| prior P1 | B05–B08 | APS pipeline/templates, coverage, acceptance suite, safe `file-map` |
| P1 stuck-swarm | B26–B28, B30, B32, B33 | Terminal assignment deny-list, batch replace `batch_id`, merger slot, six-pack APS, mutator wiring |
| P2 first set | B31, B09, B17 | Hardener quality bar; Troubleshooter role + dashboard chat; typed actions |
| P2 operator chat batch | **B34**, **B10** | Id-prefixed raw tmux inject; multiline dashboard body/response (`key: \|` blocks + `pre-wrap`) |
| P2 lifecycle batch | **B37**, **B11** | Dashboard Teardown + confirm; exact/force session kill; squadd orphan session reconcile |
| P2 visibility batch | **B29**, **B36** | Stall strip + stalled pills; TS `note` progress sidecar + chat UI |
| P2 hardener + theme card | **B12**, **B14** | Root tooling denylist + handoff reject; dependency-checker theme package card |
| P2 theme architecture gates | **B13**, **B25** | Checker quality residual + implementer hard-gate; order/checker user approval + fingerprints + theme package status |
| P1 agent death repair | **B38** | `session_dead` + residual `repair_dead_agent`; `squad_recover.sh repair` requeues task; SL vs TS owner |
| P2 theme finalize | **B23** | Theme lifecycle open/finalized; finalize gate; residual idle; re-open on new story |
| P2 control plane | **B18**, **B16**, **B19** | `squad_control_plane` priority policy; authority allow-lists; executor/renderer modules; residual class selection |
| P3 deep architecture | **B20**, **B21**, **B22** | `squad_lease`; `squad_transition` accept-merge; `squad_records` kv/headers+body/edn/events |
| P1 post-rework packet repair | **B39** | Residual does not re-record superseded cleaner/CR after impl clear-downstream; iterations gate + missing review target |
| P3 dashboard cockpit | **B24**, **B35** | Combined Board+Ops UI; durable `.squad/backlog` + approve-for-analysis → SL |
| P3 terminal + arch close | **B15**, **B41**, **B40** | Grok minimal/no-alt-screen + pane capture; SL/TS window-invisible; records call-sites |
| P1 product intake | **B53**, **B55** | Backlog body survives B10 re-parse; residual BODY_PREVIEW + refuse false empty-body answers |
| P2 TS chat / stall policy | **B52**, **B63** | Chat scroll preserve; stalls only when TS needed (no recoverable merge_blocked) |
| P3 dashboard polish batch | **B42**–**B51**, **B54**, **B44**, **B56**–**B62**, **B64** | Theme/agent links; sorts; Finalizing/Specifying columns; glow; buttons; therm; remove Live agents; WIF icons/width; hold outline; splitter stable |
| P1–P3 issue closeout | **B65**–**B82** (all) | Residual/dashboard priority; merger B27; dirt soft-defer; SL/TS YOLO; QA fail gate; senior-impl findings-first; WIF highlight+therm; reject→backlog; residual header; no double vision; TS Enter-only; mockup footer; stage labels; agent pane stick; SL six-bar therm; mid-theme analyst prompt |
| P3 WIF therm | **B66** | Per-agent three-bar pane activity on WIF rows |
| P1–P3 follow-up | **B83**–**B86** | Analyst singleton; WIF theme labels; WIF six-bar therm; session open scrolls to bottom |


---

## P3 — SL observe polish

### B65 — SL activity thermometer: six bars (not three)

**Today (B56):** three bars, heat 0–3 → idle / quiet / busy / hot.

**Expected:** Extend the thermometer to **six bars** for finer resolution of SL pane activity.

1. Server heat scale (or equivalent) supports **0–6** (or map continuously onto six lit bars).
2. UI: six `.bar` elements; light 1…n bars by heat/level; keep observe-only semantics (pane capture hash rate, no SL tasks).
3. Tooltip still reports level + session live/missing; optional finer labels if useful (e.g. idle → quiet → warm → busy → hot → max).
4. Decay/inc on unchanged/changed pane samples same idea as B56; just more steps.

**Priority (P3):** Visual resolution only.

**Where:** `squadd/web.clj` `sl-activity` heat cap/level map; `squadd/dashboard.html` `#sl-therm` markup + CSS.

**Related:** **B56** SL activity thermometer; **B66** per-agent WIF therm.

---

### B66 — WIF row: three-bar thermometer for that agent’s pane activity

**Goal:** On each **active** Work-in-flight row that has a live agent, show a **three-bar** activity thermometer measuring **that agent’s** tmux pane motion (same idea as SL therm **B56**, but per worker).

**Expected:**
1. For WIF rows with `agent_id` / session: sample that agent’s pane (`capture-pane` short tail), hash vs previous sample, heat 0–3 with decay (unchanged → cool; changed → heat up).
2. Render **three bars** on the row (compact; next to state icon or age). Tooltip: level + agent id (e.g. `busy · implementer-001`).
3. Rows **without** a live agent/session: no therm, or empty/grey bars (no capture).
4. Observe only — no inject, no status chores for agents.
5. Performance: prefer batching captures in `web-state` (or light poll path); cap cost (short `-S`, only non-retired / WIF agents). Optional shared heat map by agent_id across polls.
6. Do not replace **B61** state icons; therm is *pane activity*, icon is *lifecycle state*.

**Priority (P3):** Operator glance “who is thrashing” on WIF.

**Where:** `squadd/web.clj` (agent activity map on `work_in_flight` rows or `agents`); `squadd/dashboard.html` `renderWork` + small bar CSS (reuse SL bar styles at 3).

**Related:** **B56** / **B65** SL thermometer; **B61** WIF state icons; **B43** open agent; **B58** Live agents removed.

---

### B67 — Story card stage labels: written / approved / in-process

**Today:** Early packet states all show the same card pill **`specified`**:

| Packet `state` | Current `stage_label` |
|----------------|------------------------|
| `story_recorded` | specified |
| `story_approved` | specified |
| `specification_in_progress` | specified |

**Expected:** Distinct pills for Specifying-phase stories:

| Packet `state` | New `stage_label` |
|----------------|-------------------|
| `story_recorded` | **written** |
| `story_approved` | **approved** |
| `specification_in_progress` | **in-process** |

Update `stage-labels` in `squadd/web.clj` (and any tests/docs that assert “specified” as the early-stage pill). Board column name remains **Specifying** (**B62**); this is only the **card badge** text.

**Priority (P3):** Card readability.

**Where:** `squadd/web.clj` `stage-labels` / `stage-label`; optional `ui-design.md` if stage vocabulary is listed.

**Related:** **B62** Specifying column; Board story cards.

---

## P1 — Residual priority / product intake

### B68 — Dashboard requests outrank spawn-wait in residual

**Policy:** **Answering dashboard requests is high priority** — operator product work (backlog approve → SL request, TS chat routed to SL, etc.) must not sit behind routine spawn-queue waiting.

**Today:** `squad_control_plane/residual-class-order` places **`:pending-spawn` before `:dashboard-request`**. Live symptom: backlog item **command syntax** created pending request `dashboard-20260817T170850Z-001` with full body, but residual kept returning `wait_for_spawn` while capacity/spawn queue was full — product request stayed pending indefinitely until spawn pressure eases.

**Expected:**
1. **`:dashboard-request` ranks above `:pending-spawn`** (and generally above mechanical capacity waits that only delay workers). Keep true urgency above it only if justified (e.g. finish-in-process handoff, stale lock) — document final order.
2. Comment in control plane / `squad_next` matches code: operator product residual is high priority (comment already claims it beats story FSM; it must also beat spawn-wait).
3. Tests: with both a pending SL dashboard request and a pending spawn file, residual selects **`answer_dashboard_request`**, not `wait_for_spawn`.
4. Optional: TS-owned pending chat also not starved by spawn (if residual surfaces TS at all; front door may stay wake-based).

**Priority (P1):** Blocks product intake while swarms are busy spawning — same operator pain as “approve backlog, nothing happens.”

**Where:** `squad_control_plane.clj` `residual-class-order`; `squad_next.clj` residual selection / tests; any docs on residual priority.

**Related:** B35 backlog approve; **B55** body must still be read; B18 residual policy; live command-syntax pending behind spawn queue; **B71** header residual; **B73** spawn capacity vs residual.

---

### B73 — Squadd merger spawn ignores B27 (handoff_sent + merge_blocked still fills singleton)

**Symptom (live htw):** After `merger-001` finished with `handoff_sent` while assignment `domain-cave-state-cleaner-merge` is **`merge_blocked`**, residual/B27 treats the merger singleton as free so a deeper recovery merger can start. Spawn request sits in **`spawn-requests/new/`** for `domain-cave-state-cleaner-merge-merge`, residual reports `wait_for_spawn`, but squadd logs:

```text
spawn-request-deferred … template-capacity-full:merger
```

and never dequeues the request (while other templates still spawn).

**Root cause:** **`squad_next`** implements B27 via `merger-holds-capacity-slot?` — merger in `handoff_sent` whose task assignment is `merge_blocked` does **not** hold the singleton. **`squadd`** `template-capacity-full?` / `active-template-count` counts any **active** role with template `merger` (including that handoff_sent case). Spawn gate ≠ residual capacity.

**Expected:**
1. Squadd merger (and any singleton) capacity uses the **same B27 rule** as residual: do not count merger agents that are only `handoff_sent` with assignment `merge_blocked`.
2. When slot is free under that rule, process pending `spawn-requests/new/*_merger_*` without false `template-capacity-full:merger`.
3. Tests: merger agent handoff_sent + merge_blocked assignment + pending merger spawn → spawn proceeds (or capacity blocker is nil).
4. Prefer shared helper (or call into same policy) so residual and daemon cannot diverge again.

**Priority (P1):** Blocks merge recovery chain; merge_blocked stacks while spawn forever defers.

**Where:** `squadd.clj` `active-template-count` / `template-active-role?` / `spawn-capacity-blocker`; mirror `squad_next.clj` `merger-holds-capacity-slot?` / `capacity-active-template?`; spawn queue processing.

**Related:** B27 merger singleton recovery; B28 merger slot; live dirty-checkout merge chain; **B63** (merge_blocked not Attention stall — recovery must still run); **B75** (dirt must not become merge_blocked/merger in the first place).

---

### B75 — Transient main dirt soft-defers accept-merge (no merger)

**Symptom (live htw, 2026-08-17):** Main checkout had **tracked** dirt for a short window. First hit: `domain-cave-state-cleaner` (`cleaner-001`) — dry-run **passed** (`merge_ready` / dry-run merge passed), then **`accept-merge`** set `merge_blocked` with detail **`tracked checkout dirty`**. Six more assignments hit the same gate within ~10 minutes. Dirt later **cleared** (main clean for tracked files), but all seven stayed `merge_blocked`, handoffs held, and recovery kicked **merger** depth (then stuck on **B73**). Dirt was **transient**; the pipeline clog was permanent.

**Root cause / design mismatch:**
1. **Dry-run** intentionally uses an **isolated** merge-check worktree and answers only “does this commit merge into HEAD?” — it does **not** (and should not) inspect main’s working tree.
2. **`accept-merge`** correctly refuses to mutate a dirty main via `tracked-dirty?`, but treats that like a **content** merge failure: writes **`merge_blocked`**, parks handoffs, and drives **merger recovery** — the wrong tool for “main is busy/dirty right now.”

**Policy decision:**
- Dry-run **does not** detect dirt and must **not** gain a dirt check (keep mergeability separate from main safety).
- Dirt is detected only when about to touch **main** (`accept-merge` or equivalent).
- **Transient tracked dirt → soft-defer + requeue**, not permanent `merge_blocked` / merger spawn.

**Expected:**
1. On `tracked-dirty?` during accept-merge: **do not** write `merge_blocked` with detail that feeds the merger path; leave assignment **`merge_ready`** (or a short-lived deferred state that residual still treats as “ready to accept when main is clean”).
2. Residual / daemon **retries accept-merge** when main is clean (put work back on the queue rather than escalating).
3. Optional operator visibility: residual/status can say main dirty / merge deferred — **not** Attention stall for recoverable dirt (**B63** family).
4. True dry-run conflicts and true accept-merge content failures still use `merge_blocked` → merger / rework as today.
5. Tests: accept-merge with dirty main → assignment remains merge_ready (or deferred), no merger spawn residual; after clean, accept succeeds without merger assignment.

**Priority (P1):** Transient editor/tool dirt should not clog the whole merge pipeline or spawn false recovery chains.

**Where:** `squad_assign.clj` `accept-merge!` / `tracked-dirty?` / `block-merge!`; residual retry for merge_ready waiting on clean main; avoid merger residual on dirt detail; tests in assign-merge / residual suites.

**Related:** live dirty-checkout chain; **B73** (recovery spawn after false escalation); **B63** (merge_blocked not Attention); dry-run isolation (`with-merge-check-worktree`); **B76** (TS cannot auto-repair while stuck on Codex approval prompts).

---

### B76 — SL and TS must launch YOLO (no shell approval prompts)

**Policy:** **Squad Leader and Troubleshooter must run fully auto-approved (YOLO)** for shell/tool execution — same class of trust as transient workers. They are the in-swarm operators; backend approval prompts are not a product control plane and must not stall residual or repair work waiting on a human at a hidden pane.

**Symptom (live htw, 2026-08-17):** Transients spawn as:

```text
codex --dangerously-bypass-approvals-and-sandbox …
```

Persistent SL/TS launch as plain:

```text
codex -C <project-root> "…"
```

with **no** bypass flags (`roles.tsv` has no YOLO extra-args). Live TS pane escalated repeatedly with Codex **“Would you like to run the following command?”** while trying to unclog merge_blocked dirt recovery — only partial “always allow prefix” approvals; novel command shapes still blocked. Operator expected YOLO because TS is `:elevated-ops` repair agent; backend was not.

**Root cause:** Launch split:
1. **`squad_spawn.clj` `codex-command`** always passes `--dangerously-bypass-approvals-and-sandbox` for transients.
2. **`swarmforge.clj` `codex-launch-command`** for persistent roles only inserts optional `extra-args` from the roles row — default is empty → interactive Codex approvals.
3. Grok path has explicit YOLO/`--always-approve` → `bypassPermissions` via `extra-args`; Codex persistent path has no equivalent default.

**Expected:**
1. **SL and TS always launch YOLO** for the configured backend:
   - **Codex:** same dangerous bypass as spawn (`--dangerously-bypass-approvals-and-sandbox`), or documented equivalent full auto.
   - **Grok:** `bypassPermissions` / YOLO (not merely `acceptEdits`) by default for SL/TS.
   - **Claude / Copilot:** equivalent full-skip-approvals flags if those backends are used for SL/TS.
2. Default must **not** depend on operators remembering `--yolo` in `roles.tsv` / window rows (optional extra-args may still add other flags, but cannot leave SL/TS non-YOLO by omission).
3. Tests: persistent launch command for SL and TS includes the YOLO/bypass flags; transient spawn still includes them; no regression that turns workers off YOLO.
4. Docs/README: state that SL/TS are YOLO by design (in-swarm operators); product gates remain durable approvals (`squad_approval.sh`), not agent CLI prompts.

**Priority (P1):** Silent stall of residual/repair on invisible Codex prompts; blocks operator-driven unclog and any SL residual that needs new shell shapes.

**Where:** `swarmforge.clj` `codex-launch-command` / `agent-launchers` / `grok-permission-prefix` (default YOLO for SL+TS); optional centralize shared Codex YOLO string with `squad_spawn.clj`; launcher tests; roles.tsv docs if needed.

**Related:** live TS stuck mid-repair; **B75** dirt recovery needs unattended accept/merge-ready; **B41** SL/TS window-invisible (approvals even worse when pane is hidden); transient YOLO already correct in spawn.

---

### B71 — Status bar: true residual + product-pending badge

**Decision (option B):** Header must not show only the cheap heuristic that maps “any pending SL request → `answer_dashboard_request`.” That misleads when real residual is `process_handoff` / `wait_for_spawn` while a product request still sits pending.

**Expected:**
1. **Primary:** show **true residual** next action (same family as `squad_next.sh --residual-only` / SL residual): e.g. `residual: process_handoff`, `residual: answer_dashboard_request`, `residual: wait`.
2. **Secondary badge** when a SL-owned (or product) dashboard request is pending: e.g. `product: pending` / short title or id (`command syntax`, `dashboard-…-001`) — even if residual is something else.
3. Example shapes:
   - `… · residual: process_handoff · product: command syntax`
   - `… · residual: answer_dashboard_request · dashboard-20260817T170850Z-001`
4. Implementation: prefer **residual snapshot** written by squadd when it already runs residual/mechanical (cheap poll), or slower residual-only cadence; avoid full residual every 4s if too heavy. Thermometer (**B56**/B65) stays pane-heat only.
5. Retire or demote standalone `dashboard-next-action` heuristic as the sole `next:` label.

**Priority (P2):** Operator trust in status bar; clarifies product pending vs swarm residual.

**Where:** `squadd/web.clj` `web-state` / `dashboard-next-action`; optional daemon residual snapshot; `squadd/dashboard.html` header `#meta`.

**Related:** **B51** title bar next_action (heuristic); **B68** residual priority; live mismatch command-syntax pending while residual was handoffs.

---

### B69 — Agent session window: scroll to end on open

**Symptom:** Opening an agent session pane (Open SL / Open TS / WIF agent / `/agent/<id>`) often lands mid-scroll or at the top of capture history, so the operator does not see the **latest** output without scrolling down.

**Expected:** When an agent session window is **first brought up**, immediately **scroll to the end** (stick to bottom of the pane mirror). After that, keep existing stick/unpinned behavior if the operator scrolls up (same idea as B52 / pane “New output”).

**Priority (P3):** Operator chrome; first paint of agent windows.

**Where:** Agent pane HTML/JS served from `squadd/web.clj` (agent page + pane poll); any `openAgentWindow` popup path; ensure initial load and first content paint set `scrollTop = scrollHeight` (or equivalent).

**Related:** B15 pane capture; agent pane stick-to-bottom; Open SL/TS; **B43** WIF open agent; **B74** follow new lines when at bottom.

---

### B74 — Agent pane: stick to bottom when already at last line

**Symptom:** In an agent session window (`/agent/<id>`, Open SL/TS), when the viewport is **already showing the last line** (scrolled to bottom), new pane capture lines can leave the view **pinned** to the old position so the latest output is off-screen — operator must scroll again.

**Expected:**
1. If the operator is **at (or near) the bottom** when new content arrives, **do not hold** the previous scroll offset — **force scroll to the new bottom** so new lines stay visible.
2. If the operator has **scrolled up** to read history, preserve position (same unpin pattern as TS chat **B52** / existing “New output” affordance).
3. Near-bottom threshold should be generous enough that small growth doesn’t leave a stuck half-line (e.g. within ~1–2 viewport lines of end).
4. Complements **B69** (initial open → end): B69 is first paint; B74 is ongoing updates while already at end.

**Priority (P3):** Agent pane follow mode.

**Where:** Agent pane page JS in `squadd/web.clj` (pane poll / `textContent` update / stickBottom / nearBottom logic).

**Related:** **B69** scroll to end on open; B15 pane capture; B52 TS chat scroll preserve when *not* at bottom.

---

### B77 — No double vision: batch stack hides member story cards

**Symptom (live board):** In **Finalizing**, an active **hardener batch** appears as a batch stack card **and** each member story also appears as its own free-floating card in the same lane. Operator sees redundant cards for the same work.

**Design (`ui-design.md` Batches):**
- Multi-story hardener / QA / architecture is a **clipped stack** (one face card + peeks), not N independent cards.
- **No double vision:** members of an **active** batch must **not** also appear as free-floating cards in the same column.
- Member names: **hover/focus popup** on the stack (not sibling story cards).
- **Batch of 1:** normal single card (optional tiny clip).
- **Done:** prefer **dissolve** the stack into individual Done cards when the batch completes.

**Today:** `renderBoard` in `dashboard.html` paints batch cards (hardener/qa/architect → finalizing) **and** all stories with `board_column === finalizing` without excluding active batch members.

**Expected:**
1. While a story is a **member of an active batch** (open/in_progress/running/etc. hardener/QA/arch batch from `.squad/batches` + packet batch fields), **omit** that story’s free-floating board card from the column that shows the stack.
2. Keep the **batch stack** as the sole board representation for that group (click → batch detail; hover → member list).
3. When the batch completes / dissolves, member stories appear again as normal cards in the appropriate column (typically Done).
4. If a story leaves the batch for **solo rework**, show it as a free card again (stack may remain for remaining members or update count).
5. Same rule for Coding if any non-finalizing batches are shown there; no double vision in any lane.
6. Tests or mock: active hardener ×2 → Finalizing has one stack, not stack + two story cards.

**Priority (P3):** Board readability; design already specifies this.

**Where:** `squadd/dashboard.html` `renderBoard` (filter `colStories` by active batch membership); optional server-side `batched` flag on story rows in `squadd/web.clj`; `ui-design.md` batches section (already correct).

**Related:** B62 Finalizing column; batch hover menus; WIF batch rows (may still list batch as one row — OK).

---

### B78 — Remove ui-design/mockup footer from live dashboard

**Symptom:** Live combined cockpit still shows operator-facing chrome like:

```text
ui-design.md · mockup: dashboard-mockup.html
```

That is prototype scaffolding, not product UI. The dashboard is the real surface now.

**Expected:**
1. Remove the visible footer/status line that cites `ui-design.md` and `dashboard-mockup.html` from `squadd/dashboard.html` (and any similar operator-visible strings).
2. Dev/docs references may remain in code comments, `ui-design.md`, or README — just not on the live board chrome.
3. No empty placeholder band left behind; layout still clean.

**Priority (P3):** Polish; confuses “live” vs “mockup.”

**Where:** `swarmforge/scripts/squadd/dashboard.html` (e.g. footer `<span>ui-design.md · mockup: …</span>`); glance for other mockup badges in the live page.

**Related:** B24 cockpit; `ui-design.md` / `dashboard-mockup.html` remain design references off-screen.

---

### B79 — Batch QA failure must not auto-approve / vanish from Attention

**Symptom (live htw, 2026-08-17):** Batch QA assignment **`htw-qa`** (`qa-016`, member **`room-perception`**) handed off commit **`ffbc76b`** (“Record HTW batch QA failure”) with `qa/htw-qa-report.md` decision **Failed final batch QA** (missing accepted UI/IO scope / acceptance coverage gap). Operator saw a **blocker / fail flash** on the dashboard, then it **disappeared**. Durable state afterward:

- assignment **merged** (`88a767a`)
- packet **`qa_recorded` → `qa_approved`** with **`auto-approved-by-config`**
- batch **`htw-qa` complete**; story advanced to **`htw-architecture`**
- **no** lasting `.squad/blockers/` entry; agent retired

**Root cause (policy + mechanical):**
1. `squad.conf` has **`approval_required qa false`** — any successful record/merge path auto-approves QA.
2. QA “failure” was delivered as a normal **git_handoff** + report artifact, not as a structured fail outcome that drives **`qa_returned`**, durable blocker, or Attention-only hold.
3. Merge of the report was treated as success; Attention cleared once state became `qa_approved` / batch complete.

**Expected:**
1. **Structured QA outcome** on the assignment/packet (pass | fail | blocked), not only free-text in a markdown report. Prefer handoff headers / result fields the control plane can parse (e.g. `qa_result: failed` + reason).
2. On **fail:** do **not** set `qa_approved` / do **not** auto-advance to architecture (or next batch) while fail is outstanding.
3. On **fail:** durable operator-visible state — e.g. packet `qa_returned` / `qa_failed`, assignment non-terminal fail, and/or `.squad/blockers/` (or Attention item that stays until resolved). Flash-only is not enough.
4. On **fail:** residual path for repair (rework implementers / re-run QA batch / SL-TS recovery) — not silent promote.
5. On **pass:** current auto-approve path may remain when `approval_required qa false`.
6. Optional: scope QA verdict to **batch members** (report may note theme gaps, but fail vs pass for *this* batch must be explicit so theme-wide commentary does not falsely fail a single-story batch — or theme-wide fail must be an explicit policy).
7. Tests: QA handoff with fail outcome → no `qa_approved`; Attention/blocker present; pass outcome → record + optional auto-approve as config.

**Priority (P1):** False green on finalizing path; operator loses the only signal of product QA failure.

**Where:** QA role/template + handoff shape; `squad_packet` / residual QA record + approve transitions; `squad.conf` `approval_required qa` interaction; Attention/blocker read model in `squadd/web.clj`; live evidence `qa/htw-qa-report.md`, assignment `htw-qa`, packet `room-perception`.

**Related:** hardener/QA/architecture batch FSM; **B63** stall policy (fail must not be invisible); finalizing board (**B77** batch cards).

---

### B80 — WIF hover: senior-implementer / -fix batches highlight no stories

**Symptom (live htw):** **Senior implementer** running (`senior-implementer-001` / assignment `htw-architecture-fix`). Hovering the **Work in flight** row does **not** highlight any Board story cards (and batch member popup is empty / useless).

**Root cause:**
1. Assignment metadata: `story_id: batch`, `batch_id: htw-architecture-fix`, `template: senior-implementer`.
2. Member stories live under batch **`htw-architecture`** (manifest e.g. `room-perception`), not under `htw-architecture-fix` (no such batch dir / empty members).
3. `work-in-flight-rows` resolves members only via `batches[assignment_id]` or `batches[batch_id]`. Miss → `members=[]`, `is_batch=false`, `story_ids=["batch"]` (literal story_id).
4. Dashboard hover uses `story_ids` / batch members to `setHl` on `.card[data-story=…]` — no card has id `batch`, and wrong/missing `batch_id` does not light the architecture stack.

Same class of bug for any **rework suffix batch id** (`*-architecture-fix`, `*-r2`, etc.) that does not share a manifest with the parent batch.

**Expected:**
1. WIF rows for **batch-scoped** roles (hardener, qa, architect, senior-implementer, …) always expose **real member story ids** for highlight + member popup.
2. Resolve members by: assignment `batch_id` manifest, else parent/lineage batch (e.g. strip `-fix` / rework suffix, or `merge_for` / architecture-return linkage), else packet fields naming the active batch.
3. Never use literal `story_id: batch` as a highlight target; treat `batch` / blank as “needs member resolution.”
4. Hover WIF senior-implementer (or architect batch) → board highlights those story cards **and/or** the batch stack (consistent with **B77** no double vision — prefer stack hl when members are only in a stack).
5. Tests: assignment `htw-architecture-fix` + batch `htw-architecture` with members → WIF row `story_ids` includes those members, `is_batch` true (or equivalent), hover path has non-empty ids.

**Priority (P2):** Operator cannot see what a batch worker is touching from WIF.

**Where:** `squadd/web.clj` `work-in-flight-rows` / `batches-enriched` / member resolution; optional create path for senior-implementer `batch_id` in `squad_next` (`-architecture-fix` suffix ~2248); `dashboard.html` WIF hover `setHl` (batch-wif mode).

**Related:** B32 batch_id; architecture → senior-implementer residual; **B77** batch stack vs story cards; B43 WIF open agent.

---

### B81 — Troubleshooter chat: drop Send button (Enter only)

**Policy / UX:** Troubleshooter dashboard chat does **not** need a **Send** button. **Enter** already sends (Shift+Enter for newline). The extra control is clutter.

**Expected:**
1. Remove the Send button from the TS composer in the live cockpit.
2. Keep **Enter** = send, **Shift+Enter** = newline (existing behavior).
3. Optional: short composer hint/placeholder e.g. `Enter to send · Shift+Enter for newline` if operators need a cue.
4. Layout: composer uses the full width of the former button+field row without an empty button hole.

**Priority (P3):** Chrome simplification.

**Where:** `squadd/dashboard.html` TS composer markup/CSS/JS (Send click handler can go; keydown Enter path stays).

**Related:** B10 multiline; B52 chat scroll; ui-design TS composer notes.

---

### B82 — Senior-implementer assignment dumps theme, not architecture findings

**Symptom (live htw):** `senior-implementer-001` on **`htw-architecture-fix`** was spawned after architect **changes-requested** (`reviews/htw-architecture-review.md`: add **ui/io adapters** + acceptance for outer features; **preserve** clean inner process modules). Agent status stayed “reading architecture review…,” worktree had **no product commits**, but pane monologue planned a **greenfield rebuild** (recreate domain/cave graph, full HTW implementation) instead of applying the critique.

**Root cause (prompt / assignment packaging):**  
`htw-architecture-fix/assignment.md` is largely a **full theme dump** (scope, fidelity, module map, theme text) plus a vague “Produce the required artifact for batch.” It does **not** lead with:

- the architecture **decision** (`changes-requested`)
- the **findings list** as the work order
- explicit **do not rewrite** accepted process/domain code unless a finding says so

The **role** prompt (`senior-implementer.prompt`) already says implement only assigned architectural recommendations and preserve accepted behavior — but the **assignment package overwhelms** that with greenfield theme context, so the model treats the job as “build HTW.”

**Expected:**
1. **Architecture-fix assignment body** is critique-first:
   - Link/path to the architecture review artifact (and embed or quote findings).
   - Ordered work items derived from findings only.
   - Explicit non-goals: do not reimplement stories/modules that the review marked healthy; do not re-author theme/stories/Gherkin.
2. Theme / module map / packet context may be **secondary appendix** (or paths only), not the first screen of instructions.
3. Role template reinforces: if assignment and review conflict with theme narrative, **review + findings win**.
4. Create/replace path for `senior-implementer` / `*-architecture-fix` batch uses a dedicated instructions template (not the same bulk package as first-pass implementer/analyst).
5. Tests or fixture: generated `*-architecture-fix` assignment contains review path + “changes-requested” / findings before any full theme paste; optional negative: does not open with greenfield “Build a faithful…” as the primary task.

**Priority (P1):** Senior-impl can thrash or rewrite a working core after a correct architecture review.

**Where:** assignment instruction generation for senior-implementer / architecture-fix (`squad_next` / `squad_assign` create-merger-style batch create, theme assignment templates); `swarmforge/role-templates/senior-implementer.prompt` (short “findings-first” rule); optional architect handoff shape so reform always receives the review artifact.

**Related:** architect → senior-implementer residual; live `htw-architecture` / `htw-architecture-fix`; **B79** (QA also over-scoped theme-wide — related packaging discipline).

---

## P2 — Theme continuity

### B70 — New story mid-theme: analyst updates module dependencies

**Policy:** When a **new story is added into an already executing theme** (theme open/in flight; not only greenfield analysis), the **analyst** must **adjust module dependencies accordingly** — not leave the original module map / dependency-checker / implementation-order frozen if the new story changes the graph.

**Expected:**
1. **Analyst role + residual path:** after registering a late story (or on SL/analyst residual when story set changes), re-evaluate and **update** as needed:
   - module map (components / story→module ownership)
   - `dependency-checker.edn` allowed dependencies
   - implementation-order edges (predecessors for the new story and any affected stories)
2. Re-record durable theme artifacts under `.squad/themes/<id>/` and re-run approval gates where B25 requires user approval for non-empty order / non-trivial checker changes.
3. Prompts/templates state this explicitly for mid-theme story intake (backlog approve → story on existing theme, or SL adds story while implementers run).
4. Residual should not spawn implementers for the new story against a stale order/checker that omits it.

**Priority (P2):** Correctness of architecture gates and impl order when product grows mid-swarm.

**Where:** analyst role-template / constitution articles; `squad_next` theme bookkeeping residuals; theme record commands (`squad_theme.sh` module-map / implementation-order / dependency-checker); B23 theme reopen / finalize interaction if theme was finalized.

**Related:** B23 theme lifecycle; B25 order/checker approval; B35 backlog → story on existing theme; analyst story-cutting guidance; **B72** reject → backlog.

---

### B72 — Rejected story returns to backlog for edit + re-approve

**Policy:** When a **story is rejected** (user/SL rejects story gate or equivalent product rejection), the operator must **change** it. Put it **back into the product backlog** so it can be **edited** and **re-approved** for analysis — not leave it only as a dead Board card / packet with no operator edit path.

**Expected:**
1. On story rejection (define which events: e.g. story approval **reject**, SL/product reject of story intake, and/or packet path that means “operator must rewrite intent” — not every technical `needs-change` from code review).
2. Create or restore a **`.squad/backlog/*.item`** with:
   - title/body from story (and rejection reason in body or detail field)
   - status **`open`** (editable)
   - link back to prior story id / theme if useful
3. Board: rejected story not left as a confusing active card — archive, mark rejected, or remove from active columns once backlog item exists.
4. Operator flow: edit backlog item → **Approve** again → normal product path (SL classify / analyst), same as new intake (**B35**).
5. Idempotent if already re-queued; don’t duplicate backlog spam on repeated status polls.

**Priority (P2):** Operator recovery path for rejected product intent.

**Where:** story/packet reject handlers (`squad_packet` / `squad_approval` reject story gate); backlog write API (`squadd/web.clj` backlog); residual or mechanical apply if reject is file-driven; dashboard Board hide/show.

**Related:** **B35** backlog + approve; story approval gates; Board story cards.

---

## Architecture north star

Keep the **single-writer** direction. Prefer **typed actions + planner/executor**, then **durable readers/leases**, over more prompt text or one-off retries.

**Status:** Open **B68**/**B73** (P1), **B70**/**B71**/**B72** (P2), **B65**–**B67**, **B69**, **B74** (P3).  
Dashboard design: `ui-design.md`; prototype: `dashboard-mockup.html`. Grok scroll: `swarmforge/docs/grok-agent-window-scroll.md`.
