package skillbill.infrastructure.skills.install

import skillbill.contracts.JsonCodec
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.skills.install.mcp.McpRegistrationOperations
import skillbill.infrastructure.skills.install.runtime.InstallOperations
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.install.model.McpRegistrationOutcome
import skillbill.install.model.SupportedAgent
import skillbill.model.toPath
import skillbill.ports.install.mcp.model.InstallMcpProfileEnvironment
import skillbill.ports.install.mcp.model.InstallMcpRegistrationRequest
import skillbill.ports.install.mcp.model.InstallMcpUnregistrationRequest
import skillbill.ports.system.HostPlatformPort
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DevinInstallationBoundaryTest {
  private fun host(osName: String): HostPlatformPort =
    object : HostPlatformPort by JdkHostPlatformPort {
      override val osName: String = osName

      override fun resolveEnvironment(): Map<String, String> = emptyMap()
    }

  @Test
  fun `skills, native agents and MCP config share one Devin root on each host`() {
    val home = Files.createTempDirectory("devin-home")
    val appData = Files.createTempDirectory("devin-appdata")
    val cases =
      listOf(
        Triple(host("Linux"), emptyMap(), home.resolve(".config/devin")),
        Triple(host("Mac OS X"), emptyMap(), home.resolve(".config/devin")),
        Triple(host("Windows 11"), mapOf("APPDATA" to appData.toString()), appData.resolve("devin")),
      )

    cases.forEach { (hostPlatform, environment, root) ->
      val expectedRoot = root.toAbsolutePath().normalize()
      assertEquals(
        expectedRoot.resolve("skills"),
        InstallOperations.agentPath("devin", home, environment, hostPlatform),
      )
      assertEquals(
        expectedRoot.resolve("agents"),
        InstallOperations.devinAgentsPath(home, environment, hostPlatform),
      )
      assertEquals(
        listOf(expectedRoot.resolve("agents")),
        NativeAgentProvider.Devin.homeAgentDirs(home, environment, hostPlatform),
      )
      assertEquals(
        expectedRoot.resolve("mcp_config.json"),
        McpRegistrationOperations.configPathFor(SupportedAgent.DEVIN, home, environment, hostPlatform),
      )
    }
  }

  @Test
  fun `Windows without an absolute APPDATA fails with a typed error before any write`() {
    val home = Files.createTempDirectory("devin-home-windows")
    val windows = host("Windows 10")

    listOf(emptyMap(), mapOf("APPDATA" to " "), mapOf("APPDATA" to "relative/appdata")).forEach { environment ->
      val error =
        assertFailsWith<SkillBillRuntimeException> {
          InstallOperations.agentPath("devin", home, environment, windows)
        }
      assertEquals(InstallFailureCode.AGENT_DISCOVERY_FAILED, error.code)
      assertTrue(error.message.orEmpty().contains("APPDATA"))
    }
    assertEquals(emptyList(), Files.list(home).use { entries -> entries.toList() })
  }

  @Test
  fun `a configured Devin root is detected as one skill target without the CLI`() {
    val home = Files.createTempDirectory("devin-detect")
    val linux = host("Linux")
    assertTrue(InstallOperations.detectAgentTargets(home, emptyMap(), linux).none { it.name == "devin" })

    Files.createDirectories(home.resolve(".config/devin"))

    val devinTargets = InstallOperations.detectAgentTargets(home, emptyMap(), linux).filter { it.name == "devin" }
    assertEquals(
      listOf(home.resolve(".config/devin/skills").toAbsolutePath().normalize()),
      devinTargets.map { it.path.toPath() },
    )
  }

  @Test
  fun `Devin MCP registration preserves operator settings and rejects malformed JSON without touching it`() {
    val home = Files.createTempDirectory("devin-mcp")
    val configPath = home.resolve(".config/devin/mcp_config.json")
    Files.createDirectories(configPath.parent)
    Files.writeString(
      configPath,
      """{"theme":"dark","mcpServers":{"other":{"command":"other","env":{"TOKEN":"secret"}}}}""",
    )
    val runtimeMcpBin = Path.of("/tmp/runtime-mcp")
    val profile = InstallMcpProfileEnvironment()

    val registration = InstallMcpRegistrationRequest("devin", runtimeMcpBin, home, profile)
    val first = McpRegistrationOperations.registerWithProfileEnvironment(registration)
    assertTrue(assertIs<McpRegistrationOutcome.Applied>(first).mutation.changed)
    val second = McpRegistrationOperations.registerWithProfileEnvironment(registration)
    assertEquals(false, assertIs<McpRegistrationOutcome.Applied>(second).mutation.changed)

    val registered = decode(configPath)
    assertEquals("dark", registered["theme"])
    val servers = registered["mcpServers"] as Map<*, *>
    assertEquals(mapOf("command" to "other", "env" to mapOf("TOKEN" to "secret")), servers["other"])
    assertEquals(
      mapOf("command" to "/tmp/runtime-mcp", "args" to emptyList<String>(), "env" to emptyMap<String, String>()),
      servers["skill-bill"],
    )

    McpRegistrationOperations.unregisterWithProfileEnvironment(
      InstallMcpUnregistrationRequest("devin", home, profile),
    )
    val removed = decode(configPath)
    assertEquals("dark", removed["theme"])
    assertEquals(setOf("other"), (removed["mcpServers"] as Map<*, *>).keys)

    val malformed = "{ \"mcpServers\": [\"not-an-object\"] }"
    Files.writeString(configPath, malformed)
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        McpRegistrationOperations.registerWithProfileEnvironment(registration)
      }
    assertEquals(InstallFailureCode.MALFORMED_MCP_CONFIG, error.code)
    assertEquals(malformed, Files.readString(configPath))
  }

  private fun decode(path: Path): Map<String, Any?> =
    JsonCodec.anyToStringAnyMap(
      JsonCodec.parseObjectOrNull(Files.readString(path))?.let(JsonCodec::jsonElementToValue),
    ) ?: emptyMap()
}
