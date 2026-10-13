#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
RUNTIME_ROOT=${1:-"$SCRIPT_DIR"}
OFFLINE=
if [ "${2:-}" = "--offline" ] || [ "${1:-}" = "--offline" ]; then
    OFFLINE=--offline
    if [ "${1:-}" = "--offline" ]; then RUNTIME_ROOT="$SCRIPT_DIR"; fi
fi

if command -v python3 >/dev/null 2>&1; then
    PYTHON=python3
elif command -v python >/dev/null 2>&1; then
    PYTHON=python
else
    echo "Python 3 is required to verify runtime artifacts." >&2
    exit 2
fi

if [ -n "$OFFLINE" ]; then
    exec "$PYTHON" "$SCRIPT_DIR/tools/fetch_artifacts.py" --root "$RUNTIME_ROOT" "$OFFLINE"
fi
exec "$PYTHON" "$SCRIPT_DIR/tools/fetch_artifacts.py" --root "$RUNTIME_ROOT"
