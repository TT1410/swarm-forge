# Issues

Replay-swarm and leftover-redo tracker. Bodies kept; grouped for reading. On-hold items are still open.

## Index

**On hold**

- Analyst orders the backlog; workflow starts stories; implementer waits on merged preds
- Handoff drafts: diagnose from saved session files before changing the protocol

**Done**

- Analyst plans each started story as independent (mock other backlog items)
- QA-proc writer commits procedure + implementer notes; implementer reads the notes
- Tighten QA so it actually goes end to end
- Cleaner property tests: use the environment’s framework; additive bb.edn only
- Later-role batches must project onto stories without a theme
- Sweep leftover theme references
- Flexible tools to add stories to the backlog
- Backlog icon needs a label
- Story card popup still says “Detached window (mock host)”
- View story package should include everything attached so far
- Thermometer treats “Working (Ns)” as activity
- WIF state icon: green dot becomes empty circle at handoff (should be arrows)
- Config to keep agent sessions after retire

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

---

## Done

### Analyst plans each started story as independent (mock other backlog items)

**Done.** Analyst prompt requires an independent plan (mocked ports, dummy state, how to run the stub, non-goals).

Start still means **this story alone** (the backlog-order DAG issue is on hold). The analyst must cut `plan.md` to what **this story owns** and **mock everything else**. Other backlog items are unbuilt. The backlog is a **boundary** (do not implement those items), not a foundation (do not pretend they exist).

Today I.N.V.E.S.T. “independent” and “use the whole backlog” are already in the prompt. This swarm treated the backlog as already built and faked predecessor behavior. Flip that.

**Required of the plan:**

1. Only this story is real. Do not write other items’ rules or outputs.
2. Behavior named here but owned by another item is a **port** (restore vs replace, etc.). Dummy state is allowed. Neighbor ops may be stubs until those stories exist.
3. Acceptance is **this story’s observable** — not predecessor screens, messages, or paths.
4. A **small runnable program** and a **QA-visible probe** (flag or command) so QA can inspect that state without exercising the rest of the product.
5. If the story text cannot be independent without lying, **ask the operator** (narrow it or don’t Start). Still no order file.

**Plan sections:** purpose · mocked ports · dummy state · how to run the stub · acceptance for this loop · non-goals (every other backlog item).

Mocked ports and run instructions **live in `plan.md`**. The operator’s implementation-plan approval document is that plan (they are approving a slice, not the rest of the backlog).

Gherkin and the QA procedure follow **that** plan.

**Where:** `analyst.prompt`; assignment; `plan.md` shape; Gherkin/QA-proc after plan approval.

### QA-proc writer commits procedure + implementer notes; implementer reads the notes

**Done.** Writer commits procedure + notes in one commit; packet records `qa_implementer_notes_path`; implementer waits on the QA-proc approval click and reads the notes.

The QA procedure can name how the game is driven (this swarm: `--qa-start-rooms` and friends). The implementer is told the procedure is not an input. It implements story + Gherkin only. This swarm had **no executable** (`-main` / `bb run` / stdin). QA could not type at `SAME SET-UP (Y-N)?` and recorded `QA_PROCEDURE_PASS` against `play-script`.

**Decisions:**

- **Two files**, same author, **one commit**, one `git_handoff`.
  - `qa/<story>.md` — procedure for QA and the operator (gate).
  - Sibling **implementer notes** (e.g. `qa/<story>-implementer-notes.md`) — process to run, argv/flags, deterministic seams, **QA-runnable executable**. Packet records `qa_implementer_notes_path`. **Repeat** the plan’s run/ports section (copy and refine); do not leave the implementer to read `plan.md` for that. QA-only seams may be added, not substituted.
