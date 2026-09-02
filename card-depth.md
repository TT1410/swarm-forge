# Card depth

## Purpose

Ceremony should follow the card, not the project.

Today a project is born as a two-pack, four-pack, or six-pack. Every
task on that project runs the whole chain, even when the work is a
pure helper, a Gherkin-only policy change, or a headed UI change.
That pushes teams into extra projects (or platoon squads) just to
shorten the pipeline.

**Card type** is a field on the board card. The lieutenant chooses it.
The dashboard displays it. The pipeline runs only the chain for that type.
One project, one set of roles, four ticket types.

This is not four concurrent packs on one tree. It is one pipeline
with an early exit (or a late start).

## Branch

Do **not** implement this on `two-pack`, `four-pack`, or `six-pack`.
Do not rename `refactorer` or thin those prompts. `main` stays as
it is (the tagged multi-project swarm).

Work lives on a **`lieutenant`** branch, forked from `main`. That
branch carries the **host** (dashboard, helpers, lieutenant agent)
and the **lieutenant pack** (the six-role pipeline):

`specifier → coder → cleaner → architect → hardender → QA`

The pack is on this branch the way six-pack is on `six-pack`:
`swarmforge.conf`, role prompts, local constitution. Role prompts
start from six-pack, then differ: card type, receive-time "this
card" brief, specifier does not ask for the next feature. Cleanup
is **cleaner**. Mutation is **hardender**. No dialect.

The pack template lives at **`.swarmforge/project-pack`** on this
branch (conf, six roles, local constitution). `get-swarm-forge`
installs the **host** from this branch into the forge. It does
**not** compose that pack as a project at `.`, and does **not**
install a choosable pack under `packs/`. The template is already
in the host tree at `.swarmforge/project-pack`.

**New Project** copies `.swarmforge/project-pack` into
`projects/<name>/` the same way `main` copies `packs/<pack>/` into
a project. No pack radios
(two/four/six). No Utility/Component/QA/Review radios — those are
**New Task** only. Every new project is this pipeline. A GitHub
clone is the same copy-then-instantiate; the first card is often
**review**. Open/Refresh refreshes a project from
`.swarmforge/project-pack`, not from `packs/two-pack` etc.

### Reuse (do not change `main`)

Dashboard and helpers on `lieutenant` start as copies of `main`.
Partition **on this branch** so the delta is small and could merge
later. Do not extract files on `main` to make that true.

**Dashboard.** `pack/dashboard.html` starts as `main`'s shell
(layout, bands, Attention, chat). **New Project is a lieutenant
delta:** edit that dialog here; drop pack radios. Every new
project is this six-role pipeline. Lieutenant-only **New Task**
UI (four type radios, badge, type on POST) can still live in a
separate include (`pack/dashboard-types.js` or a marked region).
`main` keeps serving one unedited file.

**Helpers.** Card-type logic is one new library, e.g.
`card_type.bb`: parse/write the board column, legal types, chain
table, starting lane, last role, `to:` membership. `pack_board`, `swarm_handoff`, `handoffd`,
`pack_web`, `ready_for_next` on this branch `load-file` it. The
diff against `main` in those scripts should be the load plus call
sites, not a rewrite.

Role prompts, `lieutenant.prompt`, and handoff constitution
articles on this branch are allowed to diverge from `main` / six-pack.
They are the differences.

## Vocabulary

- **Type**: Which chain this card travels. One of `utility`,
  `component`, `QA`, or `review`. Stored on the board row in
  `tasks.tsv`. `QA` here is the card type (headed UI path), not
  the QA *role*.
- **Card**: A board task (`tasks/<name>.md` plus the swimlane row).
  Type is part of the card, visible on the dashboard.
- **Lieutenant**: Chooses type when creating or accepting a card.
  Does not specify behavior and does not implement.

Four named types. Not a free graph of roles. Not a 1-pack jack of
all trades. Not two packs on one project.

Names and Owns are the **six-pack** set as used on this branch.
Cleanup is **cleaner**. Type is which chain, not which pack
dialect. Early exit **drops** later gates; it does not dump them
onto whoever is last.

