# Prompt Brainstorm

Scope: brainstorm the deferred prompt-load issue from the August 7, 2026 swarm
trial. This is not an implementation plan yet.

## Problem

Worker agents read a large amount of startup context before doing their local
task. Some context is necessary: assignment, role contract, required protocol,
tool startup instructions, and handoff rules. Other context may be redundant,
workflow-oriented, or better loaded on demand.

Large startup context has several risks:

1. Agents spend too much time reading instead of acting.
2. Agents may treat old prompt workflow language as authoritative even though
   `squad_next` is the workflow authority.
3. Required tool instructions can get diluted by general guidance.
4. Prompt drift across roles can make similar agents behave inconsistently.
5. Long prompts make it harder to see whether a role has the exact information
   it needs and no misleading extra policy.

## Current Observations

The generated assignment already injects several common sections:

1. Theme
2. Story, when story scoped
3. Story packet, when available
4. Required tools
5. Optional tools
6. Tool startup instructions
7. Required tool evidence instructions
8. Leader instructions
9. Required transient protocol

Many role prompts also repeat universal constraints:

1. Do not fetch, clone, install, or update external tools unless explicitly
   assigned.
2. Stay inside the assigned worktree.
3. Use `squad_event.sh` lifecycle states correctly.
4. Commit completed work.
5. Send a handoff to `squad-leader`.

Tool-heavy roles add local startup requirements:

1. `gherkin-writer` requires APS tool checks.
2. `cleaner` requires CRAP and DRY tool checks.
3. `qa` requires CRAP and DRY tool checks.
4. `hardener` requires mutation, APS, CRAP, and DRY tool checks.

## Principles

1. `squad_next` owns workflow direction. Worker prompts should not tell agents
   what phase comes next, which agent should run after them, or how the SL
   should route artifacts.
2. Role prompts should describe local role behavior only: what artifact to
   inspect or produce, what quality bar applies, and what evidence or handoff
   the role must create.
3. Universal transient protocol should live in one generated section, not be
   repeated differently in each role prompt.
4. Tool requirements should come from the role contract and `tool-table.edn`.
   Role prompts may explain how to use required tools, but should not invent
   source URLs, versions, install commands, or alternate repositories.
5. Required tool checks should stay close to the assignment so the agent sees
   them before doing work.
6. Optional broad engineering guidance should be available on demand, not
   loaded into every worker by default.
7. Prompt reductions should be tested with generated assignment snapshots so
   important protocol or tool instructions are not accidentally removed.

## Questions

1. Which lines in role prompts are truly role-specific, and which belong in the
   shared generated transient protocol?
2. Should common worktree, commit, handoff, and `squad_event.sh` language be
   removed from every role prompt once it is generated centrally?
3. Which roles need detailed engineering principles at startup, and which only
   need a terse local checklist?
4. Should tool-heavy roles link to a compact tool-use checklist rather than
   embedding detailed tool policy in each prompt?
5. Should reviewers receive only artifact, packet, and review rubric context,
   with no implementation workflow context?
6. Should role contracts become the source of truth for prompt assembly,
   reducing static prompt files to role-specific judgement/rubric text?
7. What should an agent do when it needs additional context: ask the SL, read a
   named on-demand file, or use a helper command that exposes the next allowed
   context packet?

## Candidate Startup Shape

Each generated assignment could be assembled in this order:

1. Assignment header and identity.
2. Local artifact scope.
3. Theme and story packet excerpts needed for the task.
4. Role-specific rubric.
5. Required tools and exact startup checks from `tool-table.edn`.
6. Required output contract.
7. Shared transient protocol.
8. Handoff draft.

Everything else should require explicit demand:

1. Full project architecture notes.
2. Full prior discussion or bug history.
3. Broad testing philosophy.
4. Detailed language-specific style guides.
5. Workflow state-machine explanation.
6. Historical trial postmortems.

## Candidate Experiments

1. Add a prompt inventory command that prints every file and generated section a
   role assignment includes.
2. Add snapshot tests for one generated assignment per role.
3. Extract shared transient protocol into one renderer-owned block.
4. Remove duplicated protocol lines from role prompts and compare snapshots.
5. Remove workflow-routing language from worker prompts and keep it only in
   `squad_next`/SL materials.
6. Split role prompts into short rubric files plus contract data.
7. Run a trial with reduced prompts and compare:
   - time to first useful action
   - tool startup compliance
   - handoff format correctness
   - blockers caused by missing context
   - incorrect workflow decisions by transient agents

## Risk Notes

Reducing prompts too aggressively can cause agents to miss hard-won operational
rules. The first cleanup should consolidate duplicated protocol rather than
delete behavior. Required tool instructions, worktree boundaries, commit rules,
and handoff format should remain explicit in generated assignments until tests
prove another representation is reliable.

## Stall Detection And SL Repair

The Squad Leader is smart enough to repair many inconsistent workflow states,
but it should not be responsible for discovering every stall from scratch.

Possible division of responsibility:

1. `squadd` detects operational idleness and suspicious inactivity:
   - SL pane idle too long.
   - No active workers.
   - Handoff queues not draining.
   - Spawn requests stuck.
   - Agents dark after an activity timeout.
2. `squad_next` detects logical workflow contradictions:
   - implementation-ready stories with no implementer assignment.
   - merged assignments not reflected in packets.
   - approvals satisfied but no next stage emitted.
   - stale review state blocking progress.
   - `wait` would be returned while eligible or inconsistent work remains.
3. The Squad Leader acts as the repair agent:
   - reads the invariant violation and evidence.
   - decides whether to run suggested commands, inspect further, or report a
     blocker to the user.
   - uses judgment for cases deterministic tools cannot yet repair safely.

Instead of returning `wait` in an inconsistent state, `squad_next` could return
a structured blocker such as:

```text
NEXT_ACTION: blocked
BLOCKER: implementation_ready_without_assignment
STORY: hunt-the-wumpus-001-cave-setup
EVIDENCE: implementation_assignment_state=ready, implementation_sha missing, no implementer assignment found
SUGGESTED_COMMAND: squad_assign.sh create ...
```

This preserves the SL as the intelligent repair actor while making stall
detection deterministic and visible.
