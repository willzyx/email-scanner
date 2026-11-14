package org.willzyx.mailscan.config

import java.time.DateTimeException
import java.time.Duration
import java.time.ZoneId
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ConfigException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Everything the process needs from its environment; see `.env.example`. A variable that is not set,
 * or holds nothing but spaces, takes its default; the one without a default must be set. The mailboxes
 * are not here: they are listed in a file of their own, see [MailboxConfig].
 */
data class Env(
    /** Of the bot that reports for every mailbox. */
    val telegramBotToken: String,
    val ollamaUrl: HttpUrl,
    val ollamaModel: String,
    /** The language the model writes summaries in: any language name it understands. */
    val summaryLanguage: String,
    val scanInterval: Duration,
    /** The zone "today" is taken in for the first scan's lookback. */
    val zone: ZoneId,
) {
    // The bot token stays out of logs and stack traces.
    override fun toString(): String =
        "Env(ollamaUrl=$ollamaUrl, ollamaModel=$ollamaModel, summaryLanguage=$summaryLanguage, " +
            "scanInterval=$scanInterval, zone=$zone)"

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
                telegramBotToken = required("TELEGRAM_BOT_TOKEN"),
                ollamaUrl = ollamaUrl,
                ollamaModel = get("OLLAMA_MODEL", "gemma4:e4b"),
                summaryLanguage = get("SUMMARY_LANGUAGE", "Russian"),
                scanInterval = Duration.ofMinutes(minutes.toLong()),
                zone = zone,
            )
        }
    }
}
