"""SQLite checks for the recorded current Room v3 schema, not Android migration tests."""
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / "android/app/schemas/app.brucia.data.BruciaDb/3.json"


def create(db, schema):
    for entity in schema["entities"]:
        db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    for query in schema["setupQueries"]:
        db.execute(query)


class DatabaseSchemaTest(unittest.TestCase):
    def setUp(self):
        self.schema = json.loads(SCHEMA.read_text())["database"]

    def test_sql_matches_recorded_columns_nullability_and_primary_keys(self):
        with sqlite3.connect(":memory:") as db:
            create(db, self.schema)
            for entity in self.schema["entities"]:
                columns = {row[1]: row for row in db.execute(f'PRAGMA table_info("{entity["tableName"]}")')}
                self.assertEqual(set(columns), {field["columnName"] for field in entity["fields"]})
                for field in entity["fields"]:
                    row = columns[field["columnName"]]
                    self.assertEqual(row[2], field["affinity"])
                    self.assertEqual(bool(row[3]), field["notNull"])
                self.assertEqual([name for name, row in columns.items() if row[5]], entity["primaryKey"]["columnNames"])

    def test_reopening_current_schema_preserves_research_records(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "brucia.db"
            with sqlite3.connect(path) as db:
                create(db, self.schema)
                db.execute("PRAGMA user_version=3")
                db.execute("INSERT INTO ap VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", ("synthetic", "lab", 1, -42, "WPA2", None, None, 123, 0, 0, 0, 1))
                db.execute("INSERT INTO saved(type,name,subtitle,payload,timeMillis) VALUES(?,?,?,?,?)", ("rf", "fixture", "test", "capture", 123))
            with sqlite3.connect(path) as db:
                create(db, self.schema)
                self.assertEqual(db.execute("SELECT ssid,rssi FROM ap").fetchall(), [("lab", -42)])
                self.assertEqual(db.execute("SELECT name,payload FROM saved").fetchall(), [("fixture", "capture")])
                self.assertEqual(db.execute("SELECT identity_hash FROM room_master_table WHERE id=42").fetchone()[0], self.schema["identityHash"])

    def test_nullable_location_and_generated_saved_ids(self):
        with sqlite3.connect(":memory:") as db:
            create(db, self.schema)
            for _ in range(2):
                db.execute("INSERT INTO saved(type,name,subtitle,payload,timeMillis) VALUES('nfc','fixture','','data',123)")
            self.assertEqual(db.execute("SELECT id FROM saved ORDER BY id").fetchall(), [(1,), (2,)])
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute("INSERT INTO saved(type,name,subtitle,payload,timeMillis) VALUES(NULL,'fixture','','data',123)")


if __name__ == "__main__":
    unittest.main()
