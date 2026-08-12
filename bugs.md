# Bugs

Prioritized open issues. Priority is **impact on swarm correctness and operator unblock**, not chronological discovery.

| Pri | ID | Title | Area |
|-----|-----|--------|------|
| **P1** | B05 | No acceptance pipeline — Gherkin mutation and full suite cannot run | Quality / APS |
| **P1** | B06 | Agents run CRAP / clj-mutate without coverage data | Quality tools |
| **P1** | B07 | Late roles skip full acceptance suite before handoff | Role contracts |
| **P1** | B08 | Mechanical apply crashes when agent heartbeat disappears mid-read | Daemon reliability |
| **P2** | B09 | Operator unblock needs Troubleshooter (not SL) | Roles / dashboard |
| **P2** | B10 | Dashboard SL answers truncate to first line | Dashboard IO |
| **P2** | B11 | Zombie tmux sessions after agent retire | Lifecycle cleanup |
| **P2** | B12 | Hardener edits root tooling (`bb.edn`) against role rules | Role policy |
| **P3** | B13 | Analyst dependency-checker policy missing or coarse | Analysis quality |
| **P3** | B14 | Theme package page missing `dependency-checker.edn` card | Dashboard UI |
| **P3** | B15 | Grok agent terminal window does not fill / scroll correctly | Operator UX |

**Fixed (removed):** P0 B01 implementer rework thrash; B02 held handoff finish; B03 durable implementation-order gate; B04 spawn queue template HOL.

**Suggested fix order:** B05–B07 as one **quality-gate cluster** (APS + coverage + handoff suite), then B08, then P2 operator/UX, then P3 architecture polish.

**Related clusters**

| Cluster | Bugs | Note |
|---------|------|------|
| Acceptance / hardening false green | B05, B06, B07 | No runner, no LCOV, soft prompts |
| Dependency-checker | B13, B14 | Analyst policy + theme UI |

---

## P1 — Quality gates and daemon reliability

### B05 — No acceptance pipeline — Gherkin mutation and full acceptance suite cannot run

**Symptom:** Hardener (and similarly late roles) install `gherkin-mutator` / `gherkin-parser` but **do not run Gherkin mutation**. Agents report e.g. “Gherkin mutator not run because no acceptance runner worker is configured” or that the QA harness is a fixed transcript script, not a generated-feature runner. Full acceptance suite before handoff (B07) also fails for the same underlying gap: there is no project acceptance command to run.

**Cause:** Product never gains the **Acceptance Pipeline Specification (APS)** wiring that constitution requires. Live product has:
- `features/*.feature` (from gherkin-writers)
- unit tests via `bb test`
- optional batch QA script (`qa/scripts/…`) as a **hand-written transcript harness**

Missing (constitution **Acceptance Pipeline**): acceptance entrypoint/generator, acceptance runtime, project step handlers, **runner adapter**, convenience scripts, and a stable command usable as:

```text
gherkin-mutator --runner-worker <command>
```

Implementer role says “if assigned acceptance tests fail, keep working,” but implementer **required tools** are only `dependency-checker`; assignments do not force APS setup. Implementers (and early tooling stories) ship units + features without a runnable acceptance pipeline. Hardener correctly cannot invent that stack at harden time.

**Expected:**  
1. By the time stories are implementable (or as first implementation / project-setup work), the product has a **working acceptance runner** driven by accepted Gherkin (generate + run features).  
2. Canonical command(s) exist (e.g. `bb` task or script) that execute one or all acceptance features.  
3. Hardener runs **Gherkin mutation** with `--runner-worker` pointing at that command, plus code mutation / CRAP / DRY as today.  
4. QA / hardener / architect / senior-implementer can run the **full acceptance suite** before handoff (B07).  
5. Workflow or assignment text makes APS setup a **blocking** implementer (or dedicated setup) duty when features exist and the runner does not.

**Solution direction:**  
- Prompt/contract: implementer (or first story / tooling story) owns APS project components, not only process units.  
- Template product `bb.edn` / docs: acceptance tasks + runner-worker example.  
- Gate hardener “Gherkin mutation complete” or fail soft with **blocker** to SL if runner missing (don’t silently skip).  
- Align with B07 — same pipeline, two consumers.

