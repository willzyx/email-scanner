package org.willzyx.mailscan.db

import java.time.Instant

/**
 * When the last digest of each mailbox was made, kept across restarts. A mailbox is named by its
 * account, as in [CursorRepository].
 */
interface DigestRepository {
    /** When the last digest of the mailbox of [account] was made, or null if it has had none. */
    suspend fun get(account: String): Instant?

    /** Replaces the time of the last digest of the mailbox of [account]; [sentAt] is kept to the second. */
    suspend fun set(account: String, sentAt: Instant)
}