## Paths

The branch keeps six windows (specifier, coder, cleaner, architect,
hardender, QA). A card uses one of these chains. That is not a
prefix of `roles.tsv`: utility skips specifier; review starts at
cleaner.

| Type | Chain | When |
|---|---|---|
| **utility** | coder → cleaner → Done | Internal helpers, wiring, rename. No user story. The card text *is* the spec. **No Gherkin.** |
| **component** | specifier (Gherkin, **no** headed QA suite) → coder → cleaner → architect → **hardender** → Done | Observable policy, no pixels. Gherkin + mutation. No headed QA. |
| **QA** | specifier (Gherkin **and** headed QA suite) → coder → cleaner → architect → hardender → QA → Done | User-visible behavior. Headed QA through the UI. |
| **review** | cleaner → architect → hardender → QA → Done | Existing tree. No new feature. Specifier and coder do not run. The QA role **passes through** if `qa/` is missing or empty. If those files exist, QA runs them and does not invent new ones. |

- **utility** skips specifier, architect, hardender, and the QA
  role. The lieutenant creates the card in the **coder** lane.
- **component** skips the QA role. Specifier writes Gherkin and
  does not write headed QA procedures. Hardender's forward handoff
  marks the card Done.
- **QA** is the full six-role path (today's six-pack).
- **review** skips specifier and coder. It **always starts at
  cleaner**, then architect, hardender, and the QA role. The QA
  role always gets the card. **Pass through** means `qa/` is missing
  or empty: terminal handoff, no headed work, no suite invented. If
  `qa/` has files, QA runs them and does not add new ones. A GitHub
  clone / brownfield repo often begins with a review card.

Store these names in `tasks.tsv`. Badge and `--type` use the same
four words. Do not store `2` / `5` / `6`.

This is a change from the first draft of this note, which stopped
the component path at architect and skipped mutation. Policy with
no UI is what you want mutated. Headed QA is what has nothing to
do.

A **utility** card is **weaker** than today's two-pack (no
architecture, no mutation). That is intended: utilities do not
absorb later gates.

## Who chooses

The lieutenant. Not the specifier, not the coder, not New Project.
New Project does not pick a pack or a task type. Task type is New
Task and the lieutenant.

Type is chosen by **what can fail**, not by how small the diff looks.

- New or changed **pixels / window behavior** → **QA**
- New or changed **observable policy** (filters, protocol rules,
  use cases) with no shell change → **component**
- **No Gherkin would survive mutation** (helpers, wiring, rename) →
  **utility**
- **Existing tree**, no new feature, needs cleanup, structure,
  and/or mutation → **review**. The QA role still sees the card;
  it passes through if `qa/` is missing or empty.
- If unsure (new behavior) → **component**
- Never **utility** for a protocol or business rule
- Never **component** for a new button, menu, or visible layout change
- Never **review** for new product behavior — recut as component or QA

A later card may raise type (a component tab filter plus a QA
"click the tab and see it"). A card must not be lowered to dodge
the QA role. Review is not a dodge; it is "the behavior is already
there."

