package skillbill.engine.diagnostics.block.recovery

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.remediation.FeatureTaskRuntimeStandaloneIncidentRecovery
import skillbill.engine.goalrunner.persist.WorkflowGoalRunnerBlockWrites
import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAction

@Inject
internal class BlockIncidentRecoveryActions(
  private val goalWrites: WorkflowGoalRunnerBlockWrites,
  private val standaloneRecovery: FeatureTaskRuntimeStandaloneIncidentRecovery,
) {
  fun apply(
    unit: UnitOfWork,
    action: BlockIncidentRecoveryAction,
    target: BlockIncidentRecoveryTarget,
    admittedAttempt: BlockIncidentRecoveryAttempt,
  ): BlockIncidentRecoveryActionResult =
    when (action) {
      BlockIncidentRecoveryAction.NONE -> BlockIncidentRecoveryActionResult.Refused
      BlockIncidentRecoveryAction.REOPEN_BLOCKED_PHASE,
      BlockIncidentRecoveryAction.RETRY_AFTER_PREREQUISITE,
      -> if (
        admittedAttempt.action != action || admittedAttempt.targetWorkflowId != target.launchWorkflowId ||
          admittedAttempt.targetInvocationId != target.invocationId ||
          admittedAttempt.status != BlockIncidentRecoveryAttemptStatus.APPLYING ||
          admittedAttempt.ownerToken.isNullOrBlank() || admittedAttempt.ownerGeneration == null ||
          unit.blockIncidentRecoveries.byIdempotencyKey(admittedAttempt.idempotencyKey) != admittedAttempt
      ) {
        BlockIncidentRecoveryActionResult.Refused
      } else {
        val applied =
          when (target.executionIdentity.routeScope) {
            FeatureTaskRouteScope.GOAL_CHILD ->
              goalWrites.reopenBlockedPhaseForOperatorResume(
                unit,
                target.phaseId,
                RECOVERY_REASON,
                target.executionIdentity,
                target.expectedExecutionPlan,
                admittedAttempt.attemptId,
                admittedAttempt,
                target.launchWorkflowId,
              )
            FeatureTaskRouteScope.STANDALONE ->
              standaloneRecovery.reopen(
                unit,
                target.mutationWorkflowId,
                requireNotNull(target.invocationId),
                target.phaseId,
                RECOVERY_REASON,
                admittedAttempt,
                target.executionIdentity,
                target.expectedExecutionPlan,
              )
          }
        if (applied) BlockIncidentRecoveryActionResult.Applied else BlockIncidentRecoveryActionResult.Refused
      }
    }

  private companion object {
    const val RECOVERY_REASON = "Apply the confirmed block incident recovery action."
  }
}

internal sealed interface BlockIncidentRecoveryActionResult {
  data object Applied : BlockIncidentRecoveryActionResult
  data object Refused : BlockIncidentRecoveryActionResult
}
