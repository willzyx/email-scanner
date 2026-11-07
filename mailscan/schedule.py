"""Scheduling for the run loop: pure functions, testable without a clock."""
from datetime import timedelta

POLL_SECONDS = 30
ERROR_RETRY = timedelta(minutes=1)


def next_scan(now, interval, backlog):
    """Right away if the mailbox has a backlog, otherwise after `interval`."""
    return now if backlog else now + interval
