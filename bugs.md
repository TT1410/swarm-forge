# Bugs

## Workflow advisor serializes story approvals

Observed in the HTW trial: when multiple stories were ready for story approval,
`squad_next.sh` created/requested one approval at a time. The web UI only showed
one pending story approval, and the SL did not request the next story approval
until the previous one was approved.

Expected behavior: all currently ready approval requests for a gate should be
created without waiting for earlier approvals to resolve, or the advisor should
continue returning `create_approval_request` for missing ready approvals before
switching to `request_user_approval`.

## Workflow advisor blocks pipelining behind unresolved approvals

Observed in the HTW trial: as individual stories were approved, the advisor did
not immediately send those approved stories to the next eligible agents. It
continued prioritizing remaining story approvals, so Gherkin/QA assignment work
was delayed until all stories were approved.

Expected behavior: the workflow should be pipelined per story. Once Story N is
approved, it should become eligible for its next workflow actions immediately,
even if Story N+1 still needs approval.

Likely cause: `squad_next.sh` globally sorts approval work ahead of downstream
story work by priority, so approval gates serialize the whole theme instead of
only gating the affected story.

## SL does not drain advisor actions to fill available slots

Observed in the HTW trial: the swarm was configured for five transient slots, but
only one or two transient agents were active while `squad_next.sh` still returned
eligible `create_assignment` actions.

Expected behavior: after completing a recommended advisor action, the SL should
keep calling `squad_next.sh` and executing returned actions until the advisor
returns `wait`, an approval wait, capacity full, or another real blocker. The
swarm should not remain underfilled while eligible assignments exist.

Likely cause: the SL treats a single `NEXT_ACTION` as one orchestration turn and
then waits, instead of draining the ready-action queue up to available capacity.

## Story packets can become stale after assignment merges

Observed in the HTW trial: Story 2 had a merged Gherkin assignment on disk, but
its story packet still reported `gherkin_assignment_state: pending`. As a result,
`squad_next.sh` recommended a redundant `gherkin-writer` revision assignment for
the same story.

Expected behavior: processing a handoff and merging an assignment should update
the story packet deterministically so advisor decisions reflect the accepted
artifact state.

Likely cause: packet refresh or artifact attachment is incomplete for some
merged assignment paths, leaving `squad_next.sh` to reason from stale packet
fields instead of the actual assignment state.

## SL misclassifies active dirty worktrees as blocked

Observed in the HTW trial: the SL reported a dirty worktree as blocked and in
need of recovery while the assigned transient agent still had recent pane
activity and was actively drafting its artifact. The agent later committed and
sent a normal handoff, proving it was not blocked.

Expected behavior: a dirty worktree should be treated as normal in-progress
work while the agent is live and recently active. Recovery should require
staleness evidence such as no recent heartbeat/status update, no pane-tail
change past the stale threshold, no pending handoff, and an active/session state
that remains inconsistent after the grace period.

Likely cause: the SL or advisor is treating dirty worktree presence as a
recovery signal without gating it behind liveness checks.

## Advisor repeats writer revisions after revised artifact is merged

Observed in the HTW trial: Story 3 received a Gherkin review with
`changes-requested`, then a revised Gherkin assignment `...-gherkin-r2` was
created, merged, and attached to the story packet. After that, `squad_next.sh`
still returned another `gherkin-writer` assignment `...-gherkin-r3` with reason
`Gherkin review requested changes`.

Expected behavior: once a revised artifact is merged and attached, the prior
rejected review should no longer drive another writer revision. The next
workflow action should be a fresh reviewer assignment for the revised artifact.

Likely cause: the packet keeps the old `changes-requested` review state as the
authoritative review state after the replacement artifact is attached, so the
advisor thinks the current artifact still needs writer correction instead of
review.
