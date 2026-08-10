# Bugs

Current scope: issues observed during the August 9, 2026 swarm trial.

Fixed outside this list:

- **Agents disappear before retirement** — dashboard now shows agents from
  spawn until `retired` (including temporary `failed`), via
  `dashboard-agent-visible?` in `squadd/web.clj`.

Open bugs:

| # | Title |
|---|--------|
| 3 | Reviewer handoffs use ambiguous free-form decisions |
| 9 | Chained merger assignments can remain merge-blocked indefinitely |
| 10 | Agents self-retire before workflow resolution |
| 11 | Concurrent retirements hit registry lock contention |
| 12 | Swarm teardown leaks worktrees and stale agent state |
| 13 | No per-worker agent-tool configuration for squad transients |

## Review Result Contract

### Bug 3: Reviewer Handoffs Use Ambiguous Free-Form Decisions

Reviewers wrote review artifacts with free-form recommendation language, and the
workflow could not reliably classify the review result.

Observed behavior:

1. Review artifacts used phrases such as `Recommendation: accept`,
   `Recommendation: Revise`, `Revise before approval`, and
   `No blocking findings`.
2. `squad_next.sh` only recognized strict decision values such as `accepted` and
   `changes-requested`.
3. Merged review assignments were not recorded into story packets.
4. The workflow reached `NEXT_ACTION: wait` even though several reviewed
   artifacts still needed approval or revision.

Expected behavior:

All reviewer roles should use a dedicated review handoff tool with exactly two
outcome options:

1. `accepted`
2. `changes-requested`

This applies to all review roles, including Gherkin reviewers, QA procedure
reviewers, code reviewers, and architects. The reviewer role prompts should
explicitly instruct reviewers to use that tool for handoff, so the workflow
records a deterministic decision instead of parsing review prose.

Implementation notes:

1. Do not fix this by making `squad_next.sh` better at parsing prose.
2. Review outcome is workflow state and should be written through a deterministic
   workflow helper with a closed vocabulary.
3. The spelling should be `changes-requested`, not `changed-requested`, matching
   the existing FSM vocabulary.
4. The reviewer should still write normal review comments in the artifact.
5. The handoff helper should validate that the assignment exists, the assignment
   template is a reviewer role, the outcome is one of the two allowed values, the
   expected artifact exists, and a commit is present.
6. The handoff or result manifest should carry
   `review_decision: accepted|changes-requested`.
7. `squad_next.sh` should trust that structured field, not infer the review
   decision from review artifact prose.

Architecture notes:

- Highest-leverage structural bug. The system already prefers a closed
  vocabulary, then undermines it by scraping review markdown via a weak exact-line
  parser (`review-decision-from-content` matches `accepted` / `accept`, not
  `Recommendation: accept`).
- Reviewer prompts still say write review + `git_handoff`, not call a closed
  review-decision helper. `review-decision` already prefers
  `(:review-decision assignment)` when present; nothing durable writes that field
  reliably from agents.
- Root cause for several stalls: no packet review → wrong stage → wrong spawn or
  `wait`. Do not make the prose parser smarter.

## Merge Recovery

### Bug 9: Chained Merger Assignments Can Remain Merge-Blocked Indefinitely

Merge recovery produced a chain of merger assignments, and the chain still had
blocked/in-progress state at teardown.

Observed behavior:

1. Story 3 implementation merge was blocked:
   `hunt-the-wumpus-003-movement-hazards-and-wumpus-wake-implementation`
   had `state: merge_blocked`.
2. The first merger assignment,
   `hunt-the-wumpus-003-movement-hazards-and-wumpus-wake-implementation-merge`,
   also ended `merge_blocked`.
3. The second merger assignment,
   `...-implementation-merge-merge`, also ended `merge_blocked`.
4. A third merger assignment,
   `...-implementation-merge-merge-merge`, remained `in_progress` at teardown,
   despite its agent status later being stale/retired after the swarm was
   killed.

Expected behavior:

The merger workflow should have a bounded, explicit recovery policy. If a merger
handoff still cannot be merged, the workflow should either create the next
merger with clear lineage and preserve required worktrees, or declare a
dashboard-visible blocker after a configured limit. It should not leave an
ambiguous chain of blocked merger assignments and stale in-progress state.

Architecture notes:

- Any `merge_blocked` assignment gets a merger whose id is
  `assignment-id + "-merge"`. When the merger itself is blocked, the rule applies
  again → unbounded suffix growth. No max depth, no terminal human blocker.
- Recovery is modeled as another assignment, consistent with the rest of the
  system, but merger failure needs an explicit policy: depth limit, preserve
  worktree, surface a dashboard blocker, stop inventing new agents.
- `existing-merger-assignment` only looks for a prefix match and does not cap the
  chain.

## Agent Lifecycle And Cleanup

### Bug 10: Agents Self-Retire Before Workflow Resolution

An agent reported `retired` before the Squad Leader had resolved its handoff
through the workflow.

Observed behavior:

1. `gherkin-writer-002` reported `state: retired`.
2. Its handoff was still pending workflow processing.
3. `squadd` repeatedly logged:
   `agent-retired-awaiting-workflow gherkin-writer-002`.
