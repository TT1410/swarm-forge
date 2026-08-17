# Squad UI design — Story Board + Swarm Ops

Working design for **B24** (dashboard information architecture) and **B35** (product backlog registry).  
Not a second story FSM: the UI is a **read model + dispatch surface** over packets, batches, and backlog files.

**Status:** Spec + live prototype + product implementation target (2026-08-17).

**Prototype:** `dashboard-mockup.html` (repo root) — interactive HTML/JS simulator of board, ops rail, backlog, batch/WIF hover, and detached windows. Treat it as the **behavioral reference** when implementing or regressing the real dashboard.

**Product code:** `swarmforge/scripts/squadd/web.clj` (+ `swarmforge/scripts/squadd/dashboard.html` when split out). Serves the live squad project under `.squad/`.

**Issues:** Implementation of this doc closes **B24** (dashboard IA) and **B35** (product backlog). **B15** (Grok terminal geometry) is agent-pane chrome (fixed separately via `--minimal` / pane capture); not part of this web cockpit design.

---

## North star

| Surface | Job | Where it lives |
|---------|-----|----------------|
| **Story Board** | Where is each story on the path from idea → done? | Main area of the **dashboard** |
| **Swarm Ops** | What needs me? Who is live? What is in flight? | Same dashboard (rails / bands) |
| **Troubleshooter chat** | Operator ↔ TS product chat | Same dashboard (contained panel) |
| **Detached windows** | Agent sessions, story/theme detail, other ancillary UI | **Movable OS/app windows** — not new browser tabs |

**Hard rules**

- Columns and badges are **derived** from `.squad` packets, batch manifests, and backlog store.
- No drag-to-change-state in v1 (would invent a second FSM).
- Backlog (B35) is product intake; story lifecycle truth stays in `.squad/stories/*/packet`.
- Prefer existing dashboard request + `route-to-sl` for dispatch.
- **Timestamps are durable truth:** every story, theme, and other backlog item records **created_at** and **updated_at** when written; the UI must not invent durable dates.
- **Combining Board + Ops must not drop data** — only density and placement change. Full detail goes to drawers / hover / **detachable windows**, not deletion.
- **Agent sessions stay outside the main board chrome** as detached session windows; the dashboard embeds TS **chat inject + history** and **Open** controls.
- **No new browser tabs for ancillary UI.** Agent sessions, story displays, theme displays, and similar secondary surfaces open as **detached, movable, resizable windows** (floating app windows / popup windows managed by the host), not `target=_blank` tab spam. The operator can place them beside the cockpit on any monitor.

---

## Combined cockpit (default)

**Yes — Board and Ops can share one browser window without losing data**, if Ops is a dense rail + Attention strip and the Board keeps the center.

**Troubleshooter and Squad Leader full sessions remain detached session windows** (Open). Required, not optional. Same pattern for story/theme detail when the operator detaches them.

### Window inventory

| Window | Count | Kind | Contents |
|--------|-------|------|----------|
| **Dashboard (cockpit)** | 1 | Primary, fixed role | Board + Ops + TS **chat** (inject/history) |
| **Troubleshooter session** | 1 | Detached, movable | Full agent session — deep work, residual judgment |
| **Squad Leader session** | 1 | Detached, movable | Full agent session — orchestration |
| **Transient agent sessions** | 0–N | Detached, movable | Implementer, cleaner, … when opened |
| **Story display** | 0–N | Detached, movable | Packet/detail for one story (optional undock from board) |
| **Theme display** | 0–N | Detached, movable | Theme package detail (map, order, checker, lifecycle) |
| **Other ancillary** | 0–N | Detached, movable | Batch detail, backlog edit (if undocked), logs, etc. |

Dashboard chat = short commands and durable Q&A log. Long TS/SL work = **detached session windows**. Both stay first-class.

### Detached movable windows (not browser tabs)

Secondary UI must be **undocked windows the operator can drag and resize**, not extra tabs in the same browser window strip.

