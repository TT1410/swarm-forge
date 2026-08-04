# Bug Fix Plan

## Phase 1: Make Workflow State Authoritative

Goal: eliminate helper/FSM disagreement before touching scheduling behavior.

Relevant `bugs.md` sections:

- `Packet review fields do not distinguish stale findings from current state`
- `Helpers enforce workflow from stale side metadata`
- `Architecture result does not propagate to member story packets`
- `Dashboard story state label is misleading`
- `Accepted analyst stories are not registered for approval`

1. Define the canonical workflow state model.
   - One authoritative source for story stage, current artifact iteration,
     current review verdict, batch membership, approval state, and active
     assignment.
   - Treat files like `active-batches/<kind>` as derived indexes only.
2. Add a workflow-state read/write API.
   - Helpers must call this API instead of independently interpreting side
     files.
   - It must expose "current effective state" distinct from historical
     iterations.
3. Update packet semantics.
   - Tie review verdicts to the artifact SHA they reviewed.
   - Preserve old rejected reviews in history.
   - Prevent stale `code_review: changes-requested` from appearing as the
     current verdict after a later fix supersedes it.
4. Add consistency validation.
   - Detect disagreement between packet fields, batch manifests, active-batch
     indexes, assignments, and approvals.
   - Report repair instructions rather than enforcing stale state.

## Phase 2: Make Helpers Mechanical Only

Goal: helpers validate and apply transitions, but never decide workflow.

Relevant `bugs.md` sections:

- `Helpers enforce workflow from stale side metadata`
- `Spawning an agent does not mark assignment in progress`
- `Review report merge checks blocked by untracked SL-side files`
- `Merge conflicts should route to a merger agent`
- `Kill swarm leaves dead agents and worktrees behind`

1. Refactor helpers:
   - `squad_batch_story.sh`
   - `squad_packet.sh`
   - `squad_assign.sh`
   - `squad_approval.sh`
   - spawn/retire helpers
2. Remove helper-side workflow enforcement.
   - No helper should reject a valid FSM-requested action because of stale
     derived metadata.
   - Terminal old batches must not block new batches.
3. Make helper updates transactional.
   - Batch add updates packet, manifest, indexes, and events atomically.
   - Assignment creation marks assignment in-progress/assigned immediately.
   - Retirement removes session, worktree, branch, and records durable status.
4. Fix merge-readiness mechanics.
   - Run dry-run merges in a clean temporary worktree/index.
   - Prevent untracked SL-side `.squad/reviews/*.md` files from blocking
     incoming review reports.

## Phase 3: Complete FSM Transitions

Goal: every workflow result has a deterministic next step.

Relevant `bugs.md` sections:

- `SL-created approval duplicates FSM-created approval`
- `FSM ignores SL-created replacement assignments`
- `Pending approval starves ready replacement assignments`
- `Any active agent causes global scheduler wait`
- `Old review assignment suppresses review of revised artifact`
- `FSM lacks code-review revision loop`
- `Architecture batch waits instead of starting with ready members`
- `Architecture result does not propagate to member story packets`
- `Senior-implementor output does not route back to architect`
- `Merge conflicts should route to a merger agent`

1. Fix approval gates.
   - FSM recommends approval requests.
   - SL creates them idempotently.
   - Web approvals wake the SL.
   - Approval gates never wait for all stories.
2. Fix per-story progression.
   - Accepted story can move immediately to Gherkin/QA procedure.
   - Accepted Gherkin can move immediately onward.
   - Accepted QA procedure can move immediately onward.
   - Code review rejection loops:

     ```text
     implementer -> cleaner -> code-reviewer -> implementer
     ```

3. Fix batch progression.
   - Hardener, QA, and architecture batches start when they have eligible
     members.
   - Batches do not wait for all stories.
   - Once started, a batch is closed.
   - Later eligible stories go to a later batch.
4. Fix architecture loop.

   ```text
   architect changes-requested
   -> senior-implementor
   -> architect review
   -> accepted or changes-requested
   ```

5. Add merger workflow.

   ```text
   merge blocked
   -> merger
   -> SL merge check
   -> merged or another merger pass
   ```

   No merge lock for now.

## Phase 4: Tooling Contracts

Goal: agents either use required tools correctly or block.

Relevant `bugs.md` sections:

- `Hardener proceeds without required hardening tools`
- `Cleaner proceeds without required CRAP and DRY tools`
- `Helper usage examples still produce wrong command shapes`

