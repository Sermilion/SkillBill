package skillbill.engine.goalrunner.telemetry

import skillbill.engine.goalrunner.model.GoalRunnerObservabilityLivenessClass
import skillbill.engine.goalrunner.model.GoalRunnerObservabilityWorkerRole
import skillbill.engine.goalrunner.status.GoalRunnerStatusDurableReadTracker
import skillbill.ports.diagnostics.RuntimeDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalRunnerBestEffortEmissionSupportTest {
  @Test
  fun `best-effort diagnostics apply the shared message cap`() {
    val capped = GoalRunnerBestEffortEmission.boundedMessage("x".repeat(500))

    assertEquals(GoalRunnerBestEffortEmission.MAX_DIAGNOSTIC_MESSAGE_LENGTH, capped.length)
  }

  @Test
  fun `degraded attempt ledger read records seam expected and used values`() {
    val records = mutableListOf<String>()
    val diagnostics =
      object : RuntimeDiagnostics {
        override fun info(message: String) = Unit

        override fun warning(
          message: String,
          error: Throwable?,
        ) {
          records += message
        }

        override fun error(
          message: String,
          error: Throwable?,
        ) = Unit
      }
    val tracker = GoalRunnerStatusDurableReadTracker(diagnostics)
    tracker.recordDegradedRead(
      seam = "goal-status.attempt_ledger",
      expected = "ledger_summary",
      used = "degraded",
      error = IllegalStateException("ledger unavailable"),
    )
    assertEquals(true, tracker.degraded)
    assertEquals(
      "seam=goal-status.attempt_ledger value_expected=ledger_summary value_used=degraded",
      records.single(),
    )
  }

  @Test
  fun `observability wire vocabulary preserves supported worker role and liveness class bytes`() {
    val signal =
      GoalRunnerObservabilitySignal(
        workflowPhase = "implement",
        livenessClass = GoalRunnerObservabilityLivenessClass.PHASE_CHANGE,
        activitySummary = "Child workflow is at step implement.",
      )
    assertEquals(GoalRunnerObservabilityWorkerRole.GOAL_RUNNER_SUPERVISOR, signal.workerRole)
    assertEquals("goal_runner_supervisor", signal.workerRole.wireValue)
    assertEquals("phase_change", signal.livenessClass.wireValue)
  }
}
