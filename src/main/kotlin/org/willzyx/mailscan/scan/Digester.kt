package org.willzyx.mailscan.scan

import java.time.Clock
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import org.slf4j.LoggerFactory
import org.willzyx.mailscan.db.AnalysisRepository
import org.willzyx.mailscan.db.DigestRepository
import org.willzyx.mailscan.llm.DigestWriter
import org.willzyx.mailscan.telegram.TelegramClient
import org.willzyx.mailscan.telegram.digestText

/**
 * Sends [telegram] a digest of the mailbox of [account] at each of [times], the times of day by
 * [clock]: has [writer] retell the emails that [analyses] holds since the digest before, and
 * remembers in [digests] when it made this one.
 */
@Suppress("LongParameterList") // One parameter per thing the digester works with, and the one setting it has.
class Digester(
    private val account: String,
    private val analyses: AnalysisRepository,
    private val digests: DigestRepository,
    private val writer: DigestWriter,
    private val telegram: TelegramClient,
    private val clock: Clock,
    private val times: List<LocalTime>,
) {
    /**
     * Sends a digest when one of the times has come since the last digest, and does nothing
     * otherwise. The digest covers the emails rated since the last one and up to now; the first one
     * of a mailbox goes out at once, and covers the emails rated since the time before the last.
     * A period without emails is passed over in silence.
     *
     * When the database, the model or Telegram fails, the exception goes to the caller and the
     * digest stays due, so the next call makes it anew.
     */
    suspend fun sendIfDue() {
        // To the second, as the database keeps the time an email was rated and the time of a digest.
        val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val due = lastDigestTime(now, clock.zone, times)
        val last = digests.get(account)
        if (last != null && last >= due) return

        val from = last ?: lastDigestTime(due.minusSeconds(1), clock.zone, times)
        val emails = analyses.between(account, from, now)
        if (emails.isEmpty()) {
            log.info("{}: no emails for a digest", account)
        } else {
            val text = writer.write(emails)
            telegram.sendMessage(digestText(account, from.atZone(clock.zone), now.atZone(clock.zone), emails, text))
            log.info("{}: a digest of {} emails sent to Telegram", account, emails.size)
        }
        // After the message has gone out: a digest that Telegram does not take is made again.
        digests.set(account, now)
    }

    companion object {
        private val log = LoggerFactory.getLogger(Digester::class.java)
    }
}
