# Backlog mission document

Date: 2026-08-20  
Status: implemented  
Extends: `docs/superpowers/specs/2026-08-20-system-analyst-design.md`

## Problem

A product needs a **mission**: what kind of program this is (one console loop, print status, take a command, process it). Hunt stories are features of that program. If a mission file is imported like a story, Start-the-backlog snapshots it, the system-analyst stubs it as a socket, and residual runs it through the story pipeline. That is wrong.

Example: `~/junk/htw-stories/000-Mission.md` begins `#MISSION` and describes the console loop. The other six files are stories.

## Goals

- Operator can add a mission by **Add Story** titled `Mission`, or by importing a markdown file whose first heading is `#MISSION`.
- The mission is visible on the backlog deck, labeled **Mission**, editable and saveable **before Start backlog**.
- Start backlog **requires** a mission and at least one open story.
- The system-analyst **uses** the mission as the product form.
- The mission is **not** a story: not a socket, not in the snapshot, not per-card Start, not a board card, not the analyst→Gherkin→implementer pipeline.

## Non-goals (v1)

- Editing the mission after Start backlog (frozen onto the assignment at spawn).
- More than one mission.
- Frame revision when the mission changes later.
- Hunt-specific prompt text beyond “the Mission is the product form.”

## Recognition

An item is a mission when **either**:

- Title is `Mission` (trim, case-insensitive), or
- First heading is `#MISSION` (optional space, case-insensitive: `#MISSION`, `# Mission`).

Checked on **Add**, **import**, and **Save** before Start backlog.

Import of `#MISSION` without a space: that heading is the mission marker, not a missing title. Title becomes `Mission`. Body is the rest of the file.

## Item

`.squad/backlog/<id>.item`:

- `status: mission` (not `open`, not `started`)
- `title:` operator title (default `Mission`)
- `body:` guidance text

`open` stays the story filter. Snapshot, per-card Start, and residual Starts use `status: open` only.

**One mission.** A second item that qualifies is rejected until the existing mission is deleted or saved as a normal story (title not Mission, no `#MISSION` heading).

## Cockpit

- Deck lists **open stories and the mission**. Count includes both. Mission row labeled **Mission**.
- Mission editor: Save and Delete. **No Start** on that item.
- Save reclassifies from the current title/heading (mission ↔ open story) while still before Start backlog.
- **Start backlog** enabled only when: a mission exists, at least one open story, no `frame_sha`, no in-flight system-analyst.
- Start backlog with only a mission, or only stories, is rejected with a reason that names the missing piece.

## Assignment and residual

- Start backlog snapshots **open story ids only**. The mission is not in `open_item_ids`.
- System-analyst assignment includes a **Mission** section with the saved body.
- **Sockets** are open story titles only.
- Prompt: the Mission text is the product form (one process, that UI/loop). Open items are sockets to stub inside it. Do not invent a second program. Do not treat the mission as a socket.
- `qa/product.md` placeholders are per open story, not the mission.
- After frame approve+merge: residual Starts snapshotted story ids only.

## Failures

- Add/Save a second mission: 409, existing mission named.
- Start backlog without a mission: 400, say a mission is required.
- Start backlog with a mission and no open stories: 400, say open stories are required.
- Per-card Start of a mission item: 409, not a story.

## Tests (acceptance)

- Import a dir with `#MISSION` file plus two story files: one `status: mission` titled Mission, two `open`; mission body is the guidance, not the heading line.
- Add Story title `Mission`: `status: mission`; deck includes it labeled Mission; no Start on that editor.
- Add Story title `Mission` when one already exists: rejected.
- Save mission: body change persists; still `status: mission`.
- Save: strip `#MISSION` and retitle away from Mission → becomes `open`. Save an open item titled `Mission` → becomes mission (only if no other mission).
- Start backlog with stories and no mission: 400. With mission and no stories: 400. With both: 200; `open_item_ids` are the stories only.
- System-analyst assignment has **Mission** body and Sockets for the stories, not the mission title.
- Per-card Start of the mission id: 409.
- After frame on master, residual Starts only the snapshotted stories.

## Implementation notes

- Classify in `create-backlog!` / `update-backlog!` (shared helper). Import already goes through `create-backlog!`; fix markdown title parse so `#MISSION` (no space) is a heading.
- Dashboard: include `status: mission` in the deck list; hide Start on that editor; Start-backlog disabled until mission + open story.
- `start-backlog-all!` already snapshots `open` only; add the mission-exists check.
- Assignment render: Mission section from the one `status: mission` item; sockets already exclude non-open if we pass only open titles.
- Prompt: one short Mission/product-form rule. Do not Hunt-specialize SwarmForge.
