package skillbill.config.model

import skillbill.install.model.SupportedAgent

public sealed interface FrozenWorkflowAgentOptionCheck {
  public data object Accepted : FrozenWorkflowAgentOptionCheck

  public data class Refused(
    public val phaseId: String,
    public val retainedAgentId: String,
    public val requestedAgentId: String,
    public val reason: Reason,
  ) : FrozenWorkflowAgentOptionCheck

  public enum class Reason {
    UNKNOWN_PROVIDER,
    CONFLICTING_OVERRIDE,
    CONFLICTING_PHASE,
  }
}

public fun checkFrozenWorkflowAgentOptions(
  assignments: ResolvedWorkflowAgentAssignments,
  explicitOverride: String?,
  explicitPhaseAgents: Map<String, String>,
): FrozenWorkflowAgentOptionCheck {
  val invalid = invalidExplicitProvider(assignments, explicitOverride, explicitPhaseAgents)
  if (invalid != null) return invalid
  if (assignments.policyMode != WorkflowAgentAssignmentPolicyMode.FROZEN) {
    return FrozenWorkflowAgentOptionCheck.Accepted
  }
  return conflictingOverride(assignments, explicitOverride)
    ?: conflictingPhase(assignments, explicitPhaseAgents)
    ?: FrozenWorkflowAgentOptionCheck.Accepted
}

private fun invalidExplicitProvider(
  assignments: ResolvedWorkflowAgentAssignments,
  override: String?,
  phases: Map<String, String>,
): FrozenWorkflowAgentOptionCheck.Refused? {
  val requested = override?.takeIf { SupportedAgent.fromWireOrNull(it) == null }
  val invalidPhase =
    phases.toSortedMap().entries.firstOrNull { (phase, provider) ->
      phase !in WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS || SupportedAgent.fromWireOrNull(provider) == null
    }
  val phase =
    if (requested != null) {
      assignments.phaseAssignments.keys.sorted().firstOrNull() ?: "unknown"
    } else {
      invalidPhase?.key ?: return null
    }
  return FrozenWorkflowAgentOptionCheck.Refused(
    phase,
    assignments.phaseAssignments[phase]?.agentId?.wireValue ?: "unknown",
    requested ?: requireNotNull(invalidPhase).value,
    FrozenWorkflowAgentOptionCheck.Reason.UNKNOWN_PROVIDER,
  )
}

private fun conflictingOverride(
  assignments: ResolvedWorkflowAgentAssignments,
  requested: String?,
): FrozenWorkflowAgentOptionCheck.Refused? {
  if (requested == null) return null
  val conflict =
    assignments.phaseAssignments.toSortedMap().entries.firstOrNull {
      requested != it.value.agentId.wireValue
    } ?: return null
  return FrozenWorkflowAgentOptionCheck.Refused(
    conflict.key,
    conflict.value.agentId.wireValue,
    requested,
    FrozenWorkflowAgentOptionCheck.Reason.CONFLICTING_OVERRIDE,
  )
}

private fun conflictingPhase(
  assignments: ResolvedWorkflowAgentAssignments,
  phases: Map<String, String>,
): FrozenWorkflowAgentOptionCheck.Refused? {
  val conflict =
    phases.toSortedMap().entries.firstOrNull { (phase, provider) ->
      assignments.phaseAssignments[phase]?.agentId?.wireValue != provider
    } ?: return null
  val retained = assignments.phaseAssignments[conflict.key]
  return FrozenWorkflowAgentOptionCheck.Refused(
    conflict.key,
    retained?.agentId?.wireValue ?: "unknown",
    conflict.value,
    if (retained == null) {
      FrozenWorkflowAgentOptionCheck.Reason.UNKNOWN_PROVIDER
    } else {
      FrozenWorkflowAgentOptionCheck.Reason.CONFLICTING_PHASE
    },
  )
}
