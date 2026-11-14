package skillbill.infrastructure.skills.install

import skillbill.contracts.JsonCodec
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.infrastructure.contracts.sha256Hex
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.SupportedAgent
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionRequest
import skillbill.ports.install.selection.model.WriteLatestSuccessfulInstallSelectionRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileSystemInstallSelectionMigrationTest {
  @Test
  fun `retired agent does not block reuse and original survives repeated reads and successful installation`() {
    val home = Files.createTempDirectory("install-selection-migration")
    val path = writeSelection(home, listOf("claude", "codex", "cursor", "junie"))
    val original = Files.readString(path)
    val messages = mutableListOf<String>()
    val diagnostics =
      object : RuntimeDiagnostics by NoopRuntimeDiagnostics {
        override fun warning(
          message: String,
          error: Throwable?,
        ) {
          messages += message
        }
      }
    val store = FileSystemInstallSelectionPersistence(diagnostics)
    val request = ReadLatestSuccessfulInstallSelectionRequest(home)
    val selection = store.readLatestSuccessfulSelection(request).selection
    assertEquals(selection, store.readLatestSuccessfulSelection(request).selection)
    assertEquals(setOf(SupportedAgent.CLAUDE, SupportedAgent.CODEX, SupportedAgent.CURSOR), selection.selectedAgents)
    assertEquals(PlatformPackSelectionMode.ALL, selection.platformPackSelection.mode)
    assertEquals(InstallTelemetryLevel.FULL, selection.telemetryLevel)
    assertEquals(true, selection.mcpRegistrationChoice.register)
    assertEquals("/old/runtime-mcp", selection.mcpRegistrationChoice.runtimeMcpBin.toString())
    assertEquals(original, Files.readString(path))
    val backup = backupPath(path, original)
    assertEquals(original, Files.readString(backup))
    assertContains(messages.first(), "seam=install_selection_migration")
    assertContains(messages.first(), "removed_count=1")
    store.writeLatestSuccessfulSelection(WriteLatestSuccessfulInstallSelectionRequest(home, selection))
    assertEquals(selection, store.readLatestSuccessfulSelection(request).selection)
    assertEquals(original, Files.readString(backup))
    assertEquals(2, Files.list(path.parent).use { it.count() }.toInt())
  }

  @Test
  fun `retired selection does not hide unknown agents unsupported versions or malformed settings`() {
    listOf(
      payload(listOf("codex", "junie", "unknown-agent")),
      payload(listOf("codex", "junie"), version = "2.0"),
      payload(listOf("codex", "junie")).replace("\"full\"", "\"invalid\""),
    ).forEach { original ->
      val home = Files.createTempDirectory("install-selection-invalid-migration")
      val path = writePayload(home, original)
      val error =
        assertFailsWith<SkillBillRuntimeException> {
          FileSystemInstallSelectionPersistence(NoopRuntimeDiagnostics).readLatestSuccessfulSelection(
            ReadLatestSuccessfulInstallSelectionRequest(home),
          )
        }
      assertEquals(InstallFailureCode.MALFORMED_INSTALL_SELECTION_RECORD, error.code)
      assertEquals(original, Files.readString(path))
      assertEquals(1, Files.list(path.parent).use { it.count() }.toInt())
    }
  }

  @Test
  fun `retired only selection requires explicit reselection without changing the record`() {
    val home = Files.createTempDirectory("install-selection-retired-only")
    val path = writeSelection(home, listOf("junie"))
    val original = Files.readString(path)
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        FileSystemInstallSelectionPersistence(NoopRuntimeDiagnostics).readLatestSuccessfulSelection(
          ReadLatestSuccessfulInstallSelectionRequest(home),
        )
      }
    assertEquals(InstallFailureCode.MALFORMED_INSTALL_SELECTION_RECORD, error.code)
    assertContains(error.message.orEmpty(), "without --reuse-last-selection")
    assertEquals(original, Files.readString(path))
  }

  @Test
  fun `backup failure leaves original available and retry resumes after obstruction is removed`() {
    val home = Files.createTempDirectory("install-selection-backup-failure")
    val path = writeSelection(home, listOf("codex", "junie"))
    val original = Files.readString(path)
    val backup = backupPath(path, original)
    Files.createDirectory(backup)
    val store = FileSystemInstallSelectionPersistence(NoopRuntimeDiagnostics)
    val request = ReadLatestSuccessfulInstallSelectionRequest(home)
    val error = assertFailsWith<SkillBillRuntimeException> { store.readLatestSuccessfulSelection(request) }
    assertEquals("BACKUP_FAILED", error.code.toString())
    assertEquals(original, Files.readString(path))
    Files.delete(backup)
    assertEquals(setOf(SupportedAgent.CODEX), store.readLatestSuccessfulSelection(request).selection.selectedAgents)
    assertEquals(original, Files.readString(backup))
  }

  private fun writeSelection(
    home: Path,
    agents: List<String>,
  ): Path = writePayload(home, payload(agents))

  private fun writePayload(
    home: Path,
    payload: String,
  ): Path {
    val path = home.resolve(".skill-bill/install-selection.json")
    Files.createDirectories(path.parent)
    Files.writeString(path, payload)
    return path
  }

  private fun backupPath(
    path: Path,
    payload: String,
  ): Path = path.resolveSibling("${path.fileName}.before-agent-migration-${sha256Hex(payload)}.json")

  private fun payload(
    agents: List<String>,
    version: String = "1.0",
  ): String =
    JsonCodec.mapToJsonString(
      mapOf(
        "contract_version" to version,
        "selected_agents" to agents,
        "platform_pack_selection" to mapOf("mode" to "all", "selected_slugs" to emptyList<String>()),
        "telemetry_level" to "full",
        "mcp_registration" to mapOf("register" to true, "runtime_mcp_bin" to "/old/runtime-mcp"),
      ),
    )
}
