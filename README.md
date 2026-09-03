<p align="center" style="color: red; font-weight: bold; font-size: 2em; font-style: italic; text-decoration: underline;">
Do not spend any money on a bankrbot SWARM token.
</p>

# SwarmForge

**A disciplined tmux-based agent orchestration platform that turns swarms of AI agents into reliable, professional software engineers.**

## Intent

This checkout is the **`lieutenant`** forge: a multi-project host plus one
pipeline template at `.swarmforge/project-pack`. `main` is documentary and
the source of pack-only scripts and shared constitution articles. It is not
a product you run.

`get-swarm-forge` requires a product name:

- `two-pack` / `four-pack` / `six-pack` — compose that pack into the current
  directory (scripts from `main`, pack files from that branch).
- `project-manager` — multi-pack forge (`packs/`, New Project pack radios).
- `lieutenant` — this host and `.swarmforge/project-pack`.

SwarmForge is an agent coordination system that facilitates communication between agents working in different git worktrees.

It provides a shared structure for role-specific prompts, worktree assignment, tmux sessions, and message passing so multiple agents can collaborate on the same project without stepping on each other.

## Branches

Pack templates live on dedicated branches. Each branch contains a
`swarmforge/swarmforge.conf`, local constitution articles, and the role prompts
for one workflow. The configuration file—not this README—is the authority for
that pack's current roles, routes, worktrees, agent backends, receive modes, and
propagation modes. `get-swarm-forge <pack>` composes one pack into `.`;
`get-swarm-forge project-manager` copies its available templates under `packs/`;
and this `lieutenant` host instantiates `.swarmforge/project-pack`.

### `two-pack`

`two-pack` is the smallest workflow template. Use it for work that benefits
from a short implementation and refinement path. Inspect that branch's
`swarmforge/swarmforge.conf` for its current topology.

### `four-pack`

`four-pack` is the compact specification workflow template. Use it when work
needs specification and multiple engineering passes without the largest
topology. Inspect that branch's `swarmforge/swarmforge.conf` for its current
topology.

### `six-pack`

`six-pack` is the largest workflow template. Use it for work that benefits from
more independently owned specification, engineering, and verification passes.
Inspect that branch's `swarmforge/swarmforge.conf` for its current topology.

### `simple-windows`

`simple-windows` is a tag on `main`, not a workflow branch. It marks the last commit before the pack cockpit: one Terminal window per role, no dashboard, and no `window-invisible`. It does not sit on `squad` or the other squad branches.

```sh
git fetch origin tag simple-windows
git checkout simple-windows
```

Or download that snapshot:

```sh
curl -L "https://github.com/unclebob/swarm-forge/archive/refs/tags/simple-windows.tar.gz" | tar -xz --strip-components=1
```

`simple-windows` is not a `get-swarm-forge` product. Pack-only installs are `get-swarm-forge two-pack` (or `four-pack` / `six-pack`).

## Prerequisites

SwarmForge runs locally. Before starting a runnable branch, make sure the target machine has:

- `zsh`
- `git`
- `tmux`
- Babashka (`bb`)
- At least one configured agent backend, such as `codex`, `claude`, `copilot`, or `grok`

## Getting Started

Install the `get-swarm-forge` helper somewhere on your `PATH`, such as `~/cmds` or `~/bin`:

```sh
mkdir -p ~/cmds
cp get-swarm-forge ~/cmds/get-swarm-forge
chmod +x ~/cmds/get-swarm-forge
```

Make sure that utility directory is on your shell `PATH`, then run the helper in
the directory that will be the **forge** (not a single project):

```sh
get-swarm-forge lieutenant
```

`get-swarm-forge lieutenant` installs this host and `.swarmforge/project-pack`.
It does not install `packs/two-pack` and friends. For a pack-only checkout use
`get-swarm-forge six-pack` (or `two-pack` / `four-pack`). For the multi-pack
forge use `get-swarm-forge project-manager`.

Start the host dashboard:

```sh
./swarm
```

`./swarm` starts the dashboard and the **lieutenant** only. It does not start
project agents. Startup prints a **Dashboard:** URL (also written to
`.swarmforge/dashboard-url`) and opens it in the browser when `open` is
available.