The operator approves the **plan** (and may override a card's type).
The operator does not pick the next card's type as a matter of
routine.

## Display

Type is visible on the swimlane card next to the name. Operators
and agents must see it without opening `tasks/<name>.md`.

Badge is the type name: `utility` / `component` / `QA` / `review`.
The name must be present.

The **New Task** dialog has five radio buttons: Utility, Component,
QA, Review, and **LT**. Default **component**. Utility through
review store that type on a waiting card. **LT** does not create a
card; it sends the name and text to the lieutenant. The operator
picks a type the same way the lieutenant picks it on
`pack_board create --type`.

**New Project** does not have those radios, and on this branch it
does not have pack radios either. Task type is per card. Every
project is this six-role pipeline.

Store type with the board row in `tasks.tsv` (alongside name, lane,
task id). The task document may repeat it in a header for agents
who read the file:

```text
# hex round-trip

Type: utility
```

Handoff headers include `type:` (the card type) so every role sees
the same value the board shows. Roles must not change it. (Handoff
already has a message `type:` of `git_handoff` / `note`. The card
type needs a distinct header, e.g. `card_type:`.)

## Pipeline mechanics

One specifier, one coder, one cleaner, one architect, one hardender,
one QA. Same worktrees as today.

1. Lieutenant (or operator New Task) creates a card with a type and
   a name. Utility is created in the coder lane; component and QA
   in specifier; review in cleaner.
2. Each role does its usual work, then `git_handoff` to the next
   role **in this card's chain**. After the last role for that
   depth, the helper marks the card Done. It does not invent a
   handoff to a skipped role.
3. Reverse / `back-all` only includes roles that actually ran on
   this card. The QA role does not reverse-merge onto a component
   card. On review, QA still ran even if it passed through.
4. Specifier, on Done of a previous card, does **not** ask the
   operator for the next feature. It waits for a card, or takes one
   already in its lane.

Filling the pipe: when specifier hands a component or QA card to
coder, the lieutenant may submit the next **independent** card
(often a utility, a review, or a non-overlapping component) into a
free lane. Review occupies cleaner onward (specifier and coder
are free at the start). It does not pair with a utility on cleaner
at the same time. A QA-type card is Done when the
plan node is checked off, not when the next card is first allowed.

Independence is a heuristic: different namespaces, no shared feature
file, not two edits to the same window. Parallel cards on one tree
can still conflict; that remains expected. Prefer one live QA-type
card and one utility/component/review over two QA-type cards.

Last role by path: **cleaner** (utility), **hardender** (component),
**QA role** (QA type and review). Architect is last on no path.

Specifier's Attention gate stays for component and QA types. Utility
and review never hit it.

## Lieutenant and the plan

Today the host lieutenant is a forge concierge: chat, suggest a
project, point at `mission.md`, do not implement. Card type makes
it the **planner and dispatcher**. Specifier no longer pulls the
next feature from the operator. The lieutenant pushes cards.

That is a much bigger role, not a footnote after the board field.

The lieutenant:

- Keeps a long-term plan (DAG of named cards). Each node has a
  name, dependencies, and a type.
- Gets operator approval of that plan. The operator may override a
  card's type. The operator does not pick the next card's type as
  routine.
- Chooses type by **what can fail** (see Who chooses).
- Cuts the next ready card: writes `tasks/<name>.md`, sets type,
  `pack_board create --type` into the right project and lane.
- Fills the pipe: when specifier hands a component or QA card to
  coder, may submit the next **independent** card into a free lane
  (often a utility, a review, or a non-overlapping component).
  Prefer one live QA-type card and one utility/component/review
  over two QA-type cards.
- Checks off Done nodes, unlocks dependents, cuts the next ready
  card.
- On clarify / contradiction / reverse merge, **stops filling**.
  The plan may be wrong; recut rather than queue more work.

It still does not implement, does not write Gherkin, and does not
follow pack constitution. Specifier turns component and QA cards into Gherkin
(and headed QA procedures when type is QA).

It lives on the forge, not in a pack window. Creating a card means
`--root projects/<name>` (or the same project flag New Task uses).
It has to see each open project's board (`pack_board list`), not
only chat.

Notify the lieutenant when:

- Specifier `git_handoff`s to coder (specifier is free; fill the pipe)
- Card Done (check the plan node; unlock dependents)
- Clarify / contradiction (do not queue more work on a stuck pack)
- QA or architect reverse merge (the plan may be wrong)

Without those notifies, the lieutenant is guessing from chat. The
prompt is not enough.

**Notify:** `handoffd` (project) writes a file under the **forge**
`.swarmforge/` naming the project and the event (specifier handed
to coder, card Done, clarify, reverse merge), then tmux-wakes the
lieutenant pane. Same idea as other wakes; the file is the payload.

Until this role exists, the only legal card source is operator New
Task. Specifier must still not ask for the next feature — that
vacuum is why the lieutenant is not optional in the design, only
phaseable after the field exists.

### Prompt rules

These go in `lieutenant.prompt` on this branch. They are judgment,
not helper if-trees. Do not pin the wording with tests.

- Choose type by **what can fail**: pixels → QA; observable policy,
  no shell → component; no Gherkin would survive mutation →
  utility; existing tree, no new feature → review. If unsure (new
  behavior) → component.
- Review always starts at cleaner, then architect, hardender, QA.
  The QA role passes through if `qa/` is missing or empty.
- Never review for new product behavior. Recut as component or QA.
- Never utility for a protocol or business rule. Never component
  for a new button, menu, or visible layout.
- A GitHub clone / brownfield project often starts with a review
  card.
- Cut cards with `pack_board --root projects/<name> create --type`.
  Never pass `--lane`. Never `git_handoff`. Never implement or
  write Gherkin.
- Fill idle lanes. Prefer one live QA-type card and one
  utility / component / review over two QA-type cards. Review
  occupies cleaner onward; do not pair it with a utility on
  cleaner at the same time.
- On clarify, contradiction, or reverse merge: **stop filling**.
  Recut rather than pile on.
- Off-plan (a card not on the operator-approved plan): ask the
  operator. Do not treat markdown as something the helper parses.
- Board `done`, `stop`, `increment-audit`, and moving a **live**
  card only after Attention. Starting a waiting card into its
  starting lane does not need Attention. Happy path later-lane
  moves are `handoffd`.

### Gates

The lieutenant is also the troubleshooter. Do not put the workflow
in an if-tree in the prompt and hope. Gate **mechanics**. Leave
**judgment** (type, independence, recut text, when to stop) to the
agent and the operator. Off-workflow is allowed only as **urgent**,
and urgent still does not mean implement.

**Hard (helpers refuse):**

- Not a pack agent. `swarm_handoff`, `merge_and_process`,
  `done_with_current` refuse `SWARMFORGE_ROLE=lieutenant`. The
  lieutenant does not sit on a pack worktree.
- `pack_board move`, `done`, `stop`, and `increment-audit` take
  **`--caller`** (`handoffd` or `lieutenant`). They succeed for
  `--caller handoffd`, for `--caller lieutenant` starting a
  **waiting** card into that type's starting lane (no Attention),
  or for `--caller lieutenant` **after an Attention check** on
  any other act. Chat does not count. Any other caller is refused.
  Happy-path later-lane cards still move only because `handoffd`
  delivered a `git_handoff`.
- `pack_board archive` takes **`--archive <window>`** (which pane to
  capture), not `--role`. Do not reuse `--role` for caller.
- Does not change type on a live card. Close or recut (new card).
  Recut is delete/archive of the old row plus `create --type`.
- `create` type must be `utility` / `component` / `QA` / `review`.
  Starting lane is forced by type (utility → coder, component and
  QA → specifier, review → cleaner).
- While that project has a pending clarify or an in-flight reverse
  merge, **no new card** except a recut of the stuck card, or an
  operator override (Attention). Do not pile work on a stuck pack.

**Attention, not reject:**

- A second live QA-type card is a warning, not a lock. Independence
  is a heuristic.

**Prompt, not a helper gate:**

- Off-plan create (a card not on the operator-approved plan) is a
  **lieutenant prompt rule**: ask the operator; do not treat the
  markdown plan as something `pack_board` can parse. Troubleshooting
  a hole the plan missed is real; it is not silent. The helper does
  not hold the create.

**Never gated:** chat, clarify, `pack_board list`, writing the plan
markdown, notes that are not `git_handoff`, refusing to fill.

**Urgent** means recut, stop filling, and talk to the operator. It
does not mean the lieutenant writes Gherkin, commits in a role
worktree, or jumps a card to QA.

## Relation to packs, subprojects, and platoons

This branch does not use `two-pack` / `four-pack` / `six-pack`.
Those stay on `main`. The only pack here is the **lieutenant pack**
on this branch, instantiated per project, not into the forge. Card
type is not a pack radio.

- **Subprojects / platoon squads** remain for real components
  (independently deployable artifacts, separate path law, contracts).
  They are the wrong tool for "this card is small."
- Do not stand up a `util/` squad to avoid Gherkin. Use a utility
  card on the project that owns the helper.
- Architecture specs in-process still enforce dependency direction
  (`ui` must not be required from policy namespaces). Type is not
  a substitute for Clean Architecture.

Do not put two pipelines on one project. The useful parallelism is
two **cards** (fill idle lanes).

## Names and duties

On this branch the names and Owns lists **are** six-pack's, copied
then edited. Cleaner never mutates. Architect never mutates.
Hardender mutates on component, QA, and review. Specifier writes
the headed QA suite only on type QA. Early exit drops later gates;
it does not fatten the last role.

Two-pack / four-pack dialects are why this is not those branches.
A utility card here is weaker than today's two-pack (no architecture,
no mutation). That is intended.

