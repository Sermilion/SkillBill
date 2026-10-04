package skillbill.cli.goal.core

import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason

internal const val GOAL_EXIT_COMPLETE: Int = 0
internal const val GOAL_EXIT_FAILED: Int = 1
internal const val GOAL_EXIT_PAUSED: Int = 2
internal const val GOAL_EXIT_BLOCKED: Int = 3

internal fun GoalRunnerRunReport.goalRunExitCode(): Int =
  when (this) {
    is GoalRunnerRunReport.Completed -> GOAL_EXIT_COMPLETE
    is GoalRunnerRunReport.Stopped -> stop.reason.goalExitCode()
  }

private fun GoalRunnerStopReason.goalExitCode(): Int =
  when (this) {
    GoalRunnerStopReason.PAUSED -> GOAL_EXIT_PAUSED
    GoalRunnerStopReason.FAILED,
    GoalRunnerStopReason.TIMEOUT,
    GoalRunnerStopReason.PULL_REQUEST_FAILED,
    -> GOAL_EXIT_FAILED
    GoalRunnerStopReason.BLOCKED,
    GoalRunnerStopReason.POLICY_BLOCKED,
    GoalRunnerStopReason.DEPENDENCIES_BLOCKED,
    GoalRunnerStopReason.INTERRUPTED,
    GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
    GoalRunnerStopReason.RECONCILED_RESUMABLE,
    GoalRunnerStopReason.AWAITING_OPERATOR_DECISION,
    -> GOAL_EXIT_BLOCKED
  }
