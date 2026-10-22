package skillbill.engine.diagnostics.block

import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.ports.blockincident.model.BlockedWorkflowWithoutIncident
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCorrelatedField
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFieldAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact

internal object BlockIncidentRecoveredObservations {
  fun build(
    candidate: BlockedWorkflowWithoutIncident,
    record: WorkflowStateSnapshot,
    identity: BlockIncidentIdentity?,
  ): BlockIncidentObservation {
    val recordedReason =
      record.artifacts[DecompositionManifestPayloadKeys.BLOCKED_REASON]
        ?.toString()?.takeIf(String::isNotBlank)
    val reason =
      recordedReason
        ?: "Workflow ${record.workflowId} remained blocked at ${record.currentStepId.ifBlank { "an unknown step" }}, " +
        "but its incident capture was interrupted before it recorded a reason."
    val parentWorkflowId = record.artifacts.goalContinuationArtifact()?.parentWorkflowId
    val shared =
      buildMap {
        if (parentWorkflowId == null) {
          put(BlockIncidentCorrelatedField.PARENT_WORKFLOW_ID, BlockIncidentFieldAvailability.NOT_APPLICABLE)
        }
        if (recordedReason == null) {
          put(BlockIncidentCorrelatedField.REASON, BlockIncidentFieldAvailability.NOT_CAPTURED)
        }
      }
    val currentAttempt = record.steps.firstOrNull { it.stepId == record.currentStepId }?.attemptCount
    val applicable =
      identity?.takeIf {
        it.stepId == record.currentStepId &&
          it.incidentToken == BlockIncidentAliases.token(it.alias) &&
          BlockIncidentIdentity.matchesBlockedAttempt(
            record.artifacts,
            it.stepId,
            it.attempt,
            currentAttempt,
          )
      }
    val observation =
      when {
        applicable == null -> recoveredBlock(candidate, record, reason)
        candidate.family == WorkflowFamily.TASK_RUNTIME && !applicable.isGoalWriterIdentity(record.workflowId) ->
          featureTaskBlock(record, applicable, reason)
        else -> goalBlock(candidate.family, record, applicable, reason)
      }
    return observation.copy(
      parentWorkflowId = parentWorkflowId,
      unavailableFields = observation.unavailableFields + shared,
    )
  }

  private fun BlockIncidentIdentity.isGoalWriterIdentity(workflowId: String): Boolean =
    alias == BlockIncidentAliases.goal(workflowId, stepId, generation, attempt, childIncidentToken)

  private fun goalBlock(
    family: WorkflowFamily,
    record: WorkflowStateSnapshot,
    identity: BlockIncidentIdentity,
    reason: String,
  ): BlockIncidentObservation {
    val phaseId = identity.stepId.takeIf { family == WorkflowFamily.TASK_RUNTIME }
    return BlockIncidentObservation(
      workflowId = record.workflowId,
      phaseId = phaseId,
      attempt = identity.attempt,
      reason = reason,
      alias = identity.alias,
      linkOccurrenceToken = identity.incidentToken,
      additionalAliases =
        listOfNotNull(
          identity.sourceAlias,
          identity.childIncidentToken?.let(BlockIncidentAliases::childCause),
        ),
      generation = identity.generation,
      origin = BlockIncidentOrigin.ACCEPTED_GOAL_BLOCK,
      failureCode = identity.failureCode,
      failureDisposition = identity.failureDisposition?.wireValue,
      exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
      unavailableFields =
        GOAL_NOT_APPLICABLE_FIELDS.associateWith { BlockIncidentFieldAvailability.NOT_APPLICABLE } +
          UNRECOVERABLE_FAILURE_FIELDS.associateWith { BlockIncidentFieldAvailability.NOT_CAPTURED } +
          mapOf(
            BlockIncidentCorrelatedField.ATTEMPT to
              if (phaseId == null || identity.attempt == null) {
                BlockIncidentFieldAvailability.NOT_APPLICABLE
              } else {
                BlockIncidentFieldAvailability.AVAILABLE
              },
            BlockIncidentCorrelatedField.PHASE_ID to
              if (phaseId == null) {
                BlockIncidentFieldAvailability.NOT_APPLICABLE
              } else {
                BlockIncidentFieldAvailability.AVAILABLE
              },
            BlockIncidentCorrelatedField.FAILURE_CODE to
              if (identity.failureCode == null) {
                BlockIncidentFieldAvailability.NOT_CAPTURED
              } else {
                BlockIncidentFieldAvailability.AVAILABLE
              },
          ),
    )
  }

