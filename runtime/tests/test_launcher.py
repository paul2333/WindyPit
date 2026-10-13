import os
import hashlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from pathlib import Path


RUNTIME = Path(__file__).resolve().parents[1]
LAUNCHER = RUNTIME / "tools" / "launcher.py"
sys.path.insert(0, str(RUNTIME / "tools"))
from launcher import LauncherError, build_command, format_command, validate_java_major


def plugin_jar(name, version):
    payload = io.BytesIO()
    with zipfile.ZipFile(payload, "w") as jar:
        jar.writestr(
            "plugin.yml",
            f"name: {name}\nversion: {version}\nfolia-supported: true\n",
        )
    return payload.getvalue()


class LauncherContractTests(unittest.TestCase):
    def run_launcher(self, *arguments, env=None, root=RUNTIME):
        child_env = os.environ.copy()
        child_env.pop("WINDYPIT_JAVA_HOME", None)
        child_env.pop("JAVA_HOME", None)
        if env:
            child_env.update(env)
        return subprocess.run(
            [sys.executable, str(LAUNCHER), "--root", str(root), *arguments],
            capture_output=True,
            text=True,
            env=child_env,
            check=False,
        )

    def test_missing_java_is_reported(self):
        result = self.run_launcher("--dry-run", env={"WINDYPIT_JAVA_HOME": "C:/missing/windypit-jdk"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("java", (result.stdout + result.stderr).lower())

    def test_invalid_gc_selection_is_rejected(self):
        result = self.run_launcher("--gc", "serial", "--dry-run")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("invalid choice", (result.stdout + result.stderr).lower())

    def test_java_24_is_below_the_required_minimum(self):
        with self.assertRaisesRegex(RuntimeError, "Java 25 or newer"):
            validate_java_major(24, 25)

    def test_java_25_meets_the_required_minimum(self):
        self.assertEqual(validate_java_major(25, 25), 25)

    def test_command_format_quotes_paths_with_spaces(self):
        command = format_command(
            [r"C:\Program Files\Microsoft JDK\bin\java.exe", "-jar", r"C:\runtime files\server.jar"]
        )
        self.assertIn('"C:\\Program Files\\Microsoft JDK\\bin\\java.exe"', command)
        self.assertIn('"C:\\runtime files\\server.jar"', command)

    def test_java_25_dry_run_accepts_paths_with_spaces(self):
        java_homes = [
            os.environ.get("WINDYPIT_JAVA_HOME"),
            os.environ.get("JAVA_HOME"),
            r"C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot" if os.name == "nt" else None,
        ]
        java_home = next(
            (
                Path(candidate)
                for candidate in java_homes
                if candidate
                and (Path(candidate) / "bin" / ("java.exe" if os.name == "nt" else "java")).is_file()
            ),
            None,
        )
        if java_home is None:
            self.skipTest("Java 25 is not installed")

        with tempfile.TemporaryDirectory(prefix="WindyPit runtime with spaces ") as temp:
            root = Path(temp)
            server_name = "folia fixture.jar"
            server_payload = b"fixture server"
            (root / "server").mkdir()
            (root / "server" / server_name).write_bytes(server_payload)
            (root / "plugins").mkdir()
            plugin_rows = []
            for name in ("ViaVersion", "ViaBackwards", "ViaRewind"):
                filename = f"{name} fixture.jar"
                version = "4.2.0" if name == "ViaRewind" else "5.12.0"
                payload = plugin_jar(name, version)
                (root / "plugins" / filename).write_bytes(payload)
                plugin_rows.append(
                    {
                        "name": name,
                        "version": version,
                        "filename": filename,
                        "url": "https://example.invalid/" + filename.replace(" ", "%20"),
                        "sha256": hashlib.sha256(payload).hexdigest(),
                        "required": True,
                        "depends_on": [],
                    }
                )
            manifest = {
                "minecraft": "26.1.2",
                "server": "Folia",
                "java_min": 25,
                "filename": server_name,
                "url": "https://example.invalid/folia.jar",
                "sha256": hashlib.sha256(server_payload).hexdigest(),
            }
            (root / "manifest.lock").write_text(json.dumps(manifest), encoding="utf-8")
            (root / "plugins.lock").write_text(json.dumps(plugin_rows), encoding="utf-8")
            config = {
                "java.home": "",
                "server.jar": "server/" + server_name,
                "plugins.directory": "plugins",
                "heap.min": "1G",
                "heap.max": "2G",
                "gc": "g1",
                "server.args": [],
                "eula.path": "test-data/eula.txt",
                "test.data.path": "test-data",
            }
            (root / "windypit.yml").write_text(json.dumps(config), encoding="utf-8")
            result = self.run_launcher(
                "--dry-run",
                "--nogui",
                env={"WINDYPIT_JAVA_HOME": str(java_home)},
                root=root,
            )

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('"', result.stdout)
        self.assertIn(server_name, result.stdout)
        self.assertIn("UseG1GC", result.stdout)

    def test_dry_run_rejects_server_path_that_bypasses_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payload = b"fixture server"
            (root / "server").mkdir()
            (root / "server" / "locked.jar").write_bytes(payload)
            (root / "server" / "other.jar").write_bytes(payload)
            (root / "plugins").mkdir()
            plugin_rows = []
            for name in ("ViaVersion", "ViaBackwards", "ViaRewind"):
                version = "4.2.0" if name == "ViaRewind" else "5.12.0"
                filename = f"{name}.jar"
                plugin_payload = plugin_jar(name, version)
                (root / "plugins" / filename).write_bytes(plugin_payload)
                plugin_rows.append(
                    {
                        "name": name,
                        "version": version,
                        "filename": filename,
                        "url": "https://example.invalid/" + filename,
                        "sha256": hashlib.sha256(plugin_payload).hexdigest(),
                        "required": True,
                        "depends_on": [],
                    }
                )
            (root / "manifest.lock").write_text(
                json.dumps(
                    {
                        "minecraft": "26.1.2",
                        "server": "Folia",
                        "java_min": 25,
                        "filename": "locked.jar",
                        "url": "https://example.invalid/locked.jar",
                        "sha256": hashlib.sha256(payload).hexdigest(),
                    }
                ),
                encoding="utf-8",
            )
            (root / "plugins.lock").write_text(json.dumps(plugin_rows), encoding="utf-8")
            (root / "windypit.yml").write_text(
                json.dumps(
                    {
                        "java.home": "",
                        "server.jar": "server/other.jar",
                        "plugins.directory": "plugins",
                        "heap.min": "1G",
                        "heap.max": "2G",
                        "gc": "g1",
                        "server.args": [],
                        "eula.path": "test-data/eula.txt",
                        "test.data.path": "test-data",
                    }
                ),
                encoding="utf-8",
            )

            with patch("launcher._java_executable", return_value=Path("fake-java")), patch(
                "launcher.java_major", return_value=25
            ):
                with self.assertRaisesRegex(LauncherError, "locked server artifact"):
                    build_command(root, environment={"WINDYPIT_JAVA_HOME": "unused"})


if __name__ == "__main__":
    unittest.main()
