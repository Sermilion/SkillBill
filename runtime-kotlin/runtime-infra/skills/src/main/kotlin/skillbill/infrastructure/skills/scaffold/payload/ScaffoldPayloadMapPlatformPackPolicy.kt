package skillbill.infrastructure.skills.scaffold.payload

import skillbill.error.shellcontent.invalidScaffoldPayloadError
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.policy.PLATFORM_PACK_PRESET_DESCRIPTORS
import skillbill.scaffold.policy.displayNameFromSlug
import skillbill.scaffold.policy.model.PlatformPackDefaults
import skillbill.scaffold.policy.model.PlatformPackSelection

internal fun resolvePlatformPackSelection(payload: Map<String, Any?>): PlatformPackSelection {
  rejectLegacyPlatformPackSelector(payload, "skeleton_mode")
  rejectLegacyPlatformPackSelector(payload, "specialist_areas")
  return PlatformPackSelection(selectedAreas = APPROVED_CODE_REVIEW_AREAS.sorted())
}

private fun rejectLegacyPlatformPackSelector(
  payload: Map<String, Any?>,
  field: String,
) {
  if (!payload.containsKey(field)) return
  throw invalidScaffoldPayloadError(
    "Scaffold payload field '$field' is no longer supported for kind 'platform-pack'. " +
      "Create the full platform pack, then remove unwanted focus areas through governed removal paths.",
  )
}

internal fun resolvePlatformPackDefaults(
  payload: Map<String, Any?>,
  platform: String,
): PlatformPackDefaults {
  val preset = PLATFORM_PACK_PRESET_DESCRIPTORS[platform]
  val routing = payload["routing_signals"] as? Map<*, *>
  val strong = routing?.get("strong")?.let { requireStringListPayload(it, "routing_signals.strong") }
  val tieBreakers = routing?.get("tie_breakers")?.let { requireStringListPayload(it, "routing_signals.tie_breakers") }
  val resolvedStrong = completeExtensionPairs(strong ?: preset?.strongSignals.orEmpty())
  enforceRoutingSignalsStrongNonEmpty(resolvedStrong, preset != null, platform)
  val displayName =
    requireStringOrDefaultMap(
      payload,
      "display_name",
      preset?.displayName ?: displayNameFromSlug(platform),
    )
  val resolvedTieBreakers =
    completeTieBreakerContract(
      tieBreakers ?: preset?.tieBreakers.orEmpty(),
      displayName,
    )
  return PlatformPackDefaults(
    displayName = displayName,
    strongSignals = resolvedStrong,
    tieBreakers = resolvedTieBreakers,
    presetUsed = preset != null && routing == null,
  )
}

private fun completeExtensionPairs(signals: List<String>): List<String> =
  buildList {
    signals.forEach { signal ->
      add(signal)
      if (signal.matches(Regex("\\.[A-Za-z0-9]+"))) add("*$signal")
      if (signal.matches(Regex("\\*\\.[A-Za-z0-9]+"))) add(signal.removePrefix("*"))
    }
  }.distinct()

private fun completeTieBreakerContract(
  rules: List<String>,
  displayName: String,
): List<String> =
  buildList {
    addAll(rules)
    if (rules.none { containsAll(it, "prefer", "dominat") && !it.contains("do not prefer", ignoreCase = true) }) {
      add("Prefer $displayName when its declared strong signals dominate the changed product surface.")
    }
    if (rules.none { containsAll(it, "do not prefer", "adjacent") }) {
      add("Do not prefer $displayName when an adjacent pack's declared signals dominate.")
    }
    if (!containsAll(rules.joinToString(" "), "exclude", "generated", "vendor", "dominan")) {
      add("Exclude generated and vendored files from dominance scoring.")
    }
  }

private fun containsAll(
  content: String,
  vararg fragments: String,
): Boolean = fragments.all { content.contains(it, ignoreCase = true) }

private fun enforceRoutingSignalsStrongNonEmpty(
  strong: List<String>,
  hasPreset: Boolean,
  platform: String,
) {
  if (strong.isNotEmpty()) return
  val message =
    if (!hasPreset) {
      "Scaffold payload field 'routing_signals.strong' must contain at least one routing signal " +
        "when no built-in platform preset exists for '$platform'."
    } else {
      "Scaffold payload field 'routing_signals.strong' must contain at least one routing signal."
    }
  throw invalidScaffoldPayloadError(message)
}
