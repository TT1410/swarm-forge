# SwarmForge six-pack

The `six-pack` branch is the full pack-only workflow. It gives specification,
implementation, cleanup, architecture, mutation hardening, and final QA to six
separate agents.

The repository's master branch is named
[`main`](https://github.com/unclebob/swarm-forge/tree/main). Read its
[README](https://github.com/unclebob/swarm-forge/blob/main/README.md) for
prerequisites, installation of `get-swarm-forge`, and the shared runtime model.
This README covers only the six-pack's structure and operation.

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
      local-engineering.prompt
      local-workflow.prompt
  roles/
    specifier.prompt
    coder.prompt
    cleaner.prompt
    architect.prompt
    hardender.prompt
    QA.prompt
```

`get-swarm-forge six-pack` combines those files with the runtime scripts and
shared constitution articles from `main`. Generated transport state lives in
`.swarmforge/`; generated role checkouts live in `.worktrees/`.

The branch's [`swarmforge/swarmforge.conf`](swarmforge/swarmforge.conf) is the
authority for the active agents, worktrees, receive modes, and propagation.

| Role | Default backend | Working directory | Receive mode | Propagation |
|---|---|---|---|---|
| `specifier` | Codex | project root (`master`) | task | forward only |
| `coder` | Grok | `.worktrees/coder` | task | forward only |
| `cleaner` | Grok | `.worktrees/cleaner` | batch | back one |
| `architect` | Grok | `.worktrees/architect` | batch | back all |
| `hardender` | Codex | `.worktrees/hardender` | batch | forward only |
| `QA` | Grok | `.worktrees/QA` | batch | back all |

`master` means the main project checkout on its current branch; the branch does
not need to be named `master`.

## Workflow

```text
New Task → specifier → approval → coder → cleaner → architect → hardender → QA → Done
```

- `specifier` writes deterministic Gherkin and the end-to-end QA procedures
  that will later drive independent user-interface verification.
- `coder` builds the acceptance pipeline and implements approved behavior with
  TDD, unit tests, and generated acceptance tests.
- `cleaner` performs local behavior-preserving cleanup, coverage improvement,
  CRAP and DRY analysis, and module-responsibility checks.
- `architect` improves boundaries and dependency direction and owns property
  testing support.
- `hardender` runs language mutation, soft Gherkin mutation, CRAP, and DRY
  gates and strengthens tests and edge handling.
- `QA` converts the specified QA procedures into executable checks, exercises
  the product through its user interface, fixes narrow defects, and performs
  final independent verification.

Because the specifier is the project-root role, its forward handoff is held in
**Attention** for operator approval before delivery to the coder. Later
handoffs move the board automatically. Cleaner and architect results propagate
back to earlier roles as configured. QA's terminal result propagates to all
earlier roles and moves the card to Done.

Batch mode lets the four downstream quality roles process compatible queued
handoffs together while keeping specification and implementation task-focused.

## Install and run

From the existing project that should receive the pack:

```sh
get-swarm-forge six-pack
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

- Change `swarmforge/swarmforge.conf` to change the six active roles, their
  backends, worktrees, or queue behavior.
- Change `swarmforge/roles/` to change the division of work.
- Keep six-pack additions in `project.prompt` or `local-*.prompt` articles.
- Put common runtime, dashboard, terminal, installer, or shared constitution
  changes on `main`, not here.
