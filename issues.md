# Issues

Replay-swarm and leftover-redo tracker. Bodies kept; grouped for reading. On-hold items are still open.

## Index

**On hold**

- Analyst orders the backlog; workflow starts stories; implementer waits on merged preds

**Open**

- Start the backlog: System-Analyst builds the executable frame
- QA placeholders should use story names
- System-analyst did not use the story bodies

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
