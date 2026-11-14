package org.willzyx.mailscan.llm.impl

import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import org.willzyx.mailscan.http.JsonHttp
import org.willzyx.mailscan.llm.ANSWER_SCHEMA
import org.willzyx.mailscan.llm.Analysis
import org.willzyx.mailscan.llm.EmailAnalyzer
import org.willzyx.mailscan.llm.LlmUnavailableException
import org.willzyx.mailscan.llm.systemPrompt
import org.willzyx.mailscan.llm.toAnalysis
import org.willzyx.mailscan.llm.userMessage
import org.willzyx.mailscan.mail.ParsedEmail

/**
 * [EmailAnalyzer] on a model served by Ollama at [baseUrl]: one `/api/chat` request per email, with
 * the answer held to [ANSWER_SCHEMA]. The model writes its summaries in [language]. On a CPU, and
 * right after the model is loaded, an answer takes minutes, so [http] has to wait that long.
 */
class OllamaAnalyzer(
    private val baseUrl: HttpUrl,
    private val model: String,
    language: String,
    private val http: JsonHttp,
) : EmailAnalyzer {
    private val systemPrompt = systemPrompt(language)

    override suspend fun analyze(mail: ParsedEmail): Analysis {
        val request =
            buildJsonObject {
                put("model", model)
                put("stream", false)
                put("format", ANSWER_SCHEMA)
                // Thinking on a CPU adds minutes to every email, and the ratings are as good without it.
                // A model that cannot think takes the flag without complaint.
                put("think", false)
                put("keep_alive", KEEP_ALIVE)
                putJsonObject("options") {
                    put("temperature", 0)
                    put("num_ctx", NUM_CTX)
                }
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", userMessage(mail))
                    }
                }
            }
        val response =
            try {
                http.post(baseUrl.newBuilder().addPathSegments("api/chat").build(), request, "Ollama /api/chat")
            } catch (e: IOException) {
                throw LlmUnavailableException("Ollama is unavailable: ${e.message}", e)
            }
        return toAnalysis(answer(response), mail.subject)
    }

    private companion object {
        /** How long Ollama keeps the model in memory after a request: a backlog runs warm, idle time frees the RAM. */
        const val KEEP_ALIVE = "1m"

        /** The context window in tokens: room for the prompt, an email cut to its limit and the answer. */
        const val NUM_CTX = 4096
    }
}

/** The JSON object the model wrote as its message, or null when it wrote something else. */
private fun answer(response: JsonElement): JsonObject? {
    val message = (response as? JsonObject)?.get("message") as? JsonObject
    val content = message?.get("content") as? JsonPrimitive ?: return null
    return try {
        Json.parseToJsonElement(content.content) as? JsonObject
    } catch (_: SerializationException) {
        null
    }
}
