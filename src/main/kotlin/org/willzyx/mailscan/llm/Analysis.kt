package org.willzyx.mailscan.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** How much an email matters to the user. */
enum class Importance {
    /** The user has to do or answer something soon. */
    HIGH,

    /** Concerns the user personally but is not urgent. */
    MEDIUM,

    /** Ads, newsletters, automatic notifications that need no action. */
    LOW,
}

/** What the model says about one email. */
data class Analysis(
    val importance: Importance,
    /** One to three sentences: who writes, what is needed, by when. */
    val summary: String,
    /** Why the model chose this importance. */
    val reason: String,
    /** Whether the user has to do something about the email. */
    val actionRequired: Boolean,
)

/**
 * Reads the model's [answer], making do with a broken one, since the email must not get lost for it.
 * No answer at all, or an importance that is not one of the three, counts as medium. A summary of a
 * word or two gives way to the reason, or else to the [subject] of the email: a straight quote in
 * the text can end the JSON string early and cut the summary short.
 */
internal fun toAnalysis(answer: JsonObject?, subject: String): Analysis {
    if (answer.isNullOrEmpty()) {
        return Analysis(Importance.MEDIUM, "Subject: $subject", "Could not parse the model's answer", false)
    }
    val importance = Importance.entries.firstOrNull { it.name.lowercase() == answer.string("importance") }
    val summary = answer.string("summary").trim()
    val reason = answer.string("reason").trim()
    return Analysis(
        importance = importance ?: Importance.MEDIUM,
        summary = listOf(summary, reason).firstOrNull { it.length >= MIN_SUMMARY_CHARS } ?: "Subject: $subject",
        reason = reason,
        actionRequired = (answer["action_required"] as? JsonPrimitive)?.booleanOrNull ?: false,
    )
}

private const val MIN_SUMMARY_CHARS = 20

/** The text of the field [name], or an empty string when the answer has no such text field. */
private fun JsonObject.string(name: String): String =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
