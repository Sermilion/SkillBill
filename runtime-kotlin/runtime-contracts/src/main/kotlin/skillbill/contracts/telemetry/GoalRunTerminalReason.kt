package skillbill.contracts.telemetry

enum class GoalRunTerminalReason(val wireValue: String) {
  SUPERSEDED_BY_NEW_RUN("superseded_by_new_run"),
  GOAL_RUN_FAILED("goal_run_failed"),
}
