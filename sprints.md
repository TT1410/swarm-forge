Note: refer to the behavior of the current squad when this document says “as usual”.

The living mockup is `sprint-mockup.html`. Run it with `bb swarmforge/scripts/sprint_mockup.clj` (http://127.0.0.1:4987/).

Implementation plan: `sprint-implementation-plan.md`.

# Sprints

The Squad Leader owns one project. A project has many stories. Every story belongs to that project.

The project is the backlog, the named sprints being assembled, the sprint in flight (if any), abandoned sprints, and the completed sprints.

Stories can be added to a project at any time, even while sprints are executing. A story added after a sprint has started stays in the backlog, or in a named draft sprint, until that sprint is scheduled.

## Backlog

The backlog is the unscheduled remainder: stories not in any named sprint.

Operators add, edit, or delete stories in the backlog (dashboard Add Story). That is the usual path. If the human asks the Troubleshooter to create or add stories, Troubleshooter may write and register them itself and must not hand that to the Squad Leader. The Squad Leader stays idle until the operator schedules a sprint.

## Scheduling

Operators assemble one or more named sprints and list them. They move selected stories from the backlog into a named sprint. A story belongs to at most one sprint.

Several sprints may be assembled at once, including while another sprint is in flight. Only a listed, named sprint can be scheduled. They may not schedule a sprint until the current scheduled sprint is complete or cancelled.

Scheduling a named sprint starts implementation.

Rules:
1. Only one sprint can be scheduled at a time.
2. Sprint 0 must be complete before any implementation sprint can be scheduled.
3. Once scheduled, the sprint is locked. The squad implements it and takes it to completion. It cannot be changed.
4. A scheduled sprint can be cancelled. The sprint stays in good standing with its stories still in it. In-flight work is tagged as abandoned and kept on a branch. Schedule that same sprint again with no reopen or reassembly.

## Sprint 0

The first sprint is sprint 0. It is created automatically with the project. It implements no stories. Every later sprint is an implementation sprint.

The Squad Leader reviews every story known when sprint 0 is scheduled — backlog and all named sprints — and produces:
1. A module breakdown, including the dependency map.
2. An implementation order derived from that map.

Stories added after sprint 0 starts are ignored until a later sprint’s map update.

Sprint 0 is complete when those two documents are approved.

## Implementation sprints

Every later sprint implements stories.

When a sprint is scheduled, the Squad Leader:
1. Reviews the stories in the sprint.
2. Adjusts the module map and implementation order if needed. Those updates must be reapproved.
3. Writes the sprint specification: the sprint’s stories plus the (updated) module map and implementation order.
4. Hands that specification to the analyst.

## Analysis

The analyst looks at the stories in the sprint and elaborates them so they are consistent with each other and with the project. It then breaks the work into tasks by module and specifies the intermodule interfaces those tasks depend on.

A task belongs to one module. One task can represent many stories: it is what those stories need from that module. The interfaces say what each module owes the others, so implementers can TDD against the contracts without waiting on neighboring modules.

The analyst hands the elaborated stories, the tasks, and the interfaces back to the Squad Leader. The Squad Leader presents the sprint plan (elaborated stories, tasks, and interfaces) for a single approval. After that approval, two tracks proceed:

1. The Squad Leader schedules the tasks for implementation, in implementation order.
2. The elaborated stories go to test specification.

## Test specification

The elaborated stories are passed, as usual, to the Gherkin and QA authors and reviewers. They produce features and QA procedures that must be approved as usual.

Gherkin is integration testing. It is not the implementer’s spec.

## Implementation

After the sprint plan is approved, the Squad Leader hands each task to an implementer in implementation order. Implementers are not concerned with Gherkin or QA at this point. They do follow TDD.

The pipeline then proceeds as usual: implementers → cleaners → code reviewers.

The result is implemented, cleaned, and reviewed modules.

## Finalizing

Hardening, QA, Architect, and Senior Implementer wait until:
1. Every module in the sprint has passed through the pipeline to that point and is ready for hardening.
2. The sprint’s features and QA procedures are approved.

Then the whole sprint goes through the rest of the pipeline.

Hardening continues as usual. The hardener’s first task is to get the Gherkin passing, by implementing the test harness according to the APA. That is the integration step: module TDD meets the Gherkin.

A sprint is complete when QA and Architect bless it. If Architect hands changes to Senior Implementer, Senior Implementer gives the final blessing.

## Completion

A completed sprint is tagged in git. That tag and its SHA are registered in the list of completed sprints.
