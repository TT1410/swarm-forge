# Bugs

## Spawn queue head-of-line block on template capacity

**Symptom:** Spawn requests for free templates sit in `.squad/spawn-requests/new/` while the daemon logs `spawn-queue-waiting … deferred this pass; N still queued`. Residual stays on `wait_for_spawn`. Other templates (e.g. `qa-procedure-writer` under cap, `qa-procedure-reviewer` idle) never spawn until the **head** request’s template frees a slot.

**Cause:** `poll-spawn-requests!` in `squadd.clj` treats `template-capacity-full:<template>` as capacity pressure and **stops scanning the queue** for that poll (`:while (not @capacity-pressure?)`). FIFO head is often another `gherkin-writer` at max_active_template 3, so later requests that could spawn are never tried.

**Expected:** On `template-capacity-full` (and likely `group-capacity-full` for a single group), defer **that** request and **continue** the scan so other templates can spawn. Only total `capacity-full` (max_transient_agents) should stop early / back off the whole poll.

**Where:** `swarmforge/scripts/squadd.clj` — `poll-spawn-requests!`, `process-spawn-request!`, `capacity-style-blocker?` / how `capacity-pressure?` is set.

**Repro (live pattern):** gherkin-writer at 3/3, queue head gherkin-writer, then qa-procedure-writer and qa-procedure-reviewer requests behind it; writers at 1/3 and reviewers at 0/3 stay unspawned for many minutes.

## Grok agent terminal window does not fill / scroll correctly

**Symptom:** When the operator opens (clicks into) a window for an agent running the **Grok** backend, the popped terminal shows only about **~25 lines of text**, all pinned to the **top** of the window. The rest of the window is empty. Scrolling moves within that short band from that top position **upward**; content does not fill or use the full window height like a normal shell pane.

**Likely cause:** Screen / TUI control from the Grok CLI (alternate screen buffer, fixed viewport rows, cursor addressing, or redraw sized to a small initial geometry rather than the real window). Related investigation notes: `swarmforge/docs/grok-agent-window-scroll.md` (alt screen, mouse capture, host terminal vs tmux). May also involve spawn-time rows/cols or terminal-adapter sizing if the session is created with a short height that Grok never re-queries.

**Expected:** Live Grok agent window uses the full terminal geometry; visible transcript fills the window and scrolls in a usable way (or documents a reliable operator path if the upstream TUI cannot).

**Where to look:** Grok launch path (spawn / `launch.sh` / backend flags), terminal adapters under `swarmforge/scripts/terminal-adapters/`, tmux pane size at create, host terminal alt-screen behavior; compare with Codex-backed agent windows which do not show this.

**Repro:** Start a swarm with a Grok-backed role (e.g. `gherkin-reviewer` / `qa-procedure-reviewer` per `squad.conf`), open that agent’s terminal window while it is running, observe ~25-line top band and empty lower region.

## Implementation order ignored when only root draft exists

**Symptom:** Implementers spawn and run for dependent stories (e.g. `crooked-arrow`, `replay-setup`) while the foundation story (`cave-setup`) has **no** `implementation_sha` and is still blocked (e.g. QA procedure changes-requested). Operators expect makefile-style order in `implementation-order.md` to hard-gate implementers so cave-setup is first.

**Cause:** `squad_next.clj` only loads durable order from  
`.squad/themes/<theme-id>/implementation-order.md` via `load-implementation-order`.  
If that file was never recorded (`squad_theme.sh implementation-order …`), the map is `{}` and `implementer-dependencies-satisfied?` treats **empty providers as satisfied**. Root `implementation-order.md` (analyst draft / theme package draft) is **not** consulted by the gate—only by dashboard display. So the hard gate is a no-op until someone records the theme copy.

**Expected:** Recording into the theme must not depend on SL memory. Durable theme order remains the sole gate input; missing durable order must not mean “no dependencies.”

**Solution:**

