package skillbill.engine.featuretask.model.core

import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.PhaseAgentRoutingSource

public data class FeatureTaskRuntimeAgentAssignment(
  public val perPhaseAgentIds: Map<String, String> = emptyMap(),
  public val override: String? = null,
  public val matrix: ExecutionMatrix? = null,
) {
  init {
    perPhaseAgentIds.forEach { (phaseId, agentId) ->
      require(phaseId.isNotBlank()) {
        "FeatureTaskRuntimeAgentAssignment.perPhaseAgentIds must not contain a blank phase id."
      }
      require(agentId.isNotBlank()) {
        "FeatureTaskRuntimeAgentAssignment.perPhaseAgentIds['$phaseId'] must not map to a blank agent id."
      }
    }
    override?.let { value ->
      require(value.isNotBlank()) {
        "FeatureTaskRuntimeAgentAssignment.override must not be blank when provided."
      }
    }
  }
}

public data class FeatureTaskRuntimeResolvedPhaseAgent(
  public val phaseId: String,
  public val invokedAgentId: String,
  public val configuredAgentOverrideId: String?,
  public val routingSource: PhaseAgentRoutingSource = PhaseAgentRoutingSource.INVOKING_AGENT,
  public val selectedProviderId: String? = null,
) {
  public val resolvedAgentId: String = selectedProviderId ?: configuredAgentOverrideId ?: invokedAgentId

  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeResolvedPhaseAgent.phaseId must be non-blank." }
    require(invokedAgentId.isNotBlank()) { "FeatureTaskRuntimeResolvedPhaseAgent.invokedAgentId must be non-blank." }
    configuredAgentOverrideId?.let { value ->
      require(value.isNotBlank()) {
        "FeatureTaskRuntimeResolvedPhaseAgent.configuredAgentOverrideId must not be blank when provided."
      }
    }
    selectedProviderId?.let { value ->
      require(value.isNotBlank()) {
        "FeatureTaskRuntimeResolvedPhaseAgent.selectedProviderId must not be blank when provided."
      }
    }
  }
}