## Agents must not shapeshift

The same named windows handle all four types. A standing
"if type is utility, ignore APS" in `coder.prompt` will be ignored;
the agent keeps the six-pack identity. The **tree still has
`features/`** from other cards. The coder will try to run
acceptance tests on a utility card unless the **turn** is narrowed.

What each path suppresses (same names, six-pack Owns):

| Path | Specifier | Coder | Architect / hardender |
|---|---|---|---|
| utility | does not run | no APS, no `features/`, no acceptance tests; card text is the spec | do not run |
| component | Gherkin, **no** headed QA suite | APS + acceptance tests; ignore headed QA suite | as six-pack |
| QA | Gherkin + headed QA suite | same coder as component | as six-pack |
| review | does not run | does not run | cleaner first; then structure + mutation; QA role passes through if no procedures |

Do not put that in an if-tree in the role file. Put it on the card,
at receive time, and in the helper:

- `ready_for_next` prints `CARD_TYPE:` and a short **this card**
  in/out list. That is the turn.
- Helpers refuse the wrong artifacts: a utility commit must not add
  `features/` or headed QA procedures; a component specifier must
  not add headed QA procedures; a review card must not add Gherkin
  or headed QA procedures as a new product spec.
- `to:` must be on this card's chain.
- Tool Startup stays the six-pack max. It does not mean "on this
  utilities card, generate an acceptance entrypoint."

