# Clean Architecture for the Module Map

Sketch **policy vs mechanism** for the theme—not APIs, classes, or frameworks.
Use `swarmforge/templates/theme-module-map.md`. Dependencies point **inward only**.

## Core idea

**Inner = policies. Outer = mechanisms.**

| Layer | What it is | Map home |
|--------|------------|----------|
| **Entities** | The **primary data model** plus the **general functions that manipulate that data**—core domain facts and operations many use cases share (e.g. cave graph + adjacency ops, hunter + position/state ops). Most stable; not UI, not concrete IO. | Shared domain list and/or under use cases as **shared domain concepts** |
| **Use cases** | **Application-specific** process rules that orchestrate entities toward a goal | **Use Cases (Business / Process Rules)** — primary structure |
| **UI adapters** | Presentation / interaction surface | **UI** |
| **IO adapters** | Files, stdio, clock, RNG, DB, frameworks | **IO** |

## Dependency Rule

- Source dependencies only point **inward**.
- Use cases may depend on **entities**; neither use cases nor entities depend on UI or concrete IO.
- UI and IO depend on process (or ports process owns)—never the reverse.
- Cross boundaries with **simple data**, not UI models or DB row types.

## What you write (coarse)

1. **Purpose** — what the system is for; what separation protects.
2. **Dependency Rule** — inward-only bullets for this theme.
3. **Entities** — name the primary data and the general operations on it (brief).
4. **Use cases** — for each: intent, process responsibilities, suggested process module(s), which entities they use, “does not own” UI/IO.
5. **UI** — commands, prompts, displayed state; which use cases they drive.
6. **IO** — external mechanisms; process defines needs, IO implements.

## North star

**Entities (data + general ops) and use cases inside; UI and IO outside; arrows only point in.**
