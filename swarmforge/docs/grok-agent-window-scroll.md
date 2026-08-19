# Grok Agent Window Scrolling

**Bug context:** Operators could not usefully scroll live Grok-backed agent
sessions in tmux panes / terminal windows. Panes looked ~25 lines pinned.

## Product fix (landed)

1. **Grok launch flags** (`swarmforge.clj` + `squad_spawn.clj`):
   - `--minimal` — scrollback-native TUI: finalized blocks go into the terminal’s
     native scrollback; a small pinned region holds the prompt + running turn.
   - `--no-alt-screen` — stay on the main buffer so host scroll and
     `tmux capture-pane` see real history (not an alternate full-screen buffer).
2. **tmux session geometry** on create: `-x 200 -y 50` and
   `history-limit 50000` so detached sessions are not stuck at tiny defaults.
3. **Dashboard agent pane**: `capture-pane -S -2000` (was ~200) with stick-to-bottom
   when near the bottom, and distance-from-bottom preservation when scrolled up.

## Operator paths

| Goal | Approach |
|------|----------|
| Follow live output | Stay at bottom in terminal, or open dashboard agent pane |
| Read earlier output | Terminal/tmux native scroll (with `--minimal`), dashboard pane, or tmux copy-mode |
| Deep history | tmux `history-limit` is already raised at spawn; use copy-mode search if needed |
| Open hidden SL/TS | Dashboard **Open SL** / chat, or `tmux -S <socket> attach -t swarmforge-squad-leader` |

## tmux native scroll (fallback)

In a tmux pane: copy-mode (`prefix` + `[`), scroll with wheel/keys; `q` exits.

## What not to expect

Older Grok fullscreen alt-screen sessions (without `--minimal`) still fight
host scroll. Re-spawn agents after upgrading SwarmForge so new flags apply.

## Related

- SL/TS `window-invisible` by default; dashboard is the operator surface
- `ui-design.md` — cockpit; agent panes are separate from the web board
