package skillbill.infrastructure.sqlite

import skillbill.error.core.RejectedOutputDiagnosticFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rejectedOutputDiagnosticPersistenceMessage
import skillbill.infrastructure.sqlite.core.schema.databasePath
import skillbill.ports.diagnostics.RejectedOutputDiagnosticPermissions
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

internal class FileRejectedOutputDiagnosticPermissions(
  private val databasePath: Path,
  private val diagnostics: RuntimeDiagnostics,
  private val posixViewAvailable: () -> Boolean = {
    databasePath.fileSystem.supportedFileAttributeViews().contains("posix")
  },
  private val setPosixPermissions: (Path, Set<PosixFilePermission>) -> Unit = Files::setPosixFilePermissions,
) : RejectedOutputDiagnosticPermissions {
  override fun applyRestrictivePermissions() {
    if (!posixViewAvailable()) {
      diagnostics.warning(
        "Rejected output diagnostic permissions skipped: posix file attribute view is unavailable.",
      )
      return
    }
    try {
      databasePath.parent?.let { parent ->
        if (Files.exists(parent)) {
          setPosixPermissions(
            parent,
            setOf(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE,
            ),
          )
        }
      }
      if (Files.exists(databasePath)) {
        setPosixPermissions(
          databasePath,
          setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
      }
    } catch (error: IOException) {
      throw SkillBillRuntimeException(
        RejectedOutputDiagnosticFailureCode.PERSISTENCE,
        rejectedOutputDiagnosticPersistenceMessage("apply-restrictive-permissions"),
        error,
      )
    }
  }
}
