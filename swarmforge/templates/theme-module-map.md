# Theme Module Map

High-level structural map for this theme (Clean Architecture sketch).
Owned by the squad leader. Approved with the theme. Soft guidance for analyst,
implementer, and architect — not a detailed design.

Keep this document coarse: modules and use cases, not APIs or class diagrams.

## Purpose

One paragraph: what this system is for, and what structural separation protects.

## Dependency Rule

Source dependencies point **inward** (outer mechanisms → inner policies).

- Process / use-case rules must not depend on UI or concrete IO.
- UI and IO adapters depend on process (or on ports owned by process), not the reverse.
- Prefer simple data across boundaries; do not leak UI or persistence types into process.

State the intended dependency direction for this theme in a short bullet list
(component → allowed dependents, or outer → inner only).

## Use Cases (Business / Process Rules)

Primary structure. Organize by **use case**. Each use case is application-specific
business/process policy — not screens and not persistence.

For each use case include:

### Use case: \<name\>

- **Intent:** one sentence goal.
- **Process responsibilities:** what the rules layer decides or orchestrates.
- **Suggested process module(s):** short names that can become namespace segments
  (e.g. `setup`, `movement`, `combat`). Prefer stable, scream-by-use-case names.
- **Shared domain concepts (optional):** entities/values many use cases touch
  (e.g. cave graph, hunter). Keep brief.
- **Does not own:** UI wording, input devices, DB/files, clocks, RNG plumbing.

List every major use case the theme needs. Analysts should treat these as the
default skeleton for story cuts.

## UI (Interface Adapters)

Surfaces and presentation adapters implied by the use cases. Not full UX design.

- Commands / prompts / views / reports that exist for this theme.
- Which use cases each UI surface drives or displays.
- Rule: UI may call into process use cases; process must not import UI.
- List the theme’s interaction surface here (commands, prompt text patterns,
  displayed state, key user-visible outcomes) so UI stories can carry that UI
  and process stories need only reference it indirectly.

## IO (Interface Adapters / Drivers)

External mechanisms: persistence, stdin/stdout, files, network, clock, randomness,
OS, frameworks.

- What IO this theme needs and which use cases require it.
- Prefer ports/adapters: process defines needs; IO implements them.
- Rule: concrete IO stays outside process; process must not depend on concrete IO types.

## Component Sketch (optional)

If useful, name the top-level components (second namespace segments) that should
exist once code lands, e.g.:

- `:ui` — …
- `:<use-case-or-process>` — …
- `:io` or adapters — …

These names must stay consistent with the product `dependency-checker.edn`
policy the **analyst** authors at analysis handoff (not deferred to implementers).

## Tooling Layout (note)

Root `bb.edn` / `deps.edn` are shared infrastructure, not use-case modules.
Prefer thin `bb.edn` (tasks + optional `{:local/root "."}`), deps in `deps.edn`,
and task bodies under `bb/tasks/`. Story work should not thrash root tooling.

## Out of Scope

Explicit non-goals for this map (what not to invent here): story text, Gherkin,
APIs, class design, file-by-file layout, framework choice details.

## Revision Notes

How this map may change (SL updates when stories or architecture review force it).
Leave empty on first draft if none.
