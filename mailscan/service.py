"""Core logic: one pass over the mailbox (scan) and the scheduled run loop."""
import fcntl
import logging
import time
from contextlib import contextmanager
from datetime import datetime, timedelta

from . import config
from .formatting import format_important
from .imap_client import IMAPError
from .llm import LLMUnavailable
from .parser import parse_message
from .schedule import ERROR_RETRY, POLL_SECONDS, next_scan
from .telegram import TelegramError

log = logging.getLogger("mailscan")


class Busy(Exception):
    """Another scan is in progress (e.g. a manual command next to the run loop)."""


@contextmanager
def work_lock(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w") as f:
        try:
            fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise Busy("Another scan is already running") from None
        yield


def scan(settings, db, llm, tg, open_mailbox, today=None):
    """One pass over new emails: rate each one, send the important ones to Telegram right away.
    Returns whether emails are left in the mailbox for the next pass.

    IMAPError, LLMUnavailable and TelegramError propagate with the cursor on the last finished email,
    so the email that failed is retried next time.
    """
    folder = config.IMAP_FOLDER
    with open_mailbox() as box:
        cursor = db.cursor(folder)
        if cursor and cursor[0] == box.uidvalidity:
            uids = box.uids_after(cursor[1])
        else:
            if cursor:
                log.warning("UIDVALIDITY changed (%d → %d): re-reading the last day of emails",
                            cursor[0], box.uidvalidity)
            day = (today or datetime.now(settings.tz).date()) - timedelta(days=config.INITIAL_LOOKBACK_DAYS)
            uids = box.uids_since(day)
            if not uids and box.uidnext:
                db.advance(folder, box.uidvalidity, box.uidnext - 1)
        batch = uids[:config.MAX_PER_RUN]
        for uid in batch:
            raw = box.fetch(uid)
            if raw is None:
                log.info("UID %d: email already deleted", uid)
            else:
                try:
                    mail = parse_message(raw, config.BODY_MAX_CHARS)
                except Exception as e:  # noqa: BLE001 — one malformed email must not block the mailbox
                    log.error("UID %d: could not parse the email (%s), skipped", uid, type(e).__name__)
                    mail = None
                if mail is not None:
                    analysis = llm.analyze(mail)
                    log.info("UID %d: %s", uid, analysis.importance)
                    if analysis.importance == "high":
                        tg.send(format_important(mail, analysis))
                        log.info("UID %d: sent to Telegram", uid)
            db.advance(folder, box.uidvalidity, uid)
    return len(uids) > len(batch)


def check(llm, open_mailbox, count):
    """Rates the last `count` emails and prints the results. Sends nothing, the cursor stays put."""
    with open_mailbox() as box:
        for uid in box.uids_last(count):
            raw = box.fetch(uid)
            if raw is None:
                print(f"UID {uid}: already deleted", flush=True)
                continue
            try:
                mail = parse_message(raw, config.BODY_MAX_CHARS)
            except Exception as e:  # noqa: BLE001 — show the problem instead of stopping the check
                print(f"UID {uid}: could not parse the email ({type(e).__name__})", flush=True)
                continue
            a = llm.analyze(mail)
            action = " ⚡" if a.action_required else ""
            print(f"UID {uid} [{a.importance}{action}] {mail.sender} — {mail.subject}\n"
                  f"    {a.summary}\n    ↳ {a.reason}", flush=True)


def tick(settings, db, llm, tg, open_mailbox, now, next_at):
    """One iteration of the run loop. Returns the time of the next scan."""
    if now < next_at:
        return next_at
    backlog = scan(settings, db, llm, tg, open_mailbox)
    return next_scan(now, timedelta(minutes=settings.scan_interval_min), backlog)


def run(settings, db, llm, tg, open_mailbox, lock_path, sleep=time.sleep):
    tz = settings.tz
    log.info("mailscan started: scan every %d min (%s)", settings.scan_interval_min, tz.key)
    next_at = datetime.now(tz)
    paused_until = None
    while True:
        now = datetime.now(tz)
        if paused_until is None or now >= paused_until:
            paused_until = None
            try:
                with work_lock(lock_path):
                    next_at = tick(settings, db, llm, tg, open_mailbox, now, next_at)
            except Busy:
                log.info("A manual command is running, waiting")
            except (IMAPError, LLMUnavailable, TelegramError) as e:
                log.error("%s — retrying in a minute", e)
                paused_until = next_at = now + ERROR_RETRY
            except Exception:  # noqa: BLE001 — one error must not kill the process
                log.exception("Unexpected error — retrying in a minute")
                paused_until = next_at = now + ERROR_RETRY
        sleep(POLL_SECONDS)
