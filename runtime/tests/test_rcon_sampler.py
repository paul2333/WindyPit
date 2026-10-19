import contextlib
import io
import json
import socket
import sys
import tempfile
import threading
import unittest
from pathlib import Path

RUNTIME = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(RUNTIME / "tools"))
import rcon_sampler
from rcon_sampler import parse_tps, parse_windypit_tps, summarize

FIXTURES = Path(__file__).resolve().parent / "fixtures"


class TpsParserTests(unittest.TestCase):
    def setUp(self):
        self.tps_text = (FIXTURES / "p5-tps-sample.txt").read_text(encoding="utf-8")
        self.windy_text = (FIXTURES / "p5-windypit-tps-sample.txt").read_text(encoding="utf-8")

    def test_parses_tps_tick_tracking_and_async_fields(self):
        parsed = parse_tps(self.tps_text)
        self.assertEqual(parsed["tps_1m"], 19.95)
        self.assertEqual(parsed["tps_15m"], 20.0)
        self.assertEqual(parsed["tick_p50_ms"], 1.3)
        self.assertEqual(parsed["tick_p95_ms"], 5.5)
        self.assertEqual(parsed["tick_p99_ms"], 12.5)
        self.assertEqual(parsed["mspt"], 12.5)
        self.assertEqual(parsed["tracking_queued"], 12)
        self.assertEqual(parsed["tracking_dropped"], 3)
        self.assertEqual(parsed["async_explosion_rejected"], 4)
        self.assertEqual(parsed["async_path_rejected"], 0)
        self.assertEqual(parsed["online_players"], 20)

    def test_parses_windypit_scheduler_counters(self):
        parsed = parse_windypit_tps(self.windy_text)
        self.assertEqual(parsed["wp_tps_1m"], 19.95)
        self.assertEqual(parsed["threading_mode"], "REGION")
        self.assertEqual(parsed["arena_owner"], "PRIMARY")
        self.assertEqual(parsed["region_rejected"], 1)
        self.assertEqual(parsed["region_queue_max"], 3)
        self.assertEqual(parsed["compute_stale_dropped"], 1)
        self.assertEqual(parsed["compute_queue"], 2)
        self.assertEqual(parsed["world_dropped"], 1)
        self.assertEqual(parsed["world_queue_sum"], 2)
        self.assertEqual(parsed["world_tick_p99_max_ms"], 8.0)
        self.assertEqual(len(parsed["worlds"]), 2)

    def test_rejects_unknown_output(self):
        with self.assertRaises(ValueError):
            parse_tps("Unknown plugin message")
        with self.assertRaises(ValueError):
            parse_windypit_tps("Unknown plugin message")

    def test_summary_reports_mean_min_max_and_p99_max(self):
        tps = parse_tps(self.tps_text)
        windy = parse_windypit_tps(self.windy_text)
        rows = [
            {**tps, **windy, "timestamp": "t0", "elapsed_seconds": 0},
            {**tps, **windy, "timestamp": "t1", "elapsed_seconds": 30, "tps_1m": 20.0},
        ]
        summary = summarize(rows)
        tps_stats = summary["metrics"]["tps_1m"]
        self.assertEqual(tps_stats["count"], 2)
        self.assertAlmostEqual(tps_stats["mean"], 19.975)
        self.assertEqual(tps_stats["min"], 19.95)
        self.assertEqual(tps_stats["max"], 20.0)
        self.assertIn("p99_max", tps_stats)
        tick_stats = summary["metrics"]["tick_p99_ms"]
        self.assertEqual(tick_stats["p99_max_kind"], "max-of-p99")
        self.assertEqual(tick_stats["p99_max"], 12.5)


class FakeRconServer(threading.Thread):
    """A tiny Source RCON server that answers /tps and /windypit-tps."""

    def __init__(self, responses: dict[str, str]):
        super().__init__(daemon=True)
        self.responses = responses
        self.commands: list[str] = []
        self.errors: list[str] = []
        self.listener = socket.socket()
        self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(2)
        self.port = self.listener.getsockname()[1]

    def run(self):
        try:
            with self.listener:
                connection, _ = self.listener.accept()
                with connection:
                    connection.settimeout(5.0)
                    request_id, packet_type, _password = rcon_sampler.recv_packet(connection)
                    if packet_type != 3:
                        raise AssertionError(f"expected auth packet, got {packet_type}")
                    connection.sendall(rcon_sampler.pack_packet(request_id, 2, ""))
                    while len(self.commands) < len(self.responses):
                        request_id, packet_type, text = rcon_sampler.recv_packet(connection)
                        if packet_type != 2:
                            continue
                        self.commands.append(text)
                        body = self.responses.get(text, "unknown command")
                        connection.sendall(rcon_sampler.pack_packet(request_id, 0, body))
        except Exception as error:  # noqa: BLE001 - surfaced by the test
            self.errors.append(f"{type(error).__name__}: {error}")


class RconIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.tps_text = (FIXTURES / "p5-tps-sample.txt").read_text(encoding="utf-8")
        self.windy_text = (FIXTURES / "p5-windypit-tps-sample.txt").read_text(encoding="utf-8")

    def test_once_samples_both_commands_and_writes_metrics(self):
        server = FakeRconServer({"tps": self.tps_text, "windypit-tps": self.windy_text})
        server.start()
        with tempfile.TemporaryDirectory() as temporary:
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                code = rcon_sampler.main(
                    [
                        "--host", "127.0.0.1",
                        "--port", str(server.port),
                        "--password", "test",
                        "--once",
                        "--output-dir", temporary,
                    ]
                )
            server.join(timeout=5)
            self.assertEqual(server.errors, [])
            self.assertEqual(code, 0)
            self.assertEqual(server.commands, ["tps", "windypit-tps"])
            metrics = Path(temporary) / "metrics.csv"
            summary_path = Path(temporary) / "metrics-summary.json"
            self.assertTrue(metrics.is_file())
            summary = json.loads(summary_path.read_text(encoding="utf-8"))
            self.assertEqual(summary["samples"], 1)
            self.assertEqual(summary["errors"], 0)
            self.assertAlmostEqual(summary["metrics"]["tps_1m"]["mean"], 19.95)
            header = metrics.read_text(encoding="utf-8").splitlines()[0]
            self.assertIn("tick_p99_ms", header)
            self.assertIn("world_tick_p99_max_ms", header)

    def test_send_command_mode(self):
        server = FakeRconServer({"stop": "Stopping the server"})
        server.start()
        with contextlib.redirect_stdout(io.StringIO()):
            code = rcon_sampler.main(
                [
                    "--host", "127.0.0.1",
                    "--port", str(server.port),
                    "--password", "test",
                    "--send-command", "stop",
                ]
            )
        server.join(timeout=5)
        self.assertEqual(code, 0)
        self.assertEqual(server.commands, ["stop"])

    def test_connection_failure_is_non_zero(self):
        with tempfile.TemporaryDirectory() as temporary:
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                code = rcon_sampler.main(
                    ["--host", "127.0.0.1", "--port", "1", "--once", "--timeout", "0.5",
                     "--output-dir", temporary]
                )
            self.assertNotEqual(code, 0)
            summary = json.loads((Path(temporary) / "metrics-summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["errors"], 1)
            self.assertIn("error", (Path(temporary) / "metrics.csv").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
