package skillbill.infrastructure.skills.install.mcp

import skillbill.contracts.persistence.RuntimeDatabaseEnvironmentKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.install.model.McpRegistrationFailureCode
import skillbill.install.model.McpRegistrationOutcome
import skillbill.ports.install.mcp.model.InstallMcpProfileEnvironment
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenCodeMcpConfigTest {
  @Test
  fun `registration preserves JSONC and repeats and removes only its managed entry`() {
    val home = Files.createTempDirectory("opencode-home")
    val configDir = home.resolve(".config/opencode")
    Files.createDirectories(configDir)
    val path = configDir.resolve("opencode.jsonc")
    val original = """{
  // keep this note
  "model": "provider/model",
  "mcp": { "other": { "type": "remote", "url": "https://example.test" } },
}
"""
    Files.writeString(path, original)
    val profile =
      requireNotNull(
        InstallMcpProfileEnvironment.from(
          mapOf(
            InstallMcpProfileEnvironment.OPENCODE_CONFIG_DIR_ENV to configDir.toString(),
            InstallMcpProfileEnvironment.PWD_ENV to home.toString(),
          ),
        ),
      )
    assertEquals(profile, InstallMcpProfileEnvironment.from(profile.asEnvironmentMap()))
    val command = home.resolve("runtime bin/runtime-mcp").toString()

    val first = OpenCodeMcpConfig(home, profile).register(path, command)
    val afterFirst = Files.readString(path)
    val second = OpenCodeMcpConfig(home, profile).register(path, command)
    val afterSecond = Files.readString(path)

    assertTrue(assertIs<McpRegistrationOutcome.Applied>(first).mutation.changed)
    assertFalse(assertIs<McpRegistrationOutcome.Applied>(second).mutation.changed)
    assertEquals(afterFirst, afterSecond)
    assertContains(afterFirst, "// keep this note")
    assertContains(afterFirst, "provider/model")
    assertContains(afterFirst, "runtime bin/runtime-mcp")
    assertContains(afterFirst, "\"command\":[\"$command\"]")
    assertContains(afterFirst, "\"type\":\"local\"")
    assertContains(afterFirst, "\"other\"")
    val removal = OpenCodeMcpConfig(home, profile).unregister(path, expectedRuntimeMcpBin = Path.of(command))
    assertTrue(assertIs<McpRegistrationOutcome.Applied>(removal).mutation.changed)
    val afterRemove = Files.readString(path)
    assertContains(afterRemove, "// keep this note")
    assertContains(afterRemove, "\"other\"")
    assertFalse(afterRemove.contains("\"skill-bill\""))

    val replacement = """{"mcp":{"skill-bill":{"type":"local","command":["other"],"environment":{}}}}"""
    Files.writeString(path, replacement)
    val refusedRemoval = OpenCodeMcpConfig(home, profile).unregister(path, expectedRuntimeMcpBin = Path.of(command))
    assertTrue(refusedRemoval is McpRegistrationOutcome.ProfilesFailed)
    assertEquals(replacement, Files.readString(path))
  }

  @Test
  fun `empty MCP object supports registration and a changed runtime binding prevents removal`() {
    val home = Files.createTempDirectory("opencode-empty-mcp")
    val path = home.resolve("opencode.jsonc")
    val original = """{"model":"provider/model","mcp":{}}"""
    Files.writeString(path, original)
    val command = home.resolve("runtime-mcp")
    val profile =
      InstallMcpProfileEnvironment(
        workingDirectory = home.toString(),
        databasePath = home.resolve("original.db").toString(),
      )

    val registration = OpenCodeMcpConfig(home, profile).register(path, command.toString())
    assertTrue(assertIs<McpRegistrationOutcome.Applied>(registration).mutation.changed)
    val managed = Files.readString(path)
    val replaced = managed.replace("original.db", "user-owned.db")
    Files.writeString(path, replaced)

    val refusal = OpenCodeMcpConfig(home, profile).unregister(path, expectedRuntimeMcpBin = command)

    assertTrue(refusal is McpRegistrationOutcome.ProfilesFailed)
    assertEquals(replaced, Files.readString(path))
    Files.writeString(path, managed)
    val removal = OpenCodeMcpConfig(home, profile).unregister(path, expectedRuntimeMcpBin = command)
    assertTrue(assertIs<McpRegistrationOutcome.Applied>(removal).mutation.changed)
    val removed = Files.readString(path)
    assertContains(removed, "provider/model")
    assertFalse(removed.contains(RuntimeDatabaseEnvironmentKeys.DATABASE_PATH))
  }

  @Test
  fun `malformed config refusal leaves original bytes untouched`() {
    val home = Files.createTempDirectory("opencode-malformed")
    val path = home.resolve("opencode.jsonc")
    val originals = listOf("{\n  \"mcp\": [\n}", "{# invalid JSONC\n}", "// comment only", "", "{\"mcp\":[]}")
    originals.forEach { original ->
      Files.writeString(path, original)
      val profile = InstallMcpProfileEnvironment(workingDirectory = home.toString())

      val registrationError =
        assertFailsWith<SkillBillRuntimeException> {
          OpenCodeMcpConfig(home, profile).register(path, "/runtime-mcp")
        }
      assertEquals(McpRegistrationFailureCode.OPENCODE_CONFIG_INVALID, registrationError.code)
      val removalError =
        assertFailsWith<SkillBillRuntimeException> {
          OpenCodeMcpConfig(home, profile).unregister(path, expectedRuntimeMcpBin = Path.of("/runtime-mcp"))
        }
      assertEquals(McpRegistrationFailureCode.OPENCODE_CONFIG_INVALID, removalError.code)
      assertEquals(original, Files.readString(path))
    }
  }

  @Test
  fun `shadowing source is refused and publication failure keeps original bytes for retry`() {
    val home = Files.createTempDirectory("opencode-shadow")
    val configDir = home.resolve(".config/opencode")
    Files.createDirectories(configDir)
    val shadow = configDir.resolve("opencode.json")
    val shadowBytes = """{"mcp":{"skill-bill":{"type":"remote","url":"https://example.test"}}}"""
    Files.writeString(shadow, shadowBytes)
    val target = configDir.resolve("opencode.jsonc")
    val profile = InstallMcpProfileEnvironment(workingDirectory = home.toString())

    val refusal = OpenCodeMcpConfig(home, profile).register(target, "/runtime-mcp")

    assertTrue(refusal is McpRegistrationOutcome.ProfilesFailed)
    assertFalse(Files.exists(target))
    assertEquals(shadowBytes, Files.readString(shadow))

    Files.delete(shadow)
    val original = "{\n  // retained\n  \"model\": \"provider/model\"\n}\n"
    Files.writeString(target, original)
    assertFailsWith<SkillBillRuntimeException> {
      OpenCodeMcpConfig(home, profile, publishFile = { _, _ ->
        throw IOException("controlled publication failure")
      }).register(target, "/runtime-mcp")
    }
    assertEquals(original, Files.readString(target))
    val retry = OpenCodeMcpConfig(home, profile).register(target, "/runtime-mcp")
    assertTrue(retry is McpRegistrationOutcome.Applied)
  }
}
