# Squad Branch Design Notes

## Intent

The `squad` branch explores a dynamic SwarmForge workflow centered on one
persistent squad leader agent. The squad leader accepts tasks from the user,
breaks them down, delegates bounded work to transient agents, coordinates
review and quality assurance, and reports completion back to the user.

Unlike the fixed pack branches, the transient agents are created for a specific
assignment and retired when their work is complete.

## Persistent And Transient Roles

The persistent topology should stay small:

```conf
window squad-leader codex master task
```

The squad leader is the only persistent role and the only role that talks
directly to the user. It owns task clarification, planning, assignment,
spawning, review coordination, merge decisions, QA coordination, and final
reporting.

The squad leader owns theme splitting. A theme is a larger specification that
must be decomposed into independently deliverable stories. The squad leader may
spawn an investigator to gather source material or technical context, but it
does not delegate final theme-to-story decomposition or approval framing.

Transient agents should be created from templates such as:

- `implementer`
- `reviewer`
- `qa`
- `refactorer`
- `investigator`
- `fixer`

Transient agents receive narrow assignments, work in dedicated worktrees or
branches, and communicate through handoffs. They should not talk directly to
the user, spawn other agents, reinterpret the parent task, or broaden their
assignment without a squad leader handoff.

## Invisible Agents

Transient agents may run in detached tmux sessions without opening OS terminal
windows. Each still has a real tmux session, window, pane, PTY, environment,
scrollback, and process tree. "Invisible" means no Terminal.app, iTerm, Ghostty,
or other terminal surface is opened for that session by default.

This model keeps the squad leader visible while allowing transient agents to
run in the background. Operators can still attach to or inspect a transient
session when debugging.

## Status Reporting

Because transient agents are invisible, status reporting is a first-class
workflow requirement.

Status should be event-driven first, with timer-based reminders as a backstop.
Transient agents and helper scripts should write structured status and event
records. The squad leader interprets those records and reports concise status
to the user.

Status, heartbeat, and telemetry records should not use the handoff mechanism.
Handoffs represent durable work transfer with queue lifecycle semantics:
queued, accepted, in process, and completed. Heartbeats are telemetry, not work.
Sending them through handoffs would pollute task queues and create false
completion obligations.

Use separate files instead:

```text
.squad/agents/<agent-id>/heartbeat
.squad/agents/<agent-id>/status
.squad/tasks/<task-id>/events.log
```

This keeps three channels distinct:

- handoffs for durable work transfer
- heartbeat and status files for current liveness and state
- event logs for meaningful progress history

The timer daemon should not invent user-facing reports. It should wake the
squad leader when a report is due, when an agent appears stale, or when a
notable state transition is detected.

Recommended user reporting triggers:

- after initial planning and agent spawning
- every few minutes while work is active
- immediately on blocker, failure, exited agent, or merge conflict
- immediately when the whole user task completes
- after a long quiet period, even if nothing meaningful changed

The squad leader should suppress noisy "still working" reports when there has
been no meaningful change unless the quiet period itself is noteworthy.

## Squad Status Daemon

A future `squad-statusd` should be narrow:

- monitor task, agent, heartbeat, and event files
- check whether tmux sessions and panes still exist
- check whether pane processes are dead
- detect stale agents
- detect long periods with no status events
- send generic wake-up notifications to the squad leader

It should not make planning decisions, merge decisions, QA decisions, or
technical interpretations of agent prose. Those remain squad leader
responsibilities.

## Helper Script Convention

Squad helper commands should follow the existing SwarmForge script convention.
Agent-facing and user-facing commands should have stable `.sh` entrypoints,
with nontrivial implementation in matching `.bb` files.

Examples:

```text
squad_spawn.sh     -> squad_spawn.bb
squad_event.sh     -> squad_event.bb
squad_run.sh       -> squad_run.bb
squad_retire.sh    -> squad_retire.bb
squad_status.sh    -> squad_status.bb
squad_statusd.sh   -> squad_statusd.bb
squad_theme.sh     -> squad_theme.bb
squad_tool.sh      -> squad_tool.bb
```

The `.sh` wrapper is the command surface placed on `PATH` for agents. The
Babashka implementation should own structured parsing, state-file updates,
atomic writes, time handling, process coordination, and tests.

Not every script needs both files. Internal libraries and daemons may be `.bb`
only when agents do not call them directly. Terminal and OS integration glue may
remain shell-only when shell is the natural boundary.

## Shared Tool Cache And Transient Launch Root

Transient squad agents may need heavyweight task tools such as mutation, CRAP,
DRY, and APS commands. Installing those tools separately in every transient
worktree would waste time and make quality-gate assignments unnecessarily slow.

Use a shared project-local tool cache:

