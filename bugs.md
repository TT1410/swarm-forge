# Bugs

Current scope: issues observed during the August 10, 2026 swarm trial.

Open bugs are ordered by priority: highest first (run-killers and correctness),
then recovery/visibility, then efficiency and polish.

## Open Bugs

10. **Blocker UI Should Offer Re-Enter Pipeline (Design Later)**

    After a story approval is rejected (and once a blocker exists), the dashboard
    should expose a clear control on that blocker — e.g. a button — that puts the
    story back into the pipeline (re-open approval, clear/supersede the rejection,
    and allow gherkin/QA-procedure work to proceed after a new approve).

    Today there is no supported path: `squad_approval.sh approve` only works on
    pending files, a rejected approval blocks re-request of the same gate, and
    recovering requires ad-hoc `squad_packet.sh approve` or deleting the rejected
    record. This needs product/workflow design before implementation — defer
    detailed design until later. Depends on durable blockers (bugs 4 and 6).
