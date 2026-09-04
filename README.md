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

| Stage | Templates | Ownership |
|---|---|---|
| Sprint analysis | `analyst` | Elaborates scheduled stories into module tasks and explicit intermodule interfaces. |
| Feature specification | `gherkin-writer`, `gherkin-reviewer` | Write deterministic acceptance features, then independently accept them or return concrete findings. |
| QA specification | `qa-procedure-writer`, `qa-procedure-reviewer` | Write headed user-interface procedures, then independently review their completeness and executability. |
| Module implementation | `implementer`, `cleaner`, `code-reviewer` | Implement one module task with TDD, clean it without changing behavior, and issue an independent accept/change review. |
| Sprint integration | `hardener`, `qa`, `architect`, `senior-implementer` | Integrate and mutation-harden the sprint, run UI QA, review architecture, and apply accepted architecture findings. |
| Merge repair | `merger` | Resolve a daemon-detected integration conflict for the assigned merge only; repeated repair depth is capped by configuration. |

## Configuration

[`swarmforge/squad.conf`](swarmforge/squad.conf) is the source of truth for
transient capacity, backend selection, approval gates, and merger depth.
[`swarmforge/swarmforge.conf`](swarmforge/swarmforge.conf) separately defines
the two persistent roles with this form:

```text
window[-invisible] <role> <backend> <worktree> [task|batch] [backend arguments...]
```

Both persistent roles currently use invisible, task-mode tmux sessions in the
project checkout. Changing `window-invisible` to `window` adds a terminal
surface. `squad.conf` does not start workers; the daemon consults it as
assignments become ready.

Current transient defaults are:

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

## Constitution, prompts, and contracts

[`swarmforge/constitution.prompt`](swarmforge/constitution.prompt) is the
entry point for the two persistent roles and takes precedence over all loaded
articles:

| Article | Responsibility |
|---|---|
| [`engineering.prompt`](swarmforge/constitution/articles/engineering.prompt) | General tool identity, testability, acceptance-pipeline, verification, and quality guardrails. |
| [`workflow.prompt`](swarmforge/constitution/articles/workflow.prompt) | Worktree boundaries, commit attribution, scratch paths, and startup failures. |
| [`handoffs.prompt`](swarmforge/constitution/articles/handoffs.prompt) | Persistent-role handoff creation, receipt, merge, and completion. |
| [`project.prompt`](swarmforge/constitution/articles/project.prompt) | Local-state locations, handoff style, and ownership protection; its project-shape paragraph still records the original single-leader slice. |
| [`local-engineering.prompt`](swarmforge/constitution/articles/local-engineering.prompt) | Squad tool startup, project tooling layout, and verification, preceded by legacy first-slice development constraints. |
| [`local-workflow.prompt`](swarmforge/constitution/articles/local-workflow.prompt) | Sprint 0, scheduling, module work, approvals, assignments, reviews, batching, telemetry, and completion policy. |

The persistent prompts then load declarative contracts:

- [`squad-leader.prompt`](swarmforge/roles/squad-leader.prompt) and
  [`squad-leader.contract.edn`](swarmforge/roles/squad-leader.contract.edn)
  allow orchestration metadata, sprint framing, module maps, approvals, and
  reports while forbidding worker-owned product artifacts.
- [`troubleshooter.prompt`](swarmforge/roles/troubleshooter.prompt) and
  [`troubleshooter.contract.edn`](swarmforge/roles/troubleshooter.contract.edn)
  define the operator-facing diagnostic, repair, recovery, and requested
  backlog-entry role outside the product state machine.

Transient workers do not start from that persistent constitution entry point.
Their generated prompt loads [`worker-common.prompt`](swarmforge/worker-common.prompt),
then `role-templates/<template>.prompt` and its `.contract.edn`, followed by the
generated assignment. The assignment supplies the sprint/story scope,
interfaces, module map where relevant, required tools, evidence, and runtime
paths; it takes precedence over the role prompt and common protocol.

Contracts declare capabilities and tool/evidence requirements consumed by
assignment generation. [`tool-table.edn`](swarmforge/tool-table.edn) is the
authority for external tool sources and versions.
[`clean-architecture.md`](swarmforge/clean-architecture.md) defines the design
model for roles that explicitly load it, while the templates under
`swarmforge/templates/` define the initial module-map, dependency, tooling, and
acceptance-pipeline shapes.

The first-slice statements in `project.prompt` and `local-engineering.prompt`
are historical and do not describe the implemented sprint/transient runtime.
Current topology and operation come from the configurations, contracts,
`sprints.md`, workflow article, advisor, and daemon described here.

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

## Runtime components and generated state

| Component | Responsibility |
|---|---|
| `squadd.*` | Applies mechanical transitions, owns normal main-git merges, services worker lifecycle requests, and updates the dashboard. |
| `squad_sprint.*` and `squad_sprint_next.*` | Persist sprint lifecycle and project it into schedule, cancel, Sprint 0, implementation, and completion actions. |
| `squad_next.*` | Combines sprint, story, assignment, approval, and agent state into the authoritative next action. |
| `squad_assign.*`, `squad_spawn*`, `squad_retire.*` | Generate assignments and manage transient branches, worktrees, prompts, sessions, and retirement. |
| `squad_packet.*`, `squad_batch.*`, `squad_approval.*`, `squad_theme.*` | Persist story packets, module batches, approvals/blockers, module maps, and implementation order. |
| Telemetry, recovery, dashboard, and mockup components | Record execution, recover agents, expose operator controls, and exercise the proposed UI. |

`.squad/sprints/` stores draft, scheduled, cancelled, and completed sprint
records and their task projections. `.squad/themes/` stores the Sprint 0 module
map and implementation order. Story packets, assignments, batches, approvals,
blockers, agent records, and saved sessions live under their corresponding
`.squad/` subdirectories. `.worktrees/` holds generated transient checkouts;
`.swarmforge/` holds tmux and handoff transport state. Stories, features, QA
procedures, source, tests, and other product artifacts remain normal committed
project files.

Common launcher concepts originate on `main`; sprint records, scheduling,
advisor behavior, dashboard projection, role templates, and squad policy belong
on this branch.