```text
.swarmforge/tools/
  bin/
  src/
  cache/
  manifests/
  locks/
```

The launcher should set:

```text
SWARMFORGE_PROJECT_ROOT=<project-root>
SWARMFORGE_WORKTREE=<assigned-worktree>
SWARMFORGE_TOOL_CACHE_DIR=<project-root>/.swarmforge/tools
PATH=$SWARMFORGE_TOOL_CACHE_DIR/bin:<worktree>/swarmforge/scripts:$PATH
```

To make the shared cache writable without per-agent escalation, transient
agents should be launched with the project root as their sandbox/project root,
then instructed to immediately `cd` into their assigned worktree before doing
task work.

Generated transient prompts must state:

- the project root
- the assigned worktree
- the shared tool cache directory
- the requirement to `cd` to the assigned worktree before task work
- the prohibition on editing the project root except through approved squad
  helper commands and shared tool-cache helpers

This is intentionally prompt-enforced at first because agent CLIs do not expose
the same command-line contract for "project/sandbox root" versus "working
directory". Codex has `-C`, Grok currently uses `--cwd`, and the existing Claude
launcher relies on the shell working directory. A project-root launch plus
explicit worktree `cd` is the portable baseline across agents.

## Daemon-Owned Transient Spawning

The Hunt the Wumpus trial exposed another sandbox boundary: when the squad
leader directly runs `squad_spawn.sh`, the leader's agent sandbox is asked to
approve privileged orchestration operations such as:

- `git worktree add`
- `tmux -S <socket> new-session`
- access to the tmux socket under `/tmp`

Those operations are legitimate swarm orchestration, but they should not be
performed by the squad leader process. The leader should request a spawn through
durable project-local state, and a launcher-owned daemon should perform the
privileged work.

Adopt the same architectural pattern used for handoffs:

- `squad_spawn_request.sh <template> <task-id> <assignment-file>` writes a
  request under `.squad/spawn-requests/new/`
- the request helper performs only normal project-local file writes
- `squad_spawnd.bb`, started by `./swarm`, watches the request directory
- the daemon validates the request and runs the existing spawn mechanics:
  worktree creation, prompt and launch script generation, `roles.tsv` update,
  handoff directory creation, helper synchronization, and tmux session launch
- completed requests move to `.squad/spawn-requests/completed/`
- failed requests move to `.squad/spawn-requests/failed/`
- daemon results are also reflected under `.squad/agents/<agent-id>/`
- the squad leader monitors the resulting agent status instead of owning tmux
  or git worktree operations directly

Direct `squad_spawn.sh` can remain as an operator/debug command, but the squad
leader should use the request path during normal squad operation. This avoids
per-spawn escalations for transient agents.

## Dynamic Role Registry

SwarmForge writes `.swarmforge/roles.tsv` at startup from
`swarmforge/swarmforge.conf`. It is the runtime registry used by helper scripts
and the handoff daemon.

Each row is tab-separated:

```text
role    worktree-name    worktree-path    session    display-name    agent    receive-mode
```

The squad branch needs dynamic registration for transient agents. A spawn
helper should add a row for each transient agent after creating its worktree,
handoff directories, and detached tmux session.

The current handoff daemon reloads `roles.tsv` on each poll, so dynamic
registration should be possible without restarting the daemon. The spawn helper
must still update the file atomically so the daemon never reads a partially
written registry.

When daemon-owned spawning is implemented, the daemon becomes the only normal
writer for transient role rows. `squad_spawn.sh` should remain available for
manual/operator tests, but role updates from squad-leader orchestration should
flow through spawn requests.

Transient role names should use hyphens, not underscores. Existing handoff
filename conventions rely on underscores as structural separators and reject
role names containing underscores.

## Artifact Transitions

The squad leader manages artifact transitions. A large user request may start
as a theme: a larger specification that must be decomposed into independently
deliverable stories.

Core transition graph:

```text
user intent
  -> clarified theme
  -> stories
  -> user-approved story plan

stories
  -> Gherkin feature files
  -> end-to-end QA procedure specs
  -> focused unit tests and production code

Gherkin feature files + end-to-end QA procedure specs
  -> user-approved acceptance specification

Gherkin feature files
  -> pruned and normalized Gherkin
  -> generated acceptance test entrypoints/scripts
  -> project step handlers/runtime/runner adapter
  -> executable acceptance tests

end-to-end QA procedure specs
  -> executable end-to-end QA scripts
  -> UI-level QA execution

focused unit tests and production code
  -> passing unit tests
  -> passing acceptance tests
  -> cleaned/refactored implementation
  -> architectural review and boundary improvements
  -> property tests where useful
  -> language mutation hardening
  -> soft Gherkin mutation
  -> CRAP and DRY verification
  -> final independent QA verification
  -> completion broadcast
```

