package skillbill.infrastructure.skills.externaladdon

import skillbill.contracts.JsonCodec
import skillbill.error.core.ExternalAddonConfigError
import skillbill.model.toPath
import skillbill.ports.agentaddon.model.ExternalAgentAddonSourceConfigRequest
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileExternalAgentAddonSourceConfigStoreTest {
  @Test
  fun `reads agent add-on entries from the shared external addon sources config`() {
    val home = Files.createTempDirectory("external-agent-addon-config")
    val agentRoot = Files.createDirectories(home.resolve("agent-addons"))
    val platformRoot = Files.createDirectories(home.resolve("platform-addons"))
    writeConfig(
      home,
      mapOf(
        "external_addon_sources" to
          listOf(
            mapOf("kind" to "platform-pack", "path" to platformRoot.toString(), "platform" to "ios"),
            mapOf("kind" to "agent-addon", "path" to "~/agent-addons"),
          ),
      ),
    )

    val result =
      FileExternalAgentAddonSourceConfigStore().readExternalAgentAddonSources(
        ExternalAgentAddonSourceConfigRequest(home, mapOf(CONFIG_ENVIRONMENT_KEY to configPath(home).toString())),
      )

    assertEquals(listOf(agentRoot.toAbsolutePath().normalize()), result.sources.map { it.path.toPath() })
  }

  @Test
  fun `legacy platform entries are ignored by the agent source reader`() {
    val home = Files.createTempDirectory("external-agent-addon-legacy-platform")
    val platformRoot = Files.createDirectories(home.resolve("platform-addons"))
    writeConfig(
      home,
      mapOf(
        "external_addon_sources" to
          listOf(
            mapOf("path" to platformRoot.toString(), "platform" to "ios"),
          ),
      ),
    )

    val result =
      FileExternalAgentAddonSourceConfigStore().readExternalAgentAddonSources(
        ExternalAgentAddonSourceConfigRequest(home, mapOf(CONFIG_ENVIRONMENT_KEY to configPath(home).toString())),
      )

    assertEquals(emptyList(), result.sources)
  }

  @Test
  fun `unknown shared external addon source kind fails loudly`() {
    val home = Files.createTempDirectory("external-agent-addon-unknown-kind")
    val sourceRoot = Files.createDirectories(home.resolve("source"))
    writeConfig(
      home,
      mapOf(
        "external_addon_sources" to
          listOf(
            mapOf("kind" to "unknown", "path" to sourceRoot.toString()),
          ),
      ),
    )

    assertFailsWith<ExternalAddonConfigError> {
      FileExternalAgentAddonSourceConfigStore().readExternalAgentAddonSources(
        ExternalAgentAddonSourceConfigRequest(home, mapOf(CONFIG_ENVIRONMENT_KEY to configPath(home).toString())),
      )
    }
  }

  private fun configPath(home: Path): Path = home.resolve("config.json")

  private fun writeConfig(
    home: Path,
    payload: Map<String, Any?>,
  ) {
    Files.writeString(configPath(home), JsonCodec.mapToJsonString(payload) + "\n")
  }
}
