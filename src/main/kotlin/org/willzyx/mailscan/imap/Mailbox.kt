package org.willzyx.mailscan.imap

import java.time.LocalDate

/** The mail server cannot be reached or refused a request. */
class MailboxException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A mailbox to scan. An interface so the scanner can be tested without a mail server. */
interface Mailbox {
    /** Connects and opens the inbox for one pass over it. Close the session when done. */
    suspend fun open(): MailboxSession
}

/**
 * An open connection to the inbox. It only reads: nothing is marked as read, moved or deleted.
 * Every call throws a [MailboxException] when the server fails.
 */
interface MailboxSession : AutoCloseable {
    /** Changes when the server renumbers the emails: a UID kept from under another value means nothing. */
    suspend fun uidValidity(): Long

    /** The UIDs above [lastUid], in ascending order, which is the order the emails arrived in. */
    suspend fun uidsAfter(lastUid: Long): List<Long>

    /** The UIDs of the emails the server received on [day] or later, in ascending order. */
    suspend fun uidsSince(day: LocalDate): List<Long>

    /** The email as it was sent, or only its start when it is large; null if it has been deleted meanwhile. */
    suspend fun fetch(uid: Long): ByteArray?
}
