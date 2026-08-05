# Bugs

This file groups the swarm trial bugs by the subsystem that should own the fix.
The common theme is that durable workflow state must be canonical, helpers must
enforce validity without inventing workflow, and the dashboard must render that
canonical state without guessing.

## Workflow FSM And Helper Contract

### FSM Must Be The Workflow Authority

The FSM should recommend actions to the squad leader, and the squad leader
should execute those actions. The SL should not independently create workflow
records such as approvals, assignments, reviews, retirements, or recovery paths
under alternate ids.

Observed failure modes:

- The SL created an approval manually, then `squad_next.sh` recommended a
  second canonical approval for the same gate.
- The SL sometimes recognized that the FSM recommendation was wrong and worked
  around it manually.
- Helper tools sometimes failed or forced manual file edits, so the SL bypassed
  them and hand-wrote assignment mirrors.

Expected behavior:

1. `squad_next.sh` is the single source of workflow recommendations.
2. The SL creates or updates durable workflow state only through helper commands
   recommended by the FSM, except that the SL is allowed to create workflow
   records directly when it is explicitly acting on user instruction or making a
   quality-control decision about an artifact it has reviewed.
3. Helpers enforce command format, object identity, and data validity, but do not
   impose an alternate workflow policy.
4. After executing a recommendation, the SL calls `squad_next.sh` again until the
   next state is waiting, blocked, or user-gated.

### Approval Requests Must Be Idempotent By Semantic Gate

Approval records can be duplicated when the same workflow gate is created with
different approval ids.

Observed in a trial: the SL manually requested theme approval using one id, then
`squad_next.sh` recommended another approval id for the same
`(target_kind, target_id, gate)`.

Expected behavior:

1. `squad_approval.sh request` should be idempotent by
   `(target_kind, target_id, gate)`, not just by approval id.
2. If an equivalent pending approval exists, return that approval.
3. If the equivalent approval is already approved or rejected, report that
   resolved state and do not create a duplicate.
4. Web approval button presses should wake the SL and cause it to run and execute
   `squad_next.sh`.

### Squad Next Emits Invalid Batch Assignment Commands

`squad_next.sh` can recommend a batch assignment command using `batch` where
`squad_assign.sh create` expects a story id or another valid scope.

Observed in `/Users/unclebob/junk/squad`:

```text
NEXT_ACTION: create_assignment
THEME: greg-yob-wumpus
STORY: batch
TEMPLATE: hardener
COMMAND: squad_assign.sh create greg-yob-wumpus batch hardener greg-yob-wumpus-hardener-r5 <instructions-file>
```

Earlier attempts to run this command shape failed with:

```text
Story file not found: .../.squad/themes/greg-yob-wumpus/stories/batch.md
```

Expected behavior:

1. `squad_next.sh` should only emit commands accepted by the corresponding
   helper.
2. Batch-scoped work should use `squad_assign.sh create-batch ...`, not
   story-shaped `squad_assign.sh create ... batch ...` syntax.
3. Batch assignment creation should not require the SL to bypass helpers or
   write state files manually.

### Bulk Operations Are Missing

The helper interface forces the SL to run many nearly identical commands for
repeated artifacts.

Needed bulk operations:

- Add all stories produced by an analyst in one command.
- Request or record approval for all eligible stories in one command.
- Generalize this pattern for any repeated workflow action over a selected or
  eligible set of artifacts.

Expected behavior: bulk helpers should take explicit artifact ids, apply the same
deterministic validation as single-artifact helpers, and report per-artifact
results. They should not default to "all eligible" without an explicit selected
set.

### Direct SL-Created Stories Are Not Supported

The user should be able to define a story directly through the SL.

Expected behavior:

1. The SL creates the story artifact through a helper.
2. The story is registered as already approved; a user-authored direct story is
   treated as already user-approved.
3. The story receives an id, source artifact, packet entry, and events log.
4. The FSM sends the approved story to Gherkin and QA procedure agents through
   the normal downstream path.

