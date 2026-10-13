package skillbill.goalrunner.model

import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

data class GoalAttemptLedgerStatusEntry(
  val action: String?,
  val stopReason: String?,
  val diagnosticClass: String?,
  val currentStep: String?,
  val previousStep: String?,
  val subtaskId: Int?,
  val loopId: String?,
  val cumulativeLoopCount: Int?,
  val reAttemptCause: String?,
  val findingsInScope: Int?,
) {
  companion object {
    fun fromWire(wire: FeatureTaskRuntimeWorkflowArtifactMap): GoalAttemptLedgerStatusEntry =
      GoalAttemptLedgerStatusEntry(
        wire[GoalAttemptLedgerStatusKeys.ACTION]?.toString(),
        wire[GoalAttemptLedgerStatusKeys.STOP_REASON]?.toString(),
        wire[GoalAttemptLedgerStatusKeys.DIAGNOSTIC_CLASS]?.toString(),
        wire[GoalAttemptLedgerStatusKeys.CURRENT_STEP]?.toString(),
        wire[GoalAttemptLedgerStatusKeys.PREVIOUS_STEP]?.toString(),
        wire[GoalAttemptLedgerStatusKeys.SUBTASK_ID].asGoalRunnerIntOrNull(),
        wire[GoalAttemptLedgerStatusKeys.LOOP_ID]?.toString(),
        wire[GoalAttemptLedgerStatusKeys.CUMULATIVE_LOOP_COUNT].asGoalRunnerIntOrNull(),
        wire[GoalAttemptLedgerStatusKeys.RE_ATTEMPT_CAUSE]?.toString(),
        wire[GoalAttemptLedgerStatusKeys.FINDINGS_IN_SCOPE].asGoalRunnerIntOrNull(),
      )
  }
}
