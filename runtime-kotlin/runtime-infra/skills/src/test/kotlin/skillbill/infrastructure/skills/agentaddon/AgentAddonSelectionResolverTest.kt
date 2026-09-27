package skillbill.infrastructure.skills.agentaddon

import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.error.shellcontent.AgentAddonSelectionDriftError
import skillbill.error.shellcontent.InvalidAgentAddonSelectionError
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
      AgentAddonSelectionResolver().resolveInitial(
        repo,
        listOf("second", "first"),
        AgentAddonConsumer.BILL_FEATURE,
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
    val resolver = AgentAddonSelectionResolver()

    assertFailsWith<InvalidAgentAddonSelectionError> {
      resolver.resolveInitial(repo, listOf("helper", "helper"), AgentAddonConsumer.BILL_FEATURE, listOf("codex"))
    }
    assertFailsWith<InvalidAgentAddonSelectionError> {
      resolver.resolveInitial(repo, listOf("helper"), AgentAddonConsumer.BILL_FEATURE, listOf("claude"))
    }
    assertFailsWith<InvalidAgentAddonSelectionError> {
      resolver.resolveInitial(repo, listOf("helper"), AgentAddonConsumer.BILL_FEATURE, emptyList())
    }
  }

  @Test
  fun `initial resolution can select from external agent add-on roots`() {
    val repo = Files.createTempDirectory("addon-selection-external-repo")
    val external = Files.createTempDirectory("addon-selection-external-root")
    writeAddon(external, "external-helper", "External", "codex", "external content")

    val selection =
      AgentAddonSelectionResolver().resolveInitial(
        repo,
        listOf("external-helper"),
        AgentAddonConsumer.BILL_FEATURE,
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
    val resolver = AgentAddonSelectionResolver()
    val initial =
      resolver.resolveInitial(
        repo,
        listOf("helper"),
        AgentAddonConsumer.BILL_FEATURE,
        listOf("codex"),
      )
    Files.writeString(content, "changed")

    assertFailsWith<AgentAddonSelectionDriftError> {
      resolver.verifyPersisted(initial.persisted, AgentAddonConsumer.BILL_FEATURE, listOf("codex"))
    }
  }

  @Test
  fun `selection persisted for bill-feature still verifies after the manifest adds the skill-bill consumer`() {
    val repo = Files.createTempDirectory("addon-selection-added-consumer")
    writeAddon(repo, "helper", "Helper", "codex", "original")
    val resolver = AgentAddonSelectionResolver()
    val initial =
      resolver.resolveInitial(repo, listOf("helper"), AgentAddonConsumer.BILL_FEATURE, listOf("codex"))
    val manifest = repo.resolve("agent-addons/helper/agent-addon.yaml")
    Files.writeString(
      manifest,
      Files.readString(manifest).replace("consumers: [bill-feature]", "consumers: [bill-feature, skill-bill]"),
    )

    val verified = resolver.verifyPersisted(initial.persisted, AgentAddonConsumer.BILL_FEATURE, listOf("codex"))

    assertEquals(initial, verified)
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
      consumers: [bill-feature]
      """.trimIndent() + "\n",
    )
    return Files.write(root.resolve("content.md"), content.toByteArray())
  }
}
