package org.willzyx.mailscan.db.impl

import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.willzyx.mailscan.db.Cursor

private const val HOME = "home@example.com"
private const val WORK = "work@example.com"

/** 1 793 744 100 seconds since 1970. */
private val AT: Instant = Instant.parse("2026-11-03T22:15:00Z")

class SqliteDigestRepositoryTest {
    @TempDir
    lateinit var dir: Path

    private val file: Path get() = dir.resolve("mailscan.db")

    @Test
    fun `a mailbox that has had no digest has no time`() =
        runTest {
            openSqlite(file).use { db ->
                val digests = SqliteDigestRepository(db)
                assertNull(digests.get(HOME))
            }
        }

    @Test
    fun `the time is kept per mailbox and replaced by the next one`() =
        runTest {
            openSqlite(file).use { db ->
                val digests = SqliteDigestRepository(db)
                digests.set(HOME, AT)
                digests.set(WORK, AT.plusSeconds(5))
                digests.set(HOME, AT.plusSeconds(60))

                assertEquals(AT.plusSeconds(60), digests.get(HOME))
                assertEquals(AT.plusSeconds(5), digests.get(WORK))
            }
            assertEquals(
                listOf("$HOME | 1793744160", "$WORK | 1793744105"),
                query(file, "select * from email_digest order by account"),
            )
        }

    @Test
    fun `the time is kept to the second`() =
        runTest {
            openSqlite(file).use { db ->
                val digests = SqliteDigestRepository(db)
                digests.set(HOME, AT.plusMillis(999))
                assertEquals(AT, digests.get(HOME))
            }
        }

    @Test
    fun `the time survives closing and reopening the file`() =
        runTest {
            openSqlite(file).use { SqliteDigestRepository(it).set(HOME, AT) }
            openSqlite(file).use { assertEquals(AT, SqliteDigestRepository(it).get(HOME)) }
        }

    @Test
    fun `the times live in one file with the cursors, both written through one connection`() =
        runTest {
            openSqlite(file).use { db ->
                val cursors = SqliteCursorRepository(db)
                val digests = SqliteDigestRepository(db)
                for (n in 1L..3L) {
                    cursors.set(HOME, Cursor(uidValidity = 7, lastUid = n))
                    digests.set(HOME, AT.plusSeconds(n))
                }
                assertEquals(Cursor(uidValidity = 7, lastUid = 3), cursors.get(HOME))
                assertEquals(AT.plusSeconds(3), digests.get(HOME))
            }
        }
}
