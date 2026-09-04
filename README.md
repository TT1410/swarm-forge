# SwarmForge squad

The `squad` branch is an experimental dynamic-worker workflow. Two persistent
agents coordinate a pool of short-lived role specialists around a durable story
state machine. Unlike the fixed two-, four-, and six-packs, most product agents
exist only for one assignment and retire afterward.

The repository's master branch is named
[`main`](https://github.com/unclebob/swarm-forge/tree/main). Read its
[README](https://github.com/unclebob/swarm-forge/blob/main/README.md) for the
base SwarmForge concepts and prerequisites. `squad` is a separate line of work,
not a `get-swarm-forge` product, and its squad control plane is owned by this
branch.

## Structure

```text
swarmforge/
  swarmforge.conf                  persistent agents
  squad.conf                       worker backends, capacity, approval gates
  roles/
    squad-leader.prompt
    squad-leader.contract.edn
    troubleshooter.prompt
    troubleshooter.contract.edn
  role-templates/                  transient role prompts and contracts
  worker-common.prompt             rules shared by every transient worker
  clean-architecture.md            architectural policy
  tool-table.edn                   approved tool identities
  templates/                       generated project/orchestration templates
  scripts/
    squadd.*                       daemon and dashboard
    squad_next.*                   deterministic workflow advisor
    squad_assign.*                 assignment and merge records
    squad_packet.*                 per-story state
    squad_approval.*               operator gates and rejection blockers
    squad_spawn* / squad_retire.*  transient lifecycle
    squad_status.* / squad_event.* telemetry
.squad/                            generated durable squad state
.worktrees/                        generated transient checkouts
```

The committed configuration and role definitions live under `swarmforge/`.
Runtime roles, assignments, story packets, approvals, blockers, batches,
telemetry, recovery records, and saved sessions live under `.squad/`.
`.swarmforge/` remains the tmux and handoff transport state used by the shared
launcher.

## Persistent control plane

[`swarmforge/swarmforge.conf`](swarmforge/swarmforge.conf) starts two invisible
tmux roles in the project root:

| Role | Backend | Responsibility |
|---|---|---|
| `squad-leader` | Codex | Follows the workflow advisor, creates assignments, frames approvals, accepts worker merges when directed, and reports product progress. It never authors product artifacts. |
| `troubleshooter` | Grok | Owns operator chat, status questions, repair, recovery, and requested backlog entry. It routes product work to the squad leader. |

The launcher also starts `squadd`, the mechanical daemon. It advances safe
state-machine transitions, services spawn requests, delivers handoffs, retires
workers, updates the dashboard, and wakes the persistent roles. In this branch,
the squad leader—not the daemon—executes worker merge acceptance when
`squad_next.sh --residual-only` directs it.

The persistent roles are invisible by default. Use the dashboard's **Open SL**
or chat controls, or attach to their tmux sessions, when a raw pane is needed.

## Transient workers

Transient workers are created from `swarmforge/role-templates/`. Each gets a
generated assignment, its own branch and worktree, a tmux session, the common
worker protocol, and one role contract. It sends its committed result only to
the squad leader and does not delegate to another worker.

The default active pipeline uses these templates:

| Stage | Template | Ownership |
|---|---|---|
| Product framing | `system-analyst` | The executable product skeleton, `frame.md`, and product-level QA procedure. |
| Analysis | `analyst` | One story's implementation plan and boundaries. |
| Test specification | `gherkin-writer`, `qa-procedure-writer` | Acceptance features, headed QA procedure, and implementer notes. |
| Implementation | `implementer` | TDD, unit behavior, and accepted Gherkin. |
| Cleanup | `cleaner` | Behavior-preserving cleanup, coverage, property tests, CRAP, and DRY. |
| Review | `code-reviewer` | Recommendations and an accept/change decision; no code edits. |
| Hardening | `hardener` | Applies review findings, mutation hardening, and robustness work. |
| Verification | `qa` | Independent UI-level QA and release evidence. |
| Architecture | `architect` | Architectural review and recommendations. |
| Remediation | `senior-implementer` | Applies accepted architecture findings when required. |

## Configuration

This branch has two configuration files with separate jobs:

- [`swarmforge/swarmforge.conf`](swarmforge/swarmforge.conf) defines persistent
  roles with
  `window[-invisible] <role> <backend> <worktree> [task|batch] [backend arguments...]`.
  It starts squad leader and troubleshooter in the project checkout. Changing
  `window-invisible` to `window` adds a terminal surface without changing the
  role's tmux execution.
- [`swarmforge/squad.conf`](swarmforge/squad.conf) defines transient capacity,
  backend selection, approval gates, and session retention. It does not start
  workers; the daemon consults it when the advisor requests one.

The current transient defaults are:

- At most 10 transient agents at once.
- `hardener`, `qa`, `architect`, `senior-implementer`, and `system-analyst` are
  singletons; the writing and implementation stages allow up to three each.
- Workers inherit the Codex squad-leader backend unless overridden.
  `code-reviewer` and `architect` use Grok; the persistent troubleshooter also
  uses Grok.
- Product framing, the implementation plan, Gherkin, and the QA procedure
  require operator approval. Rejection creates a durable blocker.
- Retired agent pane and liveness records are saved by default.

`SWARMFORGE_SQUAD_AGENT` can override the backend for every transient worker.
Per-template `transient_agent` lines in `squad.conf` override the global
default without starting agents themselves.

## Constitution, prompts, and contracts

[`swarmforge/constitution.prompt`](swarmforge/constitution.prompt) is the
entry point for the two persistent roles and takes precedence over the article
files it loads:

| Article | Responsibility |
|---|---|
| [`engineering.prompt`](swarmforge/constitution/articles/engineering.prompt) | General tool identity, testability, acceptance-pipeline, verification, and quality guardrails. |
| [`workflow.prompt`](swarmforge/constitution/articles/workflow.prompt) | Worktree boundaries, commit attribution, scratch paths, and startup failures. |
| [`handoffs.prompt`](swarmforge/constitution/articles/handoffs.prompt) | Persistent-role handoff creation, receipt, merge, and completion. |
| [`project.prompt`](swarmforge/constitution/articles/project.prompt) | Local-state locations, terse handoffs, and ownership protection; its project-shape paragraph still records the branch's original single-leader slice. |
| [`local-engineering.prompt`](swarmforge/constitution/articles/local-engineering.prompt) | Squad tool startup, project tooling layout, and verification, preceded by legacy first-slice development constraints. |
| [`local-workflow.prompt`](swarmforge/constitution/articles/local-workflow.prompt) | The per-story state machine, approvals, assignments, batching, role order, and terminal handback policy. |

After the constitution, each persistent agent reads its prompt and contract:

- [`squad-leader.prompt`](swarmforge/roles/squad-leader.prompt) plus
  [`squad-leader.contract.edn`](swarmforge/roles/squad-leader.contract.edn)
  permit orchestration metadata and user-facing decisions while forbidding
  product artifacts. The contract names `squad_next.sh --residual-only` as the
  decision source.
- [`troubleshooter.prompt`](swarmforge/roles/troubleshooter.prompt) plus
  [`troubleshooter.contract.edn`](swarmforge/roles/troubleshooter.contract.edn)
  permit operator-facing diagnosis and state repair but exclude the role from
  the product state machine.

Transient workers use a different, generated instruction stack. They read
[`worker-common.prompt`](swarmforge/worker-common.prompt), then their
`role-templates/<template>.prompt`, which in turn names its `.contract.edn`, and
then the generated assignment. The assignment supplies scope, artifacts,
required tools, evidence, and runtime paths and has precedence over the role
prompt and common protocol. Transients are not launched with the persistent
constitution entry point.

The EDN contracts declare role capabilities and required tool/evidence data
used while assignments are generated; the prompt supplies procedural
instructions. [`tool-table.edn`](swarmforge/tool-table.edn) is the authority for
external tool source/version identities, and
[`clean-architecture.md`](swarmforge/clean-architecture.md) supplies the design
model to roles that explicitly load it.

The first-slice wording in `project.prompt` and `local-engineering.prompt` is
historical and no longer describes the implemented transient-worker runtime.
The current topology comes from the two configuration files, contracts,
advisor, and daemon described here. The product-frame prerequisite is likewise
implemented by `squad_product.*`, `squad_next.*`, and the dashboard; it has not
yet been folded into `local-workflow.prompt`.

## Product frame

Story execution begins only after the branch has an approved product frame:

1. The operator supplies a mission, adds backlog items, and selects **Start
   backlog**. That action snapshots the currently open item IDs into the
   durable product record; it does not immediately start every story.
2. The workflow advisor assigns `system-analyst`. Its worker creates one
   executable product skeleton plus `frame.md` and `qa/product.md`, leaving
   individual story rules unimplemented.
3. The frame is presented for operator approval. Once accepted and merged, its
   commit and artifact paths are recorded in `.squad/product`.
4. Only then do the snapshotted backlog items enter the story pipeline. Items
   added later remain in the backlog until the operator starts them.

## Story workflow

```text
framed backlog item
  → operator Start (or inclusion in the Start backlog snapshot)
  → analyst plan → approval
  → Gherkin + QA procedure → approvals
  → implementer
  → cleaner
  → code-reviewer
  → hardener
  → QA
  → architect
  → senior-implementer when needed
  → done
```

1. The operator explicitly starts a framed backlog item, individually or by
   including it in the product snapshot. The squad leader does not select
   backlog work on its own.
2. An analyst produces one implementation plan. After approval, Gherkin and QA
   procedures are written in parallel and presented for approval.
3. The implementer starts only after the plan and both test artifacts are
   approved. The result then moves through cleanup and code review.
4. The hardener applies review recommendations before hardening. Hardener, QA,
   architect, and senior implementer may batch all compatible stories ready at
   the same stage.
5. The architect either blesses the result or returns recommendations to the
   senior implementer. The story is done after the architect when there are no
   findings, or after senior implementation otherwise. There is no final user
   blessing gate.

Every story has a durable packet under `.squad/stories/<story-id>/packet` that
reunites its source artifact, approvals, specifications, implementation
results, reviews, and batch membership. `squad_next.sh` is the sole workflow
advisor; helper validation and dashboard appearance do not independently
define readiness.

## Runtime components and generated state

| Component | Responsibility |
|---|---|
| `squadd.*` | Repeatedly applies mechanical transitions, services spawn and retirement requests, delivers handoffs, and updates the dashboard. |
| `squad_next.*` | Projects durable state into the single next-action/residual decision used by the squad leader and daemon. |
| `squad_assign.*`, `squad_spawn*`, `squad_retire.*` | Generate assignments and manage each transient branch, worktree, prompt, session, and lifecycle. |
| `squad_product.*`, `squad_packet.*`, `squad_batch.*`, `squad_approval.*` | Persist product framing, per-story state, quality batches, approvals, rejections, and blockers. |
| `squad_event.*`, `squad_run.*`, `squad_status.*`, `squad_recover.*` | Record telemetry and support inspection and recovery. |
| `squadd/web.clj` and dashboard assets | Present the backlog, frame, board, Attention rows, chat, agents, and operator controls. |

`.squad/product` records the frame and initial backlog snapshot.
`.squad/backlog/`, `.squad/stories/`, `.squad/assignments/`, `.squad/batches/`,
`.squad/approvals/`, `.squad/blockers/`, `.squad/agents/`, and
`.squad/sessions/` are the durable control-plane records. `.worktrees/` holds
generated transient checkouts; `.swarmforge/` holds lower-level tmux and
handoff transport state. Product artifacts such as the executable, `frame.md`,
features, QA procedures, source, and tests live outside those orchestration
directories and are committed normally.

## Run and operate

Use this branch directly or copy its archive into the project; do not invoke
`get-swarm-forge` for it. From a prepared checkout:

```sh
./swarm
```

Startup launches the two persistent roles, handoff daemon, squad daemon,
dashboard, and local state. Add and start stories from the dashboard, use
**Attention** for approvals and blockers, use chat for the troubleshooter, and
open SL when direct squad-leader inspection is necessary.

Stop the branch with the dashboard teardown control or:

```sh
./close-swarm
```

For this branch's handoff details, see
[`swarmforge/handoff-protocol.md`](swarmforge/handoff-protocol.md). Common
launcher changes belong on `main`; dynamic squad state, helpers, templates,
contracts, and workflow policy belong on `squad`.
