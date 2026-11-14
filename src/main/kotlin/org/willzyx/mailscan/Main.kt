package org.willzyx.mailscan

import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.willzyx.mailscan.config.ConfigException
import org.willzyx.mailscan.config.Env
import org.willzyx.mailscan.config.MailboxConfig
import org.willzyx.mailscan.db.CursorRepository
import org.willzyx.mailscan.db.impl.SqliteCursorRepository
import org.willzyx.mailscan.http.impl.OkHttpJson
import org.willzyx.mailscan.imap.impl.ImapMailbox
import org.willzyx.mailscan.llm.impl.OllamaAnalyzer
import org.willzyx.mailscan.scan.ScanLoop
import org.willzyx.mailscan.scan.Scanner
import org.willzyx.mailscan.telegram.impl.HttpTelegramClient

private val log = LoggerFactory.getLogger("mailscan")

/** Relative to the working directory: `/app/data`, the `mailscan-data` volume, in the container. */
private val DATABASE: Path = Path.of("data", "mailscan.db")

/** Relative to the working directory as well: compose mounts the file at `/app/mailboxes.yaml`. */
private val MAILBOXES: Path = Path.of("mailboxes.yaml")

/** The first email after an idle stretch also waits for the model to be loaded, which takes minutes on a CPU. */
private val OLLAMA_TIMEOUT = Duration.ofMinutes(10)
private val TELEGRAM_TIMEOUT = Duration.ofSeconds(30)

fun main() {
    val (env, mailboxes) =
        try {
            Env.load() to MailboxConfig.load(MAILBOXES)
        } catch (e: ConfigException) {
            System.err.println(e.message)
            exitProcess(2)
        }

    try {
        SqliteCursorRepository(DATABASE).use { cursors ->
            val users = mailboxes.joinToString { it.user }
            log.info("mailscan is up: {} every {} min ({})", users, env.scanInterval.toMinutes(), env.zone)
            runBlocking { scanLoop(env, mailboxes, cursors).run(env.scanInterval) }
        }
    } catch (e: Exception) {
        log.error("Fatal", e)
        exitProcess(1)
    }
}

/** A scanner per mailbox, each reporting to the chat of its mailbox; the model and the bot are the same for all. */
private fun scanLoop(env: Env, mailboxes: List<MailboxConfig>, cursors: CursorRepository): ScanLoop {
    val analyzer =
        OllamaAnalyzer(env.ollamaUrl, env.ollamaModel, env.summaryLanguage, OkHttpJson.create(OLLAMA_TIMEOUT))
    val telegram = OkHttpJson.create(TELEGRAM_TIMEOUT)
    val clock = Clock.system(env.zone)
    return ScanLoop(
        mailboxes.map { mailbox ->
            Scanner(
                account = mailbox.user,
                mailbox = ImapMailbox(mailbox.host, mailbox.user, mailbox.password),
                cursors = cursors,
                analyzer = analyzer,
                telegram = HttpTelegramClient(telegram, env.telegramBotToken, mailbox.chatId),
                clock = clock,
            )
        },
    )
}
