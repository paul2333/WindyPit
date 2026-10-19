import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

RUNTIME = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(RUNTIME / "tools"))
import jfr_classify
from jfr_classify import analyze, classify, duration_ms, normalize_frame

FIXTURES = Path(__file__).resolve().parent / "fixtures"


class DurationParsingTests(unittest.TestCase):
    def test_iso_and_text_durations(self):
        self.assertAlmostEqual(duration_ms("PT0.060S"), 60.0)
        self.assertAlmostEqual(duration_ms("PT1M2.5S"), 62500.0)
        self.assertAlmostEqual(duration_ms("50 ms"), 50.0)
        self.assertAlmostEqual(duration_ms("1500us"), 1.5)
        self.assertAlmostEqual(duration_ms({"value": 2, "unit": "s"}), 2000.0)
        self.assertIsNone(duration_ms("not-a-duration"))
        self.assertIsNone(duration_ms(None))

    def test_frame_normalisation(self):
        self.assertEqual(
            normalize_frame("net/minecraft/server/v1_8_R3/MinecraftServer.waitUntilNextTick:846"),
            "net.minecraft.server.v1_8_R3.MinecraftServer.waitUntilNextTick",
        )


class ClassificationRuleTests(unittest.TestCase):
    def test_rule_categories(self):
        pacing = ["net/minecraft/server/v1_8_R3/MinecraftServer.waitUntilNextTick:846"]
        idle = ["java/util/concurrent/ThreadPoolExecutor.getTask:1000"]
        owner = ["com/windpvp/windspigot/scheduler/RegionScheduler.drain:100"]
        db = ["org/sqlite/core/NativeDB.step:10"]
        self.assertEqual(classify("jdk.ThreadPark", "Server thread", pacing), "intentional_tick_pacing")
        self.assertEqual(classify("jdk.ThreadPark", "WindyPit-Compute-0", idle), "intentional_idle")
        self.assertEqual(classify("jdk.JavaMonitorEnter", "Server thread", owner), "owner_core_blocking")
        self.assertEqual(classify("jdk.SocketRead", "Netty IO #1", []), "network")
        self.assertEqual(classify("jdk.FileWrite", "Server thread", db), "db_file_io")
        self.assertEqual(classify("jdk.GarbageCollection", "VM Thread", []), "gc")
        self.assertEqual(classify("jdk.SafepointEnd", "VM Thread", []), "safepoint")
        self.assertEqual(classify("jdk.ThreadPark", "Unknown-Worker", ["example/Unknown.work:1"]), "unexplained")
        # An owner thread alone is not enough: the stack must contain an owner frame.
        self.assertEqual(classify("jdk.ThreadPark", "Server thread", ["java/lang/Object.wait0:1"]), "unexplained")


class AnalyzeFixtureTests(unittest.TestCase):
    def setUp(self):
        self.document = json.loads((FIXTURES / "p5-jfr-events.json").read_text(encoding="utf-8"))

    def test_fixture_classification_and_gate(self):
        result = analyze(self.document, threshold_ms=50.0)
        gate = result["gate"]
        expected = {
            "intentional_tick_pacing": 1,
            "intentional_idle": 1,
            "gc": 1,
            "safepoint": 1,
            "network": 1,
            "db_file_io": 1,
            "owner_core_blocking": 1,
            "unexplained": 2,
        }
        self.assertEqual(gate["categories"], expected)
        self.assertEqual(gate["classified_count"], 9)
        self.assertEqual(gate["unexplained_total"], 2)
        self.assertEqual(gate["unexplained_repeated"], 2)
        self.assertEqual(gate["owner_core_50ms_count"], 1)
        self.assertEqual(gate["owner_thread_50ms_count"], 4)
        self.assertEqual(gate["execution_sample_count"], 1)
        self.assertEqual(gate["excluded_jfr_internal"], 1)
        self.assertIn("not durations", gate["hotspot_note"])

    def test_writes_csv_and_gate_json(self):
        result = analyze(self.document, threshold_ms=50.0)
        with tempfile.TemporaryDirectory() as temporary:
            csv_path, gate_path, hotspot_path = jfr_classify.write_outputs(
                result, Path(temporary), "fixture.json"
            )
            lines = csv_path.read_text(encoding="utf-8").splitlines()
            self.assertEqual(len(lines), 10)  # header + 9 classified events
            self.assertIn("stack_hash", lines[0])
            gate = json.loads(gate_path.read_text(encoding="utf-8"))
            self.assertEqual(gate["source"], "fixture.json")
            self.assertEqual(gate["categories"]["unexplained"], 2)
            self.assertTrue(hotspot_path.is_file())

    def test_threshold_filters_small_events(self):
        result = analyze(self.document, threshold_ms=100.0)
        # Only the 120 ms SocketRead remains (the JFR-internal 19 s wait is excluded).
        self.assertEqual(result["gate"]["classified_count"], 1)
        self.assertEqual(result["gate"]["categories"]["network"], 1)

    def test_invalid_document_is_rejected(self):
        with self.assertRaises(jfr_classify.ClassifyError):
            analyze({"recording": {"events": "not-a-list"}})


class CommandLineTests(unittest.TestCase):
    def test_self_test_flag(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(jfr_classify.main(["--self-test"]), 0)

    def test_json_input_via_cli(self):
        fixture = FIXTURES / "p5-jfr-events.json"
        with tempfile.TemporaryDirectory() as temporary:
            with contextlib.redirect_stdout(io.StringIO()):
                code = jfr_classify.main([str(fixture), "--output-dir", temporary])
            self.assertEqual(code, 0)
            gate = json.loads((Path(temporary) / "gate.json").read_text(encoding="utf-8"))
            self.assertEqual(gate["unexplained_repeated"], 2)

    def test_missing_input_is_non_zero(self):
        with contextlib.redirect_stderr(io.StringIO()):
            code = jfr_classify.main(["does-not-exist.json", "--output-dir", tempfile.mkdtemp()])
        self.assertEqual(code, 2)


if __name__ == "__main__":
    unittest.main()

