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
| **P2** | B88 | Rename product vocabulary: theme → project | Product / UX | IA / dashboard / copy |
| **P2** | B89 | Projects live in appropriately named subdirectories | Product / IA | Project layout / filesystem |
| **P2** | B91 | WIF analyst label: project:story (e.g. HTW:HHG), not “Theme: HTW” | UX / Bug | Dashboard / WIF |
| **P2** | B103 | Project-first intake: one project, many stories, explicit start | Product direction | Backlog / SL / analyst |
| **P1** | B100 | Workflow: story Done after QA; project Done after architect (or senior-impl) | Product / policy | Board / project lifecycle |
| **P2** | B96 | Analyst plans implementer batches (≤2 same-level, related modules) | Process / policy | Analyst / implementer / merge |
| **P2** | B97 | Architect / senior-implementer only after all project stories finish QA | Process / policy | Architecture / QA batch |
| **P2** | B98 | Architect must not require module-map edits as senior-implementer work | Process / policy | Architect / senior-implementer |
| **P3** | B87 | Rename “Work in flight” to “Work Queue” | UX | Dashboard / Ops rail |
| **P3** | B102 | Backlog should be a top button, not a full board lane | UX | Dashboard / Board |
| **P3** | B92 | Status bar: say “next action”, not “residual” | UX | Dashboard / status |
| **P3** | B93 | Card status-change green flash: slower, pulse three times | UX | Dashboard / Board |
| **P3** | B95 | Thermometers: drop last pane line before hash (timer noise) | Bug / UX | Dashboard / thermometers |

---

## Suggested fix order

1. **P1:** **B100** (story Done after QA; project Done after architect/senior-impl).
2. **P2:** **B103** (project-first intake/start), **B98** (no module-map chores for senior-impl), **B97** (arch/senior only after all stories QA), **B96** (analyst implementer batches), **B91** (WIF project:story labels), **B88** (theme → project vocabulary), **B89** (project subdirectories).
3. **P3:** **B95** (therm hash ignore timer line), **B93** (card glow pulse), **B92** (status “next action”), **B102** (Backlog button), **B87** (Work Queue label).

---

## Clusters

| Cluster | Bugs | Note |
|---------|------|------|
| Packet repair / rework cycle | **B96**, **B97**, **B98** | Implementer batches; hold architecture until project QA-complete; no late map rewrite chores. B94/B99/B101 done. |
| Operator chat / dashboard IO | **B87**, **B88**, **B91**, **B92**, **B93**, **B95**, **B102** | Work Queue; project vocab; WIF labels; next-action; card glow; therm timer noise; backlog button |
| Product intake | **B88**, **B89**, **B91**, **B96**, **B97**, **B98**, **B100**, **B103** | Project layout/vocab; mid-theme WIF; analyst batch plan; arch gate timing; map ownership; Done semantics; project-first intake/start |
| Control plane | **B100** | story vs project Done. Merger packet stamps (B99) done. |
| Lifecycle hygiene | — | B11/B12/B37/B38 done |
| Theme / architecture gates | **B100** | Story Done after QA; project Done after architect accept or senior-impl; supersedes story `final_approved` as Done gate (B23 still related) |
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

### B88 — Rename product vocabulary: theme → project

**Policy:** Throughout the product, the operator-facing term **theme** should be **project**.

**Scope (operator-facing first):**
1. Dashboard chrome: section labels (e.g. Themes → Projects), pills (`theme: htw` → `project: htw`), cards, tooltips, empty states.
2. Approvals / Attention: gate titles and reasons that say “theme” (e.g. Approve theme → Approve project).
3. Status bar / residual strings that expose “theme” to the operator.
4. Troubleshooter / SL-facing product copy in templates and dashboard answers that refer to “theme” as the product unit.
5. User docs and `ui-design.md` when next edited — use **project** for the same concept.

**Durable identifiers (compatibility):**
- On-disk paths and keys may remain `theme_id`, `.squad/themes/`, `squad_theme.sh`, packet `theme_id`, approval gates named `theme`, etc. **unless** a full migration is planned.
- Prefer a **display layer** rename (label “project”, value still `theme_id`) for v1 so existing swarms keep working.
- If API JSON exposes `"theme"` / `"themes"` to the browser, either dual-key or rename with a short compatibility period.
- Document the mapping: **project (UI) = theme (storage/API)**.

