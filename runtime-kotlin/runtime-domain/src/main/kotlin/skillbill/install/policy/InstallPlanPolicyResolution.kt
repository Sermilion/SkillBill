package skillbill.install.policy

import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallPlatformPackDiscoverySnapshot
import skillbill.install.model.InstallPolicyInput
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
