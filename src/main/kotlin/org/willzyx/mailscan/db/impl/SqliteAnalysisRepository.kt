package org.willzyx.mailscan.db.impl

import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.willzyx.mailscan.db.AnalysisRepository
import org.willzyx.mailscan.db.AnalyzedEmail
import org.willzyx.mailscan.llm.Analysis
import org.willzyx.mailscan.llm.Importance

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

    override suspend fun between(account: String, from: Instant, until: Instant): List<AnalyzedEmail> =
        withContext(dispatcher) {
            connection
                .prepareStatement(
                    """
                    select uid_validity, uid, analyzed_at, sender, subject,
                           importance, summary, reason, action_required
                    from email_analysis
                    where account = ? and analyzed_at >= ? and analyzed_at < ?
                    order by analyzed_at, uid_validity, uid
                    """.trimIndent(),
                )
                .use { statement ->
                    statement.setString(1, account)
                    statement.setLong(2, from.epochSecond)
                    statement.setLong(3, until.epochSecond)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) add(email(rows))
                        }
                    }
                }
        }

    override suspend fun deleteBefore(time: Instant): Int =
        withContext(dispatcher) {
            connection.prepareStatement("delete from email_analysis where analyzed_at < ?").use { statement ->
                statement.setLong(1, time.epochSecond)
                statement.executeUpdate()
            }
        }

    override fun close() = connection.close()
}

/** The email in the current row of [rows], its columns in the order [SqliteAnalysisRepository.between] selects them. */
private fun email(rows: ResultSet): AnalyzedEmail =
    AnalyzedEmail(
        uidValidity = rows.getLong(1),
        uid = rows.getLong(2),
        analyzedAt = Instant.ofEpochSecond(rows.getLong(3)),
        sender = rows.getString(4),
        subject = rows.getString(5),
        analysis = Analysis(
            importance = Importance.valueOf(rows.getString(6)),
            summary = rows.getString(7),
            reason = rows.getString(8),
            actionRequired = rows.getBoolean(9),
        ),
    )