### Generic Ready Assignments Are Not Spawned Reliably

The FSM does not reliably discover ready assignment records that were created by
the SL or by a recovery/revision path.

Observed failure modes:

- The SL created a replacement analyst assignment after reviewing a clarified
  theme, but `squad_next.sh` returned `wait` instead of spawning the replacement.
- Ready story work could sit idle while `squad_next.sh` focused on a duplicate
  batch recommendation.

Expected behavior:

1. Before returning `wait`, `squad_next.sh` scans canonical assignments for
   spawnable `assignment_created` work.
2. It verifies requirements, capacity, ownership, and assignment file validity.
3. Explicit ready assignments outrank ordinary stage-derived spawn candidates,
   because they represent intentional user/SL/recovery work.
4. It recommends spawning ready assignments in deterministic priority order.
5. Replacement assignment completion supersedes the replaced assignment and
   updates current artifact state.

### Retry Loops Do Not Reset Downstream State

When a reviewer requests changes and a writer/implementer retry completes, the
story does not always re-enter the correct downstream path.

Observed in `/Users/unclebob/junk/squad`: Story 1 received code-review changes,
an implementer retry was merged, and the packet returned to `implemented`, but
stale cleaner/code-review fields still blocked or confused the next cleaner and
review pass.

Expected behavior:

1. A completed retry marks downstream results derived from the prior artifact as
   superseded, retaining history.
2. Implementer retry success routes to cleaner, then code reviewer.
3. Code reviewer changes route back to implementer.
4. Cleaner success routes to code reviewer.
5. Senior implementer output routes back to architect, because the architect has
   final architectural review authority.
6. Stale `changes-requested`, blocked, or review fields from a superseded chain
   must not permanently block the current chain.

### Architect And Batch Flow Ordering Is Wrong Or Incomplete

Batches should not wait unnecessarily once their inputs are ready. The desired
late-stage flow is `QA -> architect <-> senior-implementer -> done`.

Observed failure modes:

- A batch appeared ready for architecture but was not started.
- Story 4 appeared ready for hardening, but `squad_next.sh` continued to
  recommend duplicate hardener work instead of starting the needed work.
- Senior implementer routing did not clearly return to architect review.

Expected behavior: batch and story flow should be encoded directly in the FSM,
with no requirement that all unrelated stories complete before a ready story or
batch advances. Architect and senior-implementer should form a review/revision
loop until the architect accepts the result, at which point the workflow reaches
done.

### Merge Conflicts Need A Merger Workflow

Merge conflicts should not be resolved directly by the SL long term. They should
trigger a dedicated merger agent. The merger is a special role outside normal
transient-agent capacity.

Expected behavior:

1. A `merge_blocked` assignment triggers merger workflow.
2. The merger checks out the SL's current integration state and merges the
   author agent's result branch or commit.
3. The merger runs relevant test suites to ensure the merge did not break
   existing behavior.
4. The merger hands an unconflicted merge result back to the SL.
5. The author branch remains reachable until merge resolution succeeds or is
   explicitly abandoned.
6. The author worktree is preserved when it may contain useful conflict context,
   generated artifacts, or diagnostics.

No lock is required for now, but the workflow must tolerate a second merge
conflict after the merger hands work back to the SL.

### Result Handoff Validation Is Too Weak

The workflow can accept a result handoff commit that is already reachable from
`HEAD` even when that commit does not belong to the assignment.

Observed in `/Users/unclebob/junk/squad`: Story 2 was marked `cleaned` from
cleaner assignment `greg-yob-wumpus-002-movement-and-room-hazards-cleaner-r2`,
with `cleaner_sha: 5569bb70f5`. That commit was actually a merge commit for a
different Gherkin assignment.

Expected behavior:

1. A result handoff commit is validated against sender, assignment id,
   role/template, branch, and worktree lineage.
