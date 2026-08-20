# Issues

Replay-swarm and leftover-redo tracker. Bodies kept; grouped for reading. On-hold items are still open.

## Index

**On hold**

- Analyst orders the backlog; workflow starts stories; implementer waits on merged preds
- Handoff drafts: diagnose from saved session files before changing the protocol

---

## On hold

### Analyst orders the backlog; workflow starts stories; implementer waits on merged preds

**On hold.** Prefer independent per-story plans (Pipeline, first item) until this is taken off hold.

The operator **starts the backlog**, not a single story. The analyst (best placed: already reads every story) writes a **dependency graph of existing items** — including which stories may run concurrently and which may not — plus implementation plans. Residual then runs stories in that order. This is not a new `implementation-order.md` theme ceremony and not invented sibling stories.

This swarm started replay first. Walk → pits → bats → Wumpus → arrows → replay is what the stories already say. Pits and Wumpus may proceed **in parallel after walk**. Bats need pits. Arrows need Wumpus. Replay needs a real terminal (sample: walk + pits + bats). Starting 6 first forced fake pit/bat/win stamps.

**When a dependent story gets an implementer:** not at Sa `handoff_sent`. After **SL `accept-merge` of Sa’s implementer** (`implementation_sha` on master, Sa Gherkin already green). Then residual may assign Sb’s implementer. Do **not** wait for Sa cleaner, CR, hardener, QA, or architect — those overlap with Sb implement. Specify (plan / Gherkin / QA-proc) for Sb may start as soon as the graph names the edge.

Roots start when the backlog-start and that story’s plan are approved. Concurrent siblings (pits ∥ Wumpus) both become implementable at the same pred-merged-implementation gate.

The analyst pass must exist **before** the workflow picks an implementer (first pass over the backlog, not only `plan.md` of a story already started). SL/cockpit enforce the graph; they do not author it.

**Where:** `analyst.prompt`; backlog Start vs per-story Start; residual `create_assignment` implementer; packet `implementation_sha`; this Wumpus backlog.

### Handoff drafts: diagnose from saved session files before changing the protocol

**On hold.** Agents often spent 15–70s after the work was done fumbling `swarm_handoff` (template placeholders like `<10-char-commit>`, SHA length, header-only drafts). Hardener left `result-handoff.draft` unfilled; events skipped `handoff_sent`.

Do **not** change the protocol yet (no auto-filled draft, no new helper) until we can read **saved session files** from retire. Then decide whether assignment should ship a filled draft, a writer helper, or clearer prompt examples.

**Where:** `swarm_handoff.sh`; constitution `handoffs.prompt`; leftover `result-handoff.draft`; depends on “Config to keep agent sessions after retire.”

