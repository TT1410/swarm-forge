# Resolved Issues (lieutenant)

The issues and agreed solutions below are implemented in this change. Their
acceptance criteria are retained as the record of the intended behavior.

## High: Contain project, task, approval, and clarification identifiers

Project and task names are used directly as path components. URL-decoded
approval and clarification IDs are also used as filenames. Absolute paths,
`..`, slashes, tabs, and newlines can escape their intended directories or
corrupt line-oriented state.

Solution:

- Allow only ordinary, safe characters in project names and internal IDs.
- Continue allowing spaces in task names, but reject slashes, backslashes,
  line breaks, leading or trailing whitespace, and special path names such as
  `.` and `..`.
- Before reading, writing, or deleting anything, verify that the resulting file
  is still inside the expected directory.
- Apply the same checks everywhere projects, tasks, approvals, clarifications,
  audits, and handoffs use these names or IDs.
- Return a clear error without changing files or state when a name is invalid.

Done when:

- One shared validator defines the accepted identifier format.
- Project paths remain canonically beneath `projects/`.
- Task documents and board files remain beneath their designated directories.
- Approval and clarification files remain beneath their pending/done directories.
- Invalid identifiers are rejected before any filesystem or state change.

## High: Make New Project replacement explicit and imports consistent

New Project currently refuses to continue when `projects/<name>` already
exists. The operator needs a clear choice to leave that directory alone or
permanently clear it and create the requested project in its place. For a
GitHub project, the repository is an old starting point: the submitted mission
and the forge's SwarmForge files are the newer, authoritative versions.

Solution:

- When the requested project directory exists, make no changes and show an
  alert asking whether it should be cleared and replaced.
- Explain in the alert that confirmation permanently deletes the directory's
  contents. SwarmForge does not retain a backup.
- Cancel leaves the existing directory untouched.
- Confirm stops the existing project if it is running, verifies that it
  stopped, and then deletes the project directory completely.
- For a GitHub project, clone the requested repository and replace its old
  mission and SwarmForge-managed files with the current submitted mission and
  forge versions.
- For a non-GitHub project, create a new project from scratch in the cleared
  location.
- Commit the initial or upgraded project state before starting any agents.

Done when:

- An existing project directory is never changed before explicit confirmation.
- Cancel leaves every file in the existing directory unchanged.
- Confirm permanently removes the old directory and creates the requested
  replacement without retaining a backup.
- GitHub and non-GitHub replacements follow the same confirmation flow.
- GitHub replacements contain the cloned product plus the current mission and
  SwarmForge-managed files.
- The replacement has a clean committed starting state before role worktrees or
  agents are created.

## High: Make handoff delivery transaction-like

The daemon updates the board before preflighting all recipients. It then copies
and notifies recipients one at a time. A later failure moves the original to
`failed/`, potentially leaving an advanced or Done card and only a partial set
of delivered inbox files.

Solution:

- Leave the original handoff in `outbox/` until every delivery step succeeds.
- Before writing anything, check that every recipient and destination is valid.
- On each attempt, write only the missing inbox copies. Treat an existing copy
  with the same handoff ID and recipient as already delivered, and report a
  conflicting file instead of replacing it.
- Move the board card only after every recipient has an inbox copy. Move the
  original handoff to `sent/` only after the board is correct.
- Retry temporary filesystem, board, or process failures automatically. Record
  the attempt count, last error, and next retry time on disk, and wait
  progressively longer between failures up to about one minute.
- After several failures, show the problem in Attention while continuing slow
  retries. Do not silently abandon a partially delivered handoff.
- Move a handoff directly to `failed/` only when the handoff itself is
  permanently invalid, such as malformed headers or an unknown recipient.
- Treat terminal wake-ups separately from delivery. A failed wake-up does not
  fail a stored handoff; retry the wake-up when that recipient's session
  returns.
- Resume unfinished deliveries and wake-ups automatically after daemon restart.

Done when:

- All recipients and destinations are validated before board mutation.
- Interrupted or transiently failed delivery remains retryable.
- A retry does not duplicate completed recipient copies.
- Board movement, sender archival, and final handoff state cannot claim a
  completed delivery while recipient delivery is partial.
- Repeated failures become visible to the operator without stopping later
  retries.
- Notification failure cannot move an otherwise valid handoff to `failed/`.

## High: Preserve existing Git commit hooks

Project startup unconditionally overwrites `.git/hooks/commit-msg`, which can
remove validation, signoff, or policy hooks belonging to an imported project.