**Expected:**
1. No operator-visible “theme” in the live cockpit where “project” is meant (except possibly raw ids in advanced tooltips if needed).
2. Board / Ops / backlog approve-for-analysis flows read consistently as project-scoped work.
3. Tests that assert UI copy updated; storage tests unchanged if keys stay.
4. Optional follow-up issue for full storage rename (`themes/` → `projects/`) if desired later — **not** required for this issue.

**Priority (P2):** Vocabulary consistency; reduces confusion with “theme” as design/styling.

**Where:** `squadd/dashboard.html`, `squadd/web.clj` labels; approval titles/reasons; residual/product strings; role prompts that talk to operators; `ui-design.md` when touched.

**Related:** B14 theme package card; B23 theme lifecycle; B35 backlog; B87 Work Queue naming; **B89** project directories; **B91** WIF labels.

---

### B91 — WIF analyst label: project:story (e.g. HTW:HHG), not “Theme: HTW”

**Symptom (live):** While **Holy Hand Grenade (HHG)** is being analyzed, Work Queue / WIF shows something like **`Theme: HTW`** instead of a scannable **`HTW:HHG`** (project:story).

**Root cause (two layers):**
1. Mid-theme / story-targeted analyst assignments still store **`story_id: theme`** + **`scope: theme`** (e.g. `analyst-htw-holy-hand-grenade`), so B83’s WIF path treats them as theme-wide and shows only the theme label.
2. Theme title from `theme.md` is often literally **`# Theme: HTW`**, so the “human” label becomes **`Theme: HTW`** — worse than a bare id.

**Expected:**
1. WIF story column for analysis (and similar) prefers **`{project}:{story}`** when a specific story is known — e.g. **`HTW:HHG`** or **`htw:holy-hand-grenade`** (document one display convention; short aliases OK if durable).
2. Infer story when metadata still says `story_id: theme`:
   - from assignment id patterns (`analyst-<theme>-<story>`, `htw-holy-hand-grenade-analysis`, …), and/or
   - from assignment instructions / backlog title, and/or
   - fix **create path** so story-targeted analyst assignments set real `story_id` / `scope: story` (preferred long-term).
3. Theme/project display name: strip a leading **`Theme:`** / **`Project:`** prefix from `theme.md` H1; never show the word **Theme** as the WIF label after **B88**.
4. Full theme-wide analysis (true greenfield, no single story): show **project name only** (e.g. `HTW`), not `Theme: HTW`.
5. Tests: HHG-style assignment with `story_id: theme` but id/title implying holy-hand-grenade → WIF label contains both project and story tokens; theme.md `# Theme: HTW` does not produce label starting with `Theme:`.

**Priority (P2):** Operator cannot tell which story an analyst is working from WIF.

**Where:** `squadd/web.clj` `wif-story-label` / `theme-display-name`; analyst assignment create (`squad_assign` / residual) for mid-theme stories; optional short-name map for HHG.

**Related:** **B83** (incomplete — theme-only); **B70** mid-theme analysis; **B88** project vocabulary.

---

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
4. How this interacts with B89 project directories and B88 display vocabulary.
5. Whether starting a project creates a project-scoped SL assignment, an analyst assignment, or a two-step SL-design-then-analyst pipeline.

**Priority (P2):** This changes the product intake model and should be settled before deeper backlog/project implementation.

**Where:** Backlog UI/API; project/theme creation flow; Squad Leader prompt/residual; analyst handoff input; `.squad/backlog` / `.squad/themes` durable state.

**Related:** B88 project vocabulary; B89 project directories; B96 analyst batching; B102 backlog button; B35 backlog approve-for-analysis.

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
1. `squad_theme.sh create` / project-create path (after **B88** naming) creates the named subdirectory and seeds standard layout.
2. Dashboard / tools / agent worktrees default cwd or artifact paths to that project dir when scoped.
3. Tests: create project `foo` → `projects/foo/` (or chosen root) exists with expected skeleton; no requirement that all of `stories/` live only at monorepo root.
4. Docs: one paragraph on project directory layout vs `.squad` control plane.

**Priority (P2):** Multi-project hygiene; pairs with **B88** project vocabulary.

**Where:** `squad_theme` create; assignment/worktree root selection; packet story paths; templates that write `stories/` / `features/`; optional `projects/` convention in product templates.