Create a project from the dashboard with **New Project** (name, mission,
optional GitHub `owner/repo`). There are no pack radios: every project uses the
pipeline copied from `.swarmforge/project-pack`. New Project builds the result
in a staging directory and commits a clean baseline before agents or worktrees
start. A GitHub import keeps the cloned product files but replaces any older
mission and SwarmForge-managed files with the submitted mission and the forge's
current copies. If `projects/<name>/` already exists, nothing is changed until
you confirm an alert that clearly says the directory will be permanently
cleared and replaced without a backup. Cancel leaves it untouched. **Open
Project** starts an existing directory under `projects/`. **Close** stops that
project and leaves its directory in place.

Set `SWARMFORGE_OPEN_BROWSER=0` before `./swarm` to skip the browser open. The dashboard still starts; visit the printed URL.

To stop everything, click **Teardown** in the dashboard header and confirm.
That closes every open project, then kills the lieutenant, tmux, and the
dashboard. Directories under `projects/` stay on disk. After a later
`./swarm`, nothing is running until you Open Project.

While a swarm is active, SwarmForge tries to prevent the host from sleeping. On macOS it uses `caffeinate`; on Linux it uses `systemd-inhibit` when available. Display lock or manual sleep can still interrupt agents depending on the OS. Set `SWARMFORGE_PREVENT_SLEEP=0` before `./swarm` to disable this behavior.

## Pack Cockpit

![SwarmForge dashboard](project-swarm.jpg)

The pack cockpit is a local web dashboard served from `main`'s scripts
(`pack_web`). It is the forge operator surface: several projects can run at
once. Chat talks to the **lieutenant**, who oversees the whole swarm, not to
a project agent.

Layout, top to bottom then left to right:

- **Header** — SwarmForge, live marker, **New Project**, **Open Project**, **Teardown**.
- **Attention** — human gates and repeated delivery failures from every open
  project. Each row names the work as underlined **`project`/`task`** (project
  bold).
- **Board** — one band per open project, split by a horizontal bar. Each band has a header (**New Task**, **Close**) and that pack's swimlanes plus **Done**.
- **Work Queue** — the same project stack on the right; the two sides scroll independently.
- **Chat** — follow-ups to the lieutenant. Pending replies show live green `|` status under the request.

### Operating the dashboard

**New Project.** Name the project and enter its mission (`mission.md` at the
project top). Check **github repo** and type `owner/repo` to import the product
history. The directory name is the repository's last path segment. If that
directory exists, the first request leaves it unchanged and asks whether to
permanently clear and replace it; no backup is retained. The imported product
is then overlaid with the submitted mission and the forge's current managed
SwarmForge files, committed, and started. The project always uses this forge's
project-pack configuration.

**Open Project.** Menu of directories under `projects/`. Opening refreshes the
managed SwarmForge data from `.swarmforge/project-pack`, preserving the mission
and project configuration, then commits that baseline before starting the
configured roles. A project is shown as Starting until its tmux sessions and
handoff daemon are verified; Close similarly shows Stopping until shutdown is
verified. A failure is shown as Error instead of incorrectly claiming the
project is open or closed. Recorded state is reconciled with actual processes
after a forge restart.

**Start a task.** Click **New Task** on that project's header bar, give a
short stable **name**, a **type**, and the **task** text, then **OK**. **LT**
sends the name and text to the lieutenant and does not create a card. The other
choices come from the project's configured `card` routes and create a
**waiting** card. The lieutenant starts it with `pack_board move` into that
route's first lane (no Attention) when the plan says so. It does not queue a
start note.

**Talk to the lieutenant.** Type in the chat composer (Enter sends,
Shift+Enter newline). The dashboard stores a durable request and injects
`[id] text` into the lieutenant pane. While the reply is pending, up to
two green `|` status lines appear under the request (same filtering as
card status) and replace each other as the lieutenant thinks. The chat
rail stays put unless the scroller is already at the bottom; then new
lines stay pinned to the bottom. The lieutenant backend and its CLI arguments
come from the host `swarmforge/swarmforge.conf` `Lieutenant` line.

**Approve a gated handoff.** When a workflow requests operator approval,
Attention shows **Approval**, the underlined **`project`/`task`** pair, a
**Documents** menu for artifacts, **Approve**, and **Reject**. A new Attention
row plays a short chime. Approve releases the handoff; Reject leaves the card
with its current role and notifies that role. Approval policy belongs to the
project's workflow instructions.

**Answer a clarification.** If an agent needs a human answer, Attention shows **Request clarification**, the question, and a text box. Submit injects the answer into that agent's pane. Do not use Approve/Reject for this.

