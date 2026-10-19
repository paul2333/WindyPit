"""Capture and summarize JDK Flight Recorder profiles for WindyPit."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence


DEFAULT_THRESHOLD_MS = 50.0
DEFAULT_SAMPLE_PERIOD_MS = 10.0
PROFILE_EVENTS = (
    "jdk.ExecutionSample",
    "jdk.NativeMethodSample",
    "jdk.JavaMonitorEnter",
    "jdk.JavaMonitorWait",
    "jdk.ThreadPark",
    "jdk.SocketRead",
    "jdk.SocketWrite",
    "jdk.FileRead",
    "jdk.FileWrite",
    "jdk.GarbageCollection",
    "jdk.GCPhasePause",
    "jdk.SafepointEnd",
)
DURATION_EVENTS = {
    "jdk.JavaMonitorEnter",
    "jdk.JavaMonitorWait",
    "jdk.ThreadPark",
    "jdk.SocketRead",
    "jdk.SocketWrite",
    "jdk.FileRead",
    "jdk.FileWrite",
    "jdk.GarbageCollection",
    "jdk.GCPhasePause",
    "jdk.SafepointEnd",
}
DURATION_RE = re.compile(r"^\s*([0-9]+(?:\.[0-9]+)?)\s*(ns|us|µs|ms|s|m)?\s*$", re.IGNORECASE)
ISO_DURATION_RE = re.compile(
    r"^PT(?:(?P<hours>[0-9.]+)H)?(?:(?P<minutes>[0-9.]+)M)?(?:(?P<seconds>[0-9.]+)S)?$",
    re.IGNORECASE,
)
DURATION_ARGUMENT_RE = re.compile(r"^[1-9][0-9]*(?:s|m|h)$", re.IGNORECASE)


class ProfileError(RuntimeError):
    """An unavailable JFR tool or invalid recording."""


@dataclass(frozen=True)
class SlowEvent:
    event_type: str
    duration_ms: float
    thread: str
    stack: tuple[str, ...]


@dataclass(frozen=True)
class CpuHotspot:
    stack: tuple[str, ...]
    sample_count: int
    estimated_ms: float


@dataclass(frozen=True)
class AnalysisResult:
    slow_events: tuple[SlowEvent, ...]
    idle_wait_events: tuple[SlowEvent, ...]
    cpu_hotspots: tuple[CpuHotspot, ...]
    event_count: int
    threshold_ms: float
    sample_period_ms: float


def duration_ms(value: Any) -> float | None:
    """Convert JFR duration strings or structured values to milliseconds."""
    if isinstance(value, dict):
        number = value.get("value")
        unit = str(value.get("unit", "ms")).lower()
        try:
            amount = float(number)
        except (TypeError, ValueError):
            return None
        factors = {"ns": 1e-6, "us": 1e-3, "µs": 1e-3, "ms": 1.0, "s": 1000.0}
        return amount * factors[unit] if unit in factors else None
    if isinstance(value, (int, float)):
        return float(value)
    if not isinstance(value, str):
        return None

    iso_match = ISO_DURATION_RE.fullmatch(value.strip())
    if iso_match:
        parts = {key: float(amount or 0) for key, amount in iso_match.groupdict().items()}
        return (parts["hours"] * 3600 + parts["minutes"] * 60 + parts["seconds"]) * 1000

    match = DURATION_RE.fullmatch(value)
    if not match:
        return None
    amount = float(match.group(1))
    unit = (match.group(2) or "ms").lower()
    factors = {"ns": 1e-6, "us": 1e-3, "µs": 1e-3, "ms": 1.0, "s": 1000.0, "m": 60000.0}
    return amount * factors[unit]


def _frame_name(frame: Any) -> str | None:
    if not isinstance(frame, dict):
        return None
    method = frame.get("method", frame)
    if not isinstance(method, dict):
        return None
    declaring_type = method.get("type", {})
    if isinstance(declaring_type, dict):
        class_name = declaring_type.get("name", "")
    else:
        class_name = str(declaring_type or "")
    method_name = method.get("name", "")
    if not class_name and not method_name:
        return None
    name = f"{class_name}.{method_name}" if class_name else str(method_name)
    line = frame.get("lineNumber")
    if isinstance(line, int) and line >= 0:
        name += f":{line}"
    return name


def _stack(event_values: dict[str, Any], depth: int = 8) -> tuple[str, ...]:
    trace = event_values.get("stackTrace", {})
    frames = trace.get("frames", []) if isinstance(trace, dict) else []
    if not isinstance(frames, list):
        return ()
    return tuple(name for frame in frames[:depth] if (name := _frame_name(frame)))


def _thread_name(values: dict[str, Any]) -> str:
    for key in ("eventThread", "sampledThread", "thread"):
        thread = values.get(key)
        if isinstance(thread, dict):
            name = thread.get("javaName") or thread.get("osName") or thread.get("name")
            if name:
                return str(name)
    return "unknown"


def _is_jfr_internal(thread: str, stack: tuple[str, ...]) -> bool:
    return thread.startswith("JFR ") or bool(stack and stack[0].startswith(("jdk/jfr/", "jdk.jfr.")))


def _is_expected_idle_wait(event_type: str, stack: tuple[str, ...]) -> bool:
    if event_type != "jdk.ThreadPark":
        return False
    expected_wait_frames = (
        "LinkedBlockingQueue.take",
        "ArrayBlockingQueue.take",
        "ThreadPoolExecutor.getTask",
        "ForkJoinPool.awaitWork",
        "MinecraftServer.waitUntilNextTick",
        "MinecraftServer.waitForTasks",
    )
    return any(any(marker in frame for marker in expected_wait_frames) for frame in stack)


def analyze_recording(
    document: dict[str, Any],
    threshold_ms: float = DEFAULT_THRESHOLD_MS,
    sample_period_ms: float = DEFAULT_SAMPLE_PERIOD_MS,
) -> AnalysisResult:
    recording = document.get("recording", document)
    events = recording.get("events", []) if isinstance(recording, dict) else []
    if not isinstance(events, list):
        raise ProfileError("JFR JSON must contain a recording.events array")

    slow: list[SlowEvent] = []
    idle_waits: list[SlowEvent] = []
    samples: dict[tuple[str, ...], int] = {}
    for event in events:
        if not isinstance(event, dict):
            continue
        event_type = str(event.get("type", ""))
        values = event.get("values", {})
        if not isinstance(values, dict):
            continue
        stack = _stack(values)
        thread = _thread_name(values)
        if _is_jfr_internal(thread, stack):
            continue
        if event_type == "jdk.ExecutionSample" and stack:
            sample_stack = stack[:5]
            samples[sample_stack] = samples.get(sample_stack, 0) + 1
        elif event_type == "jdk.NativeMethodSample" and stack:
            sample_stack = stack[:5]
            samples[sample_stack] = samples.get(sample_stack, 0) + 1

        if event_type not in DURATION_EVENTS:
            continue
        elapsed = duration_ms(values.get("duration"))
        if elapsed is not None and elapsed >= threshold_ms:
            event_value = SlowEvent(event_type, elapsed, thread, stack)
            if _is_expected_idle_wait(event_type, stack):
                idle_waits.append(event_value)
            else:
                slow.append(event_value)

    slow.sort(key=lambda event: event.duration_ms, reverse=True)
    idle_waits.sort(key=lambda event: event.duration_ms, reverse=True)
    hotspots = [
        CpuHotspot(stack, count, count * sample_period_ms)
        for stack, count in samples.items()
    ]
    hotspots.sort(key=lambda hotspot: hotspot.sample_count, reverse=True)
    return AnalysisResult(tuple(slow), tuple(idle_waits), tuple(hotspots[:50]), len(events), threshold_ms,
                           sample_period_ms)


def render_report(result: AnalysisResult, source: str) -> str:
    lines = [
        "# WindyPit JFR profile",
        "",
        f"- Recording: `{source}`",
        f"- JFR events parsed: {result.event_count}",
        f"- Slow duration threshold: {result.threshold_ms:g} ms",
        "",
        "## Expected pacing and idle waits",
        "",
        "Recorded parks in an idle worker queue or the server tick pacer exceed the threshold because the thread is waiting normally. They are retained here for context and are not expensive calls.",
        "",
    ]
    if result.idle_wait_events:
        for index, event in enumerate(result.idle_wait_events, 1):
            lines.append(f"{index}. **{event.duration_ms:.2f} ms** `{event.event_type}` on `{event.thread}`")
            if event.stack:
                lines.extend(f"   - `{frame}`" for frame in event.stack)
            else:
                lines.append("   - No stack trace was recorded.")
    else:
        lines.append("No expected idle or tick pacing wait met the threshold.")

    lines.extend([
        "",
        "## Contention and long operations",
        "",
        "These are measured event durations at or above the threshold, not inferred from CPU sampling. JFR's own recorder threads and expected idle/tick waits are excluded.",
        "",
    ])
    if result.slow_events:
        for index, event in enumerate(result.slow_events, 1):
            lines.append(f"{index}. **{event.duration_ms:.2f} ms** `{event.event_type}` on `{event.thread}`")
            if event.stack:
                lines.extend(f"   - `{frame}`" for frame in event.stack)
            else:
                lines.append("   - No stack trace was recorded.")
    else:
        lines.append(f"No contention or long-operation duration event met the threshold of {result.threshold_ms:g} ms.")

    lines.extend(
        [
            "",
            "## Sampled CPU hotspots",
            "",
            f"`jdk.ExecutionSample` and `jdk.NativeMethodSample` are sampled at approximately {result.sample_period_ms:g} ms. The estimated sampled milliseconds equal sample count multiplied by this configured period; this does not measure a single invocation's duration. JFR internal stacks are excluded.",
            "",
        ]
    )
    if result.cpu_hotspots:
        for index, hotspot in enumerate(result.cpu_hotspots, 1):
            stack_text = " <- ".join(hotspot.stack)
            lines.append(
                f"{index}. {hotspot.sample_count} samples (~{hotspot.estimated_ms:.1f} estimated sampled ms): `{stack_text}`"
            )
    else:
        lines.append("No CPU samples with stack traces were recorded.")
    lines.extend(
        [
            "",
            "## Interpretation",
            "",
            "Investigate repeatable slow events and CPU stacks under the same player-count and workload scenario. A sampled stack is a hotspot candidate; it is not proof that one invocation exceeded 50 ms. Compare equivalent captures before changing server or plugin behavior.",
            "",
        ]
    )
    return "\n".join(lines)


def java_tools(java_home: str | None = None) -> tuple[Path, Path, Path]:
    home_value = java_home or os.environ.get("WINDYPIT_JAVA_HOME") or os.environ.get("JAVA_HOME")
    if home_value:
        home = Path(home_value).expanduser()
        suffix = ".exe" if os.name == "nt" else ""
        jcmd = home / "bin" / f"jcmd{suffix}"
        jfr = home / "bin" / f"jfr{suffix}"
        profile_settings = home / "lib" / "jfr" / "profile.jfc"
        missing = [path for path in (jcmd, jfr, profile_settings) if not path.is_file()]
        if missing:
            raise ProfileError("Java Flight Recorder tools not found: " + ", ".join(map(str, missing)))
        return jcmd, jfr, profile_settings

    jcmd_value = shutil.which("jcmd")
    jfr_value = shutil.which("jfr")
    if not jcmd_value or not jfr_value:
        raise ProfileError("set JAVA_HOME or WINDYPIT_JAVA_HOME to a JDK that includes jcmd and jfr")
    jfr_path = Path(jfr_value).resolve()
    home = jfr_path.parent.parent
    profile_settings = home / "lib" / "jfr" / "profile.jfc"
    if not profile_settings.is_file():
        raise ProfileError(f"JFR profile settings not found: {profile_settings}")
    return Path(jcmd_value), Path(jfr_value), profile_settings


def duration_argument(value: str) -> str:
    if not DURATION_ARGUMENT_RE.fullmatch(value):
        raise argparse.ArgumentTypeError("duration must be a positive integer with s, m, or h (for example 3m)")
    return value.lower()


def build_jfr_start_command(
    jcmd: Path,
    pid: int,
    settings: Path,
    output: Path,
    duration: str,
    recording_name: str,
) -> list[str]:
    quoted_output = f'filename="{output.resolve()}"'
    return [
        str(jcmd),
        str(pid),
        "JFR.start",
        f"name={recording_name}",
        f"settings={settings.resolve()}",
        f"duration={duration}",
        quoted_output,
        "dumponexit=true",
    ]


def _run(command: Sequence[str]) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(command, capture_output=True, text=True, timeout=120, check=False)
    except (OSError, subprocess.SubprocessError) as error:
        raise ProfileError(f"could not run {command[0]}: {error}") from error


def capture(pid: int, output: Path, duration: str, threshold_ms: int = 50) -> str:
    jcmd, jfr, base_settings = java_tools()
    if pid <= 0:
        raise ProfileError("pid must be a positive Java process id")
    output = output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    recording_name = f"WindyPitProfile-{pid}"

    with tempfile.TemporaryDirectory(prefix="windypit-jfr-") as temporary:
        settings = Path(temporary) / "windypit-profile.jfc"
        configure = [
            str(jfr),
            "configure",
            "--input",
            str(base_settings),
            "--output",
            str(settings),
            f"jdk.ExecutionSample#period={DEFAULT_SAMPLE_PERIOD_MS:g}ms",
            f"jdk.JavaMonitorEnter#threshold={threshold_ms}ms",
            f"jdk.JavaMonitorWait#threshold={threshold_ms}ms",
            f"jdk.ThreadPark#threshold={threshold_ms}ms",
            f"jdk.SocketRead#threshold={threshold_ms}ms",
            f"jdk.SocketWrite#threshold={threshold_ms}ms",
            f"jdk.FileRead#threshold={threshold_ms}ms",
            f"jdk.FileWrite#threshold={threshold_ms}ms",
            f"jdk.GarbageCollection#threshold={threshold_ms}ms",
            f"jdk.GCPhasePause#threshold={threshold_ms}ms",
            f"jdk.SafepointEnd#threshold={threshold_ms}ms",
        ]
        configured = _run(configure)
        if configured.returncode != 0:
            raise ProfileError(configured.stderr.strip() or configured.stdout.strip() or "jfr configure failed")
        command = build_jfr_start_command(jcmd, pid, settings, output, duration, recording_name)
        started = _run(command)
        if started.returncode != 0:
            raise ProfileError(started.stderr.strip() or started.stdout.strip() or "JFR.start failed")
        return started.stdout.strip()


def report(recording: Path, threshold_ms: float = DEFAULT_THRESHOLD_MS, sample_period_ms: float = DEFAULT_SAMPLE_PERIOD_MS) -> Path:
    _jcmd, jfr, _settings = java_tools()
    recording = recording.expanduser().resolve()
    if not recording.is_file():
        raise ProfileError(f"recording does not exist: {recording}")
    command = [str(jfr), "print", "--json", "--stack-depth", "64", "--events", ",".join(PROFILE_EVENTS), str(recording)]
    printed = _run(command)
    if printed.returncode != 0:
        raise ProfileError(printed.stderr.strip() or printed.stdout.strip() or "jfr print failed")
    try:
        document = json.loads(printed.stdout)
    except json.JSONDecodeError as error:
        raise ProfileError(f"jfr returned invalid JSON: {error}") from error
    result = analyze_recording(document, threshold_ms=threshold_ms, sample_period_ms=sample_period_ms)
    destination = recording.with_suffix(".report.md")
    destination.write_text(render_report(result, source=recording.name), encoding="utf-8")
    return destination


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="action", required=True)
    subcommands.add_parser("list", help="list local Java processes")

    start_parser = subcommands.add_parser("start", help="start a JFR recording on a Java process")
    start_parser.add_argument("pid", type=int)
    start_parser.add_argument("--duration", type=duration_argument, default="3m")
    start_parser.add_argument("--threshold-ms", type=int, default=int(DEFAULT_THRESHOLD_MS))
    start_parser.add_argument("--output", type=Path)

    report_parser = subcommands.add_parser("report", help="analyze a .jfr recording")
    report_parser.add_argument("recording", type=Path)
    report_parser.add_argument("--threshold-ms", type=float, default=DEFAULT_THRESHOLD_MS)
    report_parser.add_argument("--sample-period-ms", type=float, default=DEFAULT_SAMPLE_PERIOD_MS)

    args = parser.parse_args(argv)
    try:
        jcmd, _jfr, _settings = java_tools()
        if args.action == "list":
            listed = _run([str(jcmd), "-l"])
            if listed.returncode != 0:
                raise ProfileError(listed.stderr.strip() or listed.stdout.strip() or "jcmd -l failed")
            print(listed.stdout, end="")
            return 0
        if args.action == "start":
            if args.threshold_ms <= 0:
                raise ProfileError("threshold-ms must be positive")
            stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
            output = args.output or Path(__file__).resolve().parents[1] / "profiles" / f"windypit-{args.pid}-{stamp}.jfr"
            print(capture(args.pid, output, args.duration, args.threshold_ms))
            print(f"Recording output: {output.expanduser().resolve()}")
            print("Report after the recording completes with: python runtime/tools/jfr_profile.py report <recording.jfr>")
            return 0
        if args.threshold_ms <= 0 or args.sample_period_ms <= 0:
            raise ProfileError("threshold-ms and sample-period-ms must be positive")
        output = report(args.recording, args.threshold_ms, args.sample_period_ms)
        print(f"JFR report written to: {output}")
        return 0
    except (ProfileError, OSError, ValueError) as error:
        print(f"JFR profile error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

