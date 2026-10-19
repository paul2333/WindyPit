"""Minimal Source RCON client for the disposable smoke servers."""
import socket
import struct
import sys


def pack(request_id, packet_type, body):
    payload = body.encode("utf-8")
    size = 4 + 4 + len(payload) + 2
    return struct.pack("<iii", size, request_id, packet_type) + payload + b"\x00\x00"


def recv_packet(sock):
    raw = b""
    while len(raw) < 4:
        chunk = sock.recv(4 - len(raw))
        if not chunk:
            raise RuntimeError("rcon closed")
        raw += chunk
    (size,) = struct.unpack("<i", raw)
    body = b""
    while len(body) < size:
        chunk = sock.recv(size - len(body))
        if not chunk:
            raise RuntimeError("rcon closed")
        body += chunk
    request_id, packet_type = struct.unpack("<ii", body[:8])
    text = body[8:-2].decode("utf-8", "replace")
    return request_id, packet_type, text


def main():
    host = "127.0.0.1"
    port = int(sys.argv[1])
    password = sys.argv[2]
    commands = sys.argv[3:]
    sock = socket.create_connection((host, port), timeout=10)
    sock.settimeout(15)
    sock.sendall(pack(1, 3, password))
    request_id, _, text = recv_packet(sock)
    if request_id == -1:
        print("rcon login failed", file=sys.stderr)
        return 2
    for index, command in enumerate(commands, start=2):
        sock.sendall(pack(index, 2, command))
        request_id, _, text = recv_packet(sock)
        print(f"### {command}")
        print(text)
    sock.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
