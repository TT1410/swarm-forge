# SwarmForge adversaries

The `adversaries` branch is an experimental two-agent review loop. A coder
implements and verifies a feature; a reviewer repeatedly challenges the result
until it either produces another committed recommendation set or records final
approval.

The repository's master branch is named
[`main`](https://github.com/unclebob/swarm-forge/tree/main). Read its
[README](https://github.com/unclebob/swarm-forge/blob/main/README.md) for the
base SwarmForge concepts and prerequisites. `adversaries` is a separate line of
work, not a `get-swarm-forge` product.

## Structure

This branch keeps only its workflow definition in git:

```text
swarm
swarmforge/
  swarmforge.conf
  constitution.prompt
  constitution/
    articles/
      project.prompt
      local-workflow.prompt
      handoffs.prompt
  roles/
    coder.prompt
    reviewer.prompt
```

`swarmforge/scripts/` is deliberately ignored. On first start, `./swarm`
downloads the shared scripts and shared-article staging files from `main`; later
starts reuse the local copy. Generated runtime state lives in `.swarmforge/`
and the reviewer checkout lives in `.worktrees/reviewer`.

## Configuration

The branch's [`swarmforge/swarmforge.conf`](swarmforge/swarmforge.conf) defines
two visible, task-mode Codex roles. Its non-comment lines use the shared form:

```text
window[-invisible] <role> <backend> <worktree> [task|batch] [forward-only|back-one|back-all] [backend arguments...]
```

File order makes coder the first role and reviewer the second. `window` opens a
terminal surface as well as a tmux session. Both lines omit the optional modes,
so both use `task` and `forward-only`.

`master` means the main project checkout on its current branch; the branch does
not need to be named `master`.

## Constitution

[`swarmforge/constitution.prompt`](swarmforge/constitution.prompt) is the
instruction entry point and takes precedence over every file it loads from
`swarmforge/constitution/articles/`:

| Article | Branch-specific responsibility |
|---|---|
| [`project.prompt`](swarmforge/constitution/articles/project.prompt) | Declares the two-agent project shape, local state locations, ownership boundary, and repeating coder/reviewer route. |
| [`local-workflow.prompt`](swarmforge/constitution/articles/local-workflow.prompt) | Defines the adversarial iteration, numbered recommendations, and approval artifact that terminates the loop. |
| [`handoffs.prompt`](swarmforge/constitution/articles/handoffs.prompt) | Defines this experiment's task-mode file handoffs and completion rules. |

The `swarm` bootstrap also caches `main`'s shared article files beneath the
downloaded runtime, but this branch's constitution loads the three committed
articles listed above. That distinction keeps the experiment's older handoff
semantics explicit.

## Roles

Each configured role must have a matching prompt:

| Prompt | Working directory | Ownership |
|---|---|---|
| [`coder.prompt`](swarmforge/roles/coder.prompt) | project root (`master`) | Implements requested behavior with TDD, runs unit and acceptance verification plus CRAP, DRY, and mutation checks, and applies reviewer recommendations. |
| [`reviewer.prompt`](swarmforge/roles/reviewer.prompt) | `.worktrees/reviewer` | Adversarially inspects correctness, architecture, tests, maintainability, evidence, and edge cases. It may write only review artifacts and never edits product code. |

The configuration selects topology and backend; the constitution defines the
loop's common law; the prompts give each agent exclusive ownership.

## Review loop

```text
New Task → coder → reviewer
                    ├─ changes needed → coder → reviewer → …
                    └─ satisfactory → approval artifact → stop
```

1. The operator gives the feature to the coder. The coder implements it, runs
   the required checks, commits, and sends a git handoff to the reviewer.
2. The reviewer examines code, tests, history, handoff state, architecture, and
   verification evidence. Its review is intentionally critical but must remain
   concrete and actionable.
3. When changes are needed, the reviewer writes the next numbered file under
   `review/recommendations/`, beginning with
   `001-recommendations.md`. Each item names the issue, risk, and expected
   correction. The reviewer commits that artifact and hands it back.
4. The coder implements the latest recommendations, reruns verification,
   commits, and returns the result for another review.
5. When satisfied, the reviewer writes and commits `review/approval.md` with
   the reviewed commit, rationale, and residual risks. It reports completion
   and sends no further work handoff.

The approval file—not another coder handoff—is the terminal condition for this
branch. The repository retains the full sequence of recommendations and the
final decision as review evidence.

## Runtime components and generated state

The committed `swarm` script bootstraps `swarmforge/scripts/` from `main` when
that ignored directory is absent, then runs the shared launcher. The launcher
parses this branch's configuration, creates the reviewer worktree, synchronizes
the constitution and role prompts, and starts tmux, the handoff daemon, the
dashboard, and both visible terminal surfaces.

`.swarmforge/` is generated transport and process state: role/session maps,
handoff inboxes and outboxes, daemon records, board data, and pane metadata.
`.worktrees/reviewer/` is a generated checkout. In contrast,
`review/recommendations/` and `review/approval.md` are committed product-review
evidence and intentionally remain in repository history.

## Run and operate

Use the branch directly or copy its archive into the project. From a prepared
checkout:

```sh
./swarm
```

Startup obtains shared scripts if necessary, creates the reviewer worktree,
starts the handoff daemon and dashboard, and opens visible tmux-backed terminal
surfaces for coder and reviewer. **New Task** targets the coder. Use Attention
for clarifications and the dashboard or agent windows to follow the loop.

Stop the swarm with dashboard Teardown or by closing the coder window, which is
the first configured visible window.

Shared launcher, terminal, and transport changes belong on `main`. The
adversarial loop, review artifacts, and coder/reviewer ownership belong on this
branch.
