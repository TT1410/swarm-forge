# Next Trial Implementation Plan

Goal: make the next swarm trial deterministic enough that the squad leader does
not rely on memory, pane watching, or ad hoc prompt interpretation for
orchestration.

## 1. Add a Deterministic Workflow Tool

Create a first-class command, likely `squad_next.sh` or `squad_workflow.sh`, that
reads durable state and emits the next required action.

It should inspect:

- `.squad/stories/*/packet`
- `.squad/assignments/*/status`
- `.squad/batches/*`
- `.squad/approvals/*`
- `.swarmforge/handoffs/inbox`
- `.squad/spawn-requests`
- `.swarmforge/roles.tsv`
- `squad_status.sh` output, including pane tails
- git worktrees and `swarmforge-*` branches

Example output:

```text
NEXT_ACTION: request_implementation_approval
STORY: hunt-the-wumpus-02-cave-topology-and-warnings
REASON: gherkin_review accepted and qa_procedure_review accepted
DO_NOT_WAIT_FOR: other stories
```

```text
NEXT_ACTION: retire_agent
AGENT: qa-procedure-writer-003
REASON: handoff completed, result merged, no further work needed
COMMAND: swarmforge/scripts/squad_retire.sh qa-procedure-writer-003
```

The squad leader uses this tool as its main orchestration loop:

```text
1. Run squad_next.sh.
2. Do the emitted next action, unless blocked.
3. Record the result with the relevant helper.
4. Run squad_next.sh again.
5. Repeat until squad_next.sh says wait or user approval is required.
```

The squad leader gets global orchestration actions such as:

- process handoff
- request user approval
- spawn agent
- retire agent
- create replacement assignment
- create batch
- wait for active agent
- clear stale lock

Transient agents use the same tool, or an agent-scoped mode of it, for
assignment-local protocol guidance only. They should not receive orchestration
authority. Agent-scoped actions include:

- record starting
- inspect assignment
- run required artifact checks
- commit result
- prepare handoff
- send handoff
- record blocked

If there is nothing actionable to do, `wait` must be an explicit action, not an
absence of state. Example:

```text
NEXT_ACTION: wait
REASON: active agents are still working
ACTIVE:
  - gherkin-writer-006 htw-04-gherkin PANE_LIVE true
CHECK_AFTER_SECONDS: 30
DO_NOT:
  - recover these agents
  - spawn replacements
  - ask user for approval
COMMAND:
  sleep 30
  squad_next.sh
```

Incoming handoff tmux notifications should remain as wake-up signals, but they
should tell the squad leader to run the workflow tool instead of
`ready_for_next.sh`.

```text
New handoff received from gherkin-writer-005 for htw-01-gherkin-r2.
If idle, run squad_next.sh.
```

The tmux message is only an interrupt. `squad_next.sh` decides whether to finish
an in-process handoff, process the new handoff, retire an agent, request
approval, spawn work, or wait.

## 2. Make Workflow Per Story, Not Batch-Gated

The squad leader must not wait for all stories to reach the same gate before
advancing one story.

Rule:

```text
When any story reaches an approval gate, request approval for that story
immediately.
```

This applies to every approval gate:

- story approval
- Gherkin approval
- QA procedure approval
- implementation approval
- cleanup, hardening, QA, architecture, and final approval gates

Examples:

- All analyst stories do not need to be complete before one story can be
  approved.
- All stories do not need accepted Gherkin before one story can move to QA
  procedure work.
- All stories do not need to be implementation-ready before one story can request
  implementation approval.

After user approval, spawn the next role for that story when capacity permits.
Other stories continue through their own Gherkin, QA, and review paths in
parallel.

## 3. Add Durable Approval Requests

Approval gates should be configured as booleans. If approval is required, the
state machine creates a pending approval and waits for the user. If approval is
not required, the state machine records the gate as approved automatically and
continues.

Default required gates:

