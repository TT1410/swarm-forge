# Bugs

## Agent Pane Viewer Shows Input Box Chrome

In the tmux windows popped up by clicking an agent, do not render the input box
at the bottom.

## Agent Pane Viewer Forces Scroll To Bottom While User Is Reading

When a tmux window opened by clicking an agent is scrolled upward, new output
should not immediately force the pane back to the bottom.

Expected behavior:

1. If the user is already at or near the bottom, new output may auto-scroll to
   keep the live tail visible.
2. If the user has scrolled away from the bottom, refreshes should preserve the
   user's scroll position while continuing to poll for new output.
3. The viewer may show an unobtrusive indication that new output is available,
   but it should not interrupt reading by jumping to the bottom.

## Merged Analyst Stories Are Not Registered Into Workflow State

After an analyst assignment is merged, `squad_next.sh` can return `wait` even
though the analyst created story files under `stories/`.

In the observed swarm, the analyst handoff declared four `stories/*.md`
artifacts, the squad leader recorded the result, verified merge readiness,
accepted the merge, completed the handoff, and retired the analyst. The story
files existed in the project, but no `.squad/stories/<story-id>/packet` records
were created. With no story packets, the story FSM had no stories to approve or
send to Gherkin/QA, so the advisor reported no work.

Expected behavior:

1. A merged analyst assignment for theme analysis should not be considered fully
   consumed until its declared story artifacts are registered.
2. `squad_next.sh` should emit deterministic commands to register each
   analyst-created `stories/*.md` artifact with the theme and create the
   corresponding story packet.
3. Once registered, the normal story approval workflow should proceed for each
   story.
4. The workflow should not infer readiness by scanning arbitrary files, but it
   may use the validated result manifest/artifacts from the merged analyst
   handoff.

## Required Tool Installation Can Leave Stale Tool Cache Locks

Gherkin writers attempted to install their required APS tools, but provisioning
failed in ways that blocked later work.

Observed behavior:

1. `gherkin-writer-001` ran the required `squad_tool.sh require` checks and then
   attempted `squad_tool.sh ensure`, but its worktree lacked
   `swarmforge/scripts/install_bb_tool.sh`. The install failed with:
   `bash: swarmforge/scripts/install_bb_tool.sh: No such file or directory`.
2. `gherkin-writer-002` had `install_bb_tool.sh` in its worktree and attempted
   `squad_tool.sh ensure gherkin-parser`, but timed out waiting for
   `.swarmforge/tools/locks/gherkin-parser.lock`.
3. The shared tool cache contained stale lock directories for
   `gherkin-parser.lock` and `ir-dry-checker.lock`, with no visible live
   `squad_tool` or installer process holding them.
4. During the same trial, the squad leader appeared to resolve the lock
   contention and later agents were able to proceed. This suggests manual or
   workflow-directed repair is possible, but the stale-lock condition still
   needs deterministic detection, reporting, and cleanup.
5. The contention was caused by multiple concurrent Gherkin writers, not
   analysts. After the first failed install left lock directories behind,
   `gherkin-writer-002`, `gherkin-writer-003`, and `gherkin-writer-004`
   overlapped while trying to `ensure` the same shared APS tools. One writer
   also attempted both `gherkin-parser` and `ir-dry-checker` installs at the
   same time.
6. The squad leader resolved the situation by removing empty stale lock
   directories and installing the APS tools sequentially with an absolute
   installer path.

Expected behavior:

1. Spawned worktrees must consistently include helper scripts referenced by
   generated tool-install commands.
2. `squad_tool.sh ensure` must remove its lock on every failed install path,
   including missing installer scripts and interrupted agent sessions.
3. Stale tool-cache locks should be detected and cleared by the workflow or
   reported as an explicit dashboard blockage with a safe repair command.
4. A failed tool install should not leave later agents unable to install the same
   required tool.
5. Shared required tools should be provisioned once before spawning multiple
   agents that require them, or `squad_tool.sh ensure` must provide robust
   stale-lock ownership/expiry semantics for concurrent callers.
6. Generated install commands should use an invocation path that works from the
   tool install working directory, not only from the project root.

## Agent Blocked Or Failed State Does Not Surface As Dashboard Blocker

The dashboard can show no blockers even when an active assignment's agent is
blocked or failed.

Observed behavior:

1. `gherkin-writer-003` had agent status `state: blocked` with detail
   `required APS tool installs timed out waiting for shared tool cache locks`.
2. `gherkin-writer-004` had failed/retry status around APS tool installation.
3. Their assignment status files still reported `state: in_progress`.
4. `/api/state` returned `"blockers":[]`, so the dashboard did not show the
   active tool-install blockage.

Expected behavior:

1. When an agent assigned to an in-progress assignment reports `blocked` or
   `failed`, the workflow should create or update an assignment blocker record,
   or the dashboard should surface the agent blockage directly with the related
   assignment.
2. The blocker should include the agent id, assignment id, role/template, and
   latest status detail.
3. Once the assignment is retried, superseded, merged, or otherwise resolved,
   the dashboard blocker should disappear from the active blocker list.

## Merged QA Procedure Artifacts Are Not Recorded In Story Packets

The squad leader reported and repaired a workflow state bug where merged QA
procedure artifacts existed in the repository but were not attached to their
story packets.

Observed behavior:

1. QA procedure writer assignments for Stories 1-3 were merged successfully.
2. The accepted merge/result manifests declared artifacts such as
   `qa/hunt-the-wumpus-01-startup-cave-and-turn-display.md`,
   `qa/hunt-the-wumpus-02-movement-and-room-hazards.md`, and
   `qa/hunt-the-wumpus-03-crooked-arrows-and-wumpus-wake.md`.
3. The story packets still showed `QA_PROCEDURE: none`, so `squad_next.sh`
   treated QA procedure work as missing and risked creating duplicate QA
   assignments.
4. The squad leader manually repaired the state with `squad_packet.sh attach`
   for the merged QA procedure artifacts. After attachment, the advisor advanced
   to QA procedure review.

Expected behavior:

1. After a `qa-procedure-writer` assignment is merged, the workflow should
   deterministically attach the declared QA procedure artifact to the
   corresponding story packet.
2. The advisor should not recommend duplicate QA procedure writer assignments
   when a merged QA procedure artifact is waiting only for packet attachment.
3. The same post-merge artifact-to-packet recording rule likely applies to other
   artifact-producing roles, including Gherkin writers.