Architect, cleaner, hardender, and the QA role change much less.
The QA role never receives utility or component. On review it
always receives the card and passes through if there is nothing to
run. Hardender is last on component, not a different hardender.

## Constraints

- Utility cards must not add `features/*.feature` or headed QA
  procedures. (Helper can refuse those files.)
- Utility cards must not import the GUI toolkit or open a window.
  **Prompt rule** (coder this-card brief), not a helper gate.
- Component cards must not require headed QA. If the behavior is
  only visible on screen, the plan needs a later QA-type card, or
  this card should have been QA.
- Review cards must not add new product behavior, Gherkin, or a new
  headed QA suite. If the review finds missing product work, recut
  as component or QA. **Pass through:** `qa/` missing or empty →
  QA Dones with no headed work. If `qa/` has files, QA runs them;
  if they fail, recut as QA type. QA does not invent a suite.
- Hardender runs mutation on component, QA, and review (testable
  modules, as today), not on utility.
- Agents must not "upgrade" or "downgrade" type mid-flight. Change
  of type is a lieutenant/operator action: close or recut the card.

## Not now / rejected

- **Arbitrary paths** (`coder → hardender` as a jump, lieutenant
  picks a subset). If the middle is skipped, that is role shopping.
  Lawful cards are the four chains above. Review is a **suffix**
  (cleaner → architect → hardender → QA), not a hole in the middle.
  It is not a general start/end field.
- **1-pack** jack of all trades. A generalist window becomes the
  escape hatch. Review-and-harden is cleaner through the QA role,
  not one agent doing everyone's job.
- **specifier → QA** for a user-found headed-coverage hole, no
  product change. Real, and a jump. Still deferred. It is not this
  review type (no specifier). If it returns, it is a fifth named
  type. If the QA role fails, recut as type QA; do not have the QA
  role patch the product.
- **Two of the same role** (two coders) on one project. Not until
  one role is saturated. Fill-the-pipe first.

