# Issues

Replay-swarm and leftover-redo tracker. Bodies kept; grouped for reading. On-hold items are still open.

## Index

**On hold**

- Analyst orders the backlog; workflow starts stories; implementer waits on merged preds

**Open**

- Start the backlog: System-Analyst builds the executable frame
- QA placeholders should use story names
- System-analyst did not use the story bodies
- Story assignments still send analysts to `.squad/backlog`
- Gherkin Tool Startup does not show parser argv
- Six stories invented synonymous steps and QA CLIs
- Story cards stay in Specifying while implementers run
- Implementer ran swarm_handoff.sh --help
- Product coverage covers SwarmForge scripts, not `src`
- Cleaner Tool Startup does not show CRAP/DRY argv
- Git handoff cannot carry evidence notes
- No-arg swarm_handoff stamped the wrong HEAD and jammed residual

---

## On hold

### Analyst orders the backlog; workflow starts stories; implementer waits on merged preds

**On hold.** Prefer independent per-story plans until this is taken off hold. **Do not use a DAG.** The preferred alternative is **Start the backlog: System-Analyst builds the executable frame**.

The operator **starts the backlog**, not a single story. The analyst (best placed: already reads every story) writes a **dependency graph of existing items** — including which stories may run concurrently and which may not — plus implementation plans. Residual then runs stories in that order. This is not a new `implementation-order.md` theme ceremony and not invented sibling stories.

This swarm started replay first. Walk → pits → bats → Wumpus → arrows → replay is what the stories already say. Pits and Wumpus may proceed **in parallel after walk**. Bats need pits. Arrows need Wumpus. Replay needs a real terminal (sample: walk + pits + bats). Starting 6 first forced fake pit/bat/win stamps.

**When a dependent story gets an implementer:** not at Sa `handoff_sent`. After **SL `accept-merge` of Sa’s implementer** (`implementation_sha` on master, Sa Gherkin already green). Then residual may assign Sb’s implementer. Do **not** wait for Sa cleaner, CR, hardener, QA, or architect — those overlap with Sb implement. Specify (plan / Gherkin / QA-proc) for Sb may start as soon as the graph names the edge.

Roots start when the backlog-start and that story’s plan are approved. Concurrent siblings (pits ∥ Wumpus) both become implementable at the same pred-merged-implementation gate.

The analyst pass must exist **before** the workflow picks an implementer (first pass over the backlog, not only `plan.md` of a story already started). SL/cockpit enforce the graph; they do not author it.

**Where:** `analyst.prompt`; backlog Start vs per-story Start; residual `create_assignment` implementer; packet `implementation_sha`; this Wumpus backlog.

---

## Open

### Start the backlog: System-Analyst builds the executable frame

**Open.** Spec: `docs/superpowers/specs/2026-08-20-system-analyst-design.md`. Replaces the on-hold DAG: no story order file, no implementer waiting on a predecessor SHA.

Independent per-story assignments still yield a series of little applications unless every role shares one product to extend. A mission paragraph is not enough. The vision has to be a **running skeleton**.

**Start the backlog** is the operator saying: this set of stories is one product. Not Start on a single card.

**System-Analyst** then reads the Mission and Sockets on the assignment and produces the **frame**, not the game:

- One executable, one console, one turn loop.
- Named sockets from the backlog (restart prompt, move, pits, bats, Wumpus, arrows) as empty ports or dummy state.
- No story’s rules, messages, or wins. Those stay in later assignments.

That frame **is** the vision. Later roles keep their assignments and **extend this process from the inside**. Replay and walk can proceed in parallel because both already live inside the same `SAME SET-UP` / `SHOOT OR MOVE` program.

**Extend, do not bolt on.** Every later agent (per-story analyst, Gherkin, QA-proc, implementer, cleaner, CR, hardener, QA, architect, SI) is oriented to grow the frame: fill a named socket, keep the one executable and the one UI. They do **not** attach a sidecar (a second `-main`, a story-specific probe app, a private cave map, a dummy topology copied into the restart module). This swarm’s replay story did the bolt-on: topology is a port, but the stub grew `yob-dodecahedron` and adjacency warnings so the probe could stand alone. That is the failure mode.

Mocks fill unbuilt neighbors **in the same process**. Gherkin and QA drive the frame’s UI, not a new command invented for the story.

