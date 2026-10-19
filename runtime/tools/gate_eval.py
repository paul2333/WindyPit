        "# WindyPit acceptance gate",
        "",
        f"**Overall: {summary['overall']}**",
        "",
        f"- Generated: {summary['generated_at']}",
        f"- Thresholds: TPS mean >= {summary['thresholds']['tps_mean_min']:g}, "
        f"tick p99 <= {summary['thresholds']['tick_p99_max_ms']:g} ms, "
        "unexplained repeated == 0, empty recording == false, "
        "accepted-work rejected/dropped/failed == 0, kill-reward RESERVED == 0",
        f"- Counts: {summary['counts']['PASS']} PASS, {summary['counts']['FAIL']} FAIL, "
        f"{summary['counts']['PARTIAL']} PARTIAL",
        "",
        "## Inputs",
        "",
    ]
    for key, value in inputs.items():
        lines.append(f"- {key}: `{value}`" if value else f"- {key}: not provided")
    missing_inputs = summary.get("missing_inputs", [])
    if missing_inputs:
        lines.extend(["", "## Missing inputs (MISSING_INPUT)", ""])
        lines.extend(f"- {check_id}" for check_id in missing_inputs)
    lines.extend(
        [
            "",
            "## Checks",
            "",
            "| Check | Status | Missing input | Value | Threshold | Reason |",
            "|---|---|---|---|---|---|",
        ]
    )
    for check in summary["checks"]:
        missing = "yes" if check.get("missing_input") else ""
        lines.append(
            f"| {check['id']} | {check['status']} | {missing} | {_format_value(check['value'])} "
            f"| {check['threshold']} | {check['reason']} |"
        )
    if summary["reasons"]:
        lines.extend(["", "## Blocking reasons", ""])
        lines.extend(f"- {reason}" for reason in summary["reasons"])
    lines.append("")
    return "\n".join(lines)


def write_outputs(summary: dict[str, Any], inputs: dict[str, str | None], output: Path, report: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    report.parent.mkdir(parents=True, exist_ok=True)
    payload = {**summary, "inputs": inputs}
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    report.write_text(render_report(summary, inputs), encoding="utf-8")


def _self_test() -> int:
    metrics = {
        "samples": 21,
        "metrics": {
            "tps_1m": {"count": 21, "mean": 19.9, "min": 19.7, "max": 20.0, "p99_max": 19.95},
            "tick_p99_ms": {"count": 21, "mean": 20.0, "min": 10.0, "max": 42.0, "p99_max": 42.0},
            **{
                name: {"count": 21, "mean": 0.0, "min": 0.0, "max": 0.0, "p99_max": 0.0}
                for name, _label in ACCEPTED_WORK_METRICS

