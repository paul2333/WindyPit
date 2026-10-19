"""Run an isolated Folia startup/shutdown smoke test and save evidence."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import shutil
import socket
import subprocess
import sys
import time
import zipfile
from pathlib import Path

from fetch_artifacts import artifact_path, load_locks, verify_file
from launcher import LauncherError, build_command, format_command
from protocol_probe import ProtocolProbeError, run_probe


STARTUP_TIMEOUT = 180
SHUTDOWN_TIMEOUT = 30
PROTOCOL_TIMEOUT = 20
STARTUP_DONE = re.compile(r"(?m)\bDone\s*\(")
ERROR_PATTERNS = (
    re.compile(r"(?i)\[?error\]?.*(?:could not|failed to|unable to).*plugin"),
    re.compile(r"(?i)\b(?:plugin load error|failed to load plugin)\b"),
)


class SmokeError(RuntimeError):
    """A smoke setup, startup, plugin, or shutdown failure."""


def _local_plugin_identity(plugin_path: Path) -> tuple[str, str]:
    """Read the minimal identity needed to validate an optional local plugin."""
    with zipfile.ZipFile(plugin_path) as archive:
        descriptor = archive.read("plugin.yml").decode("utf-8", errors="strict")
    values: dict[str, str] = {}
    for line in descriptor.splitlines():
        key, separator, value = line.partition(":")
        if separator and key.strip() in {"name", "version"}:
            values[key.strip()] = value.strip().strip("'\"")
    try:
        return values["name"], values["version"]
    except KeyError as error:
        raise SmokeError(f"local plugin descriptor is missing {error.args[0]}") from error


def _copy_artifacts(
    runtime: Path, server_root: Path, local_plugin: Path | None = None
) -> tuple[Path, list[tuple[str, str]]]:
    manifest, plugins = load_locks(runtime)
    server_source = artifact_path(runtime, "server", manifest["filename"])
    verify_file(server_source, manifest["sha256"], "Folia server")
    server_destination = server_root / manifest["filename"]
    shutil.copy2(server_source, server_destination)
    plugin_directory = server_root / "plugins"
    plugin_directory.mkdir(parents=True, exist_ok=True)
    expected: list[tuple[str, str]] = []
    for plugin in plugins:
        source = artifact_path(runtime, "plugins", plugin["filename"])
        verify_file(source, plugin["sha256"], str(plugin["name"]))
        shutil.copy2(source, plugin_directory / plugin["filename"])
        expected.append((str(plugin["name"]), str(plugin["version"])))
    if local_plugin is not None:
        if not local_plugin.is_file() or local_plugin.suffix.lower() != ".jar":
            raise SmokeError(f"local plugin is not a jar file: {local_plugin}")
        name, version = _local_plugin_identity(local_plugin)
        shutil.copy2(local_plugin, plugin_directory / local_plugin.name)
        expected.append((name, version))
    (server_root / "eula.txt").write_text(
        "# Accepted only for this disposable smoke server.\neula=true\n", encoding="utf-8"
    )
    return server_destination, expected


def _wait_for_done(process: subprocess.Popen[str], log_path: Path, deadline: float) -> str:
    while time.monotonic() < deadline:
        log = log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else ""
        if STARTUP_DONE.search(log):
            return log
        if process.poll() is not None:
            return log
        time.sleep(0.5)
    return log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else ""


def _choose_local_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def _write_smoke_properties(server_root: Path, port: int) -> Path:
    properties = {
        "server-ip": "127.0.0.1",
        "server-port": str(port),
        "online-mode": "false",
        "enforce-secure-profile": "false",
        "network-compression-threshold": "-1",
        "level-name": "world",
        "level-seed": "WindyPit-runtime-smoke",
        "level-type": r"minecraft\:flat",
        "generator-settings": json.dumps(
            {
                "layers": [
                    {"height": 1, "block": "minecraft:bedrock"},
                    {"height": 2, "block": "minecraft:dirt"},
                    {"height": 1, "block": "minecraft:grass_block"},
                ],
                "biome": "minecraft:plains",
            },
            separators=(",", ":"),
        ),
        "difficulty": "peaceful",
        "gamemode": "survival",
        "generate-structures": "false",
        "max-players": "4",
        "view-distance": "4",
        "simulation-distance": "4",
        "spawn-protection": "0",
    }
    target = server_root / "server.properties"
    content = "# Disposable localhost protocol-smoke settings.\n"
    content += "".join(f"{key}={value}\n" for key, value in properties.items())
    target.write_text(content, encoding="ascii")
    return target


def _observed_warnings(log: str) -> list[str]:
    return [
        line.strip()
        for line in log.splitlines()
        if re.search(r"(?i)\bwarn(?:ing)?\b|sun\.misc\.Unsafe", line)
    ]


def _stop(process: subprocess.Popen[str]) -> tuple[bool, str]:
    if process.poll() is not None:
        return process.returncode == 0, "process exited before stop command"
    try:
        if process.stdin is not None:
            process.stdin.write("stop\n")
            process.stdin.flush()
    except (BrokenPipeError, OSError):
        pass
    try:
        process.wait(timeout=SHUTDOWN_TIMEOUT)
        return process.returncode == 0, "stop command completed"
    except subprocess.TimeoutExpired:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
        return False, "shutdown exceeded 30 seconds; process was terminated"


def _validate_log(log: str, expected: list[tuple[str, str]]) -> list[str]:
    failures: list[str] = []
    if not re.search(r"(?i)Folia|26\.1\.2", log):
        failures.append("Folia 26.1.2 server banner was not found")
    shutdown_match = re.search(r"(?im)^.*\bStopping (?:the )?server\b.*$", log)
    validation_log = log[: shutdown_match.start()] if shutdown_match else log
    for name, version in expected:
        if not re.search(rf"(?i)\b{re.escape(name)}\b", log):
            failures.append(f"{name} was not mentioned in the server log")
        if not re.search(rf"(?i)\b{re.escape(name)}\b[^\n]{{0,80}}\bv?{re.escape(version)}\b", log):
            failures.append(f"{name} {version} was not confirmed in the server log")
        if re.search(
            rf"(?i)(?:\bdisabling\b|\bfailed\b|\berror\b)[^\n]*\b{re.escape(name)}\b"
            rf"|\b{re.escape(name)}\b[^\n]*(?:\bfailed\b|\berror\b)",
            validation_log,
        ):
            failures.append(f"{name} appears disabled or failed in the server log")
    for pattern in ERROR_PATTERNS:
        if pattern.search(log):
            failures.append("plugin load error was found in the server log")
            break
    if re.search(r"(?im)\b(?:ERROR|SEVERE)\b", log):
        failures.append("server log error was found")
    return failures


def run_smoke(
    runtime: Path,
    gc: str | None = None,
    startup_timeout: int = STARTUP_TIMEOUT,
    local_plugin: Path | None = None,
) -> int:
    runtime = runtime.resolve()
    evidence_root = runtime / "test-data"
    evidence_root.mkdir(parents=True, exist_ok=True)
    run_name = dt.datetime.now(dt.timezone.utc).strftime("smoke-%Y%m%dT%H%M%S%fZ")
    server_root = evidence_root / run_name
    server_root.mkdir(parents=True)
    log_path = server_root / "server.log"
    summary_path = runtime / "SMOKE-RESULT.md"
    process: subprocess.Popen[str] | None = None
    started = False
    stop_note = "not started"
    failures: list[str] = []
    command_text = ""
    expected: list[tuple[str, str]] = []
    manifest: dict[str, object] = {}
    plugins: list[dict[str, object]] = []
    protocol_result: dict[str, object] | None = None
    protocol_error: str | None = None
    port: int | None = None
    try:
        manifest, plugins = load_locks(runtime)
        expected = [(str(plugin["name"]), str(plugin["version"])) for plugin in plugins]
        _, expected = _copy_artifacts(runtime, server_root, local_plugin=local_plugin)
        port = _choose_local_port()
        _write_smoke_properties(server_root, port)
        command, _working_root, _major = build_command(
            runtime,
            server_root=server_root,
            gc=gc,
            nogui=True,
            heap_min="512M",
            heap_max="1G",
        )
        command_text = format_command(command)
        with log_path.open("w", encoding="utf-8") as log:
            process = subprocess.Popen(
                command,
                cwd=server_root,
                stdin=subprocess.PIPE,
                stdout=log,
                stderr=subprocess.STDOUT,
                text=True,
            )
        log = _wait_for_done(process, log_path, time.monotonic() + startup_timeout)
        if not STARTUP_DONE.search(log):
            failures.append(f"server did not reach Done within {startup_timeout} seconds")
        else:
            started = True
        failures.extend(_validate_log(log, expected))
        if started and port is not None:
            try:
                protocol_result = run_probe("127.0.0.1", port, timeout=PROTOCOL_TIMEOUT)
            except (OSError, ProtocolProbeError, ValueError) as error:
                protocol_error = str(error)
                failures.append(f"protocol 47 status/login/movement/inventory probe failed: {error}")
    except (OSError, LauncherError, SmokeError, ValueError, KeyError) as error:
        failures.append(str(error))
        log = log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else ""
    finally:
        if process is not None:
            clean, stop_note = _stop(process)
            if not clean:
                failures.append(stop_note)
        else:
            log = log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else ""
        if log_path.exists():
            log = log_path.read_text(encoding="utf-8", errors="replace")

    probe_path = server_root / "protocol-probe.json"
    probe_evidence: dict[str, object] = {"ok": protocol_result is not None}
    if protocol_result is not None:
        probe_evidence["result"] = protocol_result
    if protocol_error is not None:
        probe_evidence["error"] = protocol_error
    probe_path.write_text(json.dumps(probe_evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    status = "PASS" if started and protocol_result is not None and not failures else "BLOCKED/FAIL"
    plugin_tuple = ", ".join(f"{name} {version}" for name, version in expected) or "unavailable"
    probe_status = "PASS" if protocol_result is not None else "FAILED"
    warnings = _observed_warnings(log)
    summary = [
        "# Folia protocol smoke result",
        "",
        f"- Status: **{status}**",
        f"- Run root: `{server_root}`",
        f"- Startup timeout: {startup_timeout} seconds",
        f"- Shutdown result: {stop_note}",
        f"- Command: `{command_text}`",
        f"- Locked compatibility tuple: Folia {manifest.get('minecraft', 'unknown')} build {manifest.get('build', 'unknown')}; {plugin_tuple}.",
        f"- Protocol 47 probe: **{probe_status}**; status ping, offline login, Join Game, movement packet, player inventory Window Items, and Click Window result recorded.",
        "- Protocol 47 has no positive acknowledgement for an accepted no-op inventory click; the probe requires that the connection remain open and no rejected Confirm Transaction arrive during the settle window.",
        "- This packet probe exercises protocol translation without a vanilla UI; manually verify a vanilla 1.8.8 client, inventory display, and PvP attack behavior before deployment.",
        "",
        "## Observed failures or warnings",
        "",
    ]
    if failures:
        summary.extend(f"- {failure}" for failure in failures)
    else:
        summary.append("- No startup, plugin, shutdown, or protocol failures detected.")
    if warnings:
        summary.extend(f"- Warning: {warning}" for warning in warnings)
    else:
        summary.append("- No warnings detected in the server log.")
    summary.extend(["", f"Full log: `{log_path}`", f"Protocol evidence: `{probe_path}`", ""])
    summary_path.write_text("\n".join(summary), encoding="utf-8")
    print("\n".join(summary))
    return 0 if status == "PASS" else 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--gc", choices=("g1", "zgc"))
    parser.add_argument("--startup-timeout", type=int, default=STARTUP_TIMEOUT)
    parser.add_argument(
        "--local-plugin",
        type=Path,
        help="copy one locally built plugin jar into the disposable smoke server",
    )
    args = parser.parse_args(argv)
    if args.startup_timeout <= 0:
        parser.error("--startup-timeout must be positive")
    return run_smoke(
        args.root,
        gc=args.gc,
        startup_timeout=args.startup_timeout,
        local_plugin=args.local_plugin.resolve() if args.local_plugin else None,
    )


if __name__ == "__main__":
    raise SystemExit(main())

