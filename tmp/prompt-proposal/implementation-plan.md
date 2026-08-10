# Worker Prompt Proposal

## Goal

Transient workers should not read the full constitution or recursively load the files it references. Each spawned worker should receive a small injected prompt that tells it to read exactly two instruction files:

1. `swarmforge/worker-common.prompt`
2. `swarmforge/role-templates/<template>.prompt`

The generated per-agent prompt should then provide only runtime facts and assignment content.

## Proposed Runtime Prompt Shape

`squad_spawn` should render each transient prompt as:

```text
Read swarmforge/worker-common.prompt.
Read swarmforge/role-templates/<template>.prompt.
Then follow the runtime facts and assignment below.

# Transient Agent
agent_id: ...
template: ...
task_id: ...
project_root: ...
assigned_worktree: ...
tool_cache_dir: ...

# Assignment
Source file: ...
...
```

It should remove:

- `Read swarmforge/constitution.prompt, then read every file it refers to recursively...`
- embedded duplicate worker protocol text that now belongs in `worker-common.prompt`
- duplicated role-independent helper rules from role prompts

## File Responsibilities

`worker-common.prompt` owns:

- SL communication rules
- worktree discipline
- lifecycle states and helper command formats
- commit and handoff obligations
- blocker behavior
- tool-cache boundaries
- web/network restrictions
- no spawning and no user communication

`role-templates/<template>.prompt` owns:

- role scope
- role-specific input and output artifacts
- role-specific tool requirements
- role-specific verification expectations
- review decision semantics for reviewer roles

The generated prompt owns:

- agent id
- template
- task id
- project root
- assigned worktree
- shared tool cache path
- assignment file path and assignment body

## Implementation Steps

1. Add `swarmforge/worker-common.prompt`.
2. Reduce all transient role templates so they no longer repeat common worker protocol.
3. Change `squad_spawn.clj` to inject two read instructions instead of constitution recursion.
4. Keep assignment `Tool Startup` generation unchanged, but make role prompts say to follow that generated section.
5. Update role contract tests to expect the two-file worker model.
6. Add a spawn test that generated prompts do not mention recursive constitution loading.
7. Add a spawn test that generated prompts reference exactly the common worker prompt and the selected role prompt.
8. Remove obsolete architecture worker roles from the live project:
   - delete `swarmforge/role-templates/architecture-cleaner.prompt`
   - delete `swarmforge/role-templates/architecture-reviewer.prompt`
   - remove `architecture-reviewer` from `swarmforge/scripts/squad_next.clj`
   - remove `architecture-reviewer` special cases from `swarmforge/scripts/squad_assign.clj`
   - update spawn and daemon tests that still use `architecture-reviewer` or `architecture-cleaner` as capacity examples
   - replace architecture capacity examples with live roles such as `architect` and `senior-implementor`
9. Remove nonexistent generic worker roles from the live project:
   - delete `swarmforge/role-templates/reviewer.prompt`
   - delete `swarmforge/role-templates/specifier.prompt`
   - update tests that use `reviewer` or `specifier` as generic spawn, handoff, or cleanup fixtures
   - replace generic reviewer fixtures with live reviewer roles such as `gherkin-reviewer`, `qa-procedure-reviewer`, or `code-reviewer`
   - replace legacy specifier fixtures with live worker roles such as `analyst` or `gherkin-writer`
   - keep README mentions of non-squad historical branches only if they describe those branches, not current squad worker roles
10. Run focused prompt/spawn tests, then full test suite.

## Compatibility Notes

The squad leader is not covered by this proposal. The SL can keep richer workflow context because it coordinates the swarm. This proposal is only for transient worker agents spawned by `squad_spawn`.

The proposed role prompts below are draft content. They are intentionally shorter than the current role templates and depend on `worker-common.prompt` for shared behavior.
