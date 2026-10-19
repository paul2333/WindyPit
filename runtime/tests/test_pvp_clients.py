import contextlib
import io
import json
import struct
import sys
import unittest
from pathlib import Path

RUNTIME = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(RUNTIME / "tools"))
import pvp_clients
from protocol_probe import _string, encode_varint
from pvp_clients import (
    _detect_kill,
    parse_player_list,
    parse_spawn_player,
    parse_update_health,
    select_target,
)

RAW_UUID = bytes(range(16))


class ParserTests(unittest.TestCase):
    def test_player_list_maps_uuid_to_name(self):
        payload = (
            encode_varint(0)
            + encode_varint(1)
            + RAW_UUID
            + _string("Target001")
            + encode_varint(0)
            + encode_varint(0)
            + encode_varint(0)
            + b"\x00"
        )
        mapping: dict[bytes, str] = {}
        parse_player_list(payload, mapping)
        self.assertEqual(mapping[RAW_UUID], "Target001")

    def test_spawn_player_position_is_fixed_point(self):
        payload = (
            encode_varint(200)
            + RAW_UUID
            + struct.pack(">iii", 32, 2048, -64)
            + struct.pack(">bb", 0, 0)
            + struct.pack(">h", 0)
            + b"\x7f"
        )
        entity_id, raw_uuid, position = parse_spawn_player(payload)
        self.assertEqual(entity_id, 200)
        self.assertEqual(raw_uuid, RAW_UUID)
        self.assertEqual(position, (1.0, 64.0, -2.0))

    def test_update_health(self):
        payload = struct.pack(">f", 12.5) + encode_varint(18) + struct.pack(">f", 3.0)
        self.assertEqual(parse_update_health(payload), (12.5, 18, 3.0))

    def test_kill_detection_is_directional(self):
        self.assertTrue(_detect_kill("PvP000 killed Target001", "PvP000", {"Target001"}))
        self.assertTrue(_detect_kill("You killed Target001", "PvP000", {"Target001"}))
        self.assertFalse(_detect_kill("Target001 killed PvP000", "PvP000", {"Target001"}))


class TargetPolicyTests(unittest.TestCase):
    def setUp(self):
        self.entities = {
            1: {"pos": [10.0, 64.0, 0.0]},
            2: {"pos": [2.0, 64.0, 0.0]},
            3: {"pos": None},
        }

    def test_ring_rotates_in_entity_id_order(self):
        self.assertEqual(select_target(self.entities, 99, None, "ring", 0), 1)
        self.assertEqual(select_target(self.entities, 99, None, "ring", 1), 2)
        self.assertEqual(select_target(self.entities, 99, None, "ring", 2), 3)
        self.assertEqual(select_target(self.entities, 99, None, "ring", 3), 1)

    def test_nearest_uses_known_positions(self):
        self.assertEqual(select_target(self.entities, 99, (0.0, 64.0, 0.0), "nearest", 0), 2)

    def test_nearest_falls_back_to_ring_without_positions(self):
        entities = {1: {"pos": None}, 2: {"pos": None}}
        self.assertEqual(select_target(entities, 99, (0.0, 64.0, 0.0), "nearest", 1), 2)

    def test_self_entity_is_excluded(self):
        self.assertEqual(select_target({1: {"pos": None}}, 1, None, "ring", 0), None)


class EndToEndTests(unittest.TestCase):
    def test_self_test_fake_server(self):
        with contextlib.redirect_stdout(io.StringIO()) as output, contextlib.redirect_stderr(io.StringIO()):
            code = pvp_clients._self_test()
        self.assertEqual(code, 0, output.getvalue())
        self.assertIn("self-test OK", output.getvalue())


if __name__ == "__main__":
    unittest.main()
