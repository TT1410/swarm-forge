# Bug Fix Plan

This plan fixes the swarm issues in dependency order. Each phase should leave
the system internally consistent before the next phase starts. Do not mix phases
unless a later phase exposes a defect in an earlier phase's contract.

## Phase 1: Canonical Workflow State And Helper Contracts

Fixes:

- `FSM Must Be The Workflow Authority`
- `Approval Requests Must Be Idempotent By Semantic Gate`
- `Squad Next Emits Invalid Batch Assignment Commands`
- `Bulk Operations Are Missing`
- `Direct SL-Created Stories Are Not Supported`
- `Generic Ready Assignments Are Not Spawned Reliably`
- `Finish-Current Recommendations Can Be Stale`

Work:

1. Define the helper/FSM contract in code and docs: `squad_next.sh` recommends
   workflow actions, while helpers validate and mutate durable state.
2. Preserve the clarified SL exception: the SL may create workflow records
   directly only for explicit user instruction or artifact quality-control
   decisions.
3. Make approval requests idempotent by `(target_kind, target_id, gate)`.
4. Add `squad_assign.sh create-batch ...` and stop emitting story-shaped
   `batch` commands.
5. Add explicit-id bulk helpers for repeated story and approval operations.
6. Add helper support for direct SL-authored stories as already approved.
7. Add a generic ready-assignment scan to `squad_next.sh`; explicit ready
   assignments outrank ordinary stage-derived spawn candidates.
8. Require `finish_in_process_handoff` recommendations to include a concrete
   handoff id/path. Do not emit bare `done_with_current.sh`.

Validation:

- Unit tests for approval dedupe by semantic gate.
- Unit tests for `create-batch` command shape.
- FSM/simulator cases for direct story creation, explicit ready assignments, and
  stale `done_with_current` prevention.

## Phase 2: Assignment Lifecycle, Result Manifests, And Handoff Integrity

Fixes:

- `Lifecycle States Are Used For Step Telemetry`
- `Assignment And Agent State Are Not Synchronized`
- `Handoff Sending Is Not Cleanly Idempotent`
- `Result Handoff Validation Is Too Weak`
- `Retry Loops Do Not Reset Downstream State`

Work:

1. Enforce canonical assignment states: `created`, `in_progress`,
   `handoff_sent`, `result_received`, `merged`, `blocked`, `superseded`, and
   `retired`.
2. Remove `complete` from transient agent lifecycle. Agents use
   `handoff_ready` when work is ready to hand off.
3. Treat `failed` as terminal. Recoverable command failures stay in
   `running` detail or `squad_run` telemetry.
4. Update spawn fulfillment to mark assignments `in_progress` with agent id and
   session.
5. Make handoff send idempotent by `(task, sender, recipient, commit)`.
6. Require every handoff to include a result manifest tying assignment, agent,
   template, commit, and artifacts together.
7. Validate result handoffs by both branch/worktree lineage and result manifest.
8. Implement supersession semantics for retries: downstream artifacts from the
   prior chain are marked `superseded`, not erased.

Validation:

- Tests proving wrong-assignment reachable commits cannot advance a story.
- Tests proving duplicate handoff sends return the existing handoff identity.
- FSM/simulator retry loops for reviewer changes and implementer fixes.
- Tests for terminal `failed` and absence of transient `complete`.

## Phase 3: Throughput, Capacity, Cleanup, And Recovery

Fixes:

- `Capacity And Throughput Are Poorly Scheduled`
- `Handoff-Sent Agents Are Not Retired Promptly`
- `Swarm Shutdown Leaves In-Flight Worktrees Behind`
- `Stall Recovery Is Too Sensitive And SL Wakeups Are Weak`

Work:

1. Count live tmux sessions against `max_transient_agents`; agents in
   `handoff_sent` do not count once their handoff has actually been sent.
2. Prioritize actions to free completed slots, then fill empty slots with ready
   work.
3. Retire agents promptly after merged handoffs unless merge-blocked preservation
   applies.
4. On swarm kill, delete all non-merge-blocked transient worktrees and branches
   unconditionally.
5. Use a 60 second SL idle threshold based on no pane/status activity, not raw
   elapsed time alone.
6. Treat busy pane tails as liveness even when no `squad_event` heartbeat is
   written.
7. Tighten SL wakeup text: run `squad_next.sh`, execute its `COMMAND`, then
   continue the advisor loop until waiting, blocked, or user-gated.

Validation:

- Monte Carlo simulator runs that demonstrate full slot usage without exceeding
  capacity.
- Shutdown tests proving worktrees/branches are removed except preserved
  merge-blocked cases.
- Watchdog tests for active pane tail suppression and SL wakeup wording.

## Phase 4: Tool Provisioning And Required Tool Enforcement

Fixes:

- `Tool Installation Policy Is Contradictory`
- `APS Tool Source Identity Is Inconsistent`
- `APS Tool Usage Is Not Enforced`
- `Required Tool Failures Are Hidden`
- `Role Prompts Must Require Tool Loading Up Front`