| Requirement | Detail |
|-------------|--------|
| **Not tabs** | Do **not** use ordinary `target="_blank"` / new-tab navigation for agent sessions, story viewers, theme viewers, or other ancillary surfaces. |
| **Movable** | Each detached surface is its own window: drag title bar, place on another monitor, overlap cockpit freely. |
| **Resizable** | Operator controls size; remember last size/position per window kind when practical (local preferences). |
| **Reusable** | Re-opening SL/TS focuses the existing detached window if still open (singleton per role); story/theme windows may be one per id or reuse by id. |
| **Owned by host** | Prefer the swarm **host shell** (e.g. desktop app, controlled `window.open` popups with chrome, or embedded webview windows)—not the browser’s tab bar as the window manager. |
| **Lifecycle** | Closing a detail window does not stop agents. Closing/hiding an agent **session** window must not imply teardown unless the operator uses Teardown / retire explicitly. |
| **In-cockpit first** | Hover menus and light in-page drawers are fine for quick peeks; **Detach** (or Open) promotes that content to a movable window when the operator needs it parked beside the board. |

**Open / Detach affordances**

| Control | Opens |
|---------|--------|
| **Open SL** | Detached Squad Leader session window |
| **Open TS** | Detached Troubleshooter session window |
| Agent chip → Open | That agent’s session window |
| Story card → Detach / Open detail | Story display window (packet, artifacts, dates) |
| Theme card → Detach / Open | Theme package display window |
| Batch stack → Detach | Batch detail (members, assignment) |
| Backlog edit | May stay modal **or** detach to a movable edit window |

Implementation note: exact host API is open (Electron/Tauri child windows, Chromium app windows, or sized popups with `noopener` carefully managed). The **product rule** is fixed: **movable windows, not tabs.**

### Combined layout (desktop ≥1280px)

```
┌ header: project · squadd · slots 3/8     [Open SL ↗] [Open TS ↗] [Teardown] ┐
├──────────────────────────────────────────────────────────────────────────────┤
│ ATTENTION (full width)  stalls | blockers | approvals  or  “All clear”       │
├────────────────────────────────────────────┬─────────────────────────────────┤
│ BOARD (flex grow — primary)                │ OPS RAIL (~320–380px)           │
│                                            │                                 │
│  [Backlog deck]                            │ Themes (compact cards)          │
│                                            │ Live agents (chips)             │
│  Specified | Ready | Coding | Done         │ Work in flight (dense list)     │
│  cards / batch stacks                      │                                 │
│  hover batch → member names                │ ── Troubleshooter chat ──       │
│  hover backlog → items + dates             │ history (scroll, max-height)    │
│                                            │ [composer] [Send]               │
│                                            │ [Open TS window ↗]              │
└────────────────────────────────────────────┴─────────────────────────────────┘
```

### Data map (nothing essential omitted)

| Data | Where in combined view |
|------|-------------------------|
| Story progress / columns / badges / timers | **Board** center |
| Backlog deck + edit / approve for analysis | **Board** (deck + edit window) |
| Batch stacks + member hover | **Board** |
| Stalls, blockers, approvals | **Attention** top strip |
| Themes package status | **Ops rail** |
| Live agents | **Ops rail** chips |
| Active assignments | **Ops rail** (inner scroll if long) |
| TS chat history + send | **Ops rail** (or bottom sheet if short) |
| Full TS / SL tools & transcript | **Detached session windows** (movable) |
| Story / theme deep detail | **Detached display windows** when undocked; hover/drawer for peek |

### Why this does not lose data

- **Relocation, not deletion:** Flat Stories table leaves Ops because stories appear as **board cards** (richer). Assignments stay in the rail for “who is running.”
- **Progressive disclosure:** Theme/story package detail → short **in-page peek** or **detached movable window** (operator choice); backlog/batch members → **hover menus**.
- **Inner scroll:** Rail + TS history scroll inside fixed bands so the Board stays on screen.
- **Same JSON API:** One poll fills Board + Attention + rail; detached windows read the same project state.

### Narrow / short viewports

1. Keep **Attention** + **Board** always visible.
2. Ops rail may become a **Status ▸** drawer (one click) or bottom sheet for TS chat.
3. Optional routes `/board` and `/ops` only as **fallback** for tiny screens — **default is combined**.

### Agent + detail windows (required pattern)

```
        ┌──────── detach / Open (movable window, not a tab) ────────┐
        ▼                                                           ▼
┌ Dashboard cockpit ──┐   ┌ TS session ──┐  ┌ Story detail ─┐  ┌ Theme ──┐
│ Board + Ops rail    │   │ full agent   │  │ packet, dates │  │ package │
│ TS chat inject      │   │ movable      │  │ movable       │  │ movable │
└─────────────────────┘   └──────────────┘  └───────────────┘  └─────────┘
```

