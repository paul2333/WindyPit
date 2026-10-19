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