Transient agents should be assigned coherent ownership spans, not one agent per
graph edge by default. Some transitions belong together because they form a
tight feedback loop, especially story-to-unit-tests-to-production-code. Other
transitions deserve separate transient agents when independent judgment or
quality gates matter, such as specification, acceptance pipeline construction,
architectural review, mutation hardening, and final QA.

User approval gates are explicit. For theme-sized work, the squad leader should
ask for approval after decomposing the theme into stories and before detailed
implementation work begins. After Gherkin and QA procedure specs are written,
the squad leader should ask for acceptance-spec approval before production code
is implemented. For small tasks, the squad leader may collapse those gates only
when the story and acceptance criteria are trivial.

The squad leader owns theme-to-stories decomposition, but does not own the
artifact-producing transitions after that point. For theme-sized work,
story-to-Gherkin, story-to-QA-procedure, acceptance infrastructure,
implementation, review, hardening, QA, and cleanup transitions should be
assigned to transient agents. The squad leader records returned artifacts,
frames approval gates, monitors status, decides merge/rejection/replacement,
and reports to the user. The leader may do specialist artifact work directly
only when the user explicitly asks the leader to bypass delegation.

## Example: Faithful Hunt The Wumpus

User request:

```text
Implement Greg Yob's Hunt the Wumpus game from the 1970s, being faithful to the
original.
```

The squad leader treats this as a theme because it is larger than one behavior
slice. The first action is not to spawn implementers, but to define the fidelity
target and split it into stories.

Reference constraints from the original BASIC listing:

- text-only game
- 20 rooms arranged as a dodecahedral graph
- each room has three tunnels
- one Wumpus
- two bottomless pits
- two super bats
- player starts in a distinct room from all hazards
- player has five arrows
- each turn warns about adjacent hazards
- warnings are: Wumpus smell, draft, and bats nearby
- player may move one tunnel or shoot a crooked arrow
- arrows travel through one to five requested rooms
- an impossible arrow segment chooses a random tunnel
- an arrow that hits the Wumpus wins
- an arrow that hits the player loses
- entering a pit loses
- entering bats relocates the player randomly
- entering the Wumpus room wakes the Wumpus
- shooting wakes the Wumpus after a miss
- when awake, the Wumpus moves with probability 0.75 and stays with
  probability 0.25
- if the Wumpus ends in the player's room, the player loses

Theme-to-stories decomposition:

```text
Theme: Faithful Hunt the Wumpus

Story 1: Cave topology and setup
  The game uses the original 20-room dodecahedral adjacency table and places
  player, Wumpus, two pits, and two bats in distinct rooms.

Story 2: Turn display and warnings
  Each turn reports the player's room, adjacent tunnels, and original hazard
  warnings when hazards are one room away.

Story 3: Movement and room hazards
  The player can move only to the same room or an adjacent room; pits lose,
  bats relocate the player, and entering the Wumpus room wakes it.

Story 4: Arrow shooting
  The player can shoot a crooked arrow through one to five rooms, impossible
  segments choose random tunnels, hitting the Wumpus wins, hitting self loses,
  and missed shots consume arrows.

Story 5: Wumpus wake and movement
  The Wumpus wakes on player entry or arrow shots, moves with the original
  probability, and eats the player if it reaches the player's room.

Story 6: Game restart and same setup
  After win or loss, the game can restart with the same setup or a new setup,
  preserving the original loop semantics.
```

Example squad execution:

```text
1. squad-leader creates the theme record and fidelity checklist.
2. squad-leader spawns investigator-001 to inspect the original rules and
   produce a concise source-grounded behavior summary.
3. squad-leader converts the theme into the six stories above.
4. squad-leader asks the user to approve the fidelity target, story list,
   story order, batching choices, and any interpretation choices.
5. after user approval, squad-leader spawns specifier-001 for Story 1 and
   Story 2.
6. specifier-001 writes Gherkin for topology, setup, warnings, and visible turn
   text, plus QA procedure specs for playing through those behaviors.
7. squad-leader reviews the Gherkin and QA procedure specs against the
   fidelity checklist.
8. squad-leader asks the user to approve the acceptance specification before
   production implementation begins.
9. squad-leader spawns acceptance-builder-001 to wire Gherkin parsing,
   generated acceptance entrypoints, step handlers, and acceptance scripts.
10. squad-leader spawns implementer-001 for Story 1 and Story 2 as one coherent
   TDD assignment: unit tests plus production code.
11. implementer-001 commits passing unit and acceptance tests and hands off to
   squad-leader.
12. squad-leader spawns reviewer-001 to review fidelity and local design.
13. If review passes, squad-leader repeats the cycle for Stories 3 through 6,
    batching compatible stories only when their behaviors form one tight loop.
14. squad-leader spawns cleaner-001 after the first complete gameplay path is
    working, not after every tiny story.
15. squad-leader spawns architect-001 when code structure begins to show stable
    boundaries: game rules, random source, console UI, parser/input, and game
    loop.
16. squad-leader spawns hardener-001 after the full story set passes, focusing
    on mutation survivors in game rules and random-dependent edge cases.
17. squad-leader spawns qa-001 to convert QA procedure specs into executable
    UI-level QA scripts and run final independent verification.
18. squad-leader merges accepted commits, retires transient agents, and reports
    the completed game with the fidelity checklist and verification summary.
```

