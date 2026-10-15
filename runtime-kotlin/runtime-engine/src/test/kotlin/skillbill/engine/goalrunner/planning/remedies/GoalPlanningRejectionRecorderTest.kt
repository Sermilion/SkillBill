package skillbill.engine.goalrunner.planning.remedies

import skillbill.application.realPlanningProjectionValidator
import skillbill.application.testHarnessClock
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.goalrunner.planning.model.GoalPlanningRejectionRecord
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoalPlanningRejectionRecorderTest {
  @Test
  fun `a failed rejection write degrades with a bounded record instead of failing planning`() {
    val diagnostics = RecordingRejectionDiagnostics()
    val recorder = recorderOver(FailingRejectionDatabase(IllegalStateException("malformed parent row")), diagnostics)

    recorder.record(rejection())

    val warning = diagnostics.warnings.single()
    assertTrue(warning.contains("goal-planning.rejection_diagnostic"), warning)
    assertTrue(warning.contains("expected rejected_output_recorded"), warning)
    assertTrue(warning.contains("used rejection_not_recorded"), warning)
    assertTrue(warning.contains("wfl-parent"), warning)
  }

  @Test
  fun `an interrupt during the rejection write is rethrown with the interrupt flag set`() {
    val diagnostics = RecordingRejectionDiagnostics()
    val recorder = recorderOver(FailingRejectionDatabase(InterruptedException("write interrupted")), diagnostics)
    Thread.interrupted()

    assertFailsWith<InterruptedException> { recorder.record(rejection()) }
    assertTrue(Thread.interrupted())
    assertTrue(diagnostics.warnings.isEmpty())
  }

  private fun recorderOver(
    database: DatabaseSessionFactory,
    diagnostics: RuntimeDiagnostics,
  ) = DurableGoalPlanningRejectionRecorder(
    featureTaskRuntimePhaseRecorder(
      database,
      NoopRejectionSnapshotValidator,
      realPlanningProjectionValidator,
      realPlanningProjectionValidator,
      testHarnessClock,
      NoopRuntimeDiagnostics,
    ),
    diagnostics,
  )

  private fun rejection() =
    GoalPlanningRejectionRecord(
      parentWorkflowId = "wfl-parent",
      issueKey = "SKILL-378",
      phaseId = "plan",
      subtaskId = 1,
      attempt = 1,
      rule = "planning_projection_schema",
      reason = "The planning projection failed schema validation.",
      agentId = "codex",
      rawEvidence = "{}",
    )
}

private class RecordingRejectionDiagnostics : RuntimeDiagnostics {
  val warnings = mutableListOf<String>()

  override fun warning(
    message: String,
    error: Throwable?,
  ) {
    warnings += message
  }

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}

private object NoopRejectionSnapshotValidator : WorkflowSnapshotValidator {
  override fun validate(
    snapshot: WorkflowStateSnapshot,
    slug: String,
  ) = Unit
}

private class FailingRejectionDatabase(
  private val failure: Throwable,
) : DatabaseSessionFactory {
  override fun <T> owningTransaction(block: (UnitOfWork) -> T) =
    OwningTransactionResult.Refused(OwningTransactionResult.Refusal.ACTIVE_SESSION)

  override fun resolveDbPath(): Path = Path.of("/fake/goal-planning-rejection.db")

  override fun databaseExists(): Boolean = true

  override fun <T> read(block: (UnitOfWork) -> T): T = throw failure

  override fun <T> transaction(block: (UnitOfWork) -> T): T = throw failure

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = throw failure
}
