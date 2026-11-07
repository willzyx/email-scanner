"""End-to-end scan / tick scenarios: fake IMAP, model and Telegram over a real SQLite."""
from datetime import datetime

import pytest

from mailscan import config
from mailscan.config import load_settings
from mailscan.llm import Analysis, LLMUnavailable
from mailscan.service import check, scan, tick
from mailscan.storage import Storage
from mailscan.telegram import TelegramError

SETTINGS = load_settings({})


def eml(n, sender="anya@x.example"):
    return (f"From: Anya <{sender}>\r\nTo: me@example.com\r\nSubject: Letter {n}\r\n"
            f"Message-ID: <{n}@x.example>\r\nDate: Tue, 03 Nov 2026 10:00:00 +0200\r\n"
            f"Content-Type: text/plain; charset=utf-8\r\n\r\nEmail text {n}\r\n").encode()


class FakeBox:
    def __init__(self, messages, uidvalidity=1, uidnext=None):
        self.messages = messages
        self.uidvalidity = uidvalidity
        self.uidnext = uidnext or max(messages, default=0) + 1

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        pass

    def uids_since(self, day):
        return sorted(self.messages)

    def uids_last(self, count):
        return sorted(self.messages)[-count:]

    def uids_after(self, last_uid):
        return sorted(u for u in self.messages if u > last_uid)

    def fetch(self, uid):
        return self.messages.get(uid)


class FakeLLM:
    def __init__(self, importance=None, fail_on=()):
        self.importance = importance or {}
        self.fail_on = set(fail_on)
        self.seen = []

    def analyze(self, mail):
        self.seen.append(mail.subject)
        if mail.subject in self.fail_on:
            raise LLMUnavailable("Ollama down")
        return Analysis(self.importance.get(mail.subject, "low"), f"Summary: {mail.subject}", "reason", False)



class FakeTG:
    def __init__(self, fail=False):
        self.fail = fail
        self.sent = []

    def send(self, text):
        if self.fail:
            raise TelegramError("Telegram down")
        self.sent.append(text)


@pytest.fixture
def db(tmp_path):
    with Storage(tmp_path / "db") as storage:
        yield storage


def test_first_scan_sends_high_and_skips_the_rest(db):
    box, llm, tg = FakeBox({1: eml(1), 2: eml(2)}), FakeLLM({"Letter 1": "high"}), FakeTG()
    assert scan(SETTINGS, db, llm, tg, lambda: box) is False
    assert len(tg.sent) == 1 and "Letter 1" in tg.sent[0]
    assert db.cursor("INBOX") == (1, 2)

    assert scan(SETTINGS, db, llm, tg, lambda: box) is False  # a second pass changes nothing
    assert llm.seen == ["Letter 1", "Letter 2"] and len(tg.sent) == 1


def test_ollama_down_keeps_cursor_on_last_processed(db):
    box, llm = FakeBox({1: eml(1), 2: eml(2), 3: eml(3)}), FakeLLM(fail_on={"Letter 2"})
    with pytest.raises(LLMUnavailable):
        scan(SETTINGS, db, llm, FakeTG(), lambda: box)
    assert db.cursor("INBOX") == (1, 1)

    llm.fail_on.clear()
    scan(SETTINGS, db, llm, FakeTG(), lambda: box)
    assert db.cursor("INBOX") == (1, 3)
    assert llm.seen == ["Letter 1", "Letter 2", "Letter 2", "Letter 3"]


def test_telegram_down_retries_the_email_next_time(db):
    box, llm, tg = FakeBox({1: eml(1), 2: eml(2)}), FakeLLM({"Letter 2": "high"}), FakeTG(fail=True)
    with pytest.raises(TelegramError):
        scan(SETTINGS, db, llm, tg, lambda: box)
    assert db.cursor("INBOX") == (1, 1)  # stopped at the email that didn't go out

    tg.fail = False
    scan(SETTINGS, db, llm, tg, lambda: box)
    assert len(tg.sent) == 1 and "Letter 2" in tg.sent[0] and db.cursor("INBOX") == (1, 2)
    assert llm.seen == ["Letter 1", "Letter 2", "Letter 2"]


def test_backlog_is_processed_in_batches(db):
    box, llm = FakeBox({u: eml(u) for u in range(1, 16)}), FakeLLM()
    assert scan(SETTINGS, db, llm, FakeTG(), lambda: box) is True
    assert db.cursor("INBOX") == (1, config.MAX_PER_RUN)
    assert scan(SETTINGS, db, llm, FakeTG(), lambda: box) is False
    assert db.cursor("INBOX") == (1, 15) and len(llm.seen) == 15


def test_uidvalidity_change_rereads_the_last_day(db):
    llm = FakeLLM()
    scan(SETTINGS, db, llm, FakeTG(), lambda: FakeBox({1: eml(1)}))
    scan(SETTINGS, db, llm, FakeTG(), lambda: FakeBox({50: eml(1), 51: eml(2)}, uidvalidity=2))
    assert llm.seen == ["Letter 1", "Letter 1", "Letter 2"]
    assert db.cursor("INBOX") == (2, 51)


def test_empty_mailbox_on_first_run_sets_cursor(db):
    scan(SETTINGS, db, FakeLLM(), FakeTG(), lambda: FakeBox({}, uidnext=100))
    assert db.cursor("INBOX") == (1, 99)


def test_deleted_and_unparsable_letters_are_skipped(db, monkeypatch):
    def broken(raw, limit):
        raise ValueError("broken MIME")

    monkeypatch.setattr("mailscan.service.parse_message", broken)
    tg, llm = FakeTG(), FakeLLM()
    scan(SETTINGS, db, llm, tg, lambda: FakeBox({1: eml(1), 2: None}))
    assert not tg.sent and not llm.seen
    assert db.cursor("INBOX") == (1, 2)


def test_check_prints_the_last_emails_and_touches_nothing(db, capsys):
    box, llm = FakeBox({1: eml(1), 2: eml(2), 3: None, 4: eml(4)}), FakeLLM({"Letter 4": "high"})
    check(llm, lambda: box, 3)
    out = capsys.readouterr().out
    assert "UID 2 [low] Anya <anya@x.example> — Letter 2" in out and "Summary: Letter 2" in out
    assert "UID 3: already deleted" in out and "UID 4 [high]" in out and "Letter 1" not in out
    assert llm.seen == ["Letter 2", "Letter 4"] and db.cursor("INBOX") is None


def at(hour, minute=0, second=0):
    return datetime(2026, 11, 4, hour, minute, second, tzinfo=SETTINGS.tz)


def test_tick_scans_when_due(db):
    box, tg = FakeBox({1: eml(1)}), FakeTG()
    next_at = tick(SETTINGS, db, FakeLLM({"Letter 1": "high"}), tg, lambda: box, at(9), at(9))
    assert len(tg.sent) == 1 and "Letter 1" in tg.sent[0]
    assert next_at == at(9, 10)
    assert tick(SETTINGS, db, FakeLLM(), tg, lambda: box, at(9, 5), next_at) == next_at  # not due yet
    assert len(tg.sent) == 1


def test_tick_lets_errors_reach_the_run_loop(db):
    with pytest.raises(TelegramError):
        tick(SETTINGS, db, FakeLLM({"Letter 1": "high"}), FakeTG(fail=True), lambda: FakeBox({1: eml(1)}), at(9), at(9))