**Where:** `constitution/articles/engineering.prompt` (Acceptance Pipeline); `role-templates/implementer.*` (tools + duties); `hardener.contract.edn` / hardener assignment Tool Startup; product `bb.edn` / missing acceptance sources; live hardener-002/005 panes and tool-table `gherkin-mutator`.

**Repro (live):** Hunt the Wumpus product: features present, `bb test` only in `bb.edn`; hardener-002/005 parse Gherkin and run `clj-mutate` but skip `gherkin-mutator` for lack of `--runner-worker` / acceptance runner.

---

### B06 — Agents run CRAP / clj-mutate without coverage data

**Symptom:** Hardener (and other roles that use **CRAP** / **clj-mutate**) report “covered” mutation sites or CRAP scores without having generated or reused real line coverage. Live Babashka products often have no `target/coverage/lcov.info`. `clj-mutate` then treats **all** mutation sites as covered (bb default when LCOV is missing). CRAP without Cloverage/LCOV is similarly meaningless or misleading. Agents still hand off as if hardening/verification quality gates passed.

**Why it’s a bug:** Mutation and CRAP are **coverage-dependent** tools. Running them without coverage is not a valid quality signal. Coverage **can** be produced:
- with **`clj`** (Cloverage / project `:cov` alias → `target/coverage/lcov.info`), and/or  
- with **`bb`** where the project supports a coverage path (there is a Babashka-capable coverage approach; product must wire it or fall back to `clj`).

**Expected:** Every agent that runs **crap4clj** or **clj-mutate** must also be able to **run (or reuse valid) coverage** first:
1. Ensure LCOV (or equivalent) exists and is fresh for the modules under test, **or** run the project coverage command before CRAP/mutate.  
2. Prefer `clj-mutate --reuse-lcov` only after a successful coverage refresh—not as a way to skip coverage forever.  
3. Assignment Tool Startup / role prompts: coverage is a **required prerequisite** for CRAP and mutation, not optional.  
4. If neither `bb` nor `clj` coverage can be run in the worktree, record a **blocker** instead of faking full coverage / “N/N killed” without LCOV.

**Solution direction:**  
- Document the canonical coverage command for bb and clj product templates.  
- hardener/cleaner/qa (any CRAP/mutate role): Tool Startup requires coverage before those tools.  
- Fail or warn hard when mutate reports all-covered solely due to missing LCOV.  
- Product `deps.edn` / `bb.edn` templates include a working `:cov` / coverage task aimed at **product** `src` + tests (not SwarmForge’s own scripts).

**Where:** `hardener` / `cleaner` / `qa` prompts and contracts; `tool-table.edn`; product templates; live `~/junk/squad` — mutate with `bb test` only, no LCOV until operator ran Cloverage manually; hardener handoffs claiming full kill rates without coverage artifacts.

**Repro (live):** `clj-mutate` on `movement.clj` with only `bb test` → “13 covered / 0 uncovered” and no `target/coverage/lcov.info`. Separate Cloverage run then produced 100% LCOV—showing coverage was never part of the agent path.

---

### B07 — Hardener, architect, QA, senior-implementer skip full acceptance suite before handoff

**Symptom:** Late-stage quality agents hand off after partial verification (e.g. unit tests + custom batch QA script / focused checks) without evidence that the **full acceptance suite** (accepted Gherkin / generated acceptance tests for the merged product) was run and passed. Live example: batch QA reported `bb test` + `bb qa/scripts/hunt_the_wumpus_batch_qa.clj` + CRAP/DRY, not a full Gherkin acceptance run over `features/`.

**Expected:** Before handoff, **hardener**, **architect**, **qa**, and **senior-implementer** must each run (and pass, or report failure with detail) the **full acceptance suite** for the current merged product, in addition to whatever role-specific work they own (hardening, architecture critique, QA procedures, senior implementation). “Full acceptance suite” means the project’s accepted Gherkin / generated acceptance tests as a complete suite—not only unit tests, not only ad-hoc procedure scripts, not only focused smoke checks—unless the assignment explicitly narrows scope (it should not for these singleton/batch roles).

