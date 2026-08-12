# Bugs

Prioritized open issues. Priority is **impact on swarm correctness, operator unblock, and recurring defect classes** — not chronological discovery. Architecture debt and product/workflow defects share one list.

| Pri | ID | Title | Kind | Area |
|-----|-----|--------|------|------|
| **P1** | B27 | Second merger blocked while first is only handoff_sent (awaiting finish/retire) | Workflow / FSM | Merger capacity |
| **P1** | B26 | Single shared `acceptance/runner.clj` is a merge hotspot | Product architecture | APS / acceptance |
| **P1** | B28 | Dashboard hides non-completed assignments (e.g. `merge_blocked` missing from list) | Reliability | Dashboard |
| **P1** | B29 | When the swarm is stalled, the dashboard should explain why (usually stalled agents) | UX / design | Dashboard |
| **P1** | B30 | Hardener cannot run Gherkin mutation reliably (wrong runner facilities / data shape) | Product quality | APS / hardener |
| **P1** | B31 | Hardener quality bar not contracted (CRAP≤6, kill all mutants, reduce DRY) | Product quality | Role / hardener |
| **P1** | B32 | Batch assignment replace breaks batch-id == assignment-id (results never projected) | Workflow / FSM | Batch / replace |
| **P1** | B33 | Implementer acceptance model diverges from six-pack coder (no generate/step suite) | Product quality | APS / implementer |
| **P2** | B10 | Dashboard answers truncate to first line of multiline response | Reliability | Dashboard IO |
| **P2** | B09 | Operator unblock needs Troubleshooter (not SL) | Operator + arch | Roles / dashboard |
| **P2** | B11 | Zombie tmux sessions after agent retire | Hygiene | Lifecycle |
| **P2** | B12 | Hardener edits root tooling (`bb.edn`) against role rules | Policy | Role enforcement |
| **P2** | B25 | Implementation order and dependency-checker config must be user-approved | Workflow | Theme / analysis gates |
| **P2** | B23 | Theme close / finalize is undefined (need approval that still allows more stories) | Workflow | Theme lifecycle |
| **P2** | B17 | Actions are shell strings, not structured ops | Architecture | Integration boundary |
| **P2** | B18 | `squad_next` mixes planning, policy, presentation, and execution | Architecture | Control plane |
| **P2** | B16 | Control-plane ownership is implicit (env/prompt), not modeled | Architecture | Authority |
| **P2** | B19 | Scheduling priority is emergent across many code paths | Architecture | Planner policy |
| **P3** | B13 | Analyst dependency-checker policy missing or coarse | Product quality | Analysis |
| **P3** | B14 | Theme package page missing `dependency-checker.edn` card | UX | Dashboard |
| **P3** | B15 | Grok agent terminal window does not fill / scroll correctly | UX | Terminal / Grok |
| **P3** | B24 | Dashboard display needs better information architecture / organization | UX / design | Dashboard |
| **P3** | B20 | Shared lease primitive missing (locks are one-off) | Architecture | Concurrency |
| **P3** | B21 | FSM transitions have hidden multi-file side effects | Architecture | Persistence |
| **P3** | B22 | Durable record formats are informal across categories | Architecture | State schema |

**Fixed (removed):**
- P0 B01–B04 (rework thrash, held handoff, impl-order gate, spawn HOL)
- prior P1 B05–B08 (APS acceptance pipeline + templates; coverage before CRAP/mutate; full acceptance suite before late-role handoff; safe `file-map` TOCTOU)

**Suggested fix order:**
1. **P1:** **B28** show non-completed assignments → **B29** stall explanation → **B32** batch replace ↔ result projection → **B33** implementer APS = six-pack coder model → **B26** split/modular runner → **B30** Gherkin mutator facilities → **B31** hardener quality bar → **B27** free merger slot.
2. **P2 operator/hygiene:** B10 → B09 → B11 → B12.
3. **P2 workflow design:** B25 (approve order + dependency-checker) → B23 (theme finalize).
4. **P2 architecture foundation:** B17 → B18 → B16 → B19 (incremental; do not block P1).
5. **P3:** B13–B15, B24, B20–B22.

**Related clusters**

| Cluster | Bugs | Note |
|---------|------|------|
| Live stuck swarm (2026-08-12) | **B27**, **B26**, **B28**, **B29**, **B32** | Merger deadlock + runner hotspot + hidden assignment state + no stall explanation + **batch replace orphans results** |
| Informal file state | B10, B22 | Multiline truncate; B22 systemic |
| Control plane structure | B16, B17, B18, B19 | Ownership, typed ops, planner split, priority policy |
| Concurrency / multi-write | B20, B21 | Leases + explicit transition persistence |
| Operator path | B09, B16 | Troubleshooter + unreachable unsafe ops from SL |
| Theme lifecycle | B23 | Open / shipped / extend without lockout |
| Theme architecture gates | B13, B14, B25 | Policy content, UI cards, user approval of order + checker |
| APS / acceptance layout | **B33**, B26, **B30** | Implementer must follow six-pack coder pipeline (generate + steps); not one mega runner; mutator needs worker protocol |
| Hardener quality bar | **B31**, B30, B12 | Contract must require CRAP≤6, kill all code+Gherkin mutants, reduce DRY; facilities + no root tooling thrash |
| Batch / replace linkage | **B32** | Mechanical record assumes batch-id == assignment-id; replace orphans members |
| Merger / merge recovery | B27 | Singleton slot stuck on finished-but-not-retired merger |
| Dashboard UX | B28, B29, B10, B14, B24 | Missing states, **stall diagnosis**, truncate, cards, IA |
| Dependency-checker | B13, B14, B25 | Author, display, and approve |

Source notes for B16–B22: `architecture-improvements.md` (review findings folded in and re-prioritized).

---

## P1 — Active pipeline blockers (fix first)

### B28 — Dashboard hides non-completed assignments (e.g. `merge_blocked` missing from list)

**Symptom:** Assignments in important non-terminal states such as **`merge_blocked`** (and likely others not on an allow-list) **do not appear** in the dashboard Assignments table. On disk, `.squad/assignments/<id>/status` correctly has `state: merge_blocked` and detail `dry-run merge failed`. Operators only see agents as `handoff_sent` / “waiting,” with no assignment row explaining the block. Live stuck swarm (2026-08-12): hardener, merger, and cleaner were merge_blocked for hours without showing in Assignments.

**Cause:** `squadd/web.clj` filters with an **allow-list** of “web-active” states:

```clojure
#{"created" "assignment_created" "in_progress" "handoff_sent"
  "result_received" "merge_ready" "blocked"}
```

**`merge_blocked` is omitted.** The filter is inverted from what operators need: it keeps a few in-flight names and drops everything else (including stuck states). Blockers panel also does not treat plain `merge_blocked` as a blocker unless a durable `blocker` file exists.

**Expected:**  
1. **Only completed / terminal assignments are filtered out** of the active Assignments list. Terminal means truly finished for display purposes (e.g. `merged`, `rejected`, `superseded`, `replacement_created`, `retired` — align with `terminal-assignment-states` in `squad_next` / product semantics, not a partial allow-list).  
2. All non-completed states remain visible, including **`merge_blocked`**, `merge_ready`, `in_progress`, `handoff_sent` (if used on assignments), durable `blocked`, etc.  
3. `merge_blocked` should be **easy to spot** (state column, detail, and/or Blockers section with merge-error summary).  
4. Optional: completed assignments available under a separate “History” / toggle, not mixed into the active list.