Work:

1. Make the constitution/tool table the only source of required tool identities.
2. Generate role startup tool instructions from that table.
3. Let agents run `squad_tool.sh ensure` as needed for required tools.
4. Canonicalize APS tools to
   `github.com/unclebob/Acceptance-Pipeline-Specification`.
5. Make `squad_tool.sh require` reject source mismatches clearly.
6. Block merge when required tool evidence is missing.
7. Require Gherkin handoffs to include both command transcript evidence and
   normalized IR artifacts, including IR DRY reports and tool metadata.
8. Make missing/install-failed tools canonical assignment blockers and dashboard
   blockers.

Validation:

- Tests for tool table driven prompt generation.
- Tests for APS source mismatch rejection.
- Handoff validation tests that reject missing Gherkin parser/IR evidence.
- Dashboard data tests for tool blockers.

## Phase 5: Late-Stage Workflow And Merger Role

Fixes:

- `Architect And Batch Flow Ordering Is Wrong Or Incomplete`
- `Merge Conflicts Need A Merger Workflow`
- remaining late-stage portions of `Retry Loops Do Not Reset Downstream State`

Work:

1. Encode the late-stage FSM as `QA -> architect <-> senior-implementer -> done`.
2. Ensure ready batches do not wait for unrelated stories or batches.
3. Route senior-implementer output back to architect until the architect accepts.
4. Add the special merger role outside normal transient-agent capacity.
5. On merge conflict, preserve required branches/worktrees and assign merger.
6. Have merger merge against the SL current integration state, run required test
   suites, and hand back an unconflicted result.

Validation:

- FSM/simulator cases for QA-to-architect, architect rejection, senior
  implementation, architect acceptance, and done.
- Merge-conflict integration test that spawns merger and preserves required
  worktrees/branches.

## Phase 6: Dashboard Canonical Rendering And Controls

Fixes:

- `Dashboard Refresh Destroys SL Message Drafts`
- `Dashboard Needs Artifact Links And Renderers`
- `Dashboard Needs Live Agent Pane Windows`
- `Dashboard Agent And Assignment Filtering Uses Stale State`
- `Dashboard Story State Labels Are Confusing`
- `User Messages From Web Dashboard Need Correct SL Delivery`
- `Blockers Must Be First-Class Dashboard Items`

Work:

1. Preserve unsent SL message textarea content in browser memory across polling;
   clear it only after successful submit.
2. Keep polling active while typing and preserve text on backend failure.
3. Deliver SL messages with the required two returns and 100 ms delay.
4. Wake the SL after message delivery.
5. Add Tier 1 artifact renderers: theme, story, Gherkin, QA procedure, review,
   and blocker.
6. Make pending approval artifact names link to the renderer.
7. Add read-only live tmux pane windows for active agents.
8. Show only current canonical assignments in active views; hide superseded
   assignments by default.
9. Sort assignment lists newest first and remove approval history from the normal
   dashboard.
10. Add a top-level blockers section.
11. Use stage labels: `specified`, `gherkin approved`, `qa approved`,
    `implemented`, `cleaned`, `code reviewed`, `architect approved`, `hardened`,
    and `done`.

Validation:

- Browser tests for textarea preservation, submit success clearing, and submit
  failure retention.
- Browser tests for approval links, Tier 1 renderers, newest-first assignment
  ordering, hidden superseded assignments, and blocker section.
- Live pane endpoint/UI tests with mocked tmux output.

## Phase 7: Role Prompt Quality

Fixes:

- `Analyst Prompt Lacks Story Writing Principles`
- `Architect Prompt Needs Review-Oriented Principles`
- `Role Prompts Must Require Tool Loading Up Front`

Work:

1. Add I.N.V.E.S.T. story guidance to the analyst prompt.
2. Rephrase architect principles as advisory review criteria only.
3. Include the Dependency Rule in architect review guidance.
4. Include "low level is close to IO; high level is far from IO".
5. Strengthen architect guidance to recommend splitting large multi-responsibility
   modules into individual well-named modules with single responsibilities.
6. Generate standard required-tool startup blocks for every role prompt from the
   constitution/tool table.

Validation:

- Prompt snapshot or content tests for analyst, architect, cleaner, hardener,
  Gherkin, QA, and other tool-using roles.

## Phase 8: End-To-End Trial Readiness

Work:

1. Run focused unit and simulator suites from the prior phases.
2. Run at least ten Monte Carlo simulator scenarios with varied story counts,
   handoff delays, approval delays, and stall behaviors.
3. Verify at least one scenario fills all available slots, frees slots
   continuously, and starts pending work as slots free.
4. Run one HTW-style dry trial and confirm:
   - no duplicate approvals,
   - no invalid batch commands,
   - no hidden tool blockers,
   - no stale dashboard assignments,
   - no over-capacity transient agents,
   - no orphaned non-preserved worktrees after kill.
5. Update `bugs.md` with any new findings before another live swarm trial.
