"""Protocol-47 load clients for WindyPit baselines.

Each client performs an offline-mode login, waits for Join Game + Player Position, then sends
position packets at a fixed cadence while answering Keep Alive. It is a transport/player-tick
load generator, not a gameplay bot: it does not fight, trade or open GUIs.
"""

from __future__ import annotations

import argparse
import json
import math
import socket
import struct
import threading
import time

from protocol_probe import (
    _read_packet,
    _string,
    decode_varint,
    encode_packet,
    encode_varint,
    make_handshake,
)


def _parse_join_game(payload: bytes) -> int:
    if len(payload) < 8:
        raise ValueError("join game packet too short")
    return struct.unpack(">i", payload[:4])[0]


def _client(index: int, host: str, port: int, seconds: float, results: list[dict]) -> None:
    name = f"Load{index:03d}"
    started = time.monotonic()
    result = {"client": name, "joined": False, "position": None, "moves": 0, "error": None}
    sock = None
    try:
        sock = socket.create_connection((host, port), timeout=10.0)
        sock.sendall(make_handshake(host, port, 2))
        sock.sendall(encode_packet(0, _string(name)))
        threshold = None
        joined = False
        coords = None
        next_move = 0.0
        deadline = started + seconds
        while time.monotonic() < deadline:
            sock.settimeout(0.2)
            try:
                packet_id, payload = _read_packet(sock, threshold)
            except socket.timeout:
                packet_id, payload = None, b""
            if packet_id == 0x03 and not joined:
                threshold, _ = decode_varint(payload)
            elif packet_id == 0x01 and not joined:
                _parse_join_game(payload)
                joined = True
                result["joined"] = True
            elif packet_id == 0x08 and len(payload) >= 32:
                coords = struct.unpack(">ddd", payload[:24])
                result["position"] = list(coords)
                if not result.get("settings"):
                    sock.sendall(encode_packet(0x15, _string("en_US") + struct.pack(">bBBB", 8, 0, 1, 0), threshold))
                    result["settings"] = True
            elif packet_id == 0x00 and joined:
                keep_alive, _ = decode_varint(payload)
                sock.sendall(encode_packet(0x00, encode_varint(keep_alive), threshold))
            now = time.monotonic()
            if coords is not None and now >= next_move:
                x, y, z = coords
                bob = math.sin(now * 3.0) * 0.05
                sock.sendall(encode_packet(0x04, struct.pack(">ddd?", x, y + bob, z, True), threshold))
                result["moves"] += 1
                next_move = now + 0.1
    except Exception as error:  # noqa: BLE001 - load client must report, not crash the run
        result["error"] = f"{type(error).__name__}: {error}"
    finally:
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass
        result["seconds"] = round(time.monotonic() - started, 2)
        results.append(result)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8823)
    parser.add_argument("--clients", type=int, default=20)
    parser.add_argument("--seconds", type=float, default=60.0)
    parser.add_argument("--output", default="load-result.json")
    args = parser.parse_args()

    results: list[dict] = []
    threads = [
        threading.Thread(target=_client, args=(i, args.host, args.port, args.seconds, results), daemon=True)
        for i in range(args.clients)
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=args.seconds + 30)
    joined = sum(1 for result in results if result["joined"])
    moves = sum(result["moves"] for result in results)
    errors = [result for result in results if result["error"]]
    summary = {
        "clients": args.clients,
        "joined": joined,
        "moves": moves,
        "errors": len(errors),
        "error_samples": errors[:5],
        "results": results,
    }
    with open(args.output, "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2)
    print(json.dumps({key: summary[key] for key in ("clients", "joined", "moves", "errors")}))
    return 0 if joined == args.clients else 1


if __name__ == "__main__":
    raise SystemExit(main())

