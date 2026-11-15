package org.willzyx.mailscan.db.impl

import java.sql.Connection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.willzyx.mailscan.db.Cursor
import org.willzyx.mailscan.db.CursorRepository

/**
 * [CursorRepository] in the SQLite database behind [connection], one row of `mailbox_cursor` per
 * mailbox. The table is created when missing. The connection stays the caller's to close.
 */
class SqliteCursorRepository(
    private val connection: Connection,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CursorRepository {
    init {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                create table if not exists mailbox_cursor (
                    account      text    primary key,
                    uid_validity integer not null,
                    last_uid     integer not null
                )
                """.trimIndent(),
            )
        }
    }

    override suspend fun get(account: String): Cursor? =
        withContext(dispatcher) {
            connection
                .prepareStatement("select uid_validity, last_uid from mailbox_cursor where account = ?")
                .use { statement ->
                    statement.setString(1, account)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) Cursor(rows.getLong(1), rows.getLong(2)) else null
                    }
                }
        }

    override suspend fun set(account: String, cursor: Cursor) {
        withContext(dispatcher) {
            connection
                .prepareStatement(
                    "insert or replace into mailbox_cursor (account, uid_validity, last_uid) values (?, ?, ?)",
                )
                .use { statement ->
                    statement.setString(1, account)
                    statement.setLong(2, cursor.uidValidity)
                    statement.setLong(3, cursor.lastUid)
                    statement.executeUpdate()
                }
        }
    }
}
