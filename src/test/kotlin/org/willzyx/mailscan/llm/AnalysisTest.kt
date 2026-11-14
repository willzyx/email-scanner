package org.willzyx.mailscan.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private const val SUBJECT = "Photos from the cottage"

class AnalysisTest {
    @Test
    fun `reads a complete answer`() {
        val answer =
            """
            {"importance": "high", "summary": " Anya asks to call her back today. ",
             "reason": "An email from a person that expects an action", "action_required": true}
            """
        assertEquals(
            Analysis(
                importance = Importance.HIGH,
                summary = "Anya asks to call her back today.",
                reason = "An email from a person that expects an action",
                actionRequired = true,
            ),
            toAnalysis(json(answer), SUBJECT),
        )
    }

    @Test
    fun `reads each importance`() {
        for (importance in Importance.entries) {
            val answer = json("""{"importance": "${importance.name.lowercase()}", "summary": "$LONG_ENOUGH"}""")
            assertEquals(importance, toAnalysis(answer, SUBJECT).importance)
        }
    }

    @Test
    fun `an unknown or missing importance becomes medium`() {
        for (importance in listOf(""""urgent"""", """"HIGH"""", "1", "null", "[]")) {
            val answer = json("""{"importance": $importance, "summary": "$LONG_ENOUGH"}""")
            assertEquals(Importance.MEDIUM, toAnalysis(answer, SUBJECT).importance, importance)
        }
        assertEquals(Importance.MEDIUM, toAnalysis(json("""{"summary": "$LONG_ENOUGH"}"""), SUBJECT).importance)
    }

    @Test
    fun `a summary cut short gives way to the reason`() {
        val answer =
            """
            {"importance": "low", "summary": "The company ",
             "reason": "Looks like phishing: asks to pay a fee via a link", "action_required": false}
            """
        val analysis = toAnalysis(json(answer), SUBJECT)
        assertEquals("Looks like phishing: asks to pay a fee via a link", analysis.summary)
        assertEquals(Importance.LOW, analysis.importance)
    }

    @Test
    fun `a short summary with a short reason gives way to the subject`() {
        val analysis = toAnalysis(json("""{"importance": "low", "summary": "", "reason": "x"}"""), SUBJECT)
        assertEquals(Analysis(Importance.LOW, "Subject: $SUBJECT", "x", false), analysis)
    }

    @Test
    fun `no answer is rated medium, with the subject for a summary`() {
        val expected = Analysis(Importance.MEDIUM, "Subject: $SUBJECT", "Could not parse the model's answer", false)
        assertEquals(expected, toAnalysis(null, SUBJECT))
        assertEquals(expected, toAnalysis(JsonObject(emptyMap()), SUBJECT))
    }

    @Test
    fun `fields of a wrong kind are read as missing`() {
        val answer = json("""{"importance": "low", "summary": 42, "reason": ["a"], "action_required": "yes"}""")
        assertEquals(Analysis(Importance.LOW, "Subject: $SUBJECT", "", false), toAnalysis(answer, SUBJECT))
    }
}

private const val LONG_ENOUGH = "A summary that is long enough."

private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