Solution:

- Treat the Git `commit-msg` hook as a single entry point that dispatches to
  both SwarmForge and the project's existing hook.
- If no hook exists, install a clearly marked SwarmForge hook normally.
- If a hook already exists, preserve it unchanged as
  `commit-msg.before-swarmforge`, including its executable permissions and
  symbolic-link behavior. Never overwrite an existing saved hook.
- Install a clearly marked combined hook as `commit-msg`. It first runs the
  SwarmForge byline behavior, then runs the preserved project hook with the same
  arguments, working directory, environment, and input supplied by Git.
- Reject the commit if either behavior fails. Running SwarmForge first lets the
  project's hook inspect and approve the message containing the role byline.
- On later startups, recognize the SwarmForge marker and update the combined
  hook without wrapping it again.
- Provide removal behavior that restores the saved project hook exactly. If
  the combined hook was changed or replaced, report the conflict instead of
  deleting someone else's changes.

Done when:

- An existing `commit-msg` hook is preserved and invoked, or hook composition
  uses a non-destructive mechanism.
- Repeated SwarmForge startup is idempotent.
- Removing SwarmForge restores or leaves the repository's prior hook behavior
  intact.
- The project's original hook remains the final authority and can reject a
  commit after seeing the SwarmForge byline.

## Medium: Reconcile configurable topology with hard-coded card chains

The configuration and README permit arbitrary role sets, but card types route
through hard-coded `specifier`, `coder`, `cleaner`, `architect`, `hardender`,
and `QA` chains. Board creation and movement do not ensure that computed lanes
exist in the active configuration.

Solution:

- Define every card type's ordered route in `swarmforge.conf` alongside the
  window definitions. For example, `card utility coder cleaner` defines coder
  as the starting role and cleaner as the final role for utility cards.
- Treat the first named role as the starting lane, each following role as the
  next handoff destination, and the last role as the card-completing role.
- At startup, verify that every route is nonempty and every named role exists.
  Reject duplicate, empty, or impossible routes before starting agents.
- Show only configured card types in New Task and reject attempts to move a
  card into a role that does not exist.
- Generate one shared route description for the board, handoff validator,
  daemon, and dashboard so they cannot disagree.
- Keep configured window order responsible for which earlier roles receive the
  final result; use the card route to decide which roles actively process that
  type of card.
- Add explicit route lines to existing pack templates so their current behavior
  does not change during the migration.

Done when:

- Card chains are derived from project configuration.
- Startup rejects a route that names an unknown role or cannot be followed.
- A card cannot be created or moved into an unknown lane.
- New Task offers only the card types supported by that project.
- Existing packs retain their current card paths through explicit configuration.
- Documentation describes the actual supported customization boundary.

## Medium: Record project runtime state only after verified start and stop

Opening a project starts its runtime asynchronously and immediately records it
as open. Closing ignores stop failures and records it as closed. The dashboard
can therefore disagree with the processes that are actually running.

Solution:

- Give every project an explicit state: `closed`, `starting`, `open`,
  `stopping`, or `error`.
- Change a project to `starting` when Open is requested. Report Ready only after
  its expected tmux sessions and handoff daemon are running, and change it to
  `open` only after verifying that signal.
- If startup fails, clean up anything partially started, record the error, and
  show it in the dashboard instead of marking the project open.
- Prevent overlapping Open and Close operations while a project is already
  starting or stopping.
- Change a project to `stopping` when Close is requested. Verify that its
  sessions and daemon have stopped before changing it to `closed`.
- If stopping fails, leave the project in `error` rather than claiming it is
  closed.
- On forge restart, inspect the real project processes and correct stale
  recorded states.
- Show Starting, Stopping, and Error on the project header so the operator does
  not need to inspect a log.

Done when:

- Open waits for and verifies a bounded readiness signal and reports startup
  failures.
- Failed startup does not leave the project marked open.
- Close verifies that the project runtime stopped before marking it closed.
- Timeout and failure states are visible to the operator and recoverable.
- Recorded project state is reconciled with actual processes after forge
  restart.

## Medium: Mirror managed files into role worktrees

Startup copies scripts, roles, and constitution files over existing worktree
trees without removing files that disappeared from the source. Removed or
renamed files can remain active in a reused worktree.

Solution:

- Treat the project's current SwarmForge data as authoritative. It can only be
  newer than the copies in its role worktrees, so do not compare versions or
  attempt to merge them.