1. **Record without relying on memory** (primary)  
   After analyst merge, when an order file is in the result artifacts (or root `implementation-order.md` exists) and `.squad/themes/<theme-id>/implementation-order.md` is missing:  
   - **Preferred:** daemon mechanical apply runs  
     `squad_theme.sh implementation-order <theme-id> implementation-order.md`  
     (same class as story/packet bookkeeping), **or**  
   - **Residual hard:** surface  
     `NEXT_ACTION: record_implementation_order`  
     `COMMAND: squad_theme.sh implementation-order <theme-id> <file>`  
     until recorded (SL residual-only cannot skip past it forever).

2. **Hard-block implementers until durable order exists** (belt)  
   - Durable file **present** (even empty) → use it; empty edges = intentional no deps.  
   - Durable file **missing** → do **not** treat as `{}` satisfied; block implementer create/spawn with reason like `implementation order not recorded`.  
   Optional: if root draft exists, residual/mechanical points at that path as the record source.

3. **Do not**  
   - Have the analyst write directly under `.squad/themes/…`  
   - Rely on SL remembering after analysis  
   - Load root draft as a second gate source of truth (OK as **input to record** only)

4. **Live workaround**  
   `squad_theme.sh implementation-order hunt-the-wumpus implementation-order.md`  
   Then decide whether out-of-order implementers should finish or be retired.

**Where:** `swarmforge/scripts/squad_next.clj` — `load-implementation-order`, `implementer-dependencies-satisfied?`, `assignment-candidate` (implementer branch), bookkeeping/residual after analysis; `squad_theme.clj` `implementation-order`; optional mechanical apply in daemon path. Dashboard already documents durable vs draft in `squadd/web.clj`.

**Repro (live):** Root `implementation-order.md` has `crooked-arrow: cave-setup` and `replay-setup: cave-setup`; `.squad/themes/hunt-the-wumpus/implementation-order.md` missing; cave-setup packet blocked on QA rework with no `implementation_sha`; crooked-arrow and replay-setup still get `implementation` auto-approved and running implementers.

## Held implementer handoff never finishes after merger — agent stuck, not retired

**Symptom:** After a merger resolves a `merge_blocked` assignment and the original assignment is **merged** (e.g. `crooked-arrow-implementation` via `crooked-arrow-implementation-merge`), the **implementer** stays registered (`handoff_sent`, tmux still up). The merger is retired correctly. Residual may show `recover_agent` for the quiet implementer instead of `retire_agent`. Daemon repeatedly applies `finish_held_handoff` with **exit 1**.

**Cause:**  
1. On merge_block, the implementer’s in-process handoff is parked under `inbox/held/`.  
2. When the assignment later becomes terminal (`merged` / merger resolves original), `apply-held-handoff-finish-step!` runs  
   `SWARMFORGE_ROLE=squad-leader done_with_current.sh <held-path>`.  
3. `done_with_current.clj` `ensure-current-handoff!` only accepts the path if it is under **`inbox/in_process`**. A **held** path always fails with `CURRENT_HANDOFF_MISMATCH`.  
4. Handoff never moves to **completed**.  
5. `retirement-candidates` only retires agents with a **completed** handoff for a terminal assignment → implementer never becomes retirable.

**Expected:** After merger (or any resolution that leaves the original assignment terminal), the held handoff is finished into **completed** and the original agent is mechanically retired (same as a normal successful accept path).

**Solution (simple terms):**

1. Implementer finished and sent a handoff.  
2. Merge conflict → handoff put in a **holding tray** so other mail can flow.  
3. Merger fixes it; work is on main; original assignment is **done**.  
4. System tries to close the held handoff and retire the implementer.  
5. Close-out only works if the handoff is in the **active** tray (`in_process`). File is still in **hold** → “wrong tray” every time.  
6. Handoff never marked finished → agent never retired → looks stuck. Merger already left fine.

**Fix:** When the assignment is already done, **move held → active tray** (or finish from hold on purpose), close the mail normally, then the usual **retire finished workers** step runs. Do not call `done_with_current` on a held path without moving or teaching it to accept hold. Do not rely on SL recover/manual retire every time.