```text
approval_required theme true
approval_required story true
approval_required gherkin true
approval_required qa_procedure true
approval_required implementation false
approval_required code_review false
approval_required hardening false
approval_required qa false
approval_required architecture false
approval_required final false
```

State-machine rule:

```text
When an artifact reaches an approval gate:

if approval_required(<gate>) == true:
  create pending approval
  state = <gate>_approval_requested
  wait for user approval

if approval_required(<gate>) == false:
  record approval automatically
  state = <gate>_approved
  detail = auto-approved-by-config
  continue to next action
```

A story reaching an approval-required gate should create an explicit durable
approval request, not just a pane message.

Example artifact:

```text
.squad/approvals/pending/<story-id>-implementation.approval
```

It should include:

- story id
- accepted Gherkin assignment, SHA, and path
- accepted QA procedure assignment, SHA, and path
- requested action
- exact approval command or response format

This prevents approval requests from getting lost in the squad leader transcript.

## 4. Strengthen Story Packet State

Story packets should be the canonical progress record.

They should clearly represent:

```text
story_approval
gherkin_assignment
gherkin_review
qa_procedure_assignment
qa_procedure_review
implementation_approval
implementation_assignment
cleaner_review
hardener_review
qa_result
architecture_result
final_state
```

Replacement iterations should be explicit:

```text
gherkin_iterations:
  - htw-01-gherkin: changes-requested
  - htw-01-gherkin-r2: pending-review
```

This avoids counting iterations by naming convention alone.

Batching should be represented as a first-class object, not inferred from
assignment names or prompts.

Add:

```text
.squad/batches/<batch-id>/
  metadata
  manifest
  status
  result
  review
```

The batch manifest lists included stories and relevant packet/artifact SHAs. Each
story packet also points back to the batch for that gate.

Batchable stages include:

- hardener
- QA
- architecture
- senior-implementor, when fixing architecture findings across multiple stories

Per-story stages should generally stay per story:

- story approval
- Gherkin writing and review
- QA procedure writing and review
- implementation
- cleanup
- code review

The workflow tool should batch stories that have independently reached the same
batch-ready state. It must not wait for unrelated stories unless the user or an
explicit batch policy says to wait.

## 5. Support a Web Status and Approval Surface

The existing durable state is close to enough for a web status screen:

- story packets show per-story phase and artifact status
- assignments show task execution status
- agents show active, blocked, handoff-sent, or retired workers
- handoff inbox directories show queued, in-process, and completed handoffs
- spawn requests show pending, failed, and completed agent starts
- batch manifests show grouped quality-gate work

Add first-class durable approval requests so the same state can power approval
buttons.

Approval files:

```text
.squad/approvals/pending/<approval-id>.approval
.squad/approvals/approved/<approval-id>.approval
.squad/approvals/rejected/<approval-id>.approval
```

Example pending approval:

```text
approval_id: implementation__hunt-the-wumpus-02-cave-topology-and-warnings
gate: implementation
story_id: hunt-the-wumpus-02-cave-topology-and-warnings
state: pending
title: Approve implementation for Story 2
reason: accepted Gherkin and accepted QA procedure
created_at: 2026-08-03T...
approve_command: squad_packet.sh approve <story-id> implementation approved-by-user
reject_command: squad_packet.sh reject-approval <story-id> implementation rejected-by-user
```

The web screen can render each story's packet state and show approval buttons for
pending approvals:

```text
Story 2: ready for implementation approval
[Approve Implementation] [Reject]
```

Browser actions should call a small local helper instead of mutating files
directly:

```text
squad_approve.sh <approval-id>
squad_reject.sh <approval-id> <reason>
```

Those helpers should:

1. Read the pending approval file.
2. Validate the current packet or theme state still matches the approval.
3. Run the allowed approval or rejection transition.
4. Move the approval file to `approved/` or `rejected/`.
5. Let `squad_next.sh` discover the resulting next workflow action.

This supports both a read-only status dashboard and a safe local approval UI.