**Related:** **B88** theme→project; B23 project/theme lifecycle; B35 backlog approve → analysis.

---

### B87 — Rename “Work in flight” to “Work Queue”

**Policy / UX:** The Ops rail section currently labeled **Work in flight** should be named **Work Queue**.

**Expected:**
1. Visible section heading: **Work Queue** (not “Work in flight” / “WIF”).
2. Update any operator-facing strings in the live dashboard (`dashboard.html` / `web.clj`) that show this label.
3. Internal ids/API keys (`work_in_flight`, CSS classes, code comments) may stay for compatibility unless a rename is cheap and complete — prefer not to break clients for a display-only change.
4. Docs/mockups that mirror the cockpit (`ui-design.md`, mockup) can say Work Queue when next touched; not required for this fix if live UI is updated.

**Priority (P3):** Naming clarity.

**Where:** `squadd/dashboard.html` (section `<h2>` / Work in flight); any user-visible copy in `squadd/web.clj`.

**Related:** B46/B59 WIF table; Ops rail layout.

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

**Related:** B35 backlog CRUD / approve-for-analysis; B62 board columns; B88 project vocabulary.

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

### B94 — Pause product accept-merge while depth-2 merger open

**Policy:** While any **depth-2** merger lineage is unresolved (`*-merge-merge` in created…merge_blocked / in flight), **do not accept-merge new non-recovery product assignments onto main**. Clear the double-merge first, then drain the queue.

**Why:** Merger is already a singleton; the jam is new implementer/cleaner (etc.) results piling into `merge_blocked` / held handoffs while recovery is already at **max useful depth** (`max_merger_depth` 2).

**Pause:**
1. **`accept-merge`** of new **product** results (implementer, cleaner, reviewer git results, …) that are not the open depth-2 merger (or its resolve path).
2. Optionally: claiming **new** product `git_handoff` mail into `in_process` for mergeable work (park/hold instead).

**Keep going:**
1. The depth-2 merger’s own handoff / merge-ready / accept-merge.
2. Dashboard / product / review work that does not land competing merges on main.
3. Existing agents may finish and sit `handoff_sent` / held — don’t spawn more implementers that only add merge pressure (optional stricter variant).

**Do not:**
- Pause all handoffs (TS/SL/reviews wholesale).
- Gate on every depth-1 `-merge` the same way — depth-1 is normal recovery; **depth-2** is the “last chance / don’t dig deeper” signal.

**Expected:**
1. Detect open depth-2 merger assignments (suffix / `merge_suffix_depth` ≥ 2, or `*-merge-merge` pattern).
2. While open: residual/daemon skips or defers product `accept-merge` (and optionally merge-ready→accept for new product); surface next action like `wait_for_merge_recovery` / hold reason.
3. When depth-2 resolves (merged/cancelled/blocked terminal): resume normal accept-merge drain (including `htw-move-player`-style backlog).
4. Tests: with `foo-merge-merge` in progress + another `merge_ready` product assignment → residual/daemon does not accept-merge the product one; after depth-2 merged, product accept proceeds.

**Priority (P1):** Prevents merge-jam amplification under parallel implementers.

**Where:** `squad_next` residual / mechanical apply gates; `squad_assign` accept-merge policy or caller checks; handoff claim/park; related `max_merger_depth`.

**Related:** live command-syntax `…-merge-merge` chain; merger singleton B27/B73; **B75** dirt soft-defer; `max_merger_depth`; **B96** implementer batches (upstream throttle); **B99** merger→packet stamp.

---

### B99 — Mergers must propagate packet stamps

**Symptom (live htw):** `htw-architecture-reform` (senior-implementer) finished and landed on master via depth-2 merger (`844ffd5` → `…-merge-merge` → `7931912`). Theme events show `assignment_merged` for the reform lineage, but **no story packet** ever got `senior_implementer_sha` / `senior_implementer_recorded`. Stories stayed `qa_approved` + `architecture_review: changes-requested` and never reached `final_approved` / Done — the merger recovery **hid** the product result from the packet FSM.

**Contrast:** The same depth-2 pattern for **hardener** *does* stamp packets (`hardener_recorded` immediately after merge-merge accept). So mergers are not inherently stamp-blind; senior-implementer / some batch paths fail to project.

