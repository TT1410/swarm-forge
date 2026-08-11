# Implementation Order

Delivery order for **implementer** work under this theme. Soft for other roles;
`squad_next` hard-gates implementer assignment/spawn until listed providers have
`implementation_sha` on their story packet (implementation merged).

Owned by the analyst (with SL overrides). Sibling of the theme module map.

## Format

Non-comment lines:

```text
<dependent-story-id> after <provider-story-id> [provider-story-id ...]
```

Meaning: do not start implementer work for `dependent` until each provider
story has completed implementation (packet has `implementation_sha`).

Example:

```text
# foundation first
room-reporting after cave-topology
move-command after cave-topology
# UI after process
terminal-ui after room-reporting move-command
```

Stories with no `after` line may implement as soon as story/spec gates allow.
Empty file or missing file means no implementation-order gates.

## Notes

- Module map remains structural (entities, use cases, UI/IO).
- This file is **delivery order** only.
- Squad leader may edit edges for merge recovery or capacity judgment.