**Solution direction:**  
- Replace `web-active-assignment-states` allow-list with a **terminal deny-list** (or `not (contains? terminal-states state)`).  
- Surface `detail` / merge-error snippet for merge_blocked rows.  
- Optionally add merge_blocked to blocker-state without requiring a separate blocker file.  
- Test: create assignment with `merge_blocked`; dashboard JSON/API includes it; `merged` assignment is excluded from default active list.

**Priority rationale (P1):** Operators cannot diagnose or unblock stuck swarms if the decisive assignment state is invisible; hid B27 for hours.

**Where:** `swarmforge/scripts/squadd/web.clj` (`web-active-assignment-states`, `assignment-state`, Assignments table render, `blocker-state`).

**Related:** B27, B26, B29, B10, B24.

---

### B29 — When the swarm is stalled, the dashboard should explain why (usually stalled agents)

**Symptom / gap:** When residual is effectively stuck (`wait` with agents “active” for hours, merge_blocked, held handoffs, capacity deadlock), the **dashboard does not tell the operator why**. Status looks like ordinary busyness: agents in `handoff_sent`, empty or incomplete Assignments (B28), no prominent “stalled” signal. Operators must dig into disk, merge-error files, held inbox, and `squad_next` by hand. Live example: multi-hour wait while hardener/merger/cleaner were merge_blocked; UI never surfaced “merge conflict on acceptance/runner.clj” or “singleton merger slot held by handoff_sent.”

**Design need (open — prefer clarity over a specific chrome):**

Most stalls are **one or more agents not making progress**. The dashboard should make that obvious and attach a **human-readable reason**.

**Candidate UX (not mandatory):**
- Mark stalled agents (e.g. **red** / warning pill) when quiet beyond recovery threshold, assignment is `merge_blocked`, handoff held, dirty checkout blocked merge, etc.
- **Hover or click** shows a short reason (from assignment `detail`, `merge-error` summary, held-handoff note, recovery classification).
- Optional top **“Swarm health”** strip: “Stalled: 3 agents” with the same reasons listed.

**Better ideas welcome** if red+hover is wrong for density (e.g. dedicated Stall panel, sticky blocker list, residual-reason mirrored on the page). Goal is: **open dashboard → know why nothing moves**, without shell archaeology.

**Expected:**  
1. Define “stalled” operationally (quiet time, merge_blocked, held handoff, failed merge, no ready residual progress, etc.).  
2. Surface stalled agents and/or assignments with **state + reason** derived from existing status/merge-error/held data.  
3. Do not rely only on agent `handoff_sent` (looks healthy).  
4. Align with B28 (show merge_blocked rows) and B24 (IA so “needs you / stalled” outranks noise).

**Solution direction:**  
- Extend status API with `stalled?` / `stall_reason` per agent/assignment.  
- UI treatment TBD (color, hover, panel).  
- Prefer reusing assignment `detail` and first lines of merge-error over inventing new files when possible.

**Priority rationale (P1):** Same stuck swarm was undiagnosable from the dashboard; visibility is part of unblocking.

**Where:** `squadd/web.clj` status JSON + agents/assignments UI; agent quiet/recovery signals; assignment status + merge-error; optional residual “wait” reason export.

**Related:** B28 (show merge_blocked), B27, B26, B30, B24, B09 (troubleshooter).

---

### B30 — Hardener cannot run Gherkin mutation reliably (wrong runner facilities / data shape)

**Symptom:** Hardener is required to run `gherkin-mutator` with `--runner-worker` (assignment Tool Startup / Verification Prerequisites). In live product work the mutator either fails immediately or reports mass **errors**, not kills/survives:

- Default/placeholder feature path (`features/a-feature.feature` missing).  
- After pointing at a real feature, run completes with e.g. `killed=0 survived=0 errors=155` (“Stream closed”) when worker is **`bb acceptance`**.  
- Hardener then stalls for minutes in agent “thinking” while diagnosing protocol mismatch—no long-running mutator process; verification already failed.

Live (Hunt the Wumpus, hardener-002): coverage + `bb acceptance` suite can pass; **gherkin mutation does not**. A successful mutator **manifest** exists only on `instructions-content.feature` (hardener-001 era); other features lack kill reports; `terminal-text-ui` got an empty scenarios manifest after the error run.

**Cause:** Product acceptance facilities / **assignment guidance** are the wrong **shape** for the mutator:

1. **`bb acceptance` is a human full-suite command** (prints scenario results, exits).  
2. **`gherkin-mutator` expects a persistent NDJSON worker protocol** (runner-worker): one job per stdin line, one JSON response per stdout line (`outcome` ∈ `test_failure` | `test_success` | `infrastructure_error`); diagnostics on stderr only.  
3. Assignment text steered hardeners to `--runner-worker "bb acceptance"`, which **looks** correct after APS wiring (B05) but is **protocol-wrong**.  
4. Mega `acceptance/runner.clj` (B26) mixes suite + worker; worker path was easy to miss.

**Learned (live debug, Hunt the Wumpus 2026-08-12):**

1. **Worker mode already existed** inside `acceptance/runner.clj`: `(if (some #{"--worker"} *command-line-args*) (run-worker!) (run-suite!))` with `run-worker-job` / NDJSON. The facility was not missing entirely—it was **unadvertised and unused**.  
2. **Wrong command was the main failure:** `gherkin-mutator … --runner-worker "bb acceptance"` → suite prints human text and exits → mutator sees **Stream closed** / mass `errors` (not kills). Process check: when hardener “waited 6–12 min,” **no** `gherkin-mutator` process was running—only Codex thinking after the failed run.  
3. **Correct worker invocation (proven):**
   ```bash
   gherkin-mutator --feature features/instructions-content.feature \
     --runner-worker "bb acceptance-worker" \
     --workers 4
   # also: bb acceptance -- --worker
   ```
   Smoke run produced real **killed** lines (topic/warning/excluded-addition mutations).  
4. **IR-bound vs health-only:** `run-feature-json!` was built around **instructions-content** (example tables drive assertions → kills). Other features (e.g. terminal-text-ui) lack IR-sensitive handlers; a worker that only re-runs suite-health will **not** kill example-value mutations (false survives). Prefer instructions-content for hardener kill evidence until full step/runtime binding exists.  
5. **Manifests as evidence:** Successful mutator leaves `# mutation-stamp` + `# acceptance-mutation-manifest-begin/end` in the feature file. Master had a full kill report only on `instructions-content.feature` (`tested_at` during hardener-001). hardener-002 left an empty `scenarios:[]` manifest on `terminal-text-ui` after the error run.  
6. **Partial product fix landed** on product master (`d602c9f` and follow-ups in product tree): `bb acceptance-worker` task, `bb/tasks/acceptance_worker.clj`, `acceptance/MUTATOR_WORKER.md`, worker path clarity in `runner.clj`. SwarmForge **tool-table / hardener assignment text still say** `--runner-worker "bb acceptance"` and must be updated so the next hardener does not re-learn this.  
7. **Templates / constitution** should ship the worker task and never document suite command as mutator worker.
8. **Residual confirmed on hardener restart (2026-08-12, hardener-003 / `hunt-the-wumpus-hardener-r4`):** Product worker is present on master, and operator Leader Instructions + live session kick correctly require `--runner-worker "bb acceptance-worker"`. But **auto-generated assignment sections still contradict that**:
   - Hardener **Verification Prerequisites** (from `swarmforge/tool-table.edn` + `squad_tool_table.clj` `verification-instructions`) still say run gherkin-mutator with `--runner-worker` pointing at the project acceptance command, **typically `bb acceptance`**.
   - Canonical project commands block in generated assignments still documents:  
     `` `bb acceptance` — full Gherkin acceptance suite; also `gherkin-mutator --runner-worker "bb acceptance"` ``
   - `hardener.prompt` / role contract still list `--runner-worker "bb acceptance"` under Gherkin acceptance mutation.
   - `templates/product-bb.edn` still documents `bb acceptance` as the APS runner-worker.
   So every new hardener assignment **re-injects the wrong default** unless the operator overrides in Leader Instructions / pane paste. Fix is SwarmForge-side: tool-table prereqs, canonical command blurb, hardener prompt/contract, product-bb template — all must name `bb acceptance-worker` (or `bb acceptance -- --worker`) for mutator and keep bare `bb acceptance` for the human suite only.