**Watch the board.** Cards move when `handoffd` delivers a forward
`git_handoff`. Click a card to open its task body in a resizable window. The
card can show the agent's latest status sentence (the last pane line that
contains `I'm`). Merge-only copies from `back-one` or `back-all` do not move the
card. The last role on the card's configured route sends the terminal handoff
to every configured role earlier than it in window order; only that complete
recipient set moves the card to **Done**. A delivery is stored for every
recipient and the board is updated before the sender copy is archived. Temporary
failures are retried with backoff, and a repeatedly failing delivery appears in
Attention. Notification failure is separate: the stored handoff remains sent
and its wake-up is retried when the session returns.

**Inspect an agent.** Click a Work Queue role name, or **Open** in the header / chat rail, to pop a live pane capture. Those windows are growable. Agents themselves stay in tmux; these views do not replace the dashboard.

**Stop.** **Teardown** asks for confirmation, then kills the swarm. If the dashboard says **Swarm disconnected**, the UI is no longer talking to a live pack.

## What SwarmForge Does

SwarmForge is a lightweight, tmux-based orchestration layer that:

- Launches a **config-driven swarm** from a project-local `swarmforge/swarmforge.conf`
- Creates one tmux session per configured role
- Serves a **pack cockpit** in the browser and, by default on the pack branches, skips a Terminal window per role (`window-invisible`)
- Reads behavior from project-local `swarmforge/roles/<role>.prompt` files plus a layered `swarmforge/constitution.prompt`
- Supports per-role backends such as `claude`, `codex`, `copilot`, or `grok`
- Puts the shared `swarmforge/scripts/` directory on each agent's `PATH`, including handoff helpers for active swarm communication
- Creates git worktrees under `.worktrees/` for roles assigned to dedicated worktree names
- Initializes a git repository in a new working directory when needed
- Keeps all swarm state local to the working directory in `.swarmforge/`

## Core Features

- **Config-Driven Topology** — The swarm shape comes from `swarmforge/swarmforge.conf`, not hardcoded shell variables.
- **Project-Local Roles** — Each role is defined by `swarmforge/roles/<role>.prompt` in the working tree being orchestrated.
- **Layered Constitution** — `swarmforge/constitution.prompt` directs agents to read article files under `swarmforge/constitution/articles/`.
- **Backend Selection Per Role** — A role can launch `claude`, `codex`, `copilot`, or `grok`.
- **Pack Cockpit** — A local dashboard for New Task, Attention, the board, Work Queue, lieutenant chat, and Teardown.
- **Observable Swarm** — Watch agents from the dashboard; open a live pane when you need the raw session. Optional `window` lines still open a Terminal surface per role.
- **Self-Hosted & Lightweight** — Runs locally in tmux and a browser, with optional Terminal windows.

## Constitution Structure

Each runnable branch contains a `swarmforge/` directory with this general layout:

```text
swarmforge/
  swarmforge.conf
  constitution.prompt
  constitution/
    articles/
      project.prompt
      local-engineering.prompt
      local-workflow.prompt
      ...
  roles/
    <role>.prompt
    ...
```

`constitution.prompt` is the entry point. Runnable branches normally use it to tell agents to read every file in `swarmforge/constitution/articles/`.

Shared default articles live on `main` under:

```text
swarmforge/constitution/articles/
  engineering.prompt
  handoffs.prompt
  workflow.prompt
```

`get-swarm-forge` copies shared articles from `main` for pack-only
installs, and from the named manager for a forge. Packs must not ship
`engineering.prompt`, `workflow.prompt`, or `handoffs.prompt`. Those
filenames are law from `main`.

Pack-specific additions and exceptions use explicit local filenames:

- `project.prompt` for the workflow's project shape and local topology.
- `local-engineering.prompt` for workflow-specific engineering rules.
- `local-workflow.prompt` for workflow-specific flow rules.

The `local-*.prompt` naming convention means "add to or specialize the shared default article for this pack." Use it for extra requirements, exceptions, or narrower instructions. Do not replace a shared article by committing the same filename.

For example, `main` provides `workflow.prompt`, while `six-pack` adds `local-workflow.prompt` for QA-specific handoff behavior.

## Roles

Each role in `swarmforge/swarmforge.conf` maps to a corresponding `swarmforge/roles/<role>.prompt` file.

## How It Works

In a runnable branch:

1. SwarmForge reads `swarmforge/swarmforge.conf`.
2. The project is already composed by `get-swarm-forge`: shared helper scripts and `engineering.prompt` / `workflow.prompt` / `handoffs.prompt` from `main`, plus pack-owned files (`swarm`, `swarmforge.conf`, role prompts, `constitution.prompt`, `project.prompt`, `local-*.prompt`). Shared article filenames are never taken from the pack.
3. Startup uses that composed `swarmforge/constitution/articles/` tree. Pack specialization is `local-*.prompt` and other pack-owned files, not a same-name override of a shared article.
4. Startup validates the configured role prompts, helper scripts, and terminal adapters.
5. If the target directory is not already a git repository, startup initializes one and creates the first commit.
6. Startup creates one git worktree per configured role under `.worktrees/`, unless the role is assigned to `master` or `none`.
7. Startup replaces each role worktree's managed `swarmforge/scripts/`,
   `swarmforge/roles/`, and `swarmforge/constitution/` trees with exact copies
   of the project's current trees. Files removed or renamed in the project are
   therefore removed from reused worktrees, while product files and runtime
   state outside those trees are preserved. The local scripts directory is put
   on each agent's `PATH`.
8. SwarmForge creates tmux sessions, launches each configured backend in its assigned worktree, starts the pack dashboard, and opens a Terminal surface only for `window` (visible) roles.
9. Startup starts an OS-specific sleep inhibitor when one is available, and cleanup stops it with the swarm.
10. Roles communicate through daemon-delivered handoff files. Agents create validated drafts with `swarm_handoff.sh`, accept work with `ready_for_next.sh`, and complete work with `done_with_current.sh`.

## Handoff Protocol

Startup syncs the shared helper scripts into every role worktree under `swarmforge/scripts/` and puts that local directory on the agent's `PATH`. Agents do not send tmux messages directly. The launcher starts `handoffd.bb`, which owns tmux socket access, watches each agent outbox, copies validated handoff files into recipient inboxes, and sends only generic wake-up notifications.

Agents interact with handoffs through three helper scripts:

- `swarm_handoff.sh <draft-file>` validates outbound handoffs. Notes queue
  immediately; Git handoffs use the audit gate described below.
- `ready_for_next.sh` accepts work using the role's configured receive mode.
- `done_with_current.sh` completes the current task or batch using the role's configured receive mode.

Outbound drafts use one of two message types. A git handoff points the recipient at a committed state. The agent does not type a SHA; `swarm_handoff.sh` fills `commit` from the sender worktree HEAD (exactly 10 hexadecimal characters, resolved to a single commit). The first valid Git handoff call returns `AUDIT_REQUIRED` without queueing or completing the sender's current inbox item, and increments the task card's audit counter. The sender must re-read the complete task and referenced sources, trace every requirement and constraint to role-appropriate work and evidence, examine boundaries and failure cases, fix every finding, rerun applicable checks, and repeat the audit. Only an unchanged second call queues the handoff without another increment, after which any required approval is requested. A changed draft, task, sender, recipient set, or commit invalidates the earlier audit and creates a new counted challenge.

```text
type: git_handoff
to: <role>[,<role>...]
priority: NN
task: <short-stable-task-name>
```

A note is one short freeform message:

```text
type: note
to: <role>[,<role>...]
priority: NN
message: <one line, max 80 chars>
```

The helper generates the delivered payload. Agents do not write long handoff bodies, branch names, queue filenames, or tmux commands. If the sender's conf has `back-one` or `back-all`, the helper also writes the merge-only copies; agents do not list those earlier roles on `to:`.

Recipient agents run `ready_for_next.sh` when notified or after restart. It dispatches to the task or batch helper configured for that role. If it prints `NO_TASK`, they stop waiting for work. If it prints `TASK: <path>`, they treat the printed `TASK_NAME` and `PAYLOAD` as the task. If it prints `BATCH: <path>`, they process the printed `BATCH_ITEM` entries in helper-delivered order. If a wake-up arrives while an agent is already working, it can ignore the wake-up. `done_with_current.sh` completes the current item only: it prints `MAIL_WAITING` when more mail is queued, or `NO_TASK`. The agent then runs `ready_for_next.sh` if mail is waiting.

When a role accepts work, the receive helper copies the operator's
`tasks/<task-name>.md` into that role's worktree when necessary and commits the
current document before work begins. A Git handoff is rejected if its commit
does not contain that current task document. This keeps the original operator
intent in every forward and terminal merge, including utility and review cards
whose first role does not run on `master`.

