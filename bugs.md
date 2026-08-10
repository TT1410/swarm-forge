# Bugs

Current scope: issues observed during the August 9, 2026 swarm trial.

Fixed outside this list:

- **Agents disappear before retirement** — dashboard now shows agents from
  spawn until `retired` (including temporary `failed`), via
  `dashboard-agent-visible?` in `squadd/web.clj`.
- **Reviewer handoffs use ambiguous free-form decisions** — reviewer roles now
  use `squad_review.sh` to send a structured `review_decision:
  accepted|changes-requested`, and assignment results persist that field for the
  workflow advisor.

Open bugs:

None.
