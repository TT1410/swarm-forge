# Bugs

Current scope: throughput gaps observed during the August 8, 2026 swarm trial.

## Workflow Throughput

### Handoff Merge Processing Is Still Serial

The workflow now uses `APPLIED_TRANSITIONS` for safe bookkeeping and
`CONCURRENT_ACTIONS` for independent work, but git handoff intake remains a
single-file pipeline.

Observed behavior:

1. Eight specification agents handed off work: four Gherkin writers and four QA
   procedure writers.
2. The Squad Leader processed each handoff through the same serial sequence:
   `record_assignment_result -> merge-ready -> accept-merge -> attach_story_artifact`.
3. Each artifact attachment was applied automatically, but only after its
   individual merge completed.
4. The log showed repeated `APPLIED_TRANSITIONS: 1` blocks for
   `attach_story_artifact`, rather than one larger post-merge batch.
5. This appears correct for current git safety, but it is now the main
   throughput bottleneck after concurrent spawn and assignment creation.

Goal:

Improve throughput without corrupting the integration branch. Options to
investigate include a deterministic merge queue, a merger-agent pipeline, or a
tool-owned batch merge planner that can identify independent non-conflicting
handoffs while keeping conflicting merges serialized.

### Retirements Lag After Completed Handoffs

After all eight specification handoffs were merged and their artifacts attached,
the transient agents still remained registered.

Observed behavior:

1. `squad_next.sh --apply-mechanical` correctly emitted a concurrent batch with
   eight `retire_agent` commands plus eight reviewer assignment creation
   commands.
2. The old specification agents still appeared in `.swarmforge/roles.tsv`.
3. Their assignments were already `merged` and their story packets were already
   updated.
4. Until retirements execute, the dashboard and role registry carry stale
   completed agents and the system is harder to scan.

Goal:

Favor throughput by making completed-agent retirement happen promptly. Consider
whether retirements can be daemon-applied mechanical transitions once the
handoff is completed, merged, and reflected in durable story state.

Additional live observation:

1. A later run had 25 completed handoff files in
   `.swarmforge/handoffs/inbox/completed`.
2. The corresponding old agents had been removed from `.swarmforge/roles.tsv`,
   so those historical completed handoffs were not directly consuming capacity.
3. The same run showed a failed `gherkin-writer` still listed in the workflow
   wait report even though failed agents are excluded from capacity accounting.
4. Wait-state liveness and capacity accounting should use the same definition
   of an active transient worker, so failed workers do not make the Squad Leader
   wait instead of retrying, reporting a blocker, or retiring stale state.

### Assignment Creation Still Requires Squad Leader Instruction Synthesis

The workflow advisor now batches reviewer assignment creation in
`CONCURRENT_ACTIONS`, but the Squad Leader still has to create instruction files
before running each `squad_assign.sh create ... <instructions-file>` command.

Observed behavior:

1. The advisor emitted all reviewer assignment creation commands together.
2. The commands still contained `<instructions-file>`.
3. The Squad Leader had to synthesize the instruction files before executing
   the batch.
4. This prevents reviewer assignment creation from becoming a fully automatic
   `APPLIED_TRANSITIONS` step.

Goal:

Improve throughput by moving deterministic assignment-instruction generation
into a helper or workflow-owned renderer. Then assignment creation for standard
roles could become either auto-applied or at least a direct concurrent command
without manual file preparation by the Squad Leader.

### Assignment Creation Does Not Queue Spawn Requests

Creating an assignment does not automatically queue the corresponding worker
spawn request.

Observed behavior:

1. `squad_assign.sh create ...` writes the assignment and marks it `created`.
2. A later `squad_next.sh` call must notice the created assignment and emit a
   separate `squad_spawn_request.sh ...` command.
3. `squad_spawn_request.sh` writes a request under `.squad/spawn-requests/new/`.
4. `squadd` then polls that queue and calls `squad_spawn.sh` after checking
   capacity and singleton limits.
5. The separation preserves daemon-owned spawning, but it creates an avoidable
   extra Squad Leader action for every assignment.

Expected behavior:

Assignment creation should be able to enqueue the spawn request mechanically
when the assignment is immediately eligible. The request should still go through
`.squad/spawn-requests/new/`, and `squadd` should remain the component that
actually calls `squad_spawn.sh` so capacity and singleton rules remain
centralized.

### Successful Work Can Leave Misleading Failed Agent Status

One QA procedure writer showed a terminal-looking failure status even though its
assignment was merged and the artifact was attached to the story packet.

Observed behavior:

1. `qa-procedure-writer-004` status was:
   `state: failed`
2. The detail was:
   `handoff failed: complete current task exit 1`
3. Its assignment was nevertheless `merged`.
4. Its QA procedure artifact was attached to the story packet.
5. The workflow correctly treated the agent as retirable, but the status could
   mislead dashboards, humans, or future recovery logic.

Goal:

Keep lifecycle state consistent with durable workflow outcome. If a handoff is
eventually merged and packet-applied, the agent should not remain displayed as
failed except perhaps as historical telemetry. The active/current status should
communicate that the work was accepted and the agent is ready for retirement.

## Review Cycle Correctness

### Merged Review Results Can Be Skipped Before Spawning A Second Reviewer

