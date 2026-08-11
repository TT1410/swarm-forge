# Main Git Ownership (Single Writer)

## Goal

**Only `squadd` mutates the main product git repo** for integration
(`merge-ready` / dry-run worktrees / `accept-merge`).

| Who | Git role |
|-----|----------|
| **Workers** | Commit in **own worktree only** |
| **squadd** | **Only** process that runs `merge-ready` / `accept-merge` (and other main-repo mutations) under one lock |
| **SL** | Residual judgment only; must **not** race the daemon on merge-ready/accept |

Workers still share the object store via linked worktrees, but they must not call
main accept. Dual mechanical drivers (daemon + SL both running
`squad_next --apply-mechanical`) were a major source of intermittent
`ORIG_HEAD.lock` / `Operation not permitted` failures.

Retries with random backoff remain useful **inside** the single owner; exclusive
daemon ownership is the structural fix.

---

## Why the daemon

| Criterion | Daemon |
|-----------|--------|
| Always on while swarm lives | Yes |
| Deterministic, no LLM | Yes |
| Already runs handoff bookkeeping + mechanical apply | Yes |
| Natural place for a global git lock | Yes |
| SL stays judgment-only | Yes |

The SL is interruptible, sometimes idle, and already shares mechanical work with
the daemon—which is what double-drives accepts today. Prompts already say
“prefer not to re-run deterministic work”; this design **enforces** that.

---

## Phase 1 — Policy (stop the second driver)

**Files:** `squad-leader.prompt`, `local-workflow.prompt`, watchdog / web wake
messages in `squadd.clj` / `web.clj`

1. **SL must not run** `squad_assign.sh merge-ready` or `accept-merge` in normal
   flow.
2. **SL must not run** `squad_next.sh --apply-mechanical` as a continuous loop
   competitor.
   - Prefer: plain `squad_next.sh` (inspect residual only), **or**
   - `--apply-mechanical` only when residual says a **non-git** command the
     daemon failed (and never merge-ready/accept).
3. Wake text: “daemon applies merges; if idle, inspect residual for judgment
   only.”

This alone cuts dual accept pressure a lot, even before a hard lock.

---

## Phase 2 — Hard gate in `squad_assign` (belt)

**File:** `squad_assign.clj`

1. Add env (or config) flag, e.g.
   `SWARMFORGE_MAIN_GIT_OWNER=daemon` (default on for swarm).
2. At start of `merge-ready` / `accept-merge` (and optionally worktree-add for
   dry-run):
   - If owner is daemon **and** process is not the daemon → exit non-zero with a
     clear message:
     `MAIN_GIT_OWNER: only squadd may run merge-ready/accept-merge`.
3. How to know “is daemon”:
   - Simple: env `SWARMFORGE_ROLE=squadd` or `SWARMFORGE_MAIN_GIT=1` set only in
     `squadd`’s `process/sh` env when it runs mechanical.
   - Or: caller must hold the lock (Phase 3).

Daemon’s `apply-workflow-mechanical!` already sets env for `squad_next`; extend
that to set the allow flag for child `squad_assign` invocations.

---

## Phase 3 — Single global main-git lock (serialize)

**File:** e.g. `squad_assign.clj` or small `squad_git_lock.clj`

1. Lock path: `.swarmforge/squad/main-git.lock` (dir + owner pid, same pattern as
   `spawn.lock`).
2. **Acquire** before any of:
   - dry-run worktree add + merge
   - `accept-merge` / `git merge` on main
   - anything else that writes main refs
3. **Release** in `finally`.
4. Timeout / stale: if owner dead, clear (like spawn lock).
5. Daemon is the only process that should wait-and-retry on this lock; SL should
   not be in the contest.

Retries with random backoff stay **inside** the lock-holder for transient EPERM;
the lock stops two processes merging at once.

---

## Phase 4 — Residual / mechanical split

**File:** `squad_next.clj`

1. Keep **daemon** applying:
   `record_assignment_result`, `check_merge_readiness`, `accept_merge`, finish
   handoff, park held, claim new, retires, spawns, bookkeeping.
2. When residual would surface `COMMAND: squad_assign.sh accept-merge …` to SL:
   - Prefer **not** printing that as SL work if daemon will do it next poll —
     residual becomes `wait` / “waiting for daemon merge”.
   - Or residual only for **failed after daemon retries** (true blocker for
     operator).
3. Tests: mechanical apply from a “non-daemon” env refuses merge-ready/accept;
   daemon env succeeds under lock.

---

## Phase 5 — Optional: SL never full-apply

**File:** `squad_next.clj` CLI

- Add `squad_next.sh --residual-only` (no apply) for SL default.
- Daemon alone uses `--apply-mechanical`.
- Documents / prompts: SL uses residual-only; daemon owns apply.

Cleaner than hoping the model obeys “prefer not to.”

---

## What workers keep doing

No change to product commits in **worktrees**. They still use the shared object
store, but they must not call main accept. Main-git lock on the daemon covers
the dangerous path; workers are not the main problem.

---

## Order of work (pragmatic)

| Step | Change | Risk |
|------|--------|------|
| 1 | Prompt + wake: SL must not accept-merge / not race mechanical | Low |
| 2 | Env gate so only daemon can call merge-ready/accept | Medium (must set env correctly) |
| 3 | `main-git.lock` around dry-run + accept | Medium |
| 4 | Residual: don’t hand accept to SL | Low |
| 5 | Keep random backoff retries inside daemon | Already mostly there |

---

## Tests to add

1. Non-daemon `accept-merge` → rejected.
2. Daemon-env `accept-merge` → allowed (fixture).
3. Two concurrent accepts → second waits on lock, both eventually OK or second
   retries.
4. SL residual after failed merge is **not** “run accept-merge yourself” but
   wait/report.

---

## Bottom line

**How:** Make **squadd** the only process allowed to run `merge-ready` /
`accept-merge` (env gate + global main-git lock), and stop the SL from running
competing `--apply-mechanical` merge steps (prompt + residual + ideally
residual-only CLI). Retries stay as soft recovery **inside** that single owner.

---

## Status (implemented)

| Piece | Where |
|-------|--------|
| Env gate `MAIN_GIT_OWNER` | `squad_assign.clj` — `ensure-main-git-owner!` on merge-ready/accept |
| Global `main-git.lock` | `squad_assign.clj` — `with-main-git-lock` around dry-run + accept |
| Daemon env | `squadd.clj` `apply-workflow-mechanical!` sets `SWARMFORGE_ROLE=squadd`, `SWARMFORGE_MAIN_GIT=1` |
| SL residual | `squad_next.sh --residual-only`; main-git residual → `wait_for_daemon_main_git` |
| Prompts / wakes | squad-leader prompt+contract, local-workflow, squadd/handoffd/web wakes |
| Tests | non-daemon reject; daemon allow; stale lock reclaim; residual-only defer |
