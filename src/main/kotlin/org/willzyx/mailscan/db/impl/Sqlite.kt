package org.willzyx.mailscan.db.impl

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Opens the SQLite file at [path]; the file and its directory are created when missing. The
 * repositories of this package all work through the one connection; close it when done.
 */
fun openSqlite(path: Path): Connection {
    path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    return DriverManager.getConnection("jdbc:sqlite:$path")
}
