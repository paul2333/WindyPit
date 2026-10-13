import sys
import tempfile
import threading
import time
import unittest
import json
from pathlib import Path


RUNTIME = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(RUNTIME / "tools"))
from smoke import _validate_log, _wait_for_done, _write_smoke_properties, _local_plugin_identity


class RunningProcess:
    def poll(self):
        return None


class SmokeReadinessTests(unittest.TestCase):
    def test_local_plugin_identity_reads_name_and_version_from_descriptor(self):
        import zipfile

        with tempfile.TemporaryDirectory() as temporary:
            plugin_path = Path(temporary) / "modern.jar"
            with zipfile.ZipFile(plugin_path, "w") as archive:
                archive.writestr("plugin.yml", "name: ThePitFoliaMigration\nversion: 0.1.0-experimental\n")

            self.assertEqual(
                _local_plugin_identity(plugin_path),
                ("ThePitFoliaMigration", "0.1.0-experimental"),
            )

    def test_wait_for_done_ignores_world_preparation_banner(self):
        with tempfile.TemporaryDirectory() as temporary:
            log_path = Path(temporary) / "server.log"
            log_path.write_text("[INFO]: Done preparing level \"world\"\n", encoding="utf-8")

            def finish_startup():
                time.sleep(0.2)
                with log_path.open("a", encoding="utf-8") as log:
                    log.write("[INFO]: Done (8.4s)! For help, type \"help\"\n")

            writer = threading.Thread(target=finish_startup)
            writer.start()
            completed_log = _wait_for_done(RunningProcess(), log_path, time.monotonic() + 2)
            writer.join(timeout=1)

        self.assertIn("Done (8.4s)!", completed_log)

    def test_smoke_properties_configure_flat_layers_and_local_offline_probe(self):
        with tempfile.TemporaryDirectory() as temporary:
            properties_path = _write_smoke_properties(Path(temporary), 23456)
            properties = dict(
                line.split("=", 1)
                for line in properties_path.read_text(encoding="ascii").splitlines()
                if line and not line.startswith("#")
            )

        settings = json.loads(properties["generator-settings"])
        self.assertEqual(properties["server-ip"], "127.0.0.1")
        self.assertEqual(properties["server-port"], "23456")
        self.assertEqual(properties["online-mode"], "false")
        self.assertEqual(properties["network-compression-threshold"], "-1")
        self.assertEqual(properties["difficulty"], "peaceful")
        self.assertEqual(properties["level-type"], r"minecraft\:flat")
        self.assertEqual([layer["height"] for layer in settings["layers"]], [1, 2, 1])

    def test_server_error_severity_is_reported(self):
        failures = _validate_log("[ERROR]: World generator emitted an error\n", [])
        self.assertTrue(any("server log error" in failure.lower() for failure in failures))

    def test_smoke_requires_each_locked_plugin_and_version(self):
        expected = [
            ("ViaVersion", "5.12.1"),
            ("ViaBackwards", "5.12.1"),
            ("ViaRewind", "4.2.0"),
        ]
        log = "\n".join(
            [
                "Loading Folia 26.1.2-8",
                "[ViaVersion] Enabling ViaVersion v5.12.1",
                "[ViaBackwards] Enabling ViaBackwards v5.12.1",
                "[ViaRewind] Enabling ViaRewind v4.2.0",
            ]
        )

        self.assertEqual(_validate_log(log, expected), [])

    def test_smoke_fails_for_missing_disabled_or_failed_plugins(self):
        expected = [("ViaVersion", "5.12.1")]
        missing = _validate_log("Loading Folia 26.1.2-8\n", expected)
        disabled = _validate_log(
            "Loading Folia 26.1.2-8\n[ViaVersion] Loading server plugin ViaVersion v5.12.1\n"
            "[ViaVersion] Disabling ViaVersion v5.12.1\n",
            expected,
        )
        failed = _validate_log(
            "Loading Folia 26.1.2-8\n[ERROR]: Failed to load plugin ViaVersion\n", expected
        )

        self.assertTrue(any("ViaVersion was not mentioned" in failure for failure in missing))
        self.assertTrue(any("appears disabled or failed" in failure for failure in disabled))
        self.assertTrue(any("plugin load error" in failure for failure in failed))

    def test_smoke_ignores_expected_plugin_disable_during_server_shutdown(self):
        log = (
            "Loading Folia 26.1.2-8\n"
            "[ThePitFoliaMigration] Loading server plugin ThePitFoliaMigration v0.1.0-experimental\n"
            "[ThePitFoliaMigration] Enabling ThePitFoliaMigration v0.1.0-experimental\n"
            "Stopping the server\n"
            "[ThePitFoliaMigration] Disabling ThePitFoliaMigration v0.1.0-experimental\n"
        )
        self.assertEqual(
            _validate_log(log, [("ThePitFoliaMigration", "0.1.0-experimental")]), []
        )


if __name__ == "__main__":
    unittest.main()
