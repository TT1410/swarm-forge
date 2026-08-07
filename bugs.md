# Bugs

## Automated Workflow Transitions Need Clear Reporting

Some workflow transitions are mechanical bookkeeping and should not require
Squad Leader mediation. Examples include packet repair, direct-story packet
registration and approval, merged artifact attachment, approval request
creation/config auto-approval, ready spawn request creation, and possibly stale
lock cleanup.

When `squad_next.sh` or the workflow daemon applies these transitions
automatically, it should report them in retrospective wording such as
`APPLIED_TRANSITIONS`, not imperative wording such as `AUTO_ACTIONS`.

Expected behavior:

1. Mechanical transitions are applied by the workflow tool or daemon.
2. The Squad Leader is informed only after they have already been applied.
3. The report uses past-tense language, for example:
   `APPLIED_TRANSITIONS:`.
4. The Squad Leader prompt/contract says `APPLIED_TRANSITIONS` is
   informational and must not be executed.
5. The Squad Leader executes only the `COMMAND` for the current `NEXT_ACTION`,
   when one is present.

## Approval Buttons Need Press Feedback

The web dashboard approval buttons do not provide enough interaction feedback.
It is hard to tell that a click has been received.

Expected behavior:

1. Approval buttons visually invert while the mouse button is down.
2. The approval action is activated on mouse up, not mouse down.
3. If the pointer leaves the button before mouse up, the press feedback should
   clear and the approval should not fire.
4. The behavior should make accidental approvals less likely and make deliberate
   approvals visibly acknowledged.

## Workflow Should Batch Independent Actions

The workflow advisor currently emits one action at a time. This serializes
mechanical work through the Squad Leader and leaves agent capacity underused
even when many independent actions are ready.

Observed effect:

1. The squad capacity may be configured for 10 transient agents.
2. The swarm often runs far fewer agents because the advisor makes the Squad
   Leader process bookkeeping one command at a time.
3. Approved stories can wait for Gherkin/QA assignment creation while unrelated
   handoff recording, artifact attachment, approval creation, and retirement
   actions are processed serially.

Expected behavior:

1. The workflow should compute a safe set of ready actions, not only the single
   highest-priority action.
2. Independent mechanical actions should be batched or executed concurrently.
3. Dependent actions must remain ordered within a dependency chain.
4. Examples of independent actions include creating Gherkin and QA procedure
   assignments for different approved stories, attaching merged artifacts for
   different stories, creating multiple approval requests, and retiring multiple
   completed agents whose handoffs have already been processed.
5. Examples of ordered chains include record handoff result, verify/accept
   merge, complete handoff, attach artifact, then retire agent.
6. The workflow daemon should apply safe mechanical batches and report them as
   already-applied transitions.
7. Batching should stop at required user approval, blocker/merge conflict,
   capacity exhaustion, or true wait.

## Reviewed Artifacts Should Have One Review Cycle

The workflow currently allows repeated reviewer loops for reviewed artifacts.
In the observed swarm, Gherkin and QA procedure artifacts went through many
review/revision rounds such as `r6`, `r7`, and `r8`, consuming Squad Leader
attention and starving later stories.

Expected behavior:

1. A reviewed artifact gets at most one reviewer pass.
2. The flow is:
   `author -> reviewer -> {accept, or request changes -> author revision -> accept}`.
3. If the reviewer accepts, the artifact proceeds to the relevant approval gate.
4. If the reviewer requests changes, the author revises once.
5. After the author revision is merged, the workflow treats the reviewed artifact
   as accepted for that review gate and proceeds to the relevant approval gate.
6. The workflow must not spawn reviewer `r2`, `r3`, etc. for the same reviewed
   artifact after an author revision.
7. This policy applies at least to Gherkin and QA procedure artifacts. Whether
   code review and architecture review should follow the same one-cycle rule
   should be decided explicitly before changing those later-stage gates.

## Story Links Should Show Full Story Packet

Clicking a story on the web dashboard currently shows the story markdown
artifact. It would be more useful to show a full story packet/dossier with the
story and its related workflow artifacts.

Expected behavior:

1. `/artifact/story/<story-id>` should render a full story packet view rather
   than only the story markdown.
2. The view should include the story artifact, packet fields/current state,
   Gherkin artifact, QA procedure artifact, review comments, blockers, and
   assignment/iteration history where available.