- Do **not** split notes and procedure across two commits or two roles. They will drift; residual will start implementer on the wrong SHA.
- **Implementer reads the notes only**, not the procedure body. Gherkin stays the behavior spec. Assignment: read that path; do not treat the procedure as the spec. Notes **repeat** the plan’s run/ports section (QA-proc writer copies and may refine); they do not only list extra QA seams.
- **Specify** Gherkin and QA-proc stay parallel. **Implementer waits on the operator approval click** for QA-proc (and Gherkin, and the plan), not merely on the files being merged.
- The **QA-procedure approval document** the operator is shown must include **both** the procedure and the implementer notes. One gate, two files.

**Where:** `qa-procedure-writer.prompt` (write both files); implementer prompt (drop “QA is not an input”; require notes); residual implementer after `qa_procedure` approval; packet field; this swarm’s missing CLI.

### Tighten QA so it actually goes end to end

**Done.** QA prompt: pass means start the real program for this story; calling production functions or a test harness is a fail.

`qa.prompt` already says: execute the QA procedure through the **user interface only**, do not use a project API unless that API is the product UI, do not reinterpret the procedure. This swarm’s QA wrote `qa/replay_same_setup_qa.clj`, ran it, and recorded `QA_PROCEDURE_PASS`.

That script did not follow the procedure and did not go through the UI. It called `console/play-script` with `[:terminal "pit loss"]` and `[:same-setup "Y"]`. No process, no stdin, no `INSTRUCTIONS` / `SHOOT OR MOVE` / `SAME SET-UP` prompts, no `--qa-start-rooms`. Arrow checks `dec`’d `:arrows`. Same implementer harness, new PASS file.

Tighten QA instructions so a pass means: start the real program, drive it as the procedure says, observe stdout. Calling production functions or a test harness is a fail, not a runner. If the UI affordances the procedure names do not exist, that is a QA failure or a blocker — not a rewrite of the procedure into Clojure.

**QA is only concerned with this story.** End-to-end is the story’s runnable (as cut by the independent plan / implementer notes), not the rest of the product. If the plan mocks neighbors, QA does not wait for those stories.

**Where:** `qa.prompt` / `qa.contract.edn`; assignment verification steps; this story’s `qa/replay_same_setup_qa.clj` vs `qa/replay-the-same-set-up-or-start-a-new-hunt.md`.

### Cleaner property tests: use the environment’s framework; additive bb.edn only

**Done.** Cleaner uses `clojure.test.check` on bb; `bb.edn`/`deps.edn` edits are additive-only.

The cleaner prompt already says find or build a property-testing framework. This swarm’s cleaner added `doseq` over a handful of fixtures and called it coverage. No `test.check`, no generator, no property-test task.

**Decisions:**

- If a property-testing library exists **for that environment, use it**. On **bb**, `clojure.test.check` is **already on the classpath** — no `deps.edn` change. This swarm was not blocked; the cleaner skipped it.
- If none exists, write properties **by hand**: invariant, a way to draw inputs, many trials. Not extra example tests (`doseq` of four placements is not a property suite).
- Cleaner **may edit `bb.edn` / `deps.edn` only to add** a tool it needs (new `:deps` coordinate, new task such as `property-test`). It must **not** change existing targets (`test`, `acceptance`, `coverage`, …). Today’s “do not edit root tooling unless assigned” contradicts that; replace it with additive-only.

**Where:** `cleaner.prompt` (property-testing + tooling rules); assignment verification; this story’s `test/wumpus/core_test.clj` `doseq` “properties.”

### Later-role batches must project onto stories without a theme

**Done.** `architecture-fix` records a story manifest; SI inference is “packets waiting on SI,” never same theme; SI-returned is Done on the board.

Redo: story is **Done** after SI, or after architect with no recs. This swarm’s SI merged `architecture-fix` (turn-report rec). Residual is `wait`. The packet is still `qa_returned` / `architecture_review: changes-requested`. Board stays Finalizing.

Hardener / QA / architect get through because they write `batches/<id>/manifest.tsv` and residual records the merge onto those stories. `architecture-fix` is a batch (`story_id: batch`) with **no manifest**. Record infers members by **same `theme_id`**. Assignment has `theme_id: none`; themeless packets have no `theme_id`. Infer returns nobody. `architecture-fix` already exists merged, so residual will not spawn `-r2`.