| Rule | Detail |
|------|--------|
| **Open TS** | Always available (header and chat panel). Opens or focuses **detached** Troubleshooter session window. |
| **Open SL** | Always in header (and SL chip). Same: detached, not a tab. |
| **Chat vs session** | Dashboard chat = request/response log + inject. Session window = full agent. Neither replaces the other. |
| **Busy indicator** | TS busy pill on dashboard when session is working. |
| **Transients** | Open from chip → detached session window when a live pane exists. |
| **Story / theme** | Card click may peek in-cockpit; **Detach** parks a movable detail window. |
| **No tab bar sprawl** | Ancillary surfaces must not accumulate as browser tabs. |

### Deep links (cockpit + windows)

- `?story=` / `?batch=` → highlight Board card or stack; optional auto-open detached detail if preference set.
- Click assignment in rail → Board highlight; optional open agent session window if running.
- Approvals stay on-page in Attention (no extra window required).

---

## Board (main region of combined cockpit)

### Layout

```
┌ Backlog deck ┐  ┌ Specified ──┬─ Ready to Code ─┬─ Coding ──┬─ Done ──┐
│  (clipped    │  │ story cards │ story cards     │ cards /   │ cards   │
│   index deck)│  │             │                 │ batches   │         │
└──────────────┘  └─────────────┴─────────────────┴───────────┴─────────┘
     hover → menu of all items + dates
     select → edit window
```

The **backlog is one deck** (not a full column of separate idea cards). Execution columns hold real stories/batches only.

Optional filters on execution columns: theme; “needs approval”; hide Done.

### Columns (coarse lanes)

