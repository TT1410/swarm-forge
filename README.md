# SwarmForge sprint-module-squad

The `sprint-module-squad` branch is an experimental dynamic-worker workflow
organized around named sprints and module-sized implementation tasks. Two
persistent agents coordinate short-lived specialists; only one sprint executes
at a time, while the operator may continue shaping later sprints.

The repository's master branch is named
[`main`](https://github.com/unclebob/swarm-forge/tree/main). Read its
[README](https://github.com/unclebob/swarm-forge/blob/main/README.md) for the
base SwarmForge concepts and prerequisites. This branch is not a
`get-swarm-forge` product and owns its own sprint and squad control plane.

The complete domain description is in [`sprints.md`](sprints.md). This README
explains how that model is represented and operated by the branch.

## Structure

```text
sprints.md                         sprint domain rules
sprint-mockup.html                 living UI mockup
swarmforge/
  swarmforge.conf                  persistent agents
  squad.conf                       worker backends, capacity, approval gates
  roles/                           squad-leader and troubleshooter
  role-templates/                  transient prompts and contracts
  worker-common.prompt             shared transient protocol
  clean-architecture.md            architectural policy
  tool-table.edn                   approved tool identities
  templates/                       module-map and dependency templates
  scripts/
    squadd.*                       daemon and dashboard
    squad_sprint.*                 sprint records and commands
    squad_sprint_next.*            sprint workflow projection
    squad_next.*                   deterministic workflow advisor
    squad_assign.*                 assignments and merges
    squad_packet.*                 story state
    squad_approval.*               approvals and blockers
    squad_spawn* / squad_retire.*  transient lifecycle
.squad/                            generated durable project/sprint state
.worktrees/                        generated transient checkouts
```

Product stories, Gherkin, QA procedures, source, and tests are ordinary project
files. `.squad/` stores orchestration records and references: sprints, story
packets, assignments, approvals, blockers, agent telemetry, and recovery state.
`.swarmforge/` stores the underlying tmux and handoff transport state.

## Persistent control plane

[`swarmforge/swarmforge.conf`](swarmforge/swarmforge.conf) starts two invisible
roles in the project root:

| Role | Backend | Responsibility |
|---|---|---|
| `squad-leader` | Codex | Owns user-facing product orchestration, sprint framing, approvals, assignment decisions, and reports. It does not author product artifacts. |
| `troubleshooter` | Grok | Owns free-form operator chat, inspection, requested backlog edits, repair, and recovery. It routes product work to the squad leader. |

The launcher also starts `squadd`. The daemon applies mechanical workflow
transitions, services spawn requests, delivers handoffs, retires workers,
updates the dashboard, and is the sole owner of normal main-git merge
operations. The squad leader asks `squad_next.sh --residual-only` for judgment
work and does not race the daemon's merge commands.

## Sprint model

The project contains an unscheduled backlog, any number of named draft sprints,
at most one scheduled sprint, cancelled sprint records, and completed sprint
records.

- Stories may be added at any time. A story belongs to at most one named sprint.
- Scheduling locks a sprint. It cannot be edited while executing.
- Cancelling preserves the sprint and its stories; in-flight work is retained
  as abandoned branch state, and the same sprint can later be scheduled again.
- No implementation sprint may run until Sprint 0 is complete.

### Sprint 0

Sprint 0 is created automatically and implements no stories. When the operator
schedules it, the squad leader reviews every story currently known, writes the
project module map and implementation order, and requests one approval for
those two documents. Stories added after Sprint 0 begins wait for a later map
update. Approval completes Sprint 0.

### Implementation sprint

```text
schedule sprint
  → update module map/order if needed
  → analyst: elaborated stories + module tasks + interfaces
  → sprint-plan approval
  ├─→ Gherkin writer → reviewer → approval
  ├─→ QA-procedure writer → reviewer → approval
  └─→ module implementers → cleaners → code reviewers
       → sprint hardener/integration → QA → architect
       → senior implementer when needed
       → tag and complete sprint
```

The analyst converts the sprint stories into tasks divided by module and
defines the intermodule interfaces. After the operator approves that plan,
module implementers can proceed in the recorded dependency order while the
Gherkin and QA-procedure tracks are completed independently.

Implementers use TDD against module tasks and interfaces; they do not treat
Gherkin as their implementation specification. After every module has passed
cleanup and code review, and the feature and QA artifacts are approved, the
whole sprint enters the singleton quality pipeline. The hardener integrates the
modules and gets Gherkin passing, QA verifies the user interface, and the
architect blesses or recommends remediation. A senior implementer applies
required architectural changes. Completion records a git tag and SHA.

## Transient roles and capacity

Workers are created from `swarmforge/role-templates/`. Each receives a durable
assignment, its own branch and worktree, one role contract, and the common
worker protocol. Results return only to the squad leader.

The active templates are `analyst`, `gherkin-writer`, `gherkin-reviewer`,
`qa-procedure-writer`, `qa-procedure-reviewer`, `implementer`, `cleaner`,
`code-reviewer`, `hardener`, `qa`, `architect`, `senior-implementer`, and
`merger`.

[`swarmforge/squad.conf`](swarmforge/squad.conf) is the source of truth for
capacity and backend selection. Current defaults are:

- At most 10 transient agents at once.
- `analyst`, `hardener`, `qa`, `architect`, and `merger` are singletons; other
  active templates allow up to three concurrent workers.
- Workers inherit the Codex squad-leader backend unless overridden.
  Gherkin reviewer, QA-procedure reviewer, code reviewer, and senior implementer
  use Grok.
- User gates are enabled for the sprint framing/plan records, Gherkin, and QA
  procedure; rejection creates a durable blocker.
- `max_merger_depth 2` stops repeated merge-repair lineages from growing
  indefinitely and surfaces a blocker instead.

`SWARMFORGE_SQUAD_AGENT` overrides the backend for every transient worker.
Per-template `transient_agent` lines override the global default without
starting workers themselves.

## Run and operate

Use this branch directly or copy its archive into the project; do not invoke
`get-swarm-forge` for it. From a prepared checkout:

```sh
./swarm
```

Startup launches both persistent roles, the handoff daemon, `squadd`, and the
dashboard. The normal operator path is:

1. Add stories to the backlog.
2. Assemble named sprints.
3. Schedule Sprint 0 and approve its maps.
4. Schedule one implementation sprint at a time.
5. Respond to approval and blocker rows in Attention while the board shows
   module and story progress.

Free-form dashboard chat goes to the troubleshooter. Open the squad-leader pane
when direct orchestration inspection is needed. Stop everything with the
dashboard teardown control or `./close-swarm`.

Run the living mockup separately with:

```sh
bb swarmforge/scripts/sprint_mockup.clj
```

It serves `sprint-mockup.html` at `http://127.0.0.1:4987/`.

Common launcher concepts originate on `main`; sprint records, scheduling,
advisor behavior, dashboard projection, role templates, and squad policy belong
on this branch.
