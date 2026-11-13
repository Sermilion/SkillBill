package skillbill.infrastructure.skills.install.mcp

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class McpTomlConfigTest {
  @Test
  fun `multiline strings ending in quotes do not consume the next server during migration or removal`() {
    val triple = "\"\"\""
    val literalTriple = "'''"
    val descriptions =
      listOf(
        "description = ${triple}ends in an escaped quote\\\"$triple",
        "description = [${triple}one$triple\", ${triple}two$triple]",
        "description = ${triple}four quotes$triple\"",
        "description = ${literalTriple}five quotes$literalTriple''",
      )
    val retained = "[mcp_servers.linear-sona]\nurl = \"https://mcp.linear.app/mcp\"\n"
    listOf("skill-bill", "sona").forEach { server ->
      descriptions.forEach { description ->
        listOf(true, false).forEach { register ->
          val path = Files.createTempFile("mcp-toml-quotes", ".toml")
          Files.writeString(path, "[mcp_servers.$server]\n$description\n$retained")

          if (register) {
            McpTomlConfig.register("codex", path, "/opt/sona-mcp")
            assertContains(Files.readString(path), retained)
          }
          McpTomlConfig.unregister("codex", path)

          assertEquals(retained, Files.readString(path))
          assertFalse(McpTomlConfig.unregister("codex", path).changed)
        }
      }
    }
  }

  @Test
  fun `escaped product table keys migrate without duplicate server definitions`() {
    val headers =
      listOf(
        "[\"mcp_\\u0073ervers\".\"\\u0073ona\"]",
        "[mcp_servers.\"\\U00000073ona\".env]",
        "[mcp_servers.\"skill-\\u0062ill\"]",
      )
    val retained = "[mcp_servers.linear-sona]\nurl = \"https://mcp.linear.app/mcp\"\n"
    headers.forEach { header ->
      val path = Files.createTempFile("mcp-toml-escaped-keys", ".toml")
      val original = "$header\nvalue = \"old\"\n$retained"
      Files.writeString(path, original)

      McpTomlConfig.register("codex", path, "/opt/sona-mcp")
      val registered = Files.readString(path)
      assertFalse(registered.contains(header))
      assertEquals(1, registered.lines().count { it == "[mcp_servers.sona]" })
      assertContains(registered, retained)
      assertFalse(McpTomlConfig.register("codex", path, "/opt/sona-mcp").changed)

      Files.writeString(path, original)
      McpTomlConfig.unregister("codex", path)
      assertEquals(retained, Files.readString(path))
    }
  }

  @Test
  fun `empty quoted keys still end the selected server section`() {
    listOf("[\"\"]", "[profiles.\"\"]", "['profiles'.'']").forEach { header ->
      val path = Files.createTempFile("mcp-toml-empty-key", ".toml")
      val retained = "$header\nvalue = \"keep\"\n"
      val original = "[mcp_servers.sona]\ncommand = \"old\"\n$retained"
      Files.writeString(path, original)

      McpTomlConfig.register("codex", path, "/opt/sona-mcp")
      assertContains(Files.readString(path), retained)
      McpTomlConfig.unregister("codex", path)
      assertEquals(retained, Files.readString(path))

      Files.writeString(path, original)
      McpTomlConfig.unregister("codex", path)
      assertEquals(retained, Files.readString(path))
    }
  }
}