This example does not create one transient agent per transition. The
story-to-unit-tests-to-production-code loop stays with an implementer. Separate
agents are used where independent judgment matters: source investigation,
specification, acceptance pipeline construction, review, architecture,
hardening, and final QA.

## Implementation Plan

The current design is sufficient as direction, but not yet sufficient as a full
implementation specification. Build the squad branch in vertical slices that
prove the dynamic mechanics before attempting a full six-pack-equivalent
workflow.

### Slice 1: Static Squad Boot

Create the smallest runnable squad branch:

- `swarmforge/swarmforge.conf` with only `squad-leader`
- `swarmforge/roles/squad-leader.prompt`
- branch-local constitution articles for squad workflow rules
- branch-local project article identifying the squad topology

Success condition: `./swarm` starts one visible squad leader using the normal
SwarmForge launcher.

Status: implemented. The branch now has a root `./swarm` wrapper,
`swarmforge/swarmforge.conf`, `swarmforge/constitution.prompt`, squad-local
constitution articles, and `swarmforge/roles/squad-leader.prompt`.

### Slice 2: Spawn One Invisible Transient

Implement the first transient spawn path:

- `squad_spawn.sh` as the agent-facing command surface
- `squad_spawn.bb` as the Babashka implementation
- one transient role template, preferably `investigator`
- generated runtime prompt for a concrete transient agent
- transient worktree creation
- handoff directory creation
- detached tmux session creation
- atomic append/update of `.swarmforge/roles.tsv`

Success condition: the squad leader can spawn `investigator-001` in an
invisible tmux session without restarting the handoff daemon.

Status: implemented for the `investigator` template. `squad_spawn.sh` delegates
to `squad_spawn.bb`, creates a transient worktree, generates a runtime prompt,
creates handoff directories, atomically updates `.swarmforge/roles.tsv`, copies
helper scripts into the transient worktree, and starts a detached tmux session.

### Slice 3: Transient Handoff And Retirement

Close the first dynamic loop:

- transient agent receives a narrow assignment
- transient agent sends a normal `git_handoff` back to `squad-leader`
- squad leader receives and processes that handoff
- `squad_retire.sh`/`.bb` stops the transient session and marks the agent
  retired

Success condition: a spawned investigator can commit a small report, hand it
back to the squad leader, and be retired cleanly.

Status: implemented. The first dynamic loop has been observed with a spawned
investigator committing work, sending a normal `git_handoff` back to
`squad-leader`, and the squad leader completing the handoff. `squad_retire.sh`
delegates to `squad_retire.bb`, removes the transient role from
`.swarmforge/roles.tsv`, stops the detached tmux session when present, writes
retired state under `.squad/agents/<agent-id>/status`, and preserves the
transient worktree for audit.

### Slice 4: Status Baseline

Add non-handoff telemetry:

- `squad_event.sh`/`.bb`
- `squad_run.sh`/`.bb`
- `squad_status.sh`/`.bb`
- `.squad/agents/<agent-id>/heartbeat`
- `.squad/agents/<agent-id>/status`
- `.squad/tasks/<task-id>/events.log`

Success condition: the squad leader can inspect structured status for a running
invisible agent, and long-running commands can report status without using
handoffs.

Status: implemented. `squad_event.sh` records non-handoff telemetry by updating
`.squad/agents/<agent-id>/status`, `.squad/agents/<agent-id>/heartbeat`, and
`.squad/tasks/<task-id>/events.log`. `squad_run.sh` wraps a command and records
started/passed/failed telemetry. `squad_status.sh` prints current telemetry for
one agent or all known squad agents.

### Slice 5: Status Daemon

Add a narrow watchdog/reminder daemon:

- `squad_statusd.sh`/`.bb` or `.bb` only if internal
- stale heartbeat detection
- tmux session and pane existence checks
- pane-dead detection
- conservative pane-output movement checks
- generic wake-up notification to `squad-leader`

Success condition: the daemon wakes the squad leader when an invisible agent is
stale, exits, or a user status report is due.

