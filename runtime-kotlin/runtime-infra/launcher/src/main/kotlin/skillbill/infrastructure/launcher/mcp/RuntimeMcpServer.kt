package skillbill.infrastructure.launcher.mcp

import skillbill.contracts.JsonCodec
import skillbill.contracts.persistence.RuntimeDatabaseEnvironmentKeys
import skillbill.contracts.persistence.RuntimeMcpEnvironmentKeys
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
    ?.let { listOf("--mcp-config", claudeRuntimeMcpConfig(it, databasePath, request.validationCheckInvocationId)) }
    .orEmpty()

internal fun claudeRuntimeSettlementToolArguments(
  request: SkillRunRequest,
  runtimeMcpBin: Path?,
): List<String> =
  if (runtimeMcpBin != null && attachesRuntimeMcp(request)) {
    listOf("--allowedTools", RUNTIME_SETTLEMENT_TOOLS.joinToString(","))
  } else {
    emptyList()
  }

private val RUNTIME_SETTLEMENT_TOOLS =
  listOf(
    "mcp__${RUNTIME_MCP_SERVER_NAME}__feature_task_phase_complete",
    "mcp__${RUNTIME_MCP_SERVER_NAME}__feature_task_phase_block",
  )

internal fun codexRuntimeMcpArguments(
  request: SkillRunRequest,
  runtimeMcpBin: Path?,
  databasePath: Path?,
): List<String> =
  runtimeMcpBin
    ?.takeIf { attachesRuntimeMcp(request) }
    ?.let { codexRuntimeMcpOverrides(it, databasePath, request.validationCheckInvocationId) }
    ?.flatMap { override -> listOf("--config", override) }
    .orEmpty()

private fun attachesRuntimeMcp(request: SkillRunRequest): Boolean =
  request.reviewEvidenceBroker == null && request.reviewEvidenceEndpoint == null

private fun claudeRuntimeMcpConfig(
  runtimeMcpBin: Path,
  databasePath: Path?,
  validationCheckInvocationId: String?,
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
              "env" to runtimeMcpServerEnvironment(databasePath, validationCheckInvocationId),
            ),
        ),
    ),
  )

private fun codexRuntimeMcpOverrides(
  runtimeMcpBin: Path,
  databasePath: Path?,
  validationCheckInvocationId: String?,
): List<String> {
  val prefix = "mcp_servers.$RUNTIME_MCP_SERVER_NAME"
  val environment = runtimeMcpServerEnvironment(databasePath, validationCheckInvocationId)
  return buildList {
    add("$prefix.command=\"$runtimeMcpBin\"")
    add("$prefix.args=[]")
    if (environment.isNotEmpty()) {
      add("$prefix.env={${environment.entries.joinToString(", ") { "${it.key}=\"${it.value}\"" }}}")
    }
  }
}

internal fun runtimeMcpServerEnvironment(
  databasePath: Path?,
  validationCheckInvocationId: String? = null,
): Map<String, String> =
  buildMap {
    databasePath?.let { put(RuntimeDatabaseEnvironmentKeys.DATABASE_PATH, it.toAbsolutePath().normalize().toString()) }
    validationCheckInvocationId?.let { put(RuntimeMcpEnvironmentKeys.VALIDATION_INVOCATION_ID, it) }
  }