**Expected:**  
1. Product exposes a **documented mutator worker command** (`bb acceptance-worker`) that speaks the APS runner-worker protocol.  
2. Hardener Tool Startup / tool-table / constitution name **that** command for `gherkin-mutator --runner-worker`, not bare `bb acceptance`.  
3. `bb acceptance` remains the full human suite for handoff verification; mutator uses the worker.  
4. Hardener can produce real manifests (kills/survives) on features under test, or hand back a clear **blocker** if worker facilities are missing—without thrashing or silent skip.  
5. Templates ship worker adapter + docs; IR-bound checks for more features over time (or explicit “survives until bound” policy).  
6. Optional preflight: one NDJSON smoke job before a full mutator pass.
7. Generated assignments must **not** dual-document conflicting runners; auto text and operator overrides should agree.

**Solution direction:**  
- Product: keep/expand `acceptance-worker` + IR handlers per feature (B26 modular layout helps).  
- SwarmForge: fix tool-table verification prerequisites and hardener evidence text to `bb acceptance-worker`.  
  - `swarmforge/tool-table.edn` hardener (and any other role) lines that say typically `` `bb acceptance` `` for mutator.  
  - `squad_tool_table.clj` canonical commands blurb (suite vs mutator worker as two bullets).  
  - `role-templates/hardener.prompt` (+ contract if needed).  
  - `templates/product-bb.edn` comments/task docs: suite ≠ worker.  
- Optional: hardener preflight that fails fast if worker is not NDJSON.  
- Align with B26.

**Priority rationale (P1):** Hardener quality gate fails or wastes long agent turns without correct worker wiring; assignment text currently misleads even after product worker exists.

**Where:** product `acceptance/` + `bb/tasks/` + `bb.edn`; APS `mutator-spec.md` worker protocol; `swarmforge/tool-table.edn` verification prerequisites; `swarmforge/scripts/squad_tool_table.clj` canonical blurb; `swarmforge/role-templates/hardener.prompt`; `swarmforge/templates/product-bb.edn`; hardener assignment generation; live hardener-001/002/003 (r2 blocked zombie, r4 operator override).

**Related:** B26 (single runner), P1 B05 (suite command wired ≠ worker), hardener required evidence `gherkin_mutation`, **B31** (quality thresholds once tools run).

---

### B31 — Hardener quality bar not contracted (CRAP≤6, kill all mutants, reduce DRY)

**Symptom / gap:** Operator intent for the hardener is a real quality gate:

1. **Maintain CRAP below 6** (after real coverage).  
2. **Kill all mutants** for **both** code mutation (`clj-mutate`) and **Gherkin** mutation (`gherkin-mutator`).  
3. **Reduce DRY** (run dry analysis and eliminate / reduce duplication within scope, behavior-preserving).

What the role actually requires today is much weaker: *run* coverage / acceptance / mutation / CRAP / DRY and *report* evidence. Thresholds and “fix until green” language are missing or only live on the **cleaner**.

**Evidence of gap (contracts today):**

| Expectation | Cleaner | Hardener |
|-------------|---------|----------|
| CRAP ≤ 6 | Explicit in `cleaner.prompt` | Only “run crap4clj”; evidence is a summary, no score bar |
| Kill all code mutants | N/A (must not mutate unless assigned) | Must run `clj-mutate`; no “survived=0 / kill all” bar |
| Kill all Gherkin mutants | N/A | Must run mutator (B30 facilities); no kill-all bar; survives OK |
| Reduce DRY | Explicit: eliminate duplication unless behavior/scope blocks | Required tool `dry4clj`; “run … CRAP/DRY”; **no** “reduce/eliminate” duty; **no** `dry` evidence header |
| Owns | cleanup, names, cohesion, duplication | robustness, edge handling, mutation resistance, verification depth — DRY/CRAP bars not named |

Also:

- Hardener required-evidence in `tool-table.edn` has `coverage`, `acceptance_suite`, `gherkin_mutation`, `code_mutation`, `crap` — **no `dry`**, and no pass criteria on mutation/CRAP headers.  
- `hardener.prompt` handoff lists the same headers; Rules say “Run required verification … before handoff,” not “do not hand off until CRAP≤6 / zero survivors / DRY reduced.”  
- Tool-table install for `dry4clj` names binary `deintroverter` (SUT-grounding classifier), while dry4clj’s purpose is structural DRY candidate reporting — agents can “run dry” without doing DRY reduction at all.

**Expected:**

1. **CRAP:** After real LCOV, hardener reduces CRAP to **≤ 6** (same escape hatch as cleaner: do not change behavior / exceed scope; otherwise hand back a clear blocker with residual scores).  
2. **Code mutation:** Hardener drives `clj-mutate` until **all mutants are killed** (or equivalent project policy: zero survives for in-scope source), fixing tests/code as needed within hardener scope; survivors are not an acceptable handoff without an explicit approved exception/blocker.  
3. **Gherkin mutation:** Same bar once worker facilities work (B30): **kill mutants** under test (prefer IR-bound features); mass `errors` / empty manifests are failure, not pass; survivors require fix or blocker.  
4. **DRY:** Hardener **runs dry4clj (true DRY tool)** and **reduces duplication** unless doing so would change behavior or leave scope; handoff includes **`dry:`** evidence (candidates found + what was reduced / residual justified).  
5. Assignment generation, role prompt, contract, and tool-table verification/evidence lines all state these bars so SL/dashboard can treat incomplete bars as incomplete hardening.  
6. Fix tool identity: `dry4clj` install/binary must be the DRY reporter (or separate tools if deintroverter is also required — do not alias them).

**Solution direction:**

- Update `hardener.prompt`, `hardener.contract.edn`, and `tool-table.edn` hardener `verification-prerequisites` + `required-evidence` to mirror cleaner’s CRAP≤6 / reduce-DRY language **plus** kill-all bars for code and Gherkin mutation.  
- Evidence headers: keep existing; add `dry:`; tighten `crap` / `code_mutation` / `gherkin_mutation` descriptions to include pass criteria (score ≤6; killed/survived/errors summary with survivors=0 or blocker).  
- Align with B30 (worker command) so Gherkin kill-all is achievable.  
- Optionally teach residual/SL not to advance `hardening_approved` on evidence that only proves tools ran.  
- Correct dry4clj vs deintroverter wiring in tool-table/install.

