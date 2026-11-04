package skillbill.engine.goalrunner.persist.blockincident

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.identity.blockincident.GoalRunnerAcceptedBlockReferenceKeys
import skillbill.engine.diagnostics.block.BlockIncidentIdentity
import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot

internal object WorkflowGoalRunnerAcceptedBlockOutcomeBinding {
  fun patch(
    record: WorkflowStateSnapshot,
    identity: BlockIncidentIdentity?,
    acceptedCause: GoalRunnerAcceptedBlockReference?,
    replaceForeignReference: Boolean = false,
  ): Map<String, Any?> {
    val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME
    val value = family.value(record.artifacts) as? Map<*, *>
    if (identity == null || value == null || !value.isBlockedOutcomeFor(record, identity)) return emptyMap()
    val bound =
      GoalRunnerAcceptedBlockReference.fromArtifactMap(
        value[GoalRunnerAcceptedBlockReferenceKeys.OUTCOME_REFERENCE],
      )
    if (!replaceForeignReference && bound != null && bound.sourceWorkflowId != record.workflowId) return emptyMap()
    val currentChildCause = acceptedCause?.takeIf { it.matchesChildIdentity(identity) }
    val reference = currentChildCause ?: identity.acceptedReference(record.workflowId)
    if (bound == reference) return emptyMap()
    val updated =
      value.entries.associate { it.key.toString() to it.value } +
        (
          GoalRunnerAcceptedBlockReferenceKeys.OUTCOME_REFERENCE to
            reference.toArtifactMap()
        )
    return mapOf(family.entry(updated))
  }

  private fun Map<*, *>.isBlockedOutcomeFor(
    record: WorkflowStateSnapshot,
    identity: BlockIncidentIdentity,
  ): Boolean {
    val acceptedAttempt = record.steps.firstOrNull { it.stepId == identity.stepId }?.attemptCount?.takeIf { it > 0 }
    val blockedHere =
      this[SharedPayloadKeys.STATUS] == GoalRunnerTerminalStatus.BLOCKED.wireValue &&
        this[SharedPayloadKeys.WORKFLOW_ID] == record.workflowId
    return blockedHere &&
      this[DecompositionManifestPayloadKeys.LAST_RESUMABLE_STEP] == identity.stepId &&
      acceptedAttempt == identity.attempt
  }

  private fun GoalRunnerAcceptedBlockReference.matchesChildIdentity(identity: BlockIncidentIdentity): Boolean =
    sourceWorkflowId == identity.childWorkflowId && incidentToken == identity.childIncidentToken
}