3. Review sections should include at least the latest Gherkin review, QA
   procedure review, code review, and architecture review comments when those
   fields are present in the packet.
4. The view should make it possible to understand why a story is blocked,
   accepted, awaiting approval, or ready for the next stage without manually
   opening `.squad` files.

## Story State Display Needs Phase And Substate

The dashboard story table currently misses or obscures later workflow states
such as hardened, QA complete, and architected. Showing many separate columns is
not ideal because the workflow is roughly sequential.

Expected behavior:

1. The story table should show a broad current phase, a specific substate, and
   optionally a concise comment.
2. The broad phase should be derived from the story packet and reflect the main
   workflow position, for example: Story, Specification, Implementation,
   Hardening, QA, Architecture, Final, or Blocked.
3. The substate should capture the most relevant specific fact inside that
   phase, including parallel specification progress such as Gherkin complete,
   QA procedure complete, Gherkin review accepted, QA approved, or changes
   requested.
4. The comment should say what is waiting or what action is next, for example:
   `waiting for Gherkin review`, `waiting for QA procedure approval`,
   `waiting for hardening approval`, or `ready for final approval`.
5. Example display strings:
   - `Specification: QA complete; waiting for Gherkin review`
   - `Specification: Gherkin accepted; waiting for QA procedure approval`
   - `Implementation: cleaned; waiting for code review`
   - `Hardening: hardened; waiting for hardening approval`
   - `QA: QA complete; waiting for QA approval`
   - `Architecture: architected; waiting for architecture approval`
   - `Final: approved`

## Retired Agent Tmux Sessions Leak

After agents are retired, their tmux sessions can remain listed even though
their `.squad/agents/<agent>/status` says `retired` and their worktrees/branches
may have been removed.

Observed behavior:

1. `tmux list-sessions` still showed many old `swarmforge-*` agent sessions for
   retired agents.
2. The capacity logic does not appear to count those sessions when the agent
   status is `retired`, so this is not the apparent global-capacity cap.
3. The lingering sessions still clutter inspection, dashboard/debug tooling,
   and manual swarm management.

Expected behavior:

1. Retiring an agent should reliably kill/remove that agent's tmux session.
2. Killing a swarm should also remove any remaining transient-agent tmux
   sessions for that swarm.
3. Cleanup should be idempotent and tolerate already-missing sessions.
4. Tests should cover retired agents with lingering tmux sessions so the leak
   does not return.

## Swarm Kill Leaves Active Agent Statuses Stale

Killing the swarm can remove the tmux server and live agent processes while
leaving `.squad/agents/<agent>/status` files in active states such as
`running` or `starting`.

Observed behavior:

1. After the swarm was killed, `tmux` reported no server running on the swarm
   socket.
2. No live matching swarm processes remained.
3. The agent status files still showed `architect-002 state: running`,
   `implementer-009 state: running`, and `qa-023 state: starting`.
4. Assignment status files also still showed active work after kill, including
   `hunt-the-wumpus-006-testable-local-terminal-game-implementation`,
   `hunt-the-wumpus-architecture-r2`, `hunt-the-wumpus-hardener-r6`, and
   `hunt-the-wumpus-qa-r4` as `in_progress`.

Expected behavior:

1. Killing a swarm should mark all non-retired transient agents as terminated,
   killed, failed, or retired according to the chosen lifecycle semantics.
2. The dashboard and status tools should not report stale active agents after
   the tmux server and worker processes are gone.
3. Cleanup should reconcile agent and assignment status files against
   tmux/process liveness.
4. Batch status and dashboard views should not show work in progress after the
   corresponding agent process and tmux pane have been removed.

## Merged Results Are Not Propagated To Story Packets

Assignments can complete and merge without their result being recorded back
onto the affected story packets. This is not limited to hardener batches; it
applies to any direct or merger-resolved assignment whose output advances story
state, including implementation, cleaner, hardener, QA, architecture, and
senior-implementer work.

Observed behavior:

1. Story packets can reference an assignment or batch whose status is `merged`.
2. The corresponding story packet can still lack the phase commit field, such
   as `implementation_sha`, `cleaner_sha`, `hardener_sha`, `qa_sha`,
   `architecture_sha`, or `senior_implementer_sha`.