4. The daemon also warned:
   `agent gherkin-writer-002 reported retired; run squad_retire.sh only after workflow resolves its handoff`.

Expected behavior:

Transient agents should not self-retire after sending a handoff. After an agent
sends its handoff, it may report `handoff_sent`, but final retirement should be
performed only by `squad_retire.sh` after the Squad Leader has resolved the
handoff, merged or otherwise recorded the result, and updated durable workflow
state.

Architecture notes:

- Control-plane vs agent-plane confusion. `retired` is a valid `squad_event`
  state; prompts list it; workers can claim a workflow-terminal state that only
  the leader should own.
- `squadd` only logs `agent-retired-awaiting-workflow` and refuses full cleanup
  until `squad_retire.sh`. The protocol documents the right rule in the daemon
  warning but allows the wrong action at the tool boundary.
- Agents should max out at `handoff_sent`; retirement should be leader- or
  daemon-owned after merge/record.

### Bug 11: Concurrent Retirements Hit Registry Lock Contention

The Squad Leader attempted to execute multiple advisor-issued retirements in
parallel, and some of them failed because the retirement helper contended on the
shared squad registry lock.

Observed behavior:

1. `squad_next.sh --apply-mechanical` emitted multiple `retire_agent` commands
   in one concurrent action batch.
2. The Squad Leader executed retirements in parallel.
3. Two parallel retirements hit a registry lock race in the helper.
4. `squad_next.sh` later reissued the still-needed retirements.
5. The Squad Leader recovered by running the retirements sequentially.

Expected behavior:

Retirement should not depend on the Squad Leader discovering that parallel
retire commands are unsafe. A better solution is likely to make retirement
processing explicitly serialized, daemon-owned, or otherwise lock-aware so
completed agents can be drained promptly without registry lock races.

Architecture notes:

- Concurrent mechanical batching optimized throughput without classifying
  registry mutations as serial-only. `squad_retire` takes exclusive `spawn.lock`.
- Either mark retirements non-concurrent / single-threaded in apply, or queue
  retire through the daemon under one lock owner.
- Asking the Squad Leader to discover sequential retirement is the wrong layer.

### Bug 12: Swarm Teardown Leaks Worktrees And Stale Agent State

After the swarm was killed, the live processes were gone but cleanup left stale
git worktree and agent status state behind.

Observed behavior:

1. No tmux server remained on the squad socket.
2. No `squadd` process remained.
3. No live swarm agent processes remained.
4. `git worktree list` still showed many agent worktrees as `prunable`.
5. One physical worktree still existed:
   `~/junk/squad/.worktrees/merger-003`.
6. `merger-003` still appeared as a non-prunable git worktree.
7. Agent status files still contained stale active-looking states, including
   `code-reviewer-085` and `code-reviewer-086` as `running`, and `merger-003` as
   `handoff_sent`, despite there being no live processes.

Expected behavior:

Swarm teardown should leave no live tmux sessions, no `squadd` process, no agent
processes, no stale physical worktrees, no stale git worktree registrations, and
no active-looking agent status records. Any unmerged or intentionally preserved
worktree should be reported explicitly as a preserved artifact rather than left
as an ambiguous cleanup leak.

Architecture notes:

- Cleanup is split across processes. Killing tmux/`squadd` does not guarantee the
  same path as graceful `squad_retire` (status → retired, remove worktree, prune
  registration).
- Merger worktrees especially can remain after merge-blocked chains. Kill path ≠
  retire path.
- Needs one teardown reconciler: no process ⇒ no active-looking status, no orphan
  worktree unless explicitly preserved and listed.

## Worker Backend Configuration

### Bug 13: No Per-Worker Agent-Tool Configuration For Squad Transients

Operators should be able to choose which agent CLI each worker role uses
(e.g. `grok`, `codex`, `claude`), the way six-pack does for persistent roles.
Squad currently lacks an analogous per-worker configuration.

Observed behavior:

1. Six-pack configures the agent tool per role on each `window` line in
   `swarmforge.conf` (`window <role> <agent> <worktree> ...`), so specifier,
   coder, cleaner, etc. can each run on a chosen backend.
2. Squad has only a single global `transient_agent` setting in `squad.conf`
   (default/inherit from squad-leader), applied to all spawned workers.
3. There is no way to say, for example, implementers use `codex`, reviewers use
   `claude`, and hardener uses `grok`.

Expected behavior:

Squad should provide a configuration mechanism analogous to six-pack’s per-role
agent column so each worker template (or named worker role) can specify its
agent tool. Spawn should honor that choice when launching transient agents.
A global default (today’s `transient_agent`) may remain as fallback when a
template does not set an override.

Architecture notes:

- Persistent packs: `swarmforge.conf` `window` lines bind role → agent binary.
- Squad transients: `squad_spawn` / `squad_config` read one
  `transient_agent` value and use it for every template.
- Natural fit: per-template keys in `squad.conf` (e.g.
  `transient_agent implementer codex`) or a small table next to
  `max_active_template`, resolved at spawn time with global default fallback.
