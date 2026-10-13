#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
RUNTIME_ROOT="$SCRIPT_DIR"
GC=
STARTUP_TIMEOUT=180
while [ "$#" -gt 0 ]; do
    case "$1" in
        --gc) [ "$#" -ge 2 ] || { echo "--gc requires g1 or zgc" >&2; exit 2; }; GC=$2; shift 2 ;;
        --startup-timeout) [ "$#" -ge 2 ] || { echo "--startup-timeout requires seconds" >&2; exit 2; }; STARTUP_TIMEOUT=$2; shift 2 ;;
        --root) [ "$#" -ge 2 ] || { echo "--root requires a path" >&2; exit 2; }; RUNTIME_ROOT=$2; shift 2 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done
if command -v python3 >/dev/null 2>&1; then PYTHON=python3; elif command -v python >/dev/null 2>&1; then PYTHON=python; else echo "Python 3 is required to run the smoke test." >&2; exit 2; fi
set -- "$SCRIPT_DIR/tools/smoke.py" --root "$RUNTIME_ROOT" --startup-timeout "$STARTUP_TIMEOUT"
[ -n "$GC" ] && set -- "$@" --gc "$GC"
exec "$PYTHON" "$@"
