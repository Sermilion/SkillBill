package skillbill.infrastructure.sqlite

import skillbill.error.core.RejectedOutputDiagnosticFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.sqlite.core.schema.databasePath
import java.io.IOException
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileRejectedOutputDiagnosticPermissionsTest {
  @Test
  fun `applyRestrictivePermissions skips when posix view is unavailable`() {
    val archive = Files.createTempFile("rejected-output-perms", ".zip")
    Files.delete(archive)
    FileSystems.newFileSystem(URI.create("jar:${archive.toUri()}"), mapOf("create" to "true")).use { fileSystem ->
      SqliteTestDiagnostics.reset()
      FileRejectedOutputDiagnosticPermissions(
        databasePath = fileSystem.getPath("/metrics.db"),
        diagnostics = SqliteTestDiagnostics,
      ).applyRestrictivePermissions()

      assertEquals(1, SqliteTestDiagnostics.recordedWarnings().size)
      assertTrue(
        SqliteTestDiagnostics.recordedWarnings().single().contains("posix file attribute view is unavailable"),
      )
    }
  }

  @Test
  fun `posix permission failure becomes persistence error`() {
    val tempDir = Files.createTempDirectory("rejected-output-perms-io")
    val dbPath = tempDir.resolve("metrics.db")
    Files.createFile(dbPath)

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        FileRejectedOutputDiagnosticPermissions(
          databasePath = dbPath,
          diagnostics = SqliteTestDiagnostics,
          setPosixPermissions = { _: Path, _: Set<PosixFilePermission> ->
            throw IOException("permission probe")
          },
        ).applyRestrictivePermissions()
      }

    assertEquals(RejectedOutputDiagnosticFailureCode.PERSISTENCE, error.code)
    assertTrue(error.message.orEmpty().contains("apply-restrictive-permissions"))
  }
}
