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

---

## Suggested fix order

1. **P2:** **B103** (project-first intake/start), **B89** (project subdirectories).

---

## Clusters

| Cluster | Bugs | Note |
|---------|------|------|
| Product intake | **B89**, **B103** | Project directories; project-first start |

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

**Related:** B89 project directories; B35 backlog approve-for-analysis.

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
