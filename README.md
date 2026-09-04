# SwarmForge four-pack

The `four-pack` branch is a pack-only workflow that separates specification,
implementation, refactoring, and architecture without adding the six-pack's
dedicated hardening and headed-QA roles.

The repository's master branch is named
[`main`](https://github.com/unclebob/swarm-forge/tree/main). Read its
[README](https://github.com/unclebob/swarm-forge/blob/main/README.md) for
prerequisites, installation of `get-swarm-forge`, and the shared runtime model.
This README covers only the four-pack's structure and operation.

## Structure

This branch contributes the pack-owned half of an installation:

```text
swarm
swarmforge/
  swarmforge.conf
  constitution.prompt
  constitution/
    articles/
      project.prompt
      local-workflow.prompt
  roles/
    specifier.prompt
    coder.prompt
    refactorer.prompt
    architect.prompt
```

`get-swarm-forge four-pack` combines those files with the runtime scripts and
shared constitution articles from `main`. Generated transport state lives in
`.swarmforge/`; generated role checkouts live in `.worktrees/`.

## Configuration

The branch's [`swarmforge/swarmforge.conf`](swarmforge/swarmforge.conf) is the
authority for the active agents, worktrees, receive modes, and propagation.

| Role | Default backend | Working directory | Receive mode | Propagation |
|---|---|---|---|---|
| `specifier` | Codex | project root (`master`) | task | forward only |
| `coder` | Grok | `.worktrees/coder` | task | forward only |
| `refactorer` | Grok | `.worktrees/refactorer` | task | back one |
| `architect` | Codex | `.worktrees/architect` | batch | back all |

`master` means the main project checkout on its current branch; the branch does
not need to be named `master`.

Each non-comment line uses the shared pack form:

```text
window[-invisible] <role> <backend> <worktree> [task|batch] [forward-only|back-one|back-all] [backend arguments...]
```

File order defines the forward route. Omitted receive and propagation fields
mean `task` and `forward-only`. The refactorer selects `back-one`; the architect
selects `batch back-all`; the two Codex roles also receive `--yolo`.

## Constitution

[`swarmforge/constitution.prompt`](swarmforge/constitution.prompt) is the
instruction entry point and takes precedence over its articles. Installation
combines three shared articles from `main` with two local articles:

| Source | Article | Purpose in this pack |
|---|---|---|
| `main` | `engineering.prompt` | Shared language, TDD, acceptance-pipeline, tooling, and verification law. |
| `main` | `workflow.prompt` | Shared worktree, commit, scratch-file, and failure rules. |
| `main` | `handoffs.prompt` | Shared durable handoff and merge protocol. |
| `four-pack` | [`project.prompt`](swarmforge/constitution/articles/project.prompt) | Declares the four-role project shape, local state locations, handoff style, and ownership boundary. |
| `four-pack` | [`local-workflow.prompt`](swarmforge/constitution/articles/local-workflow.prompt) | Defines how earlier roles process the architect's merge-only terminal handbacks without restarting the pipeline. |

Every agent reads all of those articles before its role prompt. The local
articles specialize the four-pack; they do not duplicate the shared law on
`main`.

## Roles

| Prompt | Ownership |
|---|---|
| [`specifier.prompt`](swarmforge/roles/specifier.prompt) | Turns operator intent into deterministic Gherkin and examples, then submits the specification for dashboard approval. It does not prescribe implementation. |
| [`coder.prompt`](swarmforge/roles/coder.prompt) | Builds the acceptance pipeline and implements approved behavior with TDD, unit tests, and generated acceptance tests. |
| [`refactorer.prompt`](swarmforge/roles/refactorer.prompt) | Preserves behavior while improving coverage, names, cohesion, duplication, boundaries, testability, and property-test support. It runs CRAP and DRY but not mutation tests. |
| [`architect.prompt`](swarmforge/roles/architect.prompt) | Owns high-level boundaries and dependency direction, structural corrections, language mutation, DRY verification, and soft Gherkin mutation. |

Configuration determines topology and queue behavior; the constitution governs
all four agents; role prompts assign exclusive work and the next handoff.

## Workflow

```text
New Task → specifier → approval → coder → refactorer → architect → Done
```

Because the specifier is the project-root role, its forward handoff is held in
**Attention** for operator approval before delivery to the coder. Later
handoffs move the board automatically. Refactorer results propagate back to
the coder, and the architect's terminal result propagates to earlier roles and
moves the card to Done.

This is the middle-sized workflow: use it when Gherkin and a separate
architecture pass matter, but dedicated cleaner, hardender, and final QA roles
would be excessive.

## Runtime components and generated state

The branch owns no runtime implementation. `get-swarm-forge` installs the
launcher, configuration, constitution entry point, local articles, and role
prompts from this branch, then adds `swarmforge/scripts/` and shared articles
from `main`.

At startup the composed runtime creates the three non-master worktrees, mirrors
managed instructions and helpers into them, starts four tmux sessions, and
starts the handoff daemon and dashboard. `.swarmforge/` contains generated
role/session maps, handoff queues, board cards, approvals, daemon state, and
pane metadata. `.worktrees/coder`, `.worktrees/refactorer`, and
`.worktrees/architect` are generated git checkouts, not configuration sources.

## Install and run

From the existing project that should receive the pack:

```sh
get-swarm-forge four-pack
./swarm
```

The installer copies shared infrastructure from `main` and this branch's pack
definition into the current directory. `./swarm` starts the configured agents,
handoff daemon, and local dashboard. The default roles are invisible tmux
sessions; open their live panes from the dashboard.

Use **New Task** to give work to the specifier. The board follows the configured
role order, **Attention** handles the specification gate and clarifications,
and **Teardown** stops the swarm without deleting the project.

For durable handoff format, audit, retry, and merge details, see the
[`main` handoff protocol](https://github.com/unclebob/swarm-forge/blob/main/swarmforge/handoff-protocol.md).

## Changing this branch

- Change `swarmforge/swarmforge.conf` to change the four active roles, their
  backends, worktrees, or queue behavior.
- Change `swarmforge/roles/` to change the division of work.
- Keep four-pack additions in `project.prompt` or `local-*.prompt` articles.
- Put common runtime, dashboard, terminal, installer, or shared constitution
  changes on `main`, not here.
