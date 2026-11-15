package org.willzyx.mailscan.db

import java.time.Instant
import java.time.temporal.ChronoUnit

/** [DigestRepository] that lives as long as the test. */
class InMemoryDigestRepository : DigestRepository {
    private val sent = mutableMapOf<String, Instant>()

    override suspend fun get(account: String): Instant? = sent[account]

    override suspend fun set(account: String, sentAt: Instant) {
        sent[account] = sentAt.truncatedTo(ChronoUnit.SECONDS)
    }
}
