"""Telegram texts (parse_mode=HTML)."""
from html import escape

# Telegram takes 4096 characters per message; with these caps the whole message stays under 2500
SENDER_MAX = 200
SUBJECT_MAX = 200
SUMMARY_MAX = 1000


def _e(text, limit=None):
    text = text or ""
    if limit and len(text) > limit:
        text = text[:limit - 1] + "…"
    return escape(text, quote=False)


def format_important(mail, analysis):
    lines = [
        "🔴 <b>Important email</b>",
        f"<b>From:</b> {_e(mail.sender, SENDER_MAX)}",
        f"<b>Subject:</b> {_e(mail.subject, SUBJECT_MAX)}",
        "",
        _e(analysis.summary, SUMMARY_MAX),
    ]
    tail = []
    if analysis.action_required:
        tail.append("⚡ Action required")
    if analysis.reason:
        tail.append(f"Why: {_e(analysis.reason, SUMMARY_MAX)}")
    if tail:
        lines.append(" · ".join(tail))
    return "\n".join(lines)
