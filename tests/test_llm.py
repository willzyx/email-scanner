import json
import urllib.error

import pytest

from mailscan.llm import Analysis, LLMUnavailable, Ollama, to_analysis
from mailscan.parser import ParsedEmail

MAIL = ParsedEmail("<1@x.example>", "Anya", "anya@x.example", "Photos from the cottage", "Tue, 03 Nov 2026",
                   "Uploaded the photos.")
ANSWER = {"importance": "medium", "summary": "Anya sent photos from the cottage.",
          "reason": "An email from a person, no reply needed", "action_required": False}


class FakeOllama:
    def __init__(self, answer=ANSWER, capabilities=("completion", "thinking")):
        self.answer = answer if isinstance(answer, str) else json.dumps(answer, ensure_ascii=False)
        self.capabilities = capabilities
        self.calls = []

    def __call__(self, url, payload, timeout):
        self.calls.append((url, payload, timeout))
        if url.endswith("/api/show"):
            return {"capabilities": list(self.capabilities)}
        return {"message": {"content": self.answer}}


def ollama(fake, **kw):
    return Ollama("http://ollama:11434/", "gemma4:e4b", "1m", http=fake, **kw)


def test_payload_and_answer():
    fake = FakeOllama()
    assert ollama(fake).analyze(MAIL) == Analysis(**ANSWER)
    url, payload, timeout = fake.calls[-1]
    assert url == "http://ollama:11434/api/chat" and timeout == 600
    assert payload["think"] is False and payload["keep_alive"] == "1m" and payload["stream"] is False
    assert payload["format"]["required"] == ["importance", "summary", "reason", "action_required"]
    assert payload["options"] == {"temperature": 0, "num_ctx": 4096}
    assert "in Russian" in payload["messages"][0]["content"]  # the default summary language
    assert payload["messages"][1]["content"].startswith("From: Anya <anya@x.example>\nSubject: Photos from the cottage\n")


def test_summary_language_goes_into_the_prompt():
    fake = FakeOllama()
    ollama(fake, language="Greek").analyze(MAIL)
    system = fake.calls[-1][1]["messages"][0]["content"]
    assert "in Greek" in system and "in Russian" not in system


def test_model_info_requested_once_per_process():
    fake = FakeOllama()
    llm = ollama(fake)
    llm.analyze(MAIL)
    llm.analyze(MAIL)
    assert [u.rsplit("/", 1)[1] for u, _, _ in fake.calls] == ["show", "chat", "chat"]


def test_no_think_flag_for_models_without_reasoning():
    fake = FakeOllama(capabilities=("completion",))
    ollama(fake).analyze(MAIL)
    assert "think" not in fake.calls[-1][1]


def test_truncated_summary_falls_back_to_reason():
    a = to_analysis({"importance": "low", "summary": "The company ",
                     "reason": "Looks like phishing: asks to pay a fee via a link", "action_required": False}, MAIL)
    assert a.summary.startswith("Looks like phishing") and a.importance == "low"


def test_short_summary_and_reason_fall_back_to_subject():
    assert to_analysis({"importance": "low", "summary": "", "reason": "x"}, MAIL).summary == "Subject: Photos from the cottage"


def test_broken_answer_is_rated_medium():
    a = ollama(FakeOllama(answer="not json")).analyze(MAIL)
    assert a.importance == "medium" and a.summary == "Subject: Photos from the cottage"


def test_unknown_importance_becomes_medium():
    assert to_analysis({**ANSWER, "importance": "urgent"}, MAIL).importance == "medium"


def test_unreachable_ollama():
    def down(url, payload, timeout):
        raise urllib.error.URLError(ConnectionRefusedError(111, "Connection refused"))

    with pytest.raises(LLMUnavailable):
        ollama(down).analyze(MAIL)
