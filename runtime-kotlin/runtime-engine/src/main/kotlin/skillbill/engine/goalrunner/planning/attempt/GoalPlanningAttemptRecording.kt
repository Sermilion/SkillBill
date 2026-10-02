package skillbill.engine.goalrunner.planning.attempt

import skillbill.engine.goalrunner.planning.model.GoalPlanningAttemptRecord
import skillbill.engine.goalrunner.planning.model.GoalPlanningAttemptRecordArgs
import skillbill.engine.goalrunner.planning.model.GoalPlanningAttemptScope
import skillbill.engine.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.engine.goalrunner.planning.model.GoalPlanningRejectionRecord
import skillbill.engine.goalrunner.planning.model.GoalPlanningRejectionRecordArgs
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.remedies.GoalPlanningRejectionRecorder
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweepConstants
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome

internal fun recordEmptyProviderTurn(
  recorder: GoalPlanningRejectionRecorder,
  scope: GoalPlanningAttemptScope,
  production: GoalPlanningPhaseProduction.EmptyProviderTurn,
) = recordPlanningRejection(
  recorder,
  GoalPlanningRejectionRecordArgs(
    scope = scope,
    rule = GoalPlanningSweepConstants.EMPTY_PLANNING_HARVEST_RULE,
    reason = production.reason,
    agentId = production.evidence.agentId,
    rawEvidence = production.evidence.rawOutputPreview.orEmpty(),
  ),
)

internal fun recordPlanningRejection(
  recorder: GoalPlanningRejectionRecorder,
  args: GoalPlanningRejectionRecordArgs,
) {
  val scope = args.scope
  recorder.record(
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

internal fun recordPlanningAttempt(
  recorder: GoalPlanningAttemptRecorder,
  args: GoalPlanningAttemptRecordArgs,
) {
  val scope = args.scope
  recorder.record(
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
  recorder: GoalPlanningAttemptRecorder,
  scope: GoalPlanningAttemptScope,
) = recordPlanningAttempt(
  recorder,
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
