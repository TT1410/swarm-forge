# Redo UI

**Spec:** `redo.md`
**Plan:** `redo-implementation-plan.md` (Task 7)
**Base:** cockpit at `b73c972` (`squadd/dashboard.html`)

The unit is one end-to-end **story**. No sprints. No project. No theme.

---

## Layout

Keep the current cockpit chrome: header (title, next action, SL thermometer, Open SL, Open TS, Teardown), Attention, board | splitter | rail.

Drop:

- Project button / project pill as a product control
- Sprint chip and sprint planner
- **Projects** rail section

Keep:

- Add Story (toolbar)
- Backlog deck (count + list of unstarted stories)
- Work Queue
- Troubleshooter chat (Enter send, Shift+Enter newline; hold scroll unless already at bottom)

---

## Board columns

One card, one lane. **Latest stage wins.**

| Column | Cards |
|--------|--------|
| Specifying | Plan and Gherkin (and QA procedure only if that is still the furthest along — implementer has not started). A pending QA-procedure approve stays in Attention after the card moves to Coding. |
| Coding | Implementer, cleaner, code reviewer. Always **one story per card**. |
| Finalizing | Hardener, QA, architect, senior implementer. Ready stories are **batched**. |
| Done | Finished work. A Finalizing group that finished together can stay **one batch card**. |

Unstarted stories (no analyst yet) stay in the **backlog deck**, not on the board.

Stories (or a Finalizing/Done batch) are index cards. A **short pill** names the stage: `plan`, `gherkin`, `qa-proc`, `implement`, `clean`, `review`, `harden`, `qa`, `architect`, `si`, `done`.

### Finalizing / Done batches

When a hardener starts, it takes **every** story that is ready at that moment. That set is one batch card. They stay that group through Finalizing and may stay a batch card in Done.

The whole group waits until every member can take the next step. A late story that becomes ready later does **not** join; it waits for the next hardener, which again takes every story then ready.

If the same stories move on together, they stay one card (hardener → QA → architect → SI). Work Queue lists the stories on that assignment.

---

## Attention

Only user approvals:

- implementation plan
- feature (Gherkin)
- QA procedure

Each row: gate · story id · **View document** · Approve · Reject.

No theme map. No Gherkin/QA reviewer. No final bless.

---

## Add Story

Name + description textarea. Enter adds; Shift+Enter newline. **Add** lands in the backlog only. **Start** (today labeled Approve) puts that story on the board and sends it to the analyst. No SL classifying theme vs story.

Click a board card: story detail (text + links to plan / feature / QA / reviews).

---

## Work Queue

First column is the **story**. Second is the **role** (analyst, gherkin-writer, implementer, cleaner, code-reviewer, …). No merger. No module/sprint. Work Queue may show a batch as several stories on one later-role assignment.

---

## Status bar

`next action:` from residual (plan, implement, merge, …). No `residual:` label. No project badge.