**Cause / gap:**  
- Role prompts are uneven: QA says run accepted Gherkin + full test suite; senior-implementer says full verification suite; hardener says “focused verification”; architect may not hard-require acceptance.  
- Assignments / mechanical verification do not **gate** handoff on an acceptance-suite command.  
- Agents reasonably optimize to units + local harness and still hand off.  
- **Blocked in practice by B05** when no acceptance runner exists.

**Solution direction:**  
1. **Prompt/contract:** Explicit same rule for hardener, architect, qa, senior-implementer: run full acceptance suite before handoff; record exact commands and results in the handoff/report.  
2. **Assignment template / Tool Startup or Leader Instructions:** Name the canonical acceptance command(s) for the product (e.g. project-standard Gherkin/acceptance runner).  
3. **Optional enforcement:** Require verification event / report section listing acceptance suite pass before `handoff_sent` is considered complete (or SL residual rejects incomplete QA/hardener/architect/senior handoffs).  
4. Land **B05** first (or in parallel) so the command exists.

**Where:** `swarmforge/role-templates/{hardener,architect,qa,senior-implementer}.prompt` (+ contracts); assignment generation for those templates; live batch QA reports under `qa/` as evidence of current behavior.

**Repro (live):** `hunt-the-wumpus-qa` / `-r2` handoff reports pass via unit suite + batch procedure harness without documenting full Gherkin acceptance; hardener/architect/senior paths similarly weak on acceptance unless agent chooses to run it.

---

### B08 — Mechanical apply crashes when agent heartbeat disappears mid-read

**Symptom:** Daemon log shows `workflow-mechanical-failed` with a full stack:

```text
java.io.FileNotFoundException: …/.squad/agents/<agent>/heartbeat (No such file or directory)
Location: squad_next.clj file-map → slurp
```

Mechanical poll fails that tick (exit non-zero). Later polls may recover.

**Cause:** `file-map` in `squad_next.clj` does `(when (fs/exists? file) (slurp …))`. Between `exists?` and `slurp`, retire or another path can delete the agent’s `heartbeat`/`status` (TOCTOU). Same pattern anywhere agent files are slurped without a safe open.

**Expected:** Missing agent telemetry is treated as empty/unknown state, not an uncaught exception. Mechanical apply never dies on a vanished heartbeat.

**Solution direction:**  
1. Catch `FileNotFoundException` / `IOException` in `file-map` (and similar readers) → return `{}`.  
2. Or open with try/slurp in one step; treat missing file as empty map.  
3. Prefer reading status/heartbeat under a stable agent lifecycle lock if races remain common.  
4. Test: delete heartbeat between exists and slurp (or mock); mechanical apply continues.

**Where:** `swarmforge/scripts/squad_next.clj` (`file-map` ~line 50); any shared file-map helpers used during retirement races.

**Repro (live):** `2026-08-11T21:15:03Z` — `workflow-mechanical-failed` on `code-reviewer-003/heartbeat` during mechanical apply while agents were turning over.

---

## P2 — Operator path and agent hygiene

### B09 — Operator unblock and dashboard requests need a Troubleshooter (not the squad leader)

**Symptom / gap:** When the workflow is stuck (e.g. implementer rework thrash, held-handoff never finishes, missing implementation-order record), an **operator** can reach in and fix state: force packet fields, block thrash assignments, retire agents, move held handoffs, etc. The **squad leader cannot** do the same under its rules even when it has the same helper tools on paper.

**Why the SL is too constrained:**  
- Residual (`squad_next.sh --residual-only`) is the **sole** workflow driver; SL must **not invent** transitions the advisor did not emit.  
- During thrash/stuck states residual often only says `wait`, `recover_agent`, or capacity waits — never “accept code review and kill the rework loop.”  
- So a well-behaved SL waits, recovers, or reports — it does **not** perform out-of-band packet surgery or operator-style unblocks.  
- **Dashboard / squad-leader requests** today wake and route to the **SL**, which is the wrong role for “fiddle the swarm until it unsticks.”

