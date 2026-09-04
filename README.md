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

## Configuration

The branch's [`swarmforge/swarmforge.conf`](swarmforge/swarmforge.conf) is the
authority for the active agents, worktrees, receive modes, and propagation.

| Role | Default backend | Working directory | Receive mode | Propagation |
|---|---|---|---|---|
| `coder` | Grok | project root (`master`) | task | forward only |
| `cleaner` | Codex | `.worktrees/cleaner` | batch | back one |

`master` means the main project checkout on its current branch; the branch does
not need to be named `master`.

Each non-comment line uses the shared pack form:

```text
window[-invisible] <role> <backend> <worktree> [task|batch] [forward-only|back-one|back-all] [backend arguments...]
```

File order defines the forward route. Omitted receive and propagation fields
mean `task` and `forward-only`; the cleaner line overrides both defaults and
passes `--yolo` to Codex.

## Constitution

[`swarmforge/constitution.prompt`](swarmforge/constitution.prompt) is the
instruction entry point and takes precedence over its articles. At install
time, the effective `swarmforge/constitution/articles/` contains:

| Source | Article | Purpose in this pack |
|---|---|---|
| `main` | `engineering.prompt` | Shared language, TDD, testability, tooling, and verification law. |
| `main` | `workflow.prompt` | Shared worktree, commit, scratch-file, and failure rules. |
| `main` | `handoffs.prompt` | Shared durable handoff and merge protocol. |
| `two-pack` | [`project.prompt`](swarmforge/constitution/articles/project.prompt) | Declares the two-role shape, unit-test-only constraints, cleaner batching, terminal handback handling, and local state locations. |

Agents read every article before reading their role prompt. The local article
narrows this pack by forbidding Gherkin, acceptance tests, Gherkin mutation,
and property testing; it does not restate the common rules owned by `main`.

## Roles

| Prompt | Ownership |
|---|---|
| [`coder.prompt`](swarmforge/roles/coder.prompt) | Implements the requested behavior with focused TDD and unit tests, then forwards the committed slice. It leaves broad cleanup and quality analysis to the cleaner. |
| [`cleaner.prompt`](swarmforge/roles/cleaner.prompt) | Consumes compatible coder work in batches and owns cleanup, coverage, CRAP, DRY, architecture, dependency direction, and mutation hardening without introducing behavior. |

The configuration determines when and where a role runs; the constitution sets
rules shared by both agents; each role prompt sets that agent's exclusive
responsibilities and handoff behavior.

## Workflow

```text
New Task → coder → cleaner → Done
```

This pack intentionally has no specifier, Gherkin, generated acceptance suite,
property-testing role, or headed QA role. The card text is the specification,
and verification is language-local unit testing plus the cleaner's quality
checks.

When the cleaner finishes, it sends the terminal result back to the coder. The
handoff daemon merges that result, marks the card Done, and leaves both role
branches synchronized with the completed work. There is no specification
approval gate in this pack.

## Runtime components and generated state

The pack branch owns no runtime implementation. `get-swarm-forge` supplies
`swarmforge/scripts/` from `main`, while this branch supplies the launcher,
configuration, constitution entry point, local article, and role prompts.

The composed runtime parses the configuration, creates the cleaner worktree,
copies managed instructions and helpers into it, starts both tmux sessions,
and starts the handoff daemon and dashboard. `.swarmforge/` holds generated
role/session maps, handoff queues, board cards, daemon state, approvals, and
pane metadata. `.worktrees/cleaner/` is a generated git checkout. Neither is a
second source of configuration or product policy.

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
