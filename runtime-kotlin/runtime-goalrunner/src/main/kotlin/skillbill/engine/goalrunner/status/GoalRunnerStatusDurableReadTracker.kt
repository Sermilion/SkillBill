package skillbill.engine.goalrunner.status

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.ports.diagnostics.RuntimeDiagnostics

internal object GoalRunnerSubtaskReadSource {
  const val PHASE_RESULTS = "subtask_phase_results"
  const val REVIEW_RAW_RESULTS = "subtask_review_raw_results"
  const val REVIEW_FINDINGS = "subtask_review_findings"
  const val REVIEW_LANES = "subtask_review_lanes"
}

internal class SubtaskReadDegradation(
  private val tracker: GoalRunnerStatusDurableReadTracker,
) {
  var source: String? = null
    private set

  fun record(
    source: String,
    expected: String,
    used: String,
    error: Throwable? = null,
  ) {
    tracker.recordDegradedRead("goal-status.$source", expected, used, error)
    if (this.source == null) this.source = source
  }
}

internal class GoalRunnerStatusDurableReadTracker(
  private val diagnostics: RuntimeDiagnostics,
) {
  var degraded: Boolean = false
    private set

  fun recordDegradedRead(
    seam: String,
    expected: String,
    used: String,
    error: Throwable? = null,
  ) {
    degraded = true
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=$seam value_expected=$expected value_used=$used",
      error,
    )
  }
}