**Expected:** Introduce a **Troubleshooter** agent (persistent or on-demand), **outside the product workflow**:

| Property | Troubleshooter | Squad leader |
|----------|----------------|--------------|
| Part of story/pipeline FSM | **No** | Yes (orchestration) |
| Bound to residual-only for all work | **No** — may act outside advisor | Yes |
| May invent escape hatches / fix durable state | **Yes** when needed | No (don’t invent transitions) |
| May retire/block/force packet/theme fixes | **Yes** | Only if residual directs |
| Talks to user / dashboard | Primary for **debug and unblock** requests | Product orchestration, approvals framing |
| Talks to SL | **Yes** when workflow judgment or user-facing product sequencing is needed | Receives handoffs from troubleshooter when appropriate |

**Routing:**  
- **Squad leader request / dashboard request system** should **not** go to the squad leader by default.  
- It should go to the **Troubleshooter**.  
- Troubleshooter handles: stuck swarms, capacity weirdness, held handoffs, thrash loops, “make it unstick,” operator debug.  
- Troubleshooter **escalates or collaborates with SL** when the fix needs legitimate workflow action (spawn path, user approval framing, product sequencing) rather than state repair.

**Solution direction:**  
1. New role: `troubleshooter` (prompt + contract): unrestrained relative to residual, **not** a pipeline stage; explicit license to inspect deeply and repair swarm state; must not do product authoring that workers own.  
2. Dashboard requests / SL-request wake path → **troubleshooter** session (not squad-leader).  
3. Optional: residual or status alerts that look like “workflow stuck / thrash / held-finish failing” also notify troubleshooter.  
4. Keep SL residual-only for normal orchestration so it stays deterministic and trustworthy.  
5. Document: product bugs still get fixed in code; troubleshooter is the **in-swarm operator**, not a substitute for fixing FSM bugs (B01–B04).

**Where (today):** dashboard request wake in `squadd.clj` / `squadd/web.clj` → SL; SL prompt residual-only rules; no troubleshooter role.  
**Repro:** movement-hazards thrash — residual never offered accept+stop; operator (or a troubleshooter) had to force `squad_packet.sh review … accepted`, block thrash assignments, and retire agents. SL following rules could not.

---

### B10 — Dashboard SL answers truncate to first line of multiline response

**Symptom:** Operator asks via dashboard (e.g. “how do I run the game?”). SL writes a full answer file (multiple lines: intro, `bb run wumpus`, notes, related commands) and `squad_dashboard_request.sh answer` succeeds. **Dashboard UI only shows the first line**, e.g.  
`Run it from the repository root with:`  
— missing the actual command and everything after.

**Cause:** Request records use a line-oriented `key: value` format (`squad_dashboard_request.clj` `render-request` / `parse-kv` / `file-map`). Multiline `response` (or `body`/`detail`) is written as:

```text
response: Run it from the repository root with:

bb run wumpus
…
```

On read, **each line is parsed independently**; only the first line matches `response: …`. Following lines are not `key: value` and are dropped. Stored file still contains the rest as orphan lines, but API/`list-all-requests` expose truncated `response`. Dashboard then renders `esc(r.response)` (also no `white-space: pre-wrap`, which would break formatting even after the parse fix).

**Expected:** Full multiline answers round-trip: write → read → dashboard shows complete text with preserved line breaks (or a safe encoding).

**Solution direction:**  
1. Encode multiline fields (escape newlines, base64, or `response: |` block / length-prefixed body after a blank line header section).  
2. Or store answer as a sibling file `…request.answer` and reference path in the kv file.  
3. Dashboard: `white-space: pre-wrap` (or `<pre>`) on `.req-sl` / request body so newlines render.  
4. Test: answer with blank lines and commands; API and UI show full text.

**Where:** `swarmforge/scripts/squad_dashboard_request.clj` (`render-request`, `parse-kv`, `file-map`, `answer-request`); `squadd/web.clj` `renderRequests` / `.req-sl` CSS.

