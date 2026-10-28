package dev.skillbill.desktop.core.data.session

import dev.skillbill.desktop.core.domain.session.StorageFailure
import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

private const val MAX_FAILURE_CAUSES = 8

/** Thrown by a session open when the database file does not exist. */
internal class DatabaseFileMissingException(path: String) : IOException("The database $path does not exist.")

/**
 * Maps a runtime or JVM failure to the storage failure the user sees, as `desktop-tray` does: every runtime code without a
 * database meaning is an incompatible record, and an IO failure is a missing store when the file is gone. Returns null for
 * anything else, which the caller rethrows.
 */
internal object StorageFailureClassifier {
  fun classify(error: Throwable, path: String): StorageFailure? =
    generateSequence(error) { it.cause }.take(MAX_FAILURE_CAUSES).firstNotNullOfOrNull { classifyOne(it, path) }

  private fun classifyOne(error: Throwable, path: String): StorageFailure? = when (error) {
    is SkillBillRuntimeException -> when (error.code) {
      DatabaseFailureCode.MISSING -> StorageFailure.Missing
      DatabaseFailureCode.ACCESS -> StorageFailure.Access
      DatabaseFailureCode.BUSY -> StorageFailure.Busy
      DatabaseFailureCode.CORRUPT -> StorageFailure.Corrupt
      DatabaseFailureCode.INCOMPATIBLE_SCHEMA -> StorageFailure.IncompatibleSchema
      else -> StorageFailure.IncompatibleRecord
    }
    is DatabaseFileMissingException -> StorageFailure.Missing
    is IOException -> if (isMissing(path)) StorageFailure.Missing else StorageFailure.Access
    is SecurityException, is InvalidPathException -> StorageFailure.Access
    else -> null
  }

  private fun isMissing(path: String): Boolean = try {
    Files.notExists(Path.of(path))
  } catch (_: InvalidPathException) {
    false
  }
}
