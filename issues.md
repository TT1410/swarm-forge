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
| **P3** | B15 | Grok terminal window geometry / scroll | UX | Terminal |

---

## Suggested fix order

1. **P3 polish:** **B15** (Grok terminal chrome — not the web cockpit).

---

## Clusters

| Cluster | Bugs | Note |
|---------|------|------|
| Packet repair / rework cycle | — | **B39** done (no superseded cleaner/CR re-record after clear-downstream) |
| Operator chat / dashboard IO | — | **B24** done (combined cockpit); B34/B10/B37/B29/B36/B14 earlier |
| Product intake | — | **B35** done (backlog deck + approve → SL) |
| Lifecycle hygiene | — | B11/B12/B37/B38 done |
| Theme / architecture gates | — | B23 finalize + B25/B13/B14 done |
| Control plane | — | B18/B16/B19 + B20 lease + B21 transition + B22 records done |
| Terminal chrome | **B15** | Grok pane geometry (orthogonal to dashboard) |

Source notes for B16–B22: `architecture-improvements.md` (review findings folded in).

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
| P3 dashboard cockpit | **B24**, **B35** | Combined Board+Ops UI (`squadd/dashboard.html`); durable `.squad/backlog` + approve-for-analysis → SL; see `ui-design.md` + `dashboard-mockup.html` |

**Partial progress (still open under related IDs):**
- Analysis authors checker + order (prompt/contract); seed order when implementer-ready; quality + user approval (B13/B25) done.
- Theme package shows Implementation Order and Dependency Checker with status (missing / hollow / awaiting approval / approved).
- Troubleshooter not counted as active transient; product chat `route-to-sl` to SL.
- Dead-agent repair (B38): classify + residual + repair command; SL/TS execute repair (not full daemon auto).
- Theme finalize (B23): lifecycle file + finalize/reopen commands + package card + residual idle.
- Control plane (B18/B16/B19): policy + authority modules; squad_next still holds state scanning (further extraction optional).
- Deep arch (B20–B22): lease used for main-git + spawn/retire; accept-merge durable writes via transition; shared records helpers (more call sites can migrate).

---

## P3 — Terminal polish

### B15 — Grok agent terminal window geometry / scroll

**Symptom:** Grok panes ~25 lines pinned; scroll unusable. Codex OK.

**Expected:** Full geometry or documented operator path.

**Where:** Grok launch / tmux size; `swarmforge/docs/grok-agent-window-scroll.md`.

---

## Architecture north star

Keep the **single-writer** direction. Prefer **typed actions + planner/executor**, then **durable readers/leases**, over more prompt text or one-off retries.

**Status:** P0–P2 control plane and deep arch foundation done; **B39** post-rework packet repair done; **B24/B35** combined cockpit + backlog done.  
**Next:** **B15** (Grok terminal window geometry).  
Deep arch modules: `squad_lease` (B20), `squad_transition` (B21 accept-merge), `squad_records` (B22). Further migration of call sites is incremental. Dashboard design: `ui-design.md`; prototype: `dashboard-mockup.html`.