The one-review-cycle policy is only partially enforced. Some reviewed artifacts
received a second reviewer assignment while the first reviewer result was
already merged but not reflected in the story packet.

Observed behavior:

1. Story `hunt-the-wumpus-02-turn-loop-movement-and-hazards` has:
   - `hunt-the-wumpus-02-turn-loop-movement-and-hazards-qa-procedure-review`
     in `state: merged`
   - `hunt-the-wumpus-02-turn-loop-movement-and-hazards-qa-procedure-review-r2`
     also in `state: merged`
   - `hunt-the-wumpus-02-turn-loop-movement-and-hazards-qa-procedure-review-r3`
     in `state: result_received`
   - packet field `qa_procedure_review_state: pending`
2. Story `hunt-the-wumpus-04-game-end-and-same-setup-replay` has:
   - `hunt-the-wumpus-04-game-end-and-same-setup-replay-qa-procedure-review`
     in `state: merged`
   - `hunt-the-wumpus-04-game-end-and-same-setup-replay-qa-procedure-review-r2`
     also in `state: merged`
   - `hunt-the-wumpus-04-game-end-and-same-setup-replay-qa-procedure-review-r3`
     also in `state: merged`
   - packet field `qa_procedure_review_state: pending`
3. The workflow therefore spawned a second QA procedure reviewer before
   recording the first merged review decision.
4. This wastes agent capacity and violates the intended review cycle:
   `author -> reviewer -> {accept, or request changes -> author revision -> accept}`.

Expected behavior:

1. Merged review assignments must be recorded into the story packet before any
   downstream eligibility check can create another reviewer assignment.
2. If the first review accepted the artifact, the workflow should proceed to the
   approval gate.
3. If the first review requested changes, the workflow should send the artifact
   back to the author for one revision.
4. After the author revision is merged, the workflow should auto-accept that
   artifact for the reviewed gate under the one-review-cycle policy.
5. The FSM must not create `*-review-r2` reviewer assignments just because the
   packet is missing a review result that already exists in merged assignment
   state.

Goal:

Improve throughput and correctness by making review-result recording a required
mechanical repair that runs before reviewer-assignment eligibility. This should
eliminate redundant reviewer agents and unblock stories as soon as durable
review results exist.

### Artifacts Can Loop Through Revisions Or Reviews Up To R11

The workflow did not merely create one extra review. Some artifacts were sent
through repeated writer/reviewer rounds up to `r11`.

Observed behavior:

1. Story `hunt-the-wumpus-01-startup-and-cave` had QA procedure writer
   assignments from the original through `qa-procedure-r11`, with `r11` still
   `in_progress` at teardown.
2. Story `hunt-the-wumpus-02-turn-loop-movement-and-hazards` had QA procedure
   reviewer assignments from the original through `qa-procedure-review-r11`,
   with `r11` still `in_progress` at teardown.
3. Story `hunt-the-wumpus-03-crooked-arrows` had Gherkin writer assignments
   from the original through `gherkin-r11`, with `r11` still `in_progress` at
   teardown.
4. Story `hunt-the-wumpus-04-game-end-and-same-setup-replay` had Gherkin writer
   assignments from the original through `gherkin-r11`, with `r11` still
   `in_progress` at teardown, and QA procedure review reached `r5`.
5. Story `hunt-the-wumpus-05-compact-command-syntax` had Gherkin writer
   assignments from the original through `gherkin-r9`, with `r9` still
   `in_progress` at teardown.
6. The story packets generally recorded only the original review iteration or a
   later accepted result, not the full repeated loop history.

Expected behavior:

The workflow should enforce the one-review-cycle rule for reviewed artifacts:
`author -> reviewer -> {accept, or request changes -> author revision -> accept}`.
It should not keep spawning new writer or reviewer revisions after the allowed
cycle is complete. If a review result cannot be recorded into packet state, that
should become a blocker rather than an unbounded revision/review loop.

## Dashboard

### Agent Tmux Viewer Shows Input Box And Pending Input

Clicking an agent in the web dashboard opens a tmux window viewer that displays
the command input box at the bottom.

Observed behavior:

1. The rendered tmux viewer included the input area from the agent session.
2. The viewer also exposed pending input text, such as `Explain this codebase`.
3. This makes the dashboard look like an interactive terminal and can expose
   stale or accidental prompt text that is not part of the agent's useful
   output history.

Expected behavior:

The dashboard agent tmux viewer should be read-only output. It should not render
the input box, and it should not render the contents of pending input.

## Cleanup

### Squad Teardown Leaves Active Metadata Behind

After the squad was torn down, the live processes were gone but the durable
metadata still described active workers.

Observed behavior:

1. The tmux socket reported no server running.
2. No active `squadd`, Squad Leader, or worker agent processes were found.
3. `.swarmforge/roles.tsv` still listed the Squad Leader and five transient
   agents.
4. Agent status files still showed four agents as `running` and one as
   `handoff_sent`.
5. `.swarmforge/handoffs/inbox/new/` still contained one handoff.

Expected behavior:

Squad teardown should leave the system in a coherent terminal state. If the
processes are killed, active role records and agent statuses should be marked or
cleaned consistently, and any unprocessed handoffs should be either preserved
with explicit recovery metadata or moved to a clearly named cleanup/recovery
location.
