package org.willzyx.mailscan.telegram

/** [TelegramClient] that keeps what was [sent], or refuses everything while it is [down]. */
class FakeTelegramClient : TelegramClient {
    val sent = mutableListOf<String>()
    var down = false

    override suspend fun sendMessage(text: String) {
        if (down) throw TelegramException("Telegram sendMessage: no connection (timeout)")
        sent += text
    }
}
