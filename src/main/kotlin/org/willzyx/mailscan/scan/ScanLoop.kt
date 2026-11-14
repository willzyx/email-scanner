package org.willzyx.mailscan.scan

import java.time.Clock
import java.time.Duration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.willzyx.mailscan.db.AnalysisRepository
import org.willzyx.mailscan.imap.MailboxException
import org.willzyx.mailscan.llm.LlmUnavailableException
import org.willzyx.mailscan.telegram.TelegramException

/**
 * Scans the mailboxes of [scanners] one after another, round after round: one email is in work at
 * a time, however many mailboxes there are. Each round starts with deleting from [analyses] the
 * emails rated more than a week ago by [clock].
 */
class ScanLoop(
    private val scanners: List<Scanner>,
    private val analyses: AnalysisRepository,
    private val clock: Clock,
) {
    /**
     * Makes rounds until the coroutine is cancelled: the next one at once while some mailbox has a
     * backlog, and after a pause of [interval] when none has. A pass that fails is logged, whatever
     * went wrong, and the round goes on to the next mailbox: one bad pass must not stop the scanner.
     * The round after it comes in a minute. The same goes for a deletion that fails.
     */
    suspend fun run(interval: Duration): Nothing {
        while (true) {
            var backlog = false
            var failed = !deleteOldEmails()
            for (scanner in scanners) {
                try {
                    if (scanner.scan()) backlog = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    when (e) {
                        // What goes wrong outside and passes by itself: the message says all there is to say.
                        is MailboxException, is LlmUnavailableException, is TelegramException ->
                            log.error("{}: {}; trying again in a minute", scanner.account, e.message)
                        else -> log.error("{}: unexpected error; trying again in a minute", scanner.account, e)
                    }
                    failed = true
                }
            }
            val pause =
                when {
                    failed -> PAUSE_AFTER_FAILURE
                    backlog -> Duration.ZERO
                    else -> interval
                }
            delay(pause.toKotlinDuration())
        }
    }

    /**
     * Deletes the emails rated more than [KEEP_EMAILS] ago, of whatever mailbox, one that is no longer
     * scanned included. Returns whether that worked.
     */
    private suspend fun deleteOldEmails(): Boolean =
        try {
            val deleted = analyses.deleteBefore(clock.instant() - KEEP_EMAILS)
            if (deleted > 0) log.info("Deleted {} emails rated over {} days ago", deleted, KEEP_EMAILS.toDays())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Could not delete the old emails; trying again in a minute", e)
            false
        }

    companion object {
        /** How long a rated email is kept in the database. */
        val KEEP_EMAILS: Duration = Duration.ofDays(7)
        private val PAUSE_AFTER_FAILURE: Duration = Duration.ofMinutes(1)
        private val log = LoggerFactory.getLogger(ScanLoop::class.java)
    }
}
