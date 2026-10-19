import re
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "tools" / "run_scenario.ps1"


class RunScenarioContractTests(unittest.TestCase):
    def setUp(self):
        self.text = SCRIPT.read_text(encoding="utf-8")

    def test_required_parameters_exist(self):
        for parameter in (
            "Scenario",
            "Clients",
            "Bots",
            "UseThePit",
            "WarmupSeconds",
            "MeasureSeconds",
            "SampleIntervalSeconds",
            "Port",
            "RconPort",
        ):
            self.assertRegex(self.text, rf"\[[^\]]+\]\s*\${parameter}\b", parameter)

    def test_defaults_match_acceptance_window(self):
        self.assertIn("[int]$WarmupSeconds = 300", self.text)
        self.assertIn("[int]$MeasureSeconds = 600", self.text)
        self.assertIn("[int]$SampleIntervalSeconds = 30", self.text)

    def test_java_25_flags_and_startup_marker(self):
        self.assertIn("--add-opens', 'java.base/java.lang=ALL-UNNAMED'", self.text)
        self.assertIn("--add-opens', 'java.base/java.net=ALL-UNNAMED'", self.text)
        self.assertIn("--add-opens', 'java.base/java.nio=ALL-UNNAMED'", self.text)
        self.assertIn("--enable-native-access=ALL-UNNAMED", self.text)
        self.assertIn("'Done ('", self.text)

    def test_required_tools_are_wired(self):
        for tool in (
            "load_clients.py",
            "pvp_clients.py",
            "rcon_sampler.py",
            "jfr_profile.py",
            "jfr_classify.py",
            "verify_ledger.py",
            "gate_eval.py",
        ):
            self.assertIn(tool, self.text)

    def test_manifest_and_layout_fields(self):
        for field in (
            "sha256",
            "jvmArgs",
            "warmupSeconds",
            "measureSeconds",
            "clients",
            "bots",
            "configSha256",
            "gateOverall",
            "manifest.json",
        ):
            self.assertIn(field, self.text)
        for directory in ("logs", "jfr", "metrics", "clients"):
            self.assertIn(f"'{directory}'", self.text)

    def test_scenario_name_is_path_safe(self):
        self.assertIn("ValidatePattern('^[A-Za-z0-9._-]+$')", self.text)
        self.assertIn("perf\\$Scenario", self.text)


if __name__ == "__main__":
    unittest.main()

