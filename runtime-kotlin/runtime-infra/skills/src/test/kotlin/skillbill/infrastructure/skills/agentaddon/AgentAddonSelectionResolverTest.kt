package skillbill.infrastructure.skills.agentaddon

import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.AgentAddonFailureCode
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentAddonSelectionResolverTest {
  @Test
  fun `initial resolution preserves requested order and hashes exact bytes`() {
    val repo = Files.createTempDirectory("addon-selection")
    writeAddon(repo, "second", "Second", "codex", "two\r\n")
    writeAddon(repo, "first", "First", "codex", "one\n")

    val selection =
      AgentAddonSelectionResolver(NoopRuntimeDiagnostics).resolveInitial(
        repo,
        listOf("second", "first"),
        AgentAddonConsumer.SKILL_BILL,
        listOf("codex"),
      )

    assertEquals(listOf("second", "first"), selection.entries.map { it.persisted.slug })
    assertEquals("two\r\n", selection.entries.first().content)
    assertTrue(selection.entries.all { it.persisted.contentSha256.matches(Regex("[0-9a-f]{64}")) })
  }

  @Test
  fun `duplicates and incompatible receiving agents fail loudly`() {
    val repo = Files.createTempDirectory("addon-selection-invalid")
    writeAddon(repo, "helper", "Helper", "codex", "content")
    val resolver = AgentAddonSelectionResolver(NoopRuntimeDiagnostics)

    val duplicate =
      assertFailsWith<SkillBillRuntimeException> {
        resolver.resolveInitial(repo, listOf("helper", "helper"), AgentAddonConsumer.SKILL_BILL, listOf("codex"))
      }
    val incompatible =
      assertFailsWith<SkillBillRuntimeException> {
        resolver.resolveInitial(repo, listOf("helper"), AgentAddonConsumer.SKILL_BILL, listOf("claude"))
      }
    val noReceivingAgent =
      assertFailsWith<SkillBillRuntimeException> {
        resolver.resolveInitial(repo, listOf("helper"), AgentAddonConsumer.SKILL_BILL, emptyList())
      }
    listOf(duplicate, incompatible, noReceivingAgent).forEach { error ->
      assertEquals(AgentAddonFailureCode.INVALID_SELECTION, error.code)
    }
  }

  @Test
  fun `initial resolution can select from external agent add-on roots`() {
    val repo = Files.createTempDirectory("addon-selection-external-repo")
    val external = Files.createTempDirectory("addon-selection-external-root")
    writeAddon(external, "external-helper", "External", "codex", "external content")

    val selection =
      AgentAddonSelectionResolver(NoopRuntimeDiagnostics).resolveInitial(
        repo,
        listOf("external-helper"),
        AgentAddonConsumer.SKILL_BILL,
        listOf("codex"),
        listOf(external.resolve("agent-addons")),
      )

    assertEquals(listOf("external-helper"), selection.entries.map { it.persisted.slug })
    assertEquals("external content", selection.entries.single().content)
  }

  @Test
  fun `resume loads recorded identity directly and rejects digest drift`() {
    val repo = Files.createTempDirectory("addon-selection-resume")
    val content = writeAddon(repo, "helper", "Helper", "codex", "original")
    val resolver = AgentAddonSelectionResolver(NoopRuntimeDiagnostics)
    val initial =
      resolver.resolveInitial(
        repo,
        listOf("helper"),
        AgentAddonConsumer.SKILL_BILL,
        listOf("codex"),
      )
    Files.writeString(content, "changed")

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        resolver.verifyPersisted(initial.persisted, AgentAddonConsumer.SKILL_BILL, listOf("codex"))
      }
    assertEquals(AgentAddonFailureCode.SELECTION_DRIFT, error.code)
  }

  @Test
  fun `legacy bill-feature manifest verifies a persisted selection as skill-bill with a migration record`() {
    val repo = Files.createTempDirectory("addon-selection-legacy-consumer")
    writeAddon(repo, "helper", "Helper", "codex", "original")
    val manifest = repo.resolve("agent-addons/helper/agent-addon.yaml")
    Files.writeString(
      manifest,
      Files.readString(manifest).replace("consumers: [skill-bill]", "consumers: [bill-feature]"),
    )
    val diagnostics = RecordingAgentAddonDiagnostics()
    val resolver = AgentAddonSelectionResolver(diagnostics)
    val initial =
      resolver.resolveInitial(repo, listOf("helper"), AgentAddonConsumer.SKILL_BILL, listOf("codex"))
    diagnostics.warnings.clear()

    val verified = resolver.verifyPersisted(initial.persisted, AgentAddonConsumer.SKILL_BILL, listOf("codex"))

    assertEquals(initial, verified)
    assertEquals(
      listOf(
        "skillbill agent-addon: record_kind=migration; seam=$AGENT_ADDON_PERSISTED_CONSUMER_SEAM; " +
          "value_used=skill-bill; value_expected=skill-bill; cause=legacy_consumer_bill-feature",
      ),
      diagnostics.migrationRecords,
    )
  }

  private fun writeAddon(
    repo: Path,
    slug: String,
    description: String,
    agent: String,
    content: String,
  ): Path {
    val root = Files.createDirectories(repo.resolve("agent-addons/$slug"))
    Files.writeString(
      root.resolve("agent-addon.yaml"),
      """
      contract_version: "1.0"
      slug: $slug
      description: $description
      agent_ids: [$agent]
      consumers: [skill-bill]
      """.trimIndent() + "\n",
    )
    return Files.write(root.resolve("content.md"), content.toByteArray())
  }
}
