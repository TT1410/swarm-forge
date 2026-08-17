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
| — | — | *No open issues.* | — | — |

---

## Suggested fix order

*Backlog clear.* Prefer opportunistic hardening when touching control-plane or dashboard code.

---

## Clusters

| Cluster | Bugs | Note |
|---------|------|------|
| Packet repair / rework cycle | — | **B39** done |
| Operator chat / dashboard IO | — | **B52**, **B42**–**B51**, **B54**, **B44** done |
| Product intake | — | **B35** + **B53** done |
| Lifecycle hygiene | — | B11/B12/B37/B38 done |
| Theme / architecture gates | — | B23 finalize + B25/B13/B14 done |
| Control plane | — | B18/B16/B19 + **B40** records call-sites |
| Deep durable arch | — | B20–B22 + **B40** write-atomic/kv adoption |
| Terminal chrome | — | **B15**, **B41** done |

Former free-standing notes (`architecture-improvements.md`) are superseded: foundations landed as B16–B22; residual call-site migration **B40** done.

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
| P3 terminal + arch close | **B15**, **B41**, **B40** | Grok `--minimal --no-alt-screen` + tmux geometry/history + larger pane capture; SL+TS `window-invisible` by default; records call-site migration |
| P1 product intake | **B53** | Backlog approve body survives B10 re-parse; only known request fields end multiline blocks; approve message avoids bare `key: value` lines |
| P2 TS chat | **B52** | Chat stick-to-bottom only when operator is near bottom / after send; scroll listener preserves history position across poll |
| P3 dashboard batch | **B42**–**B51**, **B54**, **B44** | Theme View link; WIP/live-agent session links; Finalizing column; pipeline sort WIP+cards; status green glow 1s; button highlight black text; title stamp + next_action; resizable Board/Ops split; column height fills board |

---

## Architecture north star

Keep the **single-writer** direction. Prefer **typed actions + planner/executor**, then **durable readers/leases**, over more prompt text or one-off retries.

**Status:** All tracked issues closed. Control plane and deep arch foundations (B16–B22) + **B40**; terminal **B15/B41**; cockpit **B24/B35** + polish **B42**–**B54**; product intake **B53**.  
Dashboard design: `ui-design.md`; prototype: `dashboard-mockup.html`. Grok scroll: `swarmforge/docs/grok-agent-window-scroll.md`.
