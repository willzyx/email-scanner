package org.willzyx.mailscan.llm.impl

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.willzyx.mailscan.llm.ANSWER_SCHEMA
import org.willzyx.mailscan.llm.Analysis
import org.willzyx.mailscan.llm.EmailAnalyzer
import org.willzyx.mailscan.llm.systemPrompt
import org.willzyx.mailscan.llm.toAnalysis
import org.willzyx.mailscan.llm.userMessage
import org.willzyx.mailscan.mail.ParsedEmail

/**
 * [EmailAnalyzer] on the model of [chat]: one request per email, with the answer held to
 * [ANSWER_SCHEMA]. The model writes its summaries in [language].
 */
class OllamaAnalyzer(
    private val chat: OllamaChat,
    language: String,
) : EmailAnalyzer {
    private val systemPrompt = systemPrompt(language)

    override suspend fun analyze(mail: ParsedEmail): Analysis =
        toAnalysis(jsonObject(chat.ask(systemPrompt, userMessage(mail), ANSWER_SCHEMA)), mail.subject)
}

/** The JSON object the model wrote as its [answer], or null when it wrote something else or nothing. */
private fun jsonObject(answer: String?): JsonObject? =
    try {
        answer?.let { Json.parseToJsonElement(it) as? JsonObject }
    } catch (_: SerializationException) {
        null
    }