**Preferred implementation:** In `apply-held-handoff-finish-step!`, when assignment is terminal: move handoff `held/` → `in_process/`, then existing `done_with_current.sh` + mechanical `retire_agent`. Alternative: held-specific finish into `completed/`. Optional: don’t residual `recover_agent` for quiet `handoff_sent` when assignment is already merged and only held-finish is broken.

**Live workaround:** Move held file to `in_process` (if empty) and `done_with_current`, or `squad_retire.sh <agent>` after confirming merged — product should do this automatically.

**Where:** `squad_next.clj` — `park-merge-blocked-in-process-handoffs!`, `apply-held-handoff-finish-step!`, `retirement-candidates` / `completed-handoff-retirable?`; `done_with_current.clj` — `ensure-current-handoff!` (in_process only).

**Repro (live):** Implementer handoff in `inbox/held/…from_implementer-001…`; assignment `crooked-arrow-implementation` state `merged` / resolved by merger; merger handoff completed and merger retired; mechanical log `finish_held_handoff … exit=1` + `CURRENT_HANDOFF_MISMATCH`; implementer still in `roles.tsv` and residual `recover_agent`.

## Hardener, architect, QA, senior-implementer skip full acceptance suite before handoff

**Symptom:** Late-stage quality agents hand off after partial verification (e.g. unit tests + custom batch QA script / focused checks) without evidence that the **full acceptance suite** (accepted Gherkin / generated acceptance tests for the merged product) was run and passed. Live example: batch QA reported `bb test` + `bb qa/scripts/hunt_the_wumpus_batch_qa.clj` + CRAP/DRY, not a full Gherkin acceptance run over `features/`.

**Expected:** Before handoff, **hardener**, **architect**, **qa**, and **senior-implementer** must each run (and pass, or report failure with detail) the **full acceptance suite** for the current merged product, in addition to whatever role-specific work they own (hardening, architecture critique, QA procedures, senior implementation). “Full acceptance suite” means the project’s accepted Gherkin / generated acceptance tests as a complete suite—not only unit tests, not only ad-hoc procedure scripts, not only focused smoke checks—unless the assignment explicitly narrows scope (it should not for these singleton/batch roles).

**Cause / gap:**  
- Role prompts are uneven: QA says run accepted Gherkin + full test suite; senior-implementer says full verification suite; hardener says “focused verification”; architect may not hard-require acceptance.  
- Assignments / mechanical verification do not **gate** handoff on an acceptance-suite command.  
- Agents reasonably optimize to units + local harness and still hand off.

**Solution direction:**  
1. **Prompt/contract:** Explicit same rule for hardener, architect, qa, senior-implementer: run full acceptance suite before handoff; record exact commands and results in the handoff/report.  
2. **Assignment template / Tool Startup or Leader Instructions:** Name the canonical acceptance command(s) for the product (e.g. project-standard Gherkin/acceptance runner).  
3. **Optional enforcement:** Require verification event / report section listing acceptance suite pass before `handoff_sent` is considered complete (or SL residual rejects incomplete QA/hardener/architect/senior handoffs).

**Where:** `swarmforge/role-templates/{hardener,architect,qa,senior-implementer}.prompt` (+ contracts); assignment generation for those templates; live batch QA reports under `qa/` as evidence of current behavior.

**Repro (live):** `hunt-the-wumpus-qa` / `-r2` handoff reports pass via unit suite + batch procedure harness without documenting full Gherkin acceptance; hardener/architect/senior paths similarly weak on acceptance unless agent chooses to run it.

## Implementer rework thrash after code-review changes-requested (one-cycle broken)

**Symptom:** A story racks up many implementer assignments in quick succession (`…-implementation`, `…-r2`, … `…-r14`) after a single code review **changes-requested**, each rework merging in ~2 minutes, without a new code-review accept (or a second deliberate review cycle). Looks like “14 revisions” but is scheduler churn, not fourteen full story redesigns. Packet can still show `code_review: changes-requested` and only the first implementation in `implementation_iterations` while later `-rN` keep spawning.