**Priority rationale (P1):** Without this bar, “hardening complete” can mean “tools executed” while high CRAP, surviving mutants, and duplication remain — false quality gate for the whole theme.

**Where:** `swarmforge/role-templates/hardener.prompt`, `hardener.contract.edn`, `swarmforge/tool-table.edn` (hardener role + dry4clj install), assignment generation / required evidence; contrast `cleaner.prompt`; live hardener handoffs that report runs without thresholds.

**Related:** B30 (Gherkin mutator facilities prerequisite), B12 (hardener tooling thrash), cleaner as the role that already has CRAP≤6 + eliminate DRY.

---

### B32 — Batch assignment replace breaks batch-id == assignment-id (results never projected)

**Symptom / gap:** After a **batch** assignment (hardener / QA / architecture) is **replaced** (or otherwise recreated under a new assignment id), the replacement can **merge successfully** while the swarm goes fully **idle**: residual reports

```text
NEXT_ACTION: wait
REASON: no handoffs, pending approvals, active transient agents, or stale locks
```

even though member stories still lack `hardener_sha` / equivalent result fields and never advance (no hardening approval, no QA batch, etc.).

Live (Hunt the Wumpus, 2026-08-12):

1. Batch **`hunt-the-wumpus-hardener-r2`** closed with 6 member stories; assignment r2 blocked (zombie hardener-002).  
2. Operator **`squad_assign.sh replace`** → **`hunt-the-wumpus-hardener-r4`** (same theme/scope batch, `replaces: r2`).  
3. hardener-003 finished; r4 **merged** (`b69df9d3e2` / merge commit on master); agent retired.  
4. Residual pure wait. Packets still `code_review_approved` with `hardener_batch: hunt-the-wumpus-hardener-r2`, `hardener_review_state: batched`, **no** `hardener_sha`.  
5. Batch r2 status: `closed`, **RESULT: none**. Open r3 (one story) same class of orphan.

**Secondary symptom (post-unstick race, same day):** Operator projected r4’s SHA onto r2/r3 members and marked batches complete so the pipeline could advance. Mechanical next then still **created/spawned `hunt-the-wumpus-hardener-r3` / hardener-004** against a batch that already had a result and whose member stories already had `hardener_sha`. Theme ran through QA → architecture → **all stories `final_approved`** while hardener-004 kept working (dry analysis / handoff prep). Residual only reported “active agents still working” — it never said “batch already complete / stories past hardening / theme final.” Operator had to **block + retire** the redundant hardener.

**Cause:** Mechanical bookkeeping assumes **batch-id == assignment-id**:

- `batch-result-record-candidates` walks `batch-manifest-rows root (:assignment-id assignment)` — members live under the **original** batch id.  
- Merged **replacement** assignment has a **new** id → looks for a batch named `…-r4` (missing / empty) → **no** `record_merged_batch_result`.  
- Original assignment is `superseded` / not `merged` → `batch-result-available?` false until someone writes a batch `result` file by hand.  
- `batch-complete` / story transitions that depend on projected `hardener_sha` never fire.  
- Residual has nothing in inbox, no active agents, no approvals pending → **false “nothing to do”** (pairs with B29: stall reason should name stranded batches).  
- **Inverse race:** create-batch / queue-spawn / residual do not gate on “members already have result SHA,” “batch already complete,” or “stories already past hardening / final.” So unstick projection + late spawn leaves a **zombie batch agent** after the theme is done.

`squad_assign.sh replace` only creates the new assignment + supersedes the old; it does **not** re-link batch metadata, rewrite `active-batches/*`, or retarget mechanical record commands.

**Expected:**

1. Replacing a **batch** assignment either:  
   - **keeps** assignment-id == batch-id (restart in place / reopen assignment on same id), **or**  
   - **re-links** the batch to the replacement (batch metadata `assignment_id`, manifest, story `hardener_batch` / active-batch pointers) and teaches mechanical paths to follow `replaces` / `replacement` edges.  
2. When a replacement batch assignment **merges**, automatic `record_merged_batch_result` (or equivalent) projects the result SHA onto **all original batch members**, then batch complete + downstream approvals/QA can run without operator surgery.  
3. Residual / stall UX (B29) must **not** report empty wait when closed batches have members without result fields while a replacement assignment is merged (or superseded lineage has a merged child).  
4. **Do not create/spawn** a batch assignment (and **cancel / refuse spawn** if already in flight) when: batch state is already `complete` / `result_received` with a result SHA, **or** all members already have the kind’s result field (`hardener_sha` etc.), **or** members have advanced past that gate (e.g. hardening approved / final). Residual should surface **retire obsolete batch agent** instead of only “wait on active agents.”  
5. Operator docs: do not recommend bare `replace` for batch hardeners until this is fixed; prefer recovery that preserves id linkage.

**Solution direction:**

- Prefer: `replace` for batch-scope assignments **forbids** id change, or creates replacement under **same** id after terminalizing the old attempt.  
- Or: store `batch_id` on assignment metadata distinct from `assignment_id`; all batch mechanical candidates use `batch_id`.  
- Or: on merge of assignment with `replaces: <old-batch-id>`, project results using old batch manifest.  
- Emit residual candidate: “merged batch replacement needs result projection” when linkage is broken.  
- Gate `create-batch` / spawn / batch-ready candidates on member result presence and story stage; if assignment is `in_progress` but batch/members already satisfied, residual offers **block/retire** (or auto-retire policy).  
- Tests: (a) create-batch → block → replace with new id → merge → expect member packets to gain `hardener_sha` and next QA batch without manual `squad_packet.sh record` / `squad_batch.sh result`. (b) batch complete + member SHAs present → no further hardener create/spawn; if agent still running, residual directs retire.

**Priority rationale (P1):** Successful hardener work can land on master while the whole theme stalls indefinitely with residual claiming idle — high-impact false idle; operator unstick required every time replace is used on batch roles. The inverse race wastes a full hardener turn (and risks noisy late merges) after the theme is already final.

**Where:** `squad_assign.clj` `replace-assignment!`; `squad_next.clj` `batch-result-record-candidates`, `batch-complete-candidate`, `batch-result-available?`, `batch-manifest-rows`, batch create/spawn readiness; batch records under `.squad/batches/`; story packets `hardener_batch` / `*_sha`; live r2/r4 hardener restart + hardener-004 after final_approved 2026-08-12.

**Related:** B29 (stall explain), B27 (false active / empty ready), B09 (operator/troubleshoot path), batch create/complete lifecycle.

---

### B27 — Second merger blocked while first is only handoff_sent (awaiting finish/retire)

**Symptom / gap:** When a merger agent has finished its attempt and is only waiting for handoff completion / retirement (often `handoff_sent`, assignment already `merge_blocked` or otherwise no longer “working”), the swarm still treats the **merger template as active**. `merger-candidates` short-circuits:

```clojure
(if (active-template? agents "merger")
  []   ;; no create/spawn for any other merge recovery
  …)
```

So **no second merger can start** — not a nested `-merge-merge` on the failed lineage, and not a merger for a different merge_blocked assignment (e.g. cleaner) — until the first merger agent is fully cleared from the active set.

