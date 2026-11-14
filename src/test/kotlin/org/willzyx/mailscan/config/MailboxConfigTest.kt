package org.willzyx.mailscan.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

private const val PASSWORD = "aaaa bbbb cccc dddd"

class MailboxConfigTest {
    @TempDir
    lateinit var dir: Path

    private val file: Path get() = dir.resolve("mailboxes.yaml")

    private fun load(yaml: String): List<MailboxConfig> {
        Files.writeString(file, yaml.trimIndent())
        return MailboxConfig.load(file)
    }

    private fun failure(yaml: String): String = assertFailsWith<ConfigException>(yaml) { load(yaml) }.message.orEmpty()

    @Test
    fun `reads the mailboxes in the order they are written in`() {
        val mailboxes =
            load(
                """
                # Home first.
                mailboxes:
                  - user: home@example.com
                    password: $PASSWORD
                    chat_id: 111

                  - user: work@example.org
                    password: 'p#ss: w*rd'
                    chat_id: -1001234567890
                    host: imap.example.org   # not Gmail
                """,
            )
        assertEquals(
            listOf(
                MailboxConfig("home@example.com", PASSWORD, chatId = "111", host = "imap.gmail.com"),
                MailboxConfig("work@example.org", "p#ss: w*rd", chatId = "-1001234567890", host = "imap.example.org"),
            ),
            mailboxes,
        )
    }

    @Test
    fun `a password is taken as it is written`() {
        val mailboxes =
            load(
                """
                mailboxes:
                  - {user: a@example.com, password: 12345678, chat_id: 1}
                  - {user: b@example.com, password: 0012300, chat_id: 1}
                  - {user: c@example.com, password: 1.50, chat_id: 1}
                  - {user: d@example.com, password: true, chat_id: 1}
                """,
            )
        assertEquals(listOf("12345678", "0012300", "1.50", "true"), mailboxes.map { it.password })
    }

    @Test
    fun `a file that is missing is a ConfigException naming it`() {
        val failure = assertFailsWith<ConfigException> { MailboxConfig.load(file) }
        assertTrue(failure.message.orEmpty().startsWith("$file: "), failure.message)
    }

    @Test
    fun `broken YAML is a ConfigException naming the file and the line`() {
        // A colon followed by a space inside an unquoted password ends the value there.
        val message =
            failure(
                """
                mailboxes:
                  - user: home@example.com
                    password: secret: word
                """,
            )
        assertTrue(message.startsWith("$file: "), message)
        assertTrue("line 3" in message, message)
    }

    @Test
    fun `a file without a list of mailboxes is a ConfigException naming it`() {
        for (yaml in listOf("", "# nothing but a comment", "mailboxes:", "mailboxes: none", "- a")) {
            val message = failure(yaml)
            assertTrue(message.startsWith("$file: "), message)
        }
        assertEquals("$file: no mailboxes are listed", failure("mailboxes: []"))
    }

    @Test
    fun `a missing key is a ConfigException naming the key`() {
        for (key in listOf("user", "password", "chat_id")) {
            val lines = listOf("user: home@example.com", "password: $PASSWORD", "chat_id: 111")
            val entry = lines.filterNot { it.startsWith("$key:") }.joinToString(", ")
            val message = failure("mailboxes:\n  - {$entry}")
            assertTrue(message.startsWith("$file: "), message)
            assertTrue(key in message, message)
        }
    }

    @Test
    fun `an unknown key is a ConfigException naming the key`() {
        val message =
            failure(
                """
                mailboxes:
                  - user: home@example.com
                    password: p
                    chat_id: 111
                    hots: imap.example.org
                """,
            )
        assertTrue(message.startsWith("$file: "), message)
        assertTrue("hots" in message, message)
    }

    @Test
    fun `a mailbox listed twice is a ConfigException`() {
        val message =
            failure(
                """
                mailboxes:
                  - {user: home@example.com, password: p, chat_id: 111}
                  - {user: work@example.com, password: p, chat_id: 222}
                  - {user: home@example.com, password: p, chat_id: 333}
                """,
            )
        assertEquals("$file: a mailbox is listed more than once", message)
    }

    @Test
    fun `the text form leaves out the password`() {
        val text = MailboxConfig("home@example.com", PASSWORD, chatId = "111", host = "imap.gmail.com").toString()
        assertFalse(PASSWORD in text, text)
        assertEquals("MailboxConfig(user=home@example.com, chatId=111, host=imap.gmail.com)", text)
    }
}
