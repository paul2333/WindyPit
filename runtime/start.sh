#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
RUNTIME_ROOT="$SCRIPT_DIR"
GC=
NOGUI=
DRY_RUN=
SERVER_ROOT=
HEAP_MIN=
HEAP_MAX=

while [ "$#" -gt 0 ]; do
    case "$1" in
        --gc) [ "$#" -ge 2 ] || { echo "--gc requires g1 or zgc" >&2; exit 2; }; GC=$2; shift 2 ;;
        --nogui) NOGUI=1; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        --root) [ "$#" -ge 2 ] || { echo "--root requires a path" >&2; exit 2; }; RUNTIME_ROOT=$2; shift 2 ;;
        --server-root) [ "$#" -ge 2 ] || { echo "--server-root requires a path" >&2; exit 2; }; SERVER_ROOT=$2; shift 2 ;;
        --heap-min) [ "$#" -ge 2 ] || { echo "--heap-min requires a value" >&2; exit 2; }; HEAP_MIN=$2; shift 2 ;;
        --heap-max) [ "$#" -ge 2 ] || { echo "--heap-max requires a value" >&2; exit 2; }; HEAP_MAX=$2; shift 2 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

if command -v python3 >/dev/null 2>&1; then
    PYTHON=python3
elif command -v python >/dev/null 2>&1; then
    PYTHON=python
else
    echo "Python 3 is required to start WindyPit." >&2
    exit 2
fi

set -- "$SCRIPT_DIR/tools/launcher.py" --root "$RUNTIME_ROOT"
[ -n "$GC" ] && set -- "$@" --gc "$GC"
[ -n "$NOGUI" ] && set -- "$@" --nogui
[ -n "$DRY_RUN" ] && set -- "$@" --dry-run
[ -n "$SERVER_ROOT" ] && set -- "$@" --server-root "$SERVER_ROOT"
[ -n "$HEAP_MIN" ] && set -- "$@" --heap-min "$HEAP_MIN"
[ -n "$HEAP_MAX" ] && set -- "$@" --heap-max "$HEAP_MAX"
exec "$PYTHON" "$@"