## Implementation

One field that every path already uses, not a second pipeline. The
board row is the source of truth. The New Task dialog, `pack_board`,
and `git_handoff` all read and write that field.

Do this **on `lieutenant`**, in order: persist the field, expose it
in New Task, paint the badge, make the pipeline stop where the
card says, then give the lieutenant the dispatcher job. Copy
six-pack role prompts onto this branch and edit them as part of
making the pipeline stop. Do not touch pack branches or `main`.

### 1. Store type on the board row

`tasks.tsv` is tab-separated with no header:

`name  lane  created  updated  task-id  audit_count`

Add `type` as the next column: `utility`, `component`, `QA`, or
`review`. A missing column on an old row means **QA** (the full
chain on this branch). New cards default to **component**.

`rewrite-lane` and `rewrite-audit-count` rebuild the line from named
fields and would drop type if left as-is. Same for parsers in
`pack_web.bb` (`task-entry`), `handoffd.bb`, and `swarm_handoff.bb`.
Parse and write through one row helper so move, audit, list, and
`/api/state` all keep the field.

`pack_board create` takes `--type`. Reject anything other than
`utility` / `component` / `QA` / `review`. **`--type` is required.
`--lane` is rejected** if present — lane is computed from type, not
chosen by hand. Every project on this branch has the six windows.

Starting lane comes from type, not from "always master":

- **utility** → `coder`; the New Task note goes to coder
- **component** or **QA** → specifier (master); the note goes to
  specifier
- **review** → cleaner; the note goes to cleaner

`write-task-doc!` puts `Type: utility` (or `component`, `QA`,
`review`) under the title. Agents do not change it.

### 2. New Task dialog and API

Today the dialog is name + text; `POST /api/tasks` is
`{name, text, project}`; `create-task!` always uses `master-role`.

Add five radio buttons next to Name and Task: Utility, Component,
QA, Review, LT. Default **component**. Submit
`{name, text, type, project}`. Utility through review use the same
create path as `pack_board --type`. **LT** skips create and notifies
the lieutenant.

On success, clear the radios back to component.

Do not add those radios to **New Project**. On this branch New
Project does not choose a pack.

### 3. Show it on the card

`/api/state` already sends each task to `cardEl`. Add `type` there.
Render the type name next to the card name (same title row as the
audit count). The name has to be on the card, not only in
`tasks/<name>.md`.

### 4. Early exit is the card chain, not last-in-pack

Today Done is "the sender is the last role in `roles.tsv`"
(`terminal-handoff?` / `last-pack-role?`). Change that to "the
sender is last **on this card's chain**." Look up the card. Use the
path table, **not** `take n` on `roles.tsv`.

| Type | Chain | Last role |
|---|---|---|
| utility | coder → cleaner | cleaner |
| component | specifier → coder → cleaner → architect → hardender | hardender |
| QA | full six roles | QA role |
| review | cleaner → architect → hardender → QA | QA role |

`handoffd` Done-on-card-last is necessary and not sufficient.
`swarm_handoff` today uses last-pack-role in two other places that
must follow the card:

- `with-non-forwarding` — last **on this card** is merge-only
- `reverse-roles` — `back-all` / `back-one` copy to earlier roles
  **on this card**, not every earlier pack window

Utility makes **cleaner** last on the card while the QA role is
still last in the pipeline. If those helpers stay "last window in
`roles.tsv`," cleaner still forwards to architect and the card does
not go Done.

Terminal `to:` is every pack role **upstream of last-on-card**,
including roles before the card's starting lane. The helper copies
`card_type:` from the board into the handoff header, refuses a
`to:` that is downstream of last (no `to: QA` on component, no
`to: hardender` on utility), requires specifier and coder on
review, and refuses the wrong artifacts (see Agents must not
shapeshift). Roles must not change the header.

`ready_for_next` prints `CARD_TYPE:` (and the next role / this-card
in-out list) so the agent does not infer the path from the task
file.

### 5. Role prompts and constitution

