import hashlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


RUNTIME = Path(__file__).resolve().parents[1]
FETCHER = RUNTIME / "tools" / "fetch_artifacts.py"


def plugin_jar(name="ViaVersion", version="5.12.0", folia_supported=True):
    descriptor = f"name: {name}\nversion: {version}\n"
    if folia_supported:
        descriptor += "folia-supported: true\n"
    payload = io.BytesIO()
    with zipfile.ZipFile(payload, "w") as jar:
        jar.writestr("plugin.yml", descriptor)
    return payload.getvalue()


class FetchContractTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.payloads = {
            "folia-fixture.jar": b"fixture server payload",
            "ViaVersion-fixture.jar": plugin_jar(),
        }
        self.manifest = {
            "minecraft": "26.1.2",
            "server": "Folia",
            "java_min": 25,
            "filename": "folia-fixture.jar",
            "url": "https://example.invalid/folia-fixture.jar",
            "sha256": hashlib.sha256(self.payloads["folia-fixture.jar"]).hexdigest(),
        }
        self.plugins = [
            {
                "name": "ViaVersion",
                "version": "5.12.0",
                "filename": "ViaVersion-fixture.jar",
                "url": "https://example.invalid/ViaVersion-fixture.jar",
                "sha256": hashlib.sha256(self.payloads["ViaVersion-fixture.jar"]).hexdigest(),
                "required": True,
                "depends_on": [],
            }
        ]
        (self.root / "manifest.lock").write_text(json.dumps(self.manifest), encoding="utf-8")
        (self.root / "plugins.lock").write_text(json.dumps(self.plugins), encoding="utf-8")
        for directory, filename in (
            ("server", self.manifest["filename"]),
            ("plugins", self.plugins[0]["filename"]),
        ):
            target = self.root / directory / filename
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(self.payloads[filename])

    def tearDown(self):
        self.temp.cleanup()

    def run_fetcher(self):
        return subprocess.run(
            [sys.executable, str(FETCHER), "--root", str(self.root), "--offline"],
            capture_output=True,
            text=True,
            check=False,
        )

    def test_offline_verification_uses_server_and_plugin_lock_paths(self):
        result = self.run_fetcher()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((self.root / "server" / "folia-fixture.jar").is_file())
        self.assertTrue((self.root / "plugins" / "ViaVersion-fixture.jar").is_file())

    def test_offline_verification_rejects_hash_mismatch(self):
        (self.root / "plugins" / "ViaVersion-fixture.jar").write_bytes(b"tampered")
        result = self.run_fetcher()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("sha256", (result.stdout + result.stderr).lower())

    def test_offline_verification_rejects_missing_artifacts(self):
        (self.root / "server" / "folia-fixture.jar").unlink()
        result = self.run_fetcher()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing", (result.stdout + result.stderr).lower())

    def test_offline_verification_rejects_malformed_checksum(self):
        self.plugins[0]["sha256"] = "not-a-sha256"
        (self.root / "plugins.lock").write_text(json.dumps(self.plugins), encoding="utf-8")
        result = self.run_fetcher()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("sha256", (result.stdout + result.stderr).lower())

    def test_offline_verification_rejects_duplicate_filenames(self):
        self.plugins[0]["filename"] = self.manifest["filename"]
        (self.root / "plugins.lock").write_text(json.dumps(self.plugins), encoding="utf-8")
        result = self.run_fetcher()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("duplicate", (result.stdout + result.stderr).lower())

    def test_offline_verification_checks_plugin_descriptor_name_and_version(self):
        payload = plugin_jar(name="UnexpectedPlugin", version="9.9.9")
        target = self.root / "plugins" / self.plugins[0]["filename"]
        target.write_bytes(payload)
        self.plugins[0]["sha256"] = hashlib.sha256(payload).hexdigest()
        (self.root / "plugins.lock").write_text(json.dumps(self.plugins), encoding="utf-8")

        result = self.run_fetcher()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("descriptor name mismatch", (result.stdout + result.stderr).lower())

    def test_offline_verification_requires_folia_support_declaration(self):
        payload = plugin_jar(folia_supported=False)
        target = self.root / "plugins" / self.plugins[0]["filename"]
        target.write_bytes(payload)
        self.plugins[0]["sha256"] = hashlib.sha256(payload).hexdigest()
        (self.root / "plugins.lock").write_text(json.dumps(self.plugins), encoding="utf-8")

        result = self.run_fetcher()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("folia-supported", (result.stdout + result.stderr).lower())


if __name__ == "__main__":
    unittest.main()
