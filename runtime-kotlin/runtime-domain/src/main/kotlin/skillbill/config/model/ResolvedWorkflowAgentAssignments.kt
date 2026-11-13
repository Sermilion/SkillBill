package skillbill.config.model

import skillbill.install.model.SupportedAgent
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePhaseIds

enum class WorkflowAgentAssignmentPolicyMode(val id: String) {
  FROZEN("frozen"),
  LEGACY("legacy"),
}

enum class WorkflowAgentAssignmentSource(val id: String) {
  RUN_OVERRIDE("run_override"),
  CLI_PHASE("cli_phase"),
  CONFIGURED_PHASE("configured_phase"),
  CONFIGURED_TIER("configured_tier"),
  INVOKING_AGENT("invoking_agent"),
  LEGACY_RECORDED("legacy_recorded"),
  LEGACY_INVOKING("legacy_invoking"),
}

data class WorkflowAgentPhaseAssignment(
  val agentId: SupportedAgent,
  val tier: ExecutionTier,
  val source: WorkflowAgentAssignmentSource,
)

data class ResolvedWorkflowAgentAssignments(
  val admissionWorkflowId: String,
  val policyMode: WorkflowAgentAssignmentPolicyMode,
  val invokedAgentId: SupportedAgent,
  val configuredAgentOverrideId: SupportedAgent?,
  val phaseAssignments: Map<String, WorkflowAgentPhaseAssignment>,
) {
  init {
    require(admissionWorkflowId.isNotBlank())
    require(phaseAssignments.keys.all { it in WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS })
    if (policyMode == WorkflowAgentAssignmentPolicyMode.FROZEN) {
      require(phaseAssignments.keys == WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS.toSet())
      require(phaseAssignments.values.none { it.source.isLegacy() })
    } else {
      require(phaseAssignments.values.all { it.source.isLegacy() })
    }
  }
}

private fun WorkflowAgentAssignmentSource.isLegacy(): Boolean =
  this == WorkflowAgentAssignmentSource.LEGACY_RECORDED || this == WorkflowAgentAssignmentSource.LEGACY_INVOKING

val WORKFLOW_AGENT_ASSIGNMENT_PHASE_IDS: List<String> = FeatureTaskRuntimePhaseIds.all.toList()