Status: implemented. `squad_statusd.sh` supports
`--once --no-notify` for manual/test audits and long-running mode for polling.
It detects missing, invalid, and stale heartbeats; checks tmux session
existence and dead panes when tmux checks are enabled; logs alerts under
`.swarmforge/daemon/squad-statusd.log`; and sends a generic wake-up to
`squad-leader` in notifying mode. The launcher starts it automatically when the
swarm starts and cleanup stops it.

### Slice 6: Full Squad Workflow

Add the remaining templates and quality gates:

- `specifier`
- `acceptance-builder`
- `implementer`
- `reviewer`
- `cleaner`
- `architect`
- `hardener`
- `qa`

Success condition: the squad leader can execute a theme-sized workflow with
theme splitting, user approval, acceptance-spec approval, implementation,
review, hardening, QA, completion reporting, and transient retirement.

Status: partially implemented. The remaining full-workflow role templates now
exist and can be spawned dynamically: `specifier`, `acceptance-builder`,
`implementer`, `reviewer`, `cleaner`, `architect`, `hardener`, and `qa`.
Launcher-managed status daemon startup is implemented. A fully exercised
theme-sized end-to-end workflow remains future work.

### Slice 7: Theme Workflow Manifest

Add a lightweight durable manifest for theme-sized work:

- `squad_theme.sh` as the command surface
- `squad_theme.bb` as the Babashka implementation
- `.squad/themes/<theme-id>/theme.md`
- `.squad/themes/<theme-id>/stories/<story-id>.md`
- `.squad/themes/<theme-id>/approvals.tsv`
- `.squad/themes/<theme-id>/status`
- `.squad/themes/<theme-id>/events.log`

Success condition: the squad leader can create a theme record, add story
records, record user approval gates, and inspect theme status without starting
transient agents.

Status: implemented. `squad_theme.sh` supports `create`, `story`, `approve`,
and `status`. The command validates ids, preserves source text, records
approval gates append-only, and writes a current status file.

### Slice 8: Durable Assignment Files

Connect theme/story state to concrete transient assignments:

- `squad_assign.sh` as the command surface
- `squad_assign.bb` as the Babashka implementation
- `.squad/assignments/<assignment-id>/assignment.md`
- `.squad/assignments/<assignment-id>/metadata`
- `.squad/assignments/<assignment-id>/status`
- `.squad/assignments/<assignment-id>/result-handoff.draft`
- `.squad/themes/<theme-id>/assignments/<assignment-id>.md`

Success condition: the squad leader can generate a durable assignment from a
theme story and leader instructions, pass that generated assignment to
`squad_spawn.sh`, and give the transient agent a standard result handoff shape.

Status: implemented. `squad_assign.sh` supports `create`, `result`, and
`status`. It validates ids, verifies the referenced theme story and role
template, creates a durable assignment file, writes a standard result
`git_handoff` draft, records assignment metadata/status, records validated
result handoffs, and appends theme events.

### Slice 9: Assignment Result Intake

Record transient result handoffs against durable assignment state:

- validate that the handoff is `type: git_handoff`
- validate that `to` is `squad-leader`
- validate that `task` matches the assignment id
- validate that `commit` is a 10-character commit header
- copy the result handoff to `.squad/assignments/<assignment-id>/result.handoff`
- write `.squad/assignments/<assignment-id>/result`
- update assignment status to `result_received`
- append assignment and theme events

Success condition: the squad leader can take a completed transient handoff,
record it against the assignment, and inspect current assignment result state
before making merge, review, rejection, or replacement decisions.

Status: implemented through `squad_assign.sh result`.

### Slice 10: Generated Transient Launch Scripts

Move transient startup mechanics out of the tmux command string and into a
durable per-agent launcher:

- `.squad/agents/<agent-id>/launch.sh`
- `SWARMFORGE_PROJECT_ROOT`
- `SWARMFORGE_WORKTREE`
- `SWARMFORGE_TOOL_CACHE_DIR`
- shared tool cache `bin` directory prepended to `PATH`
- worktree script directory prepended to `PATH`
- tool cache directories created before agent startup
- `cd "$SWARMFORGE_WORKTREE"` before invoking the agent CLI

Success condition: `squad_spawn.sh` creates an auditable launch script for each
transient agent, records the launch script and tool cache paths in metadata,
and tmux starts that script rather than an inline command.

Status: implemented. Generated prompts also name the project root, assigned
worktree, and tool cache directory, and require the transient agent to verify it
is working from the assigned worktree before task work.

### Slice 11: Shared Tool Cache Registry

Add a small control-plane helper for shared transient tools:

