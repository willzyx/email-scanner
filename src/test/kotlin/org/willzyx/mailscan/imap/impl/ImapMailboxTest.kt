package org.willzyx.mailscan.imap.impl

import com.icegreen.greenmail.store.MailFolder
import com.icegreen.greenmail.user.GreenMailUser
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.icegreen.greenmail.util.ServerSetupTest
import jakarta.mail.Flags
import jakarta.mail.internet.MimeMessage
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.willzyx.mailscan.imap.MailboxException

private const val USER = "me@example.com"
private const val PASSWORD = "app password"

/** [ImapMailbox] against GreenMail: a real IMAP server that runs inside the test, on ports of its own choosing. */
class ImapMailboxTest {
    private val server = GreenMail(ServerSetup.dynamicPort(arrayOf(ServerSetupTest.IMAP, ServerSetupTest.IMAPS)))
    private lateinit var user: GreenMailUser

    @BeforeTest
    fun start() {
        server.start()
        user = server.setUser(USER, USER, PASSWORD)
    }

    @AfterTest
    fun stop() = server.stop()

    private val inbox: MailFolder get() = server.managers.imapHostManager.getInbox(user)

    /** Puts an email into the inbox as if the server got it at noon of [received]; returns its UID. */
    private fun deliver(
        subject: String,
        received: LocalDate = LocalDate.of(2026, 11, 3),
        text: String = "Email text",
    ): Long {
        val raw = "From: Anya <anya@x.example>\r\nSubject: $subject\r\n\r\n$text\r\n"
        val noon = Date.from(received.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant())
        return inbox.appendMessage(MimeMessage(null, raw.byteInputStream()), Flags(), noon)
    }

    /** Over plain IMAP: the server's certificate is self-signed. */
    private fun mailbox(password: String = PASSWORD, maxFetchBytes: Int = ImapMailbox.MAX_FETCH_BYTES) =
        ImapMailbox(
            server.imap.bindTo,
            USER,
            password,
            port = server.imap.port,
            secure = false,
            maxFetchBytes = maxFetchBytes,
        )

    @Test
    fun `opens the inbox and tells its UIDVALIDITY`() =
        runTest {
            mailbox().open().use { session ->
                assertEquals(inbox.uidValidity, session.uidValidity())
            }
        }

    @Test
    fun `finds the emails after a UID, oldest first`() =
        runTest {
            val (first, second, third) = listOf("One", "Two", "Three").map { deliver(it) }
            mailbox().open().use { session ->
                assertEquals(listOf(first, second, third), session.uidsAfter(0))
                assertEquals(listOf(second, third), session.uidsAfter(first))
                // Asked for what follows the last email, a server answers with the last email itself.
                assertEquals(emptyList(), session.uidsAfter(third))
                assertEquals(emptyList(), session.uidsAfter(third + 100))
            }
        }

    @Test
    fun `a deleted email leaves a gap in the UIDs, not an error`() =
        runTest {
            val (first, second, third) = listOf("One", "Two", "Three").map { deliver(it) }
            inbox.setFlags(Flags(Flags.Flag.DELETED), true, second, null, true)
            inbox.expunge()
            mailbox().open().use { session ->
                assertEquals(listOf(third), session.uidsAfter(first))
            }
        }

    @Test
    fun `finds the emails received on a day or later`() =
        runTest {
            val monday = deliver("Monday", received = LocalDate.of(2026, 11, 2))
            val tuesday = deliver("Tuesday", received = LocalDate.of(2026, 11, 3))
            val wednesday = deliver("Wednesday", received = LocalDate.of(2026, 11, 4))
            mailbox().open().use { session ->
                assertEquals(listOf(monday, tuesday, wednesday), session.uidsSince(LocalDate.of(2026, 11, 1)))
                assertEquals(listOf(tuesday, wednesday), session.uidsSince(LocalDate.of(2026, 11, 3)))
                assertEquals(listOf(wednesday), session.uidsSince(LocalDate.of(2026, 11, 4)))
                assertEquals(emptyList(), session.uidsSince(LocalDate.of(2026, 11, 5)))
            }
        }

