package skillbill.infrastructure.launcher.agentrun

import skillbill.contracts.JsonCodec
import skillbill.infrastructure.launcher.mcp.RUNTIME_MCP_BIN_ENV
import skillbill.infrastructure.launcher.mcp.resolveRuntimeMcpBin
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeMcpServerTest {
  private val bin = Path.of("/opt/skill-bill/runtime-mcp/bin/runtime-mcp")
  private val database = Path.of("/opt/skill-bill/review-metrics.db")

  @Test
  fun `a claude phase worker carries the skill-bill server bound to the runtime database`() {
    val command = ClaudeAgentRunCommandBuilder(databasePath = database, runtimeMcpBin = bin).build(request()).command

    val config = command[command.indexOf("--mcp-config") + 1]
    val servers =
      JsonCodec.anyToStringAnyMap(
        JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(config))?.get("mcpServers"),
      )
    val server = JsonCodec.anyToStringAnyMap(servers?.get("skill-bill"))
    assertEquals(bin.toString(), server?.get("command"))
    assertEquals(emptyList<Any?>(), server?.get("args"))
    assertEquals(mapOf("SKILL_BILL_REVIEW_DB" to database.toString()), server?.get("env"))
    assertEquals("--dangerously-skip-permissions", command[command.indexOf("--mcp-config") + 2])
    assertTrue("--strict-mcp-config" !in command)
  }

  @Test
  fun `a governed claude review keeps only its isolated evidence server`() {
    val command =
      ClaudeAgentRunCommandBuilder(databasePath = database, runtimeMcpBin = bin)
        .build(governedReviewRequest(nativeReviewWorkerName = "bill-code-review-inline"))
        .command

    assertEquals(1, command.count { it == "--mcp-config" })
    assertTrue(command.none { it.contains(bin.toString()) })
  }

  @Test
  fun `a codex phase worker carries the skill-bill server and a governed codex review does not`() {
    val builder = CodexAgentRunCommandBuilder(databasePath = database, runtimeMcpBin = bin)

    val worker = builder.build(request()).command
    assertTrue("mcp_servers.skill-bill.command=\"$bin\"" in worker)
    assertTrue("mcp_servers.skill-bill.args=[]" in worker)
    assertTrue("mcp_servers.skill-bill.env={SKILL_BILL_REVIEW_DB=\"$database\"}" in worker)

    val governed = builder.build(governedReviewRequest()).command
    assertTrue(governed.none { it.startsWith("mcp_servers.skill-bill.") })
  }

  @Test
  fun `no resolved runtime-mcp binary leaves the worker command unchanged`() {
    assertTrue(
      ClaudeAgentRunCommandBuilder(databasePath = database).build(request()).command.none { it == "--mcp-config" },
    )
    assertTrue(
      CodexAgentRunCommandBuilder(
        databasePath = database,
      ).build(request()).command.none { it.startsWith("mcp_servers.") },
    )
  }

  @Test
  fun `the runtime-mcp binary resolves only when it is executable`() {
    val home = Files.createTempDirectory("runtime-mcp-home")
    val installed = home.resolve(".skill-bill/runtime/runtime-mcp/bin/runtime-mcp")
    Files.createDirectories(installed.parent)
    Files.writeString(installed, "#!/bin/sh\n")

    assertNull(resolveRuntimeMcpBin(emptyMap(), home))

    Files.setPosixFilePermissions(installed, PosixFilePermissions.fromString("rwx------"))
    assertEquals(installed.toAbsolutePath().normalize(), resolveRuntimeMcpBin(emptyMap(), home))

    val configured = home.resolve("custom-mcp")
    Files.writeString(configured, "#!/bin/sh\n")
    Files.setPosixFilePermissions(configured, PosixFilePermissions.fromString("rwx------"))
    assertEquals(configured, resolveRuntimeMcpBin(mapOf(RUNTIME_MCP_BIN_ENV to configured.toString()), home))
  }
}
