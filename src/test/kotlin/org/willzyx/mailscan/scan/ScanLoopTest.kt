package org.willzyx.mailscan.scan

import java.time.Duration
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.willzyx.mailscan.db.AnalyzedEmail
import org.willzyx.mailscan.db.Cursor
import org.willzyx.mailscan.db.InMemoryAnalysisRepository
import org.willzyx.mailscan.db.InMemoryCursorRepository
import org.willzyx.mailscan.db.InMemoryDigestRepository
import org.willzyx.mailscan.imap.FakeMailbox
import org.willzyx.mailscan.imap.Mailbox
import org.willzyx.mailscan.imap.MailboxException
import org.willzyx.mailscan.imap.MailboxSession
import org.willzyx.mailscan.llm.Analysis
import org.willzyx.mailscan.llm.DigestWriter
import org.willzyx.mailscan.llm.FakeAnalyzer
import org.willzyx.mailscan.llm.FakeDigestWriter
import org.willzyx.mailscan.llm.Importance
import org.willzyx.mailscan.telegram.FakeTelegramClient

private const val HOME = "home@example.com"
private const val WORK = "work@example.com"
private val INTERVAL = Duration.ofMinutes(5)

/** By [CLOCK] the last of these came three hours and a quarter ago, and the one before it the morning before. */
private val DIGEST_TIMES = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0))

class ScanLoopTest {
    private val cursors = InMemoryCursorRepository()
    private val analyses = InMemoryAnalysisRepository()
    private val digests = InMemoryDigestRepository()
    private val writer = FakeDigestWriter()
    private val homeChat = FakeTelegramClient()
    private val workChat = FakeTelegramClient()

    private fun home(mailbox: Mailbox, analyzer: FakeAnalyzer = FakeAnalyzer(), writer: DigestWriter = this.writer) =
        Scanner(HOME, mailbox, cursors, analyses, analyzer, homeChat, CLOCK) to
            Digester(HOME, analyses, digests, writer, homeChat, CLOCK, DIGEST_TIMES)

    private fun work(mailbox: Mailbox, analyzer: FakeAnalyzer = FakeAnalyzer()) =
        Scanner(WORK, mailbox, cursors, analyses, analyzer, workChat, CLOCK) to
            Digester(WORK, analyses, digests, writer, workChat, CLOCK, DIGEST_TIMES)

    private fun loop(vararg mailboxes: Pair<Scanner, Digester>) = ScanLoop(mailboxes.toList(), analyses, CLOCK)

    private fun mailbox(uids: Iterable<Int>) = FakeMailbox(uids.associate { it.toLong() to eml(it) })

    /** An email of [account] that was rated [ago] before now. */
    private suspend fun rated(account: String, uid: Long, ago: Duration) {
        val analysis = Analysis(Importance.LOW, "Summary", "reason", actionRequired = false)
        analyses.save(account, AnalyzedEmail(1, uid, CLOCK.instant() - ago, "Anya", "Old letter $uid", analysis))
    }

    /** A mailbox that works as [working] does once [down] is set to false. */
    private class Flaky(private val working: Mailbox, private val failure: () -> Exception) : Mailbox {
        var down = true

        override suspend fun open(): MailboxSession = if (down) throw failure() else working.open()
    }