- `squad_tool.sh init`
- `squad_tool.sh register <tool-name> <source> <version> <executable-file>`
- `squad_tool.sh ensure <tool-name> <source> <version> -- <install-command...>`
- `squad_tool.sh require <tool-name> <source> <version>`
- `squad_tool.sh status [tool-name]`
- `.swarmforge/tools/bin/`
- `.swarmforge/tools/src/`
- `.swarmforge/tools/cache/`
- `.swarmforge/tools/manifests/`
- `.swarmforge/tools/locks/`

Success condition: an agent can initialize the shared cache, register an
already-built executable into the shared `bin` directory, write a manifest, and
query that manifest without reinstalling the tool.

Status: implemented. This slice does not fetch or build CRAP, mutation, DRY, or
APS tools. It only establishes the shared cache contract that later installers
can use.

### Slice 12: Shared Tool Cache Validation

Add a fast validation path before tool installation:

- `squad_tool.sh require <tool-name> <source> <version>`
- success only when manifest and executable are present
- success only when manifest source and version match the requested values
- exit code `3` for missing cached tool
- exit code `4` for source or version mismatch

Success condition: a transient agent can check whether a required cached tool is
usable before deciding to build or install it.

Status: implemented.

### Slice 13: Shared Tool Cache Ensure

Add a locked install-or-reuse path:

- `squad_tool.sh ensure <tool-name> <source> <version> -- <install-command...>`
- acquires the per-tool cache lock
- reuses a matching cached executable without running the install command
- runs the install command only when the matching executable is missing
- exposes `SWARMFORGE_TOOL_TARGET`, `SWARMFORGE_TOOL_BIN_DIR`,
  `SWARMFORGE_TOOL_SRC_DIR`, and related environment variables to the install
  command
- expects the install command to write the executable to
  `SWARMFORGE_TOOL_TARGET`
- writes the manifest after successful installation

Success condition: a transient agent can express a tool requirement as a single
command that reuses a matching cache entry or installs and records the tool
under the shared cache.

Status: implemented for caller-provided install commands. Repository-specific
CRAP, mutation, DRY, and APS installers remain future slices.

### Slice 14: Approval-Gated Assignment Creation

Connect recorded approval gates to downstream assignment creation:

- `squad_assign.sh create <theme-id> <story-id> <template> <assignment-id>
  <instructions-file> --requires approval:<gate>`
- validates that the theme and story exist
- loads `.squad/themes/<theme-id>/approvals.tsv`
- refuses assignment creation when the required approval gate has not been
  recorded
- exits with code `3` for a blocked assignment
- records the requirement in the generated assignment and metadata when the
  assignment is allowed
- preserves the original ungated `create` form for early specification and
  backward-compatible workflows

Success condition: the squad leader can prevent implementation, review, QA,
hardening, or cleanup assignments from running ahead of user-approved upstream
artifacts.

Status: implemented for explicit `approval:<gate>` requirements. Default
template-to-gate policy remains a future slice.

### Slice 15: Acceptance Artifact State

Record acceptance artifacts as durable theme state:

- `squad_theme.sh acceptance <theme-id> <artifact-id> <acceptance-file>`
- validates that the theme exists
- stores the artifact under
  `.squad/themes/<theme-id>/acceptance/<artifact-id>.md`
- refuses duplicate artifact ids
- updates the theme status to `acceptance_added`
- appends an `acceptance_added` event
- includes recorded acceptance artifact ids in `squad_theme.sh status`

Success condition: Gherkin and acceptance-spec outputs can be recorded before
the user approves the `acceptance` gate, and later assignments can point to a
durable artifact rather than an informal conversation decision.

Status: implemented for markdown acceptance artifacts. Separate artifact types,
schema validation, and generated test-script linkage remain future slices.

### Slice 16: Merge Readiness Intake

Add a conservative merge-intake check after a result handoff has been recorded:

- `squad_assign.sh merge-ready <assignment-id>`
- requires an existing assignment result
- validates that the recorded 10-character result commit exists
- marks the assignment `merge_ready` when the commit is already reachable from
  `HEAD`
- otherwise performs a dry-run `git merge --no-commit --no-ff <commit>`
- aborts the dry-run merge before returning
- records `merge_ready` when the dry-run merge succeeds
- records `merge_blocked` and exits with code `4` when the dry-run merge fails
- writes durable merge state under `.squad/assignments/<assignment-id>/merge`
- surfaces the merge record from `squad_assign.sh status`

Success condition: the squad leader can distinguish a recorded result that is
safe to consider for review/QA/merge from one that is blocked by merge
conflicts, without automatically resolving or committing anything.

Status: implemented for local commits visible to the project checkout. Fetching
missing transient branches and committing accepted merges remain future slices.

### Slice 17: Review, Rejection, And Replacement Bundle

