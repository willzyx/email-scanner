package org.willzyx.mailscan.db.impl

import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.willzyx.mailscan.db.AnalyzedEmail
import org.willzyx.mailscan.db.Cursor
import org.willzyx.mailscan.llm.Analysis
import org.willzyx.mailscan.llm.Importance

private const val HOME = "home@example.com"
private const val WORK = "work@example.com"
private const val SENDER = "Anya <anya@x.example>"

/** 1 793 744 100 seconds since 1970. */
private val AT: Instant = Instant.parse("2026-11-03T22:15:00Z")

class SqliteAnalysisRepositoryTest {
    @TempDir
    lateinit var dir: Path

    private val file: Path get() = dir.resolve("mailscan.db")

    private fun email(uid: Long, importance: Importance, uidValidity: Long = 7, subject: String = "Letter $uid") =
        AnalyzedEmail(
            uidValidity = uidValidity,
            uid = uid,
            analyzedAt = AT.plusSeconds(uid),
            sender = SENDER,
            subject = subject,
            analysis = Analysis(importance, "Summary $uid", "Reason $uid", importance == Importance.HIGH),
        )

    private fun rows() = query(file, "select * from email_analysis order by account, uid_validity, uid")

    @Test
    fun `an email of any importance is kept, with all that is known of it`() =
        runTest {
            openSqlite(file).use { db ->
                val analyses = SqliteAnalysisRepository(db)
                analyses.save(HOME, email(1, Importance.HIGH))
                analyses.save(HOME, email(2, Importance.MEDIUM))
                analyses.save(HOME, email(3, Importance.LOW))
            }

            assertEquals(
                listOf(
                    "$HOME | 7 | 1 | 1793744101 | $SENDER | Letter 1 | HIGH | Summary 1 | Reason 1 | 1",
                    "$HOME | 7 | 2 | 1793744102 | $SENDER | Letter 2 | MEDIUM | Summary 2 | Reason 2 | 0",
                    "$HOME | 7 | 3 | 1793744103 | $SENDER | Letter 3 | LOW | Summary 3 | Reason 3 | 0",
                ),
                rows(),
            )
        }

    @Test
    fun `an email saved a second time replaces its first record`() =
        runTest {
            openSqlite(file).use { db ->
                val analyses = SqliteAnalysisRepository(db)
                analyses.save(HOME, email(1, Importance.LOW))
                analyses.save(HOME, email(1, Importance.HIGH, subject = "Rated again"))
            }

            assertEquals(
                listOf("$HOME | 7 | 1 | 1793744101 | $SENDER | Rated again | HIGH | Summary 1 | Reason 1 | 1"),
                rows(),
            )
        }

    @Test
    fun `the same UID in another mailbox, or after a renumbering, is another email`() =
        runTest {
            openSqlite(file).use { db ->
                val analyses = SqliteAnalysisRepository(db)
                analyses.save(HOME, email(1, Importance.LOW))
                analyses.save(WORK, email(1, Importance.LOW))
                analyses.save(HOME, email(1, Importance.LOW, uidValidity = 8))
            }

            assertEquals(
                listOf("$HOME 7 1", "$HOME 8 1", "$WORK 7 1"),
                rows().map { it.split(" | ").take(3).joinToString(" ") },
            )
        }

    @Test
    fun `the emails of a mailbox rated in a period are read back whole, in the order they were rated`() =
        runTest {
            openSqlite(file).use { db ->
                val analyses = SqliteAnalysisRepository(db)
                // Rated one, two, three and four seconds after AT; saved out of order.
                analyses.save(HOME, email(3, Importance.LOW))
                analyses.save(HOME, email(1, Importance.HIGH))
                analyses.save(WORK, email(2, Importance.LOW))
                analyses.save(HOME, email(4, Importance.MEDIUM))

                assertEquals(
                    listOf(email(1, Importance.HIGH), email(3, Importance.LOW), email(4, Importance.MEDIUM)),
                    analyses.between(HOME, AT, AT.plusSeconds(60)),
                )
                assertEquals(listOf(email(2, Importance.LOW)), analyses.between(WORK, AT, AT.plusSeconds(60)))
                assertEquals(emptyList(), analyses.between("nobody@example.com", AT, AT.plusSeconds(60)))
            }
        }

    @Test
    fun `an email rated at the moment two periods meet is in the later one only`() =
        runTest {
            openSqlite(file).use { db ->
                val analyses = SqliteAnalysisRepository(db)
                for (uid in 1L..3L) analyses.save(HOME, email(uid, Importance.LOW))
                val meet = AT.plusSeconds(2)

                assertEquals(listOf(1L), analyses.between(HOME, AT, meet).map { it.uid })
                assertEquals(listOf(2L, 3L), analyses.between(HOME, meet, AT.plusSeconds(60)).map { it.uid })
            }
        }

    @Test
    fun `the emails rated before a moment are deleted, of every mailbox, and counted`() =
        runTest {
            openSqlite(file).use { db ->
                val analyses = SqliteAnalysisRepository(db)
                // Rated one, two, three and four seconds after AT.
                analyses.save(HOME, email(1, Importance.HIGH))
                analyses.save(WORK, email(2, Importance.LOW))
                analyses.save(HOME, email(3, Importance.LOW))
                analyses.save(WORK, email(4, Importance.LOW))

                assertEquals(0, analyses.deleteBefore(AT.plusSeconds(1)))
                assertEquals(2, analyses.deleteBefore(AT.plusSeconds(3)))
                assertEquals(0, analyses.deleteBefore(AT.plusSeconds(3)))
            }

            assertEquals(listOf("$HOME 7 3", "$WORK 7 4"), rows().map { it.split(" | ").take(3).joinToString(" ") })
        }

    @Test
    fun `the records survive closing and reopening the file`() =
        runTest {
            openSqlite(file).use { SqliteAnalysisRepository(it).save(HOME, email(1, Importance.LOW)) }
            openSqlite(file).use { SqliteAnalysisRepository(it).save(HOME, email(2, Importance.LOW)) }
            assertEquals(2, rows().size)
        }

    @Test
    fun `the records live in one file with the cursors, both written through one connection`() =
        runTest {
            openSqlite(file).use { db ->
                val cursors = SqliteCursorRepository(db)
                val analyses = SqliteAnalysisRepository(db)
                for (uid in 1L..3L) {
                    analyses.save(HOME, email(uid, Importance.LOW))
                    cursors.set(HOME, Cursor(uidValidity = 7, lastUid = uid))
                }
                assertEquals(Cursor(uidValidity = 7, lastUid = 3), cursors.get(HOME))
            }
            assertEquals(3, rows().size)
        }
}
