# Issues

Replay-swarm and leftover-redo tracker. Bodies kept; grouped for reading. On-hold items are still open.

## Index

**On hold**

- Analyst orders the backlog; workflow starts stories; implementer waits on merged preds

**Open**

- Start the backlog: System-Analyst builds the executable frame

**Done**

- Analysis fumbles (analyst-001 session)

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

**Open.** Not implemented. Spec: `docs/superpowers/specs/2026-08-20-system-analyst-design.md`. Replaces the on-hold DAG: no story order file, no implementer waiting on a predecessor SHA.

Independent per-story assignments still yield a series of little applications unless every role shares one product to extend. A mission paragraph is not enough. The vision has to be a **running skeleton**.

**Start the backlog** is the operator saying: this set of stories is one product. Not Start on a single card.

**System-Analyst** then reads every backlog item and produces the **frame**, not the game:

- One executable, one console, one turn loop.
- Named sockets from the backlog (restart prompt, move, pits, bats, Wumpus, arrows) as empty ports or dummy state.
- No story’s rules, messages, or wins. Those stay in later assignments.

That frame **is** the vision. Later roles keep their assignments and **extend this process from the inside**. Replay and walk can proceed in parallel because both already live inside the same `SAME SET-UP` / `SHOOT OR MOVE` program.

**Extend, do not bolt on.** Every later agent (per-story analyst, Gherkin, QA-proc, implementer, cleaner, CR, hardener, QA, architect, SI) is oriented to grow the frame: fill a named socket, keep the one executable and the one UI. They do **not** attach a sidecar (a second `-main`, a story-specific probe app, a private cave map, a dummy topology copied into the restart module). This swarm’s replay story did the bolt-on: topology is a port, but the stub grew `yob-dodecahedron` and adjacency warnings so the probe could stand alone. That is the failure mode.

Mocks fill unbuilt neighbors **in the same process**. Gherkin and QA drive the frame’s UI, not a new command invented for the story.

What it is not: a theme, an order file, a second backlog, or a late glue pass. If the System-Analyst implements hunt behavior, the stories have been collapsed. If it only writes a document, you are back to stubs.

Operator gates that frame the way they gate a plan. After it is on master, per-story work cuts **this** story only, against that executable.

**Where:** backlog Start (whole backlog, not one story); new System-Analyst role (or first pass before per-story analyst); residual waits for the frame on master before story implementers; constitution / prompts so every role treats the frame as already real.

### Analysis fumbles (analyst-001 session)

**Done.** No-arg `swarm_handoff.sh` fills HEAD commit/artifacts; `--help` is usage. `squad_run.sh` accepts a bare command. Assignment lists other backlog titles, drops “provided theme,” and says the story is in the document. Analyst prompt names `.squad/backlog` and the plan path.

The plan was fine. After that the agent reconstructed helper CLIs and looked in the wrong place. Prompts that say “do the right thing” did not help. Same pattern on every fumble: do not make the agent author ceremony.

Skip: commit message without the assignment id. `worker-common` already says identify the assignment.

Do not change note handoffs, SL routing, retire capture, analyst plan sections, or the independent-story rule.

**Where:** `.squad/sessions/analyst-001/pane.txt`; `swarm_handoff.sh`; `squad_run.sh`; `squad_assign.clj`; `analyst.prompt`; `worker-common.prompt`; constitution `handoffs.prompt`.

#### Handoff drafts: `swarm_handoff.sh` with no args fills commit and artifacts from HEAD

Agents often spent 15–70s after the work was done fumbling `swarm_handoff` (template placeholders like `<10-char-commit>`, SHA length, header-only drafts). Hardener left `result-handoff.draft` unfilled; events skipped `handoff_sent`.

This run: the assignment already shipped a nearly complete `result-handoff.draft`. The agent never touched it. Instructions showed the **shape**, not the **path**, and `swarm_handoff.sh` requires a file, so it ran `--help` (treated as a filename: `Draft file not found: --help`), then `mktemp` in `/tmp` and queued successfully. The leftover draft still has `<10-char-commit>`. SHA and artifacts were correct. The protocol is the problem, not the SHA.

This agent was already told to use “the provided draft.” A filled draft at create time cannot know the commit SHA. A separate fill helper plus `swarm_handoff.sh <file>` is an extra step they will skip.

**Proposed fix:** assignment `git_handoff` is `swarm_handoff.sh` with no args. After the commit:

```sh
swarm_handoff.sh
```

The helper:

1. Uses `SWARMFORGE_ROLE` and the agent metadata `task_id` to find `.squad/assignments/<id>/result-handoff.draft` on the **project root**.
2. Sets `commit` to `git rev-parse --short=10 HEAD`.
3. Sets `artifacts` from `git diff-tree --name-only -r HEAD` (or `none`).
4. Validates and queues as it does today.
5. Reviewers still must have `review_decision` in the draft (or pass `--review-decision`); otherwise fail with that one line.
6. A path argument stays for `note` handoffs and odd cases.

`--help` prints usage. It must not be read as a draft path.

Assignment protocol text becomes one command, not a template block. Drop “using this draft shape” and the pasted placeholders. Constitution: assigned `git_handoff` is no-arg `swarm_handoff.sh`; notes still use a draft file.

The draft file remains the record; the agent stops authoring it.

#### `squad_run.sh` CLI

After the plan was written it ran `squad_run.sh "verify required analyst plan sections" sh -lc '…'` and hit `Usage: squad_run.sh [--expect-failure] <phase> <detail> -- <command...>`. Reran correctly. `worker-common.prompt` says to use `squad_run.sh` and never shows `<phase> <detail> --`.

**Proposed fix:** accept the natural form.

```sh
squad_run.sh grep -q "^## purpose$" plan.md
squad_run.sh --expect-failure bb test
```

Default `phase=run`, detail = the command string. Keep `--` / `--phase` / `--detail` for callers that want them. Print that one-liner in `worker-common.prompt`. `--help` prints usage, not a failed parse.

Do not require a heading-grep via `squad_run` for the analyst; if they use it, the simple form must work.

#### Non-goals missed the backlog

The prompt says read other backlog items only to name them as non-goals. Five other open items (walk, pits, bats, Wumpus, arrows) live in `.squad/backlog/*.item`. The analyst grepped `stories/*.md`, found only the started story, and wrote that no other stories are present. Ports in the plan were close; the non-goals list was empty of the actual backlog.

**Proposed fix:** put the other titles on the assignment. `assignment.md` gets a **Non-goals (other backlog items)** list of open `.squad/backlog` titles (not this story). Analyst copies those names into `plan.md` non-goals; read `.squad/backlog/<id>.item` only if a port needs a sentence.

Name the path in `analyst.prompt`: other items are `.squad/backlog/*.item`, not `stories/*.md`. Unstarted work is not under `stories/`.

#### Hunting for a `stories/` directory

The assignment already inlined the story. The prompt says it is on disk under `stories/`. The file is `stories/<id>.md`; the plan is `.squad/stories/<id>/plan.md`. The agent listed directories.

**Proposed fix:** assignment: “The story is in this document. Write `.squad/stories/<id>/plan.md`. Do not search for a stories directory.” Prompt: same two paths, file vs plan dir.

#### Leftover “provided theme”

`squad_assign.clj` default instructions still say “Use the provided theme, story packet, and role prompt as the source of truth.”

**Proposed fix:** drop theme. “Use the story in this assignment and the role prompt.” Sweep remaining “theme” in assignment copy (not git/project-root).
