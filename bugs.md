# Bugs

## Prompt Workflow Instructions Are Inconsistent

`swarmforge/constitution/articles/handoffs.prompt` tells notified roles to run
`ready_for_next.sh`. That is correct for transient agents, but it is wrong or
ambiguous for the squad leader.

The squad leader prompt and current squad behavior require the squad leader to
run `squad_next.sh` when woken for handoff mail, status attention, web approval
changes, recovery checks, or user redirection. `squad_next.sh` is the single
source of workflow truth and direction for the squad leader.

Expected behavior:

1. Handoff receiving instructions distinguish persistent squad leader behavior
   from transient agent behavior.
2. Transient agents run `ready_for_next.sh` / `done_with_current.sh` for task
   intake and completion.
3. The squad leader runs `squad_next.sh`, executes the returned `COMMAND`, and
   repeats until the advisor returns wait, blocked, or user-gated state.
4. Generic `workflow.prompt`, `handoffs.prompt`, `local-workflow.prompt`, and
   `roles/squad-leader.prompt` should describe the same effective workflow and
   avoid conflicting wakeup instructions.

Related prompt discrepancies found by scan:

- `handoffs.prompt` still contains pack-pipeline language about forwarding a
  `git_handoff` to the next role in the chain and terminal broadcast/merge-only
  behavior. Current squad transients are not pipeline intermediates; every
  transient result handoff returns to `squad-leader`.
- `local-workflow.prompt` refers to an "artifact reviewer", but no current
  `artifact-reviewer` template is listed for the squad. It should name the
  actual artifact review roles or use "artifact review roles" generically.
- `local-workflow.prompt` says the squad leader may make decisions after
  "forwarding its artifacts"; "forwarding" is stale pipeline language. The squad
  leader should record and assign the next step according to `squad_next.sh`.
- `cleaner.prompt` says `architecture-cleaner` owns high-level architectural
  cleanup. Current flow says `architect` critiques and `senior-implementor`
  applies selected architectural improvements.
- `architecture-cleaner.prompt` and `architecture-reviewer.prompt` still exist
  as active-looking prompt files, but they are not listed in the current squad
  leader template set. They should be marked legacy/deprecated or removed from
  active prompt/config paths.
- `specifier.prompt` is legacy relative to the current split flow. It owns both
  Gherkin and QA procedure generation, while current squad workflow uses
  `gherkin-writer` and `qa-procedure-writer`.
- `hardener.prompt` and `qa.prompt` still refer to "specifier" outputs. Current
  wording should refer to approved Gherkin and approved QA procedure artifacts.
- `hardener.prompt` and `qa.prompt` say to process queued handoffs as a singleton
  batch. Current squad batching should be explicit through batch assignments,
  story packets, and `squad_batch.sh`; agents should not infer batch membership
  from queued handoffs.

## Completed Handoff Can Be Lost Before Merge And Result Recording

In the `~/junk/squad` trial on 2026-08-05, the analyst handed off commit
`5be412ab53` for assignment `hunt-the-wumpus-yob-analysis`. The squad leader
processed the handoff with `ready_for_next.sh`, completed it with
`done_with_current.sh`, and then retired `analyst-001`.

The analyst did commit the story artifacts. `git show 5be412ab53` showed the
four declared story files, but the commit was never merged into the squad leader
worktree and the assignment result was never recorded. After the analyst branch
and worktree were removed, the commit became unreachable except through the
local object database. The assignment remained:

- `state: in_progress`
- `RESULT: none`
- `MERGE: none`
- `ACCEPTED_MERGE: none`

After the analyst was retired, `squad_next.sh` returned `wait` with "no
handoffs, pending approvals, active transient agents, or stale locks", leaving
the workflow stuck. The durable state still showed unfinished assignment work,
but the advisor no longer had any actionable next step and the committed
artifacts were no longer reachable through an agent branch or worktree.

This is a workflow bug. The FSM/advisor allowed an invalid transition:

`handoff received -> handoff marked completed -> agent retired -> assignment
still in_progress -> squad_next waits`

