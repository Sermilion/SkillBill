package skillbill.engine.goalrunner.status

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.ports.diagnostics.RuntimeDiagnostics

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
