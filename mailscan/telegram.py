"""Telegram Bot API: sendMessage with parse_mode=HTML."""
import json
import time
import urllib.error
import urllib.request

API = "https://api.telegram.org"
TIMEOUT = 30
MAX_RETRY_AFTER = 60


class TelegramError(Exception):
    pass


def http_post(url, payload, timeout=TIMEOUT):
    """(HTTP status, JSON body). Telegram answers with JSON on errors too (400, 429)."""
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.load(resp)
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.load(e)
        except ValueError:
            return e.code, {}


class Telegram:
    def __init__(self, token, chat_id="", http=http_post, sleep=time.sleep):
        self.token = token
        self.chat_id = chat_id
        self.http = http
        self.sleep = sleep

    def _call(self, method, payload=None):
        # The URL contains the bot token: keep it out of error messages
        url = f"{API}/bot{self.token}/{method}"
        for attempt in range(3):
            try:
                status, data = self.http(url, payload or {})
            except (urllib.error.URLError, OSError, ValueError) as e:
                reason = str(getattr(e, "reason", e)).replace(self.token, "***")
                raise TelegramError(f"Telegram {method}: no connection ({reason})") from None
            if status == 429 and attempt < 2:
                retry_after = int(data.get("parameters", {}).get("retry_after", 5))
                self.sleep(min(retry_after, MAX_RETRY_AFTER) + 1)
                continue
            if not data.get("ok"):
                raise TelegramError(f"Telegram {method}: {status} {data.get('description', '')}".rstrip())
            return data["result"]
        raise TelegramError(f"Telegram {method}: too many requests")

    def send(self, text):
        """One message; formatting.format_important keeps it well under Telegram's 4096 characters."""
        self._call("sendMessage", {
            "chat_id": self.chat_id,
            "text": text,
            "parse_mode": "HTML",
            "link_preview_options": {"is_disabled": True},
        })

    def updates(self):
        return self._call("getUpdates")
