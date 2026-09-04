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

## Workflow

```text
New Task → specifier → approval → coder → refactorer → architect → Done
```

- `specifier` turns operator intent into deterministic Gherkin specifications
  and examples. It does not produce a headed QA suite.
- `coder` builds the acceptance pipeline, implements approved behavior with
  TDD and unit tests, and keeps generated acceptance tests separate.
- `refactorer` preserves behavior while improving coverage, names, cohesion,
  duplication, testability, and property-test support. It runs CRAP and DRY
  analysis but does not own mutation execution.
- `architect` owns high-level boundaries and dependency direction, performs
  mutation hardening and DRY verification, and runs soft Gherkin mutation when
  feature files exist.

Because the specifier is the project-root role, its forward handoff is held in
**Attention** for operator approval before delivery to the coder. Later
handoffs move the board automatically. Refactorer results propagate back to
the coder, and the architect's terminal result propagates to earlier roles and
moves the card to Done.

This is the middle-sized workflow: use it when Gherkin and a separate
architecture pass matter, but dedicated cleaner, hardender, and final QA roles
would be excessive.

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
