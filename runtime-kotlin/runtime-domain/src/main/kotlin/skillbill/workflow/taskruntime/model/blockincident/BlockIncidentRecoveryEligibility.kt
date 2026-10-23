package skillbill.workflow.taskruntime.model.blockincident

import skillbill.workflow.model.WorkflowStatus

object BlockIncidentRecoveryEligibility {
  fun evaluate(
    incident: BlockIncident,
    diagnosis: BlockIncidentDiagnosisRecord?,
    facts: BlockIncidentRecoveryFacts,
  ): BlockIncidentRecoveryEligibilityResult {
    val reason = unavailableReason(incident, diagnosis, facts)
    if (reason != null) return BlockIncidentRecoveryEligibilityResult.Unavailable(reason)
    val unmet =
      diagnosis!!.prerequisites.filter { prerequisite ->
        prerequisite.check != BlockIncidentPrerequisiteCheck.USER_CONFIRMED ||
          prerequisite.kind !in facts.confirmedPrerequisites
      }.map { BlockIncidentRecoveryPrerequisiteInstruction(it.kind, it.kind.instruction()) }
    if (unmet.isNotEmpty()) return BlockIncidentRecoveryEligibilityResult.NeedsPrerequisites(unmet)
    val action = diagnosis.proposedRecoveryAction
    return BlockIncidentRecoveryEligibilityResult.Available(
      BlockIncidentRecoveryPreview(
        action.wireValue,
        if (action == BlockIncidentRecoveryAction.REOPEN_BLOCKED_PHASE) {
          "Reopen blocked phase"
        } else {
          "Retry after prerequisite"
        },
        diagnosis.recoveryEffects,
        facts.targetWorkflowId,
        facts.targetInvocationId,
        facts.phaseId,
        incident.occurrence.occurrenceToken,
        incident.state.revision,
      ),
    )
  }

  private fun unavailableReason(
    incident: BlockIncident,
    diagnosis: BlockIncidentDiagnosisRecord?,
    facts: BlockIncidentRecoveryFacts,
  ): BlockIncidentRecoveryUnavailableReason? {
    return when {
      diagnosis == null || diagnosisStatusUnsettled(incident.state.diagnosis) ->
        BlockIncidentRecoveryUnavailableReason.INVESTIGATION_NOT_SETTLED
      diagnosis.proposedRecoveryAction == BlockIncidentRecoveryAction.NONE ->
        BlockIncidentRecoveryUnavailableReason.NO_SUPPORTED_ACTION
      facts.workflowStatus != WorkflowStatus.BLOCKED -> BlockIncidentRecoveryUnavailableReason.NOT_BLOCKED
      facts.currentAttempt != incident.occurrence.attempt ||
        facts.currentGeneration != incident.occurrence.generation ->
        BlockIncidentRecoveryUnavailableReason.GENERATION_CHANGED
      !facts.attemptLimitRemaining -> BlockIncidentRecoveryUnavailableReason.ATTEMPT_LIMIT_REACHED
      facts.operatorDecisionForbidsRetry -> BlockIncidentRecoveryUnavailableReason.OPERATOR_DECISION
      facts.gateFailureCausedBlock -> BlockIncidentRecoveryUnavailableReason.QUALITY_GATE
      else -> null
    }
  }

  private fun diagnosisStatusUnsettled(status: BlockIncidentDiagnosis): Boolean =
    status == BlockIncidentDiagnosis.PENDING || status == BlockIncidentDiagnosis.INVESTIGATING

  private fun BlockIncidentPrerequisiteKind.instruction(): String =
    when (this) {
      BlockIncidentPrerequisiteKind.CREDENTIALS_CONFIGURED -> "Confirm credentials are configured."
      BlockIncidentPrerequisiteKind.CONFIGURATION_FIXED -> "Confirm the configuration has been fixed."
      BlockIncidentPrerequisiteKind.PROJECT_EDIT_COMPLETED -> "Confirm the required project edit is complete."
      BlockIncidentPrerequisiteKind.EXTERNAL_SERVICE_AVAILABLE -> "Confirm the external service is available."
    }
}

sealed interface BlockIncidentRecoveryEligibilityResult {
  data class Available(val preview: BlockIncidentRecoveryPreview) : BlockIncidentRecoveryEligibilityResult

  data class NeedsPrerequisites(
    val instructions: List<BlockIncidentRecoveryPrerequisiteInstruction>,
  ) : BlockIncidentRecoveryEligibilityResult

  data class Unavailable(val reason: BlockIncidentRecoveryUnavailableReason) : BlockIncidentRecoveryEligibilityResult
}

data class BlockIncidentRecoveryPrerequisiteInstruction(
  val kind: BlockIncidentPrerequisiteKind,
  val instruction: String,
)

data class BlockIncidentRecoveryPreview(
  val action: String,
  val label: String,
  val effects: String?,
  val targetWorkflowId: String?,
  val targetInvocationId: String?,
  val phaseId: String?,
  val incidentToken: String,
  val incidentRevision: Long,
)

enum class BlockIncidentRecoveryUnavailableReason {
  NO_SUPPORTED_ACTION,
  INVESTIGATION_NOT_SETTLED,
  NOT_BLOCKED,
  GENERATION_CHANGED,
  ATTEMPT_LIMIT_REACHED,
  OPERATOR_DECISION,
  QUALITY_GATE,
}
