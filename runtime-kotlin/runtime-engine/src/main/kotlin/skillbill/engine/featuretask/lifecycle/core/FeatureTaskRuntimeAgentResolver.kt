package skillbill.engine.featuretask.lifecycle.core

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeResolvedPhaseAgent

public object FeatureTaskRuntimeAgentResolver {
  public fun resolve(
    phaseId: String,
    assignment: FeatureTaskRuntimeAgentAssignment,
    invokedAgentId: String,
  ): FeatureTaskRuntimeResolvedPhaseAgent {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeAgentResolver.resolve requires a non-blank phaseId." }
    require(invokedAgentId.isNotBlank()) {
      "FeatureTaskRuntimeAgentResolver.resolve requires a non-blank invokedAgentId; the invoking agent is the " +
        "documented default and must always be present (no hardcoded codex fallback)."
    }
    val resolvedInvoked =
      assignment.perPhaseAgentIds[phaseId]?.takeIf(String::isNotBlank)
        ?: invokedAgentId
    return FeatureTaskRuntimeResolvedPhaseAgent(
      phaseId = phaseId,
      invokedAgentId = resolvedInvoked,
      configuredAgentOverrideId = assignment.override?.takeIf(String::isNotBlank),
    )
  }
}
