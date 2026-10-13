"""Fetch and verify the locked Folia server and Via plugin artifacts."""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path, PureWindowsPath
from typing import Any

from yaml_subset import load_yaml


SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
CHUNK_SIZE = 1024 * 1024


class FetchError(RuntimeError):
    """A lock, path, transfer, or checksum validation error."""


class HttpsRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        if urllib.parse.urlparse(new_url).scheme.lower() != "https":
            raise FetchError(f"refusing non-HTTPS redirect to {new_url!r}")
        return super().redirect_request(request, response, code, message, headers, new_url)


def _require_mapping(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise FetchError(f"{label} must be a YAML mapping")
    return value


def _require_hash(entry: dict[str, Any], label: str) -> str:
    expected = entry.get("sha256")
    if not isinstance(expected, str) or not SHA256_RE.fullmatch(expected):
        raise FetchError(f"{label} is missing a valid lowercase sha256 checksum")
    return expected


def _validate_filename(entry: dict[str, Any], label: str) -> str:
    filename = entry.get("filename")
    if (
        not isinstance(filename, str)
        or not filename
        or filename in {".", ".."}
        or Path(filename).name != filename
        or PureWindowsPath(filename).name != filename
        or "/" in filename
        or "\\" in filename
    ):
        raise FetchError(f"{label} filename must be a plain filename")
    return filename


def _validate_url(entry: dict[str, Any], label: str) -> str:
    url = entry.get("url")
    parsed = urllib.parse.urlparse(url) if isinstance(url, str) else None
    if parsed is None or parsed.scheme.lower() != "https" or not parsed.netloc:
        raise FetchError(f"{label} URL must be an absolute HTTPS URL")
    return url


def load_locks(root: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    try:
        manifest = _require_mapping(load_yaml(root / "manifest.lock"), "manifest.lock")
        plugins_value = load_yaml(root / "plugins.lock")
    except (OSError, ValueError) as error:
        raise FetchError(f"cannot read runtime locks: {error}") from error

    for key in ("minecraft", "server", "java_min", "filename", "url", "sha256"):
        if key not in manifest:
            raise FetchError(f"manifest.lock is missing required field {key!r}")
    if not isinstance(plugins_value, list):
        raise FetchError("plugins.lock must be a YAML list")
    plugins: list[dict[str, Any]] = []
    for index, value in enumerate(plugins_value):
        entry = _require_mapping(value, f"plugins.lock entry {index + 1}")
        for key in ("name", "version", "filename", "url", "sha256", "required", "depends_on"):
            if key not in entry:
                raise FetchError(f"plugins.lock entry {index + 1} is missing required field {key!r}")
        if not isinstance(entry["required"], bool):
            raise FetchError(f"plugins.lock entry {index + 1} required must be boolean")
        if not isinstance(entry["depends_on"], list) or not all(
            isinstance(item, str) for item in entry["depends_on"]
        ):
            raise FetchError(f"plugins.lock entry {index + 1} depends_on must be a list of names")
        plugins.append(entry)

    filenames = [_validate_filename(manifest, "manifest.lock")]
    filenames.extend(_validate_filename(plugin, f"plugin {plugin['name']}") for plugin in plugins)
    if len(filenames) != len(set(filenames)):
        raise FetchError("runtime locks contain duplicate artifact filenames")

    _require_hash(manifest, "manifest.lock")
    _validate_url(manifest, "manifest.lock")
    for plugin in plugins:
        label = f"plugin {plugin['name']}"
        _require_hash(plugin, label)
        _validate_url(plugin, label)
    return manifest, plugins


def artifact_path(root: Path, artifact_type: str, filename: str) -> Path:
    """Return the canonical server/ or plugins/ destination for a lock entry."""

    if artifact_type not in {"server", "plugins"}:
        raise ValueError(f"unknown artifact type: {artifact_type}")
    return root / artifact_type / filename


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(CHUNK_SIZE), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_file(path: Path, expected: str, label: str) -> str:
    if not path.is_file():
        raise FetchError(f"missing locked artifact {label}: {path}")
    actual = sha256_file(path)
    if actual != expected:
        raise FetchError(f"sha256 mismatch for {label}: expected {expected}, got {actual}")
    return actual


def _plugin_descriptor_values(text: str) -> dict[str, str]:
    """Read the three top-level scalar metadata fields used by Bukkit plugin.yml."""

    values: dict[str, str] = {}
    for line in text.lstrip("\ufeff").splitlines():
        if not line or line[0].isspace() or line.lstrip().startswith("#"):
            continue
        key, separator, raw_value = line.partition(":")
        if not separator or key not in {"name", "version", "folia-supported"}:
            continue
        raw_value = raw_value.strip()
        if raw_value.startswith(('"', "'")):
            quote = raw_value[0]
            end = raw_value.find(quote, 1)
            if end < 0:
                continue
            value = raw_value[1:end]
        else:
            value = raw_value.split(" #", 1)[0].strip()
        values[key] = value
    return values


def verify_plugin_descriptor(path: Path, expected_name: str, expected_version: str) -> None:
    """Reject a correctly hashed plugin jar whose descriptor disagrees with its lock."""

    try:
        with zipfile.ZipFile(path) as jar:
            descriptor_name = next(
                (name for name in ("plugin.yml", "paper-plugin.yml") if name in jar.namelist()),
                None,
            )
            if descriptor_name is None:
                raise FetchError(f"plugin {expected_name} has no plugin.yml or paper-plugin.yml descriptor")
            descriptor = jar.read(descriptor_name).decode("utf-8-sig")
    except (OSError, UnicodeDecodeError, zipfile.BadZipFile, KeyError) as error:
        raise FetchError(f"cannot read plugin descriptor for {expected_name}: {error}") from error

    values = _plugin_descriptor_values(descriptor)
    if values.get("name") != expected_name:
        raise FetchError(
            f"plugin descriptor name mismatch for {expected_name}: found {values.get('name')!r}"
        )
    if values.get("version", "").lstrip("v") != expected_version.lstrip("v"):
        raise FetchError(
            f"plugin descriptor version mismatch for {expected_name}: found {values.get('version')!r}"
        )
    if values.get("folia-supported", "").lower() != "true":
        raise FetchError(f"plugin {expected_name} descriptor does not declare folia-supported: true")


def _download(url: str, target: Path, expected: str, label: str) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    part = target.with_name(target.name + ".part")
    headers = {"User-Agent": "WindyPit-runtime-fetcher/1.0"}
    parsed = urllib.parse.urlparse(url)
    if parsed.hostname == "api.github.com" and "/releases/assets/" in parsed.path:
        headers["Accept"] = "application/octet-stream"
    request = urllib.request.Request(url, headers=headers)
    opener = urllib.request.build_opener(HttpsRedirectHandler())
    try:
        with opener.open(request, timeout=60) as response, part.open("wb") as output:
            while True:
                block = response.read(CHUNK_SIZE)
                if not block:
                    break
                output.write(block)
            output.flush()
            os.fsync(output.fileno())
        actual = sha256_file(part)
        if actual != expected:
            raise FetchError(f"download sha256 mismatch for {label}: expected {expected}, got {actual}")
        os.replace(part, target)
    except (OSError, urllib.error.URLError, TimeoutError) as error:
        raise FetchError(f"download failed for {label}: {error}") from error
    finally:
        try:
            part.unlink(missing_ok=True)
        except OSError:
            pass


def process_artifacts(root: Path, offline: bool = False) -> list[tuple[str, Path, str]]:
    manifest, plugins = load_locks(root)
    artifacts: list[tuple[str, dict[str, Any], str]] = [("server", manifest, "Folia server")]
    artifacts.extend(("plugins", plugin, str(plugin["name"])) for plugin in plugins)
    verified: list[tuple[str, Path, str]] = []
    for artifact_type, entry, label in artifacts:
        filename = _validate_filename(entry, label)
        expected = _require_hash(entry, label)
        target = artifact_path(root, artifact_type, filename)
        if target.is_file():
            digest = verify_file(target, expected, label)
            if artifact_type == "plugins":
                verify_plugin_descriptor(target, str(entry["name"]), str(entry["version"]))
            print(f"verified {label}: {target} ({digest})")
        elif offline:
            if artifact_type == "plugins" and not entry.get("required", False):
                continue
            raise FetchError(f"missing locked artifact {label}: {target} (offline mode)")
        else:
            _download(_validate_url(entry, label), target, expected, label)
            digest = verify_file(target, expected, label)
            if artifact_type == "plugins":
                verify_plugin_descriptor(target, str(entry["name"]), str(entry["version"]))
            print(f"downloaded and verified {label}: {target} ({digest})")
        verified.append((artifact_type, target, digest))
    return verified


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--offline", action="store_true", help="verify cached artifacts without network access")
    args = parser.parse_args(argv)
    try:
        process_artifacts(args.root.resolve(), offline=args.offline)
    except (FetchError, OSError) as error:
        print(f"fetch error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
