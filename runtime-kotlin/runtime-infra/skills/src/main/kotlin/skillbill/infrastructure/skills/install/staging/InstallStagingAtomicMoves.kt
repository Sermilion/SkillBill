package skillbill.infrastructure.skills.install.staging

import skillbill.infrastructure.host.jvm.atomicMoveReplacing
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

private val stagingSupportLog: Logger = Logger.getLogger("skillbill.install.InstallStagingIO")

internal fun writeInternalStagingFile(
  tempDir: Path,
  name: String,
  bytes: ByteArray,
): Path {
  val file = tempDir.resolve(name).normalize()
  require(file.parent == tempDir.toAbsolutePath().normalize()) {
    "Internal sidecar '$name' staging path '$file' escapes staging dir '$tempDir'."
  }
  Files.write(file, bytes)
  return file
}

internal fun promoteByBackupAndMove(
  tempDir: Path,
  finalStagingDir: Path,
) {
  val backup = finalStagingDir.resolveSibling(".${finalStagingDir.fileName}.backup-${UUID.randomUUID()}")
  atomicMoveReplacing(finalStagingDir, backup)
  try {
    atomicMoveReplacing(tempDir, finalStagingDir)
  } catch (error: IOException) {
    restoreInstallStagingBackup(backup, finalStagingDir, error)
    throw error
  }
  suppressedDelete(backup)
}

internal fun restoreInstallStagingBackup(
  backup: Path,
  finalStagingDir: Path,
  primaryError: Throwable,
) {
  suppressedDelete(finalStagingDir, primaryError)
  try {
    atomicMoveReplacing(backup, finalStagingDir)
  } catch (restoreError: IOException) {
    primaryError.addSuppressed(restoreError)
    stagingSupportLog.log(Level.SEVERE, "Failed to restore install staging backup '$backup'.", restoreError)
  }
}

internal fun suppressedDelete(
  path: Path,
  primaryError: Throwable? = null,
) {
  if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
    return
  }
  try {
    deleteInstallStagingDirectory(path)
  } catch (error: IOException) {
    primaryError?.addSuppressed(error)
    stagingSupportLog.log(Level.WARNING, "suppressedDelete failed path=$path (cleanup error suppressed)", error)
  } catch (error: UncheckedIOException) {
    primaryError?.addSuppressed(error)
    stagingSupportLog.log(Level.WARNING, "suppressedDelete failed path=$path (cleanup error suppressed)", error)
  }
}
