package org.willzyx.mailscan.config

import com.fasterxml.jackson.annotation.JsonProperty
import java.nio.file.Path
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue

/**
 * One mailbox to scan and the Telegram chat its emails are reported to: an entry of `mailboxes.yaml`,
 * see `mailboxes.example.yaml`.
 */
data class MailboxConfig(
    /** The address the mailbox is logged in to with. */
    val user: String,
    val password: String,
    @param:JsonProperty("chat_id") val chatId: String,
    val host: String = "imap.gmail.com",
) {
    // The password stays out of logs and stack traces.
    override fun toString(): String = "MailboxConfig(user=$user, chatId=$chatId, host=$host)"

    companion object {
        /**
         * The mailboxes listed in the YAML file at [path], in the order they are written in. A file
         * that is missing or is not what is expected is a [ConfigException].
         */
        fun load(path: Path): List<MailboxConfig> {
            val yaml =
                YAMLMapper.builder()
                    .addModule(kotlinModule())
                    // A mistyped `host` must not pass for a mailbox on the default server.
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build()
            val mailboxes =
                try {
                    yaml.readValue<MailboxesFile>(path.toFile()).mailboxes
                } catch (e: JacksonException) {
                    throw ConfigException("$path: ${e.message}", e)
                }
            if (mailboxes.isEmpty()) throw ConfigException("$path: no mailboxes are listed")
            // The scan position is kept by the address: two mailboxes cannot share it.
            if (mailboxes.distinctBy { it.user }.size < mailboxes.size) {
                throw ConfigException("$path: a mailbox is listed more than once")
            }
            return mailboxes
        }
    }
}

private data class MailboxesFile(val mailboxes: List<MailboxConfig>)
