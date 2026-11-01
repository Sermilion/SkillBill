package skillbill.engine.goal

import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt

public const val GOAL_PLANNING_OPERATION_KIND: String = "goal_planning"

public fun executionLivenessBlocksIdleSafeRefresh(liveness: ExecutionLiveness): Boolean =
  when (liveness) {
    ExecutionLiveness.IDLE -> false
    ExecutionLiveness.LIVE,
    ExecutionLiveness.LIVE_EXPIRED,
    ExecutionLiveness.UNKNOWN,
    ExecutionLiveness.FOREIGN_HOST,
    ExecutionLiveness.PID_REUSED,
    -> true
  }

public data class OperatorBlockedPhaseReopenRequest(
  public val phaseId: String,
  public val reason: String,
  public val recoveryAttempt: BlockIncidentRecoveryAttempt? = null,
  public val targetWorkflowId: String? = null,
)