That theme filter is leftover ceremony.

**Fix:**

1. When creating `architecture-fix`, write a manifest of packets that need SI (`architecture_review` is `changes-requested`, no `senior_implementer_sha`). Membership is stories, not a theme.
2. Record the merged SI SHA onto those members (`squad_packet.sh record <story> senior-implementer …`). Keep inference only as “every packet waiting on SI,” never “same theme.”
3. Map SI-returned (`architecture_revision_returned` / `senior_implementer_returned`) to **Done**. Today even a successful stamp would stay Finalizing.

Do not unstick a finished SI by spawning another assignment.

**Where:** `inferred-batch-member-rows` / `batch-member-rows` / `batch-assignment-candidate` in `squad_next.clj`; batch create for `architecture-fix`; `board-column` in `squadd/web.clj`; this story’s packet vs merged `architecture-fix`.

### Sweep leftover theme references

**Done.** SI no longer filters by `theme_id`; story cards do not show a theme; startup checks `squad_backlog.sh` not `squad_theme.sh`. `squad_assign.sh create` is `<story-id> <template> …` (no theme-id). Residual does not emit `none`. New assignment metadata omits `theme_id`.

Redo dropped theme/project ceremony. Leftovers still in the control plane (this swarm: SI member inference on `theme_id`) stall the pipeline.

Hunt remaining **theme** references in production code (and tests that still require them). Treat **each** as a bug: investigate whether it is dead ceremony, a renamed concept, or still doing work. Repair so **that reference no longer exists** — no `theme_id: none` stand-in, no “same theme” filters, no theme CLI/packet fields, no dashboard copy that talks about a theme the operator no longer has.

**Project** is allowed only as the **overall container of operational data** (the swarm/product tree, project root, `.squad` / git). It is not a backlog entity, not a theme synonym, not Add Project / Start Project. If a string means that old intake object, delete or rename it. If it means the tree the squad is running in, it may stay.

**Where:** `swarmforge/scripts`, `swarmforge/roles`, `squadd`, contracts/prompts; grep `theme` / `theme_id` / `.squad/themes` / `project` used as a product object.

### Flexible tools to add stories to the backlog

**Done.** `squad_backlog.sh import <dir-or-file>` and `add --title`; Troubleshooter prompt names the CLI; items land open.

Stories will come from many places. They might already be files on disk. They might be written by the Troubleshooter under user direction. The operator should be able to get those into the backlog quickly without a one-path ceremony.

The Troubleshooter prompt already says it may add a backlog story “via the backlog helpers or `.squad/backlog`.” There is no backlog helper CLI. The only first-class writer is the cockpit HTTP `POST /api/backlog`. Hand-writing `.squad/backlog/<id>.item` works only if you already know that file layout.

**Needed:** flexible add tools — files, TS-composed text, dashboard — all landing as open backlog items. Do not Start unless asked.

**TS batch add:** a **CLI the Troubleshooter can run** to load a **batch of stories** efficiently (e.g. a directory of markdown files, as in `~/junk/htw-stories`). Not “reverse-engineer POST `/api/backlog`.” Operator Add Story stays. Items land **open**, not started.

**Where:** Troubleshooter prompt vs a real batch-add CLI (`squad_backlog` or equivalent); `.squad/backlog` item format; cockpit Add Story.

### Backlog icon needs a label

**Done.** `#backlog-deck` shows a Backlog label.

The backlog deck control is an unlabeled icon (count on stacked sheets). The operator should see that it is the backlog without hovering.

**Where:** cockpit toolbar (`#backlog-deck`).

### Story card popup still says “Detached window (mock host)”

**Done.** Story float footer no longer says mock host.

Clicking a story card opens a float whose footer is leftover mockup copy: “Detached window (mock host). Close does not stop agents.” That is not operator language for the live cockpit.