| Column / surface | Operator meaning | Packet / data source (folded) |
|------------------|------------------|-------------------------------|
| **Backlog deck** | Product intent not yet analyzed as squad stories | B35 items (`open`, etc.) — see [Backlog deck](#backlog-deck-b35) |
| **Specified** | Story exists; specs still in flight or not approved for code | Story recorded → Gherkin/QA procedure through reviews/approvals; not yet implementation-approved |
| **Ready to Code** | Specs + story gates done; waiting for implementer (or order gate) | Implementation approved / ready; no live coding yet |
| **Coding** | Code path through clean → harden → QA → architecture | `implemented` … until pre-final |
| **Done** | Story finished for this slice | `final_approved` |

Packet states fold into these lanes; fine grain is **badges**, not extra columns.

### Index card (single story)

**Sizing**

- Cards have a **minimum size** (readable index-card face).
- Above that minimum, size is **content-hugging only** — never stretch to fill the column width or height.
- Columns are only as wide as their widest card; empty lane space stays empty (corkboard), not inflated cards.
- Long titles ellipsize inside the max width rather than growing the card without bound (soft max width allowed).

**Always show**

- Title / story id (+ `#n` when numbered)
- Theme chip
- **Timer** — wall clock since last modification (`updated_at`); soft SLA colors (config). Prefer packet/theme/backlog durable timestamps over “now” invented in the browser.
- **Primary substate badge(s)** (1–3 most relevant)
- Optional: live agent face when work is active on this story

**Timestamps on the card / drawer**

| Field | Meaning |
|-------|---------|
| **Created** | `created_at` — first durable record of this story, theme, or backlog item |
| **Modified** | `updated_at` — last durable write that changed the item (packet stage change, backlog edit, theme package update, etc.) |

Drawer shows both as absolute timestamps; the face timer is relative age from **modified** (or from last column-changing transition when that is tracked separately).

### Story card → detail window

Clicking a story card opens a **small detached stats window** (movable, not a tab):

| Section | Content |
|---------|---------|
| **Header** | Story id / number / title, theme, board column |
| **Dates** | Created, modified, relative age |
| **Badges** | Current substate stickers |
| **Pipeline stats** | Compact grid: Gherkin, QA procedure, implementation, cleaner, code review, hardener, QA, architecture (from packet) |
| **Full story** | **View full story** control expands or opens the complete story markdown (project `stories/<id>.md` text) in the same window (scrollable) or a larger detached reader |
| **Agent session** | If the story has an **active worker**, show role + agent id and an **Open agent session** link/button that focuses the **detached session window** for that agent. If idle, show muted “No agent session on this story.” |

Closing the detail window does not stop agents or change packet state.

**Substate badges (stickers, not columns)**

| Badge | When |
|-------|------|
| Gherkin done | Gherkin accepted (optionally + approved) |
| QA proc done | QA procedure accepted (optionally + approved) |
| Being reviewed | Open review assignment (gherkin / QA proc / code / architecture) |
| Rework | Current `changes-requested` (or post-revision implementer) |
| Cleaned | Cleaner recorded; code review not finished |
| Hardened | Hardener done / hardening approved |
| QA accepted | Story QA batch approved |
| Arch rework | Architecture changes-requested or senior-implementer path |
| Order-blocked | Implementation order waiting on providers |

**Card sketch**

```
┌─────────────────────────────┐
│ #7  terminal-ui      ⏱ 47m │
│ theme: hunt-the-wumpus      │
│ ┌Rework┐ ┌Cleaned┐          │
│ code review · changes req.  │
│ agent: implementer-010      │
└─────────────────────────────┘
```

**Interactions (story / batch cards)**

| Action | Behavior |
|--------|----------|
| Click story card | Drawer: stage detail, artifact links, packet summary, last events, created/modified |
| Click batch stack | Batch drawer; hover stack → member name popup (see batches) |
| Story / batch | No drag between columns (v1) |

Backlog interactions are defined under [Backlog deck](#backlog-deck-b35) (not per-card column actions).

### Batches — clipped card stacks

A multi-story **batch** (hardener / QA / architecture) is a **set of index cards clipped together**, not one fat card and not only N independent cards.

```
        ┌──────────────┐  ← face (top) card
      ┌─┤ Hardener ×3  │
    ┌─┤ │ ⏱ 28m        │
    │ │ │ [clip]       │
    │ └─┴──────────────┘
    │   offset edges = stacked / clipped
```

**Visual rules**

| Rule | Detail |
|------|--------|
| **Stack** | 2–4 card edges peeping (offset ~4–6px). Cap peeks so large batches stay readable. |
| **Clip** | Paperclip / binder icon or “BATCH” corner tab on the stack. |
| **Face card** | Batch kind (Hardener / QA / Architecture), timer (batch activity or oldest member), batch substate, count badge (`×N`). |
| **Column** | Whole stack in one column from **batch stage** (members move together). |
| **No double vision** | Members of an **active** batch do not also appear as free-floating cards in the same column. |
| **Identity** | Stack key = `batch_id` (from packet `*_batch` / `.squad/batches/<id>/`). |
| **Batch of 1** | Normal single card; optional tiny clip chrome or none. |
| **Done** | Prefer **dissolve** stack into individual Done cards when batch completes (clean Done column). |

**Hover / focus popup (required)**

Hovering (or keyboard-focusing) the stack shows a popup of **every member story name**:

```
┌ Batch: hunt-the-wumpus-hardener-r3 ─────────┐
│ Hardener · 3 stories · ⏱ 28m                 │
│─────────────────────────────────────────────│
│ • cave-setup                                 │
│ • room-perception                            │
│ • wumpus-wake                                │
└─────────────────────────────────────────────┘
```

| Detail | Behavior |
|--------|----------|
| Content | Story display name / id for each manifest member; optional per-row substate later |
| Order | Manifest order or story number |
| Open delay | ~200–300ms (avoid flicker while scrubbing) |
| Position | Anchored to stack; flip if near viewport edge |
| Dismiss | Leave stack+menu; Esc; scroll |
| Click stack | Drawer for **batch** (assignment, agent, links) |
| Click popup row | Focus that story’s detail |
| Touch | First tap opens popup; second tap navigates |

**Data sources**

- `.squad/batches/<batch-id>/manifest.tsv`
- Packet fields: `hardener_batch`, `qa_batch`, `architecture_batch` (active membership)
- Board read model groups members of an active batch into one stack

**Edge cases**

| Case | Behavior |
|------|----------|
| One member in rework | Stack remains; popup row may show ⚠; Ops owns deep blockers |
| Story needs solo work while batched | Prefer stack-only while batch active |
| Failed batch | Stack stays until residual/repair clears membership |

---

## Ops region (rail of combined cockpit)

Machine status + TS chat. **Dense, scannable, needs-you first.**  
In the combined layout this is the **right rail** (+ full-width Attention). It is not a second product window by default.

### Problems with the current single-page dump

- Full-width stacked sections (blockers → approvals → TS → theme → stories → agents → assignments) force long scroll.
- Empty sections still cost vertical space and attention.
- Stories duplicate the Board.
- Troubleshooter history competes with status for the same column.
- Tables use generous padding; few rows still feel “tall.”

### Tightening principles

1. **Attention full width; pulse + work + chat in the rail** (or Status drawer on narrow screens).
2. **Collapse empty** — zero pending → single muted “All clear” line, not three empty tables.
3. **Density** — compact rows (≈28–32px), 11–12px meta text, pills over paragraphs.
4. **No Stories list** in Ops — Board owns story cards.
5. **Chat is contained** — fixed-height history + composer inside the rail; full TS session via **Open TS** (detached movable window, not a tab).
6. **Stable chrome** — meta, Open SL/TS, Teardown in a thin header (no Board|Ops split by default).
7. **Detail undock** — theme/story package views can detach; never only “open in new tab.”

### Proposed layout (narrow / secondary)

Stack: **Attention → Live agents (chips) → Work in flight → Themes (collapsed) → TS**.  
Prefer horizontal chips for agents; keep TS history short.

### Band details

#### 1. Attention (top priority)

Merge **stalls + blockers + approvals** into one band with three labeled buckets (or tabs if crowded).

| Bucket | Row content | Actions |
|--------|-------------|---------|
| **Stalls** | agent/assignment · why · age | Link to agent window; no fake “resolve” |
| **Blockers** | assignment · reason (one line) | Link Ops/Board; recover path unchanged |
| **Approvals** | gate · target (story/theme) · age | **Approve** / **Reject** inline |

- Max ~5 rows visible per bucket; “Show all” expands.
- Whole band **hidden** when all three counts are zero (or single “All clear” row — pick one; prefer **All clear** so the operator knows the strip isn’t broken).

#### 2. Pulse — Themes + Live agents (two columns)

**Themes (left)** — not a wall of prose:

| Compact card line | Example |
|-------------------|---------|
| id + lifecycle pill | `hunt-the-wumpus` · `open` / `finalized` |
| three glyphs/pills | order · checker · map (ok / missing / awaiting approval) |
| expand | Click card → peek drawer **or** Detach → movable theme display window |

Multiple themes = horizontal wrap of small cards, not full tables.

**Live agents (right)** — **chips**, not a full table:

- Always: **SL**, **TS** (color: idle / busy / dead).
- Then: active transients only (template + short id + state pill).
- Retired / hidden agents: never listed.
- Dead (B38): chip turns danger; optional one-line repair hint.
- “Open” on SL/TS in the chip row → detached session window (not a browser tab).

#### 3. Work in flight (assignments)

One dense table, **active only** (existing dashboard filter):

| Column | Width habit |
|--------|-------------|
| Story / batch | ellipsis; click → Board |
| Role | `implementer`, `hardener`, … |
| State | pill |
| Age | from assignment `updated_at` |

Group optional: by story (one header row + children) when >8 rows.  
Cap height (~240px) + scroll inside the band so TS stays reachable.

#### 4. Troubleshooter (contained footer)

| Piece | Spec |
|-------|------|
| Header | Title + busy indicator + Open TS (detached window, one line) |
| History (TS response) | **Scrollable** region (flex-grow inside rail); **oldest at top, newest at bottom**; does not push the page. On poll refresh: if the operator scrolled up, **keep that scroll position**; if they were near the bottom (or the list fits), stick to the bottom so new turns appear in view |
| Composer (TS command) | **Four lines tall**, `overflow-y: auto` if typed content exceeds four lines; Send beside; **Enter** sends; **Shift+Enter** inserts a line break |
| Empty history | One muted line, no large blank panel |

**Scrolling rule (whole dashboard):** any band/section that can grow vertically uses **inner scroll** (`min-height: 0` + `overflow: auto`) rather than expanding the page. Applies to Attention (when many rows), Board columns with many cards, Ops rail sections (Themes, Agents, Work in flight, TS history), and detached window bodies.

**Work in flight table:** `table-layout: fixed`. Column widths must be **wide enough for full headers** (Story, Role, State, Age) without abbreviating header text. **Role / State / Age** stay modestly fixed; **Story** takes the remainder. Cell text is shown in full and **only CSS-ellipsis** when it exceeds the column width (no pre-truncating story names in JS). Full values on `title` tooltips.

**Batch hover:** hovering a clipped batch stack shows a popup listing **every member story name** (portaled/fixed so board column outlines do not clip it).

Do **not** put a long help paragraph under the title; tooltip or one short muted clause is enough.

### What leaves Ops

| Move / drop | Why |
|-------------|-----|
| **Stories** full list | Board owns progress |
| Empty **Blockers** / **Approvals** / **Stalls** as separate full sections | Merged into Attention |
| Full agent roster including retired | Noise |
| Terminal assignments | Already filtered; keep strict |
| Theme essay text on the main surface | Drawer / expand only |

### Density checklist (implementation cues)

- Section `h2` + table + 18px gaps → band title 12px uppercase + 8px gap.
- Prefer **one** scroll on `main` plus **inner** scroll on history and long assignment lists.
- Approval buttons stay on the Attention row (no second jump to a distant Approvals section).
- Meta in header: last poll time, transient slot count, squadd liveness — not a separate “status” block.

### ASCII — calm state (nothing needs you)

```
[Board | Ops]  squad · ok · 2/8 slots                              [Teardown]

No stalls · No blockers · No pending approvals

Themes                          Live
┌ hunt-the-wumpus · open ┐     SL ● idle   TS ● idle
│ order✓ checker✓ map✓   │     implementer-011 · running
└────────────────────────┘

Work in flight
 terminal-ui   implementer   running   4m

Troubleshooter                              [Open ↗]
 (history…)
 [message…                              ] [Send]
```

### Success criteria (Ops / combined)

- With pending approvals, **Approve** is visible without scrolling past the Board or burying chat.
- Board + Attention + rail fit a typical laptop without hunting a second dashboard tab.
- Operator can answer in seconds: *Do I need to act? Who is live? What is in flight? Where is each story?*
- **Open TS** / **Open SL** always available as **detached movable windows**; dashboard chat does not replace session windows; **no browser-tab sprawl**.
- No durable field present only on a “hidden” view — peeks/hover hide bulk; Detach keeps full data in a movable window.

---

## Backlog deck (B35)

Durable product intake owned by the **swarm project**, not SwarmForge `issues.md`.  
On the Board the backlog appears as a **deck of index cards** (one stack), not a column of individual idea cards.

### Face of the deck

```
      ┌─────────────┐
    ┌─┤ Backlog  ×N │
  ┌─┤ │ ⏱ newest    │
  │ │ │ [deck]      │
  │ └─┴─────────────┘
```

| Face shows | Detail |
|------------|--------|
| Label | “Backlog” |
| Count | Number of open (and optionally dispatched-not-yet-linked) items |
| Timer | Age of most recently **modified** item (`updated_at`) |
| Empty | Muted empty deck + “Add item” affordance |

### Hover popup — full item menu

**Hovering (or focusing) the deck** opens a popup menu of **all backlog items**, with dates. Long lists **scroll** inside the menu.

```
┌ Backlog ─────────────────────────────────────┐
│ [+ New item]                                 │
│──────────────────────────────────────────────│
│ Wumpus terminal polish                       │
│   created 2026-08-10  ·  modified 2026-08-15 │
│──────────────────────────────────────────────│
│ Dice app theme                               │
│   created 2026-08-12  ·  modified 2026-08-12 │
│──────────────────────────────────────────────│
│ … (scroll)                                   │
└──────────────────────────────────────────────┘
```

| Rule | Behavior |
|------|----------|
| Rows | One row per backlog item: **title** (primary), **created_at** and **updated_at** (secondary line or columns) |
| Order | Default: `updated_at` descending (most recently touched first) |
| Scroll | Menu has max-height; overflow scrolls; keyboard ↑/↓ + Enter supported |
| Open delay | ~200–300ms (same spirit as batch hover) |
| Dismiss | Leave deck+menu; Esc; scroll board |
| Touch | First tap opens menu; second tap selects a row |
| + New item | Prefer a dedicated **Add New Item** control **below the backlog deck** (not only in the menu). Opens a **detached movable edit window** (not a browser tab). |

### Add New Item + edit window (detached)

**Add New Item** (below the deck) opens an empty **detached edit window**. Selecting a backlog row opens the same window loaded with that item.

| Control | Behavior |
|---------|----------|
| **Fields** | Title, body (multiline). Operator does **not** choose theme vs story. |
| **Dates** | Show **Created** and **Modified** when the item already exists (read-only). |
| **Cancel** | Close window; discard unsaved edits (confirm if dirty). |
| **Add** (new) / **Save** (existing) | Persist to backlog only; bump `created_at`/`updated_at` as appropriate; item stays in the deck (`open`). Does **not** enter the pipeline. |
| **Delete** | Confirm; remove or soft-`cancelled` (existing items). |
| **Approve** | Enter the **pipeline**. Mark backlog item `dispatched` / approved; notify **Squad Leader**. **SL decides** whether this is a **new theme** or a **story on an existing theme** (and which theme). Operator does not classify. Analyst path follows SL’s judgment. |

Optional later: **→ Troubleshooter** from the edit window (clarify/split) without Approve.

**Approve** is the product gate into the swarm. Implementation: product request / `route-to-sl` (or equivalent) with item body; SL residual classifies theme vs story and creates theme/story + analyst work as today.

### Store (suggested)

`.squad/backlog/` (or `.swarmforge/product-backlog/`)

| Field | Notes |
|-------|--------|
| id, title, body | Multiline body supported (B10) |
| kind intent | `theme` \| `story` \| `unspecified` |
| status | `open` \| `approved_for_analysis` / `dispatched` \| `done` \| `cancelled` |
| **created_at** | Set once on create (ISO-8601 UTC); never cleared |
| **updated_at** | Set on every durable edit (title/body/status/links/approve) |
| links | Optional `theme_id`, `story_id`, dashboard request id after approve/dispatch |

### Boundaries

- Not a second story FSM; not a replacement for packets.
- Deck + edit window are the only backlog UI on the Board; items do not appear as free-floating column cards until they become real stories (then they land in **Specified**, etc.).
- Dates in the hover menu and edit window come from durable store fields, not browser-only clocks.

---

## Timestamps (stories, themes, backlog)

All durable product objects on the board carry creation and modification times.

| Object | Where | created_at | updated_at |
|--------|--------|------------|------------|
| **Backlog item** | Backlog store (B35) | On first write | On any field change or dispatch |
| **Theme** | Theme package / status (e.g. `.squad/themes/<id>/`) | When theme is first created/recorded | When theme, module map, order, checker, approvals, or lifecycle (finalize/reopen) changes |
| **Story** | Story packet (`.squad/stories/<id>/packet`) | When packet is first created | On every packet write that changes content (attach, review, result, approval, etc.) |

**Rules**

1. Writers (helpers / residual / SL tools) set timestamps; the dashboard does not invent durable dates.
2. **created_at** is immutable after first set. If missing on legacy records, leave blank or backfill once from file mtime only as a migration—do not keep using mtime as truth.
3. **updated_at** moves forward on every meaningful durable change (not on pure UI poll).
4. Board timer and sort (“recently active”) use **updated_at** unless a more specific stage-entered-at is added later.
5. Ops lists (assignments, agents) keep their own status timestamps; they are not a substitute for story/theme/backlog created/updated.

**UI**

- Card: relative **modified** age (timer).
- Drawer / backlog detail: **Created** and **Modified** absolute times.
- Optional sort: Backlog and columns by `updated_at` or `created_at` (operator preference later).

---

## End-to-end flow

```
Operator opens backlog deck → hover menu (titles + dates)
       │
       ├─→ select item → edit window → Save / Delete
       │
       └─→ Approve for analysis → SL / analyst path
                                      │
                                      ▼
                         Board columns (packet-derived stories)
                         Batches = clipped stacks + hover names
                                      │
                         Ops (approvals, agents, chat, blockers)
```

---

## Phased delivery

| Phase | Scope | Bugs |
|-------|--------|------|
| **A** | **Combined cockpit** shell: Attention + Board center + Ops rail; map packets → 4 columns; basic cards + timer; dense Ops (no flat Stories list); keep Open SL/TS | B24 core |
| **B** | Substate badges; theme filter; card drawer; **batch stacks + hover member popup**; deep links Ops → Board | B24 |
| **C** | **Backlog deck** + hover menu (titles + created/modified) + edit window (save / delete / **approve for analysis**); durable store with timestamps; link to theme/story after dispatch | B35 |
| **D** | SLA colors; needs-approval pulse; optional theme swimlanes; Done archive vs theme finalize (B23); optional → TS from edit window | polish |

Out of scope for early phases: drag-and-drop column moves, editable board state for stories, third mailbox beyond dashboard request / TS chat.

---

## Open decisions

1. Ops rail width (~320–380px) vs collapsible Status drawer breakpoint.
2. Timer basis: **updated_at** only vs last column-changing transition (if tracked separately).
3. Edit window: modal overlay vs side panel vs separate route.
4. “Ready to Code” + order-blocked: column only vs badge only vs both.
5. Batch of 1: clip chrome or plain card.
6. Soft SLA thresholds (defaults TBD in config).
7. Legacy packets/themes without `created_at`: blank vs one-time mtime backfill migration.
8. After **Approve for analysis**: hide item from deck immediately vs keep as `dispatched` until theme/story link exists.
9. Soft delete (`cancelled`) vs hard delete for backlog items.
10. Whether fallback `/board` + `/ops` routes ship in v1 or only combined.
11. Host for detached windows: desktop shell child windows vs controlled popups (must still feel like movable windows, not tabs).
12. Persist window geometry (size/position) per window kind / id.

---

## Interaction rules (board ↔ work in flight)

Recorded from prototype iteration (`dashboard-mockup.html`). Product UI must match these.

### Hover portals (no clipping)

Backlog item menus and batch member lists are **fixed-position portals** outside the board’s overflow/outline (not nested under the dashed column box). Position with the anchor’s `getBoundingClientRect()`. Stay open while pointer is on anchor **or** menu; short hide delay on leave.

### Batch ×N (board stack)

| Action | Result |
|--------|--------|
| Hover stack | Popup lists **every member story name** (from batch manifest / `db.batches`) |
| Hover stack | Highlight stack + member **cards** on the board + the **batch Work-in-flight row only** |

### Work in flight (WIF)

| Action | Result |
|--------|--------|
| Hover **batch ×N** row | Member-name popup; highlight board batch stack + member cards; highlight **only that batch WIF row** — **not** separate WIF lines that merely share a member story id (e.g. do not light `wumpus-wake` just because it is in the batch) |
| Hover a **story** WIF row | Highlight matching board card(s); highlight that WIF row; if the story is a batch member, may also light the batch stack / batch WIF row |
| Hover truncated cell | Native `title` (or equivalent) shows **full** story/role/state text — no JS pre-abbreviation of story names |

### Board story card

| Action | Result |
|--------|--------|
| Hover card | Highlight matching WIF row(s) |
| Click card | Detached story window: dates, badges, pipeline stats, **View full story**, **Open agent session** when a worker is active |

### Work in flight columns

- Headers always full words: Story, Role, State, Age (columns wide enough for headers).
- Story column flexible; Role/State/Age modest fixed widths.
- Ellipsis only via CSS when content exceeds cell width.

---

## Success criteria

- Operator answers in a few seconds: which stories are coding, which wait on me, which are done.
- Operator sees multi-story work as **one clipped batch** and can read **member names on hover**.
- Operator can manage product intent via **backlog deck → menu → edit window** (save / delete / approve for analysis) without chat-only paste.
- Bidirectional WIF ↔ board highlight works without false positives on member WIF lines when hovering the batch row.
- SL/TS still drive execution via residual; UI observes and dispatches only.

---

## Related

- **Prototype:** `dashboard-mockup.html` (behavioral reference for board/ops/backlog/hover)
- **Product:** `swarmforge/scripts/squadd/web.clj`, `swarmforge/scripts/squadd/dashboard.html`
- `issues.md` — B24, B35 (this design); **B15** separate (Grok terminal geometry)
- Packet stages — `swarmforge/scripts/squad_state.clj`
- Theme finalize — B23; dead-agent repair — B38 (Ops agent visibility)
- Chat multiline / inject — B10, B34
