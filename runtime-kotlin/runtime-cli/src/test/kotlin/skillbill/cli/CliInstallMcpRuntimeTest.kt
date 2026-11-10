package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentSource
import skillbill.infrastructure.skills.nativeagent.composition.renderNativeAgentSource
import skillbill.ports.install.mcp.model.InstallMcpProfileEnvironment
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliInstallMcpRuntimeTest {
  @Test
  fun `OpenCode MCP uses captured cwd and absent selection does not block no-op removal`() {
    val home = Files.createTempDirectory("skillbill-cli-opencode-cwd")
    val working = Files.createDirectories(home.resolve("actual"))
    val stale = home.resolve("stale")
    val configPath = working.resolve("config/opencode.json")
    val baseContext = installCliContext(home)
    val context =
      baseContext.copy(
        workingDirectory = working,
        environment =
          baseContext.environment +
            mapOf(
              InstallMcpProfileEnvironment.PWD_ENV to stale.toString(),
              InstallMcpProfileEnvironment.OPENCODE_CONFIG_ENV to "config/opencode.json",
              InstallMcpProfileEnvironment.OPENCODE_DISABLE_PROJECT_CONFIG_ENV to "1",
            ),
      )
    val removeArguments = listOf("install", "unregister-mcp", "opencode")

    val absentRemoval = CliRuntime.run(removeArguments, context)

    assertEquals(0, absentRemoval.exitCode, absentRemoval.stdout)
    assertFalse(Files.exists(configPath))
    val command = home.resolve("runtime bin/runtime-mcp").toString()
    val register =
      CliRuntime.run(
        listOf("install", "register-mcp", "opencode", "--runtime-mcp-bin", command),
        context,
      )
    assertEquals(0, register.exitCode, register.stdout)
    assertContains(Files.readString(configPath), "skill-bill")
    assertFalse(Files.exists(stale))

    val removal = CliRuntime.run(removeArguments + listOf("--runtime-mcp-bin", command), context)

    assertEquals(0, removal.exitCode, removal.stdout)
    assertFalse(Files.readString(configPath).contains("skill-bill"))
  }

  @Test
  fun `native subagent commands preserve existing regular target files`() {
    val fixture = installFixture()
    val codexTarget = fixture.home.resolve(".codex/agents/${fixture.codexToml.fileName}")
    val junieTarget = fixture.home.resolve(".junie/agents/${fixture.junieMd.fileName}")
    Files.createDirectories(codexTarget.parent)
    Files.createDirectories(junieTarget.parent)
    Files.writeString(codexTarget, "user codex file\n")
    Files.writeString(junieTarget, "user junie file\n")

    assertEquals(0, runInstall(fixture, "link-codex-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-junie-agents").exitCode)

    assertFalse(Files.isSymbolicLink(codexTarget))
    assertFalse(Files.isSymbolicLink(junieTarget))
    assertEquals("user codex file\n", Files.readString(codexTarget))
    assertEquals("user junie file\n", Files.readString(junieTarget))
  }

  @Test
  fun `native subagent commands replace legacy repository artifact symlinks`() {
    val fixture = installFixture()
    val codexTarget = fixture.home.resolve(".codex/agents/${fixture.codexToml.fileName}")
    val junieTarget = fixture.home.resolve(".junie/agents/${fixture.junieMd.fileName}")
    val legacyRoot = fixture.home.resolve("old-repo")
    val legacyCodex =
      legacyRoot.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review/codex-agents")
        .resolve(fixture.codexToml.fileName)
    val legacyJunie =
      legacyRoot.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review/junie-agents")
        .resolve(fixture.junieMd.fileName)
    listOf(legacyCodex, legacyJunie).forEach { legacy ->
      Files.createDirectories(legacy.parent)
      Files.writeString(legacy, "legacy generated artifact\n")
    }
    Files.createDirectories(codexTarget.parent)
    Files.createDirectories(junieTarget.parent)
    Files.createSymbolicLink(codexTarget, legacyCodex)
    Files.createSymbolicLink(junieTarget, legacyJunie)

    assertEquals(0, runInstall(fixture, "link-codex-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-junie-agents").exitCode)

    assertGeneratedAgentLinked(codexTarget, fixture.codexToml)
    assertGeneratedAgentLinked(junieTarget, fixture.junieMd)
    assertFalse(Files.readString(codexTarget).contains("legacy generated artifact"))
    assertFalse(Files.readString(junieTarget).contains("legacy generated artifact"))
  }

  @Test
  fun `native subagent commands replace stale install cache symlinks`() {
    val fixture = installFixture()
    val codexTarget = fixture.home.resolve(".codex/agents/${fixture.codexToml.fileName}")
    val junieTarget = fixture.home.resolve(".junie/agents/${fixture.junieMd.fileName}")
    val oldCache = fixture.home.resolve(".skill-bill/native-agents/old-cache-key")
    val oldCodex = oldCache.resolve("codex-agents/${fixture.codexToml.fileName}")
    val oldJunie = oldCache.resolve("junie-agents/${fixture.junieMd.fileName}")
    listOf(oldCodex, oldJunie).forEach { stale ->
      Files.createDirectories(stale.parent)
      Files.writeString(stale, "stale cache artifact\n")
    }
    Files.createDirectories(codexTarget.parent)
    Files.createDirectories(junieTarget.parent)
    Files.createSymbolicLink(codexTarget, oldCodex)
    Files.createSymbolicLink(junieTarget, oldJunie)

    assertEquals(0, runInstall(fixture, "link-codex-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-junie-agents").exitCode)

    assertGeneratedAgentLinked(codexTarget, fixture.codexToml)
    assertGeneratedAgentLinked(junieTarget, fixture.junieMd)
    assertFalse(codexTarget.toRealPath().startsWith(oldCache))
    assertFalse(junieTarget.toRealPath().startsWith(oldCache))
  }

  @Test
  fun `native subagent discovery ignores symlinked source files`() {
    val fixture = installFixture()
    val outsideSource = fixture.home.resolve("outside-native.md")
    Files.writeString(
      outsideSource,
      renderNativeAgentSource(
        NativeAgentSource(name = "bill-symlinked", description = "Outside source.", body = "# Outside\n"),
      ),
    )
    val sourceSymlink = fixture.codexToml.parent.parent.resolve("native-agents/bill-symlinked.md")
    Files.createSymbolicLink(sourceSymlink, outsideSource)

    assertEquals(0, runInstall(fixture, "link-codex-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-junie-agents").exitCode)

    assertFalse(Files.exists(fixture.home.resolve(".codex/agents/bill-symlinked.toml")))
    assertFalse(Files.exists(fixture.home.resolve(".junie/agents/bill-symlinked.md")))
  }

  @Test
  fun `native subagent install renders from source without rewriting stale repository artifacts`() {
    val fixture = installFixture()
    val stale = "stale generated file\n"
    Files.createDirectories(fixture.junieMd.parent)
    Files.writeString(fixture.junieMd, stale)

    assertEquals(0, runInstall(fixture, "link-junie-agents").exitCode)

    assertEquals(stale, Files.readString(fixture.junieMd))
    val installed = fixture.home.resolve(".junie/agents/${fixture.junieMd.fileName}")
    assertGeneratedAgentLinked(installed, expected = null)
    assertContains(Files.readString(installed), "# bill-kotlin-code-review-testing")
    assertFalse(Files.readString(installed).contains(stale))
  }

  @Test
  fun `link claude agents fails atomically when one source has invalid frontmatter`() {
    val fixture = installFixture()
    val skillDir = fixture.codexToml.parent.parent
    val malformedSourcePath = skillDir.resolve("native-agents/bill-malformed-source.md")
    Files.writeString(
      malformedSourcePath,
      """
      ---
      name: bill-malformed-source
      ---

      # Body
      """.trimIndent(),
    )

    val result = runInstall(fixture, "link-claude-agents")

    assertEquals(1, result.exitCode, result.stdout)
    val claudeAgentsDir = fixture.home.resolve(".claude/agents")
    if (Files.exists(claudeAgentsDir)) {
      val partialFiles =
        Files.list(claudeAgentsDir).use { stream ->
          stream.filter { path -> path.fileName.toString().startsWith("bill-") }.toList()
        }
      assertTrue(partialFiles.isEmpty(), "Expected no partial Claude agent files but found $partialFiles")
    }
  }

  @Test
  fun `native subagent install does not modify repository source files`() {
    val fixture = installFixture()
    val before = snapshotInstallRepo(fixture)

    assertEquals(0, runInstall(fixture, "link-codex-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-junie-agents").exitCode)

    assertEquals(before, snapshotInstallRepo(fixture))
  }

  @Test
  fun `mcp registration writes and removes packaged bin commands for all config formats`() {
    mcpCases().forEach { case ->
      val home = Files.createTempDirectory("skillbill-cli-mcp-${case.agent}")
      val context = installCliContext(home)
      val configPath = home.resolve(case.relativeConfigPath)
      Files.createDirectories(configPath.parent)
      Files.writeString(configPath, case.seed)

      val register =
        CliRuntime.run(
          listOf("install", "register-mcp", case.agent, "--runtime-mcp-bin", "/tmp/runtime-mcp"),
          context,
        )
      assertEquals(0, register.exitCode, "${case.agent}: ${register.stdout}")
      case.assertRegistered(Files.readString(configPath))

      val unregister = CliRuntime.run(listOf("install", "unregister-mcp", case.agent), context)
      assertEquals(0, unregister.exitCode, "${case.agent}: ${unregister.stdout}")
      case.assertUnregistered(Files.readString(configPath))
    }
  }

  @Test
  fun `claude mcp registration fans into every resolved profile and summary reports them`() {
    val home = Files.createTempDirectory("skillbill-cli-mcp-claude-multi")
    Files.createDirectories(home.resolve(".claude"))
    val work = home.resolve(".claude-work")
    Files.createDirectories(work)
    Files.writeString(work.resolve(".claude.json"), "{\n  \"theme\": \"work\"\n}\n")
    val defaultConfig = home.resolve(".claude.json")
    val workConfig = work.resolve(".claude.json")
    val context = installCliContext(home)

    val register =
      CliRuntime.run(
        listOf("--home", home.toString(), "install", "register-mcp", "claude", "--runtime-mcp-bin", "/tmp/runtime-mcp"),
        context,
      )

    assertEquals(0, register.exitCode, register.stdout)
    assertContains(register.stdout, defaultConfig.toString())
    assertContains(register.stdout, workConfig.toString())
    assertEquals(
      setOf(defaultConfig.toString(), workConfig.toString()),
      mcpProfileConfigPaths(register).toSet(),
    )
    assertTrue(mcpProfileEntries(register).all { entry -> entry["changed"] == true })
    listOf(defaultConfig, workConfig).forEach { path ->
      val servers = decodeJsonObject(Files.readString(path))["mcpServers"] as Map<*, *>
      val skillBill = servers["skill-bill"] as Map<*, *>
      assertEquals("stdio", skillBill["type"])
      assertEquals("/tmp/runtime-mcp", skillBill["command"])
    }
    assertEquals("work", decodeJsonObject(Files.readString(workConfig))["theme"])

    val unregister =
      CliRuntime.run(
        listOf("--home", home.toString(), "install", "unregister-mcp", "claude"),
        context,
      )

    assertEquals(0, unregister.exitCode, unregister.stdout)
    assertContains(unregister.stdout, defaultConfig.toString())
    assertContains(unregister.stdout, workConfig.toString())
    assertEquals(
      setOf(defaultConfig.toString(), workConfig.toString()),
      mcpProfileConfigPaths(unregister).toSet(),
    )
    assertTrue(mcpProfileEntries(unregister).all { entry -> entry["changed"] == true })
    listOf(defaultConfig, workConfig).forEach { path ->
      assertFalse(
        "skill-bill" in (decodeJsonObject(Files.readString(path))["mcpServers"] as? Map<*, *> ?: emptyMap<Any, Any>()),
      )
    }
    assertEquals("work", decodeJsonObject(Files.readString(workConfig))["theme"])
  }
}
