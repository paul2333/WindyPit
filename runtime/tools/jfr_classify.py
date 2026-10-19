def _event_document(document: dict[str, Any]) -> list[dict[str, Any]]:
    recording = document.get("recording", document)
    events = recording.get("events", []) if isinstance(recording, dict) else []
    if not isinstance(events, list):
        raise ClassifyError("JFR JSON must contain a recording.events array")
    return [event for event in events if isinstance(event, dict)]


def analyze(document: dict[str, Any], threshold_ms: float = DEFAULT_THRESHOLD_MS) -> dict[str, Any]:
    """Classify a JFR JSON document and return the gate payload plus event rows."""
    if threshold_ms <= 0:
        raise ValueError("threshold_ms must be positive")
    events = _event_document(document)
    rows: list[dict[str, Any]] = []
    hotspots: Counter[str] = Counter()
    hotspot_frames: dict[str, tuple[str, ...]] = {}
    category_counts: Counter[str] = Counter({category: 0 for category in CATEGORIES})
    owner_thread_count = 0
    execution_samples = 0
    duration_seen = 0
    excluded_jfr_internal = 0

    for event in events:
        event_type = str(event.get("type", ""))
        values = event.get("values", {})
        if not isinstance(values, dict):
            continue
        frames = stack_frames(values)
        thread = thread_name(values)

        if event_type in SAMPLE_EVENTS:
            if frames:
                execution_samples += 1
                digest = stack_hash(frames)
                hotspots[digest] += 1
                hotspot_frames.setdefault(digest, frames)
            continue
        if event_type not in DURATION_EVENTS:
            continue
        duration_seen += 1
        elapsed = duration_ms(values.get("duration"))
        if elapsed is None or elapsed < threshold_ms:
            continue
        if is_jfr_internal(thread, frames):
            excluded_jfr_internal += 1
            continue
        category = classify(event_type, thread, frames)
        category_counts[category] += 1
        owner = is_owner_thread(thread)
        if owner:
            owner_thread_count += 1
        rows.append(
            {
                "event_type": event_type,
                "category": category,
                "thread": thread,
                "duration_ms": round(elapsed, 3),
                "stack_hash": stack_hash(frames),
                "owner_thread": owner,
                "stack_depth": len(frames),
                "top_frame": frames[0] if frames else "",
                "start_time": str(values.get("startTime", "")),
            }
        )

    rows.sort(key=lambda row: row["duration_ms"], reverse=True)

    unexplained = [row for row in rows if row["category"] == "unexplained"]
    signatures: Counter[tuple[str, str]] = Counter(
        (row["event_type"], row["stack_hash"]) for row in unexplained
    )
    repeated_groups = [
        {
            "event_type": event_type,
            "stack_hash": digest,
            "count": count,
            "top_frame": next(
                (
                    row["top_frame"]
                    for row in unexplained
                    if row["event_type"] == event_type and row["stack_hash"] == digest
                ),
                "",
            ),
        }
        for (event_type, digest), count in signatures.items()
        if count >= 2
    ]
    repeated_groups.sort(key=lambda group: group["count"], reverse=True)
    unexplained_repeated = sum(group["count"] for group in repeated_groups)

    hotspot_rows = [
        {
            "stack_hash": digest,
            "count": count,
            "top_frame": hotspot_frames[digest][0] if hotspot_frames[digest] else "",
            "stack": " <- ".join(hotspot_frames[digest][:8]),
        }
        for digest, count in hotspots.most_common(20)
    ]

    gate = {
        "source": "",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "threshold_ms": threshold_ms,
        "event_count": len(events),
        "duration_event_count": duration_seen,
        "classified_count": len(rows),
        "excluded_jfr_internal": excluded_jfr_internal,
        "categories": {category: category_counts[category] for category in CATEGORIES},
        "unexplained_total": len(unexplained),
        "unexplained_repeated": unexplained_repeated,
        "unexplained_repeated_groups": repeated_groups,
        "owner_core_50ms_count": category_counts["owner_core_blocking"],
        "owner_thread_50ms_count": owner_thread_count,
        "execution_sample_count": execution_samples,
        "hotspot_note": (
            "jdk.ExecutionSample/jdk.NativeMethodSample are sampled stacks; "
            "hotspot counts are not durations and are excluded from every category."
        ),

