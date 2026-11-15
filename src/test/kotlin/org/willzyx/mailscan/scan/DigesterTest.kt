package org.willzyx.mailscan.scan

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.willzyx.mailscan.db.AnalyzedEmail
import org.willzyx.mailscan.db.InMemoryAnalysisRepository
import org.willzyx.mailscan.db.InMemoryDigestRepository
import org.willzyx.mailscan.llm.Analysis
import org.willzyx.mailscan.llm.FakeDigestWriter
import org.willzyx.mailscan.llm.Importance
import org.willzyx.mailscan.llm.LlmUnavailableException
import org.willzyx.mailscan.telegram.FakeTelegramClient
import org.willzyx.mailscan.telegram.TelegramException

private const val HOME = "home@example.com"
private const val WORK = "work@example.com"
private val NICOSIA: ZoneId = ZoneId.of("Asia/Nicosia")
private val MORNING_AND_EVENING = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0))

/** The moment the clock in Nicosia shows [text], as in `2026-11-03T10:30`. */
private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(NICOSIA).toInstant()

class DigesterTest {
    private val analyses = InMemoryAnalysisRepository()
    private val digests = InMemoryDigestRepository()
    private val writer = FakeDigestWriter()
    private val telegram = FakeTelegramClient()
    private var uid = 0L

    /** The digester of [account] at the moment the clock in Nicosia shows [now]. */
    private fun digester(now: String, account: String = HOME) =
        Digester(account, analyses, digests, writer, telegram, Clock.fixed(at(now), NICOSIA), MORNING_AND_EVENING)

    /** Keeps an email with [subject], rated when the clock in Nicosia showed [ratedAt]. */
    private suspend fun rated(
        ratedAt: String,
        subject: String,
        importance: Importance = Importance.LOW,
        account: String = HOME,
    ) {
        val analysis = Analysis(importance, "Summary of $subject", "reason", false)
        analyses.save(account, AnalyzedEmail(1, ++uid, at(ratedAt), "Sender", subject, analysis))
    }

    @Test
    fun `the first digest of a mailbox goes out at once, for the emails since the time before the last`() =
        runTest {
            rated("2026-11-02T20:59:59", "Too old")
            rated("2026-11-02T21:00", "Evening", Importance.HIGH)
            rated("2026-11-03T08:00", "Night", Importance.MEDIUM)
            rated("2026-11-03T10:29:59", "Morning")

            digester("2026-11-03T10:30").sendIfDue()

            assertEquals(
                """
                📬 <b>Digest</b> · home@example.com
                2 Nov 21:00 – 3 Nov 10:30
                🔴 1 · 🟡 1 · ⚪ 1

                Digest of Evening, Night, Morning
                """.trimIndent(),
                telegram.sent.single(),
            )
            assertEquals(at("2026-11-03T10:30"), digests.get(HOME))
        }

    @Test
    fun `after a digest nothing happens until the next time comes`() =
        runTest {
            rated("2026-11-03T08:00", "Night")
            digester("2026-11-03T09:00").sendIfDue()
            rated("2026-11-03T12:00", "Noon")

            digester("2026-11-03T09:01").sendIfDue()
            digester("2026-11-03T20:59:59").sendIfDue()

            assertEquals(1, telegram.sent.size)
            assertEquals(1, writer.asked.size)
            assertEquals(at("2026-11-03T09:00"), digests.get(HOME))
        }

    @Test
    fun `when the next time comes the digest covers the emails since the one before`() =
        runTest {
            rated("2026-11-03T08:00", "Night")
            digester("2026-11-03T09:04").sendIfDue()
            rated("2026-11-03T12:00", "Noon")
            rated("2026-11-03T20:00", "Evening")

            digester("2026-11-03T21:00").sendIfDue()

            assertEquals(2, telegram.sent.size)
            val lines = telegram.sent.last().lines()
            assertEquals("3 Nov 09:04 – 21:00", lines[1])
            assertEquals("Digest of Noon, Evening", lines.last())
            assertEquals(at("2026-11-03T21:00"), digests.get(HOME))
        }

    @Test
    fun `an email rated at the moment a digest is made is in the next one, and in no other`() =
        runTest {
            rated("2026-11-03T08:00", "Night")
            rated("2026-11-03T09:04", "On the boundary")

            digester("2026-11-03T09:04").sendIfDue()
            digester("2026-11-03T21:00").sendIfDue()

            assertEquals(
                listOf(listOf("Night"), listOf("On the boundary")),
                writer.asked.map { emails -> emails.map { it.subject } },
            )
        }