Record review and failure decisions as durable assignment state:

- `squad_assign.sh review <assignment-id> <accepted|changes-requested>
  <review-file>`
- requires an existing assignment result
- records review metadata under `.squad/assignments/<assignment-id>/review`
- stores review text under `.squad/assignments/<assignment-id>/review.md`
- updates assignment status to `review_accepted` or
  `review_changes_requested`
- `squad_assign.sh reject <assignment-id> <reason-file>`
- records rejection metadata under `.squad/assignments/<assignment-id>/rejection`
- stores rejection text under `.squad/assignments/<assignment-id>/rejection.md`
- updates assignment status to `rejected`
- `squad_assign.sh replace <old-assignment-id> <new-assignment-id>
  <template> <instructions-file>`
- creates a new assignment for the old assignment's theme and story
- preserves the old assignment and records its replacement link
- records the reverse `replaces` link in the new assignment
- carries forward any explicit approval requirement from the old assignment

Success condition: the squad leader can preserve rejected work, explain why it
was rejected, and create an auditable replacement assignment without losing the
original result or worktree context.

Status: implemented for durable assignment records. Automatic transient spawn
for replacements remains a future slice.

### Slice 18: HTW Trial Readiness Bundle

Prepare the branch for a real Hunt the Wumpus squad trial by closing the
minimum end-to-end control-plane gaps:

- `squad_assign.sh accept-merge <assignment-id>`
- requires a recorded result handoff
- requires recorded `merge_ready` state
- requires a recorded `review_accepted` decision
- records accepted merge state under
  `.squad/assignments/<assignment-id>/accepted-merge`
- records `merged` status when the commit is already reachable from `HEAD`
- performs a real `git merge --no-ff` only when the result commit is not already
  reachable
- records `merge_blocked` and exits with code `4` if an accepted merge fails
- `squad_report.sh <theme-id>`
- summarizes theme state, stories, acceptance artifacts, approvals,
  assignments, results, merge readiness, accepted merges, reviews, rejections,
  and replacements
- launcher cleanup starts long-running daemons and the window watchdog outside
  the project directory
- watchdog-owned teardown stops both the handoff daemon and squad status daemon
- cleanup script moves to `/` before stopping processes, killing sessions, and
  closing terminal windows

Success condition: a Hunt the Wumpus trial can drive the squad from theme
recording through approval-gated assignments, result intake, review,
merge-readiness, accepted merge, rejection/replacement paths, and a final
auditable report, while teardown does not leave a process holding the trial
directory open.

Status: implemented. The trial still relies on the squad leader to orchestrate
real transient agents and on the user to approve gates; this slice supplies the
control-plane commands and report needed to run that trial.

### Slice 19: Squad Leader Delegation Boundary

The first Hunt the Wumpus trial exposed a policy gap: the squad leader correctly
created the theme, split stories, and asked for approval, but then began
authoring Gherkin, QA procedures, and implementation work directly. That is not
the intended squad workflow for theme-sized work.

Clarify the leader boundary:

- the leader owns theme-to-stories decomposition
- the leader must delegate story-to-Gherkin and story-to-QA-procedure work
- the leader must delegate implementation, review, hardening, QA, and cleanup
- the leader records returned artifacts and decisions
- the leader may bypass delegation only when the user explicitly asks for that
  direct execution

Success condition: a fresh squad leader should create durable assignments and
spawn matching transient agents for artifact-producing transitions instead of
doing those transitions personally.

Status: implemented as constitution and role-prompt policy. A future slice may
add helper-level enforcement that requires an assignment to name the intended
template before artifacts can be recorded.

## Implementation Deficits

The following implementation deficits were identified before the first slices.
They are tracked here as resolved, partially resolved, or still open.

### Resolved

- [x] exact task id and agent id allocation rules for current transient spawn
      behavior
- [x] exact status, heartbeat, and event file formats for current telemetry
- [x] atomic write protocol for telemetry files
- [x] atomic update and bounded locking protocol for `.swarmforge/roles.tsv`
- [x] generated transient prompt format for current role-template spawning
- [x] prompt storage location under `.squad/agents/<agent-id>/prompt.md`
- [x] generated transient launch script format
- [x] shared tool cache environment variables and startup directory creation
      for transient agents
- [x] shared tool cache registry for already-built executables
- [x] shared tool cache validation by source and version
- [x] locked shared tool install-or-reuse command for caller-provided installers
- [x] portable transient launch model: project-root agent invocation plus
      required worktree `cd`
- [x] tmux session and window naming conventions for transient agents
- [x] capture and persist transient tmux targets through generated metadata and
      `roles.tsv`