3. In the observed swarm, multiple hardener assignments completed, but no QA
   batch started because the stories still lacked `hardener_sha`.
4. Story 4's cleaner assignment was merged through a merger with merge commit
   `eb71a53e69`, but the story packet remained at `state: implemented` without
   `cleaner_sha`.
5. Story 1's QA batch assignment `hunt-the-wumpus-qa` was merged with result
   commit `a95f25163b`, but the story packet still had only
   `qa_batch: hunt-the-wumpus-qa` and no `qa_sha`, so architect was not eligible.
6. Story 6's QA procedure review assignment
   `hunt-the-wumpus-006-testable-local-terminal-game-qa-procedure-review-r2`
   merged an accepted review at merge commit `60853bcfc0`, but the story packet
   still showed `qa_procedure_review_state: pending` and the old
   `qa_procedure_review_target_sha: e4a40c049a`, so the story remained
   `specification_in_progress`.
7. Batch status files can remain open after the relevant assignments have been
   merged.
8. Because the phase SHA or review decision is missing, the FSM does not consider the story
   eligible for the next step.

Expected behavior:

1. When any direct or batch assignment is merged, including merger-resolved
   merges, the workflow must propagate the result to every affected story
   packet.
2. Each member should receive a command equivalent to:
   `squad_packet.sh record <story-id> <phase> <assignment-id> master <merge-sha>`.
3. If the merged assignment is a merger assignment, the `<assignment-id>` and
   `<merge-sha>` recorded should identify the original story phase and the
   effective merge commit that landed the resolved work.
4. Batch state should be closed/completed after all member results are
   propagated.
5. Next-step eligibility should follow automatically once the required phase
   result is recorded and any configured approval gate is satisfied or not
   required.
6. This should be handled as deterministic workflow bookkeeping, not left for
   the Squad Leader to discover manually.

## Batch Agents Start With Singleton Batches

The workflow can start singleton batch agents as soon as the first eligible
story is added to a batch, even when other stories are already ready or about
to become ready for the same batch phase.

Observed behavior:

1. The first QA batch, `hunt-the-wumpus-qa`, contained only story 1.
2. Story 2 was also QA-ready shortly afterward, but was put into a separate
   one-story QA batch, `hunt-the-wumpus-qa-r2`.
3. Hardening showed the same pattern: `hunt-the-wumpus-hardener`,
   `hunt-the-wumpus-hardener-r4`, and `hunt-the-wumpus-hardener-r5` each
   contained one story.
4. The same risk likely applies to architect batches because `architect` is
   handled by the same batch/singleton workflow.

Expected behavior:

1. Batch phases should collect all currently eligible stories before starting
   the batch agent.
2. The FSM should prefer filling an open batch over spawning that batch agent
   when more eligible stories exist.
3. Singleton-agent capacity limits should prevent concurrent hardener, QA, or
   architect agents, but should not force one-story batches.
4. Tests should cover hardener, QA, and architect batching with multiple
   eligible stories.

## Batch Assignments Can Spawn Without Backing Batch Records

The workflow can spawn a batch agent whose assignment references a batch
manifest that does not exist under `.squad/batches/<batch-id>/`.

Observed behavior:

1. `hunt-the-wumpus-hardener-r6` was assigned to `hardener-006`.
2. The assignment told the agent to use
   `.squad/batches/hunt-the-wumpus-hardener-r6/manifest`.
3. No `.squad/batches/hunt-the-wumpus-hardener-r6` directory existed.
4. The hardener reported `blocked` with detail:
   `missing batch manifest and story packets for hunt-the-wumpus-hardener-r6`.
5. `hunt-the-wumpus-qa-r4` was also spawned with an assignment referencing
   `.squad/batches/hunt-the-wumpus-qa-r4/manifest`, but no corresponding batch
   directory existed when inspected.

Expected behavior:

1. A batch assignment must not be created or spawned until the batch record and
   manifest exist.
2. `squad_next` should prefer creating or repairing the batch record before
   recommending `squad_assign.sh create-batch` or `squad_spawn_request.sh`.
3. `squad_assign.sh create-batch` should fail loudly if the requested batch
   manifest is missing or empty.
4. The dashboard should show this as a workflow bookkeeping blocker if it ever
   occurs.

## Command Telemetry Uses Failed For Expected Probe Failures