The workflow must retain ownership of an unfinished assignment. Helper commands
may provide guardrails, but the primary fix belongs in `squad_next.sh` and the
workflow state rules.

Expected behavior:

1. A completed `git_handoff` must not be marked done until the workflow has
   merged the referenced commit or recorded an explicit merge-conflict/blockage
   action.
2. The workflow must verify that the referenced commit exists and contains every
   declared artifact before allowing the source agent to be retired.
3. If the referenced commit or declared artifacts are missing, the workflow must
   leave the source agent recoverable and create an explicit blocked/retry action.
4. The source agent branch or another durable ref must be preserved until the
   handoff result has been merged or superseded.
5. `squad_next.sh` must detect assignments that are `in_progress` with no live
   agent, no result, and no pending recovery action, and return a repair/blockage
   command instead of `wait`.
6. The dashboard should surface this as a blocked workflow state rather than
   showing an idle swarm.

## Agent Pane Viewer Clears When Agent Dies

In the dashboard, clicking an agent opens a separate view of that agent's tmux
window activity. When the agent dies or its tmux session is removed, the viewer
currently clears the pane contents.

Expected behavior:

1. The viewer should preserve the last captured pane text after the agent dies.
2. The view may indicate that the agent/session is no longer live, but it must
   not discard the captured text.
3. Refresh/polling should keep the stale final pane contents visible until the
   user closes the viewer or selects a different agent.

## Replacement Assignment Merge Does Not Satisfy Original Workflow Step

In the `~/junk/squad` trial on 2026-08-05, the squad leader correctly recovered
from the earlier lost analyst handoff by creating a replacement analysis
assignment, `hunt-the-wumpus-yob-analysis-commit-stories`.

For `analyst-003`, the squad leader handled the handoff in the correct order:

1. Recorded the result with `squad_assign.sh result`.
2. Checked merge readiness with `squad_assign.sh merge-ready`.
3. Accepted the merge with `squad_assign.sh accept-merge`.
4. Completed the handoff.
5. Retired the analyst only after the merge was recorded.

After that successful merge, `squad_next.sh` unexpectedly returned another
`create_assignment` action for analysis instead of advancing the merged story
artifacts into the next workflow stage. The squad leader noticed the duplicate
analysis recommendation and paused to inspect state instead of blindly spawning
another analyst.

Expected behavior:

1. A merged replacement assignment must satisfy the original workflow step it
   supersedes.
2. `squad_next.sh` should follow `replacement` / `replaces` links when deciding
   whether theme analysis is complete.
3. Once a replacement analysis result is merged, the workflow should advance the
   resulting stories toward story approval / Gherkin / QA according to the
   configured gates.
4. The advisor should not recommend duplicate analysis merely because the
   original superseded assignment is not itself merged.

## APS Tools Are Not Provisioned Before Gherkin Writers Spawn

In the `~/junk/squad` trial on 2026-08-05, Gherkin writer agents failed at
startup because required APS tools were unavailable:

- `gherkin-writer-001` blocked with missing `gherkin-parser` and
  `ir-dry-checker` and no authorized `ensure` command.
- `gherkin-writer-002` reported `squad_tool.sh require gherkin-parser` exit 3,
  `squad_tool.sh require ir-dry-checker` exit 3, then blocked with missing APS
  tool manifests.

The agents behaved correctly by blocking instead of fetching or substituting
informal inspection. The bug is in provisioning/workflow: roles that require
tools must not be spawned into assignments that only contain failing `require`
checks unless the shared tool cache has already been provisioned.

Expected behavior:

1. The squad leader or workflow must provision required APS tools before
   spawning Gherkin writers, or the assignment must include exact authorized
   `squad_tool.sh ensure ...` commands.
2. Required tool metadata should come from `swarmforge/tool-table.edn`; agents
   should not infer alternate repositories or commands.
3. If provisioning fails, the workflow should show a blocking tool-provisioning
   issue on the dashboard instead of repeatedly spawning doomed Gherkin writers.
4. Tool-required roles should continue treating missing required tools as a
   blocking issue.

## Missing Formal Tool Evidence Should Not Block Verified Tool Use

