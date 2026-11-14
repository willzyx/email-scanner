package org.willzyx.mailscan.telegram

/** Telegram refused a message or cannot be reached. The message of the exception never holds the bot token. */
class TelegramException(message: String) : Exception(message)

/** The Telegram chat the scanner reports to. An interface so the scanner can be tested without Telegram. */
fun interface TelegramClient {
    /** Sends [text], written in Telegram's HTML, to the chat. Throws a [TelegramException] when that fails. */
    suspend fun sendMessage(text: String)
}