**Expected (one-cycle rework):**  
1. Code review: changes-requested  
2. **One** implementer rework assignment  
3. **Code review again** (accept or changes-requested)  
4. Packet updates so the prior changes-requested is **consumed / stale** and does not authorize endless new implementers  

**Cause:** After implementer rework merges, packet `code_review: changes-requested` stays live. Ready-action / one-cycle revision logic keeps creating the next `implementation-rN` instead of scheduling **re-review** or treating the request as satisfied for one cycle. Related helpers: `one-cycle-revision-candidate`, `stale-changes-requested?`, `field-changes-requested?` in `squad_next.clj` — the “stale / one-cycle” path is not stopping further implementer creates.

**Solution direction:**  
1. After a post-review implementer rework is **merged** (or result recorded), require **code-reviewer** again before any further implementer create.  
2. Mark the changes-requested **consumed** (or stale) once the rework assignment for that review target is complete, until a new review writes a new decision.  
3. Cap implementer revision depth (e.g. stop or escalate after N reworks without an intervening code-review accept).  
4. Ensure packet `implementation_iterations` / review fields reflect each rework cycle so state matches reality.

**Where:** `swarmforge/scripts/squad_next.clj` — implementer create after `code_review` changes-requested; `one-cycle-revision-candidate`, `stale-changes-requested?`; packet update on implementer merge / review re-record.

**Repro (live):** `movement-hazards`: one code-review changes-requested; implementer assignments through `movement-hazards-implementation-r14` in ~40 minutes; packet still `code_review: changes-requested`; no intervening code-review accept between r2–r14.

## Operator unblock and dashboard requests need a Troubleshooter (not the squad leader)

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
5. Document: product bugs still get fixed in code; troubleshooter is the **in-swarm operator**, not a substitute for fixing FSM bugs.

**Where (today):** dashboard request wake in `squadd.clj` / `squadd/web.clj` → SL; SL prompt residual-only rules; no troubleshooter role.  
**Repro:** movement-hazards thrash — residual never offered accept+stop; operator (or a troubleshooter) had to force `squad_packet.sh review … accepted`, block thrash assignments, and retire agents. SL following rules could not.

## No acceptance pipeline — Gherkin mutation and full acceptance suite cannot run

**Symptom:** Hardener (and similarly late roles) install `gherkin-mutator` / `gherkin-parser` but **do not run Gherkin mutation**. Agents report e.g. “Gherkin mutator not run because no acceptance runner worker is configured” or that the QA harness is a fixed transcript script, not a generated-feature runner. Full acceptance suite before handoff (separate bug) also fails for the same underlying gap: there is no project acceptance command to run.

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
4. QA / hardener / architect / senior-implementer can run the **full acceptance suite** before handoff (see related bug).  
5. Workflow or assignment text makes APS setup a **blocking** implementer (or dedicated setup) duty when features exist and the runner does not.

**Solution direction:**  
- Prompt/contract: implementer (or first story / tooling story) owns APS project components, not only process units.  
- Template product `bb.edn` / docs: acceptance tasks + runner-worker example.  
- Gate hardener “Gherkin mutation complete” or fail soft with **blocker** to SL if runner missing (don’t silently skip).  
- Align with “full acceptance suite before handoff” bug — same pipeline, two consumers.

**Where:** `constitution/articles/engineering.prompt` (Acceptance Pipeline); `role-templates/implementer.*` (tools + duties); `hardener.contract.edn` / hardener assignment Tool Startup; product `bb.edn` / missing acceptance sources; live hardener-002/005 panes and tool-table `gherkin-mutator`.

**Repro (live):** Hunt the Wumpus product: features present, `bb test` only in `bb.edn`; hardener-002/005 parse Gherkin and run `clj-mutate` but skip `gherkin-mutator` for lack of `--runner-worker` / acceptance runner.

## Dashboard SL answers truncate to first line of multiline response

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