  private fun featureTaskBlock(
    record: WorkflowStateSnapshot,
    identity: BlockIncidentIdentity,
    reason: String,
  ): BlockIncidentObservation =
    BlockIncidentObservation(
      workflowId = record.workflowId,
      phaseId = identity.stepId,
      attempt = identity.attempt,
      reason = reason,
      alias = identity.alias,
      linkOccurrenceToken = identity.incidentToken,
      additionalAliases = listOfNotNull(identity.sourceAlias),
      generation = identity.generation,
      origin = BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK,
      failureCode = identity.failureCode,
      failureDisposition = identity.failureDisposition?.wireValue,
      exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
      unavailableFields =
        phaseFields(identity.stepId) +
          mapOf(
            BlockIncidentCorrelatedField.THROWABLE_TYPE to BlockIncidentFieldAvailability.NOT_APPLICABLE,
            BlockIncidentCorrelatedField.EXIT_CODE to BlockIncidentFieldAvailability.NOT_APPLICABLE,
            BlockIncidentCorrelatedField.FAILURE_CODE to
              if (identity.failureCode == null) {
                BlockIncidentFieldAvailability.NOT_CAPTURED
              } else {
                BlockIncidentFieldAvailability.AVAILABLE
              },
          ),
    )

  private fun recoveredBlock(
    candidate: BlockedWorkflowWithoutIncident,
    record: WorkflowStateSnapshot,
    reason: String,
  ): BlockIncidentObservation {
    val stepId = record.currentStepId
    val alias = BlockIncidentAliases.recoveredBlock(record.workflowId, stepId, candidate.blockEnteredAt)
    if (candidate.family == WorkflowFamily.VERIFY || stepId.isBlank()) {
      return BlockIncidentObservation(
        workflowId = record.workflowId,
        reason = reason,
        alias = alias,
        generation = null,
        origin = BlockIncidentOrigin.ACCEPTED_GOAL_BLOCK,
        failureCode = null,
        exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
        unavailableFields =
          GOAL_NOT_APPLICABLE_FIELDS.associateWith { BlockIncidentFieldAvailability.NOT_APPLICABLE } +
            UNRECOVERABLE_FAILURE_FIELDS.associateWith { BlockIncidentFieldAvailability.NOT_CAPTURED } +
            (BlockIncidentCorrelatedField.ATTEMPT to BlockIncidentFieldAvailability.NOT_APPLICABLE) +
            (BlockIncidentCorrelatedField.PHASE_ID to BlockIncidentFieldAvailability.NOT_APPLICABLE) +
            (BlockIncidentCorrelatedField.FAILURE_CODE to BlockIncidentFieldAvailability.NOT_CAPTURED),
      )
    }
    return BlockIncidentObservation(
      workflowId = record.workflowId,
      phaseId = stepId,
      attempt = record.steps.firstOrNull { it.stepId == stepId }?.attemptCount?.takeIf { it > 0 },
      reason = reason,
      alias = alias,
      generation = null,
      origin = BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK,
      failureCode = null,
      exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
      unavailableFields =
        phaseFields(stepId) +
          UNRECOVERABLE_FAILURE_FIELDS.associateWith { BlockIncidentFieldAvailability.NOT_CAPTURED } +
          (BlockIncidentCorrelatedField.FAILURE_CODE to BlockIncidentFieldAvailability.NOT_CAPTURED),
    )
  }

  private fun phaseFields(phaseId: String): Map<BlockIncidentCorrelatedField, BlockIncidentFieldAvailability> {
    val pullRequestAvailability =
      if (phaseId in BlockIncidentFeatureTaskCorrelation.PULL_REQUEST_PHASES) {
        BlockIncidentFieldAvailability.NOT_CAPTURED
      } else {
        BlockIncidentFieldAvailability.NOT_APPLICABLE
      }
    return BlockIncidentFeatureTaskCorrelation.PULL_REQUEST_FIELDS.associateWith { pullRequestAvailability } +
      mapOf(
        BlockIncidentCorrelatedField.PHASE_RUN_ID to BlockIncidentFieldAvailability.NOT_APPLICABLE,
        BlockIncidentCorrelatedField.EXECUTION_ID to BlockIncidentFieldAvailability.NOT_APPLICABLE,
        BlockIncidentCorrelatedField.INVOCATION_ID to BlockIncidentFieldAvailability.NOT_CAPTURED,
      )
  }

  private val GOAL_NOT_APPLICABLE_FIELDS =
    listOf(
      BlockIncidentCorrelatedField.PHASE_RUN_ID, BlockIncidentCorrelatedField.INVOCATION_ID,
      BlockIncidentCorrelatedField.EXECUTION_ID,
      BlockIncidentCorrelatedField.GENERATION, BlockIncidentCorrelatedField.REPAIR_TURN,
      BlockIncidentCorrelatedField.OUTPUT_GENERATION, BlockIncidentCorrelatedField.MONITOR_SELECTOR,
      BlockIncidentCorrelatedField.PULL_REQUEST_ID, BlockIncidentCorrelatedField.PULL_REQUEST_URL,
      BlockIncidentCorrelatedField.HEAD_REPOSITORY, BlockIncidentCorrelatedField.HEAD_BRANCH,
      BlockIncidentCorrelatedField.WORKTREE_PATH,
    )
  private val UNRECOVERABLE_FAILURE_FIELDS =
    listOf(
      BlockIncidentCorrelatedField.THROWABLE_TYPE,
      BlockIncidentCorrelatedField.EXIT_CODE,
    )
}
