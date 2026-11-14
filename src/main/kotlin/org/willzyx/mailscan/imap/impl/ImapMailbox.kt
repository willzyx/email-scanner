package org.willzyx.mailscan.imap.impl

import jakarta.mail.FetchProfile
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.MessageRemovedException
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.ReceivedDateTerm
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Properties
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPMessage
import org.willzyx.mailscan.imap.Mailbox
import org.willzyx.mailscan.imap.MailboxException
import org.willzyx.mailscan.imap.MailboxSession

/**
 * The inbox of [user] on the IMAP server [host]. The connection is encrypted and the server has to
 * show a certificate issued for [host]; [secure] is switched off only to test against a local server.
 *
 * No more than [maxFetchBytes] of an email are fetched: its text is almost always at the start, and
 * an attachment of 25 MB would not fit into the memory the scanner runs in.
 */
class ImapMailbox(
    private val host: String,
    private val user: String,
    private val password: String,
    private val port: Int = 993,
    secure: Boolean = true,
    private val maxFetchBytes: Int = MAX_FETCH_BYTES,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Mailbox {
    private val protocol = if (secure) "imaps" else "imap"

    private val session: Session =
        Session.getInstance(
            Properties().apply {
                setProperty("mail.$protocol.connectiontimeout", TIMEOUT_MS)
                setProperty("mail.$protocol.timeout", TIMEOUT_MS)
                // An email is read in pieces of this size: most take one request, the largest allowed takes four.
                setProperty("mail.$protocol.fetchsize", FETCH_PIECE_BYTES)
                // Without this check any valid certificate would do, and the password would go to whoever answered.
                setProperty("mail.$protocol.ssl.checkserveridentity", "true")
            },
        )

    override suspend fun open(): MailboxSession =
        withContext(dispatcher) {
            val store = session.getStore(protocol)
            try {
                store.connect(host, port, user, password)
                val inbox = store.getFolder("INBOX") as IMAPFolder
                // Read-only: the server itself will refuse to change anything in the folder.
                inbox.open(Folder.READ_ONLY)
                ImapSession(store, inbox, maxFetchBytes, dispatcher)
            } catch (e: MessagingException) {
                store.closeQuietly()
                throw MailboxException("IMAP $host: ${e.message}", e)
            }
        }

    companion object {
        const val MAX_FETCH_BYTES = 4_000_000

        /** For connecting and for each answer of the server, in milliseconds. */
        private const val TIMEOUT_MS = "60000"
        private const val FETCH_PIECE_BYTES = "1048576"
    }
}

private class ImapSession(
    private val store: Store,
    private val inbox: IMAPFolder,
    private val maxFetchBytes: Int,
    private val dispatcher: CoroutineDispatcher,
) : MailboxSession {
    override suspend fun uidValidity(): Long =
        imap("UIDVALIDITY") {
            inbox.uidValidity.takeIf { it > 0 } ?: throw MailboxException("The server did not report UIDVALIDITY")
        }

    override suspend fun uidsAfter(lastUid: Long): List<Long> =
        imap("search by UID") {
            // The range "from this UID to the last one" holds the last email even when its UID is lower.
            uids(inbox.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID)).filter { it > lastUid }
        }

    override suspend fun uidsSince(day: LocalDate): List<Long> =
        imap("search by date") {
            // The search takes a day without a time; the library writes it out in this zone again.
            val start = Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant())
            uids(inbox.search(ReceivedDateTerm(ComparisonTerm.GE, start)))
        }

    override suspend fun fetch(uid: Long): ByteArray? =
        imap("fetch") {
            val message = inbox.getMessageByUID(uid) as IMAPMessage? ?: return@imap null
            // Peek: reading an email must not mark it as read.
            message.peek = true
            try {
                // The stream asks the server for the next piece only when it is read that far.
                message.mimeStream.use { it.readNBytes(maxFetchBytes) }
            } catch (_: MessageRemovedException) {
                null
            } catch (e: IOException) {
                // Deleted by another mail program since it was listed: the server has no content for it.
                if (message.isExpunged) null else throw e
            }
        }

    override fun close() = store.closeQuietly()

    /** The UIDs of [messages], in ascending order. */
    private fun uids(messages: Array<Message>): List<Long> {
        // One request for all the UIDs; asked one by one, each would be a round trip to the server.
        inbox.fetch(messages, FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) })
        return messages.map { inbox.getUID(it) }.sorted()
    }

    /** Runs [request] off the caller's thread; a failure becomes a [MailboxException] naming [what]. */
    private suspend fun <T> imap(what: String, request: () -> T): T =
        withContext(dispatcher) {
            try {
                request()
            } catch (e: MessagingException) {
                throw MailboxException("IMAP $what: ${e.message}", e)
            } catch (e: IOException) {
                throw MailboxException("IMAP $what: ${e.message}", e)
            }
        }
}

/** Logs out. A failure here changes nothing: the pass is over, and the server drops the connection by itself. */
private fun Store.closeQuietly() {
    try {
        close()
    } catch (_: MessagingException) {
    }
}