**Repro (live):** `dashboard-20260811T221429Z-001` — answer file had full `bb run wumpus` instructions; `.request` file has full text after first line; `response:` field as read by `file-map` is only `Run it from the repository root with:`; operator saw only that in the dashboard window.

---

### B11 — Zombie tmux sessions after agent retire

**Symptom:** Agent status is **retired**, worktree removed, agent gone from `roles.tsv`, residual reports no active transients — but **tmux sessions still exist** (e.g. `swarmforge-cleaner-006`, `swarmforge-hardener-001`, `swarmforge-implementer-001`, `swarmforge-qa-011`). Retire detail sometimes says *“tmux session was not running”* even when the session is still listed in `tmux ls`.

**Cause:** Retire path fails to kill or mis-detects session liveness (wrong socket, race, or “not running” check that disagrees with the swarm tmux server). Overlaps held-handoff incomplete cleanup (B02) but is a distinct **session leak**: windows keep consuming desktop/process resources and confuse operators.

**Expected:** After successful `squad_retire.sh`, the agent’s tmux session is gone on the swarm socket; status detail matches reality; no orphan `swarmforge-<agent>` sessions for retired agents.

**Solution direction:**  
1. Retire always targets the project’s tmux socket; verify `has-session` after kill.  
2. If session still present, force kill and log failure if still alive.  
3. Periodic squadd reconcile: sessions named `swarmforge-*` with no non-retired agent → kill or alert.  
4. Align with held-handoff finish so “soft” retires still clean sessions.

**Where:** `squad_retire.clj` session stop / worktree removal; `squadd` role reconciliation; live: retired agents with `tmux=yes`, `worktree=no`.

**Repro (live):** After swarm progress, only `squad-leader` in `roles.tsv`, residual `wait` with no actives, but `tmux ls` still shows cleaner-006, hardener-001/002, implementer-001/019, qa-011/012 — all agent records `state: retired`.

---

### B12 — Hardener edits root tooling (`bb.edn`) against role rules

**Symptom:** Hardener commits change root `bb.edn` (and can cause merge conflicts on `bb.edn`). Role prompt says do **not** edit root tooling files (`bb.edn`, `deps.edn`, …) unless the assignment explicitly requires tooling work.

**Cause:** Prompt/rule is soft; hardener follows local convenience (e.g. test task lists) without enforcement. Concurrent hardeners + mergers then fight over `bb.edn`.

**Expected:** Hardener does not touch root tooling unless assignment says so; violations are rejected at review or blocked by policy/check. Hardening stays in `src/` / `test/` (and allowed artifact roots).

**Solution direction:**  
1. Strengthen hardener prompt + assignment Leader Instructions.  
2. Optional pre-handoff check: hardener result commit must not modify denylisted paths.  
3. Code review / mechanical gate on batch hardener artifacts.  
4. Prefer product layout that doesn’t require hardener to edit `bb.edn` for tests (tasks under `bb/tasks/`).

**Where:** `swarmforge/role-templates/hardener.prompt`; hardener contract `artifact-roots`; live commit e.g. `b873564` (*Harden Wumpus process random choice validation*) includes `bb.edn`; merge-errors also show `bb.edn` conflicts on hardener batches.

---

## P3 — Architecture polish and operator UX

### B13 — Analyst dependency-checker policy is missing or pitifully coarse

**Symptom:** Product `dependency-checker.edn` is a minimal two-component sketch, e.g.:

```clojure
{:allowed-dependencies {:main [:process]
                        :process []}
 :fail-on-cycles true
 :fail-on-violations true}
```

That only says “main may depend on process; process may not depend on main.” Everything under process is one blob (`process → process` free-for-all). The checker stays green while the real module graph (many process namespaces, arch-view edges) has **no internal policy**. Not a useful Clean Architecture gate.

**Who owns it:** The **analyst** is supposed to **create** `dependency-checker.edn` as a durable product artifact, aligned with the **theme module map** and story structure — a real allowed-dependency policy, not a stub for implementers to invent, and not a two-bucket placeholder that fails to encode use-case / cave / UI boundaries. Module map template already notes names should stay consistent with a future dependency-checker policy.

