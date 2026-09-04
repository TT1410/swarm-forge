# SwarmForge project-manager

The `project-manager` branch is a multi-project, multi-pack forge. One host
lieutenant and dashboard supervise projects under `projects/`; each project
runs a selected two-, four-, or six-pack swarm.

The repository's master branch is named
[`main`](https://github.com/unclebob/swarm-forge/tree/main). Read its
[README](https://github.com/unclebob/swarm-forge/blob/main/README.md) for the
SwarmForge overview, prerequisites, product comparison, and installation of
`get-swarm-forge`. This README covers only the project-manager forge.

![Project-manager dashboard](project-swarm.jpg)

## Structure

The project-manager branch supplies the forge host. During installation,
`get-swarm-forge project-manager` also downloads the three pack branches and
creates this layout:

```text
<forge>/
  swarm
  swarmforge/
    swarmforge.conf              # host lieutenant backend
    roles/
      lieutenant.prompt          # forge concierge instructions
    scripts/                     # dashboard and shared runtime
    constitution/articles/       # shared articles copied into projects
  packs/
    two-pack/                    # installed from the two-pack branch
    four-pack/                   # installed from the four-pack branch
    six-pack/                    # installed from the six-pack branch
  projects/                      # created or imported project repositories
```

`packs/` and `projects/` are installation/runtime content; the source branch
does not commit copies of the three packs. Re-run the installer when the forge
or its pack templates need to be refreshed from their branches.

The forge host and each open project have separate tmux sessions and runtime
state. A project also has its own `.worktrees/` role checkouts and
`.swarmforge/` handoff state.

## Host configuration and installed packs

The host configuration is
[`swarmforge/swarmforge.conf`](swarmforge/swarmforge.conf). With no active
`Lieutenant` line, the host defaults to Grok. Add a line such as this to select
the backend and pass extra arguments:

```conf
Lieutenant grok --yolo
```

The complete host form is `Lieutenant <backend> [backend arguments...]`.
Project configurations use the fixed-pack form documented on `main`:

```text
window[-invisible] <role> <backend> <worktree> [task|batch] [forward-only|back-one|back-all] [backend arguments...]
```

The lieutenant runs in the forge root and follows
`swarmforge/roles/lieutenant.prompt`. It is a concierge: it answers forge-level
chat, summarizes status, and suggests a pack or project. It does not implement
project work and does not follow the project engineering constitution.

Each installed pack is configured independently:

| Pack | Default workflow | Details |
|---|---|---|
| `two-pack` | `coder` → `cleaner` | [`two-pack` README](https://github.com/unclebob/swarm-forge/blob/two-pack/README.md) |
| `four-pack` | `specifier` → `coder` → `refactorer` → `architect` | [`four-pack` README](https://github.com/unclebob/swarm-forge/blob/four-pack/README.md) |
| `six-pack` | `specifier` → `coder` → `cleaner` → `architect` → `hardender` → `QA` | [`six-pack` README](https://github.com/unclebob/swarm-forge/blob/six-pack/README.md) |

The corresponding `packs/<pack>/swarmforge/swarmforge.conf` is the default for
new projects. New Project shows that configuration in an editable field, so a
project can customize backends, worktrees, receive modes, or CLI arguments
before creation. The resulting `projects/<name>/swarmforge/swarmforge.conf` is
that project's active configuration.

## Constitution and roles

The host and its projects use different instruction assemblies:

- The host reads only
  [`swarmforge/roles/lieutenant.prompt`](swarmforge/roles/lieutenant.prompt).
  That prompt makes it a forge concierge and explicitly excludes it from the
  project engineering constitution.
- A project agent reads the installed pack's `constitution.prompt`, then every
  article under that project's `swarmforge/constitution/articles/`, and then
  `swarmforge/roles/<role>.prompt`. The constitution governs every project
  role; the role prompt assigns one part of the pipeline.

Every created or refreshed project receives the shared articles carried by
this forge:

| Article | Shared responsibility |
|---|---|
| [`engineering.prompt`](swarmforge/constitution/articles/engineering.prompt) | Language, TDD, testability, acceptance tooling, and quality verification. |
| [`workflow.prompt`](swarmforge/constitution/articles/workflow.prompt) | Worktree boundaries, commit attribution, temporary files, and failure handling. |
| [`handoffs.prompt`](swarmforge/constitution/articles/handoffs.prompt) | Structured handoff creation, delivery, merge direction, batching, and completion. |

The chosen pack overlays its own `constitution.prompt`, differently named
local articles, and role prompts. Those local files define the pack-specific
shape and terminal handback rules; they cannot replace the three shared
article names. The two-, four-, and six-pack READMEs linked above describe
their configuration and each project role without duplicating that material
here.

## Install and start

After installing `get-swarm-forge` as described on `main`, run this in the
empty directory that will contain the forge:

```sh
get-swarm-forge project-manager
./swarm
```

The installer creates `packs/` and `projects/` and installs the host.
`./swarm` starts only the dashboard and host lieutenant. Project agents start
when a project is created or opened. Startup prints the local dashboard URL and
normally opens it in a browser.

Set `SWARMFORGE_OPEN_BROWSER=0` to leave the browser closed. Set
`SWARMFORGE_PREVENT_SLEEP=0` to disable the host sleep inhibitor.

## Project lifecycle

### Create

**New Project** accepts a name, mission, pack, and editable configuration. It
can create an empty project or clone a GitHub `owner/repo`. The forge then
overlays the selected pack, copies its shared runtime and constitution articles,
writes `mission.md`, records the selected pack, and starts the project swarm.
An empty project is initialized as a git repository; a GitHub import retains
the cloned repository and history.

Project names must be unique. If `projects/<name>/` already exists, creation is
refused and the existing directory is left unchanged.

### Open, close, and refresh

**Open Project** starts an existing directory under `projects/`. It reads the
pack recorded in that project's `.swarmforge/pack`, refreshes the shared runtime
and pack-owned prompts/articles from the forge, and preserves the project's own
`swarmforge/swarmforge.conf`.

**Close** stops one project's agent sessions and leaves its directory on disk.
Several projects can run at once; the dashboard stacks their boards and work
queues and aggregates their approvals and clarifications.

**Teardown** closes every open project and then stops the host lieutenant,
dashboard, and tmux sessions. It does not delete anything under `projects/`.

## Work lifecycle

1. **New Task** on a project creates a card in that pack's project-root lane
   and delivers the task to the role assigned to `master`: coder for two-pack,
   specifier for four- and six-pack.
2. Each agent accepts committed work, performs its role, and sends a durable
   handoff. Successful forward delivery moves the board card to the recipient's
   lane; configured backward copies merge results without moving the card.
3. On four- and six-pack projects, the specifier's first forward handoff is held
   in **Attention** for operator approval. Clarifications also appear there.
4. The last configured role sends the terminal result to earlier roles. The
   board then moves the card to Done.

The right-hand chat rail talks to the host lieutenant, not a project agent.
Open a role from **Work Queue** to inspect that project's live tmux pane.

For shared handoff and runtime details, see the
[`main` handoff protocol](https://github.com/unclebob/swarm-forge/blob/main/swarmforge/handoff-protocol.md).

## Runtime components and generated state

| Component | Responsibility |
|---|---|
| `forge.*` | Create or clone projects, overlay the selected pack, refresh managed files, and start or stop project swarms. |
| `swarmforge.*` | Parse host and project configuration, construct worktrees and tmux sessions, and launch backends. |
| `handoffd.*` and handoff helpers | Queue, deliver, audit, merge, and complete committed work. |
| `pack_board.*`, `pack_web.*`, and dashboard assets | Maintain project boards and expose chat, Attention, pane inspection, and lifecycle controls. |
| Terminal adapters, watchdog, and cleanup scripts | Present requested windows, monitor sessions, and shut down the forge cleanly. |

The forge root's `.swarmforge/` contains host sessions, open-project records,
and dashboard process state. Each open project owns its own `.swarmforge/`
role/session maps, handoff queues, board, approvals, clarifications, and daemon
state, plus generated role checkouts under `.worktrees/`. `packs/` contains
installed templates; `projects/` contains durable project repositories. Close
and Teardown remove processes, not those repositories.

## Changing this branch

- Change `swarmforge/swarmforge.conf` or
  `swarmforge/roles/lieutenant.prompt` to change the forge concierge.
- Change a pack on its own branch; the project-manager installer obtains pack
  templates from those branches.
- Put common runtime, dashboard, terminal, installer, or shared constitution
  changes on `main` first, then carry the required host copies here.
- Treat each installed pack configuration and each project's preserved copy as
  the source of truth for its active topology.