The durable handoff files and lifecycle headers replace the old logbook and
resend queue. Runtime handoff state lives under `.swarmforge/handoffs/` in each
worktree, with `outbox`, `sent`, `failed`, and `inbox` subdirectories. Temporary
delivery failures remain in the outbox with retry metadata; after three failed
attempts they are also reported in dashboard Attention while retries continue.
Malformed handoffs and unknown recipients are permanent failures. Wake-up
failures use a separate durable retry queue and never undo a completed
delivery. Agents should not hand-edit, merge, stage, or commit handoff runtime
state. See [swarmforge/handoff-protocol.md](swarmforge/handoff-protocol.md) for
the full protocol.

## Committed and Ignored Files

The project repository commits the product, `mission.md`, task documents, and
the project-owned `swarmforge/` configuration, prompts, constitution, and helper
scripts. Git handoffs name a commit, so these committed documents are what a
receiving role merges. In particular, `tasks/<task-name>.md` carries the
operator's current task intent. A receiver then re-reads its own mirrored role
and constitution instructions; runtime inbox files are transport state, not
committed work instructions.

Generated runtime data is deliberately ignored. In a project, SwarmForge owns
a marked ignore block for `/.swarmforge/` and `/.worktrees/`, while preserving
unrelated project ignore rules. In the forge repository, `/projects/`,
`/.worktrees/`, and runtime entries under `/.swarmforge/` are ignored, with
`/.swarmforge/project-pack/` explicitly kept visible and committed. The
installer repairs older SwarmForge-managed rules to this policy.

Startup installs a marked combined `.git/hooks/commit-msg` hook. When a project
already has that hook, SwarmForge preserves it as
`commit-msg.before-swarmforge`, applies the SwarmForge byline first, and then
runs the preserved executable hook with Git's original arguments and
environment. Repeated startup does not wrap it again. To remove SwarmForge's
dispatcher and restore the prior hook, run:

```sh
bb swarmforge/scripts/swarmforge.bb --remove-hooks .
```

Removal refuses to replace a `commit-msg` hook that someone changed after
SwarmForge installed it.

## The `swarmforge.conf` File

`swarmforge/swarmforge.conf` defines card routes and the swarm window by window.
It is the sole authority for current role and agent assignments. Its line forms
are:

```conf
card <type> <role> [<role>...]
window-invisible <role> <agent> <worktree> [task|batch] [forward-only|back-one|back-all] [extra-cli-args...]
window <role> <agent> <worktree> [task|batch] [forward-only|back-one|back-all] [extra-cli-args...]
```

Each `card` line defines one New Task type and its ordered active route. The
first role is its starting lane, each following role is the next handoff
destination, and the last role completes it. A route must be nonempty, contain
no duplicate roles, and name only roles defined by window lines. Startup
validates all routes and writes the shared normalized description used by the
dashboard, board, handoff validator, and daemon. Window order—not route order—
determines which earlier roles receive a terminal result.

`window-invisible` starts the agent in tmux without a Terminal window (the pack default). `window` also opens a Terminal surface for that role.

The optional receive mode defaults to `task`. Use `batch` for roles that should consume queued handoffs that share priority, card type, and reverse/forward with the first file as one batch. Equal priority of a different type or direction stays queued.

The optional propagation token defaults to `forward-only`. The card still
follows the forward send to the next role on its configured route.

- `forward-only` — no extra copies.
- `back-one` — also queue a merge-only copy to the previous role on this card's
  configured route.
- `back-all` — also queue merge-only copies to every earlier role on this
  card's configured route.

Those extra copies do not move the card. The recipient merges the copy and keeps working; it does not hand that copy onward. The card goes Done only when the last role **on this card** sends a terminal `git_handoff`.

The **host** configuration sets its lieutenant independently:

```conf
Lieutenant <agent> [extra-cli-args...]
```

Fields after the agent name are passed to the lieutenant CLI. The active choice
belongs in the host configuration rather than in this README.

For example, a project could define a three-role change route and a separate
review route without making either one SwarmForge's built-in roster:

```conf
card change lead implement verify
card review verify
window-invisible lead codex master
window-invisible implement claude implementation task back-one
window-invisible verify copilot verification batch back-all
```

Any fields after receive-mode and the propagation token are passed directly to the agent CLI as additional arguments. If you omit those tokens, extra arguments may start at the fifth field:

```conf
window coder copilot wt-coder --yolo
window architect claude wt-arch task --dangerously-skip-permissions
```

You can define as many windows as your project needs. Each `<role>` maps to
`swarmforge/roles/<role>.prompt`. This lets each project choose its own swarm
shape instead of being locked to a fixed set of roles.