Task event logs record some expected red-test runs, exploratory probes, tool
help checks, and intermediate verification failures as lifecycle state
`failed`, even when the agent continues working and later succeeds.

Observed behavior:

1. Implementers logged `failed` for expected red tests such as
   `unit failed: expected failing story 6 tests exit 1` and
   `test failed: unit-red exit 1`.
2. Gherkin tool probing logged `failed` for help/usage exits such as
   `inspect failed: gherkin parser help exit 2` and
   `inspect failed: ir dry checker usage exit 2`.
3. QA and hardener agents logged several `failed` intermediate checks while
   continuing to investigate, fix, or complete the assignment.
4. These events can make dashboards, recovery logic, and humans interpret an
   active agent as having failed when the failure was expected or non-terminal.

Expected behavior:

1. `failed` should mean the assignment or agent has reached a terminal failure
   or has handed back a blocker.
2. Expected red tests, negative probes, and intermediate failing checks should
   use non-terminal telemetry such as `running` with detail, or a separate
   command-result field that does not alter lifecycle meaning.
3. `squad_run.sh` should distinguish expected failure from unexpected failure
   when the caller marks a command as an expected red/probe step.
4. Dashboard and recovery decisions should use lifecycle state, not raw command
   exit telemetry, to determine whether an agent has failed.

## FSM Does Not Record Merged Review Results

The FSM has repair rules for missing story registration and missing artifact
attachments, but it does not have a repair rule that converts a merged review
assignment into a story packet review decision.

Observed behavior:

1. Review assignments can reach `state: merged` with a durable review artifact
   under `.squad/reviews/...md`.
2. `squad_next` does not inspect merged review assignments and recommend a
   `squad_packet.sh review ...` command.
3. In the observed swarm, Story 6's accepted QA procedure review r2 was merged,
   but the story packet remained `qa_procedure_review_state: pending`.
4. The SL can drop this step because the FSM only tells it to complete the
   handoff/merge lifecycle, not to record the review decision in the packet.

Expected behavior:

1. `squad_next` should emit a deterministic action such as
   `NEXT_ACTION: record_review_result` for merged review assignments whose
   decision is not yet reflected in the story packet.
2. The action should generate:
   `squad_packet.sh review <story-id> <kind> <accepted|changes-requested> <assignment-id> master <merge-sha>`.
3. It must cover Gherkin review, QA procedure review, code review, and
   architecture review.
4. It should derive or validate the decision from the durable review artifact
   and the assignment role/template.
5. It should use the merge commit as the packet review SHA, preserving the
   reviewed target SHA semantics already implemented by `squad_packet.sh`.
6. For architecture reports, non-blocking recommendations must not be treated
   as `changes-requested`. Senior implementer should be triggered only by an
   explicit blocking `changes-requested` architecture disposition. Accepted
   architecture with optional recommendations should mark the story done after
   any configured architecture/final approval gates are satisfied. If
   architecture requests blocking changes, the story should route through
   senior implementer and then be marked done after any configured final
   approval gate is satisfied. The intended architecture-stage flow is
   `architect -> done` or `architect -> senior implementer -> done`.
7. This repair action should run before downstream eligibility checks so
   stories cannot remain blocked by a merged-but-unrecorded review.

## Brainstorm: Worker Startup Prompt Load Is Too Heavy

Worker agents currently read many prompt, protocol, project, workflow, and
role-support files at startup. Some of that context is necessary, but some may
be overly broad, redundant, stale, or irrelevant to the specific role.

This is a brainstorming issue for later, not an immediate trial blocker.

Questions to explore:

1. Which files are truly required for every transient agent?
2. Which files should be role-specific only?
3. Which project or engineering context should be loaded on demand rather than
   at startup?
4. Are any prompts still carrying workflow direction that should belong only to
   the FSM and `squad_next`?
5. Can the startup set be reduced to a compact core protocol, the role prompt,
   the assignment, required tool instructions, and handoff rules?

Candidate direction:

1. Create a short universal transient protocol file.
2. Keep each role prompt focused on local role behavior and required tools.
3. Remove broad orchestration guidance from worker prompts.
4. Move optional background material behind explicit role instructions to read
   it only when relevant.
5. Add tests or fixtures that show each role's startup context list so prompt
   load changes are visible.
