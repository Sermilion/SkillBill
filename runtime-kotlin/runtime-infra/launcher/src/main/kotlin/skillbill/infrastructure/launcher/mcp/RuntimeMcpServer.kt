package skillbill.infrastructure.launcher.mcp

import skillbill.contracts.JsonCodec
import skillbill.contracts.persistence.RuntimeDatabaseEnvironmentKeys
import skillbill.infrastructure.host.jvm.resolveUserHome
import skillbill.model.EnvironmentContext
import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Files
import java.nio.file.Path

internal const val RUNTIME_MCP_SERVER_NAME: String = "skill-bill"
internal const val RUNTIME_MCP_BIN_ENV: String = "SKILL_BILL_RUNTIME_MCP_BIN"

internal fun runtimeMcpBinCandidate(
  environment: Map<String, String>,
  userHome: Path,
): Path {
  val configured = environment[RUNTIME_MCP_BIN_ENV]?.takeIf(String::isNotBlank)
  val home =
    userHome.takeUnless { it.toString().isBlank() || it == EnvironmentContext.UnspecifiedUserHome }
      ?: environment["HOME"]?.takeIf(String::isNotBlank)?.let(Path::of)
      ?: resolveUserHome(null)
  return configured?.let(Path::of)
    ?: home.resolve(".skill-bill").resolve("runtime").resolve("runtime-mcp").resolve("bin").resolve("runtime-mcp")
}

internal fun resolveRuntimeMcpBin(
  environment: Map<String, String>,
  userHome: Path,
): Path? =
  runtimeMcpBinCandidate(environment, userHome)
    .takeIf(Files::isExecutable)
    ?.toAbsolutePath()
    ?.normalize()

internal fun claudeRuntimeMcpArguments(
  request: SkillRunRequest,
  runtimeMcpBin: Path?,
  databasePath: Path?,
): List<String> =
  runtimeMcpBin
    ?.takeIf { attachesRuntimeMcp(request) }
    ?.let { listOf("--mcp-config", claudeRuntimeMcpConfig(it, databasePath)) }
    .orEmpty()

internal fun codexRuntimeMcpArguments(
  request: SkillRunRequest,
  runtimeMcpBin: Path?,
  databasePath: Path?,
): List<String> =
  runtimeMcpBin
    ?.takeIf { attachesRuntimeMcp(request) }
    ?.let { codexRuntimeMcpOverrides(it, databasePath).flatMap { override -> listOf("--config", override) } }
    .orEmpty()

private fun attachesRuntimeMcp(request: SkillRunRequest): Boolean =
  request.reviewEvidenceBroker == null && request.reviewEvidenceEndpoint == null

private fun claudeRuntimeMcpConfig(
  runtimeMcpBin: Path,
  databasePath: Path?,
): String =
  JsonCodec.mapToJsonString(
    mapOf(
      "mcpServers" to
        mapOf(
          RUNTIME_MCP_SERVER_NAME to
            linkedMapOf(
              "type" to "stdio",
              "command" to runtimeMcpBin.toString(),
              "args" to emptyList<String>(),
              "env" to runtimeMcpServerEnvironment(databasePath),
            ),
        ),
    ),
  )

private fun codexRuntimeMcpOverrides(
  runtimeMcpBin: Path,
  databasePath: Path?,
): List<String> {
  val prefix = "mcp_servers.$RUNTIME_MCP_SERVER_NAME"
  val environment = runtimeMcpServerEnvironment(databasePath)
  return buildList {
    add("$prefix.command=\"$runtimeMcpBin\"")
    add("$prefix.args=[]")
    if (environment.isNotEmpty()) {
      add("$prefix.env={${environment.entries.joinToString(", ") { "${it.key}=\"${it.value}\"" }}}")
    }
  }
}

private fun runtimeMcpServerEnvironment(databasePath: Path?): Map<String, String> =
  databasePath
    ?.let { mapOf(RuntimeDatabaseEnvironmentKeys.DATABASE_PATH to it.toAbsolutePath().normalize().toString()) }
    .orEmpty()