**Likely gaps:**
1. After `mark-original-resolved-by-merger!`, residual `record_merged_result` / `record_merged_batch_result` must still run for the **blocked product** assignment (not only the merger).
2. Senior-implementer is absent from `batch-template-kinds` and reform often has **no** `.squad/batches/<id>/manifest` of member stories → `batch-manifest-rows` empty → nothing to stamp even when reform is `merged`.
3. Assignment cleanup must not delete the product assignment before packet projection completes.

**Expected:**
1. When a merger (any depth) successfully merges and marks the original product assignment `merged` / `resolved_by`, the control plane **propagates the same packet stamps** that a direct product `accept-merge` would have: e.g. `squad_packet.sh record <story> <kind> <assignment> master <sha>` for each affected story (single-story or batch members).
2. Senior-implementer reform batches have a durable member manifest (architecture `changes-requested` stories, or equivalent) so `record_merged_batch_result` has targets.
3. Kind coverage: at least implementer / cleaner / hardener / qa / senior-implementer / architect review paths that already use merger recovery — no “merged on git, invisible to packets.”
4. Tests: product assignment `merge_blocked` → merger (or merge-merge) accepts → original marked merged → member packets gain the result SHA / review field without a direct product accept-merge; senior-impl reform with architecture changes-requested members stamps `senior_implementer_sha` and unblocks final-approval gate.

**Priority (P1):** Merge recovery that lands code but leaves the FSM stuck is a correctness hole (Done empty, architecture forever open, duplicate reform risk).

**Where:** `squad_assign` `mark-original-resolved-by-merger!` / accept-merge; `squad_next` `batch-result-record-candidates` / `result-record-candidate` / `batch-template-kinds` + senior-implementer batch membership; residual mechanical apply order vs assignment cleanup.

**Related:** live `htw-architecture-reform` double-merge; **B94** depth-2 accept-merge pause; **B97** arch/senior timing; **B82** senior-impl findings-first; hardener batch stamp path (working reference); **B100** Done semantics (story vs project).

---

### B100 — Workflow: story Done after QA; project Done after architect (or senior-impl)

**Policy change (product workflow):**

1. **Story → Done lane after QA.** When a story reaches QA success (`qa_approved` / equivalent), its board card moves to **Done**. Story Done no longer waits on architecture, senior-implementer, or story-level `final_approved`.
2. **Project (née theme) → done** when either:
   - the **architect accepts** the whole project (architecture review accepted for the project-level pass), **or**
   - the **senior-implementer** addresses the architect’s issues (reform complete / `senior_implementer_sha` projected — see **B99**), after which the architecture gate is satisfied.
3. Architecture / senior-implementer are **project-level** closing work over the QAd story set, not per-story “Finalizing” before Done.

**Today (supersede):**
- Board **Done** = packet `final_approved` only (`ui-design.md` / `board-column`).
- Post-QA stories sit in Finalizing through architecture / final approval.
- Theme/project **finalize** (B23) is a separate lifecycle gate; story `final_approval` is a per-story residual.

**Expected:**
1. Dashboard `board-column` / stage maps: `qa_approved` (and later arch fields) → **Done** for story cards; remove dependence on `final_approved` for the Done lane.
2. Project lifecycle/status shows **done** (or finalized) when project architecture is accepted **or** senior-impl has cleared changes-requested for that project pass — align with **B97** (arch only after all stories QA) and **B88** (project naming).
3. Drop or demote per-story `final` / `final_approval` as the operator-visible Done gate (keep internal fields only if still needed for compat; do not block Done lane).
4. Finalizing column (if kept) is for pre-QA late pipeline only — or shrink/remove once Done-after-QA is live.
5. Docs: `ui-design.md` Done row; any “final approved” operator copy.
6. Tests: story `qa_approved` → board column `done`; project not done until architect accept or senior-impl stamp; after senior-impl stamp / arch accept → project done.

**Priority (P1):** Matches intended product model; fixes empty Done while QAd stories linger in Finalizing.

**Where:** `squadd/web.clj` board-column / pipeline-stage; project/theme lifecycle (`squad_theme` finalize or successor); `squad_next` final-approval residual; `ui-design.md`; **B88** vocabulary.

