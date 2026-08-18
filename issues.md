# Issues

Prioritized open issues. Priority is **impact on swarm correctness, operator unblock, and recurring defect classes** — not chronological discovery.

**How to read priority**
- **P1 — Fix before the next serious multi-story swarm.**
- **P2 — Important soon.** Live operator friction, hygiene, product intake.
- **P3 — When capacity allows.** Polish, large IA redesign.

---

## Index (open only)

| Pri | ID | Title | Kind | Area |
|-----|-----|--------|------|------|
| **P2** | B89 | Projects live in appropriately named subdirectories | Product / IA | Project layout / filesystem |
| **P2** | B103 | Project-first intake: one project, many stories, explicit start | Product direction | Backlog / SL / analyst |
| **P2** | B96 | Analyst plans implementer batches (≤2 same-level, related modules) | Process / policy | Analyst / implementer / merge |
| **P3** | B102 | Backlog should be a top button, not a full board lane | UX | Dashboard / Board |
| **P3** | B92 | Status bar: say “next action”, not “residual” | UX | Dashboard / status |
| **P3** | B93 | Card status-change green flash: slower, pulse three times | UX | Dashboard / Board |
| **P3** | B95 | Thermometers: drop last pane line before hash (timer noise) | Bug / UX | Dashboard / thermometers |

---

## Suggested fix order

1. **P2:** **B103** (project-first intake/start), **B96** (analyst implementer batches), **B89** (project subdirectories).
2. **P3:** **B95** (therm hash ignore timer line), **B93** (card glow pulse), **B92** (status “next action”), **B102** (Backlog button).

---

## Clusters

| Cluster | Bugs | Note |
|---------|------|------|
| Product intake | **B89**, **B96**, **B103** | Project directories; analyst batch plan; project-first start |
| Operator chat / dashboard IO | **B92**, **B93**, **B95**, **B102** | next-action; card glow; therm timer noise; backlog button |

---

## Open detail

### B103 — Project-first intake: one project, many stories, explicit start

**Direction change:** The squad works on a **project** (née theme). Intake should distinguish the project document from the stories that belong to that project, and project startup should be explicit.

**Model:**
1. A **project** can be added to the backlog with an **Add Project** menu item.
2. Only **one project** can be added / active in the backlog workflow at a time.
3. **Stories** can be added with an **Add Story** menu item.
4. Stories are considered part of the current project, not separate peer-level projects.
5. A project can be started with a **Start Project** menu item.

**Start Project behavior:**
1. When the operator starts the project, the Squad Leader reads the project document and all associated story documents.
2. The Squad Leader creates a high-level design for the project.
3. That high-level design becomes the input fed into the analyst.
4. The analyst then proceeds from the SL-created project design instead of being asked to infer the whole design from loosely related backlog items.

**Open design questions / brainstorm later:**
1. Exact backlog UI shape: whether Add Project / Add Story / Start Project live under the backlog deck button, a project menu, or a top-level intake control.
2. Durable state model for one pending project plus many attached stories.
3. Validation rules when no project exists, when a project is already pending, and when stories are added before start.
4. How this interacts with B89 project directories and project display vocabulary.
5. Whether starting a project creates a project-scoped SL assignment, an analyst assignment, or a two-step SL-design-then-analyst pipeline.

**Priority (P2):** This changes the product intake model and should be settled before deeper backlog/project implementation.

**Where:** Backlog UI/API; project/theme creation flow; Squad Leader prompt/residual; analyst handoff input; `.squad/backlog` / `.squad/themes` durable state.

**Related:** B89 project directories; B96 analyst batching; B102 backlog button; B35 backlog approve-for-analysis.

---

### B89 — Projects live in appropriately named subdirectories

**Policy:** Each **project** (today: theme) should be created as an **appropriately named subdirectory** of the swarm root — not only as opaque control-plane state under `.squad/themes/<id>/` with product files mixed at the repo root.

**Intent:**
1. On project create (or first durable materialization), establish a **project-scoped directory** whose name matches the project id / slug (e.g. `projects/htw/` or `htw/` — pick one convention and document it).
2. Project-owned product artifacts (stories, features, qa procedures, reviews, module map, dependency-checker, implementation-order, source trees when applicable) live **under that subdirectory** rather than polluting the monorepo root by default.
3. Swarm control plane may still use `.squad/…` for FSM state; **product** files belong in the project directory.
4. Paths recorded in packets/assignments resolve relative to the project root (or document dual-root rules clearly).
5. Existing swarms: migration path or “root continues to work” until re-homed — do not break open htw mid-flight without a plan.

**Expected:**
1. `squad_theme.sh create` / project-create path creates the named subdirectory and seeds standard layout.
2. Dashboard / tools / agent worktrees default cwd or artifact paths to that project dir when scoped.
3. Tests: create project `foo` → `projects/foo/` (or chosen root) exists with expected skeleton; no requirement that all of `stories/` live only at monorepo root.
4. Docs: one paragraph on project directory layout vs `.squad` control plane.

**Priority (P2):** Multi-project hygiene.

**Where:** `squad_theme` create; assignment/worktree root selection; packet story paths; templates that write `stories/` / `features/`; optional `projects/` convention in product templates.

**Related:** B23 project/theme lifecycle; B35 backlog approve → analysis.

---

### B102 — Backlog should be a top button, not a full board lane

**Problem:** The Backlog deck currently consumes a full board lane. That gives pending intake the same visual weight as active workflow columns and squeezes Specifying / Coding / Finalizing / Done.

