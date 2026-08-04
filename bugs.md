# Bugs

## SL-created approval duplicates FSM-created approval

Observed in the HTW trial: after creating the `hunt-the-wumpus` theme, the SL
manually created a theme approval request:

```sh
squad_approval.sh request approve-hunt-the-wumpus-theme ...
```

Then `squad_next.sh` recommended its canonical approval request:

```sh
squad_approval.sh request theme__hunt-the-wumpus ...
```

The result was two pending approvals for the same workflow gate:

- `approve-hunt-the-wumpus-theme`
- `theme__hunt-the-wumpus`

Both targeted `(target_kind=theme, target_id=hunt-the-wumpus, gate=theme)`.
Approving one would leave the other as stale pending state.

Expected behavior: the FSM is the single source of workflow recommendations.
The SL should create artifacts needed to satisfy the current recommended action,
execute the command recommended by `squad_next.sh`, and then call
`squad_next.sh` again. The SL should not independently create workflow records
such as approvals, assignments, reviews, or retirements under alternate IDs.

Likely cause: the SL interpreted the theme setup as requiring a hand-authored
approval request before returning to the FSM. Separately, `squad_next.sh`
deduplicates approval records by approval id rather than by
`(target_kind, target_id, gate)`, so it did not recognize the manually created
approval as equivalent to its canonical request.

Reliable fix:

1. Make `squad_next.sh` the only workflow planner.

   The SL prompt must say that approvals, assignments, reviews, retirements, and
   recovery actions are only created when `squad_next.sh` returns that exact
   `NEXT_ACTION` and `COMMAND`. For theme setup, the SL may create the theme
   artifact, then must immediately return to `squad_next.sh` for the approval
   request.

2. Make `squad_approval.sh request` idempotent by semantic key.

   The helper should detect existing pending, approved, or rejected approvals
   for the same `(target_kind, target_id, gate)`, even when the approval id
   differs. If an equivalent pending approval exists, print the existing approval
   and exit successfully. If the equivalent approval is already approved or
   rejected, report that resolved state and exit without creating another
   record.

This gives the SL a strict planner/executor contract while also making retries,
races, web requests, and accidental alternate ids harmless.

## FSM ignores SL-created replacement assignments

Observed in the HTW trial: after the analyst produced stories, the SL reviewed
the merged artifacts and found that they conflicted with the user's clarified
command grammar. The SL correctly recorded a `changes-requested` review and
created a replacement analyst assignment:

```text
assignment_id: hunt-the-wumpus-analysis-command-grammar
template: analyst
state: assignment_created
replaces: hunt-the-wumpus-analysis
```

After the original analyst was retired, `squad_next.sh` returned:

```text
NEXT_ACTION: wait
REASON: no handoffs, pending approvals, active transient agents, or stale locks
```

Expected behavior: the SL must have the flexibility to review any merged
artifact and send it back for revision. Once the SL records that review and
creates a replacement assignment through the helper, the FSM should treat that
replacement assignment like any other ready assignment. If capacity is
available, `squad_next.sh` should recommend spawning the appropriate replacement
agent.

Likely cause: `squad_next.sh` builds spawn candidates mostly from its own
stage-derived transition table. It does not have a general pass that scans
durable `assignment_created` records and recommends spawning them when their
requirements are satisfied and no active agent already owns the assignment.

Reliable fix:

1. Keep SL review authority explicit.

   The SL may inspect merged artifacts, record review decisions, and create
   replacement assignments when an artifact needs correction. This is not
   workflow planning; it is artifact quality control.

2. Add a generic ready-assignment spawn pass to `squad_next.sh`.

   Before returning `wait`, the FSM should scan `.squad/assignments/*` for
   assignments in `assignment_created` state, including replacements. For each
   assignment, it should verify:

   - the assignment's requirements are satisfied,
   - no active or created agent already owns the assignment,
   - template/group capacity allows a spawn,
   - the assignment has a valid assignment file.

   If those checks pass, `squad_next.sh` should return `request_spawn` with:

   ```sh
   squad_spawn_request.sh <template> <assignment-id> <assignment-file>
   ```

3. Preserve deterministic ordering.

   Generic ready assignments should be sorted by priority, theme, story, stage,
   creation time, and assignment id so replacement work does not starve normal
   pipeline work and normal pipeline work does not hide ready replacements.

