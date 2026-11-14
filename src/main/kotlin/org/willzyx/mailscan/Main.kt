package org.willzyx.mailscan

import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.willzyx.mailscan.config.ConfigException
import org.willzyx.mailscan.config.Env
import org.willzyx.mailscan.db.CursorRepository
import org.willzyx.mailscan.db.impl.SqliteCursorRepository
import org.willzyx.mailscan.http.impl.OkHttpJson
import org.willzyx.mailscan.imap.impl.ImapMailbox
import org.willzyx.mailscan.llm.impl.OllamaAnalyzer
import org.willzyx.mailscan.scan.Scanner
import org.willzyx.mailscan.telegram.impl.HttpTelegramClient

private val log = LoggerFactory.getLogger("mailscan")

/** Relative to the working directory: `/app/data`, the `mailscan-data` volume, in the container. */
private val DATABASE: Path = Path.of("data", "mailscan.db")

/** The first email after an idle stretch also waits for the model to be loaded, which takes minutes on a CPU. */
private val OLLAMA_TIMEOUT = Duration.ofMinutes(10)
private val TELEGRAM_TIMEOUT = Duration.ofSeconds(30)

fun main() {
    val env =
        try {
            Env.load()
        } catch (e: ConfigException) {
            System.err.println(e.message)
            exitProcess(2)
        }

    try {
        SqliteCursorRepository(DATABASE).use { cursors ->
            log.info("mailscan is up: {} every {} min ({})", env.imapUser, env.scanInterval.toMinutes(), env.zone)
            runBlocking { scanner(env, cursors).run(env.scanInterval) }
        }
    } catch (e: Exception) {
        log.error("Fatal", e)
        exitProcess(1)
    }
}

private fun scanner(env: Env, cursors: CursorRepository): Scanner {
    val ollama = OkHttpJson.create(OLLAMA_TIMEOUT)
    val telegram = OkHttpJson.create(TELEGRAM_TIMEOUT)
    return Scanner(
        account = env.imapUser,
        mailbox = ImapMailbox(env.imapHost, env.imapUser, env.imapPassword),
        cursors = cursors,
        analyzer = OllamaAnalyzer(env.ollamaUrl, env.ollamaModel, env.summaryLanguage, ollama),
        telegram = HttpTelegramClient(telegram, env.telegramBotToken, env.telegramChatId),
        clock = Clock.system(env.zone),
    )
}
