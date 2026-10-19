import argparse
import sys
import unittest
from pathlib import Path


RUNTIME = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(RUNTIME / "tools"))
from jfr_profile import analyze_recording, build_jfr_start_command, duration_argument, duration_ms, render_report


class JfrProfileTests(unittest.TestCase):
    def test_duration_parser_handles_jfr_iso_and_unit_values(self):
        self.assertAlmostEqual(duration_ms("PT0.051S"), 51.0)
        self.assertAlmostEqual(duration_ms("75.5 ms"), 75.5)
        self.assertAlmostEqual(duration_ms("0.2 s"), 200.0)
        self.assertIsNone(duration_ms("unknown"))

    def test_capture_arguments_quote_recording_paths_with_spaces(self):
        command = build_jfr_start_command(
            Path("C:/Java 25/bin/jcmd.exe"),
            1234,
            Path("C:/temporary profile/settings.jfc"),
            Path("C:/server profiles/capture.jfr"),
            "3m",
            "WindyPitProfile-1234",
        )

        self.assertEqual(command[1:3], ["1234", "JFR.start"])
        self.assertEqual(command[-2], 'filename="C:\\server profiles\\capture.jfr"')
        self.assertEqual(duration_argument("3M"), "3m")
        with self.assertRaises(argparse.ArgumentTypeError):
            duration_argument("0m")

    def test_analysis_keeps_only_slow_duration_events_and_separates_samples(self):
        recording = {
            "recording": {
                "events": [
                    {
                        "type": "jdk.JavaMonitorEnter",
                        "values": {
                            "duration": "PT0.075S",
                            "eventThread": {"osName": "Server thread"},
                            "stackTrace": {
                                "frames": [
                                    {"method": {"type": {"name": "net.minecraft.server.World"}, "name": "tick"}, "lineNumber": 81},
                                    {"method": {"type": {"name": "com.example.Pit"}, "name": "update"}},
                                ]
                            },
                        },
                    },
                    {
                        "type": "jdk.ThreadPark",
                        "values": {"duration": "PT0.020S", "stackTrace": {"frames": []}},
                    },
                    {
                        "type": "jdk.JavaMonitorWait",
                        "values": {
                            "duration": "PT3S",
                            "eventThread": {"osName": "JFR Recording Scheduler"},
                            "stackTrace": {"frames": []},
                        },
                    },
                    {
                        "type": "jdk.ExecutionSample",
                        "values": {
                            "sampledThread": {"osName": "Server thread"},
                            "stackTrace": {
                                "frames": [
                                    {"method": {"type": {"name": "net.minecraft.server.World"}, "name": "tick"}},
                                    {"method": {"type": {"name": "com.example.Pit"}, "name": "update"}},
                                ]
                            },
                        },
                    },
                    {
                        "type": "jdk.ExecutionSample",
                        "values": {
                            "sampledThread": {"osName": "JFR Recorder Thread"},
                            "stackTrace": {
                                "frames": [
                                    {"method": {"type": {"name": "jdk/jfr/internal/JVM"}, "name": "emitEvent"}}
                                ]
                            },
                        },
                    },
                ]
            }
        }

        result = analyze_recording(recording, threshold_ms=50, sample_period_ms=10)

        self.assertEqual(len(result.slow_events), 1)
        self.assertEqual(result.slow_events[0].event_type, "jdk.JavaMonitorEnter")
        self.assertEqual(result.slow_events[0].duration_ms, 75.0)
        self.assertEqual(len(result.cpu_hotspots), 1)
        self.assertEqual(result.cpu_hotspots[0].sample_count, 1)
        self.assertEqual(result.cpu_hotspots[0].estimated_ms, 10.0)

    def test_report_labels_sample_time_as_estimate_and_writes_no_claim_of_single_call_latency(self):
        result = analyze_recording(
            {"recording": {"events": [{"type": "jdk.ExecutionSample", "values": {"stackTrace": {"frames": []}}}]}},
            threshold_ms=50,
            sample_period_ms=10,
        )
        report = render_report(result, source="capture.jfr")

        self.assertIn("Sampled CPU hotspots", report)
        self.assertIn("estimated sampled milliseconds", report)
        self.assertIn("does not measure a single invocation's duration", report)
        self.assertIn("No contention or long-operation duration event met the threshold", report)

    def test_report_separates_expected_idle_waits_from_contention_candidates(self):
        recording = {"recording": {"events": [
            {"type": "jdk.ThreadPark", "values": {
                "duration": "PT8S", "eventThread": {"osName": "Lighting Thread"},
                "stackTrace": {"frames": [
                    {"method": {"type": {"name": "java.util.concurrent.LinkedBlockingQueue"}, "name": "take"}},
                    {"method": {"type": {"name": "java.util.concurrent.ThreadPoolExecutor"}, "name": "getTask"}},
                ]},
            }},
            {"type": "jdk.ThreadPark", "values": {
                "duration": "PT0.05S", "eventThread": {"osName": "Server thread"},
                "stackTrace": {"frames": [
                    {"method": {"type": {"name": "net.minecraft.server.MinecraftServer"}, "name": "waitUntilNextTick"}},
                ]},
            }},
            {"type": "jdk.ThreadPark", "values": {
                "duration": "PT0.08S", "eventThread": {"osName": "Server thread"},
                "stackTrace": {"frames": [
                    {"method": {"type": {"name": "com.example.CombatLock"}, "name": "lock"}},
                ]},
            }},
        ]}}

        result = analyze_recording(recording, threshold_ms=50)
        report = render_report(result, source="idle.jfr")

        self.assertEqual(len(result.idle_wait_events), 2)
        self.assertEqual(len(result.slow_events), 1)
        self.assertEqual(result.slow_events[0].duration_ms, 80.0)
        self.assertIn("Expected pacing and idle waits", report)
        self.assertIn("8000.00 ms", report)
        self.assertIn("Contention and long operations", report)

if __name__ == "__main__":
    unittest.main()

