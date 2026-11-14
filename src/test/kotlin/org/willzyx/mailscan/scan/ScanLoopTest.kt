package org.willzyx.mailscan.scan

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
import org.willzyx.mailscan.imap.MailboxException
import org.willzyx.mailscan.imap.MailboxSession
import org.willzyx.mailscan.llm.FakeAnalyzer
import org.willzyx.mailscan.llm.Importance
import org.willzyx.mailscan.telegram.FakeTelegramClient

private const val HOME = "home@example.com"
private const val WORK = "work@example.com"
private val INTERVAL = Duration.ofMinutes(5)

class ScanLoopTest {
    private val cursors = InMemoryCursorRepository()
    private val homeChat = FakeTelegramClient()
    private val workChat = FakeTelegramClient()

    private fun home(mailbox: Mailbox, analyzer: FakeAnalyzer = FakeAnalyzer()) =
        Scanner(HOME, mailbox, cursors, analyzer, homeChat, CLOCK)

    private fun work(mailbox: Mailbox, analyzer: FakeAnalyzer = FakeAnalyzer()) =
        Scanner(WORK, mailbox, cursors, analyzer, workChat, CLOCK)

    private fun mailbox(uids: Iterable<Int>) = FakeMailbox(uids.associate { it.toLong() to eml(it) })

    /** A mailbox that works as [working] does once [down] is set to false. */
    private class Flaky(private val working: Mailbox, private val failure: () -> Exception) : Mailbox {
        var down = true

        override suspend fun open(): MailboxSession = if (down) throw failure() else working.open()
    }

    @Test
    fun `runs round after round while there is a backlog, then once per interval`() =
        runTest {
            val mailbox = mailbox(1..15)
            val running = launch { ScanLoop(listOf(home(mailbox))).run(INTERVAL) }

            // Two passes at the very start, twelve emails and three, with no pause between them.
            delay(1.seconds)
            assertEquals(2, mailbox.sessionsOpened)
            assertEquals(Cursor(uidValidity = 1, lastUid = 15), cursors.get(HOME))

            delay(5.minutes - 2.seconds)
            assertEquals(2, mailbox.sessionsOpened)
            delay(2.seconds)
            assertEquals(3, mailbox.sessionsOpened)
            delay(5.minutes)
            assertEquals(4, mailbox.sessionsOpened)

            running.cancelAndJoin()
        }

    @Test
    fun `the mailboxes are scanned one after another, each to its own cursor and its own chat`() =
        runTest {
            val analyzer = FakeAnalyzer(mapOf("Letter 1" to Importance.HIGH, "Letter 13" to Importance.MEDIUM))
            val scanners = listOf(home(mailbox(1..2), analyzer), work(mailbox(11..13), analyzer))
            val running = launch { ScanLoop(scanners).run(INTERVAL) }

            delay(1.seconds)
            assertEquals(listOf("Letter 1", "Letter 2", "Letter 11", "Letter 12", "Letter 13"), analyzer.saw)
            assertEquals(Cursor(uidValidity = 1, lastUid = 2), cursors.get(HOME))
            assertEquals(Cursor(uidValidity = 1, lastUid = 13), cursors.get(WORK))
            assertEquals(listOf("Letter 1"), homeChat.sent.map { subject(it) })
            assertEquals(listOf("Letter 13"), workChat.sent.map { subject(it) })

            running.cancelAndJoin()
        }

    @Test
    fun `a backlog in one mailbox starts the next round at once, for every mailbox`() =
        runTest {
            val backlogged = mailbox(1..15)
            val quiet = mailbox(1..2)
            val running = launch { ScanLoop(listOf(home(backlogged), work(quiet))).run(INTERVAL) }

            delay(1.seconds)
            assertEquals(Cursor(uidValidity = 1, lastUid = 15), cursors.get(HOME))
            assertEquals(2, backlogged.sessionsOpened)
            assertEquals(2, quiet.sessionsOpened)

            running.cancelAndJoin()
        }

    @Test
    fun `a mailbox that fails does not hold up the next one and is tried again in a minute`() =
        runTest {
            val broken = Flaky(mailbox(1..3)) { MailboxException("IMAP imap.example.com: no route to host") }
            val running = launch { ScanLoop(listOf(home(broken), work(mailbox(1..2)))).run(INTERVAL) }

            delay(1.seconds)
            assertNull(cursors.get(HOME))
            assertEquals(Cursor(uidValidity = 1, lastUid = 2), cursors.get(WORK))
            broken.down = false

            delay(58.seconds)
            assertNull(cursors.get(HOME))
            delay(2.seconds)
            assertEquals(Cursor(uidValidity = 1, lastUid = 3), cursors.get(HOME))

            running.cancelAndJoin()
        }

    @Test
    fun `a pass that fails halfway is picked up in a minute where it stopped`() =
        runTest {
            val analyzer = FakeAnalyzer()
            analyzer.downFor = setOf("Letter 2")
            val running = launch { ScanLoop(listOf(home(mailbox(1..3), analyzer))).run(INTERVAL) }

            delay(1.seconds)
            assertEquals(Cursor(uidValidity = 1, lastUid = 1), cursors.get(HOME))
            analyzer.downFor = emptySet()

            delay(58.seconds)
            assertEquals(Cursor(uidValidity = 1, lastUid = 1), cursors.get(HOME))
            delay(2.seconds)
            assertEquals(Cursor(uidValidity = 1, lastUid = 3), cursors.get(HOME))

            running.cancelAndJoin()
        }

    @Test
    fun `an error nobody expected does not stop the loop either`() =
        runTest {
            val buggy = Flaky(mailbox(1..3)) { IllegalStateException("a bug") }
            val running = launch { ScanLoop(listOf(home(buggy))).run(INTERVAL) }

            delay(1.seconds)
            buggy.down = false
            delay(1.minutes)
            assertEquals(Cursor(uidValidity = 1, lastUid = 3), cursors.get(HOME))

            running.cancelAndJoin()
        }
}

/** The subject in the Telegram message [text] about an email. */
private fun subject(text: String): String =
    text.lines().first { it.startsWith("<b>Subject:</b> ") }.substringAfter("</b> ")