2. Every handoff also includes an explicit assignment result manifest tying the
   result to the assignment, agent, template, commit, and produced artifacts.
3. A commit merely being reachable from `HEAD` is not enough.
4. No-op results still need an explicit assignment result artifact or no-op
   declaration tied to the assignment.
5. Invalid result handoffs block the assignment and become visible dashboard
   blockers.

### Finish-Current Recommendations Can Be Stale

`squad_next.sh` can recommend `finish_in_process_handoff` and tell the SL to run
`done_with_current.sh` when no current handoff/task exists.

Observed in `/Users/unclebob/junk/squad`: the SL ran `done_with_current.sh` and
received `NO_TASK`; a fresh `squad_next.sh` then recommended retiring an agent.

Expected behavior: `finish_in_process_handoff` is recommended only when the
handoff helper has an actual in-process handoff and `squad_next.sh` can include
the concrete handoff id or path. Otherwise the FSM should return the next valid
action. `squad_next.sh` should not emit bare `done_with_current.sh` without a
concrete current handoff.

## Agent Lifecycle, Capacity, And Cleanup

### Lifecycle States Are Used For Step Telemetry

Agents use lifecycle states such as `failed` and `complete` for intermediate
steps, then continue working. This makes liveness, dashboard display, and stall
recovery ambiguous.

Observed in `/Users/unclebob/junk/squad`: `hardener-004` logged:

```text
failed  hardening failed: gherkin-mutator story 001 exit 1
running hardening: gherkin-parser story 001 after pruning
running hardening passed: gherkin-mutator story 001 after pruning
```

Other agents logged `complete` before later `handoff_ready` and `handoff_sent`.

Expected behavior:

1. Lifecycle states describe assignment lifecycle, not command results.
2. Recoverable red/green failures are recorded as `running` detail or command
   telemetry.
3. `failed` is terminal unless an explicit deterministic retry/recovery
   transition changes assignment state.
4. `complete` should be removed from transient agent lifecycle. Agents should use
   `handoff_ready` when the assignment artifact is complete and ready to hand
   off.

### Assignment And Agent State Are Not Synchronized

Spawning an agent does not always mark the assignment as active, and result
handoffs do not always transition assignments deterministically.

Expected behavior:

1. Spawn fulfillment sets assignment state to `in_progress` and records agent id
   and session.
2. Result handoff transitions assignment from `in_progress` to `result_received`
   or a blocked/invalid state.
3. `squad_next.sh` reasons from canonical assignment state, not stale mirrors.
4. Canonical assignment states are `created`, `in_progress`, `handoff_sent`,
   `result_received`, `merged`, `blocked`, `superseded`, and `retired`.

### Capacity And Throughput Are Poorly Scheduled

The swarm can exceed configured capacity and can also underuse available slots.

Observed failure modes:

- `max_transient_agents` was 5, but six transient tmux sessions were active.
- The swarm repeatedly filled slots, waited while agents sat in `handoff_sent`,
  flushed retirements only after the wave completed, then started a new wave.
- Ready work sat idle while only one or two agents were running.

Likely causes:

- Capacity checks use stale or incomplete active-agent state.
- `handoff_sent` agents with live tmux sessions do not consistently count
  against capacity.
- `squad_next` action priority does not first free completed slots and then fill
  available slots.

Expected behavior:

1. Live transient tmux sessions never exceed `max_transient_agents`.
2. Agents whose merged handoff no longer needs them are retired promptly.
3. Ready work is spawned as soon as capacity is available.
4. The swarm behaves as a continuous pipeline, not a fill/wait/flush batch.
5. Agents in `handoff_sent` do not count against capacity once their handoff has
   been sent, even if they have not yet been retired.

### Handoff-Sent Agents Are Not Retired Promptly

Agents in `handoff_sent` can remain present after the SL has merged their
handoff, occupying capacity and cluttering status displays.

Observed in `/Users/unclebob/junk/squad`: several agents remained listed as
`handoff_sent` after `squadd.log` recorded `handoff-delivered` for their
handoffs.

