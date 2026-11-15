package org.willzyx.mailscan.llm.impl

import org.willzyx.mailscan.db.AnalyzedEmail
import org.willzyx.mailscan.llm.DigestWriter
import org.willzyx.mailscan.llm.LlmUnavailableException
import org.willzyx.mailscan.llm.digestEntry
import org.willzyx.mailscan.llm.digestParts
import org.willzyx.mailscan.llm.digestPrompt
import org.willzyx.mailscan.llm.mergePrompt

/**
 * [DigestWriter] on the model of [chat], which writes in [language]. The model gets the emails most
 * important first, then in the order they were rated, and answers in free text, a paragraph of prose.
 *
 * The emails that fit one request are retold in one. More of them are retold portion by portion, and
 * the model then joins the parts, in several rounds when the parts do not fit one request either.
 */
class OllamaDigestWriter(
    private val chat: OllamaChat,
    language: String,
) : DigestWriter {
    private val digestPrompt = digestPrompt(language)
    private val mergePrompt = mergePrompt(language)

    override suspend fun write(emails: List<AnalyzedEmail>): String {
        val sorted = emails.sortedWith(compareBy({ it.analysis.importance }, { it.analyzedAt }))
        var parts = portions(sorted.map { digestEntry(it) }).map { ask(digestPrompt, it.joinToString(SEPARATOR)) }
        // A request takes at least two parts, so every round leaves fewer of them.
        while (parts.size > 1) {
            parts = portions(parts).map { if (it.size == 1) it.single() else ask(mergePrompt, digestParts(it)) }
        }
        return parts.single()
    }

    /** The model's answer to [user] under [system], trimmed and at most [MAX_CHARS] long. */
    private suspend fun ask(system: String, user: String): String {
        val answer = chat.ask(system, user, maxTokens = MAX_TOKENS)?.trim().orEmpty()
        // Unlike a rating, a digest has nothing to fall back on: better a minute late than empty.
        if (answer.isEmpty()) throw LlmUnavailableException("Ollama wrote an empty digest")
        return if (answer.length > MAX_CHARS) answer.take(MAX_CHARS - 1) + "…" else answer
    }

    private companion object {
        /**
         * How much text a request takes besides the instructions. The context of 4096 tokens also
         * holds the instructions, about 300 tokens, and the answer; the rest is enough for this many
         * characters even of text that takes a token for every two of them.
         */
        const val INPUT_MAX_CHARS = 6000

        /** Where Ollama cuts an answer off: the prompt asks for less, and this stops a model that does not listen. */
        const val MAX_TOKENS = 700

        /**
         * A digest with its heading has to fit one Telegram message of 4096 characters, and several
         * parts of one have to fit [INPUT_MAX_CHARS].
         */
        const val MAX_CHARS = 1500

        const val SEPARATOR = "\n\n"

        /** [texts] in their order, split into runs that each fit one request; a run has at least one text. */
        fun portions(texts: List<String>): List<List<String>> {
            val portions = mutableListOf<MutableList<String>>()
            var chars = 0
            for (text in texts) {
                val added = text.length + SEPARATOR.length
                if (portions.isEmpty() || chars + added > INPUT_MAX_CHARS) {
                    portions += mutableListOf<String>()
                    chars = 0
                }
                portions.last() += text
                chars += added
            }
            return portions
        }
    }
}