What it is not: a theme, an order file, a second backlog, or a late glue pass. If the System-Analyst implements hunt behavior, the stories have been collapsed. If it only writes a document, you are back to stubs.

Operator gates that frame the way they gate a plan. After it is on master, per-story work cuts **this** story only, against that executable.

**Where:** backlog Start (whole backlog, not one story); System-Analyst role; residual waits for the frame on master before story implementers; constitution / prompts so every role treats the frame as already real.

### QA placeholders should use story names

**Open.** `qa/product.md` from `system-analyst-001` is only HTML comments:

```
<!-- bl-20260821-002 -->
```

No walk / pits / bats / Wumpus / arrows / replay. Later QA-proc writers cannot see which placeholder is theirs.

The prompt asked for `<!-- <backlog-id> -->` per open item. The agent did that and stopped.

**Proposed fix:** assignment and prompt: one placeholder per **open story**, labeled with the story title (and the id if needed). Not a bare backlog id. Mission is not a placeholder.

**Where:** `qa/product.md`; `system-analyst.prompt`; `system-analyst-001` pane.

### System-analyst did not use the story bodies

**Open.** After it found the product-root items, it opened all seven `.item` files. `frame.md` got titles and ids. The frame UI is still `LOOK` / `WAIT` / `QUIT`. Walk already specifies `INSTRUCTIONS (Y-N)?` and the room report. Those sentences never entered the executable.

It treated the files as a list of ids, not as stories. Counting “seven backlog items” (mission plus six stories) as “game sockets plus the application loop” is the same miss.

**Proposed fix:** put story **bodies** (or the first-screen / command text) on the assignment, not a path to hunt. Prompt: the Mission is the loop; the stories name the empty prompts of **that** loop. Do not invent LOOK/WAIT/QUIT. Do not invent a menu of socket names.

**Where:** `.squad/sessions/system-analyst-001/pane.txt`; `frame.md`; `src/wumpus/frame.clj`; assignment Mission/Sockets; `system-analyst.prompt`.

### Story assignments still send analysts to `.squad/backlog`

**Open.** Six story analysts (`analyst-001` … `006`) wrote one `plan.md` each, pointed at `bb wumpus`, copied the assignment non-goals, and handed off cleanly. They still listed `.squad`, `backlog`, or `assignment.md` in the worktree, found nothing (control plane is on the product root), then wrote the plan from the injected assignment anyway. Analyst-001 grepped `/Users/unclebob/junk/squad/.squad/backlog/*.item` and added Mission as a non-goal. Mission is not a story.

The assignment still says: “Read `.squad/backlog/<id>.item` only if a port needs a sentence.” The analyst prompt still says other items live in `.squad/backlog/*.item`. That is the hunt.

Plans then authorized a **dummy cave** until Walk lands (pits, bats, Wumpus, arrows). That is the bolt-on the frame was meant to stop: a second topology instead of this loop.

**Proposed fix:** Non-goals are the titles already on the assignment. Do not send the analyst to `.squad/backlog` or `stories/`. The story is in this document. Neighbor names are in Non-goals. Dummy **state** for unbuilt neighbors is allowed in the same process; a private dummy map is not. Extend `frame.md`’s cave/UI.

**Where:** `squad_assign.clj` `non-goals-section`; `analyst.prompt`; `.squad/sessions/analyst-00{1-6}/pane.txt`; the six `plan.md` files.

### Gherkin Tool Startup does not show parser argv

**Open.** Six gherkin writers (`gherkin-writer-001` … `006`) installed APS tools and parsed before handoff. Every one then ran `gherkin-parser --help` and `ir-dry-checker --help` to learn how to invoke them. Smell’s first DRY run omitted the report path (`ir-dry-checker <json-ir>` with no output file), then reran with the path from `--help`.

Tool Startup on the assignment lists `squad_tool.sh require` / `ensure` only. It does not show:

```
gherkin-parser <feature-file> <json-output>
ir-dry-checker [--include-exact] <json-ir> <report-output>
```

Same pattern as the old `swarm_handoff.sh --help` fumble: the helper is named, the argv is not.

**Proposed fix:** put the exact parse and DRY command lines on the assignment next to Tool Startup (or in `tool-table.edn` and render them). Do not send the agent to `--help`.

**Where:** `squad_tool_table.clj` `startup-instructions`; `tool-table.edn`; gherkin-writer assignment Tool Startup; `.squad/sessions/gherkin-writer-00{1-6}/pane.txt`.

### Six stories invented synonymous steps and QA CLIs

**Open.** Biggest concern from the gherkin/QA-proc pass. Six writers, each IR-DRY-checked **their own file**, so the collision never fired. The implementer harness has to learn a dictionary.

Same act, many phrasings. Start: `When the game starts a hunt` / `When a hunt starts` / `When a hunt placement is created` / `When the hunt places bottomless pits` / `Given the player has started a hunt` / `Given a hunt is in progress`. Turn: `When the next turn is shown` / `When the turn report is printed` / `Then the next turn begins in room N` / `the report prints` / `the transcript contains` / `the game prints "YOU ARE IN ROOM N"`. Move: Walk answers `M` then `WHERE TO?`; pits/bats/Wumpus use `When the player moves` / `When the hunter moves`. Cave: `fixed Yob cave map is active` vs a private tunnel table vs `room 1 has tunnels to` vs `room 1 tunnels to rooms`. Hazards and win/lose split the same way (`pits occupy` / `pits are in` / `bottomless pit rooms are` / placement table; `hunt is lost immediately` / `hunt is lost` / `hunt ends with` / `game outcome is a loss with`).

Walk mostly talks to the console. The others talk to **state** (`places pits`, `Wumpus port`, `wake choice will be stay`). That is a second language, not a synonym of the UI.

QA flags are the same split on the command line: `--qa-hunter-room`, `--qa-setup hunter=`, `--qa-placement`, `--qa-scenario`. Four names for “put the pieces here.”

**Proposed fix:** one product UI vocabulary. Gherkin and QA procedures type the same prompts the stories already name (`INSTRUCTIONS`, `SHOOT OR MOVE`, `WHERE TO?`). Dummy **state** in the same process is allowed; a private map or a per-story QA CLI is not. Run IR DRY across the `features/` set, not per file.

**Where:** `features/*.feature`; `qa/product.md`; `qa/*-implementer-notes.md`; gherkin-writer / qa-procedure-writer prompts; `.squad/sessions/gherkin-writer-00{1-6}` and `qa-procedure-writer-00{1-6}`.

### Story cards stay in Specifying while implementers run

**Open.** Many implementers are in flight (`implementer-001`…`006`; replay already `implemented` and a cleaner is running). Story cards do not reliably enter the Coding swimlane.

`board-column` maps `implementation_approval_ready` → **specifying** and `implemented` → **coding**. Packet state stays `implementation_approval_ready` until `implementation_sha` is present (merged implementer result). A live implementer, `implementation_assignment_state: ready`, or `handoff_sent` does not move the card. Pits and bats were `handoff_sent` with packets still `implementation_approval_ready`. Walk, arrows, and Wumpus were still `running` in specifying.

**Proposed fix:** Coding when this story has an implementer (assignment created / agent live / handoff in flight), not only after the SHA is on master. Specifying is plan / Gherkin / QA-proc. Do not wait for merge to leave Specifying.

**Where:** `squadd/web.clj` `board-column-by-state`; `squad_state.clj` `state-transitions`; dashboard board; live packets vs `.squad/agents/implementer-00*`.

### Implementer ran swarm_handoff.sh --help

**Open.** Replay `implementer-003` committed, then ran `swarm_handoff.sh --help`, hunted for a result draft, then ran the no-file command and queued successfully. The assignment already said `swarm_handoff.sh` with no file. Same reconstruct-the-CLI pattern as Gherkin parser argv.

Pits `implementer-001` did the no-file path on the first try.

**Proposed fix:** keep the assignment as one command with no file. Do not print a draft shape in `--help` that looks like the agent must fill a template. Evidence (`unit_tests`, `acceptance_suite`) belongs in the lifecycle detail, which 003 already put there after `--help`.

**Where:** `.squad/sessions/implementer-003/pane.txt`; `swarm_handoff.sh`; implementer assignment protocol.

### Product coverage covers SwarmForge scripts, not `src`

**Open.** Replay `cleaner-001` ran `bb coverage` first. LCOV was SwarmForge (`squad-*.clj`), every `hunt-wumpus.core` function 0% (`COVERAGE_OK: existing LCOV kept; clj exit 255`). CRAP on that file is invalid. They noticed and ran a one-off Cloverage `-p src -s test` without changing the `coverage` task.

