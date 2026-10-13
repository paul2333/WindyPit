"""Parser for the deliberately small YAML subset used by runtime lock files.

The lock and launcher files use ordinary YAML scalar mappings and a list of
scalar mappings. Inline arrays use JSON flow syntax. Keeping this parser in
the Python standard library makes fetch and launch work without PyYAML.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


def _scalar(value: str, line_number: int) -> Any:
    value = value.strip()
    if not value:
        return None
    if value.startswith(('"', "'", "[", "{")):
        try:
            return json.loads(value)
        except json.JSONDecodeError as error:
            raise ValueError(f"invalid JSON-style YAML scalar on line {line_number}: {error}") from error
    if value == "true":
        return True
    if value == "false":
        return False
    if value in ("null", "~"):
        return None
    if value.isdigit():
        return int(value)
    return value


def _key_value(text: str, line_number: int) -> tuple[str, Any]:
    if ":" not in text:
        raise ValueError(f"expected a key/value pair on line {line_number}")
    key, value = text.split(":", 1)
    key = key.strip()
    if not key:
        raise ValueError(f"empty YAML key on line {line_number}")
    return key, _scalar(value, line_number)


def load_yaml(path: str | Path) -> Any:
    """Load the lock-file subset, rejecting malformed or duplicate keys."""

    source = Path(path)
    text = source.read_text(encoding="utf-8")
    try:
        # JSON is a YAML 1.2 subset and is useful for small fixture lock files.
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    lines: list[tuple[int, str, int]] = []
    for number, raw in enumerate(text.splitlines(), start=1):
        if "\t" in raw:
            raise ValueError(f"tabs are not supported in YAML indentation (line {number})")
        content = raw.strip()
        if not content or content.startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        lines.append((indent, content, number))
    if not lines:
        raise ValueError(f"empty YAML file: {source}")

    if lines[0][0] == 0 and (lines[0][1] == "-" or lines[0][1].startswith("- ")):
        items: list[dict[str, Any]] = []
        current: dict[str, Any] | None = None
        for indent, content, number in lines:
            if indent == 0 and (content == "-" or content.startswith("- ")):
                current = {}
                items.append(current)
                pair_text = content[1:].strip()
                if pair_text:
                    key, value = _key_value(pair_text, number)
                    current[key] = value
            elif indent > 0 and current is not None:
                key, value = _key_value(content, number)
                if key in current:
                    raise ValueError(f"duplicate YAML key {key!r} on line {number}")
                current[key] = value
            else:
                raise ValueError(f"unsupported YAML list indentation on line {number}")
        return items

    result: dict[str, Any] = {}
    for indent, content, number in lines:
        if indent != 0:
            raise ValueError(f"nested YAML mappings are not supported (line {number})")
        key, value = _key_value(content, number)
        if key in result:
            raise ValueError(f"duplicate YAML key {key!r} on line {number}")
        result[key] = value
    return result