Expected behavior: once a transient agent's handoff result has been merged, the
agent should be retired promptly unless merge-blocked preservation applies.
Retirement removes the tmux session, worktree, role registration, and branch
according to the normal policy.

### Swarm Shutdown Leaves In-Flight Worktrees Behind

Killing or stopping the swarm can leave an in-flight transient agent registered
as `running`, with its git worktree and branch still present, even though its
tmux session is gone and `squadd` has stopped.

Observed in `/Users/unclebob/junk/squad`: after shutdown, `tmux ls` was empty
and `.swarmforge/daemon/squadd.log` ended with `stopped`, but `hardener-004`
still had `state: running`, and `git worktree list --porcelain` still showed
`.worktrees/hardener-004` on branch `swarmforge-hardener-004`.

Expected behavior:

1. Swarm shutdown cleanup reconciles every registered transient agent.
2. If the tmux session is gone, the agent does not remain in `running`.
3. On swarm kill, all non-merge-blocked transient worktrees and branches are
   deleted unconditionally.
4. Preserved worktrees are marked with explicit preserved/blocked state and
   reason.

### Handoff Sending Is Not Cleanly Idempotent

Agents can believe they sent a handoff, then resume running and send again when
a helper-side draft or path issue occurs.

Observed in `/Users/unclebob/junk/squad`: `qa-procedure-reviewer-007` logged
`handoff_sent`, then `running` because the draft path was missing, then
`handoff_sent` again. Only one completed inbox handoff remained in that case,
but the agent-facing lifecycle was confusing and could create duplicate SL work.

Expected behavior:

1. Handoff creation/sending is idempotent by `(task, sender, recipient, commit)`.
2. If a send fails before enqueue, the helper reports failure without recording
   `handoff_sent`.
3. If a send succeeds, repeating the command returns the existing handoff
   identity.

### Stall Recovery Is Too Sensitive And SL Wakeups Are Weak

Recovery is too aggressive, too race-sensitive, and does not reliably use agent
activity evidence such as status timestamps and pane tails.

Observed failure modes:

- The SL treated active agents as blocked or missing even when their pane tails
  showed work.
- The SL was woken by `squadd`, ran `squad_next.sh`, and summarized the
  recommendation instead of executing the `COMMAND`.
- The SL can sit idle waiting for a prompt when no agents are active and ready
  work exists.

Expected behavior:

1. `squadd` determines idleness from no pane/status activity for the configured
   60 second threshold, not raw elapsed time alone.
2. Agent pane tails and heartbeat/status updates count as liveness. A busy pane
   tail suppresses stall recovery even if no `squad_event` heartbeat is written.
3. A wakeup tells the SL to run `squad_next.sh`, execute the recommended command,
   then continue the advisor loop.
4. If a command cannot be executed, the SL records/reports a blocker instead of
   repeatedly printing the same recommendation.

## Tool Provisioning And Role Contracts

### Tool Installation Policy Is Contradictory

The constitution identifies required quality-tool repositories and says
quality-gate agents should install missing task-specific tools when needed, but
some role prompts instruct agents to use `squad_tool.sh require` and block unless
the assignment explicitly authorizes `ensure`.

Observed in `/Users/unclebob/junk/squad`: cleaner agents knew the Clojure CRAP
and DRY tool sources, but the shared tool cache was empty. `require` failed with
`SQUAD_TOOL_MISSING`, and the cleaner blocked because its prompt prohibited
installation without assignment-specific authorization.

Expected behavior:

1. Constitution, role prompts, role contracts, and generated assignments agree
   on the tool acquisition policy.
2. Agents load/install required tools as needed by running `squad_tool.sh ensure`
   for tools listed in the canonical role/tool table.
3. Missing required tools become deterministic assignment blockers only after
   install/provisioning has failed.
4. All roles that need external tools are explicitly told at startup to load or
   ensure those tools.