- [x] squad leader wake-up target and notification text for the current daemon
- [x] command-wrapper behavior for current long-running command telemetry
- [x] leader-to-transient assignment file convention for theme/story work
- [x] user approval gates can be enforced by explicit downstream assignment
      requirements
- [x] acceptance-spec artifacts can be recorded as durable theme state
- [x] transient-to-leader result handoff validation and durable intake for
      assignment results
- [x] transient result intake can record merge-ready or merge-blocked state
      without committing a merge
- [x] review decisions can be recorded as durable assignment state
- [x] rejected transient work can be preserved with durable rejection reasons
- [x] replacement assignments can be linked to rejected or superseded work
- [x] accepted merge decisions can be recorded and applied after merge-ready
      and review-accepted state
- [x] final theme verification summaries can be generated from durable squad
      state
- [x] launcher and watchdog teardown stop squad daemons without keeping the
      project directory as their current working directory
- [x] squad leader delegation boundary is explicit for post-story
      artifact-producing transitions
- [x] retirement lifecycle for stopped and running sessions
- [x] retired transient worktrees are preserved for audit
- [x] branch-local constitution articles required for squad authority
- [x] `squad-leader.prompt` responsibilities and prohibitions
- [x] transient template responsibilities and prohibitions
- [x] test strategy for spawn, retire, status, daemon, cleanup, and theme
      manifest behavior
- [x] avoid repeated expensive startup tool installation for the squad leader

### Partially Resolved

- [ ] exact `.squad/` directory schema beyond implemented agent telemetry and
      theme workflow manifest and assignment paths
- [ ] direct transient spawn works, but squad-leader initiated spawning still
      needs a daemon-owned request path to avoid per-spawn sandbox escalations
- [ ] dynamic role registration currently appends to `roles.tsv`; daemon-owned
      spawning should become the normal writer for squad-leader initiated
      transients
- [ ] role template composition rules are concrete for current templates but do
      not yet support shared template fragments
- [ ] status daemon polling interval and stale thresholds have defaults and
      environment overrides, but not final policy
- [ ] status report cadence and suppression rules are described but not fully
      automated
- [ ] exact helper command arguments, outputs, and exit codes are stable for
      implemented helpers only
- [ ] policy for crashed, stale, or wedged invisible agents exists as daemon
      detection, but not as a full squad-leader recovery workflow
- [ ] transient result intake records handoffs, merge readiness, review,
      rejection, replacement links, and accepted merges, but does not yet fetch
      missing transient branches

### Open

- [ ] merge policy for fetching missing transient branches
- [ ] policy for merge conflicts created by transient work
- [ ] policy for interrupting or restarting a running transient agent
- [ ] default policy for which assignment templates require which approval
      gates
- [ ] acceptance artifact schema and type policy beyond markdown files
- [ ] helper-level enforcement that acceptance artifacts cite a producing
      assignment or transient agent

## Heartbeats

Agent-emitted heartbeats are useful as cooperative progress signals, but they
are not reliable enough to be the sole liveness mechanism.

Use three distinct signals:

1. Process liveness, daemon-owned.
   The daemon checks tmux session, pane, PID, dead-pane state, and output
   movement.
2. Cooperative agent events.
   Agents emit structured events at workflow boundaries such as assignment
   accepted, coding started, tests started, tests passed, blocked, handoff
   ready, and done.
3. Command-wrapper heartbeats.
   Long-running commands should be executed through a helper that emits status
   before, during, and after the command.

A stale cooperative heartbeat means "agent is not reporting"; it does not prove
the agent is dead. The daemon should combine heartbeat age with process state
and pane activity before waking the squad leader.

## Tmux Pane Inspection

The status daemon may inspect invisible sessions through tmux, for example by
capturing pane output or reading pane metadata. This is a secondary diagnostic
signal, not the authoritative status channel.

Pane inspection can help detect:

- dead panes
- missing sessions
- unchanged output over time
- changed output despite stale cooperative status
- the current foreground command

Pane output is unstructured and backend-specific. The daemon should avoid
parsing agent prose for technical meaning. A good daemon message is:

```text
agent implementer-001 heartbeat stale for 7 minutes; pane output unchanged
```

The squad leader can then decide whether to wait, inspect, interrupt, restart,
or report a blocker to the user.

## Open Design Questions

- Should direct `squad_spawn.sh` remain an unrestricted operator command, or
  should normal use move entirely to `squad_spawn_request.sh` plus
  `squad_spawnd.bb`?
- Should transient worktrees be created from role-specific branch names,
  task-specific branch names, or both?
- What is the minimal set of squad helper scripts needed for the first
  experiment?
- When a result commit is not locally visible, should the squad leader fetch
  from the transient worktree, from a remote branch, or from both?
- What status format should be stable enough for scripts but easy for agents to
  write through helpers?
