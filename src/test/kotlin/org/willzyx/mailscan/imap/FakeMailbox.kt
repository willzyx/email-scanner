package org.willzyx.mailscan.imap

import java.time.LocalDate

/**
 * [Mailbox] that holds [emails] by UID; a null stands for an email deleted after it was listed.
 * Searches by date find every email, and [searchedSince] tells which days were asked for.
 */
class FakeMailbox(
    private val emails: Map<Long, ByteArray?>,
    private val uidValidity: Long = 1,
) : Mailbox {
    val searchedSince = mutableListOf<LocalDate>()
    var sessionsOpened = 0
        private set
    var sessionsClosed = 0
        private set

    override suspend fun open(): MailboxSession {
        sessionsOpened++
        return Session()
    }

    private inner class Session : MailboxSession {
        override suspend fun uidValidity(): Long = uidValidity

        override suspend fun uidsAfter(lastUid: Long): List<Long> = emails.keys.filter { it > lastUid }.sorted()

        override suspend fun uidsSince(day: LocalDate): List<Long> {
            searchedSince += day
            return emails.keys.sorted()
        }

        override suspend fun fetch(uid: Long): ByteArray? = emails[uid]

        override fun close() {
            sessionsClosed++
        }
    }
}
