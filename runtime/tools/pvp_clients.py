"""Minimal protocol-47 PvP clients for WindyPit combat scenarios.

Each client logs in offline-mode, tracks spawned players, answers KeepAlive and
respawn prompts, and sends ``Use Entity`` (ATTACK) plus ``Animation`` packets at
a fixed cadence.  It is deliberately small: it exercises the real combat packet
path (damage, death, respawn, kill messages) without pretending to be a full
game client.

The codec and compression primitives are reused from ``protocol_probe.py``; the
threading shape follows ``load_clients.py`` without modifying either module.

Usage::

    python runtime/tools/pvp_clients.py --clients 4 --seconds 60 \
        --target-policy ring --host 127.0.0.1 --port 8823 --output pvp.json

The tool writes per-client counters and a total JSON summary.  A client that
fails to join is reported in ``errors`` and the process exits non-zero.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import socket
import struct
import sys
import threading
import time
import uuid
from pathlib import Path
from typing import Any

from protocol_probe import (
    ProtocolProbeError,
    _read_packet,
    _read_string,
    _string,
    decode_varint,
    encode_packet,
    encode_varint,
    make_handshake,
)

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8823
DEFAULT_ATTACK_INTERVAL_MS = 1000
DEFAULT_SECONDS = 60.0
KILL_VERBS = ("killed", "slain", "defeated", "murdered")


class PvpClientError(RuntimeError):
    """A malformed server packet or an unusable client configuration."""


def _uuid_text(raw: bytes) -> str:
    if len(raw) != 16:
        raise PvpClientError("UUID field must be 16 bytes")
    return str(uuid.UUID(bytes=raw))


def parse_player_list(payload: bytes, uuid_names: dict[bytes, str]) -> None:
    """Record UUID -> name mappings from an ADD_PLAYER (action 0) packet."""
    action, offset = decode_varint(payload, 0)
    if action != 0:
        return
    count, offset = decode_varint(payload, offset)
    for _ in range(count):
        if offset + 16 > len(payload):
            raise PvpClientError("player list entry is truncated")
        raw_uuid = payload[offset:offset + 16]
        offset += 16
        name, offset = _read_string(payload, offset)
        uuid_names[raw_uuid] = name
        properties, offset = decode_varint(payload, offset)
        for _ in range(properties):
            _name, offset = _read_string(payload, offset)
            _value, offset = _read_string(payload, offset)
            if offset >= len(payload):
                raise PvpClientError("player list property is truncated")
            signed = payload[offset]
            offset += 1
            if signed:
                _signature, offset = _read_string(payload, offset)
        _gamemode, offset = decode_varint(payload, offset)
        _ping, offset = decode_varint(payload, offset)
        if offset >= len(payload):
            raise PvpClientError("player list entry has no display-name flag")
        has_display_name = payload[offset]
        offset += 1
        if has_display_name:
            _display_name, offset = _read_string(payload, offset)


def parse_spawn_player(payload: bytes) -> tuple[int, bytes, tuple[float, float, float]]:
    """Return (entity_id, raw_uuid, position) from Spawn Player (0x0C)."""
    entity_id, offset = decode_varint(payload, 0)
    if offset + 16 > len(payload):
        raise PvpClientError("spawn player packet has no UUID")
    raw_uuid = payload[offset:offset + 16]
    offset += 16
    if offset + 12 > len(payload):
        raise PvpClientError("spawn player packet has no coordinates")
    x, y, z = struct.unpack_from(">iii", payload, offset)
    return entity_id, raw_uuid, (x / 32.0, y / 32.0, z / 32.0)


def parse_update_health(payload: bytes) -> tuple[float, int, float]:
    if len(payload) < 5:
        raise PvpClientError("update health packet is too short")
    health = struct.unpack_from(">f", payload, 0)[0]
    food, offset = decode_varint(payload, 4)
    if offset + 4 > len(payload):
        raise PvpClientError("update health packet has no saturation")
    saturation = struct.unpack_from(">f", payload, offset)[0]
    return health, food, saturation


def parse_destroy_entities(payload: bytes) -> list[int]:
    count, offset = decode_varint(payload, 0)
    ids: list[int] = []
    for _ in range(count):
        entity_id, offset = decode_varint(payload, offset)
        ids.append(entity_id)
    return ids


def parse_chat_text(payload: bytes) -> str:
    message, _offset = _read_string(payload, 0)
    try:
        document = json.loads(message)
    except json.JSONDecodeError:
        return message

    def flatten(node: Any) -> str:
        if isinstance(node, str):
            return node
        if isinstance(node, dict):
            parts = [flatten(node.get("text", ""))]
            if "extra" in node:
                parts.append(flatten(node["extra"]))
            return "".join(parts)
        if isinstance(node, list):
            return "".join(flatten(item) for item in node)
        return ""

    return flatten(document)


def parse_relative_move(payload: bytes) -> tuple[int, tuple[float, float, float]]:
    entity_id, offset = decode_varint(payload, 0)
    if offset + 3 > len(payload):
        raise PvpClientError("relative move packet is truncated")
    dx, dy, dz = struct.unpack_from(">bbb", payload, offset)
    return entity_id, (dx / 32.0, dy / 32.0, dz / 32.0)


def parse_teleport(payload: bytes) -> tuple[int, tuple[float, float, float]]:
    entity_id, offset = decode_varint(payload, 0)
    if offset + 12 > len(payload):
        raise PvpClientError("teleport packet is truncated")
    x, y, z = struct.unpack_from(">iii", payload, offset)
    return entity_id, (x / 32.0, y / 32.0, z / 32.0)


def select_target(
    entities: dict[int, dict[str, Any]],
    self_entity_id: int | None,
    position: tuple[float, float, float] | None,
    policy: str,
    cursor: int,
) -> int | None:
    candidates = sorted(
        (entity_id, entity) for entity_id, entity in entities.items() if entity_id != self_entity_id
    )
    if not candidates:
        return None
    if policy == "nearest" and position is not None:
        with_position = [
            (entity_id, entity)
            for entity_id, entity in candidates
            if isinstance(entity.get("pos"), (list, tuple)) and len(entity["pos"]) == 3
        ]
        if with_position:
            def distance(item: tuple[int, dict[str, Any]]) -> float:
                target = item[1]["pos"]
                return math.dist(position, (float(target[0]), float(target[1]), float(target[2])))

            return min(with_position, key=distance)[0]
    return candidates[cursor % len(candidates)][0]


def _detect_kill(message: str, name: str, known_names: set[str]) -> bool:
    lowered = message.lower()
    verbs = "|".join(KILL_VERBS)
    if re.search(rf"\byou (?:{verbs})\b", lowered):
        return True
    for other in known_names:
        if other.lower() == name.lower():
            continue
        pattern = rf"\b{re.escape(name.lower())}\b.*\b(?:{verbs})\b.*\b{re.escape(other.lower())}\b"
        if re.search(pattern, lowered):
            return True
    return False


def _client(
    index: int,
    host: str,
    port: int,
    seconds: float,
    attack_interval_ms: int,
    target_policy: str,
    results: list[dict[str, Any]],
    chat_line: str | None = None,
) -> None:
    name = f"PvP{index:03d}"
    started = time.monotonic()
    result: dict[str, Any] = {
        "client": name,
        "joined": False,
        "attacks_sent": 0,
        "health_drop_events": 0,
        "deaths": 0,
        "respawns": 0,
        "kills": 0,
        "chat_messages": 0,
        "targets_known": 0,
        "health": None,
        "errors": [],
    }
    sock: socket.socket | None = None
    try:
        sock = socket.create_connection((host, port), timeout=10.0)
        sock.sendall(make_handshake(host, port, 2))
        sock.sendall(encode_packet(0, _string(name)))
        compression_threshold: int | None = None
        joined = False
        self_entity_id: int | None = None
        position: tuple[float, float, float] | None = None
        entities: dict[int, dict[str, Any]] = {}
        uuid_names: dict[bytes, str] = {}
        known_names: set[str] = set()
        health = 20.0
        previous_health: float | None = None
        last_move = 0.0
        next_attack = 0.0
        target_cursor = 0
        settings_sent = False
        chat_sent = False
        deadline = started + seconds
        attack_interval = attack_interval_ms / 1000.0

        while time.monotonic() < deadline:
            remaining = deadline - time.monotonic()
            sock.settimeout(max(0.001, min(0.05, remaining)))
            try:
                packet_id, payload = _read_packet(sock, compression_threshold)
            except socket.timeout:
                packet_id, payload = None, b""

            if packet_id is not None:
                if packet_id == 0x03 and compression_threshold is None:
                    threshold, offset = decode_varint(payload, 0)
                    if offset != len(payload):
                        raise PvpClientError("Set Compression packet contains trailing data")
                    compression_threshold = threshold if threshold >= 0 else None
                elif packet_id == 0x00 and not joined:
                    reason, _offset = _read_string(payload, 0)
                    raise PvpClientError(f"server rejected login: {reason}")
                elif packet_id == 0x01 and not joined:
                    if len(payload) < 4:
                        raise PvpClientError("Join Game packet is too short")
                    self_entity_id = struct.unpack_from(">i", payload, 0)[0]
                    joined = True
                    result["joined"] = True
                    next_attack = time.monotonic()
                    if chat_line and not chat_sent:
                        sock.sendall(
                            encode_packet(
                                0x02,
                                _string(json.dumps({"text": chat_line})) + struct.pack(">b", 0),
                                compression_threshold,
                            )
                        )
                        chat_sent = True
                elif packet_id == 0x00 and joined:
                    keep_alive, _offset = decode_varint(payload, 0)
                    sock.sendall(encode_packet(0x00, encode_varint(keep_alive), compression_threshold))
                elif packet_id == 0x08:
                    if len(payload) < 33:
                        raise PvpClientError("Player Position And Look packet is too short")
                    position = struct.unpack_from(">ddd", payload, 0)
                    sock.sendall(
                        encode_packet(
                            0x04,
                            struct.pack(">ddd?", position[0], position[1], position[2], True),
                            compression_threshold,
                        )
                    )
                    if not settings_sent:
                        sock.sendall(
                            encode_packet(
                                0x15,
                                _string("en_US") + struct.pack(">bBBB", 8, 0, 1, 0),
                                compression_threshold,
                            )
                        )
                        settings_sent = True
                elif packet_id == 0x38:
                    parse_player_list(payload, uuid_names)
                    known_names.update(uuid_names.values())
                    for entity in entities.values():
                        resolved = uuid_names.get(entity["uuid"])
                        if resolved:
                            entity["name"] = resolved
                elif packet_id == 0x0C:
                    entity_id, raw_uuid, entity_position = parse_spawn_player(payload)
                    entity_name = uuid_names.get(raw_uuid) or f"uuid:{_uuid_text(raw_uuid)}"
                    entities[entity_id] = {"uuid": raw_uuid, "name": entity_name, "pos": list(entity_position)}
                    known_names.add(entity_name)
                elif packet_id == 0x0D:
                    for entity_id in parse_destroy_entities(payload):
                        entities.pop(entity_id, None)
                elif packet_id == 0x15:
                    entity_id, delta = parse_relative_move(payload)
                    entity = entities.get(entity_id)
                    if entity and entity.get("pos"):
                        entity["pos"] = [entity["pos"][i] + delta[i] for i in range(3)]
                elif packet_id == 0x18:
                    entity_id, entity_position = parse_teleport(payload)
                    entity = entities.get(entity_id)
                    if entity:
                        entity["pos"] = list(entity_position)
                elif packet_id == 0x06:
                    health, _food, _saturation = parse_update_health(payload)
                    if previous_health is not None and health < previous_health - 0.01:
                        result["health_drop_events"] += 1
                    if previous_health is not None and previous_health > 0 and health <= 0:
                        result["deaths"] += 1
                    previous_health = health
                    result["health"] = health
                elif packet_id == 0x07:
                    result["respawns"] += 1
                    previous_health = None
                    sock.sendall(encode_packet(0x16, encode_varint(0), compression_threshold))
                elif packet_id == 0x02 and joined:
                    message = parse_chat_text(payload)
                    result["chat_messages"] += 1
                    if _detect_kill(message, name, known_names):
                        result["kills"] += 1

            now = time.monotonic()
            if joined and now - last_move >= 0.25:
                sock.sendall(encode_packet(0x03, struct.pack(">?", True), compression_threshold))
                last_move = now
            if joined and entities and now >= next_attack:
                target = select_target(entities, self_entity_id, position, target_policy, target_cursor)
                target_cursor += 1
                if target is not None:
                    sock.sendall(
                        encode_packet(
                            0x02,
                            encode_varint(target) + encode_varint(1),
                            compression_threshold,
                        )
                    )
                    sock.sendall(encode_packet(0x0A, b"", compression_threshold))
                    result["attacks_sent"] += 1
                next_attack = now + attack_interval
            result["targets_known"] = len(entities)
    except Exception as error:  # noqa: BLE001 - every client reports instead of crashing the run
        result["errors"].append(f"{type(error).__name__}: {error}")
    finally:
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass
        result["seconds"] = round(time.monotonic() - started, 2)
        results.append(result)


def run(
    clients: int,
    seconds: float,
    host: str,
    port: int,
    attack_interval_ms: int,
    target_policy: str,
    output: Path,
    chat_line: str | None = None,
) -> int:
    if clients <= 0:
        raise ValueError("clients must be positive")
    if seconds <= 0:
        raise ValueError("seconds must be positive")
    if attack_interval_ms <= 0:
        raise ValueError("attack interval must be positive")
    if target_policy not in ("ring", "nearest"):
        raise ValueError("target policy must be ring or nearest")

    results: list[dict[str, Any]] = []
    threads = [
        threading.Thread(
            target=_client,
            args=(index, host, port, seconds, attack_interval_ms, target_policy, results, chat_line),
            daemon=True,
        )
        for index in range(clients)
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=seconds + 30)

    results.sort(key=lambda item: item["client"])
    joined = sum(1 for item in results if item["joined"])
    errors = [item for item in results if item["errors"]]
    summary = {
        "clients": clients,
        "seconds": seconds,
        "host": host,
        "port": port,
        "attack_interval_ms": attack_interval_ms,
        "target_policy": target_policy,
        "joined": joined,
        "attacks_sent": sum(item["attacks_sent"] for item in results),
        "health_drop_events": sum(item["health_drop_events"] for item in results),
        "deaths": sum(item["deaths"] for item in results),
        "respawns": sum(item["respawns"] for item in results),
        "kills": sum(item["kills"] for item in results),
        "chat_messages": sum(item["chat_messages"] for item in results),
        "errors": len(errors),
        "error_samples": errors[:5],
        "results": results,
    }
    output = output.expanduser()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "clients": clients,
                "joined": joined,
                "attacks_sent": summary["attacks_sent"],
                "health_drop_events": summary["health_drop_events"],
                "deaths": summary["deaths"],
                "respawns": summary["respawns"],
                "kills": summary["kills"],
                "errors": summary["errors"],
            }
        )
    )
    if joined < clients or errors:
        print(
            f"pvp_clients: {clients - joined} client(s) did not join, {len(errors)} client(s) reported errors",
            file=sys.stderr,
        )
        return 1
    return 0


def _self_test() -> int:
    # Pure parsers.
    raw_uuid = bytes(range(16))
    spawn = encode_varint(200) + raw_uuid + struct.pack(">iii", 32, 2048, -64) + b"\x00\x00" + b"\x00\x00" + b"\x7f"
    entity_id, parsed_uuid, parsed_position = parse_spawn_player(spawn)
    if entity_id != 200 or parsed_uuid != raw_uuid or parsed_position != (1.0, 64.0, -2.0):
        print("self-test failed: spawn player parsing", file=sys.stderr)
        return 1
    health, food, _saturation = parse_update_health(struct.pack(">f", 12.5) + encode_varint(18) + struct.pack(">f", 3.0))
    if health != 12.5 or food != 18:
        print("self-test failed: update health parsing", file=sys.stderr)
        return 1
    chat = _string(json.dumps({"text": "PvP000 killed Target001"}))
    if "killed" not in parse_chat_text(chat):
        print("self-test failed: chat parsing", file=sys.stderr)
        return 1
    if not _detect_kill("PvP000 killed Target001", "PvP000", {"Target001"}):
        print("self-test failed: kill detection", file=sys.stderr)
        return 1
    if _detect_kill("Target001 killed PvP000", "PvP000", {"Target001"}):
        print("self-test failed: death message treated as kill", file=sys.stderr)
        return 1

    # Fake protocol-47 server that exercises login, compression, spawn, health,
    # chat, respawn and attack packets end to end.
    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", 0))
    listener.listen(1)
    port = listener.getsockname()[1]
    server_errors: list[str] = []
    observed: dict[str, Any] = {"packets": [], "use_entity": None}

    def serve() -> None:
        try:
            with listener:
                connection, _address = listener.accept()
                with connection:
                    connection.settimeout(5.0)
                    _read_packet(connection)
                    _read_packet(connection)  # Login Start
                    threshold = 64
                    connection.sendall(encode_packet(0x03, encode_varint(threshold)))
                    connection.sendall(
                        encode_packet(
                            0x02,
                            _string("00000000-0000-0000-0000-000000000001") + _string("PvP000"),
                            threshold,
                        )
                    )
                    join_game = struct.pack(">iBBBB", 100, 0, 0, 0, 20) + _string("default") + b"\x00"
                    connection.sendall(encode_packet(0x01, join_game, threshold))
                    connection.sendall(
                        encode_packet(0x08, struct.pack(">dddffB", 0.0, 64.0, 0.0, 0.0, 0.0, 0), threshold)
                    )
                    player_list = (
                        encode_varint(0)
                        + encode_varint(1)
                        + raw_uuid
                        + _string("Target001")
                        + encode_varint(0)
                        + encode_varint(0)
                        + encode_varint(0)
                        + b"\x00"
                    )
                    connection.sendall(encode_packet(0x38, player_list, threshold))
                    spawn = (
                        encode_varint(200)
                        + raw_uuid
                        + struct.pack(">iii", 32, 2048, 0)
                        + struct.pack(">bb", 0, 0)
                        + struct.pack(">h", 0)
                        + b"\x7f"
                    )
                    connection.sendall(encode_packet(0x0C, spawn, threshold))
                    connection.sendall(
                        encode_packet(0x06, struct.pack(">f", 20.0) + encode_varint(20) + struct.pack(">f", 5.0), threshold)
                    )
                    connection.sendall(encode_packet(0x00, encode_varint(7), threshold))
                    connection.sendall(
                        encode_packet(
                            0x02,
                            _string(json.dumps({"text": "PvP000 killed Target001"})) + b"\x00",
                            threshold,
                        )
                    )
                    connection.sendall(
                        encode_packet(0x06, struct.pack(">f", 8.0) + encode_varint(20) + struct.pack(">f", 5.0), threshold)
                    )
                    connection.sendall(
                        encode_packet(0x06, struct.pack(">f", 0.0) + encode_varint(20) + struct.pack(">f", 5.0), threshold)
                    )
                    connection.sendall(
                        encode_packet(0x07, struct.pack(">iBB", 0, 0, 0) + _string("default"), threshold)
                    )
                    deadline = time.monotonic() + 4.0
                    while time.monotonic() < deadline:
                        connection.settimeout(max(0.001, deadline - time.monotonic()))
                        try:
                            packet_id, payload = _read_packet(connection, threshold)
                        except socket.timeout:
                            break
                        except Exception:  # noqa: BLE001 - client closed the socket at the end of its run
                            break
                        observed["packets"].append(packet_id)
                        if packet_id == 0x00:
                            keep_alive, _ = decode_varint(payload, 0)
                            connection.sendall(encode_packet(0x00, encode_varint(keep_alive), threshold))
                        if packet_id == 0x02:
                            target, offset = decode_varint(payload, 0)
                            button, _offset = decode_varint(payload, offset)
                            observed["use_entity"] = (target, button)
        except Exception as error:  # noqa: BLE001 - surfaced in self-test output
            server_errors.append(f"{type(error).__name__}: {error}")

    server_thread = threading.Thread(target=serve, daemon=True)
    server_thread.start()
    results: list[dict[str, Any]] = []
    client_thread = threading.Thread(
        target=_client,
        args=(0, "127.0.0.1", port, 2.0, 200, "ring", results),
        daemon=True,
    )
    client_thread.start()
    client_thread.join(timeout=15)
    server_thread.join(timeout=8)

    if server_errors:
        print(f"self-test failed: fake server errors {server_errors}", file=sys.stderr)
        return 1
    if not results:
        print("self-test failed: client produced no result", file=sys.stderr)
        return 1
    result = results[0]
    if not result["joined"] or result["attacks_sent"] < 1:
        print(f"self-test failed: client did not attack: {result}", file=sys.stderr)
        return 1
    if observed["use_entity"] is None or observed["use_entity"][1] != 1:
        print(f"self-test failed: Use Entity ATTACK not observed: {observed}", file=sys.stderr)
        return 1
    if result["respawns"] < 1 or result["kills"] < 1 or result["deaths"] < 1:
        print(f"self-test failed: combat counters {result}", file=sys.stderr)
        return 1
    print("pvp_clients self-test OK")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Protocol-47 PvP clients: login, track spawns, attack, respawn and report counters."
    )
    parser.add_argument("--host", default=DEFAULT_HOST, help="server host (default 127.0.0.1)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="server port (default 8823)")
    parser.add_argument("--clients", type=int, default=1, help="number of PvP clients")
    parser.add_argument("--seconds", type=float, default=DEFAULT_SECONDS, help="run duration in seconds")
    parser.add_argument(
        "--attack-interval-ms", type=int, default=DEFAULT_ATTACK_INTERVAL_MS,
        help="milliseconds between attacks per client (default 1000)",
    )
    parser.add_argument("--target-policy", choices=("ring", "nearest"), default="ring", help="target selection")
    parser.add_argument("--output", type=Path, default=Path("pvp-result.json"), help="JSON output path")
    parser.add_argument("--chat-line", help="optional chat message to send after joining")
    parser.add_argument("--self-test", action="store_true", help="run parser and fake-server self-checks")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.self_test:
        return _self_test()
    try:
        return run(
            args.clients,
            args.seconds,
            args.host,
            args.port,
            args.attack_interval_ms,
            args.target_policy,
            args.output,
            args.chat_line,
        )
    except (ValueError, OSError, ProtocolProbeError, PvpClientError) as error:
        print(f"pvp_clients error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
