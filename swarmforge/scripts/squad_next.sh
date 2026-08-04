#!/usr/bin/env sh
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
exec bb "$SCRIPT_DIR/squad_next.clj" "$@"
