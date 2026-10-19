import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

RUNTIME = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(RUNTIME / "tools"))
import gate_eval
from gate_eval import ACCEPTED_WORK_METRICS, evaluate


def good_metrics() -> dict:
    return {
        "samples": 21,
        "metrics": {
            "tps_1m": {"count": 21, "mean": 19.9, "min": 19.7, "max": 20.0, "p99_max": 19.95},
            "tick_p99_ms": {"count": 21, "mean": 20.0, "min": 10.0, "max": 42.0, "p99_max": 42.0},
            **{
                name: {"count": 21, "mean": 0.0, "min": 0.0, "max": 0.0, "p99_max": 0.0}
                for name, _label in ACCEPTED_WORK_METRICS
            },
        },
    }


class EvaluateTests(unittest.TestCase):
    def test_all_gates_pass(self):
        summary = evaluate(good_metrics(), {"unexplained_repeated": 0},
                           {"ledger": {"reserved": 0, "kill_reward_reserved": 0}})
        self.assertEqual(summary["overall"], "PASS")
        self.assertEqual(summary["counts"]["FAIL"], 0)
        self.assertEqual(summary["counts"]["PARTIAL"], 0)

    def test_tps_failure(self):
        metrics = good_metrics()
        metrics["metrics"]["tps_1m"]["mean"] = 19.0
        summary = evaluate(metrics, {"unexplained_repeated": 0}, {"ledger": {"kill_reward_reserved": 0}})
        self.assertEqual(summary["overall"], "FAIL")
        check = next(item for item in summary["checks"] if item["id"] == "tps_mean")
        self.assertEqual(check["status"], "FAIL")

    def test_tick_p99_failure(self):
        metrics = good_metrics()
        metrics["metrics"]["tick_p99_ms"]["p99_max"] = 55.0
        summary = evaluate(metrics, {"unexplained_repeated": 0}, {"ledger": {"kill_reward_reserved": 0}})
        self.assertEqual(summary["overall"], "FAIL")

    def test_unexplained_repeated_failure(self):
        summary = evaluate(good_metrics(), {"unexplained_repeated": 3}, {"ledger": {"kill_reward_reserved": 0}})
        self.assertEqual(summary["overall"], "FAIL")

    def test_accepted_work_counter_failure(self):
        metrics = good_metrics()
        metrics["metrics"]["world_dropped"]["max"] = 1
        summary = evaluate(metrics, {"unexplained_repeated": 0}, {"ledger": {"kill_reward_reserved": 0}})
        self.assertEqual(summary["overall"], "FAIL")
        self.assertTrue(any(reason.startswith("accepted.world_dropped") for reason in summary["reasons"]))

    def test_kill_reward_reserved_failure(self):
        summary = evaluate(good_metrics(), {"unexplained_repeated": 0},
                           {"ledger": {"reserved": 2, "kill_reward_reserved": 1}})
        self.assertEqual(summary["overall"], "FAIL")

    def test_missing_inputs_are_partial(self):
        summary = evaluate(good_metrics(), None, None)
        self.assertEqual(summary["overall"], "PARTIAL")
        self.assertEqual(summary["counts"]["PARTIAL"], 2)

    def test_missing_metric_is_partial(self):
        metrics = good_metrics()
        del metrics["metrics"]["compute_rejected"]
        summary = evaluate(metrics, {"unexplained_repeated": 0}, {"ledger": {"kill_reward_reserved": 0}})
        self.assertEqual(summary["overall"], "PARTIAL")


class CommandLineTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        (self.root / "metrics.json").write_text(json.dumps(good_metrics()), encoding="utf-8")
        (self.root / "jfr-gate.json").write_text(json.dumps({"unexplained_repeated": 0}), encoding="utf-8")
        (self.root / "ledger.json").write_text(
            json.dumps({"ledger": {"reserved": 0, "kill_reward_reserved": 0}}), encoding="utf-8"
        )

    def tearDown(self):
        self.temporary.cleanup()

    def test_pass_writes_summary_and_report(self):
        output = self.root / "gate-summary.json"
        report = self.root / "gate-report.md"
        with contextlib.redirect_stdout(io.StringIO()):
            code = gate_eval.main(
                [
                    "--metrics", str(self.root / "metrics.json"),
                    "--jfr-gate", str(self.root / "jfr-gate.json"),
                    "--ledger", str(self.root / "ledger.json"),
                    "--output", str(output),
                    "--report", str(report),
                ]
            )
        self.assertEqual(code, 0)
        summary = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(summary["overall"], "PASS")
        self.assertIn("Overall: PASS", report.read_text(encoding="utf-8"))

    def test_fail_exits_1(self):
        metrics = good_metrics()
        metrics["metrics"]["tps_1m"]["mean"] = 18.0
        (self.root / "bad.json").write_text(json.dumps(metrics), encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()):
            code = gate_eval.main(
                [
                    "--metrics", str(self.root / "bad.json"),
                    "--jfr-gate", str(self.root / "jfr-gate.json"),
                    "--ledger", str(self.root / "ledger.json"),
                    "--output", str(self.root / "gate-summary.json"),
                    "--report", str(self.root / "gate-report.md"),
                ]
            )
        self.assertEqual(code, 1)
        summary = json.loads((self.root / "gate-summary.json").read_text(encoding="utf-8"))
        self.assertEqual(summary["overall"], "FAIL")

    def test_missing_file_exits_2(self):
        with contextlib.redirect_stderr(io.StringIO()):
            code = gate_eval.main(["--metrics", str(self.root / "missing.json")])
        self.assertEqual(code, 2)


if __name__ == "__main__":
    unittest.main()