Live (Hunt the Wumpus, 2026-08-12): `merger-003` sat `handoff_sent` for hours after dry-run merge failed; handoff in `inbox/held/`; residual only `wait` on “active agents”; `ready-actions` and merger candidates both empty even though other merge_blocked work needed recovery.

**Expected:**  
1. A merger that has **handed off** and is only waiting for held-finish / retire must **not** monopolize the singleton merger slot for *new* recovery work.  
2. Either:
   - **Free the template slot** when state is effectively terminal for capacity purposes (`handoff_sent` + assignment `merge_blocked` / held handoff / no further product work), **or**  
   - **Finish/retire that merger promptly** so the slot frees, **or**  
   - Allow **one recovery merger create** even when a prior merger is `handoff_sent` if that prior agent is only awaiting cleanup (not `running` / not mid-merge).  
3. Prefer not leaving operators with pure `wait` while merge recovery is the real need.

**Solution direction:**  
- Tighten `active-template?` / capacity counting for merger: exclude agents whose assignment is merge_blocked and handoff is held/completed-pending, or treat `handoff_sent`+merge_blocked as non-capacity for singleton.  
- Or: mechanical path must finish held handoff / retire merge_blocked mergers so slot frees (pairs with held-finish P0 B02 — may still leave merge_blocked agents unretirable without completed path).  
- Residual: when open merger is only handoff_sent and merge_blocked, surface create next merger or retire_agent instead of wait.  
- Tests: two merge_blocked lineages; first merger handoff_sent → second merger create/spawn still offered.


**Priority rationale (P1):** Live multi-hour stuck swarms; residual only `wait` while merge recovery is blocked — same severity class as former P0 pipeline deadlocks.

**Where:** `squad_next.clj` `merger-candidates`, `active-template?`, capacity/singleton accounting; retirement/held-finish for merge_blocked; live `merger-003` + empty merger candidates.

**Related:** B26 (why merges keep failing on one file); fixed P0 held handoff finish; max_merger_depth / durable merge blockers.

---

---

---

### B26 — Single shared `acceptance/runner.clj` is a merge hotspot

**Symptom / gap:** Product acceptance is wired as **one** shared file, typically `acceptance/runner.clj` (loaded by `bb acceptance` / `gherkin-mutator --runner-worker`). Every story that extends acceptance tends to **edit that same file** (new feature flags, requires, step helpers, scenario checks). Parallel implementers, cleaners, and hardeners all touch it. Git then reports:

```text
CONFLICT (content): Merge conflict in acceptance/runner.clj
```

Live example (Hunt the Wumpus, 2026-08-12): hardener and then merger both dry-run-failed on `acceptance/runner.clj` because master already held a large multi-feature runner while the hardener branch carried a divergent rewrite focused on instructions. Agents sat `merge_blocked` / `handoff_sent` for hours; singleton merger slot stayed occupied (see stuck-swarm diagnosis).

This is not only “unlucky concurrent edits.” A **single monolithic runner** forces cross-story contention on one blob even when intents compose (add more scenarios).

**Expected:**  
1. **Thin shell** `acceptance/runner.clj` (or task body) that discovers and runs story/feature modules — rarely needs content merges.  
2. **Story- or feature-owned modules** (e.g. `acceptance/steps/<story>.clj`, per-feature entrypoints, or APS-generated entrypoints) so new work is usually a **new file**.  
3. Product templates and implementer/hardener guidance: prefer extending via new modules, not rewriting the shared runner.  
4. Optional: mechanical/layout check that flags runaway growth of a single runner file during review/hardening.

**Solution direction:**  
- Refactor product APS layout: dispatcher + pluggable step/feature modules (align with APS generator/runtime/handlers).  
- Update `swarmforge/templates` acceptance scaffold and role prompts so agents don’t treat one runner as the only place for new acceptance logic.  
- Constitution / implementer notes: shared runner edits are high-conflict; prefer additive files.


**Priority rationale (P1):** Structural product layout that repeatedly forces content conflicts and merge_blocked storms on multi-story themes; feeds B27.

**Where:** product `acceptance/`; `bb/tasks/acceptance.clj`; APS templates; implementer/hardener guidance; live Wumpus `acceptance/runner.clj` (~multi-story megafile).

**Related:** **B33** (implementer should build generator/runtime/steps, not grow mega runner); P1 APS wiring (B05 fixed for commands); B27 (merger slot stuck after conflict); B12/root tooling fights are a similar “everyone edits the same file” pattern.

---

### B33 — Implementer acceptance model diverges from six-pack coder (no generate/step suite)

**Symptom / gap:** Live products (e.g. Hunt the Wumpus) treat acceptance as **one imperative Clojure program** (`acceptance/runner.clj` + thin `bb acceptance` / `bb acceptance-worker` loaders): feature-presence checks, hand-coded `assert-true` blocks, and only partial IR binding for mutation. That is **not** how the six-pack **coder** is instructed to treat acceptance, and not full APS.

**Six-pack coder contract** (`six-pack` branch, `swarmforge/roles/coder.prompt`) — implementer should match this:

1. At startup, ensure APS pipeline tools (`gherkin-parser`; do not reimplement the parser).  
2. Build **project-specific** components: **acceptance entrypoint generator**, **acceptance runtime**, **step handlers**, normal acceptance scripts.  
3. Step handlers: **regex-based parameter extraction by default** for repeated step shapes; separate literal handlers only when wording is genuinely different behavior.  
4. **Running acceptance** means: run `gherkin-parser` → run project entrypoint generator → run the **generated executable tests** (project test-runner style).  
5. Keep **generated acceptance tests separate from unit tests**.  
6. Still **TDD with unit tests**; do **not** use generated acceptance as a substitute for units.  
7. Does not own QA suite / language mutation / CRAP / DRY / Gherkin mutation (those stay cleaner/hardener/etc.).

APS normal run (same intent):

```text
feature → gherkin-parser → JSON IR → entrypoint generator
  → generated test entry points → project test runner
  (runtime + step handlers)
```

**What squad implementer + product do today:**

| Expectation (six-pack coder / APS) | Live implementer outcome |
|------------------------------------|---------------------------|
| Generator + generated entrypoints | No (or unused) generator; no per-feature generated tests |
| Runtime dispatches steps | One `run-suite!` procedural body |
| Step handlers (regex captures) | Ad-hoc asserts; little reusable step layer |
| Features drive execution | Features often only **presence-checked** or re-encoded in runner |
| Suite-like failures per scenario/example | Single process, exit codes, `ACCEPTANCE_PASS: …` prints |
| Units via TDD + separate acceptance | Units exist; acceptance is a second monolithic program |
| Additive story files | Everyone edits **`acceptance/runner.clj`** (B26) |

`implementer.prompt` *names* generator/runtime/step handlers when the suite is missing, but scaffolds and practice steer to **`bb acceptance` + one runner file**, and still document that command as the mutator worker target (conflicts with B30). Agents “make acceptance pass” by extending the mega program, not by installing the six-pack pipeline.

**Expected:**

1. **Implementer role** (prompt, contract, constitution, tool-table, templates) requires the **six-pack coder acceptance model**: parser → generator → generated tests + runtime + step handlers.  
2. Product scaffold ships thin shells and extension points (per-feature/step modules, generator hook), not a growing god-runner.  
3. `bb acceptance` runs the **generated** suite (or discovers generated entrypoints); worker mode evaluates IR through the **same step runtime** (B30).  
4. New story work prefers **new step/feature modules + regenerate**, not rewrite of shared runner body.  
5. Implementer still owns focused unit TDD; acceptance does not replace units.  
6. Alignment with B26 (modular layout) and B30 (mutator worker); B31 hardener quality depends on IR-bound steps actually killing mutants.

