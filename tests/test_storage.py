from mailscan.storage import Storage


def test_cursor(tmp_path):
    with Storage(tmp_path / "db") as db:
        assert db.cursor("INBOX") is None
        db.advance("INBOX", 7, 10)
        assert db.cursor("INBOX") == (7, 10)
        db.advance("INBOX", 8, 1)
        assert db.cursor("INBOX") == (8, 1)
        assert db.cursor("Other") is None


def test_cursor_survives_reopen(tmp_path):
    with Storage(tmp_path / "db") as db:
        db.advance("INBOX", 7, 10)
    with Storage(tmp_path / "db") as db:
        assert db.cursor("INBOX") == (7, 10)