Illustrative configuration (not a statement of current assignments):

```conf
card change lead implement verify
card check verify
window-invisible lead <agent> master
window-invisible implement <agent> implementation task back-one <cli-arg>
window-invisible verify <agent> verification batch back-all
```

In the example above, the agents run in these worktrees:

- `lead` -> main working directory on `master`
- `implement` -> `.worktrees/implementation`
- `verify` -> `.worktrees/verification`

If a window uses `master` as its worktree name, SwarmForge does not create `.worktrees/master`; that role runs in the main working directory on the `master` branch.

## tmux Behavior

SwarmForge uses a project-specific tmux socket recorded in `.swarmforge/tmux-socket`, so each project swarm is isolated from other tmux sessions. It also honors tmux `base-index` and `pane-base-index` settings when launching agents and sending notifications, so configurations that number windows or panes from `1` work without requiring users to change their tmux preferences.

## Terminal Behavior

Pack branches use `window-invisible`, so this adapter does not open a window per role. Visible `window` lines still open trackable terminal windows or tabs through a small terminal backend adapter.

Default detection:

- If AppleScript is available, SwarmForge opens macOS Terminal.app windows.
- Otherwise, if `wt.exe` is available, SwarmForge opens Windows Terminal windows.
- Otherwise, SwarmForge attaches the cleanup tmux session in the current shell.

After copying a runnable branch, set `SWARMFORGE_TERMINAL` to override detection:

```sh
SWARMFORGE_TERMINAL=ghostty ./swarm
SWARMFORGE_TERMINAL=terminal-app ./swarm
SWARMFORGE_TERMINAL=windows-terminal ./swarm
SWARMFORGE_TERMINAL=none ./swarm
```

Use `ghostty` when you want SwarmForge to open Ghostty tabs instead of the default Terminal.app windows. Use `windows-terminal` when you want SwarmForge to open Windows Terminal windows from WSL. Use `none` when you want SwarmForge to skip terminal automation and attach the cleanup tmux session in the current shell.

### Adding A Terminal Backend

The shared terminal backends are carried on `main` under `swarmforge/scripts/terminal-adapters/`. Runnable branches copy those scripts at startup. To add a new backend, update `main` by creating one file named after the backend:

```text
swarmforge/scripts/terminal-adapters/wezterm.sh
```

The file must define this small contract:

```sh
terminal_backend_label() {
  echo "WezTerm"
}

terminal_backend_can_open_sessions() {
  return 0
}

terminal_backend_tracks_windows() {
  return 0
}

terminal_open_session() {
  local session="$1"
  local title="$2"
  local sibling_id="${3:-}"

  # Open a terminal surface that runs:
  # cd "$WORKING_DIR" && exec tmux -S "$TMUX_SOCKET" attach-session -t "$session"
  #
  # Print a stable window/tab id to stdout.
}

terminal_window_exists() {
  local window_id="$1"

  # Return 0 if the id from terminal_open_session still exists.
  # Return nonzero otherwise.
}

terminal_close_window() {
  local window_id="$1"

  # Close the id from terminal_open_session.
}
```

If the terminal can open sessions but cannot return stable ids for open/check/close, keep `terminal_backend_can_open_sessions` as `return 0` and set `terminal_backend_tracks_windows` to `return 1`. SwarmForge will open one surface per session and skip the watchdog for that backend. `swarmforge/scripts/terminal-adapters/windows-terminal.sh` is an example of this launch-only style.

If the backend cannot open sessions at all, set both capability functions to `return 1`; SwarmForge will attach the cleanup tmux session in the current shell. Only edit `swarmforge/scripts/swarm-terminal-adapter.sh` when adding aliases or changing default auto-detection.

## Window Behavior

The usual shutdown path for a pack is **Teardown** on the dashboard, not closing a Terminal window.

If you use visible `window` lines, each agent window is attached to a tmux session. Terminal selection, copy, and paste may follow tmux and terminal-emulator rules rather than ordinary text-field behavior. If copy or paste feels unusual, check whether tmux copy mode is active before assuming the agent is stuck.

The first **visible** window in `swarmforge.conf` is the cleanup window. Closing that window shuts down tmux sessions, remaining tracked windows, and the swarm.

Closing any other tracked window is non-destructive. The watchdog reopens that window and attaches it back to the same tmux session, so the agent state and terminal history remain intact. This is often the simplest way to recover a window that has landed in an unfamiliar tmux mode or otherwise feels stuck.
