import urllib.error

import pytest

from mailscan.telegram import Telegram, TelegramError

TOKEN = "123456:SECRET-token"
OK = (200, {"ok": True, "result": {}})


def test_send_html_message():
    calls = []
    Telegram(TOKEN, "42", http=lambda url, payload: calls.append((url, payload)) or OK).send("<b>hi</b>")
    url, payload = calls[0]
    assert url == f"https://api.telegram.org/bot{TOKEN}/sendMessage"
    assert payload["chat_id"] == "42" and payload["parse_mode"] == "HTML" and payload["text"] == "<b>hi</b>"


def test_token_never_in_network_error():
    def down(url, payload):
        raise urllib.error.URLError(f"cannot reach {url}")

    with pytest.raises(TelegramError) as e:
        Telegram(TOKEN, "42", http=down).send("x")
    assert "SECRET" not in str(e.value)


def test_api_error_is_reported():
    bad = (400, {"ok": False, "description": "Bad Request: chat not found"})
    with pytest.raises(TelegramError, match="chat not found"):
        Telegram(TOKEN, "42", http=lambda url, payload: bad).send("x")


def test_429_waits_and_retries():
    responses = [(429, {"ok": False, "parameters": {"retry_after": 3}}), OK]
    sleeps = []
    Telegram(TOKEN, "42", http=lambda url, payload: responses.pop(0), sleep=sleeps.append).send("x")
    assert sleeps == [4] and not responses
