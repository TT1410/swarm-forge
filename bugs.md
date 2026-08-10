# Bugs

Current scope: issues observed during the August 10, 2026 swarm trial.

Code investigation notes below each bug were added by comparing the report to the current tree (primarily `swarmforge/scripts/`).

## Open Bugs

1. **Transient Failed Status Can Overfill Agent Capacity**

   Observed in `~/junk/squad`: the configured transient capacity is 10, but 11
   agents were running. A gherkin reviewer briefly reported `failed` for tool
   checks, which caused `squadd` to stop counting that still-live agent against
   capacity. While it was temporarily undercounted, `squadd` spawned another QA
   procedure reviewer. The gherkin reviewer then recovered to `running`, leaving 11
   active agents.

   Live agents in temporary failure or recovery states must still count against
   capacity. Only retired agents, and possibly terminal unrecoverable failures
   after explicit workflow handling, should free a transient slot. Tool failures
   that an agent is retrying should be reported as `running` detail or `blocked`,
   not as a capacity-free terminal `failed` state.

   **Discovered in code**

   - Capacity deliberately ignores `failed` in two places:
     - `squad_next.clj`: `active-agent?` excludes `#{"retired" "failed"}`;
       `capacity-counted-agent?` builds on that (plus merger / handoff_sent
       exceptions).
     - `squad_spawn.clj`: `capacity-counted-row?` also excludes
       `#{"retired" "failed"}` before counting a live tmux session.
   - `squad_run.clj` writes agent state `failed` on any non-zero tool/command
     exit (unless `--expect-failure`), then exits. Agents commonly call this for
     verification phases, so a transient tool failure frees a slot while the
     agent process/session can still be alive and later set `running` again via
     `squad_event.sh`.
   - `failed` is an allowed agent-reportable lifecycle state in `squad_event.clj`,
     not a leader-only terminal status.
   - Tests encode the current (buggy for capacity) semantics:
     `squad-next-does-not-wait-on-failed-transients` expects a failed transient
     not to appear as active. Dashboard deliberately still shows failed agents
     (`dashboard-agent-visible?` only hides `retired`), so UI and capacity policy
     disagree about whether failed means “gone.”

2. **Review Handoff Accept-Merge Fails On Untracked Review Artifacts**

   Observed in `~/junk/squad`: multiple review assignments became
   `merge_blocked`. `squad_assign.sh merge-ready` passed in a temporary worktree,
   but `squad_assign.sh accept-merge` failed in the SL/root worktree because an
   untracked review artifact already existed, for example:

   ```text
   error: The following untracked working tree files would be overwritten by merge:
       .squad/reviews/hunt-the-wumpus-finish-replay-qa-procedure-review.md
   Please move or remove them before you merge.
   Aborting
   ```

   The reviewers wrote the review reports inside their own worktrees, which is
   correct for the current tool contract. The unsafe part is the artifact path:
   `.squad/reviews/` is also runtime/workflow state in the SL/root worktree. If the
   SL or a helper materializes a local untracked review report there before merging
   the reviewer commit, Git refuses to merge the committed artifact because it would
   overwrite the untracked local file.

   More precise cause: transient agents are launched in their own worktrees but
   with `SWARMFORGE_PROJECT_ROOT` set to the SL/root worktree. `squad_review.sh`
   validates the review path relative to `SWARMFORGE_PROJECT_ROOT`, so a normal
   worktree-local file such as
   `.worktrees/qa-procedure-reviewer-002/.squad/reviews/<review>.md` does not
   satisfy the helper's required `.squad/reviews/` prefix. In the observed run, the
   root `.squad/reviews/*.md` files that blocked merges were byte-for-byte
   identical to the corresponding agent worktree review files, suggesting the
   review files were duplicated/materialized into the SL/root `.squad/reviews/`
   namespace to satisfy the helper before merge.

   Preferred solution: move committed review artifacts out of `.squad/` and into a
   normal project artifact root, for example `reviews/<assignment-id>.md`.
   `.squad/assignments/<assignment-id>/review.md` can remain workflow state after a
   review is recorded, but `.squad/` should not be the committed artifact namespace
   for reviewer output. This keeps runtime state and mergeable artifacts separate.

   **Discovered in code**

   - Confirmed: spawn launch scripts export
     `SWARMFORGE_PROJECT_ROOT=<root>` and `SWARMFORGE_WORKTREE=<agent worktree>`
     (`squad_spawn.clj` `render-launch-script`). `squad_config/project-root`
     prefers `SWARMFORGE_PROJECT_ROOT`.
   - `squad_review.clj` `ensure-review-file!` requires a path whose relative form
     under project root starts with `.squad/reviews/` and ends in `.md`. A file
     under `.worktrees/<agent>/.squad/reviews/...` relativizes to
     `.worktrees/...` and is rejected.
   - `squad_assign.clj` uses the same `.squad/reviews/` durable-path convention
     (`durable-review-file?`, `review-paths-in-commit`).
   - `accept-merge!` runs `git merge` in the project root and on failure calls
     `block-merge!` → `merge_blocked`. There is no special handling for untracked
     local files colliding with paths in the incoming commit. `merge-ready` uses a
     temporary worktree dry-run, so it can pass even when the root working tree
     already has colliding untracked files.
   - Constitution still documents review reports as durable under
     `.squad/reviews/` (`local-workflow.prompt`), so the preferred
     `reviews/` relocation is a contract change, not only a helper fix.