**Expected:** Analyst delivers a dependency-checker input that:
1. Names **real components** matching the module map (e.g. UI/main, process use-cases, pure cave/topology, IO if split) — not only `:main` and `:process`.  
2. Lists **allowed edges** that enforce inward dependencies (UI → process → pure domain; no process → UI).  
3. Constrains **within process** where the map requires it (e.g. movement/shooting may use cave; cave depends on nothing product-internal).  
4. Is included in the **analysis handoff** so implementers, code reviewers, and hardeners **enforce** it rather than invent a pitiful default.  
5. Grows when stories add components; not left frozen as a one-time stub.  
6. Is good enough that a green checker run means something architectural, not just “two layers exist.”

**Cause:** Analysis path does not require or review a real dependency-checker policy. Live swarm got a stub (likely first implementer) that satisfies “run the tool” without encoding architecture. Analyst prompt currently emphasizes stories/module-map guidance, not authoring `dependency-checker.edn`.

**Solution direction:**  
- Analyst prompt/contract: author `dependency-checker.edn` from the module map; list it among required analysis artifacts.  
- SL/theme gates: analysis incomplete without a non-trivial policy (or explicit operator waiver).  
- Code review / hardener: fail or flag if policy is only the two-bucket template while many process modules exist.  
- Product/template example with multi-component `allowed-dependencies` for Clean Architecture apps.

**Where:** `role-templates/analyst.prompt` / contract; `templates/theme-module-map.md`; analysis assignment artifacts; product root `dependency-checker.edn`; live product `dependency-checker.edn` vs process modules under `src/`.

**Repro (live):** Hunt the Wumpus — many main/process modules, checker config only `:main`/`:process`; `dependency-checker` reports 2 components, 0 violations, while internal process dependencies are unconstrained.

---

### B14 — Theme package page should include dependency-checker.edn card

**Symptom:** Clicking the **theme** link on the dashboard opens the theme package view (scheme, module map, implementation order, …). **`dependency-checker.edn` is not shown** as a card, so operators cannot inspect the architecture dependency policy next to the module map.

**Expected:** Theme package UI includes a card for **`dependency-checker.edn`** (durable product path at project root, or wherever analysis records it), alongside theme scheme, module map, and implementation order. Missing file should show a clear empty/missing state (and ideally that analysis is incomplete — see B13).

**Solution direction:** Extend theme package section builders in `squadd/web.clj` (same pattern as module map / implementation-order draft-vs-durable); load/display `dependency-checker.edn` content; TOC entry for the card.

**Where:** `swarmforge/scripts/squadd/web.clj` theme package / artifact-content for `theme`; dashboard theme link.

**Related:** B13 — analyst must author a real dependency-checker policy.

---

### B15 — Grok agent terminal window does not fill / scroll correctly

**Symptom:** When the operator opens (clicks into) a window for an agent running the **Grok** backend, the popped terminal shows only about **~25 lines of text**, all pinned to the **top** of the window. The rest of the window is empty. Scrolling moves within that short band from that top position **upward**; content does not fill or use the full window height like a normal shell pane.

**Likely cause:** Screen / TUI control from the Grok CLI (alternate screen buffer, fixed viewport rows, cursor addressing, or redraw sized to a small initial geometry rather than the real window). Related investigation notes: `swarmforge/docs/grok-agent-window-scroll.md` (alt screen, mouse capture, host terminal vs tmux). May also involve spawn-time rows/cols or terminal-adapter sizing if the session is created with a short height that Grok never re-queries.

**Expected:** Live Grok agent window uses the full terminal geometry; visible transcript fills the window and scrolls in a usable way (or documents a reliable operator path if the upstream TUI cannot).

**Where to look:** Grok launch path (spawn / `launch.sh` / backend flags), terminal adapters under `swarmforge/scripts/terminal-adapters/`, tmux pane size at create, host terminal alt-screen behavior; compare with Codex-backed agent windows which do not show this.

**Repro:** Start a swarm with a Grok-backed role (e.g. `gherkin-reviewer` / `qa-procedure-reviewer` per `squad.conf`), open that agent’s terminal window while it is running, observe ~25-line top band and empty lower region.