**Solution direction:**

- Port/adapt six-pack `coder.prompt` acceptance section into squad **`implementer.prompt` / contract** (and constitution engineering APS bullets) so the duty is explicit and not optional “if missing.”  
- Replace `swarmforge/templates` acceptance scaffold: entrypoint generator stub, runtime, step-handler convention, generated-test output dir, suite runner that executes generated tests.  
- Prefer regex step handlers as default (as six-pack and APS step-handler contract).  
- Migrate live products off mega `runner.clj` over time (B26); stop teaching implementers that “acceptance = edit runner.clj.”  
- Keep unit tests as `clojure.test`/speclj; keep generated acceptance separate.  
- Tests/docs: implementer assignment for first feature produces generator+steps+generated tests, not only a hand-written suite script.

**Priority rationale (P1):** Wrong acceptance architecture is systemic: merge hotspots (B26), weak Gherkin mutation (B30/B31), and implementers shipping non-APS “acceptance programs” while Gherkin remains documentary. Six-pack already defined the correct coder duty; squad must not diverge.

**Where:** `swarmforge/roles/coder.prompt` on `six-pack` (source of truth for intent); `swarmforge/role-templates/implementer.prompt` + contract; constitution `engineering.prompt` APS section; `swarmforge/templates/` acceptance/bb tasks; product `acceptance/runner.clj`, `bb/tasks/acceptance*.clj`; APS `README.md` / `acceptance-generator.md` pipeline.

**Related:** B26 (mega runner hotspot — symptom of this model), B30 (worker/suite + IR binding), B31 (hardener kill-all needs real steps), B05 (suite command wired ≠ full APS), cleaner ownership of CRAP/DRY (six-pack coder does not own those).

---

## P2 — Operator path, workflow gates, architecture foundation

### B10 — Dashboard answers truncate to first line of multiline response

**Architecture note:** Concrete instance of informal `key: value` records (B22). Fix the request/answer path now; formalize formats later.

**Symptom:** Operator asks via dashboard. Full multiline answer is written and `squad_dashboard_request.sh answer` succeeds. **UI only shows the first line** (e.g. intro without `bb run wumpus`).

**Cause:** Line-oriented `key: value` parse; only the first `response: …` line is kept. Also no `white-space: pre-wrap` on render.

**Expected:** Full multiline answers round-trip write → read → dashboard with preserved breaks.

**Solution direction:**  
1. Headers + body (or escape/base64/`response: |` block) for multiline fields.  
2. Or sibling `….answer` file referenced from the kv header.  
3. Dashboard: `pre-wrap` / `<pre>` on request body.  
4. Test: blank lines and commands survive API and UI.

**Where:** `squad_dashboard_request.clj`; `squadd/web.clj` request rendering.

**Repro (live):** `dashboard-20260811T221429Z-001` — full answer in file; dashboard showed only first line.

---

---

---

### B09 — Operator unblock and dashboard requests need a Troubleshooter (not the squad leader)

**Architecture note:** Same finding as control-plane role split — SL must not be both residual-only workflow participant and free-form operator (see B16).

**Symptom / gap:** When the workflow is stuck, an **operator** can force packet fields, block thrash, retire agents, move held handoffs, etc. The **squad leader cannot** under residual-only rules. **Dashboard / SL requests** still wake and route to the **SL**.

**Why the SL is too constrained:**  
- Residual is the sole workflow driver; SL must not invent transitions.  
- Stuck states often residual as `wait` / `recover_agent` / capacity waits.  
- Well-behaved SL waits or reports — does not perform out-of-band surgery.

**Expected:** A **Troubleshooter** (persistent or on-demand), **outside the product FSM**:

| Property | Troubleshooter | Squad leader |
|----------|----------------|--------------|
| Part of story/pipeline FSM | **No** | Yes |
| Bound to residual-only for all work | **No** | Yes |
| May invent escape hatches / fix durable state | **Yes** when needed | No |
| May retire/block/force packet/theme fixes | **Yes** | Only if residual directs |
| Dashboard / debug requests | **Primary** | Product orchestration framing |
| Collaborates with SL | When legitimate workflow action is needed | Receives handoffs when appropriate |

**Solution direction:**  
1. Role `troubleshooter` (prompt + contract): repair swarm state; no product authoring workers own.  
2. Dashboard / SL-request wake → troubleshooter by default.  
3. Optional stuck-pattern alerts to troubleshooter.  
4. Keep SL residual-only for normal orchestration.  
5. Code fixes still land in product; troubleshooter is in-swarm operator, not a substitute for B16–B19.

**Where:** `squadd.clj` / `squadd/web.clj` request wake → SL; SL residual-only rules; no troubleshooter role.

**Repro:** Stuck thrash / held-finish — residual never offered the escape hatch; operator had to force packet/review state. SL following rules could not.

---

---

---

### B11 — Zombie tmux sessions after agent retire

**Symptom:** Agent **retired**, worktree gone, not in `roles.tsv` — but **tmux sessions still exist**. Retire detail may claim session was not running while `tmux ls` lists it.

**Cause:** Retire kill/detect mismatch (wrong socket, race, or liveness check vs swarm server). Distinct from held-handoff finish (fixed P0 B02): this is **session leak**.

**Expected:** After successful `squad_retire.sh`, agent tmux session is gone; status matches reality.

**Solution direction:**  
1. Retire always uses project tmux socket; verify `has-session` after kill.  
2. Force kill if still present; log hard failure if alive.  
3. Periodic squadd reconcile: `swarmforge-*` sessions with no live agent → kill or alert.

**Where:** `squad_retire.clj`; squadd role reconciliation.

**Repro (live):** Only SL in `roles.tsv`, residual idle, `tmux ls` still shows retired worker sessions.

---

---

---

### B12 — Hardener edits root tooling (`bb.edn`) against role rules

**Symptom:** Hardener commits change root `bb.edn` (merge conflicts). Prompt says do **not** edit root tooling unless assignment requires it.

**Cause:** Soft prompt only; concurrent hardeners + mergers fight over `bb.edn`.

**Expected:** Hardener does not touch denylisted root tooling unless assignment says so; violations rejected or blocked.

**Solution direction:**  
1. Strengthen prompt + Leader Instructions.  
2. Pre-handoff check: hardener result must not modify denylist.  
3. Prefer product layout that does not require hardener `bb.edn` edits (tasks under `bb/tasks/`).

**Where:** `hardener.prompt` / contract; live hardener commits including `bb.edn`.

---

---

---

### B25 — Implementation order and dependency-checker config must be user-approved

**Symptom / gap:** After analysis (or early tooling), two architecture-control artifacts can land and start driving the swarm **without an explicit user approval gate**:

1. **Module / implementation ordering** — makefile-style `implementation-order.md` (durable under `.squad/themes/<id>/implementation-order.md`). It hard-gates implementers (providers must have `implementation_sha`). Recording can be mechanical from a root draft; the operator never has to accept the order.  
2. **Dependency-checker configuration** — product `dependency-checker.edn` (allowed component graph). Implementers and hardeners enforce whatever is on disk; a pitiful or wrong policy (B13) can still go green without the user ever reviewing it.

