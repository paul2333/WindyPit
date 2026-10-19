"""Build and optionally execute the Java 25 Folia launch command."""

from __future__ import annotations

import argparse
import os
import re
import shlex
import subprocess
import sys
from pathlib import Path
from typing import Any, Sequence

from fetch_artifacts import FetchError, artifact_path, load_locks, process_artifacts
from yaml_subset import load_yaml


WINDOWS_JAVA_HOME = Path(r"C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot")
JAVA_VERSION_RE = re.compile(r"\bversion\s+[\"']?(\d+)(?:[.]|[\"']|$)", re.IGNORECASE)
JAVA_FALLBACK_VERSION_RE = re.compile(r"\b(\d+)[.]\d+(?:[.]|$)")
MEMORY_RE = re.compile(r"^[1-9][0-9]*(?:K|M|G)$", re.IGNORECASE)


class LauncherError(RuntimeError):
    """A runtime configuration or Java discovery error."""


def _config(root: Path) -> dict[str, Any]:
    try:
        value = load_yaml(root / "windypit.yml")
    except (OSError, ValueError) as error:
        raise LauncherError(f"cannot read windypit.yml: {error}") from error
    if not isinstance(value, dict):
        raise LauncherError("windypit.yml must be a YAML mapping")
    return value


def _memory(config: dict[str, Any], key: str, override: str | None = None) -> str:
    value = override if override is not None else config.get(key)
    if not isinstance(value, str) or not MEMORY_RE.fullmatch(value):
        raise LauncherError(f"{key} must be a memory value such as 2G")
    return value.upper()


def _java_executable(home: Path) -> Path:
    executable = home / "bin" / ("java.exe" if os.name == "nt" else "java")
    if not executable.is_file():
        raise LauncherError(f"Java executable not found under {home}: {executable}")
    return executable


def resolve_java_home(
    environment: dict[str, str] | None = None,
    configured_home: str | None = None,
    root: Path | None = None,
) -> Path:
    env = os.environ if environment is None else environment
    override = env.get("WINDYPIT_JAVA_HOME")
    if override:
        return Path(override).expanduser()
    if configured_home:
        configured = Path(configured_home).expanduser()
        if not configured.is_absolute() and root is not None:
            configured = root / configured
        return configured
    if os.name == "nt":
        return WINDOWS_JAVA_HOME
    java_home = env.get("JAVA_HOME")
    if java_home:
        return Path(java_home).expanduser()
    raise LauncherError("Java 25 home is not set; use WINDYPIT_JAVA_HOME or JAVA_HOME")


def java_major(java: Path) -> int:
    try:
        result = subprocess.run(
            [str(java), "-version"],
            capture_output=True,
            text=True,
            timeout=15,
            check=False,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise LauncherError(f"could not execute Java for version check: {error}") from error
    output = (result.stdout or "") + "\n" + (result.stderr or "")
    match = JAVA_VERSION_RE.search(output) or JAVA_FALLBACK_VERSION_RE.search(output)
    if not match:
        raise LauncherError(f"could not parse Java version from output: {output.strip()!r}")
    return int(match.group(1))


def validate_java_major(major: int, minimum: int = 25) -> int:
    if major < minimum:
        raise LauncherError(f"Java {minimum} or newer is required (found Java {major})")
    return major


def format_command(command: Sequence[str]) -> str:
    if os.name == "nt":
        return subprocess.list2cmdline([str(value) for value in command])
    return shlex.join(str(value) for value in command)


def _path_from_config(root: Path, value: Any, key: str) -> Path:
    if not isinstance(value, str) or not value:
        raise LauncherError(f"{key} must be a non-empty relative path")
    path = Path(value)
    if path.is_absolute() or ".." in path.parts:
        raise LauncherError(f"{key} must stay inside the runtime root")
    return root / path


def build_command(
    root: Path,
    server_root: Path | None = None,
    gc: str | None = None,
    nogui: bool = False,
    heap_min: str | None = None,
    heap_max: str | None = None,
    environment: dict[str, str] | None = None,
) -> tuple[list[str], Path, int]:
    root = root.resolve()
    config = _config(root)
    manifest, _plugins = load_locks(root)
    minimum = manifest.get("java_min", 25)
    if not isinstance(minimum, int):
        raise LauncherError("manifest java_min must be an integer")
    configured_home = config.get("java.home", "")
    if not isinstance(configured_home, str):
        raise LauncherError("java.home must be a string")
    java_home = resolve_java_home(environment, configured_home=configured_home, root=root)
    java = _java_executable(java_home)
    major = validate_java_major(java_major(java), minimum)

    configured_gc = gc or config.get("gc", "g1")
    if not isinstance(configured_gc, str):
        raise LauncherError("gc must be g1 or zgc")
    selected_gc = configured_gc.lower()
    if selected_gc not in {"g1", "zgc"}:
        raise LauncherError(f"unsupported GC {selected_gc!r}; choose g1 or zgc")
    minimum_heap = _memory(config, "heap.min", heap_min)
    maximum_heap = _memory(config, "heap.max", heap_max)
    try:
        process_artifacts(root, offline=True)
    except (FetchError, OSError, ValueError) as error:
        raise LauncherError(f"runtime artifact verification failed: {error}") from error
    server_jar = _path_from_config(root, config.get("server.jar"), "server.jar")
    locked_server_jar = artifact_path(root, "server", str(manifest["filename"]))
    if server_jar.resolve() != locked_server_jar.resolve():
        raise LauncherError(
            f"server.jar must point to the locked server artifact: {locked_server_jar}"
        )
    if not server_jar.is_file():
        raise LauncherError(f"server jar does not exist: {server_jar}")
    working_root = (server_root or root).resolve()
    working_root.mkdir(parents=True, exist_ok=True)
    args = config.get("server.args", [])
    if not isinstance(args, list) or not all(isinstance(value, str) for value in args):
        raise LauncherError("server.args must be a list of strings")

    command = [
        str(java),
        f"-Xms{minimum_heap}",
        f"-Xmx{maximum_heap}",
        "-XX:+UseG1GC" if selected_gc == "g1" else "-XX:+UseZGC",
        "-jar",
        str(server_jar),
        *args,
    ]
    if nogui:
        command.append("--nogui")
    return command, working_root, major


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--server-root", type=Path, help="isolated working directory for generated server data")
    parser.add_argument("--gc", choices=("g1", "zgc"), help="garbage collector")
    parser.add_argument("--nogui", action="store_true", help="pass --nogui to Folia")
    parser.add_argument("--dry-run", action="store_true", help="print the command without starting Folia")
    parser.add_argument("--heap-min", help="override configured minimum heap")
    parser.add_argument("--heap-max", help="override configured maximum heap")
    args = parser.parse_args(argv)
    try:
        command, working_root, major = build_command(
            args.root,
            server_root=args.server_root,
            gc=args.gc,
            nogui=args.nogui,
            heap_min=args.heap_min,
            heap_max=args.heap_max,
        )
    except (LauncherError, OSError, ValueError) as error:
        print(f"launcher error: {error}", file=sys.stderr)
        return 2
    print(f"Java major: {major}")
    print(f"Working directory: {working_root}")
    print(format_command(command))
    if args.dry_run:
        return 0
    try:
        return subprocess.call(command, cwd=working_root)
    except OSError as error:
        print(f"launcher error: could not start Folia: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

