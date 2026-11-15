package org.willzyx.mailscan.llm.impl

import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import org.willzyx.mailscan.http.JsonHttp
import org.willzyx.mailscan.llm.LlmUnavailableException

/**
 * The [model] served by Ollama at [baseUrl], asked one question per `/api/chat` request. On a CPU, and
 * right after the model is loaded, an answer takes minutes, so [http] has to wait that long.
 */
class OllamaChat(
    private val baseUrl: HttpUrl,
    private val model: String,
    private val http: JsonHttp,
) {
    /**
     * Asks the model [user] under the instructions of [system]. With [format] its answer is held to
     * that JSON schema, and without one it is free text. With [maxTokens] the answer is cut off at
     * that many tokens. Returns what the model wrote, or null when the response carries no text.
     *
     * @throws LlmUnavailableException when Ollama cannot be reached or refuses the request
     */
    suspend fun ask(system: String, user: String, format: JsonObject? = null, maxTokens: Int? = null): String? {
        val request =
            buildJsonObject {
                put("model", model)
                put("stream", false)
                if (format != null) put("format", format)
                // Thinking on a CPU adds minutes to every answer, and the answers are as good without it.
                // A model that cannot think takes the flag without complaint.
                put("think", false)
                put("keep_alive", KEEP_ALIVE)
                putJsonObject("options") {
                    put("temperature", 0)
                    put("num_ctx", NUM_CTX)
                    if (maxTokens != null) put("num_predict", maxTokens)
                }
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", system)
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", user)
                    }
                }
            }
        val response =
            try {
                http.post(baseUrl.newBuilder().addPathSegments("api/chat").build(), request, "Ollama /api/chat")
            } catch (e: IOException) {
                throw LlmUnavailableException("Ollama is unavailable: ${e.message}", e)
            }
        val message = (response as? JsonObject)?.get("message") as? JsonObject
        return (message?.get("content") as? JsonPrimitive)?.content
    }

    private companion object {
        /** How long Ollama keeps the model in memory after a request: a backlog runs warm, idle time frees the RAM. */
        const val KEEP_ALIVE = "1m"

        /** The context window in tokens: room for the instructions, the question and the answer. */
        const val NUM_CTX = 4096
    }
}
