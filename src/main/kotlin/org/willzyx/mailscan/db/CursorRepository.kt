package org.willzyx.mailscan.db

/** Where the scan of a mailbox stopped: the last UID handled, good only while its inbox keeps [uidValidity]. */
data class Cursor(val uidValidity: Long, val lastUid: Long)

/**
 * The scan position of each mailbox, kept across restarts. A mailbox is named by its account: the
 * address it is logged in to with. Nothing about the emails themselves is stored.
 */
interface CursorRepository {
    /** The cursor of the mailbox of [account], or null if it has never been scanned. */
    suspend fun get(account: String): Cursor?

    /** Replaces the cursor of the mailbox of [account]. */
    suspend fun set(account: String, cursor: Cursor)
}
