package org.willzyx.mailscan.config

import java.time.Duration
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrl

class EnvTest {
    private val credentials =
        mapOf(
            "IMAP_USER" to "me@example.com",
            "IMAP_PASSWORD" to "app password",
            "TELEGRAM_BOT_TOKEN" to "123:abc",
            "TELEGRAM_CHAT_ID" to "42",
        )

    @Test
    fun `the credentials alone give the defaults for the rest`() {
        assertEquals(
            Env(
                imapHost = "imap.gmail.com",
                imapUser = "me@example.com",
                imapPassword = "app password",
                telegramBotToken = "123:abc",
                telegramChatId = "42",
                ollamaUrl = "http://localhost:11434".toHttpUrl(),
                ollamaModel = "gemma4:e4b",
                summaryLanguage = "Russian",
                scanInterval = Duration.ofMinutes(1),
                zone = ZoneId.of("Asia/Nicosia"),
            ),
            Env.load(credentials),
        )
    }

    @Test
    fun `reads every variable`() {
        val env =
            Env.load(
                credentials +
                    mapOf(
                        "IMAP_HOST" to "imap.example.com",
                        "OLLAMA_URL" to "http://ollama:11434",
                        "OLLAMA_MODEL" to "other:1b",
                        "SUMMARY_LANGUAGE" to "Greek",
                        "SCAN_INTERVAL_MIN" to "10",
                        "TZ" to "Europe/Berlin",
                    ),
            )
        assertEquals(
            Env(
                imapHost = "imap.example.com",
                imapUser = "me@example.com",
                imapPassword = "app password",
                telegramBotToken = "123:abc",
                telegramChatId = "42",
                ollamaUrl = "http://ollama:11434".toHttpUrl(),
                ollamaModel = "other:1b",
                summaryLanguage = "Greek",
                scanInterval = Duration.ofMinutes(10),
                zone = ZoneId.of("Europe/Berlin"),
            ),
            env,
        )
    }

    @Test
    fun `spaces around a value are dropped and a blank value takes the default`() {
        val env =
            Env.load(
                credentials +
                    mapOf(
                        "IMAP_USER" to "  me@example.com ",
                        "IMAP_HOST" to "  ",
                        "SCAN_INTERVAL_MIN" to " 5 ",
                        "TZ" to "",
                    ),
            )
        assertEquals("me@example.com", env.imapUser)
        assertEquals("imap.gmail.com", env.imapHost)
        assertEquals(Duration.ofMinutes(5), env.scanInterval)
        assertEquals(ZoneId.of("Asia/Nicosia"), env.zone)
    }

    @Test
    fun `a missing or blank credential is a ConfigException naming the variable`() {
        for (name in credentials.keys) {
            for (environment in listOf(credentials - name, credentials + (name to "  "))) {
                val failure = assertFailsWith<ConfigException>(name) { Env.load(environment) }
                assertEquals("$name is not set", failure.message)
            }
        }
    }

    @Test
    fun `a bad scan interval is a ConfigException naming the variable`() {
        for (bad in listOf("abc", "1.5", "10m", "0", "-3", "99999999999")) {
            val failure =
                assertFailsWith<ConfigException>(bad) { Env.load(credentials + ("SCAN_INTERVAL_MIN" to bad)) }
            assertTrue("SCAN_INTERVAL_MIN" in failure.message.orEmpty(), bad)
        }
    }

    @Test
    fun `an Ollama address that is not an http one is a ConfigException naming the variable and the value`() {
        for (bad in listOf("ollama:11434", "ftp://ollama:11434", "http://", "not an address")) {
            val failure = assertFailsWith<ConfigException>(bad) { Env.load(credentials + ("OLLAMA_URL" to bad)) }
            assertEquals("OLLAMA_URL: expected an http(s) address, got '$bad'", failure.message)
        }
    }

    @Test
    fun `an unknown time zone is a ConfigException naming the variable and the value`() {
        for (bad in listOf("Mars/Olympus", "Asia/Nikosia", "not a zone")) {
            val failure = assertFailsWith<ConfigException>(bad) { Env.load(credentials + ("TZ" to bad)) }
            assertEquals("TZ: unknown time zone '$bad'", failure.message)
        }
    }

    @Test
    fun `the text form leaves out the password and the bot token`() {
        val text = Env.load(credentials).toString()
        assertFalse("app password" in text, text)
        assertFalse("123:abc" in text, text)
        assertTrue("me@example.com" in text, text)
    }
}