4. Make replacement completion update the replaced assignment and current
   artifact state.

   When the replacement result is merged, the replaced assignment should remain
   traceable as superseded, and the FSM should reason from the replacement
   artifact as the current artifact for any downstream review or approval gate.

## Spawning an agent does not mark assignment in progress

Observed in the HTW trial: after manually spawning `analyst-002` for replacement
assignment `hunt-the-wumpus-analysis-command-grammar`, the agent registry showed
the agent as running:

```text
AGENT: analyst-002
TASK_ID: hunt-the-wumpus-analysis-command-grammar
STATE: running
PANE_LIVE: true
```

But the assignment record still showed:

```text
assignment_id: hunt-the-wumpus-analysis-command-grammar
state: assignment_created
```

Expected behavior: once a spawn request is fulfilled and an agent is assigned to
an assignment, the assignment state should move to an active state such as
`assigned` or `in_progress`, with the agent id recorded. The assignment record
and the agent registry should agree about ownership and liveness.

Likely cause: spawn fulfillment creates the agent registry entry and tmux
session, but does not update `.squad/assignments/<assignment-id>/status`.

Reliable fix:

- When `squad_spawn_request.sh` or the spawn daemon successfully creates an
  agent for an assignment, update the assignment status with:

  ```text
  state: in_progress
  agent_id: <agent-id>
  session: <session>
  updated_at: <timestamp>
  ```

- When the agent sends a result handoff, transition the assignment from
  `in_progress` to `result_received`.
- Make `squad_next.sh` treat `assignment_created` as spawnable, `in_progress` as
  owned by an active or recently active agent, and stale `in_progress` as a
  recovery candidate only after liveness checks fail.

## Dashboard drops agent around handoff transition

Observed in the HTW trial: the web dashboard stopped listing an active transient
agent just before handoff, then listed it again during handoff processing. The
agent had not actually disappeared; `squad_status.sh` still showed the agent and
the tmux pane remained live.

Expected behavior: the dashboard should show agents consistently through the
full lifecycle from spawn through retirement, including `complete`,
`handoff_ready`, `handoff_sent`, and handoff processing states. The agent should
not disappear from the web view merely because it is between active work and
handoff delivery.

Likely cause: this is a state interpretation/filtering issue in the web tool.
The dashboard appears to filter agents by a subset of states considered
"active", while the lower-level status tools still know about the agent. During
fast transitions, the agent can move through a state that the dashboard does not
render, then reappear when it reaches a rendered handoff state.

Reliable fix:

- Define one shared lifecycle classification for dashboard visibility instead
  of having the web UI infer visibility from its own state subset.
- Show every non-retired agent in the active agent list.
- Show retired agents only in history or omit them from the normal active view,
  but do not hide live agents in transitional states.
- Ensure `complete`, `handoff_ready`, and `handoff_sent` are treated as visible
  live states until `squad_retire.sh` records `retired`.

## Accepted analyst stories are not registered for approval

Observed in the HTW trial: the replacement analyst assignment
`hunt-the-wumpus-analysis-command-grammar` was merged and reviewed as accepted.
The revised story files existed under `stories/*.md`, and the assignment status
was:

```text
state: review_accepted
```

After the agent was retired, `squad_next.sh` returned:

```text
NEXT_ACTION: wait
REASON: no handoffs, pending approvals, active transient agents, or stale locks
```

No story approvals were registered because no durable story packets existed
under `.squad/stories`.

Expected behavior: accepting an analyst/story-writing assignment should lead to
durable story registration. Each accepted story artifact should be recorded in
the workflow state, then `squad_next.sh` should create or request the configured
story approval gates.

Likely cause: the workflow treats accepted theme-level analyst work as complete
but does not transition the resulting `stories/*.md` artifacts into `.squad`
story packet state. `squad_next.sh` only reasons over packet records, not raw
files in `stories/`.

Reliable fix:

1. Add an explicit story-registration transition after accepted analyst work.

   When a theme-scoped analyst assignment reaches `review_accepted`, the FSM
   should recommend a deterministic registration action for the produced story
   artifacts.

2. Use a helper to register stories into durable workflow state.

   The command should either call existing helpers such as:

   ```sh
   squad_theme.sh story <theme-id> <story-id> <story-file>
   ```

   or use a dedicated story packet helper that records the story id, theme id,
   artifact path, source assignment, revision, and approval fields.

3. Make registration idempotent.

   Re-running the registration action should update or report existing story
   records for the same story id and artifact path rather than creating
   duplicates.