**Expected:**
1. Remove the dedicated **Backlog** board column from the main lane set.
2. Add a compact top dashboard control: a small icon button that looks like an oblique side-view **deck of cards**, showing the open backlog count without taking a board lane.
3. Clicking the button opens the existing backlog menu/list and preserves current item edit, add, delete, and approve-for-analysis behavior.
4. Empty backlog still has a clear add-new path from that button/menu.
5. Keep the durable backlog API and `.squad/backlog` storage unchanged.
6. Tests/assertions updated so the board columns are workflow columns only, while backlog remains reachable from the top control.

**Priority (P3):** Board space and scanability; backlog is intake, not an active workflow lane.

**Where:** `squadd/dashboard.html` `renderBoard` / backlog deck binding; optional `squadd_web_test` dashboard HTML assertions.

**Related:** B35 backlog CRUD / approve-for-analysis; B62 board columns.

---

### B92 — Status bar: say “next action”, not “residual”

**Policy:** Operator-facing status chrome should use **next action**, not **residual**.

**Today (B71):** header meta looks like `… · residual: process_handoff · product: …`.

**Expected:**
1. Display label **`next action:`** (or compact `next:`) for the same value currently shown as residual.
2. Keep internal names (`residual`, `--residual-only`, daemon `residual-next` snapshot) unless a later rename is planned — this issue is **status display copy** only.
3. Product-pending badge wording unchanged unless it also says “residual.”
4. Tests/assertions that look for `residual:` in dashboard HTML/JS updated to `next action:` / `next:`.

**Priority (P3):** Clearer cockpit language.

**Where:** `squadd/dashboard.html` meta line (B71); any other operator-visible “residual” strings in the live dashboard.

**Related:** **B71** residual header + product badge; B51 next_action heuristic.

---

### B96 — Analyst plans implementer batches (≤2 same-level, related modules)

**Policy:** The **analyst** plans implementer work as **batches of at most two stories**, grouped by **implementation-order level** (antichain / same ready level) and **module affinity** from the module map / dependency-checker. Control plane assigns **one implementer** per batch (not one per story by default).

**Why:** Parallel same-level implementers are a primary cause of merge pile-ups (shared `acceptance/runner.clj`, tooling, domain). Batching two related stories into **one agent / one commit / one handoff** cuts landing conflicts while still respecting the order DAG.

**Analyst delivers (durable):**
1. Existing **`implementation-order.md`** (edges → levels).
2. A batching plan (section of order file, or sibling e.g. `implementer-batches.md` / theme record) listing groups:
   - each group: **1–2 story ids**, no edge between them, prefer shared module/use-case cluster;
   - leftovers as solo batches of one.
3. Do **not** fuse approved story files into one product story — batch **references** existing story ids; implementer-facing work order may summarize both.

**Control plane / SL:**
1. Spawn implementer from a **batch assignment** listing both stories when a batch is ready (predecessors done).
2. **Handoff:** one git handoff when **both** stories in the batch are complete; record implementation SHA on **both** packets.
3. Soft plan: SL may split a batch on reject/rework; don’t treat the plan as frozen forever.
4. Optional: `max_active_template implementer 1` (or low) so only one batch lands at a time unless capacity explicitly raised.

**Expected:**
1. Analyst role/prompt + templates document level+module batching (≤2).
2. Residual create/spawn path consumes batch plan when present; falls back to per-story if absent (compat).
3. Tests: order with two independent level-1 stories → one batch of two in the plan artifact; implementer assignment carries both ids; single result SHA updates both packets.

**Priority (P2):** Structural fix for merge jams; analyst owns the schedule shape.

**Where:** `analyst.prompt` / contract; `theme-implementation-order` template (+ batch section or sibling); `squad_theme` record helpers; `squad_next` implementer create/spawn; packet `record` for multi-story SHA.

**Related:** implementation-order hard gate; analyst singleton.

---

### B93 — Card status-change green flash: slower, pulse three times

**Today:** Board story cards get a brief green **glow** when their status fingerprint changes (B48/card-glow). The flash is easy to miss.

**Expected:**
1. When a card’s status changes, the green highlight **pulses three times**.
2. Animation is **slower** than today (noticeable; roughly on the order of ~0.6–1.0s per pulse, or ~2–3s total — tune for readability, not urgency).
3. After three pulses, glow settles off (same end state as current single flash).
4. Does not loop forever; does not block clicks; works with existing `.card.glow` / fingerprint change detection.
5. Optional: same treatment for batch stacks if they use the same glow class.

**Priority (P3):** Glanceability of board updates.

**Where:** `squadd/dashboard.html` `@keyframes card-glow` (or successor) and `.card.glow` animation iteration/duration; keep trigger logic in `renderBoard` fingerprint glow.

**Related:** B48 card progress sort / glow; Board cards.

---

### B95 — Thermometers: drop last pane line before hash (timer noise)

**Symptom:** SL and Work Queue activity thermometers can stay warm or pulse even when the agent is idle, because the pane’s **last line** often updates every second (elapsed-time / status counter in Codex/Grok TUIs). Hashing the full tail treats that churn as real activity.

**Expected:**
1. Before hashing a pane sample for **SL** (`sl-activity`) and **per-agent** (`agent-pane-heat`) thermometers, **drop the last line** of the captured text (trim trailing newlines, then remove the final non-empty line — or equivalent).
2. Hash the remainder only. Empty-after-drop → treat as idle / heat 0 path as today when no useful content.
3. Same rule for both thermometers so behavior stays consistent.
4. Optional later: ignore other known timer/status patterns; v1 is “drop last line.”
5. Tests: two samples differing only in a trailing timer line → same hash / no heat increase; a real content change above the last line → heat increases.

**Priority (P3):** Thermometers should reflect real pane work, not clock widgets.

**Where:** `squadd/web.clj` `sl-activity` and `agent-pane-heat` (shared helper e.g. `pane-sample-for-hash`); capture still stores/displays full pane elsewhere.

**Related:** B56/B65 SL therm; B66/B84 WIF therm.
