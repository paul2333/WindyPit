        values = _numbers(match.group(1))[:5]
        if len(values) == 5:
            for key, value in zip(("tps_5s", "tps_10s", "tps_1m", "tps_5m", "tps_15m"), values):
                data[key] = value

    match = re.search(
        r"Tick durations \(min/med/p95/p99/max ms\) from last 10s, 1m:\s*(\S+)\s+(\S+)", text
    )
    if match:
        for prefix, group in (("tick_10s", match.group(1)), ("tick_1m", match.group(2))):
            parts = group.split("/")
            if len(parts) != 5 or any(part.upper() == "N/A" for part in parts):
                continue
            try:
                values = [float(part) for part in parts]
            except ValueError:
                continue
            for suffix, value in zip(("min", "p50", "p95", "p99", "max"), values):
                data[f"{prefix}_{suffix}_ms"] = value

    # Canonical tick percentiles: prefer the one minute window, fall back to 10s.
    for suffix in ("p50", "p95", "p99"):
        value = data.get(f"tick_1m_{suffix}_ms", data.get(f"tick_10s_{suffix}_ms"))
        if value is not None:
            data[f"tick_{suffix}_ms"] = value

    match = re.search(r"Milliseconds to Run Last Tick:\s*([0-9.]+)", text)
    if match:
        data["mspt"] = float(match.group(1))

    match = re.search(r"Tracking Queue:\s*(\d+)\s+queued,\s*(\d+)\s+dropped", text)
    if match:
        data["tracking_queued"] = int(match.group(1))
        data["tracking_dropped"] = int(match.group(2))

    match = re.search(r"Online Players:\s*(\d+)", text)
    if match:
        data["online_players"] = int(match.group(1))
    match = re.search(r"Entity Count:\s*(\d+)", text)
    if match:
        data["entity_count"] = int(match.group(1))
    match = re.search(r"Tile Entity Count:\s*(\d+)", text)
    if match:
        data["tile_entity_count"] = int(match.group(1))

    match = re.search(r"Async queues:\s*(.+)", text)
    if match:
        for name in ("explosion", "path"):
            executor = re.search(
                rf"{name}=(\d+)/(\d+) active=(\d+) rejected=(\d+)", match.group(1)
            )
            if executor:
                data[f"async_{name}_queued"] = int(executor.group(1))
                data[f"async_{name}_capacity"] = int(executor.group(2))
                data[f"async_{name}_active"] = int(executor.group(3))
                data[f"async_{name}_rejected"] = int(executor.group(4))
            elif re.search(rf"{name}=off", match.group(1)):
                data[f"async_{name}_rejected"] = 0
                data[f"async_{name}_enabled"] = 0

    if not any(key.startswith("tps_") for key in data) and "tick_p99_ms" not in data:
        raise ValueError("/tps response did not contain TPS or tick duration fields")
    return data


def parse_windypit_tps(raw: str) -> dict[str, Any]:
    """Parse the ``/windypit-tps`` scheduler diagnostics response."""
    text = strip_colors(raw)
    data: dict[str, Any] = {}

    match = re.search(
        r"TPS:\s*1m=([0-9.]+)\s+5m=([0-9.]+)\s+15m=([0-9.]+)\s*\|\s*MSPT:\s*([0-9.]+)", text
    )
    if match:
        data["wp_tps_1m"] = float(match.group(1))
        data["wp_tps_5m"] = float(match.group(2))
        data["wp_tps_15m"] = float(match.group(3))
        data["wp_mspt"] = float(match.group(4))

    match = re.search(r"Threading mode:\s*(\S+)\s*\|\s*Arena owner:\s*(\S+)", text)

