"""SQLite with one table: where the scan stopped in each folder. Nothing about the emails is stored."""
import sqlite3

SCHEMA = """
CREATE TABLE IF NOT EXISTS state (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""


class Storage:
    def __init__(self, path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path, timeout=30)
        self.db.executescript(SCHEMA)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def close(self):
        self.db.close()

    def get_state(self, key):
        row = self.db.execute("SELECT value FROM state WHERE key = ?", (key,)).fetchone()
        return row[0] if row else None

    def _set_state(self, key, value):
        self.db.execute("INSERT OR REPLACE INTO state (key, value) VALUES (?, ?)", (key, str(value)))

    def cursor(self, folder):
        """(uidvalidity, last_uid), or None if the folder has never been scanned."""
        uidvalidity, last_uid = self.get_state(f"{folder}:uidvalidity"), self.get_state(f"{folder}:last_uid")
        if uidvalidity is None or last_uid is None:
            return None
        return int(uidvalidity), int(last_uid)

    def advance(self, folder, uidvalidity, last_uid):
        with self.db:
            self._set_state(f"{folder}:uidvalidity", uidvalidity)
            self._set_state(f"{folder}:last_uid", last_uid)
