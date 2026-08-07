# Bug Fix Plan

Scope: fix the bugs recorded in `bugs.md` from the August 7, 2026 swarm trial.
Each phase should be completed with focused automated tests before moving to the
next phase. No phase should leave a known half-fixed workflow path.

## Phase 1: Deterministic Packet Propagation

Status: implemented in this pass.

Addresses:

- `Merged Results Are Not Propagated To Story Packets`
- `FSM Does Not Record Merged Review Results`

Goal:

Make `squad_next` the deterministic source of all post-merge packet updates so
the Squad Leader cannot accidentally drop a completed artifact, review, or
batch result.

Implementation steps:

1. Add packet-repair candidates for merged direct result assignments:
   implementation, cleaner, hardener, QA, architecture, and senior implementer.
2. Add packet-repair candidates for merged batch assignments by reading the
   batch manifest and emitting one packet update per member.
3. Add packet-repair candidates for merger-resolved assignments. The repair must
   identify the original assignment phase and record the effective merge commit.
4. Add review-result repair candidates for merged review assignments:
   Gherkin review, QA procedure review, code review, and architecture review.
5. Parse or validate review decisions from durable review artifacts under
   `.squad/reviews/`.
6. Use the accepted merge commit as the story packet SHA for all post-merge
   packet updates.
7. Close or complete batch state after all member packet updates have been
   applied.
8. Ensure these repair actions run before downstream eligibility checks.

Verification:

1. Unit tests for direct merged assignment repair.
2. Unit tests for batch merged assignment repair with multiple members.
3. Unit tests for merger-resolved assignment repair.
4. Unit tests for accepted and changes-requested review repair.
5. Simulator scenario proving Story 1 QA and Story 6 QA procedure review do not
   remain stuck after merge.

## Phase 2: Architecture End-State Semantics

Status: implemented in this pass.

Addresses:

- `Architecture Review Flow Must End The Story`
- `FSM Does Not Record Merged Review Results`

Goal:

Make architecture the final review stage, with no accidental extra architect
loop after senior implementer.

Implementation steps:

1. Treat explicit accepted architecture disposition as eligible for done after
   configured architecture/final approval gates.
2. Treat non-blocking architecture recommendations as accepted, not as
   changes-requested.
3. Treat explicit blocking architecture changes-requested as the only trigger
   for senior implementer.
4. After senior implementer output is merged and recorded, route the story to
   done after configured final approval, not back through another architect
   pass.
5. Update state derivation so dashboard and status APIs report the same final
   phase/substate.

Verification:

1. FSM tests for `architect -> done`.
2. FSM tests for `architect -> senior implementer -> done`.
3. Tests proving optional recommendations do not spawn senior implementer.
4. Simulator scenario covering both architecture outcomes.

## Phase 3: Batch Formation Correctness

Status: implemented in this pass.

Addresses:

- `Batch Agents Start With Singleton Batches`
- `Batch Assignments Can Spawn Without Backing Batch Records`

Goal:

Ensure batch phases collect all currently eligible stories and never spawn a
batch agent without a valid backing batch manifest.

Implementation steps:

1. Change batch action selection so open batch membership additions outrank
   spawning the batch agent when eligible stories remain.
2. Add an explicit "batch is closed" transition before spawning the singleton
   batch agent.
3. Ensure `hardener`, `qa`, and `architect` use the same closed-batch rule.
4. Validate that `squad_assign.sh create-batch` refuses missing or empty batch
   manifests.
5. Teach `squad_next` to repair missing batch records before recommending
   assignment creation or spawn.
6. Preserve singleton capacity for hardener, QA, and architect agents without
   forcing singleton member lists.

Verification:

1. FSM tests where three stories become hardener-ready before spawn.
2. FSM tests where two stories become QA-ready close together.
3. FSM tests where architecture batches collect multiple QA-approved stories.
4. Negative tests for missing and empty batch manifests.
5. Simulator scenario showing one batch per phase when multiple stories are
   ready.

## Phase 4: Concurrent Safe Action Batching

Status: implemented in this pass.

Addresses:

- `Workflow Should Batch Independent Actions`
- `Automated Workflow Transitions Need Clear Reporting`

Goal:

Reduce Squad Leader serialization by allowing the workflow tool or daemon to
apply safe independent mechanical transitions and report them clearly.

Implementation steps:

1. Classify workflow actions as mechanical, SL-mediated, user-gated, blocking,
   or wait.
2. Add dependency keys so independent actions can be grouped while ordered
   chains stay serialized.
3. Apply safe mechanical action batches inside the workflow daemon or an
   explicit workflow command.
4. Report completed mechanical work as `APPLIED_TRANSITIONS`, never as
   `AUTO_ACTIONS`.
5. Keep exactly one imperative `NEXT_ACTION` for work that the SL must perform.
6. Update the SL prompt/contract to treat `APPLIED_TRANSITIONS` as
   informational.
7. Stop batching at approval gates, blockers, merge conflicts, capacity limits,
   or true wait.

Verification:

1. Tests proving independent packet repairs are batched.
2. Tests proving handoff lifecycle ordering is preserved.
3. Tests proving approval gates are not bypassed.
4. Tests proving output wording cannot be confused with commands for the SL.
5. Simulator scenario showing higher slot utilization than one-action-at-a-time
   processing.

## Phase 5: Review Cycle Policy

Status: implemented in this pass.

Addresses:

- `Reviewed Artifacts Should Have One Review Cycle`

Goal:

Prevent repeated reviewer loops for Gherkin and QA procedure artifacts.

Implementation steps:

1. Encode the review-cycle rule for Gherkin and QA procedure:
   `author -> reviewer -> {accept, or changes-requested -> author revision -> accept}`.
2. After a revision is merged following changes-requested, record the review
   gate as accepted instead of spawning another reviewer.
3. Prevent reviewer `r2`, `r3`, and later loops for the same artifact class
   after a completed revision.
4. Leave code review and architecture review policy explicit and separate until
   the desired rule is decided for those stages.
5. Add packet fields or iteration interpretation needed to distinguish initial
   review from post-review revision.

Verification:

1. Tests for Gherkin accept on first review.
2. Tests for Gherkin changes-requested then one author revision then accepted.
3. Tests for QA procedure changes-requested then one author revision then
   accepted.
4. Regression test proving no reviewer r2/r3 loop is recommended after the
   revision path.

## Phase 6: Cleanup And Liveness Reconciliation

Status: verified in this pass; existing cleanup paths already covered this behavior.

Addresses:

- `Retired Agent Tmux Sessions Leak`
- `Swarm Kill Leaves Active Statuses Stale`

Goal:

Make swarm kill and agent retirement leave no misleading live sessions or stale
active state.

Implementation steps:

1. Make agent retirement kill/remove the corresponding tmux session
   idempotently.
2. Make swarm kill remove all remaining transient-agent tmux sessions for that
   swarm.
3. Reconcile non-retired agent status files against tmux/process liveness during
   kill.
4. Reconcile assignment status files whose only active agent disappeared during
   kill.
5. Decide and document the terminal state name for killed work, such as
   `killed`, `terminated`, `failed`, or `retired`.
6. Ensure dashboards and status APIs filter or annotate stale historical state
   correctly.

Verification:

1. Tests for retiring one agent with an existing tmux session.
2. Tests for retiring one agent whose tmux session is already gone.
3. Tests for swarm kill with active agents and assignments.
4. Tests proving status/dashboard APIs do not show killed work as active.

## Phase 7: Telemetry Semantics

Status: implemented in this pass.

Addresses:

- `Command Telemetry Uses Failed For Expected Probe Failures`

Goal:

Separate command exit telemetry from lifecycle failure so expected red tests and
tool probes do not confuse humans or recovery logic.

Implementation steps:

1. Define lifecycle states as terminal/non-terminal and reserve `failed` for
   terminal assignment failure or handed-off blockers.
2. Extend `squad_run.sh` or its callers to mark expected-failure commands.
3. Record expected red tests, negative probes, and help/usage probes as
   non-terminal command results.
4. Keep unexpected command failures visible without changing lifecycle state
   unless the agent explicitly declares terminal failure or blockage.
5. Update dashboard and recovery logic to use lifecycle state, not raw command
   exit event labels.

Verification:

1. Tests for expected red test telemetry.
2. Tests for unexpected command failure telemetry.
3. Tests proving active agents that log expected failures are not treated as
   terminally failed.
4. Dashboard/state API tests for failed versus active-with-failing-command.

## Phase 8: Dashboard Usability And Story Dossiers

Status: implemented in this pass.

Addresses:

- `Approval Buttons Need Press Feedback`
- `Story Links Should Show Full Story Packet`
- `Story State Display Needs Phase And Substate`

Goal:

Make the dashboard accurately explain workflow state and make approvals/artifact
review safer to use.

Implementation steps:

1. Add press-state feedback to approval buttons and fire approvals on mouse up.
2. Replace story markdown-only links with a full story packet/dossier view.
3. Include packet fields, story artifact, Gherkin, QA procedure, review
   comments, blockers, assignment history, and iteration history in the dossier.
4. Derive a story phase, substate, and comment from the packet/state machine.
5. Show later workflow phases clearly: hardened, QA complete, architected, done,
   and blocked.
6. Ensure dashboard state uses the same derived state logic as CLI status.

Verification:

1. Browser/UI tests for approval mouse down/up behavior.
2. API tests for story dossier payloads.
3. Rendering tests for story packet/dossier links.
4. State derivation tests for representative story packets across all phases.

## Phase 9: Prompt Load Brainstorming And Cleanup

Status: deferred until after bug repair validation.

Addresses:

- `Worker Startup Prompt Load Is Too Heavy`

Goal:

Reduce worker startup context without weakening required protocol, role
behavior, or tool setup.

Implementation steps:

1. Inventory files each transient role reads at startup.
2. Separate universal transient protocol from role-specific guidance.
3. Remove workflow orchestration language from worker prompts when `squad_next`
   owns that decision.
4. Move broad project or engineering context behind explicit on-demand
   instructions.
5. Keep required tool startup instructions local to each role assignment.
6. Add fixtures or tests that show the startup context list for each role.

Verification:

1. Snapshot tests for generated assignment prompt structure.
2. Role prompt tests proving required protocol and tool instructions remain.
3. Manual review of one generated assignment per role.

## Completion Criteria

The bug-fix effort is complete when:

1. Every bug section in `bugs.md` is covered by tests or an explicit
   documented decision.
2. The simulator covers merged result propagation, review result propagation,
   batch formation, architecture completion, kill cleanup, and action batching.
3. A fresh HTW swarm trial does not require manual packet repairs, does not
   spawn missing-manifest batch agents, does not loop reviewer revisions, and
   does not leave stale active sessions/statuses after kill.
4. The dashboard accurately shows pending approvals, story phase/substate,
   blockers, and story dossiers.
