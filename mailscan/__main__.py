"""python -m mailscan run | scan | check [-n N] | telegram-test | telegram-chat-id | status"""
import argparse
import logging
import signal
import sys

from . import config
from .config import ConfigError, load_settings
from .imap_client import IMAPError, Mailbox
from .llm import LLMUnavailable, Ollama
from .service import Busy, check, run, scan, work_lock
from .storage import Storage
from .telegram import Telegram, TelegramError

log = logging.getLogger("mailscan")

LOCK_PATH = config.DB_PATH.parent / "scan.lock"
IMAP_VARS = ("imap_user", "imap_password")
TELEGRAM_VARS = ("telegram_bot_token", "telegram_chat_id")


def _mailbox(s):
    return lambda: Mailbox(s.imap_host, config.IMAP_PORT, s.imap_user, s.imap_password, config.IMAP_FOLDER,
                           config.FETCH_MAX_BYTES)


def _ollama(s):
    return Ollama(s.ollama_url, s.ollama_model, config.OLLAMA_KEEP_ALIVE, s.summary_language)


def _telegram(s):
    return Telegram(s.telegram_bot_token, s.telegram_chat_id)


def cmd_run(s, args):
    s.require(*IMAP_VARS, *TELEGRAM_VARS)
    # Python is PID 1 in the container: without a SIGTERM handler docker stop would wait 10 s and kill it
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    with Storage(config.DB_PATH) as db:
        run(s, db, _ollama(s), _telegram(s), _mailbox(s), LOCK_PATH)


def cmd_scan(s, args):
    s.require(*IMAP_VARS, *TELEGRAM_VARS)
    with Storage(config.DB_PATH) as db, work_lock(LOCK_PATH):
        backlog = scan(s, db, _ollama(s), _telegram(s), _mailbox(s))
    if backlog:
        log.info("Only %d emails are taken per pass, the rest wait for the next scan", config.MAX_PER_RUN)


def cmd_check(s, args):
    s.require(*IMAP_VARS)
    check(_ollama(s), _mailbox(s), args.count)


def cmd_telegram_test(s, args):
    s.require(*TELEGRAM_VARS)
    _telegram(s).send("✅ mailscan: test message. The bot and TELEGRAM_CHAT_ID are set up correctly.")
    print("Sent")


def cmd_telegram_chat_id(s, args):
    s.require("telegram_bot_token")
    chats = {}
    for update in _telegram(s).updates():
        message = update.get("message") or update.get("edited_message") or {}
        if "chat" in message:
            chats[message["chat"]["id"]] = message["chat"]
    if not chats:
        print("Nobody has messaged the bot yet. Send it any message in Telegram and run the command again.")
        return 1
    for chat in chats.values():
        name = " ".join(filter(None, (chat.get("first_name"), chat.get("last_name")))) or chat.get("title", "")
        print(f"TELEGRAM_CHAT_ID={chat['id']}    # {chat.get('type')}: {name}")


def cmd_status(s, args):
    with Storage(config.DB_PATH) as db:
        cursor = db.cursor(config.IMAP_FOLDER)
        print(f"Cursor {config.IMAP_FOLDER}: " + (f"UIDVALIDITY {cursor[0]}, last UID {cursor[1]}" if cursor
                                                 else "not scanned yet"))


COMMANDS = {
    "run": cmd_run,
    "scan": cmd_scan,
    "check": cmd_check,
    "telegram-test": cmd_telegram_test,
    "telegram-chat-id": cmd_telegram_chat_id,
    "status": cmd_status,
}


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m mailscan",
                                 description="Home mail scanner: Gmail → local LLM → Telegram")
    sub = ap.add_subparsers(dest="command", required=True)
    sub.add_parser("run", help="run continuously on the schedule (the container command)")
    sub.add_parser("scan", help="one pass over new emails")
    p = sub.add_parser("check", help="rate the last N emails and print the results; sends nothing, moves nothing")
    p.add_argument("-n", "--count", type=int, default=5, help="how many of the latest emails (default: %(default)s)")
    sub.add_parser("telegram-test", help="send a test message")
    sub.add_parser("telegram-chat-id", help="show the chat_id of everyone who messaged the bot")
    sub.add_parser("status", help="where the scan stopped")
    args = ap.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        datefmt="%Y-%m-%d %H:%M:%S")
    try:
        return COMMANDS[args.command](load_settings(), args) or 0
    except ConfigError as e:
        log.error("%s", e)
        return 2
    except (Busy, IMAPError, LLMUnavailable, TelegramError) as e:
        log.error("%s", e)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
