package org.willzyx.mailscan.db.impl

import java.nio.file.Path
import java.sql.Connection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.willzyx.mailscan.db.AnalysisRepository
import org.willzyx.mailscan.db.AnalyzedEmail

/**
 * [AnalysisRepository] in the SQLite file at [path], one row of `email_analysis` per email. The file,
 * its directory and the table are created when missing; the file may be the one the cursors are in.
 * Close it when done.
 */
class SqliteAnalysisRepository(
    path: Path,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AnalysisRepository,
    AutoCloseable {
    private val connection: Connection = openSqlite(path)

    init {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                create table if not exists email_analysis (
                    account         text    not null,
                    uid_validity    integer not null,
                    uid             integer not null,
                    analyzed_at     integer not null, -- seconds since 1970, UTC
                    sender          text    not null,
                    subject         text    not null,
                    importance      text    not null, -- HIGH, MEDIUM or LOW
                    summary         text    not null,
                    reason          text    not null,
                    action_required integer not null, -- 0 or 1
                    primary key (account, uid_validity, uid)
                )
                """.trimIndent(),
            )
        }
    }

    override suspend fun save(account: String, email: AnalyzedEmail) {
        withContext(dispatcher) {
            connection
                .prepareStatement(
                    """
                    insert or replace into email_analysis
                        (account, uid_validity, uid, analyzed_at, sender, subject,
                         importance, summary, reason, action_required)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                )
                .use { statement ->
                    statement.setString(1, account)
                    statement.setLong(2, email.uidValidity)
                    statement.setLong(3, email.uid)
                    statement.setLong(4, email.analyzedAt.epochSecond)
                    statement.setString(5, email.sender)
                    statement.setString(6, email.subject)
                    statement.setString(7, email.analysis.importance.name)
                    statement.setString(8, email.analysis.summary)
                    statement.setString(9, email.analysis.reason)
                    statement.setBoolean(10, email.analysis.actionRequired)
                    statement.executeUpdate()
                }
        }
    }

    override fun close() = connection.close()
}
