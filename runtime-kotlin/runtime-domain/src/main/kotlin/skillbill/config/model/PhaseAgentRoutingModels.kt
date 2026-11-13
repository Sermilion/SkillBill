package skillbill.config.model

enum class PhaseAgentRoutingSource {
  RUN_OVERRIDE,
  CLI_PHASE,
  CONFIGURED_PHASE,
  CONFIGURED_TIER,
  INVOKING_AGENT,
  LEGACY_RECORDED,
  LEGACY_INVOKING,
}

data class PhaseAgentRoutingSelection(
  val providerId: String,
  val source: PhaseAgentRoutingSource,
)

fun resolvePhaseAgent(
  phaseId: String,
  invokedAgentId: String,
  perPhaseAgentIds: Map<String, String>,
  runOverride: String?,
  matrix: ExecutionMatrix?,
): PhaseAgentRoutingSelection {
  runOverride?.takeIf(String::isNotBlank)?.let {
    return PhaseAgentRoutingSelection(it, PhaseAgentRoutingSource.RUN_OVERRIDE)
  }
  perPhaseAgentIds[phaseId]?.takeIf(String::isNotBlank)?.let {
    return PhaseAgentRoutingSelection(it, PhaseAgentRoutingSource.CLI_PHASE)
  }
  return configuredPhaseAgent(phaseId, matrix)
    ?: PhaseAgentRoutingSelection(invokedAgentId, PhaseAgentRoutingSource.INVOKING_AGENT)
}

private fun configuredPhaseAgent(
  phaseId: String,
  matrix: ExecutionMatrix?,
): PhaseAgentRoutingSelection? {
  matrix?.phaseAgents?.get(phaseId)?.let {
    return PhaseAgentRoutingSelection(it.id, PhaseAgentRoutingSource.CONFIGURED_PHASE)
  }
  if (matrix != null && matrix.tierAgents.isNotEmpty()) {
    matrix.tierAgents[matrix.tierOf(phaseId)]?.let {
      return PhaseAgentRoutingSelection(it.id, PhaseAgentRoutingSource.CONFIGURED_TIER)
    }
  }
  return null
}
