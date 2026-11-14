package skillbill.infrastructure.skills.install

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.SharedInstallSelection
import skillbill.install.model.SupportedAgent

internal fun SharedInstallSelection.toInstallSelectionJson(): String = JsonCodec.mapToJsonString(toWireMap())

private fun SharedInstallSelection.toWireMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.CONTRACT_VERSION to INSTALL_SELECTION_CONTRACT_VERSION,
    InstallSelectionKeys.SELECTED_AGENTS to selectedAgents.map(SupportedAgent::id).sorted(),
    InstallSelectionKeys.PLATFORM_PACK_SELECTION to platformPackSelection.toWireMap(),
    InstallSelectionKeys.TELEMETRY_LEVEL to telemetryLevel.id,
    InstallSelectionKeys.MCP_REGISTRATION to mcpRegistrationChoice.toWireMap(),
  )

private fun PlatformPackSelection.toWireMap(): Map<String, Any?> =
  linkedMapOf(
    InstallSelectionKeys.MODE to mode.name.lowercase(),
    InstallSelectionKeys.SELECTED_SLUGS to selectedSlugs.sorted(),
  )

private fun McpRegistrationChoice.toWireMap(): Map<String, Any?> =
  linkedMapOf(
    InstallSelectionKeys.REGISTER to register,
    InstallSelectionKeys.RUNTIME_MCP_BIN to runtimeMcpBin?.toString(),
  )

internal const val INSTALL_SELECTION_CONTRACT_VERSION = "1.0"

internal val INSTALL_SELECTION_KEYS =
  setOf(
    SharedPayloadKeys.CONTRACT_VERSION,
    InstallSelectionKeys.SELECTED_AGENTS,
    InstallSelectionKeys.PLATFORM_PACK_SELECTION,
    InstallSelectionKeys.TELEMETRY_LEVEL,
    InstallSelectionKeys.MCP_REGISTRATION,
  )
internal val PLATFORM_PACK_SELECTION_KEYS = setOf(InstallSelectionKeys.MODE, InstallSelectionKeys.SELECTED_SLUGS)
internal val MCP_REGISTRATION_KEYS = setOf(InstallSelectionKeys.REGISTER, InstallSelectionKeys.RUNTIME_MCP_BIN)

internal object InstallSelectionKeys {
  const val SELECTED_AGENTS = "selected_agents"
  const val PLATFORM_PACK_SELECTION = "platform_pack_selection"
  const val TELEMETRY_LEVEL = "telemetry_level"
  const val MCP_REGISTRATION = "mcp_registration"
  const val MODE = "mode"
  const val SELECTED_SLUGS = "selected_slugs"
  const val REGISTER = "register"
  const val RUNTIME_MCP_BIN = "runtime_mcp_bin"
}
