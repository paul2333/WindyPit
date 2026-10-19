        "profileVersionBefore": document.get("profileVersionBefore"),
        "profileVersionAfter": document.get("profileVersionAfter"),
        "attempt": document.get("attempt"),
    }


def _asserted_types(assert_reserved_types: Sequence[str] | None) -> tuple[list[str], bool]:
    requested = [
        str(item).strip()
        for item in (assert_reserved_types or DEFAULT_ASSERT_TYPES)
        if str(item).strip()
    ]
    if not requested:
        requested = list(DEFAULT_ASSERT_TYPES)
    if any(item.lower() in ("all", "*") for item in requested):
        return ["all"], True
    return sorted(set(requested)), False


def build_report(
    db: Path,
    player_query: str | None,
    ledger_limit: int,
    assert_reserved_types: Sequence[str] | None = None,
) -> dict[str, Any]:
    if ledger_limit < 0:
        raise ValueError("ledger limit must not be negative")
    asserted_types, assert_all = _asserted_types(assert_reserved_types)
    connection = open_readonly(db)
    try:
        player_rows = load_json_rows(connection, "players")
        ledger_rows = load_json_rows(connection, "transaction_ledger")
        points_rows = load_json_rows(connection, "points") if "points" in table_names(connection) else []

        player: dict[str, Any] | None = None
        points: dict[str, Any] | None = None
        if player_query:
            found = find_player(player_rows, player_query)
            if found is None:
                raise LedgerError(f"player {player_query!r} was not found in the players table")
            player_id, document = found
            player = player_view(player_id, document)
            point_match = find_player(points_rows, player_id)
            if point_match is None:
                point_match = find_player(points_rows, player_query)
            if point_match is not None:
                point_document = point_match[1]
                points = {
                    "balance": point_document.get("balance"),
                    "source": point_document.get("source"),
                    "importedAt": point_document.get("importedAt"),
                }

        states: dict[str, int] = {}
        types: dict[str, int] = {}
        reserved = 0
        kill_reward_reserved = 0
        reserved_by_type: dict[str, int] = {}
        for _row_id, document in ledger_rows:
            state = str(document.get("state", "") or "")
            entry_type = str(document.get("type", "") or "")
            states[state] = states.get(state, 0) + 1
            types[entry_type] = types.get(entry_type, 0) + 1
            if state == "RESERVED":
                reserved += 1
                reserved_by_type[entry_type] = reserved_by_type.get(entry_type, 0) + 1
                if entry_type == "kill-reward":
                    kill_reward_reserved += 1

        reserved_by_type_lower = {key.lower(): count for key, count in reserved_by_type.items()}
        asserted_reserved = (
            reserved
            if assert_all
            else sum(reserved_by_type_lower.get(name.lower(), 0) for name in asserted_types)
        )

        ordered = sorted(
            ledger_rows,
            key=lambda row: (
                row[1].get("updatedAt") if isinstance(row[1].get("updatedAt"), (int, float)) else 0,
                row[0],
            ),
            reverse=True,
        )
        recent = [ledger_entry(row_id, document) for row_id, document in ordered[:ledger_limit]]

