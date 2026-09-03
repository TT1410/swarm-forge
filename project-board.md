# Project board

This checkout is the **lieutenant** forge. Pack templates are not
choosable. Every project is the six-role pipeline copied from
`.swarmforge/project-pack`. Several projects can run at once. Chat
talks to the **lieutenant**, not to a project agent.

## Install

`get-swarm-forge lieutenant` installs this host and
`.swarmforge/project-pack`. It does not write `packs/two-pack` and
friends. `./swarm` starts the dashboard and the lieutenant only.

## New Project

The dashboard has a **New Project** button. The dialog has:

- **name** — the project directory under **projects**, or, with GitHub
  checked, `owner/repo`. The directory name is the last path segment.
- **github repo** — when checked, that repo is cloned into
  `projects/<inferred-name>` and then treated as the new project.
- **mission** — written to `mission.md` at the top of the project.

There are no pack radios. Every new project is this pipeline.
Existing names get an alert and are not overwritten.

Opening a project **refreshes** it from `.swarmforge/project-pack`
(keeps `mission.md` and the project's conf) and starts that pack.

## Cockpit

Top to bottom:

- **New Project** and **Open Project** — at the top, next to each other,
  above Attention.
- **Attention** — stays in the same place. Each row names the **project**
  as well as the task (approvals and clarifications from every open
  project).
- **Board and Work Queue** — stacked project bands, independently
  scrollable.
- **Chat** — follow-ups to the **lieutenant**.

Each project band has a **horizontal bar** above its swimlane cards.
That bar is the project header. **New Task** and **Close** are on that
bar.

**New Task** defaults to **LT** (name and text go to the lieutenant, no
card). Utility, Component, QA, and Review park a **waiting** card. The
lieutenant starts it when the plan says so.

## Concurrent projects

More than one project can run at the same time. Each band is this
pipeline. The dashboard stacks projects top to bottom, split by a
**horizontal bar**, in both the swimlanes and the Work Queue. Those two
sides **scroll independently**.

## Close, Open, Teardown

**Close** is per project. It kills that project's agents, removes its
bands, and leaves the directory.

**Open Project** is the inverse: a menu of directories under
`projects/`. Already-open names get an alert.

**Teardown** stops every running project, kills the lieutenant, and
shuts the dashboard. Directories under **projects** stay. After a later
`./swarm`, nothing is running until you Open Project.
