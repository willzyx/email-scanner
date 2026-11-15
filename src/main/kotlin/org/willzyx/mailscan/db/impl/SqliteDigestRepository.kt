package org.willzyx.mailscan.db.impl

import java.sql.Connection
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.willzyx.mailscan.db.DigestRepository

/**
 * [DigestRepository] in the SQLite database behind [connection], one row of `email_digest` per
 * mailbox. The table is created when missing. The connection stays the caller's to close.
 */
class SqliteDigestRepository(
    private val connection: Connection,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DigestRepository {
    init {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                create table if not exists email_digest (
                    account text    primary key,
                    sent_at integer not null -- seconds since 1970, UTC
                )
                """.trimIndent(),
            )
        }
    }

    override suspend fun get(account: String): Instant? =
        withContext(dispatcher) {
            connection.prepareStatement("select sent_at from email_digest where account = ?").use { statement ->
                statement.setString(1, account)
                statement.executeQuery().use { rows ->
                    if (rows.next()) Instant.ofEpochSecond(rows.getLong(1)) else null
                }
            }
        }

    override suspend fun set(account: String, sentAt: Instant) {
        withContext(dispatcher) {
            connection
                .prepareStatement("insert or replace into email_digest (account, sent_at) values (?, ?)")
                .use { statement ->
                    statement.setString(1, account)
                    statement.setLong(2, sentAt.epochSecond)
                    statement.executeUpdate()
                }
        }
    }
}
