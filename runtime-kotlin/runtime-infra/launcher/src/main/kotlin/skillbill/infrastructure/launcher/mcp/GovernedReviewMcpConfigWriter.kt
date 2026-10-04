package skillbill.infrastructure.launcher.mcp

import skillbill.contracts.review.GovernedReviewEvidenceContracts
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.GovernedReviewFailureCode
import skillbill.infrastructure.skills.install.mcp.McpTomlConfig
import skillbill.infrastructure.skills.install.mcp.mutableStringAnyMap
import skillbill.infrastructure.skills.install.mcp.readJsonObject
import skillbill.infrastructure.skills.install.mcp.writeJson
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

object GovernedReviewMcpConfigWriter {
  fun write(
    configPath: Path,
    bridgeCommand: List<String>,
    socketPath: Path,
    token: String,
    lane: String,
  ): Path {
    if (bridgeCommand.isEmpty()) {
      throw SkillBillRuntimeException(
        GovernedReviewFailureCode.EVIDENCE_TRANSPORT,
        "A governed review MCP bridge command is required.",
      )
    }
    val env =
      linkedMapOf(
        GovernedReviewEvidenceContracts.SOCKET_ENV to socketPath.toString(),
        GovernedReviewEvidenceContracts.TOKEN_ENV to token,
        GovernedReviewEvidenceContracts.LANE_ENV to lane,
      )
    val settings =
      linkedMapOf<String, Any?>(
        "mcpServers" to
          linkedMapOf(
            GovernedReviewEvidenceContracts.SERVER_NAME to
              linkedMapOf(
                "type" to "stdio",
                "command" to bridgeCommand.first(),
                "args" to bridgeCommand.drop(1),
                "env" to env,
              ),
          ),
      )
    try {
      writeJson(configPath, settings)
      Files.setPosixFilePermissions(configPath, PosixFilePermissions.fromString("rw-------"))
      val cursorConfig = cursorProjectConfigPath(configPath)
      Files.createDirectories(cursorConfig.parent)
      writeJson(cursorConfig, settings)
      Files.setPosixFilePermissions(cursorConfig, PosixFilePermissions.fromString("rw-------"))
      val tomlPath = tomlConfigPath(configPath)
      McpTomlConfig.writeGovernedServer(
        tomlPath,
        McpTomlConfig.GovernedServer(
          serverName = GovernedReviewEvidenceContracts.SERVER_NAME,
          command = bridgeCommand.first(),
          args = bridgeCommand.drop(1),
          env = env,
          enabledTools = GovernedReviewEvidenceContracts.OPERATIONS,
        ),
      )
      Files.setPosixFilePermissions(tomlPath, PosixFilePermissions.fromString("rw-------"))
    } catch (error: IOException) {
      throw SkillBillRuntimeException(
        GovernedReviewFailureCode.EVIDENCE_TRANSPORT,
        "Failed to write the per-launch governed review MCP config at '$configPath'.",
        error,
      )
    }
    return configPath
  }

  fun cursorProjectConfigPath(mcpConfigPath: Path): Path = mcpConfigPath.parent.resolve(".cursor").resolve("mcp.json")

  fun tomlConfigPath(mcpConfigPath: Path): Path = mcpConfigPath.resolveSibling("mcp.toml")

  fun codexConfigOverrides(
    mcpConfigPath: Path,
    socketPath: Path,
    token: String,
    lane: String,
  ): List<String> {
    val prefix = "mcp_servers.${GovernedReviewEvidenceContracts.SERVER_NAME}"
    val server =
      if (Files.isRegularFile(mcpConfigPath)) {
        mutableStringAnyMap(readJsonObject(mcpConfigPath)["mcpServers"])
          .let { mutableStringAnyMap(it[GovernedReviewEvidenceContracts.SERVER_NAME]) }
      } else {
        linkedMapOf()
      }
    val command = server["command"]?.toString().orEmpty()
    val args = (server["args"] as? List<*>)?.map { it.toString() }.orEmpty()
    val env =
      mutableStringAnyMap(server["env"]).ifEmpty {
        linkedMapOf(
          GovernedReviewEvidenceContracts.SOCKET_ENV to socketPath.toString(),
          GovernedReviewEvidenceContracts.TOKEN_ENV to token,
          GovernedReviewEvidenceContracts.LANE_ENV to lane,
        )
      }
    return buildList {
      if (command.isNotEmpty()) add("$prefix.command=\"$command\"")
      add("$prefix.args=[${args.joinToString(", ") { "\"$it\"" }}]")
      GovernedReviewEvidenceContracts.OPERATIONS.forEach { operation ->
        add("$prefix.tools.$operation.approval_mode=\"approve\"")
      }
      add("$prefix.enabled_tools=[${GovernedReviewEvidenceContracts.OPERATIONS.joinToString(", ") { "\"$it\"" }}]")
      add("$prefix.env={${env.entries.joinToString(", ") { "${it.key}=\"${it.value}\"" }}}")
    }
  }
}