**Where:** `openStoryDetail` in `squadd/dashboard.html`.

### View story package should include everything attached so far

**Done.** Story package includes story, packet, plan, notes, Gherkin, QA procedure, reviews. Story gates open that package. HTML sections have ids (`#gherkin`, `#qa-procedure`, `#implementation-plan`, …) so Attention hashes jump.

Attention “View story package” (implementation-plan gate) opens `/artifact/story/<id>`. That page is the story file plus the packet. The analyst’s `plan.md` is on disk and named on the packet (`implementation_plan_path`) but is not in the package body. The operator cannot read the plan from that link.

The story package should show **everything attached so far**: story, packet, plan, implementer notes, Gherkin, QA procedure, reviews — not only story+packet. Keep adding sections as later artifacts land. One Attention link, the whole package. Gate-specific labels may jump to a section; they still open this package.

**Where:** `story-content` / `approval-document-ref` in `squadd/web.clj`; Attention document for every story gate.

### Thermometer treats “Working (Ns)” as activity

**Done.** `pane-sample-for-hash` strips the trailing status footer by backend (Grok `Working (Ns)` chrome vs Codex prompt).

The SL / WIF activity bars hash the tmux pane and heat up when the hash changes. `pane-sample-for-hash` only drops the last non-empty line. Codex/Grok waiting counters (`Working (12s)`, `Working (13s)`, …) sit above a status/prompt footer, so they stay in the sample and tick every poll. Idle wait looks like work.

A waiting counter is not activity. The thermometer should ignore those lines.

**Decision:** strip only the **trailing status footer**, not every line that says “working.” Footer shape **depends on the agent backend** (Grok `Working (Ns)` vs Codex elapsed/status widgets, and later types). Needs **polymorphism** keyed on backend (roles.tsv / agent metadata), not one regex for all panes.

**Where:** `pane-sample-for-hash` / `sl-activity` / `agent-pane-heat` in `squadd/web.clj`; backend from agent metadata / roles.tsv.

### WIF state icon: green dot becomes empty circle at handoff (should be arrows)

**Done.** WIF rows expose `agent_state`; the glyph uses agent lifecycle when `agent_id` is present.

Work Queue icons are supposed to be **arrows** for `handoff_ready` / `handoff_sent`. Running is a green pulsing **dot**. During and after handoff the operator sees that dot turn into an **empty grey circle**.

`stateIcon` already maps those handoff states to arrows. WIF rows use **assignment** `state`, which is `in_progress` then `result_received` / `merged` — never `handoff_ready` / `handoff_sent` (those are agent lifecycle). Unknown assignment states fall through to `dot-grey`.

The icon should follow the agent’s handoff (arrow), not an unmapped assignment status.

**Decision:** if the WIF row has an `agent_id`, the glyph uses **agent** lifecycle (`handoff_ready` / `handoff_sent` → arrows; `running` → green dot). Assignment state is used only when there is no agent.

**Where:** `stateIcon` / `renderWork` in `squadd/dashboard.html`; `work-in-flight-rows` must expose agent state (not only assignment `state`).

### Config to keep agent sessions after retire

**Done.** `save_agent_sessions` in `squad.conf` archives pane/liveness under `.squad/sessions/<agent-id>/` on retire, then still kills tmux. Default off.

On retire the control plane stops the tmux session and removes the worktree. Liveness tails and pane history go with them. After the fact we cannot see how an agent formulated a handoff, which tools it tried, or where it wasted a minute.

Need a **configuration switch** (squad.conf / env) that **saves agent sessions as files** — pane capture and/or session log under `.squad/agents/<id>/` (or an archive beside it) **on retire**. Tmux is still killed. Default can still be delete-as-you-go (no files). Not a kept-alive tmux session.

**Where:** `squad.conf`; `squad_retire`; write capture/log files then stop tmux; `.squad/agents/<id>/`.
