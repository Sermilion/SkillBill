package skillbill.infrastructure.skills.agentaddon

import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.model.toPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentAddonDeliveryResolverTest {
  @Test
  fun `resolves skill-bill pointers dynamically in deterministic order`() {
    val repo = Files.createTempDirectory("agent-addon-delivery")
    writeAddon(repo, "z-last", "Z body\r\n")
    writeAddon(repo, "a-first", "A body")

    val pointers = AgentAddonDeliveryResolver().resolve(repo, AgentAddonConsumer.SKILL_BILL)

    assertEquals(listOf("a-first", "z-last"), pointers.map { it.slug })
    assertEquals(listOf("agent-addon-a-first.md", "agent-addon-z-last.md"), pointers.map { it.name })
    assertEquals("agent-addons/a-first/content.md", pointers.first().contentRelativePath)
    assertContentEquals("A body\n".encodeToByteArray(), pointers.first().renderedBytes)
  }

  @Test
  fun `catalogue uses namespaced identity and declaration metadata`() {
    val repo = Files.createTempDirectory("agent-addon-catalogue")
    writeAddon(repo, "review-helper", "Body")

    val entry = AgentAddonDeliveryResolver().catalogue(repo).single()

    assertEquals("agent-addon:review-helper", entry.identity)
    assertEquals("Review helper", entry.description)
    assertEquals(listOf("codex"), entry.agentIds)
    assertEquals(listOf("skill-bill"), entry.consumers)
    assertTrue(entry.manifestPath.toPath().endsWith("agent-addon.yaml"))
  }

  private fun writeAddon(
    repo: Path,
    slug: String,
    content: String,
  ) {
    val root = repo.resolve("agent-addons/$slug")
    Files.createDirectories(root)
    Files.writeString(
      root.resolve("agent-addon.yaml"),
      """
      contract_version: "1.0"
      slug: $slug
      description: Review helper
      agent_ids:
        - codex
      consumers:
        - skill-bill
      """.trimIndent() + "\n",
    )
    Files.writeString(root.resolve("content.md"), content)
  }
}
