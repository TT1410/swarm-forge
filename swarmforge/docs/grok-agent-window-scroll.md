# Grok Agent Window Scrolling (Investigation Notes)

**Bug context:** Operators cannot usefully scroll live Grok-backed agent sessions
in tmux panes / terminal windows. Related to, but broader than, the dashboard
agent-pane stick-to-bottom behavior.

## What works today

1. **Dashboard agent pane** (`/agent/<id>`): mirrors tmux `capture-pane` (last
   ~200 lines) with stick-to-bottom when near the bottom, and
   distance-from-bottom preservation when the operator has scrolled up. Prefer
   this for reviewing history while the agent runs.
2. **tmux native scroll**: in a tmux pane, enter copy-mode
   (`prefix` + `[` by default), then scroll with wheel/keys; `q` exits. History
   depends on `history-limit` in tmux.conf.
3. **Terminal app scroll**: often fights the TUI (alt screen / full redraw).
   Disabling mouse reporting in the terminal sometimes helps; results vary by
   app (Terminal.app, iTerm2, Ghostty).

## Grok-specific factors

- Grok (and similar agent CLIs) typically run as full-screen TUIs that redraw
  the visible region and may enable the alternate screen buffer.
- Mouse wheel may be captured by the app as input, not scroll.
- tmux mouse mode (`set -g mouse on`) can help wheel-scroll in copy-mode but can
  also intercept clicks intended for the TUI.

## Recommended operator workflow

| Goal | Approach |
|------|----------|
| Follow live output | Stay at bottom in terminal, or open dashboard agent pane pinned to bottom |
| Read earlier output | Dashboard pane (scroll up; “New output” button when unpinned) **or** tmux copy-mode |
| Deep history | Increase tmux `history-limit`; use copy-mode search |
| Avoid fight with TUI | Prefer dashboard mirror over fighting Grok’s alt-screen in the host terminal |

## Possible product follow-ups (not required for close)

- Document this path in operator README / SL prompt.
- Optional: larger capture window or full scrollback file for dashboard pane.
- Optional: spawn Grok with flags that disable mouse capture if the CLI supports them (verify per version).

## Conclusion

**Practical fix for operators:** use the **dashboard agent pane** for scrollable
history; use **tmux copy-mode** for in-terminal review. Do not expect the live
Grok TUI itself to behave like a plain shell log without CLI/tmux configuration
changes.

Investigation complete for SwarmForge purposes; further Grok TUI changes would
be upstream of this project.