Shared `handoffs.prompt` and the protocol on this branch: next role
is this card's chain; last role is terminal. Six-pack-derived role
prompts still name a fixed next window ("hand off to hardender").
Edit those copies here. Do not edit the pack branches.

Per-role `to:` and work, six-pack names:

- **Lieutenant** — `--type`, failure-mode rules, starting lane.
- **Specifier** — component: Gherkin, no headed QA suite, then
  coder. QA type: Gherkin + headed QA procedures, then coder.
  Utility and review: does not run. After Done, wait for a card;
  do not ask for the next feature.
- **Coder** — utility: card text is the spec. Component and QA
  type: implement Gherkin. Review: does not run. Always cleaner
  next when they run.
- **Cleaner** — same cleanup. **utility: they are last** (terminal
  `to:` specifier,coder). Component, QA type, and **review:
  they are first on review**; architect next.
- **Architect** — utility: skipped. Component, QA type, and review:
  hardender next. Not last.
- **Hardender** — utility: skipped. **component: they are last**.
  QA type and review: QA role next.
- **QA role** — last on QA type and on review. Terminal `to:`
  specifier,coder,cleaner,architect,hardender, including
  pass-through when `qa/` is missing or empty (no suite invented).
  If `qa/` has files, run them; do not add new ones.

Do not pin prompt wording with automated tests (`Agents.md`). Tests
hit the board, helper, `handoffd`, artifacts, and the dialog.

### 6. Lieutenant as dispatcher

The field can land with operator New Task only. The design does not:
specifier has stopped asking, and nobody else is cutting cards.

This slice is the bigger lieutenant, not a later nice-to-have.

- Expand `lieutenant.prompt` with **Prompt rules** above. Still do
  not implement.
- Card create from the forge: `pack_board --root projects/<name>
  create --type …` (same path as `POST /api/tasks`).
- Board visibility from the forge: list cards per open project.
- Wake the lieutenant on specifier handoff, card Done, clarify, and
  reverse merge: forge notify file plus tmux wake, not "watch the
  board."
- Plan storage can start as markdown the lieutenant writes (same
  habit as `tasks/<name>.md`). Durable DAG UI is not required to
  start dispatching.
- Specifier prompt: do not ask the operator for the next feature;
  wait for a card.

Independence remains a lieutenant heuristic, not a helper that
proves two cards do not conflict.

### Test spine (behavior, not copy)

- `pack_board create --type utility` → coder lane, `Type: utility`
  in the task file, TSV column set
- `pack_board create --type utility --lane specifier` is rejected
- default create → component
- `POST /api/tasks` with `type: utility` same as CLI
- dashboard New Task: five radios on the forge (including LT),
  default component; submitted utility shows on the card; New
  Project has no type radios and no pack radios
- component: hardender `git_handoff` → Done, not the QA role
- component: architect `git_handoff` → hardender
- QA type: hardender `git_handoff` → QA role (today's full path)
- utility: cleaner `git_handoff` `to:` specifier,coder → Done, not architect
- helper rejects `to: QA` on a component card; `to: architect` on a
  utility card; review terminal includes specifier and coder;
  utility terminal includes specifier and coder
- helper rejects `features/*.feature` on a utility or review commit
- `pack_board create --type review` → cleaner lane; QA role
  `git_handoff` → Done
- review with missing or empty `qa/`: QA still receives the card
  and Dones (pass through); no suite is added
- review with files in `qa/`: QA runs them; does not add files
- move/audit do not strip type
- `with-non-forwarding` and reverse copies follow the card chain
- `pack_board --root projects/<name> create --type component` from
  the forge cwd
- specifier→coder handoff writes a forge notify file and would
  tmux-wake the lieutenant; same for card Done
- lieutenant `swarm_handoff` is rejected
- `pack_board move` / `done --caller handoffd` succeeds; `--caller
  lieutenant` waiting→starting-lane succeeds with no Attention;
  other lieutenant `move` / `done` / `stop` / `increment-audit`
  need Attention; any other caller is rejected
- `pack_board archive --archive <window>` (not `--role`)
- `pack_board create` while that project has a pending clarify is
  rejected except recut or operator override
