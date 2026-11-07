package skillbill.engine.goalrunner.qa

import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.history.GoalFinalRepairContext
import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.model.quality.GoalQualityPreQaBuildState
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

internal fun requireResolvedNestedGateOutcome(
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  execution: GoalQualityExecution,
  definition: SkeletonDefinition,
  acceptedPlan: String,
  failure: Exception? = null,
) {
  val progress =
    outcomeStore.goalQualityPhaseProgress(
      execution.parentWorkflowId,
      execution.invocationId,
      definition.id,
      acceptedPlan,
    )
  val pending =
    listOfNotNull(
      progress.buildGateProgress?.let { it.pendingExternalOperation to it.pendingExternalCheckpoint },
      progress.validationGateProgress?.let { it.pendingExternalOperation to it.pendingExternalCheckpoint },
    ).firstOrNull { it.first != null }
  if (pending != null) {
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
      unresolvedGateOutcomeReason(pending.first, pending.second, failure),
      failure,
    )
  }
}

internal fun requireResolvedActiveGateOutcome(
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  execution: GoalQualityExecution?,
  failure: Exception,
) {
  if (execution == null) return
  if (execution.preQaBuild == GoalQualityPreQaBuildState.RUNNING) {
    val plans = requireNotNull(execution.executionPlans)
    requireResolvedNestedGateOutcome(
      outcomeStore,
      execution,
      SkeletonDefinition.GOAL_BUILD,
      plans.build,
      failure,
    )
    return
  }
  val definition =
    when (execution.status) {
      GoalQualityExecutionStatus.QA_PENDING -> SkeletonDefinition.GOAL_QA
      GoalQualityExecutionStatus.VALIDATION_RUNNING -> SkeletonDefinition.VALIDATION
      else -> return
    }
  val plans = requireNotNull(execution.executionPlans)
  requireResolvedNestedGateOutcome(
    outcomeStore,
    execution,
    definition,
    if (definition == SkeletonDefinition.GOAL_QA) plans.qa else plans.validation,
    failure,
  )
}

internal fun transitionQuality(
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  state: GoalRunnerManifestState,
  current: GoalQualityExecutionRecord,
  next: GoalQualityExecutionRecord,
): GoalQualityExecutionRecord {
  val execution = requireNotNull(current.execution)
  if (!outcomeStore.transitionGoalQualityExecution(
      state.parentWorkflowId,
      execution.invocationId,
      current,
      next,
    )
  ) {
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
      "Goal quality execution '${execution.invocationId}' changed before its transition was accepted.",
    )
  }
  return requireLatestQuality(outcomeStore, state, next)
}

internal fun requireLatestQuality(
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  state: GoalRunnerManifestState,
  previous: GoalQualityExecutionRecord,
): GoalQualityExecutionRecord {
  val latest =
    outcomeStore.goalQualityExecution(state.parentWorkflowId)
      ?: throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Goal quality execution disappeared before validation settlement.",
      )
  if (latest.execution?.invocationId != previous.execution?.invocationId) {
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
      "Goal quality execution changed before validation settlement.",
    )
  }
  return latest
}

internal fun requiredFinalRepairContext(
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  state: GoalRunnerManifestState,
): GoalFinalRepairContext =
  outcomeStore.goalFinalRepairContext(state.parentWorkflowId)
    ?: throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
      "Final repair context is missing for '${state.parentWorkflowId}'.",
    )
