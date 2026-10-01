package skillbill.cli.scaffold.wizard

import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.model.CliRunInputs
import skillbill.install.model.SupportedAgent
import skillbill.scaffold.model.SkillKind

internal fun addOnWizardPayload(
  state: CliRunState,
  inputs: CliRunInputs,
): Map<String, Any?> =
  buildMap {
    putScaffoldBase(SkillKind.ADD_ON.wireValue)
    put("platform", promptRequired(state, inputs, "Platform slug"))
    put("name", promptRequired(state, inputs, "Add-on name"))
    when (normalizeAddOnLocationMode(promptDefault(state, inputs, "Add-on source (native/external)", "native"))) {
      "native" -> Unit
      "external" -> put("addon_location_path", promptRequired(state, inputs, "External add-on source path"))
    }
    promptOptional(state, inputs, "Description").ifNotBlank { description -> put("description", description) }
  }

internal fun agentAddonWizardPayload(
  state: CliRunState,
  inputs: CliRunInputs,
): Map<String, Any?> =
  buildMap {
    putScaffoldBase(SkillKind.AGENT_ADDON.wireValue)
    put("slug", promptRequired(state, inputs, "Agent add-on slug"))
    put("description", promptRequired(state, inputs, "Description"))
    inputs.liveStdout("Supported agents: ${SupportedAgent.supportedIds.joinToString(", ")}\n")
    put("agent_ids", requiredCommaSeparated(state, inputs, "Agent IDs (comma-separated)"))
    put("consumers", requiredCommaSeparated(state, inputs, "Consumers (comma-separated, supported: skill-bill)"))
  }

internal fun platformPackWizardPayload(
  state: CliRunState,
  inputs: CliRunInputs,
  platformPackPresets: Map<String, String>,
): Map<String, Any?> =
  buildMap {
    putScaffoldBase(SkillKind.PLATFORM_PACK.wireValue)
    val platform = promptRequired(state, inputs, "Platform slug")
    put("platform", platform)
    promptOptional(state, inputs, "Display name").ifNotBlank { displayName -> put("display_name", displayName) }
    promptOptional(state, inputs, "Description").ifNotBlank { description -> put("description", description) }
    promptRoutingSignals(state, inputs, platform, platform in platformPackPresets)
      .ifNotEmpty { signals -> put("routing_signals", mapOf("strong" to signals)) }
    applyExternalPackSourcePrompts(state, inputs, this)
  }

internal fun assistedPlatformPackWizardPayload(
  state: CliRunState,
  inputs: CliRunInputs,
  platformPackPresets: Map<String, String>,
): Map<String, Any?> {
  val platformInput = promptRequired(state, inputs, "Language or platform")
  return assistedPlatformPackPayload(platformInput, platformPackPresets).toMutableMap().also { payload ->
    applyExternalPackSourcePrompts(state, inputs, payload)
  }
}

internal fun applyExternalPackSourcePrompts(
  state: CliRunState,
  inputs: CliRunInputs,
  payload: MutableMap<String, Any?>,
) {
  val source =
    normalizePlatformPackSourceMode(
      promptDefault(state, inputs, "Pack source (native/external)", "native"),
    )
  if (source != "external") {
    return
  }
  payload["pack_location_path"] = promptRequired(state, inputs, "External pack root path")
  payload["pack_registration"] =
    normalizePlatformPackRegistration(
      promptDefault(state, inputs, "Create new pack or register existing (create/register)", "create"),
    )
}

internal fun assistedPlatformPackPayload(
  platformInput: String,
  platformPackPresets: Map<String, String>,
): Map<String, Any?> =
  buildMap {
    val profile = assistedPlatformProfile(platformInput)
    val displayName = platformPackPresets[profile.slug] ?: profile.displayName
    putScaffoldBase(SkillKind.PLATFORM_PACK.wireValue)
    put("platform", profile.slug)
    put("display_name", displayName)
    put("description", "$displayName platform pack for code review and quality checks.")
    if (profile.slug !in platformPackPresets) {
      put("routing_signals", mapOf("strong" to profile.strongSignals))
    }
  }

internal fun MutableMap<String, Any?>.putScaffoldBase(kind: String) {
  put("scaffold_payload_version", "1.0")
  put("kind", kind)
}

internal inline fun String.ifNotBlank(block: (String) -> Unit) {
  if (isNotBlank()) block(this)
}

internal inline fun <T> List<T>.ifNotEmpty(block: (List<T>) -> Unit) {
  if (isNotEmpty()) block(this)
}
