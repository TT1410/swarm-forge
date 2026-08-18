Note: refer to the behavior of the current squad when this document says “as usual”.

# Sprints

The Squad Leader owns one project. A project has many stories. Every story belongs to that project.

The project is the backlog, the sprint being assembled, the sprint in flight (if any), and the completed sprints.

Stories can be added to a project at any time, even while sprints are executing. A story added after a sprint has started stays in the backlog (or a draft sprint) until a later sprint.

## Backlog

The backlog is the unscheduled remainder: stories not in a draft sprint and not in the scheduled sprint.

Operators can add, edit, or delete stories in the backlog.

## Scheduling

Operators move selected stories from the backlog into a draft sprint. They may assemble the next sprint while another sprint is in flight. They may not schedule it until the current scheduled sprint is complete or cancelled.

Scheduling a draft sprint starts implementation.

Rules:
1. Only one sprint can be scheduled at a time.
2. Once scheduled, the sprint is locked. The squad implements it and takes it to completion. It cannot be changed.
3. A scheduled sprint can be cancelled. Its stories return to the backlog unchanged. In-flight work is tagged as abandoned and kept on a branch.

## Sprint 0

The first sprint is sprint 0. It implements no stories.

The Squad Leader reviews the stories that were in the sprint when it was scheduled and produces:
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
