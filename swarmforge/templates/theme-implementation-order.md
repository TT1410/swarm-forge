# Implementation Order

Delivery order for **implementer** work under this theme. Soft for other roles;
`squad_next` hard-gates implementer assignment/spawn until listed providers have
`implementation_sha` on their story packet (implementation merged).

Owned by the analyst (with SL overrides). Sibling of the theme module map —
**not a story**. Do not place this under `stories/` or register it as a story.
Record only via `squad_theme.sh implementation-order` into
`.squad/themes/<theme-id>/implementation-order.md`.

## Format

Non-comment edge lines use makefile-style colons only:

```text
<dependent-story-id>: <provider-story-id> [provider-story-id ...]
```

Meaning: do not start implementer work for `dependent` until each provider
story has completed implementation (packet has `implementation_sha`).

Example:

```text
# foundation first
room-reporting: cave-topology
move-command: cave-topology
# UI after process surface
terminal-ui: room-reporting move-command
```

Do **not** use the word `after` (it can collide with a story id). Invalid edge
lines are rejected when the file is recorded.

Stories with no edge line may implement as soon as story/spec gates allow.

Analyst **always** commits this file at analysis handoff (root `implementation-order.md`).
When there are no multi-story implementer gates, use a comment-only file, for example:

```text
# No multi-story implementer dependencies for this theme.
# Stories may implement when story/spec gates allow.
```

Missing root draft is incomplete analysis. Empty file (no comments, no edges) is rejected
when recording. Comment-only is valid and means no implementation-order gates.
Durable record: `squad_theme.sh implementation-order <theme-id> implementation-order.md`.

## Notes

- Module map remains structural (entities, use cases, UI/IO).
- This file is **delivery order** only.
- Squad leader may edit edges for merge recovery or capacity judgment.