    @Test
    fun `an empty inbox has nothing to find`() =
        runTest {
            mailbox().open().use { session ->
                assertEquals(emptyList(), session.uidsAfter(0))
                assertEquals(emptyList(), session.uidsSince(LocalDate.of(2026, 11, 1)))
            }
        }

    @Test
    fun `searching marks nothing as read`() =
        runTest {
            repeat(3) { deliver("Letter $it") }
            mailbox().open().use { session ->
                session.uidsAfter(0)
                session.uidsSince(LocalDate.of(2026, 11, 1))
            }
            assertEquals(3, inbox.unseenCount)
        }

    @Test
    fun `fetches an email as it was sent and leaves it unread`() =
        runTest {
            deliver("One")
            val uid = deliver("Two", text = "Second email")
            val raw = mailbox().open().use { it.fetch(uid) }

            assertEquals(
                "From: Anya <anya@x.example>\r\nSubject: Two\r\n\r\nSecond email\r\n",
                assertNotNull(raw).decodeToString(),
            )
            assertEquals(2, inbox.unseenCount)
        }

    @Test
    fun `an email longer than one piece arrives whole`() =
        runTest {
            // 1.5 MB: more than the 1 MB an email is asked for at a time.
            val text = "0123456789".repeat(150_000)
            val uid = deliver("Long", text = text)
            val raw = assertNotNull(mailbox().open().use { it.fetch(uid) })
            assertTrue(raw.decodeToString().endsWith("\r\n\r\n$text\r\n"))
        }

    @Test
    fun `only the start of an email over the limit is fetched`() =
        runTest {
            val uid = deliver("Huge", text = "0123456789".repeat(1000))
            val raw = assertNotNull(mailbox(maxFetchBytes = 100).open().use { it.fetch(uid) })
            val whole = "From: Anya <anya@x.example>\r\nSubject: Huge\r\n\r\n" + "0123456789".repeat(1000)
            assertEquals(whole.take(100), raw.decodeToString())
        }

    @Test
    fun `an email that is not there any more is null`() =
        runTest {
            val uid = deliver("One")
            mailbox().open().use { session ->
                assertEquals(listOf(uid), session.uidsAfter(0))
                // Deleted by another mail program after the scanner has listed it.
                inbox.setFlags(Flags(Flags.Flag.DELETED), true, uid, null, true)
                inbox.expunge()

                assertNull(session.fetch(uid))
                assertNull(session.fetch(uid + 100))
            }
        }

    @Test
    fun `a wrong password is a MailboxException that does not repeat it`() =
        runTest {
            val failure = assertFailsWith<MailboxException> { mailbox(password = "wrong secret").open() }
            assertTrue(server.imap.bindTo in failure.message.orEmpty(), failure.message)
            assertFalse("wrong secret" in failure.message.orEmpty(), failure.message)
        }

    @Test
    fun `a server that does not answer is a MailboxException`() =
        runTest {
            val mailbox = mailbox()
            server.stop()
            assertFailsWith<MailboxException> { mailbox.open() }
        }

    @Test
    fun `the connection is encrypted unless told otherwise`() =
        runTest {
            // The plain IMAP port answers a TLS handshake with text, so the connection never gets to the password.
            val secure = ImapMailbox(server.imap.bindTo, USER, PASSWORD, port = server.imap.port)
            val failure = assertFailsWith<MailboxException> { secure.open() }
            assertTrue(failure.causes().any { it is SSLException }, failure.toString())
        }

    @Test
    fun `a server with a certificate nobody vouches for is refused`() =
        runTest {
            // GreenMail's IMAPS port shows a self-signed certificate.
            val secure = ImapMailbox(server.imaps.bindTo, USER, PASSWORD, port = server.imaps.port)
            val failure = assertFailsWith<MailboxException> { secure.open() }
            assertTrue(failure.causes().any { it is SSLHandshakeException }, failure.toString())
            assertTrue("certification path" in failure.causes().last().message.orEmpty(), failure.toString())
        }
}

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }
