import ssl
from datetime import date

import pytest

from mailscan.imap_client import IMAPError, Mailbox, _quote


class FakeConn:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def uid(self, *args):
        self.calls.append(args)
        return self.responses.pop(0)


def box_with(*responses):
    box = Mailbox("imap.example", 993, "user", "pass", "INBOX", fetch_max_bytes=1000)
    box.conn = FakeConn(responses)
    return box


def test_uids_after_ignores_last_existing_uid():
    box = box_with(("OK", [b"5"]))
    assert box.uids_after(5) == []
    assert box.conn.calls == [("SEARCH", None, "UID", "6:*")]


def test_uids_after_returns_new_sorted():
    assert box_with(("OK", [b"8 6 7"])).uids_after(5) == [6, 7, 8]


def test_uids_since_uses_english_month():
    box = box_with(("OK", [b""]))
    assert box.uids_since(date(2026, 11, 3)) == []
    assert box.conn.calls == [("SEARCH", None, "SINCE", "03-Nov-2026")]


def test_uids_last_takes_the_newest():
    box = box_with(("OK", [b"3 1 2 9 5"]))
    assert box.uids_last(2) == [5, 9]
    assert box.conn.calls == [("SEARCH", None, "ALL")]
    assert box_with(("OK", [b""])).uids_last(2) == []


def test_fetch_is_partial_and_read_only():
    box = box_with(("OK", [(b"1 (UID 6 BODY[]<0> {3}", b"abc"), b")"]))
    assert box.fetch(6) == b"abc"
    assert box.conn.calls == [("FETCH", "6", "(BODY.PEEK[]<0.1000>)")]


def test_fetch_deleted_message():
    assert box_with(("OK", [None])).fetch(6) is None


def test_server_error():
    with pytest.raises(IMAPError):
        box_with(("NO", [b"failure"])).uids_after(1)


def test_connection_verifies_server_certificate(monkeypatch):
    seen = {}

    class FakeIMAP:
        def __init__(self, host, port, ssl_context=None, timeout=None):
            seen["ctx"] = ssl_context

        def login(self, user, password):
            pass

        def select(self, folder, readonly=False):
            seen["readonly"] = readonly
            return "OK", [b"3"]

        def response(self, code):
            return code, [b"42" if code == "UIDVALIDITY" else b"100"]

        def logout(self):
            pass

    monkeypatch.setattr("mailscan.imap_client.imaplib.IMAP4_SSL", FakeIMAP)
    with Mailbox("imap.example", 993, "user", "pass", "INBOX", 1000) as box:
        assert (box.uidvalidity, box.uidnext) == (42, 100)
    assert seen["readonly"] is True
    assert seen["ctx"].verify_mode == ssl.CERT_REQUIRED and seen["ctx"].check_hostname


def test_quote_folder():
    assert _quote("INBOX") == "INBOX"
    assert _quote("My Folder") == '"My Folder"'
