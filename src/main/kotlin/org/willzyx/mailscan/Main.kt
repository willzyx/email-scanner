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
import org.willzyx.mailscan.db.AnalysisRepository
import org.willzyx.mailscan.db.CursorRepository
import org.willzyx.mailscan.db.DigestRepository
import org.willzyx.mailscan.db.impl.SqliteAnalysisRepository
import org.willzyx.mailscan.db.impl.SqliteCursorRepository
import org.willzyx.mailscan.db.impl.SqliteDigestRepository
import org.willzyx.mailscan.http.impl.OkHttpJson
import org.willzyx.mailscan.imap.impl.ImapMailbox
import org.willzyx.mailscan.llm.impl.OllamaAnalyzer
import org.willzyx.mailscan.llm.impl.OllamaChat
import org.willzyx.mailscan.llm.impl.OllamaDigestWriter
import org.willzyx.mailscan.scan.Digester
import org.willzyx.mailscan.scan.ScanLoop
import org.willzyx.mailscan.scan.Scanner
import org.willzyx.mailscan.telegram.impl.HttpTelegramClient

private val log = LoggerFactory.getLogger("mailscan")

/** Relative to the working directory: `/app/data`, the `mailscan-data` volume, in the container. */
private val DATABASE: Path = Path.of("data", "mailscan.db")

/** Relative to the working directory as well: compose mounts the file at `/app/mailboxes.yaml`. */
private val MAILBOXES: Path = Path.of("mailboxes.yaml")

/** The first request after an idle stretch also waits for the model to be loaded, which takes minutes on a CPU. */
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
            SqliteAnalysisRepository(DATABASE).use { analyses ->
                SqliteDigestRepository(DATABASE).use { digests ->
                    log.info(
                        "mailscan is up: {} every {} min, digests at {} ({})",
                        mailboxes.joinToString { it.user },
                        env.scanInterval.toMinutes(),
                        env.digestTimes.joinToString(),
                        env.zone,
                    )
                    runBlocking { scanLoop(env, mailboxes, cursors, analyses, digests).run(env.scanInterval) }
                }
            }
        }
    } catch (e: Exception) {
        log.error("Fatal", e)
        exitProcess(1)
    }
}

/**
 * A scanner and a digester per mailbox, both reporting to the chat of their mailbox; the model, the bot
 * and the database are the same for all.
 */
private fun scanLoop(
    env: Env,
    mailboxes: List<MailboxConfig>,
    cursors: CursorRepository,
    analyses: AnalysisRepository,
    digests: DigestRepository,
): ScanLoop {
    val chat = OllamaChat(env.ollamaUrl, env.ollamaModel, OkHttpJson.create(OLLAMA_TIMEOUT))
    val analyzer = OllamaAnalyzer(chat, env.summaryLanguage)
    val writer = OllamaDigestWriter(chat, env.summaryLanguage)
    val http = OkHttpJson.create(TELEGRAM_TIMEOUT)
    val clock = Clock.system(env.zone)
    return ScanLoop(
        mailboxes.map { mailbox ->
            val imap = ImapMailbox(mailbox.host, mailbox.user, mailbox.password)
            val telegram = HttpTelegramClient(http, env.telegramBotToken, mailbox.chatId)
            Scanner(mailbox.user, imap, cursors, analyses, analyzer, telegram, clock) to
                Digester(mailbox.user, analyses, digests, writer, telegram, clock, env.digestTimes)
        },
        analyses,
        clock,
    )
}
