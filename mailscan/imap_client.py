"""Read-only IMAP: SELECT readonly and BODY.PEEK, so messages are never marked as read."""
import imaplib
import ssl

# strftime('%b') depends on the locale, while IMAP expects English month names
_MONTHS = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")


class IMAPError(Exception):
    pass


def _quote(folder):
    if any(c in folder for c in ' "()\\'):
        return '"' + folder.replace("\\", "\\\\").replace('"', '\\"') + '"'
    return folder


class Mailbox:
    def __init__(self, host, port, user, password, folder, fetch_max_bytes):
        self.host, self.port, self.user, self.password, self.folder = host, port, user, password, folder
        self.fetch_max_bytes = fetch_max_bytes
        self.conn = None
        self.uidvalidity = None
        self.uidnext = None

    def __enter__(self):
        try:
            # Without an explicit context imaplib doesn't verify the server certificate,
            # and the password could be intercepted
            self.conn = imaplib.IMAP4_SSL(self.host, self.port, ssl_context=ssl.create_default_context(), timeout=60)
            self.conn.login(self.user, self.password)
            typ, data = self.conn.select(_quote(self.folder), readonly=True)
            if typ != "OK":
                raise IMAPError(f"Could not open folder {self.folder}: {data}")
            self.uidvalidity = self._code("UIDVALIDITY")
            self.uidnext = self._code("UIDNEXT")
        except (imaplib.IMAP4.error, OSError) as e:
            self.__exit__()
            raise IMAPError(f"IMAP {self.host}: {e}") from e
        if self.uidvalidity is None:
            self.__exit__()
            raise IMAPError("The server did not report UIDVALIDITY")
        return self

    def __exit__(self, *exc):
        if self.conn is not None:
            try:
                self.conn.logout()
            except (imaplib.IMAP4.error, OSError):
                pass
            self.conn = None

    def _code(self, name):
        _, data = self.conn.response(name)
        return int(data[0]) if data and data[0] else None

    def _uid(self, *args):
        try:
            typ, data = self.conn.uid(*args)
        except (imaplib.IMAP4.error, OSError) as e:
            raise IMAPError(f"IMAP UID {args[0]}: {e}") from e
        if typ != "OK":
            raise IMAPError(f"IMAP UID {args[0]}: {data}")
        return data

    def uids_since(self, day):
        """UIDs of messages received on or after `day` (by the server's internal date)."""
        data = self._uid("SEARCH", None, "SINCE", f"{day.day:02d}-{_MONTHS[day.month - 1]}-{day.year}")
        return sorted(int(x) for x in (data[0] or b"").split()) if data else []

    def uids_after(self, last_uid):
        data = self._uid("SEARCH", None, "UID", f"{last_uid + 1}:*")
        # With nothing new, "N:*" still returns the last existing UID
        return sorted(u for u in (int(x) for x in (data[0] or b"").split()) if u > last_uid) if data else []

    def uids_last(self, count):
        """UIDs of the last `count` messages in the folder (by arrival, which is UID order)."""
        data = self._uid("SEARCH", None, "ALL")
        return sorted(int(x) for x in (data[0] or b"").split())[-count:] if data else []

    def fetch(self, uid):
        """First fetch_max_bytes bytes of the message, or None if it was deleted meanwhile."""
        for item in self._uid("FETCH", str(uid), f"(BODY.PEEK[]<0.{self.fetch_max_bytes}>)"):
            if isinstance(item, tuple):
                return item[1]
        return None
