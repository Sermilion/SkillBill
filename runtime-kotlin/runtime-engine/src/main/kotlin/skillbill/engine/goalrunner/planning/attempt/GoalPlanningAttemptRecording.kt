package skillbill.engine.goalrunner.planning.attempt

import skillbill.engine.goalrunner.planning.model.GoalPlanningAttemptRecord
import skillbill.engine.goalrunner.planning.model.GoalPlanningAttemptRecordArgs
import skillbill.engine.goalrunner.planning.model.GoalPlanningAttemptScope
import skillbill.engine.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.engine.goalrunner.planning.model.GoalPlanningRejectionRecord
import skillbill.engine.goalrunner.planning.model.GoalPlanningRejectionRecordArgs
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.sweep.DefaultGoalPlanningSweep
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweepConstants
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome

internal fun recordEmptyProviderTurn(
  sweep: DefaultGoalPlanningSweep,
  scope: GoalPlanningAttemptScope,
  production: GoalPlanningPhaseProduction.EmptyProviderTurn,
) = recordPlanningRejection(
  sweep,
  GoalPlanningRejectionRecordArgs(
    scope = scope,
    rule = GoalPlanningSweepConstants.EMPTY_PLANNING_HARVEST_RULE,
    reason = production.reason,
    agentId = production.evidence.agentId,
    rawEvidence = production.evidence.rawOutputPreview.orEmpty(),
  ),
)

internal fun recordPlanningRejection(
  sweep: DefaultGoalPlanningSweep,
  args: GoalPlanningRejectionRecordArgs,
) {
  val scope = args.scope
  sweep.planningRejectionRecorder.record(
    GoalPlanningRejectionRecord(
      parentWorkflowId = scope.shared.parentWorkflowId,
      issueKey = scope.shared.issueKey,
      phaseId = diagnosticPhaseId(scope.phaseId, scope.subtask),
      subtaskId = scope.subtask?.id ?: 0,
      attempt = scope.attempt,
      rule = args.rule,
      reason = args.reason,
      agentId = args.agentId,
      rawEvidence = args.rawEvidence,
    ),
  )
}

fun diagnosticPhaseId(
  phaseId: String,
  subtask: DecompositionSubtask?,
): String = subtask?.let { "$phaseId:${it.id}" } ?: phaseId

internal fun backoffStop(
  sweep: DefaultGoalPlanningSweep,
  scope: GoalPlanningAttemptScope,
): GoalPlanningPhaseProduction.Stopped? =
  sweep.interruptibleWait(
    sweep.burstSchedule.emptyTurnBackoffAfterAttempt(scope.attempt),
    scope.shared,
    scope.subtask?.id ?: 0,
    scope.phaseId,
  )?.let { stoppedOutcome -> GoalPlanningPhaseProduction.Stopped(stoppedOutcome) }

internal fun recordPlanningAttempt(
  sweep: DefaultGoalPlanningSweep,
  args: GoalPlanningAttemptRecordArgs,
) {
  val scope = args.scope
  sweep.planningAttemptRecorder.record(
    GoalPlanningAttemptRecord(
      scope.shared.parentWorkflowId,
      scope.shared.issueKey,
      scope.phaseId,
      scope.subtask?.id ?: 0,
      scope.attempt,
      args.outcome,
      args.eventKind,
    ),
  )
}

internal fun recordPlanningAttemptStarted(
  sweep: DefaultGoalPlanningSweep,
  scope: GoalPlanningAttemptScope,
) = recordPlanningAttempt(
  sweep,
  GoalPlanningAttemptRecordArgs(
    scope = scope,
    outcome = GoalProgressOutcome.NONE,
    eventKind = GoalProgressEventKind.OPERATION_STARTED,
  ),
)

internal fun planningAttemptScope(
  shared: GoalPlanningSharedContext,
  phaseId: String,
  subtask: DecompositionSubtask?,
  attempt: Int,
) = GoalPlanningAttemptScope(shared, phaseId, subtask, attempt)
