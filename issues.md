# Resolved Issues (lieutenant)

The issue and agreed solution below are implemented in this change. Its
acceptance criteria are retained as the record of the intended behavior.

## High: Treat a receiver-created batch as one atomic unit of work

A batch receiver currently collects compatible handoffs from its inbox and
places them in one batch directory, but the resulting batch is not presented or
processed as a single unit of work.

`ready_for_next_batch` calls `merge_and_process` once for every handoff, in
filename order, and prints the batch description only after all of those merges
succeed. When an early merge conflicts, the helper exits before the agent sees
the batch identity, its complete membership, or its atomic completion rule. The
agent can then mistake the first handoff for an independent task.

`swarm_handoff` compounds the problem. A handoff made while a batch directory is
current automatically receives every member's task ID. The first handoff can
therefore archive the whole batch and move every card together even when its
commit contains only part of the batch.

This happened in the Spacewar cleaner:

- The cleaner grouped `adaptive-polling` at `2b8c1c5` and
  `tactical-persistence` at `a477af4` into one batch.
- `2b8c1c5` is an ancestor of `a477af4`, so `a477af4` was the single commit that
  represented all incoming work.
- The helper tried to merge `2b8c1c5` first. That redundant merge conflicted and
  stopped the helper before it printed the batch.
- The cleaner resolved and cleaned only `adaptive-polling`, then handed off
  `717ac3f`.
- The outgoing handoff inherited both batch task IDs, so both cards were marked
  done although the tactical work was absent.
- The cleaner later produced the tactical cleanup as `8fd8b81`, but could not
  hand it off because its card was already done.

Solution:

- Form and persist an explicit batch manifest before attempting any merge. The
  manifest records the batch ID, ordered member names and task IDs, each
  incoming commit, and the commit selected to represent the complete batch.
- Treat "latest" as ancestry, not timestamp or filename order. Select the
  unique member commit that contains every other member commit in its ancestry.
- Refuse to start a batch when no unique member commit contains all members.
  Report the non-linear commits instead of silently performing multiple merges.
- Tell the agent about the atomic work unit before merging anything. Output the
  batch ID, every member, the selected merge commit, and an explicit instruction
  that all members are one unit requiring one combined result commit and one
  outgoing handoff.
- Merge only the selected commit. On a conflict, retain the manifest and repeat
  the same batch declaration so rerunning `ready_for_next` resumes the same
  atomic unit.
- Before accepting the outgoing handoff, verify that every incoming member
  commit is an ancestor of the submitted result commit.
- Carry the batch ID and full membership on the outgoing handoff. Transition all
  member cards together only after the complete batch handoff is accepted.

Done when:

- A compatible linear set of N incoming handoffs causes one merge of its unique
  ancestry-maximal commit, not N sequential merges.
- Batch identity, complete membership, selected commit, and atomic semantics are
  visible before the first merge and remain visible after a merge conflict.
- The agent cannot submit or complete one member separately from the current
  batch.
- A result commit missing any batch member is rejected without archiving the
  batch or moving any card.
- A non-linear set of commits is rejected with a clear diagnostic and no partial
  merge or board transition.
- One valid batch handoff advances every member exactly once and leaves no batch
  member's work stranded on the receiver branch.
- Regression coverage reproduces the Spacewar two-card conflict and proves that
  only `a477af4` is merged and no partial batch handoff can mark both cards done.
