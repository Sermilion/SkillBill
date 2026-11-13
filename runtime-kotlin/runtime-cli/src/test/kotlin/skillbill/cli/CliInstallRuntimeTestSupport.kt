package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliExecutionResult
import skillbill.cli.model.CliRuntimeContext
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentSource
import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentSource
import skillbill.infrastructure.skills.nativeagent.composition.renderNativeAgentSource
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun runInstall(
  fixture: InstallFixture,
  command: String,
  vararg extraArgs: String,
): CliExecutionResult =
  CliRuntime.run(
    listOf(
      "install",
      command,
      "--platform-packs",
      fixture.platformPacks.toString(),
      "--skills",
      fixture.skills.toString(),
    ) + extraArgs,
    installCliContext(fixture.home),
  )

internal fun installCliContext(home: Path): CliRuntimeContext =
  CliRuntimeContext(
    userHome = home,
    environment = isolatedCliEnvironment(home),
  )

internal fun installFixture(): InstallFixture {
  val home = Files.createTempDirectory("skillbill-cli-install-native")
  Files.createDirectories(home.resolve(".claude"))
  Files.createDirectories(home.resolve(".codex"))
  Files.createDirectories(home.resolve(".junie"))
  Files.createDirectories(home.resolve(".cursor"))
  val platformPacks = home.resolve("platform-packs")
  val skills = home.resolve("skills")
  val baseCodexAgents = skills.resolve("bill-code-review/codex-agents")
  val baseJunieAgents = skills.resolve("bill-code-review/junie-agents")
  val baseNativeAgents = skills.resolve("bill-code-review/native-agents")
  val codexAgents = platformPacks.resolve("kotlin/code-review/bill-kotlin-code-review/codex-agents")
  val junieAgents = platformPacks.resolve("kotlin/code-review/bill-kotlin-code-review/junie-agents")
  val nativeAgents = platformPacks.resolve("kotlin/code-review/bill-kotlin-code-review/native-agents")
  val kmpCodexAgents = platformPacks.resolve("kmp/code-review/bill-kmp-code-review/codex-agents")
  val kmpJunieAgents = platformPacks.resolve("kmp/code-review/bill-kmp-code-review/junie-agents")
  val kmpNativeAgents = platformPacks.resolve("kmp/code-review/bill-kmp-code-review/native-agents")
  Files.createDirectories(baseNativeAgents)
  Files.createDirectories(nativeAgents)
  Files.createDirectories(kmpNativeAgents)
  val baseCodexToml = baseCodexAgents.resolve("bill-code-review-worker.toml")
  val baseJunieMd = baseJunieAgents.resolve("bill-code-review-worker.md")
  val codexToml = codexAgents.resolve("bill-kotlin-code-review-testing.toml")
  val junieMd = junieAgents.resolve("bill-kotlin-code-review-testing.md")
  val cursorMd =
    platformPacks.resolve("kotlin/code-review/bill-kotlin-code-review/cursor-agents")
      .resolve("bill-kotlin-code-review-testing.md")
  val kmpCodexToml = kmpCodexAgents.resolve("bill-kmp-code-review-ui.toml")
  val kmpJunieMd = kmpJunieAgents.resolve("bill-kmp-code-review-ui.md")
  val fixture =
    InstallFixture(
      home,
      platformPacks,
      skills,
      baseCodexToml,
      baseJunieMd,
      codexToml,
      junieMd,
      kmpCodexToml,
      kmpJunieMd,
      cursorMd,
    )
  writeInstallFixtureFiles(fixture)
  return fixture
}

internal fun writeInstallFixtureFiles(fixture: InstallFixture) {
  writeMinimalPackManifest(fixture.platformPacks.resolve("kotlin"), "*.kt")
  writeMinimalPackManifest(fixture.platformPacks.resolve("kmp"), "commonMain")
  writeNativeAgentSet(fixture.baseCodexToml, "bill-code-review-worker", "Review changed code.")
  writeNativeAgentSet(fixture.codexToml, "bill-kotlin-code-review-testing", "Review Kotlin tests.")
  writeNativeAgentSet(fixture.kmpCodexToml, "bill-kmp-code-review-ui", "Review KMP UI.")
}

internal fun assertGeneratedAgentLinked(
  path: Path,
  expected: Path?,
) {
  assertTrue(Files.isSymbolicLink(path))
  assertContains(path.toRealPath().toString(), ".skill-bill")
  if (expected != null) {
    val provider =
      NativeAgentProvider.entries.first { provider ->
        provider.directoryName == expected.parent.fileName.toString()
      }
    val name = expected.fileName.toString().removeSuffix(".${provider.extension}")
    val source = parseNativeAgentSource(expected.parent.parent.resolve("native-agents/$name.md"))
    assertEquals(provider.render(source), Files.readString(path))
  }
}

