# Redo UI

**Spec:** `redo.md`  
**Plan:** `redo-implementation-plan.md` (Slice 6)  
**Base:** cockpit at `b73c972` (`squadd/dashboard.html`)

The unit is one end-to-end **story**. No sprints. No project. Operators never see “theme.”

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

| Column | Cards |
|--------|--------|
| Specifying | Stories in implementation plan, Gherkin, or QA procedure (including waiting for user approval of those artifacts) |
| Coding | Implementer, code reviewer, cleaner |
| Finalizing | Hardener, QA, architect, senior implementer |
| Done | Completed **stories** |

Unstarted stories (no analyst yet) stay in the **backlog deck**, not on the board.

Stories are index cards. A **short pill** names the stage: `plan`, `gherkin`, `qa-proc`, `implement`, `review`, `clean`, `harden`, `qa`, `architect`, `si`, `done`.

---

## Attention

Only user approvals:

- implementation plan
- feature (Gherkin)
- QA procedure
- later story bless (architect / SI), if the FSM still requires it

Each row: gate · story id · **View document** · Approve · Reject.

No theme map. No Gherkin/QA reviewer.

---

## Add Story

Name + description textarea. Enter adds; Shift+Enter newline. **Add** lands in the backlog only. **Start** (today labeled Approve) puts that story on the board and sends it to the analyst. No SL classifying theme vs story.

Click a board card: story detail (text + links to plan / feature / QA / reviews).

---

## Work Queue

First column is the **story**. Second is the **role** (analyst, gherkin-writer, implementer, code-reviewer, cleaner, …). No merger. No module/sprint.

---

## Status bar

`next action:` from residual (plan, implement, merge, …). No `residual:` label. No project badge.
