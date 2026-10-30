package skillbill.infrastructure.sqlite.core.schema

import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import skillbill.error.core.DatabaseAccessOperation
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.databaseAccessFailure
import skillbill.error.core.databaseBusy
import skillbill.error.core.databaseCorrupt
import skillbill.error.core.databaseMissing
import skillbill.ports.filesystem.model.DatabaseFileObservation
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.sql.Connection
import java.sql.SQLException

internal fun fileObservation(
  path: Path,
  operation: DatabaseAccessOperation = DatabaseAccessOperation.OPEN,
): DatabaseFileObservation {
  val normalized = path.toAbsolutePath().normalize()
  val attributes =
    try {
      Files.readAttributes(normalized, BasicFileAttributes::class.java)
    } catch (failure: IOException) {
      throw when (failure) {
        is NoSuchFileException -> databaseMissing(normalized.toString(), operation)
        else -> databaseAccessFailure(normalized.toString(), operation, "file identity cannot be read", failure)
      }
    }
  return DatabaseFileObservation(normalized, databaseFileIdentity(attributes))
}

internal fun databaseFileIdentity(attributes: BasicFileAttributes): String =
  attributes.fileKey()?.toString()
    ?: "creation-time:${attributes.creationTime().toMillis()}"

internal fun Connection.databasePath(): Path {
  val url = metaData.url ?: error("sqlite connection url is missing")
  val raw = url.removePrefix("jdbc:sqlite:")
  return Path.of(raw).toAbsolutePath().normalize()
}

internal fun databaseAccessError(
  dbPath: Path,
  operation: DatabaseAccessOperation,
  error: SQLException,
): SkillBillRuntimeException {
  if (error.isSqliteBusy()) return databaseBusy(error)
  val normalizedPath = dbPath.toAbsolutePath().normalize()
  if (Files.notExists(normalizedPath)) return databaseMissing(normalizedPath.toString(), operation)
  if (error.isSqliteCorrupt()) return databaseCorrupt(normalizedPath.toString(), operation, error)
  return databaseAccessFailure(
    dbPath = normalizedPath.toString(),
    operation = operation,
    condition = "sqlite result code ${error.errorCode}",
    cause = error,
  )
}

private fun Throwable.isSqliteBusy(): Boolean =
  generateSequence(this) { it.cause }.any { failure ->
    failure is SQLiteException &&
      (failure.resultCode.code and SQLITE_PRIMARY_CODE_MASK) == SQLiteErrorCode.SQLITE_BUSY.code
  }

private fun Throwable.isSqliteCorrupt(): Boolean =
  generateSequence(this) { it.cause }.any { failure ->
    failure is SQLiteException &&
      (failure.resultCode.code and SQLITE_PRIMARY_CODE_MASK) in SQLITE_CORRUPT_PRIMARY_CODES
  }

private const val SQLITE_PRIMARY_CODE_MASK = 0xFF
private val SQLITE_CORRUPT_PRIMARY_CODES =
  setOf(SQLiteErrorCode.SQLITE_CORRUPT.code, SQLiteErrorCode.SQLITE_NOTADB.code)
