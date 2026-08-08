# Bugs

Current scope: bugs and follow-up notes observed during the August 7, 2026
swarm trial.

## Workflow And FSM

### Story Registration From One Analyst Handoff Was Serialized

The analyst handed off three story artifacts from one assignment. The workflow
registered the resulting story packets one at a time instead of registering all
ready story artifacts as one mechanical batch.

Observed behavior:

1. The analyst completed assignment `hunt-the-wumpus-analysis` and handed off
   three story files.
2. The story packets were registered sequentially:
   - `hunt-the-wumpus-01-console-start-setup` at `20:29:58`
   - `hunt-the-wumpus-02-turn-movement-hazards` at `20:30:06`
   - `hunt-the-wumpus-03-crooked-arrows` at `20:30:15`
3. The Squad Leader window showed `squad_next.sh` issuing repeated
   `register_story_artifact` work instead of grouping all independent story
   registrations.
4. The first concurrent action batch contained only analyst retirement plus
   story 1 registration.
5. Later batches combined story 2 registration with story 1 approval-request
   creation, then story 3 registration with story 2 approval-request creation.
6. No `APPLIED_TRANSITIONS` report appeared for the story registration
   bookkeeping.

Likely cause:

The concurrent scheduler appears to treat story registration actions from the
same analyst assignment as dependent because they share the same assignment id.
For this case, the produced story packets are separate artifacts and can be
registered independently once the analyst handoff has been merged.

Expected behavior:

1. After a merged analyst handoff exposes multiple story artifacts,
   `squad_next.sh --apply-mechanical` or the workflow daemon should register
   every unregistered story artifact that is ready from that handoff.
2. The workflow should report those completed registrations as
   `APPLIED_TRANSITIONS`, not as repeated Squad Leader commands.
3. If registration remains SL-mediated, `squad_next.sh` should at least include
   all independent `register_story_artifact` commands in `CONCURRENT_ACTIONS`.
4. Dependency keys should distinguish the output story packet from the source
   assignment when the action is safe to apply independently.
5. User approval gates should still be respected after registration; automatic
   story registration must not imply story approval unless the approval gate is
   configured as not required.

### Approved Story Specification Work Was Serialized

After the user approved story
`hunt-the-wumpus-01-console-start-setup`, the workflow correctly moved the story
into specification work, but it serialized independent assignment creation and
spawning instead of producing a full concurrent action set.

Observed behavior:

1. The web approval for
   `story__hunt-the-wumpus-01-console-start-setup` was recorded as approved at
   `20:32:20`.
2. The daemon woke the Squad Leader, and the Squad Leader resumed by running
   `squad_next.sh`.
3. The workflow then proceeded one step at a time:
   - create the Gherkin writer assignment
   - spawn the Gherkin writer
   - create the QA procedure writer assignment
   - spawn the QA procedure writer
4. The resulting assignments were both in progress:
   - `hunt-the-wumpus-01-console-start-setup-gherkin`
   - `hunt-the-wumpus-01-console-start-setup-qa-procedure`
5. `squad_next.sh` emitted `CONCURRENT_ACTIONS`, but only with a single
   concurrent command at the inspected point.
6. No `APPLIED_TRANSITIONS` were reported.

Expected behavior:

1. Once a story is approved, creating the Gherkin writer assignment and QA
   procedure writer assignment should be treated as independent ready actions.
2. If those assignment creations are mechanical, the workflow tool or daemon
   should apply them and report them as `APPLIED_TRANSITIONS`.
3. If they remain Squad Leader mediated, `squad_next.sh` should list both
   assignment creation commands in `CONCURRENT_ACTIONS`.
4. After assignment creation, spawning the Gherkin writer and QA procedure
   writer should also be batched in `CONCURRENT_ACTIONS` when capacity permits.
5. The next call to `squad_next.sh` should reissue any forgotten assignment or
   spawn command whose durable state has not changed.
