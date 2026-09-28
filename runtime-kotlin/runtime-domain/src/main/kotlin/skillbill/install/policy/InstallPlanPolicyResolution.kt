package skillbill.install.policy

import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallPlatformPackDiscoverySnapshot
import skillbill.install.model.InstallPlatformPackSnapshot
import skillbill.install.model.InstallPolicyInput
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.SupportedAgent

internal fun resolveAgentTargets(input: InstallPolicyInput): List<InstallAgentTarget> =
  when (input.request.agentSelection.mode) {
    InstallAgentSelectionMode.DETECTED -> resolveDetectionDerivedTargets(input)
    InstallAgentSelectionMode.MANUAL -> resolveManualTargets(input)
  }

internal fun resolveDetectionDerivedTargets(input: InstallPolicyInput): List<InstallAgentTarget> {
  val selectedTargets = input.request.agentSelection.detectedTargets.ifEmpty { input.detectedAgentTargets }
  return selectedTargets.map { target -> target.copy(source = InstallAgentTargetSource.DETECTED) }
}

internal fun resolveManualTargets(input: InstallPolicyInput): List<InstallAgentTarget> {
  val explicitTargets = input.request.targetPaths.agentTargets.groupBy(InstallAgentTarget::agent)
  val defaultTargets = input.defaultAgentTargets.groupBy { target -> target.agent }
  val manualAgents =
    input.request.agentSelection.manualAgents
      .ifEmpty { explicitTargets.keys }
  return manualAgents
    .sortedBy(SupportedAgent::id)
    .flatMap { agent ->
      explicitTargets[agent]?.map { target -> target.copy(source = InstallAgentTargetSource.MANUAL) }
        ?: (defaultTargets[agent] ?: error("Manual agent '${agent.id}' has no explicit or default target path."))
          .map { default ->
            InstallAgentTarget(
              agent = agent,
              path = default.path,
              source = InstallAgentTargetSource.MANUAL,
            )
          }
    }
}

/** Parent skill every platform-pack sidecar declares in `internal-for` and installs under. */
const val PACK_SIDECAR_PARENT_SKILL = "skill-bill"

fun selectedPlatformSlugs(input: InstallPolicyInput): List<String> {
  val explicitlySelected =
    selectedPlatformSlugs(
      selection = input.request.platformPackSelection,
      discoveredSlugs = input.platformPacks.map(InstallPlatformPackSnapshot::slug),
    )
  val selected = explicitlySelected.toMutableSet()
  if (input.baseSkills.any { it.name == PACK_SIDECAR_PARENT_SKILL }) {
    input.resolvedReviewFallbackSlug?.let(selected::add)
  }
  var changed: Boolean
  do {
    changed = false
    input.platformPacks.forEach { pack ->
      val selectedRequiredBaseline =
        pack.baselineLayers.any { layer ->
          layer.required && layer.platform in selected
        }
      if (selectedRequiredBaseline && selected.add(pack.slug)) {
        changed = true
      }
    }
  } while (changed)
  return input.platformPacks.map(InstallPlatformPackSnapshot::slug).filter(selected::contains)
}

internal fun expandRequiredComposedPacks(
  explicitlySelected: List<String>,
  platformPacks: List<InstallPlatformPackDiscoverySnapshot>,
): List<String> {
  val selected = explicitlySelected.toMutableSet()
  var changed: Boolean
  do {
    changed = false
    platformPacks.forEach { pack ->
      if (pack.baselineLayers.any { it.required && it.platform in selected } && selected.add(pack.slug)) {
        changed = true
      }
    }
  } while (changed)
  return platformPacks.map(InstallPlatformPackDiscoverySnapshot::slug).filter(selected::contains)
}

fun selectedPlatformSlugs(
  selection: PlatformPackSelection,
  discoveredSlugs: List<String>,
): List<String> =
  when (selection.mode) {
    PlatformPackSelectionMode.NONE -> emptyList()
    PlatformPackSelectionMode.ALL -> discoveredSlugs
    PlatformPackSelectionMode.SELECTED -> {
      val unknown = selection.selectedSlugs - discoveredSlugs.toSet()
      require(unknown.isEmpty()) {
        "Unknown platform pack selection: ${unknown.sorted().joinToString(", ")}. " +
          "Discovered platform packs: ${discoveredSlugs.joinToString(", ")}."
      }
      discoveredSlugs.filter { slug -> slug in selection.selectedSlugs }
    }
  }
