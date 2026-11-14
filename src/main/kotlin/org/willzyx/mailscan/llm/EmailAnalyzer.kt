package org.willzyx.mailscan.llm

import org.willzyx.mailscan.mail.ParsedEmail

/** The model is down or did not answer. The pass stops there, and the email is tried again in the next one. */
class LlmUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Rates an email and sums it up. An interface so the scanner can be tested without a model. */
fun interface EmailAnalyzer {
    /** Throws an [LlmUnavailableException] when the model cannot be asked. */
    suspend fun analyze(mail: ParsedEmail): Analysis
}
