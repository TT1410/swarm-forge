Rethinking this.

1. Worker hands a SHA to the Squad Leader. SL merges it, same as a six-pack handoff target. SL handles conflicts. No merger agent. No `merge_blocked`. Daemon does not dry-run, `merge-ready`, or `accept-merge`.
2. Get rid of the dry run.
3. Stories are end to end functional use cases.
4. Module implementation order does not matter. There is no order file and no order gate.
5. No sprints. No project. No theme.
6. Simplify the workflow.
7. Analysts turn a started story into an implementation plan. The analyst **does** use the whole backlog and all completed stories. It follows Clean Architecture: the dependency rule, immutable processing rules, and a hard split between those rules and IO. User approves the plan.
8. QA procedure and Gherkin authored without review. User approves those artifacts. The QA procedure is end-to-end through the UI.
9. Implementer as in six-pack: unit tests first, and Gherkin passing. Starts when the plan and Gherkin are approved. Does **not** wait for the QA procedure.
10. Cleaner adds property tests and cleans.
11. Code reviewer makes recommendations only.
12. Hardener applies those recommendations, then hardens as in six-pack.
13. QA writes the script that executes the QA procedure. It may change the app so QA passes, as long as unit tests and Gherkin still pass. It may add QA-only arguments and APIs. Follows the procedure end-to-end through the UI.
14. Architect makes recommendations. It **does** use the whole backlog and all completed stories. Architect and senior implementer keep the module map and dependency rules current. That is not a startup ceremony. If there are no recs, those stories are done.
15. Senior implementer implements architect recommendations, and with the architect keeps the module map and dependencies current.
16. Story complete. No final user bless. Done after SI, or after architect if there are no recs.
17. Stories start in a **backlog**. Operator adds many (title + body); they sit there. Nothing runs until the operator **starts** one. Starting a story puts it on the board and sends it to the analyst. Rejected plan / Gherkin / QA can return to the backlog for edit and another start.

Hardener, QA, architect, and SI batch **every** story that is ready when that hardener starts. That group stays together through Finalizing and may stay together in Done. The group waits for every member before the next step. A late story waits for the next hardener.

Caps live only in `swarmforge/squad.conf` (`max_transient_agents`, `max_active_template`). Not hardcoded. SL and Troubleshooter do not count.

Backends live in config only (`swarmforge.conf` for SL/TS, `transient_agent` in `squad.conf` for workers). Default: **write on Codex, judge on Grok**.

- Squad Leader: Codex
- Troubleshooter: Grok
- Codex (inherit SL): analyst, Gherkin writer, QA-procedure writer, implementer, cleaner, hardener, QA, senior implementer
- Grok: code reviewer, architect

- Global transient cap: 10
- Singleton (`max_active_template` 1): hardener, QA, architect, senior implementer
- Cap 3: analyst, Gherkin writer, QA-procedure writer, implementer, cleaner, code reviewer

## Roles

**Operator.** Dashboard: add and start stories; approve or reject plan, Gherkin, and QA procedure.

**Squad Leader.** Owns main git. Merges each worker SHA and handles conflicts. Does not write product code, plans, or tests. Does not classify theme vs story. Does not start backlog items.

**Troubleshooter.** In-swarm operator. Full authority over the structure: add or remove stories, start or retire workers, change packets and other squad data.

**Analyst.** After Start. One implementation plan for that story. Uses the whole backlog and all completed stories. Clean Architecture as above. Does not invent stories or an order file.

**Gherkin writer.** After the plan is approved. Writes the `.feature` file. No reviewer. User approves.

**QA procedure writer.** After the plan is approved. May run beside Gherkin. Writes the end-to-end-through-UI procedure. No reviewer. User approves.

**Implementer.** After plan and Gherkin are approved. One story. Units first, Gherkin passing. Does not wait for the QA procedure. Does not write property tests.

**Cleaner.** After implementer. Property tests and clean. Does not apply code-review recs.

**Code reviewer.** After cleaner. Recommendations only. Recs go to the hardener. No property tests. No production edits.

**Hardener.** After code review. Applies recs, then hardens as in six-pack. May batch ready stories.

**QA.** After hardener. Writes the script that runs the QA procedure. May change the app and add QA-only hooks; unit tests and Gherkin must still pass. May batch ready stories.

**Architect.** After QA for the stories it takes. Recs. Uses the whole backlog and completed stories. Keeps module map and dependencies current. May batch. No recs → those stories are done.

**Senior implementer.** After architect recs. Implements them. Keeps module map and dependencies current with the architect. May batch. Then the story is done.

Gone: merger, Gherkin reviewer, QA-procedure reviewer.

This is already on this SHA: `.squad/backlog`, deck button with count, Add vs start (today labeled Approve). Keep the holding area; drop theme/project dispatch.

## Code

This tree has a lot of leftover machinery. Redo is not another overlay.

**Delete with each task.** When a path dies, delete its functions, templates, config lines, and tests in that same commit. No empty stubs. No `theme-candidates` that always returns `[]`.

Dead with the matching task: merger, dry-run, `merge_blocked`, **`squad_theme.sh` / `squad_theme.clj`**, `.squad/themes/`, theme create/map/approve/finalize, implementation-order gate, B96 implementer pairs, Gherkin/QA reviewers, final bless, hardcoded singleton set.

A story packet has no `theme_id`. `squad_packet.sh create` takes a story id, not a theme. Module map and dependency rules are ordinary files that architect and SI edit. They are not theme records.

`squad_next.clj` should shrink. New behavior lives in `redo_*_test.clj`; old tests that encoded the dead path are deleted, not inverted into no-ops.

After each task: leftover functions in touched files have CRAP ≤ 6 (`bb crap`). Delete first; do not paint coverage on doomed code.

UI: `redo-ui.md`.
