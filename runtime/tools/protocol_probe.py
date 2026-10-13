"""Exercise a Minecraft 1.8.8 (protocol 47) status/login/play connection.

This is intentionally a small standard-library probe rather than a full client.
It checks status, offline-mode login, a position update, and an inventory click
packet. The server must be a disposable smoke instance with online-mode=false.
"""

from __future__ import annotations

import argparse
import json
import socket
import struct
import sys
import time
import zlib
from pathlib import Path


PROTOCOL_VERSION = 47
DEFAULT_TIMEOUT = 15.0
CLICK_SETTLE_TIMEOUT = 1.0


class ProtocolProbeError(RuntimeError):
    """A malformed packet or failed protocol assertion."""


def encode_varint(value: int) -> bytes:
    if value < 0:
        value &= 0xFFFFFFFF
    result = bytearray()
    while True:
        part = value & 0x7F
        value >>= 7
        if value:
            result.append(part | 0x80)
        else:
            result.append(part)
            return bytes(result)


def decode_varint(data: bytes, offset: int = 0) -> tuple[int, int]:
    result = 0
    shift = 0
    for index in range(offset, min(len(data), offset + 5)):
        part = data[index]
        result |= (part & 0x7F) << shift
        if not part & 0x80:
            return result, index + 1
        shift += 7
    raise ProtocolProbeError("incomplete or overlong VarInt")


