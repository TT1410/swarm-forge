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
- [x] tmux session and window naming conventions for transient agents
- [x] capture and persist transient tmux targets through generated metadata and
      `roles.tsv`
- [x] squad leader wake-up target and notification text for the current daemon
- [x] command-wrapper behavior for current long-running command telemetry
- [x] leader-to-transient assignment file convention for theme/story work
- [x] transient-to-leader result handoff validation and durable intake for
      assignment results
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
- [ ] dynamic role registration currently appends to `roles.tsv`; a separate
      squad registry has not been justified or rejected for future workflows
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
- [ ] user approval gates are recorded in
      `.squad/themes/<theme-id>/approvals.tsv`, but are not yet connected to
      downstream assignments
- [ ] acceptance-spec decisions can be recorded as approval gates, but do not
      yet have dedicated acceptance artifact state
- [ ] transient result intake records handoffs, but does not yet automate merge,
      review, rejection, or replacement decisions

### Open

- [ ] merge policy for transient branches and worktrees
- [ ] policy for rejected transient work
- [ ] policy for merge conflicts created by transient work
- [ ] policy for interrupting, restarting, or replacing a transient agent
- [ ] how user approval gates flow from
      `.squad/themes/<theme-id>/approvals.tsv` into later implementation and QA
      assignments
- [ ] how acceptance-spec decisions are represented beyond the first approval
      gate records
- [ ] how final verification summaries are produced and audited

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

- How should transient agents be registered with `.swarmforge/roles.tsv` while
  the handoff daemon is running?
- Should transient worktrees be created from role-specific branch names,
  task-specific branch names, or both?
- What is the minimal set of squad helper scripts needed for the first
  experiment?
- How should the squad leader merge and retire completed transient work when
  multiple agents produce related commits?
- What status format should be stable enough for scripts but easy for agents to
  write through helpers?
