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

## Host and packs

The host configuration is
[`swarmforge/swarmforge.conf`](swarmforge/swarmforge.conf). With no active
`Lieutenant` line, the host defaults to Grok. Add a line such as this to select
the backend and pass extra arguments:

```conf
Lieutenant grok --yolo
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

## Changing this branch

- Change `swarmforge/swarmforge.conf` or
  `swarmforge/roles/lieutenant.prompt` to change the forge concierge.
- Change a pack on its own branch; the project-manager installer obtains pack
  templates from those branches.
- Put common runtime, dashboard, terminal, installer, or shared constitution
  changes on `main` first, then carry the required host copies here.
- Treat each installed pack configuration and each project's preserved copy as
  the source of truth for its active topology.
