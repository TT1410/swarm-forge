# Bugs

Current scope: issues observed during the August 9, 2026 swarm trial.

Fixed outside this list:

- **Agents disappear before retirement** — dashboard now shows agents from
  spawn until `retired` (including temporary `failed`), via
  `dashboard-agent-visible?` in `squadd/web.clj`.

Open bugs:

| # | Title |
|---|--------|
| 3 | Reviewer handoffs use ambiguous free-form decisions |

## Review Result Contract

### Bug 3: Reviewer Handoffs Use Ambiguous Free-Form Decisions

Reviewers wrote review artifacts with free-form recommendation language, and the
workflow could not reliably classify the review result.

Observed behavior:

1. Review artifacts used phrases such as `Recommendation: accept`,
   `Recommendation: Revise`, `Revise before approval`, and
   `No blocking findings`.
2. `squad_next.sh` only recognized strict decision values such as `accepted` and
   `changes-requested`.
3. Merged review assignments were not recorded into story packets.
4. The workflow reached `NEXT_ACTION: wait` even though several reviewed
   artifacts still needed approval or revision.

Expected behavior:

All reviewer roles should use a dedicated review handoff tool with exactly two
outcome options:

1. `accepted`
2. `changes-requested`

This applies to all review roles, including Gherkin reviewers, QA procedure
reviewers, code reviewers, and architects. The reviewer role prompts should
explicitly instruct reviewers to use that tool for handoff, so the workflow
records a deterministic decision instead of parsing review prose.

Implementation notes:

1. Do not fix this by making `squad_next.sh` better at parsing prose.
2. Review outcome is workflow state and should be written through a deterministic
   workflow helper with a closed vocabulary.
3. The spelling should be `changes-requested`, not `changed-requested`, matching
   the existing FSM vocabulary.
4. The reviewer should still write normal review comments in the artifact.
5. The handoff helper should validate that the assignment exists, the assignment
   template is a reviewer role, the outcome is one of the two allowed values, the
   expected artifact exists, and a commit is present.
6. The handoff or result manifest should carry
   `review_decision: accepted|changes-requested`.
7. `squad_next.sh` should trust that structured field, not infer the review
   decision from review artifact prose.

Architecture notes:

- Highest-leverage structural bug. The system already prefers a closed
  vocabulary, then undermines it by scraping review markdown via a weak exact-line
  parser (`review-decision-from-content` matches `accepted` / `accept`, not
  `Recommendation: accept`).
- Reviewer prompts still say write review + `git_handoff`, not call a closed
  review-decision helper. `review-decision` already prefers
  `(:review-decision assignment)` when present; nothing durable writes that field
  reliably from agents.
- Root cause for several stalls: no packet review → wrong stage → wrong spawn or
  `wait`. Do not make the prose parser smarter.

