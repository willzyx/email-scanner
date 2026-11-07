"""Ollama /api/chat: email importance and summary as schema-constrained JSON."""
import json
import urllib.error
import urllib.request
from dataclasses import dataclass

from .prompt import DEFAULT_LANGUAGE, SCHEMA, build_user_message, system_prompt

IMPORTANCE = ("high", "medium", "low")
MIN_SUMMARY_CHARS = 20
# The first email after idling also waits for the model to load, which is slow under swap
CHAT_TIMEOUT = 600
INFO_TIMEOUT = 30
NUM_CTX = 4096


class LLMUnavailable(Exception):
    """Ollama is down or didn't answer: stop the pass, the email is retried next time."""


@dataclass(frozen=True)
class Analysis:
    importance: str
    summary: str
    reason: str
    action_required: bool


def http_json(url, payload=None, timeout=INFO_TIMEOUT):
    """GET without payload, POST with it. Returns the parsed JSON response."""
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.load(resp)


def to_analysis(data, mail):
    """Normalizes the model's answer. A broken answer is rated medium, i.e. not sent."""
    if not isinstance(data, dict) or not data:
        return Analysis("medium", f"Subject: {mail.subject}", "Could not parse the model's answer", False)
    importance = data.get("importance") if data.get("importance") in IMPORTANCE else "medium"
    summary = str(data.get("summary") or "").strip()
    reason = str(data.get("reason") or "").strip()
    # A straight quote in the text can close the JSON string early, cutting the summary to a word or two
    if len(summary) < MIN_SUMMARY_CHARS:
        summary = reason if len(reason) >= MIN_SUMMARY_CHARS else f"Subject: {mail.subject}"
    return Analysis(importance, summary, reason, bool(data.get("action_required")))


class Ollama:
    def __init__(self, url, model, keep_alive, language=DEFAULT_LANGUAGE, http=http_json):
        self.url = url.rstrip("/")
        self.system_prompt = system_prompt(language)
        self.model = model
        self.keep_alive = keep_alive
        self.http = http
        self._thinking = None

    def _call(self, path, payload=None, timeout=INFO_TIMEOUT):
        try:
            return self.http(self.url + path, payload, timeout)
        except (urllib.error.URLError, OSError, ValueError) as e:
            raise LLMUnavailable(f"Ollama {path}: {getattr(e, 'reason', e)}") from e

    def _prepare(self):
        """Once per process: does the model support thinking."""
        if self._thinking is not None:
            return
        info = self._call("/api/show", {"model": self.model})
        self._thinking = "thinking" in info.get("capabilities", [])

    def _chat(self, schema, system, user):
        """One schema-constrained chat call. Returns the parsed JSON object, or None if the answer is broken."""
        self._prepare()
        payload = {
            "model": self.model,
            "stream": False,
            "format": schema,
            "keep_alive": self.keep_alive,
            "options": {"temperature": 0, "num_ctx": NUM_CTX},
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        }
        # Thinking on CPU costs minutes of extra generation, and the benchmark scored 20/20 without it
        if self._thinking:
            payload["think"] = False
        resp = self._call("/api/chat", payload, CHAT_TIMEOUT)
        try:
            data = json.loads(resp["message"]["content"])
        except (KeyError, TypeError, json.JSONDecodeError):
            return None
        return data if isinstance(data, dict) else None

    def analyze(self, mail):
        return to_analysis(self._chat(SCHEMA, self.system_prompt, build_user_message(mail)), mail)