**Related:** **B97** arch after all QA; **B99** merger packet stamps (needed so senior-impl can close project); **B23** theme finalize; **B88** theme→project; **B98** no module-map chores for senior-impl; **B101** one CR per story.

---

### B101 — One code review per story; CR changes-requested → impl→cleaner→hardener

**Policy:** **Exactly one** code-review pass per story. Happy path and rework path:

1. **Happy path:** implementer → cleaner → **code-reviewer** → (CR accepted) → hardener → …
2. **If CR requests changes:** implementer → cleaner → **hardener** (do **not** spawn CR-r2 / another code review). The first CR verdict is final for the story; hardener absorbs the post-rework quality gate.

**Today (wrong):**
- After `code_review: changes-requested`, residual creates implementer rework, then cleaner, then **another** code-reviewer (`…-code-review-r2`), which can changes-request again → thrash / stuck rework gates (live `htw-holy-hand-grenade`: CR → impl-r2 → cleaner-r2 → CR-r2 changes-requested again; then `implementer-rework-already-created?` blocked r3).
- Multiple CR rounds per story are allowed by `code-review-assignment-candidate` / clear-downstream re-entry.

**Expected:**
1. At most **one** `code-reviewer` assignment (and one recorded `code_review` verdict) per story lifetime.
2. After first CR **accepted:** existing path to hardener (unchanged).
3. After first CR **changes-requested:** create/spawn implementer rework → cleaner → then **hardener** eligibility (treat rework+clean as sufficient to enter harden; do not require a second CR accept).
4. Residual must **never** emit `…-code-review-r2` (or any further code-reviewer) for that story.
5. Fix or retire thrash guards (`implementer-rework-already-created?`, etc.) so they match “one rework after the single CR,” not “block forever because r1 still exists.”
6. Tests: CR changes-requested → impl-r2 create; after cleaner-r2 recorded → hardener candidate (or batch member), **no** code-review-r2 candidate; story with existing CR assignment/verdict → no second CR create.

**Priority (P1):** Stops CR ping-pong and HHG-class stuck stories; matches intended workflow.

**Where:** `squad_next` `code-review-assignment-candidate`, `implementation-revision-candidate`, cleaner→harden readiness / `hardener-member-ready?` (or equivalent after CR changes-requested + rework cleaner); clear-downstream on re-record; role prompts if they imply endless CR cycles.

**Related:** live HHG CR-r2 loop; **B99** merger stamps; **B94** merge pressure; **B100** Done after QA (harden/QA still run after this path).

---

### B96 — Analyst plans implementer batches (≤2 same-level, related modules)

**Policy:** The **analyst** plans implementer work as **batches of at most two stories**, grouped by **implementation-order level** (antichain / same ready level) and **module affinity** from the module map / dependency-checker. Control plane assigns **one implementer** per batch (not one per story by default).

**Why:** Parallel same-level implementers are a primary cause of merge pile-ups (shared `acceptance/runner.clj`, tooling, domain). Batching two related stories into **one agent / one commit / one handoff** cuts landing conflicts while still respecting the order DAG. Complements **B94** (brake while depth-2 merger open).

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

**Related:** **B94** depth-2 accept-merge pause; **B70** mid-theme deps/order; implementation-order hard gate (B03/B13/B25); **B85** analyst singleton; **B97** architecture timing.

---

### B97 — Architect / senior-implementer only after all project stories finish QA

**Policy:** Do **not** start **architect** (or the following **senior-implementer** reform path) until **every story in the project** has finished **QA** and is waiting at the post-QA / pre-architecture gate (e.g. `qa_approved` or equivalent “QA done, ready for architecture”).

**Agree:** Yes for a **project-level** architecture pass. Early architecture on a partial story set produced premature reform (partial codebase, missing stories still coding) and senior-implementer over-scope. Architect should see the **full** implemented+QAd system the analyst planned.

**Today:** Architecture batches can form **incrementally** as individual stories hit `qa_approved` (e.g. one story enters `htw-architecture` while siblings are still Specifying/Coding), then senior-implementer may fire on that partial batch.

