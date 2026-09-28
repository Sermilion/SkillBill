package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonCodec
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CliAgentAddonResolveSelectionRuntimeTest {
  @Test
  fun `resolve-selection reads external agent add-on roots from shared config`() {
    val home = Files.createTempDirectory("cli-agent-addon-external-home")
    val repo = Files.createTempDirectory("cli-agent-addon-external-repo")
    val externalRoot = Files.createDirectories(home.resolve("private-agent-addons"))
    writeAgentAddon(externalRoot, "codex-policy")
    writeConfig(
      home,
      mapOf(
        "external_addon_sources" to
          listOf(
            mapOf("kind" to "agent-addon", "path" to externalRoot.toString()),
          ),
      ),
    )

    val result =
      CliRuntime.run(
        listOf(
          "agent-addon",
          "resolve-selection",
          "--repo-root",
          repo.toString(),
          "--receiving-agent",
          "codex",
          "--token",
          "agent-addon:codex-policy",
        ),
        CliRuntimeContext(userHome = home, environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath(home).toString())),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "codex-policy")
    assertContains(result.stdout, externalRoot.resolve("codex-policy/agent-addon.yaml").toString())
  }

  private fun writeAgentAddon(
    root: Path,
    slug: String,
  ) {
    val source = Files.createDirectories(root.resolve(slug))
    Files.writeString(
      source.resolve("agent-addon.yaml"),
      """
      contract_version: "1.0"
      slug: $slug
      description: Codex policy.
      agent_ids: [codex]
      consumers: [skill-bill]
      """.trimIndent() + "\n",
    )
    Files.writeString(source.resolve("content.md"), "# Codex policy\n")
  }

  private fun configPath(home: Path): Path = home.resolve("config.json")

  private fun writeConfig(
    home: Path,
    payload: Map<String, Any?>,
  ) {
    Files.writeString(configPath(home), JsonCodec.mapToJsonString(payload) + "\n")
  }
}
