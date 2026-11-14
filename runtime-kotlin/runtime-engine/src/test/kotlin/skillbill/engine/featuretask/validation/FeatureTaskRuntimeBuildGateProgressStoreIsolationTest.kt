package skillbill.engine.featuretask.validation

import skillbill.application.testHarnessClock
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.model.phase.ValidationFindingSetProjection
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.runner.InMemoryRuntimeWorkflowRepository
import skillbill.engine.featuretask.runner.RuntimeFakeDatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeProjectCheckBuildCommandAttempt
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeProjectCheckBuildCommandAttemptEntry
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
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
    recorder.openTestWorkflow(workflowId, "session-1")

    val validationProgress =
      FeatureTaskRuntimeValidationGateProgress(
        gateRunCount = 2,
        gateRuns =
          listOf(
            gateRunRecord(outcome = "failed"),
            gateRunRecord(outcome = "failed"),
          ),
      )
    val compilerFinding = ValidationGateFinding("compiler", "TypeMismatch", "expected String", "src/Main.kt:12")
    val buildProgress =
      FeatureTaskRuntimeValidationGateProgress(
        gateRunCount = 1,
        gateRuns = listOf(gateRunRecord(outcome = "passed")),
        completeFindings = ValidationFindingSetProjection(listOf(compilerFinding)).toHandoffMaps(),
        buildCommandAttempt =
          FeatureTaskRuntimeProjectCheckBuildCommandAttempt(
            "saved-build-list",
            listOf(
              FeatureTaskRuntimeProjectCheckBuildCommandAttemptEntry(
                argv = listOf("./gradlew", "compileKotlin"),
                workingDirectory = ".",
                command = "./gradlew compileKotlin",
                exitCode = 1,
                durationMs = 10,
                outcome = ValidationGateRunOutcome.FAILED,
                stdoutExcerpt = "bounded output",
              ),
            ),
          ),
      )

    recorder.persistValidationGateProgress(workflowId, validationProgress)
    recorder.persistBuildGateProgress(workflowId, buildProgress)

    val reloadedValidation = assertNotNull(recorder.loadValidationGateProgress(workflowId))
    val reloadedBuild = assertNotNull(recorder.loadBuildGateProgress(workflowId))
    assertEquals(2, reloadedValidation.gateRunCount)
    assertEquals("failed", reloadedValidation.gateRuns.last().outcome.wireValue)
    assertEquals(1, reloadedBuild.gateRunCount)
    assertEquals("passed", reloadedBuild.gateRuns.single().outcome.wireValue)
    assertEquals(buildProgress.completeFindings, reloadedBuild.completeFindings)
    assertEquals(buildProgress.buildCommandAttempt, reloadedBuild.buildCommandAttempt)
  }

  private fun gateRunRecord(outcome: String): FeatureTaskRuntimeValidationGateRunRecord =
    FeatureTaskRuntimeValidationGateRunRecord(
      durationMs = 1,
      outcome = outcome,
      cacheMode = "warm",
      executedWorkUnits = 1,
      command = "./gradlew check",
      exitCode = if (outcome == "passed") 0 else 1,
      repositoryCheckpoint = "checkpoint",
    )
}