4. After registration, continue the normal approval pipeline.

   Once story packets exist, `squad_next.sh` should recommend story approval
   creation for every story whose `story` approval gate is required and not yet
   satisfied.

## Dashboard assignment ordering and approval history are noisy

Observed in the HTW trial: the web dashboard lists assignments, but the ordering
does not prioritize the newest work. During active swarm monitoring, the newest
assignments are the most relevant because they show the current recovery,
revision, and handoff context.

Expected behavior: assignments in the web dashboard should be listed in reverse
chronological order, newest first.

Observed separately: the dashboard includes an approval history section. During
live operation this adds noise without helping the SL or user make the next
decision.

Expected behavior: remove the approval history section from the dashboard. The
dashboard should focus on pending approvals, current stories, active agents, and
active/relevant assignments.

## Merge conflicts should route to a merger agent

Observed in the HTW trial discussion: `squad_assign.sh merge-ready` correctly
detects merge conflicts by attempting a no-commit merge and marking the
assignment `merge_blocked` when the dry-run merge fails. The policy for what
happens next needs to be explicit.

Expected behavior: merge conflicts should be handled by a specialized transient
agent named `merger`, not by the SL and not automatically by the original
transient agent. The SL decides that a merge conflict needs merger work, creates
or requests the merger assignment, and the merger hands back an unconflicted
merge result.

Policy:

- The SL does not resolve merge conflicts directly.
- The SL routes merge conflicts to a `merger` role through the workflow.
- The merger resolves mechanical conflicts between current `HEAD` and the
  handed-off result commit.
- The merger may make minimal integration edits needed to preserve both sides.
- The merger records what it changed during conflict resolution.
- The merger must not silently rewrite the artifact's intended behavior while
  resolving conflicts.
- If a conflict exposes a substantive requirement or design disagreement, the
  merger should hand back a blocker instead of guessing.
- Unlike most transient artifact roles, the merger is allowed to run relevant
  test suites to ensure the resolved merge did not break existing behavior.
- Do not introduce a merge lock for now. Other handoffs may continue to be
  accepted while a merger is active.
- Because there is no merge lock, a merger handoff may itself conflict if
  `master` has moved again before the SL accepts it. In that case, the SL should
  route the new conflict through another merger pass rather than resolving it
  directly.

Reliable fix:

- When `squad_assign.sh merge-ready` records `merge_blocked` because of a merge
  conflict, `squad_next.sh` should recommend creating a `merger` assignment or
  requesting a `merger` spawn.
- Add a `merger` role template and contract.
- Add helper support for merger assignments that includes:
  - assignment id and original assignment id,
  - conflicted result commit,
  - target branch or current `HEAD`,
  - merge error details,
  - allowed test command scope,
  - required conflict-resolution notes.
- Define the merger handoff format so it returns either:
  - an unconflicted merge commit ready for SL acceptance, or
  - a blocker explaining the substantive conflict.
- Update the FSM so a successful merger handoff can move the original assignment
  back to merge-ready or merged workflow state without losing traceability.
- Add FSM coverage for repeated merger passes caused by concurrent accepted
  handoffs, since no merge lock is used.

## Dashboard should allow sending free-form messages to SL

Requested during the HTW trial: the web dashboard should include a text area and
a submit button for sending free-form user text to the squad leader.

Expected behavior:

- The dashboard shows a multiline text area.
- The dashboard shows a submit button near that text area.
- When the user submits text, the web daemon sends that text to the SL.
- The SL receives it in the same practical channel as other wake-up or handoff
  notifications, so the message can interrupt a wait state and prompt the SL to
  run `squad_next.sh` or otherwise respond according to its role.
- When sending the text into the SL tmux pane, send two returns with a 100ms
  delay between them. The second return is needed to reliably submit the
  message to the running Codex session.

Purpose: this gives the user a low-friction way to clarify requirements,
approve direction informally, or redirect the SL without manually attaching to
the tmux session.

## Pending approval starves ready replacement assignments

Observed in the HTW trial: while Story 04 was waiting for QA procedure approval,
there were no active transient agents. Several independent replacement
assignments were already in `assignment_created` state and could have been
spawned:

```text
hunt-the-wumpus-01-console-setup-qa-procedure-revision
hunt-the-wumpus-02-turn-movement-hazards-gherkin-revision
hunt-the-wumpus-03-crooked-arrows-gherkin-revision
hunt-the-wumpus-05-fidelity-contract-gherkin-revision
hunt-the-wumpus-05-fidelity-contract-qa-procedure-revision
```

