package skillbill.engine.goalrunner.model

internal enum class GoalRunnerObservabilityWorkerRole(val wireValue: String) {
  GOAL_RUNNER_SUPERVISOR("goal_runner_supervisor"),
}

internal enum class GoalRunnerObservabilityLivenessClass(val wireValue: String) {
  RESUME("resume"),
  SUBTASK_START("subtask_start"),
  PHASE_CHANGE("phase_change"),
  HEARTBEAT("heartbeat"),
  FILE_ACTIVITY("file_activity"),
  WORKER_OUTPUT_SUMMARY("worker_output_summary"),
  BLOCK("block"),
  FAILURE("failure"),
  COMPLETION("completion"),
  DEGRADATION("degradation"),
}
