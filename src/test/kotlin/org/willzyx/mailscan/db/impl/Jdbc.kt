package org.willzyx.mailscan.db.impl

import java.nio.file.Path
import java.sql.DriverManager
import java.sql.Statement

/** Runs [block] on a connection of its own to the SQLite file at [file]. */
internal fun jdbc(file: Path, block: Statement.() -> Unit) {
    DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
        connection.createStatement().use(block)
    }
}

/** The rows [sql] selects from the SQLite file at [file], each as its column values joined with " | ". */
internal fun query(file: Path, sql: String): List<String> {
    val result = mutableListOf<String>()
    jdbc(file) {
        executeQuery(sql).use { rows ->
            val columns = 1..rows.metaData.columnCount
            while (rows.next()) result += columns.joinToString(" | ") { rows.getString(it) }
        }
    }
    return result
}