But `squad_next.sh` returned only:

```text
NEXT_ACTION: request_user_approval
APPROVAL: qa-procedure__hunt-the-wumpus-04-terminal-replay
```

Expected behavior: a pending approval for one story should not globally block
ready work for other stories. If transient slots are available and independent
assignments are ready, the FSM should recommend spawning those assignments even
while another story is waiting at an approval gate.

Likely cause: approval waits have higher global priority than ready assignment
spawns, and `squad_next.sh` returns a single pending approval action before
considering independent ready assignments.

Reliable fix:

- Treat approvals as gates for their own target artifact, not as global swarm
  blockers.
- When there are open transient slots, prefer ready spawn/create-assignment work
  over merely re-reporting an already pending approval.
- Continue showing pending approvals in `squad_next.sh` output or dashboard
  state, but do not let them starve independent work.
- Include replacement assignments in the generic ready-assignment spawn pass so
  revision work keeps moving while unrelated approvals are pending.

## Any active agent causes global scheduler wait

Observed in the HTW trial: after Story 04 implementation started, the swarm had
one active implementer and a configured capacity of five transient agents:

```text
max_transient_agents 5
ACTIVE: implementer-001 hunt-the-wumpus-04-terminal-replay-implementation running
```

`squad_next.sh` returned:

```text
NEXT_ACTION: wait
REASON: active agents are still working or awaiting handoff delivery
```

Other independent work was available, including previously created replacement
assignments, but no additional agents were spawned.

Expected behavior: active agents should consume capacity, not block the whole
scheduler. If total transient capacity is not full and independent work is
ready, `squad_next.sh` should recommend creating or spawning additional
assignments while existing agents continue working.

Likely cause: the advisor has a global wait path for "active agents are still
working" that fires before checking whether additional work can fit in the
remaining transient slots.

Reliable fix:

- Replace the global active-agent wait with capacity-aware scheduling.
- Count active transient agents against `max_transient_agents`.
- Count template and group limits separately.
- If open capacity remains, continue evaluating ready workflow actions.
- Return `wait` for active agents only when no independent ready action exists
  or all relevant capacity limits are full.
- Include the active-agent summary as context in `wait` output, not as a reason
  to stop scheduling by itself.

## Dashboard story state label is misleading

Observed in the HTW trial: the web dashboard showed Story 1 as
`specification_in_progress` even though the story had progressed into
implementation-related work. The visible story state did not match the user's
understanding of what was actively happening.

Expected behavior: the dashboard should show a derived user-facing current
phase for each story, not the raw packet `state` field.

Likely cause: the dashboard Stories table displays `s.state` directly from the
story packet. That field is a coarse packet/FSM state and can remain
`specification_in_progress` while downstream stage fields or assignments show
that the story is in review, approval, implementation, revision, or another
more specific phase.

Reliable fix:

- Add a derived dashboard story phase, computed from the most advanced relevant
  fields and live assignment/agent state.
- Prefer active work over coarse packet state. For example, show
  `implementation running` when an implementer assignment/agent is active.
- Show pending gates explicitly, such as `awaiting QA approval`.
- Show revision states explicitly, such as `Gherkin revision needed` or
  `QA procedure revision running`.
- Keep the raw packet `state` available for debugging if useful, but do not use
  it as the primary user-facing story state.

## Old review assignment suppresses review of revised artifact

Observed in the HTW trial: Story 1 had a revised QA procedure attached:

```text
qa_procedure_assignment: hunt-the-wumpus-01-console-setup-qa-procedure-revision
qa_procedure_assignment_state: complete
qa_procedure_review_state: pending
```

The correct next step was a fresh `qa-procedure-reviewer` assignment for the
revised QA procedure. However, the old QA procedure review assignment still
existed:

```text
hunt-the-wumpus-01-console-setup-qa-procedure-review
template: qa-procedure-reviewer
state: review_accepted
```

No new review assignment was created for the revised artifact.

Expected behavior: each revised artifact needs its own fresh review. An old
review assignment for a superseded artifact must not suppress review of a newer
replacement artifact.

Likely cause: the FSM checks for any existing review assignment by story and
template, without considering whether that assignment reviewed the current
artifact revision and without treating `review_accepted` as terminal for
assignment-creation suppression.

Reliable fix:

- Treat `review_accepted` and `review_changes_requested` as terminal assignment
  states for the purpose of deciding whether another review assignment is
  needed.
- Scope review assignment suppression to the current artifact revision, not only
  `(story_id, template)`.
- Include artifact path, artifact assignment id, and artifact sha in review
  assignment metadata.
- When a replacement writer artifact is attached, clear or supersede the prior
  review state so the FSM can request review of the current artifact.

## FSM lacks code-review revision loop

Observed in workflow review: the intended post-implementation loop is:

```text
implementer -> cleaner -> code-reviewer -> implementer -> cleaner -> code-reviewer -> ...
```

The loop should continue until the code reviewer accepts the cleaned
implementation.

Current FSM behavior appears to be linear:

```text
implementation_sha present
-> cleaner assignment if cleaner_sha missing
-> code-reviewer assignment if cleaner_sha present and code_review missing
-> code-review approval if code_review accepted
-> hardener
```

Expected behavior: when a code reviewer requests changes, the FSM should route
the work back to an implementer revision assignment. After the revised
implementation is merged and attached, the FSM should run cleaner again, then
code reviewer again.

Likely cause: the transition table does not include an explicit
`code_review == changes-requested` branch, and downstream state is keyed only on
the presence of fields such as `implementation_sha`, `cleaner_sha`, and
`code_review`.

Reliable fix:

- Add a code-review rejection transition:

  ```text
  code_review: changes-requested -> replacement implementer assignment
  ```

- Scope cleaner and code-review state to the current implementation revision,
  not only the story.
- When a replacement implementation is attached, clear or supersede
  `cleaner_sha`, `code_review`, `code_review_sha`, and downstream post-cleaning
  state for the prior implementation.
- Require the replacement implementer assignment to include the code review
  report, current implementation commit, approved story, approved Gherkin, and
  approved QA procedure.
- After replacement implementation is merged, rerun the normal cleaner and
  code-reviewer stages before allowing hardener work.

## Hardener proceeds without required hardening tools

Observed in the HTW trial: `hardener-001` attempted to require the expected
hardening tools:

```text
clj-mutate
gherkin-parser
dry4clj
gherkin-mutator
```

Each `squad_tool.sh require` call returned `SQUAD_TOOL_MISSING` with reason
`missing manifest`. The hardener then recorded:

```text
required hardening tools missing from cache; proceeding with repository workflow only
```

Expected behavior: the hardener must not silently downgrade to repository-only
verification when required hardening tools are unavailable. Mutation, soft
Gherkin mutation, CRAP, and DRY checks are the hardener's core role.

Policy:

- If the assignment or role policy permits fetching/installing tools, the
  hardener should obtain the required tools through `squad_tool.sh ensure`.
- If fetching/installing is not permitted, missing required hardening tools are
  a blocker.
- The hardener should record `blocked` and hand the missing-tool blocker to the
  SL rather than proceeding without the tools.

Reliable fix:

- Tighten the hardener prompt so `SQUAD_TOOL_MISSING` for required hardening
  tools requires either approved tool acquisition or a blocker handoff.
- Do not allow "repository workflow only" as a substitute for hardening.
- Make hardener assignments include explicit tool-acquisition permission or
  preflight the shared tool cache before spawning the hardener.

## Packet review fields do not distinguish stale findings from current state

Observed in the HTW trial: Story 1 appeared to be invalidly included in
hardener batch `hunt-the-wumpus-hardener` because its packet still showed an old
negative code review:

```text
hardener_batch: hunt-the-wumpus-hardener
hardener_batch_stage: code_reviewed
hardener_batch_assignment: hunt-the-wumpus-01-console-setup-code-review
hardener_batch_sha: f53e2c6619
code_review: changes-requested
code_review_assignment: hunt-the-wumpus-01-console-setup-code-review
```

But Story 1 also had later downstream state from a replacement/fix path:
implementation fix, hardening approval, QA approval, and architecture review.
The packet retained the old `code_review: changes-requested` fields beside newer
state, making the current effective workflow state ambiguous.

Expected behavior: packet state must distinguish historical review findings from
the current effective review for the current artifact/implementation iteration.
Old rejected reviews should remain available as history, but they must not look
like the current effective verdict after a replacement/fix path succeeds.

This is different from the genuinely invalid Stories 2/3 case, where rejected
code-review results were newly added to the hardener batch before a replacement
implementation had succeeded.

Policy:

