package org.willzyx.mailscan.mail

import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmailParserTest {
    @Test
    fun `reads the sender, the subject and the date of a plain email`() {
        val mail =
            parse(
                "From: Anya <anya@x.example>",
                "Subject: Letter 1",
                "Date: Tue, 03 Nov 2026 10:00:00 +0200",
                "",
                "Email text",
            )
        assertEquals("Anya <anya@x.example>", mail.sender)
        assertEquals("Letter 1", mail.subject)
        assertEquals("Tue, 03 Nov 2026 10:00:00 +0200", mail.date)
        assertEquals("Email text", mail.body)
    }

    @Test
    fun `decodes the name of the sender`() {
        assertEquals(
            "Банк Север — Служба безопасности <security@banksever-online-verify.example>",
            fixture("emails/20_ru_bank_phishing.eml").sender,
        )
        // Plain and encoded words in one name.
        assertEquals(
            "Hostinek — Dział Rozliczeń <billing@hostinek.example>",
            fixture("emails_work/10_pl_invoice_overdue.eml").sender,
        )
        // A name encoded in two pieces on two lines: the pieces join without a space.
        assertEquals(
            "Διαχείριση Πολυκατοικίας <diaxeirisi.polykatoikia@mail.example>",
            fixture("emails/14_el_building_water.eml").sender,
        )
    }

    @Test
    fun `a name in quotes or with a comment in brackets is read whole`() {
        assertEquals("Dimitris K. <dimitris.k@mailbox.example>", fixture("emails/09_en_friend_trip.eml").sender)
        assertEquals(
            "김지은 (Talent Partner) <jieun.kim@nextwave-talent.example>",
            fixture("emails_work/15_ko_recruiter.eml").sender,
        )
        assertEquals("Ivanov, Ivan <i@x.example>", parse("From: \"Ivanov, Ivan\" <i@x.example>", "", "text").sender)
    }

    @Test
    fun `a sender without a name is the address alone, in lower case`() {
        assertEquals("anya@x.example", parse("From: anya@x.example", "", "text").sender)
        assertEquals("anya@x.example", parse("From: <Anya@X.Example>", "", "text").sender)
        assertEquals("Anya <anya@x.example>", parse("From: Anya <ANYA@x.example>", "", "text").sender)
    }

    @Test
    fun `the first of several senders is taken`() {
        val mail = parse("From: Anya <anya@x.example>, Boris <b@x.example>", "", "text")
        assertEquals("Anya <anya@x.example>", mail.sender)
    }

    @Test
    fun `decodes an encoded subject`() {
        assertEquals(
            "Προσωρινή δέσμευση κάρτας λόγω ύποπτης συναλλαγής",
            fixture("emails/03_el_bank_card_hold.eml").subject,
        )
        assertEquals("您的快递已到达取件点，请凭取件码领取", fixture("emails_work/05_zh_delivery.eml").subject)
    }

    @Test
    fun `decodes an encoded word in the middle of a plain subject`() {
        assertEquals("Q4 budget numbers — need by EOD", fixture("emails_work/18_en_manager_eod.eml").subject)
    }

    @Test
    fun `joins a subject that takes several lines`() {
        assertEquals(
            "Re: [URGENT] prod-payments: replica lag 40 min, need your approval to roll back 3.14.2",
            fixture("emails_work/07_uk_thread_resolved.eml").subject,
        )
        assertEquals(
            "Привет, как дела",
            parse("Subject: =?utf-8?b?0J/RgNC40LLQtdGCLCA=?=", " =?utf-8?b?0LrQsNC6INC00LXQu9Cw?=", "", "text").subject,
        )
    }

    @Test
    fun `a subject in an unknown encoding stays as it is written`() {
        val written = "=?x-no-such-charset?b?0J/RgNC40LLQtdGC?="
        assertEquals(written, parse("Subject: $written", "", "text").subject)
    }

    @Test
    fun `a missing header is an empty string`() {
        val mail = parse("To: me@example.com", "", "Email text")
        assertEquals("", mail.sender)
        assertEquals("", mail.subject)
        assertEquals("", mail.date)
    }

    @Test
    fun `every test email has a readable sender and subject, a date and a body`() {
        assertEquals(40, FIXTURES.size)
        for (name in FIXTURES) {
            val mail = fixture(name)
            assertTrue(Regex(""".+ <[a-z0-9.-]+@[a-z0-9.-]+\.example>""").matches(mail.sender), "$name: ${mail.sender}")
            assertFalse("=?" in mail.sender, "$name: ${mail.sender}")
            assertTrue(mail.subject.isNotEmpty(), name)
            assertFalse("=?" in mail.subject, "$name: ${mail.subject}")
            assertTrue(" Nov 2026 " in mail.date, "$name: ${mail.date}")
            assertTrue(mail.body.isNotBlank(), name)
            assertEquals(mail.body.lines().joinToString("\n") { it.trim() }.trim(), mail.body, name)
            assertFalse("\n\n\n" in mail.body, name)
        }
    }

    @Test
    fun `the body is tidied up, spaces and blank lines`() {
        val mail =
            parse(
                "Content-Type: text/plain; charset=utf-8",
                "",
                "",
                "  Hello,   world\t!  ",
                "price:\u00A0\u00A010\u00A0€",
                "",
                " \t ",
                "",
                "",
                "Bye",
                "",
                "",
            )
        assertEquals("Hello, world !\nprice: 10 €\n\nBye", mail.body)
    }

    @Test
    fun `the text of an HTML email is tidied up as well`() {
        val mail =
            parse(
                "Content-Type: text/html; charset=utf-8",
                "",
                "<html><body>",
                "<h1>Sale</h1>",
                "<div><div><p>Only   today:&nbsp;&nbsp;-15%</p></div></div>",
                "<p>Bye</p>",
                "</body></html>",
            )
        assertEquals("Sale\n\nOnly today: -15%\n\nBye", mail.body)
    }

    @Test
    fun `the body is cut to the limit`() {
        val raw = RESOURCES.resolve("emails_work/17_nl_meeting_notes_long.eml").readBytes()
        val whole = parseEmail(raw).body
        assertTrue(whole.length in 1001..BODY_MAX_CHARS, "${whole.length}")
        assertEquals(whole.take(100), parseEmail(raw, bodyMaxChars = 100).body)

        val huge = parse("Content-Type: text/plain; charset=utf-8", "", "x".repeat(BODY_MAX_CHARS + 500))
        assertEquals(BODY_MAX_CHARS, huge.body.length)
    }

    @Test
    fun `a cut does not leave half of an emoji`() {
        val raw = "Content-Type: text/plain; charset=utf-8\r\n\r\nabc🔥def".toByteArray()
        assertEquals("abc", parseEmail(raw, bodyMaxChars = 4).body)
        assertEquals("abc🔥", parseEmail(raw, bodyMaxChars = 5).body)
    }

    @Test
    fun `decodes a base64 body and a quoted-printable one`() {
        assertTrue("Β΄ τάξη" in fixture("emails/06_el_school_trip.eml").body)
        assertTrue("Постановление" in fixture("emails/05_ru_traffic_fine.eml").body)
    }

    @Test
    fun `the plain text of an email is preferred to its HTML`() {
        val mail =
            parse(
                "Content-Type: multipart/alternative; boundary=b",
                "",
                "--b",
                "Content-Type: text/html; charset=utf-8",
                "",
                "<p>In HTML</p>",
                "--b",
                "Content-Type: text/plain; charset=utf-8",
                "",
                "In plain text",
                "--b--",
                "",
            )
        assertEquals("In plain text", mail.body)
    }

    @Test
    fun `an email without plain text gives the text of its HTML`() {
        val body = fixture("emails/15_en_shop_promo.eml").body
        assertTrue("Black Friday is here" in body, body)
        // No markup, and nothing from its <style> and <script>.
        assertFalse("<" in body, body)
        assertFalse(".btn" in body, body)
        assertFalse("dataLayer" in body, body)
    }

    @Test
    fun `text hidden in the HTML never reaches the body`() {
        // A promo with instructions for the model in a block the reader does not see.
        val body = fixture("emails/17_ru_injection_promo.eml").body
        assertTrue("Кешбэк 15%" in body, body)
        assertFalse("ИИ-АССИСТЕНТ" in body, body)
        assertFalse("importance" in body, body)
    }

    @Test
    fun `the text is found inside nested parts, next to an attached file`() {
        // multipart/mixed of a multipart/alternative and a PDF.
        assertTrue(fixture("emails_work/01_ru_contract.eml").body.startsWith("Добрый день!"))
    }

    @Test
    fun `an attached text file is not the body`() {
        val mail =
            parse(
                "Content-Type: multipart/mixed; boundary=b",
                "",
                "--b",
                "Content-Type: text/plain; charset=utf-8",
                "Content-Disposition: attachment; filename=notes.txt",
                "",
                "Notes from the attached file",
                "--b",
                "Content-Type: text/html; charset=utf-8",
                "",
                "<p>What the email says</p>",
                "--b--",
                "",
            )
        assertEquals("What the email says", mail.body)
    }

    @Test
    fun `an email without a text part has an empty body`() {
        val mail = parse("Content-Type: image/png", "Content-Transfer-Encoding: base64", "", "aGVsbG8=")
        assertEquals("", mail.body)
    }

    @Test
    fun `a body in a charset Java does not know is read as UTF-8`() {
        val mail = parse("Content-Type: text/plain; charset=x-no-such-charset", "", "Привет")
        assertEquals("Привет", mail.body)
    }

    @Test
    fun `an email cut off in the middle is read as far as it goes`() {
        // The scanner fetches only the start of a large email, so the closing boundary may be missing.
        val mail =
            parse(
                "Content-Type: multipart/mixed; boundary=b",
                "",
                "--b",
                "Content-Type: text/plain; charset=utf-8",
                "",
                "The text before the cut",
            )
        assertEquals("The text before the cut", mail.body)
    }
}

/** An email made of [lines], joined the way they travel: with CRLF. */
private fun parse(vararg lines: String): ParsedEmail = parseEmail(lines.joinToString("\r\n").toByteArray())

private fun fixture(name: String): ParsedEmail = parseEmail(RESOURCES.resolve(name).readBytes())

/** `src/test/resources` as the build lays it out. */
private val RESOURCES: Path =
    Path.of(checkNotNull(EmailParserTest::class.java.getResource("/emails")) { "No test emails" }.toURI()).parent

/** The test emails: 20 home ones in `emails` and 20 work ones in `emails_work`. */
private val FIXTURES: List<String> =
    listOf("emails", "emails_work").flatMap { folder ->
        RESOURCES.resolve(folder).listDirectoryEntries("*.eml").map { "$folder/${it.name}" }.sorted()
    }
