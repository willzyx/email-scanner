package org.willzyx.mailscan.db

/** [AnalysisRepository] that lives as long as the test. */
class InMemoryAnalysisRepository : AnalysisRepository {
    private val emails = linkedMapOf<Triple<String, Long, Long>, AnalyzedEmail>()

    /** The emails kept for the mailbox of [account], in the order they were first saved. */
    fun of(account: String): List<AnalyzedEmail> = emails.filterKeys { it.first == account }.values.toList()

    override suspend fun save(account: String, email: AnalyzedEmail) {
        emails[Triple(account, email.uidValidity, email.uid)] = email
    }
}