- Eligibility checks must use the current active iteration, not any historical
  rejected review field.
- Replacement/fix work should supersede or version old review fields when it
  becomes the current implementation path.
- Historical findings should be kept in iteration history, not exposed as the
  canonical current `code_review` if they no longer apply to the current
  implementation sha.
- Only stories whose current effective code review is accepted should enter a
  hardener batch.
- A story whose current effective review is `changes-requested` must route back
  through the revision loop:

```text
implementer -> cleaner -> code-reviewer
```

Likely cause: story packets store one set of canonical review fields while also
using `*_iterations` as history. Replacement/fix paths can add newer successful
artifact state without clearing, superseding, or versioning older negative
review fields. Separately, hardener eligibility can be fooled if it looks only at
`code_review_sha` presence and/or optional `code-review` approval satisfaction
rather than the current effective verdict for the current implementation sha.

Reliable fix:

- Define a deterministic "current effective iteration" view for each stage.
- Tie review verdicts to the artifact sha they reviewed.
- When replacement/fix implementation is accepted, either:
  - clear superseded negative review fields from the canonical view, or
  - keep them only in iteration history while promoting the replacement verdict
    into the canonical current fields.
- Hardener eligibility must require the current effective review to be accepted:

  ```text
  code_review: accepted
  code_review_sha: present
  code_review_approval satisfied
  ```

- Once a batch has been started, that batch is closed. Do not add new stories to
  an already-started hardener, QA, or architecture batch.
- If a story was already incorrectly batched, provide a deterministic way to
  remove or supersede that hardener batch membership.
- Add FSM tests covering:
  - stale historical `code_review: changes-requested` superseded by a later
    accepted/fixed implementation path,
  - current effective `code_review: changes-requested` with a present
    `code_review_sha` and optional code-review approval disabled; expected next
    action is implementer revision, not hardener batch.

## SL can sit idle at prompt without re-entering workflow

Observed in HTW trials: from time to time the SL waits at a prompt even when no
agent work or command is visibly in progress. If no handoff message arrives or a
previous wake message is missed, the swarm can stall even though `squad_next.sh`
would have useful work to recommend.

Expected behavior: a lightweight supervisor daemon should monitor SL liveness
and nudge the SL back into the workflow when it is visibly idle.

Policy:

- Keep this separate from `squad_next.sh`. The FSM remains deterministic
  workflow guidance; the watchdog handles liveness only.
- The daemon watches the SL tmux pane, not raw assignment age.
- Poll the SL pane periodically and track whether the last 20-40 visible lines
  have changed.
- If the pane has not changed for 60 seconds and appears to be at an idle prompt,
  send:

  ```text
  Run squad_next.sh.
  ```

- Send the required second return after a short delay so the prompt submits.
- If the pane is changing, do nothing.
- If the SL is running a command, do nothing.
- If the SL is waiting on required user approval, avoid spam; either do nothing
  or use a much slower reminder cadence.

Reliable fix:

- Add a daemon such as `squad_leader_watchdog.sh`.
- Base idleness on pane-content inactivity, not wall-clock time since last
  workflow action.
- Reuse the same tmux send behavior used for dashboard-to-SL messages: send the
  text, press return, wait about 100ms, press return again.

## Architecture batch waits instead of starting with ready members

Observed in the HTW trial: Stories 1 and 4 reached `qa_approved` and were added
to architecture batch `hunt-the-wumpus-architecture`:

```text
architecture_batch: hunt-the-wumpus-architecture
architecture_result_state: batched
```

The batch existed under `.squad/batches/hunt-the-wumpus-architecture/` with
members in `manifest.tsv`, but no architecture assignment or architect agent was
created.

Expected behavior: batches should not wait for all stories. Once a batch has one
or more eligible members and the relevant role capacity is available, the FSM
should recommend starting that batch. Once started, the batch is closed and later
eligible stories go to a later batch.

Reliable fix:

- Add FSM logic that emits an architect assignment/spawn recommendation for an
  open architecture batch with eligible members.
- Do not require every story in the theme to be architecture-ready before
  starting an architecture batch.
- When the architect starts, close the batch so later stories cannot be added to
  the active batch.
- Add simulator/FSM coverage for partial batches: Stories 1 and 4 can enter
  architecture while Stories 2, 3, and 5 are still in implementation/cleaning.

## Architecture result does not propagate to member story packets

Observed in the HTW trial: architecture assignment
`hunt-the-wumpus-architecture` completed and merged successfully:

```text
assignment_id: hunt-the-wumpus-architecture
state: merged
commit: cb5fb2c2b1
merge_commit: 42398a6f7b
```

The architect report verdict was:

```text
Changes requested.
```

Initially, member story packets for Stories 1 and 4 still showed:

```text
architecture_batch: hunt-the-wumpus-architecture
architecture_result_state: batched
```

After later SL processing, the packets advanced only to:

```text
architecture_assignment: hunt-the-wumpus-architecture
architecture_sha: 42398a6f7b
architecture_result_state: pending_review
```

They still did not record the verdict:

```text
architecture_review: changes-requested
```

This blocks the senior-implementor path. `squad_next.bb` only recommends
`senior-implementor` when it sees `architecture_review: changes-requested` in a
story packet, so a merged architecture critique with an unrecorded verdict leaves
the senior-implementor trigger invisible to the FSM.

Expected behavior: when a batch role result is merged, the workflow should parse
or record the batch verdict and update every member story packet consistently.
For a changes-requested architecture result, each member story should reflect the
architecture finding and route to the appropriate revision path.

Reliable fix:

- Add deterministic result handling for architecture batch handoffs.
- Record the architecture verdict, report path, assignment id, result commit,
  and merge commit on every member story packet.
- Transition member stories out of `architecture_result_state: batched` or
  `pending_review` after the architecture handoff verdict is processed.
- Ensure `changes-requested` architecture verdicts set
  `architecture_review: changes-requested` so the senior-implementor FSM rule can
  fire.
- Add FSM tests for architecture `accepted` and `changes-requested` batch
  results.

## Senior-implementor output does not route back to architect

Observed in the FSM: an architecture critique with `changes-requested` can
trigger a `senior-implementor` assignment, and `squad_packet.sh record` can
record `senior_implementor_sha`. However, `squad_next.bb` has no follow-up
transition that routes the senior-implementor result back to the architect for
review.

Expected behavior: the architect has final say on architecture acceptance.
Senior-implementor output must go back to the architect, not directly to final
approval or a terminal state.

Required loop:

```text
architect changes-requested
-> senior-implementor
-> architect review
-> accepted or changes-requested
-> senior-implementor ...
```

Reliable fix:

- Add an FSM transition for `senior_implementor_sha` present and no current
  accepted architecture review: create/spawn an architect review assignment.
- The architect review assignment must include the original architecture
  critique, the senior-implementor commit, and the affected story/batch members.
- If architect accepts, record `architecture_review: accepted` and proceed to
  architecture approval/final flow.
- If architect requests changes, record `architecture_review: changes-requested`
  and route another senior-implementor pass.
- Add simulator coverage for at least one architecture reject/fix/re-review
  cycle.

## Helpers enforce workflow from stale side metadata

Observed in the HTW trial: the SL tried to move Story 2 into a new hardener
batch `hunt-the-wumpus-hardener-r2` after Story 2's current effective code
review was accepted. `squad_batch_story.sh add` refused:

```text
Story hunt-the-wumpus-02-turn-movement-hazards is already in active hardener
batch hunt-the-wumpus-hardener
```

The old batch manifest row had been removed and the packet had been updated, but
the helper still read `.squad/stories/.../active-batches/hardener`, which pointed
at the old batch. That stale side file made the helper enforce an obsolete
workflow decision.

Policy: the workflow FSM/state model is the sole authority for routing,
eligibility, and next actions. Helpers may validate formats and mechanically
apply requested transitions, but they must not independently decide workflow
truth from denormalized side metadata.

Expected helper behavior:

- `squad_next` or the workflow tool decides whether a story may enter a batch.
- `squad_batch_story.sh add` executes that transition atomically.
- `active-batches/<kind>` and similar files are indexes/caches, not routing
  authority.
- Terminal old batch states must not block new workflow actions.
- If helper-visible metadata disagrees with workflow state, the helper should
  report a consistency error with repair instructions, not enforce stale routing.

Reliable fix:

- Make batch membership updates transactional across packet fields, batch
  manifest, and any derived active-batch index.
- Stop using `active-batches/<kind>` as an authority for rejecting workflow
  transitions.
- Treat batches in terminal states such as `result_received`, `rejected`, or
  `merged` as inactive even if stale indexes point to them.
- Add tests where a story leaves an old terminal batch and enters a new batch
  without manual side-file repair.