Theme + module map already require approval before analysis. Ordering and dependency policy are equally consequential for **build sequence** and **Clean Architecture boundaries**, but they skip that class of gate.

**Expected:**  
1. User-facing **approval** (dashboard) for **implementation order** once a non-empty durable order is proposed (or when the order is first recorded / materially revised).  
2. User-facing **approval** for **dependency-checker.edn** once a non-trivial policy is proposed (or on material revision).  
3. Implementer / hardener gates respect “approved” state: do not treat unapproved order or checker config as final for hard enforcement (or residual blocks until approved).  
4. Re-approval (or a clear “still valid” path) when analyst/architecture work changes either artifact.  
5. Theme package UI shows approval status for both (pairs with B14 for checker display).

**Solution direction:**  
- New approval gates (e.g. `implementation-order`, `dependency-checker`) via `squad_approval.sh` / packet-or-theme state fields.  
- Residual after analysis merge: request these approvals when drafts exist and are unapproved.  
- Mechanical record of order (P0 B03) remains; **approval** is the operator step after record.  
- Align with B13 (real checker content) and B23 (theme lifecycle)—order/checker approve sits between theme start and story implementation thrash.

**Priority rationale (P2):** Prevents silent wrong build order and fake architecture gates; same “user must own the architecture control plane” idea as theme approve. Content quality of the checker remains B13.

**Where:** `squad_approval` gates; `squad_theme` / durable order + product `dependency-checker.edn`; `squad_next` residual after analysis / before implementers; dashboard theme package + approval UI; analyst/architect handoffs that produce these files.

**Related:** B13 (policy content), B14 (display), fixed P0 B03 (durable order must exist).

---

---

---

### B23 — Theme close / finalize is undefined (approval that still allows more stories)

**Symptom / gap:** Story packets have a path through implementation → final approval, and `squad_report.sh` can emit a theme report, but there is **no clear product/workflow notion of closing or finalizing a theme**. Operators and residual cannot tell:

- When a theme is “done enough to ship” vs still open-ended analysis/implementation  
- Whether the swarm should wind down (retire transients, stop spawning implementers for that theme) vs keep waiting for more stories  
- How a user marks “this release/theme slice is accepted” without permanently forbidding **new** stories later  

Themes today are effectively never finished: analysis can add stories, packets advance independently, and nothing transitions the theme into a durable terminal or “shipped but extensible” state.

**Expected (design intent — needs product decision, then FSM/UI):**

1. **Theme finalize / ship approval** — a user-facing approval (dashboard) that means: current theme scope is accepted as complete for this slice (all relevant stories final-approved, or an explicit operator override).  
2. **Still allow more stories** — finalize must **not** lock the theme forever. After finalize (or on a new “reopen / extend” path), analysts and user-directed stories can still add work. Prefer states like:
   - `open` — analysis/implementation in progress  
   - `shipped` / `finalized` — user accepted current slice; residual may idle or report rather than thrash  
   - return to `open` (or `extended`) when new stories are registered  
3. **Residual / SL behavior** when finalized: clear next action (e.g. theme report, wait for user, or idle) instead of endless capacity waits with no goal.  
4. **Dashboard** shows theme lifecycle state and a control to request finalize (and to add stories after ship).

**Solution direction (open design):**  
- Add theme-level approval gate (e.g. `theme-final` / `theme-ship`) distinct from early `theme` approve (theme + module map to start work).  
- Record durable state on `.squad/themes/<id>/` (status + timestamp + detail).  
- `squad_next`: when all stories terminal **or** user finalizes with exceptions, offer finalize residual; after finalize, stop creating work unless new stories appear.  
- New stories after finalize: either auto-reopen theme or require a light “extend theme” path — prefer the lighter path so incremental stories stay easy.  
- Document in SL / constitution: finalize is **acceptance of current slice**, not “no further product development.”

**Priority rationale (P2):** Blocks a coherent end-to-end product loop and operator understanding of “are we done?” without being a crash. Design before heavy FSM; can start as approval + status only.

**Where:** `squad_theme.clj` / theme status; `squad_approval` gates; `squad_next` theme candidates / residual when no story work remains; dashboard theme package; SL prompt theme lifecycle; `squad_report.sh`.

---

---

---

### B17 — Actions are shell strings, not structured ops

**From architecture review.** Actions are strings like `squad_assign.sh accept-merge …`, then `bash -c`. Quoting, env, and authority leak everywhere (held-path finish, paths with spaces, role env).

**Expected:** Actions are structured data, e.g. `{:op :accept-merge :assignment-id … :authority :daemon}`. Shell is rendered only at the outermost CLI boundary (or not at all for in-process executor).

**Solution direction:**  
1. Define a closed set of ops with required keys + authority.  
2. Planner emits ops; executor dispatches; renderer pretty-prints for humans.  
3. Migrate hot paths first (handoff steps, packet record, spawn request) without rewriting every script at once.

**Enables:** B16, B18, safer held/finish class fixes.  
**Where:** `squad_next.clj` candidates / `apply-candidate!`; handoff mechanical steps.

**Priority rationale (P2):** Foundational architecture. Land incrementally; do not block product delivery on a full rewrite.

---

---

---

### B18 — `squad_next` mixes planning, policy, presentation, and execution

**From architecture review.** One module computes candidates, applies them, prints user-facing commands, encodes daemon priority, and hides commands for SL residual mode. Every workflow change is high-risk (thrash, held finish, order gates all lived here).

**Expected:** Three layers:  
1. **Planner** — pure: state → typed actions (B17).  
2. **Executor** — applies allowed action sets for the current authority.  
3. **Renderer** — formats daemon / SL residual / operator views.

**Solution direction:**  
1. Extract pure candidate → action map first (no I/O).  
2. Move `apply-candidate!` / mechanical pass into executor with allow-lists.  
3. Residual print becomes renderer over the same actions (no second policy path).

**Depends on:** B17 (structured actions). Pairs with B19 (priority in planner only).  
**Where:** `swarmforge/scripts/squad_next.clj` (majority of control-plane complexity).

**Priority rationale (P2):** Highest long-term leverage against recurring FSM bugs. Slice after structured actions start.

---

---

---

### B16 — Control-plane ownership is implicit (env/prompt), not modeled

**From architecture review.** `squadd` owns mechanical FSM work and main Git writes, but ownership is enforced through env vars (`SWARMFORGE_MAIN_GIT`, `SWARMFORGE_ROLE`) and prompt discipline. That is weak: unsafe ops remain reachable from the wrong entrypoint if env is wrong or a role invents a command.

**Expected:** Ownership is first-class in the command/action API: **daemon/internal** vs **SL-facing** vs **operator/troubleshooter**. Unsafe actions (accept-merge, packet surgery, etc.) are unreachable from the SL residual entrypoint.

**Solution direction:**  
1. Tag structured actions with `:authority` (see B17).  
2. SL residual renderer/executor only accepts residual-safe ops.  
3. Daemon executor accepts mechanical + main-git set.  
4. Troubleshooter (B09) gets an explicit elevated operator set — not “SL with fewer rules.”

**Depends on / enables:** B17, B18, B09.  
**Where:** `squad_next.clj`, `squadd.clj`, assign/merge scripts, residual-only path.

**Priority rationale (P2):** Single-writer already works in practice; modeling ownership prevents regressions and makes B09 clean.

---

---

---

