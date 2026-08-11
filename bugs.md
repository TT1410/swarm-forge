# Bugs

No open bugs.

## Recently fixed

- **merge_blocked residual:** hold agent/worktree; no `recover_agent`; drive
  merger; retire only after assignment merged to main.
- **Approval-rejection blockers:** dashboard Resolve removed; clear via
  `squad_approval.sh resolve-rejection` after operator/SL recovery.
- **Implementation order:** analyst authors `implementation-order.md`;
  `squad_theme.sh implementation-order`; `squad_next` hard-gates implementers.
- **Per-template caps:** `squad.conf` max 3 non-singleton / 1 singleton.
- **Spawn residual capacity:** `create_assignment --queue-spawn` counts toward
  concurrent spawn budget (with `request_spawn`).
- **Spawn defer logging / poll:** log each deferred request once; summary
  `spawn-queue-waiting`; adaptive poll backoff under capacity pressure.
- **`sl-watchdog-active`:** no longer logged every poll.
- **Agent pane scroll:** preserve distance-from-bottom when not stick-to-bottom.
- **Grok window scroll:** investigated; see
  `swarmforge/docs/grok-agent-window-scroll.md`.
