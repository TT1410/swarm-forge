# Bugs

## Open Bugs

1. **Agent Pane Scroll Position Jitters When New Output Arrives**

   When an agent pane is open (click agent → pane window) and the operator has
   scrolled in that view, the scroll position jitters when new text is appended
   at the end. Reading mid-history becomes unstable as output grows.

   Expected: if the user is not pinned to the bottom, keep a stable scroll
   anchor (no jump/jitter when content is prepended or length changes). Only
   auto-scroll to the bottom when the user was already near the bottom (or
   chooses “New output” / stick-to-bottom).

2. **Approval-Rejection Blocker Recovery Should Be SL-Routed, Not A Dashboard Resolve Button**

   Durable approval-rejection blockers (under `.squad/blockers/`) must stay
   visible to the squad leader (e.g. via `squad_next` `handle_durable_blocker`)
   so the SL cannot claim “no blocker” while files remain. Clearing must not be
   a one-click dashboard **Resolve** control.

   Expected recovery model:
   - Operator and SL discuss how to clear the blocker.
   - Typically: reword the story / QA procedure / related artifact, then route
     the document back to the appropriate review (or other) workflow step.
   - Only after that recovery work is done, the SL uses a **CLI tool** to clear
     the durable blocker (archive rejection, remove blocker files, reopen the
     gate for re-request). The tool is not a substitute for fixing the issue.
   - Remove the dashboard Resolve button / one-click resolve API as the primary
     operator path; keep (or refine) an SL-facing clear tool for use after
     recovery.

   Related: durable blockers should not permanently starve all other residual
   workflow if that freezes the swarm—surface them accurately without making
   “handle blocker” the only possible next action forever.

3. **SL Loops On `recover_agent` For Quiet Agents Whose Assignments Are Already `merge_blocked`**

   Observed on the Hunt the Wumpus run: `squad_next` residual stays
   `recover_agent` for implementers (and similar) that are `handoff_sent` with
   stale heartbeats, while `squad_recover` reports `delivered_handoff` or
   `live` and the assignment is already **`merge_blocked`**. The SL re-runs
   recover and `squad_next --apply-mechanical` forever; squadd’s SL watchdog
   keeps waking the SL (~1 min), amplifying the loop.

   Root dynamics:
   - Quiet `handoff_sent` → recovery candidate.
   - Handoff already delivered/completed; work cannot land (merge conflict).
   - `merge_blocked` is not a terminal state for retirement, so the agent is
     not retirable and the residual never advances.
   - Recover does not change durable state → next residual is recover again.

   Expected:
   - After handoff is recorded and assignment is `merge_blocked`, residual
     should prioritize **merge recovery** (merger/rework/block), not endless
     `recover_agent` for that quiet worker.
   - Or allow retire/hold once handoff is terminal-for-worker purposes and
     merge outcome is already `merge_blocked`.
   - SL should not be stuck in recover ↔ next when recover state is unchanged
     (`delivered_handoff` / `live` + merge_blocked).