**Expected:**
1. **Ready predicate:** all registered project stories (open theme/project story set) have completed QA (configurable packet states: at least `qa_approved`, not still in implement/clean/CR/harden/QA).
2. Until then: residual must **not** create/spawn `architect` or `senior-implementer` assignments / architecture batches.
3. When the gate opens: create **one** architecture batch (or project-scoped architecture assignment) covering the full set; senior-implementer only after architecture **changes-requested** as today.
4. Stories that finish QA early go to **Done** (**B100**) and simply wait for siblings before the **project** architecture pass starts — do not start architecture on a partial set.
5. **Caveat / escape hatch (document):** optional explicit operator or finalize-slice action to run architecture on a declared subset for huge/lagging projects — default is **all stories**.
6. Tests: two stories, only one `qa_approved` → no architect candidate; both `qa_approved` → architect/batch candidate appears.

**Priority (P2):** Correct architecture timing; reduces false reform on incomplete projects.

**Where:** `squad_next` `architecture-ready?` / batch readiness; packet/batch membership rules; board stage copy optional; senior-implementer residual gated on architecture outcome after this hold.

**Related:** architecture batch FSM; **B96** implementer batches; **B82** senior-impl findings-first; **B23** theme/project finalize; **B79** QA fail must not false-green into arch; **B98** module-map not a reform chore; **B100** story Done after QA / project Done after arch.

---

### B98 — Architect must not require module-map edits as senior-implementer work

**Policy:** After stories are in flight / past analysis, **module-map updates are not senior-implementer deliverables**. Architects may **note** map drift as commentary; they must **not** list module-map edits under required findings / “Requested change” for architecture-reform. Senior-implementer **ignores** “Module Map Recommendations” unless SL **explicitly** assigns a map-only chore.

**Symptom (live htw):** `reviews/htw-architecture-r2-review.md` (and later reviews) include **Module Map Recommendations** (HHG purpose/use-cases/UI/IO notes). Senior-implementer treats them as assigned work alongside real code findings (IO randomness, acceptance coverage), wasting effort on soft docs that don’t change runtime and can fight control-plane theme state (`squad_theme.sh module-map`).

**Why pointless now:** Module map is **soft guidance** for analyst/early implementers. Once stories exist and code is under architecture review, rewriting `.squad/themes/<id>/module-map.md` does not fix Dependency Rule or acceptance gaps; code findings do.

**Expected:**
1. Architect role/prompt: **Module Map Recommendations** = optional non-blocking commentary only; required findings are code/structure/acceptance only.
2. Senior-implementer role/prompt (**B82**): work order = code/acceptance findings; skip map sections unless SL says otherwise.
3. Architecture-reform assignment packaging does not elevate map bullets into Leader Instructions.
4. If map must be updated for operators, prefer SL/analyst residual or a dedicated tiny assignment — not bundled into reform.
5. Tests/fixtures optional: review with only map recommendations → no senior-impl create, or senior-impl instructions omit map chores.

**Priority (P2):** Stops false reform scope and theme-state thrash.

**Where:** `architect.prompt` / contract; `senior-implementer.prompt`; architecture review template; residual that creates `*-architecture-reform`.

**Related:** **B82** findings-first; **B97** arch timing; live HHG map recommendations in `htw-architecture-r2-review.md`.

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

---

### B90 — Session pane open-at-bottom failed: wrong scroll container

**Symptom:** Despite B69/B74/B86, Open SL / Open TS / agent session windows still opened at the **top** of the transcript.

**Root cause:** `#pane` used `min-height: calc(100vh - 42px)` **without a max-height**, so the `<pre>` **grew with content**. Overflow scrolled the **document/window**, while JS set `pane.scrollTop` — a no-op on an unconstrained element. Also, named `window.open` reuse skipped reload/`firstPaint`.

**Fix:** Make `#pane` a fixed-height scrollport (`height`/`max-height` + `overflow:auto`; `html,body` `overflow:hidden`). Force `toEnd` after content paint (rAF + short timeouts). Cache-bust agent URLs on open so reused named windows reload.

**Where:** `squadd/web.clj` `pane-page`; `dashboard.html` `openAgentWindow`.

**Related:** B69, B74, B86.

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
| P1 pane scroll | **B90** | Session pane open-at-bottom failed: `pre` grew with content so `scrollTop` was a no-op; fixed viewport scrollport + reopen reload |
| P1 merge/rework | **B101**, **B94**, **B99** | One CR then impl→cleaner→hardener; pause product accept-merge while depth-2 merger open; merger resolution stamps product packets (incl. senior-impl without manifest) |


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