3. **Workflow Allows Handoff Completion After Merge Block**

   After `accept-merge` failed and marked a review assignment `merge_blocked`,
   `squad_next.sh --apply-mechanical` recommended `finish_in_process_handoff` for
   that same handoff. A handoff whose assignment is merge-blocked is not resolved
   and should not be completed. The workflow should instead keep the handoff
   blocked, declare or route the merge blocker, and preserve enough state for
   recovery before allowing completion/retirement.

   **Discovered in code**

   - `in-process-git-handoff-command` only special-cases assignment states
     `created`/`assignment_created`/`in_progress`/`handoff_sent`/`unknown` →
     record result; `result_received` → merge-ready; `merge_ready` → accept-merge.
     **`merge_blocked` falls through to `nil`.**
   - When that helper returns nil, `print-in-process-handoff-action!` always
     recommends `finish_in_process_handoff` / `done_with_current.sh`.
   - Action priority puts `:finish-in-process` first in `action-rules`, so an
     in-process handoff for a merge-blocked assignment wins over ready actions
     such as `merger-candidates` (`create_assignment` / `request_spawn` for
     merger) until the handoff is finished.
   - Retirement is stricter than handoff completion: `completed-handoff-retirable?`
     treats `merge_blocked` as unresolved unless a downstream merger result is
     recorded. So the handoff can complete while the agent remains non-retirable —
     inconsistent gates.
   - No test appears to assert “do not finish in-process handoff while
     assignment is merge_blocked”; existing handoff-completion tests cover the
     happy path through `merged` then finish.

4. **Swarm Teardown Leaves Ambiguous Worktree Cleanup Residue**

   After killing the swarm in `~/junk/squad`, no `squadd`, watchdog, SL, transient
   agent processes, or `swarmforge-*` tmux sessions remained. However, many
   `swarmforge-*` branch/worktree registrations remained. Some were prunable
   registrations whose directories were already gone, while other reviewer
   worktrees still existed because their assignments were `merge_blocked`.

   It is correct to preserve worktrees/branches needed for merge-block recovery,
   but teardown should make that explicit. Resolved/retired/merged assignments
   should have their worktrees removed, registrations pruned, and branches deleted.
   Merge-blocked assignments should keep the needed worktree/branch and be marked
   as intentionally preserved for recovery. After teardown, a reconciliation pass
   should run `git worktree prune` and report intentionally preserved worktrees
   separately from accidental cleanup leaks.

   **Discovered in code**

   - Two conflicting teardown policies exist:
     - **Partial stop** (`stop_squadd` without `--full-teardown`):
       `cleanup-transient-git!` **skips** roles whose assignment is
       `merge_blocked` (status or merge file). Preserves worktrees silently;
       does not label them or prune dead registrations only.
     - **Full teardown** (`--full-teardown`, and `close-swarm` /
       `swarm-cleanup.sh`): `force-cleanup-all-managed-worktrees!` removes **all**
       managed `.worktrees/*` paths and deletes branches, including merge-blocked
       ones. Tests assert this
       (`window_cleanup_test.clj`: “merge-blocked merger worktrees must be
       force-removed on full teardown”).
   - Preferred bug behavior (preserve merge-blocked, report intentionally) matches
     neither path completely: partial preserves without reporting intent; full
     destroys everything and only prints leftovers via
     `report-remaining-worktrees!` if force-remove failed.
   - `close-swarm` calls `stop_squadd --full-teardown` then `swarm-cleanup.sh`,
     which calls full teardown again and force-removes worktrees a second time.
     Errors are swallowed (`|| true` in cleanup), so partial git failures can
     leave prunable registrations without a clear failure signal.
   - Observation of surviving merge-blocked worktrees after a kill is consistent
     with a non-full stop path, an older cleanup path, or a failed force-remove —
     not with the current full `close-swarm` happy path.

5. **Approval Button Press State Is Lost On Refresh**

   In the web dashboard, holding the mouse down on an Approve button highlights the
   button as expected, but the highlight disappears when the page refreshes. The
   pressed state should survive polling refreshes, and the subsequent mouse up
   should still count as the approval click.

   **Discovered in code**

   - Dashboard polls every 2s (`setInterval(render, 2000)` in `squadd/web.clj`).
   - `render()` always replaces `approvalsPanel.innerHTML` with a freshly built
     table of Approve/Reject buttons. Any DOM node being pressed is destroyed and
     recreated.
   - Press feedback is only CSS `button:active` — no JS pressed flag, no
     localStorage key (unlike the SL message draft, which is preserved via
     `localStorage`).
   - Approve uses `onclick` only (fires on completed click). Mid-hold refresh can
     drop `:active` and leave mouseup on a replaced element, so the click may be
     lost as well as the highlight.
   - No pointer-down / pointer-up / “pending press” handling exists for approvals.

