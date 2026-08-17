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
| **P3** | B40 | Finish architecture migration after B16–B22 foundations | Architecture | Control plane / durable state |

---

## Suggested fix order

1. **P3 polish:** **B15** (Grok terminal chrome — not the web cockpit).
2. **P3 architecture follow-through:** **B40** when changing control-plane code anyway (not blocking swarms).

---

## Clusters

| Cluster | Bugs | Note |
|---------|------|------|
| Packet repair / rework cycle | — | **B39** done |
| Operator chat / dashboard IO | — | **B24** done; B34/B10/B37/B29/B36/B14 earlier |
| Product intake | — | **B35** done |
| Lifecycle hygiene | — | B11/B12/B37/B38 done |
| Theme / architecture gates | — | B23 finalize + B25/B13/B14 done |
| Control plane | **B40** | B18/B16/B19 foundations done; residual migration |
| Deep durable arch | **B40** | B20–B22 foundations done; more call sites |
| Terminal chrome | **B15** | Grok pane geometry (orthogonal to dashboard) |

Former free-standing notes (`architecture-improvements.md`) are superseded: foundations landed as B16–B22; **gaps = B40**.

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

---

## P3 — Terminal polish

### B15 — Grok agent terminal window geometry / scroll

**Symptom:** Grok panes ~25 lines pinned; scroll unusable. Codex OK.

**Expected:** Full geometry or documented operator path.

**Where:** Grok launch / tmux size; `swarmforge/docs/grok-agent-window-scroll.md`.

---

## P3 — Architecture follow-through

### B40 — Finish architecture migration after B16–B22 foundations

**Context:** Review findings that became B16–B22 landed **foundations** (control-plane policy/authority/executor/renderer; lease; accept-merge transition; shared records helpers; Troubleshooter vs SL ownership). The following **deltas remain** — incremental migration, not a missing first cut.

| Residual gap | Related foundation | What’s left |
|--------------|-------------------|-------------|
| **Authority surface incomplete** | B16 | Not every SL-facing entrypoint is blocked from daemon-only ops; env/prompt still share some ownership load |
| **`squad_next` still a mixed kernel** | B18/B19 | State scanning, candidate building, and residual selection still live largely in `squad_next`; further extraction optional but desirable |
| **Actions still often shell strings** | B17 typed actions | Structured `{:op … :authority …}` is partial; many paths still render/`bash -c` command strings mid-stack |
| **Leases not universal** | B20 `squad_lease` | Main-git + spawn/retire use lease; other locks (handoff in-process/held, ad hoc status, some queues) not fully on the common lease model |
| **Record formats not fully standardized** | B22 `squad_records` | Helpers exist (kv / headers+body / edn / events); many call sites still ad hoc parse/write |
| **Transitions beyond accept-merge** | B21 `squad_transition` | Accept-merge is transition-shaped; other multi-file FSM updates may still hide multi-writer side effects |
| **Priority policy call-site drift** | B18 | Central policy module exists; ensure all ordering decisions go through it when touching scheduler paths |

**Not residual (already addressed directionally):** SL vs Troubleshooter product ownership (B09 / route-to-sl / B35 approve-to-SL).

**Priority (P3):** Does not block multi-story swarms. Prefer doing slices when editing the same modules (e.g. next time `squad_next` or merge path is open).

**North star (unchanged):** single-writer; typed planner/executor; durable leases and record helpers — reduce races with structure, not more prompt text or one-off retries.

**Where:** `squad_next.clj`, `squad_control_plane.clj`, `squad_executor.clj`, `squad_renderer.clj`, `squad_lease.clj`, `squad_transition.clj`, `squad_records.clj`, assign/merge call sites.

**Done when:** New workflow changes go through typed actions + policy; new durable writes use lease/records/transition helpers by default; remaining stringy/`bash -c` and informal parsers are exceptional and documented.

---

## Architecture north star

Keep the **single-writer** direction. Prefer **typed actions + planner/executor**, then **durable readers/leases**, over more prompt text or one-off retries.

**Status:** Control plane and deep arch **foundations** done (B16–B22); residual migration tracked as **B40**. B39, B24/B35 done.  
**Next:** **B15**, then **B40** opportunistically.  
Dashboard design: `ui-design.md`; prototype: `dashboard-mockup.html`.
