package org.willzyx.mailscan.http

import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl

/** [JsonHttp] that answers with whatever [answer] returns or throws, and remembers what it was asked. */
class FakeJsonHttp(private val answer: (url: HttpUrl, body: JsonElement) -> JsonElement) : JsonHttp {
    data class Call(val url: HttpUrl, val body: JsonElement)

    val calls = mutableListOf<Call>()

    override suspend fun post(url: HttpUrl, body: JsonElement, what: String): JsonElement {
        calls += Call(url, body)
        return answer(url, body)
    }
}