5. Required tools are defined only in the constitution/tool table. Role prompts
   refer to that table instead of hardcoding divergent tool commands.

Affected roles include cleaner, hardener, QA, architect/reviewer roles, code
reviewer, Gherkin writer/reviewer, and any role using CRAP, DRY, mutation, or APS
tools.

### APS Tool Source Identity Is Inconsistent

The hardener role contract required `gherkin-parser` and `gherkin-mutator` from
`github.com/unclebob/Acceptance-Pipeline-Specification`, but retry assignment
text and shared manifests used standalone sources:

```text
github.com/unclebob/gherkin-parser
github.com/unclebob/gherkin-mutator
```

Expected behavior:

1. The constitution, role prompts, generated assignments, and tool manifests use
   the same canonical source identity for each tool.
2. APS commands such as `gherkin-parser`, `ir-dry-checker`, and
   `gherkin-mutator` consistently resolve to
   `github.com/unclebob/Acceptance-Pipeline-Specification` unless the tool table
   is deliberately changed.
3. `squad_tool.sh require` rejects source mismatches clearly.

### APS Tool Usage Is Not Enforced

Gherkin-writing and related acceptance-pipeline agents can produce and hand off
artifacts without proving they used required APS tools.

Observed in `/Users/unclebob/junk/squad`: Gherkin prompts referenced
`gherkin-parser` and `ir-dry-checker`, but artifacts were merged and advanced
even when the shared tool cache later showed those tools missing and logs did
not show successful parser or IR DRY runs.

Expected behavior:

1. APS parser and IR DRY tools are required when assigned to a Gherkin role.
2. The agent must run the parser against produced Gherkin before handoff.
3. The agent must run IR DRY checking/normalization when assigned.
4. The handoff includes both command transcript evidence and generated normalized
   IR artifacts. Evidence must include tool name, canonical source/version,
   command, exit code, short output summary, normalized IR artifact path, IR DRY
   result/report path, and metadata tying those outputs to the story,
   assignment, and tool manifest.
5. Failure to install, load, or run required APS tools blocks the assignment and
   appears on the dashboard.

### Required Tool Failures Are Hidden

When an agent cannot load required tools, the failure can disappear into a
handoff or retirement path instead of becoming visible canonical workflow state.

Observed in `/Users/unclebob/junk/squad`: cleaner and hardener assignments
blocked on missing `crap4clj`, `dry4clj`, `gherkin-parser`, or
`gherkin-mutator`, but the dashboard and packets did not consistently show a
clear user-visible blockage.

Expected behavior:

1. Agent reports missing or unloaded required tool.
2. Workflow directs tool loading/provisioning.
3. If loading succeeds, the agent continues.
4. If loading fails, the assignment/story packet records a blocked state with
   tool and reason.
5. Missing required tool evidence blocks merge of the handoff; the web dashboard
   shows the blockage prominently.

## Dashboard And Web Tool

### Dashboard Refresh Destroys SL Message Drafts

The web dashboard refresh loop clears the squad leader message textarea while
the user is typing.

Expected behavior:

1. Polling should continue normally while the user is typing.
2. The unsent textarea value should always be preserved across dashboard
   refreshes, whether or not the textarea currently has focus.
3. Draft preservation only needs to live in browser memory; it does not need to
   survive a full page reload.
4. After a successful submit, the textarea should clear immediately.
5. If the backend rejects the message or delivery fails, the typed text should
   remain in the textarea for retry.

### Dashboard Needs Artifact Links And Renderers

Pending approval rows should link the artifact name to a readable rendered view
of the referenced artifact.

Renderer priority:

1. Tier 1 renderers are required first: theme, story, Gherkin, QA procedure,
   review, and blocker.
2. Later renderers can cover implementation result/handoff manifest, cleaner
   report, code review report, architect report, hardener report, QA execution
   report, and merger result.
