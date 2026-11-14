package org.willzyx.mailscan.db.impl

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.willzyx.mailscan.db.Cursor

private const val HOME = "home@example.com"
private const val WORK = "work@example.com"

class SqliteCursorRepositoryTest {
    @TempDir
    lateinit var dir: Path

    private val file: Path get() = dir.resolve("mailscan.db")

    @Test
    fun `a mailbox that was never scanned has no cursor`() =
        runTest {
            SqliteCursorRepository(file).use { cursors ->
                assertNull(cursors.get(HOME))
            }
        }

    @Test
    fun `a cursor is kept per mailbox and replaced by the next one`() =
        runTest {
            SqliteCursorRepository(file).use { cursors ->
                cursors.set(HOME, Cursor(uidValidity = 7, lastUid = 100))
                cursors.set(WORK, Cursor(uidValidity = 3, lastUid = 5))
                cursors.set(HOME, Cursor(uidValidity = 8, lastUid = 2))

                assertEquals(Cursor(uidValidity = 8, lastUid = 2), cursors.get(HOME))
                assertEquals(Cursor(uidValidity = 3, lastUid = 5), cursors.get(WORK))
            }
            assertEquals(
                listOf("$HOME | 8 | 2", "$WORK | 3 | 5"),
                query(file, "select * from mailbox_cursor order by account"),
            )
        }

    @Test
    fun `a cursor survives closing and reopening the file`() =
        runTest {
            // UIDs are unsigned 32-bit numbers, so the largest one does not fit an Int.
            val cursor = Cursor(uidValidity = 4_294_967_295, lastUid = 4_294_967_294)
            SqliteCursorRepository(file).use { it.set(HOME, cursor) }
            SqliteCursorRepository(file).use { assertEquals(cursor, it.get(HOME)) }
        }

    @Test
    fun `the missing directory of the file is created`() =
        runTest {
            val nested = dir.resolve("data/deeper/mailscan.db")
            SqliteCursorRepository(nested).use { it.set(HOME, Cursor(1, 1)) }
            SqliteCursorRepository(nested).use { assertEquals(Cursor(1, 1), it.get(HOME)) }
        }

    @Test
    fun `the state table of the Python version is neither read nor touched`() =
        runTest {
            // The schema and the rows the Python version left in the database.
            jdbc(file) { execute("CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT NOT NULL)") }
            jdbc(file) { execute("INSERT INTO state (key, value) VALUES ('INBOX:uidvalidity', '7')") }
            jdbc(file) { execute("INSERT INTO state (key, value) VALUES ('INBOX:last_uid', '99')") }

            SqliteCursorRepository(file).use { cursors ->
                assertNull(cursors.get(HOME))
                cursors.set(HOME, Cursor(uidValidity = 7, lastUid = 100))
            }

            assertEquals(
                listOf("INBOX:last_uid | 99", "INBOX:uidvalidity | 7"),
                query(file, "select * from state order by key"),
            )
        }
}
