package org.willzyx.mailscan.scan

import java.time.Duration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.willzyx.mailscan.imap.MailboxException
import org.willzyx.mailscan.llm.LlmUnavailableException
import org.willzyx.mailscan.telegram.TelegramException

/**
 * Scans the mailboxes of [scanners] one after another, round after round: one email is in work at
 * a time, however many mailboxes there are.
 */
class ScanLoop(private val scanners: List<Scanner>) {
    /**
     * Makes rounds until the coroutine is cancelled: the next one at once while some mailbox has a
     * backlog, and after a pause of [interval] when none has. A pass that fails is logged, whatever
     * went wrong, and the round goes on to the next mailbox: one bad pass must not stop the scanner.
     * The round after it comes in a minute.
     */
    suspend fun run(interval: Duration): Nothing {
        while (true) {
            var backlog = false
            var failed = false
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

    companion object {
        private val PAUSE_AFTER_FAILURE: Duration = Duration.ofMinutes(1)
        private val log = LoggerFactory.getLogger(ScanLoop::class.java)
    }
}
