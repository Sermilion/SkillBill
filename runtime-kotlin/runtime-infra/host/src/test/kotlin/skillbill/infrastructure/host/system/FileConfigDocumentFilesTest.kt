package skillbill.infrastructure.host.system

import org.junit.jupiter.api.io.TempDir
import skillbill.ports.config.model.ConfigDocumentFileRead
import skillbill.ports.config.model.ConfigDocumentFormat
import skillbill.ports.config.model.ConfigDocumentReplaceRequest
import skillbill.ports.config.model.ConfigDocumentReplaceResult
import skillbill.ports.config.model.ConfigDocumentTree
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.projectstore.FixedProjectStorePort
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FileConfigDocumentFilesTest {
  @Test
  fun `yaml rewrite preserves decimal precision`(
    @TempDir tempDir: Path,
  ) {
    val path = tempDir.resolve("config.yaml")
    val adapter = FileConfigDocumentFiles(NoopRuntimeDiagnostics, FixedProjectStorePort(tempDir.resolve("store")))
    Files.writeString(path, "custom: 0.1234567890123456789\n")
    val read = assertIs<ConfigDocumentFileRead.Present>(adapter.read(path, ConfigDocumentFormat.YAML))

    assertIs<ConfigDocumentReplaceResult.Written>(
      adapter.replace(ConfigDocumentReplaceRequest(path, path, read.fingerprint, ConfigDocumentFormat.YAML, read.tree)),
    )

    val reread = assertIs<ConfigDocumentFileRead.Present>(adapter.read(path, ConfigDocumentFormat.YAML))
    assertEquals(BigDecimal("0.1234567890123456789"), reread.tree.value("custom"))
  }

  @Test
  fun `yaml stream with multiple documents is unreadable`(
    @TempDir tempDir: Path,
  ) {
    val path = tempDir.resolve("config.yaml")
    val adapter = FileConfigDocumentFiles(NoopRuntimeDiagnostics, FixedProjectStorePort(tempDir.resolve("store")))
    Files.writeString(path, "first: one\n---\nsecond: two\n")

    assertIs<ConfigDocumentFileRead.Malformed>(adapter.read(path, ConfigDocumentFormat.YAML))
  }

  @Test
  fun `replace refuses a changed baseline and then writes against the refreshed fingerprint`(
    @TempDir tempDir: Path,
  ) {
    val path = tempDir.resolve("config.json")
    Files.writeString(path, "{\"existing\":true}\n")
    val adapter = FileConfigDocumentFiles(NoopRuntimeDiagnostics, FixedProjectStorePort(tempDir.resolve("store")))
    val initial = adapter.read(path, ConfigDocumentFormat.JSON)
    val initialFingerprint = assertIs<ConfigDocumentFileRead.Present>(initial).fingerprint
    val externalBytes = "{\"external\":true}\n".toByteArray()
    Files.write(path, externalBytes)

    val refused =
      adapter.replace(
        ConfigDocumentReplaceRequest(
          path,
          path,
          initialFingerprint,
          ConfigDocumentFormat.JSON,
          ConfigDocumentTree(mapOf("service" to true)),
        ),
      )

    assertIs<ConfigDocumentReplaceResult.ChangedOnDisk>(refused)
    assertContentEquals(externalBytes, Files.readAllBytes(path))
    val refreshed = assertIs<ConfigDocumentFileRead.Present>(adapter.read(path, ConfigDocumentFormat.JSON))
    val written =
      adapter.replace(
        ConfigDocumentReplaceRequest(
          path,
          path,
          refreshed.fingerprint,
          ConfigDocumentFormat.JSON,
          ConfigDocumentTree(mapOf("service" to true)),
        ),
      )

    assertIs<ConfigDocumentReplaceResult.Written>(written)
    assertEquals("{\"service\":true}\n", Files.readString(path))
  }
}