3. The renderer registry should be extensible so adding later artifact types does
   not require changing the approval list UI.

### Dashboard Needs Live Agent Pane Windows

Clicking an active agent should open a separate window that continuously shows
the agent's tmux pane activity in a scrolling view.

Expected behavior: the pane updates live or near-live, preserves scroll behavior
appropriately, and shows enough recent output to judge progress. The live pane
window is read-only.

### Dashboard Agent And Assignment Filtering Uses Stale State

The dashboard can show stale assignments as active, hide active agents during
lifecycle transitions, or keep superseded blocked assignments in the current
view.

Observed failure modes:

- Agents disappear and reappear around handoff transitions.
- An original Gherkin review assignment remained listed as active after an `-r2`
  replacement review was accepted and merged.
- Original blocked hardener assignments remained visible as current after a
  replacement hardener succeeded.
- Many `handoff_sent` agents remained listed after their useful work was merged.

Expected behavior:

1. Dashboard visibility derives from canonical packet/assignment state and
   explicit supersession records.
2. Every non-retired agent remains visible from spawn through retirement, with a
   stable display state.
3. Active assignment views show only current canonical assignments; superseded
   assignments are hidden by default and shown only in history/debug views.
4. Assignment lists are ordered reverse chronologically, newest first.
5. The approval history section should be removed from the normal dashboard.

### Dashboard Story State Labels Are Confusing

Story state on the dashboard does not reliably reflect the latest canonical
stage.

Observed failure modes:

- Story 1 was cleaned, but the dashboard still showed `implemented` or
  `specification in progress`.
- Blocked hardener/cleaner state appeared to contradict what the SL believed had
  succeeded.

Expected behavior: dashboard story state is derived from the latest canonical
packet/FSM stage, including cleaner completion, code review status, architecture
review, hardening, QA, blockers, and superseded retry chains. The major stage
labels are `specified`, `gherkin approved`, `qa approved`, `implemented`,
`cleaned`, `code reviewed`, `architect approved`, `hardened`, and `done`.

### User Messages From Web Dashboard Need Correct SL Delivery

The web dashboard should provide a textarea and submit button that sends text to
the SL. Sending text to the SL currently requires two returns with a 100 ms delay
between them, otherwise the SL may not receive or process the message correctly.

Expected behavior:

1. The dashboard preserves user input while polling.
2. Submit sends the message to the SL pane.
3. Delivery sends the required extra return sequence reliably.
4. The SL wakes and runs the workflow loop after processing the message.

### Blockers Must Be First-Class Dashboard Items

Failures that require user intervention or workflow repair should appear clearly
on the dashboard in a dedicated top-level blockers section.

Examples:

- Required tool install/load failures.
- Invalid result handoffs.
- Merge conflicts awaiting merger.
- Agents that cannot load their required tools after being told to do so.
- Assignments that are blocked but have been incorrectly treated as complete.

## Role Prompt Quality

### Analyst Prompt Lacks Story Writing Principles

The analyst prompt should describe story writing principles: scope,
independence, acceptance readiness, downstream implementability, and avoiding
vague or bundled requirements. It should explicitly teach and require the
I.N.V.E.S.T. criteria: independent, negotiable, valuable, estimable, small, and
testable.

### Architect Prompt Needs Review-Oriented Principles

The architect only reviews and recommends; its prompt should frame architectural
principles as advisory review criteria and recommendations, not as direct
implementation orders. The architect should not directly mark an artifact
blocked pending architectural changes.

Required principles include:

- The Dependency Rule.
- Low level is close to IO; high level is far from IO.
- Prefer separating large modules with many responsibilities into individual
  well-named modules with single responsibilities.

### Role Prompts Must Require Tool Loading Up Front

Every role prompt should include a standard "load required tools first" block
generated from the constitution/tool table. All roles with required tools should
load or ensure those tools before substantive work. If tools cannot be loaded,
the role must report a canonical blocker with enough detail for the dashboard and
FSM.
