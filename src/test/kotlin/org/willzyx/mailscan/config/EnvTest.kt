package org.willzyx.mailscan.config

import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrl

class EnvTest {
    private val token = mapOf("TELEGRAM_BOT_TOKEN" to "123:abc")

    @Test
    fun `the bot token alone gives the defaults for the rest`() {
        assertEquals(
            Env(
                telegramBotToken = "123:abc",
                ollamaUrl = "http://localhost:11434".toHttpUrl(),
                ollamaModel = "gemma4:e4b",
                summaryLanguage = "Russian",
                scanInterval = Duration.ofMinutes(1),
                zone = ZoneId.of("Asia/Nicosia"),
                digestTimes = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0)),
            ),
            Env.load(token),
        )
    }

    @Test
    fun `reads every variable`() {
        val env =
            Env.load(
                token +
                    mapOf(
                        "OLLAMA_URL" to "http://ollama:11434",
                        "OLLAMA_MODEL" to "other:1b",
                        "SUMMARY_LANGUAGE" to "Greek",
                        "SCAN_INTERVAL_MIN" to "10",
                        "TZ" to "Europe/Berlin",
                        "DIGEST_TIMES" to "07:30,13:00,19:30",
                    ),
            )
        assertEquals(
            Env(
                telegramBotToken = "123:abc",
                ollamaUrl = "http://ollama:11434".toHttpUrl(),
                ollamaModel = "other:1b",
                summaryLanguage = "Greek",
                scanInterval = Duration.ofMinutes(10),
                zone = ZoneId.of("Europe/Berlin"),
                digestTimes = listOf(LocalTime.of(7, 30), LocalTime.of(13, 0), LocalTime.of(19, 30)),
            ),
            env,
        )
    }

    @Test
    fun `spaces around a value are dropped and a blank value takes the default`() {
        val env =
            Env.load(
                token +
                    mapOf(
                        "TELEGRAM_BOT_TOKEN" to "  123:abc ",
                        "OLLAMA_MODEL" to "  ",
                        "SCAN_INTERVAL_MIN" to " 5 ",
                        "TZ" to "",
                    ),
            )
        assertEquals("123:abc", env.telegramBotToken)
        assertEquals("gemma4:e4b", env.ollamaModel)
        assertEquals(Duration.ofMinutes(5), env.scanInterval)
        assertEquals(ZoneId.of("Asia/Nicosia"), env.zone)
    }

    @Test
    fun `a missing or blank bot token is a ConfigException naming the variable`() {
        for (environment in listOf(emptyMap(), mapOf("TELEGRAM_BOT_TOKEN" to "  "))) {
            val failure = assertFailsWith<ConfigException> { Env.load(environment) }
            assertEquals("TELEGRAM_BOT_TOKEN is not set", failure.message)
        }
    }

    @Test
    fun `a bad scan interval is a ConfigException naming the variable`() {
        for (bad in listOf("abc", "1.5", "10m", "0", "-3", "99999999999")) {
            val failure =
                assertFailsWith<ConfigException>(bad) { Env.load(token + ("SCAN_INTERVAL_MIN" to bad)) }
            assertTrue("SCAN_INTERVAL_MIN" in failure.message.orEmpty(), bad)
        }
    }

    @Test
    fun `an Ollama address that is not an http one is a ConfigException naming the variable and the value`() {
        for (bad in listOf("ollama:11434", "ftp://ollama:11434", "http://", "not an address")) {
            val failure = assertFailsWith<ConfigException>(bad) { Env.load(token + ("OLLAMA_URL" to bad)) }
            assertEquals("OLLAMA_URL: expected an http(s) address, got '$bad'", failure.message)
        }
    }

    @Test
    fun `an unknown time zone is a ConfigException naming the variable and the value`() {
        for (bad in listOf("Mars/Olympus", "Asia/Nikosia", "not a zone")) {
            val failure = assertFailsWith<ConfigException>(bad) { Env.load(token + ("TZ" to bad)) }
            assertEquals("TZ: unknown time zone '$bad'", failure.message)
        }
    }

    @Test
    fun `the digest times are put in order, spaces around them dropped`() {
        val env = Env.load(token + ("DIGEST_TIMES" to " 20:00 , 08:00 "))
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), env.digestTimes)
    }

    @Test
    fun `a digest time that cannot be read is a ConfigException naming the variable and the value`() {
        for (bad in listOf("9:00", "09:00:30", "25:00", "09.00", "morning", "09:00,", "09:00;21:00")) {
            val failure = assertFailsWith<ConfigException>(bad) { Env.load(token + ("DIGEST_TIMES" to bad)) }
            assertEquals("DIGEST_TIMES: expected times like 09:00,21:00, got '$bad'", failure.message)
        }
    }

    @Test
    fun `a digest time listed twice is a ConfigException naming the variable and the value`() {
        val failure =
            assertFailsWith<ConfigException> { Env.load(token + ("DIGEST_TIMES" to "09:00,21:00,09:00")) }
        assertEquals("DIGEST_TIMES: the same time is listed twice in '09:00,21:00,09:00'", failure.message)
    }

    @Test
    fun `the text form leaves out the bot token`() {
        val text = Env.load(token).toString()
        assertFalse("123:abc" in text, text)
        assertTrue("gemma4:e4b" in text, text)
    }
}
