package skillbill.engine.work

import skillbill.application.TestDecompositionManifestStore
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
import skillbill.engine.goalrunner.plan.StandalonePlanReportBuilder
import skillbill.goalrunner.model.GoalRunnerAssessmentResult
import skillbill.goalrunner.model.GoalRunnerAssessmentState
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class IdeStatusProjectorTest {
  @Test
  fun `capability discovery failure retains the QA pipeline and records a diagnostic`() {
    val warnings = mutableListOf<String>()
    val projector = projector(warnings)

    assertEquals(GOAL_FINISH_PIPELINE, projector.goalFinishPipeline(Path.of("repo")))
    assertContains(warnings.single(), "tracked paths unavailable")
  }

  @Test
  fun `assessment steps lead the finish pipeline only when a record exists and add the fix step after a fix ran`() {
    val projector = projector(mutableListOf())

    fun assessment(
      state: GoalRunnerAssessmentState,
      fixRan: Boolean,
    ) = GoalRunnerAssessmentResult(state, 1, null, fixRan, emptyList(), emptyList())

    assertEquals(GOAL_FINISH_PIPELINE, projector.goalFinishPipeline(Path.of("repo"), null))
    assertEquals(
      listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT) + GOAL_FINISH_PIPELINE,
      projector.goalFinishPipeline(Path.of("repo"), assessment(GoalRunnerAssessmentState.SKIPPED, fixRan = false)),
    )
    assertEquals(
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT_FIX,
      ) + GOAL_FINISH_PIPELINE,
      projector.goalFinishPipeline(Path.of("repo"), assessment(GoalRunnerAssessmentState.COMPLETED, fixRan = true)),
    )
  }

  private fun projector(warnings: MutableList<String>): IdeStatusProjector {
    val database = TrackingDatabase(work = emptyList(), workflows = IdeStatusWorkflowStates())
    val persistence =
      FeatureTaskRuntimeWorkflowPersistence(
        database,
        acceptingIdeStatusSnapshotValidator,
        featureTaskRuntimeWireArtifactValidatorForTest(),
      )
    return IdeStatusProjector(
      workflowSnapshotValidator = acceptingIdeStatusSnapshotValidator,
      goalRunnerStatusService = testGoalRunnerStatusService(EmptyManifestStore, EmptyOutcomeStore),
      featureTaskRuntimeStatusService =
        FeatureTaskRuntimeStatusService(
          goalRunnerDefaultPhaseRecorder(),
          persistence,
          FeatureTaskRuntimeRunInvariantsStore(database, persistence),
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
      planReports =
        StandalonePlanReportBuilder(
          EmptyManifestStore,
          TestDecompositionManifestStore,
          FixedProjectStorePort(ideStatusProjectStoreRoot),
          NoopRuntimeDiagnostics,
        ),
    )
  }
}
