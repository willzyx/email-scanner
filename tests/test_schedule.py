from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from mailscan.schedule import next_scan


def test_next_scan():
    now, interval = datetime(2026, 11, 4, 10, tzinfo=ZoneInfo("Asia/Nicosia")), timedelta(minutes=10)
    assert next_scan(now, interval, backlog=True) == now
    assert next_scan(now, interval, backlog=False) == now + interval
