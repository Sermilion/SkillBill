package skillbill.config.model

import skillbill.install.model.SupportedAgent
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePhaseIds

internal fun validateExecutionMatrixRouting(fields: Map<String, Any?>): ExecutionMatrixParse.Invalid? =
  tierAgentsInvalid(fields) ?: phaseAgentsInvalid(fields)

internal fun buildExecutionMatrixRouting(fields: Map<String, Any?>): ExecutionMatrixAgentRouting =
  ExecutionMatrixAgentRouting(
    tierAgents = buildTierAgents(fields[ExecutionMatrixFieldKeys.TIER_AGENTS]),
    phaseAgents = buildPhaseAgents(fields[ExecutionMatrixFieldKeys.PHASE_AGENTS]),
  )

private fun tierAgentsInvalid(fields: Map<String, Any?>): ExecutionMatrixParse.Invalid? {
  val key = ExecutionMatrixFieldKeys.TIER_AGENTS
  if (key !in fields) return null
  val entries =
    fields[key] as? Map<*, *> ?: return invalidExecutionMatrix(
      "$EXECUTION_MATRIX_KEY.$key",
      fields[key],
      "must be a mapping.",
    )
  return entries.entries.firstNotNullOfOrNull { (tier, provider) ->
    val path = "$EXECUTION_MATRIX_KEY.$key.$tier"
    when {
      tier !is String || ExecutionTier.fromId(tier) == null ->
        invalidExecutionMatrix(path, provider, "must be reasoning or implementation.")
      !isSupportedAgentId(provider) -> invalidExecutionMatrix(path, provider, "must be a supported install agent id.")
      else -> null
    }
  }
}

private fun phaseAgentsInvalid(fields: Map<String, Any?>): ExecutionMatrixParse.Invalid? {
  val key = ExecutionMatrixFieldKeys.PHASE_AGENTS
  if (key !in fields) return null
  val entries =
    fields[key] as? Map<*, *> ?: return invalidExecutionMatrix(
      "$EXECUTION_MATRIX_KEY.$key",
      fields[key],
      "must be a mapping.",
    )
  return entries.entries.firstNotNullOfOrNull { (phase, provider) ->
    val path = "$EXECUTION_MATRIX_KEY.$key.$phase"
    when {
      phase !is String || phase !in FeatureTaskRuntimePhaseIds.all ->
        invalidExecutionMatrix(path, provider, "is not a runtime phase.")
      !isSupportedAgentId(provider) -> invalidExecutionMatrix(path, provider, "must be a supported install agent id.")
      else -> null
    }
  }
}

private fun isSupportedAgentId(value: Any?): Boolean =
  value is String && value.isNotBlank() && SupportedAgent.entries.any { it.id == value }

private fun buildTierAgents(raw: Any?): Map<ExecutionTier, SupportedAgent> =
  (raw as? Map<*, *>)?.entries?.associate { (tier, provider) ->
    checkNotNull(ExecutionTier.fromId(tier as? String ?: "")) to supportedAgent(provider)
  } ?: emptyMap()

private fun buildPhaseAgents(raw: Any?): Map<String, SupportedAgent> =
  (raw as? Map<*, *>)?.entries?.associate { (phase, provider) ->
    checkNotNull(phase as? String) to supportedAgent(provider)
  } ?: emptyMap()

private fun supportedAgent(value: Any?): SupportedAgent =
  checkNotNull(SupportedAgent.entries.firstOrNull { it.id == value })
