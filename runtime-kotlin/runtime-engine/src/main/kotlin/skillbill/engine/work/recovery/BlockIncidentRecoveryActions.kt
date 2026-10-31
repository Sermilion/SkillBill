package skillbill.engine.work.recovery

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.remediation.FeatureTaskRuntimeStandaloneIncidentRecovery
import skillbill.engine.featuretask.lifecycle.remediation.StandaloneIncidentRecoveryRequest
import skillbill.engine.goalrunner.persist.OperatorBlockedPhaseReopenRequest
import skillbill.engine.goalrunner.persist.WorkflowGoalRunnerBlockWrites
import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAction
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus

@Inject
public class BlockIncidentRecoveryActions(
  private val goalWrites: WorkflowGoalRunnerBlockWrites,
  private val standaloneRecovery: FeatureTaskRuntimeStandaloneIncidentRecovery,
) {
  internal fun apply(
    unit: UnitOfWork,
    action: BlockIncidentRecoveryAction,
    target: BlockIncidentRecoveryTarget,
    admittedAttempt: BlockIncidentRecoveryAttempt,
  ): BlockIncidentRecoveryActionResult =
    when (action) {
      BlockIncidentRecoveryAction.NONE -> BlockIncidentRecoveryActionResult.Refused
      BlockIncidentRecoveryAction.REOPEN_BLOCKED_PHASE,
      BlockIncidentRecoveryAction.RETRY_AFTER_PREREQUISITE,
      ->
        if (
          !matchesAdmission(unit, action, target, admittedAttempt)
        ) {
          BlockIncidentRecoveryActionResult.Refused
        } else {
          val applied =
            when (target.executionIdentity.routeScope) {
              FeatureTaskRouteScope.GOAL_CHILD ->
                goalWrites.reopenBlockedPhaseForOperatorResume(
                  unit,
                  OperatorBlockedPhaseReopenRequest(
                    target.phaseId,
                    RECOVERY_REASON,
                    admittedAttempt,
                    target.launchWorkflowId,
                  ),
                  target.executionIdentity,
                  target.expectedExecutionPlan,
                )
              FeatureTaskRouteScope.STANDALONE ->
                standaloneRecovery.reopen(
                  unit,
                  StandaloneIncidentRecoveryRequest(
                    requireNotNull(target.invocationId),
                    target.phaseId,
                    RECOVERY_REASON,
                    admittedAttempt,
                    target.executionIdentity,
                    target.expectedExecutionPlan,
                  ),
                )
            }
          if (applied) BlockIncidentRecoveryActionResult.Applied else BlockIncidentRecoveryActionResult.Refused
        }
    }

  private fun matchesAdmission(
    unit: UnitOfWork,
    action: BlockIncidentRecoveryAction,
    target: BlockIncidentRecoveryTarget,
    attempt: BlockIncidentRecoveryAttempt,
  ): Boolean {
    val targetMatches =
      attempt.action == action && attempt.targetWorkflowId == target.launchWorkflowId &&
        attempt.targetInvocationId == target.invocationId
    val owned =
      attempt.status == BlockIncidentRecoveryAttemptStatus.APPLYING &&
        !attempt.ownerToken.isNullOrBlank() && attempt.ownerGeneration != null
    return targetMatches && owned && unit.blockIncidentRecoveries.byIdempotencyKey(attempt.idempotencyKey) == attempt
  }

  private companion object {
    const val RECOVERY_REASON = "Apply the confirmed block incident recovery action."
  }
}

internal sealed interface BlockIncidentRecoveryActionResult {
  data object Applied : BlockIncidentRecoveryActionResult

  data object Refused : BlockIncidentRecoveryActionResult
}
