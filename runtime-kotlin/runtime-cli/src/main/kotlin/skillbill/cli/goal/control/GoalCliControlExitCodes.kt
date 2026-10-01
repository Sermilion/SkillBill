package skillbill.cli.goal.control

import skillbill.engine.goalrunner.model.GoalRunnerAcceptResult
import skillbill.engine.goalrunner.model.GoalRunnerOperatorDecisionResult
import skillbill.engine.goalrunner.model.GoalRunnerPauseResult
import skillbill.engine.goalrunner.model.GoalRunnerPauseStatus
import skillbill.engine.goalrunner.model.GoalRunnerRepairResult
import skillbill.engine.goalrunner.model.GoalRunnerRepairStatus
import skillbill.engine.goalrunner.model.GoalRunnerReplanResult
import skillbill.engine.goalrunner.model.GoalRunnerResetResult
import skillbill.engine.goalrunner.model.GoalRunnerResumeStatus
import skillbill.engine.goalrunner.model.GoalRunnerStopStatus
import skillbill.engine.goalrunner.model.GoalRunnerStopVerbResult

internal fun goalPauseExitCode(result: GoalRunnerPauseResult): Int =
  if (result.status == GoalRunnerPauseStatus.NOT_FOUND) 1 else 0

internal fun goalResumeExitCode(status: GoalRunnerResumeStatus): Int =
  if (status == GoalRunnerResumeStatus.NOT_FOUND) 1 else 0

internal fun goalStopExitCode(result: GoalRunnerStopVerbResult): Int =
  when (result.status) {
    GoalRunnerStopStatus.STOPPED,
    GoalRunnerStopStatus.ALREADY_STOPPED,
    GoalRunnerStopStatus.NO_LIVE_LEASE,
    -> 0
    GoalRunnerStopStatus.IDENTITY_MISMATCH,
    GoalRunnerStopStatus.NOT_FOUND,
    -> 1
  }

internal fun goalResetExitCode(result: GoalRunnerResetResult?): Int =
  if (result != null && result.refusalReason == null && result.recovery?.recoveryCommand == null) 0 else 1

internal fun goalReplanExitCode(result: GoalRunnerReplanResult?): Int = if (result == null) 1 else 0

internal fun goalRepairExitCode(result: GoalRunnerRepairResult): Int =
  when (result.status) {
    GoalRunnerRepairStatus.HEALTHY,
    GoalRunnerRepairStatus.REPAIRED,
    -> 0
    GoalRunnerRepairStatus.INSPECTED,
    GoalRunnerRepairStatus.OPERATOR_REQUIRED,
    -> 2
    GoalRunnerRepairStatus.NOT_WEDGED,
    GoalRunnerRepairStatus.LIVE_LEASE_REFUSED,
    GoalRunnerRepairStatus.NOT_FOUND,
    -> 1
  }

internal fun goalOperatorDecisionExitCode(result: GoalRunnerOperatorDecisionResult): Int =
  when (result) {
    is GoalRunnerOperatorDecisionResult.Recorded -> 0
    is GoalRunnerOperatorDecisionResult.Rejected -> 1
  }

internal fun goalAcceptExitCode(result: GoalRunnerAcceptResult): Int =
  when (result) {
    is GoalRunnerAcceptResult.Accepted -> 0
    is GoalRunnerAcceptResult.Rejected -> 1
  }