def _string(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return encode_varint(len(encoded)) + encoded


def _read_varint(sock: socket.socket) -> int:
    result = 0
    shift = 0
    for _ in range(5):
        part = sock.recv(1)
        if not part:
            raise ProtocolProbeError("connection closed while reading VarInt")
        value = part[0]
        result |= (value & 0x7F) << shift
        if not value & 0x80:
            return result
        shift += 7
    raise ProtocolProbeError("overlong packet-length VarInt")


def _recv_exact(sock: socket.socket, length: int) -> bytes:
    result = bytearray()
    while len(result) < length:
        block = sock.recv(length - len(result))
        if not block:
            raise ProtocolProbeError("connection closed in packet body")
        result.extend(block)
    return bytes(result)


def encode_packet(packet_id: int, payload: bytes = b"", compression_threshold: int | None = None) -> bytes:
    packet_data = encode_varint(packet_id) + payload
    if compression_threshold is None:
        body = packet_data
    elif len(packet_data) >= compression_threshold:
        body = encode_varint(len(packet_data)) + zlib.compress(packet_data)
    else:
        body = encode_varint(0) + packet_data
    return encode_varint(len(body)) + body


def decode_frame(frame: bytes, compression_threshold: int | None = None) -> tuple[int, bytes]:
    length, offset = decode_varint(frame)
    body = frame[offset:]
    if len(body) != length:
        raise ProtocolProbeError(f"frame length mismatch: declared {length}, got {len(body)}")
    if compression_threshold is not None:
        data_length, offset = decode_varint(body)
        if data_length:
            try:
                packet_data = zlib.decompress(body[offset:])
            except zlib.error as error:
                raise ProtocolProbeError(f"invalid compressed packet: {error}") from error
            if len(packet_data) != data_length:
                raise ProtocolProbeError("compressed packet length mismatch")
        else:
            packet_data = body[offset:]
    else:
        packet_data = body
    packet_id, offset = decode_varint(packet_data)
    return packet_id, packet_data[offset:]


def _read_packet(sock: socket.socket, compression_threshold: int | None = None) -> tuple[int, bytes]:
    length = _read_varint(sock)
    if length <= 0 or length > 8 * 1024 * 1024:
        raise ProtocolProbeError(f"invalid packet length {length}")
    return decode_frame(encode_varint(length) + _recv_exact(sock, length), compression_threshold)


def make_handshake(host: str, port: int, next_state: int) -> bytes:
    return encode_packet(
        0,
        encode_varint(PROTOCOL_VERSION) + _string(host) + struct.pack(">H", port) + encode_varint(next_state),
    )


def _status(sock: socket.socket, host: str, port: int) -> dict[str, object]:
    sock.sendall(make_handshake(host, port, 1))
    sock.sendall(encode_packet(0))
    packet_id, payload = _read_packet(sock)
    if packet_id != 0:
        raise ProtocolProbeError(f"expected status response 0x00, got 0x{packet_id:02x}")
    size, offset = decode_varint(payload)
    try:
        status = json.loads(payload[offset:offset + size].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ProtocolProbeError(f"invalid status JSON: {error}") from error
    if not isinstance(status, dict):
        raise ProtocolProbeError("status response is not an object")
    nonce = struct.pack(">q", int(time.time_ns() & 0x7FFFFFFFFFFFFFFF))
    sock.sendall(encode_packet(1, nonce))
    packet_id, pong = _read_packet(sock)
    if packet_id != 1 or pong != nonce:
        raise ProtocolProbeError("status ping/pong did not round-trip")
    return status


def _read_string(payload: bytes, offset: int = 0) -> tuple[str, int]:
    size, offset = decode_varint(payload, offset)
    end = offset + size
    if end > len(payload):
        raise ProtocolProbeError("string exceeds packet payload")
    try:
        value = payload[offset:end].decode("utf-8")
    except UnicodeDecodeError as error:
        raise ProtocolProbeError(f"string is not valid UTF-8: {error}") from error
    return value, end


def _parse_login_success(payload: bytes, expected_username: str) -> str:
    uuid, offset = _read_string(payload)
    username, offset = _read_string(payload, offset)
    if offset != len(payload):
        raise ProtocolProbeError("login success packet contains trailing data")
    if username != expected_username:
        raise ProtocolProbeError(f"login success returned unexpected username {username!r}")
    return uuid


def _parse_join_game(payload: bytes) -> int:
    if len(payload) < 9:
        raise ProtocolProbeError("Join Game packet is too short")
    entity_id = struct.unpack_from(">i", payload, 0)[0]
    _level_type, offset = _read_string(payload, 8)
    if offset + 1 != len(payload) or payload[offset] not in (0, 1):
        raise ProtocolProbeError("Join Game packet has an invalid reduced-debug flag")
    return entity_id


def _play(sock: socket.socket, host: str, port: int, username: str, timeout: float) -> dict[str, object]:
    sock.sendall(make_handshake(host, port, 2))
    sock.sendall(encode_packet(0, _string(username)))
    compression_threshold: int | None = None
    login_uuid: str | None = None
    joined = False
    entity_id: int | None = None
    movement_sent = False
    client_settings_sent = False
    window_items_received = False
    click_sent = False
    click_acknowledged = False
    click_confirmation = "pending"
    click_window_id = 0
    click_action_number = 1
    coordinates: tuple[float, float, float] | None = None
    server_packet_ids: list[int] = []
    click_deadline: float | None = None
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline and not click_acknowledged:
        now = time.monotonic()
        if click_sent and click_deadline is not None and now >= click_deadline:
            break
        remaining = deadline - now
        if click_deadline is not None:
            remaining = min(remaining, click_deadline - now)
        sock.settimeout(max(remaining, 0.001))
        try:
            packet_id, payload = _read_packet(sock, compression_threshold)
        except socket.timeout as error:
            break
        server_packet_ids.append(packet_id)
        if packet_id == 0x03 and login_uuid is None:
            compression_threshold, offset = decode_varint(payload)
            if offset != len(payload):
                raise ProtocolProbeError("Set Compression packet contains trailing data")
            continue
        if packet_id == 0x02 and login_uuid is None:
            login_uuid = _parse_login_success(payload, username)
            continue
        if packet_id == 0x01 and login_uuid is not None and not joined:
            entity_id = _parse_join_game(payload)
            joined = True
            continue
        if packet_id == 0x00 and login_uuid is None:
            reason, _ = _read_string(payload)
            raise ProtocolProbeError(f"server rejected login: {reason}")
        if packet_id == 0x00 and joined:  # Keep Alive
            keep_alive, _ = decode_varint(payload)
            sock.sendall(encode_packet(0x00, encode_varint(keep_alive), compression_threshold))
            continue
        if packet_id == 0x08 and joined and not movement_sent:
            if len(payload) != 8 * 3 + 4 * 2 + 1:
                raise ProtocolProbeError("player position/look packet is too short")
            x, y, z, _yaw, _pitch, _flags = struct.unpack(">dddffB", payload)
            coordinates = (x, y, z)
            x, y, z = coordinates
            sock.sendall(encode_packet(0x04, struct.pack(">ddd?", x, y, z, True), compression_threshold))
            sock.sendall(
                encode_packet(
                    0x15,
                    _string("en_US") + struct.pack(">bBBB", 8, 0, 1, 0),
                    compression_threshold,
                )
            )
            movement_sent = True
            client_settings_sent = True
            continue
        if packet_id == 0x30 and joined and not click_sent:
            if not payload:
                raise ProtocolProbeError("Window Items packet is empty")
            window_id = payload[0]
            if window_id == 0:
                window_items_received = True
                # Slot zero starts empty in the disposable survival inventory.
                # Clicking it with an empty item exercises transaction handling
                # without changing the player's inventory.
                click_payload = (
                    struct.pack(">BhBh", window_id, 0, 0, click_action_number)
                    + encode_varint(0)
                    + struct.pack(">h", -1)
                )
                sock.sendall(encode_packet(0x0E, click_payload, compression_threshold))
                click_sent = True
                click_window_id = window_id
                click_deadline = time.monotonic() + min(timeout, CLICK_SETTLE_TIMEOUT)
            continue
        if packet_id == 0x32 and click_sent:
            if len(payload) != 4:
                raise ProtocolProbeError("Confirm Transaction packet has an invalid size")
            window_id, action_number, accepted = struct.unpack(">Bh?", payload)
            if window_id != click_window_id or action_number != click_action_number:
                continue
            if not accepted:
                raise ProtocolProbeError("server rejected the protocol 47 inventory click")
            click_acknowledged = True
            click_confirmation = "accepted-confirmation"
    if login_uuid is None:
        raise ProtocolProbeError("protocol 47 login did not receive Login Success")
    if not joined:
        raise ProtocolProbeError("protocol 47 login did not receive Join Game")
    if not movement_sent:
        raise ProtocolProbeError("protocol 47 player movement packet was not sent")
    if not window_items_received or not click_sent:
        raise ProtocolProbeError("protocol 47 player inventory Window Items packet was not received")
    if not click_acknowledged:
        # Protocol 47 only sends Confirm Transaction when it rejects an action;
        # an accepted no-op click has no positive confirmation packet.
        click_confirmation = "not-rejected"
    return {
        "login_success": True,
        "login_uuid": login_uuid,
        "joined": joined,
        "entity_id": entity_id,
        "movement_sent": movement_sent,
        "client_settings_sent": client_settings_sent,
        "position": coordinates,
        "window_items_received": window_items_received,
        "inventory_click_sent": click_sent,
        "inventory_click_acknowledged": click_acknowledged,
        "inventory_click_accepted": click_confirmation != "rejected",
        "inventory_click_confirmation": click_confirmation,
        "inventory_window_id": click_window_id,
        "inventory_action_number": click_action_number,
        "server_packet_ids_seen": sorted({f"0x{packet_id:02x}" for packet_id in server_packet_ids}),
    }


def run_probe(host: str, port: int, timeout: float = DEFAULT_TIMEOUT, username: str = "WindyProbe") -> dict[str, object]:
    if timeout <= 0:
        raise ValueError("timeout must be positive")
    with socket.create_connection((host, port), timeout=timeout) as status_socket:
        status_socket.settimeout(timeout)
        status = _status(status_socket, host, port)
    with socket.create_connection((host, port), timeout=timeout) as play_socket:
        play_socket.settimeout(timeout)
        play = _play(play_socket, host, port, username, timeout)
    return {"protocol": PROTOCOL_VERSION, "status": status, "play": play}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25565)
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT)
    parser.add_argument("--username", default="WindyProbe")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        result = run_probe(args.host, args.port, args.timeout, args.username)
    except (OSError, ProtocolProbeError, ValueError) as error:
        print(f"protocol probe failed: {error}", file=sys.stderr)
        return 1
    output = json.dumps(result, indent=2, sort_keys=True)
    print(output)
    if args.output:
        args.output.write_text(output + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
