package skillbill.engine.goalrunner.persist.blockincident

import skillbill.engine.diagnostics.block.BlockIncidentAliases
import skillbill.engine.diagnostics.block.BlockIncidentObservation
import skillbill.engine.goalrunner.model.GoalRunnerBlockWrite
import skillbill.engine.goalrunner.model.GoalRunnerBlockWriteResult
import skillbill.error.core.SkillBillRuntimeException
import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCorrelatedField
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFieldAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact

internal object WorkflowGoalRunnerHistoricalBlockObservation {
  fun build(
    write: GoalRunnerBlockWrite,
    reference: GoalRunnerAcceptedBlockReference,
    sourceAlias: BlockIncidentAlias?,
  ): GoalRunnerBlockWriteResult {
    val goalAlias =
      BlockIncidentAliases.goal(
        reference.sourceWorkflowId,
        reference.blockedStepId,
        reference.executionGeneration,
        reference.attempt,
        reference.childIncidentToken,
      )
    val childToken = reference.childIncidentToken
    val observation =
      BlockIncidentObservation(
        workflowId = reference.sourceWorkflowId,
        parentWorkflowId = write.goalParentWorkflowId(),
        phaseId = reference.blockedStepId.takeIf { write.family == WorkflowFamily.TASK_RUNTIME },
        attempt = reference.attempt,
        reason = write.blockedReason,
        alias = goalAlias,
        linkOccurrenceToken = reference.incidentToken,
        linkedAcceptedBlockReference = reference,
        additionalAliases = listOfNotNull(sourceAlias, childToken?.let(BlockIncidentAliases::childCause)),
        generation = reference.executionGeneration,
        origin = write.blockOrigin(),
        failureCode = write.failureCode,
        failureDisposition = write.failureDisposition?.wireValue,
        exitCode = write.failureExitCode,
        throwableType = write.failureThrowableType,
        exitBehavior = write.blockExitBehavior(),
        unavailableFields = historicalUnavailableFields(write, reference),
      )
    return GoalRunnerBlockWriteResult(
      reference.blockedStepId,
      observation,
      reference.copy(
        aliasKind = goalAlias.kind.wireValue,
        aliasValue = goalAlias.value,
        sourceAliasKind = sourceAlias?.kind?.wireValue,
        sourceAliasValue = sourceAlias?.value,
      ),
    )
  }

  private fun historicalUnavailableFields(
    write: GoalRunnerBlockWrite,
    reference: GoalRunnerAcceptedBlockReference,
  ): Map<BlockIncidentCorrelatedField, BlockIncidentFieldAvailability> {
    val taskRuntime = write.family == WorkflowFamily.TASK_RUNTIME
    return mapOf(
      BlockIncidentCorrelatedField.PHASE_ID to availableWhen(taskRuntime),
      BlockIncidentCorrelatedField.PHASE_RUN_ID to BlockIncidentFieldAvailability.NOT_APPLICABLE,
      BlockIncidentCorrelatedField.ATTEMPT to availableWhen(taskRuntime && reference.attempt != null),
      BlockIncidentCorrelatedField.THROWABLE_TYPE to availableWhen(write.failureThrowableType != null),
      BlockIncidentCorrelatedField.EXIT_CODE to availableWhen(write.failureExitCode != null),
      BlockIncidentCorrelatedField.FAILURE_CODE to
        if (write.failureCode == null) {
          BlockIncidentFieldAvailability.NOT_CAPTURED
        } else {
          BlockIncidentFieldAvailability.AVAILABLE
        },
      BlockIncidentCorrelatedField.GENERATION to availableWhen(reference.executionGeneration != null),
    )
  }
}

internal fun GoalRunnerBlockWrite.blockOrigin(): BlockIncidentOrigin =
  if (failureExitCode == null && failureThrowableType == null) {
    BlockIncidentOrigin.ACCEPTED_GOAL_BLOCK
  } else {
    BlockIncidentOrigin.ENTRY_FAILURE
  }

internal fun GoalRunnerBlockWrite.blockExitBehavior(): BlockIncidentExitBehavior =
  when {
    failureThrowableType != null -> BlockIncidentExitBehavior.THROWN
    failureExitCode != null -> BlockIncidentExitBehavior.EXITED_WITH_CODE
    else -> BlockIncidentExitBehavior.RETURNED_FAILURE
  }

internal fun availableWhen(present: Boolean): BlockIncidentFieldAvailability =
  if (present) BlockIncidentFieldAvailability.AVAILABLE else BlockIncidentFieldAvailability.NOT_APPLICABLE

internal fun GoalRunnerBlockWrite.goalParentWorkflowId(): String? =
  try {
    record.artifacts.goalContinuationArtifact()?.parentWorkflowId
  } catch (ignored: SkillBillRuntimeException) {
    null
  }