## 6. Have the Workflow Tool Emit Correct Command Formats

The deterministic tool should instruct agents and the squad leader on command
usage at the point of need.

Example:

```text
VALID_COMMAND:
  squad_event.sh running "reviewing revised Gherkin against prior findings"

VALID_STATES:
  starting
  running
  blocked
  failed
  complete
  handoff_ready
  handoff_sent
  retired

RULE:
  First argument is lifecycle state only.
  Put agent id, task id, and progress words in the detail string.
```

This should reduce repeated `squad_event.sh` misuse.

## 7. Fix Recovery Sensitivity

Recovery is too aggressive and race-prone.

New rule: do not recover or replace an agent if any of these are true:

- `PANE_LIVE: true`
- pane tail changed recently
- recent heartbeat or status update
- recent artifact modification
- handoff is queued or in process
- assignment is already complete, merged, or reviewed
- agent is awaiting squad leader processing, not doing more work

Recovery should require sustained inactivity across multiple checks and should
include pane-tail activity as progress.

## 8. Improve `squad_status.sh`

Keep the live pane tail feature and make it more actionable.

`squad_status.sh` should include:

- `PANE_LIVE`
- `PANE_CAPTURED_AT`
- `LAST_20_LINES`
- `PANE_TAIL_HASH`
- `PANE_TAIL_CHANGED_AT`

That gives the squad leader a deterministic activity signal without manually
reading tmux.

## 9. Make Locks Stale-Aware

The stale registry lock caused retirement failures.

Fix `squad_spawn.sh` and `squad_retire.sh` lock handling:

- lock owner file should include PID, command, host, and timestamp
- if owner PID is dead, clear lock automatically
- if owner PID is alive, wait or report normally
- error message should name the owning process if alive

`spawn.lock` should not require manual cleanup when owner PID is gone.

## 10. Make Retirement Idempotent

`squad_retire.sh` should treat already-retired or partially-cleaned agents as
success.

Cases to handle cleanly:

- tmux session already gone
- worktree already removed
- branch already deleted
- role entry already absent
- agent status already says retired

Return success with reconciliation details, not `Unknown transient agent`, when
enough evidence shows the agent is already retired.

## 11. Fix Kill-Swarm Cleanup

The kill path killed tmux and `squadd` but left worktrees, branches, and roles.

Add final reconciliation to cleanup:

- kill tmux sessions
- stop `squadd` and status daemons
- remove every transient role from `.swarmforge/roles.tsv`
- remove every managed `.worktrees/<agent>`
- delete every matching `swarmforge-<agent>` branch
- prune git worktrees
- remove stale locks
- report leftovers

It must clean based on registry and git state, not only live tmux sessions.

## 12. Remove Per-Worktree Tool Hardlinks

Yolo agents can use tools from the project root and shared tool cache directly.
The hardlink materialization strategy is no longer needed as an escalation
workaround.

Remove or disable per-worktree hardlink materialization, including:

- hardlinking cached tools into `.worktrees/<agent>/.swarmforge/tools/bin`
- relying on hardlinked tool copies to avoid sandbox escalation
- any prompt or helper language that implies hardlinks are required for yolo
  transient agents

Keep root/shared tool cache usage simple: agents may use the root-visible tool
paths and shared cache through the environment prepared by `squad_spawn.sh`.

## 13. Next Trial Success Criteria

Before running another trial, we should be able to observe:

- a story reaches `implementation_approval_ready`
- a durable approval request appears
- a web/status consumer can render story state and pending approval buttons from
  durable files
- squad leader requests approval for that story immediately
- squad leader does not wait for all stories
- agents are retired after processed handoffs
- kill-swarm removes sessions, worktrees, branches, and role entries
- stale locks self-clear
- yolo agents use root/shared tools without per-worktree hardlink materialization
- recovery does not fire while pane tails show activity

The main architectural change is this: the squad leader should follow a
deterministic workflow engine, not infer orchestration from prompts, inbox state,
and memory.
