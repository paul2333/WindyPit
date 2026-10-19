import contextlib
import io
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

RUNTIME = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(RUNTIME / "tools"))
import verify_ledger
from verify_ledger import build_report, find_player, open_readonly

SCHEMA = """
CREATE TABLE players (id TEXT PRIMARY KEY NOT NULL, data TEXT NOT NULL CHECK(json_valid(data)));
CREATE TABLE points (id TEXT PRIMARY KEY NOT NULL, data TEXT NOT NULL CHECK(json_valid(data)));
CREATE TABLE transaction_ledger (id TEXT PRIMARY KEY NOT NULL, data TEXT NOT NULL CHECK(json_valid(data)));
"""

UUID = "11111111-1111-1111-1111-111111111111"


def create_database(path: Path, reserved: bool = True) -> None:
    connection = sqlite3.connect(path)
    connection.executescript(SCHEMA)
    connection.execute(
        "INSERT INTO players(id, data) VALUES (?, ?)",
        (
            UUID,
            json.dumps(
                {
                    "uuid": UUID,
                    "playerName": "PvP000",
                    "lowerName": "pvp000",
                    "coins": 123.5,
                    "experience": 456.0,
                    "kills": 7,
                    "deaths": 2,
                    "streakKills": 3,
                    "highestStreaks": 5,
                    "prestige": 1,
                }
            ),
        ),
    )
    connection.execute(
        "INSERT INTO points(id, data) VALUES (?, ?)",
        (UUID, json.dumps({"uuid": UUID, "balance": 42, "source": "test", "importedAt": 0})),
    )
    ledger = [
        ("tx-1", {"txId": "tx-1", "type": "kill-reward", "state": "COMMITTED", "payload": "{\"coins\":10}",
                  "recoveryOutcome": "NONE", "updatedAt": 1000}),
        ("tx-3", {"txId": "tx-3", "type": "market-buy", "state": "COMMITTED", "payload": "{}",
                  "recoveryOutcome": "NONE", "updatedAt": 3000}),
    ]
    if reserved:
        ledger.append(
            ("tx-2", {"txId": "tx-2", "type": "kill-reward", "state": "RESERVED", "payload": "{}",
                      "recoveryOutcome": "NONE", "updatedAt": 2000})
        )
    for row_id, document in ledger:
        connection.execute(
            "INSERT INTO transaction_ledger(id, data) VALUES (?, ?)", (row_id, json.dumps(document))
        )
    connection.commit()
    connection.close()


class LedgerReportTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.db = Path(self.temporary.name) / "thepit.sqlite"
        create_database(self.db)

    def tearDown(self):
        self.temporary.cleanup()

    def test_player_points_and_ledger_report(self):
        report = build_report(self.db, "PvP000", 10)
        player = report["player"]
        self.assertEqual(player["name"], "PvP000")
        self.assertEqual(player["coins"], 123.5)
        self.assertEqual(player["experience"], 456.0)
        self.assertEqual(player["kills"], 7)
        self.assertEqual(player["deaths"], 2)
        self.assertEqual(player["streak"], 3)
        self.assertEqual(report["points"]["balance"], 42)
        self.assertEqual(report["ledger"]["total"], 3)
        self.assertEqual(report["ledger"]["reserved"], 1)
        self.assertEqual(report["ledger"]["kill_reward_reserved"], 1)
        self.assertEqual(report["ledger"]["counts_by_state"]["COMMITTED"], 2)
        self.assertEqual(report["ledger"]["recent"][0]["txId"], "tx-3")
        self.assertEqual(report["ledger"]["recent"][1]["recoveryOutcome"], "NONE")
        self.assertFalse(report["assertions"]["no_reserved"])

    def test_lookup_by_uuid_without_dashes(self):
        report = build_report(self.db, UUID.replace("-", ""), 1)
        self.assertEqual(report["player"]["name"], "PvP000")
        self.assertEqual(len(report["ledger"]["recent"]), 1)

    def test_unknown_player_is_an_error(self):
        with self.assertRaises(verify_ledger.LedgerError):
            build_report(self.db, "MissingPlayer", 5)

    def test_readonly_connection_rejects_writes(self):
        connection = open_readonly(self.db)
        try:
            with self.assertRaises(sqlite3.OperationalError):
                connection.execute("DELETE FROM players")
        finally:
            connection.close()

    def test_find_player_matches_name_case_insensitively(self):
        connection = open_readonly(self.db)
        try:
            rows = verify_ledger.load_json_rows(connection, "players")
        finally:
            connection.close()
        self.assertIsNotNone(find_player(rows, "pvp000"))
        self.assertIsNone(find_player(rows, "someone-else"))


class CommandLineTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.db = Path(self.temporary.name) / "thepit.sqlite"
        create_database(self.db)

    def tearDown(self):
        self.temporary.cleanup()

    def test_assert_no_reserved_exits_2(self):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()) as errors:
            code = verify_ledger.main(["--db", str(self.db), "--player", "PvP000", "--assert-no-reserved"])
        self.assertEqual(code, 2)
        self.assertIn("RESERVED", errors.getvalue())

    def test_clean_ledger_passes_assertion(self):
        clean = Path(self.temporary.name) / "clean.sqlite"
        create_database(clean, reserved=False)
        with contextlib.redirect_stdout(io.StringIO()):
            code = verify_ledger.main(["--db", str(clean), "--assert-no-reserved", "--json"])
        self.assertEqual(code, 0)

    def test_json_output_is_machine_readable(self):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            code = verify_ledger.main(["--db", str(self.db), "--player", "PvP000", "--json"])
        self.assertEqual(code, 0)
        document = json.loads(output.getvalue())
        self.assertEqual(document["player"]["kills"], 7)
        self.assertEqual(document["ledger"]["reserved"], 1)

    def test_output_file_is_utf8_json(self):
        destination = Path(self.temporary.name) / "ledger.json"
        with contextlib.redirect_stdout(io.StringIO()):
            code = verify_ledger.main(
                ["--db", str(self.db), "--player", "PvP000", "--json", "--output", str(destination)]
            )
        self.assertEqual(code, 0)
        document = json.loads(destination.read_text(encoding="utf-8"))
        self.assertEqual(document["player"]["coins"], 123.5)

    def test_missing_database_is_non_zero(self):
        with contextlib.redirect_stderr(io.StringIO()):
            code = verify_ledger.main(["--db", str(Path(self.temporary.name) / "missing.sqlite")])
        self.assertEqual(code, 1)


if __name__ == "__main__":
    unittest.main()

