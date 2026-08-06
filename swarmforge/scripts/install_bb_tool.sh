#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "Usage: install_bb_tool.sh <tool-root> <bb-task>" >&2
  exit 1
fi

tool_root="$1"
bb_task="$2"

if [ -z "${SWARMFORGE_TOOL_TARGET:-}" ]; then
  echo "SWARMFORGE_TOOL_TARGET is required" >&2
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
