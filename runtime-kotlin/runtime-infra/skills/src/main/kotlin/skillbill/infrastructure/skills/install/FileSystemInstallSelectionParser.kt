package skillbill.infrastructure.skills.install

import skillbill.contracts.JsonCodec
import skillbill.error.core.catchingNonCancellation
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.SharedInstallSelection
import skillbill.install.model.SupportedAgent
import skillbill.ports.repository.toFileLocation
import java.nio.file.Path

internal fun parseInstallSelectionPayload(
  path: Path,
  rawPayload: String,
): SharedInstallSelection {
  val payload =
    JsonCodec.anyToStringAnyMap(
      JsonCodec.parseObjectOrNull(rawPayload)?.let(JsonCodec::jsonElementToValue),
    ) ?: throw malformedInstallSelection(path, "Root value must be a JSON object.")
  return catchingNonCancellation { payload.toInstallSelection(path) }
    .getOrElse { error -> throw error.toMalformedInstallSelection(path) }
}

private fun Map<String, Any?>.toInstallSelection(path: Path): SharedInstallSelection {
  requireExactKeys(path, keys, INSTALL_SELECTION_KEYS, "root")
  requireContractVersion(path, requireString(path, "contract_version"))
  return SharedInstallSelection(
    selectedAgents = requireStringList(path, "selected_agents").mapTo(mutableSetOf(), SupportedAgent::fromId),
    platformPackSelection = requireObject(path, "platform_pack_selection").toPlatformPackSelection(path),
    telemetryLevel = requireTelemetryLevel(path, requireString(path, "telemetry_level")),
    mcpRegistrationChoice = requireObject(path, "mcp_registration").toMcpRegistrationChoice(path),
  )
}

private fun requireContractVersion(
  path: Path,
  version: String,
) {
  if (version != INSTALL_SELECTION_CONTRACT_VERSION) {
    throw malformedInstallSelection(
      path = path,
      reason = "Unsupported contract_version '$version'; expected '$INSTALL_SELECTION_CONTRACT_VERSION'.",
    )
  }
}

private fun Map<String, Any?>.toPlatformPackSelection(path: Path): PlatformPackSelection {
  requireExactKeys(path, keys, PLATFORM_PACK_SELECTION_KEYS, "platform_pack_selection")
  val mode =
    when (val rawMode = requireString(path, "mode")) {
      "none" -> PlatformPackSelectionMode.NONE
      "selected" -> PlatformPackSelectionMode.SELECTED
      "all" -> PlatformPackSelectionMode.ALL
      else -> throw malformedInstallSelection(path, "Unknown platform pack selection mode '$rawMode'.")
    }
  val selectedSlugs = requireStringList(path, "selected_slugs").toSet()
  if (selectedSlugs.any(String::isBlank)) {
    throw malformedInstallSelection(path, "Field 'platform_pack_selection.selected_slugs' must not contain blanks.")
  }
  requirePlatformPackSelectionInvariants(path, mode, selectedSlugs)
  return PlatformPackSelection(
    mode = mode,
    selectedSlugs = selectedSlugs,
  )
}

private fun Map<String, Any?>.toMcpRegistrationChoice(path: Path): McpRegistrationChoice {
  requireExactKeys(path, keys, MCP_REGISTRATION_KEYS, "mcp_registration")
  val runtimeMcpBin =
    when (val rawPath = get("runtime_mcp_bin")) {
      null -> null
      is String ->
        if (rawPath.isBlank()) {
          throw malformedInstallSelection(path, "Field 'runtime_mcp_bin' must not be blank.")
        } else {
          Path.of(rawPath)
        }
      else -> throw malformedInstallSelection(path, "Field 'runtime_mcp_bin' must be a string or null.")
    }
  return McpRegistrationChoice(
    register = requireBoolean(path, "register"),
    runtimeMcpBin = runtimeMcpBin?.toFileLocation(),
  )
}

private fun requireTelemetryLevel(
  path: Path,
  rawLevel: String,
): InstallTelemetryLevel =
  InstallTelemetryLevel.entries.firstOrNull { level -> level.id == rawLevel }
    ?: throw malformedInstallSelection(path, "Unknown telemetry level '$rawLevel'.")

private fun requirePlatformPackSelectionInvariants(
  path: Path,
  mode: PlatformPackSelectionMode,
  selectedSlugs: Set<String>,
) {
  when (mode) {
    PlatformPackSelectionMode.SELECTED -> {
      if (selectedSlugs.isEmpty()) {
        throw malformedInstallSelection(
          path,
          "Field 'platform_pack_selection.selected_slugs' must not be empty when mode is 'selected'.",
        )
      }
    }

    PlatformPackSelectionMode.NONE,
    PlatformPackSelectionMode.ALL,
    -> {
      if (selectedSlugs.isNotEmpty()) {
        throw malformedInstallSelection(
          path,
          "Field 'platform_pack_selection.selected_slugs' must be empty when mode is '${mode.name.lowercase()}'.",
        )
      }
    }
  }
}