internal fun writeNativeAgentSet(
  codexPath: Path,
  name: String,
  description: String,
) {
  val skillDir = codexPath.parent.parent
  val source = NativeAgentSource(name = name, description = description, body = "# $name\n\nDo the work.")
  Files.writeString(skillDir.resolve("native-agents/$name.md"), renderNativeAgentSource(source))
}

internal fun snapshotInstallRepo(fixture: InstallFixture): Map<String, String> =
  listOf(fixture.skills, fixture.platformPacks).flatMap { root ->
    Files.walk(root).use { stream ->
      stream
        .filter(Files::isRegularFile)
        .sorted()
        .toList()
        .map { path -> root.relativize(path).toString() to Files.readString(path) }
    }
  }.toMap()

internal fun writeMinimalPackManifest(
  packRoot: Path,
  strongSignal: String,
) {
  Files.writeString(
    packRoot.resolve("platform.yaml"),
    """
    platform: ${packRoot.fileName}
    contract_version: "1.8"
    routing_signals:
      strong: ["$strongSignal"]
    declared_code_review_areas: []
    """.trimIndent(),
  )
}

internal data class InstallFixture(
  val home: Path,
  val platformPacks: Path,
  val skills: Path,
  val baseCodexToml: Path,
  val baseJunieMd: Path,
  val codexToml: Path,
  val junieMd: Path,
  val kmpCodexToml: Path,
  val kmpJunieMd: Path,
  val cursorMd: Path,
)

internal data class McpCase(
  val agent: String,
  val relativeConfigPath: String,
  val seed: String,
  val assertRegistered: (String) -> Unit,
  val assertUnregistered: (String) -> Unit,
)

internal fun mcpCases(): List<McpCase> =
  listOf(
    mcpJsonCase(
      agent = "claude",
      relativeConfigPath = ".claude.json",
      seed = "{\n  \"theme\": \"dark\",\n  \"mcpServers\": {\"other\": {\"command\": \"other\"}}\n}\n",
      expectedKey = "theme",
      expectedValue = "dark",
    ),
    junieMcpCase(),
    McpCase(
      agent = "codex",
      relativeConfigPath = ".codex/config.toml",
      seed = "[profile.default]\nmodel = \"gpt-5\"\n\n[mcp_servers.other]\ncommand = \"other\"\nargs = []\n",
      assertRegistered = { raw ->
        assertContains(raw, "[profile.default]")
        assertContains(raw, "[mcp_servers.other]")
        assertContains(raw, "[mcp_servers.sona]")
        assertContains(raw, "command = \"/tmp/runtime-mcp\"")
      },
      assertUnregistered = { raw ->
        assertContains(raw, "[profile.default]")
        assertContains(raw, "[mcp_servers.other]")
        assertFalse("[mcp_servers.skill-bill]" in raw)
        assertFalse("[mcp_servers.sona]" in raw)
      },
    ),
  )

internal fun junieMcpCase(): McpCase =
  mcpJsonCase(
    agent = "junie",
    relativeConfigPath = ".junie/mcp/mcp.json",
    seed = "{\n  \"mcpServers\": {\"other\": {\"command\": \"other\"}}\n}\n",
    expectedKey = null,
    expectedValue = null,
  )

internal fun mcpJsonCase(
  agent: String,
  relativeConfigPath: String,
  seed: String,
  expectedKey: String?,
  expectedValue: Any?,
): McpCase =
  McpCase(
    agent = agent,
    relativeConfigPath = relativeConfigPath,
    seed = seed,
    assertRegistered = { raw ->
      val settings = decodeJsonObject(raw)
      if (expectedKey != null) {
        assertEquals(expectedValue, settings[expectedKey])
      }
      val servers = settings["mcpServers"] as Map<*, *>
      assertTrue("other" in servers)
      val skillBill = servers["sona"] as Map<*, *>
      assertEquals("/tmp/runtime-mcp", skillBill["command"])
    },
    assertUnregistered = { raw ->
      val settings = decodeJsonObject(raw)
      if (expectedKey != null) {
        assertEquals(expectedValue, settings[expectedKey])
      }
      assertTrue("other" in (settings["mcpServers"] as Map<*, *>))
      assertFalse("skill-bill" in (settings["mcpServers"] as Map<*, *>))
      assertFalse("sona" in (settings["mcpServers"] as Map<*, *>))
    },
  )

internal fun mcpProfileEntries(result: CliExecutionResult): List<Map<*, *>> =
  (result.payload?.get("profiles") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

internal fun mcpProfileConfigPaths(result: CliExecutionResult): List<String> =
  mcpProfileEntries(result).mapNotNull { entry -> entry["config_path"] as? String }