In the `~/junk/squad` trial on 2026-08-05, replacement Gherkin writers were able
to load and use the required APS tools, but several Gherkin assignments were
blocked because their handoff lacked the strict `tool_evidence` header:

- `hunt-the-wumpus-yob-001-cave-instructions-gherkin-r2`
- `hunt-the-wumpus-yob-002-movement-and-hazards-gherkin-r2`
- `hunt-the-wumpus-yob-003-crooked-arrows-and-wumpus-gherkin`
- `hunt-the-wumpus-yob-004-replay-and-session-flow-gherkin`

The blocker detail was "missing required tool evidence header tool_evidence
(command transcript for gherkin-parser and ir-dry-checker)". This rule is too
strict when the workflow can verify or reasonably establish that the required
tools were used through committed evidence artifacts, normalized IR files, DRY
reports, agent event logs, or captured command output.

Expected behavior:

1. Required tool use should remain mandatory for roles that require those tools.
2. A missing formal `tool_evidence` handoff header should not by itself block
   the workflow if equivalent evidence is present in committed artifacts or
   agent logs.
3. The validator should accept multiple evidence sources, including declared
   handoff headers, evidence artifact paths, parser/IR output artifacts, and
   recorded command telemetry.
4. Missing evidence should produce a warning or repair request when the tool use
   is otherwise verifiable, and a blocker only when required tool use cannot be
   verified.

## Resolved Blockages Remain Visible On Dashboard

When a blockage is resolved, the web dashboard can continue showing the old
blockage even though the workflow has moved on. In the `~/junk/squad` trial on
2026-08-05, APS tool blockages were repaired by provisioning the tools and
spawning replacement Gherkin writers, but stale blockage information could still
appear after the replacement work was active.

Expected behavior:

1. When a blocking condition is superseded, retried, repaired, merged, or
   otherwise resolved, it should be removed from the active dashboard blockage
   list.
2. Historical blockage records may remain in logs, but the dashboard should only
   show currently actionable blockages.
3. Replacement assignments should clear or supersede blockers from the failed
   assignment they replace.
4. The workflow state should expose enough blocker lifecycle information for the
   dashboard to distinguish active blockers from resolved history.

## Agent-Authored Retired State Can Delete Worktree Before Handoff Processing

In the `~/junk/squad` trial on 2026-08-05, `qa-procedure-writer-003` sent a
handoff for assignment
`hunt-the-wumpus-yob-003-crooked-arrows-and-wumpus-qa-procedure` with commit
`187500a449`. The commit existed locally and contained the expected QA artifact.

Before the squad leader processed the result, the agent status became:

- `state: retired`
- `detail: qa-procedure-writer-003 completed handoff`

`squadd` then reconciled that agent-authored retired state by killing the
session, removing the worktree, and deleting the branch:

- `retired-session-killed qa-procedure-writer-003`
- `git-worktree-removed qa-procedure-writer-003`
- `git-branch-deleted qa-procedure-writer-003`
- `role-retired-reconciled qa-procedure-writer-003`

When the squad leader later tried to record the handoff with
`squad_assign.sh result`, the helper refused because the sender worktree was
already gone.

Expected behavior:

1. Transient agents must not be able to trigger destructive cleanup by writing
   `state: retired`.
2. Destructive retirement cleanup should happen only after the squad leader or
   workflow explicitly runs `squad_retire.sh`.
3. `squadd` may display an agent-authored retired status as a request/claim that
   the agent is done, but it must not remove the session, worktree, or branch
   until the workflow confirms the handoff is merged, superseded, or otherwise
   durably resolved.
4. Handoff processing should be able to proceed from commit and handoff metadata
   without requiring the sender worktree when the commit is present.

## Squad Leader Message Box Does Not Submit On Return

In the web dashboard, the squad leader message box should support chat-style
submission behavior.

Expected behavior:

1. Pressing Return/Enter in the squad leader message box sends the message.
2. Pressing Shift-Return/Shift-Enter inserts a line break without sending.
3. The behavior should preserve the existing rule that sending text to the squad
   leader wakes the SL correctly.
