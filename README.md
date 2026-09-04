# SwarmForge Lieutenant

The `lieutenant` branch is a single-pipeline forge. One host lieutenant runs a
dashboard, plans work, and dispatches cards across any number of project swarms.
It is a forge installed around projects, not a pack installed inside one
existing project.

The repository's master branch is named
[`main`](https://github.com/unclebob/swarm-forge/tree/main). Read its
[README](https://github.com/unclebob/swarm-forge/blob/main/README.md) for the
SwarmForge overview, prerequisites, product comparison, and installation of
`get-swarm-forge`. This README covers only the structure and operation of the
lieutenant forge.

![Lieutenant forge dashboard](project-swarm.jpg)

## Structure

An installed lieutenant forge has two levels:

| Level | Purpose | Configuration |
|---|---|---|
| Forge host | Runs the dashboard and one lieutenant agent. The lieutenant plans and dispatches; it does not implement project work. | `swarmforge/swarmforge.conf` |
| Project swarm | Runs the engineering agents for one directory under `projects/`. Every new project begins with this branch's project-pack. | Default: `.swarmforge/project-pack/swarmforge/swarmforge.conf`; active copy: `projects/<name>/swarmforge/swarmforge.conf` |

The relevant source and installed layout is:

```text
<forge>/
  swarm
  swarmforge/
    swarmforge.conf              # host lieutenant backend and arguments
    roles/
      lieutenant.prompt          # planner and dispatcher instructions
    scripts/                     # shared runtime and dashboard
    constitution/articles/       # shared articles copied into projects
  .swarmforge/
    project-pack/                # committed default project template
      swarmforge/
        swarmforge.conf          # card routes, agents, and worktrees
        constitution.prompt
        roles/
          specifier.prompt
          coder.prompt
          cleaner.prompt
          architect.prompt
          hardender.prompt
          QA.prompt
  projects/                      # generated projects; ignored by forge git
```

Most other files under `.swarmforge/` are generated host state. Inside a
project, `.swarmforge/` is runtime state and `.worktrees/` contains the role
worktrees; both are ignored by that project's git repository. The exception at
the forge level is `.swarmforge/project-pack/`, which is committed because it
is the template for new and reopened projects.

The runtime scripts, shared constitution articles, handoff machinery, tmux
behavior, and installer are common SwarmForge infrastructure. Their canonical
home and documentation are on [`main`](https://github.com/unclebob/swarm-forge/tree/main);
this branch carries the copies required for a standalone lieutenant install.

## Host and project configuration

The host configuration contains one `Lieutenant` line. In this branch it is:

```conf
Lieutenant grok
```

That line selects the host agent backend and any additional CLI arguments. The
host lieutenant runs in the forge root, has its own tmux session, and follows
`swarmforge/roles/lieutenant.prompt`. It is deliberately outside the project
engineering constitution.

The committed project template is
[`.swarmforge/project-pack/swarmforge/swarmforge.conf`](.swarmforge/project-pack/swarmforge/swarmforge.conf).
It is the authority for the default card routes, project agent backends,
worktree assignments, receive modes, and backward propagation.

### Default card routes

| Card type | Route | Intended use |
|---|---|---|
| `utility` | `coder` → `cleaner` → Done | A small implementation or maintenance task whose card text is the specification; no Gherkin or headed QA. |
| `component` | `specifier` → `coder` → `cleaner` → `architect` → `hardender` → Done | A behavior change with Gherkin specification and the engineering quality pipeline, but no headed QA suite. |
| `QA` | `specifier` → `coder` → `cleaner` → `architect` → `hardender` → `QA` → Done | A behavior change that also needs specified and executable end-to-end UI verification. |
| `review` | `cleaner` → `architect` → `hardender` → `QA` → Done | A brownfield review and hardening pass without inventing new behavior. QA runs an existing `qa/` suite or passes through if none exists. |

The roles divide responsibility as follows:

- `specifier` turns intent into Gherkin; on `QA` cards it also specifies the
  headed end-to-end QA procedures.
- `coder` implements behavior with TDD, unit tests, and generated acceptance
  tests where the card route requires them.
- `cleaner` performs local behavior-preserving cleanup and quality analysis.
- `architect` improves boundaries and dependency direction and owns property
  testing support.
- `hardender` performs mutation hardening and the final non-headed quality
  gates.
- `QA` executes independent user-interface verification and makes narrow fixes
  for failures it finds.

### Default worktrees and queue modes

| Role | Working directory | Receive mode | Propagation |
|---|---|---|---|
| `specifier` | project root (`master`) | task | forward only |
| `coder` | `.worktrees/coder` | task | forward only |
| `cleaner` | `.worktrees/cleaner` | batch | back one |
| `architect` | `.worktrees/architect` | batch | back all |
| `hardender` | `.worktrees/hardender` | batch | forward only |
| `QA` | `.worktrees/QA` | batch | back all |

In a project configuration, `master` means the main project checkout on its
current branch. It does not require that git branch to be named `master` and is
unrelated to this repository's `main` branch.

New Project preloads the template configuration into an editable **Config**
field, so a project can change its routes, backends, or worktrees before it is
created. On a later **Open Project**, the forge refreshes that project's
managed scripts, shared articles, constitution entry point, and role prompts
from the current forge, but preserves the project's own
`swarmforge/swarmforge.conf`.

## Install and start

After installing `get-swarm-forge` as described on `main`, run this in the
empty directory that will contain the forge:

```sh
get-swarm-forge lieutenant
./swarm
```

`get-swarm-forge lieutenant` installs the host, the project-pack, and an empty
`projects/` directory. `./swarm` starts only the dashboard and host lieutenant;
project agents start when a project is created or opened. Startup prints the
local dashboard URL and normally opens it in a browser.

Set `SWARMFORGE_OPEN_BROWSER=0` to leave the browser closed. Set
`SWARMFORGE_PREVENT_SLEEP=0` to disable the host sleep inhibitor.

## Project lifecycle

### Create a project

**New Project** accepts a name, mission, and project configuration. It can
either create an empty project or clone a GitHub `owner/repo`. Creation happens
in a staging directory; the forge overlays its shared runtime and project-pack,
writes `mission.md`, establishes the runtime ignore rules, commits a clean
managed baseline, moves the result to `projects/<name>/`, and starts its swarm.

If `projects/<name>/` already exists, the dashboard asks before replacing it.
Replacement permanently clears that directory and keeps no backup.

### Open, close, and refresh

**Open Project** starts an existing directory under `projects/`. Before
startup, it refreshes the managed SwarmForge tree from the host and project-pack
and commits any resulting managed changes. Product files, `mission.md`, and the
project's configuration are preserved.

**Close** stops that project's agent sessions and handoff daemon but leaves the
project directory in place. Several projects may be open at once. The dashboard
aggregates their boards, work queues, approvals, clarifications, failures, and
agent status.

**Teardown** closes every open project and then stops the host lieutenant,
dashboard, and tmux sessions. Project directories remain on disk. After the
next `./swarm`, projects remain stopped until they are opened again.

## Work lifecycle

1. The lieutenant reads a new project's `mission.md`, watches its live board,
   proposes cards with types and dependencies, and asks the operator to approve
   the plan. It never edits the product itself.
2. **New Task** defaults to `LT`. An `LT` task sends a directive to the
   lieutenant without creating a card. Selecting `utility`, `component`, `QA`,
   or `review` creates a waiting card of that type and notifies the lieutenant.
3. The lieutenant starts an approved waiting card by moving it into the first
   lane of its configured route. It may run independent cards in parallel when
   their starting lanes are free.
4. Each role accepts its work, merges the committed handoff, performs its owned
   part of the job, commits, and hands the card to the next role. The handoff
   daemon moves the board card when delivery succeeds. Batch roles may accept
   compatible queued cards together.
5. Because `specifier` uses the project root, its forward handoff on
   `component` and `QA` routes is held in **Attention** for operator approval
   before delivery to `coder`. Clarification requests and repeated delivery
   failures also appear in Attention.
6. The last role sends the route's terminal handoff to every configured role
   before it in window order. After that complete delivery, the board moves the
   card to **Done**.

The chat rail talks only to the host lieutenant. Agent names in **Work Queue**
open live captures of project-agent panes; the agents themselves continue to
run in tmux.

For the durable handoff format, audit gate, delivery states, retries, and merge
rules, see the
[`main` handoff protocol](https://github.com/unclebob/swarm-forge/blob/main/swarmforge/handoff-protocol.md).

## Changing the lieutenant branch

- Change `swarmforge/swarmforge.conf` or
  `swarmforge/roles/lieutenant.prompt` to change the host lieutenant.
- Change `.swarmforge/project-pack/swarmforge/swarmforge.conf` and its `roles/`
  prompts to change the default project pipeline.
- Put common runtime, installer, terminal, dashboard, or shared constitution
  changes on `main` first, then carry the required copies into this branch.
- Treat the two configuration files as the source of truth. This README should
  explain their structure and intent, not replace them.
