package skillbill.infrastructure.skills.nativeagent.rendering

import skillbill.error.shellcontent.invalidNativeAgentCompositionSchemaError
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentSource

private const val DEVIN_ALLOWED_TOOLS_KEY: String = "allowed-tools"

private val DEVIN_NEUTRAL_TOOL_NAMES: Map<String, String> =
  mapOf(
    "Read" to "read",
    "Grep" to "grep",
    "Glob" to "glob",
    "Bash" to "exec",
  )

private val EXACT_MCP_TOOL_PATTERN: Regex = Regex("^mcp__[A-Za-z0-9][A-Za-z0-9_-]*__[A-Za-z0-9][A-Za-z0-9_-]*$")

internal fun devinCapabilityFields(agent: NativeAgentSource): List<String> =
  if (agent.tools.isEmpty()) {
    emptyList()
  } else {
    listOf("$DEVIN_ALLOWED_TOOLS_KEY:") + agent.tools.map { tool -> "  - ${devinToolName(agent.name, tool)}" }
  }

private fun devinToolName(
  agentName: String,
  tool: String,
): String =
  DEVIN_NEUTRAL_TOOL_NAMES[tool]
    ?: tool.takeIf(EXACT_MCP_TOOL_PATTERN::matches)
    ?: throw invalidNativeAgentCompositionSchemaError(
      agentName,
      "tool '$tool' has no exact Devin equivalent; declare only " +
        "${DEVIN_NEUTRAL_TOOL_NAMES.keys.joinToString(", ")} or exact mcp__<server>__<tool> names",
    )