- Before starting an agent, replace that worktree's SwarmForge-managed scripts,
  roles, and constitution trees with exact copies of the current project data.
- Remove every older managed file that is no longer present in the current
  project data, including files left behind by renames.
- Limit removal to the managed SwarmForge trees. Preserve product code,
  project-owned files, handoffs, and runtime state outside those trees.
- Verify that the replacement completed before starting the agent. If it did
  not, leave the agent stopped and report the error.
- Make repeated startup synchronization produce the same exact result without
  accumulating files or changes.

Done when:

- Managed script, role, and constitution trees are exact mirrors after startup.
- Files removed from the source are removed from reused worktrees.
- Runtime state and project-owned files outside the managed trees are preserved.
- No agent starts with an incomplete managed-file replacement.

## Medium: Make the forge Git-ignore policy coherent

The forge creates `projects/`, but the host `.gitignore` does not ignore it,
making accidental staging of nested project repositories possible. In the
current checkout, `.git/info/exclude` ignores all of `.swarmforge/`, which also
hides new files beneath the intentionally committed
`.swarmforge/project-pack/`.

Solution:

- In the forge repository, ignore `/projects/`, `.worktrees/`, and runtime-only
  `.swarmforge` data.
- Explicitly keep `.swarmforge/project-pack/` visible and committed.
- Remove the obsolete blanket `.swarmforge/` rule from the forge's local Git
  exclusion file because it hides new project-pack files.
- In generated projects, ignore `.swarmforge/` runtime state and `.worktrees/`,
  while continuing to track the project's `swarmforge/` scripts, roles,
  configuration, and constitution.
- Have SwarmForge maintain its ignore rules in a clearly marked block without
  changing unrelated user rules.
- Make installation and startup repair older SwarmForge-managed ignore rules so
  existing checkouts receive the same policy.

Done when:

- The host ignores generated `projects/` contents.
- Runtime-only `.swarmforge` state remains ignored.
- New files beneath `.swarmforge/project-pack/` are visible to Git.
- Installer and direct-checkout behavior produce the same ignore policy.

## Low: Make swarmforge.conf the sole authority for agent assignments

The README describes Codex defaults for the six-pack specifier and hardender,
while the lieutenant project-pack configuration currently assigns Grok to every
role.

Solution:

- Remove statements from the README that specify which agent backends are
  currently assigned to particular roles or packs.
- Keep the active agent choices only in each project's `swarmforge.conf`; that
  file is the sole authority for the agents currently in use.
- Use the README to explain the configuration-file format, supported agent
  names, optional receive and propagation modes, CLI arguments, and how an
  operator changes an assignment.
- Label any sample configuration as an example rather than a statement of the
  current defaults.
- Review related documentation for repeated agent assignments and remove or
  convert them to configuration examples.

Done when:

- The README explains how to configure agents without claiming which agents are
  currently assigned.
- Current backend choices exist in one authoritative place:
  `swarmforge.conf`.

## Medium: Review and update the README and related documentation end to end

The README contains many descriptions, examples, defaults, and workflows that
will be affected by these issues. Updating only the paragraph nearest each code
change is likely to leave contradictory instructions elsewhere in the README or
in related documentation.

Solution:

- After the behavior changes are settled, review the entire README from top to
  bottom rather than making isolated edits.
- Compare every command, configuration example, file layout,
  dashboard workflow, and handoff description with the implemented behavior
  and current project-pack template.
- Document the confirmed New Project clear-and-replace flow, committed versus
  ignored artifacts, configured card routes, handoff retries, combined Git
  hook, verified open/close state, managed-file synchronization, and agent
  configuration format.
- Review `swarmforge/handoff-protocol.md`, configuration comments, usage text,
  dashboard labels, and screenshots for the same changes.
- Remove duplicate descriptions where possible and point them to one
  authoritative explanation so defaults do not drift again.
- Keep historical branch descriptions clearly separated from the behavior of
  the currently shipped lieutenant project pack.

Done when:

- A complete documentation pass has been made after the production changes.
- README commands and configuration examples match the current parser and can
  be followed as written.
- Project creation, task routing, handoff, Git, runtime, and ignore behavior are
  described consistently everywhere they appear.
- The README does not duplicate current agent assignments; configuration
  examples match the parser and are clearly labeled as examples.
- Related documents and screenshots contain no known descriptions of removed
  or superseded behavior.
