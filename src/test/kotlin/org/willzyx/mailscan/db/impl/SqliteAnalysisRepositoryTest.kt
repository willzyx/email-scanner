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
            SqliteAnalysisRepository(file).use { analyses ->
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
            SqliteAnalysisRepository(file).use { analyses ->
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
            SqliteAnalysisRepository(file).use { analyses ->
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
    fun `the records survive closing and reopening the file`() =
        runTest {
            SqliteAnalysisRepository(file).use { it.save(HOME, email(1, Importance.LOW)) }
            SqliteAnalysisRepository(file).use { it.save(HOME, email(2, Importance.LOW)) }
            assertEquals(2, rows().size)
        }

    @Test
    fun `the records live in one file with the cursors, each written through its own connection`() =
        runTest {
            SqliteCursorRepository(file).use { cursors ->
                SqliteAnalysisRepository(file).use { analyses ->
                    for (uid in 1L..3L) {
                        analyses.save(HOME, email(uid, Importance.LOW))
                        cursors.set(HOME, Cursor(uidValidity = 7, lastUid = uid))
                    }
                    assertEquals(Cursor(uidValidity = 7, lastUid = 3), cursors.get(HOME))
                }
            }
            assertEquals(3, rows().size)
        }
}