## Kill swarm leaves dead agents and worktrees behind

Observed in the HTW trial after killing the swarm: tmux sessions disappeared,
but agent metadata and git worktrees remained:

```text
hardener-002: state: running
code-reviewer-008: state: handoff_sent
.worktrees/code-reviewer-008
.worktrees/hardener-002
```

Expected behavior: killing a swarm should fully retire remaining transient
agents and remove their worktrees/branches when possible. After tmux sessions are
gone, no agent should remain in `running` or `handoff_sent` state unless the
cleanup explicitly records a recoverable handoff.

Reliable fix:

- After terminating tmux sessions, enumerate all non-retired transient agents.
- For each agent, mark it retired or killed with explicit detail that the swarm
  was terminated.
- Remove its git worktree and branch when safe.
- If an agent had `handoff_sent`, either preserve the handoff in the inbox/outbox
  with recovery metadata or mark the agent retired after confirming the handoff
  file location.
- Add a final cleanup verification step: no tmux sessions, no non-retired
  transient statuses, and no leftover transient git worktrees.

## Review report merge checks blocked by untracked SL-side files

Observed repeatedly in HTW assignment merge errors: review handoffs failed
`merge-ready` because the SL worktree already had untracked files at the same
paths the review agent commit wanted to add:

```text
error: The following untracked working tree files would be overwritten by merge:
  .squad/reviews/hunt-the-wumpus-01-console-setup-gherkin-review.md
Please move or remove them before you merge.
Aborting
```

This happened for multiple Gherkin and QA procedure review reports. These are
not semantic merge conflicts and should not need a merger agent; they are
orchestration artifact path collisions.

Expected behavior: the SL should not create untracked review files at the same
durable paths owned by reviewer agents, and merge-readiness checks should run
from a clean state or isolated temporary worktree.

Reliable fix:

- Keep SL-authored review notes under a separate path from transient review
  agent reports.
- Before `merge-ready`, detect untracked files that would be overwritten and
  either move them to orchestration scratch space or fail with a precise repair
  action.
- Prefer running dry-run merges in a temporary clean worktree/index so local
  orchestration scratch files cannot block incoming review commits.
- Add tests where an untracked `.squad/reviews/...md` file exists before a review
  handoff merge check; expected behavior is deterministic repair guidance, not a
  generic merge block.

## Cleaner proceeds without required CRAP and DRY tools

Observed in the HTW trial: `cleaner-002` recorded:

```text
tooling: CRAP/DRY cache manifests missing; continuing with assigned source/test
inspection
```

and later completed:

```text
cleanup complete; bb test passed; CRAP/DRY manifests missing in shared cache
```

Expected behavior: the cleaner must use CRAP and DRY tools. Missing required
cleaner tools should not silently downgrade the role to source inspection plus
tests.

Reliable fix:

- Tighten the cleaner prompt and contract to treat missing CRAP/DRY tools as
  either an explicit tool-acquisition step or a blocker handoff.
- Make cleaner assignments include explicit permission for `squad_tool.sh ensure`
  when tools may be installed/fetched.
- Add a preflight before spawning cleaner work, or make cleaner startup fail
  fast with `blocked` if required manifests are unavailable and installation is
  not assigned.
- Add tests or simulator checks that a cleaner without CRAP/DRY does not report
  successful cleanup.

## Helper usage examples still produce wrong command shapes

Observed in HTW panes: several agents called helper commands with invalid
argument shapes before recovering. Examples:

```text
squad_event.sh qa-008 starting "hunt-the-wumpus-qa startup"
SQUAD_EVENT_USAGE_ERROR: first argument is the state, not the agent id.

squad_tool.sh require crap4clj
Usage: squad_tool.sh init ...
```

The agents recovered after reading usage output, but these repeated errors show
that prompts/contracts do not give sufficiently concrete command examples for
required helper calls.

Expected behavior: role prompts and assignment templates should include exact,
valid helper command forms for common required actions.

Reliable fix:

- Include valid `squad_event.sh` examples that never pass the agent id as the
  first argument.
- Include valid `squad_tool.sh require <tool> <source> <version>` examples for
  CRAP, DRY, APS, and mutation tools where those tools are required.
- Make generated assignments list exact required tool names, sources, and
  versions when the role is expected to call `require`.
- Add prompt regression checks for invalid examples such as
  `squad_event.sh <agent-id> starting ...` and `squad_tool.sh require <tool>`
  without source/version.
