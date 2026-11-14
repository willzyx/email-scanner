package org.willzyx.mailscan.llm

import org.willzyx.mailscan.mail.ParsedEmail

/**
 * [EmailAnalyzer] that rates an email by its subject, low unless [importance] says otherwise, and
 * remembers the subjects it [saw]. It is down for the subjects in [downFor].
 */
class FakeAnalyzer(private val importance: Map<String, Importance> = emptyMap()) : EmailAnalyzer {
    val saw = mutableListOf<String>()
    var downFor: Set<String> = emptySet()

    override suspend fun analyze(mail: ParsedEmail): Analysis {
        saw += mail.subject
        if (mail.subject in downFor) throw LlmUnavailableException("Ollama is unavailable: connection refused")
        return Analysis(
            importance = importance[mail.subject] ?: Importance.LOW,
            summary = "Summary of ${mail.subject}",
            reason = "reason",
            actionRequired = false,
        )
    }
}
