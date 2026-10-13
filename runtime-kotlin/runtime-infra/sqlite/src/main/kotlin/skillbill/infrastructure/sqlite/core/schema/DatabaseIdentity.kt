package skillbill.infrastructure.sqlite.core.schema

import skillbill.error.core.DatabaseAccessOperation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.sql.SQLException

internal fun databaseFileIdentity(attributes: BasicFileAttributes): String =
  attributes.fileKey()?.toString()
    ?: "creation-time:${attributes.creationTime().toMillis()}"

internal data class DatabaseIdentity(
  internal val absolutePath: String,
  internal val userVersion: Int,
  internal val fileIdentity: String,
  internal val fileSizeBytes: Long,
) {
  fun matches(current: DatabaseIdentity): Boolean =
    current.absolutePath == absolutePath &&
      current.userVersion == userVersion &&
      current.fileIdentity == fileIdentity &&
      current.fileSizeBytes >= fileSizeBytes

  companion object {
    fun read(path: Path): DatabaseIdentity? {
      if (!Files.exists(path)) return null
      val normalized = path.toAbsolutePath().normalize()
      val attrs = Files.readAttributes(normalized, BasicFileAttributes::class.java)
      val userVersion = readUserVersion(normalized)
      return DatabaseIdentity(
        absolutePath = normalized.toString(),
        userVersion = userVersion,
        fileIdentity = databaseFileIdentity(attrs),
        fileSizeBytes = attrs.size(),
      )
    }

    internal fun readUserVersion(path: Path): Int {
      try {
        DatabaseRuntime.openReadConnectionAt(path).use { database ->
          val connection = database.connection
          connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { rows ->
              if (!rows.next()) {
                throw databaseAccessError(
                  path,
                  DatabaseAccessOperation.READ,
                  SQLException("PRAGMA user_version returned no row"),
                )
              }
              return rows.getInt(1)
            }
          }
        }
      } catch (error: SQLException) {
        throw databaseAccessError(path, DatabaseAccessOperation.READ, error)
      }
    }
  }
}
