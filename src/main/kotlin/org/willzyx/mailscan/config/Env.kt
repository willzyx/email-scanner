package org.willzyx.mailscan.config

import java.time.DateTimeException
import java.time.Duration
import java.time.ZoneId
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ConfigException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Everything the process needs from its environment; see `.env.example`. A variable that is not set,
 * or holds nothing but spaces, takes its default; the ones without a default must be set.
 */
data class Env(
    val imapHost: String,
    val imapUser: String,
    val imapPassword: String,
    val telegramBotToken: String,
    val telegramChatId: String,
    val ollamaUrl: HttpUrl,
    val ollamaModel: String,
    /** The language the model writes summaries in: any language name it understands. */
    val summaryLanguage: String,
    val scanInterval: Duration,
    /** The zone "today" is taken in for the first scan's lookback. */
    val zone: ZoneId,
) {
    // The password and the bot token stay out of logs and stack traces.
    override fun toString(): String =
        "Env(imapHost=$imapHost, imapUser=$imapUser, telegramChatId=$telegramChatId, ollamaUrl=$ollamaUrl, " +
            "ollamaModel=$ollamaModel, summaryLanguage=$summaryLanguage, scanInterval=$scanInterval, zone=$zone)"

    companion object {
        fun load(environment: Map<String, String> = System.getenv()): Env {
            fun get(name: String, default: String = ""): String =
                environment[name]?.trim()?.takeIf { it.isNotEmpty() } ?: default

            fun required(name: String): String = get(name).ifEmpty { throw ConfigException("$name is not set") }

            val intervalText = get("SCAN_INTERVAL_MIN", "1")
            val minutes =
                intervalText.toIntOrNull()
                    ?: throw ConfigException("SCAN_INTERVAL_MIN: expected a number of minutes, got '$intervalText'")
            if (minutes < 1) throw ConfigException("SCAN_INTERVAL_MIN: the minimum is 1 minute")

            val zoneText = get("TZ", "Asia/Nicosia")
            val zone =
                try {
                    ZoneId.of(zoneText)
                } catch (e: DateTimeException) {
                    throw ConfigException("TZ: unknown time zone '$zoneText'", e)
                }

            val ollamaText = get("OLLAMA_URL", "http://localhost:11434")
            val ollamaUrl =
                ollamaText.toHttpUrlOrNull()
                    ?: throw ConfigException("OLLAMA_URL: expected an http(s) address, got '$ollamaText'")

            return Env(
                imapHost = get("IMAP_HOST", "imap.gmail.com"),
                imapUser = required("IMAP_USER"),
                imapPassword = required("IMAP_PASSWORD"),
                telegramBotToken = required("TELEGRAM_BOT_TOKEN"),
                telegramChatId = required("TELEGRAM_CHAT_ID"),
                ollamaUrl = ollamaUrl,
                ollamaModel = get("OLLAMA_MODEL", "gemma4:e4b"),
                summaryLanguage = get("SUMMARY_LANGUAGE", "Russian"),
                scanInterval = Duration.ofMinutes(minutes.toLong()),
                zone = zone,
            )
        }
    }
}
