import json
import re
import tempfile
import unittest
from pathlib import Path

import sys


RUNTIME = Path(__file__).resolve().parents[1]
MANIFEST_PATH = RUNTIME / "manifest.lock"
PLUGINS_PATH = RUNTIME / "plugins.lock"
sys.path.insert(0, str(RUNTIME / "tools"))
from fetch_artifacts import FetchError, load_locks
from yaml_subset import load_yaml


def read_yaml(path: Path):
    return load_yaml(path)


class RuntimeLockTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = read_yaml(MANIFEST_PATH)
        cls.plugins = read_yaml(PLUGINS_PATH)

    def test_manifest_pins_folia_stable_build_and_java_minimum(self):
        self.assertEqual(self.manifest["minecraft"], "26.1.2")
        self.assertEqual(self.manifest["server"], "Folia")
        self.assertEqual(self.manifest["java_min"], 25)
        self.assertEqual(self.manifest["filename"], "folia-26.1.2-8.jar")
        self.assertEqual(
            self.manifest["url"],
            "https://fill-data.papermc.io/v1/objects/"
            "607afd1c3320008e1ffd2eaee6780ace4419d5f8c527b75e79f259be79ebf57b/"
            "folia-26.1.2-8.jar",
        )
        self.assertEqual(
            self.manifest["sha256"],
            "607afd1c3320008e1ffd2eaee6780ace4419d5f8c527b75e79f259be79ebf57b",
        )
        self.assertEqual(self.manifest["channel"], "STABLE")
        self.assertIn("26.2.7", self.manifest["notes"])
        self.assertIn("beta", self.manifest["notes"].lower())

    def test_plugins_pin_release_attested_compatibility_tuple(self):
        self.assertEqual(
            [(plugin["name"], plugin["version"]) for plugin in self.plugins],
            [("ViaVersion", "5.12.1"), ("ViaBackwards", "5.12.1"), ("ViaRewind", "4.2.0")],
        )
        self.assertTrue(all(plugin["required"] for plugin in self.plugins))
        self.assertEqual(self.plugins[1]["depends_on"], ["ViaVersion"])
        self.assertEqual(self.plugins[2]["depends_on"], ["ViaVersion", "ViaBackwards"])

    def test_filenames_are_unique_across_server_and_plugins(self):
        filenames = [self.manifest["filename"]] + [plugin["filename"] for plugin in self.plugins]
        self.assertEqual(len(filenames), len(set(filenames)))

    def test_hashes_are_lowercase_sha256(self):
        hashes = [self.manifest["sha256"]] + [plugin["sha256"] for plugin in self.plugins]
        for value in hashes:
            with self.subTest(value=value):
                self.assertRegex(value, re.compile(r"^[0-9a-f]{64}$"))

    def _write_locks(self, root: Path, manifest=None, plugins=None):
        (root / "manifest.lock").write_text(
            json.dumps(self.manifest if manifest is None else manifest), encoding="utf-8"
        )
        (root / "plugins.lock").write_text(
            json.dumps(self.plugins if plugins is None else plugins), encoding="utf-8"
        )

    def test_lock_loader_rejects_duplicate_filenames(self):
        manifest = dict(self.manifest)
        plugins = [dict(plugin) for plugin in self.plugins]
        plugins[0]["filename"] = manifest["filename"]

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self._write_locks(root, manifest, plugins)
            with self.assertRaisesRegex(FetchError, "duplicate"):
                load_locks(root)

    def test_lock_loader_rejects_malformed_hashes(self):
        manifest = dict(self.manifest)
        manifest["sha256"] = "A" * 64

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self._write_locks(root, manifest)
            with self.assertRaisesRegex(FetchError, "sha256"):
                load_locks(root)

    def test_lock_loader_reports_missing_lock_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "manifest.lock").write_text(json.dumps(self.manifest), encoding="utf-8")
            with self.assertRaisesRegex(FetchError, "cannot read runtime locks"):
                load_locks(root)

    def test_runtime_configuration_has_required_keys_and_no_live_data_path(self):
        config = read_yaml(RUNTIME / "windypit.yml")
        self.assertEqual(
            set(config),
            {
                "java.home",
                "server.jar",
                "plugins.directory",
                "heap.min",
                "heap.max",
                "gc",
                "server.args",
                "eula.path",
                "test.data.path",
            },
        )
        self.assertIn(config["gc"], {"g1", "zgc"})
        self.assertNotIn("arena-region-id", config)
        self.assertIn("test-data", config["test.data.path"])
        self.assertNotIn("worlds", config["test.data.path"].lower())


if __name__ == "__main__":
    unittest.main()
