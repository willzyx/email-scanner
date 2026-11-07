from mailscan.formatting import SUBJECT_MAX, format_important
from mailscan.llm import Analysis
from mailscan.parser import ParsedEmail


def mail(**kw):
    base = dict(message_id="<1@x.example>", from_name="Bank <North>", from_addr="a@b.example",
                subject="Subject & <tag>", date="Tue, 03 Nov 2026", body="text")
    return ParsedEmail(**{**base, **kw})


def analysis(**kw):
    return Analysis(**{**dict(importance="high", summary="Summary", reason="Reason", action_required=True), **kw})


def test_important_escapes_html():
    text = format_important(mail(), analysis())
    assert text.startswith("🔴 <b>Important email</b>")
    assert "Bank &lt;North&gt; &lt;a@b.example&gt;" in text
    assert "Subject &amp; &lt;tag&gt;" in text
    assert "⚡ Action required · Why: Reason" in text


def test_important_without_name_reason_and_action():
    text = format_important(mail(from_name=""), analysis(reason="", action_required=False))
    assert "<b>From:</b> a@b.example" in text
    assert "Why" not in text and "⚡" not in text


def test_long_fields_keep_the_message_within_one_telegram_message():
    long = "<" * 5000
    text = format_important(mail(from_name=long, subject=long), analysis(summary=long, reason=long))
    from html import unescape
    assert len(unescape(text)) < 4096  # Telegram counts the text after parsing the HTML entities


def test_long_subject_is_truncated():
    text = format_important(mail(subject="я" * 500), analysis())
    assert "я" * (SUBJECT_MAX - 1) + "…" in text and "я" * SUBJECT_MAX not in text
