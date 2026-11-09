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
    INSTALL_SELECTION_PROFILE_KEY to installationProfile.wireValue,
    "selected_agents" to selectedAgents.map(SupportedAgent::id).sorted(),
    "platform_pack_selection" to platformPackSelection.toWireMap(),
    "telemetry_level" to telemetryLevel.id,
    "mcp_registration" to mcpRegistrationChoice.toWireMap(),
  )

private fun PlatformPackSelection.toWireMap(): Map<String, Any?> =
  linkedMapOf(
    "mode" to mode.name.lowercase(),
    "selected_slugs" to selectedSlugs.sorted(),
  )

private fun McpRegistrationChoice.toWireMap(): Map<String, Any?> =
  linkedMapOf(
    "register" to register,
    "runtime_mcp_bin" to runtimeMcpBin?.toString(),
  )

internal const val INSTALL_SELECTION_CONTRACT_VERSION = "1.1"
internal const val INSTALL_SELECTION_PREVIOUS_CONTRACT_VERSION = "1.0"
internal const val INSTALL_SELECTION_PROFILE_KEY = "installation_profile"

internal val INSTALL_SELECTION_KEYS =
  setOf(
    SharedPayloadKeys.CONTRACT_VERSION,
    INSTALL_SELECTION_PROFILE_KEY,
    "selected_agents",
    "platform_pack_selection",
    "telemetry_level",
    "mcp_registration",
  )
internal val INSTALL_SELECTION_LEGACY_KEYS = INSTALL_SELECTION_KEYS - INSTALL_SELECTION_PROFILE_KEY
internal val PLATFORM_PACK_SELECTION_KEYS = setOf("mode", "selected_slugs")
internal val MCP_REGISTRATION_KEYS = setOf("register", "runtime_mcp_bin")