This swarm’s product is inside a SwarmForge tree. `deps.edn` `:cov` uses `-p swarmforge/scripts`. Product template `product-deps.edn` already aims `--src-ns-path src`. The live `bb coverage` still covers the framework.

**Proposed fix:** product `bb coverage` covers product `src` (and its tests). Do not leave CRAP on a SwarmForge LCOV. If this checkout is both framework and product, the product task must still point at `src`.

**Where:** `deps.edn` `:cov`; `bb/tasks/coverage.clj`; `swarmforge/templates/product-deps.edn`; `.squad/sessions/cleaner-001/pane.txt`.

### Cleaner Tool Startup does not show CRAP/DRY argv

**Open.** `cleaner-001` installed crap4clj, dry4clj, and dependency-checker, then ran `--help` on each to learn how to invoke them. First `dry4clj --help` failed (`no such file`) until a second `squad_tool.sh require` created the worktree hardlink. Same reconstruct-the-CLI pattern as Gherkin parser argv.

Tool Startup lists `require` / `ensure` only. It does not show:

```
crap4clj --use-existing-coverage --lcov target/coverage/lcov.info --source-root src
dry4clj --project-root . --sut-ns <product-ns> src test
dependency-checker dependency-checker.edn --source-path src
```

**Proposed fix:** put those exact run lines on the cleaner assignment next to Tool Startup (or in `tool-table.edn` and render them). Do not send the agent to `--help`.

**Where:** `squad_tool_table.clj` `startup-instructions`; `tool-table.edn`; cleaner assignment Tool Startup; `.squad/sessions/cleaner-001/pane.txt`.

### Git handoff cannot carry evidence notes

**Open.** Cleaner assignment asks for evidence headers (`coverage:`, `crap:`). No-arg `swarm_handoff.sh` fills a **git** draft only. Extra headers are invalid. `message` is allowed only when `type: note`.

`cleaner-002` queued the git handoff, saw no coverage/CRAP lines, started a follow-up note, opened `result-handoff.draft`, and **did not send the note** because the git schema would fail validation. Evidence went into `handoff_sent` detail only.

`cleaner-003` wrote `.swarmforge/handoffs/cleaner-003-evidence.note` and ran `swarm_handoff.sh` on that file. A second packet sat in `inbox/new` behind the walk jam.

**Proposed fix:** git handoff stays one command, no file. Required evidence (`coverage`, `crap`, `unit_tests`, `acceptance_suite`) is lifecycle detail or first-class git_handoff fields the helper fills. Do not make the agent author a note draft. A path argument remains for true notes, not for CRAP.

**Where:** `swarm_handoff.clj` `fields-by-type` / `assignment-git-handoff!`; cleaner Required Tool Evidence; `cleaner-002` pane; `cleaner-003` evidence note.

### No-arg swarm_handoff stamped the wrong HEAD and jammed residual

**Open.** Walk `implementer-006` committed `ed652ec` on `swarmforge-implementer-006` and ran `swarm_handoff.sh` with no file. The helper filled the **assignment** draft from the wrong HEAD: `e7136cb` (Merge pit implementation handoff, already on master), `artifacts: none` (default `diff-tree` on a merge commit). `keep-draft` left that file at `.squad/assignments/walk-the-numbered-dodecahedron-cave-implementation/result-handoff.draft`. The queued handoff used those fields.

Residual dequeued it to `inbox/in_process` and will not leave it:

```
Result commit e7136cbcd5 is not reachable from sender branch swarmforge-implementer-006
```

SL reruns the same `squad_assign.sh result …` command. Nothing behind it moves (Wumpus/arrows implementations, two cleaners, replay CR `changes-requested`).

Fill uses `git rev-parse HEAD` / `diff-tree HEAD` in the process cwd (`user.dir`). The assignment draft and outbox live on the **project root**. If cwd is master, or GIT_DIR is the main repo, you get master’s merge commit, not the worktree SHA.

**Proposed fix:** no-arg fill reads HEAD and artifacts from the agent’s **assigned worktree**, never from master. If the SHA is not on the sender branch, fail the handoff and do not leave a poison file in `in_process`. Residual must not loop the same invalid `record_assignment_result`.

**Where:** `swarm_handoff.clj` `head-commit` / `head-artifacts` / `assignment-git-handoff!` / `state-dir`; walk `result-handoff.draft`; `inbox/in_process/…implementer-006…handoff`; SL pane; `squad_next` residual.