    @Test
    fun `a moment is taken to the second, as the database keeps it`() =
        runTest {
            rated("2026-11-03T09:04", "In the same second")
            val clock = Clock.fixed(at("2026-11-03T09:04").plusMillis(900), NICOSIA)

            Digester(HOME, analyses, digests, writer, telegram, clock, MORNING_AND_EVENING).sendIfDue()
            assertEquals(at("2026-11-03T09:04"), digests.get(HOME))
            assertEquals(emptyList(), telegram.sent)

            digester("2026-11-03T21:00").sendIfDue()
            assertEquals(listOf("In the same second"), writer.asked.single().map { it.subject })
        }

    @Test
    fun `a period without emails is passed over in silence`() =
        runTest {
            digester("2026-11-03T09:00").sendIfDue()

            assertEquals(emptyList(), telegram.sent)
            assertEquals(emptyList(), writer.asked)
            assertEquals(at("2026-11-03T09:00"), digests.get(HOME))

            // The period is closed: an email that comes later waits for the next time.
            rated("2026-11-03T09:30", "Late morning")
            digester("2026-11-03T10:00").sendIfDue()
            assertEquals(emptyList(), telegram.sent)

            digester("2026-11-03T21:00").sendIfDue()
            assertEquals("3 Nov 09:00 – 21:00", telegram.sent.single().lines()[1])
        }

    @Test
    fun `times missed while the service was down are made up for with one digest`() =
        runTest {
            rated("2026-11-01T08:00", "Before the stop")
            digester("2026-11-01T09:00").sendIfDue()
            rated("2026-11-01T10:00", "Just before the stop")
            rated("2026-11-03T15:00", "After the start")

            digester("2026-11-03T15:01").sendIfDue()
            digester("2026-11-03T15:02").sendIfDue()

            assertEquals(2, telegram.sent.size)
            val lines = telegram.sent.last().lines()
            assertEquals("1 Nov 09:00 – 3 Nov 15:01", lines[1])
            assertEquals("Digest of Just before the stop, After the start", lines.last())
        }

    @Test
    fun `a digest covers the emails of its own mailbox, and each mailbox keeps its own time`() =
        runTest {
            rated("2026-11-03T08:00", "Home letter")
            rated("2026-11-03T08:00", "Work letter", account = WORK)

            digester("2026-11-03T09:00").sendIfDue()

            assertEquals(listOf("Home letter"), writer.asked.single().map { it.subject })
            assertNull(digests.get(WORK))

            digester("2026-11-03T09:01", account = WORK).sendIfDue()

            assertEquals("📬 <b>Digest</b> · work@example.com", telegram.sent.last().lines().first())
            assertEquals(listOf("Work letter"), writer.asked.last().map { it.subject })
            assertEquals(at("2026-11-03T09:01"), digests.get(WORK))
            assertEquals(at("2026-11-03T09:00"), digests.get(HOME))
        }

    @Test
    fun `when the model is down the digest stays due and is made anew`() =
        runTest {
            rated("2026-11-03T08:00", "Night")
            writer.down = true

            assertFailsWith<LlmUnavailableException> { digester("2026-11-03T09:00").sendIfDue() }

            assertEquals(emptyList(), telegram.sent)
            assertNull(digests.get(HOME))

            writer.down = false
            rated("2026-11-03T09:00:30", "Came meanwhile")
            digester("2026-11-03T09:01").sendIfDue()

            assertEquals("Digest of Night, Came meanwhile", telegram.sent.single().lines().last())
            assertEquals(at("2026-11-03T09:01"), digests.get(HOME))
        }

    @Test
    fun `when Telegram is down the digest stays due and is made anew`() =
        runTest {
            rated("2026-11-03T08:00", "Night")
            digester("2026-11-03T09:00").sendIfDue()
            rated("2026-11-03T12:00", "Noon")
            telegram.down = true

            assertFailsWith<TelegramException> { digester("2026-11-03T21:00").sendIfDue() }

            assertEquals(at("2026-11-03T09:00"), digests.get(HOME))

            telegram.down = false
            digester("2026-11-03T21:01").sendIfDue()

            assertEquals(2, telegram.sent.size)
            assertEquals("3 Nov 09:00 – 21:01", telegram.sent.last().lines()[1])
            assertEquals(at("2026-11-03T21:01"), digests.get(HOME))
        }
}