6. **Agent Window Scroll Position Jitters On New Output**

   When an agent is clicked in the web dashboard, the tmux text window opens as
   expected. If the user scrolls to an earlier point in that window, that scroll
   position should hold. Instead, as new text is appended at the end of the window,
   the visible scroll position jitters and jumps.

   **Discovered in code**

   - Pane page already attempts stickiness: measure `nearBottom` before fetch;
     only auto-scroll when near bottom; otherwise show a “New output” button
     (`pane-page` in `squadd/web.clj`).
   - Content update always assigns `pane.textContent = text` for the entire
     capture (last ~200 tmux lines after stripping). Full rewrite reflows the
     document; with sticky headers/layout, same `scrollY` can still visually jump
     when line count or line heights change above the viewport.
   - Capture is only 200 lines (`capture-pane -S -200`). As the live pane scrolls,
     the snapshot window slides; earlier content drops off the top while the user
     is reading mid-history — feels like jitter even if `scrollY` is unchanged.
   - Footer stripping (`strip-input-region`) can oscillate if the input/footer
     marker is intermittent across captures, changing content height each second
     and aggravating scroll drift (see bug 8).
   - `nearBottom` uses `window` scroll metrics against `document.body`; the
     content lives in `pre#pane`. Usually equivalent, but layout quirks can make
     the threshold brittle.

7. **Agent Pane Cleanup Must Be Backend-Aware**

   The dashboard pane popup currently strips input/footer text using Codex-shaped
   rules. Grok panes have a different footer shape, including a boxed `│ ❯` input
   area, a `Grok ...` footer line, and shortcut/help footer lines such as
   `Shift+Tab:mode | Ctrl+x:shortcuts`. When clicking a Grok agent, the window does
   not scroll properly and the input box is not removed properly.

   Pane capture cleanup should detect the agent backend from metadata. Codex panes
   should use Codex-specific input/footer stripping. Grok panes should remove the
   boxed prompt/footer region and shortcut/help lines. Unknown backends should
   render raw or use only conservative stripping. Scroll preservation should be
   backend-independent: if the user is not near the bottom, refreshes must not
   change the scroll position; if the user is near the bottom, the window may
   follow new output.

   **Discovered in code**

   - `strip-input-region` only cuts from the last line starting with `› `
     (Codex prompt). No Grok markers (`│ ❯`, `Grok ...`, shortcut footer lines).
   - `agent-pane-content` reads agent `metadata` for `session` only; it does not
     read `backend` even though spawn writes `backend: <agent>` into that same
     metadata file.
   - Web tests mock Codex-shaped pane output with `›` lines
     (`squadd_web_test.clj`); no Grok pane fixture.
   - Scroll and strip are coupled: bad strip leaves a tall footer or oscillating
     height, which interacts with bug 7’s full-text rewrite.

8. **Analyst Can Bundle Multiple Stories Into One Registered Story**

   Observed in `~/junk/squad`: the analyst wrote six story sections into
   `stories/hunt-the-wumpus-analysis.md`, but handed off only that one artifact.
   The workflow registered one story packet,
   `.squad/stories/hunt-the-wumpus-analysis/packet`, because `squad_next` derives
   one story id from each `stories/*.md` artifact filename and does not parse
   multiple markdown story sections.

   The analyst prompt and contract need to require one story per artifact/file,
   with each file having a distinct story id suitable for registration. The
   workflow should continue treating story artifacts mechanically, rather than
   guessing story boundaries from markdown headings.

   The Squad Leader should also be prepared to repair malformed-but-salvageable
   handoffs. If an analyst handoff contains one file with multiple clear story
   sections, the SL may split it into one file per story, register each story, and
   note the repair. If the split is ambiguous, the SL should reject the handoff and
   send it back to the analyst rather than guessing.

   **Discovered in code**

   - Registration is filename-mechanical: `artifact-story-id` is the basename with
     the extension stripped. `analyst-story-registration-candidates` emits one
     `register_story_artifact` / packet create per `stories/*.md` path listed on
     the merged assignment artifacts — never scans file content for sections.
   - `analyst.contract.edn` allows writes under `stories/` and multi-artifact
     roots, but does not say “one story per file.”
   - `analyst.prompt` says “story artifacts” (plural) and “Commit the story
     artifacts,” but does not require one story per file. Contrast
     gherkin-writer / qa-procedure-writer prompts, which do say “exactly one
     story per assignment and one story per handoff.”
   - Constitution is mixed: `local-workflow.prompt` says analyst handoffs **may
     include multiple stories for one theme** (intended as multiple files), and
     packets are per story id under `.squad/stories/<story-id>/packet`. Nothing
     forbids multi-section single files, so the observed packing is allowed by
     current prompts and mechanically collapses to one story id.
   - Mechanical apply will register that single id once merge succeeds; there is
     no SL repair step automated for splitting multi-section analyst files.
