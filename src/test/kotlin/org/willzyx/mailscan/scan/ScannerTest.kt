package org.willzyx.mailscan.scan

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.willzyx.mailscan.db.Cursor
import org.willzyx.mailscan.db.InMemoryCursorRepository
import org.willzyx.mailscan.imap.FakeMailbox
import org.willzyx.mailscan.imap.Mailbox
import org.willzyx.mailscan.imap.MailboxSession
import org.willzyx.mailscan.llm.FakeAnalyzer
import org.willzyx.mailscan.llm.Importance
import org.willzyx.mailscan.llm.LlmUnavailableException
import org.willzyx.mailscan.telegram.FakeTelegramClient
import org.willzyx.mailscan.telegram.TelegramException

private const val ACCOUNT = "me@example.com"

/** A quarter past midnight on 4 November in Nicosia, which is still 3 November in UTC. */
private val CLOCK = Clock.fixed(Instant.parse("2026-11-03T22:15:00Z"), ZoneId.of("Asia/Nicosia"))

class ScannerTest {
    private val cursors = InMemoryCursorRepository()
    private val telegram = FakeTelegramClient()

    private fun scanner(mailbox: Mailbox, analyzer: FakeAnalyzer = FakeAnalyzer(), account: String = ACCOUNT) =
        Scanner(account, mailbox, cursors, analyzer, telegram, CLOCK)

    private fun mailbox(uids: Iterable<Int>, uidValidity: Long = 1) =
        FakeMailbox(uids.associate { it.toLong() to eml(it) }, uidValidity)

    @Test
    fun `the first pass takes the emails of today and yesterday`() =
        runTest {
            val mailbox = mailbox(1..3)

            assertFalse(scanner(mailbox).scan())

            // Yesterday by the user's calendar, not by UTC.
            assertEquals(listOf(LocalDate.of(2026, 11, 3)), mailbox.searchedSince)
            assertEquals(Cursor(uidValidity = 1, lastUid = 3), cursors.get(ACCOUNT))
        }

    @Test
    fun `the next pass takes only what came after the cursor`() =
        runTest {
            scanner(mailbox(1..3)).scan()
            val grown = mailbox(1..5)

            assertFalse(scanner(grown).scan())

            assertEquals(emptyList(), grown.searchedSince)
            assertEquals(Cursor(uidValidity = 1, lastUid = 5), cursors.get(ACCOUNT))
        }

    @Test
    fun `a pass with nothing new leaves the cursor where it is`() =
        runTest {
            val mailbox = mailbox(1..3)
            scanner(mailbox).scan()

            assertFalse(scanner(mailbox).scan())

            assertEquals(Cursor(uidValidity = 1, lastUid = 3), cursors.get(ACCOUNT))
        }

    @Test
    fun `a backlog is worked off in passes of twelve emails`() =
        runTest {
            val mailbox = mailbox(1..15)

            assertTrue(scanner(mailbox).scan())
            assertEquals(Cursor(uidValidity = 1, lastUid = 12), cursors.get(ACCOUNT))

            assertFalse(scanner(mailbox).scan())
            assertEquals(Cursor(uidValidity = 1, lastUid = 15), cursors.get(ACCOUNT))
        }

    @Test
    fun `exactly twelve new emails leave no backlog`() =
        runTest {
            assertFalse(scanner(mailbox(1..12)).scan())
            assertEquals(Cursor(uidValidity = 1, lastUid = 12), cursors.get(ACCOUNT))
        }

    @Test
    fun `when the server renumbers the mailbox, the last day is read again`() =
        runTest {
            scanner(mailbox(1..3)).scan()
            val renumbered = mailbox(listOf(50, 51), uidValidity = 2)

            scanner(renumbered).scan()

            assertEquals(listOf(LocalDate.of(2026, 11, 3)), renumbered.searchedSince)
            assertEquals(Cursor(uidValidity = 2, lastUid = 51), cursors.get(ACCOUNT))
        }

    @Test
    fun `an empty mailbox gets no cursor`() =
        runTest {
            assertFalse(scanner(mailbox(emptyList())).scan())
            assertNull(cursors.get(ACCOUNT))
        }

    @Test
    fun `each mailbox has a cursor of its own`() =
        runTest {
            scanner(mailbox(1..3), account = "home@example.com").scan()
            scanner(mailbox(1..7), account = "work@example.com").scan()

            assertEquals(Cursor(uidValidity = 1, lastUid = 3), cursors.get("home@example.com"))
            assertEquals(Cursor(uidValidity = 1, lastUid = 7), cursors.get("work@example.com"))
        }

    @Test
    fun `the connection is closed after the pass`() =
        runTest {
            val mailbox = mailbox(1..3)
            scanner(mailbox).scan()
            assertEquals(1, mailbox.sessionsOpened)
            assertEquals(1, mailbox.sessionsClosed)
        }

    @Test
    fun `emails of high and medium importance go to Telegram, oldest first, and low ones do not`() =
        runTest {
            val analyzer = FakeAnalyzer(mapOf("Letter 1" to Importance.HIGH, "Letter 3" to Importance.MEDIUM))
            val mailbox = mailbox(1..3)

            scanner(mailbox, analyzer).scan()

            assertEquals(listOf("Letter 1", "Letter 2", "Letter 3"), analyzer.saw)
            assertEquals(2, telegram.sent.size)
            val (first, second) = telegram.sent
            assertEquals(
                """
                🔴 <b>Important email</b>
                <b>From:</b> Anya &lt;anya@x.example&gt;
                <b>Subject:</b> Letter 1

                Summary of Letter 1
                Why: reason
                """.trimIndent(),
                first,
            )
            assertEquals(listOf("🟡 <b>Email</b>", "<b>Subject:</b> Letter 3"), second.lines().slice(listOf(0, 2)))

            // A second pass has nothing to do: nobody is asked or told anything again.
            scanner(mailbox, analyzer).scan()
            assertEquals(3, analyzer.saw.size)
            assertEquals(2, telegram.sent.size)
        }

