package skillbill.engine.featuretask.validation

import skillbill.application.testHarnessClock
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.engine.InMemoryRuntimeWorkflowRepository
import skillbill.engine.RuntimeFakeDatabaseSessionFactory
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FeatureTaskRuntimeBuildGateProgressStoreIsolationTest {
  @Test
  fun `build gate progress store persists to build artifact without overwriting validation progress`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    val database = RuntimeFakeDatabaseSessionFactory(repository)
    val recorder =
      featureTaskRuntimePhaseRecorder(
        database,
        testWorkflowSnapshotValidator,
        AcceptingFeatureTaskRuntimeWireArtifactValidator,
        AcceptingFeatureTaskRuntimeWireArtifactValidator,
        testHarnessClock,
        NoopRuntimeDiagnostics,
      )
    val workflowId = "wf-build-gate-isolation"
    recorder.ensureWorkflowOpen(workflowId, "session-1")

    val validationProgress =
      FeatureTaskRuntimeValidationGateProgress(
        gateRunCount = 2,
        gateRuns =
          listOf(
            gateRunRecord(outcome = "failed"),
            gateRunRecord(outcome = "failed"),
          ),
      )
    val buildProgress =
      FeatureTaskRuntimeValidationGateProgress(
        gateRunCount = 1,
        gateRuns = listOf(gateRunRecord(outcome = "passed")),
      )

    recorder.persistValidationGateProgress(workflowId, validationProgress)
    recorder.persistBuildGateProgress(workflowId, buildProgress)

    val reloadedValidation = assertNotNull(recorder.loadValidationGateProgress(workflowId))
    val reloadedBuild = assertNotNull(recorder.loadBuildGateProgress(workflowId))
    assertEquals(2, reloadedValidation.gateRunCount)
    assertEquals("failed", reloadedValidation.gateRuns.last().outcome.wireValue)
    assertEquals(1, reloadedBuild.gateRunCount)
    assertEquals("passed", reloadedBuild.gateRuns.single().outcome.wireValue)
  }

  private fun gateRunRecord(outcome: String): FeatureTaskRuntimeValidationGateRunRecord =
    FeatureTaskRuntimeValidationGateRunRecord(
      durationMs = 1,
      outcome = outcome,
      cacheMode = "warm",
      executedWorkUnits = 1,
    )
}
