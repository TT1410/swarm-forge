# SwarmForge two-pack

The `two-pack` branch is the smallest pack-only workflow: a coder implements a
task and a cleaner performs the entire refinement and hardening pass.

The repository's master branch is named
[`main`](https://github.com/unclebob/swarm-forge/tree/main). Read its
[README](https://github.com/unclebob/swarm-forge/blob/main/README.md) for
prerequisites, installation of `get-swarm-forge`, and the shared runtime model.
This README covers only the two-pack's structure and operation.

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
  roles/
    coder.prompt
    cleaner.prompt
```

`get-swarm-forge two-pack` combines those files with the runtime scripts and
shared `engineering.prompt`, `workflow.prompt`, and `handoffs.prompt` articles
from `main`. Runtime state is written to `.swarmforge/`; the cleaner's checkout
is created under `.worktrees/cleaner`.

The branch's [`swarmforge/swarmforge.conf`](swarmforge/swarmforge.conf) is the
authority for the active agents, worktrees, receive modes, and propagation.

| Role | Default backend | Working directory | Receive mode | Propagation |
|---|---|---|---|---|
| `coder` | Grok | project root (`master`) | task | forward only |
| `cleaner` | Codex | `.worktrees/cleaner` | batch | back one |

`master` means the main project checkout on its current branch; the branch does
not need to be named `master`.

## Workflow

```text
New Task → coder → cleaner → Done
```

The two roles deliberately cover different concerns:

- `coder` owns requested behavior and focused unit tests. It works in small TDD
  steps and hands a committed implementation to `cleaner`.
- `cleaner` batches compatible coder handoffs and owns behavior-preserving
  cleanup, coverage improvement, CRAP and DRY analysis, architecture and
  dependency direction, module boundaries, and mutation hardening.

This pack intentionally has no specifier, Gherkin, generated acceptance suite,
property-testing role, or headed QA role. The card text is the specification,
and verification is language-local unit testing plus the cleaner's quality
checks.

When the cleaner finishes, it sends the terminal result back to the coder. The
handoff daemon merges that result, marks the card Done, and leaves both role
branches synchronized with the completed work. There is no specification
approval gate in this pack.

## Install and run

From the existing project that should receive the pack:

```sh
get-swarm-forge two-pack
./swarm
```

The installer copies shared infrastructure from `main` and this branch's pack
definition into the current directory. `./swarm` starts both tmux-hosted agents,
the handoff daemon, and the local dashboard. The default roles are invisible
tmux sessions; open their live panes from the dashboard.

Use **New Task** to give work to the coder. The board moves when committed
handoffs are delivered. **Attention** surfaces clarifications and delivery
problems, and **Teardown** stops the swarm without deleting the project.

For durable handoff format, audit, retry, and merge details, see the
[`main` handoff protocol](https://github.com/unclebob/swarm-forge/blob/main/swarmforge/handoff-protocol.md).

## Changing this branch

- Change `swarmforge/swarmforge.conf` to change the two active roles, their
  backends, worktrees, or queue behavior.
- Change `swarmforge/roles/` to change role ownership.
- Change `swarmforge/constitution/articles/project.prompt` only for rules local
  to this two-role workflow.
- Put common runtime, dashboard, terminal, installer, or shared constitution
  changes on `main`, not here.
