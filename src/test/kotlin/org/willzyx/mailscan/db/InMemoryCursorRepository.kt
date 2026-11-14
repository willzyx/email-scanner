package org.willzyx.mailscan.db

/** [CursorRepository] that lives as long as the test. */
class InMemoryCursorRepository : CursorRepository {
    private val cursors = mutableMapOf<String, Cursor>()

    override suspend fun get(account: String): Cursor? = cursors[account]

    override suspend fun set(account: String, cursor: Cursor) {
        cursors[account] = cursor
    }
}