    @Test
    fun `runs round after round while there is a backlog, then once per interval`() =
        runTest {
            val mailbox = mailbox(1..15)
            val running = launch { loop(home(mailbox)).run(INTERVAL) }

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
    fun `the mailboxes are scanned one after another, each to its own cursor, records and chat`() =
        runTest {
            val analyzer = FakeAnalyzer(mapOf("Letter 1" to Importance.HIGH, "Letter 13" to Importance.MEDIUM))
            val mailboxes = arrayOf(home(mailbox(1..2), analyzer), work(mailbox(11..13), analyzer))
            val running = launch { loop(*mailboxes).run(INTERVAL) }

            delay(1.seconds)
            assertEquals(listOf("Letter 1", "Letter 2", "Letter 11", "Letter 12", "Letter 13"), analyzer.saw)
            assertEquals(Cursor(uidValidity = 1, lastUid = 2), cursors.get(HOME))
            assertEquals(Cursor(uidValidity = 1, lastUid = 13), cursors.get(WORK))
            assertEquals(listOf("Letter 1"), homeChat.sent.map { subject(it) })
            assertEquals(listOf("Letter 13"), workChat.sent.map { subject(it) })
            assertEquals(listOf("Letter 1", "Letter 2"), analyses.of(HOME).map { it.subject })
            assertEquals(listOf("Letter 11", "Letter 12", "Letter 13"), analyses.of(WORK).map { it.subject })

            running.cancelAndJoin()
        }

    @Test
    fun `a backlog in one mailbox starts the next round at once, for every mailbox`() =
        runTest {
            val backlogged = mailbox(1..15)
            val quiet = mailbox(1..2)
            val running = launch { loop(home(backlogged), work(quiet)).run(INTERVAL) }

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
            val running = launch { loop(home(broken), work(mailbox(1..2))).run(INTERVAL) }

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
            val running = launch { loop(home(mailbox(1..3), analyzer)).run(INTERVAL) }

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
    fun `emails rated more than a week ago are deleted, those of a mailbox no longer scanned too`() =
        runTest {
            rated(HOME, uid = 101, ago = Duration.ofDays(7).plusSeconds(1))
            rated(HOME, uid = 102, ago = Duration.ofDays(7))
            rated(WORK, uid = 103, ago = Duration.ofDays(30))
            val running = launch { loop(home(mailbox(1..2))).run(INTERVAL) }

            delay(1.seconds)
            assertEquals(listOf(102L, 1L, 2L), analyses.of(HOME).map { it.uid })
            assertEquals(emptyList(), analyses.of(WORK))

            running.cancelAndJoin()
        }

    @Test
    fun `a deletion that fails does not hold up the mailboxes and is tried again in a minute`() =
        runTest {
            rated(HOME, uid = 101, ago = Duration.ofDays(8))
            analyses.down = true
            val running = launch { loop(home(mailbox(1..2))).run(INTERVAL) }

            delay(1.seconds)
            assertEquals(listOf(101L, 1L, 2L), analyses.of(HOME).map { it.uid })
            analyses.down = false

            delay(58.seconds)
            assertEquals(3, analyses.of(HOME).size)
            delay(2.seconds)
            assertEquals(listOf(1L, 2L), analyses.of(HOME).map { it.uid })

            running.cancelAndJoin()
        }

    @Test
    fun `once the new emails of a mailbox are handled, its digest goes out to its chat when the time has come`() =
        runTest {
            rated(HOME, uid = 101, ago = Duration.ofHours(1))
            val running = launch { loop(home(mailbox(1..2)), work(mailbox(11..12))).run(INTERVAL) }

            delay(1.seconds)
            assertEquals(
                """
                📬 <b>Digest</b> · home@example.com
                3 Nov 09:00 – 4 Nov 00:15
                🔴 0 · 🟡 0 · ⚪ 1

                • Digest of Old letter 101
                """.trimIndent(),
                homeChat.sent.single(),
            )
            // Nothing was rated in the other mailbox before this round, so its period is passed over.
            assertEquals(emptyList(), workChat.sent)
            assertEquals(CLOCK.instant(), digests.get(HOME))
            assertEquals(CLOCK.instant(), digests.get(WORK))

            // The time for the next one is hours away.
            delay(10.minutes)
            assertEquals(1, homeChat.sent.size)

            running.cancelAndJoin()
        }

    @Test
    fun `the digest of a mailbox waits for its backlog to be worked off`() =
        runTest {
            rated(HOME, uid = 101, ago = Duration.ofHours(1))
            val analyzer = FakeAnalyzer()
            val seenWhenAsked = mutableListOf<Int>()
            val writer =
                DigestWriter {
                    seenWhenAsked += analyzer.saw.size
                    "• A digest"
                }
            val running = launch { loop(home(mailbox(1..15), analyzer, writer)).run(INTERVAL) }

            delay(1.seconds)
            // Not after the first pass of twelve emails: after the second, which left nothing to do.
            assertEquals(listOf(15), seenWhenAsked)
            assertEquals(1, homeChat.sent.size)

            running.cancelAndJoin()
        }

    @Test
    fun `a digest that fails does not hold up the next mailbox and is tried again in a minute`() =
        runTest {
            rated(HOME, uid = 101, ago = Duration.ofHours(1))
            writer.down = true
            val running = launch { loop(home(mailbox(1..2)), work(mailbox(11..12))).run(INTERVAL) }

            delay(1.seconds)
            assertEquals(emptyList(), homeChat.sent)
            assertNull(digests.get(HOME))
            // The emails of the mailbox itself are handled all the same, and so is the next mailbox.
            assertEquals(Cursor(uidValidity = 1, lastUid = 2), cursors.get(HOME))
            assertEquals(Cursor(uidValidity = 1, lastUid = 12), cursors.get(WORK))
            writer.down = false

            delay(58.seconds)
            assertEquals(emptyList(), homeChat.sent)
            delay(2.seconds)
            assertEquals(1, homeChat.sent.size)
            assertEquals(CLOCK.instant(), digests.get(HOME))

            running.cancelAndJoin()
        }

    @Test
    fun `an error nobody expected does not stop the loop either`() =
        runTest {
            val buggy = Flaky(mailbox(1..3)) { IllegalStateException("a bug") }
            val running = launch { loop(home(buggy)).run(INTERVAL) }

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