    @Test
    fun `when the model is down, the pass stops and the next one starts with the same email`() =
        runTest {
            val analyzer = FakeAnalyzer()
            val mailbox = mailbox(1..3)
            analyzer.downFor = setOf("Letter 2")

            assertFailsWith<LlmUnavailableException> { scanner(mailbox, analyzer).scan() }
            assertEquals(Cursor(uidValidity = 1, lastUid = 1), cursors.get(ACCOUNT))
            assertEquals(1, mailbox.sessionsClosed)

            analyzer.downFor = emptySet()
            scanner(mailbox, analyzer).scan()
            assertEquals(Cursor(uidValidity = 1, lastUid = 3), cursors.get(ACCOUNT))
            assertEquals(listOf("Letter 1", "Letter 2", "Letter 2", "Letter 3"), analyzer.saw)
        }

    @Test
    fun `when Telegram is down, the email is rated and sent again in the next pass`() =
        runTest {
            val analyzer = FakeAnalyzer(mapOf("Letter 2" to Importance.HIGH))
            val mailbox = mailbox(1..2)
            telegram.down = true

            assertFailsWith<TelegramException> { scanner(mailbox, analyzer).scan() }
            // Stopped at the email that did not go out; the low one before it is done with.
            assertEquals(Cursor(uidValidity = 1, lastUid = 1), cursors.get(ACCOUNT))

            telegram.down = false
            scanner(mailbox, analyzer).scan()
            assertEquals(Cursor(uidValidity = 1, lastUid = 2), cursors.get(ACCOUNT))
            assertEquals(listOf("Letter 1", "Letter 2", "Letter 2"), analyzer.saw)
            assertTrue("Letter 2" in telegram.sent.single())
        }

    @Test
    fun `an email deleted meanwhile and one nobody can read are passed over`() =
        runTest {
            val analyzer = FakeAnalyzer(mapOf("Letter 4" to Importance.HIGH))
            // A multipart without a single part: the parser throws on it.
            val broken = "Subject: Letter 2\r\nContent-Type: multipart/mixed; boundary=b\r\n\r\n--b--\r\n"
            val mailbox = FakeMailbox(mapOf(1L to null, 2L to broken.toByteArray(), 3L to null, 4L to eml(4)))

            assertFalse(scanner(mailbox, analyzer).scan())

            assertEquals(listOf("Letter 4"), analyzer.saw)
            assertTrue("Letter 4" in telegram.sent.single())
            assertEquals(Cursor(uidValidity = 1, lastUid = 4), cursors.get(ACCOUNT))
        }

    @Test
    fun `runs pass after pass while there is a backlog, then once per interval`() =
        runTest {
            val mailbox = mailbox(1..15)
            val running = launch { scanner(mailbox).run(Duration.ofMinutes(5)) }

            // Two passes at the very start, twelve emails and three, with no pause between them.
            delay(1.seconds)
            assertEquals(2, mailbox.sessionsOpened)
            assertEquals(Cursor(uidValidity = 1, lastUid = 15), cursors.get(ACCOUNT))

            delay(5.minutes - 2.seconds)
            assertEquals(2, mailbox.sessionsOpened)
            delay(2.seconds)
            assertEquals(3, mailbox.sessionsOpened)
            delay(5.minutes)
            assertEquals(4, mailbox.sessionsOpened)

            running.cancelAndJoin()
        }

    @Test
    fun `a pass that fails is tried again in a minute`() =
        runTest {
            val analyzer = FakeAnalyzer()
            analyzer.downFor = setOf("Letter 2")
            val running = launch { scanner(mailbox(1..3), analyzer).run(Duration.ofMinutes(5)) }

            delay(1.seconds)
            assertEquals(Cursor(uidValidity = 1, lastUid = 1), cursors.get(ACCOUNT))
            analyzer.downFor = emptySet()

            delay(58.seconds)
            assertEquals(Cursor(uidValidity = 1, lastUid = 1), cursors.get(ACCOUNT))
            delay(2.seconds)
            assertEquals(Cursor(uidValidity = 1, lastUid = 3), cursors.get(ACCOUNT))

            running.cancelAndJoin()
        }

    @Test
    fun `an error nobody expected does not stop the scanner either`() =
        runTest {
            val working = mailbox(1..3)
            var bug = true
            val mailbox =
                object : Mailbox {
                    override suspend fun open(): MailboxSession = if (bug) error("a bug") else working.open()
                }
            val running = launch { scanner(mailbox).run(Duration.ofMinutes(5)) }

            delay(1.seconds)
            bug = false
            delay(1.minutes)
            assertEquals(Cursor(uidValidity = 1, lastUid = 3), cursors.get(ACCOUNT))

            running.cancelAndJoin()
        }
}

private fun eml(n: Int): ByteArray {
    val email =
        "From: Anya <anya@x.example>\r\nTo: me@example.com\r\nSubject: Letter $n\r\n" +
            "Date: Tue, 03 Nov 2026 10:00:00 +0200\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n\r\nEmail text $n\r\n"
    return email.toByteArray()
}
