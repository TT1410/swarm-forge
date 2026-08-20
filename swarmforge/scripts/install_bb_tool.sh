#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: install_bb_tool.sh <bb-task>" >&2
  exit 1
fi

bb_task="$1"
tool_root="${SWARMFORGE_TOOL_SRC_DIR:-}"

if [ -z "${SWARMFORGE_TOOL_TARGET:-}" ]; then
  echo "SWARMFORGE_TOOL_TARGET is required" >&2
  exit 1
fi

if [ -z "$tool_root" ]; then
  echo "SWARMFORGE_TOOL_SRC_DIR is required" >&2
  exit 1
fi

if [ ! -f "$tool_root/bb.edn" ]; then
  echo "Tool root is missing bb.edn: $tool_root" >&2
  exit 2
fi

cat > "$SWARMFORGE_TOOL_TARGET" <<EOF
#!/usr/bin/env bash
exec bb --config "$tool_root/bb.edn" "$bb_task" "\$@"
EOF
