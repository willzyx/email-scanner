"""Settings from environment variables. Things there is no reason to change are module constants."""
import os
from dataclasses import dataclass
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from .prompt import DEFAULT_LANGUAGE

ROOT = Path(__file__).resolve().parent.parent

IMAP_PORT = 993
IMAP_FOLDER = "INBOX"
OLLAMA_KEEP_ALIVE = "1m"
BODY_MAX_CHARS = 4000
# Fetch no more than this: the text is almost always at the start, and a 25 MB attachment
# would crash the container with its 256 MB memory limit
FETCH_MAX_BYTES = 4_000_000
MAX_PER_RUN = 12
INITIAL_LOOKBACK_DAYS = 1
# /app/data (the mailscan-data volume) in the container, ./data when run locally
DB_PATH = ROOT / "data" / "mailscan.db"


class ConfigError(Exception):
    pass


@dataclass(frozen=True)
class Settings:
    imap_host: str
    imap_user: str
    imap_password: str
    telegram_bot_token: str
    telegram_chat_id: str
    ollama_url: str
    ollama_model: str
    summary_language: str
    scan_interval_min: int
    tz: ZoneInfo

    def require(self, *names):
        missing = [n.upper() for n in names if not getattr(self, n)]
        if missing:
            raise ConfigError("Missing environment variables: " + ", ".join(missing))



def load_settings(env=None):
    env = os.environ if env is None else env
    get = lambda name, default="": (env.get(name) or default).strip()  # noqa: E731
    try:
        interval = int(get("SCAN_INTERVAL_MIN", "10"))
    except ValueError as e:
        raise ConfigError(f"SCAN_INTERVAL_MIN: expected a number of minutes, got '{get('SCAN_INTERVAL_MIN')}'") from e
    if interval < 1:
        raise ConfigError("SCAN_INTERVAL_MIN: the minimum is 1 minute")
    try:
        tz = ZoneInfo(get("TZ", "Asia/Nicosia"))
    except (ZoneInfoNotFoundError, ValueError) as e:
        raise ConfigError(f"TZ: unknown time zone '{get('TZ')}'") from e
    return Settings(
        imap_host=get("IMAP_HOST", "imap.gmail.com"),
        imap_user=get("IMAP_USER"),
        imap_password=get("IMAP_PASSWORD"),
        telegram_bot_token=get("TELEGRAM_BOT_TOKEN"),
        telegram_chat_id=get("TELEGRAM_CHAT_ID"),
        ollama_url=get("OLLAMA_URL", "http://localhost:11434"),
        ollama_model=get("OLLAMA_MODEL", "gemma4:e4b"),
        summary_language=get("SUMMARY_LANGUAGE", DEFAULT_LANGUAGE),
        scan_interval_min=interval,
        tz=tz,
    )
