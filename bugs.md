# Bugs

Current scope: issues observed during the August 10, 2026 swarm trial.

Open bugs are ordered by priority: highest first (run-killers and correctness),
then recovery/visibility, then efficiency and polish.

## Open Bugs

1. **Same Assignment Can Spawn Two Live Agents**

   Observed in `~/junk/squad`: assignment `command-shorthand-input-analysis`
   (theme-scope analyst) received two successful spawn requests about three
   seconds apart and ran both `analyst-002` and `analyst-003` against the same
   `task_id`. Assignment status only records the last assignee
   (`agent_id: analyst-003`). Both agents drafted in parallel.

   Spawn does not refuse a second agent when the assignment is already
   `in_progress` or already has a live agent with that `task_id`. A race between
   create/`--queue-spawn` and a later `request_spawn` (or two mechanical ticks
   before the first agent counts as active for that assignment) can enqueue a
   second request after the first has completed.

   Expected: at most one live transient per assignment/`task_id`.
   `request_spawn` / spawn admission must no-op or fail if a pending spawn
   request, in-process spawn, or active agent already covers that assignment.

2. **`merge-merge` Max Depth Sometimes Hard-Blocks Instead Of Recovering**

   Log/state from the trial: many second-level merger assignments end as SL
   **reject** + rework (`rN+1` implementer/cleaner). Others end as assignment
   `state: blocked` / “blocked by squad leader” with no further rN
   (examples: `cli-…-implementation-r2-merge-merge`,
   `hazards-…-cleaner-merge-merge`, `hazards-…-cleaner-r3-merge-merge`,
   `hunt-the-wumpus-greg-yob-hardener-merge-merge`). Same underlying
   dry-run conflict on `core.clj` / `core_test.clj`, different terminal
   policy.

   Expected: one explicit, documented policy at max merger depth — e.g.
   always create a durable blocker + stop spawning, or always reject back to
   implementer/cleaner — not a mix of reject-and-escalate vs park-as-blocked
   that is hard to see in the dashboard.

3. **Merge Thrash Includes Tooling Config, Not Only Product Code**

   Beyond `src/wumpus/core.clj` and `test/wumpus/core_test.clj` (50+ and 48
   conflict mentions in assignment `merge-error` files), dry-run merges also
   conflicted on `deps.edn` and `bb.edn` (including add/add). Parallel
   implementers/mergers are fighting over project tooling as well as domain
   code, which multiplies cascade depth (observed implementer ladders up to
   **r6**, cleaner ladders up to **r5**, 40 merge-blocked rejection notes).

   Expected: either serialize edits to shared config, keep story work off
   root tooling files, or treat config conflicts with a dedicated resolution
   path so they do not drive endless rN rework. Complements implementer
   co-scheduling / module isolation when stories share the same files.

4. **Merge-Conflict Rejects Do Not Create Dashboard Blockers**

   The trial produced 40 rejection notes under `.squad/rejections/` (all
   merge-blocked). Assignments move to `rejected` or `blocked`, and packets
   often sit at `implemented` with `cleaner_review_state: blocked` (and
   hardener/qa/architecture blocked) without a first-class blocker the
   dashboard Blockers panel would show for operator recovery.

   Related to bug 6 (approval reject → blocker) but for the **merge-conflict
   reject / max-depth block** path: durable, visible blockers should exist
   whenever work is parked because SL cannot land a handoff.

5. **Agents Can Report Retired Before Workflow Resolves Their Handoff**

   From `~/junk/squad/.swarmforge/daemon/squadd.log`: three
   `SQUAD_STATUS_ALERT` / `agent-retired-awaiting-workflow` events
   (`merger-018`, `implementer-015`, `merger-032`) with the message to run
   `squad_retire.sh` only after the workflow resolves the handoff. The agent
   session/role recovery path marks the agent retired (or recoverable) while
   the assignment handoff is still mid-flight (`merge_blocked` / reject /
   merge-merge spawn still pending).

   Expected: agent lifecycle should not surface “retired” (or should not free
   the role for recovery/reuse) until the assignment’s handoff has a terminal
   workflow outcome (merged, rejected with rework spawned, or blocked). Alerts
   should be rare; today they indicate a real retire-vs-handoff race.

