import json
import socket
import struct
import sys
import threading
import time
import unittest
from pathlib import Path


RUNTIME = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(RUNTIME / "tools"))
import protocol_probe
from protocol_probe import decode_frame, decode_varint, encode_packet, encode_varint, make_handshake


class ProtocolProbeCodecTests(unittest.TestCase):
    def test_varint_round_trips_protocol_values(self):
        for value in (0, 1, 47, 127, 128, 255, 300, 2147483647):
            with self.subTest(value=value):
                encoded = encode_varint(value)
                self.assertEqual(decode_varint(encoded), (value, len(encoded)))

    def test_frame_round_trips_without_compression(self):
        frame = encode_packet(0x01, b"status payload")
        packet_id, payload = decode_frame(frame)
        self.assertEqual(packet_id, 0x01)
        self.assertEqual(payload, b"status payload")

    def test_frame_round_trips_with_compression(self):
        frame = encode_packet(0x02, b"login payload" * 20, compression_threshold=8)
        packet_id, payload = decode_frame(frame, compression_threshold=8)
        self.assertEqual(packet_id, 0x02)
        self.assertEqual(payload, b"login payload" * 20)

    def test_status_handshake_uses_protocol_47_and_next_state_status(self):
        frame = make_handshake("127.0.0.1", 25565, next_state=1)
        packet_id, payload = decode_frame(frame)
        self.assertEqual(packet_id, 0)
        protocol, offset = decode_varint(payload)
        self.assertEqual(protocol, 47)
        host_size, offset = decode_varint(payload, offset)
        self.assertEqual(payload[offset:offset + host_size], b"127.0.0.1")
        offset += host_size
        self.assertEqual(int.from_bytes(payload[offset:offset + 2], "big"), 25565)
        offset += 2
        self.assertEqual(decode_varint(payload, offset), (1, len(payload)))


class ProtocolProbeIntegrationTests(unittest.TestCase):
    def test_compressed_login_movement_and_unrejected_click_are_reported(self):
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(2)
        port = listener.getsockname()[1]
        errors = []
        observed = {}

        def serve():
            try:
                with listener:
                    with listener.accept()[0] as connection:
                        connection.settimeout(2)
                        protocol_probe._read_packet(connection)
                        protocol_probe._read_packet(connection)  # Status Request
                        status = json.dumps({"version": {"name": "test"}, "players": {"online": 0}})
                        connection.sendall(protocol_probe.encode_packet(0, protocol_probe._string(status)))
                        _packet_id, nonce = protocol_probe._read_packet(connection)
                        connection.sendall(protocol_probe.encode_packet(1, nonce))

                    with listener.accept()[0] as connection:
                        connection.settimeout(2)
                        protocol_probe._read_packet(connection)
                        protocol_probe._read_packet(connection)  # Login Start
                        threshold = 1
                        connection.sendall(protocol_probe.encode_packet(3, encode_varint(threshold)))
                        compressed = threshold
                        connection.sendall(
                            protocol_probe.encode_packet(
                                2,
                                protocol_probe._string("00000000-0000-0000-0000-000000000001")
                                + protocol_probe._string("WindyProbe"),
                                compressed,
                            )
                        )
                        join_game = struct.pack(">iBbBB", 7, 0, 0, 0, 1)
                        join_game += protocol_probe._string("default") + b"\x00"
                        connection.sendall(protocol_probe.encode_packet(1, join_game, compressed))
                        connection.sendall(protocol_probe.encode_packet(0, encode_varint(31), compressed))
                        connection.sendall(
                            protocol_probe.encode_packet(
                                8,
                                struct.pack(">dddffB", 12.5, 64.0, -2.25, 0.0, 0.0, 0),
                                compressed,
                            )
                        )
                        connection.sendall(protocol_probe.encode_packet(0x30, b"\x00\x00\x00", compressed))

                        for _ in range(4):
                            packet_id, payload = protocol_probe._read_packet(connection, compressed)
                            observed.setdefault("client_packets", []).append((packet_id, payload))
                            if packet_id == 0:
                                connection.sendall(protocol_probe.encode_packet(0, payload, compressed))
                        # An accepted no-op click has no positive 1.8 ACK; the
                        # connection must remain open without a rejection packet.
                        time.sleep(2.5)
            except Exception as error:  # surfaced in the test thread below
                errors.append(error)

        server_thread = threading.Thread(target=serve, daemon=True)
        server_thread.start()
        probe_error = None
        result = None
        try:
            result = protocol_probe.run_probe("127.0.0.1", port, timeout=2)
        except Exception as error:
            probe_error = error
        finally:
            server_thread.join(timeout=3)
        self.assertIsNone(probe_error, f"probe failed; fake server errors={errors}, observed={observed}")
        self.assertFalse(server_thread.is_alive(), "fake server did not finish")
        self.assertEqual(errors, [])
        self.assertTrue(result["play"]["login_success"])
        self.assertTrue(result["play"]["joined"])
        self.assertTrue(result["play"]["movement_sent"])
        self.assertEqual(result["play"]["inventory_click_confirmation"], "not-rejected")
        self.assertFalse(result["play"]["inventory_click_acknowledged"])
        packets = [packet for packet in observed["client_packets"] if packet[0] != 0]
        self.assertEqual([packet[0] for packet in packets], [0x04, 0x15, 0x0E])
        self.assertEqual(struct.unpack(">ddd?", packets[0][1]), (12.5, 64.0, -2.25, True))
        self.assertEqual(struct.unpack(">BhBh", packets[2][1][:6]), (0, 0, 0, 1))

    def test_run_probe_timeout_does_not_mutate_default_timeout(self):
        original = protocol_probe.DEFAULT_TIMEOUT
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        port = listener.getsockname()[1]
        listener.close()
        with self.assertRaises(OSError):
            protocol_probe.run_probe("127.0.0.1", port, timeout=0.1)
        self.assertEqual(protocol_probe.DEFAULT_TIMEOUT, original)


if __name__ == "__main__":
    unittest.main()
