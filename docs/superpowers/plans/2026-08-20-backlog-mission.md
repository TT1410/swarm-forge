# Backlog Mission Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Classify a backlog mission (`#MISSION` heading or title Mission), show it on the deck as editable guidance, require it for Start backlog, and feed it to the system-analyst without running it through the story pipeline.

**Architecture:** Classify in `create-backlog!` / `update-backlog!` (`status: mission`). Snapshot, sockets, and per-card Start keep using `status: open`. Dashboard lists mission + open stories. Assignment gets a Mission section from the one mission item.

**Tech Stack:** Babashka Clojure, existing `squadd.web` backlog + dashboard.html, `squad_assign` / `squad_backlog`, `clojure.test`.

**Spec:** `docs/superpowers/specs/2026-08-20-backlog-mission-design.md`

---

### Files

- Modify: `swarmforge/scripts/squadd/web.clj` — classify, one-mission 409, start-all requires mission, per-card Start rejects mission
- Modify: `swarmforge/scripts/squad_backlog.clj` — parse `#MISSION` (no space) as heading; title `Mission`
- Modify: `swarmforge/scripts/squadd/dashboard.html` — deck includes mission, label, hide Start, Start-backlog needs mission + open story
- Modify: `swarmforge/scripts/squad_assign.clj` — Mission section; sockets from open stories only
- Modify: `swarmforge/role-templates/system-analyst.prompt` — Mission is the product form
- Test: `test/swarmforge/system_analyst_test.clj`, `test/swarmforge/open_issues_test.clj`

TDD each task. Do not Hunt-specialize prompts. Do not commit unless asked.

### Task 1: Recognition + create-backlog classification

- [x] Failing tests: title Mission → `status: mission`; `#MISSION` body → mission titled Mission; import dir with `#MISSION` + two stories
- [ ] `mission-title?`, `mission-heading?`, `mission-spec?` in `web.clj`; `create-backlog!` sets `status: mission`
- [ ] `title-body-from-markdown` treats `(?i)^#\s*mission\s*$` as heading, title `Mission`, body without that line

### Task 2: One mission + Save reclassify

- [ ] Second mission 409 names existing id
- [ ] Save body on mission persists, still mission
- [ ] Save strip heading + retitle → `open`; save title Mission on open item → mission (if none other)
- [ ] `update-backlog!` classifies; do not reclassify `started`

### Task 3: Start backlog + per-card Start

- [ ] Start with stories no mission → 400 mission required
- [ ] Start with mission no stories → 400 open stories required
- [ ] Start with both → 200, `open_item_ids` are stories only
- [ ] Per-card Start of mission id → 409 not a story
- [ ] Update existing Start tests to include a mission where they expect 200

### Task 4: Dashboard

- [ ] Deck filter includes `status==='mission'`; row labeled Mission; count includes it
- [ ] Editor hides Start when `item.status==='mission'`
- [ ] `renderFrame`: disable Start backlog unless mission AND open story (and no sha / no in-flight SA)

### Task 5: Assignment + prompt

- [ ] create-product with mission + Walk/Shoot: `## Mission` has body; Sockets have Walk and Shoot, not Mission
- [ ] `other-backlog-titles` for product/sockets: `status` open only
- [ ] Prompt: Mission on the assignment is the product form (one process / that UI). Open items are sockets. Do not treat the mission as a socket.