### B19 — Scheduling priority is emergent across many code paths

**From architecture review.** Bookkeeping, daemon-ready actions, spawn requests, handoffs, SL/dashboard requests, status, watchdogs, and approvals each have their own ordering. Policy like “main Git before retire” or “operator request outranks FSM task” is emergent, not testable in one place.

**Expected:** One planner policy function (or ordered policy table) decides priority. Tests assert policy directly.

**Solution direction:**  
1. After B18, all ready work enters the planner as typed actions.  
2. Single sort key / priority table for daemon drain vs residual vs operator.  
3. Remove ad-hoc “first in this loop wins” except where explicitly policy.

**Depends on:** B17–B18.  
**Where:** mechanical pass ordering; spawn poll vs workflow; dashboard wake vs residual.

**Priority rationale (P2):** Same foundation cluster as B18; do not invent a third priority scheme before structured planner exists.

---

---

## P3 — Product polish and deeper architecture

### B13 — Analyst dependency-checker policy is missing or pitifully coarse

**Symptom:** Product `dependency-checker.edn` is a minimal two-component sketch (`:main` / `:process`). Green checker while internal process graph is unconstrained.

**Who owns it:** Analyst should author a real policy from the theme module map.

**Expected:** Real components and allowed edges (UI → process → pure domain); analysis incomplete without non-trivial policy (or waiver). Policy should then be **user-approved** (B25), not only present on disk.

**Where:** `analyst.prompt` / contract; `theme-module-map.md`; product `dependency-checker.edn`.

**Related:** B14, B25.

---

---

---

### B14 — Theme package page should include dependency-checker.edn card

**Symptom:** Theme package view omits `dependency-checker.edn`.

**Expected:** Card next to module map / implementation order; clear missing state; ideally approval status for checker and order (B25).

**Where:** `squadd/web.clj` theme package sections.  
**Related:** B13, B25.

---

---

---

### B15 — Grok agent terminal window does not fill / scroll correctly

**Symptom:** Grok-backed agent windows show ~25 lines pinned at top; rest empty; scroll unusable. Codex panes do not show this.

**Expected:** Full geometry, usable scroll — or documented operator path if upstream TUI cannot.

**Where:** Grok launch / terminal adapters / tmux size; `swarmforge/docs/grok-agent-window-scroll.md`.

---

---

---

### B24 — Dashboard display needs better information architecture / organization

**Symptom / gap:** The operator dashboard (`squadd` web UI) works as a flat status surface—agents, assignments, approvals, requests, theme package, story packets—but **layout and hierarchy are hard to scan**. As swarms grow, the important signal (what needs me, what’s blocked, theme progress) competes with noise (every agent row, every intermediate state). Related defects (B10 multiline truncate, B14 missing theme cards, B09 request routing) sit on top of a display that was never designed as a coherent operator console.

**Expected (design — needs deliberate IA, not only more panels):**

1. **Clear visual priority:** “Needs operator” (approvals, dashboard requests, stuck/blocked) above “in flight” above “history / detail.”  
2. **Theme-centric navigation:** theme → stories → pipeline stage, not only a bag of agents and assignment ids. Align with theme lifecycle (B23) when that exists.  
3. **Scannable density:** progressive disclosure (summaries, expand for detail) rather than dumping full packet/request text into the main scroll.  
4. **Consistent grouping** of related surfaces (approvals vs SL/troubleshooter requests vs agent fleet vs theme package artifacts).  
5. **Stable chrome** so operators always know where to look for the next action.

**Solution direction (open design):**  
- Sketch wireframes / IA before more one-off cards.  
- Inventory current sections in `squadd/web.clj` (`dashboard-html`, `render`, API payloads) and regroup by operator job-to-be-done.  
- Prefer filters/tabs (Active / Needs you / Theme / Agents) over a single infinite list.  
- Coordinate with B10 (readable multiline), B14 (complete theme package), B09 (who owns requests), B23 (theme state badge).

**Priority rationale (P3):** Operator pain and cognitive load, not a functional blocker. Do after or alongside concrete dashboard bugs (B10, B14); avoid large redesign mid-P0/P1 firefighting.

**Where:** `swarmforge/scripts/squadd/web.clj` (HTML/CSS/JS + JSON renderers); dashboard request UI; theme package view; any status APIs the SPA polls.

---

---

---

### B20 — Shared lease primitive missing (locks are one-off)

**From architecture review.** Main-git lock protects merge-ready/accept-merge, but spawn lock, handoff in-process/held, status files, and request queues each invent their own concurrency story.

**Expected:** A **small common lease module**: token ownership, stale/TTL policy, atomic acquire/release, consistent logging. **Per-resource use** (merge lease, spawn lease, handoff claim) — **not** one mega-lock over the swarm.

**Solution direction:**  
1. Extract merge-lock patterns into `lease.clj` (or similar).  
2. Adopt for spawn and handoff claim next.  
3. Document which resources need leases vs single-writer daemon ownership (B16).

**Priority rationale (P3):** Valuable after hot paths stabilize; premature unification can hide different lifetimes. Prefer after B17–B18 slices exist.

**Where:** main-git lock; spawn lock; handoff in_process claim.

---

---

---

### B21 — FSM transitions have hidden multi-file side effects

**From architecture review.** e.g. `accept-merge` updates assignment status, merge state, accepted-merge, merger lineage, events, theme events. Hard to reason about as one transition.

**Expected:** Transition functions with explicit before → after state; **one persistence layer** writes all affected records for that transition.

**Solution direction:**  
1. Document each high-risk transition’s write set.  
2. Implement transition + persist pairs for accept-merge, record-result, retire.  
3. Tests assert full after-state, not a single file.

**Depends on:** B17–B18 help; B22 helps record shape.  
**Priority rationale (P3):** Large; reduce risk after typed actions exist.

---

---

---

### B22 — Durable record formats are informal across categories

**From architecture review.** Many files are `key: value` until blank line. Easy to debug, but multiline values (B10) and partial lifecycle writes recur. (Safe missing-file readers for agent telemetry landed with P1.)

**Expected:** Standardize by category:  
- **Headers + body** for messages/requests/answers  
- **EDN** (or similar) for structured state  
- **Append-only events** for history  

Shared safe readers (missing file → empty; no exists/slurp race).

**Solution direction:**  
1. Land B10 as targeted multiline fix.  
2. Introduce format helpers and migrate one record type at a time (dashboard requests, then assignment status, then packets if needed).  
3. Avoid big-bang rewrite of every file on disk.

**Priority rationale (P3) as a program:** Systemic. Concrete remaining pain is P2 B10.

**Where:** `file-map` / `parse-kv` call sites; dashboard requests; agent status/heartbeat; packets.

---

---

## Architecture north star (not a freeze)

Keep the **single-writer** direction. Prefer **typed actions + planner/executor**, then **durable readers/leases**, over more prompt text or one-off retries. **Fix P1 B28, B29, B32, B33, B26, B30, B31, B27 before large control-plane rewrites** — dashboard stall visibility, batch replace ↔ result projection, implementer APS = six-pack coder model, modular acceptance layout, reliable Gherkin mutation facilities, hardener quality bar (CRAP/mutants/DRY), free merger slot. Theme finalize (B23) should stay a deliberate product approval, not an irreversible lockout of new stories. Dashboard IA (B24) should follow operator jobs, not grow as an uncurated status dump.
