package skillbill.agentaddon

import skillbill.infrastructure.skills.agentaddon.requireAgentAddon
import skillbill.model.toPath
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentAddonSourceLoaderRepoTest {
  @Test
  fun `shipped execution budget declaration has governed source shape and guidance`() {
    val repo = repoRootFromTest()
    val declaration = requireAgentAddon(repo, "execution-budget")
    val content = Files.readString(declaration.contentPath.toPath())

    assertEquals("1.0", declaration.contractVersion)
    assertEquals(listOf("codex"), declaration.agents)
    assertEquals(listOf("bill-feature", "skill-bill"), declaration.consumers.map { it.id })
    assertEquals(
      listOf("agent-addon.yaml", "content.md"),
      Files.list(
        declaration.addonRoot.toPath(),
      ).use { files -> files.map { it.fileName.toString() }.sorted().toList() },
    )
    assertTrue(content.contains("stopping boundary"))
    assertTrue(content.contains("PR babysitting"))
    assertTrue(content.contains("compact, durable hand-offs"))
    assertTrue(content.contains("Delegate only when the user explicitly requests delegation"))
    assertTrue(!content.contains("SKILL.md") && !content.contains("context window"))
  }
}