6. **Rejecting A Story Approval Should Create A Blocker**

   Clicking Reject on a story approval in the dashboard only moves the approval
   file to `.squad/approvals/rejected/` and does not create a durable blocker.
   The story packet stays at `story_recorded` with `story_approval_state: pending`,
   so the workflow parks the story without an explicit, visible blocker for the
   squad leader or user.

   Expected: rejecting a story (or other gate) approval creates a blocker that
   records the rejection, the target story/gate, and enough detail for recovery
   (revise story, re-request approval, or send back to analyst). The dashboard
   Blockers panel should show it, and `squad_next` should route residual judgment
   from that blocked state rather than silently omitting the story.

7. **Teardown Leaves Assignments `in_progress` With Dead Agents**

   After `close-swarm` / full teardown, agents are retired and tmux/squadd are
   gone, but assignment status files still show `state: in_progress` with
   `agent_id` / `session` pointing at dead sessions (e.g. cleaner-r3 →
   `cleaner-012`, cleaner-r5 → `cleaner-011`). ~13 assignments remained
   `in_progress` after the Aug 10 teardown. Handoffs also remain in
   `inbox/new` and `inbox/in_process` with no consumer.

   Expected: full teardown (or `stop_squadd --full-teardown`) marks open
   assignments cancelled/abandoned, clears or archives live agent bindings,
   and drains or fails in-flight handoffs so a restart does not treat them as
   active work.

8. **Merge-Ready Dry-Run Often Runs Twice Before Reject**

   Many assignment `events.log` files record two consecutive
   `merge_blocked` / “dry-run merge failed” events a few seconds apart on the
   same handoff commit before a single `rejected` event (widespread across
   implementer, cleaner, merger, and hardener assignments). Wastes work and
   can double-signal SL/watchdog.

   Expected: one merge-ready evaluation per handoff receipt (or explicit
   backoff with a single terminal outcome), then reject/block once.

9. **squadd.log Concurrent Writes Corrupt Event Lines**

   The same log mixes ISO-timestamped daemon events with bare
   `SQUAD_STATUS_OK` / `SQUAD_STATUS_ALERT: …` lines that have no timestamp.
   At least one line is truncated mid-token: `rkflow-mechanical-applied 1`
   (missing the `wo` of `workflow` and the timestamp prefix), consistent with
   two writers interleaving into one file without locking or a single writer.

   Expected: one coherent log stream (or separate files), every line
   timestamped, no torn event names.

10. **Blocker UI Should Offer Re-Enter Pipeline (Design Later)**

    After a story approval is rejected (and once a blocker exists), the dashboard
    should expose a clear control on that blocker — e.g. a button — that puts the
    story back into the pipeline (re-open approval, clear/supersede the rejection,
    and allow gherkin/QA-procedure work to proceed after a new approve).

    Today there is no supported path: `squad_approval.sh approve` only works on
    pending files, a rejected approval blocks re-request of the same gate, and
    recovering requires ad-hoc `squad_packet.sh approve` or deleting the rejected
    record. This needs product/workflow design before implementation — defer
    detailed design until later. Depends on durable blockers (bugs 4 and 6).

11. **All Stories Should Have A Story Number**

    Stories should carry a stable story number (not only a slug id / filename
    stem). Today registration and dashboard identity lean on story ids derived
    from artifact paths (e.g. `fixed-cave-and-setup`) without a required numeric
    story number for ordering, reporting, or human reference.

    Expected: every story has an explicit story number in the story artifact and
    durable packet/theme metadata, visible in the dashboard and usable by the
    workflow for ordering and communication.