1. Fix role prompts and contracts.
   - Cleaner must use CRAP/DRY.
   - Hardener must use mutation, Gherkin parser/mutator, CRAP, and DRY.
   - Gherkin writer must use APS parser/IR DRY where required.
   - QA uses CRAP/DRY before handoff.
2. Add exact command examples.
   - Valid `squad_event.sh <state> <detail...>`.
   - Valid `squad_tool.sh require <tool> <source> <version>`.
   - No examples with agent id as first `squad_event.sh` argument.
3. Enforce missing-tool behavior.
   - If install/fetch is allowed, use `ensure`.
   - If not allowed, create a `blocked` handoff.
   - No "repository-only" fallback for required role tools.

## Phase 5: Liveness And Cleanup

Goal: no invisible stalls and no residue after kill.

Relevant `bugs.md` sections:

- `SL can sit idle at prompt without re-entering workflow`
- `Kill swarm leaves dead agents and worktrees behind`
- `Dashboard drops agent around handoff transition`

1. Consolidate daemon responsibilities under `squadd`.
   - Make `squadd` the only long-running squad daemon.
   - Move any remaining `squad_statusd` liveness/status responsibilities into
     `squadd`.
   - Remove `squad_statusd` as an independent daemon, including startup,
     shutdown, cleanup, and copied-script references.
2. Add SL watchdog inside `squadd`.
   - Watch SL tmux pane tail.
   - If unchanged for 60 seconds and at idle prompt, send `Run squad_next.sh.`
     plus the second return.
   - Do not spam during visible activity or required approval wait.
3. Improve agent liveness in `squadd`.
   - Use pane-tail activity and heartbeat/status.
   - Do not treat active visible work as stalled.
   - Recovery must be race-tolerant.
4. Fix kill cleanup.
   - Kill tmux sessions.
   - Retire all non-retired agents.
   - Remove transient worktrees.
   - Delete transient branches when safe.
   - Preserve/recover handoffs from `handoff_sent` agents.

## Phase 6: Dashboard Fixes

Goal: dashboard reflects authoritative workflow state and can drive approvals and
messages.

Relevant `bugs.md` sections:

- `Dashboard drops agent around handoff transition`
- `Dashboard assignment ordering and approval history are noisy`
- `Dashboard story state label is misleading`
- `Dashboard should allow sending free-form messages to SL`
- `SL-created approval duplicates FSM-created approval`

1. Render only active agents and active assignments.
2. Show story state from canonical FSM state, not stale packet fields.
3. Show newest assignments first.
4. Remove approval history section.
5. Add approval buttons from pending approval state.
6. Add SL message textarea.
   - Submit sends text to SL.
   - Send two returns with 100ms delay.

## Phase 7: Simulation And Regression Tests

Goal: prove the full workflow, not individual patches.

Relevant `bugs.md` sections:

- All workflow and helper bugs above.
- `SL-created approval duplicates FSM-created approval`
- `FSM ignores SL-created replacement assignments`
- `Pending approval starves ready replacement assignments`
- `Any active agent causes global scheduler wait`
- `FSM lacks code-review revision loop`
- `Architecture batch waits instead of starting with ready members`
- `Senior-implementor output does not route back to architect`
- `Helpers enforce workflow from stale side metadata`
- `Review report merge checks blocked by untracked SL-side files`

1. Expand simulator coverage.
   - Multiple stories.
   - Partial approvals.
   - Partial batches.
   - Reviewer rejects once then accepts.
   - Architecture reject/fix/re-review.
   - Merge conflicts with repeated merger passes.
   - Stalls: active-then-recovers and active-then-dark.
   - Agent slots filling and freeing.
2. Add helper regression tests.
   - No stale active-batch blocker.
   - No untracked review-file merge blocker.
   - Transactional batch add.
   - Retire removes worktree/branch.
   - Required tool missing causes blocked handoff.
3. Keep simulator out of normal `bb test`.
   - Add explicit command for Monte Carlo/regression simulation.

## Phase 8: End-To-End Trial

Goal: validate no partial fixes remain.

Relevant `bugs.md` sections:

- All sections in `bugs.md`; this phase is the integrated acceptance check.

1. Run a fresh HTW trial.
2. Watch:
   - approvals
   - handoffs
   - batch creation/closure
   - code review loops
   - hardener/QA/architecture loops
   - merger behavior
   - cleanup after kill
3. Do not accept manual SL creativity as success.
   - The FSM must recommend the correct actions.
   - The SL should execute, not invent workflow.
