#!/usr/bin/env zsh
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: swarm-cleanup.sh <tmux-socket> <window-ids-file> [session ...]" >&2
  exit 1
fi

TMUX_SOCKET="$1"
WINDOW_IDS_FILE="$2"
TERMINAL_BACKEND="${SWARMFORGE_TERMINAL_BACKEND:-terminal-app}"
WORKING_DIR="$(cd "$(dirname "$WINDOW_IDS_FILE")/.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
shift
shift

cd /

has_command() {
  command -v "$1" &>/dev/null
}

source "$SCRIPT_DIR/swarm-terminal-adapter.sh"
load_terminal_backend "$TERMINAL_BACKEND"

if has_command bb; then
  if [[ -f "$SCRIPT_DIR/stop_squadd.clj" ]]; then
    bb "$SCRIPT_DIR/stop_squadd.clj" "$WORKING_DIR" --full-teardown 2>/dev/null || true
  fi
  bb "$SCRIPT_DIR/stop_handoff_daemon.clj" "$WORKING_DIR" 2>/dev/null || true
else
  squadd_pid_file="$WORKING_DIR/.swarmforge/daemon/squadd.pid"
  if [[ -f "$squadd_pid_file" ]]; then
    squadd_pid="$(< "$squadd_pid_file")"
    if [[ "$squadd_pid" == <-> ]]; then
      kill -TERM "$squadd_pid" 2>/dev/null || true
    fi
    rm -f "$squadd_pid_file"
  fi
  DAEMON_PID_FILE="$WORKING_DIR/.swarmforge/daemon/handoffd.pid"
  if [[ -f "$DAEMON_PID_FILE" ]]; then
    daemon_pid="$(< "$DAEMON_PID_FILE")"
    if [[ "$daemon_pid" == <-> ]]; then
      kill -TERM "$daemon_pid" 2>/dev/null || true
    fi
    rm -f "$DAEMON_PID_FILE"
  fi
fi

for session in "$@"; do
  tmux -S "$TMUX_SOCKET" kill-session -t "$session" 2>/dev/null || true
done

if [[ -S "$TMUX_SOCKET" ]]; then
  while IFS= read -r session; do
    [[ -n "$session" ]] || continue
    tmux -S "$TMUX_SOCKET" kill-session -t "$session" 2>/dev/null || true
  done < <(tmux -S "$TMUX_SOCKET" list-sessions -F '#{session_name}' 2>/dev/null || true)
fi

tmux -S "$TMUX_SOCKET" kill-server 2>/dev/null || true

sleep 1

agents_dir="$WORKING_DIR/.squad/agents"
if [[ -d "$agents_dir" ]]; then
  for agent_dir in "$agents_dir"/*; do
    [[ -d "$agent_dir" ]] || continue
    agent_id="${agent_dir:t}"
    status_file="$agent_dir/status"
    heartbeat_file="$agent_dir/heartbeat"
    old_state="$(awk -F': ' '$1 == "state" { print $2; exit }' "$status_file" 2>/dev/null || true)"
    if [[ "$agent_id" == "squad-leader" ]]; then
      detail="squad leader terminated by cleanup"
    elif [[ "$old_state" == "handoff_sent" ]]; then
      detail="swarm terminated after handoff_sent; inspect handoff inbox/outbox for recovery"
    else
      detail="swarm terminated by cleanup"
    fi
    timestamp="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    printf "state: retired\ndetail: %s\nupdated_at: %s\n" "$detail" "$timestamp" > "$status_file"
    printf "agent: %s\nstate: retired\ndetail: %s\nupdated_at: %s\n" "$agent_id" "$detail" "$timestamp" > "$heartbeat_file"
  done
fi

if has_command git && [[ -d "$WORKING_DIR/.git" ]]; then
  typeset -U managed_worktrees
  managed_worktrees=()
  while IFS= read -r worktree_path; do
    [[ "$worktree_path" == "$WORKING_DIR/.worktrees/"* || "$worktree_path" == */.worktrees/* ]] || continue
    managed_worktrees+=("$worktree_path")
  done < <(git -C "$WORKING_DIR" worktree list --porcelain 2>/dev/null | awk '/^worktree / {print substr($0, 10)}')

  if [[ -d "$WORKING_DIR/.worktrees" ]]; then
    for worktree_path in "$WORKING_DIR"/.worktrees/*(N); do
      [[ -d "$worktree_path" ]] || continue
      managed_worktrees+=("$worktree_path")
    done
  fi

  for worktree_path in "${managed_worktrees[@]}"; do
    [[ "$worktree_path" == "$WORKING_DIR/.worktrees/"* ]] || continue
    agent_id="${worktree_path:t}"
    branch="swarmforge-$agent_id"
    git -C "$WORKING_DIR" worktree remove --force "$worktree_path" 2>/dev/null || rm -rf "$worktree_path"
    git -C "$WORKING_DIR" branch -D "$branch" 2>/dev/null || true
  done
  git -C "$WORKING_DIR" worktree prune 2>/dev/null || true
fi

roles_file="$WORKING_DIR/.swarmforge/roles.tsv"
if [[ -f "$roles_file" ]]; then
  tmp_roles="$(mktemp)"
  awk -F '\t' '$1 == "squad-leader" { print }' "$roles_file" > "$tmp_roles"
  mv "$tmp_roles" "$roles_file"
fi

rm -f "$WORKING_DIR/.swarmforge/daemon/squad-web-url"

if [[ -f "$WINDOW_IDS_FILE" ]]; then
  while IFS= read -r window_id; do
    [[ -n "$window_id" ]] || continue
    terminal_close_window "$window_id"
  done < "$WINDOW_IDS_FILE"
fi
