package org.willzyx.mailscan.scan

import java.time.Clock
import java.time.LocalDate
import org.slf4j.LoggerFactory
import org.willzyx.mailscan.db.Cursor
import org.willzyx.mailscan.db.CursorRepository
import org.willzyx.mailscan.imap.Mailbox
import org.willzyx.mailscan.imap.MailboxSession
import org.willzyx.mailscan.llm.EmailAnalyzer
import org.willzyx.mailscan.llm.Importance
import org.willzyx.mailscan.mail.parseEmail
import org.willzyx.mailscan.telegram.TelegramClient
import org.willzyx.mailscan.telegram.emailText

/**
 * Goes over the new emails of [mailbox], the mailbox of [account], oldest first: has [analyzer] rate
 * each one, sends the ones that matter to [telegram], and remembers in [cursors] how far it got.
 * [clock] tells what day it is, in the user's time zone.
 */
class Scanner(
    val account: String,
    private val mailbox: Mailbox,
    private val cursors: CursorRepository,
    private val analyzer: EmailAnalyzer,
    private val telegram: TelegramClient,
    private val clock: Clock,
) {
    /**
     * One pass over the new emails, at most [MAX_PER_PASS] of them. Returns whether more are waiting:
     * a backlog is then worked off pass by pass instead of holding one connection for an hour.
     *
     * When the mailbox, the model or Telegram fails, the exception goes to the caller with the cursor
     * on the last email that was finished, so the next pass starts with the one that failed.
     */
    suspend fun scan(): Boolean =
        mailbox.open().use { session ->
            val uidValidity = session.uidValidity()
            val uids = newUids(session, uidValidity)
            val batch = uids.take(MAX_PER_PASS)
            for (uid in batch) {
                handle(uid, session.fetch(uid))
                // Saved after each email: a pass that fails halfway is picked up where it stopped.
                cursors.set(account, Cursor(uidValidity, uid))
            }
            uids.size > batch.size
        }

    /**
     * The emails after the saved cursor. Without a cursor, on the first pass, or with one that the
     * server has made void by renumbering the mailbox, the emails of today and yesterday are taken instead.
     */
    private suspend fun newUids(session: MailboxSession, uidValidity: Long): List<Long> {
        val cursor = cursors.get(account)
        if (cursor?.uidValidity == uidValidity) return session.uidsAfter(cursor.lastUid)
        if (cursor != null) {
            log.warn(
                "{}: UIDVALIDITY changed ({} → {}), reading the last day of emails again",
                account,
                cursor.uidValidity,
                uidValidity,
            )
        }
        return session.uidsSince(LocalDate.now(clock).minusDays(FIRST_PASS_DAYS_BACK))
    }

    /** Rates the email [raw] and reports it unless it is of low importance. The log tells nothing of its content. */
    private suspend fun handle(uid: Long, raw: ByteArray?) {
        if (raw == null) {
            log.info("{}: UID {}: already deleted", account, uid)
            return
        }
        val mail =
            try {
                parseEmail(raw)
            } catch (e: Exception) {
                // An email nobody can read must not stop the scanner at it for good.
                log.error("{}: UID {}: could not read the email ({}), skipped", account, uid, e.javaClass.simpleName)
                return
            }
        val analysis = analyzer.analyze(mail)
        log.info("{}: UID {}: {}", account, uid, analysis.importance)
        if (analysis.importance != Importance.LOW) {
            telegram.sendMessage(emailText(mail, analysis))
            log.info("{}: UID {}: sent to Telegram", account, uid)
        }
    }

    companion object {
        const val MAX_PER_PASS = 12
        private const val FIRST_PASS_DAYS_BACK = 1L
        private val log = LoggerFactory.getLogger(Scanner::class.java)
    }
}
