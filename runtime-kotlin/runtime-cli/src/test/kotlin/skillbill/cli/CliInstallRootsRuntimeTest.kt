package skillbill.cli

import skillbill.cli.core.CliRuntime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliInstallRootsRuntimeTest {
  @Test
  fun `claude mcp registration preserves other servers and fails loudly for a malformed profile`() {
    val home = Files.createTempDirectory("skillbill-cli-mcp-claude-malformed")
    Files.createDirectories(home.resolve(".claude"))
    val defaultConfig = home.resolve(".claude.json")
    Files.writeString(defaultConfig, "{\n  \"mcpServers\": {\"other\": {\"command\": \"other\"}}\n}\n")
    val work = home.resolve(".claude-work")
    Files.createDirectories(work)
    val malformed = work.resolve(".claude.json")
    Files.writeString(malformed, "{ not valid json")
    val context = installCliContext(home)

    val register =
      CliRuntime.run(
        listOf("--home", home.toString(), "install", "register-mcp", "claude", "--runtime-mcp-bin", "/tmp/runtime-mcp"),
        context,
      )

    assertEquals(1, register.exitCode)
    assertContains(register.stderr, malformed.toString())
    assertEquals("{ not valid json", Files.readString(malformed))
    val servers = decodeJsonObject(Files.readString(defaultConfig))["mcpServers"] as Map<*, *>
    assertEquals(setOf("other", "sona"), servers.keys)
  }

  @Test
  fun `claude mcp unregister fails loudly yet surfaces profiles it already removed`() {
    val home = Files.createTempDirectory("skillbill-cli-mcp-claude-partial-unregister")
    Files.createDirectories(home.resolve(".claude"))
    val work = home.resolve(".claude-work")
    Files.createDirectories(work)
    Files.createFile(work.resolve(".claude.json"))
    val defaultConfig = home.resolve(".claude.json")
    val workConfig = work.resolve(".claude.json")
    val liveStdout = StringBuilder()
    val context = installCliContext(home).copy(liveStdout = { liveStdout.append(it) })

    val register =
      CliRuntime.run(
        listOf("--home", home.toString(), "install", "register-mcp", "claude", "--runtime-mcp-bin", "/tmp/runtime-mcp"),
        context,
      )
    assertEquals(0, register.exitCode, register.stdout)

    Files.writeString(workConfig, "{ not valid json")

    val unregister =
      CliRuntime.run(
        listOf("--home", home.toString(), "install", "unregister-mcp", "claude"),
        context,
      )

    assertEquals(1, unregister.exitCode)
    assertContains(liveStdout.toString(), defaultConfig.toString())
    assertFalse(
      "sona" in (
        decodeJsonObject(
          Files.readString(defaultConfig),
        )["mcpServers"] as? Map<*, *> ?: emptyMap<Any, Any>()
      ),
    )
    assertEquals("{ not valid json", Files.readString(workConfig))
  }

  @Test
  fun `cleanup command removes skill bill links and reports user paths as skipped`() {
    val home = Files.createTempDirectory("skillbill-cli-install-cleanup")
    val targetDir = home.resolve("agent-skills")
    Files.createDirectories(targetDir)
    val skillSource = home.resolve("bill-test-skill")
    Files.createDirectories(skillSource)
    Files.createSymbolicLink(targetDir.resolve("bill-test-skill"), skillSource)
    Files.writeString(targetDir.resolve("not-skill-bill"), "user file")

    val cleanup =
      CliRuntime.run(
        listOf(
          "install",
          "cleanup-agent-target",
          "--target-dir",
          targetDir.toString(),
          "--skill-name",
          "bill-test-skill",
          "--legacy-name",
          "not-skill-bill",
        ),
        installCliContext(home),
      )

    assertEquals(0, cleanup.exitCode, cleanup.stdout)
    assertContains(cleanup.stdout, "removed\t${targetDir.resolve("bill-test-skill")}")
    assertContains(cleanup.stdout, "skipped\t${targetDir.resolve("not-skill-bill")}")
    assertFalse(Files.exists(targetDir.resolve("bill-test-skill")))
    assertTrue(Files.exists(targetDir.resolve("not-skill-bill")))
  }

  @Test
  fun `cleanup command prunes orphan staging links not named on the command line`() {
    val home = Files.createTempDirectory("skillbill-cli-install-cleanup-orphan")
    val targetDir = home.resolve("agent-skills")
    Files.createDirectories(targetDir)
    val stagingRoot = home.resolve(".skill-bill/installed-skills")
    Files.createDirectories(stagingRoot)

    val currentStaging = stagingRoot.resolve("bill-current-deadbeefdeadbeef")
    Files.createDirectories(currentStaging)
    Files.createSymbolicLink(targetDir.resolve("bill-current"), currentStaging)

    Files.createSymbolicLink(
      targetDir.resolve("bill-go-code-review"),
      stagingRoot.resolve("bill-go-code-review-0123456789abcdef"),
    )

    val userTarget = home.resolve("user-thing")
    Files.createDirectories(userTarget)
    Files.createSymbolicLink(targetDir.resolve("user-link"), userTarget)

    val cleanup =
      CliRuntime.run(
        listOf(
          "--home",
          home.toString(),
          "install",
          "cleanup-agent-target",
          "--target-dir",
          targetDir.toString(),
          "--skill-name",
          "bill-current",
        ),
        installCliContext(home),
      )

    assertEquals(0, cleanup.exitCode, cleanup.stdout)
    assertFalse(Files.isSymbolicLink(targetDir.resolve("bill-current")))
    assertFalse(Files.isSymbolicLink(targetDir.resolve("bill-go-code-review")))
    assertTrue(Files.isSymbolicLink(targetDir.resolve("user-link")))
  }

  @Test
  fun `global home option supports paths with spaces`() {
    val home = Files.createTempDirectory("skillbill cli home with spaces")
    Files.createDirectories(home.resolve(".codex"))

    val result =
      CliRuntime.run(
        listOf("--home", home.toString(), "install", "agent-path", "codex"),
        installCliContext(home),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(home.resolve(".codex/skills").toString(), result.stdout.trim())
  }

  @Test
  fun `claude-roots prints every resolved config root one per line`() {
    val home = Files.createTempDirectory("skillbill-cli-claude-roots")
    Files.createDirectories(home.resolve(".claude"))
    val work = home.resolve(".claude-work")
    Files.createDirectories(work)
    Files.createFile(work.resolve(".claude.json"))

    val result =
      CliRuntime.run(
        listOf("--home", home.toString(), "install", "claude-roots"),
        installCliContext(home),
      )

    assertEquals(0, result.exitCode, result.stdout)
    val lines = result.stdout.trim().lines().filter(String::isNotBlank)
    assertEquals(
      listOf(home.resolve(".claude").toString(), work.toString()),
      lines,
    )
  }

  @Test
  fun `claude-roots exposes a roots json payload`() {
    val home = Files.createTempDirectory("skillbill-cli-claude-roots-json")
    Files.createDirectories(home.resolve(".claude"))

    val result =
      CliRuntime.run(
        listOf("--home", home.toString(), "install", "claude-roots"),
        installCliContext(home),
      )

    assertEquals(0, result.exitCode, result.stdout)
    val roots =
      (result.payload?.get("roots") as? List<*>)
        ?.mapNotNull { entry -> entry as? String }
        .orEmpty()
    assertEquals(listOf(home.resolve(".claude").toString()), roots)
  }

  @Test
  fun `agent-path stays single active root even with named profiles present`() {
    val home = Files.createTempDirectory("skillbill-cli-agent-path-single")
    Files.createDirectories(home.resolve(".claude"))
    val work = home.resolve(".claude-work")
    Files.createDirectories(work)
    Files.createFile(work.resolve(".claude.json"))

    val result =
      CliRuntime.run(
        listOf("--home", home.toString(), "install", "agent-path", "claude"),
        installCliContext(home),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(home.resolve(".claude/skills").toString(), result.stdout.trim())
  }
}
