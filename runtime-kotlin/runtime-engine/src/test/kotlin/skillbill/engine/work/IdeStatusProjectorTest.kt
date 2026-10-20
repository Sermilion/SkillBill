package skillbill.engine.work

import skillbill.application.testHarnessClock
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.engine.featuretask.runloop.durable.FeatureTaskRuntimeRunInvariantsStore
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeStatusService
import skillbill.engine.featuretask.testing.featureTaskRuntimeWireArtifactValidatorForTest
import skillbill.engine.goalrunner.execution.core.goalRunnerDefaultPhaseRecorder
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class IdeStatusProjectorTest {
  @Test
  fun `capability discovery failure retains the QA pipeline and records a diagnostic`() {
    val warnings = mutableListOf<String>()
    val database = TrackingDatabase(work = emptyList(), workflows = IdeStatusWorkflowStates())
    val persistence =
      FeatureTaskRuntimeWorkflowPersistence(
        database,
        acceptingIdeStatusSnapshotValidator,
        featureTaskRuntimeWireArtifactValidatorForTest(),
      )
    val projector =
      IdeStatusProjector(
        workflowSnapshotValidator = acceptingIdeStatusSnapshotValidator,
        goalRunnerStatusService = testGoalRunnerStatusService(EmptyManifestStore, EmptyOutcomeStore),
        featureTaskRuntimeStatusService =
          FeatureTaskRuntimeStatusService(
            goalRunnerDefaultPhaseRecorder(),
            persistence,
            FeatureTaskRuntimeRunInvariantsStore(database, persistence, testHarnessClock),
            FeatureTaskRuntimeDecomposeTerminalRecorder(database, testHarnessClock),
          ),
        manifestStore = EmptyManifestStore,
        diagnostics =
          object : RuntimeDiagnostics by NoopRuntimeDiagnostics {
            override fun warning(
              message: String,
              error: Throwable?,
            ) {
              warnings += message
            }
          },
        qaCapabilities =
          QaCapabilityResolver(
            object : WorkflowGitOperations by RecordingWorkflowGitOperations() {
              override fun trackedPaths(repoRoot: Path) = WorkflowGitNameListResult.Failed("tracked paths unavailable")
            },
          ) { error("Failed Git discovery must not become pack absence.") },
      )

    assertEquals(GOAL_FINISH_PIPELINE, projector.goalFinishPipeline(Path.of("repo")))
    assertContains(warnings.single(), "tracked paths unavailable")
  }
}
